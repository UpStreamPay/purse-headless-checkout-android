package eu.purse.googlepay.redirect

/**
 * What a `/validate` response is asking the client to do next, resolved by [RedirectionResolver].
 * Mirrors the three dispatch shapes web's `RedirectionHandler` exposes
 * (`usp-widget/packages/common/src/Redirection.ts`).
 */
sealed interface RedirectAction {
    /** Full-page GET navigation — Custom Tabs, cannot carry a POST body. */
    data class Get(val url: String) : RedirectAction

    /** POST navigation with form fields — needs a WebView; Custom Tabs cannot POST. */
    data class PostForm(val url: String, val formDataUrlEncoded: String) : RedirectAction

    /** The server sent literal `<form>` HTML to inject and auto-submit — WebView `loadDataWithBaseURL`. */
    data class PostHtml(val html: String) : RedirectAction
}
