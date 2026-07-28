package eu.purse.partnerui.plugins.generics.redirection

import eu.purse.core.payload.ValidationResult
import eu.purse.partnerui.PartnerUiContext

/**
 * What [RedirectionCoordinator] needs from the surrounding checkout session — kept as an interface so
 * this plugin can depend only on `:core` + `:partner-ui`, not `:headless-checkout` (which itself
 * depends on `:partner-ui`; a dependency back the other way would cycle). `:headless-checkout`'s
 * `PurseHeadlessCheckout.redirectionHost` is the concrete implementation — the same seam shape as
 * `GooglePayHost` / `KlarnaHost` / `CardHost`.
 */
interface RedirectionHost {

    /**
     * The session's own configured return URL (`LegacyPaymentSession.redirection`), if any. Used the
     * same two ways it is for the other plugins: as the
     * [RedirectAction.returnUrlPrefix][eu.purse.partnerui.redirect.RedirectAction.returnUrlPrefix] so
     * the in-app WebView recognizes reaching it as completion, and as the suppression check that
     * keeps the widget's own confirmation page from being mistaken for a genuine PSP redirect (see
     * [RedirectionResolver][eu.purse.partnerui.redirect.RedirectionResolver]'s doc).
     */
    val redirectionReturnUrl: String?

    /** The [PartnerUiContext] for this redirection item against the current split. */
    fun buildContext(): PartnerUiContext

    /** Stash the `/init` step id and browser block for the next [submitPayment]. */
    fun setCollected(data: Map<String, Any?>)

    /** Run the plugin-driven `/validate` pipeline over the current split. */
    suspend fun submitPayment(): ValidationResult
}
