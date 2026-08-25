package eu.purse.partnerui

/**
 * Lifecycle signals a plugin (or its coordinator) emits back to its host — the Kotlin analog of web
 * `partner-ui`'s `UnifiedEvent` catalogue (`packages/partner-ui/src/events.ts`), trimmed to the
 * codes that have an Android behaviour. A plugin fires these through
 * [PartnerUiContext.dispatchEvent]; the host (`:headless-checkout`) reacts the way web's
 * `item-factory/utils.ts` wiring does — deactivate the method on fatal events, clear a recoverable
 * state on ready, gate fulfillment, auto-submit on request-validate, terminate the session on
 * end-of-session — and re-exposes every event on its public stream for the merchant.
 */
sealed class PluginEvent {

    /** The plugin's client is up and usable — clears a recoverable disabled state (web `ready`). */
    data object Ready : PluginEvent()

    /**
     * The plugin's own interactive form became complete-and-valid (or stopped being) — web's
     * `fulfilled` / `formDataChanged`. Gates `isPaymentFulfilled` for the item.
     */
    data class Fulfilled(val isFulfilled: Boolean) : PluginEvent()

    /**
     * The plugin wants the payment submitted now (an xpay-style sheet was approved) — web's
     * `requestValidate`. [skipOnBeforeValidate] mirrors web's flag for flows the merchant hook
     * already blessed.
     */
    data class RequestValidate(val skipOnBeforeValidate: Boolean = false) : PluginEvent()

    /** The shopper is being sent to an external step (3-D Secure, PSP page) — web `redirect`. */
    data object Redirect : PluginEvent()

    /** The plugin's `/init`/setup failed before any UI could work — web `initFailed`. */
    data class InitFailed(val message: String? = null) : PluginEvent()

    /** The vendor client failed unrecoverably — web `clientError`/`died`. */
    data class ClientError(val message: String? = null) : PluginEvent()

    /**
     * The partner reported an error. [recoverable] distinguishes web's `partnerRecoverableError`
     * (dim the method, it comes back on its own) from `partnerError` (fatal for the session).
     */
    data class PartnerError(val message: String? = null, val recoverable: Boolean = false) : PluginEvent()

    /** The shopper backed out of the partner flow — web `cancelled`. */
    data object Cancelled : PluginEvent()

    /** The partner said the session is finished for good — web `endOfSession`. */
    data object EndOfSession : PluginEvent()
}
