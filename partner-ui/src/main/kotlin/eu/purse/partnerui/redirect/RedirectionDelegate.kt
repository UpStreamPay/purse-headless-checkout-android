package eu.purse.partnerui.redirect

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
     * and the merchant owns confirming the outcome (e.g. `paymentStatus()` on return); `false` to
     * let the SDK open its in-app WebView as usual.
     * @throws Exception when the merchant hook failed — the SDK opens nothing and reports the
     * failure to the element listener; the checkout itself stays usable.
     */
    suspend fun delegateRedirection(action: RedirectAction): Boolean = false
}

/**
 * One shared "offer to the merchant, else launch" step for the five coordinators, so the hook
 * contract (handled → no WebView; not handled → WebView; threw → nothing, report) can't drift
 * between them.
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
        if (handled) return Outcome.Delegated
        launch?.invoke(action)
        return Outcome.Launched
    }
}
