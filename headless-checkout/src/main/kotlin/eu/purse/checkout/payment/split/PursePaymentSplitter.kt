// Web: packages/headless-checkout/src/session/payment/splitter/payment-splitter.ts
// Same: one primary + N secondaries, secondaries first, primary absorbs the rest.
// Differs: integer cents; no autofill middlewares.
package eu.purse.checkout.payment.split

import eu.purse.checkout.payment.secondary.SecondaryTakePolicy
import eu.purse.checkout.payment.secondary.SecondaryToken
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The payment split for a session — the Kotlin analog of web headless-checkout's
 * `PursePaymentSplitter`. Model: **one primary + N secondaries**. Applied secondary tokens (gift
 * cards, vouchers) are drawn down first; the primary absorbs whatever is left ([primaryReach]).
 *
 * All arithmetic is in **integer cents** to avoid binary-float drift, matching web; public amounts
 * are `BigDecimal` major-currency units at 2-dp scale. Not thread-safe — drive it from one thread
 * (the SDK does so behind [eu.purse.checkout.PurseHeadlessCheckout]).
 *
 * The voucher → loyalty → primary autofill middleware chain web runs over the splits is a
 * documented extension point; only the per-instrument [SecondaryTakePolicy] is modelled so far.
 */
class PursePaymentSplitter(orderAmount: BigDecimal) {

    private var amountCents: Long = toCents(orderAmount)
    private var primary: PrimarySource? = null

    /** Applied secondary tokens, keyed by token id, in insertion order. */
    private val secondaries = LinkedHashMap<String, Pair<SecondaryToken, Long>>()

    val orderAmount: BigDecimal get() = fromCents(amountCents)

    /** Total already covered by applied secondaries, never more than the order amount. */
    val secondaryContribution: BigDecimal get() = fromCents(secondaryContributionCents())

    /** Amount a primary still has to pay — order minus what secondaries cover, floored at zero. */
    val primaryReach: BigDecimal get() = fromCents(remainingCents())

    /** Alias of [primaryReach], the amount still owed. */
    val remaining: BigDecimal get() = primaryReach

    /** The current primary — a method or a saved token. */
    val primaryMethod: PrimarySource? get() = primary

    /** The applied secondary tokens, in the order they were added. */
    val appliedSecondaries: List<SecondaryToken> get() = secondaries.values.map { it.first }

    /**
     * True once payment can be submitted: a zero-amount session, secondaries covering the whole
     * amount, or a primary selected to cover the remainder — mirroring web's completion rule.
     */
    val fulfilled: Boolean get() = amountCents == 0L || remainingCents() == 0L || primary != null

    /** The submittable set: each applied token's slice, plus the primary's remainder when one is set. */
    val splits: List<PaymentSplit>
        get() {
            val list = secondaries.values.map { (token, cents) -> PaymentSplit(fromCents(cents), token) }
                .toMutableList()
            val remainder = remainingCents()
            primary?.let { if (remainder > 0L) list += PaymentSplit(fromCents(remainder), it) }
            return list
        }

    fun setPrimary(source: PrimarySource?) {
        primary = source
    }

    /**
     * Draw an applied [token] down against what's left. The draw is capped at the token's balance,
     * its per-session limit, and the remaining amount. A [SecondaryTakePolicy.MAX] token
     * (non-splittable, e.g. loyalty) always takes the full cap; a [SecondaryTakePolicy.NONE] token
     * takes [requestedAmount] (or the full cap when null). Re-applying replaces the prior slice.
     */
    fun takeSplit(token: SecondaryToken, requestedAmount: BigDecimal? = null) {
        val alreadyThis = secondaries[token.id]?.second ?: 0L
        val remainingWithoutThis = amountCents - (rawSecondarySumCents() - alreadyThis)
        val limitCents = token.limit?.let(::toCents) ?: Long.MAX_VALUE
        val cap = minOf(toCents(token.balance), remainingWithoutThis, limitCents).coerceAtLeast(0L)
        val take = when (token.takePolicy) {
            SecondaryTakePolicy.MAX -> cap
            SecondaryTakePolicy.NONE -> minOf(requestedAmount?.let(::toCents) ?: cap, cap).coerceAtLeast(0L)
        }
        if (take == 0L) secondaries.remove(token.id) else secondaries[token.id] = token to take
    }

    fun clearSplit(token: SecondaryToken) {
        secondaries.remove(token.id)
    }

    /** Reset to a fresh order amount, dropping the primary and every applied secondary. */
    fun reset(orderAmount: BigDecimal) {
        amountCents = toCents(orderAmount)
        primary = null
        secondaries.clear()
    }

    private fun rawSecondarySumCents(): Long = secondaries.values.sumOf { it.second }
    private fun secondaryContributionCents(): Long = rawSecondarySumCents().coerceAtMost(amountCents)
    private fun remainingCents(): Long = (amountCents - secondaryContributionCents()).coerceAtLeast(0L)

    private companion object {
        fun toCents(bd: BigDecimal): Long = bd.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()

        /** Back to a 2-dp major-unit value, e.g. 3000 → `30.00`. */
        fun fromCents(cents: Long): BigDecimal = BigDecimal(cents).movePointLeft(2)
    }
}
