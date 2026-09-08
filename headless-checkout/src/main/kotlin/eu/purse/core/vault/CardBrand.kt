// Web: packages/model card brands (`vault.supported_brands`)
// Same: same wire values.
// Differs: distinct from SecureFields' own `Brand` enum.
package eu.purse.core.vault

/**
 * A card scheme as reported by the session's `vault.supported_brands`.
 *
 * Distinct from SecureFields' own `Brand` enum (`eu.purse.securefields.Brand`) — that one uses
 * SecureFields' own BIN-lookup vocabulary (e.g. `CB`, `AMEX`). Converting between the two is a
 * separate step, sketched as `toVaultBrand()` in docs/architecture/headless-checkout.md.
 */
enum class CardBrand(val wireValue: String) {
    CARTE_BANCAIRE("CARTE_BANCAIRE"),
    VISA("VISA"),
    MASTERCARD("MASTERCARD"),
    AMERICAN_EXPRESS("AMERICAN_EXPRESS"),
    MAESTRO("MAESTRO"),
    DINERS_CLUB("DINERS_CLUB"),
    DISCOVER("DISCOVER"),
    UNIONPAY("UNIONPAY"),
    JCB("JCB"),
    ONEY("ONEY"),
    UNKNOWN("");

    companion object {
        fun fromWire(value: String): CardBrand = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}
