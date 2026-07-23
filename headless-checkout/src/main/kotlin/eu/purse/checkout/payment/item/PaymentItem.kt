package eu.purse.checkout.payment.item

import eu.purse.core.payment.IntegrationMode
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.Partner
import eu.purse.core.vault.CardBrand
import java.math.BigDecimal

/**
 * One `protocols.{partner}.{method}` entry turned into a means of payment — the Kotlin analog of
 * web's `PurseHeadlessCheckoutPaymentItem` hierarchy (`types/headless-checkout.ts`). [id] follows
 * web's `"{partner}-{method}"` convention (e.g. `"purse-creditcard"`, `"paypal-wallet"`).
 *
 * Immutable: recomputing a method's [disabled] state (as the split changes) returns a copy — see
 * [withDisabled] and [MethodAvailability].
 */
sealed class PaymentItem {
    abstract val id: String
    abstract val partner: Partner
    abstract val method: Method
    abstract val configuration: MethodConfiguration
    abstract val disabled: DisabledState?
    abstract val isSecondary: Boolean

    val minAmount: BigDecimal? get() = configuration.settings.paymentMinAmount
    val maxAmount: BigDecimal? get() = configuration.settings.paymentMaxAmount

    abstract fun withDisabled(disabled: DisabledState?): PaymentItem
}

/**
 * A primary method — exactly one covers the amount left after secondaries. Surfaces the vault's
 * [supportedBrands] (empty when the method has no card vault) and the `merchant_specifics`
 * [integrationType], so the merchant's own UI can drive rendering.
 */
data class PrimaryMethod(
    override val id: String,
    override val partner: Partner,
    override val method: Method,
    override val configuration: MethodConfiguration,
    override val disabled: DisabledState?,
    /** `settings.merchant_specifics.integration_mode` — `redirection` / `popup`, or `null`. */
    val integrationType: IntegrationMode?,
    /** `vault.supported_brands` — the card schemes this method accepts; empty when not vaulted. */
    val supportedBrands: List<CardBrand>,
) : PaymentItem() {
    override val isSecondary: Boolean get() = false
    override fun withDisabled(disabled: DisabledState?) = copy(disabled = disabled)
}

/** A secondary (co-payment) method — gift cards, vouchers, loyalty. Drawn down before the primary. */
data class SecondaryMethod(
    override val id: String,
    override val partner: Partner,
    override val method: Method,
    override val configuration: MethodConfiguration,
    override val disabled: DisabledState?,
) : PaymentItem() {
    override val isSecondary: Boolean get() = true
    override fun withDisabled(disabled: DisabledState?) = copy(disabled = disabled)
}
