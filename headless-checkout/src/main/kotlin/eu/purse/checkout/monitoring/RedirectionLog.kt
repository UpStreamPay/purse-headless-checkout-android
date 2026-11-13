// Web: packages/headless-checkout/src/utils/redirection-log.ts (`getRedirectionHostname`, `logRedirection`)
// Same: `HEADLESS_CHECKOUT_REDIRECTION`, Widget V3's payload (`url` / `action` / `string_form`, `delegated`),
//       hostname only, `couldn't parse the url`, never throws.
// Differs: posted once the `onRedirection` hook has answered, since an Android hook can decline.
package eu.purse.checkout.monitoring

import eu.purse.partnerui.redirect.RedirectAction
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import org.json.JSONObject

/** Logged in place of a hostname when the destination is not an absolute URL. Same text as Widget V3. */
internal const val UNPARSEABLE_REDIRECTION_HOST = "couldn't parse the url"

/**
 * `scheme://authority`, the only part of a URL the hostname is read from. Cutting the path, query
 * and fragment off first means a character [URI] rejects there (a raw space, a `|`) cannot turn a
 * readable host into [UNPARSEABLE_REDIRECTION_HOST], which web's lenient `URL` would not do either.
 */
private val SCHEME_AND_AUTHORITY = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)")

/**
 * The hostname only: the path, query string and fragment can carry payment or shopper data, and the
 * authority's user info and port are dropped too. [UNPARSEABLE_REDIRECTION_HOST] when [url] is not an
 * absolute URL with a host.
 */
internal fun redirectionHostname(url: String): String {
    val match = SCHEME_AND_AUTHORITY.find(url.trim()) ?: return UNPARSEABLE_REDIRECTION_HOST
    val (scheme, authority) = match.destructured
    val host = try {
        URI("$scheme://$authority").host
    } catch (_: URISyntaxException) {
        null
    }
    return host?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT) ?: UNPARSEABLE_REDIRECTION_HOST
}

/**
 * The `HEADLESS_CHECKOUT_REDIRECTION` payload for [action]: exactly one of `url` ([RedirectAction.Get]),
 * `action` ([RedirectAction.PostForm]) — both a hostname — or `string_form: true`
 * ([RedirectAction.PostHtml], an HTML form with no URL to read), plus [delegated]. Never the form
 * fields nor the HTML.
 */
internal fun redirectionPayload(action: RedirectAction, delegated: Boolean): JSONObject {
    val payload = when (action) {
        is RedirectAction.Get -> monitoringPayload("url" to redirectionHostname(action.url))
        is RedirectAction.PostForm -> monitoringPayload("action" to redirectionHostname(action.url))
        is RedirectAction.PostHtml -> monitoringPayload("string_form" to true)
    }
    return payload.put("delegated", delegated)
}

/**
 * Posts `HEADLESS_CHECKOUT_REDIRECTION`. Synchronous and never throws, so the redirection that
 * follows is neither delayed nor blocked by monitoring.
 */
internal fun CheckoutEventBus.postRedirection(action: RedirectAction, delegated: Boolean) {
    runCatching { post(CheckoutEventCode.HEADLESS_CHECKOUT_REDIRECTION, EventLevel.INFO, redirectionPayload(action, delegated)) }
}
