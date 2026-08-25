package eu.purse.core.session

import eu.purse.core.payment.IntegratedToken
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.Partner
import eu.purse.core.vault.SessionVault
import java.math.BigDecimal
import java.time.Instant

/**
 * The `payment_session` object decoded from `widget.data` — see
 * docs/architecture/headless-checkout.md.
 */
data class LegacyPaymentSession(
    val id: String,
    val amount: BigDecimal?,
    val informationRequest: Boolean?,
    val currencyCode: String,
    val expirationDate: Instant,
    val success: String?,
    val failure: String?,
    val redirection: String?,
    val protocols: Map<Partner, Map<Method, MethodEntry>>,
    val vault: SessionVault?,
)

data class MethodEntry(
    val configurations: MethodConfiguration,
    /** Saved instruments (card-on-file) this method carries — web's `integrated_tokens`. */
    val integratedTokens: List<IntegratedToken> = emptyList(),
)
