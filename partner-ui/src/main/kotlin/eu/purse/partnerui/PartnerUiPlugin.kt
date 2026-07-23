package eu.purse.partnerui

import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.payload.ValidateItemPayload
import eu.purse.core.payload.ValidationResult
import eu.purse.core.vault.VaultVendor

/**
 * The contract every partner (PSP) integration implements — the Kotlin analog of web
 * `partner-ui`'s `Plugin<Context>` (`packages/partner-ui/src/commons/Plugin/types.ts`). One plugin
 * integrates one `(partner, method)` pair (e.g. `paypal/wallet`, `adyen/creditcard`).
 *
 * There are intentionally **no implementations** in this module yet — only the contract and its
 * lifecycle, so `:headless-checkout` can be built against a stable shape. The lifecycle mirrors
 * web's, with `suspend` standing in for its `Promise`-returning hooks:
 *
 * ```
 * init:      onSessionSet → onSourcesLoaded(deferred) → ensureClientCompatibility(optional)
 * react:     onAmountUpdate / onDataUpdate            (as the split or plugin state changes)
 * validate:  validateUi(optional) → preValidation ── host POSTs /validate ──▶ postValidation
 * teardown:  destroyClient
 * ```
 */
interface PartnerUiPlugin {

    val partner: Partner
    val method: Method

    /** Which interfaces this plugin can present — see [PluginInterface]. */
    val interfaces: List<PluginInterface>

    /** Set when this plugin is a card-vaulting plugin (web's vault plugin family); else `null`. */
    val vault: VaultVendor? get() = null

    /** External SDK scripts/assets the host must load before the client is usable. */
    val sources: List<String> get() = emptyList()

    /** A session has been attached (or refreshed) — create/refresh the vendor client. */
    suspend fun onSessionSet(context: PartnerUiContext) {}

    /** The amount this item must cover changed (the splitter moved). */
    suspend fun onAmountUpdate(context: PartnerUiContext) {}

    /** The plugin's own state changed (host-driven) — re-render/react. */
    suspend fun onDataUpdate(context: PartnerUiContext) {}

    /**
     * Build the payload this item contributes to the `/validate` ("create payment") call. Web:
     * `preValidation(context): Promise<ValidateItemPayload>`.
     */
    suspend fun preValidation(context: PartnerUiContext): ValidateItemPayload

    /**
     * Handle the `/validate` outcome for this item — follow a redirection / 3-D Secure, confirm a
     * capture, or surface an error. Web: `postValidation(data, context)`.
     */
    suspend fun postValidation(result: ValidationResult, context: PartnerUiContext) {}

    /** Release the vendor client and any held resources. */
    suspend fun destroyClient(context: PartnerUiContext) {}

    /** Optional client-side check before [preValidation]; return `false` to block submission. */
    suspend fun validateUi(context: PartnerUiContext): Boolean = true

    /**
     * Optional headless eligibility pre-check (web's `ensureClientCompatibility`). Throw to mark
     * the method incompatible with the current client/session.
     */
    suspend fun ensureClientCompatibility(context: PartnerUiContext) {}
}
