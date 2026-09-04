package eu.purse.checkout.demo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.purse.checkout.element.PaymentElementUi
import eu.purse.checkout.element.rememberPaymentElement
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.checkout.payment.item.PaymentToken
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SavedSecondaryToken
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.checkout.payment.split.PaymentSplit

/**
 * Screen 2 — the checkout: what's left to pay, the express buttons, the rows you can select
 * (methods and saved tokens), and gift cards. One "Pay" button submits the selected row's element;
 * express elements submit themselves.
 */
@Composable
fun PayScreen(controller: DemoController) {
    val checkout = controller.checkout ?: return
    val remaining by checkout.remainingAmountToPay.collectAsState()
    val fulfilled by checkout.isPaymentFulfilled.collectAsState()
    val split by checkout.amountSplit.collectAsState()
    LaunchedEffect(split) { controller.syncSelection(split) }

    Box(Modifier.fillMaxSize()) {
        DemoScreen(
            step = 2,
            title = stringResource(R.string.pay_title),
            onBack = controller::back,
            bottomAction = {
                BigButton(
                    text = if (remaining.signum() > 0) {
                        stringResource(R.string.btn_pay, amountText(remaining, checkout.currency))
                    } else {
                        stringResource(R.string.btn_pay_now)
                    },
                    onClick = controller::submit,
                    enabled = controller.submitEnabled(fulfilled),
                    loading = controller.submitting,
                )
            },
        ) {
            // `paymentSession` is the observable handle on the session in force (setSession swaps it).
            val session by checkout.paymentSession.collectAsState()
            AmountHeader(session.amount, checkout.currency, remaining, fulfilled, split)
            SmallButton(
                text = stringResource(R.string.btn_refresh_session),
                onClick = controller::refreshSession,
                enabled = !controller.refreshingSession,
                loading = controller.refreshingSession,
            )

            val express = controller.expressMethods
            if (express.isNotEmpty()) {
                SectionTitle(stringResource(R.string.section_express))
                express.forEach { ExpressElement(controller, it) }
            }

            val savedTokens by checkout.paymentTokens.collectAsState()
            if (savedTokens.isNotEmpty()) {
                SectionTitle(stringResource(R.string.section_saved_cards))
                savedTokens.forEach { SelectableRow(controller, it) }
            }

            val methods = controller.payableMethods
            if (methods.isNotEmpty()) {
                SectionTitle(stringResource(R.string.section_primary))
                methods.forEach { SelectableRow(controller, it) }
            }

            val savedGiftCards by checkout.savedSecondaryTokens.collectAsState()
            if (savedGiftCards.isNotEmpty()) {
                SectionTitle(stringResource(R.string.section_saved_giftcards))
                savedGiftCards.forEach { SavedGiftCardRow(controller, it) }
            }

            val giftCards = controller.giftCardMethods
            if (giftCards.isNotEmpty()) {
                SectionTitle(stringResource(R.string.section_secondary))
                Text(
                    text = stringResource(R.string.helper_giftcard),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                giftCards.forEach { GiftCardRow(controller, it) }
            }

            EventLog(controller.events)
        }

        if (controller.submitting) BusyOverlay()
    }
}

/** Total, what's still owed, and how the split currently covers it. */
@Composable
private fun AmountHeader(
    total: java.math.BigDecimal,
    currency: String,
    remaining: java.math.BigDecimal,
    fulfilled: Boolean,
    split: List<PaymentSplit>,
) {
    DemoCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.label_total),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                )
                Text(
                    text = amountText(total, currency),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = if (fulfilled) {
                    stringResource(R.string.label_covered)
                } else {
                    "${stringResource(R.string.label_remaining)} ${amountText(remaining, currency)}"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (fulfilled) DemoStatusColors.success else LocalContentColor.current,
            )
        }
        split.forEach { entry ->
            Row(Modifier.fillMaxWidth()) {
                Text(text = entry.source.id, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(text = amountText(entry.amount, currency), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** An express method's own button (Google Pay, PayPal): always composed, submits itself. */
@Composable
private fun ExpressElement(controller: DemoController, method: PrimaryMethod) {
    val checkout = controller.checkout ?: return
    val listener = remember(method.id) { controller.listenerFor(method) }
    val element = rememberPaymentElement(checkout, method, listener)
    PaymentElementUi(element, Modifier.fillMaxWidth().height(56.dp))
}

/**
 * One selectable row — a [PrimaryMethod] or a saved [PaymentToken]. Selecting it commits it as the
 * primary and composes its element; nothing is `/init`'d for a row the shopper hasn't picked.
 * Tapping the open row again collapses it and clears the primary.
 */
@Composable
private fun SelectableRow(controller: DemoController, item: PaymentItem) {
    val selected = controller.selectedItem?.id == item.id
    DemoCard(border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
        Row(
            Modifier.fillMaxWidth().clickable { controller.select(if (selected) null else item) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(text = controller.label(item), style = MaterialTheme.typography.titleMedium)
                Text(text = subtitle(controller, item), style = monoStyle, color = LocalContentColor.current.copy(alpha = 0.7f))
            }
            Text(text = if (selected) "▾" else "▸", style = MaterialTheme.typography.bodyLarge)
        }
        AnimatedVisibility(selected) { ElementBody(controller, item) }
    }
}

@Composable
private fun subtitle(controller: DemoController, item: PaymentItem): String = when (item) {
    is PaymentToken -> if (item.isVaultedCard) {
        "${item.maskedPan} · ${item.brand} · " + stringResource(
            if (item.requiresCvv) R.string.saved_card_cvv_badge else R.string.saved_card_no_cvv_badge,
        )
    } else {
        "${controller.partnerLabel(item)} · " + stringResource(R.string.saved_wallet_token_badge)
    }
    else -> item.id
}

/**
 * The selected row's element: its UI when it has one (card form, CVV field, Klarna widget), a
 * readiness line otherwise (redirection, saved card without CVV, saved PayPal). The element is
 * handed to the controller so the shared "Pay" button can submit it.
 */
@Composable
private fun ElementBody(controller: DemoController, item: PaymentItem) {
    val checkout = controller.checkout ?: return
    val listener = remember(item.id) { controller.listenerFor(item) }
    val element = rememberPaymentElement(checkout, item, listener)
    LaunchedEffect(element) { controller.attachElement(element) }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        helperText(controller, item, hasUi = element.hasUi)?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (element.hasUi) {
            PaymentElementUi(element, Modifier.fillMaxWidth())
        } else {
            Text(
                text = stringResource(if (element.ready) R.string.redirection_ready else R.string.redirection_preparing),
                style = MaterialTheme.typography.titleSmall,
                color = if (element.ready) DemoStatusColors.success else LocalContentColor.current,
            )
        }
        if (item is PrimaryMethod && item.simulable) SimulationPreview(checkout, item)
    }
}

@Composable
private fun helperText(controller: DemoController, item: PaymentItem, hasUi: Boolean): String? = when {
    item is PaymentToken && !item.isVaultedCard -> stringResource(R.string.helper_saved_wallet_token, controller.partnerLabel(item))
    item is PaymentToken -> stringResource(if (hasUi) R.string.helper_saved_card_cvv else R.string.helper_saved_card_no_cvv)
    item is PrimaryMethod && controller.isRedirection(item) -> stringResource(R.string.helper_redirection)
    else -> null
}

/** One saved gift card: balance arrives in the background, one tap draws it in. */
@Composable
private fun SavedGiftCardRow(controller: DemoController, token: SavedSecondaryToken) {
    val applied = controller.isSavedGiftCardApplied(token)
    DemoCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = controller.savedGiftCardLabel(token),
                style = MaterialTheme.typography.titleSmall,
                color = if (token.disabled != null) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current,
                modifier = Modifier.weight(1f),
            )
            if (applied) {
                SmallButton(text = stringResource(R.string.btn_remove_giftcard), onClick = { controller.removeSavedGiftCard(token) })
            } else if (token.disabled == null && token.balanceResolved) {
                SmallButton(text = stringResource(R.string.btn_apply_saved_giftcard), onClick = { controller.applySavedGiftCard(token) })
            }
        }
    }
}

/** One gift card: check its balance (`/init`, no split change), then decide what to contribute. */
@Composable
private fun GiftCardRow(controller: DemoController, method: SecondaryMethod) {
    val state = controller.giftCardState(method)
    var expanded by remember { mutableStateOf(false) }
    DemoCard {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text(text = method.id, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(text = if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodyLarge)
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.pan,
                    onValueChange = { state.pan = it },
                    label = { Text(stringResource(R.string.hint_giftcard_pan)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (controller.requiresCvv(method)) {
                    OutlinedTextField(
                        value = state.cvv,
                        onValueChange = { state.cvv = it },
                        label = { Text(stringResource(R.string.hint_giftcard_cvv)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SmallButton(text = stringResource(R.string.btn_check_balance), onClick = { controller.checkBalance(method, state) }, loading = state.checking)
                state.balanceLabel?.let { Text(text = it, style = MaterialTheme.typography.titleSmall) }
                if (state.token != null) {
                    OutlinedTextField(
                        value = state.amount,
                        onValueChange = { state.amount = it },
                        label = { Text(stringResource(R.string.hint_giftcard_amount)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.token?.canBeRegistered == true) {
                        Row(Modifier.fillMaxWidth().clickable { state.register = !state.register }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = state.register, onCheckedChange = { state.register = it })
                            Text(text = stringResource(R.string.giftcard_save), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (state.register) {
                            OutlinedTextField(
                                value = state.label,
                                onValueChange = { state.label = it },
                                label = { Text(stringResource(R.string.hint_giftcard_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    SmallButton(text = stringResource(R.string.btn_apply_giftcard), onClick = { controller.applyGiftCard(state) })
                }
                state.appliedLabel?.let { Text(text = it, style = MaterialTheme.typography.titleSmall, color = DemoStatusColors.success) }
                state.error?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** Blocks the screen while a payment is in flight. */
@Composable
private fun BusyOverlay() {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        DemoCard(modifier = Modifier.padding(48.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(16.dp))
                Text(text = stringResource(R.string.submitting), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
