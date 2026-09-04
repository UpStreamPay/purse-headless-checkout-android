// Web: packages/headless-checkout/src/session/payment/mops/disabled.ts
// Same: same codes, recoverable / filtered / requiresUiClear sets.
// Differs: nothing.
package eu.purse.checkout.payment.item

import java.math.BigDecimal

/**
 * Why a [PaymentItem] currently can't be selected, even though the session offers it — the Kotlin
 * analog of web headless-checkout's `DisabledState` (`session/payment/mops/disabled.ts`), including
 * its classification sets and predicates.
 *
 * Codes fall into behavioural groups (mirroring web's exported code lists):
 * - [isFiltered] — the item is removed from the public method list entirely (web filters it out of
 *   `mops`); the merchant never renders it.
 * - [isTechnicallyDisabled] — a hard runtime failure; web makes `appendTo` throw for these.
 * - [requiresUiClear] — any mounted partner UI for the item should be torn down.
 * - [isRecoverable] — the partner reported broken-for-now, not broken-for-good: render the method
 *   dimmed but keep its UI mounted; it comes back on its own.
 * - [isRecomputable] — re-evaluated from scratch whenever the amount left to pay changes; anything
 *   else survives a recompute untouched (see [MethodAvailability.recompute]).
 *
 * Web additionally has `SUBMITTING` (declared but never set), the `PAYMENT_ELEMENT_*` codes (DOM
 * payment-element rendering) and `DOES_NOT_BELONG_TO_CURRENT_SESSION` (multi-`setSession` element
 * survival) — none of which have an Android behaviour to attach to, so they are deliberately absent.
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
    }

    /**
     * The platform suspended the method: `configurations.stable == false`. Permanent for the
     * session, and filtered from the public list (web `item-factory/utils.ts:139`).
     */
    data object MethodUnstable : DisabledState(Code.METHOD_UNSTABLE)

    /**
     * `payment_min_amount` exceeds the full order amount, so no split could ever enable the method.
     * Computed once at session build (web `item-factory/utils.ts:144`) and filtered from the list.
     */
    data class AmountConditionUnreachable(
        val minAmount: BigDecimal,
        val maxAmount: BigDecimal?,
        val orderAmount: BigDecimal,
    ) : DisabledState(Code.AMOUNT_CONDITION_UNREACHABLE)

    /** The remaining amount owed by a primary is already zero. */
    data object PaymentFulfilled : DisabledState(Code.PAYMENT_FULFILLED)

    data class MinAmountNotMet(val minAmount: BigDecimal, val restToPay: BigDecimal) :
        DisabledState(Code.MIN_AMOUNT_NOT_MET)

    /** Never applies to secondary methods — a gift card can hold more than the amount owed. */
    data class MaxAmountExceeded(val maxAmount: BigDecimal) : DisabledState(Code.MAX_AMOUNT_EXCEEDED)

    /** The resolved plugin rejected the current client/session in its eligibility pre-check. */
    data object PluginIncompatible : DisabledState(Code.PLUGIN_INCOMPATIBLE)

    /** A secondary's `/init` failed terminally for this session. */
    data object TokenInitFailed : DisabledState(Code.TOKEN_INIT_FAILED)

    /** A partner plugin reported an unrecoverable failure while mounted. */
    data class PartnerUiFatalError(val reason: String? = null) :
        DisabledState(Code.PARTNER_UI_FATAL_ERROR)

    /** A partner plugin reported a failure it expects to come back from without a restart. */
    data class PartnerUiRecoverableError(val reason: String? = null) :
        DisabledState(Code.PARTNER_UI_RECOVERABLE_ERROR)

    /** The plugin failed before it could mount any UI at all. */
    data class FatalErrorBeforeRender(val reason: String? = null) :
        DisabledState(Code.FATAL_ERROR_BEFORE_RENDER)

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
