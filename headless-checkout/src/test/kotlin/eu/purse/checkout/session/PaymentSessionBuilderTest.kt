package eu.purse.checkout.session

import eu.purse.checkout.payment.item.DisabledState
import eu.purse.checkout.payment.item.PaymentToken
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SavedSecondaryToken
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.checkout.payment.secondary.SecondaryTakePolicy
import eu.purse.core.payment.IntegratedToken
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.MethodSettings
import eu.purse.core.payment.Partner
import eu.purse.core.payment.RegistrationMode
import eu.purse.core.payment.TokenSource
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

    // --- saved instruments -------------------------------------------------------------------------

    private fun integratedToken(id: String, uniquenessToken: String? = "9250004781234567890", pinCode: String? = "123") =
        IntegratedToken(
            id = id,
            value = "$id-value",
            uniquenessToken = uniquenessToken,
            pinCode = pinCode,
            expirationDate = "2027-01-01T00:00:00Z",
            favorite = false,
            name = "my card",
            source = TokenSource.MERCHANT,
            createdAt = "2026-01-01T00:00:00Z",
            brandName = null,
            displayToken = "•••• 7890",
            holderName = null,
            maskedPan = null,
            networkTokenAvailable = false,
        )

    @Test
    fun `a secondary method's integrated tokens become saved secondary tokens, a primary's stay payment tokens`() {
        val built = PaymentSessionBuilder.build(
            session(
                amount = BigDecimal("10"),
                protocols = mapOf(
                    Partner.Illicado to mapOf(
                        Method.GiftCard to MethodEntry(
                            configuration(labels = listOf("secondary")),
                            integratedTokens = listOf(integratedToken("gc-1")),
                        ),
                    ),
                    Partner.Purse to mapOf(
                        Method.CreditCard to MethodEntry(
                            configuration(labels = listOf("primary")),
                            integratedTokens = listOf(integratedToken("card-1")),
                        ),
                    ),
                ),
            ),
        )

        val saved = built.meansOfPayment.filterIsInstance<SavedSecondaryToken>().single()
        assertEquals("gc-1", saved.id)
        assertEquals(Partner.Illicado, saved.partner)
        assertEquals("9250004781234567890", saved.pan)
        assertEquals("123", saved.cvv)
        assertEquals("•••• 7890", saved.name)
        assertTrue(saved.isSecondary)
        // No balance until the checkout re-initialises it.
        assertEquals(0, BigDecimal.ZERO.compareTo(saved.balance))
        assertTrue(!saved.balanceResolved)
        // Illicado's shipped config decides the take policy.
        assertEquals(SecondaryTakePolicy.NONE, saved.takePolicy)

        // The primary's token is untouched by the fix — and a secondary's never becomes one.
        val cards = built.meansOfPayment.filterIsInstance<PaymentToken>()
        assertEquals(listOf("card-1"), cards.map { it.id })
        // The methods themselves are still there, one of each.
        assertTrue(built.meansOfPayment.any { it is SecondaryMethod && it.partner == Partner.Illicado })
        assertTrue(built.meansOfPayment.any { it is PrimaryMethod && it.partner == Partner.Purse })
    }

    @Test
    fun `a saved secondary token converts to the splitter's instrument with its saved flag set`() {
        val built = PaymentSessionBuilder.build(
            session(
                amount = BigDecimal("10"),
                protocols = mapOf(
                    Partner.Illicado to mapOf(
                        Method.GiftCard to MethodEntry(
                            configuration(labels = listOf("secondary"), maxAmount = BigDecimal("50")),
                            integratedTokens = listOf(integratedToken("gc-2")),
                        ),
                    ),
                ),
            ),
        )

        val token = built.meansOfPayment.filterIsInstance<SavedSecondaryToken>().single()
            .copy(balance = BigDecimal("8.00"), balanceResolved = true)
            .asSecondaryToken()

        assertEquals("gc-2", token.id)
        assertTrue(token.isSaved)
        assertTrue("a saved card is never re-registered", !token.canBeRegistered)
        assertEquals(0, BigDecimal("8.00").compareTo(token.balance))
        assertEquals(BigDecimal("50"), token.limit)
    }
}
