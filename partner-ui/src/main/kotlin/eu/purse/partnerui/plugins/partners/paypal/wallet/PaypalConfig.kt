package eu.purse.partnerui.plugins.partners.paypal.wallet

/**
 * The merchant's PayPal REST client id, for the PayPal SDK's `CoreConfig`.
 *
 * Unlike every other plugin's config, this does **not** come from `/init` — it is session data
 * (`protocols.paypal.wallet.configurations.settings.merchant_specifics.client_id`, parsed into
 * `MethodSettings.merchantClientId`), read by [PaypalMapper.parseSessionConfig]. Google Pay's
 * equivalent goes the other way and is `/init`-supplied, a difference its own
 * [eu.purse.partnerui.plugins.generics.googlepay.GooglePayConfig] doc already calls out.
 */
data class PaypalConfig(val clientId: String)
