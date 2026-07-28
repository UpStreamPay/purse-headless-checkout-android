package eu.purse.checkout

import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.core.payment.IntegrationMode
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class PurseHeadlessCheckoutTest {

    private fun checkout() = PurseHeadlessCheckout.init(CheckoutTestSessions.widgetData())

    @Test
    fun `init decodes the session and exposes its methods and split`() {
        val checkout = checkout()

        assertEquals("session-123", checkout.currentSessionId)
        assertEquals("EUR", checkout.currency)
        assertEquals(0, BigDecimal("4.80").compareTo(checkout.session.amount))
        assertEquals("tenant-abc", checkout.session.vaultsConfiguration?.purse?.tenantId)

        // 3 primaries (purse cc, paypal wallet, adyen googlepay) + 1 secondary (illicado).
        assertEquals(3, checkout.primaryMethods.size)
        assertEquals(1, checkout.secondaryMethods.size)

        // Not yet fulfilled: full amount owed, no primary chosen.
        assertFalse(checkout.isPaymentFulfilled.value)
        assertEquals(0, BigDecimal("4.80").compareTo(checkout.remainingAmountToPay.value))
    }

    @Test
    fun `merchant_specifics integration_mode and vault brands surface on the primary`() {
        val checkout = checkout()

        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }
        assertEquals(IntegrationMode.POPUP, paypal.integrationType)

        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }
        assertTrue(card.supportedBrands.isNotEmpty())
    }

    @Test
    fun `choosing a primary fulfills the payment`() {
        val checkout = checkout()

        checkout.setPrimary(checkout.primaryMethods.single { it.method == Method.CreditCard })

        assertTrue(checkout.isPaymentFulfilled.value)
    }

    @Test
    fun `an applied secondary token covering the amount fulfills without a primary`() {
        val checkout = checkout()

        val giftCard = checkout.secondaryMethods.single()
        // A token would normally come from getSecondaryToken (a /init network call); build one
        // directly here to exercise the split path without the network.
        val token = SecondaryToken(
            id = "illicado-giftcard-7890",
            partner = giftCard.partner,
            method = giftCard.method,
            pan = "9250004781234567890",
            cvv = "123",
            name = "gift",
            balance = BigDecimal("10.00"),
        )
        checkout.applySecondary(token)

        assertTrue(checkout.isPaymentFulfilled.value)
        assertEquals(0, BigDecimal.ZERO.compareTo(checkout.remainingAmountToPay.value))
        assertEquals(listOf(token), checkout.appliedSecondaries)
    }

    @Test
    fun `submitPayment fails while no partner-ui plugin is registered`() {
        val checkout = checkout()
        // PayPal wallet has no plugin yet (unlike the purse-vault card primary, which now resolves
        // to CardPlugin by default — see PluginRegistry's init block).
        checkout.setPrimary(checkout.primaryMethods.single { it.partner == Partner.Paypal })

        assertThrows(PurseCheckoutException.NoPluginRegistered::class.java) {
            runBlocking { checkout.submitPayment() }
        }
    }
}
