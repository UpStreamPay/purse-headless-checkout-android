// Web: `DelegatedRedirectionHandlerFactory` (`redirectionHandler`)
// Same: the merchant may take a redirect over.
// Differs: one shared dispatch step for every coordinator.
package eu.purse.partnerui.redirect

import eu.purse.core.InternalPurseApi

/**
 * The merchant's chance to take a redirect over — the seam through which `:headless-checkout`
 * exposes its `PurseCheckoutHooks.onRedirection` hook to every coordinator, the Kotlin analog of
 * web's `redirectionHandler` (`DelegatedRedirectionHandlerFactory`: when the merchant supplies one,
 * partner-ui hands it the resolved action instead of navigating itself).
 *
 * `PluginHost` extends this; the default says "not handled", so a host built without a hook
 * behaves exactly as before — the SDK opens its own in-app WebView.
 */
interface RedirectionDelegate {

    /**
     * Offer [action] to the merchant.
     *
     * @return `true` when the merchant handled the redirect itself — the SDK then opens **nothing**
     * and the merchant owns confirming the outcome (from its backend, on return); `false` to
     * let the SDK open its in-app WebView as usual.
     * @throws Exception when the merchant hook failed — the SDK opens nothing and reports the
     * failure to the element listener; the checkout itself stays usable.
     */
    suspend fun delegateRedirection(action: RedirectAction): Boolean = false

    /**
     * The shopper is leaving the checkout for [action]: [delegated] is `true` when
     * [delegateRedirection] handed it to the merchant, `false` when the SDK is about to open its own
     * WebView. Monitoring only (SDK-12712): [RedirectDispatcher] calls it once per redirect, after the
     * hook answered and before the launch. It must neither block nor throw. The default does nothing.
     */
    @InternalPurseApi
    fun onRedirecting(action: RedirectAction, delegated: Boolean) {}
}

/**
 * One shared "offer to the merchant, else launch" step for the five coordinators, so the hook
 * contract (handled → no WebView; not handled → WebView; threw → nothing, report) can't drift
 * between them. Every redirect that leaves the checkout goes through here, so this is also where it
 * is reported, once, through [RedirectionDelegate.onRedirecting].
 */
internal object RedirectDispatcher {

    sealed interface Outcome {
        /** The merchant took the redirect over — nothing more to do here. */
        data object Delegated : Outcome

        /** The SDK's own launcher was invoked (or would have been, had one been wired). */
        data object Launched : Outcome

        /** The merchant hook threw — nothing was launched; [error] is the merchant's exception. */
        data class HookFailed(val error: Throwable) : Outcome
    }

    suspend fun dispatch(
        action: RedirectAction,
        delegate: RedirectionDelegate,
        launch: ((RedirectAction) -> Unit)?,
    ): Outcome {
        val handled = try {
            delegate.delegateRedirection(action)
        } catch (e: Exception) {
            return Outcome.HookFailed(e)
        }
        // Reported once the hook has answered, the only point where `delegated` is known, and before
        // the launch. A throwing hook opened nothing, so it reports no redirect (CLIENT_HOOK_ERROR
        // covers it), and neither does a decline with no launcher wired. Monitoring can never stop
        // the redirect.
        if (handled || launch != null) runCatching { delegate.onRedirecting(action, delegated = handled) }
        if (handled) return Outcome.Delegated
        launch?.invoke(action)
        return Outcome.Launched
    }
}
