package eu.purse.partnerui.plugins.partners.paypal.wallet

import eu.purse.core.payload.ItemPayload
import eu.purse.core.payload.ValidateItemPayload
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PartnerUiPlugin

/**
 * The PayPal **wallet** (pay-now) [PartnerUiPlugin] — the protocol half (pure, no PayPal SDK
 * types). Ports web's `plugins/partners/paypal/wallet`, whose in-page JS-SDK popup is swapped here
 * for a native browser switch.
 *
 * PayPal is the one shipped method whose native step is a full round trip *out of the app and
 * back*, and it sits **before** `/validate`:
 *
 * ```
 * /init ──▶ order_id ──▶ browser switch: buyer approves ──▶ /validate (captures)
 * ```
 *
 * So, like Google Pay and unlike Klarna, there is no [postValidation] — by the time `/validate`
 * runs the buyer has already approved, and [preValidation] just carries the `step_id` that links
 * the capture back to the init. The interactive half (PayPal's `PayPalWebCheckoutClient`, the
 * approval Activity, the chained-3DS redirect) lives in [PaypalCoordinator]; the `step_id` reaches
 * this plugin through `PartnerUiContext.collected`, the same seam Google Pay uses for its sheet
 * token.
 *
 * **A saved PayPal token** (`PartnerUiContext.token`, one of the method's `integrated_tokens[]`)
 * skips the browser switch entirely — the buyer approved when they vaulted it. Web's paypal plugin
 * with a `token` attribute renders nothing (`getTemplateForUI` → empty, headless `hasUI()` →
 * `false`), `/init`s on session set and, in `preValidation`, patches the item with the token
 * (`patchItemPayloadWithToken`). Here [preValidation] does the `/init` itself, right before
 * building the item, the way the card plugin does for a card-on-file: one call, and a host that
 * calls `submitPayment()` directly (no coordinator, no element) still sends a complete item. No
 * CVV is ever involved — a CVV is a card-form concept, and there is no card form behind PayPal.
 */
class PaypalPlugin(override val partner: Partner) : PartnerUiPlugin {

    override val method: Method = Method.Wallet

    /**
     * `POST /init` → the PayPal order the buyer must approve, plus the step id `/validate` carries
     * back. Suspend/blocking — call off the main thread.
     */
    suspend fun buildRequest(context: PartnerUiContext): PaypalRequest {
        val initResponse = context.api.init(
            PaypalMapper.buildInitPayload(context.amount, partner.wireValue),
        )
        return PaypalRequest(
            orderId = PaypalMapper.parseInit(initResponse),
            stepId = PaypalMapper.extractStepId(initResponse),
        )
    }

    /**
     * The `/validate` item — the envelope plus the `/init` `step_id`. Nothing the buyer produced is
     * sent: PayPal's approval is recorded against the order on PayPal's side, and the backend
     * re-reads it from the `step_id`, so the `orderId`/`payerId` the SDK hands back are kept for
     * diagnostics only (see [COLLECTED_ORDER_ID]).
     *
     * With a saved token ([PartnerUiContext.token]) there was no approval step to collect from:
     * this `POST /init`s now (same body as the pay-now init — web's token-mode `initSession`),
     * takes the `step_id` off the answer, and sends `payment_data.token` (web's
     * `patchItemPayloadWithToken`; no `card` block since a PayPal token has no expiry or scheme).
     * Suspend/blocking in that case — the validator already runs it off the main thread.
     */
    override suspend fun preValidation(context: PartnerUiContext): ValidateItemPayload {
        val token = context.token
            ?: return ItemPayload(
                partner = partner,
                method = method,
                amount = context.amount,
                stepId = context.collected[COLLECTED_STEP_ID] as? Int,
            )

        val initResponse = context.api.init(PaypalMapper.buildInitPayload(context.amount, partner.wireValue))
        PaypalMapper.ensureInitSucceeded(initResponse)
        return ItemPayload(
            partner = partner,
            method = method,
            amount = context.amount,
            stepId = PaypalMapper.extractStepId(initResponse),
            paymentData = token.patchPaymentData(),
        )
    }

    companion object {
        /** Key [PaypalCoordinator] uses in `setCollected` for the `/init` step id. */
        const val COLLECTED_STEP_ID = "paypalStepId"

        /**
         * Key [PaypalCoordinator] uses in `setCollected` for the approved PayPal order id.
         * Diagnostic only — not sent to `/validate` (see [preValidation]).
         */
        const val COLLECTED_ORDER_ID = "paypalOrderId"

        /** Key [PaypalCoordinator] uses in `setCollected` for PayPal's payer id. Diagnostic only. */
        const val COLLECTED_PAYER_ID = "paypalPayerId"
    }
}
