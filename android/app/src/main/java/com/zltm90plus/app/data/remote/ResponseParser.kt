package com.zltm90plus.app.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Turns a raw response body into a [ResponseNode] tree, supporting both JSON and XML because
 * ZLT firmware uses one or the other depending on version and carrier build.
 */
object ResponseParser {

    fun parse(body: String, encoding: String? = null): ResponseNode? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        val looksLikeXml = trimmed.startsWith("<") && !trimmed.startsWith("<!DOCTYPE html", ignoreCase = true)
        val preferXml = encoding.equals("xml", ignoreCase = true)

        return if (preferXml || looksLikeXml) {
            parseXml(trimmed) ?: ResponseNode.ofJson(trimmed)
        } else {
            ResponseNode.ofJson(trimmed) ?: parseXml(trimmed)
        }
    }

    /** Extracts the session cookie from `Set-Cookie` headers without logging its value. */
    fun extractSessionToken(headers: Map<String, List<String>>): String? =
        extractSessionCookie(headers)

    /**
     * Builds a valid `Cookie:` header value from `Set-Cookie` response headers.
     *
     * The previous implementation returned the whole `Set-Cookie` string, attributes included
     * (`sysauth=abc; path=/; HttpOnly`). That is not a legal request header, and a router that
     * validates its session cookie rejects it, so every authenticated request after login failed.
     * Only the `name=value` pairs are kept here, joined with `; `.
     */
    fun extractSessionCookie(headers: Map<String, List<String>>): String? {
        val cookieHeaders = headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
        if (cookieHeaders.isEmpty()) return null

        val pairs = cookieHeaders.mapNotNull { header ->
            val pair = header.split(";").first().trim()
            val name = pair.substringBefore('=', missingDelimiterValue = "").trim()
            if (pair.contains('=') && name.isNotEmpty() && !name.startsWith("$")) pair else null
        }
        return pairs.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun parseXml(xml: String): ResponseNode? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // Local, trusted-ish device XML: block external entity resolution anyway.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = document.documentElement ?: return null
        elementToNode(root)
    }.getOrNull()

    private fun elementToNode(element: Element): ResponseNode {
        val childElements = element.childNodes.let { nodes ->
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        }
        if (childElements.isEmpty()) {
            return ResponseNode.ofScalar(element.textContent.orEmpty())
        }

        val grouped = LinkedHashMap<String, MutableList<ResponseNode>>()
        childElements.forEach { child ->
            grouped.getOrPut(child.tagName) { mutableListOf() } += elementToNode(child)
        }

        val values = LinkedHashMap<String, ResponseNode>()
        grouped.forEach { (tag, nodes) ->
            values[tag] = if (nodes.size == 1) nodes.first() else ResponseNode.ofArray(nodes)
        }

        val directText = element.childNodes.let { nodes ->
            (0 until nodes.length)
                .mapNotNull { nodes.item(it) }
                .filter { it.nodeType == org.w3c.dom.Node.TEXT_NODE }
                .joinToString("") { it.nodeValue.orEmpty() }
        }.trim()
        if (directText.isNotEmpty()) values["#text"] = ResponseNode.ofScalar(directText)

        return ResponseNode.ofObject(values)
    }

    /** Convenience for building a synthetic tree in tests and the mock API. */
    fun of(json: String): ResponseNode? = ResponseNode.ofJson(json)

    fun of(value: JSONObject): ResponseNode = ResponseParser.of(value.toString())!!
    fun of(value: JSONArray): ResponseNode = ResponseParser.of(value.toString())!!
}
