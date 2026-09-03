package eu.purse.partnerui.plugins.generics.redirection

import eu.purse.core.payload.ItemPayload
import eu.purse.core.payload.ValidateItemPayload
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PartnerUiPlugin
import org.json.JSONObject

/**
 * The **generic redirection** [PartnerUiPlugin] — the protocol half (pure JSON, no Android types).
 * Ports web's `plugins/generics/redirection`
 * (`usp-widget/packages/partner-ui/src/plugins/generics/redirection/index.ts`).
 *
 * This is the first *generic template* plugin in this SDK, and it behaves differently from the three
 * that came before it: [GooglePayPlugin][eu.purse.partnerui.plugins.generics.googlepay.GooglePayPlugin]
 * and [KlarnaPlugin][eu.purse.partnerui.plugins.partners.klarna.KlarnaPlugin] each hardcode their one
 * [method], because each integrates one PSP product. This one hardcodes **neither** [partner] nor
 * [method] — it serves whichever `(partner, method)` pair the session's own
 * `protocols.{partner}.{method}.configurations.template` labels `"redirection"`, which is a set the
 * SDK cannot know ahead of time (web's list spans Fintecture, Bizum, Oney, Sequra, ACI, PayPal in
 * `redirection` integration mode, …). See [PluginRegistry][eu.purse.partnerui.PluginRegistry] for how
 * that resolution works and [RedirectionModule] for the registration.
 *
 * The whole flow is: `/init` (for a `step_id`) → `/validate` → **follow the redirect the response
 * carries**. There is no shopper-facing form and no vendor SDK — the PSP's own hosted page *is* the
 * payment step. So the plugin body is genuinely this small, and the two interesting pieces live
 * elsewhere:
 *
 * - [RedirectionCoordinator] runs it and follows the redirect, through the shared
 *   [RedirectionResolver][eu.purse.partnerui.redirect.RedirectionResolver] /
 *   [RedirectionCheckoutContract][eu.purse.partnerui.redirect.RedirectionCheckoutContract] pair;
 * - [RedirectionMapper] builds the payloads, including the `partner_data.browser` block a 3-D Secure
 *   step needs.
 *
 * ### Why `postValidation` is not where the redirect is followed
 *
 * Web's redirection plugin *does* follow it there — its `postValidation` calls `manageRedirection`,
 * because in a browser "follow a redirect" is `window.location = url` and needs nothing but the URL.
 * Headless on Android it needs an `ActivityResultLauncher` registered before the host Activity
 * starts, and a suspending round-trip to another Activity — Android lifecycle plumbing this module
 * deliberately keeps out of the pure protocol half. So the resolution happens in
 * [RedirectionCoordinator.submit] the moment `submitPayment()` returns, off exactly the same
 * `/validate` body and priority order web's `manageRedirection` uses. Google Pay, Klarna and the card
 * plugin all already work this way; this plugin just makes it the *only* step.
 */
class RedirectionPlugin(
    override val partner: Partner,
    override val method: Method,
) : PartnerUiPlugin {

    /**
     * `POST /init` → the `step_id` the `/validate` payload must carry back. Mirrors web's
     * `onSessionSet`, which POSTs the same body and stashes `initData.step_id` as
     * `context.dmz.initialStepId`. Suspend/blocking — call off the main thread.
     *
     * [browser] is the `partner_data.browser` block from [RedirectionMapper.browserData]; pass `null`
     * where no [android.content.Context] is available (the payload is still valid without it).
     *
     * Web additionally runs an optional per-partner `fingerprintingScript` here — its only entry is
     * PayPal's Fraudnet, which is a browser-`<script>` mechanism with no native equivalent and whose
     * plugin was dropped in this SDK's headless pivot. Nothing else in web's
     * `RedirectionCustomSettings` applies, so the hook is deliberately absent rather than stubbed.
     */
    suspend fun buildRequest(context: PartnerUiContext, browser: JSONObject? = null): RedirectionRequest {
        val initResponse = context.api.init(
            RedirectionMapper.buildInitPayload(
                amount = context.amount,
                partnerWireValue = partner.wireValue,
                methodWireValue = method.wireValue,
                browser = browser,
            ),
        )
        RedirectionMapper.ensureInitSucceeded(initResponse)
        return RedirectionRequest(
            partnerWireValue = partner.wireValue,
            methodWireValue = method.wireValue,
            stepId = RedirectionMapper.extractStepId(initResponse),
        )
    }

    /**
     * The `/validate` item — the envelope, the `/init` `step_id`, and the browser block, exactly as
     * web's `preValidation` builds it, then patched with a saved token's value, holder, expiry and
     * network (web's `patchItemPayloadWithToken`) when the item replays a card-on-file.
     */
    override suspend fun preValidation(context: PartnerUiContext): ValidateItemPayload {
        val browser = context.collected[COLLECTED_BROWSER] as? JSONObject
        val paymentData = JSONObject()
        context.token?.let { token ->
            paymentData.put("token", token.value)
            token.holderName?.let { paymentData.put("holder", it) }
            val card = JSONObject()
            token.wireExpiryDate()?.let { card.put("expiry_date", it) }
            token.selectedNetwork?.let { card.put("selected_network", it) }
            if (card.length() > 0) paymentData.put("card", card)
        }

        return ItemPayload(
            partner = partner,
            method = method,
            amount = context.amount,
            stepId = context.collected[COLLECTED_STEP_ID] as? Int,
            paymentData = paymentData,
            partnerData = RedirectionMapper.partnerDataFor(browser),
        )
    }

    companion object {
        /**
         * The `configurations.template` value this plugin is registered under — web's
         * `GenericTypes.redirection`. Matched **strictly equal**, exactly as web's
         * `getGenericFromTemplateName` does: a `redirection_suffix` or `prefix_redirection` template
         * resolves to no generic at all (web has a test asserting precisely that).
         */
        const val TEMPLATE = "redirection"

        /** Key [RedirectionCoordinator] uses in `setCollected` for the `/init` step id. */
        const val COLLECTED_STEP_ID = "redirectionStepId"

        /** Key [RedirectionCoordinator] uses in `setCollected` for [RedirectionMapper.browserData]. */
        const val COLLECTED_BROWSER = "redirectionBrowser"
    }
}

/** What `/init` handed back for a redirection item — everything [RedirectionCoordinator] needs. */
data class RedirectionRequest(
    val partnerWireValue: String,
    val methodWireValue: String,
    /** `null` when `/init` returned no `step_id`; the `/validate` payload then simply omits it. */
    val stepId: Int?,
)
