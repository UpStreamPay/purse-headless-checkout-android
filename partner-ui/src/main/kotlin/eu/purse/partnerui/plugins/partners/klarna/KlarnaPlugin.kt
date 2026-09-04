package eu.purse.partnerui.plugins.partners.klarna

import eu.purse.core.payload.ItemPayload
import eu.purse.core.payload.ValidateItemPayload
import eu.purse.core.payload.ValidationResult
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PartnerUiPlugin

/**
 * `true` if Klarna approved the authorization; `false` if the shopper declined. Threaded from
 * [KlarnaCoordinator] into [KlarnaPlugin.postValidation] via
 * `PartnerUiContext.collected[KlarnaPlugin.COLLECTED_AUTHORIZE]`, since this plugin has no Klarna
 * SDK types of its own — see the class doc.
 */
typealias KlarnaAuthorize = suspend () -> Boolean

/** Thrown by [KlarnaPlugin.postValidation] when Klarna's own authorization step is declined —
 * distinct from a genuine error so [KlarnaCoordinator] can report it as a cancellation. */
class KlarnaAuthorizationDeclined : Exception("Klarna declined to authorize")

/**
 * The Klarna [PartnerUiPlugin] — the protocol half (pure, no Klarna SDK types). It builds the
 * `KlarnaPaymentView` request from `/init` ([buildRequest], driven by [KlarnaCoordinator] before the
 * widget renders); builds the (empty-bodied, `step_id`-only) `/validate` payload ([preValidation]);
 * and, once `/validate` succeeds, calls Klarna's own `authorize()` ([postValidation]) — exactly web's
 * `klarna_installments` plugin, whose `preValidation` only carries `step_id` and whose
 * `postValidation` is what calls `client.authorize()`, confirmed against both the plugin itself and
 * the orchestrator that calls it (`headless-checkout-validation.ts`'s `preValidateItems` → `validate`
 * → `postValidateItems`, in that order). This is *why* a single `submitPayment()` call is enough to
 * both submit and open Klarna's own payment step — no separate coordinator-driven call needed after
 * the fact, unlike this plugin's own earlier port.
 *
 * The interactive half (the live `KlarnaPaymentView`, its `authorize`/`finalize` calls) lives in
 * [KlarnaCoordinator]. Both the `step_id` `/init` returns and the live `authorize` callback reach
 * this plugin through `PartnerUiContext.collected`, populated by the SDK from what the coordinator
 * handed back via `PurseHeadlessCheckout.setCollected` — the same seam Google Pay uses for its sheet
 * token.
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

    /**
     * Runs only once `/validate` itself succeeded (mirrors web's `executePayment`, which only calls
     * `postValidateItems` when `global_status === SUCCESS`). Invokes the live `authorize()` callback
     * [KlarnaCoordinator] stashed at [COLLECTED_AUTHORIZE] once the widget was ready.
     *
     * @throws KlarnaAuthorizationDeclined if the shopper declined.
     * @throws IllegalStateException if no widget was ever prepared (no callback to call).
     */
    @Suppress("UNCHECKED_CAST") // KlarnaAuthorize is a suspend function type — erased at runtime,
    // same as every other `context.collected[...] as? T` cast this plugin family already relies on.
    override suspend fun postValidation(result: ValidationResult, context: PartnerUiContext) {
        if (!result.isSuccess) return
        val authorize = context.collected[COLLECTED_AUTHORIZE] as? KlarnaAuthorize
            ?: error("Klarna authorize callback missing — was the widget ever prepared?")
        if (!authorize()) throw KlarnaAuthorizationDeclined()
    }

    companion object {
        /** Key the coordinator uses in `PurseHeadlessCheckout.setCollected` for the `/init` step id. */
        const val COLLECTED_STEP_ID = "klarnaStepId"

        /** Key the coordinator uses in `PurseHeadlessCheckout.setCollected` for the live
         * [KlarnaAuthorize] callback — see [postValidation]. */
        const val COLLECTED_AUTHORIZE = "klarnaAuthorize"
    }
}

/** `/init` `plugin_result.logs` for Klarna, trimmed to what `KlarnaPaymentView.initialize` needs (web's `getKlarnaConfigFromInitData`). */
data class KlarnaConfig(val clientToken: String)

/** What the native widget needs from `/init`: the client token, and the `step_id` `/validate` carries back. */
data class KlarnaRequest(
    val partnerWireValue: String,
    val clientToken: String,
    val stepId: Int?,
)
