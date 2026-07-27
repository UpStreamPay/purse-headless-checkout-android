package eu.purse.partnerui.plugins.partners.klarna

import eu.purse.core.payload.ItemPayload
import eu.purse.core.payload.ValidateItemPayload
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PartnerUiPlugin

/**
 * The Klarna [PartnerUiPlugin] — the protocol half (pure, no Klarna SDK types). It builds the
 * `KlarnaPaymentView` request from `/init` ([buildRequest], driven by [KlarnaCoordinator] before the
 * widget renders) and, once the split is fulfilled, builds the (empty-bodied, `step_id`-only)
 * `/validate` payload ([preValidation]) — exactly web's `klarna_installments.preValidation`, which
 * defers the actual Klarna `authorize()` call to *after* `/validate` succeeds (see
 * [KlarnaCoordinator]'s doc for why that ordering matters).
 *
 * The interactive half (the live `KlarnaPaymentView`, its `authorize`/`finalize` calls) lives in
 * [KlarnaCoordinator]. The `step_id` `/init` returns reaches [preValidation] through
 * `PartnerUiContext.collected`, populated by the SDK from what the coordinator handed back via
 * `PurseHeadlessCheckout.setCollected` — the same seam Google Pay uses for its sheet token.
 */
class KlarnaPlugin(override val partner: Partner) : PartnerUiPlugin {

    override val method: Method = Method.Installments

    /** `POST /init` → client token + step id — everything needed to initialize the native widget. */
    suspend fun buildRequest(context: PartnerUiContext): KlarnaRequest {
        val initResponse = context.api.init(KlarnaMapper.buildInitPayload(context.amount, partner.wireValue))
        val config = KlarnaMapper.parseInitConfig(initResponse)
        return KlarnaRequest(
            partnerWireValue = partner.wireValue,
            clientToken = config.clientToken,
            stepId = KlarnaMapper.extractStepId(initResponse),
        )
    }

    override suspend fun preValidation(context: PartnerUiContext): ValidateItemPayload = ItemPayload(
        partner = partner,
        method = method,
        amount = context.amount,
        stepId = context.collected[COLLECTED_STEP_ID] as? Int,
    )

    companion object {
        /** Key the coordinator uses in `PurseHeadlessCheckout.setCollected` for the `/init` step id. */
        const val COLLECTED_STEP_ID = "klarnaStepId"
    }
}
