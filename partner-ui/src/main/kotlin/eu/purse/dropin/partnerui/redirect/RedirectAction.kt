package eu.purse.dropin.partnerui.redirect

/**
 * What a `/validate` response is asking the client to do next, resolved by [RedirectionResolver].
 * Mirrors the three dispatch shapes `RedirectionHandler` exposes on web
 * (`usp-widget/packages/common/src/Redirection.ts`) — see
 * docs/architecture/redirect-handling.md#android-translation for the Android equivalent of each.
 */
sealed interface RedirectAction {
    /** Full-page GET navigation — Custom Tabs, cannot carry a POST body. */
    data class Get(val url: String) : RedirectAction

    /** POST navigation with form fields — needs a WebView (`postUrl()`); Custom Tabs cannot POST. */
    data class PostForm(val url: String, val formDataUrlEncoded: String) : RedirectAction

    /** The server sent literal `<form>` HTML to inject and auto-submit — WebView `loadDataWithBaseURL()`. */
    data class PostHtml(val html: String) : RedirectAction
}
