package com.zltm90plus.app

import com.zltm90plus.app.data.remote.RouterRedirect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The device answers an API request with a status line that repeats the request line, hiding a
 * normal redirect underneath. OkHttp rejects that before it builds a response, so the `Location`
 * is destroyed and can only be recovered by reading the bytes. These are the exact bytes from the
 * field log.
 */
class RouterRedirectTest {

    private fun reply(path: String, location: String) = buildString {
        append("$path HTTP/1.1 301 Moved Permanently\r\n")
        append("Server: GoAhead-Webs\r\n")
        append("Location: $location\r\n")
        append("Content-Length: 0\r\n")
        append("Connection: close\r\n\r\n")
    }

    @Test
    fun `the target is read from a status line that repeats the request line`() {
        val reply = reply("/goform/goform_set_cmd_process", "http://192.168.8.1:8080/index.html")

        assertEquals(
            "http://192.168.8.1:8080/index.html",
            RouterRedirect.location(reply),
        )
    }

    @Test
    fun `the target is resolved to a scheme and authority`() {
        val target = RouterRedirect.follow(
            base = "http://192.168.8.1",
            reply = reply("/goform/goform_set_cmd_process", "https://192.168.8.1:8443/login"),
        )

        assertEquals("https", target?.scheme)
        assertEquals("192.168.8.1:8443", target?.authority)
    }

    @Test
    fun `a relative target is resolved against the device`() {
        val target = RouterRedirect.follow(
            base = "http://192.168.8.1",
            reply = reply("/goform/goform_set_cmd_process", "/cgi-bin/luci"),
        )

        assertEquals("http", target?.scheme)
        assertEquals("192.168.8.1:80", target?.authority)
    }

    /**
     * A redirect is supplied by the device, so following it blindly would let a device point the
     * login at an address of its choosing and collect the router password.
     */
    @Test
    fun `a redirect off the local network is refused`() {
        assertNull(
            RouterRedirect.follow(
                base = "http://192.168.8.1",
                reply = reply("/goform/goform_set_cmd_process", "http://198.51.100.9/collect"),
            ),
        )
    }

    @Test
    fun `a reply that is not a redirect yields no target`() {
        assertNull(RouterRedirect.location("HTTP/1.1 200 OK\r\nServer: GoAhead\r\n\r\n"))
        assertNull(RouterRedirect.location("HTTP/1.1 404 Not Found\r\n\r\n"))
        assertNull(RouterRedirect.location(""))
    }

    /** The raw read is the only path that recovers a target OkHttp discarded, so it is exercised. */
    @Test
    fun `the target is read from a live reply over a plain socket`() {
        val device = FakeGoformServer().start()
        try {
            device.echoRequestLineAsStatus = true
            device.redirectTarget = "http://127.0.0.1:8080/admin"
            val authority = "${device.host}:${device.port}"

            val target = RouterRedirect.probeHttp(authority, "/goform/goform_set_cmd_process")

            assertEquals("the redirect named another port, so it must be read", "http", target?.scheme)
            assertEquals("127.0.0.1:8080", target?.authority)
        } finally {
            device.stop()
        }
    }
}