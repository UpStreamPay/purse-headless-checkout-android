// Web: packages/partner-ui/src/events.ts (`UnifiedEvent`)
// Same: the codes the checkout reacts to (ready, init/client/partner errors, end of session).
// Differs: no `fulfilled` (that travels through `PluginHost.setFulfilled`) and no `requestValidate`
//          (a self-submitting coordinator calls `setAsPrimary` + `submitPayment` directly).
package eu.purse.partnerui

/**
 * Lifecycle signals a coordinator sends back to the checkout through
 * [PartnerUiContext.dispatchEvent]. The checkout deactivates the method on fatal events, clears a
 * recoverable state on [Ready], terminates the session on [EndOfSession], and re-exposes every
 * event on its `pluginEvents` stream for the merchant's UI.
 */
sealed class PluginEvent {

    /**
     * A stable name for this event — what the `HEADLESS_CHECKOUT_PARTNER_UI_EVENT` monitoring
     * payload carries as `code`. Explicit rather than the class name so an R8-minified app reports
     * the same codes as a debug build.
     */
    abstract val code: String

    /** The plugin's client is up and usable — clears a recoverable disabled state. */
    data object Ready : PluginEvent() {
        override val code = "Ready"
    }

    /** The shopper is being sent to an external step (3-D Secure, PSP page). */
    data object Redirect : PluginEvent() {
        override val code = "Redirect"
    }

    /** The plugin's `/init`/setup failed before any UI could work. */
    data class InitFailed(val message: String? = null) : PluginEvent() {
        override val code get() = "InitFailed"
    }

    /** The vendor client failed unrecoverably. */
    data class ClientError(val message: String? = null) : PluginEvent() {
        override val code get() = "ClientError"
    }

    /** The partner reported an error; [recoverable] dims the method instead of disabling it. */
    data class PartnerError(val message: String? = null, val recoverable: Boolean = false) : PluginEvent() {
        override val code get() = "PartnerError"
    }

    /** The shopper backed out of the partner flow. */
    data object Cancelled : PluginEvent() {
        override val code = "Cancelled"
    }

    /** The partner said the session is finished for good. */
    data object EndOfSession : PluginEvent() {
        override val code = "EndOfSession"
    }
}
