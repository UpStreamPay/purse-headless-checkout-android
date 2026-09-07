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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Screen 3 — what actually happened, said once and clearly, plus the two things you'd reach for next:
 * the session's transactions (the authoritative status, especially after a redirect) and a capture.
 *
 * The capture's `payment_id` arrives prefilled: every coordinator now reports one via `onPaymentId`
 * when `/validate` carries it, which is what makes this tool usable at all — hunting a payment id out
 * of a log to paste back in was the old demo's dead end.
 */
@Composable
fun ResultScreen(controller: DemoController) {
    val outcome = controller.outcome ?: return
    val haptic = LocalHapticFeedback.current

    // The result deserves to be *felt*, not just read — one buzz as the screen lands.
    LaunchedEffect(outcome) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    DemoScreen(
        step = 3,
        title = stringResource(R.string.result_title),
        onBack = controller::back,
        bottomAction = {
            BigButton(text = stringResource(R.string.btn_start_over), onClick = controller::startOver)
        },
    ) {
        StatusBanner(outcome)
        TransactionsCard(controller)
        CaptureCard(controller)
        EventLog(controller.events)
    }
}

@Composable
private fun StatusBanner(outcome: DemoResult) {
    val accent = when (outcome) {
        is DemoResult.Authorized -> DemoStatusColors.success
        is DemoResult.Failed -> MaterialTheme.colorScheme.error
        is DemoResult.Unconfirmed -> DemoStatusColors.warning
    }
    val onAccent = when (outcome) {
        is DemoResult.Authorized -> DemoStatusColors.onSuccess
        is DemoResult.Failed -> MaterialTheme.colorScheme.onError
        is DemoResult.Unconfirmed -> DemoStatusColors.onWarning
    }
    val glyph = when (outcome) {
        is DemoResult.Authorized -> "✓"
        is DemoResult.Failed -> "✕"
        is DemoResult.Unconfirmed -> "!"
    }
    val title = stringResource(
        when (outcome) {
            is DemoResult.Authorized -> R.string.result_authorized
            is DemoResult.Failed -> R.string.result_failed
            is DemoResult.Unconfirmed -> R.string.result_unconfirmed
        },
    )
    val detail = when (outcome) {
        is DemoResult.Authorized -> outcome.detail
        is DemoResult.Failed -> outcome.detail
        is DemoResult.Unconfirmed -> outcome.detail
    }

    DemoCard(color = accent.copy(alpha = 0.14f)) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(84.dp)
                    .background(accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = glyph,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = onAccent,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.result_via, outcome.method),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (outcome is DemoResult.Unconfirmed) {
                Text(
                    text = stringResource(R.string.result_unconfirmed_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The session's own transactions — the authoritative answer. What matters is each one's
 * `action → state`, so those are the lines on screen; the full JSON stays behind an expander.
 */
@Composable
private fun TransactionsCard(controller: DemoController) {
    DemoCard {
        SectionTitle(stringResource(R.string.section_transactions))
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
}

@Composable
private fun CaptureCard(controller: DemoController) {
    DemoCard {
        SectionTitle(stringResource(R.string.section_capture))
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
                text = stringResource(
                    if (controller.capturing) R.string.capture_capturing else R.string.btn_capture,
                ),
                onClick = controller::capture,
                enabled = controller.capturePaymentId.isNotBlank(),
                loading = controller.capturing,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        controller.captureResult?.let { MonoBlock(it) }
    }
}
