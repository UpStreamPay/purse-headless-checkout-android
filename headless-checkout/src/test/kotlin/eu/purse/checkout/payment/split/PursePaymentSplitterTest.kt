package eu.purse.checkout.payment.split

import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.MethodSettings
import eu.purse.core.payment.Partner
import eu.purse.core.payment.RegistrationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class PursePaymentSplitterTest {

    private fun config(max: BigDecimal? = null, secondary: Boolean = false) = MethodConfiguration(
        stable = true,
        template = null,
        labels = if (secondary) listOf("secondary") else emptyList(),
        position = null,
        vault = null,
        settings = MethodSettings(
            registrationMode = RegistrationMode.ON_SESSION,
            saveToken = false,
            saveTokenMode = null,
            directDebit = false,
            paymentMinAmount = null,
            paymentMaxAmount = max,
            usagePerSessionLimit = null,
            integrationMode = null,
        ),
    )

    private fun primary() = PrimaryMethod(
        "purse-creditcard", Partner.Purse, Method.CreditCard, config(), disabled = null,
        integrationType = null, supportedBrands = emptyList(),
    )

    private fun giftCard(id: String = "illicado-giftcard", max: BigDecimal? = null) =
        SecondaryMethod(id, Partner.Illicado, Method.GiftCard, config(max = max, secondary = true), disabled = null)

    @Test
    fun `a fresh splitter owes the full order amount`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))

        assertEquals(BigDecimal("50.00"), splitter.remaining)
        assertEquals(BigDecimal("0.00"), splitter.secondaryContribution)
        assertFalse(splitter.fulfilled)
    }

    @Test
    fun `a zero-amount session is fulfilled from the start`() {
        val splitter = PursePaymentSplitter(BigDecimal.ZERO)

        assertTrue(splitter.fulfilled)
        assertEquals(BigDecimal("0.00"), splitter.remaining)
    }

    @Test
    fun `a partial secondary reduces the remaining amount without fulfilling it`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))

        splitter.takeSplit(giftCard(), BigDecimal("20.00"))

        assertEquals(BigDecimal("20.00"), splitter.secondaryContribution)
        assertEquals(BigDecimal("30.00"), splitter.remaining)
        assertFalse(splitter.fulfilled)
    }

    @Test
    fun `secondaries accumulate across distinct instruments`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))

        splitter.takeSplit(giftCard("gc-1"), BigDecimal("20.00"))
        splitter.takeSplit(giftCard("gc-2"), BigDecimal("15.00"))

        assertEquals(BigDecimal("35.00"), splitter.secondaryContribution)
        assertEquals(BigDecimal("15.00"), splitter.remaining)
    }

    @Test
    fun `a secondary covering the whole amount fulfills the split`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))

        splitter.takeSplit(giftCard(), BigDecimal("50.00"))

        assertTrue(splitter.fulfilled)
        assertEquals(BigDecimal("0.00"), splitter.remaining)
    }

    @Test
    fun `a secondary never draws more than the remaining amount`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))

        // A 90.00 gift card against a 50.00 order only draws down the 50.00 owed.
        splitter.takeSplit(giftCard(), BigDecimal("90.00"))

        assertEquals(BigDecimal("50.00"), splitter.secondaryContribution)
        assertEquals(BigDecimal("0.00"), splitter.remaining)
        assertTrue(splitter.fulfilled)
    }

    @Test
    fun `selecting a primary fulfills the split and adds a primary slice for the remainder`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))
        splitter.takeSplit(giftCard(), BigDecimal("20.00"))

        splitter.setPrimary(primary())

        assertTrue(splitter.fulfilled)
        val primarySplit = splitter.splits.single { it.source is PrimaryMethod }
        assertEquals(BigDecimal("30.00"), primarySplit.amount)
    }

    @Test
    fun `clearing a secondary restores the amount it covered`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))
        val gc = giftCard()
        splitter.takeSplit(gc, BigDecimal("20.00"))

        splitter.clearSplit(gc)

        assertEquals(BigDecimal("50.00"), splitter.remaining)
        assertEquals(BigDecimal("0.00"), splitter.secondaryContribution)
    }
}
