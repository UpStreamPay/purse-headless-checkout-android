// Web: packages/headless-checkout/src/session/payment/mops/disabled.ts
// Same: same codes and messages, recoverable / filtered / requiresUiClear sets.
// Differs: SUBMITTING is set on every method during a submit (web declares it but never sets it).
package eu.purse.checkout.payment.item

import java.math.BigDecimal

/**
 * Why a [PaymentItem] currently can't be selected, even though the session offers it — the Kotlin
 * analog of web headless-checkout's `DisabledState` (`session/payment/mops/disabled.ts`), including
 * its classification sets and predicates. Every state carries web's displayable [message]; the
 * amount-driven ones also carry their context (minimum, maximum, amount still owed).
 *
 * Codes fall into behavioural groups (mirroring web's exported code lists):
 * - [isFiltered] — the item is removed from the public method list entirely (web filters it out of
 *   `mops`); the merchant never renders it.
 * - [isTechnicallyDisabled] — a hard runtime failure; web makes `appendTo` throw for these, and
 *   `rememberPaymentElement` renders nothing.
 * - [requiresUiClear] — any mounted partner UI for the item should be torn down.
 * - [isRecoverable] — the partner reported broken-for-now, not broken-for-good: render the method
 *   dimmed but keep its UI mounted; it comes back on its own.
 * - [isRecomputable] — re-evaluated from scratch whenever the amount left to pay changes; anything
 *   else survives a recompute untouched (see [MethodAvailability.recompute]).
 *
 * [Submitting] is overlaid on every otherwise-enabled item for the duration of `submitPayment` and
 * lifted when it returns, whatever the outcome. Web's `PAYMENT_ELEMENT_*` codes (DOM rendering) and
 * `DOES_NOT_BELONG_TO_CURRENT_SESSION` (an element outliving `setSession`) have no Android
 * behaviour to attach to — `itemGenerations` rebuilds such elements instead — and are absent.
 */
sealed class DisabledState(val code: Code) {

    enum class Code {
        METHOD_UNSTABLE,
        AMOUNT_CONDITION_UNREACHABLE,
        PAYMENT_FULFILLED,
        MIN_AMOUNT_NOT_MET,
        MAX_AMOUNT_EXCEEDED,
        PLUGIN_INCOMPATIBLE,
        TOKEN_INIT_FAILED,
        PARTNER_UI_FATAL_ERROR,
        PARTNER_UI_RECOVERABLE_ERROR,
        FATAL_ERROR_BEFORE_RENDER,
        SUBMITTING,
    }

    /** A human-readable explanation a merchant can show — web's `message`. */
    abstract val message: String

    /**
     * The platform suspended the method: `configurations.stable == false`. Permanent for the
     * session, and filtered from the public list (web `item-factory/utils.ts:139`).
     */
    data object MethodUnstable : DisabledState(Code.METHOD_UNSTABLE) {
        override val message = "This payment method is currently unstable and cannot be used."
    }

    /**
     * `payment_min_amount` exceeds the full order amount, so no split could ever enable the method.
     * Computed once at session build (web `item-factory/utils.ts:144`) and filtered from the list.
     */
    data class AmountConditionUnreachable(
        val minAmount: BigDecimal,
        val maxAmount: BigDecimal?,
        val orderAmount: BigDecimal,
    ) : DisabledState(Code.AMOUNT_CONDITION_UNREACHABLE) {
        override val message get() = "This payment cannot be used for this order"
    }

    /** The remaining amount owed by a primary is already zero. */
    data object PaymentFulfilled : DisabledState(Code.PAYMENT_FULFILLED) {
        override val message = "The payment amount has already been fulfilled."
    }

    data class MinAmountNotMet(val minAmount: BigDecimal, val restToPay: BigDecimal) :
        DisabledState(Code.MIN_AMOUNT_NOT_MET) {
        override val message get() =
            "The minimum amount for this payment method is ${minAmount.toPlainString()}. " +
                "Amount is currently ${restToPay.toPlainString()}."
    }

    /** Never applies to secondary methods — a gift card can hold more than the amount owed. */
    data class MaxAmountExceeded(val maxAmount: BigDecimal, val restToPay: BigDecimal? = null) :
        DisabledState(Code.MAX_AMOUNT_EXCEEDED) {
        override val message get() =
            "The maximum amount for this payment method is ${maxAmount.toPlainString()}." +
                (restToPay?.let { " Amount is currently ${it.toPlainString()}." } ?: "")
    }

    /** The resolved plugin rejected the current client/session in its eligibility pre-check. */
    data object PluginIncompatible : DisabledState(Code.PLUGIN_INCOMPATIBLE) {
        override val message = "This payment method is not compatible with the current client."
    }

    /** A secondary's `/init` failed terminally for this session. */
    data object TokenInitFailed : DisabledState(Code.TOKEN_INIT_FAILED) {
        override val message = "Failed to initialize the secondary payment token."
    }

    /** A partner plugin reported an unrecoverable failure while mounted. */
    data class PartnerUiFatalError(val reason: String? = null) : DisabledState(Code.PARTNER_UI_FATAL_ERROR) {
        override val message get() =
            "An unexpected error occurred in the payment form. The payment method has been deactivated."
    }

    /** A partner plugin reported a failure it expects to come back from without a restart. */
    data class PartnerUiRecoverableError(val reason: String? = null) : DisabledState(Code.PARTNER_UI_RECOVERABLE_ERROR) {
        override val message get() = "This payment method is temporarily unavailable."
    }

    /** The plugin failed before it could mount any UI at all. */
    data class FatalErrorBeforeRender(val reason: String? = null) : DisabledState(Code.FATAL_ERROR_BEFORE_RENDER) {
        override val message get() = "This payment method is not eligible for this order."
    }

    /** A payment is being submitted — every method is locked until `submitPayment` returns. */
    data object Submitting : DisabledState(Code.SUBMITTING) {
        override val message = "A payment is being submitted."
    }

    val isFiltered: Boolean get() = code in FILTERED_CODES
    val isTechnicallyDisabled: Boolean get() = code in TECHNICALLY_DISABLED_CODES
    val requiresUiClear: Boolean get() = code in SHOULD_CLEAR_UI_CODES
    val isRecoverable: Boolean get() = code in RECOVERABLE_CODES
    val isRecomputable: Boolean get() = code in RECOMPUTABLE_CODES

    companion object {
        /** Web `FilteredDisabledStateCodes` — the item disappears from the public method list. */
        val FILTERED_CODES: Set<Code> = setOf(
            Code.METHOD_UNSTABLE,
            Code.AMOUNT_CONDITION_UNREACHABLE,
            Code.FATAL_ERROR_BEFORE_RENDER,
            Code.PLUGIN_INCOMPATIBLE,
        )

        /** Web `TechnicallyDisabledStateCodes` (minus the DOM-only codes). */
        val TECHNICALLY_DISABLED_CODES: Set<Code> = setOf(
            Code.TOKEN_INIT_FAILED,
            Code.PARTNER_UI_FATAL_ERROR,
        )

        /** Web `ShouldClearUIDisabledStatesCodes`. */
        val SHOULD_CLEAR_UI_CODES: Set<Code> = TECHNICALLY_DISABLED_CODES + setOf(
            Code.MIN_AMOUNT_NOT_MET,
            Code.PAYMENT_FULFILLED,
            Code.MAX_AMOUNT_EXCEEDED,
        )

        /** Web `RecoverableDisabledStateCodes`. */
        val RECOVERABLE_CODES: Set<Code> = setOf(Code.PARTNER_UI_RECOVERABLE_ERROR)

        /**
         * Web `RecomputableDisabledStateCodes` — the only states a change in the amount to pay
         * re-evaluates; everything else is final for the session.
         */
        val RECOMPUTABLE_CODES: Set<Code> = RECOVERABLE_CODES + setOf(
            Code.MIN_AMOUNT_NOT_MET,
            Code.PAYMENT_FULFILLED,
            Code.MAX_AMOUNT_EXCEEDED,
        )
    }
}
