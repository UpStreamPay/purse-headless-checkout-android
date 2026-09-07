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

    /** The plugin's client is up and usable — clears a recoverable disabled state. */
    data object Ready : PluginEvent()

    /** The shopper is being sent to an external step (3-D Secure, PSP page). */
    data object Redirect : PluginEvent()

    /** The plugin's `/init`/setup failed before any UI could work. */
    data class InitFailed(val message: String? = null) : PluginEvent()

    /** The vendor client failed unrecoverably. */
    data class ClientError(val message: String? = null) : PluginEvent()

    /** The partner reported an error; [recoverable] dims the method instead of disabling it. */
    data class PartnerError(val message: String? = null, val recoverable: Boolean = false) : PluginEvent()

    /** The shopper backed out of the partner flow. */
    data object Cancelled : PluginEvent()

    /** The partner said the session is finished for good. */
    data object EndOfSession : PluginEvent()
}
