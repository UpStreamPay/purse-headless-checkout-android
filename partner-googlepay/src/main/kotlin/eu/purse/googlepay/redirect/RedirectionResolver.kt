package eu.purse.googlepay.redirect

import org.json.JSONObject

/**
 * Resolves what a `/validate` response is asking the client to do next, mirroring web's
 * `manageRedirection` (`usp-widget/packages/sdk/src/redirection/redirection-manager.ts`) — same
 * priority order, field names, and per-method scoping.
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
     * @param sessionFallbackUrl the session-level `redirection` field, used only for the
     * `should_submit_widget` fallback branch.
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
            .firstOrNull {
                it.optStringOrNull("partner") == partnerWireValue && it.optStringOrNull("method") == methodWireValue
            }
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

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null
}
