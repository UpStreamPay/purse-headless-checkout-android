package eu.purse.dropin.partnerui.partner.paypal.wallet

import eu.purse.dropin.core.api.PaymentApiException
import eu.purse.dropin.partnerui.redirect.RedirectAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class PaypalWalletMapperTest {

    @Test
    fun `init payload carries partner, method, amount, and pay-now preferences`() {
        val payload = PaypalWalletMapper.buildInitPayload(BigDecimal("42.50"))

        assertEquals("paypal", payload.getString("partner"))
        assertEquals("wallet", payload.getString("method"))
        assertEquals("42.5", payload.getString("amount"))
        assertEquals("none", payload.getJSONObject("payment_data").getString("vault_vendor"))
        assertFalse(payload.getJSONObject("payment_preferences").getBoolean("registration"))
    }

    @Test
    fun `parseInit reads order_id and step_id`() {
        val response = JSONObject(
            """
            {
              "step_id": 42,
              "status": { "state": "SUCCESS" },
              "plugin_result": { "logs": { "order_id": "ORDER-123" } }
            }
            """.trimIndent(),
        )

        val order = PaypalWalletMapper.parseInit(response)

        assertEquals("ORDER-123", order.orderId)
        assertEquals("42", order.stepId)
    }

    @Test
    fun `parseInit tolerates a missing status but requires an order_id`() {
        val response = JSONObject(
            """{ "plugin_result": { "logs": { "order_id": "ORDER-9" } } }""",
        )

        val order = PaypalWalletMapper.parseInit(response)

        assertEquals("ORDER-9", order.orderId)
        assertNull(order.stepId)
    }

    @Test(expected = PaymentApiException::class)
    fun `parseInit throws when init is not successful`() {
        val response = JSONObject(
            """{ "status": { "state": "FAILED", "description": "nope" } }""",
        )
        PaypalWalletMapper.parseInit(response)
    }

    @Test(expected = PaymentApiException::class)
    fun `parseInit throws when order_id is absent`() {
        val response = JSONObject(
            """{ "status": { "state": "SUCCESS" }, "plugin_result": { "logs": {} } }""",
        )
        PaypalWalletMapper.parseInit(response)
    }

    @Test
    fun `validate payload is a single-item array carrying the step_id`() {
        val payload = PaypalWalletMapper.buildValidatePayload(BigDecimal("42.50"), stepId = "42")

        assertEquals(1, payload.length())
        val item = payload.getJSONObject(0)
        assertEquals("paypal", item.getString("partner"))
        assertEquals("wallet", item.getString("method"))
        assertEquals("42.5", item.getString("amount"))
        assertEquals(42L, item.getLong("step_id"))
    }

    @Test
    fun `validate payload nulls the step_id when absent`() {
        val payload = PaypalWalletMapper.buildValidatePayload(BigDecimal.TEN, stepId = null)

        assertTrue(payload.getJSONObject(0).isNull("step_id"))
    }

    @Test
    fun `parseValidation maps a SUCCESS global_status to success`() {
        val result = PaypalWalletMapper.parseValidation(
            JSONObject("""{ "global_status": "SUCCESS", "results": [] }"""),
        )

        assertTrue(result.isSuccess)
        assertEquals("SUCCESS", result.globalStatus)
        assertNull(result.redirectAction)
    }

    @Test
    fun `parseValidation surfaces a chained redirection url and non-success status`() {
        val result = PaypalWalletMapper.parseValidation(
            JSONObject(
                """{ "global_status": "PENDING", "redirection": { "url": "https://3ds.example/step" } }""",
            ),
        )

        assertFalse(result.isSuccess)
        assertEquals(RedirectAction.Get("https://3ds.example/step"), result.redirectAction)
    }
}
