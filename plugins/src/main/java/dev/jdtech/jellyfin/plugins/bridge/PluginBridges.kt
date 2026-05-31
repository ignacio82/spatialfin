package dev.jdtech.jellyfin.plugins.bridge

/**
 * Bridge for HTTP requests from JavaScript plugins.
 */
interface HttpBridge {
    // Returns a JSON string of HttpResponse
    fun request(method: String, url: String, headersJson: String, body: String?, usePlatformAuth: Boolean): String
}

/**
 * Bridge for HTML/XML parsing from JavaScript plugins.
 */
interface DOMParserBridge {
    fun parse(html: String): String // Returns a handle (UUID) to the document
    fun querySelector(handle: String, selector: String): String? // Returns handle
    fun querySelectorAll(handle: String, selector: String): Array<String> // Returns list of handles
    fun getAttribute(handle: String, attribute: String): String?
    fun getTextContent(handle: String): String?
    fun getInnerHtml(handle: String): String?
    fun getOuterHtml(handle: String): String?
    fun closeHandle(handle: String)
}

/**
 * Bridge for utility functions from JavaScript plugins.
 */
interface UtilitiesBridge {
    fun uuid(): String
    fun base64Encode(data: String): String
    fun base64EncodeBytes(data: IntArray): String
    fun base64Decode(data: String): String
    fun md5String(data: String): String
    fun log(message: String)
}
