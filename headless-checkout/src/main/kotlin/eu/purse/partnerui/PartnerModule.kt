// Web: packages/partner-ui/src/plugins/index.ts (the static plugin table) + `appendTo` per plugin.
// Same: a plugin is both a protocol (`/init`, `/validate`) and something rendered for the shopper.
// Differs: partner SDKs ship as optional Gradle artifacts, so each one contributes its plugins and
//          its Compose surface through this seam and is discovered with `ServiceLoader` — web
//          bundles every plugin in one package.
package eu.purse.partnerui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * One partner artifact's contribution to the checkout: the plugins it adds to [PluginRegistry] and
 * how the methods those plugins serve render on screen.
 *
 * `:partner-ui` ships two built-in modules (Purse vault card, generic redirection). The optional
 * artifacts `headless-checkout-googlepay`, `headless-checkout-klarna` and `headless-checkout-paypal`
 * each ship one more, declared in `META-INF/services/eu.purse.partnerui.PartnerModule` so adding the
 * dependency is all an app does. A host can also add one explicitly with [PluginRegistry.install]
 * (a `Partner.Other` integration, or a build that strips service files).
 */
interface PartnerModule {

    /** Add this artifact's plugins — called once, when the registry loads. */
    fun register(registry: PluginRegistry)

    /** Whether [plugin] is one of this module's own (`plugin is MyPlugin`). */
    fun renders(plugin: PartnerUiPlugin): Boolean

    /**
     * The rendered half of [plugin] for one payment item: create the coordinator, wire its
     * Activity-result launchers, run `prepare` against the request's host, and describe what to
     * show. Only called when [renders] returned `true`.
     */
    @Composable
    fun rememberSurface(plugin: PartnerUiPlugin, request: SurfaceRequest): PaymentSurface
}

/**
 * What a [PartnerModule] needs to render one payment item.
 *
 * @property host the checkout seam for this item (`PurseHeadlessCheckout.host(item)`); one instance
 * per [key], so a coordinator may be remembered against it.
 * @property isToken `true` when the item is a saved instrument rather than a fresh method.
 * @property key changes when the element must rebuild (a new session generation for this method).
 * @property listener where the surface reports readiness and outcomes.
 * @property appReturnUrl a merchant override for the deep link a partner's own widget returns to
 * (Klarna); `null` means the module's default, `"{packageName}.<partner>://return"`.
 */
class SurfaceRequest(
    val host: PluginHost,
    val isToken: Boolean,
    val key: Any,
    val listener: PaymentElementListener,
    val appReturnUrl: String? = null,
)

/**
 * What one rendered method looks like to the payment element: whether there is anything on screen,
 * whether the method drives its own submission from its button, what [submit] does, and the UI.
 */
class PaymentSurface(
    /** `false` renders nothing — web's `hasUI()`. */
    val hasUi: Boolean,
    /** Google Pay / PayPal render their own button and pay from its tap; [submit] is then a no-op. */
    val selfSubmitting: Boolean,
    private val onSubmit: () -> Unit,
    /** The composable to place; only called when [hasUi]. */
    val ui: @Composable (Modifier) -> Unit,
) {
    fun submit() = onSubmit()

    companion object {
        /** A surface with nothing to show whose submit runs [onSubmit] (redirection, no-CVV tokens). */
        fun headless(onSubmit: () -> Unit) = PaymentSurface(hasUi = false, selfSubmitting = false, onSubmit) { }
    }
}
