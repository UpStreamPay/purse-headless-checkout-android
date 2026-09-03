package eu.purse.core.session

import eu.purse.core.environment.PurseEnvironment

/**
 * The decoded `widget.data` payload — see docs/architecture/headless-checkout.md.
 */
data class PurseSessionParams(
    val apiKey: String,
    val apiUrl: String,
    val environment: PurseEnvironment,
    val entityId: String,
    val paymentSession: LegacyPaymentSession,
    /** Present when the merchant backend attached a `wallet_session` (saved-card wallet access). */
    val walletSession: WalletSessionParams? = null,
)
