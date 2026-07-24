package eu.purse.googlepay

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wallet.PaymentData
import com.google.android.gms.wallet.contract.ApiTaskResult
import com.google.android.gms.wallet.contract.TaskResultContracts
import eu.purse.checkout.PurseHeadlessCheckout
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.googlepay.redirect.RedirectionCheckoutContract
import eu.purse.googlepay.redirect.RedirectionCheckoutResult
import eu.purse.googlepay.redirect.RedirectionResolver
import eu.purse.partnerui.PluginRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives the interactive Google Pay flow end to end, gluing the [GooglePayPlugin] protocol to
 * Google's `PaymentsClient` sheet and the 3-D Secure [RedirectionCheckoutContract]:
 *
 * 1. [prepare] — `plugin.buildRequest` (`/init` + Worldline refine) then `isReadyToPay`.
 * 2. [launchSheet] — shows the Google Pay sheet.
 * 3. on the sheet result — hands the token/network to the SDK ([PurseHeadlessCheckout.setCollected])
 *    and calls [PurseHeadlessCheckout.submitPayment] (which validates all splits together).
 * 4. if `/validate` returns a redirection — launches the 3-D Secure Activity, then re-checks the
 *    session status on return.
 *
 * **Construct this in the host Activity's `onCreate`** — it registers `ActivityResult` launchers,
 * which must happen before the Activity is STARTED. The session is supplied later, per payment,
 * via [prepare]. Outcomes are reported on [listener].
 */
class GooglePayCoordinator(
    private val activity: ComponentActivity,
    private val listener: Listener,
) {
    interface Listener {
        /** Result of the `isReadyToPay` check after [prepare] — show the button only when `true`. */
        fun onReady(ready: Boolean) {}

        /** Terminal success — [summary] is a human-readable status for the demo to display. */
        fun onResult(summary: String) {}

        /** A 3-D Secure step is being launched (informational). */
        fun onRequiresAction() {}

        fun onCanceled() {}
        fun onError(message: String?) {}
    }

    private val paymentDataLauncher =
        activity.registerForActivityResult(TaskResultContracts.GetPaymentDataResult()) { onPaymentData(it) }

    private val redirectLauncher =
        activity.registerForActivityResult(RedirectionCheckoutContract()) { onRedirectReturn(it) }

    private var checkout: PurseHeadlessCheckout? = null
    private var client: GooglePayClient? = null
    private var item: PaymentItem? = null
    private var request: GooglePayRequest? = null

    /**
     * Build the request (`/init` + refine) for [item] of [checkout] and check readiness; reports via
     * [Listener.onReady]. Call once the session is initialized.
     */
    fun prepare(checkout: PurseHeadlessCheckout, item: PaymentItem) {
        this.checkout = checkout
        this.item = item
        val client = GooglePayClient.create(activity, checkout.params.environment).also { this.client = it }
        activity.lifecycleScope.launch {
            try {
                val plugin = PluginRegistry.getPlugin(item.partner, item.method, checkout.session.source) as? GooglePayPlugin
                    ?: error("no Google Pay plugin registered — call GooglePayModule.register()")
                val req = withContext(Dispatchers.IO) { plugin.buildRequest(checkout.pluginContext(item)) }
                request = req
                listener.onReady(client.isReadyToPay(req.isReadyToPayJson).awaitBoolean())
            } catch (e: Exception) {
                listener.onError(e.message)
            }
        }
    }

    /** Launch the Google Pay sheet. Call after [prepare] reported ready and the button was tapped. */
    fun launchSheet() {
        val req = request ?: return listener.onError("call prepare() first")
        val client = client ?: return listener.onError("call prepare() first")
        paymentDataLauncher.launch(client.loadPaymentDataTask(req.paymentDataRequestJson))
    }

    private fun onPaymentData(taskResult: ApiTaskResult<PaymentData>) {
        val checkout = checkout ?: return listener.onError("not prepared")
        val gpItem = item ?: return listener.onError("no Google Pay method selected")
        when (taskResult.status.statusCode) {
            CommonStatusCodes.SUCCESS -> {
                val paymentData = taskResult.result ?: return listener.onError("empty Google Pay result")
                val methodData = JSONObject(paymentData.toJson()).optJSONObject("paymentMethodData")
                val token = methodData?.optJSONObject("tokenizationData")?.optString("token").orEmpty()
                val network = methodData?.optJSONObject("info")?.optString("cardNetwork")
                checkout.setCollected(
                    gpItem,
                    mapOf(
                        GooglePayPlugin.COLLECTED_TOKEN to token,
                        GooglePayPlugin.COLLECTED_NETWORK to network,
                    ),
                )
                submit(checkout, gpItem)
            }

            CommonStatusCodes.CANCELED -> listener.onCanceled()
            else -> listener.onError("Google Pay failed: ${taskResult.status.statusMessage}")
        }
    }

    private fun submit(checkout: PurseHeadlessCheckout, item: PaymentItem) {
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { checkout.submitPayment() }
                val action = RedirectionResolver.resolve(
                    validateResponse = result.raw,
                    partnerWireValue = item.partner.wireValue,
                    methodWireValue = GooglePayMapper.METHOD,
                    sessionFallbackUrl = checkout.session.source.redirection,
                )
                if (action != null) {
                    listener.onRequiresAction()
                    redirectLauncher.launch(action)
                } else {
                    listener.onResult("global_status=${result.globalStatus}")
                }
            } catch (e: Exception) {
                listener.onError(e.message)
            }
        }
    }

    private fun onRedirectReturn(result: RedirectionCheckoutResult) {
        val checkout = checkout ?: return listener.onError("not prepared")
        when (result) {
            RedirectionCheckoutResult.Approved -> activity.lifecycleScope.launch {
                try {
                    // Approved only means the shopper returned — re-query the authoritative status.
                    val transactions = withContext(Dispatchers.IO) { checkout.sessionTransactions() }
                    listener.onResult("after 3-D Secure — session transactions: $transactions")
                } catch (e: Exception) {
                    listener.onError(e.message)
                }
            }

            RedirectionCheckoutResult.Canceled -> listener.onCanceled()
            is RedirectionCheckoutResult.Failed -> listener.onError(result.message)
        }
    }

    private suspend fun com.google.android.gms.tasks.Task<Boolean>.awaitBoolean(): Boolean =
        suspendCancellableCoroutine { cont ->
            addOnCompleteListener { task ->
                if (task.isSuccessful) cont.resume(task.result ?: false)
                else cont.resumeWithException(task.exception ?: RuntimeException("isReadyToPay failed"))
            }
        }
}
