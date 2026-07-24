package eu.purse.partnerui

import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.core.vault.VaultVendor

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
 * Plugins live in their own modules (e.g. `:partner-googlepay`) and **register themselves at
 * runtime** via [register] / [registerFallback], so this contract module never depends on any
 * implementation. Until something registers, `getPlugin` returns `null`.
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

    /** Register a plugin for an exact `(partner, method)`. Idempotent (last registration wins). */
    fun register(partner: Partner, method: Method, builder: PluginBuilder) {
        partnerPlugins[partner to method] = builder
    }

    /** Register a fallback for a method regardless of partner (e.g. a default Google Pay builder). */
    fun registerFallback(method: Method, builder: PluginBuilder) {
        methodFallbacks[method] = builder
    }

    fun getPlugin(partner: Partner, method: Method, session: LegacyPaymentSession): PartnerUiPlugin? {
        val configuration = session.protocols[partner]?.get(method)?.configurations ?: return null

        val builder = configuration.vault
            ?.vendor
            ?.takeIf { it != VaultVendor.NONE }
            ?.let(vaultPlugins::get)
            ?: configuration.template?.let(genericPlugins::get)
            ?: partnerPlugins[partner to method]
            ?: methodFallbacks[method]

        return builder?.invoke(PluginBuilderData(partner, method))
    }
}
