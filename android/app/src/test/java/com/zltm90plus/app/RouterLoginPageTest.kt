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

    /**
     * The real shell the device serves: no form, no endpoint, only script tags. This is the page
     * that made the previous build keep its wrong path.
     */
    private val realShell = """
        <!DOCTYPE html><html lang=""><head><meta charset="utf-8"><title></title>
        <link href="css/app.css" rel="preload" as="style">
        <link href="js/app.js" rel="preload" as="script">
        <link href="js/chunk-vendors.js" rel="preload" as="script">
        </head><body><div id="app"></div>
        <script type="module" src="./assist-entry.js"></script>
        <script src="js/chunk-vendors.js"></script><script src="js/app.js"></script></body></html>
    """.trimIndent()

    @Test
    fun `a single page shell yields no endpoint on its own`() {
        assertNull("the shell has no form, so the HTML alone cannot answer", RouterLoginPage.parse(realShell))
    }

    @Test
    fun `the shells scripts are read, the device code before the framework`() {
        val scripts = RouterLoginPage.scriptSources(realShell)

        assertTrue(
            "both bundles must be found, with a leading slash so they are not glued to the port",
            scripts.containsAll(listOf("/js/app.js", "/js/chunk-vendors.js")),
        )
        assertTrue(
            "the device's own bundle must come first, the framework is megabytes of noise",
            scripts.indexOf("/js/app.js") < scripts.indexOf("/js/chunk-vendors.js"),
        )
    }

    /** A relative src is resolved against the page, and a CDN script is never followed. */
    @Test
    fun `relative sources are resolved and off host scripts are skipped`() {
        val html = """
            <script type="module" src="./assist-entry.js"></script>
            <script src="js/app.js"></script>
            <script src="https://cdn.example.com/vue.js"></script>
        """.trimIndent()

        val scripts = RouterLoginPage.scriptSources(html)

        assertTrue(scripts.contains("/assist-entry.js"))
        assertTrue(scripts.contains("/js/app.js"))
        assertTrue("an off-host bundle has no business being fetched", scripts.none { it.contains("cdn.") })
    }

    @Test
    fun `endpoints are read from a bundle`() {
        val bundle = """var api={login:"/cgi-bin/goform/goform_set_cmd_process",r:"/cgi-bin/goform/goform_get_cmd_process"};"""

        val paths = RouterLoginPage.endpointsInBundle(bundle)

        assertTrue(paths.contains("/cgi-bin/goform/goform_set_cmd_process"))
        assertTrue(paths.contains("/cgi-bin/goform/goform_get_cmd_process"))
        assertEquals("the login endpoint must be offered first", "/cgi-bin/goform/goform_set_cmd_process", paths[0])
    }

    /** A path assembled at runtime is not a path, and must never be treated as one. */
    @Test
    fun `a bundle that names no endpoint yields nothing`() {
        assertTrue(RouterLoginPage.endpointsInBundle("var x=1;fetch(prefix+cmd)").isEmpty())
    }

    @Test
    fun `relative asset paths are not mistaken for api endpoints`() {
        val bundle = """load("css/app.css");load("favicon.ico");load("js/chunk-vendors.js");"""

        assertTrue(RouterLoginPage.endpointsInBundle(bundle).isEmpty())
    }
}