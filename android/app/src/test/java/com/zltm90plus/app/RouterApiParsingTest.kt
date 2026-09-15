package com.zltm90plus.app

import com.zltm90plus.app.data.remote.RouterRoutesConfig
import com.zltm90plus.app.data.remote.ResponseParser
import com.zltm90plus.app.data.remote.normalizeNetworkType
import com.zltm90plus.app.data.remote.signalLevelFromDbm
import com.zltm90plus.app.data.remote.signalLevelFromPercent
import com.zltm90plus.app.data.model.SignalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterRoutesConfigTest {

    private val sample = """
        {
          "version": 7,
          "requestTimeoutSeconds": 20,
          "encoding": "xml",
          "routes": {
            "battery": { "path": "/battery", "method": "get" },
            "login": {
              "path": "/login",
              "method": "POST",
              "body": { "user": "{username}", "pass": "{password}" },
              "successIndicators": ["ok"],
              "failureIndicators": ["denied"]
            }
          },
          "fields": { "batteryPercent": ["battery", "level"] },
          "genericAliases": { "true": ["1"], "false": ["0"] }
        }
    """.trimIndent()

    @Test
    fun `parses routes and fields`() {
        val config = RouterRoutesConfig.parse(sample)
        assertEquals(7, config.version)
        assertEquals(20, config.requestTimeoutSeconds)
        assertEquals("xml", config.encoding)
        assertEquals("/battery", config.route("battery")?.path)
        assertEquals("GET", config.route("battery")?.method)
        assertEquals(listOf("ok"), config.route("login")?.successIndicators)
        assertEquals("{username}", config.route("login")?.bodyTemplate?.get("user"))
        assertEquals(listOf("battery", "level"), config.aliases("batteryPercent"))
    }

    @Test
    fun `missing route returns null instead of throwing`() {
        val config = RouterRoutesConfig.parse(sample)
        assertNull(config.route("dataUsage"))
        assertTrue(config.aliases("unknownField").isEmpty())
    }

    @Test
    fun `empty config is safe`() {
        assertNull(RouterRoutesConfig.EMPTY.route("login"))
        assertEquals(12, RouterRoutesConfig.EMPTY.requestTimeoutSeconds)
    }
}

class ResponseParserTest {

    private val config = RouterRoutesConfig.parse(
        """{"fields":{"batteryPercent":["battery","level"]},"genericAliases":{"true":["1","yes"],"false":["0","no"]}}""",
    )

    @Test
    fun `parses nested json`() {
        val node = ResponseParser.parse("""{"data":{"battery":78,"charging":"yes"}}""")!!
        assertEquals(78, node.findInt(config.aliases("batteryPercent")))
    }

    @Test
    fun `parses flat json`() {
        val node = ResponseParser.parse("""{"battery":42}""")!!
        assertEquals(42, node.findInt(config.aliases("batteryPercent")))
    }

    @Test
    fun `parses xml payloads`() {
        val xml = "<response><battery>65</battery><charging>0</charging></response>"
        val node = ResponseParser.parse(xml, "xml")!!
        assertEquals(65, node.findInt(config.aliases("batteryPercent")))
    }

    @Test
    fun `missing field returns null rather than a default`() {
        val node = ResponseParser.parse("""{"unrelated":true}""")!!
        assertNull(node.findInt(config.aliases("batteryPercent")))
        assertNull(node.findString(listOf("carrier")))
    }

    @Test
    fun `value with unit suffix is parsed as bytes`() {
        val node = ResponseParser.parse("""{"total_traffic":"3.8 GB"}""")!!
        assertEquals(3, node.findInt(listOf("total_traffic")))
    }

    @Test
    fun `percentage strings are parsed`() {
        val node = ResponseParser.parse("""{"signal_strength":"72%"}""")!!
        assertEquals(72, node.findInt(listOf("signal_strength")))
    }

    @Test
    fun `array extraction finds device list`() {
        val json = """{"device_list":[{"mac":"AA:BB","ip":"192.168.0.2"},{"mac":"CC:DD"}]}"""
        val node = ResponseParser.parse(json)!!
        val items = node.findArray(listOf("device_list"))
        assertNotNull(items)
        assertEquals(2, items!!.size)
        assertEquals("AA:BB", items[0].findString(listOf("mac")))
    }

    @Test
    fun `cookie header token extraction ignores attributes`() {
        val token = ResponseParser.extractSessionToken(
            mapOf("Set-Cookie" to listOf("sysauth=abc123; path=/; HttpOnly")),
        )
        assertEquals("sysauth=abc123", token)
    }

    @Test
    fun `malformed payload returns null instead of throwing`() {
        assertNull(ResponseParser.parse("not json and not xml"))
    }
}

class SignalMappingTest {

    @Test
    fun `percent buckets map correctly`() {
        assertEquals(SignalLevel.NONE, signalLevelFromPercent(0))
        assertEquals(SignalLevel.WEAK, signalLevelFromPercent(20))
        assertEquals(SignalLevel.FAIR, signalLevelFromPercent(45))
        assertEquals(SignalLevel.GOOD, signalLevelFromPercent(70))
        assertEquals(SignalLevel.EXCELLENT, signalLevelFromPercent(90))
    }

    @Test
    fun `dbm buckets map correctly`() {
        assertEquals(SignalLevel.EXCELLENT, signalLevelFromDbm(-70))
        assertEquals(SignalLevel.GOOD, signalLevelFromDbm(-95))
        assertEquals(SignalLevel.FAIR, signalLevelFromDbm(-105))
        assertEquals(SignalLevel.WEAK, signalLevelFromDbm(-115))
        assertEquals(SignalLevel.NONE, signalLevelFromDbm(-130))
        assertEquals(SignalLevel.UNKNOWN, signalLevelFromDbm(0))
    }

    @Test
    fun `network type is normalized`() {
        assertEquals("LTE", normalizeNetworkType("lte"))
        assertEquals("LTE", normalizeNetworkType("4G"))
        assertEquals("5G", normalizeNetworkType("nr"))
        assertEquals("3G", normalizeNetworkType("HSPA"))
        assertEquals("2G", normalizeNetworkType("EDGE"))
        assertNull(normalizeNetworkType("  "))
        assertNull(normalizeNetworkType(null))
    }
}