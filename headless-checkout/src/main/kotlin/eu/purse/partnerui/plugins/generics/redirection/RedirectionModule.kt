// Web: packages/partner-ui/src/plugins/generics/redirection/index.ts (`hasUI: () => false`)
// Same: nothing on screen; submit runs /validate and follows the PSP page.
// Differs: a `PartnerModule` so the payment element can render it without knowing the plugin.
package eu.purse.partnerui.plugins.generics.redirection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import eu.purse.partnerui.PartnerModule
import eu.purse.partnerui.PartnerUiPlugin
import eu.purse.partnerui.PaymentSurface
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.SurfaceRequest
import eu.purse.partnerui.rememberRedirectionCoordinator

/** The generic redirection template — every method the session marks `template = "redirection"`. */
object RedirectionModule : PartnerModule {

    override fun register(registry: PluginRegistry) {
        registry.registerGeneric(RedirectionPlugin.TEMPLATE) { RedirectionPlugin(it.partner, it.method) }
    }

    override fun renders(plugin: PartnerUiPlugin) = plugin is RedirectionPlugin

    @Composable
    override fun rememberSurface(plugin: PartnerUiPlugin, request: SurfaceRequest): PaymentSurface {
        val coordinator = rememberRedirectionCoordinator(request.listener)
        LaunchedEffect(coordinator, request.key) { coordinator.prepare(request.host) }
        return remember(coordinator) { PaymentSurface.headless { coordinator.submit() } }
    }
}
