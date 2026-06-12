package com.kaivor.agent.skills.builtin

internal object LocationRequestNormalizer {

    private val currentLocationPhrases = setOf(
        "current location",
        "use current location",
        "my location",
        "use my location",
        "your location",
        "present location",
        "live location",
        "current pickup",
        "right now location",
        "right now pickup",
        "gps",
        "using gps",
        "use gps",
        "from gps",
        "pickup from gps",
        "pickup using gps",
        "here",
    )

    fun normalizePickup(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return ""
        return if (wantsCurrentLocation(trimmed)) "" else trimmed
    }

    fun wantsCurrentLocation(raw: String?): Boolean {
        val normalized = raw
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("""[^a-z0-9]+"""), " ")
            ?.trim()
            .orEmpty()

        if (normalized.isBlank()) return false
        if (normalized in currentLocationPhrases) return true

        return (normalized.contains("current") && normalized.contains("location")) ||
            (normalized.contains("my") && normalized.contains("location")) ||
            (normalized.contains("use") && normalized.contains("location")) ||
            (normalized.contains("gps") && normalized.contains("pickup")) ||
            (normalized.contains("gps") && normalized.contains("location")) ||
            (normalized.contains("right now") && normalized.contains("location")) ||
            normalized == "where i am" ||
            normalized == "where i am now" ||
            normalized == "where i am right now"
    }
}
