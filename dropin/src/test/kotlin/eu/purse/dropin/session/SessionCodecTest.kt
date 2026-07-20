package eu.purse.dropin.session

import eu.purse.dropin.payment.method.Method
import eu.purse.dropin.payment.partner.Partner
import eu.purse.dropin.core.environment.PurseEnvironment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Base64

class SessionCodecTest {

    @Test
    fun `decode parses a well-formed widget data blob`() {
        val params = SessionCodec.decode(TestSessions.widgetData())

        assertEquals("key-123", params.apiKey)
        assertEquals("https://api.purse-sandbox.com/payment/v1", params.apiUrl)
        assertEquals(PurseEnvironment.SANDBOX, params.environment)
        assertEquals("entity-1", params.entityId)
        assertEquals("session-123", params.paymentSession.id)
        // BigDecimal.equals() is scale-sensitive; org.json's Double-based tokenizer collapses
        // "4.80" to "4.8" before we ever see it (see JsonExtensions.kt's optBigDecimalOrNull doc),
        // so compare numerically via compareTo rather than asserting an exact scale.
        assertEquals(0, BigDecimal("4.80").compareTo(params.paymentSession.amount))
    }

    @Test
    fun `decode parses the session-level vault tenant id`() {
        val params = SessionCodec.decode(TestSessions.widgetData())

        assertEquals("tenant-abc", params.paymentSession.vault?.vendorFrontData?.purse?.tenantId)
        assertEquals("merchant-xyz", params.paymentSession.vault?.vendorFrontData?.pciProxy?.merchantId)
    }

    @Test
    fun `decode preserves unknown partners and methods instead of dropping them`() {
        val session = """
            {
              "id": "session-456",
              "amount": 10,
              "currency_code": "EUR",
              "expiration_date": "2026-07-17T12:00:00Z",
              "protocols": {
                "some_future_psp": {
                  "some_future_method": {
                    "configurations": { "stable": true, "labels": [], "settings": {} }
                  }
                }
              }
            }
        """.trimIndent()

        val params = SessionCodec.decode(TestSessions.widgetData(paymentSessionJson = session))
        val partner = params.paymentSession.protocols.keys.single()
        val method = params.paymentSession.protocols.values.single().keys.single()

        assertEquals(Partner.Other("some_future_psp"), partner)
        assertEquals(Method.Other("some_future_method"), method)
    }

    @Test
    fun `decode throws for invalid base64`() {
        assertThrows(DropinSessionException.SessionMalformed::class.java) {
            SessionCodec.decode("not valid base64!!")
        }
    }

    @Test
    fun `decode throws for invalid JSON`() {
        val notJson = Base64.getEncoder().encodeToString("not json".toByteArray())
        assertThrows(DropinSessionException.SessionMalformed::class.java) {
            SessionCodec.decode(notJson)
        }
    }

    @Test
    fun `decode throws when payment_session is missing`() {
        val root = JSONObject().apply {
            put("api_key", "k")
            put("api_url", "u")
            put("environment", "sandbox")
            put("entity_id", "e")
        }
        val widgetData = Base64.getEncoder().encodeToString(root.toString().toByteArray())

        val exception = assertThrows(DropinSessionException.SessionMalformed::class.java) {
            SessionCodec.decode(widgetData)
        }
        assertTrue(exception.message!!.contains("payment_session"))
    }

    @Test
    fun `decode throws when id is missing from the session`() {
        val session = """
            { "currency_code": "EUR", "expiration_date": "2026-07-17T12:00:00Z", "protocols": {} }
        """.trimIndent()

        val exception = assertThrows(DropinSessionException.SessionMalformed::class.java) {
            SessionCodec.decode(TestSessions.widgetData(paymentSessionJson = session))
        }
        assertTrue(exception.message!!.contains("id"))
    }

    @Test
    fun `decode throws when expiration_date is missing from the session`() {
        val session = """
            { "id": "session-789", "currency_code": "EUR", "protocols": {} }
        """.trimIndent()

        val exception = assertThrows(DropinSessionException.SessionMalformed::class.java) {
            SessionCodec.decode(TestSessions.widgetData(paymentSessionJson = session))
        }
        assertTrue(exception.message!!.contains("expiration_date"))
    }

    @Test
    fun `decode throws for an unrecognized environment`() {
        assertThrows(DropinSessionException.SessionMalformed::class.java) {
            SessionCodec.decode(TestSessions.widgetData(environment = "not-a-real-tier"))
        }
    }

    @Test
    fun `decode throws when environment is missing`() {
        assertThrows(DropinSessionException.SessionMalformed::class.java) {
            SessionCodec.decode(TestSessions.widgetData(environment = null))
        }
    }

    @Test
    fun `decode does not crash on a session with no vault field at all`() {
        val session = """
            { "id": "session-000", "currency_code": "EUR", "expiration_date": "2026-07-17T12:00:00Z", "protocols": {} }
        """.trimIndent()

        val params = SessionCodec.decode(TestSessions.widgetData(paymentSessionJson = session))

        assertNull(params.paymentSession.vault)
        assertTrue(params.paymentSession.protocols.isEmpty())
    }
}
