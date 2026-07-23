package eu.purse.checkout.payment.split

import eu.purse.checkout.payment.item.PaymentItem
import java.math.BigDecimal

/**
 * One slice of the order amount assigned to a payment item — web's `PaymentSplit`. The set of
 * splits (secondaries plus the primary's remainder) is what the "create payment" pipeline submits
 * to `/validate`. [amount] is in major currency units.
 */
data class PaymentSplit(
    val amount: BigDecimal,
    val source: PaymentItem,
)
