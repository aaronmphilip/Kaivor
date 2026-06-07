package com.bharatdroid.agent.skills.builtin

import com.google.gson.JsonParser

object WebFormInfo {
    private val reservedKeys = setOf(
        "action", "url", "query", "goal", "required", "requiredfields",
        "required_fields", "submit", "fields", "fieldvalues", "field_values",
        "data", "info",
    )

    fun extractFields(params: Map<String, Any>): MutableMap<String, String> {
        val fields = linkedMapOf<String, String>()
        listOf("fields", "fieldValues", "field_values", "data", "info").forEach { key ->
            addStructuredFields(params[key], fields)
        }
        params.forEach { (key, value) ->
            val normalized = key.lowercase()
            if (normalized !in reservedKeys && value !is Map<*, *>) {
                val text = value.toString().trim()
                if (text.isNotBlank()) fields[key] = text
            }
        }
        return fields
    }

    fun extractRequiredFields(params: Map<String, Any>): List<String> {
        val raw = params["required"] ?: params["requiredFields"] ?: params["required_fields"]
        return splitList(raw)
    }

    fun missingRequired(fields: Map<String, String>, required: List<String>): List<String> {
        val normalizedFields = fields
            .filterValues { it.isNotBlank() }
            .keys
            .map { normalizeKey(it) }
            .toSet()

        return required.filter { normalizeKey(it) !in normalizedFields }
    }

    fun formatFields(fields: Map<String, String>): String {
        if (fields.isEmpty()) return "(none provided)"
        return fields.entries.joinToString("\n") { (key, value) -> "- $key: $value" }
    }

    fun parseMissingInfo(result: String): String? {
        val marker = Regex("""MISSING_INFO\s*:\s*([^\n.]+)""", RegexOption.IGNORE_CASE)
        return marker.find(result)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun addStructuredFields(raw: Any?, fields: MutableMap<String, String>) {
        when (raw) {
            null -> return
            is Map<*, *> -> raw.forEach { (key, value) ->
                val field = key?.toString()?.trim().orEmpty()
                val text = value?.toString()?.trim().orEmpty()
                if (field.isNotBlank() && text.isNotBlank()) fields[field] = text
            }
            is String -> {
                val text = raw.trim()
                if (text.isBlank()) return
                if (text.startsWith("{")) {
                    runCatching {
                        val obj = JsonParser.parseString(text).asJsonObject
                        obj.entrySet().forEach { (key, value) ->
                            if (value.isJsonPrimitive) {
                                val fieldValue = value.asString.trim()
                                if (fieldValue.isNotBlank()) fields[key] = fieldValue
                            }
                        }
                    }.onFailure {
                        addPairsFromText(text, fields)
                    }
                } else {
                    addPairsFromText(text, fields)
                }
            }
            else -> addPairsFromText(raw.toString(), fields)
        }
    }

    private fun addPairsFromText(text: String, fields: MutableMap<String, String>) {
        text.split("\n", ";", ",").forEach { part ->
            val separator = when {
                ":" in part -> ":"
                "=" in part -> "="
                else -> null
            } ?: return@forEach
            val key = part.substringBefore(separator).trim()
            val value = part.substringAfter(separator).trim()
            if (key.isNotBlank() && value.isNotBlank()) fields[key] = value
        }
    }

    private fun splitList(raw: Any?): List<String> {
        return when (raw) {
            null -> emptyList()
            is Iterable<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            is Array<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            else -> {
                val text = raw.toString().trim()
                if (text.startsWith("[")) {
                    runCatching {
                        JsonParser.parseString(text).asJsonArray
                            .mapNotNull { item -> item.asString.trim().takeIf(String::isNotBlank) }
                    }.getOrElse {
                        splitPlainList(text)
                    }
                } else {
                    splitPlainList(text)
                }
            }
        }
    }

    private fun splitPlainList(text: String): List<String> =
        text.split(",", ";", "\n")
            .map { it.trim().trim('"', '[', ']') }
            .filter { it.isNotBlank() }

    private fun normalizeKey(key: String): String =
        key.lowercase().replace(Regex("""[^a-z0-9]"""), "")
}
