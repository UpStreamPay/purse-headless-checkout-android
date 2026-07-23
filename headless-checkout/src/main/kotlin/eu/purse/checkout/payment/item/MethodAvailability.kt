package eu.purse.checkout.payment.item

import eu.purse.core.payment.MethodConfiguration
import java.math.BigDecimal

/**
 * The amount-driven subset of web headless-checkout's `updateMopDisabledState`: given how much a
 * primary still has to pay (`primaryReach` — the session amount minus what applied secondaries
 * cover), decide whether each method is currently selectable.
 *
 * Web re-runs this over every method whenever the splitter's `primaryReach` changes. This SDK does
 * the same from [eu.purse.checkout.PurseHeadlessCheckout] after each split change, and once at
 * build time (with `primaryReach` = the full amount).
 */
object MethodAvailability {

    /**
     * The amount-driven disabled reason for one method against [primaryReach]; `null` when
     * selectable. An [isInformationRequest] session never disables on amount.
     */
    fun disabledFor(
        configuration: MethodConfiguration,
        isInformationRequest: Boolean,
        primaryReach: BigDecimal,
    ): DisabledState? {
        if (isInformationRequest) return null

        val minAmount = configuration.settings.paymentMinAmount
        val maxAmount = configuration.settings.paymentMaxAmount

        return when {
            primaryReach.signum() == 0 -> DisabledState.PaymentFulfilled
            minAmount != null && primaryReach < minAmount ->
                DisabledState.MinAmountNotMet(minAmount, primaryReach)
            // A secondary is never disabled for exceeding max — a gift card can legitimately hold
            // more balance than the amount owed; it just can't draw down more than what's left.
            maxAmount != null && primaryReach > maxAmount && !configuration.isSecondary ->
                DisabledState.MaxAmountExceeded(maxAmount)
            else -> null
        }
    }

    /** Recompute every item's disabled state against a new [primaryReach], returning a fresh list. */
    fun recompute(
        methods: List<PaymentItem>,
        isInformationRequest: Boolean,
        primaryReach: BigDecimal,
    ): List<PaymentItem> = methods.map { item ->
        item.withDisabled(disabledFor(item.configuration, isInformationRequest, primaryReach))
    }
}
