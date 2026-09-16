package com.zltm90plus.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A real HTTP server standing in for the router.
 *
 * The API tests open an actual socket, send real headers and parse real responses; nothing about
 * the transport is mocked. Only `java.net` is used so the same server runs on the JVM and under
 * Robolectric, whose sandbox replaces the Android framework classes but leaves sockets intact.
 *
 * It reproduces the details that broke the previous client: the firmware session cookie arrives
 * as `Set-Cookie` with attributes, a successful login answers `{"result":"0"}` rather than a JSON
 * token, reads are `cmd`-driven, and a read without the session cookie is refused.
 */
class FakeGoformServer(
    private val password: String = "admin",
    /** Firmware fields this build does not implement; they come back absent. */
    private val unsupportedFields: Set<String> = emptySet(),
) {

    private val serverSocket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

    val loginAttempts = AtomicInteger()

    /** How many times the login page was fetched, to prove the endpoint was learned from it. */
    val pageRequests = AtomicInteger()

    /** How many script bundles were fetched, to prove the shell was followed to its code. */
    val bundleRequests = AtomicInteger()
    val queriesWithoutSession = AtomicInteger()
    val lastCmd: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val lastSetBody: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val lastQueryResponse: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** When true every query answers as logged out, to exercise session-expiry handling. */
    @Volatile
    var sessionAlwaysInvalid = false

    /**
     * When true, every answer is preceded by a verbatim echo of the request line, which is what
     * the device's GoAhead server was observed doing: the bytes began
     * `/goform/goform_set_cmd_process HTTP/1.1 301 Moved Permanently`. OkHttp cannot parse that as
     * a status line, so the client has to recognise it rather than call it a network fault.
     */
    @Volatile
    var echoRequestLineAsStatus = false

    /** When true, the goform paths answer 404, which is what this firmware does over https. */
    @Volatile
    var goformReturns404 = false

    /**
     * When the malformed reply is served, this is the `Location` it carries, so a test can stand in
     * for the device that answers the configured address with a redirect to the port it really
     * listens on.
     */
    @Volatile
    var redirectTarget: String? = null

    /**
     * Where the device really accepts a login, and where it really serves reads.
     *
     * Defaults match the configured paths. A test moves them to stand in for firmware 1.12.8, which
     * answers the configured paths with 404 — the field failure this exists to catch.
     */
    @Volatile
    var loginPath: String = "/goform/goform_set_cmd_process"

    @Volatile
    var readPath: String = "/goform/goform_get_cmd_process"

    /** Field names the device's page states, so a test can prove they are taken from the page. */
    @Volatile
    var pageUserField: String = "user"

    @Volatile
    var pagePasswordField: String = "password"

    /**
     * Serve the single-page shell firmware 1.12.8 actually serves, instead of a plain login form.
     *
     * The real page is a Vue shell: `<div id="app">` and two `<script src>` tags, with no form and
     * no endpoint anywhere in the HTML. The endpoint exists only inside the bundle, so a client that
     * reads just the HTML learns nothing — the failure the field log revealed.
     */
    @Volatile
    var servesSinglePageShell: Boolean = false

    /** The bundle the shell loads, and the endpoints it quotes. */
    @Volatile
    var appBundlePath: String = "js/app.js"

    @Volatile
    var vendorBundlePath: String = "js/chunk-vendors.js"

    @Volatile
    var appBundleBody: String =
        """var api={base:"/cgi-bin/goform/goform_set_cmd_process",read:"/cgi-bin/goform/goform_get_cmd_process"};"""

    /** The framework bundle, deliberately huge and free of endpoints, as the real one is. */
    @Volatile
    var vendorBundleBody: String = "/* vue */ var framework='" + "x".repeat(200_000) + "';"

    /**
     * The login page this server serves at `/`.
     *
     * A device has to publish the endpoint its form posts to, or the page could not log anyone in,
     * so this is the one source that cannot be a guess about the firmware.
     */
    private fun loginPageHtml(): String =
        if (!servesSinglePageShell) {
            """
            <!DOCTYPE html>
            <html><head><title>ZLT Login</title></head>
            <body>
              <form id="loginForm" action="$loginPath" method="post">
                <input name="$pageUserField" type="text"/>
                <input name="$pagePasswordField" type="password"/>
              </form>
              <script>var enc = base64($pagePasswordField);</script>
            </body></html>
            """.trimIndent()
        } else {
            """
            <!DOCTYPE html><html lang=""><head><meta charset="utf-8"><title></title>
            <link href="css/app.css" rel="preload" as="style">
            <link href="js/app.js" rel="preload" as="script">
            <link href="js/chunk-vendors.js" rel="preload" as="script">
            </head><body><div id="app"><div id="first-loading-body"></div></div>
            <script src="$vendorBundlePath"></script>
            <script src="$appBundlePath"></script></body></html>
            """.trimIndent()
        }

    /** The exact page this server serves, so a test can check the parse against the real bytes. */
    fun pageHtml(): String = loginPageHtml()

    @Volatile
    private var running = true

    val port: Int get() = serverSocket.localPort
    val host: String get() = "127.0.0.1"

    fun start() = apply {
        thread(isDaemon = true, name = "fake-goform-server") {
            while (running) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(socket) } }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket.close() }
    }

    private fun serve(socket: Socket) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val name = line.substringBefore(':', "")
                if (name.isNotEmpty()) headers[name.trim().lowercase()] = line.substringAfter(':').trim()
            }

            val body = if (headers["content-length"] != null) {
                val length = headers["content-length"]!!.toIntOrNull() ?: 0
                val buffer = CharArray(length)
                var read = 0
                while (read < length) {
                    val count = reader.read(buffer, read, length - read)
                    if (count < 0) break
                    read += count
                }
                String(buffer, 0, read)
            } else {
                ""
            }

            val query = target.substringAfter('?', "")
            val path = target.substringBefore('?')
            if (goformReturns404 && path.contains("/goform/")) {
                write(socket, Response(404, "<html><body>404 Not Found</body></html>"))
                return
            }
            val response = when {
                path.endsWith(loginPath) && path.contains("set_cmd_process") -> handleSet(body)
                path.endsWith(readPath) && path.contains("get_cmd_process") -> handleGet(query, headers)
                // The page a real device serves, naming the endpoint it posts a login to. Only the
                // configured path is named when the test moves the endpoint, so a client that
                // cannot read the page keeps asking the wrong place and fails.
                path == "/" -> { pageRequests.incrementAndGet(); Response(200, loginPageHtml()) }
                // Scripts are served by exact path, so a client that asked for the wrong one, or
                // never asked, cannot accidentally succeed.
                path == "/$vendorBundlePath" -> {
                    bundleRequests.incrementAndGet()
                    Response(200, vendorBundleBody, listOf("Content-Type" to "application/javascript"))
                }
                path == "/$appBundlePath" -> {
                    bundleRequests.incrementAndGet()
                    Response(200, appBundleBody, listOf("Content-Type" to "application/javascript"))
                }
                else -> Response(404, "<html><body>404 Not Found</body></html>")
            }
            write(socket, response, if (echoRequestLineAsStatus) target else null)
        }
    }

    private fun handleSet(body: String): Response {
        lastSetBody.add(body)
        val form = body.parseForm()
        return when (form["goformId"]) {
            "LOGIN" -> {
                loginAttempts.incrementAndGet()
                // The firmware wants the password Base64-encoded in this field; a clear-text
                // password is rejected exactly like a wrong one, so decoding it here is what makes
                // the test able to tell "wrong password" from "wrong encoding".
                val presented = form["password"].orEmpty()
                val decoded = runCatching {
                    String(java.util.Base64.getDecoder().decode(presented), Charsets.UTF_8)
                }.getOrDefault(presented)
                if (form["user"] == "admin" && decoded == password) {
                    // A real Set-Cookie header carries attributes, which is exactly what the old
                    // client forwarded verbatim as a request Cookie header.
                    Response(
                        status = 200,
                        body = """{"result":"0"}""",
                        headers = listOf("Set-Cookie" to "sessionid=$SESSION_ID; path=/; HttpOnly"),
                    )
                } else {
                    Response(200, """{"result":"3"}""")
                }
            }
            "LOGOUT" -> Response(200, """{"result":"0"}""")
            else -> Response(200, """{"result":"0"}""")
        }
    }

    private fun handleGet(query: String, headers: Map<String, String>): Response {
        val cmd = query.parseForm()["cmd"].orEmpty()
        lastCmd.add(cmd)

        val cookie = headers["cookie"].orEmpty()
        if (sessionAlwaysInvalid) return Response(200, """{"loginfo":"not_login"}""")
        if (!cookie.contains("sessionid=$SESSION_ID")) {
            queriesWithoutSession.incrementAndGet()
            return Response(200, """{"result":"error","loginfo":"not_login"}""")
        }

        val requested = cmd.split(",").filter { it.isNotBlank() && it !in unsupportedFields }
        if (requested.isEmpty()) return Response(200, "{}")
        // Built through JSONObject so values containing quotes (the station list is itself JSON
        // text) are escaped the way real firmware escapes them.
        val json = org.json.JSONObject().apply {
            requested.forEach { field -> put(field, fields[field].orEmpty()) }
        }
        lastQueryResponse.add(json.toString())
        return Response(200, json.toString())
    }

    /**
     * [echoStatusInsteadOf] writes the request line verbatim where the status line belongs, which
     * is the malformed reply the device produced. It is passed explicitly per response rather than
     * read from the flag so the behaviour is visible at each call site.
     */
    private fun write(socket: Socket, response: Response, echoStatusInsteadOf: String? = null) {
        val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1)
        val payload = response.body.toByteArray(Charsets.UTF_8)
        if (echoStatusInsteadOf != null) {
            // "/goform/goform_set_cmd_process HTTP/1.1 301 Moved Permanently", exactly as seen.
            writer.write(echoStatusInsteadOf + " HTTP/1.1 301 Moved Permanently\r\n")
            redirectTarget?.let { writer.write("Location: $it\r\n") }
            writer.write("Content-Length: ${payload.size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.flush()
            socket.getOutputStream().write(payload)
            socket.getOutputStream().flush()
            return
        }
        writer.write("HTTP/1.1 ${response.status} OK\r\n")
        writer.write("Content-Type: application/json\r\n")
        writer.write("Content-Length: ${payload.size}\r\n")
        response.headers.forEach { (name, value) -> writer.write("$name: $value\r\n") }
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
        socket.getOutputStream().write(payload)
        socket.getOutputStream().flush()
    }

    private fun String.parseForm(): Map<String, String> =
        split("&")
            .mapNotNull { pair ->
                val key = pair.substringBefore('=', "")
                if (key.isEmpty()) null
                else key to java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
            .toMap()

    private data class Response(
        val status: Int,
        val body: String,
        val headers: List<Pair<String, String>> = emptyList(),
    )

    /**
     * Values mirror the reference device (ZLT M90 PLUS, firmware 1.12.8). This firmware answers
     * `-` for fields it cannot read, which must surface as "unavailable" rather than as text.
     */
    private val fields: Map<String, String> = mapOf(
        "imei" to "860540080045325",
        "imsi" to "602023419866456",
        "iccid" to "8920022032136564570F",
        "msisdn" to "-",
        "cr_version" to "1.12.8",
        "wa_inner_version" to "ZLT_M90PLUS_V1.12.8",
        "hardware_version" to "M90P_V1.0",
        "lan_ipaddr" to "192.168.1.1",
        "wan_ipaddr" to "10.44.0.9",
        "network_type" to "LTE",
        "network_provider" to "Mobily",
        "ppp_status" to "ppp_connected",
        "modem_main_state" to "modem_init_complete",
        "signalbar" to "80",
        "lte_rsrp" to "-92",
        "battery_value" to "78",
        "battery_charging" to "0",
        "battery_vol_percent" to "78",
        "monthly_tx_bytes" to "134217728",
        "monthly_rx_bytes" to "1073741824",
        "data_volume_limit_size" to "50",
        "data_volume_limit_unit" to "GB",
        "realtime_time" to "636",
        "monthly_time" to "18120",
        "loginfo" to "ok",
        "station_list" to
            """[{"hostname":"iPhone","mac":"A4:5E:60:11:22:33","ip":"192.168.1.101","type":"5G"},""" +
            """{"mac":"B8:27:EB:44:55:66","ip":"192.168.1.102"}]""",
    )

    private companion object {
        const val SESSION_ID = "test-session-9f3a"
    }
}
