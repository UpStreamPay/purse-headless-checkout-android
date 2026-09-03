package eu.purse.partnerui.plugins.partners.paypal.wallet

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import eu.purse.core.environment.PurseEnvironment
import eu.purse.core.payload.ValidationResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PluginEvent
import eu.purse.partnerui.redirect.RedirectAction
import eu.purse.partnerui.redirect.RedirectionCheckoutContract
import eu.purse.partnerui.redirect.RedirectionCheckoutResult
import eu.purse.partnerui.redirect.RedirectDispatcher
import eu.purse.partnerui.redirect.RedirectionResolver
import eu.purse.partnerui.redirect.withReturnUrlPrefix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the interactive PayPal wallet flow end to end, gluing the [PaypalPlugin] protocol to
 * PayPal's own browser switch ([PaypalCheckoutContract]) and the chained-3DS
 * [RedirectionCheckoutContract]:
 *
 * 1. [prepare] — stashes the host. Deliberately does **no** network work; see [submit].
 * 2. [submit] — `plugin.buildRequest` (`/init`, creating the PayPal order) then launches PayPal's
 *    approval.
 * 3. on approval — hands the `step_id` and approved order to the [PaypalHost]
 *    ([PaypalHost.setCollected]) and calls [PaypalHost.submitPayment], which `/validate`s all splits
 *    together and captures.
 * 4. if `/validate` returns a redirection — launches the 3-D Secure Activity, then reports that the
 *    shopper returned (not the payment outcome — see [onRedirectReturn]'s doc).
 *
 * Knows nothing about `:headless-checkout` — [PaypalHost] is the seam (see its doc).
 *
 * **Construct this in the host Activity's `onCreate`** — it registers `ActivityResult` launchers,
 * which must happen before the Activity is STARTED. The host is supplied later, per payment, via
 * [prepare]. Outcomes are reported on [listener].
 */
class PaypalCoordinator private constructor(
    private val activity: ComponentActivity,
    private val listener: PaymentElementListener,
    registerLaunchers: Boolean,
) {
    /**
     * Same contract as [PaymentElementListener] — kept as a nested name for source compatibility.
     * `onReady` means PayPal is usable for this session; `onResult` with a `null` globalStatus is
     * a redirect return whose real outcome must be re-checked against the session.
     */
    interface Listener : PaymentElementListener

    /** Imperative construction — must run in the Activity's `onCreate`, before it is STARTED. */
    constructor(activity: ComponentActivity, listener: PaymentElementListener) :
        this(activity, listener, registerLaunchers = true)

    /** Launch seams — see [GooglePayCoordinator]'s twin fields for the rationale. */
    internal var launchPaypal: ((PaypalCheckoutInput) -> Unit)? = null
    internal var launchRedirect: ((RedirectAction) -> Unit)? = null

    init {
        if (registerLaunchers) {
            val paypalLauncher =
                activity.registerForActivityResult(PaypalCheckoutContract()) { onPaypalResult(it) }
            val redirectLauncher =
                activity.registerForActivityResult(RedirectionCheckoutContract()) { onRedirectReturn(it) }
            launchPaypal = { input -> paypalLauncher.launch(input) }
            launchRedirect = { action -> redirectLauncher.launch(action) }
        }
    }

    /** PayPal is prepared for this session, as Compose state — what [PaypalButton] shows itself on. */
    var isReady: Boolean by androidx.compose.runtime.mutableStateOf(false)
        internal set

    private var host: PaypalHost? = null
    private var partnerWireValue: String? = null

    /** The `/init` step id, held between [submit] and [onPaypalResult]. */
    private var pendingStepId: Int? = null

    /**
     * Set for the span from [submit] to a terminal outcome — guards against a double tap firing two
     * approvals, which would `POST /init` twice and leave an orphan PayPal order. Reset on
     * cancel/error so the shopper can retry; left `true` after a successful submit since that item
     * is done.
     */
    private var submitting = false

    /**
     * Attach [host] and report readiness. Unlike Klarna's `prepare`, this performs **no** `/init`:
     * PayPal only needs its `order_id` at approval time, and creating the order when the shopper
     * merely selects PayPal would leave an orphan order behind every time they change their mind.
     * The `/init` therefore happens in [submit], on the button tap.
     *
     * This is also the reset point for [submitting], so a coordinator reused across sessions (as the
     * demo does) isn't left permanently blocked after a prior session's successful submit.
     */
    fun prepare(host: PaypalHost) {
        this.host = host
        submitting = false
        partnerWireValue = host.buildContext().partner.wireValue
        isReady = true
        listener.onReady(true)
    }

    /** Report a lifecycle event to the headless host (which deactivates/reacts, web-parity). */
    private fun dispatch(event: PluginEvent) {
        runCatching { host?.buildContext()?.dispatchEvent(event) }
    }

    /**
     * `POST /init`, then launch PayPal's approval. Call when the branded button is tapped. A no-op
     * while a previous tap's flow is still in progress — see [submitting].
     */
    fun submit() {
        if (submitting) return
        val host = host ?: return listener.onError("call prepare() first")
        submitting = true
        activity.lifecycleScope.launch {
            try {
                val context = host.buildContext()
                partnerWireValue = context.partner.wireValue
                val plugin = PaypalPlugin(context.partner)
                // Read the client id BEFORE /init: a session missing it can never reach the approval
                // step, and failing first means we don't leave an orphan PayPal order behind.
                val config = PaypalMapper.parseSessionConfig(context.session, context.partner, context.method)
                val request = withContext(Dispatchers.IO) { plugin.buildRequest(context) }
                // Held rather than stashed: setCollected replaces the whole map for this item, so the
                // step id travels with the approval's own output in one call (see onPaypalResult).
                pendingStepId = request.stepId
                listener.onRequiresAction()
                launchPaypal?.invoke(
                    PaypalCheckoutInput(
                        orderId = request.orderId,
                        clientId = config.clientId,
                        sandbox = host.environment != PurseEnvironment.PRODUCTION,
                    ),
                )
            } catch (e: Exception) {
                submitting = false
                dispatch(PluginEvent.InitFailed(e.message))
                listener.onError(e.message)
            }
        }
    }

    internal fun onPaypalResult(result: PaypalCheckoutResult) {
        val host = host ?: run { submitting = false; return listener.onError("not prepared") }
        when (result) {
            is PaypalCheckoutResult.Approved -> {
                host.setCollected(
                    mapOf(
                        PaypalPlugin.COLLECTED_STEP_ID to pendingStepId,
                        PaypalPlugin.COLLECTED_ORDER_ID to result.orderId,
                        PaypalPlugin.COLLECTED_PAYER_ID to result.payerId,
                    ),
                )
                // The approved order both selects the method and pays with it — web's
                // `validationRequested` → setPrimary + submit. A cancel never reaches this line.
                host.setAsPrimary()
                capture(host)
            }

            PaypalCheckoutResult.Canceled -> {
                submitting = false
                dispatch(PluginEvent.Cancelled)
                listener.onCanceled()
            }

            is PaypalCheckoutResult.Failed -> {
                submitting = false
                dispatch(PluginEvent.ClientError(result.message))
                listener.onError(result.message)
            }
        }
    }

    /** `POST /validate` — the capture, once the buyer has approved the order. */
    private fun capture(host: PaypalHost) {
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { host.submitPayment() }
                resolveOutcome(result, host)
            } catch (e: Exception) {
                // `/validate` never landed (or errored) — safe to let the shopper retry.
                submitting = false
                listener.onError(e.message)
            }
        }
    }

    private suspend fun resolveOutcome(result: ValidationResult, host: PaypalHost) {
        Log.d(
            TAG,
            "validate response: global_status=${result.globalStatus} " +
                "redirection=${result.raw.optJSONObject("redirection")}",
        )
        RedirectionResolver.extractWidgetConfirmationPaymentId(
            validateResponse = result.raw,
            partnerWireValue = partnerWireValue.orEmpty(),
            methodWireValue = PaypalMapper.METHOD,
        )?.let { listener.onPaymentId(it) }

        val action = RedirectionResolver.resolve(
            validateResponse = result.raw,
            partnerWireValue = partnerWireValue.orEmpty(),
            methodWireValue = PaypalMapper.METHOD,
            sessionReturnUrl = host.redirectionReturnUrl,
        )?.withReturnUrlPrefix(host.redirectionReturnUrl)
            // A PayPal `/validate` echoes the order's own approval URL back in `redirection.url`,
            // even on `global_status = SUCCESS` — confirmed against a real response, which returned
            // `checkoutnow?token=<the very order the buyer had just approved>`. That's the *web*
            // flow's popup URL: web's paypal plugin never runs it through `manageRedirection`, it
            // just navigates to the merchant's success page. Following it natively re-opens PayPal
            // on an already-approved order and books a second payment step. So this plugin, which
            // owns the approval itself, always drops it — while still following a genuine hop
            // elsewhere (a chained ACS/3-D Secure challenge), which is never a PayPal-hosted URL.
            ?.takeUnless { it.targetUrl()?.isPaypalApprovalUrl() == true }

        if (action != null) {
            // The merchant's onRedirection hook goes first (web's redirectionHandler); the SDK's
            // WebView only opens when it declines.
            when (val outcome = RedirectDispatcher.dispatch(action, host, launchRedirect)) {
                is RedirectDispatcher.Outcome.HookFailed -> {
                    // Nothing was opened — the shopper can retry once the merchant fixes it.
                    submitting = false
                    listener.onError("onRedirection hook failed: ${outcome.error.message}")
                }
                else -> {
                    dispatch(PluginEvent.Redirect)
                    listener.onRequiresAction()
                }
            }
        } else {
            listener.onResult("global_status=${result.globalStatus}", result.globalStatus)
        }
    }

    internal fun onRedirectReturn(result: RedirectionCheckoutResult) {
        when (result) {
            // Approved only means the shopper came back — NOT the payment outcome. It deliberately
            // does not chase that outcome itself: a real 3-D Secure challenge's own final redirect is
            // constructed server-side (by the bank/PSP), and a confirmed backend pain point is that
            // this can land on a Purse API gateway URL that 401s on the session's own client-facing
            // api_key — nothing fixable client-side. Report status independently instead (e.g. this
            // demo's own alfred-backed "Get Transactions" tool) rather than surface that as an error
            // here.
            RedirectionCheckoutResult.Approved -> listener.onResult("returned from 3-D Secure — check the session status independently to confirm the outcome")

            RedirectionCheckoutResult.Canceled -> {
                dispatch(PluginEvent.Cancelled)
                listener.onCanceled()
            }
            is RedirectionCheckoutResult.Failed -> listener.onError(result.message)
        }
    }

    internal companion object {
        const val TAG = "PaypalCoordinator"

        /** Compose factory seam — [rememberPaypalCoordinator] injects managed launchers. */
        fun forCompose(activity: ComponentActivity, listener: PaymentElementListener) =
            PaypalCoordinator(activity, listener, registerLaunchers = false)
    }
}

/** The URL a [RedirectAction] would navigate to; `null` for injected-HTML actions, which have none. */
private fun RedirectAction.targetUrl(): String? = when (this) {
    is RedirectAction.Get -> url
    is RedirectAction.PostForm -> url
    is RedirectAction.PostHtml -> null
}

private fun String.isPaypalApprovalUrl(): Boolean = PaypalMapper.isApprovalUrl(this)
