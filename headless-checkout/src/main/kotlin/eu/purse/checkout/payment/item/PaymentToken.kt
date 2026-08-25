package eu.purse.checkout.payment.item

import eu.purse.checkout.payment.split.PrimarySource
import eu.purse.core.payment.IntegratedToken
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.Partner
import eu.purse.core.payment.TokenSource

/**
 * A saved card (card-on-file) offered by the session — the Kotlin analog of web's
 * `PurseHeadlessCheckoutPrimaryToken` (`types/headless-checkout.ts`), built from a method's
 * `integrated_tokens[]`. Selectable as the split's primary via
 * [eu.purse.checkout.PurseHeadlessCheckout.setPrimaryToken]; managed (rename / delete / favorite)
 * through the checkout's wallet operations when a `wallet_session` is attached.
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
    override fun withDisabled(disabled: DisabledState?) = copy(disabled = disabled)

    /**
     * Whether this token pays without collecting the CVV — web's no-CVV gates
     * (`plugins/utils/noCVV.ts`): the merchant's `pay_token_no_cvv` setting, or an available
     * network token when the experience enables them.
     */
    val paysWithoutCvv: Boolean
        get() = configuration.settings.payTokenNoCvv ||
            (networkTokenAvailable && configuration.settings.networkToken)

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
