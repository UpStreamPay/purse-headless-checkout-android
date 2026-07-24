package eu.purse.googlepay.redirect

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * The outcome of a redirect round-trip. **Not the payment outcome** — it only means the shopper
 * came back into the app. [Approved] is a hint to re-check status (re-query the session), not proof
 * the payment succeeded.
 */
sealed interface RedirectionCheckoutResult {
    /** The shopper returned via the deep link — re-check status to learn the real outcome. */
    data object Approved : RedirectionCheckoutResult

    /** The shopper dismissed the Custom Tab / WebView without returning via the deep link. */
    data object Canceled : RedirectionCheckoutResult

    /** The redirect itself couldn't be opened (e.g. a malformed [RedirectAction]). */
    data class Failed(val message: String?) : RedirectionCheckoutResult
}

/**
 * Launches a chained 3-D Secure redirect after a Google Pay `/validate` and returns once the shopper
 * comes back. Register it with `registerForActivityResult(RedirectionCheckoutContract()) { … }`. The
 * return Activity + its deep-link intent-filter ship in this module's manifest, so the host needs no
 * manifest changes.
 */
class RedirectionCheckoutContract : ActivityResultContract<RedirectAction, RedirectionCheckoutResult>() {

    override fun createIntent(context: Context, input: RedirectAction): Intent {
        val intent = Intent(context, RedirectionCheckoutActivity::class.java)
        return when (input) {
            is RedirectAction.Get -> intent
                .putExtra(EXTRA_TYPE, TYPE_GET)
                .putExtra(EXTRA_URL, input.url)
            is RedirectAction.PostForm -> intent
                .putExtra(EXTRA_TYPE, TYPE_POST_FORM)
                .putExtra(EXTRA_URL, input.url)
                .putExtra(EXTRA_FORM_DATA, input.formDataUrlEncoded)
            is RedirectAction.PostHtml -> intent
                .putExtra(EXTRA_TYPE, TYPE_POST_HTML)
                .putExtra(EXTRA_HTML, input.html)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): RedirectionCheckoutResult =
        when (intent?.getStringExtra(EXTRA_STATUS)) {
            RedirectionCheckoutActivity.STATUS_APPROVED -> RedirectionCheckoutResult.Approved
            RedirectionCheckoutActivity.STATUS_FAILED ->
                RedirectionCheckoutResult.Failed(intent.getStringExtra(EXTRA_MESSAGE))
            else -> RedirectionCheckoutResult.Canceled
        }

    companion object {
        const val EXTRA_TYPE = "eu.purse.googlepay.redirect.TYPE"
        const val EXTRA_URL = "eu.purse.googlepay.redirect.URL"
        const val EXTRA_FORM_DATA = "eu.purse.googlepay.redirect.FORM_DATA"
        const val EXTRA_HTML = "eu.purse.googlepay.redirect.HTML"
        const val EXTRA_STATUS = "eu.purse.googlepay.redirect.STATUS"
        const val EXTRA_MESSAGE = "eu.purse.googlepay.redirect.MESSAGE"
        const val TYPE_GET = "get"
        const val TYPE_POST_FORM = "post_form"
        const val TYPE_POST_HTML = "post_html"
    }
}
