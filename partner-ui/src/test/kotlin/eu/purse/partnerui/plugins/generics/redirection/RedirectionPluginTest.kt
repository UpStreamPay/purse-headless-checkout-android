package eu.purse.partnerui.plugins.generics.redirection

import eu.purse.core.api.PaymentApiClient
import eu.purse.core.api.PaymentApiException
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.partnerui.PartnerUiContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The pure half of the generic redirection template. [RedirectionMapper.browserData] is deliberately
 * untested here — it's the one function that needs a real `android.content.Context`
 * (`WebSettings.getDefaultUserAgent`, `DisplayMetrics`), so it belongs to an instrumented test rather
 * than this JVM suite.
 */
class RedirectionPluginTest {

    private val session = LegacyPaymentSession(
        id = "s-1",
        amount = BigDecimal("4.80"),
        informationRequest = false,
        currencyCode = "EUR",
        expirationDate = java.time.Instant.parse("2999-01-01T00:00:00Z"),
        success = null,
        failure = null,
        redirection = null,
        protocols = emptyMap(),
        vault = null,
    )

    private val partner = Partner.Other("fintecture")
    private val method = Method.Other("instantpayment")

    private fun context(
        collected: Map<String, Any?> = emptyMap(),
        token: eu.purse.partnerui.PayToken? = null,
    ) = PartnerUiContext(
        partner = partner,
        method = method,
        session = session,
        amount = BigDecimal("4.80"),
        api = PaymentApiClient("k", "https://api.example", "e", "s-1"),
        collected = collected,
        token = token,
    )

    @Test
    fun `serves whatever partner and method it was built for`() {
        val plugin = RedirectionPlugin(partner, method)
        assertEquals("fintecture", plugin.partner.wireValue)
        assertEquals("instantpayment", plugin.method.wireValue)
        assertNull(plugin.vault)
    }

    @Test
    fun `init payload carries the envelope, the browser block and nothing else`() {
        val browser = JSONObject().put("user_agent", "ua")
        val payload = RedirectionMapper.buildInitPayload(
            amount = BigDecimal("4.80"),
            partnerWireValue = "fintecture",
            methodWireValue = "instantpayment",
            browser = browser,
        )

        assertEquals("fintecture", payload.getString("partner"))
        assertEquals("instantpayment", payload.getString("method"))
        assertEquals("4.80", payload.get("amount").toString())
        assertEquals("ua", payload.getJSONObject("partner_data").getJSONObject("browser").getString("user_agent"))
        assertEquals(0, payload.getJSONObject("payment_data").length())
        assertEquals(0, payload.getJSONObject("payment_preferences").length())
    }

    @Test
    fun `init payload omits partner_data browser when none was collected`() {
        val payload = RedirectionMapper.buildInitPayload(BigDecimal.ONE, "bizum", "bizum", browser = null)
        assertEquals(0, payload.getJSONObject("partner_data").length())
    }

    @Test
    fun `extractStepId reads the top-level step_id, or null`() {
        assertEquals(7, RedirectionMapper.extractStepId(JSONObject().put("step_id", 7)))
        assertNull(RedirectionMapper.extractStepId(JSONObject()))
        assertNull(RedirectionMapper.extractStepId(JSONObject().put("step_id", JSONObject.NULL)))
    }

    @Test
    fun `an empty but successful init is accepted — this template has nothing to parse`() {
        RedirectionMapper.ensureInitSucceeded(JSONObject())
        RedirectionMapper.ensureInitSucceeded(JSONObject().put("status", JSONObject().put("state", "SUCCESS")))
    }

    @Test
    fun `a failed init throws`() {
        val response = JSONObject().put(
            "status",
            JSONObject().put("state", "ERROR").put("description", "no can do"),
        )
        val error = runCatching { RedirectionMapper.ensureInitSucceeded(response) }.exceptionOrNull()
        assertTrue(error is PaymentApiException)
        assertTrue(error!!.message!!.contains("no can do"))
    }

    @Test
    fun `preValidation carries the collected step id and browser block`() = runBlocking {
        val browser = JSONObject().put("locale", "fr-FR")
        val payload = RedirectionPlugin(partner, method).preValidation(
            context(
                mapOf(
                    RedirectionPlugin.COLLECTED_STEP_ID to 7,
                    RedirectionPlugin.COLLECTED_BROWSER to browser,
                ),
            ),
        )

        assertEquals(partner, payload.partner)
        assertEquals(method, payload.method)
        assertEquals(7, payload.stepId)
        assertEquals("fr-FR", payload.partnerData.getJSONObject("browser").getString("locale"))
        assertEquals(0, payload.order.length())
        assertEquals(0, payload.paymentPreferences.length())
    }

    @Test
    fun `preValidation without a step id or browser still builds a valid item`() = runBlocking {
        val payload = RedirectionPlugin(partner, method).preValidation(context())
        assertNull(payload.stepId)
        assertEquals(0, payload.partnerData.length())
        // vault_vendor defaults to "none" on serialization, as for every other plugin's payload.
        assertEquals("none", payload.toJson().getJSONObject("payment_data").getString("vault_vendor"))
    }

    @Test
    fun `preValidation forwards a context token as payment_data token`() = runBlocking {
        val payload = RedirectionPlugin(partner, method)
            .preValidation(context(token = eu.purse.partnerui.PayToken("tok_123")))
        assertEquals("tok_123", payload.paymentData.getString("token"))
        assertFalse("no card metadata to patch on this token", payload.paymentData.has("card"))
    }

    @Test
    fun `preValidation patches the full token metadata like web's patchItemPayloadWithToken`() = runBlocking {
        val token = eu.purse.partnerui.PayToken(
            value = "tok_456",
            expirationDate = "2027-08-31T00:00:00Z",
            holderName = "J DOE",
            selectedNetwork = "VISA",
        )
        val payload = RedirectionPlugin(partner, method).preValidation(context(token = token))

        assertEquals("tok_456", payload.paymentData.getString("token"))
        assertEquals("J DOE", payload.paymentData.getString("holder"))
        val card = payload.paymentData.getJSONObject("card")
        assertEquals("2027-08-31", card.getString("expiry_date"))
        assertEquals("VISA", card.getString("selected_network"))
    }
}
