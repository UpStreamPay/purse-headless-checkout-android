package eu.purse.dropin.partnerui.vault.purse.creditcard

/**
 * The `/init` result for a `purse`-vendor vault-backed card payment — just the `step_id` to carry
 * into `/validate`. Mirrors [eu.purse.dropin.partnerui.partner.paypal.wallet.WalletOrder], minus an
 * `orderId`: unlike PayPal, this `/init` call registers the method and nothing else — the card
 * data/token comes from SecureFields, not this response. See
 * `usp-widget/packages/partner-ui/src/plugins/generics/hostedfields/index.ts`'s `onSessionSet`.
 */
data class CardInit(val stepId: String?)
