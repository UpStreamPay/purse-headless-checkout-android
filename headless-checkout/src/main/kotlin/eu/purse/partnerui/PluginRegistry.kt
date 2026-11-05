// Web: packages/partner-ui/src/plugins/index.ts (`getPlugin`) + `partners/partner_plugins.ts`
// Same: resolution order vault → template → integration_mode=redirection → (partner, method); a
//       template no plugin serves falls through (web's `'stripe_googlepay'` reaches Stripe's partner
//       plugin, an `'axepta_wero'` in redirection mode the redirection generic). An item nothing
//       serves is the checkout's to retire, PARTNER_UI_FATAL_ERROR at engagement (SDK-12575).
// Differs: a last per-method fallback (Google Pay whatever the partner), which web lacks; a
//          mutable registry instead of a static table, so a host can add a plugin for a
//          `Partner.Other`; the Google Pay, Klarna and PayPal plugins arrive from optional artifacts
//          (`PartnerModule` via `ServiceLoader`) instead of being compiled in.
package eu.purse.partnerui

import eu.purse.core.payment.IntegrationMode
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.core.vault.VaultVendor
import eu.purse.partnerui.plugins.generics.redirection.RedirectionModule
import eu.purse.partnerui.plugins.generics.redirection.RedirectionPlugin
import eu.purse.partnerui.plugins.vaults.purse.creditcard.CardModule
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/** What a [PluginBuilder] receives — web's `PluginBuilderData`. */
data class PluginBuilderData(
    val partner: Partner,
    val method: Method,
)

/** Plugins are factory functions, as on web. */
typealias PluginBuilder = (PluginBuilderData) -> PartnerUiPlugin

/**
 * Resolves the plugin for a `(partner, method)` of a session. The card and redirection plugins
 * `:partner-ui` ships register here at class load, then every [PartnerModule] found on the classpath
 * (the optional `headless-checkout-googlepay` / `-klarna` / `-paypal` artifacts) adds its own. The
 * session's `protocols` remain the only gate on whether a method is offered; an app build that wants
 * a method out passes it to `PurseHeadlessCheckout.init(disabledMethods = …)`, which is scoped to
 * that checkout instead of this process-wide registry.
 */
object PluginRegistry {

    private val vaultPlugins = mutableMapOf<VaultVendor, PluginBuilder>()
    private val genericPlugins = mutableMapOf<String, PluginBuilder>()
    private val partnerPlugins = mutableMapOf<Pair<Partner, Method>, PluginBuilder>()
    private val methodFallbacks = mutableMapOf<Method, PluginBuilder>()
    private val installed = mutableListOf<PartnerModule>()

    init {
        install(CardModule)
        install(RedirectionModule)
        // The two-argument form is the one R8 recognizes and rewrites into direct instantiation,
        // so a minified app keeps working even if META-INF/services is stripped.
        try {
            ServiceLoader.load(PartnerModule::class.java, PartnerModule::class.java.classLoader).forEach(::install)
        } catch (e: ServiceConfigurationError) {
            // A packaging defect (a service file naming a class that is missing or has no public
            // no-arg constructor) — surface it as such rather than as a bare static-init failure.
            throw IllegalStateException("A Purse partner artifact is mis-packaged: ${e.message}", e)
        }
    }

    /** The modules currently contributing plugins, built-ins first, then in classpath order. */
    val modules: List<PartnerModule> get() = installed.toList()

    /**
     * Add [module]'s plugins. Every artifact on the classpath is installed automatically; call this
     * for a module of your own, or when a build tool stripped the artifact's service declaration.
     * Installing the same module twice is a no-op.
     */
    fun install(module: PartnerModule) {
        if (installed.any { it::class == module::class }) return
        installed += module
        module.register(this)
    }

    /** The module that renders [plugin], or `null` when no installed module claims it. */
    fun moduleFor(plugin: PartnerUiPlugin): PartnerModule? = installed.firstOrNull { it.renders(plugin) }

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

    /**
     * ```
     * vault.vendor ≠ none                                → vault plugin
     * configurations.template                            → generic plugin
     * merchant_specifics.integration_mode = redirection  → redirection generic (web checks this
     *                                                      before its own partner table too)
     * exact (partner, method)                            → partner plugin
     * method                                             → method fallback
     * ```
     *
     * A `configurations.template` no plugin is registered for does not stop the walk, as on web: the
     * session may name a template the partner plugin itself handles (web's `'stripe_googlepay'`).
     * `null` means nothing serves the pair; the checkout then retires the item when it is engaged.
     */
    fun getPlugin(partner: Partner, method: Method, session: LegacyPaymentSession): PartnerUiPlugin? {
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
