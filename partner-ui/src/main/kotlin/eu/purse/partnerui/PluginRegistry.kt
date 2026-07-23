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
 * priority.
 *
 * All three registries are intentionally **empty**: no concrete plugins exist yet, so this always
 * returns `null`. Registering a plugin later is a one-line addition to the relevant map.
 */
object PluginRegistry {

    /** Card-vaulting plugins, keyed by `configurations.vault.vendor` (web's vault family). */
    private val vaultPlugins: Map<VaultVendor, PluginBuilder> = emptyMap()

    /** Reusable templates, keyed by `configurations.template` (web's generic family). */
    private val genericPlugins: Map<String, PluginBuilder> = emptyMap()

    /** PSP-specific native plugins, keyed by `(partner, method)` (web's partner family). */
    private val partnerPlugins: Map<Pair<Partner, Method>, PluginBuilder> = emptyMap()

    fun getPlugin(partner: Partner, method: Method, session: LegacyPaymentSession): PartnerUiPlugin? {
        val configuration = session.protocols[partner]?.get(method)?.configurations ?: return null

        val builder = configuration.vault
            ?.vendor
            ?.takeIf { it != VaultVendor.NONE }
            ?.let(vaultPlugins::get)
            ?: configuration.template?.let(genericPlugins::get)
            ?: partnerPlugins[partner to method]

        return builder?.invoke(PluginBuilderData(partner, method))
    }
}
