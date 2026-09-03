package eu.purse.checkout.demo

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.wallet.button.ButtonConstants
import com.google.android.gms.wallet.button.ButtonOptions
import com.google.android.gms.wallet.button.PayButton
import com.paypal.android.paymentbuttons.PayPalButton
import com.paypal.android.paymentbuttons.PayPalButtonColor
import com.paypal.android.paymentbuttons.PayPalButtonLabel
import com.paypal.android.paymentbuttons.PaymentButtonShape
import eu.purse.checkout.PurseHeadlessCheckout
import eu.purse.checkout.payment.item.PaymentToken
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SavedSecondaryToken
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.checkout.payment.split.PaymentSplit
import eu.purse.core.payment.Method
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayMapper

/**
 * Screen 2 — the actual checkout: what's left to pay, the express (Google Pay) button, the primaries
 * you can select, and gift cards to chip away at the amount. One "Pay" button at the bottom submits
 * whichever primary is selected — Google Pay excepted, since its own button *is* the submit action
 * (see [DemoController]'s class doc).
 */
@Composable
fun PayScreen(controller: DemoController) {
    val checkout = controller.checkout ?: return
    val remaining by checkout.remainingAmountToPay.collectAsState()
    val fulfilled by checkout.isPaymentFulfilled.collectAsState()
    val split by checkout.amountSplit.collectAsState()

    Box(Modifier.fillMaxSize()) {
        DemoScreen(
            step = 2,
            title = stringResource(R.string.pay_title),
            onBack = controller::back,
            bottomAction = {
                BigButton(
                    // What the primary would actually charge is what's *left*, not the order total —
                    // and once gift cards cover everything, that's nothing at all.
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
            AmountHeader(checkout, remaining, fulfilled, split)

            if (controller.googlePayReady || controller.paypalReady) {
                SectionTitle(stringResource(R.string.section_express))
                if (controller.googlePayReady) {
                    GooglePayButton(enabled = !controller.googlePayBusy, onClick = controller::onGooglePayClicked)
                }
                if (controller.paypalReady) {
                    PaypalButton(enabled = !controller.paypalBusy, onClick = controller::onPaypalClicked)
                }
            }

            val savedCards by checkout.paymentTokens.collectAsState()
            if (savedCards.isNotEmpty()) {
                SectionTitle(stringResource(R.string.section_saved_cards))
                savedCards.forEach { SavedCardRow(controller, it) }
            }

            val methods = controller.payableMethods
            if (methods.isNotEmpty()) {
                SectionTitle(stringResource(R.string.section_primary))
                methods.forEach { PrimaryMethodRow(controller, it) }
            }

            // Saved gift cards resolve their balance in the background — observe the flow so the
            // rows move from "checking…" to a balance (or "unavailable") on their own.
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

        // All three flags matter: `submitting` covers the shared Pay button, `googlePayBusy` the sheet
        // the Google Pay button opened, and `paypalBusy` PayPal's whole /init → approval → /validate
        // round trip. Any of them means the screen must stop taking taps until it resolves.
        if (controller.submitting || controller.googlePayBusy || controller.paypalBusy) BusyOverlay()
    }
}

/** Total, what's still owed, and how the split currently covers it — the live feedback the old
 * layout only had buried in a monospace dump. */
@Composable
private fun AmountHeader(
    checkout: PurseHeadlessCheckout,
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
                    text = amountText(checkout.session.amount, checkout.currency),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = if (fulfilled) {
                    stringResource(R.string.label_covered)
                } else {
                    "${stringResource(R.string.label_remaining)} ${amountText(remaining, checkout.currency)}"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (fulfilled) DemoStatusColors.success else LocalContentColor.current,
            )
        }
        split.forEach { entry ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = entry.source.id,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = amountText(entry.amount, checkout.currency),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * PayPal's own branded button, from `com.paypal.android:payment-buttons`. Gold with the "Checkout"
 * label is PayPal's primary recommended treatment and reads on both themes, so unlike
 * [GooglePayButton] this one doesn't switch on [isSystemInDarkTheme].
 */
@Composable
private fun PaypalButton(enabled: Boolean, onClick: () -> Unit) {
    AndroidView(
        factory = { context ->
            PayPalButton(context).apply {
                color = PayPalButtonColor.GOLD
                label = PayPalButtonLabel.CHECKOUT
                shape = PaymentButtonShape.ROUNDED
            }
        },
        update = { button ->
            button.isEnabled = enabled
            button.setOnClickListener { onClick() }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    )
}

/**
 * Google's own branded button, hosted as-is — it's the one control this demo must not restyle, and
 * per Google Pay's brand guidelines the dark button goes on light backgrounds and vice versa.
 * Chrome only: the real request (with the gateway's tokenization spec) was built per-session by
 * `GooglePayCoordinator.prepare`.
 */
@Composable
private fun GooglePayButton(enabled: Boolean, onClick: () -> Unit) {
    val theme = if (isSystemInDarkTheme()) ButtonConstants.ButtonTheme.LIGHT else ButtonConstants.ButtonTheme.DARK
    AndroidView(
        factory = { context ->
            PayButton(context).apply {
                initialize(
                    ButtonOptions.newBuilder()
                        .setButtonType(ButtonConstants.ButtonType.PAY)
                        .setButtonTheme(theme)
                        .setAllowedPaymentMethods(GooglePayMapper.buttonAllowedPaymentMethodsJson().toString())
                        .build(),
                )
            }
        },
        update = { button ->
            button.isEnabled = enabled
            button.setOnClickListener { onClick() }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    )
}

/**
 * One selectable primary. Selecting it is what commits it (`setPrimary`) *and* mounts its payment
 * control — nothing is `/init`'d or rendered for a row the shopper hasn't picked. Tapping the open
 * row again collapses it and clears the primary.
 */
@Composable
private fun PrimaryMethodRow(controller: DemoController, method: PrimaryMethod) {
    val selected = controller.selectedPrimary?.id == method.id
    DemoCard(
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { controller.selectPrimary(if (selected) null else method) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(text = controller.label(method), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = method.id,
                    style = monoStyle,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
            Text(text = if (selected) "▾" else "▸", style = MaterialTheme.typography.bodyLarge)
        }
        AnimatedVisibility(selected) {
            // Redirection first, matching the controller's own dispatch and PluginRegistry's priority.
            when {
                controller.isRedirection(method) -> RedirectionNotice(controller, method)
                method.method == Method.Installments -> KlarnaWidget(controller, method)
                method.method == Method.CreditCard -> CardPaymentForm(controller, method)
                else -> Unit
            }
        }
    }
}

/**
 * One saved card (`paymentTokens`). Selecting it commits it as the primary (`setPrimaryToken`) and
 * mounts [SavedCardForm] — a CVV field when the PSP still wants one, a one-line notice otherwise.
 * Tapping the open row again collapses it and clears the primary.
 */
@Composable
private fun SavedCardRow(controller: DemoController, token: PaymentToken) {
    val selected = controller.selectedToken?.id == token.id
    DemoCard(
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { controller.selectToken(if (selected) null else token) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    text = if (token.favorite) "★ ${controller.label(token)}" else controller.label(token),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${token.maskedPan} · ${token.brand} · " + stringResource(
                        if (token.requiresCvv) R.string.saved_card_cvv_badge else R.string.saved_card_no_cvv_badge,
                    ),
                    style = monoStyle,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
            Text(text = if (selected) "▾" else "▸", style = MaterialTheme.typography.bodyLarge)
        }
        AnimatedVisibility(selected) { SavedCardForm(controller, token) }
    }
}

/**
 * The generic redirection template's entire "UI": an explanation and a readiness line. There is
 * deliberately nothing to fill in — the PSP's own hosted page collects everything, which is exactly
 * why one plugin can serve every partner configured this way. `prepare` (`/init`) runs on mount, i.e.
 * on selection, and the shared "Pay" button does the rest.
 */
@Composable
private fun RedirectionNotice(controller: DemoController, method: PrimaryMethod) {
    LaunchedEffect(method.id) { controller.prepareRedirection(method) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.helper_redirection),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (controller.redirectionPrepared) {
                stringResource(R.string.redirection_ready)
            } else {
                stringResource(R.string.redirection_preparing)
            },
            style = MaterialTheme.typography.titleSmall,
            color = if (controller.redirectionPrepared) DemoStatusColors.success else LocalContentColor.current,
        )
    }
}

/**
 * Hosts the one `KlarnaPaymentView` container the coordinator was constructed with, re-parented into
 * this row (see [DemoController.klarnaContainer]) — the defensive `removeView` is what makes
 * re-selecting a row safe, since a View can only have one parent. `prepare` runs on mount, i.e. on
 * selection, not before.
 */
@Composable
private fun KlarnaWidget(controller: DemoController, method: PrimaryMethod) {
    LaunchedEffect(method.id) { controller.prepareKlarna(method) }
    AndroidView(
        factory = {
            controller.klarnaContainer.also { (it.parent as? ViewGroup)?.removeView(it) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * One **saved** gift card: its balance arrives in the background (the row says "checking…" until
 * then), and a single tap draws it in for as much as the order needs — no number, no CVC to type.
 */
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
                SmallButton(
                    text = stringResource(R.string.btn_remove_giftcard),
                    onClick = { controller.removeSavedGiftCard(token) },
                )
            } else if (token.disabled == null && token.balanceResolved) {
                SmallButton(
                    text = stringResource(R.string.btn_apply_saved_giftcard),
                    onClick = { controller.applySavedGiftCard(token) },
                )
            }
        }
    }
}

/**
 * One gift card: check its balance (`/init`, which does **not** touch the split), then decide what
 * to contribute. Two deliberate steps — the amount controls only appear once a balance is known.
 */
@Composable
private fun GiftCardRow(controller: DemoController, method: SecondaryMethod) {
    val state = controller.giftCardState(method)
    var expanded by remember { mutableStateOf(false) }
    DemoCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = method.id,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
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
                SmallButton(
                    text = stringResource(R.string.btn_check_balance),
                    onClick = { controller.checkBalance(method, state) },
                    loading = state.checking,
                )
                state.balanceLabel?.let {
                    Text(text = it, style = MaterialTheme.typography.titleSmall)
                }
                if (state.token != null) {
                    OutlinedTextField(
                        value = state.amount,
                        onValueChange = { state.amount = it },
                        label = { Text(stringResource(R.string.hint_giftcard_amount)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Only when the method's save_token_mode lets the shopper choose (PREFER /
                    // PREFER_NO) — ALWAYS and NEVER give nothing to decide.
                    if (state.token?.canBeRegistered == true) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { state.register = !state.register },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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
                    SmallButton(
                        text = stringResource(R.string.btn_apply_giftcard),
                        onClick = { controller.applyGiftCard(state) },
                    )
                }
                state.appliedLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleSmall,
                        color = DemoStatusColors.success,
                    )
                }
                state.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** Blocks the screen while a payment is in flight — a submit that looks like nothing happened is
 * exactly what made the old demo hard to read. */
@Composable
private fun BusyOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        DemoCard(modifier = Modifier.padding(48.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(16.dp))
                Text(
                    text = stringResource(R.string.submitting),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
