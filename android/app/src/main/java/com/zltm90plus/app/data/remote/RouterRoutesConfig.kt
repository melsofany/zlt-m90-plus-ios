package com.zltm90plus.app.data.remote

import org.json.JSONObject

/**
 * Firmware route table and response field aliases, loaded from `assets/router_routes.json`.
 *
 * Nothing in this file is hard-coded on purpose: ZLT M90 Plus firmware differs by version
 * and by carrier, so a single editable JSON file is the one place a developer touches when
 * capturing the real endpoints from the device web UI.
 */
data class RouterRoutesConfig(
    val version: Int,
    val requestTimeoutSeconds: Int,
    val connectTimeoutSeconds: Int,
    val encoding: String,
    val routes: Map<String, Route>,
    val fields: Map<String, List<String>>,
    /** Optional global field aliases used when a response key is not listed in [fields]. */
    val genericAliases: Map<String, List<String>>,
) {
    data class Route(
        val path: String,
        val method: String = "GET",
        val bodyTemplate: Map<String, String> = emptyMap(),
        /** Response keys that should be treated as a successful login. */
        val successIndicators: List<String> = emptyList(),
        val failureIndicators: List<String> = emptyList(),
    )

    fun route(key: String): Route? = routes[key]

    /** Candidate response keys for a logical field, longest-first for deterministic matching. */
    fun aliases(field: String): List<String> = fields[field].orEmpty()

    companion object {
        const val ASSET_NAME = "router_routes.json"

        /** Used when the asset is missing or malformed; keeps the app usable and honest. */
        val EMPTY = RouterRoutesConfig(
            version = 0,
            requestTimeoutSeconds = 12,
            connectTimeoutSeconds = 8,
            encoding = "json",
            routes = emptyMap(),
            fields = emptyMap(),
            genericAliases = emptyMap(),
        )

        /**
         * Parses the route configuration. Unknown keys are ignored so the file can grow
         * without breaking older builds.
         */
        fun parse(json: String): RouterRoutesConfig {
            val root = JSONObject(json)
            val routesObj = root.optJSONObject("routes") ?: JSONObject()
            val routes = mutableMapOf<String, Route>()
            routesObj.keys().forEach { key ->
                val obj = routesObj.optJSONObject(key) ?: return@forEach
                routes[key] = Route(
                    path = obj.optString("path"),
                    method = obj.optString("method", "GET").uppercase(),
                    bodyTemplate = obj.optJSONObject("body")?.toStringMap().orEmpty(),
                    successIndicators = obj.optJSONArray("successIndicators")?.toStringList().orEmpty(),
                    failureIndicators = obj.optJSONArray("failureIndicators")?.toStringList().orEmpty(),
                )
            }

            val fieldsObj = root.optJSONObject("fields") ?: JSONObject()
            val fields = mutableMapOf<String, List<String>>()
            fieldsObj.keys().forEach { key ->
                fields[key] = fieldsObj.optJSONArray(key)?.toStringList().orEmpty()
            }

            val genericObj = root.optJSONObject("genericAliases") ?: JSONObject()
            val generic = mutableMapOf<String, List<String>>()
            genericObj.keys().forEach { key ->
                generic[key] = genericObj.optJSONArray(key)?.toStringList().orEmpty()
            }

            return RouterRoutesConfig(
                version = root.optInt("version", 1),
                requestTimeoutSeconds = root.optInt("requestTimeoutSeconds", 12),
                connectTimeoutSeconds = root.optInt("connectTimeoutSeconds", 8),
                encoding = root.optString("encoding", "json").lowercase(),
                routes = routes,
                fields = fields,
                genericAliases = generic,
            )
        }
    }
}

internal fun JSONObject.toStringMap(): Map<String, String> {
    val out = mutableMapOf<String, String>()
    keys().forEach { key -> out[key] = optString(key) }
    return out
}

internal fun org.json.JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { optString(it).takeIf { value -> value.isNotBlank() } }
