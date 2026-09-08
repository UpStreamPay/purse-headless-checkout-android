// Web: packages/partner-ui/src/plugins/generics/googlepay/index.ts (the plugin table entry + `appendTo`)
// Same: registered for (ingenico, googlepay) plus a fallback on the `googlepay` method; renders
//       Google's own button and pays from its tap.
// Differs: an optional artifact discovered through `ServiceLoader`; Compose owns the launchers.
package eu.purse.partnerui.plugins.generics.googlepay

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.wallet.contract.TaskResultContracts
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PartnerModule
import eu.purse.partnerui.PartnerUiPlugin
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PaymentSurface
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.SurfaceRequest
import eu.purse.partnerui.plugins.generics.googlepay.partners.ingenico.Ingenico
import eu.purse.partnerui.rememberRedirectLauncher
import eu.purse.partnerui.requireComponentActivity

/**
 * The Google Pay artifact's contribution: [GooglePayPlugin] for `(ingenico, googlepay)` and for any
 * other partner's `googlepay` method, rendered as Google's branded button. A saved Google Pay token
 * is not a thing, so [rememberSurface] always renders the button.
 *
 * A class with a public no-arg constructor, as `ServiceLoader` requires; `PluginRegistry` keeps one.
 */
class GooglePayModule : PartnerModule {

    override fun register(registry: PluginRegistry) {
        registry.register(Partner.Other(Ingenico.WIRE_VALUE), Method.GooglePay) { GooglePayPlugin(it.partner) }
        registry.registerFallback(Method.GooglePay) { GooglePayPlugin(it.partner) }
    }

    override fun renders(plugin: PartnerUiPlugin) = plugin is GooglePayPlugin

    @Composable
    override fun rememberSurface(plugin: PartnerUiPlugin, request: SurfaceRequest): PaymentSurface {
        val coordinator = rememberGooglePayCoordinator(request.listener)
        LaunchedEffect(coordinator, request.key) { coordinator.prepare(request.host) }
        return remember(coordinator) {
            PaymentSurface(hasUi = true, selfSubmitting = true, onSubmit = { }) { modifier ->
                GooglePayButton(coordinator, modifier)
            }
        }
    }
}

/** The only way to obtain a [GooglePayCoordinator]: Compose-managed launchers, legal at any composition point. */
@Composable
fun rememberGooglePayCoordinator(listener: PaymentElementListener): GooglePayCoordinator {
    val activity = LocalContext.current.requireComponentActivity()
    val coordinator = remember(listener) { GooglePayCoordinator(activity, listener) }
    val paymentDataLauncher = rememberLauncherForActivityResult(TaskResultContracts.GetPaymentDataResult()) {
        coordinator.onPaymentData(it)
    }
    coordinator.launchPaymentData = { task -> paymentDataLauncher.launch(task) }
    coordinator.launchRedirect = rememberRedirectLauncher(coordinator)
    return coordinator
}
