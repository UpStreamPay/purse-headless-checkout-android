package eu.purse.partnerui.plugins.vaults.purse.creditcard

import eu.purse.checkout.FakePaymentApi
import eu.purse.core.api.InitGuard
import eu.purse.core.api.PaymentApiClient
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PayToken
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPluginTest {

    private val session = LegacyPaymentSession(
        id = "s-1",
        amount = BigDecimal("4.80"),
        informationRequest = false,
        currencyCode = "EUR",
        expirationDate = Instant.parse("2999-01-01T00:00:00Z"),
        success = null,
        failure = null,
        redirection = null,
        protocols = emptyMap(),
        vault = null,
    )

    private fun context(
        collected: Map<String, Any?>,
        token: PayToken? = null,
        apiRoot: String = "https://api.example",
    ) = PartnerUiContext(
        partner = Partner.Purse,
        method = Method.CreditCard,
        session = session,
        amount = BigDecimal("4.80"),
        api = PaymentApiClient("k", apiRoot, "e", "s-1"),
        collected = collected,
        token = token,
    )

    private val browser = JSONObject().put("user_agent", "ua").put("accept_header", "text/html")

    @After
    fun resetInitGuard() = InitGuard.reset()

    @Test
    fun `validateUi refuses an item whose form never produced a vault token`() = runBlocking {
        val plugin = CardPlugin(Partner.Purse)

        assertFalse(plugin.validateUi(context(emptyMap())))
        assertTrue(plugin.validateUi(context(mapOf(CardPlugin.COLLECTED_TOKEN to "vft-1"))))
    }

    @Test
    fun `validateUi passes a saved token that pays without a CVV, form or not`() = runBlocking {
        val plugin = CardPlugin(Partner.Purse)
        val noCvv = PayToken(value = "tok-1", requiresCvv = false)

        assertTrue(plugin.validateUi(context(emptyMap(), token = noCvv)))
    }

    @Test
    fun `validateUi requires the CVV-only vault token for a saved token the PSP wants a CVV for`() = runBlocking {
        val plugin = CardPlugin(Partner.Purse)
        val needsCvv = PayToken(value = "tok-1", requiresCvv = true)

        assertFalse(plugin.validateUi(context(emptyMap(), token = needsCvv)))
        assertTrue(plugin.validateUi(context(mapOf(CardPlugin.COLLECTED_TOKEN to "cvv-vft"), token = needsCvv)))
    }

    // SDK-12569: dalenys refuses a token, and 3-D Secure PSPs open no challenge, without it.
    @Test
    fun `a fresh card sends partner_data browser on validate`() = runBlocking {
        FakePaymentApi().respond("init", """{"status":{"state":"SUCCESS"}}""").use { api ->
            val collected = mapOf(CardPlugin.COLLECTED_TOKEN to "vft-1", CardPlugin.COLLECTED_BROWSER to browser)

            val item = CardPlugin(Partner.Purse).preValidation(context(collected, apiRoot = api.apiRoot))

            val sent = item.toJson().getJSONObject("partner_data").getJSONObject("browser")
            assertEquals("ua", sent.getString("user_agent"))
            assertEquals("text/html", sent.getString("accept_header"))
        }
    }

    @Test
    fun `a saved token sends partner_data browser on validate`() = runBlocking {
        FakePaymentApi().respond("init", """{"status":{"state":"SUCCESS"}}""").use { api ->
            val noCvv = PayToken(value = "tok-1", requiresCvv = false)

            val item = CardPlugin(Partner.Purse).preValidation(
                context(mapOf(CardPlugin.COLLECTED_BROWSER to browser), token = noCvv, apiRoot = api.apiRoot),
            )

            assertEquals("tok-1", item.paymentData.getString("token"))
            assertEquals("ua", item.toJson().getJSONObject("partner_data").getJSONObject("browser").getString("user_agent"))
        }
    }
}
