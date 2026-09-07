// Web: packages/headless-checkout/src/session/payment/splitter/types.ts
// Same: source + amount.
// Differs: nothing.
package eu.purse.checkout.payment.split

import java.math.BigDecimal

/**
 * One slice of the order amount assigned to a [SplitSource] — web's `PaymentSplit`. The set of
 * splits (applied secondary tokens plus the primary's remainder) is what the "create payment"
 * pipeline submits to `/validate`. [amount] is in major currency units.
 */
data class PaymentSplit(
    val amount: BigDecimal,
    val source: SplitSource,
)
