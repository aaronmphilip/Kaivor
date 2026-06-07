package com.bharatdroid.agent.skills.builtin

import org.junit.Assert.assertEquals
import org.junit.Test

class WebFormInfoTest {

    @Test
    fun `extracts fields from json string object`() {
        val fields = WebFormInfo.extractFields(
            mapOf(
                "fields" to """{"name":"Rahul","email":"rahul@example.com"}""",
                "url" to "https://example.com",
            )
        )

        assertEquals("Rahul", fields["name"])
        assertEquals("rahul@example.com", fields["email"])
        assertEquals(2, fields.size)
    }

    @Test
    fun `extracts direct non-reserved params as fields`() {
        val fields = WebFormInfo.extractFields(
            mapOf(
                "action" to "fill_form",
                "phone" to "9876543210",
                "city" to "Bangalore",
            )
        )

        assertEquals("9876543210", fields["phone"])
        assertEquals("Bangalore", fields["city"])
    }

    @Test
    fun `matches required field names loosely`() {
        val missing = WebFormInfo.missingRequired(
            fields = mapOf("Email Address" to "rahul@example.com"),
            required = listOf("email_address", "phone"),
        )

        assertEquals(listOf("phone"), missing)
    }

    @Test
    fun `extracts required fields from json array string`() {
        val required = WebFormInfo.extractRequiredFields(
            mapOf("required" to """["name","email","phone"]""")
        )

        assertEquals(listOf("name", "email", "phone"), required)
    }

    @Test
    fun `parses missing info marker from agent result`() {
        val missing = WebFormInfo.parseMissingInfo("Could not complete: MISSING_INFO: GST number")

        assertEquals("GST number", missing)
    }
}
