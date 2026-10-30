// Web: packages/partner-ui/src/plugins/partners/klarna-installments/index.ts (table entry + `appendTo`)
// Same: registered for (klarna, installments); the widget mounts into the element's container.
// Differs: an optional artifact discovered through `ServiceLoader`; the native `KlarnaPaymentView`
//          inflates into an `AndroidView`, and the host app declares the return deep link.
package eu.purse.partnerui.plugins.partners.klarna

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
 * The Klarna artifact's contribution: [KlarnaPlugin] for `(klarna, installments)`, rendered as
 * Klarna's native widget. The widget returns to the hosting Activity through
 * `SurfaceRequest.appReturnUrl`, defaulting to `"{packageName}.klarna://return"` — the deep link the
 * host app's manifest must declare.
 *
 * A class with a public no-arg constructor, as `ServiceLoader` requires; `PluginRegistry` keeps one.
 */
class KlarnaModule : PartnerModule {

    override fun register(registry: PluginRegistry) {
        registry.register(Partner.Klarna, Method.Installments) { KlarnaPlugin(it.partner) }
    }

    override fun renders(plugin: PartnerUiPlugin) = plugin is KlarnaPlugin

    @Composable
    override fun rememberSurface(plugin: PartnerUiPlugin, request: SurfaceRequest): PaymentSurface {
        val coordinator = rememberKlarnaCoordinator(request.listener)
        val returnUrl = request.appReturnUrl ?: "${LocalContext.current.packageName}.klarna://return"
        val containerAttached: MutableState<Boolean> = remember(request.key) { mutableStateOf(false) }
        // The /init client token carries the amount — prepare again when it moves (web's onAmountUpdate).
        val amount by request.host.amount.collectAsState()
        // Klarna inflates into a real ViewGroup — prepare once the AndroidView container exists.
        LaunchedEffect(coordinator, containerAttached.value, amount) {
            if (containerAttached.value) coordinator.prepare(request.host, returnUrl)
        }
        return remember(coordinator) {
            PaymentSurface(hasUi = true, selfSubmitting = false, onSubmit = { coordinator.submit() }) { modifier ->
                AndroidView(
                    modifier = modifier,
                    factory = { context ->
                        FrameLayout(context).also {
                            coordinator.container = it
                            containerAttached.value = true
                        }
                    },
                )
            }
        }
    }
}

/** The only way to obtain a [KlarnaCoordinator]: Compose-managed launchers, legal at any composition point. */
@Composable
fun rememberKlarnaCoordinator(listener: PaymentElementListener): KlarnaCoordinator {
    val activity = LocalContext.current.requireComponentActivity()
    val coordinator = remember(listener) { KlarnaCoordinator(activity, listener) }
    coordinator.launchRedirect = rememberRedirectLauncher(coordinator)
    return coordinator
}
