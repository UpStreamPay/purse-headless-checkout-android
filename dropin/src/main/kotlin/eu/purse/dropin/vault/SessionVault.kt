package eu.purse.dropin.vault

/**
 * The session-level `vault` field — distinct from the per-method [VaultInfo] inside
 * [MethodConfiguration]. This is where the SecureFields Android SDK's `tenantId` actually comes
 * from — see docs/architecture/session-and-protocols.md#session-level-vault-config--where-tenantid-actually-comes-from.
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
