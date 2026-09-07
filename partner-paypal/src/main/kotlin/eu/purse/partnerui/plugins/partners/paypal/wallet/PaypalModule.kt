// Web: packages/partner-ui/src/plugins/partners/paypal/wallet/index.ts (table entry + `appendTo`)
// Same: registered for (paypal, wallet); a fresh wallet renders PayPal's button, a saved account
//       renders nothing and submit captures.
// Differs: an optional artifact discovered through `ServiceLoader`; Compose owns the launchers.
package eu.purse.partnerui.plugins.partners.paypal.wallet

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PartnerModule
import eu.purse.partnerui.PartnerUiPlugin
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PaymentSurface
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.SurfaceRequest
import eu.purse.partnerui.rememberRedirectLauncher
import eu.purse.partnerui.requireComponentActivity

/**
 * The PayPal artifact's contribution: [PaypalPlugin] for `(paypal, wallet)` in popup mode (a
 * redirection-mode PayPal wallet stays on the generic redirection plugin, as on web).
 *
 * A class with a public no-arg constructor, as `ServiceLoader` requires; `PluginRegistry` keeps one.
 */
class PaypalModule : PartnerModule {

    override fun register(registry: PluginRegistry) {
        registry.register(Partner.Paypal, Method.Wallet) { PaypalPlugin(it.partner) }
    }

    override fun renders(plugin: PartnerUiPlugin) = plugin is PaypalPlugin

    @Composable
    override fun rememberSurface(plugin: PartnerUiPlugin, request: SurfaceRequest): PaymentSurface {
        val coordinator = rememberPaypalCoordinator(request.listener)
        LaunchedEffect(coordinator, request.key) { coordinator.prepare(request.host) }
        return remember(coordinator) {
            if (request.isToken) {
                // No button, no approval: the buyer approved when they vaulted it; submit captures.
                PaymentSurface.headless { coordinator.submit() }
            } else {
                PaymentSurface(hasUi = true, selfSubmitting = true, onSubmit = { }) { modifier ->
                    PaypalButton(coordinator, modifier)
                }
            }
        }
    }
}

/** The only way to obtain a [PaypalCoordinator]: Compose-managed launchers, legal at any composition point. */
@Composable
fun rememberPaypalCoordinator(listener: PaymentElementListener): PaypalCoordinator {
    val activity = LocalContext.current.requireComponentActivity()
    val coordinator = remember(listener) { PaypalCoordinator(activity, listener) }
    val paypalLauncher = rememberLauncherForActivityResult(PaypalCheckoutContract()) { coordinator.onPaypalResult(it) }
    coordinator.launchPaypal = { input -> paypalLauncher.launch(input) }
    coordinator.launchRedirect = rememberRedirectLauncher(coordinator)
    return coordinator
}
