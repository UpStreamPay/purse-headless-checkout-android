// Web: packages/headless-checkout payment-element `on(...)` streams
// Same: every plugin event, tagged with its item.
// Differs: one flattened `SharedFlow` on the checkout.
package eu.purse.checkout

import eu.purse.partnerui.PluginEvent

/**
 * A [PluginEvent] as re-exposed on [PurseHeadlessCheckout.pluginEvents], tagged with the id of the
 * [eu.purse.checkout.payment.item.PaymentItem] that emitted it — the merchant-facing analog of
 * web's `element.on(...)` event stream, flattened onto one flow.
 */
data class CheckoutPluginEvent(
    val itemId: String,
    val event: PluginEvent,
)
