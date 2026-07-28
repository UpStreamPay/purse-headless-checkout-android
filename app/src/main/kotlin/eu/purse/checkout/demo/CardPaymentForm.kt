package eu.purse.checkout.demo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.partnerui.plugins.vaults.purse.creditcard.rememberCardCoordinator
import eu.purse.securefields.VaultForm

/**
 * The card (Purse vault / SecureFields) primary. Compose-first in the SDK too, so there's almost
 * nothing to do here: [rememberCardCoordinator] owns the SecureFields instance and `VaultForm`
 * renders it.
 *
 * No "Pay" button of its own — the screen's single bottom button submits, gated on the form's own
 * validity (which arrives as `DemoController.cardListener.onReady`) and driven through the
 * coordinator's live `submit`, reported up via [DemoController.onCardSubmitReady].
 *
 * The `remember(method.id)` on the host is load-bearing: `rememberCardCoordinator` keys its
 * SecureFields instance on the host object, so building a fresh host on every recomposition would
 * tear down and rebuild the card form (losing whatever's been typed) each time any state on this
 * screen changes.
 */
@Composable
fun CardPaymentForm(controller: DemoController, method: PrimaryMethod) {
    val checkout = controller.checkout ?: return
    val host = remember(method.id) { checkout.cardHost(method) }
    val coordinator = rememberCardCoordinator(host, controller.cardListener)

    LaunchedEffect(coordinator) { controller.onCardSubmitReady { coordinator.submit() } }

    Column(Modifier.fillMaxWidth()) {
        VaultForm(
            coordinator.sdk,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        )
    }
}
