package eu.purse.dropin.payment.method

import java.math.BigDecimal

/**
 * The amount-driven subset of usp-widget headless-checkout's `updateMopDisabledState`
 * (`packages/headless-checkout/src/session/payment/payment.ts`, over the precedence in
 * `session/payment/mops/disabled.ts`): given how much a primary method still has to pay
 * (`primaryReach` — the session amount minus what applied secondaries cover), decide whether each
 * method is currently selectable.
 *
 * Web re-runs this over every method whenever the splitter's `primaryReach` changes (a secondary is
 * applied/removed). This POC computes it in two places against the same rule:
 * - [eu.purse.dropin.session.PaymentSessionBuilder] at build time, with `primaryReach` = the full
 *   session amount (no split has happened yet);
 * - live, via [eu.purse.dropin.DropinAndroid.methodsForPrimaryReach], as the shopper adds
 *   secondaries and [eu.purse.dropin.payment.split.PaymentSplitter.remaining] drops.
 *
 * See docs/architecture/session-and-protocols.md#method-availability-rules-disabled-states for the
 * precedence and its rationale.
 */
object MethodAvailability {

    /**
     * The amount-driven disabled reason for a single method against [primaryReach], following web's
     * precedence; `null` when the method is currently selectable. An [isInformationRequest] session
     * never disables on amount (there's nothing to reach).
     */
    fun disabledFor(
        configuration: MethodConfiguration,
        isInformationRequest: Boolean,
        primaryReach: BigDecimal,
    ): DisabledReason? {
        if (isInformationRequest) return null

        val minAmount = configuration.settings.paymentMinAmount
        val maxAmount = configuration.settings.paymentMaxAmount

        return when {
            primaryReach.signum() == 0 -> DisabledReason.PaymentFulfilled
            minAmount != null && primaryReach < minAmount -> DisabledReason.MinAmountNotMet(minAmount, primaryReach)
            // A secondary is never disabled for exceeding max — a gift card can legitimately hold
            // more balance than the amount owed; it just can't draw down more than what's left.
            maxAmount != null && primaryReach > maxAmount && !configuration.isSecondary ->
                DisabledReason.MaxAmountExceeded(maxAmount)
            else -> null
        }
    }

    /**
     * Recompute every method's amount-driven disabled state against a new [primaryReach], returning
     * a fresh list (the models are immutable — see [DropinPaymentMethod.withDisabled]). Mirrors web
     * running `updateMopDisabledState` over all mops on each `primaryReach` change.
     *
     * This POC's [DisabledReason] only models the amount-driven codes, so there is nothing to
     * preserve here; web additionally leaves any pre-existing technical/fatal reason untouched
     * rather than overwriting it with an amount recalculation.
     */
    fun recompute(
        methods: List<DropinPaymentMethod>,
        isInformationRequest: Boolean,
        primaryReach: BigDecimal,
    ): List<DropinPaymentMethod> = methods.map { method ->
        method.withDisabled(disabledFor(method.configuration, isInformationRequest, primaryReach))
    }
}
