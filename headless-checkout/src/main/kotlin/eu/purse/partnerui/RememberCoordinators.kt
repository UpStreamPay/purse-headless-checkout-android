// Web: none — web plugins render into a container element handed to `appendTo`.
// Same: one coordinator per rendered method.
// Differs: Compose owns the Activity-result launchers, so a coordinator is created where it renders;
//          each optional partner artifact ships its own `remember*Coordinator` on the helpers here.
package eu.purse.partnerui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.purse.core.InternalPurseApi
import eu.purse.partnerui.plugins.generics.redirection.RedirectionCoordinator
import eu.purse.partnerui.redirect.RedirectAction
import eu.purse.partnerui.redirect.RedirectionCheckoutContract

/** The only way to obtain a coordinator: Compose-managed launchers, legal at any composition point. */
@OptIn(InternalPurseApi::class)
@Composable
fun rememberRedirectionCoordinator(listener: PaymentElementListener): RedirectionCoordinator {
    val activity = LocalContext.current.requireComponentActivity()
    val coordinator = remember(listener) { RedirectionCoordinator(activity, listener) }
    coordinator.launchRedirect = rememberRedirectLauncher(coordinator)
    return coordinator
}

/**
 * The Compose-managed launcher for the in-app redirect WebView, delivering the return to
 * [coordinator]. Every `remember*Coordinator` — in this artifact and the optional partner ones —
 * assigns it to `PaymentCoordinator.launchRedirect`.
 */
@InternalPurseApi
@Composable
fun rememberRedirectLauncher(coordinator: PaymentCoordinator): (RedirectAction) -> Unit {
    val launcher = rememberLauncherForActivityResult(RedirectionCheckoutContract()) { coordinator.onRedirectReturn(it) }
    return { action -> launcher.launch(action) }
}

/** The [ComponentActivity] hosting this composition — coordinators need its lifecycle scope and launchers. */
@InternalPurseApi
fun Context.requireComponentActivity(): ComponentActivity {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    error("Purse payment coordinators need a ComponentActivity-hosted composition")
}
