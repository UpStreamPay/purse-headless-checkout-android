// Web: none — web navigates the page
// Same: —
// Differs: in-app WebView with progress, first-page watchdog, network-failure surfacing, deep-link return.
package eu.purse.partnerui.redirect

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Opens a chained redirect (3-D Secure GET, or a POST-form / raw-HTML case) in an **in-app WebView**
 * — not a system Custom Tab, which cannot POST and gives no way to observe or react to its
 * navigation — and hands the outcome back as an Activity result once the shopper reaches any of:
 * this app's own deep link (`${applicationId}.redirect`), the flow's known [returnUrlPrefix], or a
 * URL carrying the [isWidgetConfirmationRedirect] marker (session-data-independent — catches the
 * widget's own confirmation-page hop even when [returnUrlPrefix] is unavailable). Launch it via
 * [RedirectionCheckoutContract], never directly.
 *
 * Caveat: some ACS/3-D Secure providers detect and refuse to render inside an embedded WebView (a
 * known anti-phishing measure some card networks require) where they'd happily render in a system
 * browser/Custom Tab. If a specific partner's challenge breaks here, that partner needs its own
 * redirect path rather than reverting this one.
 *
 * A returned [RedirectionCheckoutResult.Approved] only means the shopper reached one of those URLs —
 * the caller must still re-check the session status to learn the real outcome.
 */
class RedirectionCheckoutActivity : AppCompatActivity() {

    private var launched = false
    private var leftForBrowser = false
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        launched = savedInstanceState?.getBoolean(STATE_LAUNCHED) ?: false
        leftForBrowser = savedInstanceState?.getBoolean(STATE_LEFT) ?: false

        if (savedInstanceState == null) {
            startRedirect()
        } else {
            // Recreated after process death mid-challenge: the WebView (and any in-progress ACS
            // state) is gone, and re-POSTing the form would risk a double submit. Resolve to a
            // clean cancel so the caller returns to a defined state and the shopper can retry.
            finishWith(STATUS_CANCELED, message = "redirect interrupted by process death")
        }
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
                renderWebView().loadUrl(url)
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
     * A WebView can't natively navigate our own deep-link scheme (not http/https) — when the 3DS
     * page finally navigates there, [WebViewClient.shouldOverrideUrlLoading] fires a real
     * `ACTION_VIEW` Intent so the OS intent-filter (not the WebView) delivers the return. Navigating
     * to [returnUrlPrefix] instead (the session's own configured return URL — recognized when a
     * partner never redirects to this app's own scheme) finishes directly, without letting the
     * WebView actually load and strand the shopper on that page.
     * `adb logcat -s RedirectionCheckout` shows the full redirect chain.
     */
    private fun renderWebView(): WebView {
        val view = WebView(this)
        view.settings.javaScriptEnabled = true

        // Loading affordance: an indeterminate bar over the (initially blank) page, gone once the
        // first page renders — a 3DS chain can spend seconds on blank interstitials.
        val progress = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
        }
        val container = android.widget.FrameLayout(this).apply {
            addView(
                view,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                progress,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER,
                ),
            )
        }

        // Watchdog for a challenge page that never arrives at all (dead PSP host, blackholed
        // request): fail once nothing has rendered within the timeout. Disarmed by the first
        // finished page — after that, however long the shopper takes on the challenge is theirs.
        val timeoutHandler = android.os.Handler(mainLooper)
        val timeout = Runnable {
            Log.w(TAG, "no page rendered within ${FIRST_PAGE_TIMEOUT_MS}ms — failing the redirect")
            finishWith(STATUS_FAILED, message = "the payment page did not load")
        }
        timeoutHandler.postDelayed(timeout, FIRST_PAGE_TIMEOUT_MS)

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == returnScheme) {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    return true
                }
                if (isReturnUrl(url.toString())) {
                    finishWith(STATUS_APPROVED)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "onPageStarted: $url")
                if (url != null && isReturnUrl(url)) finishWith(STATUS_APPROVED)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                timeoutHandler.removeCallbacks(timeout)
                progress.visibility = android.view.View.GONE
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                super.onReceivedHttpError(view, request, errorResponse)
                // Log-only: ACS chains legitimately serve 4xx interstitials that still render.
                if (request.isForMainFrame) {
                    Log.w(TAG, "onReceivedHttpError: HTTP ${errorResponse.statusCode} on ${request.url}")
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    // A main-frame *network* failure means nothing rendered — surface it instead of
                    // stranding the shopper on a blank page.
                    Log.w(TAG, "onReceivedError: ${error.description} on ${request.url}")
                    finishWith(STATUS_FAILED, message = "payment page failed to load: ${error.description}")
                }
            }
        }
        setContentView(container)
        return view
    }

    private fun isReturnUrl(url: String): Boolean =
        (returnUrlPrefix?.let { url.startsWith(it) } ?: false) || url.isWidgetConfirmationRedirect()

    private val returnScheme: String get() = "$packageName.redirect"

    private val returnUrlPrefix: String?
        get() = intent.getStringExtra(RedirectionCheckoutContract.EXTRA_RETURN_URL_PREFIX)

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        // Any onNewIntent beyond the initial launch came through this Activity's own scoped
        // intent-filter (${applicationId}.redirect) — that's the shopper returning.
        if (launched) finishWith(STATUS_APPROVED)
    }

    override fun onResume() {
        super.onResume()
        // No deep link arrived and we've already left this Activity once (e.g. the WebView handed
        // off to an external app) → the shopper dismissed it. A real return fires onNewIntent first
        // (setting `delivered`). Simply backing out of the WebView finishes without a result at all —
        // the ActivityResultContract framework resolves that to Canceled on its own.
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

        /** How long a first page may take before the redirect is declared dead. */
        private const val FIRST_PAGE_TIMEOUT_MS = 60_000L
    }
}
