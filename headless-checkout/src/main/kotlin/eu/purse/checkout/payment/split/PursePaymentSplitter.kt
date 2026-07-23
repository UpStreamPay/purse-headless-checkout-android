package eu.purse.checkout.payment.split

import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SecondaryMethod
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The payment split for a session — the Kotlin analog of web headless-checkout's
 * `PursePaymentSplitter`. Model: **one primary + N secondaries**. Secondaries (gift cards,
 * vouchers) are drawn down first; the primary absorbs whatever is left ([primaryReach]).
 *
 * All arithmetic is in **integer cents** to avoid binary-float drift, matching web; public amounts
 * are `BigDecimal` major-currency units at 2-dp scale. Not thread-safe — drive it from one thread
 * (the SDK does so behind [eu.purse.checkout.PurseHeadlessCheckout]).
 *
 * The voucher → loyalty → primary autofill middleware chain web runs over the splits is a
 * documented extension point; it is not modelled yet (no secondary plugins exist to feed it).
 */
class PursePaymentSplitter(orderAmount: BigDecimal) {

    private var amountCents: Long = toCents(orderAmount)
    private var primary: PrimaryMethod? = null

    /** Applied secondaries, keyed by item id, in insertion order. */
    private val secondaries = LinkedHashMap<String, Pair<SecondaryMethod, Long>>()

    val orderAmount: BigDecimal get() = fromCents(amountCents)

    /** Total already covered by applied secondaries, never more than the order amount. */
    val secondaryContribution: BigDecimal get() = fromCents(secondaryContributionCents())

    /** Amount a primary still has to pay — order minus what secondaries cover, floored at zero. */
    val primaryReach: BigDecimal get() = fromCents(remainingCents())

    /** Alias of [primaryReach], the amount still owed. */
    val remaining: BigDecimal get() = primaryReach

    val primaryMethod: PrimaryMethod? get() = primary

    /**
     * True once payment can be submitted: a zero-amount session, secondaries covering the whole
     * amount, or a primary selected to cover the remainder — mirroring web's completion rule.
     */
    val fulfilled: Boolean get() = amountCents == 0L || remainingCents() == 0L || primary != null

    /** The submittable set: each secondary's slice, plus the primary's remainder when one is set. */
    val splits: List<PaymentSplit>
        get() {
            val list = secondaries.values.map { (method, cents) -> PaymentSplit(fromCents(cents), method) }
                .toMutableList()
            val remainder = remainingCents()
            primary?.let { if (remainder > 0L) list += PaymentSplit(fromCents(remainder), it) }
            return list
        }

    fun setPrimary(method: PrimaryMethod?) {
        primary = method
    }

    /**
     * Apply a secondary instrument's [balance] against what's left. The draw is capped at the
     * balance, the method's `payment_max_amount`, and the remaining amount (overflow dropped) —
     * matching web's clamping. Re-applying the same method replaces its previous slice.
     */
    fun takeSplit(method: SecondaryMethod, balance: BigDecimal) {
        val alreadyThis = secondaries[method.id]?.second ?: 0L
        val remainingWithoutThis = amountCents - (rawSecondarySumCents() - alreadyThis)
        val maxCents = method.maxAmount?.let(::toCents) ?: Long.MAX_VALUE
        val take = minOf(toCents(balance), remainingWithoutThis, maxCents).coerceAtLeast(0L)
        if (take == 0L) secondaries.remove(method.id) else secondaries[method.id] = method to take
    }

    fun clearSplit(method: SecondaryMethod) {
        secondaries.remove(method.id)
    }

    /** Reset to a fresh order amount, dropping the primary and every secondary. */
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
