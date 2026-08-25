package eu.purse.checkout.session

import eu.purse.checkout.payment.item.DisabledState
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.MethodSettings
import eu.purse.core.payment.Partner
import eu.purse.core.payment.RegistrationMode
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.core.session.MethodEntry
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
        stable: Boolean = true,
    ) = MethodConfiguration(
        stable = stable,
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
        val built = PaymentSessionBuilder.build(session(amount = BigDecimal.ZERO, protocols = emptyMap()))
        assertTrue(built.isInformationRequest)
    }

    @Test
    fun `a non-zero amount is not an information_request by default`() {
        val built = PaymentSessionBuilder.build(session(amount = BigDecimal("10"), protocols = emptyMap()))
        assertEquals(false, built.isInformationRequest)
    }

    @Test
    fun `a minimum above the full order amount is AmountConditionUnreachable at build time`() {
        // MIN_AMOUNT_NOT_MET is reserved for the live recompute (the *remaining* amount fell below
        // the minimum); at build time an unreachable minimum is permanent and filtered — web's
        // `buildDisabledWritable`.
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
        assertTrue(disabled is DisabledState.AmountConditionUnreachable)
        assertEquals(BigDecimal("5.00"), (disabled as DisabledState.AmountConditionUnreachable).minAmount)
        assertEquals(BigDecimal("2.00"), disabled.orderAmount)
    }

    @Test
    fun `a suspended method carries MethodUnstable at build time`() {
        val built = PaymentSessionBuilder.build(
            session(
                amount = BigDecimal("10.00"),
                protocols = mapOf(
                    Partner.Purse to mapOf(
                        Method.CreditCard to MethodEntry(configuration(stable = false)),
                    ),
                ),
            ),
        )

        assertEquals(DisabledState.MethodUnstable, built.meansOfPayment.single().disabled)
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
        assertTrue(disabled is DisabledState.MaxAmountExceeded)
        assertEquals(BigDecimal("50"), (disabled as DisabledState.MaxAmountExceeded).maxAmount)
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

        assertNull(
            "a secondary method must never be disabled for exceeding its max amount",
            built.meansOfPayment.single().disabled,
        )
    }

    @Test
    fun `payment fulfilled fires only when amount is exactly zero and not treated as an information_request`() {
        val built = PaymentSessionBuilder.build(
            session(
                amount = BigDecimal.ZERO,
                informationRequest = false,
                protocols = mapOf(Partner.Purse to mapOf(Method.CreditCard to MethodEntry(configuration()))),
            ),
        )

        assertEquals(DisabledState.PaymentFulfilled, built.meansOfPayment.single().disabled)
    }
}
