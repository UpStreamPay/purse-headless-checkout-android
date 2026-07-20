package eu.purse.dropin.session

import eu.purse.dropin.payment.method.Method
import eu.purse.dropin.payment.method.MethodConfiguration
import eu.purse.dropin.payment.partner.Partner
import eu.purse.dropin.vault.SessionVault
import java.math.BigDecimal
import java.time.Instant

/**
 * The `payment_session` object decoded from `widget.data` — see
 * docs/architecture/session-and-protocols.md#full-legacypaymentsession-shape.
 *
 * `integrated_tokens` (saved cards) and the top-level `wallet` flag are intentionally not
 * modeled — both are out of scope for this POC, see docs/decisions/poc-scope.md.
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
