package eu.purse.googlepay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class GooglePayMapperTest {

    private fun ingenicoInit(withGatewayMerchantId: Boolean = true): JSONObject {
        val logs = JSONObject()
            .put("merchantId", "MID-1")
            .put("merchantName", "Acme")
            .put("countryCode", "FR")
            .put("currencyCode", "EUR")
            .put("clientSessionId", "cs")
            .put("customerId", "cust")
            .put("productId", 320)
            .put("centsAmount", 480)
            .put("localeCode", "fr_FR")
            .put("clientApiUrl", "https://worldline.example")
        if (withGatewayMerchantId) logs.put("gatewayMerchantId", "GMID-1")
        return JSONObject()
            .put("status", JSONObject().put("state", "SUCCESS"))
            .put("plugin_result", JSONObject().put("logs", logs))
    }

    @Test
    fun `ingenico init parses merchant + gateway defaults`() {
        val config = GooglePayMapper.parseInitConfig(ingenicoInit(), "ingenico")

        assertEquals(GooglePayMapper.INGENICO_DEFAULT_GATEWAY, config.gateway)
        assertEquals("GMID-1", config.gatewayMerchantId)
        assertEquals("MID-1", config.merchantId)
        assertEquals("Acme", config.merchantName)
        assertEquals(listOf("MASTERCARD", "VISA"), config.allowedCardNetworks)
    }

    @Test
    fun `ingenico gatewayMerchantId falls back to UPSTREAMPAY`() {
        val config = GooglePayMapper.parseInitConfig(ingenicoInit(withGatewayMerchantId = false), "ingenico")
        assertEquals("UPSTREAMPAY", config.gatewayMerchantId)
    }

    @Test
    fun `payment data request carries the gateway tokenization spec and amount`() {
        val config = GooglePayMapper.parseInitConfig(ingenicoInit(), "ingenico")
        val request = GooglePayMapper.buildPaymentDataRequestJson(config, "EUR", BigDecimal("4.80"))

        val tokenization = request.getJSONArray("allowedPaymentMethods")
            .getJSONObject(0).getJSONObject("tokenizationSpecification").getJSONObject("parameters")
        assertEquals(GooglePayMapper.INGENICO_DEFAULT_GATEWAY, tokenization.getString("gateway"))
        assertEquals("GMID-1", tokenization.getString("gatewayMerchantId"))

        val transaction = request.getJSONObject("transactionInfo")
        assertEquals("4.80", transaction.getString("totalPrice"))
        assertEquals("EUR", transaction.getString("currencyCode"))
    }

    @Test
    fun `validate partner_data wraps the token under session`() {
        val partnerData = GooglePayMapper.buildValidatePartnerData("tok-123")
        val session = JSONObject(partnerData.getString("session"))
        val paymentMethod = session.getJSONObject("paymentMethod")
        assertEquals("googlepay", paymentMethod.getString("type"))
        assertEquals("tok-123", paymentMethod.getString("googlePayToken"))
    }

    @Test
    fun `validate payment_data carries the selected network`() {
        val paymentData = GooglePayMapper.buildValidatePaymentData("MASTERCARD")
        assertEquals("MASTERCARD", paymentData.getJSONObject("card").getString("selected_network"))
    }

    @Test
    fun `an unsuccessful init throws`() {
        val failed = JSONObject().put("status", JSONObject().put("state", "FAILED"))
        try {
            GooglePayMapper.parseInitConfig(failed, "ingenico")
            assertTrue("expected a PaymentApiException", false)
        } catch (e: Exception) {
            assertTrue(e.message?.contains("not successful") == true)
        }
    }
}
