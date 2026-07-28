package eu.purse.checkout.demo

/**
 * What the demo shows on screen 3. Three outcomes, not two, because a payment flow can genuinely end
 * without an answer: when the shopper comes back from a partner redirect (3-D Secure, Klarna, an
 * external banking app), the coordinators deliberately report only "they returned" — the real status
 * has to be re-checked against the session (see `GooglePayCoordinator.onRedirectReturn`'s doc and
 * `docs/decisions/pain-points-recap.md`). Rendering that as success would be a lie; as failure, a
 * different one. Hence [Unconfirmed], which points at the transactions lookup instead.
 */
sealed interface PaymentOutcome {

    /** Which payment method produced this outcome, for display ("Klarna", "Card", "Google Pay"). */
    val method: String

    /**
     * The payment is good: either `/validate` came back `SUCCESS` with no follow-up, or an
     * [Unconfirmed] one was later settled by the session's own transactions (see
     * `DemoController.confirmOutcomeFrom`). [detail] says which.
     */
    data class Authorized(override val method: String, val detail: String) : PaymentOutcome

    /** `/validate` failed, errored, or the partner SDK did. [detail] is what to show under the title. */
    data class Failed(override val method: String, val detail: String) : PaymentOutcome

    /** The flow ended with no trustworthy status — [detail] says why. */
    data class Unconfirmed(override val method: String, val detail: String) : PaymentOutcome

    companion object {
        /**
         * Maps a coordinator's `onResult(summary, globalStatus)` onto the three cases above: a `null`
         * [globalStatus] means the result came from a redirect return rather than a `/validate`
         * response, which is exactly the [Unconfirmed] case.
         */
        fun of(method: String, summary: String, globalStatus: String?): PaymentOutcome = when {
            globalStatus == null -> Unconfirmed(method, summary)
            globalStatus.equals("SUCCESS", ignoreCase = true) -> Authorized(method, "global_status = $globalStatus")
            else -> Failed(method, "global_status = $globalStatus")
        }
    }
}
