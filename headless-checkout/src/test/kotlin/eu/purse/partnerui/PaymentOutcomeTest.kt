package eu.purse.partnerui

import eu.purse.core.api.PaymentApiException
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.payload.PaymentStatus
import eu.purse.core.payload.ValidationResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentOutcomeTest {

    @Test
    fun `Completed carries the API verdict as a typed status`() {
        val result = ValidationResult.fromJson(JSONObject("""{"global_status":"SUCCESS"}"""))
        val outcome = PaymentOutcome.Completed(result)
        assertEquals(PaymentStatus.SUCCESS, outcome.status)
        assertTrue(outcome.isSuccess)
    }

    @Test
    fun `an SDK error reaches Failed as itself, anything else wrapped as a partner failure`() {
        val api = PaymentApiException("timeout", status = null, path = "/validate")
        assertSame(api, api.asPurseException())

        val wrapped = IllegalStateException("sheet closed unexpectedly").asPurseException()
        assertTrue(wrapped is PartnerUiException)
        assertEquals(PurseErrorCode.PARTNER_UI_ERROR, wrapped.code)
        assertEquals("sheet closed unexpectedly", wrapped.message)
        assertTrue(wrapped.cause is IllegalStateException)
    }

    @Test
    fun `a throwable without a message still yields a message`() {
        assertEquals("unexpected partner error", RuntimeException().asPurseException().message)
    }
}
