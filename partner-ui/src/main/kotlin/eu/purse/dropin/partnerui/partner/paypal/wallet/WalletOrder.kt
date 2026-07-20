package eu.purse.dropin.partnerui.partner.paypal.wallet

/**
 * The result of a PayPal wallet `/init` — the PayPal order the shopper must approve.
 *
 * [orderId] is `plugin_result.logs.order_id` from the init response; the buyer approves this order
 * (in web via the PayPal JS SDK popup, on Android by opening PayPal's approval flow). [stepId] is
 * the init's top-level `step_id`, carried back into the follow-up `/validate` so the backend links
 * the capture to this init — mirrors web's `dmz.initialStepId`.
 */
data class WalletOrder(
    val orderId: String,
    val stepId: String?,
)
