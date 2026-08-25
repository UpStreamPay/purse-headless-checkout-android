package eu.purse.partnerui.plugins.generics.redirection

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import eu.purse.core.payload.ValidationResult
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PluginEvent
import eu.purse.partnerui.redirect.RedirectionCheckoutContract
import eu.purse.partnerui.redirect.RedirectionCheckoutResult
import eu.purse.partnerui.redirect.RedirectionResolver
import eu.purse.partnerui.redirect.withReturnUrlPrefix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the interactive half of the generic **redirection** template, gluing [RedirectionPlugin]'s
 * protocol to the shared [RedirectionCheckoutContract]:
 *
 * 1. [prepare] — `plugin.buildRequest` (`/init`), then stashes the `step_id` and the
 *    `partner_data.browser` block into `PartnerUiContext.collected` and reports ready. Nothing is
 *    rendered: this template has no form and no vendor widget, which is why [Listener.onReady] fires
 *    as soon as `/init` returns (web dispatches its `ready` **and** `fulfilled` events at the very
 *    same point, for the same reason).
 * 2. [submit] — one [RedirectionHost.submitPayment] call, then follow whatever redirect the
 *    `/validate` response carries. For every other plugin that redirect is an *extra* 3-D Secure hop
 *    after a native step; here it is the payment step itself, so a response with no redirect at all
 *    is worth surfacing loudly (see [resolveOutcome]).
 *
 * Knows nothing about `:headless-checkout` — [RedirectionHost] is the seam (see its doc).
 *
 * **Construct this in the host Activity's `onCreate`** — it registers an `ActivityResult` launcher,
 * which must happen before the Activity is STARTED. The host is supplied later, per payment, via
 * [prepare]. Outcomes are reported on [listener].
 *
 * One coordinator drives one item at a time. A session offering two redirection methods (say Bizum
 * *and* Oney) reuses this same instance: [prepare] rebinds it to whichever item the shopper selected,
 * exactly as the demo does when switching rows.
 */
class RedirectionCoordinator private constructor(
    private val activity: ComponentActivity,
    private val listener: PaymentElementListener,
    registerLaunchers: Boolean,
) {
    /**
     * Same contract as [PaymentElementListener] — kept as a nested name for source compatibility.
     * `onReady` means `/init` succeeded (there is no form, the shopper can submit immediately);
     * `onRequiresAction` is the PSP's hosted page opening — for this template, the payment step.
     */
    interface Listener : PaymentElementListener

    /** Imperative construction — must run in the Activity's `onCreate`, before it is STARTED. */
    constructor(activity: ComponentActivity, listener: PaymentElementListener) :
        this(activity, listener, registerLaunchers = true)

    /** Launch seam — see [GooglePayCoordinator]'s twin fields for the rationale. */
    internal var launchRedirect: ((eu.purse.partnerui.redirect.RedirectAction) -> Unit)? = null

    init {
        if (registerLaunchers) {
            val redirectLauncher =
                activity.registerForActivityResult(RedirectionCheckoutContract()) { onRedirectReturn(it) }
            launchRedirect = { action -> redirectLauncher.launch(action) }
        }
    }

    private var host: RedirectionHost? = null
    private var request: RedirectionRequest? = null

    /** Report a lifecycle event to the headless host (which deactivates/reacts, web-parity). */
    private fun dispatch(event: PluginEvent) {
        runCatching { host?.buildContext()?.dispatchEvent(event) }
    }

    /** Same re-entrancy guard as `GooglePayCoordinator.submitting` — see its doc. */
    private var submitting = false

    /**
     * `POST /init` against [host], stash its `step_id` + the browser block for [RedirectionPlugin] to
     * read at `preValidation` time, and report ready. Call when the shopper selects this method (an
     * unselected redirection row should do no `/init` at all — it costs a network round-trip and
     * consumes a step against the session).
     */
    fun prepare(host: RedirectionHost) {
        this.host = host
        submitting = false
        request = null
        activity.lifecycleScope.launch {
            try {
                val context = host.buildContext()
                val plugin = RedirectionPlugin(context.partner, context.method)
                val browser = RedirectionMapper.browserData(activity, context.options.locale)
                val req = withContext(Dispatchers.IO) { plugin.buildRequest(context, browser) }
                request = req
                Log.d(TAG, "prepared ${req.partnerWireValue}-${req.methodWireValue} step_id=${req.stepId}")

                // Both entries together — host.setCollected replaces the whole map for this item,
                // so stashing them separately would drop one.
                host.setCollected(
                    mapOf(
                        RedirectionPlugin.COLLECTED_STEP_ID to req.stepId,
                        RedirectionPlugin.COLLECTED_BROWSER to browser,
                    ),
                )
                listener.onReady(true)
            } catch (e: Exception) {
                dispatch(PluginEvent.InitFailed(e.message))
                listener.onError(e.message)
            }
        }
    }

    /**
     * `/validate`, then follow the redirect it carries. Call after [prepare] reported ready and the
     * shopper tapped the checkout's own submit action.
     */
    fun submit() {
        if (submitting) return
        val host = host ?: return listener.onError("call prepare() first")
        val request = request ?: return listener.onError("call prepare() first")
        submitting = true
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { host.submitPayment() }
                resolveOutcome(result, request, host)
            } catch (e: Exception) {
                // `/validate` never landed or errored — safe to let the shopper retry.
                submitting = false
                listener.onError(e.message)
            }
        }
    }

    /**
     * Identical resolution to the other three coordinators — same [RedirectionResolver], same priority
     * order — with one difference in what "no redirect" means. For Google Pay or a card, a `/validate`
     * that asks for nothing further is the *happy path*: the payment already authorized. For this
     * template the redirect **is** the payment, so the same shape means the PSP never handed over a
     * page to send the shopper to. That isn't reported as an error — `global_status` may well say
     * `SUCCESS` for an amount that needed no interaction — but it is logged and carried into the
     * summary, because it's the first thing to check when a redirection method silently does nothing.
     */
    private fun resolveOutcome(result: ValidationResult, request: RedirectionRequest, host: RedirectionHost) {
        submitting = false
        Log.d(
            TAG,
            "validate response: global_status=${result.globalStatus} " +
                "redirection=${result.raw.optJSONObject("redirection")}",
        )
        RedirectionResolver.extractWidgetConfirmationPaymentId(
            validateResponse = result.raw,
            partnerWireValue = request.partnerWireValue,
            methodWireValue = request.methodWireValue,
        )?.let { listener.onPaymentId(it) }

        val action = RedirectionResolver.resolve(
            validateResponse = result.raw,
            partnerWireValue = request.partnerWireValue,
            methodWireValue = request.methodWireValue,
            sessionReturnUrl = host.redirectionReturnUrl,
        )?.withReturnUrlPrefix(host.redirectionReturnUrl)

        if (action != null) {
            dispatch(PluginEvent.Redirect)
            listener.onRequiresAction()
            launchRedirect?.invoke(action)
        } else {
            Log.w(TAG, "no redirect to follow — a redirection method's /validate normally carries one")
            listener.onResult(
                "global_status=${result.globalStatus} (no redirect returned)",
                result.globalStatus,
            )
        }
    }

    internal fun onRedirectReturn(result: RedirectionCheckoutResult) {
        when (result) {
            // Same caveat as the other coordinators — Approved means the shopper came back, not that
            // the payment succeeded; re-check status independently.
            RedirectionCheckoutResult.Approved ->
                listener.onResult("returned from the payment page — check the session status independently to confirm the outcome")

            RedirectionCheckoutResult.Canceled -> {
                dispatch(PluginEvent.Cancelled)
                listener.onCanceled()
            }
            is RedirectionCheckoutResult.Failed -> listener.onError(result.message)
        }
    }

    internal companion object {
        const val TAG = "RedirectionCoordinator"

        /** Compose factory seam — [rememberRedirectionCoordinator] injects a managed launcher. */
        fun forCompose(activity: ComponentActivity, listener: PaymentElementListener) =
            RedirectionCoordinator(activity, listener, registerLaunchers = false)
    }
}
