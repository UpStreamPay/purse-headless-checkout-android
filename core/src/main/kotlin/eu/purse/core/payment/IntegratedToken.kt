package eu.purse.core.payment

/**
 * A saved instrument (card-on-file) carried by the session under
 * `protocols.{partner}.{method}.integrated_tokens[]` — web sdk's `IntegratedToken`
 * (`packages/sdk/src/types.ts`).
 */
data class IntegratedToken(
    val id: String,
    /** The opaque value `/validate` pays with (`payment_data.token`). */
    val value: String,
    val uniquenessToken: String?,
    /**
     * `pin_code` — the saved instrument's security code, when the partner stores one (gift cards).
     * Together with [uniquenessToken] it is what re-initialises a saved secondary token's balance.
     */
    val pinCode: String? = null,
    /** ISO expiration of the underlying card, as the session carries it. */
    val expirationDate: String?,
    val favorite: Boolean,
    val name: String?,
    val source: TokenSource,
    val createdAt: String?,
    val brandName: String?,
    val displayToken: String?,
    val holderName: String?,
    val maskedPan: String?,
    /** `network_token.available` — pairs with `settings.network_token` for the no-CVV gate. */
    val networkTokenAvailable: Boolean,
)

/** Where a saved token lives — web's `IntegratedToken.source`. */
enum class TokenSource(val wireValue: String) {
    MERCHANT("MERCHANT"),
    SHARED_WALLET("SHARED_WALLET");

    companion object {
        fun fromWire(value: String?): TokenSource =
            entries.firstOrNull { it.wireValue == value } ?: MERCHANT
    }
}
