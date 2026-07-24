package eu.purse.googlepay

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wallet.IsReadyToPayRequest
import com.google.android.gms.wallet.PaymentData
import com.google.android.gms.wallet.PaymentDataRequest
import com.google.android.gms.wallet.PaymentsClient
import com.google.android.gms.wallet.Wallet
import com.google.android.gms.wallet.WalletConstants
import eu.purse.core.environment.PurseEnvironment
import org.json.JSONObject

/**
 * Thin wrapper over Google's own `PaymentsClient` (no Purse-specific SDK). The
 * [GooglePayCoordinator] owns the ActivityResult plumbing (`GetPaymentDataResult`); this only
 * builds the `isReadyToPay` / `loadPaymentData` Tasks.
 */
class GooglePayClient private constructor(private val paymentsClient: PaymentsClient) {

    /** `isReadyToPay` — whether to show the Google Pay button (see [GooglePayMapper.buildIsReadyToPayJson]). */
    fun isReadyToPay(isReadyToPayJson: JSONObject): Task<Boolean> =
        paymentsClient.isReadyToPay(IsReadyToPayRequest.fromJson(isReadyToPayJson.toString()))

    /** Shows the Google Pay sheet. Hand the result to a `GetPaymentDataResult` launcher's `.launch()`. */
    fun loadPaymentDataTask(paymentDataRequestJson: JSONObject): Task<PaymentData> =
        paymentsClient.loadPaymentData(PaymentDataRequest.fromJson(paymentDataRequestJson.toString()))

    companion object {
        /** Only [PurseEnvironment.PRODUCTION] maps to Google's `ENVIRONMENT_PRODUCTION`; else `ENVIRONMENT_TEST`. */
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
