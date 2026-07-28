package eu.purse.partnerui

import eu.purse.core.payment.IntegrationMode
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.core.vault.VaultVendor
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayModule
import eu.purse.partnerui.plugins.generics.redirection.RedirectionModule
import eu.purse.partnerui.plugins.generics.redirection.RedirectionPlugin
import eu.purse.partnerui.plugins.partners.klarna.KlarnaModule
import eu.purse.partnerui.plugins.vaults.purse.creditcard.CardModule

/** Data a [PluginBuilder] receives to construct a plugin — web's `PluginBuilderData`. */
data class PluginBuilderData(
    val partner: Partner,
    val method: Method,
)

/** Plugins are factory functions, exactly as in web (`PluginBuilder = (data) => Plugin`). */
typealias PluginBuilder = (PluginBuilderData) -> PartnerUiPlugin

/**
 * Resolves the plugin for a `(partner, method)` in a session — the Kotlin analog of web's
 * `getPlugin` (`packages/partner-ui/src/plugins/index.ts`) with its **vault → generic → partner**
 * priority, falling back to a method-level default.
 *
 * Every plugin `:partner-ui` ships is registered **by default** (see the `init` block below) — no
 * app-side setup call required, mirroring web's `getPlugin`, which resolves off a static lookup
 * table with no registration step at all. The session itself (checked in [getPlugin] against
 * `protocols`) is the real gate on whether a method is offered — that's already decided
 * server-side by the merchant's Purse configuration, so requiring a *second*, app-side opt-in on
 * top of it was a redundant switch that could silently drift out of sync with the first one. If a
 * merchant genuinely needs to suppress a shipped method for a specific app build, use [disable]
 * rather than never registering it. [register] / [registerFallback] remain here for any plugin
 * that isn't bundled in `:partner-ui` itself (a genuinely separate module or third party).
 */
object PluginRegistry {

    /** Card-vaulting plugins, keyed by `configurations.vault.vendor` (web's vault family). */
    private val vaultPlugins = mutableMapOf<VaultVendor, PluginBuilder>()

    /** Reusable templates, keyed by `configurations.template` (web's generic family). */
    private val genericPlugins = mutableMapOf<String, PluginBuilder>()

    /** PSP-specific native plugins, keyed by `(partner, method)` (web's partner family). */
    private val partnerPlugins = mutableMapOf<Pair<Partner, Method>, PluginBuilder>()

    /** Method-level fallbacks used when no exact `(partner, method)` plugin is registered. */
    private val methodFallbacks = mutableMapOf<Method, PluginBuilder>()

    /** `(partner, method)` pairs explicitly opted out via [disable], overriding any registration. */
    private val disabled = mutableSetOf<Pair<Partner, Method>>()

    init {
        // Built-in plugins are active by default — see the class doc for why. Calling register()
        // again later (e.g. GooglePayModule.register()) is harmless (last registration wins).
        GooglePayModule.register()
        KlarnaModule.register()
        CardModule.register()
        RedirectionModule.register()
    }

    /** Register a plugin for an exact `(partner, method)`. Idempotent (last registration wins). */
    fun register(partner: Partner, method: Method, builder: PluginBuilder) {
        partnerPlugins[partner to method] = builder
    }

    /**
     * Register a reusable **generic template** plugin under a `configurations.template` value (web's
     * generic family — `"redirection"`, and eventually `"hostedfields"` / `"sdpx"` / `"lyra"`). Unlike
     * [register], this names no partner or method at all: the session decides which methods the
     * template covers, so one registration serves every `(partner, method)` whose configuration
     * carries [template].
     */
    fun registerGeneric(template: String, builder: PluginBuilder) {
        genericPlugins[template] = builder
    }

    /** Register a fallback for a method regardless of partner (e.g. a default Google Pay builder). */
    fun registerFallback(method: Method, builder: PluginBuilder) {
        methodFallbacks[method] = builder
    }

    /** Register a card-vaulting plugin for [vendor] (`configurations.vault.vendor`). */
    fun registerVault(vendor: VaultVendor, builder: PluginBuilder) {
        vaultPlugins[vendor] = builder
    }

    /**
     * Opt a built-in `(partner, method)` out for this app build, even though the session might
     * offer it and a plugin is registered for it — e.g. the app's UI for that method isn't ready
     * yet. [getPlugin] returns `null` for it until [enable] undoes this.
     */
    fun disable(partner: Partner, method: Method) {
        disabled += partner to method
    }

    /** Reverse a previous [disable]. */
    fun enable(partner: Partner, method: Method) {
        disabled -= partner to method
    }

    /**
     * Resolution order, mirroring web's `getPlugin` → `getGenericPlugin` → `getPartnerPlugin` chain:
     *
     * ```
     * disabled?                                        → null
     * vault.vendor ≠ none                              → vault plugin
     * configurations.template                          → generic template plugin   (strict equality)
     * merchant_specifics.integration_mode = redirection → the redirection generic
     * exact (partner, method)                          → partner plugin
     * method                                           → method fallback
     * ```
     *
     * The `integration_mode` step sits **above** the exact-pair lookup, not below it, because that's
     * where web puts it: its `getPartnerPlugin` checks `integration_mode === 'redirection'` and returns
     * the redirection generic *before* consulting its own `partner_plugins` table. So a method the
     * merchant configured to redirect is handled by the redirect template even when a dedicated plugin
     * for that pair exists — web has a test pinning both directions of that (`redirection` routes to
     * the generic, `popup` does not).
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
