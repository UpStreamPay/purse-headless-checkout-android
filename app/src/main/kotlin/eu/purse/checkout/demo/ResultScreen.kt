package eu.purse.checkout.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Screen 3 — the store's confirmation page: thank you and the order recap when the payment is
 * authorized, a declined notice with a way back to the checkout otherwise. A redirect return lands
 * as "confirming…" while the controller polls the session's transactions (see
 * `DemoController.confirmInBackground`), then settles.
 *
 * The `</>` panel keeps the operator's tools: the transactions lookup, a capture (its `payment_id`
 * prefilled from whichever coordinator reported one), and the event log.
 */
@Composable
fun ResultScreen(controller: DemoController) {
    val outcome = controller.outcome ?: return
    val haptic = LocalHapticFeedback.current

    // The result deserves to be *felt*, not just read — one buzz as the screen lands.
    LaunchedEffect(outcome) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    DemoScreen(
        eyebrow = stringResource(R.string.checkout_eyebrow),
        title = DemoStore.NAME,
        onBack = if (outcome is DemoResult.Failed) controller::tryAgain else null,
        headerActions = { DeveloperToggle(controller.developerPanel) { controller.developerPanel = !controller.developerPanel } },
        bottomAction = {
            if (outcome is DemoResult.Failed) {
                BigButton(text = stringResource(R.string.btn_try_again), onClick = controller::tryAgain)
            } else {
                BigButton(text = stringResource(R.string.btn_back_to_store), onClick = controller::startOver)
            }
        },
    ) {
        StatusBanner(controller, outcome)
        controller.order?.let { OrderRecap(controller, it) }
        if (controller.developerPanel) {
            DemoCard(color = MaterialTheme.colorScheme.surfaceVariant, padding = 16) {
                SectionTitle(stringResource(R.string.section_developer))
                Text(
                    text = stringResource(R.string.result_via, outcome.method) + " — " + detailOf(outcome),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TransactionsSection(controller)
                CaptureSection(controller)
                EventLog(controller.events)
            }
        }
    }
}

private fun detailOf(outcome: DemoResult): String = when (outcome) {
    is DemoResult.Authorized -> outcome.detail
    is DemoResult.Failed -> outcome.detail
    is DemoResult.Unconfirmed -> outcome.detail
}

/** Everything the banner varies on, resolved once so the layout below stays flat. */
private data class BannerLook(
    val accent: Color,
    val onAccent: Color,
    val glyph: String,
    val title: String,
    val body: String,
    val spinner: Boolean,
)

@Composable
private fun bannerLook(controller: DemoController, outcome: DemoResult): BannerLook {
    val order = controller.order
    val firstName = order?.customerName?.substringBefore(' ')
    return when (outcome) {
        is DemoResult.Authorized -> BannerLook(
            accent = DemoStatusColors.success,
            onAccent = DemoStatusColors.onSuccess,
            glyph = "✓",
            title = if (firstName != null) {
                stringResource(R.string.result_thanks_name, firstName)
            } else {
                stringResource(R.string.result_thanks)
            },
            body = stringResource(R.string.result_order_confirmed, order?.reference ?: "—"),
            spinner = false,
        )
        is DemoResult.Failed -> BannerLook(
            accent = MaterialTheme.colorScheme.error,
            onAccent = MaterialTheme.colorScheme.onError,
            glyph = "✕",
            title = stringResource(R.string.result_failed),
            body = stringResource(R.string.result_failed_body),
            spinner = false,
        )
        is DemoResult.Unconfirmed -> if (controller.confirming) {
            BannerLook(
                accent = MaterialTheme.colorScheme.primary,
                onAccent = MaterialTheme.colorScheme.onPrimary,
                glyph = "…",
                title = stringResource(R.string.result_confirming),
                body = stringResource(R.string.result_confirming_body),
                spinner = true,
            )
        } else {
            BannerLook(
                accent = DemoStatusColors.warning,
                onAccent = DemoStatusColors.onWarning,
                glyph = "!",
                title = stringResource(R.string.result_pending),
                body = stringResource(R.string.result_pending_body),
                spinner = false,
            )
        }
    }
}

@Composable
private fun StatusBanner(controller: DemoController, outcome: DemoResult) {
    val look = bannerLook(controller, outcome)
    val email = controller.order?.customerEmail
    DemoCard(color = look.accent.copy(alpha = 0.12f)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(84.dp).background(look.accent, CircleShape), contentAlignment = Alignment.Center) {
                if (look.spinner) {
                    CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp, color = look.onAccent)
                } else {
                    Text(
                        text = look.glyph,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = look.onAccent,
                    )
                }
            }
            Text(
                text = look.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = look.body,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (outcome is DemoResult.Authorized && email != null) {
                Text(
                    text = stringResource(R.string.result_email, email),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (outcome is DemoResult.Failed) {
                Text(
                    text = outcome.detail,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The order once more, compact: lines, total, how it was paid. */
@Composable
private fun OrderRecap(controller: DemoController, order: OrderSummary) {
    val checkout = controller.checkout
    val currency = checkout?.currency ?: order.currency
    val total = checkout?.session?.amount ?: order.total
    DemoCard {
        SectionTitle(stringResource(R.string.section_order))
        order.lines.forEach { line ->
            PriceLine("${line.quantity} × ${line.name}", moneyText(line.amount, currency), muted = true)
        }
        ThinDivider()
        PriceLine(stringResource(R.string.label_total), moneyText(total, currency), emphasis = true)
        controller.outcome?.let { outcome ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.result_paid_with),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(text = outcome.method, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
        if (order.address != null) {
            ThinDivider()
            Text(
                text = "${order.delivery} · ${order.address}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The session's own transactions — the authoritative answer. What matters is each one's
 * `action → state`, so those are the lines on screen; the full JSON stays behind an expander.
 */
@Composable
private fun TransactionsSection(controller: DemoController) {
    Text(text = stringResource(R.string.section_transactions), style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = controller.alfredSessionId,
        onValueChange = { controller.alfredSessionId = it },
        label = { Text(stringResource(R.string.hint_session_id)) },
        textStyle = monoStyle,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SmallButton(
        text = stringResource(
            if (controller.loadingTransactions) R.string.transactions_fetching else R.string.btn_get_transactions,
        ),
        onClick = controller::getTransactions,
        loading = controller.loadingTransactions,
    )
    controller.transactionStatuses.forEach { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.titleSmall,
            color = if (line.endsWith("SUCCESS")) DemoStatusColors.success else MaterialTheme.colorScheme.error,
        )
    }
    controller.transactions?.let { raw ->
        Disclosure(title = stringResource(R.string.section_transactions_raw)) { MonoBlock(raw) }
    }
}

@Composable
private fun CaptureSection(controller: DemoController) {
    Text(text = stringResource(R.string.section_capture), style = MaterialTheme.typography.titleSmall)
    if (controller.capturePaymentId.isBlank()) {
        Text(
            text = stringResource(R.string.capture_needs_payment_id),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedTextField(
        value = controller.capturePaymentId,
        onValueChange = { controller.capturePaymentId = it },
        label = { Text(stringResource(R.string.hint_capture_payment_id)) },
        textStyle = monoStyle,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = controller.captureAmount,
            onValueChange = { controller.captureAmount = it },
            label = { Text(stringResource(R.string.hint_capture_amount)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.width(140.dp),
        )
        SmallButton(
            text = stringResource(if (controller.capturing) R.string.capture_capturing else R.string.btn_capture),
            onClick = controller::capture,
            enabled = controller.capturePaymentId.isNotBlank(),
            loading = controller.capturing,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
    controller.captureResult?.let { MonoBlock(it) }
}
