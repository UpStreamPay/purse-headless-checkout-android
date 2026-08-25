package eu.purse.checkout

import eu.purse.checkout.payment.split.PaymentSplit

/**
 * Merchant lifecycle hooks around the "create payment" pipeline — the Kotlin analog of web's
 * `PurseHeadlessCheckoutHooks` (`types/api/session.ts`). Pass to [PurseHeadlessCheckout.init].
 */
class PurseCheckoutHooks(
    /**
     * Runs after the client-side item payloads are prepared, right before `POST /validate` —
     * e.g. to persist the split server-side. **Throwing aborts the submit** (the exception
     * propagates out of [PurseHeadlessCheckout.submitPayment]); web wraps that as
     * `ON_BEFORE_VALIDATE_HOOK_FAILED`. Skipped when a plugin auto-submits with
     * `skipOnBeforeValidate` (web's xpay path).
     */
    val onBeforeValidate: (suspend (splits: List<PaymentSplit>) -> Unit)? = null,
    /**
     * Runs after `/validate` responds, before plugin `postValidation` (and so before any
     * redirection). Errors are swallowed, matching web.
     */
    val onAfterValidate: (suspend () -> Unit)? = null,
)
