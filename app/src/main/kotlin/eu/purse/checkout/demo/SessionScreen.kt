package eu.purse.checkout.demo

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import eu.purse.checkout.PurseHeadlessCheckout

/**
 * Screen 1 — get a session. One button does the whole thing (alfred mints it, the SDK initializes
 * off its `widget.data`); the blob itself and the SDK's decoded state are both available but folded
 * away, since neither is something you read on the way to paying.
 */
@Composable
fun SessionScreen(controller: DemoController) {
    val checkout = controller.checkout
    DemoScreen(
        step = 1,
        title = stringResource(R.string.session_title),
        bottomAction = {
            BigButton(
                text = stringResource(R.string.btn_continue),
                onClick = controller::goToPay,
                enabled = checkout != null,
            )
        },
    ) {
        Text(
            text = stringResource(R.string.session_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DemoCard {
            LabeledValue(stringResource(R.string.label_alfred), controller.alfredBaseUrl, mono = true)
            BigButton(
                text = stringResource(
                    if (controller.creatingSession) R.string.btn_creating_session else R.string.btn_create_session,
                ),
                onClick = controller::createSession,
                loading = controller.creatingSession,
            )
            Disclosure(title = stringResource(R.string.section_widget_data)) {
                OutlinedTextField(
                    value = controller.widgetData,
                    onValueChange = { controller.widgetData = it },
                    label = { Text(stringResource(R.string.hint_widget_data)) },
                    textStyle = monoStyle,
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                SmallButton(
                    text = stringResource(R.string.btn_use_widget_data),
                    onClick = controller::initCheckout,
                )
            }
        }

        controller.sessionError?.let { ErrorCard(it) }
        if (checkout != null) SessionSummary(checkout, controller.sessionDetails)
    }
}

@Composable
private fun SessionSummary(checkout: PurseHeadlessCheckout, details: String) {
    val methods by checkout.paymentMethods.collectAsState()
    DemoCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = amountText(checkout.session.amount, checkout.currency),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.session_ready),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = pluralStringResource(R.plurals.session_methods, methods.size, methods.size),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Disclosure(title = stringResource(R.string.section_session_details)) { MonoBlock(details) }
    }
}
