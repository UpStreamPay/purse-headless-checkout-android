// Web: none — on web a plugin holds the checkout context directly.
// Same: what a plugin can ask of the checkout (context, collected data, fulfilment, submit).
// Differs: an interface implemented by `PurseHeadlessCheckout.host(item)`, so `:partner-ui`
//          never depends on `:headless-checkout`.
package eu.purse.partnerui

import eu.purse.core.environment.PurseEnvironment
import eu.purse.core.payload.ValidationResult
import eu.purse.partnerui.redirect.RedirectionDelegate
import java.math.BigDecimal
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything a coordinator needs from the checkout for one payment item. One shape for every
 * plugin; a coordinator only calls the members its flow uses.
 */
interface PluginHost : RedirectionDelegate {

    val environment: PurseEnvironment

    /**
     * The session's own return URL (`LegacyPaymentSession.redirection`), threaded into a 3-D Secure
     * redirect so the in-app WebView recognizes reaching it as completion. Never auto-followed.
     */
    val redirectionReturnUrl: String?

    /**
     * The amount this item must cover (`PartnerUiContext.amount`), moving exactly when plugins get
     * web's `onAmountUpdate`. A coordinator that bakes the amount into what it prepared (a Google Pay
     * sheet request, a Klarna `/init` client token) prepares again when it moves.
     */
    val amount: StateFlow<BigDecimal>

    /** The [PartnerUiContext] for this item against the current split. */
    fun buildContext(): PartnerUiContext

    /** Stash what the native step produced (a sheet token, a `/init` step id, a vault token) for the next [submitPayment]. */
    fun setCollected(data: Map<String, Any?>)

    /**
     * Commit this item as the split's primary. Self-submitting flows call it on approval (web's
     * `validationRequested`): the tap is the shopper choosing the method, and a cancelled sheet
     * leaves the split untouched. Throws when the item is disabled (`METHOD_DISABLED`) — use
     * `PaymentCoordinator.selectAndCapture`, which turns that into a submit failure.
     */
    fun setAsPrimary()

    /** Report whether this item's own form is complete and valid; gates `isPaymentFulfilled`. */
    fun setFulfilled(fulfilled: Boolean)

    /** Run the plugin-driven `/validate` pipeline over the current split. */
    suspend fun submitPayment(): ValidationResult
}
