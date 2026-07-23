package eu.purse.dropin.partnerui.generic.googlepay

import eu.purse.dropin.core.api.PaymentApiException
import eu.purse.dropin.partnerui.redirect.RedirectAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class GooglePayMapperTest {

    @Test
    fun `init payload carries partner, method, amount, and empty payment sections`() {
        val payload = GooglePayMapper.buildInitPayload(BigDecimal("42.50"), "adyen")

        assertEquals("adyen", payload.getString("partner"))
        assertEquals("googlepay", payload.getString("method"))
        // amount is stored as a JSON number (not a string) — see session-and-protocols.md's
        // `"amount": 480` example — so it round-trips as the BigDecimal itself, not a String.
        assertEquals(BigDecimal("42.50"), payload.get("amount"))
        assertEquals(0, payload.getJSONObject("partner_data").length())
        assertEquals(0, payload.getJSONObject("payment_data").length())
        assertEquals(0, payload.getJSONObject("payment_preferences").length())
    }

    @Test
    fun `parseInitConfig (adyen) reads brands, gatewayMerchantId, and merchantId from logs payment_methods`() {
        val response = JSONObject(
            """
            {
              "status": { "state": "SUCCESS" },
              "plugin_result": {
                "logs": {
                  "payment_methods": "[{\"brands\":[\"VISA\",\"MASTERCARD\"],\"configuration\":{\"gatewayMerchantId\":\"TestMerchantAccount\"},\"merchantId\":\"12345\",\"gatewayMerchantId\":\"Test Merchant\"}]"
                }
              }
            }
            """.trimIndent(),
        )

        val config = GooglePayMapper.parseInitConfig(response, "adyen")

        assertEquals("adyen", config.gateway)
        assertEquals("TestMerchantAccount", config.gatewayMerchantId)
        assertEquals("12345", config.merchantId)
        assertEquals("Test Merchant", config.merchantName)
        assertEquals(listOf("VISA", "MASTERCARD"), config.allowedCardNetworks)
    }

    @Test
    fun `parseInitConfig (default) reads logs payment_method_configuration for a non-adyen partner`() {
        val response = JSONObject(
            """
            {
              "status": { "state": "SUCCESS" },
              "plugin_result": {
                "logs": {
                  "payment_method_configuration": "{\"brands\":[\"VISA\"],\"configuration\":{\"gateway\":\"example\",\"gatewayMerchantId\":\"exampleGatewayMerchantId\",\"merchantId\":\"m-1\",\"merchantName\":\"Example Merchant\"}}"
                }
              }
            }
            """.trimIndent(),
        )

        val config = GooglePayMapper.parseInitConfig(response, "ingenico")

        assertEquals("example", config.gateway)
        assertEquals("exampleGatewayMerchantId", config.gatewayMerchantId)
        assertEquals("m-1", config.merchantId)
        assertEquals("Example Merchant", config.merchantName)
        assertEquals(listOf("VISA"), config.allowedCardNetworks)
    }

    @Test(expected = PaymentApiException::class)
    fun `parseInitConfig throws when init is not successful`() {
        val response = JSONObject("""{ "status": { "state": "FAILED", "description": "nope" } }""")
        GooglePayMapper.parseInitConfig(response, "adyen")
    }

    @Test(expected = PaymentApiException::class)
    fun `parseInitConfig throws when adyen payment_methods is absent`() {
        val response = JSONObject("""{ "status": { "state": "SUCCESS" }, "plugin_result": { "logs": {} } }""")
        GooglePayMapper.parseInitConfig(response, "adyen")
    }

    @Test(expected = PaymentApiException::class)
    fun `parseInitConfig throws when adyen payment_methods is an empty array`() {
        val response = JSONObject(
            """{ "status": { "state": "SUCCESS" }, "plugin_result": { "logs": { "payment_methods": "[]" } } }""",
        )
        GooglePayMapper.parseInitConfig(response, "adyen")
    }

    @Test
    fun `buildIsReadyToPayJson embeds allowed auth methods and card networks`() {
        val config = GooglePayConfig(
            gateway = "adyen",
            gatewayMerchantId = "TestMerchantAccount",
            merchantId = null,
            merchantName = null,
            allowedCardNetworks = listOf("VISA", "MASTERCARD"),
        )

        val json = GooglePayMapper.buildIsReadyToPayJson(config)

        assertEquals(2, json.getInt("apiVersion"))
        val method = json.getJSONArray("allowedPaymentMethods").getJSONObject(0)
        assertEquals("CARD", method.getString("type"))
        val parameters = method.getJSONObject("parameters")
        assertEquals(listOf("PAN_ONLY", "CRYPTOGRAM_3DS"), parameters.getJSONArray("allowedAuthMethods").toStringList())
        assertEquals(listOf("VISA", "MASTERCARD"), parameters.getJSONArray("allowedCardNetworks").toStringList())
        assertTrue(!method.has("tokenizationSpecification"))
    }

    @Test
    fun `buildPaymentDataRequestJson embeds gateway tokenization, transaction info, and merchant info`() {
        val config = GooglePayConfig(
            gateway = "adyen",
            gatewayMerchantId = "TestMerchantAccount",
            merchantId = "m-1",
            merchantName = "Test Merchant",
            allowedCardNetworks = listOf("VISA"),
        )

        val json = GooglePayMapper.buildPaymentDataRequestJson(config, currencyCode = "EUR", amount = BigDecimal("4.80"))

        val method = json.getJSONArray("allowedPaymentMethods").getJSONObject(0)
        val tokenization = method.getJSONObject("tokenizationSpecification")
        assertEquals("PAYMENT_GATEWAY", tokenization.getString("type"))
        assertEquals("adyen", tokenization.getJSONObject("parameters").getString("gateway"))
        assertEquals("TestMerchantAccount", tokenization.getJSONObject("parameters").getString("gatewayMerchantId"))

        val transactionInfo = json.getJSONObject("transactionInfo")
        assertEquals("EUR", transactionInfo.getString("currencyCode"))
        // totalPrice is a String field on the wire, built from amount.toPlainString() — scale is
        // preserved (4.80, not 4.8).
        assertEquals("4.80", transactionInfo.getString("totalPrice"))
        assertEquals("FINAL", transactionInfo.getString("totalPriceStatus"))
        assertEquals("FR", transactionInfo.getString("countryCode"))

        val merchantInfo = json.getJSONObject("merchantInfo")
        assertEquals("m-1", merchantInfo.getString("merchantId"))
        assertEquals("Test Merchant", merchantInfo.getString("merchantName"))
    }

    @Test
    fun `buildPaymentDataRequestJson falls back to a default card network list when none are configured`() {
        val config = GooglePayConfig(
            gateway = "adyen",
            gatewayMerchantId = "TestMerchantAccount",
            merchantId = null,
            merchantName = null,
            allowedCardNetworks = emptyList(),
        )

        val json = GooglePayMapper.buildPaymentDataRequestJson(config, currencyCode = "EUR", amount = BigDecimal.TEN)

        val parameters = json.getJSONArray("allowedPaymentMethods").getJSONObject(0).getJSONObject("parameters")
        assertEquals(listOf("VISA", "MASTERCARD", "AMEX"), parameters.getJSONArray("allowedCardNetworks").toStringList())
        assertEquals(0, json.getJSONObject("merchantInfo").length())
    }

    @Test
    fun `validate payload carries the google pay token and selected network`() {
        val payload = GooglePayMapper.buildValidatePayload(
            amount = BigDecimal("42.50"),
            partnerWireValue = "adyen",
            googlePayToken = "TOKEN-123",
            selectedNetwork = "VISA",
        )

        assertEquals(1, payload.length())
        val item = payload.getJSONObject(0)
        assertEquals("adyen", item.getString("partner"))
        assertEquals("googlepay", item.getString("method"))
        assertEquals(BigDecimal("42.50"), item.get("amount"))
        assertTrue(item.isNull("step_id"))

        val session = JSONObject(item.getJSONObject("partner_data").getString("session"))
        assertEquals("googlepay", session.getJSONObject("paymentMethod").getString("type"))
        assertEquals("TOKEN-123", session.getJSONObject("paymentMethod").getString("googlePayToken"))

        assertEquals("VISA", item.getJSONObject("payment_data").getJSONObject("card").getString("selected_network"))
    }

    @Test
    fun `validate payload omits selected_network when absent`() {
        val payload = GooglePayMapper.buildValidatePayload(
            amount = BigDecimal.TEN,
            partnerWireValue = "adyen",
            googlePayToken = "TOKEN-123",
            selectedNetwork = null,
        )

        val card = payload.getJSONObject(0).getJSONObject("payment_data").getJSONObject("card")
        assertFalse(card.has("selected_network"))
    }

    @Test
    fun `parseValidation maps a SUCCESS global_status to success`() {
        val result = GooglePayMapper.parseValidation(
            JSONObject("""{ "global_status": "SUCCESS", "results": [] }"""),
            partnerWireValue = "adyen",
        )

        assertTrue(result.isSuccess)
        assertEquals("SUCCESS", result.globalStatus)
        assertNull(result.redirectAction)
    }

    @Test
    fun `parseValidation surfaces a chained redirection url and non-success status`() {
        val result = GooglePayMapper.parseValidation(
            JSONObject(
                """{ "global_status": "PENDING", "redirection": { "url": "https://3ds.example/step" } }""",
            ),
            partnerWireValue = "adyen",
        )

        assertFalse(result.isSuccess)
        assertEquals(RedirectAction.Get("https://3ds.example/step"), result.redirectAction)
    }

    private fun org.json.JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
}
