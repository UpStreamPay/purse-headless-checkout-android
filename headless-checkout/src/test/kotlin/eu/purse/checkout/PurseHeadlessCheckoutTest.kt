package eu.purse.checkout

import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.core.payment.IntegrationMode
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PluginRegistry
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
    fun `a primary whose form is not complete does not fulfill the payment`() {
        val checkout = checkout()
        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }

        // What CardCoordinator reports the moment its SecureFields form mounts.
        checkout.setFulfilled(card, false)
        checkout.setPrimary(card)

        assertFalse(checkout.isPaymentFulfilled.value)
        assertThrows(PurseCheckoutException.ItemNotFulfilled::class.java) {
            runBlocking { checkout.submitPayment() }
        }

        // …and what it reports once every field validates.
        checkout.setFulfilled(card, true)

        assertTrue(checkout.isPaymentFulfilled.value)
    }

    @Test
    fun `an unfulfilled method that is not in the split does not block`() {
        val checkout = checkout()
        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }

        // The card form was mounted, then the shopper picked another method instead.
        checkout.setFulfilled(card, false)
        checkout.setPrimary(paypal)

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
    fun `a suspended or unreachable method never appears in the public list`() {
        val json = """
            {
              "id": "session-filter", "amount": 4.80, "currency_code": "EUR",
              "expiration_date": "2999-01-01T00:00:00Z",
              "protocols": {
                "purse": {
                  "creditcard": {
                    "configurations": {
                      "stable": true, "labels": ["primary"], "position": 1,
                      "settings": { "registration_mode": "ON_SESSION" }
                    }
                  }
                },
                "klarna": {
                  "installments": {
                    "configurations": {
                      "stable": false, "labels": ["primary"], "position": 2,
                      "settings": { "registration_mode": "ON_SESSION" }
                    }
                  }
                },
                "alma": {
                  "cb3x": {
                    "configurations": {
                      "stable": true, "labels": ["primary"], "position": 3,
                      "settings": { "registration_mode": "ON_SESSION", "payment_min_amount": 50.00 }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val checkout = PurseHeadlessCheckout.init(CheckoutTestSessions.widgetData(json))

        // The suspended (stable=false) and unreachable (min 50 > amount 4.80) methods are filtered.
        assertEquals(listOf("purse-creditcard"), checkout.paymentMethods.value.map { it.id })
        assertEquals(1, checkout.primaryMethods.size)

        // …and a split change never resurrects them (their states are not recomputable).
        checkout.setPrimary(checkout.primaryMethods.single())
        assertEquals(listOf("purse-creditcard"), checkout.paymentMethods.value.map { it.id })
    }

    @Test
    fun `submitPayment fails while no partner-ui plugin is registered`() {
        val checkout = checkout()
        // Every primary this fixture offers now resolves to a shipped plugin, so opt one out to
        // reach the no-plugin path (that's exactly what `disable` is for — see PluginRegistry).
        checkout.setPrimary(checkout.primaryMethods.single { it.partner == Partner.Paypal })
        PluginRegistry.disable(Partner.Paypal, Method.Wallet)

        try {
            assertThrows(PurseCheckoutException.NoPluginRegistered::class.java) {
                runBlocking { checkout.submitPayment() }
            }
        } finally {
            // PluginRegistry is a singleton — leaving this set would leak into every later test.
            PluginRegistry.enable(Partner.Paypal, Method.Wallet)
        }
    }
}
