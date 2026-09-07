// Web: packages/partner-ui/src/plugins/partners/klarna-installments/index.ts
// Same: /init → widget (client_token) → /validate → Klarna `authorize()` only after /validate succeeds.
// Differs: native `KlarnaPaymentView` instead of the Klarna JS SDK; `loadPaymentReview()` is never
//          called (US-only API); the host app declares the return deep link.
package eu.purse.partnerui.plugins.partners.klarna

import android.util.Log
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.klarna.mobile.sdk.api.payments.KlarnaPaymentView
import com.klarna.mobile.sdk.api.payments.KlarnaPaymentViewCallback
import com.klarna.mobile.sdk.api.payments.KlarnaPaymentsSDKError
import eu.purse.partnerui.PaymentCoordinator
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PluginEvent
import eu.purse.partnerui.PluginHost
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Klarna's interactive half: [prepare] runs `/init` and inflates the native widget into
 * [container]; [submit] runs `/validate`, and [KlarnaPlugin.postValidation] then calls the widget's
 * `authorize()` through the callback stashed in `PartnerUiContext.collected`. One submit therefore
 * both pays and opens Klarna's step. Obtain one with `rememberKlarnaCoordinator`.
 */
class KlarnaCoordinator internal constructor(
    private val activity: ComponentActivity,
    listener: PaymentElementListener,
) : PaymentCoordinator(activity.lifecycleScope, listener) {

    override val tag = "KlarnaCoordinator"

    /** Where the widget inflates — the `AndroidView` the payment element provides. Set before [prepare]. */
    var container: ViewGroup? = null

    private var view: KlarnaPaymentView? = null

    /** Resolved by `onAuthorized` / `onFinalized` / `onErrorOccurred` while `authorize()` is in flight. */
    private var pendingAuthorize: CancellableContinuation<Boolean>? = null

    private val callback = object : KlarnaPaymentViewCallback {
        override fun onInitialized(view: KlarnaPaymentView) {
            view.load(null)
        }

        /** The "ready" signal: the widget rendered whatever Klarna's session offers. */
        override fun onLoaded(view: KlarnaPaymentView) {
            listener.onReady(true)
        }

        override fun onLoadPaymentReview(view: KlarnaPaymentView, showForm: Boolean) = Unit

        override fun onAuthorized(view: KlarnaPaymentView, approved: Boolean, authToken: String?, finalizedRequired: Boolean?) {
            if (finalizedRequired == true) {
                view.finalize(null)
                return
            }
            pendingAuthorize?.resume(approved)
            pendingAuthorize = null
        }

        override fun onReauthorized(view: KlarnaPaymentView, approved: Boolean, authToken: String?) = Unit

        override fun onFinalized(view: KlarnaPaymentView, approved: Boolean, authToken: String?) {
            pendingAuthorize?.resume(approved)
            pendingAuthorize = null
        }

        override fun onErrorOccurred(view: KlarnaPaymentView, error: KlarnaPaymentsSDKError) {
            val pending = pendingAuthorize
            pendingAuthorize = null
            if (pending != null) {
                pending.resumeWithException(IllegalStateException(error.describe()))
            } else {
                submitting = false
                dispatch(PluginEvent.PartnerError(error.describe(), recoverable = !error.isFatal))
                listener.onError(error.describe())
            }
        }
    }

    /** Everything `KlarnaPaymentsSDKError` carries; `message` alone drops the `ERROR_*` code. */
    private fun KlarnaPaymentsSDKError.describe(): String = buildString {
        append(name).append(": ").append(message)
        action?.let { append(" (action=").append(it).append(")") }
        if (!invalidFields.isNullOrEmpty()) append(" invalidFields=").append(invalidFields)
        sessionId?.let { append(" sessionId=").append(it) }
        append(" fatal=").append(isFatal)
    }

    /**
     * @param returnUrl the deep link `KlarnaPaymentView.returnURL` carries back into the Activity
     * hosting the widget (`"{packageName}.klarna://return"`), declared by the host app's manifest.
     */
    fun prepare(host: PluginHost, returnUrl: String) {
        attach(host)
        activity.lifecycleScope.launch {
            try {
                val context = host.buildContext()
                val request = withContext(Dispatchers.IO) { KlarnaPlugin(context.partner).buildRequest(context) }
                logClientTokenPayload(request.clientToken)

                val container = checkNotNull(container) { "KlarnaCoordinator needs a container before prepare()" }
                val paymentView = KlarnaPaymentView(activity, KlarnaMapper.PAYMENT_METHOD_CATEGORY, callback)
                paymentView.returnURL = returnUrl
                container.removeAllViews()
                container.addView(paymentView, ViewGroup.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                view = paymentView

                val authorize: KlarnaAuthorize = { authorizeWidget(paymentView) }
                host.setCollected(
                    mapOf(
                        KlarnaPlugin.COLLECTED_STEP_ID to request.stepId,
                        KlarnaPlugin.COLLECTED_AUTHORIZE to authorize,
                    ),
                )
                paymentView.initialize(request.clientToken)
            } catch (e: Exception) {
                dispatch(PluginEvent.InitFailed(e.message))
                listener.onError(e.message)
            }
        }
    }

    private suspend fun authorizeWidget(paymentView: KlarnaPaymentView): Boolean =
        suspendCancellableCoroutine { cont ->
            pendingAuthorize = cont
            paymentView.authorize(sessionData = null, autoFinalize = true)
        }

    /** `/validate`, then Klarna's own `authorize()` inside the plugin's `postValidation`. */
    fun submit() {
        if (submitting) return
        val host = host ?: return listener.onError("call prepare() first")
        if (view == null) return listener.onError("call prepare() first")
        submitting = true
        capture(host)
    }

    /** A declined `authorize()` is a cancellation, not an error. */
    override fun onSubmitFailed(e: Exception) {
        if (e is KlarnaAuthorizationDeclined) {
            submitting = false
            dispatch(PluginEvent.Cancelled)
            listener.onCanceled()
        } else {
            super.onSubmitFailed(e)
        }
    }

    /**
     * The `client_token` is a JWT; its payload (`payment_method_categories`, country, currency) is
     * the fastest way to see why the widget shows a generic info card instead of instalments.
     */
    private fun logClientTokenPayload(clientToken: String) {
        val payloadSegment = clientToken.split(".").getOrNull(1) ?: return
        try {
            val padded = payloadSegment + "=".repeat((4 - payloadSegment.length % 4) % 4)
            val json = String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
            Log.d(tag, "klarna_client_token payload: ${JSONObject(json).toString(2)}")
        } catch (e: Exception) {
            Log.d(tag, "klarna_client_token isn't a decodable JWT (${e.message})")
        }
    }
}
