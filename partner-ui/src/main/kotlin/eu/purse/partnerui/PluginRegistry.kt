// Web: packages/partner-ui/src/plugins/index.ts (`getPlugin`) + `partners/partner_plugins.ts`
// Same: resolution order vault → template → integration_mode=redirection → (partner, method).
// Differs: a mutable registry instead of a static table, so a host can add a plugin for a
//          `Partner.Other`; Google Pay is keyed on (ingenico, googlepay) + a method fallback,
//          not on a `googlepay` template.
package eu.purse.partnerui

import eu.purse.core.payment.IntegrationMode
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.core.vault.VaultVendor
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayPlugin
import eu.purse.partnerui.plugins.generics.googlepay.partners.ingenico.Ingenico
import eu.purse.partnerui.plugins.generics.redirection.RedirectionPlugin
import eu.purse.partnerui.plugins.partners.klarna.KlarnaPlugin
import eu.purse.partnerui.plugins.partners.paypal.wallet.PaypalPlugin
import eu.purse.partnerui.plugins.vaults.purse.creditcard.CardPlugin

/** What a [PluginBuilder] receives — web's `PluginBuilderData`. */
data class PluginBuilderData(
    val partner: Partner,
    val method: Method,
)

/** Plugins are factory functions, as on web. */
typealias PluginBuilder = (PluginBuilderData) -> PartnerUiPlugin

/**
 * Resolves the plugin for a `(partner, method)` of a session. Every plugin `:partner-ui` ships is
 * registered here at class load; the session's `protocols` remain the only gate on whether a
 * method is offered. [disable] opts a shipped method out for one app build.
 */
object PluginRegistry {

    private val vaultPlugins = mutableMapOf<VaultVendor, PluginBuilder>()
    private val genericPlugins = mutableMapOf<String, PluginBuilder>()
    private val partnerPlugins = mutableMapOf<Pair<Partner, Method>, PluginBuilder>()
    private val methodFallbacks = mutableMapOf<Method, PluginBuilder>()
    private val disabled = mutableSetOf<Pair<Partner, Method>>()

    init {
        registerVault(VaultVendor.PURSE) { CardPlugin(it.partner) }
        registerGeneric(RedirectionPlugin.TEMPLATE) { RedirectionPlugin(it.partner, it.method) }
        register(Partner.Other(Ingenico.WIRE_VALUE), Method.GooglePay) { GooglePayPlugin(it.partner) }
        registerFallback(Method.GooglePay) { GooglePayPlugin(it.partner) }
        register(Partner.Klarna, Method.Installments) { KlarnaPlugin(it.partner) }
        register(Partner.Paypal, Method.Wallet) { PaypalPlugin(it.partner) }
    }

    /** Exact `(partner, method)`; last registration wins. */
    fun register(partner: Partner, method: Method, builder: PluginBuilder) {
        partnerPlugins[partner to method] = builder
    }

    /** A template plugin keyed on `configurations.template`, serving any pair the session labels with it. */
    fun registerGeneric(template: String, builder: PluginBuilder) {
        genericPlugins[template] = builder
    }

    /** A fallback for [method] whatever the partner. */
    fun registerFallback(method: Method, builder: PluginBuilder) {
        methodFallbacks[method] = builder
    }

    /** A card plugin keyed on `configurations.vault.vendor`. */
    fun registerVault(vendor: VaultVendor, builder: PluginBuilder) {
        vaultPlugins[vendor] = builder
    }

    /** Hide a shipped `(partner, method)` from this app build even when the session offers it. */
    fun disable(partner: Partner, method: Method) {
        disabled += partner to method
    }

    fun enable(partner: Partner, method: Method) {
        disabled -= partner to method
    }

    /**
     * ```
     * disabled?                                          → null
     * vault.vendor ≠ none                                → vault plugin
     * configurations.template                            → generic plugin
     * merchant_specifics.integration_mode = redirection  → redirection generic (web checks this
     *                                                      before its own partner table too)
     * exact (partner, method)                            → partner plugin
     * method                                             → method fallback
     * ```
     */
    fun getPlugin(partner: Partner, method: Method, session: LegacyPaymentSession): PartnerUiPlugin? {
        if (partner to method in disabled) return null
        val configuration = session.protocols[partner]?.get(method)?.configurations ?: return null

        val builder = configuration.vault
            ?.vendor
            ?.takeIf { it != VaultVendor.NONE }
            ?.let(vaultPlugins::get)
            ?: configuration.template?.let(genericPlugins::get)
            ?: RedirectionPlugin.TEMPLATE
                .takeIf { configuration.settings.integrationMode == IntegrationMode.REDIRECTION }
                ?.let(genericPlugins::get)
            ?: partnerPlugins[partner to method]
            ?: methodFallbacks[method]

        return builder?.invoke(PluginBuilderData(partner, method))
    }
}
