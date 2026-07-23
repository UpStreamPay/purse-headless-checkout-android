package eu.purse.dropin.partnerui.generic.redirection

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import eu.purse.dropin.partnerui.redirect.RedirectAction

/**
 * The outcome of a generic redirect round-trip. **This is not the payment outcome** — it only
 * means the shopper came back into the app. [Approved] is a hint to re-call `/validate`, not proof
 * the payment succeeded, matching docs/architecture/redirect-handling.md#the-return-path ("treat
 * returned params as a hint to re-fetch status, not as the source of truth").
 */
sealed interface RedirectionCheckoutResult {
    /** The shopper returned via the deep link — re-validate to see the real outcome. */
    data object Approved : RedirectionCheckoutResult

    /** The shopper dismissed the Custom Tab/WebView without returning via the deep link. */
    data object Canceled : RedirectionCheckoutResult

    /** The redirect itself couldn't be opened (e.g. a malformed [RedirectAction]). */
    data class Failed(val message: String?) : RedirectionCheckoutResult
}

/**
 * Launches a generic (non-partner-SDK) redirect — a chained 3DS/SCA challenge on any method, or a
 * whole `integration_mode: "redirection"` payment method (see docs/decisions/pain-points-recap.md,
 * Pain point 3) — and returns once the shopper comes back into the app.
 *
 * Register it with `registerForActivityResult(RedirectionCheckoutContract()) { result -> … }`. The
 * return Activity and its deep-link intent-filter ship in this module's manifest, so the host app
 * needs **no manifest changes** — same rationale as
 * [eu.purse.dropin.partnerui.partner.paypal.wallet.PaypalCheckoutContract].
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
        const val EXTRA_TYPE = "eu.purse.dropin.partnerui.redirection.TYPE"
        const val EXTRA_URL = "eu.purse.dropin.partnerui.redirection.URL"
        const val EXTRA_FORM_DATA = "eu.purse.dropin.partnerui.redirection.FORM_DATA"
        const val EXTRA_HTML = "eu.purse.dropin.partnerui.redirection.HTML"
        const val EXTRA_STATUS = "eu.purse.dropin.partnerui.redirection.STATUS"
        const val EXTRA_MESSAGE = "eu.purse.dropin.partnerui.redirection.MESSAGE"
        const val TYPE_GET = "get"
        const val TYPE_POST_FORM = "post_form"
        const val TYPE_POST_HTML = "post_html"
    }
}
