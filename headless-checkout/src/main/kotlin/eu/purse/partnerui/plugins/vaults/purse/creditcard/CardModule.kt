// Web: packages/partner-ui/src/plugins/vaults/purse/creditcard/index.ts (`appendTo` mounts the hosted form)
// Same: a fresh card renders the form; a saved card renders a CVV field or nothing.
// Differs: a `PartnerModule` so the payment element can render it without knowing the plugin.
package eu.purse.partnerui.plugins.vaults.purse.creditcard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import eu.purse.core.vault.VaultVendor
import eu.purse.partnerui.PartnerModule
import eu.purse.partnerui.PartnerUiPlugin
import eu.purse.partnerui.PaymentSurface
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.SurfaceRequest

/** The Purse vault credit-card plugin — every method whose `configurations.vault.vendor` is Purse. */
object CardModule : PartnerModule {

    override fun register(registry: PluginRegistry) {
        registry.registerVault(VaultVendor.PURSE) { CardPlugin(it.partner) }
    }

    override fun renders(plugin: PartnerUiPlugin) = plugin is CardPlugin

    /** A fresh card or a saved one; `CardCoordinator.Mode` (decided by the host's token) picks the form. */
    @Composable
    override fun rememberSurface(plugin: PartnerUiPlugin, request: SurfaceRequest): PaymentSurface {
        // The host is what rememberCardCoordinator keys its SecureFields instance on: a new
        // generation means a new host, hence a fresh form; the same generation keeps the form and
        // whatever the shopper typed.
        val coordinator = rememberCardCoordinator(request.host, request.listener)
        return remember(coordinator) {
            PaymentSurface(hasUi = coordinator.mode.rendersForm, selfSubmitting = false, onSubmit = { coordinator.submit() }) { modifier ->
                if (coordinator.mode.rendersForm) PurseCardForm(coordinator, modifier)
            }
        }
    }
}
