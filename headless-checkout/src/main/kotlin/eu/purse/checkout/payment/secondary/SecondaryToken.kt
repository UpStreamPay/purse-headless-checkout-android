package eu.purse.checkout.payment.secondary

import eu.purse.checkout.payment.split.SplitSource
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import java.math.BigDecimal

/**
 * A tokenized secondary (co-payment) instrument — e.g. a gift card the shopper added — returned by
 * [SecondaryTokenizer] after a Payment API `/init`. The Kotlin analog of web's
 * `PurseHeadlessCheckoutSecondaryToken`: it carries the [balance] the splitter draws down, the
 * [pan]/[cvv] needed to build the `/validate` payload, and the instrument's [takePolicy].
 *
 * Applied to the split via [eu.purse.checkout.PurseHeadlessCheckout.applySecondary]. Amounts are in
 * major currency units.
 */
data class SecondaryToken(
    /** Stable per-instrument id — distinguishes multiple cards of the same method. */
    override val id: String,
    override val partner: Partner,
    override val method: Method,
    val pan: String,
    val cvv: String?,
    /** Human label, e.g. a masked PAN. May be blank. */
    val name: String,
    /** Spendable balance right now (what the splitter may draw down). */
    val balance: BigDecimal,
    /** Full balance on the instrument, when the partner distinguishes it from [balance]. */
    val totalBalance: BigDecimal = balance,
    /** Raw expiry as returned by the partner (format varies); blank when absent. */
    val expirationDate: String = "",
    val takePolicy: SecondaryTakePolicy = SecondaryTakePolicy.NONE,
    /** Per-session draw cap for this instrument, if the method sets one. */
    val limit: BigDecimal? = null,
) : SplitSource
