package com.kaivor.agent

internal object FieldTextSanitizer {

    private const val pickupMarkersPattern =
        """(?:from|pickup(?:\s+location)?|pick\s*up|origin|current\s+location)"""

    private const val destinationMarkersPattern =
        """(?:where\s+to|destination|drop(?:\s*off)?|drop)"""

    private const val currentLocationPattern =
        """(?:current\s+location|use\s+current\s+location|my\s+location|use\s+my\s+location|your\s+location|gps|using\s+gps|use\s+gps|right\s+now\s+location|live\s+location|present\s+location|here)"""

    fun sanitize(text: String, target: ScreenElement?, fieldRole: String): String {
        val raw = cleanupValue(text)
        if (raw.isBlank() || target == null) return raw

        val knownLabels = buildKnownLabels(target)
        var sanitized = stripInstructionPrefix(raw)
        knownLabels.forEach { label ->
            sanitized = stripLeadingFieldLabel(sanitized, label)
        }

        sanitized = when (fieldRole) {
            "pickup-input" -> sanitizePickupValue(sanitized)
            "destination-input" -> sanitizeDestinationValue(sanitized)
            "title-input" -> sanitizeTitleValue(sanitized)
            else -> sanitized
        }

        sanitized = cleanupValue(sanitized)
        if (sanitized.isBlank()) return ""
        if (isPlaceholderOnly(sanitized, knownLabels, fieldRole)) return ""
        return sanitized
    }

    private fun sanitizePickupValue(text: String): String {
        val normalized = cleanupValue(text)
        val candidate = firstNonBlank(
            capture(normalized, """(?i)\b$pickupMarkersPattern\b\s*(?:[:=;\-]\s*|\[\s*)?(.+)$"""),
            capture(normalized, """(?i)\b$destinationMarkersPattern\b.*?\bfrom\b\s*(?:[:=;\-]\s*|\[\s*)?(.+)$"""),
            capture(normalized, """(?i)\bfrom\b\s*(?:[:=;\-]\s*|\[\s*)?(.+)$"""),
        ) ?: normalized

        val stripped = stripTrailingClause(
            text = candidate,
            trailingPattern = """(?:\bto\b|\b$destinationMarkersPattern\b)""",
        )
        return if (Regex("""(?i)^$currentLocationPattern$""").matches(stripped)) "" else stripped
    }

    private fun sanitizeDestinationValue(text: String): String {
        val normalized = cleanupValue(text)
        val candidate = firstNonBlank(
            capture(normalized, """(?i)\b$destinationMarkersPattern\b\s*(?:[:=;\-]\s*|\[\s*)?(.+)$"""),
            capture(normalized, """(?i)\bfrom\b.*?\bto\b\s*(?:[:=;\-]\s*|\[\s*)?(.+)$"""),
            capture(normalized, """(?i)\bto\b\s*(?:[:=;\-]\s*|\[\s*)?(.+)$"""),
        ) ?: normalized

        return stripTrailingClause(
            text = candidate,
            trailingPattern = """\b$pickupMarkersPattern\b""",
        )
    }

    private fun sanitizeTitleValue(text: String): String {
        val normalized = cleanupValue(text)
        return capture(
            normalized,
            """(?i)\b(?:add\s+title|event\s+title|title|subject)\b\s*(?:[:=;\-]\s*|\[\s*)?(.+)$""",
        )?.let(::cleanupValue) ?: normalized
    }

    private fun stripTrailingClause(text: String, trailingPattern: String): String {
        return cleanupValue(
            text.replace(
                Regex("""(?i)\s*(?:,|;|:|-)?\s*$trailingPattern.*$"""),
                "",
            ),
        )
    }

    private fun stripInstructionPrefix(text: String): String {
        return text.replaceFirst(
            Regex("""(?i)^\s*(?:type|enter|fill|set|write|use)(?:\s+only)?\s+"""),
            "",
        ).trim()
    }

    private fun buildKnownLabels(target: ScreenElement): List<String> {
        val labelBlob = buildString {
            append(target.hint)
            append(' ')
            append(target.text)
            append(' ')
            append(target.contentDescription)
            append(' ')
            append(bestLabel(target))
        }.lowercase()

        return linkedSetOf<String>().apply {
            add(target.hint)
            add(target.text)
            add(target.contentDescription)
            add(bestLabel(target))

            if (labelBlob.contains("pickup")) {
                add("Enter pickup location")
                add("Pickup location")
                add("Enter pickup")
                add("Pickup")
                add("Current location")
            }

            if (
                labelBlob.contains("where to") ||
                labelBlob.contains("destination") ||
                labelBlob.contains("drop")
            ) {
                add("Where to")
                add("Search destination")
                add("Enter destination")
                add("Destination")
                add("Drop location")
            }

            if (labelBlob.contains("title")) {
                add("Add title")
                add("Event title")
                add("Title")
                add("Subject")
            }
        }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .sortedByDescending { it.length }
    }

    private fun stripLeadingFieldLabel(text: String, label: String): String {
        val trimmedLabel = label.trim()
        if (trimmedLabel.isBlank()) return text

        val escaped = Regex.escape(trimmedLabel)
        val patterns = listOf(
            Regex("""^\s*$escaped(?:\s*[:=\-]\s*|\s+)+""", RegexOption.IGNORE_CASE),
            Regex("""^\s*(?:type|enter|fill|set|write)\s+$escaped(?:\s*[:=\-]\s*|\s+)+""", RegexOption.IGNORE_CASE),
        )

        for (pattern in patterns) {
            val candidate = text.replaceFirst(pattern, "").trim()
            if (candidate.isNotBlank() && candidate.length < text.trim().length) {
                return candidate
            }
        }

        return text
    }

    private fun capture(text: String, pattern: String): String? {
        return Regex(pattern)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanupValue)
            ?.takeIf { it.isNotBlank() }
    }

    private fun bestLabel(target: ScreenElement): String {
        return listOf(
            target.text,
            target.hint,
            target.contentDescription,
            target.viewId.substringAfterLast('/').replace('_', ' '),
        ).firstOrNull { it.isNotBlank() } ?: ""
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun isPlaceholderOnly(text: String, labels: List<String>, fieldRole: String): Boolean {
        val normalized = normalizeForComparison(text)
        if (normalized.isBlank()) return false

        val known = buildSet {
            labels.mapTo(this) { normalizeForComparison(it) }
            when (fieldRole) {
                "pickup-input" -> addAll(
                    listOf(
                        "from",
                        "pickup",
                        "pickup location",
                        "enter pickup",
                        "enter pickup location",
                        "current location",
                        "my location",
                        "use my location",
                        "use current location",
                        "gps",
                        "using gps",
                        "right now location",
                        "live location",
                        "present location",
                        "here",
                    ),
                )

                "destination-input" -> addAll(
                    listOf(
                        "to",
                        "where to",
                        "destination",
                        "search destination",
                        "enter destination",
                        "drop location",
                    ),
                )

                "title-input" -> addAll(
                    listOf("add title", "event title", "title", "subject"),
                )
            }
        }.filter { it.isNotBlank() }

        return normalized in known
    }

    private fun normalizeForComparison(text: String): String {
        return cleanupValue(text)
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun cleanupValue(text: String): String {
        return text
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
            .replace(Regex("""^[\[\(\{<\s]+"""), "")
            .replace(Regex("""[\]\)\}>]+$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', ':', ';', '-', ',', '?', '"', '\'', '[', ']', '(', ')', '{', '}', '<', '>')
    }
}
