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
    /** Interface families to try, in order. */
    val protocolOrder: List<String> = listOf(GOFORM, LUCI),
    /** Endpoint and command table for the goform (GoAhead/ZTE-Tozed) interface. */
    val goform: Goform = Goform(),
) {
    data class Route(
        val path: String,
        val method: String = "GET",
        val bodyTemplate: Map<String, String> = emptyMap(),
        /** Response keys that should be treated as a successful login. */
        val successIndicators: List<String> = emptyList(),
        val failureIndicators: List<String> = emptyList(),
    )

    /**
     * The ZLT M90 Plus answers on the goform surface of an embedded GoAhead web server rather
     * than the LuCI routes the app originally assumed. Reads and writes are `cmd`-driven
     * requests rather than REST resources, so they need their own table.
     */
    data class Goform(
        val getPath: String = "/goform/goform_get_cmd_process",
        val setPath: String = "/goform/goform_set_cmd_process",
        val sessionCheckCmd: String = "loginfo",
        val sessionOkValue: String = "ok",
        val loginGoformId: String = "LOGIN",
        val logoutGoformId: String = "LOGOUT",
        val loginUserField: String = "user",
        val loginPasswordField: String = "password",
        /**
         * How the login password has to be sent. GoAhead/ZTE goform firmwares expect the password
         * Base64-encoded; posting it in clear text is answered with a wrong-password result even
         * when the credentials are correct. Set to `plain` for a build that wants it verbatim.
         */
        val loginPasswordEncoding: String = PASSWORD_ENCODING_BASE64,
        val username: String = "admin",
        /** `result` values that mean the request was accepted (0 = ok, 4 = already logged in). */
        val successResultCodes: List<String> = listOf("0", "4"),
        /** `result` value the firmware returns for a wrong password. */
        val wrongPasswordResultCodes: List<String> = listOf("3"),
        val sessionTokenAliases: List<String> = emptyList(),
        /** Logical dataset name to comma-separated firmware field list. */
        val commands: Map<String, String> = emptyMap(),
        val writes: Map<String, Write> = emptyMap(),
    ) {
        data class Write(
            val goformId: String,
            /** Request fields with `{ssid}` / `{password}` placeholders. */
            val fields: Map<String, String> = emptyMap(),
        )
    }

    fun route(key: String): Route? = routes[key]

    /** Candidate response keys for a logical field, longest-first for deterministic matching. */
    fun aliases(field: String): List<String> = fields[field].orEmpty()

    companion object {
        const val ASSET_NAME = "router_routes.json"
        const val GOFORM = "goform"
        const val LUCI = "luci"

        /** Accepted values for [Goform.loginPasswordEncoding]. */
        const val PASSWORD_ENCODING_BASE64 = "base64"
        const val PASSWORD_ENCODING_PLAIN = "plain"

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

            val order = root.optJSONObject("protocols")
                ?.optJSONArray("order")
                ?.toStringList()
                ?.filter { it == GOFORM || it == LUCI }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(GOFORM, LUCI)

            val goformObj = root.optJSONObject("goform")
            val goform = Goform(
                getPath = goformObj?.optString("getPath")?.takeIf { it.isNotBlank() } ?: Goform().getPath,
                setPath = goformObj?.optString("setPath")?.takeIf { it.isNotBlank() } ?: Goform().setPath,
                sessionCheckCmd = goformObj?.optString("sessionCheckCmd")?.takeIf { it.isNotBlank() }
                    ?: Goform().sessionCheckCmd,
                sessionOkValue = goformObj?.optString("sessionOkValue")?.takeIf { it.isNotBlank() }
                    ?: Goform().sessionOkValue,
                loginGoformId = goformObj?.optString("loginGoformId")?.takeIf { it.isNotBlank() }
                    ?: Goform().loginGoformId,
                logoutGoformId = goformObj?.optString("logoutGoformId")?.takeIf { it.isNotBlank() }
                    ?: Goform().logoutGoformId,
                loginUserField = goformObj?.optString("loginUserField")?.takeIf { it.isNotBlank() }
                    ?: Goform().loginUserField,
                loginPasswordField = goformObj?.optString("loginPasswordField")?.takeIf { it.isNotBlank() }
                    ?: Goform().loginPasswordField,
                loginPasswordEncoding = goformObj?.optString("loginPasswordEncoding")
                    ?.takeIf { it.isNotBlank() } ?: Goform().loginPasswordEncoding,
                username = goformObj?.optString("username")?.takeIf { it.isNotBlank() } ?: Goform().username,
                successResultCodes = goformObj?.optJSONArray("successResultCodes")?.toStringList()
                    ?.takeIf { it.isNotEmpty() } ?: Goform().successResultCodes,
                wrongPasswordResultCodes = goformObj?.optJSONArray("wrongPasswordResultCodes")?.toStringList()
                    ?.takeIf { it.isNotEmpty() } ?: Goform().wrongPasswordResultCodes,
                sessionTokenAliases = goformObj?.optJSONArray("sessionTokenAliases")?.toStringList().orEmpty(),
                commands = goformObj?.optJSONObject("commands")?.toStringMap().orEmpty(),
                writes = goformObj?.optJSONObject("writes")?.let { writes ->
                    buildMap {
                        writes.keys().forEach { key ->
                            val entry = writes.optJSONObject(key) ?: return@forEach
                            val goformId = entry.optString("goformId")
                            if (goformId.isBlank()) return@forEach
                            put(
                                key,
                                Goform.Write(
                                    goformId = goformId,
                                    fields = entry.optJSONObject("fields")?.toStringMap().orEmpty(),
                                ),
                            )
                        }
                    }
                }.orEmpty(),
            )

            return RouterRoutesConfig(
                version = root.optInt("version", 1),
                requestTimeoutSeconds = root.optInt("requestTimeoutSeconds", 12),
                connectTimeoutSeconds = root.optInt("connectTimeoutSeconds", 8),
                encoding = root.optString("encoding", "json").lowercase(),
                routes = routes,
                fields = fields,
                genericAliases = generic,
                protocolOrder = order,
                goform = goform,
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
