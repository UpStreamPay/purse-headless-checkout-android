package eu.purse.partnerui.plugins.partners.klarna

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KlarnaMapperTest {

    private fun klarnaInit(stepId: Int? = 1, clientToken: String? = "client-token-abc"): JSONObject {
        val logs = JSONObject()
        clientToken?.let { logs.put("klarna_client_token", it) }
        val init = JSONObject()
            .put("status", JSONObject().put("state", "SUCCESS"))
            .put("plugin_result", JSONObject().put("logs", logs))
        stepId?.let { init.put("step_id", it) }
        return init
    }

    @Test
    fun `init parses the client token`() {
        val config = KlarnaMapper.parseInitConfig(klarnaInit())
        assertEquals("client-token-abc", config.clientToken)
    }

    @Test
    fun `extractStepId reads the top-level step_id`() {
        assertEquals(1, KlarnaMapper.extractStepId(klarnaInit(stepId = 1)))
    }

    @Test
    fun `extractStepId is null when absent`() {
        assertNull(KlarnaMapper.extractStepId(klarnaInit(stepId = null)))
    }

    @Test
    fun `a missing client token throws`() {
        try {
            KlarnaMapper.parseInitConfig(klarnaInit(clientToken = null))
            assertTrue("expected a PaymentApiException", false)
        } catch (e: Exception) {
            assertTrue(e.message?.contains("klarna_client_token") == true)
        }
    }

    @Test
    fun `an unsuccessful init throws`() {
        val failed = JSONObject().put("status", JSONObject().put("state", "FAILED"))
        try {
            KlarnaMapper.parseInitConfig(failed)
            assertTrue("expected a PaymentApiException", false)
        } catch (e: Exception) {
            assertTrue(e.message?.contains("not successful") == true)
        }
    }
}
