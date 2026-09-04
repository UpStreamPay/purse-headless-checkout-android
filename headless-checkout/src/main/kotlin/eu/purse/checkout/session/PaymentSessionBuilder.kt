// Web: packages/headless-checkout/src/session/payment/mops/item-factory/factories.ts
// Same: protocols → items, availability rules, tokens.
// Differs: nothing.
package eu.purse.checkout.session

import eu.purse.checkout.payment.item.MethodAvailability
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.checkout.payment.item.PaymentToken
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SavedSecondaryToken
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.checkout.payment.secondary.SecondaryConfigs
import eu.purse.checkout.payment.secondary.SecondaryTakePolicy
import eu.purse.core.payment.RegistrationMode
import eu.purse.core.session.LegacyPaymentSession
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Builds the ready-to-consume [PaymentSession] from a decoded [LegacyPaymentSession] — the Kotlin
 * analog of web's `getSessionFromLegacy`. Turns `protocols` into the means-of-payment list and
 * applies the build-time availability rules (against the full amount, before any split).
 */
object PaymentSessionBuilder {

    fun build(session: LegacyPaymentSession): PaymentSession {
        // `session.amount` is a cross-module property (declared in `:core`) so it can't smart-cast;
        // bind it to a local before the null-driven checks below.
        val sessionAmount = session.amount
        val isInformationRequest = session.informationRequest
            ?: (sessionAmount == null || sessionAmount.signum() == 0)
        val amount = sessionAmount ?: BigDecimal.ZERO

        return PaymentSession(
            id = session.id,
            source = session,
            amount = amount,
            amountCts = amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact(),
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
    ): List<PaymentItem> {
        val items = mutableListOf<PaymentItem>()
        for ((partner, methods) in session.protocols) {
            for ((method, entry) in methods) {
                val configuration = entry.configurations

                // In an information_request session, only OFF_SESSION-registrable methods are
                // included at all — everything else is dropped, not merely disabled.
                if (isInformationRequest &&
                    configuration.settings.registrationMode != RegistrationMode.OFF_SESSION
                ) {
                    continue
                }

                val id = "${partner.wireValue}-${method.wireValue}"
                // Permanent build-time states (suspension, unreachable min-amount) win over the
                // amount-driven ones — web sets them in `buildDisabledWritable` before the first
                // recompute, and recompute skips them as non-recomputable.
                val disabled = MethodAvailability.buildTimeDisabledFor(configuration, primaryReach)
                    ?: MethodAvailability.disabledFor(configuration, isInformationRequest, primaryReach)

                // Saved instruments ride along their method — one item per integrated_tokens[]
                // row: a PaymentToken (card-on-file, a primary) under a primary method, a
                // SavedSecondaryToken (gift card on file) under a secondary one — web's
                // `buildPrimaryPaymentToken` vs `buildSecondaryPaymentToken` split. Stripped from
                // information-request sessions (web `payment/utils.ts`: the goal is data
                // collection, not paying with saved instruments).
                if (!isInformationRequest) {
                    val takePolicy = SecondaryConfigs.forMethod(partner, method)?.takePolicy ?: SecondaryTakePolicy.NONE
                    entry.integratedTokens.forEach { token ->
                        items += if (configuration.isSecondary) {
                            SavedSecondaryToken.from(token, partner, method, configuration, disabled, takePolicy)
                        } else {
                            PaymentToken.from(token, partner, method, configuration, disabled)
                        }
                    }
                }

                items += if (configuration.isSecondary) {
                    SecondaryMethod(id, partner, method, configuration, disabled)
                } else {
                    PrimaryMethod(
                        id = id,
                        partner = partner,
                        method = method,
                        configuration = configuration,
                        disabled = disabled,
                        integrationType = configuration.settings.integrationMode,
                        supportedBrands = configuration.vault?.supportedBrands ?: emptyList(),
                    )
                }
            }
        }
        return items.sortedWith(POSITION_COMPARATOR)
    }

    /** Ascending by `position`; entries without one sort last, matching web's `positionSort`. */
    private val POSITION_COMPARATOR = Comparator<PaymentItem> { a, b ->
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
