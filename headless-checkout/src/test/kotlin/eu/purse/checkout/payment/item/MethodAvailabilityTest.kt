package eu.purse.checkout.payment.item

import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.MethodSettings
import eu.purse.core.payment.Partner
import eu.purse.core.payment.RegistrationMode
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

    private fun config(
        labels: List<String> = emptyList(),
        min: BigDecimal? = null,
        max: BigDecimal? = null,
        stable: Boolean = true,
    ) = MethodConfiguration(
        stable = stable,
        template = null,
        labels = labels,
        position = null,
        vault = null,
        settings = settings(min, max),
    )

    private fun primary(id: String, config: MethodConfiguration) = PrimaryMethod(
        id, Partner.Purse, Method.CreditCard, config, disabled = null,
        integrationType = null, supportedBrands = emptyList(),
    )

    private fun secondary(id: String, config: MethodConfiguration) =
        SecondaryMethod(id, Partner.Illicado, Method.GiftCard, config, disabled = null)

    @Test
    fun `a primary drops to MinAmountNotMet once the remaining amount falls below its minimum`() {
        val method = primary("purse-creditcard", config(min = BigDecimal("10.00")))

        val full = MethodAvailability.recompute(listOf(method), isInformationRequest = false, BigDecimal("50.00"))
        assertNull(full.single().disabled)

        val reduced = MethodAvailability.recompute(listOf(method), isInformationRequest = false, BigDecimal("5.00"))
        val disabled = reduced.single().disabled
        assertTrue(disabled is DisabledState.MinAmountNotMet)
        assertEquals(BigDecimal("10.00"), (disabled as DisabledState.MinAmountNotMet).minAmount)
        assertEquals(BigDecimal("5.00"), disabled.restToPay)
    }

    @Test
    fun `every method becomes PaymentFulfilled when the split covers the whole amount`() {
        val methods = listOf(
            primary("purse-creditcard", config(min = BigDecimal("1.00"))),
            secondary("illicado-giftcard", config(labels = listOf("secondary"))),
        )

        val recomputed = MethodAvailability.recompute(methods, isInformationRequest = false, BigDecimal.ZERO)

        assertTrue(recomputed.all { it.disabled == DisabledState.PaymentFulfilled })
    }

    @Test
    fun `a secondary is never disabled for exceeding its max even after the split changes`() {
        val giftCard = secondary("illicado-giftcard", config(labels = listOf("secondary"), max = BigDecimal("50")))

        val recomputed = MethodAvailability.recompute(listOf(giftCard), isInformationRequest = false, BigDecimal("200"))

        assertNull("a gift card can hold more balance than the amount owed", recomputed.single().disabled)
    }

    @Test
    fun `recompute clears a stale disabled state once the amount is reachable again`() {
        val stale = primary("purse-creditcard", config(min = BigDecimal("10.00")))
            .withDisabled(DisabledState.MinAmountNotMet(BigDecimal("10.00"), BigDecimal("5.00")))

        val recomputed = MethodAvailability.recompute(listOf(stale), isInformationRequest = false, BigDecimal("20.00"))

        assertNull(recomputed.single().disabled)
    }

    @Test
    fun `an information_request session never disables on amount`() {
        val method = primary("purse-creditcard", config(min = BigDecimal("10.00")))

        val recomputed = MethodAvailability.recompute(listOf(method), isInformationRequest = true, BigDecimal.ZERO)

        assertNull(recomputed.single().disabled)
    }

    // --- build-time rules (web `buildDisabledWritable`) ------------------------------------------

    @Test
    fun `an unstable method is suspended with METHOD_UNSTABLE, a filtered code`() {
        val disabled = MethodAvailability.buildTimeDisabledFor(config(stable = false), BigDecimal("50.00"))

        assertEquals(DisabledState.MethodUnstable, disabled)
        assertTrue("suspensions must disappear from the public list", disabled!!.isFiltered)
    }

    @Test
    fun `a minimum above the order amount is AMOUNT_CONDITION_UNREACHABLE at build time`() {
        val disabled = MethodAvailability.buildTimeDisabledFor(
            config(min = BigDecimal("100.00"), max = BigDecimal("500.00")),
            orderAmount = BigDecimal("50.00"),
        )

        assertTrue(disabled is DisabledState.AmountConditionUnreachable)
        disabled as DisabledState.AmountConditionUnreachable
        assertEquals(BigDecimal("100.00"), disabled.minAmount)
        assertEquals(BigDecimal("500.00"), disabled.maxAmount)
        assertEquals(BigDecimal("50.00"), disabled.orderAmount)
        assertTrue(disabled.isFiltered)
    }

    @Test
    fun `the unreachable-minimum rule is skipped for a zero order amount`() {
        // Web guards on a falsy `orderAmount` — an information-request session (amount 0) never
        // produces AMOUNT_CONDITION_UNREACHABLE.
        val disabled = MethodAvailability.buildTimeDisabledFor(
            config(min = BigDecimal("100.00")),
            orderAmount = BigDecimal.ZERO,
        )

        assertNull(disabled)
    }

    @Test
    fun `a reachable configuration has no build-time disabled state`() {
        assertNull(MethodAvailability.buildTimeDisabledFor(config(min = BigDecimal("10.00")), BigDecimal("50.00")))
    }

    // --- recompute keeps non-recomputable states (web's early return) ----------------------------

    @Test
    fun `recompute never clears a suspension`() {
        val suspended = primary("purse-creditcard", config(stable = false))
            .withDisabled(DisabledState.MethodUnstable)

        val recomputed = MethodAvailability.recompute(listOf(suspended), isInformationRequest = false, BigDecimal("50.00"))

        assertEquals(DisabledState.MethodUnstable, recomputed.single().disabled)
    }

    @Test
    fun `recompute never clears a partner fatal error but re-evaluates a recoverable one`() {
        val fatal = primary("purse-creditcard", config())
            .withDisabled(DisabledState.PartnerUiFatalError("died"))
        val recoverable = primary("paypal-wallet", config())
            .withDisabled(DisabledState.PartnerUiRecoverableError("blip"))

        val recomputed =
            MethodAvailability.recompute(listOf(fatal, recoverable), isInformationRequest = false, BigDecimal("50.00"))

        assertTrue(recomputed[0].disabled is DisabledState.PartnerUiFatalError)
        assertNull("a recoverable state is recomputable and clears when the amount allows", recomputed[1].disabled)
    }

    // --- in-split exemption (web's `isInSplits`) --------------------------------------------------

    @Test
    fun `a method already in the split is not disabled by the coverage it provides`() {
        val method = primary("purse-creditcard", config(min = BigDecimal("10.00")))

        val recomputed = MethodAvailability.recompute(
            listOf(method),
            isInformationRequest = false,
            primaryReach = BigDecimal.ZERO,
            splitSources = listOf(method),
        )

        assertNull(recomputed.single().disabled)
    }

    @Test
    fun `the max-amount rule applies even to a method in the split`() {
        val method = primary("purse-creditcard", config(max = BigDecimal("10.00")))

        val recomputed = MethodAvailability.recompute(
            listOf(method),
            isInformationRequest = false,
            primaryReach = BigDecimal("50.00"),
            splitSources = listOf(method),
        )

        assertTrue(recomputed.single().disabled is DisabledState.MaxAmountExceeded)
    }
}
