package eu.purse.dropin.partnerui.generic.redirection

import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Mirrors web's `submitRedirectionForm` (`usp-widget/packages/common/src/Redirection.ts:10-46`)
 * exactly: the POST data string carried by
 * [eu.purse.dropin.partnerui.redirect.RedirectAction.PostForm] is JSON **or** a query string (the
 * field name `formDataUrlEncoded` is a slight misnomer inherited from the web reference, not a
 * promise it's already url-encoded) — parse it into key/value pairs and build a real,
 * auto-submitting hidden `<form>`, the same way a browser would.
 *
 * **Do not POST the raw data string itself as the request body** (e.g. via `WebView.postUrl`) —
 * that was this module's first implementation, and it sends the far end malformed data (raw JSON
 * bytes under an `application/x-www-form-urlencoded` content type) that gets rejected with an
 * HTTP 400 — confirmed against a real Google Pay 3DS sandbox redirect (Google's `3ds_visa` test
 * card). Loading the HTML this builds via `WebView.loadDataWithBaseURL()` and letting the page's
 * own `<form>.submit()` fire is what actually works, matching web byte for byte.
 */
object FormRedirectHtmlBuilder {

    fun buildAutoSubmitFormHtml(actionUrl: String, dataString: String): String {
        val fields = parseFormFields(dataString)
        val inputs = fields.entries.joinToString("\n") { (name, value) ->
            "<input type=\"hidden\" name=\"${escapeHtmlAttribute(name)}\" value=\"${escapeHtmlAttribute(value)}\">"
        }
        return """
            <html><body onload="document.forms[0].submit()">
            <form method="POST" action="${escapeHtmlAttribute(actionUrl)}">
            $inputs
            </form>
            </body></html>
        """.trimIndent()
    }

    /**
     * Mirrors web's `getObjectFromString`: try JSON first, flattening one level of nested objects
     * into `key[subKey]` field names (mirrors web's nested-object handling in
     * `submitRedirectionForm`); otherwise treat [dataString] as a url-encoded query string.
     */
    fun parseFormFields(dataString: String): Map<String, String> {
        return try {
            val json = JSONObject(dataString)
            val result = LinkedHashMap<String, String>()
            json.keys().forEach { key ->
                when (val value = json.get(key)) {
                    is JSONObject -> value.keys().forEach { subKey -> result["$key[$subKey]"] = value.get(subKey).toString() }
                    else -> result[key] = value.toString()
                }
            }
            result
        } catch (e: JSONException) {
            dataString.split("&").filter { it.isNotBlank() }.associate { pair ->
                val parts = pair.split("=", limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
        }
    }

    private fun escapeHtmlAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
