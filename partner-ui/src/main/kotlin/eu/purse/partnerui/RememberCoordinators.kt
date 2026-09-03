package eu.purse.partnerui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayCoordinator
import eu.purse.partnerui.plugins.generics.redirection.RedirectionCoordinator
import eu.purse.partnerui.plugins.partners.klarna.KlarnaCoordinator
import eu.purse.partnerui.plugins.partners.paypal.wallet.PaypalCheckoutContract
import eu.purse.partnerui.plugins.partners.paypal.wallet.PaypalCoordinator
import eu.purse.partnerui.redirect.RedirectionCheckoutContract

/**
 * Compose factories for the imperative coordinators. The constructor forms must run in an
 * Activity's `onCreate` (they call `registerForActivityResult`, illegal once the Activity is
 * STARTED); these factories instead wire Compose-managed launchers
 * (`rememberLauncherForActivityResult`), which are legal at any composition point — so a
 * coordinator can be created exactly where it's rendered. This is what the unified payment element
 * builds on.
 */

@Composable
fun rememberGooglePayCoordinator(listener: PaymentElementListener): GooglePayCoordinator {
    val activity = LocalContext.current.requireComponentActivity()
    val coordinator = remember(listener) { GooglePayCoordinator.forCompose(activity, listener) }
    val paymentDataLauncher = rememberLauncherForActivityResult(
        com.google.android.gms.wallet.contract.TaskResultContracts.GetPaymentDataResult(),
    ) { coordinator.onPaymentData(it) }
    val redirectLauncher = rememberLauncherForActivityResult(RedirectionCheckoutContract()) {
        coordinator.onRedirectReturn(it)
    }
    coordinator.launchPaymentData = { task -> paymentDataLauncher.launch(task) }
    coordinator.launchRedirect = { action -> redirectLauncher.launch(action) }
    return coordinator
}

@Composable
fun rememberPaypalCoordinator(listener: PaymentElementListener): PaypalCoordinator {
    val activity = LocalContext.current.requireComponentActivity()
    val coordinator = remember(listener) { PaypalCoordinator.forCompose(activity, listener) }
    val paypalLauncher = rememberLauncherForActivityResult(PaypalCheckoutContract()) {
        coordinator.onPaypalResult(it)
    }
    val redirectLauncher = rememberLauncherForActivityResult(RedirectionCheckoutContract()) {
        coordinator.onRedirectReturn(it)
    }
    coordinator.launchPaypal = { input -> paypalLauncher.launch(input) }
    coordinator.launchRedirect = { action -> redirectLauncher.launch(action) }
    return coordinator
}

/**
 * The widget container is injected separately (`coordinator.container`) — the unified payment
 * element assigns the `AndroidView`-provided `FrameLayout` before calling `prepare`.
 */
@Composable
fun rememberKlarnaCoordinator(listener: PaymentElementListener): KlarnaCoordinator {
    val activity = LocalContext.current.requireComponentActivity()
    val coordinator = remember(listener) { KlarnaCoordinator.forCompose(activity, listener) }
    val redirectLauncher = rememberLauncherForActivityResult(RedirectionCheckoutContract()) {
        coordinator.onRedirectReturn(it)
    }
    coordinator.launchRedirect = { action -> redirectLauncher.launch(action) }
    return coordinator
}

@Composable
fun rememberRedirectionCoordinator(listener: PaymentElementListener): RedirectionCoordinator {
    val activity = LocalContext.current.requireComponentActivity()
    val coordinator = remember(listener) { RedirectionCoordinator.forCompose(activity, listener) }
    val redirectLauncher = rememberLauncherForActivityResult(RedirectionCheckoutContract()) {
        coordinator.onRedirectReturn(it)
    }
    coordinator.launchRedirect = { action -> redirectLauncher.launch(action) }
    return coordinator
}

private fun Context.requireComponentActivity(): ComponentActivity {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    error("Purse payment coordinators need a ComponentActivity-hosted composition")
}
