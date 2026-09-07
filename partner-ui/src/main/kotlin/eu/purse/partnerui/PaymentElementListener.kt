// Web: packages/headless-checkout payment-element events (ready, fulfilled, redirect, cancelled, …)
// Same: one outcome contract for every method.
// Differs: callbacks instead of an event emitter; `onResult(null)` after a redirect return.
package eu.purse.partnerui

/**
 * The one outcome contract every payment surface reports on — card, Google Pay, PayPal, Klarna and
 * redirection coordinators all share this exact shape, so the unified payment element (web's
 * `getPaymentElement` analog) can drive any of them behind a single listener.
 *
 * Every member has a default: implement only what your screen reacts to.
 */
interface PaymentElementListener {

    /**
     * The method's own readiness changed — meaning per method: a card form's validity, Google
     * Pay's `isReadyToPay`, a prepared PayPal/Klarna/redirection flow. Gate your Pay affordance
     * on it (self-submitting methods render their own button and gate themselves).
     */
    fun onReady(ready: Boolean) {}

    /**
     * Terminal outcome of a submission — [summary] is human-readable; [globalStatus] is the
     * `/validate` response's own `global_status` when the outcome came straight from one, and
     * `null` after a redirect/3-D Secure return, where the real outcome must be re-checked against
     * the session (see `PurseHeadlessCheckout.awaitPaymentOutcome`).
     */
    fun onResult(summary: String, globalStatus: String? = null) {}

    /** A 3-D Secure / PSP page step is being launched (informational). */
    fun onRequiresAction() {}

    /**
     * The `payment_id` behind this payment, when the flow could read one — purely informational
     * (e.g. prefilling a capture tool), never required for the payment itself to resolve.
     */
    fun onPaymentId(paymentId: String) {}

    /** The shopper backed out (sheet dismissed, approval abandoned, redirect page closed). */
    fun onCanceled() {}

    fun onError(message: String?) {}
}
