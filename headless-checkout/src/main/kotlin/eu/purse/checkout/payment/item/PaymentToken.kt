package eu.purse.checkout.payment.item

import eu.purse.checkout.payment.split.PrimarySource
import eu.purse.core.payment.IntegratedToken
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.Partner
import eu.purse.core.payment.TokenSource
import eu.purse.core.vault.CardBrand
import eu.purse.core.vault.VaultVendor
import eu.purse.partnerui.PayToken

/**
 * A saved instrument offered by the session under a primary method — a card-on-file, or a vaulted
 * PayPal account — the Kotlin analog of web's `PurseHeadlessCheckoutPrimaryToken`
 * (`types/headless-checkout.ts`), built from a method's `integrated_tokens[]`. Selectable as the
 * split's primary via [eu.purse.checkout.PurseHeadlessCheckout.setPrimaryToken]; managed (rename /
 * delete / favorite) through the checkout's wallet operations when a `wallet_session` is attached.
 *
 * The card-shaped display fields ([brand], [holderName], [maskedPan], [expirationDate]) are empty
 * for a token whose method has no card behind it; [label] (`description.display_token`) is what
 * every token carries.
 */
data class PaymentToken(
    /** The token's own id (not `partner-method`) — matches the wallet API's token ids. */
    override val id: String,
    override val partner: Partner,
    override val method: Method,
    override val configuration: MethodConfiguration,
    override val disabled: DisabledState?,
    /** The opaque value `/validate` pays with. */
    val value: String,
    /** The shopper-given label, when one was saved. */
    val name: String,
    /** Display metadata — web's `description{label, brand, holderName, masked_pan}`. */
    val label: String,
    val brand: String,
    val holderName: String,
    val maskedPan: String,
    /** ISO expiration of the underlying card. */
    val expirationDate: String?,
    val favorite: Boolean,
    val tokenSource: TokenSource,
    val createdAt: String?,
    /** `network_token.available` — see [paysWithoutCvv]. */
    val networkTokenAvailable: Boolean,
) : PaymentItem(), PrimarySource {

    override val isSecondary: Boolean get() = false

    /** The id of the [PrimaryMethod] this token belongs to (`"{partner}-{method}"`). */
    val methodId: String get() = "${partner.wireValue}-${method.wireValue}"
    override fun withDisabled(disabled: DisabledState?) = copy(disabled = disabled)

    /**
     * Whether this token's method captures card data through a vault (`configurations.vault.vendor`
     * other than `none`) — i.e. the token is a **card-on-file**, and its plugin (the vault step of
     * `PluginRegistry.getPlugin`) is the one that can put a CVV form on screen. `false` for a saved
     * PayPal (`paypal`/`wallet` carries no `vault`) or a token under a redirection template: web's
     * `hasUI()` answers `false` for those tokens before any CVV gate is even consulted, and neither
     * the paypal nor the redirection plugin ever collects a CVV.
     */
    val isVaultedCard: Boolean
        get() = configuration.vault?.vendor?.let { it != VaultVendor.NONE } == true

    /**
     * Whether this token pays without collecting the CVV. `true` outright when no card form exists
     * for it ([isVaultedCard] `false` — a PayPal token, say: a CVV is a card-form concept). For a
     * card-on-file, web's no-CVV gates (`plugins/utils/noCVV.ts`): the merchant's `pay_token_no_cvv`
     * setting, or an available network token when the experience enables them.
     */
    val paysWithoutCvv: Boolean
        get() = !isVaultedCard ||
            configuration.settings.payTokenNoCvv ||
            (networkTokenAvailable && configuration.settings.networkToken)

    /**
     * Whether paying with this token still asks the shopper for the CVV — the inverse of
     * [paysWithoutCvv]. When `true`, selecting the token ([eu.purse.checkout.PurseHeadlessCheckout.setPrimaryToken])
     * arms the fulfillment gate exactly like mounting a fresh card form does: `isPaymentFulfilled`
     * stays `false` until the CVV-only SecureFields form reports valid (web's `cvvOnly` hosted
     * fields). Only the `purse` vault renders that form on Android. Never `true` for a token that
     * isn't a card-on-file ([isVaultedCard]).
     */
    val requiresCvv: Boolean get() = !paysWithoutCvv

    /**
     * The `:partner-ui` view of this token — what `PartnerUiContext.token` carries into the card
     * plugin (web's `Token` attribute on the element): the opaque value, the card metadata
     * `patchItemPayloadWithToken` reads, and the CVV requirement.
     */
    fun toPayToken(): PayToken = PayToken(
        value = value,
        expirationDate = expirationDate,
        holderName = holderName.ifBlank { null },
        selectedNetwork = CardBrand.fromWire(brand).takeIf { it != CardBrand.UNKNOWN }?.wireValue,
        requiresCvv = requiresCvv,
    )

    companion object {
        internal fun from(
            token: IntegratedToken,
            partner: Partner,
            method: Method,
            configuration: MethodConfiguration,
            disabled: DisabledState?,
        ) = PaymentToken(
            id = token.id,
            partner = partner,
            method = method,
            configuration = configuration,
            disabled = disabled,
            value = token.value,
            name = token.name.orEmpty(),
            label = token.displayToken.orEmpty(),
            brand = token.brandName.orEmpty(),
            holderName = token.holderName.orEmpty(),
            maskedPan = token.maskedPan.orEmpty(),
            expirationDate = token.expirationDate,
            favorite = token.favorite,
            tokenSource = token.source,
            createdAt = token.createdAt,
            networkTokenAvailable = token.networkTokenAvailable,
        )
    }
}
