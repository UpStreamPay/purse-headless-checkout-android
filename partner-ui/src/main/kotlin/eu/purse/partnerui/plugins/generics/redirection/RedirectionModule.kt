package eu.purse.partnerui.plugins.generics.redirection

import eu.purse.partnerui.PluginRegistry

/**
 * Entry point for the generic redirection plugin. [PluginRegistry] calls [register] automatically on
 * first use — an app does **not** need to call this itself. Calling it again is harmless (idempotent;
 * last registration wins) — it stays public mainly for tests that want to register in isolation. To
 * opt a specific method out for an app build, use [PluginRegistry.disable], not the absence of a
 * [register] call — see the class doc on [PluginRegistry].
 */
object RedirectionModule {

    /**
     * Registers this plugin as the `"redirection"` **generic template**, rather than for any
     * `(partner, method)` pair — the difference from [GooglePayModule][eu.purse.partnerui.plugins.generics.googlepay.GooglePayModule]
     * and [KlarnaModule][eu.purse.partnerui.plugins.partners.klarna.KlarnaModule], and the point of
     * the template family: the *session* decides which methods it covers, via
     * `configurations.template`. Any partner/method the merchant's Purse configuration marks
     * `redirection` is handled without a line of code here naming it.
     *
     * [PluginRegistry.getPlugin] additionally routes a method whose
     * `settings.merchant_specifics.integration_mode` is `redirection` here even when its `template`
     * says otherwise — see its own doc, and web's `getPartnerPlugin`, which does the same.
     */
    fun register() {
        PluginRegistry.registerGeneric(RedirectionPlugin.TEMPLATE) { data ->
            RedirectionPlugin(data.partner, data.method)
        }
    }
}
