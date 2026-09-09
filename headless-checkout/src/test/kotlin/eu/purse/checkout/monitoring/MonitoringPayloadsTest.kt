package eu.purse.checkout.monitoring

import eu.purse.checkout.PurseCheckoutException
import eu.purse.core.api.PaymentApiException
import eu.purse.core.error.PurseErrorCode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringPayloadsTest {

    private val response = JSONObject(
        """
        {
          "global_status": "ERROR",
          "redirection": null,
          "results": [
            { "partner": "illicado", "method": "giftcard", "amount": 2.00, "id": "tx-1",
              "status": { "state": "SUCCESS", "code": "OK" }, "plugin_result": { "status": "APPLIED" } },
            { "partner": "purse", "method": "creditcard", "amount": 2.80, "id": "tx-2",
              "status": { "state": "ERROR", "code": "TECHNICAL_ISSUE_TO_CONTACT_PARTNER", "description": "${"x".repeat(300)}" },
              "plugin_result": { "status": "TIMEOUT", "off_session_data": { "secret": "never" } } },
            "not an object",
            { "partner": "purse", "method": "creditcard" }
          ]
        }
        """,
    )

    @Test
    fun `an attempt keeps method, partner, status and the partner status — nothing else`() {
        val attempts = summarizeValidationResults(response)

        assertEquals(3, attempts.length())
        val failed = attempts.getJSONObject(1)
        assertEquals(setOf("method", "partner", "status", "plugin_status"), failed.keys().asSequence().toSet())
        assertEquals("TIMEOUT", failed.getString("plugin_status"))
        val status = failed.getJSONObject("status")
        assertEquals("ERROR", status.getString("state"))
        assertEquals("TECHNICAL_ISSUE_TO_CONTACT_PARTNER", status.getString("code"))
        assertEquals("description is the one free-text field, capped", 200, status.getString("description").length)
        assertFalse("no amount, no transaction id", attempts.toString().contains("tx-") || attempts.toString().contains("2.8"))
        assertFalse("plugin_result travels as its status only", attempts.toString().contains("secret"))
        // A bare attempt still yields its identity and an empty status block.
        assertEquals(0, attempts.getJSONObject(2).getJSONObject("status").length())
    }

    @Test
    fun `onlyFailures drops the legs that succeeded`() {
        val failures = summarizeValidationResults(response, onlyFailures = true)

        assertEquals(2, failures.length())
        assertEquals("creditcard", failures.getJSONObject(0).getString("method"))
    }

    @Test
    fun `at most five attempts, and a response without results yields none`() {
        val many = JSONObject().put("results", JSONArray().apply { repeat(9) { put(JSONObject().put("method", "m$it")) } })

        assertEquals(5, summarizeValidationResults(many).length())
        assertEquals(0, summarizeValidationResults(null).length())
        assertEquals(0, summarizeValidationResults(JSONObject().put("results", "nope")).length())
    }

    @Test
    fun `an error payload names the code and the failing API call, never the response body`() {
        val api = PaymentApiException(
            "Payment API POST /validate failed: HTTP 502 (PARTNER_DOWN)",
            status = 502,
            errorCode = "PARTNER_DOWN",
            path = "/validate",
        )
        val wrapped = PurseCheckoutException.ValidateFailed("validate call failed: ${api.message}", api)

        val payload = errorPayload(wrapped, "boom", "tokenId" to "tok-1")

        assertEquals("boom", payload.getString("message"))
        assertEquals(PurseErrorCode.VALIDATE_FAILED.name, payload.getString("code"))
        assertEquals("/validate", payload.getString("api_path"))
        assertEquals(502, payload.getInt("api_status"))
        assertEquals("PARTNER_DOWN", payload.getString("api_error"))
        assertFalse("a 502 is an answer", payload.has("outcome_unknown"))
        assertEquals("tok-1", payload.getString("tokenId"))
        assertTrue(payload.getString("stack").length <= 1_000)
    }

    @Test
    fun `a transport failure is flagged outcome_unknown and a long message is capped`() {
        val payload = errorPayload(PaymentApiException("m".repeat(900), java.io.IOException("reset"), path = "/validate"))

        assertTrue(payload.getBoolean("outcome_unknown"))
        assertEquals(500, payload.getString("message").length)
        assertEquals(PurseErrorCode.API_ERROR.name, payload.getString("code"))
        assertFalse(payload.has("api_status"))
    }
}
