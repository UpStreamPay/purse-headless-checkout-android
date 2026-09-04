// Web: packages/headless-checkout/src/types/headless-checkout.ts (`PurseHeadlessCheckoutHooks`)
// Same: onBeforeValidate / onAfterValidate / redirectionHandler.
// Differs: `redirectionHandler` is `onRedirection`.
package eu.purse.checkout

import eu.purse.checkout.payment.split.PaymentSplit
import eu.purse.partnerui.redirect.RedirectAction

/**
 * Merchant lifecycle hooks around the "create payment" pipeline — the Kotlin analog of web's
 * `PurseHeadlessCheckoutHooks` (`types/api/session.ts`). Pass to [PurseHeadlessCheckout.init].
 *
 * A hook that throws never corrupts the checkout: the failure is reported (as a typed
 * [PurseCheckoutException.HookFailed] where the caller can see it, and as a monitoring event
 * everywhere), the split and collected data are left untouched, and the checkout stays submittable.
 */
class PurseCheckoutHooks(
    /**
     * Runs after the client-side item payloads are prepared, right before `POST /validate` —
     * e.g. to persist the split server-side. **Throwing aborts the submit**: nothing is sent, and
     * [PurseHeadlessCheckout.submitPayment] throws [PurseCheckoutException.HookFailed] with code
     * `ON_BEFORE_VALIDATE_HOOK_FAILED` (web's wrapping) and the merchant exception as its cause;
     * the session state returns to `READY`. Skipped when a plugin auto-submits with
     * `skipOnBeforeValidate` (web's xpay path).
     */
    val onBeforeValidate: (suspend (splits: List<PaymentSplit>) -> Unit)? = null,
    /**
     * Runs after `/validate` responds, before plugin `postValidation` (and so before any
     * redirection). Errors are swallowed, matching web — reported only as a
     * `HEADLESS_CHECKOUT_CLIENT_HOOK_ERROR` monitoring event.
     */
    val onAfterValidate: (suspend () -> Unit)? = null,
    /**
     * Take a redirect over — web's `redirectionHandler`. Called with the resolved [RedirectAction]
     * (`Get` / `PostForm` / `PostHtml`, see `RedirectionResolver`) after `/validate` asked for one
     * and before the SDK opens its in-app WebView. Return `true` to handle it yourself (the SDK
     * then opens nothing, and confirming the outcome on return is yours — see
     * [PurseHeadlessCheckout.paymentStatus]); return `false` to let the SDK proceed as usual.
     * **Throwing opens nothing**: the failure reaches the payment element's listener as an error
     * (and monitoring as `HEADLESS_CHECKOUT_CLIENT_HOOK_ERROR`), the split stays intact, and the
     * shopper can submit again.
     */
    val onRedirection: (suspend (action: RedirectAction) -> Boolean)? = null,
)
