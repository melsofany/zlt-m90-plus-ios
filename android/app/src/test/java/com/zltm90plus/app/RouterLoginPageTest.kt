package com.zltm90plus.app

import com.zltm90plus.app.data.remote.RouterLoginPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Firmware 1.12.8 answers the configured goform path with 404, so the path has to come from the
 * device instead of from the app. Its own login page is the one source that cannot be wrong: the
 * page could not log anyone in if it named the wrong endpoint.
 */
class RouterLoginPageTest {

    @Test
    fun `the endpoint is read from a form action`() {
        val html = """
            <html><body>
              <form id="loginForm" action="/goform/goform_set_cmd_process" method="post">
                <input name="user" type="text"/>
                <input name="password" type="password"/>
              </form>
            </body></html>
        """.trimIndent()

        val endpoint = RouterLoginPage.parse(html)

        assertEquals("/goform/goform_set_cmd_process", endpoint?.path)
        assertEquals("user", endpoint?.userField)
        assertEquals("password", endpoint?.passwordField)
    }

    /** A page that names the endpoint without a leading slash still needs one to build a URL. */
    @Test
    fun `a path without a leading slash is given one`() {
        val html = """<script>var url = "goform/goform_set_cmd_process";</script>"""

        assertEquals("/goform/goform_set_cmd_process", RouterLoginPage.parse(html)?.path)
    }

    @Test
    fun `a nested prefix from the page is kept`() {
        val html = """<form action="/cgi-bin/goform/goform_set_cmd_process" method="post">"""

        assertEquals("/cgi-bin/goform/goform_set_cmd_process", RouterLoginPage.parse(html)?.path)
    }

    @Test
    fun `field names stated by the page are taken from it`() {
        val html = """
            <form action="/goform/goform_set_cmd_process">
              <input name="username" type="text"/>
              <input name="passwd" type="password"/>
            </form>
        """.trimIndent()

        val endpoint = RouterLoginPage.parse(
            html,
            RouterLoginPage.Defaults(userField = "user", passwordField = "password"),
        )

        assertEquals("username", endpoint?.userField)
        assertEquals("passwd", endpoint?.passwordField)
    }

    @Test
    fun `field names the page omits fall back to the configured defaults`() {
        val html = """<form action="/goform/goform_set_cmd_process"></form>"""

        val endpoint = RouterLoginPage.parse(
            html,
            RouterLoginPage.Defaults(userField = "user", passwordField = "password"),
        )

        assertEquals("user", endpoint?.userField)
        assertEquals("password", endpoint?.passwordField)
    }

    /** This firmware encodes the password before posting it; a page that says so is honoured. */
    @Test
    fun `base64 is detected when the page mentions it`() {
        val html = """
            <form action="/goform/goform_set_cmd_process">
              <script>form.password.value = base64(password);</script>
            </form>
        """.trimIndent()

        assertTrue(RouterLoginPage.parse(html)?.passwordBase64 == true)
    }

    @Test
    fun `a page that does not mention base64 is taken at its word`() {
        val html = """<form action="/goform/goform_set_cmd_process"></form>"""

        assertFalse(RouterLoginPage.parse(html)?.passwordBase64 == true)
    }

    /** Nothing is invented when the page does not name an endpoint. */
    @Test
    fun `a page that names no endpoint yields nothing`() {
        assertNull(RouterLoginPage.parse("<html><body>hello</body></html>"))
    }
}