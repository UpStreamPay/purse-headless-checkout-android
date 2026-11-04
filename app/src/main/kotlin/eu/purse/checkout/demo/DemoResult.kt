package eu.purse.checkout.demo

import eu.purse.checkout.PurseCheckoutException
import eu.purse.partnerui.PaymentOutcome

/**
 * What the demo shows on screen 3. Three outcomes, not two, because a payment flow can genuinely end
 * without an answer: when the shopper comes back from a partner redirect (3-D Secure, Klarna, an
 * external banking app), the coordinators deliberately report only "they returned" — the real status
 * has to be confirmed by the merchant backend, alfred here (see `GooglePayCoordinator.onRedirectReturn`'s doc and
 * `docs/decisions/pain-points-recap.md`). Rendering that as success would be a lie; as failure, a
 * different one. Hence [Unconfirmed], which points at the transactions lookup instead.
 */
sealed interface DemoResult {

    /** Which payment method produced this outcome, for display ("Klarna", "Card", "Google Pay"). */
    val method: String

    /**
     * The payment is good: either `/validate` came back `SUCCESS` with no follow-up, or an
     * [Unconfirmed] one was later settled by the session's own transactions (see
     * `DemoController.confirmOutcomeFrom`). [detail] says which.
     */
    data class Authorized(override val method: String, val detail: String) : DemoResult

    /** `/validate` failed, errored, or the partner SDK did. [detail] is what to show under the title. */
    data class Failed(override val method: String, val detail: String) : DemoResult

    /** The flow ended with no trustworthy status — [detail] says why. */
    data class Unconfirmed(override val method: String, val detail: String) : DemoResult

    companion object {
        /**
         * Maps the SDK's sealed [PaymentOutcome] onto the three cases above: a redirect return, and a
         * `/validate` that left the device unanswered, are exactly the [Unconfirmed] case.
         */
        fun of(method: String, outcome: PaymentOutcome): DemoResult = when (outcome) {
            is PaymentOutcome.Completed ->
                if (outcome.isSuccess) {
                    Authorized(method, "global_status = ${outcome.result.globalStatus}")
                } else {
                    Failed(method, "global_status = ${outcome.result.globalStatus}")
                }
            PaymentOutcome.ReturnedFromRedirect ->
                Unconfirmed(method, "returned from the redirect — the session's transactions decide")
            is PaymentOutcome.Failed ->
                if ((outcome.error as? PurseCheckoutException.ValidateFailed)?.outcomeUnknown == true) {
                    Unconfirmed(method, "no answer to /validate — the payment may exist: ${outcome.error.message}")
                } else {
                    Failed(method, "${outcome.error.code}: ${outcome.error.message}")
                }
            PaymentOutcome.Canceled -> Failed(method, "canceled by the shopper")
        }
    }
}
