package eu.purse.checkout.element

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
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
import eu.purse.partnerui.plugins.vaults.purse.creditcard.rememberCardCoordinator
import eu.purse.partnerui.rememberGooglePayCoordinator
import eu.purse.partnerui.rememberKlarnaCoordinator
import eu.purse.partnerui.rememberPaypalCoordinator
import eu.purse.partnerui.rememberRedirectionCoordinator
import eu.purse.partnerui.plugins.vaults.purse.creditcard.PurseCardForm

/**
 * One rendered payment method — the Kotlin analog of web's `getPaymentElement`: whatever a method
 * needs on screen (a card form, a wallet button, Klarna's widget, a CVV field for a saved card,
 * nothing at all for a redirection method or a saved card that pays without a CVV), behind one
 * shape. Obtain it with [rememberPaymentElement], place it with [PaymentElementUi], and submit
 * form-driven methods with [submit].
 */
class PaymentElement internal constructor(
    /** The [PrimaryMethod] this element renders, or the saved [PaymentToken] it pays with. */
    val item: PaymentItem,
    /**
     * Whether this element has anything to render — web's `hasUI()`. `false` for
     * redirection-template methods, whose hosted page *is* the payment step, and for a saved card
     * behind the no-CVV gates (`PaymentToken.paysWithoutCvv`).
     */
    val hasUi: Boolean,
    /**
     * Google Pay / PayPal render their own branded button and drive the whole flow from its tap —
     * hide your own Pay affordance for these, and never call [submit] (it's a no-op).
     */
    val selfSubmitting: Boolean,
    private val readyState: State<Boolean>,
    private val onSubmit: () -> Unit,
    internal val ui: @Composable (Modifier) -> Unit,
) {
    /**
     * The method's readiness (a card form's validity, `/init` success, …) as Compose state — read
     * it in a composable to gate your Pay button. Mirrors the listener's `onReady`.
     */
    val ready: Boolean get() = readyState.value

    /**
     * Drive this method's own submission (tokenize / widget-authorize / follow the PSP page) and
     * `/validate`. No-op for [selfSubmitting] elements — their button does it.
     */
    fun submit() = onSubmit()
}

/** Renders [element]'s UI — a no-op for a method with [PaymentElement.hasUi] `false`. */
@Composable
fun PaymentElementUi(element: PaymentElement, modifier: Modifier = Modifier) = element.ui(modifier)

/**
 * Build the [PaymentElement] for [method] — resolving the same plugin `/validate` will use and
 * wiring its rendered surface, launchers and lifecycle (`prepare`) automatically:
 *
 * ```kotlin
 * val element = rememberPaymentElement(checkout, method, listener)
 * PaymentElementUi(element, Modifier.fillMaxWidth())
 * if (element.hasUi && !element.selfSubmitting) {
 *     Button(onClick = { element.submit() }, enabled = element.ready) { Text("Pay") }
 * }
 * ```
 *
 * Create the element **when the shopper selects the method** (not for every row of the list):
 * creation runs the method's `prepare` — for Klarna and redirection methods that's a `POST /init`,
 * which costs a network round-trip and a session step.
 *
 * @param listener outcome callbacks — one contract for every method (see [PaymentElementListener]).
 * @param klarnaReturnUrl Klarna's app-return URL; defaults to `"{packageName}.klarna://return"`,
 * which must match the intent-filter your manifest declares for Klarna.
 */
@Composable
fun rememberPaymentElement(
    checkout: PurseHeadlessCheckout,
    method: PrimaryMethod,
    listener: PaymentElementListener = NOOP_LISTENER,
    klarnaReturnUrl: String? = null,
): PaymentElement {
    val readyState = remember(method.id) { mutableStateOf(false) }
    val wrapped = remember(method.id, listener) {
        object : PaymentElementListener by listener {
            override fun onReady(ready: Boolean) {
                readyState.value = ready
                listener.onReady(ready)
            }
        }
    }
    val plugin = remember(method.id) {
        PluginRegistry.getPlugin(method.partner, method.method, checkout.session.source)
    }

    return when (plugin) {
        is CardPlugin -> rememberCardElement(checkout, method, readyState, wrapped)

        is GooglePayPlugin -> {
            val coordinator = rememberGooglePayCoordinator(wrapped)
            LaunchedEffect(coordinator, method.id) { coordinator.prepare(checkout.googlePayHost(method)) }
            remember(coordinator) {
                PaymentElement(
                    method, hasUi = true, selfSubmitting = true, readyState,
                    onSubmit = { },
                    ui = { modifier -> GooglePayButton(coordinator, modifier) },
                )
            }
        }

        is PaypalPlugin -> {
            val coordinator = rememberPaypalCoordinator(wrapped)
            LaunchedEffect(coordinator, method.id) { coordinator.prepare(checkout.paypalHost(method)) }
            remember(coordinator) {
                PaymentElement(
                    method, hasUi = true, selfSubmitting = true, readyState,
                    onSubmit = { },
                    ui = { modifier -> PaypalButton(coordinator, modifier) },
                )
            }
        }

        is KlarnaPlugin -> {
            val coordinator = rememberKlarnaCoordinator(wrapped)
            val packageName = LocalContext.current.packageName
            val returnUrl = klarnaReturnUrl ?: "$packageName.klarna://return"
            val containerAttached: MutableState<Boolean> = remember(method.id) { mutableStateOf(false) }
            // Klarna inflates its widget into a real ViewGroup — prepare only once the
            // AndroidView-provided container exists (i.e. once the element's UI is placed).
            LaunchedEffect(coordinator, containerAttached.value) {
                if (containerAttached.value) coordinator.prepare(checkout.klarnaHost(method, returnUrl))
            }
            remember(coordinator) {
                PaymentElement(
                    method, hasUi = true, selfSubmitting = false, readyState,
                    onSubmit = { coordinator.submit() },
                    ui = { modifier ->
                        AndroidView(
                            modifier = modifier,
                            factory = { context ->
                                FrameLayout(context).also {
                                    coordinator.container = it
                                    containerAttached.value = true
                                }
                            },
                        )
                    },
                )
            }
        }

        is RedirectionPlugin -> {
            val coordinator = rememberRedirectionCoordinator(wrapped)
            LaunchedEffect(coordinator, method.id) { coordinator.prepare(checkout.redirectionHost(method)) }
            remember(coordinator) {
                PaymentElement(
                    method, hasUi = false, selfSubmitting = false, readyState,
                    onSubmit = { coordinator.submit() },
                    ui = { },
                )
            }
        }

        else -> remember(method.id) {
            PaymentElement(
                method, hasUi = false, selfSubmitting = false, readyState,
                onSubmit = { wrapped.onError("no plugin registered for ${method.id}") },
                ui = { },
            )
        }
    }
}

/**
 * Build the [PaymentElement] that pays with a saved card [token] — web's `getPaymentElement` on a
 * `PurseHeadlessCheckoutPrimaryToken`. What it renders follows the token's CVV requirement:
 *
 * - `token.requiresCvv` → a **CVV-only** card form ([PaymentElement.hasUi] `true`); the payment is
 *   not fulfilled until it validates, exactly like a fresh card form;
 * - otherwise → **nothing** ([PaymentElement.hasUi] `false`), and [PaymentElement.submit] goes
 *   straight to `/init`+`/validate`.
 *
 * Select the token with [PurseHeadlessCheckout.setPrimaryToken] as you would call `setPrimary`
 * for a method; the element does not select it for you.
 *
 * ```kotlin
 * val element = rememberPaymentElement(checkout, token, listener)
 * if (element.hasUi) PaymentElementUi(element, Modifier.fillMaxWidth().height(56.dp))
 * Button(onClick = { element.submit() }, enabled = element.ready) { Text("Pay") }
 * ```
 */
@Composable
fun rememberPaymentElement(
    checkout: PurseHeadlessCheckout,
    token: PaymentToken,
    listener: PaymentElementListener = NOOP_LISTENER,
): PaymentElement {
    val readyState = remember(token.id) { mutableStateOf(false) }
    val wrapped = remember(token.id, listener) {
        object : PaymentElementListener by listener {
            override fun onReady(ready: Boolean) {
                readyState.value = ready
                listener.onReady(ready)
            }
        }
    }
    val plugin = remember(token.id) {
        PluginRegistry.getPlugin(token.partner, token.method, checkout.session.source)
    }
    return when (plugin) {
        is CardPlugin -> rememberCardElement(checkout, token, readyState, wrapped)
        else -> remember(token.id) {
            PaymentElement(
                token, hasUi = false, selfSubmitting = false, readyState,
                onSubmit = { wrapped.onError("no card plugin registered for saved token ${token.id}") },
                ui = { },
            )
        }
    }
}

/**
 * The card element behind both overloads — a fresh card ([PrimaryMethod]) or a saved one
 * ([PaymentToken]); `CardCoordinator.Mode` (decided by the host's token) picks the full form, the
 * CVV-only form, or no form.
 */
@Composable
private fun rememberCardElement(
    checkout: PurseHeadlessCheckout,
    item: PaymentItem,
    readyState: MutableState<Boolean>,
    listener: PaymentElementListener,
): PaymentElement {
    val host = remember(item.id) { checkout.cardHost(item) }
    val coordinator = rememberCardCoordinator(host, listener)
    return remember(coordinator) {
        PaymentElement(
            item, hasUi = coordinator.mode.rendersForm, selfSubmitting = false, readyState,
            onSubmit = { coordinator.submit() },
            ui = { modifier -> if (coordinator.mode.rendersForm) PurseCardForm(coordinator, modifier) },
        )
    }
}

private val NOOP_LISTENER = object : PaymentElementListener {}
