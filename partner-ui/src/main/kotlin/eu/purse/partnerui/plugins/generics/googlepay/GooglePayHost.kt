package eu.purse.partnerui.plugins.generics.googlepay

import eu.purse.core.environment.PurseEnvironment
import eu.purse.core.payload.ValidationResult
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.redirect.RedirectionDelegate

/**
 * What [GooglePayCoordinator] needs from the surrounding checkout session — kept as an interface so
 * this plugin can depend only on `:core` + `:partner-ui`, not `:headless-checkout` (which itself
 * depends on `:partner-ui`; a dependency back the other way would cycle). `:headless-checkout`'s
 * `PurseHeadlessCheckout.googlePayHost` is the concrete implementation.
 */
interface GooglePayHost : RedirectionDelegate {

    val environment: PurseEnvironment

    /**
     * The session's own configured return URL (`LegacyPaymentSession.redirection`), if any. Not
     * something to auto-follow (web's `should_submit_widget` fallback does that, and it's a
     * browser-page concept that doesn't apply headless — see [eu.purse.partnerui.redirect.RedirectionResolver]'s
     * doc). It's threaded into a genuine 3-D Secure redirect as its
     * [eu.purse.partnerui.redirect.RedirectAction.returnUrlPrefix], purely
     * so the in-app WebView recognizes reaching it as completion and closes itself, for partners
     * whose ACS redirects here instead of this app's own deep-link scheme.
     */
    val redirectionReturnUrl: String?

    /** The [PartnerUiContext] for this Google Pay item against the current split. */
    fun buildContext(): PartnerUiContext

    /** Stash the sheet's token + selected network for the next [submitPayment]. */
    fun setCollected(data: Map<String, Any?>)

    /**
     * Commit this Google Pay item as the split's primary. The coordinator calls it once the sheet
     * is **approved** — web's `validationRequested` wiring (`setPrimary(mop)` then submit): tapping
     * the button is the shopper choosing the method, so the merchant never pre-selects it, and a
     * cancelled sheet leaves the split untouched.
     */
    fun setAsPrimary() {}

    /** Run the plugin-driven `/validate` pipeline over the current split. */
    suspend fun submitPayment(): ValidationResult
}
