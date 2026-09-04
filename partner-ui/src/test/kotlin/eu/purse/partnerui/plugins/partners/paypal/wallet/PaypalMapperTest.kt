package eu.purse.partnerui.plugins.partners.paypal.wallet

import eu.purse.core.api.PaymentApiException
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.MethodSettings
import eu.purse.core.payment.Partner
import eu.purse.core.payment.RegistrationMode
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.core.session.MethodEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class PaypalMapperTest {

    private fun sessionWith(clientId: String?) = LegacyPaymentSession(
        id = "s-1",
        amount = BigDecimal("4.80"),
        informationRequest = false,
        currencyCode = "EUR",
        expirationDate = Instant.parse("2999-01-01T00:00:00Z"),
        success = null,
        failure = null,
        redirection = null,
        protocols = mapOf(
            Partner.Paypal to mapOf(
                Method.Wallet to MethodEntry(
                    MethodConfiguration(
                        stable = true,
                        template = null,
                        labels = emptyList(),
                        position = 1,
                        vault = null,
                        settings = MethodSettings(
                            registrationMode = RegistrationMode.ON_SESSION,
                            saveToken = false,
                            saveTokenMode = null,
                            directDebit = false,
                            paymentMinAmount = null,
                            paymentMaxAmount = null,
                            usagePerSessionLimit = null,
                            integrationMode = null,
                            merchantClientId = clientId,
                        ),
                    ),
                ),
            ),
        ),
        vault = null,
    )

    @Test
    fun `init payload carries partner, method, amount, and pay-now preferences`() {
        val payload = PaypalMapper.buildInitPayload(BigDecimal("42.50"), Partner.Paypal.wireValue)

        assertEquals("paypal", payload.getString("partner"))
        assertEquals("wallet", payload.getString("method"))
        // Stored as the BigDecimal itself — org.json serializes it unquoted on the wire.
        assertEquals(BigDecimal("42.50"), payload.get("amount"))
        assertEquals("none", payload.getJSONObject("payment_data").getString("vault_vendor"))
        assertFalse(payload.getJSONObject("payment_preferences").getBoolean("registration"))
    }

    @Test
    fun `parseInit reads the order id off plugin_result logs`() {
        val response = JSONObject(
            """
            {
              "step_id": 42,
              "status": { "state": "SUCCESS" },
              "plugin_result": { "logs": { "order_id": "ORDER-123" } }
            }
            """.trimIndent(),
        )

        assertEquals("ORDER-123", PaypalMapper.parseInit(response))
        assertEquals(42, PaypalMapper.extractStepId(response))
    }

    @Test
    fun `parseInit tolerates a missing status and extractStepId a missing step_id`() {
        val response = JSONObject("""{ "plugin_result": { "logs": { "order_id": "ORDER-9" } } }""")

        assertEquals("ORDER-9", PaypalMapper.parseInit(response))
        assertNull(PaypalMapper.extractStepId(response))
    }

    @Test
    fun `parseInit falls back to the flatter order_id shapes`() {
        assertEquals(
            "ORDER-P",
            PaypalMapper.parseInit(JSONObject("""{ "plugin_result": { "order_id": "ORDER-P" } }""")),
        )
        assertEquals("ORDER-T", PaypalMapper.parseInit(JSONObject("""{ "order_id": "ORDER-T" }""")))
    }

    @Test(expected = PaymentApiException::class)
    fun `parseInit throws when init is not successful`() {
        PaypalMapper.parseInit(JSONObject("""{ "status": { "state": "FAILED", "description": "nope" } }"""))
    }

    @Test(expected = PaymentApiException::class)
    fun `parseInit throws when order_id is absent`() {
        PaypalMapper.parseInit(
            JSONObject("""{ "status": { "state": "SUCCESS" }, "plugin_result": { "logs": {} } }"""),
        )
    }

    @Test
    fun `ensureInitSucceeded accepts a successful init that carries no order_id`() {
        // The saved-token /init creates no order for the buyer to approve — only its status matters.
        PaypalMapper.ensureInitSucceeded(
            JSONObject("""{ "status": { "state": "SUCCESS" }, "step_id": 5, "plugin_result": { "logs": {} } }"""),
        )
        PaypalMapper.ensureInitSucceeded(JSONObject("""{ "step_id": 5 }"""))
    }

    @Test(expected = PaymentApiException::class)
    fun `ensureInitSucceeded throws when init is not successful`() {
        PaypalMapper.ensureInitSucceeded(JSONObject("""{ "status": { "state": "FAILED", "description": "nope" } }"""))
    }

    @Test
    fun `isApprovalUrl catches the approval URL a real validate response echoed back`() {
        // Verbatim from a device run: /validate answered global_status=SUCCESS *and* this URL — the
        // very order the buyer had just approved. Following it re-opened PayPal and booked a second
        // payment step, so PaypalCoordinator drops it.
        assertTrue(
            PaypalMapper.isApprovalUrl("https://www.sandbox.paypal.com/checkoutnow?token=0RH99714TE857350U"),
        )
        assertTrue(PaypalMapper.isApprovalUrl("https://www.paypal.com/checkoutnow?token=ABC"))
        assertTrue(PaypalMapper.isApprovalUrl("https://sandbox.paypal.com/agreements/approve?ba_token=X"))
    }

    @Test
    fun `isApprovalUrl leaves a genuine third-party redirect alone`() {
        // A chained ACS / 3-D Secure hop is never PayPal-hosted, so it must still be followed.
        assertFalse(PaypalMapper.isApprovalUrl("https://acs.bank.example/3ds/challenge?id=1"))
        assertFalse(PaypalMapper.isApprovalUrl("https://connect.sandbox.fintecture.com/v2/abc"))
    }

    @Test
    fun `parseSessionConfig reads merchant_specifics client_id off the session`() {
        val config = PaypalMapper.parseSessionConfig(sessionWith("client-abc"), Partner.Paypal, Method.Wallet)

        assertEquals("client-abc", config.clientId)
    }

    @Test(expected = PaymentApiException::class)
    fun `parseSessionConfig throws when the session carries no client_id`() {
        PaypalMapper.parseSessionConfig(sessionWith(null), Partner.Paypal, Method.Wallet)
    }

    @Test(expected = PaymentApiException::class)
    fun `parseSessionConfig throws when the session does not offer the method`() {
        PaypalMapper.parseSessionConfig(sessionWith("client-abc"), Partner.Paypal, Method.Bnpl)
    }
}
