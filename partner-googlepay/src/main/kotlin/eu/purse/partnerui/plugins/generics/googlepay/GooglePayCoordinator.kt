// Web: packages/partner-ui/src/plugins/generics/googlepay/index.ts (the button + `onPaymentAuthorized`)
// Same: /init → isReadyToPay → sheet → the approved token goes into /validate, then 3-D Secure.
// Differs: Play Services `PaymentsClient` and an Activity-result contract instead of pay.js.
package eu.purse.partnerui.plugins.generics.googlepay

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.tasks.Task
import com.google.android.gms.wallet.PaymentData
import com.google.android.gms.wallet.contract.ApiTaskResult
import eu.purse.partnerui.PaymentCoordinator
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PluginEvent
import eu.purse.partnerui.PluginHost
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Google Pay's interactive half: [prepare] runs `/init` (+ Worldline refine) and `isReadyToPay`,
 * [launchSheet] opens the sheet, and an approved sheet selects the method and captures. The sheet
 * runs **before** `/validate`, and its request carries the amount, so the surface calls [prepare]
 * again whenever `PluginHost.amount` moves (web's `onAmountUpdate`) — except down to 0, where it
 * only clears the request (nothing to pay). Obtain one with
 * `rememberGooglePayCoordinator`.
 */
class GooglePayCoordinator internal constructor(
    private val activity: ComponentActivity,
    listener: PaymentElementListener,
) : PaymentCoordinator(activity.lifecycleScope, listener) {

    override val tag = "GooglePayCoordinator"

    /** Set by `rememberGooglePayCoordinator` with the Compose-managed `GetPaymentDataResult` launcher. */
    internal var launchPaymentData: ((Task<PaymentData>) -> Unit)? = null

    private var client: GooglePayClient? = null
    private var request: GooglePayRequest? = null

    /** The in-flight [prepare]; a newer one cancels it so a stale `/init` never lands last. */
    private var preparing: Job? = null

    /** `isReadyToPay`'s answer, as Compose state — what [GooglePayButton] shows itself on. */
    var isReady: Boolean by mutableStateOf(false)
        private set

    /** The prepared request's `allowedPaymentMethods`, for Google's own `PayButton`. */
    var allowedPaymentMethodsJson: String? by mutableStateOf(null)
        private set

    /** Build the request against the current amount; calling it again replaces the previous one. */
    fun prepare(host: PluginHost) {
        attach(host)
        preparing?.cancel()
        // The prepared request is about to go stale — no sheet on it, and no Pay button meanwhile.
        request = null
        if (isReady) listener.onReady(false)
        isReady = false
        allowedPaymentMethodsJson = null
        // Nothing left to pay: no sheet request at 0 — wait for the amount to move.
        if (!hasAmountToPrepare(host)) return
        val client = GooglePayClient.create(activity, host.environment).also { this.client = it }
        preparing = activity.lifecycleScope.launch {
            try {
                val context = host.buildContext()
                val req = withContext(Dispatchers.IO) { GooglePayPlugin(context.partner).buildRequest(context) }
                request = req
                allowedPaymentMethodsJson = req.isReadyToPayJson.optJSONArray("allowedPaymentMethods")?.toString()
                val ready = client.isReadyToPay(req.isReadyToPayJson).awaitBoolean()
                isReady = ready
                listener.onReady(ready)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dispatch(PluginEvent.InitFailed(e.message))
                fail(e)
            }
        }
    }

    /** Open the sheet. A no-op while a previous tap's flow is still in progress. */
    fun launchSheet() {
        if (submitting) return
        val req = request ?: return fail("call prepare() first")
        val client = client ?: return fail("call prepare() first")
        submitting = true
        // GetPaymentDataResult resolves off a COMPLETED task; launching the live one throws.
        client.loadPaymentDataTask(req.paymentDataRequestJson)
            .addOnCompleteListener { completedTask -> launchPaymentData?.invoke(completedTask) }
    }

    internal fun onPaymentData(taskResult: ApiTaskResult<PaymentData>) {
        val host = host ?: run {
            submitting = false
            return fail("not prepared")
        }
        when (taskResult.status.statusCode) {
            CommonStatusCodes.SUCCESS -> {
                val paymentData = taskResult.result
                    ?: run {
                        submitting = false
                        return fail("empty Google Pay result")
                    }
                val methodData = JSONObject(paymentData.toJson()).optJSONObject("paymentMethodData")
                host.setCollected(
                    mapOf(
                        GooglePayPlugin.COLLECTED_TOKEN to methodData?.optJSONObject("tokenizationData")?.optString("token").orEmpty(),
                        GooglePayPlugin.COLLECTED_NETWORK to methodData?.optJSONObject("info")?.optString("cardNetwork"),
                    ),
                )
                // The approved sheet both selects the method and pays with it (web's `validationRequested`).
                selectAndCapture(host)
            }
            CommonStatusCodes.CANCELED -> {
                submitting = false
                dispatch(PluginEvent.Cancelled)
                cancel()
            }
            else -> {
                submitting = false
                dispatch(PluginEvent.ClientError("Google Pay failed: ${taskResult.status.statusMessage}"))
                fail("Google Pay failed: ${taskResult.status.statusMessage}")
            }
        }
    }

    private suspend fun Task<Boolean>.awaitBoolean(): Boolean = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                cont.resume(task.result ?: false)
            } else {
                cont.resumeWithException(task.exception ?: RuntimeException("isReadyToPay failed"))
            }
        }
    }
}
