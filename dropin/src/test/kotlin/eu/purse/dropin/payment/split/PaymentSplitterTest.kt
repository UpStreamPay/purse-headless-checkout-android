package eu.purse.dropin.payment.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class PaymentSplitterTest {

    @Test
    fun `a fresh splitter owes the full order amount`() {
        val splitter = PaymentSplitter(BigDecimal("50.00"))

        assertEquals(BigDecimal("50.00"), splitter.remaining)
        assertEquals(BigDecimal.ZERO, splitter.secondaryContribution)
        assertFalse(splitter.isFulfilled)
    }

    @Test
    fun `a zero-amount session is fulfilled from the start`() {
        val splitter = PaymentSplitter(BigDecimal.ZERO)

        assertTrue(splitter.isFulfilled)
        assertEquals(BigDecimal.ZERO, splitter.remaining)
    }

    @Test
    fun `a partial secondary reduces the remaining amount without fulfilling it`() {
        val splitter = PaymentSplitter(BigDecimal("50.00"))

        splitter.applySecondary(BigDecimal("20.00"))

        assertEquals(BigDecimal("20.00"), splitter.secondaryContribution)
        assertEquals(BigDecimal("30.00"), splitter.remaining)
        assertFalse(splitter.isFulfilled)
    }

    @Test
    fun `secondaries accumulate across calls`() {
        val splitter = PaymentSplitter(BigDecimal("50.00"))

        splitter.applySecondary(BigDecimal("20.00"))
        splitter.applySecondary(BigDecimal("15.00"))

        assertEquals(BigDecimal("35.00"), splitter.secondaryContribution)
        assertEquals(BigDecimal("15.00"), splitter.remaining)
    }

    @Test
    fun `a secondary covering the whole amount fulfills the split`() {
        val splitter = PaymentSplitter(BigDecimal("50.00"))

        splitter.applySecondary(BigDecimal("50.00"))

        assertTrue(splitter.isFulfilled)
        assertEquals(BigDecimal.ZERO, splitter.remaining)
    }

    @Test
    fun `a secondary never contributes more than the remaining amount`() {
        val splitter = PaymentSplitter(BigDecimal("50.00"))

        // A 90.00 gift card against a 50.00 order only draws down the 50.00 owed.
        splitter.applySecondary(BigDecimal("90.00"))

        assertEquals(BigDecimal("50.00"), splitter.secondaryContribution)
        assertEquals(BigDecimal.ZERO, splitter.remaining)
        assertTrue(splitter.isFulfilled)
    }
}
