package eu.purse.dropin.session

import eu.purse.dropin.payment.method.DisabledReason
import eu.purse.dropin.payment.method.DropinPaymentMethod
import eu.purse.dropin.payment.method.MethodConfiguration
import eu.purse.dropin.payment.method.PrimaryMethod
import eu.purse.dropin.payment.method.RegistrationMode
import eu.purse.dropin.payment.method.SecondaryMethod
import java.math.BigDecimal

/**
 * Builds the ready-to-render [PaymentSession] from a decoded [LegacyPaymentSession] — applying
 * the method-list construction and availability rules documented in
 * docs/architecture/session-and-protocols.md#turning-protocols-into-a-method-list and
 * #method-availability-rules-disabled-states.
 */
object PaymentSessionBuilder {

    fun build(session: LegacyPaymentSession): PaymentSession {
        val isInformationRequest = session.informationRequest
            ?: (session.amount == null || session.amount.signum() == 0)
        val amount = session.amount ?: BigDecimal.ZERO

        return PaymentSession(
            id = session.id,
            source = session,
            amount = amount,
            isInformationRequest = isInformationRequest,
            currency = session.currencyCode,
            expirationDate = session.expirationDate,
            vaultsConfiguration = session.vault?.vendorFrontData,
            meansOfPayment = buildMeansOfPayment(session, isInformationRequest, primaryReach = amount),
        )
    }

    private fun buildMeansOfPayment(
        session: LegacyPaymentSession,
        isInformationRequest: Boolean,
        primaryReach: BigDecimal,
    ): List<DropinPaymentMethod> {
        val items = mutableListOf<DropinPaymentMethod>()
        for ((partner, methods) in session.protocols) {
            for ((method, entry) in methods) {
                val configuration = entry.configurations

                // In an information_request session, only OFF_SESSION-registrable methods are
                // included at all — everything else is dropped from the list, not just disabled.
                if (isInformationRequest && configuration.settings.registrationMode != RegistrationMode.OFF_SESSION) {
                    continue
                }

                val id = "${partner.wireValue}-${method.wireValue}"
                val disabled = computeDisabledState(configuration, isInformationRequest, primaryReach)

                items += if (configuration.isSecondary) {
                    SecondaryMethod(id, partner, method, configuration, disabled)
                } else {
                    PrimaryMethod(id, partner, method, configuration, disabled)
                }
            }
        }
        return items.sortedWith(POSITION_COMPARATOR)
    }

    /**
     * The amount-driven subset of web's `updateMopDisabledState` — see
     * docs/architecture/session-and-protocols.md#method-availability-rules-disabled-states.
     * `primaryReach` here is the full session amount, since no split/payment has happened yet;
     * recomputing this against a live splitter as the user builds a split payment is future work.
     */
    private fun computeDisabledState(
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
            maxAmount != null && primaryReach > maxAmount && !configuration.isSecondary ->
                DisabledReason.MaxAmountExceeded(maxAmount)
            else -> null
        }
    }

    /** Ascending by `position`; entries without one sort last, matching web's `positionSort`. */
    private val POSITION_COMPARATOR = Comparator<DropinPaymentMethod> { a, b ->
        val positionA = a.configuration.position
        val positionB = b.configuration.position
        when {
            positionA == null && positionB == null -> 0
            positionA == null -> 1
            positionB == null -> -1
            else -> positionA.compareTo(positionB)
        }
    }
}
