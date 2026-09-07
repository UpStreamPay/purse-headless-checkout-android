// Web: packages/sdk/src/types.ts (`VaultVendors`)
// Same: purse / pci_proxy / none.
// Differs: nothing.
package eu.purse.core.vault

/**
 * Who hosts card data capture for a `creditcard` method — see
 * docs/architecture/headless-checkout.md.
 */
enum class VaultVendor(val wireValue: String) {
    /** Purse's own Vault (SecureFields Android SDK) — the only vendor this POC targets. */
    PURSE("purse"),

    /** PSP-hosted fields — out of scope for this POC. */
    PCI_PROXY("pci_proxy"),

    /** No vault — secondary methods like Illicado. */
    NONE("none"),

    UNKNOWN("");

    companion object {
        fun fromWire(value: String?): VaultVendor = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}
