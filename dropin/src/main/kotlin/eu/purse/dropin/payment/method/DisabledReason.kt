package eu.purse.dropin.payment.method

import java.math.BigDecimal

/**
 * Why a [DropinPaymentMethod] currently can't be selected, even though it's present in
 * `protocols` — see
 * docs/architecture/session-and-protocols.md#method-availability-rules-disabled-states.
 *
 * Only the amount-driven subset the parser can compute at build time. The web taxonomy also has
 * technical-failure codes (`TokenInitFailed`, `PluginIncompatible`, ...) that only make sense
 * once a method is actually being rendered/submitted — out of scope here.
 */
sealed class DisabledReason {

    /** The remaining amount owed by primary methods is already zero. */
    object PaymentFulfilled : DisabledReason()

    data class MinAmountNotMet(val minAmount: BigDecimal, val restToPay: BigDecimal) : DisabledReason()

    /** Never applies to secondary methods — see the linked doc section. */
    data class MaxAmountExceeded(val maxAmount: BigDecimal) : DisabledReason()
}
