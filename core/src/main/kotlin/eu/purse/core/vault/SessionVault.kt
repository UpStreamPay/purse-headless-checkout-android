// Web: packages/model `vault.vendor_front_data`
// Same: purse tenant id, pci-proxy merchant id.
// Differs: nothing.
package eu.purse.core.vault

/**
 * The session-level `vault` field — distinct from the per-method [VaultInfo] inside
 * [MethodConfiguration]. This is where the SecureFields Android SDK's `tenantId` actually comes
 * from — see docs/architecture/headless-checkout.md.
 */
data class SessionVault(
    val vendorFrontData: VendorFrontData,
)

data class VendorFrontData(
    val purse: PurseVaultInfo?,
    val pciProxy: PciProxyVaultInfo?,
)

data class PurseVaultInfo(
    val tenantId: String,
)

data class PciProxyVaultInfo(
    val merchantId: String,
)
