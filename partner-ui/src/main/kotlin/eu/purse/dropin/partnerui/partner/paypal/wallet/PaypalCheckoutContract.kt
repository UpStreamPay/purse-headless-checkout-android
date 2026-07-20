package eu.purse.dropin.partnerui.partner.paypal.wallet

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/** What the caller must supply to launch PayPal for an already-created order. */
data class PaypalCheckoutInput(
    /** The PayPal order id from `/init` (see [WalletOrder.orderId]). */
    val orderId: String,
    /** The merchant's PayPal REST client id (session `merchant_specifics.client_id`). */
    val clientId: String,
    /** `true` for PayPal sandbox, `false` for live — derived from the Purse environment. */
    val sandbox: Boolean,
)

/** The outcome of the PayPal approval step (not the capture — the caller still runs `/validate`). */
sealed interface PaypalCheckoutResult {
    /** Buyer approved. Run `/validate` (with the init's `step_id`) to capture. */
    data class Approved(val orderId: String?, val payerId: String?) : PaypalCheckoutResult

    /** Buyer dismissed / cancelled the approval. */
    data object Canceled : PaypalCheckoutResult

    /** The approval flow failed to present or errored. */
    data class Failed(val message: String?) : PaypalCheckoutResult
}

/**
 * Launches the PayPal web-checkout approval for an already-created order and returns its outcome —
 * the Android analogue of web partner-ui's paypal button (approve step only; `/init` and `/validate`
 * are the caller's, via `DropinAndroid`).
 *
 * Register it with `registerForActivityResult(PaypalCheckoutContract()) { result -> … }`. The return
 * Activity and its deep-link intent-filter ship in this module's manifest, so the host app needs
 * **no manifest changes**.
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
            else -> PaypalCheckoutResult.Canceled
        }

    companion object {
        const val EXTRA_ORDER_ID = "eu.purse.dropin.partnerui.ORDER_ID"
        const val EXTRA_CLIENT_ID = "eu.purse.dropin.partnerui.CLIENT_ID"
        const val EXTRA_SANDBOX = "eu.purse.dropin.partnerui.SANDBOX"
        const val EXTRA_STATUS = "eu.purse.dropin.partnerui.STATUS"
        const val EXTRA_PAYER_ID = "eu.purse.dropin.partnerui.PAYER_ID"
        const val EXTRA_MESSAGE = "eu.purse.dropin.partnerui.MESSAGE"
    }
}
