package eu.purse.dropin.partnerui.generic.redirection

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens a generic redirect — Custom Tabs for a GET, a WebView for a POST-form or raw-HTML-form
 * case (Custom Tabs cannot POST) — and hands the outcome back as an Activity result once the
 * shopper returns via this app's own deep link. Launch it via [RedirectionCheckoutContract] —
 * never directly. See docs/architecture/redirect-handling.md#android-translation for why each
 * [eu.purse.dropin.partnerui.redirect.RedirectAction] variant maps to Custom Tabs vs. WebView.
 *
 * Unlike [eu.purse.dropin.partnerui.partner.paypal.wallet.PaypalCheckoutActivity], no partner SDK
 * owns the browser-switch here — this Activity opens the URL/form itself and relies purely on the
 * OS intent-filter match (declared in this module's manifest on `${applicationId}.redirect` — a
 * scheme distinct from PayPal's own `${applicationId}`, so the two return paths can never collide)
 * to be re-delivered here via [onNewIntent] when the shopper returns.
 *
 * A returned [RedirectionCheckoutResult.Approved] is only a hint that the shopper came back — the
 * caller must still re-call `/validate` to learn the real outcome, matching
 * docs/architecture/redirect-handling.md#the-return-path.
 */
class RedirectionCheckoutActivity : AppCompatActivity() {

    private var launched = false
    private var leftForBrowser = false
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        launched = savedInstanceState?.getBoolean(STATE_LAUNCHED) ?: false
        leftForBrowser = savedInstanceState?.getBoolean(STATE_LEFT) ?: false

        if (savedInstanceState == null) startRedirect()
    }

    private fun startRedirect() {
        launched = true
        when (intent.getStringExtra(RedirectionCheckoutContract.EXTRA_TYPE)) {
            RedirectionCheckoutContract.TYPE_GET -> {
                val url = intent.getStringExtra(RedirectionCheckoutContract.EXTRA_URL)
                if (url.isNullOrBlank()) {
                    finishWith(STATUS_FAILED, message = "missing redirect url")
                    return
                }
                CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
            }

            RedirectionCheckoutContract.TYPE_POST_FORM -> {
                val url = intent.getStringExtra(RedirectionCheckoutContract.EXTRA_URL)
                val formData = intent.getStringExtra(RedirectionCheckoutContract.EXTRA_FORM_DATA)
                if (url.isNullOrBlank() || formData == null) {
                    finishWith(STATUS_FAILED, message = "missing redirect url/form data")
                    return
                }
                val html = FormRedirectHtmlBuilder.buildAutoSubmitFormHtml(url, formData)
                renderWebView().loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }

            RedirectionCheckoutContract.TYPE_POST_HTML -> {
                val html = intent.getStringExtra(RedirectionCheckoutContract.EXTRA_HTML)
                if (html.isNullOrBlank()) {
                    finishWith(STATUS_FAILED, message = "missing redirect html")
                    return
                }
                renderWebView().loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }

            else -> finishWith(STATUS_FAILED, message = "unknown redirect action type")
        }
    }

    /**
     * A WebView can't natively navigate our own deep-link scheme (it isn't http/https) — when the
     * 3DS/redirect page finally navigates there, [WebViewClient.shouldOverrideUrlLoading]
     * intercepts it and fires a real `ACTION_VIEW` Intent instead, so the OS's normal
     * intent-filter matching (not the WebView) delivers the return — same mechanism the Custom
     * Tabs path relies on.
     *
     * Logs every URL the WebView navigates to (`Log.tag = "RedirectionCheckout"`) plus any
     * HTTP-level error response — `adb logcat -s RedirectionCheckout` shows the full redirect
     * chain. The final hop after a 3DS challenge (bank ACS → whatever notification/return URL the
     * backend configured for that challenge) is entirely outside this app's control; if it 401s
     * with "Unknown API Key", that's Purse's own API gateway (`usp-cf-api-gateway`) rejecting the
     * `api-key` query param on *that* URL, not something this redirect handler builds or can fix
     * client-side — see docs/decisions/pain-points-recap.md.
     */
    private fun renderWebView(): WebView {
        val view = WebView(this)
        view.settings.javaScriptEnabled = true
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == returnScheme) {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "onPageStarted: $url")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request.isForMainFrame) {
                    Log.w(TAG, "onReceivedHttpError: HTTP ${errorResponse.statusCode} on ${request.url}")
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    Log.w(TAG, "onReceivedError: ${error.description} on ${request.url}")
                }
            }
        }
        setContentView(view)
        return view
    }

    private val returnScheme: String get() = "$packageName.redirect"

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        // Any onNewIntent reaching here — beyond the initial launch — came through this
        // Activity's own scoped intent-filter (${applicationId}.redirect); that's the shopper
        // returning, full stop. What actually happened is for the caller's re-validate to learn.
        if (launched) finishWith(STATUS_APPROVED)
    }

    override fun onResume() {
        super.onResume()
        // No deep link ever arrived and we've already been out to the Custom Tab/WebView once →
        // the shopper dismissed it without completing. A real return fires onNewIntent first
        // (setting `delivered`), so this only fires for a genuine dismissal.
        if (launched && leftForBrowser && !delivered) {
            finishWith(STATUS_CANCELED)
        }
    }

    override fun onPause() {
        super.onPause()
        if (launched) leftForBrowser = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LAUNCHED, launched)
        outState.putBoolean(STATE_LEFT, leftForBrowser)
    }

    private fun finishWith(status: String, message: String? = null) {
        if (delivered) return
        delivered = true
        val data = Intent()
            .putExtra(RedirectionCheckoutContract.EXTRA_STATUS, status)
            .putExtra(RedirectionCheckoutContract.EXTRA_MESSAGE, message)
        setResult(RESULT_OK, data)
        finish()
    }

    companion object {
        const val STATUS_APPROVED = "approved"
        const val STATUS_CANCELED = "canceled"
        const val STATUS_FAILED = "failed"
        private const val STATE_LAUNCHED = "launched"
        private const val STATE_LEFT = "left_for_browser"
        private const val TAG = "RedirectionCheckout"
    }
}
