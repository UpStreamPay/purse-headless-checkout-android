package eu.purse.checkout.payment.secondary

import eu.purse.checkout.payment.split.SplitSource
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.payment.SaveTokenMode
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
    /**
     * The method's effective save-token policy — web's temporary secondary token `saveTokenMode`.
     * Drives [canBeRegistered]; the checkout defaults the registration decision from it.
     */
    val saveTokenMode: SaveTokenMode = SaveTokenMode.NEVER,
    /**
     * `true` when this token was produced from a **saved** instrument (the session's
     * `integrated_tokens`) rather than a card the shopper just typed — a saved card is never
     * re-registered at `/validate`.
     */
    val isSaved: Boolean = false,
) : SplitSource {

    /** Whether the shopper can ask to keep this card for next time — web's `canBeRegistered`. */
    val canBeRegistered: Boolean get() = !isSaved && saveTokenMode.canBeRegistered
}
