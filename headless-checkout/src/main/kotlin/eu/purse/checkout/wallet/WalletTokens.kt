// Web: packages/headless-checkout/src/headless-checkout.ts `setWalletSession`, `ensureEditionCapability`, `validateTokenName`.
// Same: the wallet's own token list gates edit / delete / favorite; label rule `^[a-zA-Z0-9\s]{1,25}$` + unique.
// Differs: nothing.
package eu.purse.checkout.wallet

import eu.purse.checkout.PurseCheckoutException
import eu.purse.core.api.WalletApiClient
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.session.PurseSessionParams

/** The wallet side of saved instruments: the client (when the widget data carried a `wallet_session`) and its token list. */
internal class WalletTokens(params: PurseSessionParams) {

    val client: WalletApiClient? = params.walletSession?.let { wallet ->
        WalletApiClient(
            apiKey = params.apiKey,
            walletApiRoot = params.environment.walletApiRoot,
            ownerReference = wallet.ownerReference,
            walletSessionId = wallet.sessionId,
            merchantId = wallet.merchant,
        )
    }

    /** The wallet's own tokens, as last fetched. */
    @Volatile
    var tokens: List<WalletApiClient.WalletToken> = emptyList()
        private set

    /** Blocking; call off the main thread. */
    fun fetch(): List<WalletApiClient.WalletToken> = requireClient().getTokens().also { tokens = it }

    fun forget(tokenId: String) {
        tokens = tokens.filterNot { it.id == tokenId }
    }

    fun clear() {
        tokens = emptyList()
    }

    fun requireClient(): WalletApiClient =
        client ?: throw PurseCheckoutException.WalletOperationFailed(
            "no wallet_session was attached to this checkout",
            PurseErrorCode.MISSING_WALLET_SESSION,
        )

    fun requireToken(tokenId: String) {
        if (tokens.none { it.id == tokenId }) {
            throw PurseCheckoutException.WalletOperationFailed(
                "token $tokenId is not present in the wallet",
                PurseErrorCode.MISSING_TOKEN,
            )
        }
    }

    /** @param takenNames the labels already used by the session's tokens, other than the one being renamed. */
    fun validateLabel(name: String, takenNames: Collection<String>, excludingTokenId: String?) {
        if (!TOKEN_LABEL_REGEX.matches(name)) {
            throw PurseCheckoutException.WalletOperationFailed(
                "invalid token name '$name' — 1-25 alphanumeric/space characters",
                PurseErrorCode.INVALID_TOKEN_NAME,
            )
        }
        val existsInWallet = tokens.any { it.id != excludingTokenId && it.name == name }
        if (name in takenNames || existsInWallet) {
            throw PurseCheckoutException.WalletOperationFailed(
                "a saved card is already named '$name'",
                PurseErrorCode.TOKEN_NAME_EXISTS,
            )
        }
    }

    private companion object {
        val TOKEN_LABEL_REGEX = Regex("^[a-zA-Z0-9\\s]{1,25}$")
    }
}
