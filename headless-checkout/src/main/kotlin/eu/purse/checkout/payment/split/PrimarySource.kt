package eu.purse.checkout.payment.split

/**
 * A [SplitSource] that can occupy the split's single primary slot: a
 * [eu.purse.checkout.payment.item.PrimaryMethod] (a fresh payment) or a
 * [eu.purse.checkout.payment.item.PaymentToken] (a saved card) — web lets both call
 * `setAsPrimarySource`.
 */
interface PrimarySource : SplitSource
