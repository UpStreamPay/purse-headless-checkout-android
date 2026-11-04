package eu.purse.partnerui.plugins.generics.redirection

import eu.purse.checkout.FakePaymentApi
import eu.purse.core.api.InitGuard
import eu.purse.core.api.PaymentApiClient
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.session.LegacyPaymentSessionParser
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PartnerUiException
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PayPal bnpl's eligibility probe on the generic redirection plugin — web's
 * `paypal_bnpl.ensureClientCompatibility` (`partner-ui/src/plugins/partners/paypal/bnpl/index.ts`)
 * and its spec (`paypal-bnpl.spec.ts`, "ensureClientCompatibility").
 */
class PaypalBnplEligibilityTest {

    private val api = FakePaymentApi()
    private val defaultSleeper = InitGuard.sleeper

    @Before
    fun setUp() {
        InitGuard.reset()
        InitGuard.sleeper = {}
    }

    @After
    fun tearDown() {
        InitGuard.sleeper = defaultSleeper
        InitGuard.reset()
        api.close()
    }

    private fun context(
        partner: Partner = Partner.Paypal,
        method: Method = Method.Bnpl,
        integrationMode: String? = "redirection",
        apiRoot: String = api.apiRoot,
    ): PartnerUiContext {
        val merchantSpecifics = JSONObject().put("client_id", "paypal-client-abc")
        if (integrationMode != null) merchantSpecifics.put("integration_mode", integrationMode)
        val configuration = JSONObject()
            .put("stable", true)
            .put("labels", org.json.JSONArray(listOf(partner.wireValue, method.wireValue, "primary")))
            .put("settings", JSONObject().put("registration_mode", "OFF_SESSION").put("merchant_specifics", merchantSpecifics))
        val session = LegacyPaymentSessionParser.parse(
            JSONObject()
                .put("id", "s-bnpl")
                .put("amount", 120.0)
                .put("currency_code", "EUR")
                .put("expiration_date", "2999-01-01T00:00:00Z")
                .put(
                    "protocols",
                    JSONObject().put(
                        partner.wireValue,
                        JSONObject().put(method.wireValue, JSONObject().put("configurations", configuration)),
                    ),
                ),
        )
        return PartnerUiContext(
            partner = partner,
            method = method,
            session = session,
            amount = BigDecimal("120.00"),
            api = PaymentApiClient("k", apiRoot, "e", "s-bnpl"),
        )
    }

    private fun probe(context: PartnerUiContext): Throwable? = runCatching {
        runBlocking { RedirectionPlugin(context.partner, context.method).ensureClientCompatibility(context) }
    }.exceptionOrNull()

    @Test
    fun `an eligible bnpl in redirection mode passes after one bare init`() {
        api.respond("init", """{ "status": { "state": "SUCCESS" }, "step_id": 1, "plugin_result": { "status": "SUCCESS", "logs": {} } }""")

        assertEquals(null, probe(context()))

        val init = api.initRequests().single().json
        assertEquals("paypal", init.getString("partner"))
        assertEquals("bnpl", init.getString("method"))
        assertEquals(0, BigDecimal("120.00").compareTo(BigDecimal(init.get("amount").toString())))
        // Web's probe body: the envelope only, no browser block.
        assertEquals(0, init.getJSONObject("partner_data").length())
        assertEquals(0, init.getJSONObject("payment_data").length())
        assertEquals(0, init.getJSONObject("payment_preferences").length())
    }

    @Test
    fun `a NOT_ELIGIBLE plugin_result refuses the client`() {
        api.respond(
            "init",
            """{ "status": { "action": "INIT", "state": "ERROR", "code": "FAILED" }, "plugin_result": { "status": "NOT_ELIGIBLE", "amount": 480 } }""",
        )

        val error = probe(context())

        assertTrue(error is PartnerUiException)
        assertEquals("PayPal BNPL is not eligible for this order", error!!.message)
    }

    @Test
    fun `a network failure refuses the client too — web's catch fails closed`() {
        val unreachable = FakePaymentApi().also { it.close() }.apiRoot

        assertTrue(probe(context(apiRoot = unreachable)) != null)
    }

    @Test
    fun `an HTTP error refuses the client`() {
        api.on("init") { 400 to """{"error":"BAD_REQUEST"}""" }

        assertTrue(probe(context()) != null)
        assertEquals(1, api.initRequests().size)
    }

    @Test
    fun `bnpl outside redirection mode passes without a call, as web's popup branch`() {
        assertEquals(null, probe(context(integrationMode = "popup")))
        assertEquals(null, probe(context(integrationMode = null)))
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `any other pair the redirection template serves passes without a call`() {
        assertEquals(null, probe(context(method = Method.Wallet)))
        assertEquals(null, probe(context(partner = Partner.Other("fintecture"), method = Method.Other("instantpayment"))))
        assertFalse(api.requests.any { it.path.endsWith("/init") })
    }
}
