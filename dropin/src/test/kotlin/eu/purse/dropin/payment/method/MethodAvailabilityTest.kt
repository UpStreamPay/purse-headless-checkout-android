package eu.purse.dropin.payment.method

import eu.purse.dropin.payment.partner.Partner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The live-recompute path (web's `updateMopDisabledState` reacting to a changing `primaryReach`).
 * Build-time computation is covered indirectly by `PaymentSessionBuilderTest`; both share
 * [MethodAvailability.disabledFor], so these tests focus on [MethodAvailability.recompute] behaving
 * as the split draws the remaining amount down.
 */
class MethodAvailabilityTest {

    private fun settings(min: BigDecimal? = null, max: BigDecimal? = null) = MethodSettings(
        registrationMode = RegistrationMode.ON_SESSION,
        saveToken = false,
        saveTokenMode = null,
        directDebit = false,
        paymentMinAmount = min,
        paymentMaxAmount = max,
        usagePerSessionLimit = null,
        integrationMode = null,
    )

    private fun config(labels: List<String> = emptyList(), min: BigDecimal? = null, max: BigDecimal? = null) =
        MethodConfiguration(
            stable = true,
            template = null,
            labels = labels,
            position = null,
            vault = null,
            settings = settings(min, max),
        )

    private fun primary(id: String, config: MethodConfiguration) =
        PrimaryMethod(id, Partner.Purse, Method.CreditCard, config, disabled = null)

    private fun secondary(id: String, config: MethodConfiguration) =
        SecondaryMethod(id, Partner.Illicado, Method.GiftCard, config, disabled = null)

    @Test
    fun `a primary drops to MinAmountNotMet once the remaining amount falls below its minimum`() {
        val method = primary("purse-creditcard", config(min = BigDecimal("10.00")))

        // Fresh split: full 50.00 still owed — selectable.
        val full = MethodAvailability.recompute(listOf(method), isInformationRequest = false, BigDecimal("50.00"))
        assertNull(full.single().disabled)

        // A 45.00 gift card leaves 5.00 owed — now below the 10.00 minimum.
        val reduced = MethodAvailability.recompute(listOf(method), isInformationRequest = false, BigDecimal("5.00"))
        val disabled = reduced.single().disabled
        assertTrue(disabled is DisabledReason.MinAmountNotMet)
        assertEquals(BigDecimal("10.00"), (disabled as DisabledReason.MinAmountNotMet).minAmount)
        assertEquals(BigDecimal("5.00"), disabled.restToPay)
    }

    @Test
    fun `every method becomes PaymentFulfilled when the split covers the whole amount`() {
        val methods = listOf(
            primary("purse-creditcard", config(min = BigDecimal("1.00"))),
            secondary("illicado-giftcard", config(labels = listOf("secondary"))),
        )

        val recomputed = MethodAvailability.recompute(methods, isInformationRequest = false, BigDecimal.ZERO)

        assertTrue(recomputed.all { it.disabled == DisabledReason.PaymentFulfilled })
    }

    @Test
    fun `a secondary is never disabled for exceeding its max even after the split changes`() {
        val giftCard = secondary("illicado-giftcard", config(labels = listOf("secondary"), max = BigDecimal("50")))

        val recomputed = MethodAvailability.recompute(listOf(giftCard), isInformationRequest = false, BigDecimal("200"))

        assertNull("a gift card can hold more balance than the amount owed", recomputed.single().disabled)
    }

    @Test
    fun `recompute clears a stale disabled state once the amount is reachable again`() {
        // Start already disabled (as if a prior, lower primaryReach had disabled it)...
        val stale = primary("purse-creditcard", config(min = BigDecimal("10.00")))
            .withDisabled(DisabledReason.MinAmountNotMet(BigDecimal("10.00"), BigDecimal("5.00")))

        // ...then recompute against a reach that meets the minimum again.
        val recomputed = MethodAvailability.recompute(listOf(stale), isInformationRequest = false, BigDecimal("20.00"))

        assertNull(recomputed.single().disabled)
    }

    @Test
    fun `an information_request session never disables on amount`() {
        val method = primary("purse-creditcard", config(min = BigDecimal("10.00")))

        val recomputed = MethodAvailability.recompute(listOf(method), isInformationRequest = true, BigDecimal.ZERO)

        assertNull(recomputed.single().disabled)
    }
}
