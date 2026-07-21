package eu.purse.dropin.partnerui.generic.googlepay

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wallet.IsReadyToPayRequest
import com.google.android.gms.wallet.PaymentData
import com.google.android.gms.wallet.PaymentDataRequest
import com.google.android.gms.wallet.PaymentsClient
import com.google.android.gms.wallet.Wallet
import com.google.android.gms.wallet.WalletConstants
import eu.purse.dropin.core.environment.PurseEnvironment
import org.json.JSONObject

/**
 * Thin wrapper over Google's own `PaymentsClient` (no Purse-specific SDK — see
 * docs/integration/payment-methods.md#google-pay-full). Callers still own the ActivityResult
 * plumbing: register `registerForActivityResult(GetPaymentDataResult()) { … }`
 * (`com.google.android.gms.wallet.contract.TaskResultContracts.GetPaymentDataResult`) in the host
 * Activity, then launch the [Task] returned by [loadPaymentDataTask] through that launcher —
 * mirroring how [eu.purse.dropin.partnerui.partner.paypal.wallet.PaypalCheckoutContract] is
 * registered by the caller rather than by `:partner-ui` itself.
 */
class GooglePayClient private constructor(private val paymentsClient: PaymentsClient) {

    /** `isReadyToPay` — whether to show the Google Pay button at all for [GooglePayMapper.buildIsReadyToPayJson]. */
    fun isReadyToPay(isReadyToPayJson: JSONObject): Task<Boolean> =
        paymentsClient.isReadyToPay(IsReadyToPayRequest.fromJson(isReadyToPayJson.toString()))

    /** Shows the Google Pay sheet. Hand the result to a `GetPaymentDataResult` launcher's `.launch()`. */
    fun loadPaymentDataTask(paymentDataRequestJson: JSONObject): Task<PaymentData> =
        paymentsClient.loadPaymentData(PaymentDataRequest.fromJson(paymentDataRequestJson.toString()))

    companion object {
        /**
         * @param environment the session's locked/declared [PurseEnvironment] (mirrors
         * [eu.purse.dropin.partnerui.partner.paypal.wallet.PaypalCheckoutActivity]'s
         * sandbox/live split for PayPal) — only [PurseEnvironment.PRODUCTION] maps to Google's own
         * `ENVIRONMENT_PRODUCTION`; every other tier uses `ENVIRONMENT_TEST`.
         */
        fun create(context: Context, environment: PurseEnvironment): GooglePayClient {
            val walletEnvironment = if (environment == PurseEnvironment.PRODUCTION) {
                WalletConstants.ENVIRONMENT_PRODUCTION
            } else {
                WalletConstants.ENVIRONMENT_TEST
            }
            val options = Wallet.WalletOptions.Builder().setEnvironment(walletEnvironment).build()
            return GooglePayClient(Wallet.getPaymentsClient(context, options))
        }
    }
}
