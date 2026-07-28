package eu.purse.checkout.payment.item

import java.math.BigDecimal

/**
 * Why a [PaymentItem] currently can't be selected, even though the session offers it — a trimmed
 * analog of web headless-checkout's `DisabledState` (`session/payment/mops/disabled.ts`).
 *
 * This SDK models the amount-driven codes the splitter can compute, plus [PluginIncompatible] for
 * the eligibility pre-check. Web additionally has technical/fatal codes (`TOKEN_INIT_FAILED`,
 * `PARTNER_UI_FATAL_ERROR`, …) that only arise once a concrete plugin is rendering/submitting —
 * out of scope while plugins are unimplemented.
 */
sealed class DisabledState(val code: Code) {

    enum class Code { PAYMENT_FULFILLED, MIN_AMOUNT_NOT_MET, MAX_AMOUNT_EXCEEDED, PLUGIN_INCOMPATIBLE }

    /** The remaining amount owed by a primary is already zero. */
    data object PaymentFulfilled : DisabledState(Code.PAYMENT_FULFILLED)

    data class MinAmountNotMet(val minAmount: BigDecimal, val restToPay: BigDecimal) :
        DisabledState(Code.MIN_AMOUNT_NOT_MET)

    /** Never applies to secondary methods — a gift card can hold more than the amount owed. */
    data class MaxAmountExceeded(val maxAmount: BigDecimal) : DisabledState(Code.MAX_AMOUNT_EXCEEDED)

    /** The resolved plugin rejected the current client/session in its eligibility pre-check. */
    data object PluginIncompatible : DisabledState(Code.PLUGIN_INCOMPATIBLE)
}
