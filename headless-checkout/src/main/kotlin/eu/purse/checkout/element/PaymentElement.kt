// Web: packages/headless-checkout/src/payment-element/payment-element.ts (`getPaymentElement`, `hasUI`, `appendTo`)
// Same: one element per rendered method or saved token; `hasUi` false for redirection and no-CVV tokens.
// Differs: Compose (`rememberPaymentElement` + `PaymentElementUi`) instead of a DOM container; a
//          coordinator per plugin owns the native step; the element is keyed on `itemGenerations`.
package eu.purse.checkout.element

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import eu.purse.checkout.PurseHeadlessCheckout
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.checkout.payment.item.PaymentToken
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayButton
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayPlugin
import eu.purse.partnerui.plugins.generics.redirection.RedirectionPlugin
import eu.purse.partnerui.plugins.partners.klarna.KlarnaPlugin
import eu.purse.partnerui.plugins.partners.paypal.wallet.PaypalButton
import eu.purse.partnerui.plugins.partners.paypal.wallet.PaypalPlugin
import eu.purse.partnerui.plugins.vaults.purse.creditcard.CardPlugin
import eu.purse.partnerui.plugins.vaults.purse.creditcard.PurseCardForm
import eu.purse.partnerui.plugins.vaults.purse.creditcard.rememberCardCoordinator
import eu.purse.partnerui.rememberGooglePayCoordinator
import eu.purse.partnerui.rememberKlarnaCoordinator
import eu.purse.partnerui.rememberPaypalCoordinator
import eu.purse.partnerui.rememberRedirectionCoordinator

/**
 * One rendered payment method or saved token, behind one shape: whatever it needs on screen (a card
 * form, a wallet button, Klarna's widget, a CVV field, or nothing), its readiness, and a [submit].
 * Obtain it with [rememberPaymentElement], place it with [PaymentElementUi].
 */
class PaymentElement internal constructor(
    /** The [PrimaryMethod] this element renders, or the saved [PaymentToken] it pays with. */
    val item: PaymentItem,
    /** Whether there is anything to render — web's `hasUI()`. */
    val hasUi: Boolean,
    /** Google Pay / PayPal render their own button and drive the flow from its tap; [submit] is a no-op. */
    val selfSubmitting: Boolean,
    private val readyState: State<Boolean>,
    private val onSubmit: () -> Unit,
    internal val ui: @Composable (Modifier) -> Unit,
) {
    /** Readiness as Compose state (a card form's validity, `/init` success, …) — gate your Pay button on it. */
    val ready: Boolean get() = readyState.value

    /** Drive this method's own submission and `/validate`. No-op for [selfSubmitting] elements. */
    fun submit() = onSubmit()
}

/** Renders [element]'s UI — a no-op when [PaymentElement.hasUi] is `false`. */
@Composable
fun PaymentElementUi(element: PaymentElement, modifier: Modifier = Modifier) = element.ui(modifier)

/**
 * The [PaymentElement] for [item] — a [PrimaryMethod] or a saved [PaymentToken] — resolving the same
 * plugin `/validate` will use and wiring its coordinator, launchers and `prepare`:
 *
 * ```kotlin
 * val element = rememberPaymentElement(checkout, method, listener)
 * PaymentElementUi(element, Modifier.fillMaxWidth())
 * if (element.hasUi && !element.selfSubmitting) {
 *     Button(onClick = { element.submit() }, enabled = element.ready) { Text("Pay") }
 * }
 * ```
 *
 * Create the element **when the shopper selects the item**, not for every row: creation runs
 * `prepare`, which for Klarna and redirection methods is a `POST /init`. Select the item yourself
 * (`setPrimary` / `setPrimaryToken`); the element does not.
 *
 * What a saved token renders follows its plugin: a card token needing its CVV gets a CVV-only form
 * (and gates fulfilment like a fresh card); a card token behind the no-CVV gates, a saved PayPal
 * account or a redirection-template token render nothing and [PaymentElement.submit] goes straight
 * to `/init` + `/validate`.
 *
 * The element is keyed on the item's `itemGenerations` generation: `setSession` bumps it for a
 * method whose configuration changed, so that element rebuilds while an unchanged one keeps its
 * coordinator and whatever the shopper typed.
 *
 * @param klarnaReturnUrl Klarna's app-return URL; defaults to `"{packageName}.klarna://return"`,
 * which must match the intent-filter your manifest declares.
 */
@Composable
fun rememberPaymentElement(
    checkout: PurseHeadlessCheckout,
    item: PaymentItem,
    listener: PaymentElementListener = NOOP_LISTENER,
    klarnaReturnUrl: String? = null,
): PaymentElement {
    val generations by checkout.itemGenerations.collectAsState()
    val elementKey = remember(item.id, generations[item.id]) { item.id to (generations[item.id] ?: 0) }
    val readyState = remember(elementKey) { mutableStateOf(false) }
    val wrapped = remember(elementKey, listener) {
        object : PaymentElementListener by listener {
            override fun onReady(ready: Boolean) {
                readyState.value = ready
                listener.onReady(ready)
            }
        }
    }
    val plugin = remember(elementKey) { PluginRegistry.getPlugin(item.partner, item.method, checkout.session.source) }
    val isToken = item is PaymentToken

    return when {
        plugin is CardPlugin -> rememberCardElement(checkout, item, elementKey, readyState, wrapped)

        plugin is GooglePayPlugin && !isToken -> {
            val coordinator = rememberGooglePayCoordinator(wrapped)
            LaunchedEffect(coordinator, elementKey) { coordinator.prepare(checkout.host(item)) }
            remember(coordinator) {
                PaymentElement(item, hasUi = true, selfSubmitting = true, readyState, onSubmit = { }) { modifier ->
                    GooglePayButton(coordinator, modifier)
                }
            }
        }

        plugin is PaypalPlugin -> {
            val coordinator = rememberPaypalCoordinator(wrapped)
            LaunchedEffect(coordinator, elementKey) { coordinator.prepare(checkout.host(item)) }
            remember(coordinator) {
                if (isToken) {
                    // No button, no approval: the buyer approved when they vaulted it; submit captures.
                    PaymentElement(item, hasUi = false, selfSubmitting = false, readyState, onSubmit = { coordinator.submit() }) { }
                } else {
                    PaymentElement(item, hasUi = true, selfSubmitting = true, readyState, onSubmit = { }) { modifier ->
                        PaypalButton(coordinator, modifier)
                    }
                }
            }
        }

        plugin is KlarnaPlugin && !isToken -> {
            val coordinator = rememberKlarnaCoordinator(wrapped)
            val returnUrl = klarnaReturnUrl ?: "${LocalContext.current.packageName}.klarna://return"
            val containerAttached: MutableState<Boolean> = remember(elementKey) { mutableStateOf(false) }
            // Klarna inflates into a real ViewGroup — prepare once the AndroidView container exists.
            LaunchedEffect(coordinator, containerAttached.value) {
                if (containerAttached.value) coordinator.prepare(checkout.host(item), returnUrl)
            }
            remember(coordinator) {
                PaymentElement(item, hasUi = true, selfSubmitting = false, readyState, onSubmit = { coordinator.submit() }) { modifier ->
                    AndroidView(
                        modifier = modifier,
                        factory = { context ->
                            FrameLayout(context).also {
                                coordinator.container = it
                                containerAttached.value = true
                            }
                        },
                    )
                }
            }
        }

        plugin is RedirectionPlugin -> {
            val coordinator = rememberRedirectionCoordinator(wrapped)
            LaunchedEffect(coordinator, elementKey) { coordinator.prepare(checkout.host(item)) }
            remember(coordinator) {
                PaymentElement(item, hasUi = false, selfSubmitting = false, readyState, onSubmit = { coordinator.submit() }) { }
            }
        }

        else -> remember(elementKey) {
            PaymentElement(item, hasUi = false, selfSubmitting = false, readyState, onSubmit = {
                wrapped.onError("no plugin registered for ${item.id}")
            }) { }
        }
    }
}

/** A fresh card or a saved one; `CardCoordinator.Mode` (decided by the host's token) picks the form. */
@Composable
private fun rememberCardElement(
    checkout: PurseHeadlessCheckout,
    item: PaymentItem,
    elementKey: Pair<String, Int>,
    readyState: MutableState<Boolean>,
    listener: PaymentElementListener,
): PaymentElement {
    // The host is what rememberCardCoordinator keys its SecureFields instance on: a new generation
    // means a new host, hence a fresh form; the same generation keeps the form and its input.
    val host = remember(elementKey) { checkout.host(item) }
    val coordinator = rememberCardCoordinator(host, listener)
    return remember(coordinator) {
        PaymentElement(item, hasUi = coordinator.mode.rendersForm, selfSubmitting = false, readyState, onSubmit = { coordinator.submit() }) { modifier ->
            if (coordinator.mode.rendersForm) PurseCardForm(coordinator, modifier)
        }
    }
}

private val NOOP_LISTENER = object : PaymentElementListener {}
