package eu.purse.partnerui.plugins.vaults.purse.creditcard

import eu.purse.core.environment.PurseEnvironment
import eu.purse.core.payload.ValidationResult
import eu.purse.core.vault.CardBrand
import eu.purse.partnerui.PartnerUiContext

/**
 * What [CardCoordinator] needs from the surrounding checkout session — kept as an interface so
 * this plugin can depend only on `:core` + `:partner-ui`, not `:headless-checkout` (which itself
 * depends on `:partner-ui`; a dependency back the other way would cycle). `:headless-checkout`'s
 * `PurseHeadlessCheckout.cardHost` is the concrete implementation — the same seam shape as
 * `GooglePayHost`/`KlarnaHost`.
 */
interface CardHost {

    val environment: PurseEnvironment

    /**
     * Tenant id for `SecureFieldsAndroid.init()`/`rememberSecureFields()` — from the session's
     * top-level `vault.vendor_front_data.purse.tenant_id`, distinct from this method's own
     * `configurations.vault`. `null`/blank when the session carries no Purse vault config at all.
     */
    val vaultTenantId: String?

    /** `configurations.vault.supported_brands` for this item — which card schemes SecureFields
     * should accept. Empty falls back to a sane default in [CardCoordinator]. */
    val supportedBrands: List<CardBrand>

    /** The session's own configured return URL, threaded into a genuine 3-D Secure redirect the
     * same way [eu.purse.partnerui.plugins.generics.googlepay.GooglePayHost.redirectionReturnUrl] is. */
    val redirectionReturnUrl: String?

    /** The [PartnerUiContext] for this card item against the current split. */
    fun buildContext(): PartnerUiContext

    /** Stash SecureFields' tokenize result (vault token + bin + selected network) for the next
     * [submitPayment]. */
    fun setCollected(data: Map<String, Any?>)

    /** Run the plugin-driven `/validate` pipeline over the current split. */
    suspend fun submitPayment(): ValidationResult
}
