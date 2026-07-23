package eu.purse.core.payment

import eu.purse.core.vault.CardBrand
import eu.purse.core.vault.VaultVendor
import java.math.BigDecimal

/**
 * `protocols.{partner}.{method}.configurations` — see
 * docs/architecture/headless-checkout.md.
 */
data class MethodConfiguration(
    val stable: Boolean,
    val template: String?,
    val labels: List<String>,
    val position: Int?,
    val vault: VaultInfo?,
    val settings: MethodSettings,
) {
    /**
     * A method is secondary iff its `labels` contains the literal string `"secondary"` — not a
     * `template` or partner check. See
     * docs/architecture/headless-checkout.md.
     */
    val isSecondary: Boolean get() = labels.contains(SECONDARY_LABEL)

    companion object {
        const val SECONDARY_LABEL = "secondary"
    }
}

data class VaultInfo(
    val supportedBrands: List<CardBrand>,
    val vendor: VaultVendor,
    val tokenScope: String?,
    val externalThreeDsEnabled: Boolean,
)

data class MethodSettings(
    val registrationMode: RegistrationMode,
    val saveToken: Boolean,
    val saveTokenMode: String?,
    val directDebit: Boolean,
    val paymentMinAmount: BigDecimal?,
    val paymentMaxAmount: BigDecimal?,
    val usagePerSessionLimit: Int?,
    val integrationMode: IntegrationMode?,
    /** `merchant_specifics.client_id` — the PSP's client-side SDK id (e.g. PayPal's REST client id). */
    val merchantClientId: String? = null,
)
