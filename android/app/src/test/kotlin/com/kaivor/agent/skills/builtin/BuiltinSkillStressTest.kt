package com.kaivor.agent.skills.builtin

import android.content.Context
import com.google.gson.JsonParser
import com.kaivor.agent.skills.Permission
import com.kaivor.agent.skills.Skill
import com.kaivor.agent.skills.SkillContext
import com.kaivor.agent.skills.SkillManifest
import com.kaivor.agent.skills.SkillResult
import com.kaivor.agent.skills.SandboxedRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BuiltinSkillStressTest {

    private val appContext: Context = RuntimeEnvironment.getApplication()

    private val skills = BuiltinSkills.all(imageApiKey = "test-key")

    /** Skills that touch the runner immediately even with empty params — JVM has no Android context. */
    private val runnerDependentIds = setOf("screen", "finder")

    private val paymentSkillIds = setOf(
        "swiggy", "zomato", "zepto", "blinkit", "phonepe", "gpay", "paytm", "cred",
        "ola", "uber", "rapido", "flipkart", "amazon", "bill_splitter", "ride_concierge",
    )

    @Test
    fun `all builtin skills are registered with unique ids`() {
        assertTrue("Expected at least 40 built-in skills", skills.size >= 40)
        val ids = skills.map { it.manifest.id }
        assertEquals("Skill ids must be unique", ids.distinct().size, ids.size)
    }

    @Test
    fun `manifests satisfy production invariants`() {
        skills.forEach { skill ->
            val m = skill.manifest
            assertTrue("${m.id}: id must be lowercase snake_case", m.id.matches(Regex("[a-z][a-z0-9_]*")))
            assertFalse("${m.id}: name blank", m.name.isBlank())
            assertFalse("${m.id}: version blank", m.version.isBlank())
            assertFalse("${m.id}: description blank", m.description.isBlank())
            assertFalse("${m.id}: author blank", m.author.isBlank())
            assertTrue("${m.id}: trusted builtin", m.trusted)

            if (Permission.OPEN_APP in m.permissions && m.allowedPackages.isNotEmpty()) {
                m.allowedPackages.forEach { pkg ->
                    assertTrue(
                        "${m.id}: invalid package '$pkg'",
                        pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")),
                    )
                }
            }

            if (Permission.PAYMENT in m.permissions) {
                assertTrue("${m.id}: payment skills should declare OPEN_APP", Permission.OPEN_APP in m.permissions)
            }

            assertTrue("${m.id}: exampleParamsHint present", m.exampleParamsHint.isNotBlank())
            assertTrue(
                "${m.id}: exampleParamsHint should contain a JSON object",
                m.exampleParamsHint.contains("{"),
            )
        }
    }

    @Test
    fun `empty params never crash skills without runner dependency`() = runBlocking {
        val context = testContext()

        skills
            .filter { it.manifest.id !in runnerDependentIds }
            .forEach { skill ->
                val result = try {
                    skill.execute(context, emptyMap())
                } catch (e: Exception) {
                    fail("${skill.manifest.id} threw on empty params: ${e.message}")
                }

                assertTrue(
                    "${skill.manifest.id} should return Failure or NeedsConfirmation on empty params, got $result",
                    result is SkillResult.Failure || result is SkillResult.NeedsConfirmation,
                )
            }
    }

    @Test
    fun `payment skills require confirmation before executing transfers`() = runBlocking {
        val context = testContext()

        val confirmParams = mapOf<String, Any>(
            "action" to "pay",
            "amount" to 100,
            "contact" to "Test User",
            "query" to "biryani",
            "destination" to "Airport",
        )

        skills
            .filter { it.manifest.id in paymentSkillIds }
            .forEach { skill ->
                val result = skill.execute(context, confirmParams)
                assertTrue(
                    "${skill.manifest.id} with payment params should fail fast (no agent) or ask confirmation",
                    result is SkillResult.Failure || result is SkillResult.NeedsConfirmation,
                )
            }
    }

    @Test
    fun `api skills return live data for valid params`() = runBlocking {
        val context = testContext()

        val weather = skills.first { it.manifest.id == "weather" }
            .execute(context, mapOf("city" to "Delhi", "days" to 1))
        assertTrue("Weather skill failed: $weather", weather is SkillResult.Success)
        val weatherText = (weather as SkillResult.Success).message
        assertTrue(
            "Weather response should include temperature data: $weatherText",
            weatherText.contains("Temperature", ignoreCase = true) || weatherText.contains("deg C", ignoreCase = true),
        )

        val currency = skills.first { it.manifest.id == "currency" }
            .execute(context, mapOf("amount" to 1, "from" to "USD", "to" to "INR"))
        assertTrue("Currency skill failed: $currency", currency is SkillResult.Success)
        assertTrue((currency as SkillResult.Success).message.contains("INR"))

        val qr = skills.first { it.manifest.id == "qr_code" }
            .execute(context, mapOf("content" to "https://kaivor.com"))
        assertTrue("QR skill failed: $qr", qr is SkillResult.Media)
        assertTrue((qr as SkillResult.Media).bytes.isNotEmpty())
    }

    @Test
    fun `document creator skills validate required fields`() = runBlocking {
        val context = testContext()

        val pdf = skills.first { it.manifest.id == "pdf_creator" }
            .execute(context, mapOf("title" to "Test"))
        assertTrue(pdf is SkillResult.Failure)
        assertTrue((pdf as SkillResult.Failure).reason.contains("content", ignoreCase = true))

        val pptx = skills.first { it.manifest.id == "pptx_creator" }
            .execute(context, emptyMap())
        assertTrue(pptx is SkillResult.Failure)
        assertTrue((pptx as SkillResult.Failure).reason.contains("title", ignoreCase = true))
    }

    @Test
    fun `official json skill ids match kotlin builtins`() {
        val kotlinIds = skills.map { it.manifest.id }.toSet()
        val officialIds = listOf("swiggy", "zomato", "zepto", "phonepe", "maps", "flipkart")
        officialIds.forEach { id ->
            assertTrue("Official skill '$id' should have a Kotlin builtin", id in kotlinIds)
        }
    }

    @Test
    fun `example params hints contain parseable json samples`() {
        skills.forEach { skill ->
            val samples = skill.manifest.exampleParamsHint
                .split("|")
                .map { it.trim() }
                .filter { it.startsWith("{") }

            assertTrue("${skill.manifest.id} needs at least one JSON example", samples.isNotEmpty())
            samples.forEach { sample ->
                try {
                    JsonParser.parseString(sample)
                } catch (e: Exception) {
                    fail("${skill.manifest.id} has invalid example JSON: $sample (${e.message})")
                }
            }
        }
    }

    private fun testContext(manifest: SkillManifest = SkillManifest(
        id = "test",
        name = "Test",
        version = "1.0.0",
        description = "Test harness",
        author = "test",
        permissions = emptySet(),
    )) = SkillContext(
        runner = SandboxedRunner(manifest, null, appContext),
        chatId = 1L,
        userId = "stress-test",
        agent = null,
    )
}