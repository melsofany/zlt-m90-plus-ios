package com.zltm90plus.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Models the `/cgi-bin/http.cgi` interface of the ZLT M90 PLUS 1.12.8.
 *
 * Built from a browser HAR the user captured, so every command number and every field name here is
 * one the device was observed to use — none are invented. The protocol notes are in
 * `docs/http-cgi-protocol.md`.
 *
 * Differences from [FakeGoformServer] that matter to the client:
 * - Every call is `POST` to one path, with a JSON body. There is no `cmd` in the query string.
 * - There are no cookies. The session lives entirely in the `sessionId` body field.
 * - The server issues a 32-character `token` from `cmd 232` *before* login, and the client must
 *   echo it back in the `cmd 100` login.
 * - The password is not sent; a 64-character digest is, derived inside the browser.
 *
 * As with the goform fake, this double is deliberately as strict as the device: it refuses a login
 * whose `passwd` is not the expected digest, and refuses to answer reads before login. A lenient
 * fake would let a client that never authenticates look correct.
 */
class FakeHttpCgiServer {

    private val server = ServerSocket(0)
    val port: Int get() = server.localPort

    /** The 32-character nonce handed out by `cmd 232`. */
    val token = "0123456789abcdef0123456789abcdef"

    /** The digest `cmd 100` must carry. Real firmware derives it; here it is a fixed value. */
    var expectedDigest = "deadbeef".repeat(8)

    /** `domain_value` returned by `cmd 1008`. */
    var domainValue = "4a93e679058284a39a7d6da21038cf5b"

    /** The session id handed out by a successful `cmd 100`. */
    var issuedSessionId = "5f3c9a1b".repeat(8)

    var wantUser = "Vodafone"
    var wantPassword = "secret"

    /** Battery percent the device reports. */
    var batteryCapacity = "37"

    /** When true every command answers as if not logged in. */
    @Volatile
    var sessionAlwaysInvalid = false

    /** When true `cmd 100` is refused as a wrong password. */
    @Volatile
    var rejectsCredentials = false

    /** Number of `cmd 100` requests received. */
    val loginAttempts = AtomicInteger()

    /** Number of calls made to a read command before a session existed. */
    val unauthenticatedReads = AtomicInteger()

    /** Every command number received, in order. */
    val seenCommands = mutableListOf<Int>()

    /**
     * What the client must send as `passwd`.
     *
     * A digest function is injected rather than assumed: the real recipe is not yet confirmed (see
     * `docs/http-cgi-protocol.md`), so the client takes it as a collaborator and the test supplies
     * a known one. That keeps the test honest without baking in an unverified guess.
     */
    @Volatile
    var digestOf: (String) -> String = { pwd -> sha256Hex(pwd) }

    private val running = AtomicInteger(1)

    fun start() {
        thread(isDaemon = true) {
            while (running.get() == 1) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { handle(socket) }
            }
        }
    }

    fun stop() {
        running.set(0)
        runCatching { server.close() }
    }

    private fun handle(socket: Socket) = socket.use {
        val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val text = String(body, 0, read)

        val response = if (requestLine.contains("http.cgi")) {
            respond(text)
        } else {
            Response(404, """{"success":false,"message":"not found"}""")
        }
        write(it, response)
    }

    private fun respond(requestBody: String): Response {
        val json = runCatching { org.json.JSONObject(requestBody) }.getOrNull()
            ?: return Response(400, """{"success":false,"message":"bad json"}""")

        val cmd = json.optInt("cmd", -1)
        synchronized(seenCommands) { seenCommands.add(cmd) }

        val sessionId = json.optString("sessionId", "")
        val authenticated = sessionId == issuedSessionId && !sessionAlwaysInvalid

        return when (cmd) {
            // Device info, also the pre-login call. Answers with an empty session.
            1008 -> Response(200, """{"success":true,"cmd":1008,"board_type":"ZLT M90 PLUS",""" +
                """"fake_version":"1.12.8","domain_value":"$domainValue","language":"ar",""" +
                """"model_name":"MIFI"}""")

            // The token nonce. Issued before login; not a credential.
            232 -> Response(
                200,
                """{"success":true,"cmd":232,"buffer":"3","token":"$token","netx_login_time":"16079"}""",
            )

            100 -> {
                loginAttempts.incrementAndGet()
                val user = json.optString("username", "")
                val passwd = json.optString("passwd", "")
                val sentToken = json.optString("token", "")
                val error = when {
                    rejectsCredentials -> "wrong credentials"
                    user != wantUser -> "wrong username"
                    passwd != digestOf(wantPassword) -> "wrong passwd digest"
                    sentToken != token -> "missing or stale token"
                    else -> null
                }
                if (error != null) {
                    // The device's own refusal shape: no `result` key, an envelope goform never uses.
                    Response(200, """{"success":false,"cmd":100,"message":"$error"}""")
                } else {
                    Response(
                        200,
                        """{"success":true,"cmd":100,"user_level":"3","AUTH":"ok",""" +
                            """"sessionId":"$issuedSessionId"}""",
                    )
                }
            }

            else -> {
                if (!authenticated) {
                    unauthenticatedReads.incrementAndGet()
                    return Response(200, """{"success":false,"cmd":$cmd,"message":"not logged in"}""")
                }
                when (cmd) {
                    1005 -> Response(200, """{"success":true,"cmd":1005,"battery_status":"1",""" +
                        """"battery_capacity":"$batteryCapacity","battery_charge_status":"1",""" +
                        """"power_charger_status":"1","signal_lvl":"5","sim_status":"1",""" +
                        """"network_type_str":"4G+","ssid2G":"Ahmed-Mifi"}""")
                    1002 -> Response(200, """{"success":true,"cmd":1002,"network_type_str":"4G+",""" +
                        """"RSRP":"-80","RSRQ":"-13","RSSI":"-48","SINR":"11","signal_lvl":"5",""" +
                        """"network_operator":"Vodafone EG","PLMN":"60202","currentband":"1-41-3"}""")
                    1001 -> Response(200, """{"success":true,"cmd":1001,"board_type":"ZLT M90 PLUS",""" +
                        """"hwversion":"TZ7.823.512A","module_imei":"860540080045325",""" +
                        """"device_sn":"M90PLUSRO5011D7","lan_ip":"192.168.8.1","uptime":"123595",""" +
                        """"fake_version":"1.12.8"}""")
                    337 -> Response(200, """{"success":true,"cmd":337,"limitSwitch":"0",""" +
                        """"limitSize":"100","mon_download_flow":"24146.25","ul_mon_flow":"16743.73",""" +
                        """"dl_mon_flow":"7402.52"}""")
                    224 -> Response(200, """{"success":true,"cmd":224,"wlan24g_wifi_info":[""" +
                        """{"mac":"08:a5:c8:9e:67:98","rssi":"-61","ssid":"Ahmed-Mifi",""" +
                        """"ip":"192.168.8.103","user":"DESKTOP-OSD7G7A"},""" +
                        """{"mac":"8e:6a:1a:ea:e0:b2","rssi":"-40","ssid":"Ahmed-Mifi",""" +
                        """"ip":"192.168.8.130","user":"OPPO-F11"}]}""")
                    104 -> Response(200, """{"success":true,"cmd":104,"name":"","uptime":"123601.99"}""")
                    else -> Response(200, """{"success":true,"cmd":$cmd}""")
                }
            }
        }
    }

    private fun write(socket: Socket, response: Response) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ${response.status} OK\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out: OutputStream = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private data class Response(val status: Int, val body: String)

    companion object {
        fun sha256Hex(text: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            return md.digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}