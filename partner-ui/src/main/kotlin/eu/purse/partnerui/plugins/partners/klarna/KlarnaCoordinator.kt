package eu.purse.partnerui.plugins.partners.klarna

import android.util.Log
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.klarna.mobile.sdk.api.payments.KlarnaPaymentView
import com.klarna.mobile.sdk.api.payments.KlarnaPaymentViewCallback
import com.klarna.mobile.sdk.api.payments.KlarnaPaymentsSDKError
import eu.purse.core.payload.ValidationResult
import eu.purse.partnerui.plugins.generics.googlepay.redirect.RedirectionCheckoutContract
import eu.purse.partnerui.plugins.generics.googlepay.redirect.RedirectionCheckoutResult
import eu.purse.partnerui.plugins.generics.googlepay.redirect.RedirectionResolver
import eu.purse.partnerui.plugins.generics.googlepay.redirect.withReturnUrlPrefix
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives the interactive Klarna flow end to end, gluing the [KlarnaPlugin] protocol to the native
 * `KlarnaPaymentView` (see
 * https://docs.klarna.com/acquirer/klarna/mobile-payments/integrate-with-mobile-sdk/android/native-view/)
 * and the shared 3-D-Secure-style [RedirectionCheckoutContract]:
 *
 * 1. [prepare] — `plugin.buildRequest` (`/init`), then inflates a `KlarnaPaymentView` into [container]
 *    and calls `initialize(clientToken)` → `load(null)` once Klarna confirms it (`onInitialized`).
 *    Deliberately does **not** call `loadPaymentReview()` — that's a US-market-only API (a multi-step
 *    checkout order-review page) that throws for any other `purchase_country`; every market this
 *    plugin targets renders fully off `load()` alone. Also stashes an `authorize()` callback (see
 *    [authorizeWidget]) into `PartnerUiContext.collected`, alongside the `/init` step id.
 * 2. [submit] — a single [KlarnaHost.submitPayment] call now does everything: [KlarnaPlugin]'s own
 *    `postValidation` calls the stashed [authorizeWidget] callback *after* `/validate` succeeds —
 *    unlike Google Pay (sheet runs *before* `/validate`), Klarna's own reference flow (web's
 *    `klarna_installments` plugin) only opens its payment step once `/validate` itself returns
 *    `SUCCESS`, confirmed against both the plugin and the orchestrator that calls it
 *    (`headless-checkout-validation.ts`). [submit] itself only resolves whatever redirect the
 *    (already-authorized) result carries.
 * 3. [authorizeWidget] suspends until `onAuthorized`/`onFinalized` resolves it — [submit] never
 *    touches the widget directly once `/validate` is in flight.
 *
 * Knows nothing about `:headless-checkout` — [KlarnaHost] is the seam (see its doc).
 *
 * **Construct this in the host Activity's `onCreate`** — it registers an `ActivityResult` launcher,
 * which must happen before the Activity is STARTED. [container] is the `ViewGroup` the widget renders
 * into (e.g. a `FrameLayout` in the host's layout); the host is supplied later, per payment, via
 * [prepare]. Outcomes are reported on [listener].
 *
 * The "ready" signal is [KlarnaPaymentViewCallback.onLoaded] — content finished loading for whichever
 * payment method category the session's `client_token` actually offers (see
 * `KlarnaMapper.PAYMENT_METHOD_CATEGORY`'s doc on the generic `"klarna"` category letting the token
 * decide). [KlarnaPaymentViewCallback.onLoadPaymentReview] is intentionally unused — see [prepare]'s
 * doc on why `loadPaymentReview()` is never called.
 */
class KlarnaCoordinator(
    private val activity: ComponentActivity,
    private val container: ViewGroup,
    private val listener: Listener,
) {
    interface Listener {
        /** The widget has been asked to render — show a "Pay with Klarna" trigger once this fires. */
        fun onReady(ready: Boolean) {}

        /**
         * Terminal success — [summary] is a human-readable status to display, [globalStatus] the
         * `/validate` response's own `global_status` when this outcome came from one. `null` after a
         * redirect return, where the real outcome is deliberately not chased (see [onRedirectReturn]).
         */
        fun onResult(summary: String, globalStatus: String? = null) {}

        /** A redirect step (e.g. an external banking/identity-verification app) is being launched. */
        fun onRequiresAction() {}

        /**
         * The `payment_id` behind this payment, when [submit]'s `/validate` response carries one —
         * same source and caveats as
         * [eu.purse.partnerui.plugins.generics.googlepay.GooglePayCoordinator.Listener.onPaymentId]:
         * purely informational (e.g. prefilling a "Capture" tool), never required for the payment
         * itself to resolve.
         */
        fun onPaymentId(paymentId: String) {}

        fun onCanceled() {}
        fun onError(message: String?) {}
    }

    private val redirectLauncher =
        activity.registerForActivityResult(RedirectionCheckoutContract()) { onRedirectReturn(it) }

    private var host: KlarnaHost? = null
    private var view: KlarnaPaymentView? = null
    private var partnerWireValue: String? = null

    /** Resolved by `onAuthorized`/`onFinalized`/`onErrorOccurred` — see [authorizeWidget]. `null`
     * whenever no authorize() call is currently in flight. */
    private var pendingAuthorize: CancellableContinuation<Boolean>? = null

    /** Same re-entrancy guard as `GooglePayCoordinator.submitting` — see its doc. */
    private var submitting = false

    private val callback = object : KlarnaPaymentViewCallback {
        override fun onInitialized(view: KlarnaPaymentView) {
            view.load(null)
        }

        override fun onLoaded(view: KlarnaPaymentView) {
            // The "ready" signal — the widget rendered whatever content Klarna's session offered
            // (see class doc). Deliberately NOT calling loadPaymentReview() here: per Klarna's own
            // SDK reference it only applies to a US-market multi-step checkout order-review page,
            // and throws for any other purchase_country (confirmed: it's what "operation not
            // permitted" came from for this session's BE/FR purchase_country).
            listener.onReady(true)
        }

        override fun onLoadPaymentReview(view: KlarnaPaymentView, showForm: Boolean) {
            // Not driven by this flow — loadPaymentReview() (US-only) is never called, so this
            // should never fire; implemented only because the interface requires it.
        }

        override fun onAuthorized(view: KlarnaPaymentView, approved: Boolean, authToken: String?, finalizedRequired: Boolean?) {
            if (finalizedRequired == true) {
                view.finalize(null)
                return
            }
            pendingAuthorize?.resume(approved)
            pendingAuthorize = null
        }

        override fun onReauthorized(view: KlarnaPaymentView, approved: Boolean, authToken: String?) {
            // Not driven by this flow — authorizeWidget() always calls authorize(), never reauthorize().
        }

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
                // Not mid-authorize (e.g. a load-time failure) — nothing suspended to resume;
                // report directly instead.
                submitting = false
                listener.onError(error.describe())
            }
        }
    }

    /**
     * The full detail `KlarnaPaymentsSDKError` carries — [KlarnaPaymentsSDKError.message] alone
     * drops [KlarnaPaymentsSDKError.name] (one of its `ERROR_*` codes, e.g. `ERROR_INVALID_CLIENT_TOKEN`),
     * [action] (which SDK call was in flight), [invalidFields], and [sessionId] — all of which matter
     * for telling apart e.g. a category/token mismatch from a transient load failure.
     */
    private fun KlarnaPaymentsSDKError.describe(): String = buildString {
        append(name).append(": ").append(message)
        action?.let { append(" (action=").append(it).append(")") }
        if (!invalidFields.isNullOrEmpty()) append(" invalidFields=").append(invalidFields)
        sessionId?.let { append(" sessionId=").append(it) }
        append(" fatal=").append(isFatal)
    }

    /**
     * Build the request (`/init`) against [host], stash its `step_id` **and** [authorizeWidget]
     * into `PartnerUiContext.collected` (both keyed for [KlarnaPlugin] to read at `preValidation`/
     * `postValidation` time), and inflate the native widget into [container]. Call once the session
     * is initialized — this is also the reset point for [submitting], mirroring
     * `GooglePayCoordinator.prepare`.
     */
    fun prepare(host: KlarnaHost) {
        this.host = host
        submitting = false
        activity.lifecycleScope.launch {
            try {
                val context = host.buildContext()
                partnerWireValue = context.partner.wireValue
                val plugin = KlarnaPlugin(context.partner)
                val request = withContext(Dispatchers.IO) { plugin.buildRequest(context) }
                logClientTokenPayload(request.clientToken)

                val paymentView = KlarnaPaymentView(activity, KlarnaMapper.PAYMENT_METHOD_CATEGORY, callback)
                paymentView.returnURL = host.returnUrl
                container.removeAllViews()
                // MATCH_PARENT width / WRAP_CONTENT height — exactly Klarna's own documented sample
                // (see "Render payment view" at the native-view doc). The SDK resizes the widget to
                // its actual content height itself; a fixed/MATCH_PARENT height isn't needed and
                // deviates from the documented integration.
                container.addView(paymentView, ViewGroup.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                view = paymentView

                // Both entries together — host.setCollected replaces the whole map for this item,
                // so stashing them separately would drop one.
                val authorize: KlarnaAuthorize = { authorizeWidget(paymentView) }
                host.setCollected(
                    mapOf(
                        KlarnaPlugin.COLLECTED_STEP_ID to request.stepId,
                        KlarnaPlugin.COLLECTED_AUTHORIZE to authorize,
                    ),
                )

                paymentView.initialize(request.clientToken)
            } catch (e: Exception) {
                listener.onError(e.message)
            }
        }
    }

    /**
     * Calls Klarna's own `authorize()`, suspending until `onAuthorized`/`onFinalized` resolves it.
     * Invoked by [KlarnaPlugin.postValidation] — *not* called directly by [submit] — since it only
     * runs once `/validate` itself already succeeded (see class doc).
     */
    private suspend fun authorizeWidget(paymentView: KlarnaPaymentView): Boolean =
        suspendCancellableCoroutine { cont ->
            pendingAuthorize = cont
            paymentView.authorize(sessionData = null, autoFinalize = true)
        }

    /**
     * The one call that now does everything: `/validate`, then — inside [KlarnaPlugin]'s own
     * `postValidation`, only if `/validate` succeeded — [authorizeWidget]. This just resolves
     * whatever redirect the (already-authorized) result carries. Call once [prepare] reported ready
     * and the shopper tapped the checkout's own submit action.
     */
    fun submit() {
        if (submitting) return
        val host = host ?: return listener.onError("call prepare() first")
        if (view == null) return listener.onError("call prepare() first")
        submitting = true
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { host.submitPayment() }
                resolveOutcome(result, host)
            } catch (e: KlarnaAuthorizationDeclined) {
                submitting = false
                listener.onCanceled()
            } catch (e: Exception) {
                // `/validate` never landed, errored, or Klarna's own authorize() failed outright —
                // safe to let the shopper retry.
                submitting = false
                listener.onError(e.message)
            }
        }
    }

    private fun resolveOutcome(result: ValidationResult, host: KlarnaHost) {
        submitting = false
        Log.d(TAG, "validate response: global_status=${result.globalStatus} redirection=${result.raw.optJSONObject("redirection")}")
        RedirectionResolver.extractWidgetConfirmationPaymentId(
            validateResponse = result.raw,
            partnerWireValue = partnerWireValue.orEmpty(),
            methodWireValue = KlarnaMapper.METHOD,
        )?.let { listener.onPaymentId(it) }
        val action = RedirectionResolver.resolve(
            validateResponse = result.raw,
            partnerWireValue = partnerWireValue.orEmpty(),
            methodWireValue = KlarnaMapper.METHOD,
            sessionReturnUrl = host.returnUrl,
        )?.withReturnUrlPrefix(host.returnUrl)
        if (action != null) {
            listener.onRequiresAction()
            redirectLauncher.launch(action)
        } else {
            listener.onResult("global_status=${result.globalStatus}", result.globalStatus)
        }
    }

    /**
     * Klarna's `client_token` is itself a JWT — logging its decoded payload is the fastest way to
     * see what Klarna's own session creation actually computed server-side for this purchase
     * (`payment_method_categories`, purchase country/currency, order lines), which is the real
     * signal behind the widget silently falling back to a generic "see our terms" info card instead
     * of an installment price breakdown (e.g. an empty `payment_method_categories`, or one that
     * doesn't include an installments product for this amount/market). Not shown to the shopper —
     * `Log.d` only; unsigned read, same rationale as `RedirectAction`'s `purse-redirection-data` decode.
     */
    private fun logClientTokenPayload(clientToken: String) {
        val payloadSegment = clientToken.split(".").getOrNull(1) ?: return
        try {
            val padded = payloadSegment + "=".repeat((4 - payloadSegment.length % 4) % 4)
            val json = String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
            Log.d(TAG, "klarna_client_token payload: ${JSONObject(json).toString(2)}")
        } catch (e: Exception) {
            Log.d(TAG, "klarna_client_token isn't a decodable JWT (${e.message}) — value: $clientToken")
        }
    }

    private fun onRedirectReturn(result: RedirectionCheckoutResult) {
        when (result) {
            // Same caveat as GooglePayCoordinator.onRedirectReturn — Approved means the shopper came
            // back, not that the payment succeeded; re-check status independently.
            RedirectionCheckoutResult.Approved -> listener.onResult("returned from Klarna redirect — check the session status independently to confirm the outcome")
            RedirectionCheckoutResult.Canceled -> listener.onCanceled()
            is RedirectionCheckoutResult.Failed -> listener.onError(result.message)
        }
    }

    private companion object {
        const val TAG = "KlarnaCoordinator"
    }
}
