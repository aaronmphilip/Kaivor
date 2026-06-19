package com.kaivor.agent.skills.builtin

import com.kaivor.agent.skills.DeliveryMode
import com.kaivor.agent.skills.Skill
import com.kaivor.agent.skills.SkillContext
import com.kaivor.agent.skills.SkillManifest
import com.kaivor.agent.skills.SkillResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

class CurrencySkill : Skill {

    override val manifest = SkillManifest(
        id = "currency",
        name = "Currency Converter",
        version = "1.0.0",
        description = "Convert between currencies and get live exchange rates - works instantly via API without opening any app. Supports USD, EUR, GBP, INR, JPY, AED, SGD, AUD, CAD, and 30+ more.",
        author = "kaivor-team",
        trusted = true,
        permissions = emptySet(),
        exampleParamsHint = """{"amount":100,"from":"USD","to":"INR"} | {"amount":50,"from":"AED","to":"INR"} | {"from":"EUR","to":"INR"}""",
    )

    private val client = SharedHttpClient.instance

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val amount = (params["amount"] as? Number)?.toDouble() ?: 1.0
        val from = (params["from"] as? String
            ?: params["currency"] as? String
            ?: params["source"] as? String)
            ?.uppercase()?.trim()
            ?: return SkillResult.Failure("Which currency are you converting FROM? (e.g., USD, AED, EUR)")

        val to = (params["to"] as? String
            ?: params["target"] as? String)
            ?.uppercase()?.trim()
            ?: "INR"

        if (from == to) {
            val amtStr = formatAmount(amount)
            return SkillResult.Success("$amtStr $from = $amtStr $to (same currency)")
        }

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.frankfurter.app/latest?from=$from&to=$to")
                    .header("User-Agent", "Kaivor-Agent/1.0")
                    .build()

                val body = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val hint = when (response.code) {
                            404 -> "Currency code \"$from\" not found. Use standard codes: USD, EUR, GBP, INR, JPY, AED, SGD."
                            else -> "Currency service unavailable (${response.code})."
                        }
                        return@withContext SkillResult.Failure(hint)
                    }
                    response.body?.string()
                        ?: return@withContext SkillResult.Failure("Empty response from currency service.")
                }

                val json = JSONObject(body)
                val rates = json.getJSONObject("rates")

                if (!rates.has(to)) {
                    return@withContext SkillResult.Failure(
                        "Currency \"$to\" not found. Common codes: USD, EUR, GBP, INR, JPY, AED, SGD, CAD, AUD, CHF."
                    )
                }

                val rate = rates.getDouble(to)
                val converted = amount * rate
                val date = json.getString("date")

                val amountStr = formatAmount(amount)
                val convertedStr = formatAmount(converted)
                val rateStr = if (rate >= 1) "%.4f".format(rate) else "%.6f".format(rate)

                SkillResult.Success(
                    buildString {
                        appendLine("*$amountStr $from = $convertedStr $to*")
                        appendLine()
                        appendLine("Rate: 1 $from = $rateStr $to")
                        append("_(Live rate as of $date)_")
                    },
                    delivery = DeliveryMode.LONG_TEXT,
                )
            } catch (e: Exception) {
                SkillResult.Failure("Couldn't convert currency: ${e.message}")
            }
        }
    }

    private fun formatAmount(value: Double): String {
        return if (value == value.toLong().toDouble() && value < 1_000_000) {
            value.toLong().toString()
        } else {
            "%.2f".format(value)
        }
    }
}
