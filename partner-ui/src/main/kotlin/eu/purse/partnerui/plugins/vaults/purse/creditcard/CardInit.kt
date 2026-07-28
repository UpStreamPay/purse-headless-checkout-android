package eu.purse.partnerui.plugins.vaults.purse.creditcard

/**
 * The `/init` result for a `purse`-vendor vault-backed card payment — just the `step_id` to carry
 * into `/validate`. Unlike Google Pay/Klarna's `/init`, this call carries no card data yet (that
 * only exists once SecureFields tokenizes) — it just registers the method and returns a step id,
 * mirroring web's `plugins/generics/hostedfields`' `onSessionSet`.
 */
data class CardInit(val stepId: Int?)
