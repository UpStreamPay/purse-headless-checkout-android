package eu.purse.partnerui.plugins.partners.klarna

import eu.purse.core.payload.ValidationResult
import eu.purse.partnerui.PartnerUiContext

/**
 * What [KlarnaCoordinator] needs from the surrounding checkout session — kept as an interface so this
 * plugin can depend only on `:core` + `:partner-ui`, not `:headless-checkout` (which itself depends on
 * `:partner-ui`; a dependency back the other way would cycle). `:headless-checkout`'s
 * `PurseHeadlessCheckout.klarnaHost` is the concrete implementation — the same seam shape as
 * `GooglePayHost`.
 */
interface KlarnaHost {

    /**
     * The deep-link URL `KlarnaPaymentView`'s `returnURL` should carry, for the SDK to return control
     * to this app after an external banking/identity-verification redirect (see
     * https://docs.klarna.com/acquirer/klarna/mobile-payments/integrate-with-mobile-sdk/android/native-view/).
     * Unlike Google Pay's 3-D Secure return (handled by a self-contained Activity this module ships),
     * this must resume the *same* Activity instance hosting the `KlarnaPaymentView` — so the host app
     * declares the matching intent-filter (`singleTask`) on its own Activity, not `:partner-ui`.
     */
    val returnUrl: String

    /** The [PartnerUiContext] for this Klarna item against the current split. */
    fun buildContext(): PartnerUiContext

    /** Stash the `/init` step id for the next [submitPayment]. */
    fun setCollected(data: Map<String, Any?>)

    /** Run the plugin-driven `/validate` pipeline over the current split. */
    suspend fun submitPayment(): ValidationResult
}
