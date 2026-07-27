package eu.purse.partnerui.plugins.partners.klarna

/**
 * What the backend's `/init` `plugin_result.logs` gives us for Klarna — mirrors web's
 * `getKlarnaConfigFromInitData` (`plugins/partners/klarna-installments/index.ts`), trimmed to the
 * one field the native SDK's `KlarnaPaymentView.initialize` needs.
 */
data class KlarnaConfig(val clientToken: String)
