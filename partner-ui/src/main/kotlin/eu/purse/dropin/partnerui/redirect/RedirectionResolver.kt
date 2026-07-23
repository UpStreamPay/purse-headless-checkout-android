package eu.purse.dropin.partnerui.redirect

import org.json.JSONObject

/**
 * Resolves what a `/validate` response is asking the client to do next, mirroring web's
 * `manageRedirection` (`usp-widget/packages/sdk/src/redirection/redirection-manager.ts:14-38`)
 * exactly — same priority order, same field names, same per-method scoping. See
 * docs/architecture/redirect-handling.md#the-redirect-contract for the full contract this ports.
 *
 * A `null` result means no redirect is required — the common case (see
 * docs/architecture/redirect-handling.md#what-no-redirect-means-for-a-mobile-sdk): the caller
 * should just resolve the payment result to the host app, not treat `null` as an error.
 *
 * Shared by every method's mapper in this module (`PaypalWalletMapper`, `GooglePayMapper`,
 * `CardMapper`) — lives here, not in `:dropin`, because the dependency direction is `:dropin` →
 * `:partner-ui`, not the other way around (see docs/internal/project-setup.md).
 */
object RedirectionResolver {

    /**
     * @param validateResponse the raw `/validate` response body — `{results, redirection,
     * global_status}`.
     * @param partnerWireValue the wire value of the method this redirect decision is for (e.g.
     * `"adyen"`), used to find this method's own entry in `results` — mirrors web's
     * `getPartnerMethodFromList`, matching on partner **and** method when both are given.
     * @param methodWireValue the method wire value (e.g. `"googlepay"`).
     * @param sessionFallbackUrl the session-level `redirection` field (`LegacyPaymentSession.redirection`),
     * used only for the `should_submit_widget` fallback branch — see
     * docs/architecture/redirect-handling.md#the-redirect-contract, priority step 4.
     */
    fun resolve(
        validateResponse: JSONObject,
        partnerWireValue: String,
        methodWireValue: String,
        sessionFallbackUrl: String?,
    ): RedirectAction? {
        val results = validateResponse.optJSONArray("results")
        val pluginLogs = (0 until (results?.length() ?: 0))
            .map { results!!.getJSONObject(it) }
            .firstOrNull { it.optStringOrNull("partner") == partnerWireValue && it.optStringOrNull("method") == methodWireValue }
            ?.optJSONObject("plugin_result")
            ?.optJSONObject("logs")

        val redirectionJson = pluginLogs?.optStringOrNull("redirection_json")
        val redirectionUrlFromLogs = pluginLogs?.optStringOrNull("redirection_url")
        val redirectUrlPostParams = pluginLogs?.optStringOrNull("redirect_url_post_params")

        // 1. Per-item POST form.
        if (redirectionJson != null && redirectionUrlFromLogs != null) {
            return RedirectAction.PostForm(redirectionUrlFromLogs, redirectionJson)
        }

        val redirection = validateResponse.optJSONObject("redirection")
        val globalUrl = redirection?.optStringOrNull("url")

        // 2. Global URL redirect — GET, or POST with the per-item post params if also present.
        if (globalUrl != null) {
            return if (redirectUrlPostParams != null) {
                RedirectAction.PostForm(globalUrl, redirectUrlPostParams)
            } else {
                RedirectAction.Get(globalUrl)
            }
        }

        // 3. Raw HTML form string.
        val globalForm = redirection?.optStringOrNull("form")
        if (globalForm != null) {
            return RedirectAction.PostHtml(globalForm)
        }

        // 4. should_submit_widget fallback.
        val shouldSubmitWidget = redirection?.optBoolean("should_submit_widget") ?: false
        if (shouldSubmitWidget && sessionFallbackUrl != null) {
            return RedirectAction.Get(sessionFallbackUrl)
        }

        return null
    }

    /** Local null-safe string read (the `:dropin` JsonExtensions aren't visible from here). */
    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null
}
