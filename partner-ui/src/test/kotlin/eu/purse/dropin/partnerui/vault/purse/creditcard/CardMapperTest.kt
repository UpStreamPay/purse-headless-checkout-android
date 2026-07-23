package eu.purse.dropin.partnerui.vault.purse.creditcard

import eu.purse.dropin.core.api.PaymentApiException
import eu.purse.dropin.partnerui.redirect.RedirectAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CardMapperTest {

    @Test
    fun `init payload carries partner, method, amount, and vault_vendor`() {
        val payload = CardMapper.buildInitPayload(BigDecimal("42.50"), partnerWireValue = "purse")

        assertEquals("purse", payload.getString("partner"))
        assertEquals("creditcard", payload.getString("method"))
        // amount is stored as a JSON number, not a string — see GooglePayMapperTest for the same
        // convention; it round-trips as the BigDecimal itself.
        assertEquals(BigDecimal("42.50"), payload.get("amount"))
        assertEquals("purse", payload.getJSONObject("payment_data").getString("vault_vendor"))
    }

    @Test
    fun `parseInit reads step_id`() {
        val response = JSONObject(
            """{ "step_id": 7, "status": { "state": "SUCCESS" } }""",
        )

        val init = CardMapper.parseInit(response)

        assertEquals("7", init.stepId)
    }

    @Test
    fun `parseInit tolerates a missing status and step_id`() {
        val init = CardMapper.parseInit(JSONObject("{}"))

        assertNull(init.stepId)
    }

    @Test(expected = PaymentApiException::class)
    fun `parseInit throws when init is not successful`() {
        CardMapper.parseInit(JSONObject("""{ "status": { "state": "FAILED" } }"""))
    }

    @Test
    fun `validate payload carries the vault token as a top-level payment_data field, not nested under card`() {
        val payload = CardMapper.buildValidatePayload(
            amount = BigDecimal("42.50"),
            partnerWireValue = "purse",
            vaultVendorTokenId = "VAULT-TOKEN-123",
            bin = "424242",
            selectedNetwork = "VISA",
            stepId = "7",
        )

        assertEquals(1, payload.length())
        val item = payload.getJSONObject(0)
        assertEquals("purse", item.getString("partner"))
        assertEquals("creditcard", item.getString("method"))
        assertEquals(BigDecimal("42.50"), item.get("amount"))

        val paymentData = item.getJSONObject("payment_data")
        assertEquals("VAULT-TOKEN-123", paymentData.getString("vault_vendor_token_id"))
        assertEquals("purse", paymentData.getString("vault_vendor"))
        assertFalse(paymentData.has("vault_token")) // not nested under a differently-named field either
        assertFalse(paymentData.getJSONObject("card").has("vault_token"))
        assertEquals("424242", paymentData.getJSONObject("card").getString("bin"))
        assertEquals("VISA", paymentData.getJSONObject("card").getString("selected_network"))
        assertEquals(7L, item.getLong("step_id"))
    }

    @Test
    fun `validate payload omits optional card metadata and nulls step_id when absent`() {
        val payload = CardMapper.buildValidatePayload(
            amount = BigDecimal.TEN,
            partnerWireValue = "purse",
            vaultVendorTokenId = "VAULT-TOKEN-9",
            bin = null,
            selectedNetwork = null,
            stepId = null,
        )

        val item = payload.getJSONObject(0)
        assertTrue(item.getJSONObject("payment_data").getJSONObject("card").length() == 0)
        assertTrue(item.isNull("step_id"))
    }

    @Test
    fun `parseValidation maps a SUCCESS global_status to success`() {
        val result = CardMapper.parseValidation(
            JSONObject("""{ "global_status": "SUCCESS", "results": [] }"""),
            partnerWireValue = "purse",
        )

        assertTrue(result.isSuccess)
        assertEquals("SUCCESS", result.globalStatus)
        assertNull(result.redirectAction)
    }

    @Test
    fun `parseValidation surfaces a chained redirection url and non-success status`() {
        val result = CardMapper.parseValidation(
            JSONObject(
                """{ "global_status": "WAITING", "redirection": { "url": "https://3ds.example/step" } }""",
            ),
            partnerWireValue = "purse",
        )

        assertFalse(result.isSuccess)
        assertEquals(RedirectAction.Get("https://3ds.example/step"), result.redirectAction)
    }
}
