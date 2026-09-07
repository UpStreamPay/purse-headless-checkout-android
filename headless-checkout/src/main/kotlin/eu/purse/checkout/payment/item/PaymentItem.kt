// Web: packages/headless-checkout/src/session/payment/mops/item-factory (primary / secondary items)
// Same: id `{partner}-{method}`, configuration, disabled state.
// Differs: a sealed hierarchy; saved gift cards are `SavedSecondaryToken`, not `type: 'token'` mops.
package eu.purse.checkout.payment.item

import eu.purse.checkout.payment.secondary.SecondaryTakePolicy
import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.checkout.payment.split.PrimarySource
import eu.purse.checkout.payment.split.SplitSource
import eu.purse.core.payment.IntegratedToken
import eu.purse.core.payment.IntegrationMode
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.Partner
import eu.purse.core.payment.SaveTokenMode
import eu.purse.core.payment.resolvedSaveTokenMode
import eu.purse.core.vault.CardBrand
import java.math.BigDecimal

private const val SIMULABLE_LABEL = "simulable"

/**
 * One `protocols.{partner}.{method}` entry turned into a means of payment — the Kotlin analog of
 * web's `PurseHeadlessCheckoutPaymentItem` hierarchy (`types/headless-checkout.ts`). [id] follows
 * web's `"{partner}-{method}"` convention (e.g. `"purse-creditcard"`, `"paypal-wallet"`).
 *
 * Immutable: recomputing a method's [disabled] state (as the split changes) returns a copy — see
 * [withDisabled] and [MethodAvailability].
 */
sealed class PaymentItem : SplitSource {
    abstract override val id: String
    abstract override val partner: Partner
    abstract override val method: Method
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
) : PaymentItem(), PrimarySource {
    override val isSecondary: Boolean get() = false
    override fun withDisabled(disabled: DisabledState?) = copy(disabled = disabled)

    /** The effective save-token policy — web's `resolveSaveTokenMode` (see [SaveTokenMode]). */
    val saveTokenMode: SaveTokenMode get() = configuration.settings.resolvedSaveTokenMode()

    /** Whether the shopper can choose to save this card — web's `canBeRegistered`. */
    val canBeRegistered: Boolean get() = saveTokenMode.canBeRegistered

    /**
     * Whether the method quotes a BNPL instalment schedule via
     * [eu.purse.checkout.PurseHeadlessCheckout.simulateLoan] — web's `simulable`, true when the
     * configuration `labels` carry `"simulable"`.
     */
    val simulable: Boolean get() = configuration.labels.contains(SIMULABLE_LABEL)
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

    /** The effective save-token policy a card tokenized through this method inherits. */
    val saveTokenMode: SaveTokenMode get() = configuration.settings.resolvedSaveTokenMode()

    /** Whether the shopper can ask to keep a gift card typed for this method — web's `canBeRegistered`. */
    val canBeRegistered: Boolean get() = saveTokenMode.canBeRegistered
}

/**
 * A **saved** gift card / voucher (secondary card-on-file) the session offers — web's
 * `PurseHeadlessCheckoutSecondaryToken` of `type: 'token'` (`buildSecondaryPaymentToken`), built
 * from a secondary method's `integrated_tokens[]`. Distinct from [PaymentToken] (a saved *card*
 * that becomes the primary) and from [SecondaryToken] (the splitter's applied instrument).
 *
 * The session carries no balance, so [balance] starts at zero and is resolved by a background
 * `/init` (the stored [pan] + [cvv]) when the checkout starts — [balanceResolved] flips to `true`
 * on success, [disabled] becomes [DisabledState.TokenInitFailed] on failure. Apply it with
 * [eu.purse.checkout.PurseHeadlessCheckout.applySecondary]; rename / delete through the wallet
 * operations. Exposed on `savedSecondaryTokens`, never on `paymentMethods`.
 */
data class SavedSecondaryToken(
    /** The wallet token id — matches the wallet API's ids, and is the split source id. */
    override val id: String,
    override val partner: Partner,
    override val method: Method,
    override val configuration: MethodConfiguration,
    override val disabled: DisabledState?,
    /** `uniqueness_token` — the stored instrument number the partner re-initialises with. */
    val pan: String,
    /** `pin_code` — the stored security code, when the partner keeps one. */
    val cvv: String?,
    /** Display label — `description.display_token`, else the shopper-given name. */
    val name: String,
    val expirationDate: String?,
    val createdAt: String?,
    val favorite: Boolean,
    /** Spendable balance once resolved; zero until then. */
    val balance: BigDecimal = BigDecimal.ZERO,
    val totalBalance: BigDecimal = balance,
    val balanceResolved: Boolean = false,
    val takePolicy: SecondaryTakePolicy = SecondaryTakePolicy.NONE,
) : PaymentItem() {
    override val isSecondary: Boolean get() = true
    override fun withDisabled(disabled: DisabledState?) = copy(disabled = disabled)

    /** The splitter-side instrument for this saved card — what `applySecondary` draws down. */
    fun asSecondaryToken(): SecondaryToken = SecondaryToken(
        id = id,
        partner = partner,
        method = method,
        pan = pan,
        cvv = cvv,
        name = name,
        balance = balance,
        totalBalance = totalBalance,
        expirationDate = expirationDate.orEmpty(),
        takePolicy = takePolicy,
        limit = maxAmount,
        usageLimit = configuration.settings.usagePerSessionLimit,
        saveTokenMode = SaveTokenMode.NEVER,
        isSaved = true,
    )

    companion object {
        internal fun from(
            token: IntegratedToken,
            partner: Partner,
            method: Method,
            configuration: MethodConfiguration,
            disabled: DisabledState?,
            takePolicy: SecondaryTakePolicy,
        ) = SavedSecondaryToken(
            id = token.id,
            partner = partner,
            method = method,
            configuration = configuration,
            disabled = disabled,
            pan = token.uniquenessToken.orEmpty(),
            cvv = token.pinCode,
            name = token.displayToken ?: token.name.orEmpty(),
            expirationDate = token.expirationDate,
            createdAt = token.createdAt,
            favorite = token.favorite,
            takePolicy = takePolicy,
        )
    }
}
