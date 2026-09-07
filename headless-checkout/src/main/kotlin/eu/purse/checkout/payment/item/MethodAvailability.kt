// Web: packages/headless-checkout mops recompute on `primaryReach`
// Same: min/max amount and information-request rules.
// Differs: nothing.
package eu.purse.checkout.payment.item

import eu.purse.checkout.payment.split.SplitSource
import eu.purse.core.payment.MethodConfiguration
import java.math.BigDecimal

/**
 * The amount-driven subset of web headless-checkout's `updateMopDisabledState`
 * (`session/payment/payment.ts`) plus the build-time rules of `buildDisabledWritable`
 * (`session/payment/mops/item-factory/utils.ts:127`): given how much a primary still has to pay
 * (`primaryReach` — the session amount minus what applied secondaries cover), decide whether each
 * method is currently selectable.
 *
 * Web re-runs this over every method whenever the splitter's `primaryReach` changes, skipping any
 * method whose disabled state is not recomputable (a suspension or a plugin failure survives every
 * amount change). This SDK does the same from [eu.purse.checkout.PurseHeadlessCheckout] after each
 * split change, and once at build time (with `primaryReach` = the full amount).
 */
object MethodAvailability {

    /**
     * The permanent, build-time disabled state for one method, or `null` — web's
     * `buildDisabledWritable`:
     * - `configurations.stable == false` → [DisabledState.MethodUnstable] (a platform suspension);
     * - `payment_min_amount` above the full order amount → [DisabledState.AmountConditionUnreachable].
     *
     * Both are [DisabledState.isFiltered] codes: the item never appears in the public method list.
     * The min-amount rule is skipped for a zero/absent order amount (web guards on a falsy
     * `orderAmount`), which also exempts information-request sessions.
     */
    fun buildTimeDisabledFor(
        configuration: MethodConfiguration,
        orderAmount: BigDecimal,
    ): DisabledState? {
        if (!configuration.stable) return DisabledState.MethodUnstable

        val minAmount = configuration.settings.paymentMinAmount
        if (minAmount != null && orderAmount.signum() > 0 && minAmount > orderAmount) {
            return DisabledState.AmountConditionUnreachable(
                minAmount = minAmount,
                maxAmount = configuration.settings.paymentMaxAmount,
                orderAmount = orderAmount,
            )
        }
        return null
    }

    /**
     * The amount-driven disabled reason for one method against [primaryReach]; `null` when
     * selectable. An [isInformationRequest] session never disables on amount.
     *
     * [isInSplit] mirrors web's `isInSplits` exemption: a method already participating in the split
     * is never disabled as fulfilled or below-minimum by the very split it belongs to — otherwise
     * covering the amount would disable the covering method. The max-amount rule is *not* exempted
     * (matching web).
     */
    fun disabledFor(
        configuration: MethodConfiguration,
        isInformationRequest: Boolean,
        primaryReach: BigDecimal,
        isInSplit: Boolean = false,
    ): DisabledState? {
        if (isInformationRequest) return null

        val minAmount = configuration.settings.paymentMinAmount
        val maxAmount = configuration.settings.paymentMaxAmount

        return when {
            !isInSplit && primaryReach.signum() == 0 -> DisabledState.PaymentFulfilled
            !isInSplit && minAmount != null && primaryReach < minAmount ->
                DisabledState.MinAmountNotMet(minAmount, primaryReach)
            // A secondary is never disabled for exceeding max — a gift card can legitimately hold
            // more balance than the amount owed; it just can't draw down more than what's left.
            maxAmount != null && primaryReach > maxAmount && !configuration.isSecondary ->
                DisabledState.MaxAmountExceeded(maxAmount, primaryReach)
            else -> null
        }
    }

    /**
     * Recompute every item's disabled state against a new [primaryReach], returning a fresh list.
     * Items whose current state is not [DisabledState.isRecomputable] are kept untouched — web
     * `updateMopDisabledState` returns early for those ("keep existing disabled state for other
     * reasons"). [splitSources] are the sources currently drawn on by the split, used for the
     * in-split exemption (matched on partner+method, like web).
     */
    fun recompute(
        methods: List<PaymentItem>,
        isInformationRequest: Boolean,
        primaryReach: BigDecimal,
        splitSources: List<SplitSource> = emptyList(),
    ): List<PaymentItem> = methods.map { item ->
        val current = item.disabled
        if (current != null && !current.isRecomputable) {
            item
        } else {
            val isInSplit = splitSources.any { it.partner == item.partner && it.method == item.method }
            item.withDisabled(
                disabledFor(item.configuration, isInformationRequest, primaryReach, isInSplit),
            )
        }
    }
}
