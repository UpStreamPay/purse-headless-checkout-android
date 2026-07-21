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

    /**
     * The same method with a recomputed [disabled] state — used when the live split changes what a
     * primary still has to pay (see [eu.purse.dropin.payment.method.MethodAvailability]). The models
     * are immutable, so this returns a copy rather than mutating in place (web mutates each mop's
     * `disabledState` in `updateMopDisabledState`; here the UI diffs the new list against the old).
     */
    abstract fun withDisabled(disabled: DisabledReason?): DropinPaymentMethod
}

/** Rendered as a radio-style list entry — CB, Google Pay, PayPal in this POC. */
data class PrimaryMethod(
    override val id: String,
    override val partner: Partner,
    override val method: Method,
    override val configuration: MethodConfiguration,
    override val disabled: DisabledReason?,
) : DropinPaymentMethod() {
    override fun withDisabled(disabled: DisabledReason?) = copy(disabled = disabled)
}

/** Rendered in an accordion above the primaries list — Illicado in this POC. */
data class SecondaryMethod(
    override val id: String,
    override val partner: Partner,
    override val method: Method,
    override val configuration: MethodConfiguration,
    override val disabled: DisabledReason?,
) : DropinPaymentMethod() {
    override fun withDisabled(disabled: DisabledReason?) = copy(disabled = disabled)
}
