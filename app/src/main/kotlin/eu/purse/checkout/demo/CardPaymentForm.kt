package eu.purse.checkout.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.purse.checkout.payment.item.PaymentToken
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.partnerui.plugins.vaults.purse.creditcard.rememberCardCoordinator
import eu.purse.partnerui.plugins.vaults.purse.creditcard.PurseCardForm

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

    // The SDK's own form: labels, help texts and error messages around the secure fields, styled
    // from PluginOptions.theme — what PaymentElementUi renders too.
    Column(Modifier.fillMaxWidth()) {
        PurseCardForm(coordinator, Modifier.fillMaxWidth())
    }
}

/**
 * A saved card (`paymentTokens`), through the very same coordinator — `cardHost(token)` is what
 * tells it to run in saved-card mode. When the PSP still wants the CVV (`token.requiresCvv`) the
 * coordinator renders a **CVV-only** SecureFields form and the SDK keeps `isPaymentFulfilled`
 * `false` until it validates; behind the no-CVV gates there is no form at all and the row is
 * payable as soon as it's selected. Either way the shared "Pay" button drives `submit()`, which
 * runs `/init`+`/validate` and follows a 3-D Secure redirect exactly like a fresh card.
 */
@Composable
fun SavedCardForm(controller: DemoController, token: PaymentToken) {
    val checkout = controller.checkout ?: return
    val host = remember(token.id) { checkout.cardHost(token) }
    val coordinator = rememberCardCoordinator(host, controller.cardListener)

    LaunchedEffect(coordinator) { controller.onCardSubmitReady { coordinator.submit() } }

    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                if (coordinator.mode.rendersForm) R.string.helper_saved_card_cvv else R.string.helper_saved_card_no_cvv,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (coordinator.mode.rendersForm) {
            PurseCardForm(coordinator, Modifier.fillMaxWidth())
        }
    }
}
