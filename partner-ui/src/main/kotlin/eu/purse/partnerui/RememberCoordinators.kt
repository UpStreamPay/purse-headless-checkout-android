// Web: none — web plugins render into a container element handed to `appendTo`.
// Same: one coordinator per rendered method.
// Differs: Compose owns the Activity-result launchers, so a coordinator is created where it renders.
package eu.purse.partnerui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.wallet.contract.TaskResultContracts
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayCoordinator
import eu.purse.partnerui.plugins.generics.redirection.RedirectionCoordinator
import eu.purse.partnerui.plugins.partners.klarna.KlarnaCoordinator
import eu.purse.partnerui.plugins.partners.paypal.wallet.PaypalCheckoutContract
import eu.purse.partnerui.plugins.partners.paypal.wallet.PaypalCoordinator
import eu.purse.partnerui.redirect.RedirectionCheckoutContract

/** The only way to obtain a coordinator: Compose-managed launchers, legal at any composition point. */
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

@Composable
fun rememberPaypalCoordinator(listener: PaymentElementListener): PaypalCoordinator {
    val activity = LocalContext.current.requireComponentActivity()
    val coordinator = remember(listener) { PaypalCoordinator(activity, listener) }
    val paypalLauncher = rememberLauncherForActivityResult(PaypalCheckoutContract()) { coordinator.onPaypalResult(it) }
    coordinator.launchPaypal = { input -> paypalLauncher.launch(input) }
    coordinator.launchRedirect = rememberRedirectLauncher(coordinator)
    return coordinator
}

@Composable
fun rememberKlarnaCoordinator(listener: PaymentElementListener): KlarnaCoordinator {
    val activity = LocalContext.current.requireComponentActivity()
    val coordinator = remember(listener) { KlarnaCoordinator(activity, listener) }
    coordinator.launchRedirect = rememberRedirectLauncher(coordinator)
    return coordinator
}

@Composable
fun rememberRedirectionCoordinator(listener: PaymentElementListener): RedirectionCoordinator {
    val activity = LocalContext.current.requireComponentActivity()
    val coordinator = remember(listener) { RedirectionCoordinator(activity, listener) }
    coordinator.launchRedirect = rememberRedirectLauncher(coordinator)
    return coordinator
}

@Composable
private fun rememberRedirectLauncher(coordinator: PaymentCoordinator): (eu.purse.partnerui.redirect.RedirectAction) -> Unit {
    val launcher = rememberLauncherForActivityResult(RedirectionCheckoutContract()) { coordinator.onRedirectReturn(it) }
    return { action -> launcher.launch(action) }
}

private fun Context.requireComponentActivity(): ComponentActivity {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    error("Purse payment coordinators need a ComponentActivity-hosted composition")
}
