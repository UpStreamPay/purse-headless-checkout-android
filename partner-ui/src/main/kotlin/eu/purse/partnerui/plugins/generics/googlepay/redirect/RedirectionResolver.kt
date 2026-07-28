package eu.purse.partnerui.plugins.generics.googlepay.redirect

import org.json.JSONObject

/**
 * Resolves what a `/validate` response is asking the client to do next for genuine, must-follow
 * redirects (3-D Secure / ACS) — mirrors web's `manageRedirection`
 * (`usp-widget/packages/sdk/src/redirection/redirection-manager.ts`) for its per-item and global-URL
 * cases, same priority order, field names, and per-method scoping.
 *
 * Deliberately **omits** web's `should_submit_widget` fallback (a "resubmit the widget's own page"
 * signal — meaningless without a hosted web page). Web's own headless-checkout agrees: it gates that
 * exact fallback out whenever the split has a primary (`headless-checkout-validation.ts`,
 * `postValidateItems` — `if (!hasPrimary || !data.results.length)`), and Google Pay always is one.
 *
 * The same "just navigate the browser to the widget's own confirmation page" shape can also arrive as
 * a plain global `redirection.url` (confirmed against a real `/validate` response: a
 * `https://…/widgets/confirmation?purse-redirection-data=<jwt>` URL — that route belongs to
 * `usp-widget/sites/demo`, not any bank/ACS domain, and its JWT decodes to the same
 * `authorization_status` the SAME `/validate` call already returned in `global_status`). So [resolve]
 * also drops any target that either starts with [sessionReturnUrl] (the session's own configured
 * return URL) or carries the `purse-redirection-data` marker itself
 * ([String.isWidgetConfirmationRedirect] — session-data-independent, so it still catches this even
 * when [sessionReturnUrl] is unavailable) — neither is ever a genuine PSP redirect.
 *
 * A `null` result means no redirect is required (the common case) — the caller should resolve the
 * payment result, not treat `null` as an error.
 */
object RedirectionResolver {

    /**
     * @param validateResponse the raw `/validate` body — `{results, redirection, global_status}`
     * (available as `ValidationResult.raw`).
     * @param partnerWireValue / [methodWireValue] identify this method's own entry in `results`
     * (mirrors web's `getPartnerMethodFromList`).
     * @param sessionReturnUrl the session's own configured return URL
     * (`GooglePayHost.redirectionReturnUrl`) — a resolved target starting with it is suppressed (see
     * class doc), not returned for the caller to follow.
     */
    fun resolve(
        validateResponse: JSONObject,
        partnerWireValue: String,
        methodWireValue: String,
        sessionReturnUrl: String? = null,
    ): RedirectAction? {
        val pluginLogs = pluginLogsFor(validateResponse, partnerWireValue, methodWireValue)

        val redirectionJson = pluginLogs?.optStringOrNull("redirection_json")
        val redirectionUrlFromLogs = pluginLogs?.optStringOrNull("redirection_url")
        val redirectUrlPostParams = pluginLogs?.optStringOrNull("redirect_url_post_params")
        val redirection = validateResponse.optJSONObject("redirection")
        val globalUrl = redirection?.optStringOrNull("url")
        val globalForm = redirection?.optStringOrNull("form")

        val action = if (redirectionJson != null && redirectionUrlFromLogs != null) {
            // 1. Per-item POST form.
            RedirectAction.PostForm(redirectionUrlFromLogs, redirectionJson)
        } else if (globalUrl != null) {
            // 2. Global URL redirect — GET, or POST with the per-item post params if also present.
            if (redirectUrlPostParams != null) {
                RedirectAction.PostForm(globalUrl, redirectUrlPostParams)
            } else {
                RedirectAction.Get(globalUrl)
            }
        } else if (globalForm != null) {
            // 3. Raw HTML form string.
            RedirectAction.PostHtml(globalForm)
        } else {
            null
        }

        return action.takeUnless {
            val target = it?.targetUrl() ?: return@takeUnless false
            (sessionReturnUrl != null && target.startsWith(sessionReturnUrl)) || target.isWidgetConfirmationRedirect()
        }
    }

    /**
     * Best-effort `payment_id` off the widget-confirmation redirect (see class doc) — present
     * whether or not [resolve] ends up suppressing that same redirect, since this reads it directly
     * off the `/validate` response rather than by following anything. `null` if neither the per-item
     * `redirection_url` nor the global `redirection.url` carries one.
     *
     * [partnerWireValue]/[methodWireValue] identify the per-item entry to look in and both default
     * to `""` — omit them for a split with no interactive primary at all (secondaries alone), where
     * there is no partner item to key on and only the global `redirection.url` can carry the id.
     */
    fun extractWidgetConfirmationPaymentId(
        validateResponse: JSONObject,
        partnerWireValue: String = "",
        methodWireValue: String = "",
    ): String? {
        val pluginLogs = pluginLogsFor(validateResponse, partnerWireValue, methodWireValue)
        val globalUrl = validateResponse.optJSONObject("redirection")?.optStringOrNull("url")
        return listOfNotNull(pluginLogs?.optStringOrNull("redirection_url"), globalUrl)
            .firstNotNullOfOrNull { it.widgetConfirmationPaymentId() }
    }

    private fun pluginLogsFor(
        validateResponse: JSONObject,
        partnerWireValue: String,
        methodWireValue: String,
    ): JSONObject? {
        val results = validateResponse.optJSONArray("results")
        return (0 until (results?.length() ?: 0))
            .map { results!!.getJSONObject(it) }
            .firstOrNull {
                it.optStringOrNull("partner") == partnerWireValue && it.optStringOrNull("method") == methodWireValue
            }
            ?.optJSONObject("plugin_result")
            ?.optJSONObject("logs")
    }

    private fun RedirectAction.targetUrl(): String? = when (this) {
        is RedirectAction.Get -> url
        is RedirectAction.PostForm -> url
        is RedirectAction.PostHtml -> null
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null
}
