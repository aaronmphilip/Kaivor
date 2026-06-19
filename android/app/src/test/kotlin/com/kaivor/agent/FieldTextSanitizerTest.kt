package com.kaivor.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldTextSanitizerTest {

    @Test
    fun `destination field strips where-to placeholder and trailing pickup clause`() {
        val target = editableField(hint = "Where to?")

        val sanitized = FieldTextSanitizer.sanitize(
            text = "Where to Bangalore Airport from Indiranagar",
            target = target,
            fieldRole = "destination-input",
        )

        assertEquals("Bangalore Airport", sanitized)
    }

    @Test
    fun `destination field extracts value from from-to phrase`() {
        val target = editableField(hint = "Search destination")

        val sanitized = FieldTextSanitizer.sanitize(
            text = "from Indiranagar to Bangalore Airport",
            target = target,
            fieldRole = "destination-input",
        )

        assertEquals("Bangalore Airport", sanitized)
    }

    @Test
    fun `pickup field extracts only pickup from mixed destination phrase`() {
        val target = editableField(hint = "Enter pickup location")

        val sanitized = FieldTextSanitizer.sanitize(
            text = "Where to Bangalore Airport from Indiranagar",
            target = target,
            fieldRole = "pickup-input",
        )

        assertEquals("Indiranagar", sanitized)
    }

    @Test
    fun `pickup field blocks current location placeholder phrases`() {
        val target = editableField(hint = "Enter pickup location")

        val sanitized = FieldTextSanitizer.sanitize(
            text = "use my location",
            target = target,
            fieldRole = "pickup-input",
        )

        assertEquals("", sanitized)
    }

    @Test
    fun `placeholder-only destination text is blocked`() {
        val target = editableField(hint = "Where to?")

        val sanitized = FieldTextSanitizer.sanitize(
            text = "Where to?",
            target = target,
            fieldRole = "destination-input",
        )

        assertEquals("", sanitized)
    }

    @Test
    fun `title field strips add-title placeholder`() {
        val target = editableField(hint = "Add title")

        val sanitized = FieldTextSanitizer.sanitize(
            text = "Add title Team Sync",
            target = target,
            fieldRole = "title-input",
        )

        assertEquals("Team Sync", sanitized)
    }

    @Test
    fun `plain values pass through unchanged`() {
        val target = editableField(hint = "Where to?")

        val sanitized = FieldTextSanitizer.sanitize(
            text = "Kempegowda International Airport Terminal 2",
            target = target,
            fieldRole = "destination-input",
        )

        assertEquals("Kempegowda International Airport Terminal 2", sanitized)
    }

    private fun editableField(
        text: String = "",
        hint: String = "",
        contentDescription: String = "",
    ) = ScreenElement(
        text = text,
        type = "input",
        isClickable = true,
        isEditable = true,
        centerX = 0,
        centerY = 0,
        width = 500,
        height = 80,
        viewId = "",
        packageName = "",
        className = "EditText",
        hint = hint,
        contentDescription = contentDescription,
        isCheckable = false,
        isScrollable = false,
        isSelected = false,
    )
}
