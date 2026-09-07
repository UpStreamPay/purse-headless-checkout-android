// Web: `manageRedirection` POST-form case
// Same: auto-submitting form with the PSP's fields.
// Differs: rendered into the WebView as HTML.
package eu.purse.partnerui.redirect

import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Mirrors web's `submitRedirectionForm` (`usp-widget/packages/common/src/Redirection.ts`): the POST
 * data carried by [RedirectAction.PostForm] is JSON **or** a query string — parse it into key/value
 * pairs and build a real, auto-submitting hidden `<form>`, the way a browser would.
 *
 * **Do not POST the raw data string as the request body** (e.g. `WebView.postUrl`) — that sends
 * malformed data (raw JSON under `application/x-www-form-urlencoded`) and 3-D Secure rejects it with
 * HTTP 400. Loading this HTML via `WebView.loadDataWithBaseURL()` and letting the page's own
 * `<form>.submit()` fire is what works.
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
     * Try JSON first, flattening one level of nested objects into `key[subKey]` field names (mirrors
     * web's nested-object handling); otherwise treat [dataString] as a url-encoded query string.
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
