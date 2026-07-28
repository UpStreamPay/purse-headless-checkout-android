package eu.purse.partnerui.plugins.partners.klarna

import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PluginRegistry

/**
 * Entry point for the Klarna plugin. [PluginRegistry] calls [register] automatically on first use
 * — an app does **not** need to call this itself. Calling it again is harmless (idempotent; last
 * registration wins) — it stays public mainly for tests that want to register in isolation. To
 * opt this method out for a specific app build, use [PluginRegistry.disable], not the absence of
 * a [register] call — see the class doc on [PluginRegistry].
 */
object KlarnaModule {

    /**
     * Registers the Klarna plugin for its one dedicated `(klarna, installments)` pair — unlike Google
     * Pay, Klarna isn't a template reused across gateways, so no fallback is needed.
     */
    fun register() {
        PluginRegistry.register(Partner.Klarna, Method.Installments) { data -> KlarnaPlugin(data.partner) }
    }
}
