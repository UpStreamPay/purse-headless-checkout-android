package eu.purse.core.payload

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentStatusTest {

    @Test
    fun `every web StatusState maps, case-insensitively`() {
        assertEquals(PaymentStatus.SUCCESS, PaymentStatus.fromWire("SUCCESS"))
        assertEquals(PaymentStatus.SUCCESS, PaymentStatus.fromWire("success"))
        assertEquals(PaymentStatus.FAILED, PaymentStatus.fromWire("FAILED"))
        assertEquals(PaymentStatus.ERROR, PaymentStatus.fromWire("ERROR"))
        assertEquals(PaymentStatus.UNCERTAIN, PaymentStatus.fromWire("UNCERTAIN"))
        assertEquals(PaymentStatus.WAITING, PaymentStatus.fromWire("WAITING"))
    }

    @Test
    fun `a value this SDK does not know is UNKNOWN, never a crash`() {
        assertEquals(PaymentStatus.UNKNOWN, PaymentStatus.fromWire("PARTIALLY_SETTLED"))
        assertEquals(PaymentStatus.UNKNOWN, PaymentStatus.fromWire(""))
    }

    @Test
    fun `ValidationResult exposes the typed status and keeps the raw string`() {
        val result = ValidationResult.fromJson(JSONObject("""{"global_status":"FAILED","results":[]}"""))
        assertEquals(PaymentStatus.FAILED, result.status)
        assertEquals("FAILED", result.globalStatus)
        assertFalse(result.isSuccess)
        assertTrue(ValidationResult.fromJson(JSONObject("""{"global_status":"SUCCESS"}""")).isSuccess)
    }
}
