package eu.purse.dropin.session

import eu.purse.dropin.payment.method.DisabledReason
import eu.purse.dropin.payment.method.Method
import eu.purse.dropin.payment.method.MethodConfiguration
import eu.purse.dropin.payment.method.MethodSettings
import eu.purse.dropin.payment.partner.Partner
import eu.purse.dropin.payment.method.PrimaryMethod
import eu.purse.dropin.payment.method.RegistrationMode
import eu.purse.dropin.payment.method.SecondaryMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class PaymentSessionBuilderTest {

    private val expiration = Instant.parse("2026-07-17T12:00:00Z")

    private fun configuration(
        labels: List<String> = emptyList(),
        position: Int? = null,
        registrationMode: RegistrationMode = RegistrationMode.ON_SESSION,
        minAmount: BigDecimal? = null,
        maxAmount: BigDecimal? = null,
    ) = MethodConfiguration(
        stable = true,
        template = null,
        labels = labels,
        position = position,
        vault = null,
        settings = MethodSettings(
            registrationMode = registrationMode,
            saveToken = false,
            saveTokenMode = null,
            directDebit = false,
            paymentMinAmount = minAmount,
            paymentMaxAmount = maxAmount,
            usagePerSessionLimit = null,
            integrationMode = null,
        ),
    )

    private fun session(
        amount: BigDecimal?,
        protocols: Map<Partner, Map<Method, MethodEntry>>,
        informationRequest: Boolean? = null,
    ) = LegacyPaymentSession(
        id = "session-1",
        amount = amount,
        informationRequest = informationRequest,
        currencyCode = "EUR",
        expirationDate = expiration,
        success = null,
        failure = null,
        redirection = null,
        protocols = protocols,
        vault = null,
    )

    @Test
    fun `a method is secondary only when labels contains the literal string secondary`() {
        val secondaryByLabel = configuration(labels = listOf("illicado", "giftcard", "secondary"))
        val templateSaysSecondaryButLabelDoesNot = configuration(labels = listOf("foo"), position = 1).copy(
            template = "secondary",
        )

        val built = PaymentSessionBuilder.build(
            session(
                amount = BigDecimal("10"),
                protocols = mapOf(
                    Partner.Illicado to mapOf(Method.GiftCard to MethodEntry(secondaryByLabel)),
                    Partner.Aci to mapOf(Method.CreditCard to MethodEntry(templateSaysSecondaryButLabelDoesNot)),
                ),
            ),
        )

        val illicado = built.meansOfPayment.single { it.partner == Partner.Illicado }
        val aci = built.meansOfPayment.single { it.partner == Partner.Aci }
        assertTrue(illicado is SecondaryMethod)
        assertTrue("a 'secondary' template without the label must NOT be treated as secondary", aci is PrimaryMethod)
    }

    @Test
    fun `means of payment are sorted by position ascending with undefined positions last`() {
        val built = PaymentSessionBuilder.build(
            session(
                amount = BigDecimal("10"),
                protocols = mapOf(
                    Partner.Paypal to mapOf(Method.Wallet to MethodEntry(configuration(position = 4))),
                    Partner.Illicado to mapOf(Method.GiftCard to MethodEntry(configuration(position = null))),
                    Partner.Purse to mapOf(Method.CreditCard to MethodEntry(configuration(position = 1))),
                    Partner.Adyen to mapOf(Method.GooglePay to MethodEntry(configuration(position = 3))),
                ),
            ),
        )

        assertEquals(
            listOf(Partner.Purse, Partner.Adyen, Partner.Paypal, Partner.Illicado),
            built.meansOfPayment.map { it.partner },
        )
    }

    @Test
    fun `an information_request session keeps only OFF_SESSION methods`() {
        val built = PaymentSessionBuilder.build(
            session(
                amount = null, // absent amount implicitly makes this an information_request
                protocols = mapOf(
                    Partner.Purse to mapOf(
                        Method.CreditCard to MethodEntry(configuration(registrationMode = RegistrationMode.OFF_SESSION)),
                    ),
                    Partner.Paypal to mapOf(
                        Method.Wallet to MethodEntry(configuration(registrationMode = RegistrationMode.ON_SESSION)),
                    ),
                ),
            ),
        )

        assertTrue(built.isInformationRequest)
        assertEquals(listOf(Partner.Purse), built.meansOfPayment.map { it.partner })
    }

    @Test
    fun `a zero amount is treated as an information_request even without the explicit flag`() {
        val built = PaymentSessionBuilder.build(
            session(amount = BigDecimal.ZERO, protocols = emptyMap()),
        )

        assertTrue(built.isInformationRequest)
    }

    @Test
    fun `a non-zero amount is not an information_request by default`() {
        val built = PaymentSessionBuilder.build(
            session(amount = BigDecimal("10"), protocols = emptyMap()),
        )

        assertEquals(false, built.isInformationRequest)
    }

    @Test
    fun `a method below its minimum amount is disabled with MinAmountNotMet`() {
        val built = PaymentSessionBuilder.build(
            session(
                amount = BigDecimal("2.00"),
                protocols = mapOf(
                    Partner.Purse to mapOf(
                        Method.CreditCard to MethodEntry(configuration(minAmount = BigDecimal("5.00"))),
                    ),
                ),
            ),
        )

        val disabled = built.meansOfPayment.single().disabled
        assertTrue(disabled is DisabledReason.MinAmountNotMet)
        assertEquals(BigDecimal("5.00"), (disabled as DisabledReason.MinAmountNotMet).minAmount)
        assertEquals(BigDecimal("2.00"), disabled.restToPay)
    }

    @Test
    fun `a primary method above its maximum amount is disabled with MaxAmountExceeded`() {
        val built = PaymentSessionBuilder.build(
            session(
                amount = BigDecimal("200"),
                protocols = mapOf(
                    Partner.Purse to mapOf(
                        Method.CreditCard to MethodEntry(configuration(maxAmount = BigDecimal("50"))),
                    ),
                ),
            ),
        )

        val disabled = built.meansOfPayment.single().disabled
        assertTrue(disabled is DisabledReason.MaxAmountExceeded)
        assertEquals(BigDecimal("50"), (disabled as DisabledReason.MaxAmountExceeded).maxAmount)
    }

    @Test
    fun `a secondary method above its maximum amount is never disabled`() {
        val built = PaymentSessionBuilder.build(
            session(
                amount = BigDecimal("200"),
                protocols = mapOf(
                    Partner.Illicado to mapOf(
                        Method.GiftCard to MethodEntry(
                            configuration(labels = listOf("secondary"), maxAmount = BigDecimal("50")),
                        ),
                    ),
                ),
            ),
        )

        val disabled = built.meansOfPayment.single().disabled
        assertNull("a secondary method must never be disabled for exceeding its max amount", disabled)
    }

    @Test
    fun `payment fulfilled fires only when amount is exactly zero and not treated as an information_request`() {
        // Deliberately contradicts the implicit "zero amount = information_request" rule via the
        // explicit flag, to isolate the PaymentFulfilled branch in computeDisabledState — not a
        // realistic session on its own.
        val built = PaymentSessionBuilder.build(
            session(
                amount = BigDecimal.ZERO,
                informationRequest = false,
                protocols = mapOf(
                    Partner.Purse to mapOf(Method.CreditCard to MethodEntry(configuration())),
                ),
            ),
        )

        assertEquals(DisabledReason.PaymentFulfilled, built.meansOfPayment.single().disabled)
    }
}
