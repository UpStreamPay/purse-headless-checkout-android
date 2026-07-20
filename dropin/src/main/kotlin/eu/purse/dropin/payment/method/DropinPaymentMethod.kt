package eu.purse.dropin.payment.method

import eu.purse.dropin.payment.partner.Partner
import java.math.BigDecimal

/**
 * One partner/method entry from `protocols`, turned into a list item — see
 * docs/architecture/session-and-protocols.md#turning-protocols-into-a-method-list.
 *
 * Mirrors the web `PurseHeadlessCheckoutPaymentMethod` primary/secondary split. [id] follows the
 * same `"{partner}-{method}"` convention as web (e.g. `"purse-creditcard"`, `"paypal-wallet"`).
 */
sealed class DropinPaymentMethod {
    abstract val id: String
    abstract val partner: Partner
    abstract val method: Method
    abstract val configuration: MethodConfiguration
    abstract val disabled: DisabledReason?

    val minAmount: BigDecimal? get() = configuration.settings.paymentMinAmount
    val maxAmount: BigDecimal? get() = configuration.settings.paymentMaxAmount
}

/** Rendered as a radio-style list entry — CB, Google Pay, PayPal in this POC. */
data class PrimaryMethod(
    override val id: String,
    override val partner: Partner,
    override val method: Method,
    override val configuration: MethodConfiguration,
    override val disabled: DisabledReason?,
) : DropinPaymentMethod()

/** Rendered in an accordion above the primaries list — Illicado in this POC. */
data class SecondaryMethod(
    override val id: String,
    override val partner: Partner,
    override val method: Method,
    override val configuration: MethodConfiguration,
    override val disabled: DisabledReason?,
) : DropinPaymentMethod()
