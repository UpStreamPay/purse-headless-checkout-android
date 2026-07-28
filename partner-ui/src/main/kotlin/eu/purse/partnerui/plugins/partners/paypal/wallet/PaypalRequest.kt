package eu.purse.partnerui.plugins.partners.paypal.wallet

/**
 * Everything [PaypalCoordinator] needs to run the buyer-approval browser switch for one `wallet`
 * item, built from its `/init` response ([PaypalMapper.parseInit] + [PaypalMapper.extractStepId]).
 *
 * [orderId] is the PayPal order the buyer approves — PayPal's own id, opaque to this SDK. [stepId]
 * is carried through to `/validate` (web's `context.dmz.initialStepId`) so the backend links the
 * capture to that init; it reaches [PaypalPlugin.preValidation] stashed via
 * [PaypalHost.setCollected], exactly as Klarna's does.
 */
data class PaypalRequest(
    val orderId: String,
    val stepId: Int?,
)
