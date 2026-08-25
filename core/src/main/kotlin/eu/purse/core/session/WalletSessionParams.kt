package eu.purse.core.session

/**
 * The optional `wallet_session` object from `widget.data` — web sdk's `OnePayWalletSession`. Grants
 * access to the shopper's saved-token wallet (Wallet API v2) for the checkout's lifetime.
 */
data class WalletSessionParams(
    val sessionId: String,
    val ownerReference: String,
    /** The merchant id sent as `x-merchant-id` on every Wallet API call. */
    val merchant: String,
    val expirationDate: String?,
)
