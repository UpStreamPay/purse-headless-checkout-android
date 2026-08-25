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
import eu.purse.securefields.VaultForm

/**
 * One rendered payment method — the Kotlin analog of web's `getPaymentElement`: whatever a method
 * needs on screen (a card form, a wallet button, Klarna's widget, nothing at all for a redirection
 * method), behind one shape. Obtain it with [rememberPaymentElement], place it with
 * [PaymentElementUi], and submit form-driven methods with [submit].
 */
class PaymentElement internal constructor(
    val method: PrimaryMethod,
    /**
     * Whether this method has anything to render — web's `hasUI()`. `false` for
     * redirection-template methods, whose hosted page *is* the payment step.
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
        is CardPlugin -> {
            val host = remember(method.id) { checkout.cardHost(method) }
            val coordinator = rememberCardCoordinator(host, wrapped)
            remember(coordinator) {
                PaymentElement(
                    method, hasUi = true, selfSubmitting = false, readyState,
                    onSubmit = { coordinator.submit() },
                    ui = { modifier -> VaultForm(coordinator.sdk, modifier) },
                )
            }
        }

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

private val NOOP_LISTENER = object : PaymentElementListener {}
