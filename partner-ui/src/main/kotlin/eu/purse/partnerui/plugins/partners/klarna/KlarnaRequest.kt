package eu.purse.partnerui.plugins.partners.klarna

/**
 * Everything [KlarnaCoordinator] needs to drive the native `KlarnaPaymentView` for one `installments`
 * item, built from its `/init` response ([KlarnaMapper.parseInitConfig] + [KlarnaMapper.extractStepId]).
 *
 * [stepId] is carried through to `/validate` (web's `context.dmz.initialStepId`) — unlike Google Pay's
 * collected token, it's protocol data returned by `/init` rather than something the shopper produced,
 * but it reaches [KlarnaPlugin.preValidation] the same way: stashed via `KlarnaHost.setCollected`.
 */
data class KlarnaRequest(
    val partnerWireValue: String,
    val clientToken: String,
    val stepId: Int?,
)
