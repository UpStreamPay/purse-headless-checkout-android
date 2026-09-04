// Web: none
// Same: —
// Differs: `ActivityResultContract` for the approval Activity.
package eu.purse.partnerui.plugins.partners.paypal.wallet

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/** What the caller must supply to launch PayPal's approval for an already-created order. */
data class PaypalCheckoutInput(
    /** The PayPal order id from `/init` (see [PaypalRequest.orderId]). */
    val orderId: String,
    /** The merchant's PayPal REST client id (session `merchant_specifics.client_id`). */
    val clientId: String,
    /** `true` for PayPal sandbox, `false` for live — derived from the Purse environment. */
    val sandbox: Boolean,
)

/**
 * The outcome of the PayPal approval step — **not** the payment. The caller still runs `/validate`
 * (with the init's `step_id`) to capture; [PaypalCoordinator] does that on [Approved].
 */
sealed interface PaypalCheckoutResult {
    /** Buyer approved the order. */
    data class Approved(val orderId: String?, val payerId: String?) : PaypalCheckoutResult

    /** Buyer dismissed or cancelled the approval. */
    data object Canceled : PaypalCheckoutResult

    /** The approval flow failed to present, or errored. */
    data class Failed(val message: String?) : PaypalCheckoutResult
}

/**
 * Launches the PayPal web-checkout approval for an already-created order and returns its outcome —
 * the Android analogue of web paypal-wallet's in-page JS-SDK popup (approval step only; `/init` and
 * `/validate` belong to [PaypalPlugin] / the host).
 *
 * [PaypalCoordinator] registers this; use it directly only if you are driving PayPal yourself. The
 * return Activity and its deep-link intent-filter ship in this module's manifest, so the host app
 * needs **no manifest changes**.
 */
class PaypalCheckoutContract : ActivityResultContract<PaypalCheckoutInput, PaypalCheckoutResult>() {

    override fun createIntent(context: Context, input: PaypalCheckoutInput): Intent =
        Intent(context, PaypalCheckoutActivity::class.java)
            .putExtra(EXTRA_ORDER_ID, input.orderId)
            .putExtra(EXTRA_CLIENT_ID, input.clientId)
            .putExtra(EXTRA_SANDBOX, input.sandbox)

    override fun parseResult(resultCode: Int, intent: Intent?): PaypalCheckoutResult =
        when (intent?.getStringExtra(EXTRA_STATUS)) {
            PaypalCheckoutActivity.STATUS_APPROVED -> PaypalCheckoutResult.Approved(
                orderId = intent.getStringExtra(EXTRA_ORDER_ID),
                payerId = intent.getStringExtra(EXTRA_PAYER_ID),
            )

            PaypalCheckoutActivity.STATUS_FAILED ->
                PaypalCheckoutResult.Failed(intent.getStringExtra(EXTRA_MESSAGE))

            // Covers STATUS_CANCELED and a null intent (the shopper backed out of the Activity
            // itself, so it never set a result) — both are a cancellation.
            else -> PaypalCheckoutResult.Canceled
        }

    companion object {
        const val EXTRA_ORDER_ID = "eu.purse.partnerui.paypal.ORDER_ID"
        const val EXTRA_CLIENT_ID = "eu.purse.partnerui.paypal.CLIENT_ID"
        const val EXTRA_SANDBOX = "eu.purse.partnerui.paypal.SANDBOX"
        const val EXTRA_STATUS = "eu.purse.partnerui.paypal.STATUS"
        const val EXTRA_PAYER_ID = "eu.purse.partnerui.paypal.PAYER_ID"
        const val EXTRA_MESSAGE = "eu.purse.partnerui.paypal.MESSAGE"
    }
}
