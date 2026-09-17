package eu.purse.checkout.demo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.purse.checkout.PurseHeadlessCheckout

/**
 * Screen 1 — Purse's side of the demo. The session is minted on arrival (alfred mints it, the SDK
 * initializes off its `widget.data`), then the presenter plays the store: every method the session
 * offers is a switch, with presets for the common stories ("card only", "wallets"). Whatever is on
 * here is what the checkout renders — same session, the store decides what the shopper sees.
 *
 * The plumbing (alfred's URL, a hand-pasted `widget.data`, the SDK's decoded state) stays available
 * under *Developer*.
 */
@Composable
fun SetupScreen(controller: DemoController) {
    val checkout = controller.checkout

    // One tap fewer on stage: the session is minted as soon as the page appears.
    LaunchedEffect(Unit) {
        if (checkout == null && !controller.creatingSession && controller.sessionError == null && controller.widgetData.isBlank()) {
            controller.createSession()
        }
    }

    DemoScreen(
        eyebrow = stringResource(R.string.setup_eyebrow),
        title = stringResource(R.string.setup_title),
        bottomAction = {
            BigButton(
                text = stringResource(R.string.btn_open_checkout, DemoStore.NAME),
                onClick = controller::openCheckout,
                enabled = checkout != null,
            )
        },
    ) {
        StoreCard(controller, checkout)
        controller.sessionError?.let {
            ErrorCard(it)
            SmallButton(text = stringResource(R.string.btn_retry_session), onClick = controller::createSession)
        }

        if (checkout != null) {
            SectionTitle(stringResource(R.string.picker_title))
            Text(
                text = stringResource(R.string.picker_helper),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PresetRow(controller)
            MethodPicker(controller)
        }

        Disclosure(title = stringResource(R.string.section_developer)) {
            LabeledValue(stringResource(R.string.label_alfred), controller.alfredBaseUrl, mono = true)
            OutlinedTextField(
                value = controller.widgetData,
                onValueChange = { controller.widgetData = it },
                label = { Text(stringResource(R.string.hint_widget_data)) },
                textStyle = monoStyle,
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallButton(text = stringResource(R.string.btn_use_widget_data), onClick = controller::initCheckout)
                SmallButton(
                    text = stringResource(R.string.btn_create_session),
                    onClick = controller::createSession,
                    loading = controller.creatingSession,
                )
            }
            if (controller.sessionDetails.isNotBlank()) {
                Disclosure(title = stringResource(R.string.section_session_details)) { MonoBlock(controller.sessionDetails) }
            }
        }
    }
}

/** The store and its session: the amount the shopper will pay, and whether the SDK is ready. */
@Composable
private fun StoreCard(controller: DemoController, checkout: PurseHeadlessCheckout?) {
    DemoCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = DemoStore.NAME,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = DemoStore.TAGLINE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
            if (controller.creatingSession) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            } else if (checkout != null) {
                Text(
                    text = stringResource(R.string.session_ready),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (checkout != null) {
            val methods by checkout.paymentMethods.collectAsState()
            val order = controller.order
            ThinDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.setup_basket),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                    Text(
                        text = order?.lines?.joinToString { "${it.quantity} × ${it.name}" } ?: "—",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    text = moneyText(checkout.session.amount, checkout.currency),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = pluralStringResource(R.plurals.session_methods, methods.size, methods.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
        } else if (controller.creatingSession) {
            Text(
                text = stringResource(R.string.btn_creating_session),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun PresetRow(controller: DemoController) {
    val active = controller.activePreset
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MethodPreset.entries.forEach { preset ->
            FilterChip(
                selected = active == preset,
                onClick = { controller.applyPreset(preset) },
                label = { Text(stringResource(presetLabel(preset))) },
            )
        }
    }
}

private fun presetLabel(preset: MethodPreset): Int = when (preset) {
    MethodPreset.Everything -> R.string.preset_everything
    MethodPreset.CardOnly -> R.string.preset_card_only
    MethodPreset.Wallets -> R.string.preset_wallets
    MethodPreset.NoExpress -> R.string.preset_no_express
}

/** One switch per method the session offers. Off = the checkout doesn't render it. */
@Composable
private fun MethodPicker(controller: DemoController) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        controller.pickerEntries.forEach { entry ->
            val shown = controller.isShown(entry.id)
            DemoCard(
                border = if (shown) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                padding = 14,
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { controller.toggleShown(entry.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MethodLogo(entry.iconUrl, entry.name, height = 26)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(text = entry.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = entry.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = shown,
                        onCheckedChange = { controller.toggleShown(entry.id) },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
