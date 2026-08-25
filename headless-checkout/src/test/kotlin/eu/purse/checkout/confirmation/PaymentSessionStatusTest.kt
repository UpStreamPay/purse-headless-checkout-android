package eu.purse.checkout.confirmation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentSessionStatusTest {

    private fun operation(
        action: String,
        state: String,
        id: String = "op-$action",
        parent: String? = null,
        ref: String? = "ref-1",
    ): JSONObject = JSONObject()
        .put("partner", "purse")
        .put("method", "creditcard")
        .put("id", id)
        .put("status", JSONObject().put("action", action).put("state", state))
        .apply {
            parent?.let { put("parent_transaction_id", it) }
            ref?.let { put("plugin_result", JSONObject().put("partner_reference", it)) }
        }

    @Test
    fun `an auto-captured v2 payment (AUTHORIZE plus CAPTURE rows) is Confirmed`() {
        // The exact shape docs/plugins/README.md documents: two operations, one payment.
        val transactions = JSONArray()
            .put(operation("CAPTURE", "SUCCESS", id = "cap-1", parent = "auth-1"))
            .put(operation("AUTHORIZE", "SUCCESS", id = "auth-1"))

        val status = PaymentSessionStatus.from(transactions)

        assertTrue(status is PaymentSessionStatus.Confirmed)
        assertEquals(2, status.operations.size)
        assertEquals("auth-1", status.operations[0].parentTransactionId)
        assertEquals("ref-1", status.operations[1].partnerReference)
    }

    @Test
    fun `a lone successful AUTHORIZE (v1 aggregation) is Confirmed too`() {
        val status = PaymentSessionStatus.from(JSONArray().put(operation("AUTHORIZE", "SUCCESS")))
        assertTrue(status is PaymentSessionStatus.Confirmed)
    }

    @Test
    fun `init rows alone are Pending - an init is not money movement`() {
        val status = PaymentSessionStatus.from(
            JSONArray().put(operation("INIT", "SUCCESS")).put(operation("APPLY", "SUCCESS")),
        )
        assertTrue(status is PaymentSessionStatus.Pending)
    }

    @Test
    fun `a failed authorize with no success is Failed`() {
        val status = PaymentSessionStatus.from(
            JSONArray().put(operation("INIT", "SUCCESS")).put(operation("AUTHORIZE", "FAILED")),
        )
        assertTrue(status is PaymentSessionStatus.Failed)
    }

    @Test
    fun `a WAITING authorize stays Pending, not Failed`() {
        val status = PaymentSessionStatus.from(JSONArray().put(operation("AUTHORIZE", "WAITING")))
        assertTrue(status is PaymentSessionStatus.Pending)
    }

    @Test
    fun `a retried payment - one failed then one successful authorize - is Confirmed`() {
        val status = PaymentSessionStatus.from(
            JSONArray().put(operation("AUTHORIZE", "FAILED")).put(operation("AUTHORIZE", "SUCCESS", id = "auth-2")),
        )
        assertTrue(status is PaymentSessionStatus.Confirmed)
    }

    @Test
    fun `an empty session is Pending`() {
        assertTrue(PaymentSessionStatus.from(JSONArray()) is PaymentSessionStatus.Pending)
    }
}
