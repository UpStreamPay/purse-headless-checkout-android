package eu.purse.core.session

import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.Partner
import eu.purse.core.vault.SessionVault
import java.math.BigDecimal
import java.time.Instant

/**
 * The `payment_session` object decoded from `widget.data` — see
 * docs/architecture/headless-checkout.md.
 *
 * `integrated_tokens` (saved cards) and the top-level `wallet` flag are intentionally not
 * modeled — both are out of scope for this POC, see docs/architecture/headless-checkout.md.
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
)
