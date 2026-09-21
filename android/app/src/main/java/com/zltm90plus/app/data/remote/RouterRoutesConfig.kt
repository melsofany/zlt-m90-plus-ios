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
    val protocolOrder: List<String> = listOf(HTTP_CGI, GOFORM, LUCI),
    /** Endpoint and command table for the goform (GoAhead/ZTE-Tozed) interface. */
    val goform: Goform = Goform(),
    /** Endpoint and command table for the JSON `http.cgi` interface. */
    val httpCgi: HttpCgi = HttpCgi(),
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
     * The JSON `http.cgi` interface, which firmware 1.12.8 serves instead of goform.
     *
     * Every call is a `POST` to one path with a JSON body carrying `cmd`, `method` and `sessionId`.
     * There are no cookies — the session lives in the body — and the password is never sent, only a
     * digest of it. Command numbers and field names come from `docs/http-cgi-protocol.md`, which was
     * derived from captured browser traffic; none of them are guesses.
     */
    data class HttpCgi(
        val path: String = "/cgi-bin/http.cgi",
        /** Issues [token]; called before login, so it must be allowed with an empty session. */
        val tokenCmd: Int = 232,
        /** Device/branding info, also callable before login. Carries `domain_value`. */
        val deviceConfigCmd: Int = 1008,
        /** The login command. */
        val loginCmd: Int = 100,
        /** The status blob; this is where battery lives. */
        val statusCmd: Int = 1005,
        val radioCmd: Int = 1002,
        val hardwareCmd: Int = 1001,
        val trafficCmd: Int = 337,
        val clientsCmd: Int = 224,
        val uptimeCmd: Int = 104,
        val loginUserField: String = "username",
        val loginPasswordField: String = "passwd",
        /** Field carrying the server nonce that must be echoed in the login. */
        val tokenField: String = "token",
        val sessionField: String = "sessionId",
        /**
         * How the password is turned into the value sent as [loginPasswordField].
         *
         * Observed to be a 64-character value, consistent with a SHA-256 hex digest, but which
         * inputs feed it is not yet confirmed. `sha256` is the simple recipe; keep this configurable
         * so a confirmed different recipe is a data change rather than a code change.
         */
        val passwordDigest: String = DIGEST_SHA256,
        /** Whether the session id must be sent as "" before login, as the device does. */
        val emptySessionBeforeLogin: Boolean = true,
        /**
         * Logical dataset name to command number, populated from the same field as
         * [Goform.commands] but with the device's numeric codes.
         *
         * Battery, for example, is not a command of its own — it arrives inside the `statusCmd`
         * blob, so `battery` maps to that same number.
         */
        val commands: Map<String, Int> = emptyMap(),
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
        /**
         * Substrings that identify a path belonging to this interface family.
         *
         * An endpoint learned from the device's own files is only spoken to when it carries one of
         * these. This firmware's bundle names `/cgi-bin/http.cgi`, a JSON-RPC dispatcher with no
         * `goformId` concept, and posting a goform login to it is a request in a protocol the device
         * does not speak — which is how a correct password reached an endpoint that could not judge
         * it. Configurable, because the words are a property of the firmware.
         */
        val endpointPathMarkers: List<String> = DEFAULT_ENDPOINT_PATH_MARKERS,
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
        const val HTTP_CGI = "httpcgi"

        /** Accepted values for [HttpCgi.passwordDigest]. */
        const val DIGEST_SHA256 = "sha256"

        /** Accepted values for [Goform.loginPasswordEncoding]. */
        const val PASSWORD_ENCODING_BASE64 = "base64"
        const val PASSWORD_ENCODING_PLAIN = "plain"

        /**
         * Words that mark a path as answering the goform interface.
         *
         * A bundle names several endpoints and the app currently takes only the first. On this
         * firmware the first is `/cgi-bin/http.cgi`, which is a different protocol entirely, so
         * recognising the family is what keeps the login from being sent to it.
         */
        val DEFAULT_ENDPOINT_PATH_MARKERS = listOf("goform", "set_cmd_process", "get_cmd_process")

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
                ?.filter { it == GOFORM || it == LUCI || it == HTTP_CGI }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(HTTP_CGI, GOFORM, LUCI)

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
                endpointPathMarkers = goformObj?.optJSONArray("endpointPathMarkers")?.toStringList()
                    ?.takeIf { it.isNotEmpty() } ?: Goform().endpointPathMarkers,
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

            val httpCgiObj = root.optJSONObject("httpCgi")
            val httpCgiDefaults = HttpCgi()
            val httpCgi = HttpCgi(
                path = httpCgiObj?.optString("path")?.takeIf { it.isNotBlank() } ?: httpCgiDefaults.path,
                tokenCmd = httpCgiObj?.optInt("tokenCmd", httpCgiDefaults.tokenCmd) ?: httpCgiDefaults.tokenCmd,
                deviceConfigCmd = httpCgiObj?.optInt("deviceConfigCmd", httpCgiDefaults.deviceConfigCmd)
                    ?: httpCgiDefaults.deviceConfigCmd,
                loginCmd = httpCgiObj?.optInt("loginCmd", httpCgiDefaults.loginCmd) ?: httpCgiDefaults.loginCmd,
                statusCmd = httpCgiObj?.optInt("statusCmd", httpCgiDefaults.statusCmd) ?: httpCgiDefaults.statusCmd,
                radioCmd = httpCgiObj?.optInt("radioCmd", httpCgiDefaults.radioCmd) ?: httpCgiDefaults.radioCmd,
                hardwareCmd = httpCgiObj?.optInt("hardwareCmd", httpCgiDefaults.hardwareCmd)
                    ?: httpCgiDefaults.hardwareCmd,
                trafficCmd = httpCgiObj?.optInt("trafficCmd", httpCgiDefaults.trafficCmd) ?: httpCgiDefaults.trafficCmd,
                clientsCmd = httpCgiObj?.optInt("clientsCmd", httpCgiDefaults.clientsCmd) ?: httpCgiDefaults.clientsCmd,
                uptimeCmd = httpCgiObj?.optInt("uptimeCmd", httpCgiDefaults.uptimeCmd) ?: httpCgiDefaults.uptimeCmd,
                loginUserField = httpCgiObj?.optString("loginUserField")?.takeIf { it.isNotBlank() }
                    ?: httpCgiDefaults.loginUserField,
                loginPasswordField = httpCgiObj?.optString("loginPasswordField")?.takeIf { it.isNotBlank() }
                    ?: httpCgiDefaults.loginPasswordField,
                tokenField = httpCgiObj?.optString("tokenField")?.takeIf { it.isNotBlank() }
                    ?: httpCgiDefaults.tokenField,
                sessionField = httpCgiObj?.optString("sessionField")?.takeIf { it.isNotBlank() }
                    ?: httpCgiDefaults.sessionField,
                passwordDigest = httpCgiObj?.optString("passwordDigest")?.takeIf { it.isNotBlank() }
                    ?: httpCgiDefaults.passwordDigest,
                emptySessionBeforeLogin = httpCgiObj?.optBoolean(
                    "emptySessionBeforeLogin",
                    httpCgiDefaults.emptySessionBeforeLogin,
                ) ?: httpCgiDefaults.emptySessionBeforeLogin,
                commands = httpCgiObj?.optJSONObject("commands")?.let { cmds ->
                    buildMap<String, Int> {
                        cmds.keys().forEach { key ->
                            // Stored as strings in JSON for readability; the wire format is numeric.
                            cmds.optString(key).trim().toIntOrNull()?.let { put(key, it) }
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
                httpCgi = httpCgi,
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
