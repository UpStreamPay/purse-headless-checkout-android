package eu.purse.dropin.payment.split

import java.math.BigDecimal

/**
 * Runtime payment-split state for a session: how much of the [orderAmount] the applied secondary
 * (co-payment) instruments — gift cards, vouchers — already cover, and therefore how much a primary
 * method still has to pay.
 *
 * Ports the slice of usp-widget headless-checkout's `PursePaymentSplitter` this POC needs:
 * [remaining] mirrors its `primaryReach` / the `remainingAmountToPay` store, and [isFulfilled]
 * mirrors `isPaymentFulfilled`. The Drop-in UI holds a [PaymentSplitter] and reads these rather than
 * tracking the split itself — mirroring how web's UI consumes the splitter's reactive stores.
 *
 * `PaymentSessionBuilder` computes each method's build-time disabled state against the full amount
 * (no split has happened yet); recomputing those against a live [PaymentSplitter] as the shopper
 * adds secondaries is future work — see docs/architecture/session-and-protocols.md.
 *
 * Not thread-safe: drive it from a single (UI) thread. Amounts are in major currency units, the
 * same convention as the session amounts.
 */
class PaymentSplitter(val orderAmount: BigDecimal) {

    /** Total already covered by applied secondaries, never more than [orderAmount]. */
    var secondaryContribution: BigDecimal = BigDecimal.ZERO
        private set

    /** Amount still owed by a primary — [orderAmount] minus what secondaries cover, floored at zero. */
    val remaining: BigDecimal get() = (orderAmount - secondaryContribution).max(BigDecimal.ZERO)

    /** True once applied secondaries cover the whole order amount — no primary method is needed. */
    val isFulfilled: Boolean get() = remaining.signum() <= 0

    /**
     * Apply a secondary instrument's [balance]. A secondary covers up to the [remaining] amount and
     * never more (overflow is dropped), matching web's splitter.
     */
    fun applySecondary(balance: BigDecimal) {
        secondaryContribution += balance.min(remaining)
    }
}
