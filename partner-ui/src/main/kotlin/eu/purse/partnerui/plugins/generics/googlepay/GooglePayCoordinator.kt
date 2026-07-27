package eu.purse.partnerui.plugins.generics.googlepay

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wallet.PaymentData
import com.google.android.gms.wallet.contract.ApiTaskResult
import com.google.android.gms.wallet.contract.TaskResultContracts
import eu.purse.partnerui.plugins.generics.googlepay.redirect.RedirectionCheckoutContract
import eu.purse.partnerui.plugins.generics.googlepay.redirect.RedirectionCheckoutResult
import eu.purse.partnerui.plugins.generics.googlepay.redirect.RedirectionResolver
import eu.purse.partnerui.plugins.generics.googlepay.redirect.withReturnUrlPrefix
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
 * 3. on the sheet result — hands the token/network to the [GooglePayHost] ([GooglePayHost.setCollected])
 *    and calls [GooglePayHost.submitPayment] (which validates all splits together).
 * 4. if `/validate` returns a redirection — launches the 3-D Secure Activity, then reports that the
 *    shopper returned (not the payment outcome — see [onRedirectReturn]'s doc).
 *
 * Knows nothing about `:headless-checkout` — [GooglePayHost] is the seam (see its doc).
 *
 * **Construct this in the host Activity's `onCreate`** — it registers `ActivityResult` launchers,
 * which must happen before the Activity is STARTED. The host is supplied later, per payment, via
 * [prepare]. Outcomes are reported on [listener].
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

        /**
         * The `payment_id` behind this payment, when [submit] can read one off the widget's own
         * confirmation-page redirect (see [eu.purse.partnerui.plugins.generics.googlepay.redirect.RedirectionResolver.extractWidgetConfirmationPaymentId])
         * — the id the orchestration API's `POST /v2/payments/{id}/captures` needs. Purely
         * informational (e.g. to prefill a "Capture" debug tool); never required for the payment
         * itself to resolve.
         */
        fun onPaymentId(paymentId: String) {}

        fun onCanceled() {}
        fun onError(message: String?) {}
    }

    private val paymentDataLauncher =
        activity.registerForActivityResult(TaskResultContracts.GetPaymentDataResult()) { onPaymentData(it) }

    private val redirectLauncher =
        activity.registerForActivityResult(RedirectionCheckoutContract()) { onRedirectReturn(it) }

    private var host: GooglePayHost? = null
    private var client: GooglePayClient? = null
    private var partnerWireValue: String? = null
    private var request: GooglePayRequest? = null

    /**
     * Set for the span from [launchSheet] to a terminal outcome — guards against a double tap (or
     * any other re-entrant call) firing two payment sheets, which would `POST /validate` twice for
     * the same session and get the second call rejected with `409` (idempotency conflict). Reset on
     * cancel/error so the shopper can retry; left `true` after a successful submit since that item is
     * done.
     */
    private var submitting = false

    /**
     * Build the request (`/init` + refine) against [host] and check readiness; reports via
     * [Listener.onReady]. Call once the session is initialized — this is also the reset point for
     * [submitting], so a coordinator reused across sessions (as the demo does) isn't left permanently
     * blocked after a prior session's successful submit.
     */
    fun prepare(host: GooglePayHost) {
        this.host = host
        submitting = false
        val client = GooglePayClient.create(activity, host.environment).also { this.client = it }
        activity.lifecycleScope.launch {
            try {
                val context = host.buildContext()
                partnerWireValue = context.partner.wireValue
                val plugin = GooglePayPlugin(context.partner)
                val req = withContext(Dispatchers.IO) { plugin.buildRequest(context) }
                request = req
                listener.onReady(client.isReadyToPay(req.isReadyToPayJson).awaitBoolean())
            } catch (e: Exception) {
                listener.onError(e.message)
            }
        }
    }

    /**
     * Launch the Google Pay sheet. Call after [prepare] reported ready and the button was tapped.
     * A no-op while a previous tap's flow is still in progress — see [submitting].
     */
    fun launchSheet() {
        if (submitting) return
        val req = request ?: return listener.onError("call prepare() first")
        val client = client ?: return listener.onError("call prepare() first")
        submitting = true
        // GetPaymentDataResult's contract resolves synchronously off a COMPLETED Task (success, or a
        // ResolvableApiException carrying the sheet's PendingIntent) — launch()ing the live/pending
        // Task straight from loadPaymentData() throws "The task has to be executed before using this
        // API to resolve its result." So wait for completion first, on this same (main) thread.
        client.loadPaymentDataTask(req.paymentDataRequestJson)
            .addOnCompleteListener { completedTask -> paymentDataLauncher.launch(completedTask) }
    }

    private fun onPaymentData(taskResult: ApiTaskResult<PaymentData>) {
        val host = host ?: run { submitting = false; return listener.onError("not prepared") }
        when (taskResult.status.statusCode) {
            CommonStatusCodes.SUCCESS -> {
                val paymentData = taskResult.result
                    ?: run { submitting = false; return listener.onError("empty Google Pay result") }
                val methodData = JSONObject(paymentData.toJson()).optJSONObject("paymentMethodData")
                val token = methodData?.optJSONObject("tokenizationData")?.optString("token").orEmpty()
                val network = methodData?.optJSONObject("info")?.optString("cardNetwork")
                host.setCollected(
                    mapOf(
                        GooglePayPlugin.COLLECTED_TOKEN to token,
                        GooglePayPlugin.COLLECTED_NETWORK to network,
                    ),
                )
                submit(host)
            }

            CommonStatusCodes.CANCELED -> {
                submitting = false
                listener.onCanceled()
            }

            else -> {
                submitting = false
                listener.onError("Google Pay failed: ${taskResult.status.statusMessage}")
            }
        }
    }

    private fun submit(host: GooglePayHost) {
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { host.submitPayment() }
                Log.d(
                    TAG,
                    "validate response: global_status=${result.globalStatus} " +
                        "redirection=${result.raw.optJSONObject("redirection")}",
                )
                val paymentId = RedirectionResolver.extractWidgetConfirmationPaymentId(
                    validateResponse = result.raw,
                    partnerWireValue = partnerWireValue.orEmpty(),
                    methodWireValue = GooglePayMapper.METHOD,
                )
                Log.d(TAG, "extracted payment_id=$paymentId")
                paymentId?.let { listener.onPaymentId(it) }
                val action = RedirectionResolver.resolve(
                    validateResponse = result.raw,
                    partnerWireValue = partnerWireValue.orEmpty(),
                    methodWireValue = GooglePayMapper.METHOD,
                    sessionReturnUrl = host.redirectionReturnUrl,
                )?.withReturnUrlPrefix(host.redirectionReturnUrl)
                if (action != null) {
                    listener.onRequiresAction()
                    redirectLauncher.launch(action)
                } else {
                    listener.onResult("global_status=${result.globalStatus}")
                }
            } catch (e: Exception) {
                // `/validate` never landed (or errored) — safe to let the shopper retry.
                submitting = false
                listener.onError(e.message)
            }
        }
    }

    private fun onRedirectReturn(result: RedirectionCheckoutResult) {
        when (result) {
            // Approved only means the shopper came back — NOT the payment outcome. It deliberately
            // does not chase that outcome itself: a real 3-D Secure challenge's own final redirect is
            // constructed server-side (by the bank/PSP), and a confirmed backend pain point is that
            // this can land on a Purse API gateway URL that 401s on the session's own client-facing
            // api_key — nothing fixable client-side. Report status independently instead (e.g. this
            // demo's own alfred-backed "Get Transactions" tool) rather than surface that as an error
            // here.
            RedirectionCheckoutResult.Approved -> listener.onResult("returned from 3-D Secure — check the session status independently to confirm the outcome")

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

    private companion object {
        const val TAG = "GooglePayCoordinator"
    }
}
