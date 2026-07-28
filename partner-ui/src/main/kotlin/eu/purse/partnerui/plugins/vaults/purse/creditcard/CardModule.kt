package eu.purse.partnerui.plugins.vaults.purse.creditcard

import eu.purse.core.vault.VaultVendor
import eu.purse.partnerui.PluginRegistry

/**
 * Entry point for the Purse vault credit-card plugin. [PluginRegistry] calls [register]
 * automatically on first use — an app does **not** need to call this itself; see the class doc on
 * [PluginRegistry].
 */
object CardModule {

    /** Registers this plugin as the vault handler for [VaultVendor.PURSE] — keyed by vault vendor,
     * not `(partner, method)`, since any partner's `creditcard` method can be Purse-vaulted. */
    fun register() {
        PluginRegistry.registerVault(VaultVendor.PURSE) { data -> CardPlugin(data.partner) }
    }
}
