// Web: packages/headless-checkout/src/payment-element/payment-element.ts (`getPaymentElement`, `hasUI`, `appendTo`)
// Same: one element per rendered method or saved token; `hasUi` false for redirection and no-CVV tokens.
// Differs: Compose (`rememberPaymentElement` + `PaymentElementUi`) instead of a DOM container; the
//          plugin's own `PartnerModule` (built-in or from an optional partner artifact) renders the
//          native step; the element is keyed on `itemGenerations`; a technically disabled item gets
//          an element with no UI where web's `appendTo` throws.
package eu.purse.checkout.element

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.purse.checkout.PurseHeadlessCheckout
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.checkout.payment.item.PaymentToken
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.partnerui.PartnerArtifacts
import eu.purse.partnerui.PartnerModule
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.SurfaceRequest

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
 * (`setPrimary` / `setPrimaryToken`); the element does not. An item whose `disabled` state is
 * technically disabled (`TOKEN_INIT_FAILED`, `PARTNER_UI_FATAL_ERROR`) gets an element with no UI
 * whose [PaymentElement.submit] reports the reason on `onError` — web's `appendTo` throws there.
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
 * A method whose plugin comes from an optional partner artifact (`headless-checkout-googlepay`,
 * `-klarna`, `-paypal`) renders only when that artifact is on the classpath; otherwise the element
 * has no UI and [PaymentElement.submit] reports it on `onError`.
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
    val module: PartnerModule? = remember(plugin) { plugin?.let(PluginRegistry::moduleFor) }
    val disabled = item.disabled

    return when {
        // A hard technical failure mounts nothing — web's `appendTo` throws for these states.
        disabled != null && disabled.isTechnicallyDisabled -> remember(elementKey, disabled) {
            PaymentElement(item, hasUi = false, selfSubmitting = false, readyState, onSubmit = {
                wrapped.onError("${item.id} is disabled: ${disabled.message}")
            }) { }
        }

        plugin != null && module != null -> key(elementKey) {
            // One host per element: what a coordinator keys its vendor client on (the card form's
            // SecureFields instance, for one), so a new generation means a fresh host.
            val host = remember(elementKey) { checkout.host(item) }
            val request = remember(host, wrapped) {
                SurfaceRequest(host, isToken = item is PaymentToken, key = elementKey, listener = wrapped, appReturnUrl = klarnaReturnUrl)
            }
            val surface = module.rememberSurface(plugin, request)
            remember(surface) {
                PaymentElement(item, surface.hasUi, surface.selfSubmitting, readyState, onSubmit = surface::submit, ui = surface.ui)
            }
        }

        else -> remember(elementKey) {
            PaymentElement(item, hasUi = false, selfSubmitting = false, readyState, onSubmit = {
                wrapped.onError(
                    if (plugin == null) {
                        PartnerArtifacts.missingPluginMessage(item.partner, item.method)
                    } else {
                        "no installed PartnerModule renders ${item.id}"
                    },
                )
            }) { }
        }
    }
}

private val NOOP_LISTENER = object : PaymentElementListener {}
