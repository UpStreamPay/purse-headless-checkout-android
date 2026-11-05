// Web: none — web plugins render into the DOM and follow redirects by navigating the page.
// Same: `/validate` through the checkout, then web's `manageRedirection` on the answer.
// Differs: Activity-result launchers and an in-app WebView stand in for page navigation; a
//          returned redirect is reported as "came back", never as the payment outcome.
package eu.purse.partnerui

import android.util.Log
import eu.purse.core.InternalPurseApi
import eu.purse.core.payload.ValidationResult
import eu.purse.partnerui.redirect.RedirectAction
import eu.purse.partnerui.redirect.RedirectDispatcher
import eu.purse.partnerui.redirect.RedirectionCheckoutResult
import eu.purse.partnerui.redirect.RedirectionResolver
import eu.purse.partnerui.redirect.withReturnUrlPrefix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What every method's interactive half shares: a [PluginHost], a re-entrancy guard, and the
 * `/validate` → redirect → return flow. Subclasses add only their native step (a sheet, a browser
 * switch, a widget, a card form) and call [capture] once it has produced what `/validate` needs.
 *
 * Created through the `remember*Coordinator` functions, which wire the Activity-result launchers.
 */
abstract class PaymentCoordinator protected constructor(
    private val scope: CoroutineScope,
    protected val listener: PaymentElementListener,
) {
    /** Opens the in-app redirect WebView; set by the `remember*` function that owns the launcher. */
    @InternalPurseApi
    var launchRedirect: ((RedirectAction) -> Unit)? = null

    protected var host: PluginHost? = null
        private set

    /**
     * Set from the shopper's submit gesture to a terminal outcome — a double tap must never
     * `POST /validate` twice for one session (the second answer is a `409`). Cleared on every
     * terminal listener call and on return from a redirect.
     */
    protected var submitting = false

    protected abstract val tag: String

    /** Bind [host] for the next payment and clear a previous attempt's state. */
    protected fun attach(host: PluginHost) {
        this.host = host
        submitting = false
    }

    /**
     * Whether [host] still has an amount to prepare a payment for. At 0 (a gift card covers the
     * rest) a coordinator that bakes the amount into an `/init` or a sheet request must not prepare:
     * a refused `/init` would end as `PARTNER_UI_FATAL_ERROR`, which no later amount change lifts,
     * and send a `Failed` outcome with no shopper tap. It clears what it prepared and waits for the
     * amount to move again.
     */
    protected fun hasAmountToPrepare(host: PluginHost): Boolean = host.amount.value.signum() > 0

    /** Tell the checkout what happened, so it can deactivate the method or end the session. */
    protected fun dispatch(event: PluginEvent) {
        runCatching { host?.buildContext()?.dispatchEvent(event) }
    }

    /** Whether a redirect `/validate` returned is a real next step. PayPal drops its echoed approval URL. */
    protected open fun shouldFollow(action: RedirectAction): Boolean = true

    /** `/validate` answered without a redirect. */
    protected open fun onSettled(result: ValidationResult) {
        listener.onOutcome(PaymentOutcome.Completed(result))
    }

    /**
     * `/validate` never landed or errored. Usually nothing was charged and the shopper may retry —
     * but a [PurseCheckoutException.ValidateFailed][eu.purse.checkout.PurseCheckoutException.ValidateFailed]
     * with `outcomeUnknown` means the payment may exist: the checkout then refuses a second submit;
     * the outcome is unknown, so the merchant confirms it from its backend (server-side session read)
     * and, if it did not go through, starts a new session.
     */
    protected open fun onSubmitFailed(e: Exception) {
        submitting = false
        fail(e)
    }

    /** Report a failed attempt: [error] as is when it is already an SDK error, wrapped as a partner failure otherwise. */
    protected fun fail(error: Throwable) {
        listener.onOutcome(PaymentOutcome.Failed(error.asPurseException()))
    }

    /** Report a failed attempt from a partner's own message (no exception behind it). */
    protected fun fail(message: String?) {
        listener.onOutcome(PaymentOutcome.Failed(PartnerUiException(message ?: "unexpected partner error")))
    }

    /** The shopper backed out. */
    protected fun cancel() {
        listener.onOutcome(PaymentOutcome.Canceled)
    }

    /**
     * A self-submitting flow's approval: make this item the primary, then [capture]. The checkout
     * refuses a disabled item (`METHOD_DISABLED`) — that refusal ends the attempt as a submit
     * failure instead of escaping into the result callback.
     */
    protected fun selectAndCapture(host: PluginHost) {
        try {
            host.setAsPrimary()
        } catch (e: Exception) {
            onSubmitFailed(e)
            return
        }
        capture(host)
    }

    /** `POST /validate` through [host] and act on the answer. */
    protected fun capture(host: PluginHost) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { host.submitPayment() }
                resolveOutcome(result, host)
            } catch (e: Exception) {
                onSubmitFailed(e)
            }
        }
    }

    private suspend fun resolveOutcome(result: ValidationResult, host: PluginHost) {
        val context = host.buildContext()
        val partner = context.partner.wireValue
        val method = context.method.wireValue
        Log.d(tag, "validate response: global_status=${result.globalStatus} redirection=${result.raw.optJSONObject("redirection")}")
        RedirectionResolver.extractWidgetConfirmationPaymentId(
            validateResponse = result.raw,
            partnerWireValue = partner,
            methodWireValue = method,
        )?.let { listener.onPaymentId(it) }
        val action = RedirectionResolver.resolve(
            validateResponse = result.raw,
            partnerWireValue = partner,
            methodWireValue = method,
            sessionReturnUrl = host.redirectionReturnUrl,
        )?.withReturnUrlPrefix(host.redirectionReturnUrl)?.takeIf(::shouldFollow)
        if (action == null) {
            submitting = false
            onSettled(result)
            return
        }
        // The merchant's onRedirection hook goes first (web's redirectionHandler); the SDK's WebView
        // opens only when the hook declines.
        when (val outcome = RedirectDispatcher.dispatch(action, host, launchRedirect)) {
            is RedirectDispatcher.Outcome.HookFailed -> {
                submitting = false
                fail(outcome.error)
            }
            else -> {
                dispatch(PluginEvent.Redirect)
                listener.onRequiresAction()
            }
        }
    }

    /**
     * The shopper is back from the redirect WebView. `Approved` only means they came back
     * ([PaymentOutcome.ReturnedFromRedirect]): a 3-D Secure challenge's final redirect is built
     * server-side and can land on a URL this client cannot read, so the merchant's backend confirms
     * the real outcome, never inferred here.
     */
    @InternalPurseApi
    fun onRedirectReturn(result: RedirectionCheckoutResult) {
        submitting = false
        when (result) {
            RedirectionCheckoutResult.Approved -> listener.onOutcome(PaymentOutcome.ReturnedFromRedirect)
            RedirectionCheckoutResult.Canceled -> {
                dispatch(PluginEvent.Cancelled)
                cancel()
            }
            is RedirectionCheckoutResult.Failed -> fail(result.message)
        }
    }
}
