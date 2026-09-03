package eu.purse.partnerui.plugins.partners.paypal.wallet

import eu.purse.core.environment.PurseEnvironment
import eu.purse.core.payload.ValidationResult
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.redirect.RedirectionDelegate

/**
 * What [PaypalCoordinator] needs from the surrounding checkout session — kept as an interface so
 * this plugin can depend only on `:core` + `:partner-ui`, not `:headless-checkout` (which itself
 * depends on `:partner-ui`; a dependency back the other way would cycle). `:headless-checkout`'s
 * `PurseHeadlessCheckout.paypalHost` is the concrete implementation — the same seam shape as
 * [eu.purse.partnerui.plugins.generics.googlepay.GooglePayHost].
 */
interface PaypalHost : RedirectionDelegate {

    /**
     * The Purse environment, mapped to PayPal's own by [PaypalCoordinator]:
     * [PurseEnvironment.PRODUCTION] → PayPal `LIVE`, everything else → `SANDBOX`.
     */
    val environment: PurseEnvironment

    /**
     * The session's own configured return URL (`LegacyPaymentSession.redirection`), if any. Not
     * PayPal's approval return — the PayPal SDK supplies that itself, client-side (see
     * [PaypalCheckoutActivity]). This is only threaded into a *chained* 3-D Secure redirect after
     * `/validate` as its [eu.purse.partnerui.redirect.RedirectAction.returnUrlPrefix], so the
     * in-app WebView recognizes reaching it as completion and closes itself.
     */
    val redirectionReturnUrl: String?

    /** The [PartnerUiContext] for this PayPal item against the current split. */
    fun buildContext(): PartnerUiContext

    /** Stash the `/init` step id and the approved order for the next [submitPayment]. */
    fun setCollected(data: Map<String, Any?>)

    /**
     * Commit this PayPal item as the split's primary. The coordinator calls it once the buyer has
     * **approved** the order — web's `validationRequested` wiring: the button tap is the shopper
     * choosing the method, so the merchant never pre-selects it, and a cancelled approval leaves
     * the split untouched.
     */
    fun setAsPrimary() {}

    /** Run the plugin-driven `/validate` pipeline over the current split. */
    suspend fun submitPayment(): ValidationResult
}
