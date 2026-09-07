// Web: `manageRedirection` action shapes (GET url, POST form, HTML)
// Same: same three shapes.
// Differs: carries the session return URL as a completion hint for the WebView.
package eu.purse.partnerui.redirect

import java.net.URLDecoder
import java.util.Base64
import org.json.JSONObject

/**
 * What a `/validate` response is asking the client to do next, resolved by [RedirectionResolver].
 * Mirrors the three dispatch shapes web's `RedirectionHandler` exposes
 * (`usp-widget/packages/common/src/Redirection.ts`).
 *
 * [returnUrlPrefix] — when set, [RedirectionCheckoutActivity] treats navigating to a URL starting
 * with it as the flow's completion (in addition to this app's own deep-link scheme), so it can close
 * itself instead of stranding the shopper on that page. Not resolved by [RedirectionResolver] itself
 * (it has no notion of the session) — the caller attaches it, typically from the session's own
 * configured return URL. See [withReturnUrlPrefix].
 */
sealed interface RedirectAction {

    val returnUrlPrefix: String?

    /** Full-page GET navigation — rendered in an in-app WebView so its navigation can be watched. */
    data class Get(val url: String, override val returnUrlPrefix: String? = null) : RedirectAction

    /** POST navigation with form fields — needs a WebView; there is no POST-capable system browser. */
    data class PostForm(
        val url: String,
        val formDataUrlEncoded: String,
        override val returnUrlPrefix: String? = null,
    ) : RedirectAction

    /** The server sent literal `<form>` HTML to inject and auto-submit — WebView `loadDataWithBaseURL`. */
    data class PostHtml(val html: String, override val returnUrlPrefix: String? = null) : RedirectAction
}

/** Attaches [returnUrlPrefix] to any [RedirectAction] variant — see its doc. */
fun RedirectAction.withReturnUrlPrefix(returnUrlPrefix: String?): RedirectAction = when (this) {
    is RedirectAction.Get -> copy(returnUrlPrefix = returnUrlPrefix)
    is RedirectAction.PostForm -> copy(returnUrlPrefix = returnUrlPrefix)
    is RedirectAction.PostHtml -> copy(returnUrlPrefix = returnUrlPrefix)
}

/**
 * Web's `purse-redirection-data` query parameter marks the widget's own confirmation-page redirect —
 * it carries the payment's signed final result (a JWT) for a browser to read. Its mere presence means
 * a URL is never a genuine PSP/bank redirect, regardless of host/path — a stronger, session-data-free
 * signal than comparing against the session's own configured return URL (which may be absent), used
 * as a second check alongside it wherever that URL is examined ([RedirectionResolver],
 * [RedirectionCheckoutActivity]).
 */
internal fun String.isWidgetConfirmationRedirect(): Boolean = contains("purse-redirection-data")

/**
 * Best-effort `payment_id` out of the widget-confirmation redirect's `purse-redirection-data` JWT
 * (unsigned read — the JWT arrived over the same authenticated `/validate` response we already
 * trust, so no signature check is needed just to read a field out of it). Confirmed against a real
 * decoded payload: `{"client_session_status":"SUBMITTED","payment_id":"…","authorization_status":"AUTHORIZED"}`
 * — exactly the id the orchestration API's `POST /v2/payments/{id}/captures` needs. `null` if this
 * isn't a widget-confirmation URL, or it carries no `payment_id`.
 */
internal fun String.widgetConfirmationPaymentId(): String? {
    val marker = "purse-redirection-data="
    val start = indexOf(marker)
    if (start < 0) return null
    val rawValue = substring(start + marker.length).substringBefore('&')
    return try {
        val jwt = URLDecoder.decode(rawValue, "UTF-8")
        val payloadSegment = jwt.split(".").getOrNull(1) ?: return null
        val padded = payloadSegment + "=".repeat((4 - payloadSegment.length % 4) % 4)
        val payloadJson = String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        JSONObject(payloadJson).let { if (it.has("payment_id") && !it.isNull("payment_id")) it.getString("payment_id") else null }
    } catch (e: Exception) {
        null
    }
}
