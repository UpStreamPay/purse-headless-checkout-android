// Web: packages/partner-ui/src/commons/Plugin/types.ts (`Plugin<Context>`)
// Same: one plugin per (partner, method); the lifecycle hooks and their order.
// Differs: `suspend` instead of `Promise`; no `sources` (no scripts to load) and no `onDataUpdate`
//          (the rendering surface is a coordinator, not a re-rendered template).
package eu.purse.partnerui

import eu.purse.core.payload.ValidateItemPayload
import eu.purse.core.payload.ValidationResult
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner

/**
 * The protocol half of a payment method: what goes to `/init` and `/validate`, and what to do with
 * the answer. The interactive half (a sheet, a browser switch, a native widget, a card form) lives
 * in the method's coordinator and talks to the checkout through its host seam.
 *
 * ```
 * init:      onSessionSet → ensureClientCompatibility(optional)
 * react:     onAmountUpdate
 * validate:  validateUi(optional) → preValidation ── host POSTs /validate ──▶ postValidation
 * teardown:  destroyClient
 * ```
 */
interface PartnerUiPlugin {

    val partner: Partner
    val method: Method

    /** A session has been attached (or refreshed) — create/refresh the vendor client. */
    suspend fun onSessionSet(context: PartnerUiContext) {}

    /** The amount this item must cover changed (the splitter moved). */
    suspend fun onAmountUpdate(context: PartnerUiContext) {}

    /** The payload this item contributes to `/validate`. */
    suspend fun preValidation(context: PartnerUiContext): ValidateItemPayload

    /** The `/validate` outcome for this item — follow a redirect, confirm, or surface an error. */
    suspend fun postValidation(result: ValidationResult, context: PartnerUiContext) {}

    /** Release the vendor client and any held resources. */
    suspend fun destroyClient(context: PartnerUiContext) {}

    /** Client-side check before [preValidation]; `false` blocks the submit. */
    suspend fun validateUi(context: PartnerUiContext): Boolean = true

    /** Eligibility pre-check at session build; throw to mark the method incompatible. */
    suspend fun ensureClientCompatibility(context: PartnerUiContext) {}
}
