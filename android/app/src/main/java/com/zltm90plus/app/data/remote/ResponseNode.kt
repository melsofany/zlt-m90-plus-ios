package com.zltm90plus.app.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * A parsed firmware response reduced to a uniform tree so the rest of the app does not care
 * whether the device answered with JSON or XML, or how deeply the payload nests its fields.
 */
class ResponseNode private constructor(
    private val objectValues: Map<String, ResponseNode>?,
    private val arrayValues: List<ResponseNode>?,
    val scalar: String?,
    val numeric: Double?,
    val boolean: Boolean?,
) {
    val isObject: Boolean get() = objectValues != null
    val isArray: Boolean get() = arrayValues != null

    val objectMap: Map<String, ResponseNode> get() = objectValues.orEmpty()
    val arrayItems: List<ResponseNode> get() = arrayValues.orEmpty()

    /**
     * Finds the first value matching any alias, searching breadth-first so top-level keys win
     * over same-named keys buried deeper in the payload.
     */
    fun find(aliases: List<String>): ResponseNode? {
        if (aliases.isEmpty()) return null
        val normalized = aliases.map { normalizeKey(it) }
        var frontier = listOf(this)
        repeat(MAX_SEARCH_DEPTH) {
            val next = mutableListOf<ResponseNode>()
            for (node in frontier) {
                node.objectValues?.forEach { (key, value) ->
                    if (normalizeKey(key) in normalized) return value
                    next += value
                }
                node.arrayValues?.forEach { next += it }
            }
            if (next.isEmpty()) return null
            frontier = next
        }
        return null
    }

    fun findInt(aliases: List<String>): Int? {
        val node = find(aliases) ?: return null
        node.numeric?.let { return it.toInt() }
        return node.scalar?.let { parseFlexibleInt(it) }
    }

    fun findDouble(aliases: List<String>): Double? {
        val node = find(aliases) ?: return null
        node.numeric?.let { return it }
        return node.scalar?.let { parseFlexibleDouble(it) }
    }

    fun findString(aliases: List<String>): String? =
        find(aliases)?.scalar?.takeIf { it.isNotBlank() }

    fun findBoolean(aliases: List<String>, config: RouterRoutesConfig): Boolean? {
        val node = find(aliases) ?: return null
        node.boolean?.let { return it }
        val raw = normalizeScalar(node.scalar) ?: return null
        if (raw in config.genericAliases["true"].orEmpty()) return true
        if (raw in config.genericAliases["false"].orEmpty()) return false
        return raw.toBooleanStrictOrNull()
    }

    fun findArray(aliases: List<String>): List<ResponseNode>? {
        val node = find(aliases) ?: return null
        if (node.isArray) return node.arrayItems
        // Some firmware wraps a single client object in an object keyed by MAC.
        if (node.isObject && node.objectMap.values.all { it.isObject }) return node.objectMap.values.toList()
        return null
    }

    fun asScalarOrSerialized(): String? {
        scalar?.let { return it }
        objectValues?.let { return JSONObject(it.mapValues { (_, v) -> v.asPlainValue() }).toString() }
        arrayValues?.let { return JSONArray(it.map { item -> item.asPlainValue() }).toString() }
        return null
    }

    private fun asPlainValue(): Any? = when {
        scalar != null -> scalar
        boolean != null -> boolean
        numeric != null -> numeric
        isObject -> JSONObject(objectMap.mapValues { (_, v) -> v.asPlainValue() })
        isArray -> JSONArray(arrayItems.map { it.asPlainValue() })
        else -> JSONObject.NULL
    }

    companion object {
        private const val MAX_SEARCH_DEPTH = 4

        fun ofObject(values: Map<String, ResponseNode>): ResponseNode {
            val scalar = values["#text"]?.scalar
            val numeric = scalar?.let { parseFlexibleDouble(it) }
            return ResponseNode(values, null, scalar, numeric, scalar?.let { parseFlexibleBool(it) })
        }

        fun ofArray(items: List<ResponseNode>): ResponseNode = ResponseNode(null, items, null, null, null)

        fun ofScalar(raw: String): ResponseNode {
            val trimmed = raw.trim()
            return ResponseNode(
                objectValues = null,
                arrayValues = null,
                scalar = trimmed,
                numeric = parseFlexibleDouble(trimmed),
                boolean = parseFlexibleBool(trimmed),
            )
        }

        fun ofJson(json: String): ResponseNode? = runCatching { fromJsonValue(JSONObject(json)) }
            .recoverCatching { fromJsonValue(JSONArray(json)) }
            .getOrNull()

        private fun fromJsonValue(value: Any?): ResponseNode = when (value) {
            null, JSONObject.NULL -> ofScalar("")
            is JSONObject -> {
                val keys = value.keys()
                val children = LinkedHashMap<String, ResponseNode>()
                while (keys.hasNext()) {
                    val key = keys.next()
                    children[key] = fromJsonValue(value.opt(key))
                }
                ofObject(children)
            }
            is JSONArray -> ofArray((0 until value.length()).map { fromJsonValue(value.opt(it)) })
            is Boolean -> ResponseNode(null, null, value.toString(), null, value)
            is Number -> ResponseNode(null, null, value.toString(), value.toDouble(), null)
            else -> ofScalar(value.toString())
        }
    }
}

internal fun normalizeKey(key: String): String = key.trim().lowercase()

internal fun normalizeScalar(value: String?): String? = value?.trim()?.lowercase()

internal fun parseFlexibleBool(raw: String?): Boolean? {
    val cleaned = normalizeScalar(raw) ?: return null
    if (cleaned.isEmpty()) return null
    return when (cleaned) {
        "1", "true", "yes", "on", "charging", "connected", "registered" -> true
        "0", "false", "no", "off", "not_charging", "disconnected", "unregistered" -> false
        else -> null
    }
}

internal fun parseFlexibleInt(raw: String): Int? {
    val cleaned = raw.trim().removeSuffix("%").replace(",", "")
    cleaned.toIntOrNull()?.let { return it }
    cleaned.toDoubleOrNull()?.let { return it.toInt() }
    // Values such as "3.8 GB" or "1,024 MB" appear in real quota responses.
    val unitValue = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([kKmMgGtT]?)[bB]?")
        .find(cleaned)
        ?.let { match ->
            val number = match.groupValues[1].toDoubleOrNull() ?: return null
            val multiplier = when (match.groupValues[2].lowercase()) {
                "k" -> 1_000.0
                "m" -> 1_000_000.0
                "g" -> 1_000_000_000.0
                "t" -> 1_000_000_000_000.0
                else -> 1.0
            }
            number * multiplier
        }
        ?: return null
    return unitValue.toInt()
}

internal fun parseFlexibleDouble(raw: String): Double? {
    val cleaned = raw.trim().removeSuffix("%").replace(",", "")
    cleaned.toDoubleOrNull()?.let { return it }
    return Regex("-?[0-9]+(?:\\.[0-9]+)?").find(cleaned)?.value?.toDoubleOrNull()
}
