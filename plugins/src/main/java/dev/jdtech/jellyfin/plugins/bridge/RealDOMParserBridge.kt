package dev.jdtech.jellyfin.plugins.bridge

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RealDOMParserBridge : DOMParserBridge {

    private val elements = ConcurrentHashMap<String, Element>()

    override fun parse(html: String): String {
        val doc = Jsoup.parse(html)
        val handle = UUID.randomUUID().toString()
        elements[handle] = doc
        return handle
    }

    override fun querySelector(handle: String, selector: String): String? {
        val element = elements[handle] ?: return null
        val found = element.selectFirst(selector) ?: return null
        val newHandle = UUID.randomUUID().toString()
        elements[newHandle] = found
        return newHandle
    }

    override fun querySelectorAll(handle: String, selector: String): Array<String> {
        val element = elements[handle] ?: return emptyArray()
        val found = element.select(selector)
        return found.map {
            val newHandle = UUID.randomUUID().toString()
            elements[newHandle] = it
            newHandle
        }.toTypedArray()
    }

    override fun getAttribute(handle: String, attribute: String): String? {
        return elements[handle]?.attr(attribute)
    }

    override fun getTextContent(handle: String): String? {
        return elements[handle]?.text()
    }

    override fun getInnerHtml(handle: String): String? {
        return elements[handle]?.html()
    }

    override fun getOuterHtml(handle: String): String? {
        return elements[handle]?.outerHtml()
    }

    override fun closeHandle(handle: String) {
        elements.remove(handle)
    }

    fun clear() {
        elements.clear()
    }
}
