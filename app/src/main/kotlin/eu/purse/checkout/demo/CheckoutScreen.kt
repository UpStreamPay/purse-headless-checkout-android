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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.purse.checkout.PurseHeadlessCheckout
import eu.purse.checkout.element.PaymentElementUi
import eu.purse.checkout.element.rememberPaymentElement
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.checkout.payment.item.PaymentToken
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SavedSecondaryToken
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.checkout.payment.split.PaymentSplit
import java.math.BigDecimal

/**
 * Screen 2 — the store's checkout, as a shopper would meet it: the basket, where it ships, then the
 * payment. Express buttons first, then the methods the store switched on (rows with the partner's
 * logo; the selected one opens into its element), saved cards, and a gift card fold. One "Pay" button
 * submits the selected element; express elements submit themselves.
 *
 * Nothing here says "SDK": the ids, the split and the event log live behind the `</>` toggle.
 */
@Composable
fun CheckoutScreen(controller: DemoController) {
    val checkout = controller.checkout ?: return
    val remaining by checkout.remainingAmountToPay.collectAsState()
    val fulfilled by checkout.isPaymentFulfilled.collectAsState()
    val split by checkout.amountSplit.collectAsState()
    LaunchedEffect(split) { controller.syncSelection(split) }

    Box(Modifier.fillMaxSize()) {
        DemoScreen(
            eyebrow = stringResource(R.string.checkout_eyebrow),
            title = DemoStore.NAME,
            onBack = controller::back,
            headerActions = { DeveloperToggle(controller.developerPanel) { controller.developerPanel = !controller.developerPanel } },
            bottomAction = {
                BigButton(
                    text = if (remaining.signum() > 0) {
                        stringResource(R.string.btn_pay, moneyText(remaining, checkout.currency))
                    } else {
                        stringResource(R.string.btn_pay_now)
                    },
                    onClick = controller::submit,
                    enabled = controller.submitEnabled(fulfilled),
                    loading = controller.submitting,
                )
            },
        ) {
            val order = controller.order
            if (order != null) {
                OrderCard(order, checkout, split, remaining)
                if (order.customerName != null || order.address != null) DeliveryCard(order)
            }

            PaymentSection(controller, checkout)

            if (controller.developerPanel) DeveloperPanel(controller, checkout, split, remaining, fulfilled)
        }

        if (controller.submitting) BusyOverlay()
    }
}

/** Express buttons, then the rows the store switched on, saved cards, and the gift card fold. */
@Composable
private fun PaymentSection(controller: DemoController, checkout: PurseHeadlessCheckout) {
    // Items carry their `disabled` state, which moves with the split (a gift card can bring a
    // max-amount method back): observing the flow is what redraws the rows when it does.
    val liveMethods by checkout.paymentMethods.collectAsState()
    SectionTitle(stringResource(R.string.section_payment))

    // Hide an express button only for a real disabled state (amount rules, technical failure): the
    // SUBMITTING lock of a payment in flight must not dispose the buttons' coordinators.
    val context = LocalContext.current
    val express = controller.expressMethods.filter { method ->
        val live = liveMethods.firstOrNull { it.id == method.id } ?: method
        disabledReason(context, live, checkout.currency) == null
    }
    if (express.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            express.forEach { ExpressElement(controller, it) }
        }
        OrDivider()
    }

    val savedTokens by checkout.paymentTokens.collectAsState()
    if (controller.showSaved && savedTokens.isNotEmpty()) {
        Text(
            text = stringResource(R.string.section_saved_cards),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        savedTokens.forEach { SelectableRow(controller, it) }
    }

    val methods = controller.payableMethods
    if (methods.isNotEmpty()) {
        if (controller.showSaved && savedTokens.isNotEmpty()) {
            Text(
                text = stringResource(R.string.section_primary),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        methods.forEach { SelectableRow(controller, it) }
    }
    if (express.isEmpty() && methods.isEmpty() && (!controller.showSaved || savedTokens.isEmpty())) {
        Text(
            text = stringResource(R.string.checkout_no_methods),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val savedGiftCards by checkout.savedSecondaryTokens.collectAsState()
    val giftCards = controller.giftCardMethods
    if (giftCards.isNotEmpty() || (controller.showSaved && savedGiftCards.isNotEmpty())) {
        GiftCardFold(controller, giftCards, if (controller.showSaved) savedGiftCards else emptyList())
    }
}

/** The basket: each line, then subtotal, delivery, any gift card already drawn in, and what's left to pay. */
@Composable
private fun OrderCard(order: OrderSummary, checkout: PurseHeadlessCheckout, split: List<PaymentSplit>, remaining: BigDecimal) {
    val currency = checkout.currency
    DemoCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.section_order), Modifier.weight(1f))
            Text(
                text = stringResource(R.string.order_reference, order.reference),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        order.lines.forEach { line -> BasketLine(line, currency) }
        ThinDivider()
        PriceLine(stringResource(R.string.label_subtotal), moneyText(order.subtotal, currency), muted = true)
        PriceLine(order.delivery, stringResource(R.string.label_free), muted = true)
        val secondaries = split.filter { it.source !is PrimaryMethod && it.source !is PaymentToken }
        secondaries.forEach { entry ->
            PriceLine(stringResource(R.string.label_giftcard_applied), "− " + moneyText(entry.amount, currency), muted = true)
        }
        ThinDivider()
        if (secondaries.isEmpty()) {
            PriceLine(stringResource(R.string.label_total), moneyText(checkout.session.amount, currency), emphasis = true)
        } else {
            PriceLine(stringResource(R.string.label_total), moneyText(checkout.session.amount, currency), muted = true)
            PriceLine(stringResource(R.string.label_to_pay_now), moneyText(remaining, currency), emphasis = true)
        }
    }
}

@Composable
private fun BasketLine(line: OrderSummary.Line, currency: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (line.brand ?: line.name).take(2).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text = line.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = listOfNotNull(line.brand, stringResource(R.string.order_quantity, line.quantity)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = moneyText(line.amount, currency), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DeliveryCard(order: OrderSummary) {
    DemoCard(padding = 16) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.label_deliver_to),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                order.customerName?.let { Text(text = it, style = MaterialTheme.typography.titleSmall) }
                order.address?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
            }
            Text(
                text = order.delivery,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun OrDivider() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { ThinDivider() }
        Text(
            text = stringResource(R.string.label_or_pay_with),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(Modifier.weight(1f)) { ThinDivider() }
    }
}

/** An express method's own button (Google Pay, PayPal): always composed, submits itself. */
@Composable
private fun ExpressElement(controller: DemoController, method: PrimaryMethod) {
    val checkout = controller.checkout ?: return
    val listener = remember(method.id) { controller.listenerFor(method) }
    val element = rememberPaymentElement(checkout, method, listener)
    // Google's PayButton keeps its own intrinsic height (about 50dp of visible button, centred in whatever
    // it is given), while PayPal's button stretches to fill. Size PayPal's to match Google's visible button.
    val height = if (method.method == eu.purse.core.payment.Method.GooglePay) 56.dp else 50.dp
    PaymentElementUi(element, Modifier.fillMaxWidth().height(height))
}

/**
 * One selectable row — a [PrimaryMethod] or a saved [PaymentToken], with its logo. Selecting it
 * commits it as the primary and composes its element; nothing is `/init`'d for a row the shopper
 * hasn't picked. Tapping the open row again collapses it and clears the primary.
 */
@Composable
private fun SelectableRow(controller: DemoController, item: PaymentItem) {
    val checkout = controller.checkout ?: return
    val selected = controller.selectedItem?.id == item.id
    val reason = disabledReason(LocalContext.current, item, checkout.currency)
    val disabled = reason != null
    val contentAlpha = if (disabled) 0.45f else 1f
    DemoCard(border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null, padding = 16) {
        Row(
            Modifier.fillMaxWidth().clickable { controller.select(if (selected) null else item) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = !disabled)
            Spacer(Modifier.width(10.dp))
            MethodLogo(checkout.iconUrl(item), controller.displayName(item), Modifier.alpha(contentAlpha))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = controller.displayName(item),
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalContentColor.current.copy(alpha = contentAlpha),
                )
                (reason ?: subtitle(controller, item))?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (disabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (item is PrimaryMethod && item.method == eu.purse.core.payment.Method.CreditCard) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    checkout.schemeAssets(item).take(3).forEach { asset -> MethodLogo(asset.url, asset.label, height = 18) }
                }
            }
        }
        // The element must stay composed for as long as it is selected: its coordinator runs the
        // payment on the composition's scope, so collapsing it mid-submit cancels the payment.
        AnimatedVisibility(selected) { ElementBody(controller, item) }
    }
}

@Composable
private fun subtitle(controller: DemoController, item: PaymentItem): String? = when {
    item is PaymentToken && item.isVaultedCard -> "${item.maskedPan} · " + stringResource(
        if (item.requiresCvv) R.string.saved_card_cvv_badge else R.string.saved_card_no_cvv_badge,
    )
    item is PaymentToken -> stringResource(R.string.saved_wallet_token_badge)
    item is PrimaryMethod && item.method == eu.purse.core.payment.Method.CreditCard && controller.severalCardMethods ->
        stringResource(R.string.subtitle_card_via, cardProviderName(LocalContext.current, item))
    item is PrimaryMethod && item.method == eu.purse.core.payment.Method.Installments -> stringResource(R.string.subtitle_installments)
    item is PrimaryMethod && controller.isRedirection(item) -> stringResource(R.string.subtitle_redirection)
    else -> null
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

/** "Have a gift card?" — folded by default, as on any store; opens into the saved cards and the form(s). */
@Composable
private fun GiftCardFold(controller: DemoController, methods: List<SecondaryMethod>, saved: List<SavedSecondaryToken>) {
    var expanded by remember { mutableStateOf(saved.isNotEmpty()) }
    DemoCard(padding = 16) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.giftcard_fold),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(text = if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodyLarge)
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                saved.forEach { SavedGiftCardRow(controller, it) }
                methods.forEach { GiftCardForm(controller, it) }
            }
        }
    }
}

/** One saved gift card: balance arrives in the background, one tap draws it in. */
@Composable
private fun SavedGiftCardRow(controller: DemoController, token: SavedSecondaryToken) {
    val applied = controller.isSavedGiftCardApplied(token)
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

/** One gift card: check its balance (`/init`, no split change), then decide what to contribute. */
@Composable
private fun GiftCardForm(controller: DemoController, method: SecondaryMethod) {
    val state = controller.giftCardState(method)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TestGiftCardHint(onCopied = { controller.snackbar = it })
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
        SmallButton(text = stringResource(R.string.btn_check_balance), onClick = {
            controller.checkBalance(method, state)
        }, loading = state.checking)
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
                Row(
                    Modifier.fillMaxWidth().clickable { state.register = !state.register },
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
            SmallButton(text = stringResource(R.string.btn_apply_giftcard), onClick = { controller.applyGiftCard(state) })
        }
        state.appliedLabel?.let { Text(text = it, style = MaterialTheme.typography.titleSmall, color = DemoStatusColors.success) }
        state.error?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
}

/** The sandbox gift card, with its number as a tap-to-copy chip so nobody types 19 digits on stage. */
@Composable
private fun TestGiftCardHint(onCopied: (String) -> Unit) {
    val context = LocalContext.current
    val copied = stringResource(R.string.giftcard_copied)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.helper_giftcard_prefix),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = TEST_GIFT_CARD_PAN,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("gift card", TEST_GIFT_CARD_PAN))
                    onCopied(copied)
                }
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(
            text = stringResource(R.string.helper_giftcard_suffix),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val TEST_GIFT_CARD_PAN = "9250004100006146860"

/** What the SDK sees, for the presenter: the session, the split, the state flags, every callback. */
@Composable
private fun DeveloperPanel(
    controller: DemoController,
    checkout: PurseHeadlessCheckout,
    split: List<PaymentSplit>,
    remaining: BigDecimal,
    fulfilled: Boolean,
) {
    val state by checkout.sessionState.collectAsState()
    DemoCard(color = MaterialTheme.colorScheme.surfaceVariant, padding = 16) {
        SectionTitle(stringResource(R.string.section_developer))
        LabeledValue(stringResource(R.string.dev_session_id), checkout.currentSessionId, mono = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LabeledValue(stringResource(R.string.dev_state), state.toString(), mono = true)
            LabeledValue(stringResource(R.string.dev_fulfilled), fulfilled.toString(), mono = true)
            LabeledValue(stringResource(R.string.dev_remaining), amountText(remaining, checkout.currency), mono = true)
        }
        Text(
            text = stringResource(R.string.dev_split),
            style = MaterialTheme.typography.labelMedium,
            color = LocalContentColor.current.copy(alpha = 0.75f),
        )
        MonoBlock(
            if (split.isEmpty()) "—" else split.joinToString("\n") { "${it.source.id}  →  ${amountText(it.amount, checkout.currency)}" },
        )
        SmallButton(
            text = stringResource(R.string.btn_refresh_session),
            onClick = controller::refreshSession,
            enabled = !controller.refreshingSession,
            loading = controller.refreshingSession,
        )
        Disclosure(title = stringResource(R.string.section_session_details)) { MonoBlock(controller.sessionDetails) }
        EventLog(controller.events)
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
