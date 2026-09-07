package eu.purse.checkout.payment.split

import eu.purse.checkout.PurseCheckoutException
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.secondary.SecondaryTakePolicy
import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.MethodSettings
import eu.purse.core.payment.Partner
import eu.purse.core.payment.RegistrationMode
import eu.purse.core.error.PurseErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class PursePaymentSplitterTest {

    private fun primary() = PrimaryMethod(
        "purse-creditcard", Partner.Purse, Method.CreditCard,
        MethodConfiguration(
            stable = true, template = null, labels = emptyList(), position = null, vault = null,
            settings = MethodSettings(
                RegistrationMode.ON_SESSION, false, null, false, null, null, null, null,
            ),
        ),
        disabled = null, integrationType = null, supportedBrands = emptyList(),
    )

    private fun token(
        id: String = "illicado-giftcard-7890",
        balance: BigDecimal,
        policy: SecondaryTakePolicy = SecondaryTakePolicy.NONE,
        limit: BigDecimal? = null,
        usageLimit: Int? = null,
    ) = SecondaryToken(
        id = id, partner = Partner.Illicado, method = Method.GiftCard,
        pan = "9250004781234567890", cvv = "123", name = "gift", balance = balance,
        takePolicy = policy, limit = limit, usageLimit = usageLimit,
    )

    @Test
    fun `a fresh splitter owes the full order amount`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))

        assertEquals(BigDecimal("50.00"), splitter.remaining)
        assertEquals(BigDecimal("0.00"), splitter.secondaryContribution)
        assertFalse(splitter.fulfilled)
    }

    @Test
    fun `a zero-amount session needs a primary before it is fulfilled`() {
        val splitter = PursePaymentSplitter(BigDecimal.ZERO)

        assertFalse("nothing to submit yet", splitter.fulfilled)
        assertEquals(BigDecimal("0.00"), splitter.remaining)

        splitter.setPrimary(primary())   // an information request registers the chosen method
        assertTrue(splitter.fulfilled)
    }

    @Test
    fun `an explicit amount above the balance or the rest to pay is refused, not clamped`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))

        val aboveBalance = assertThrows(PurseCheckoutException.InvalidTake::class.java) {
            splitter.takeSplit(token(balance = BigDecimal("10.00")), requestedAmount = BigDecimal("12.00"))
        }
        assertEquals(PurseErrorCode.INVALID_TAKE_AMOUNT, aboveBalance.code)

        val aboveOrder = assertThrows(PurseCheckoutException.InvalidTake::class.java) {
            splitter.takeSplit(token(balance = BigDecimal("90.00")), requestedAmount = BigDecimal("60.00"))
        }
        assertEquals(PurseErrorCode.INVALID_TAKE_AMOUNT, aboveOrder.code)
        assertTrue("nothing was applied", splitter.appliedSecondaries.isEmpty())
    }

    @Test
    fun `an explicit amount above the method maximum is MAX_AMOUNT_REACHED`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))

        val thrown = assertThrows(PurseCheckoutException.InvalidTake::class.java) {
            splitter.takeSplit(token(balance = BigDecimal("40.00"), limit = BigDecimal("20.00")), requestedAmount = BigDecimal("25.00"))
        }
        assertEquals(PurseErrorCode.MAX_AMOUNT_REACHED, thrown.code)

        // Without an explicit amount the draw is simply capped at the maximum.
        splitter.takeSplit(token(balance = BigDecimal("40.00"), limit = BigDecimal("20.00")))
        assertEquals(BigDecimal("20.00"), splitter.secondaryContribution)
    }

    @Test
    fun `the method's usage_per_session_limit caps how many of its tokens share a split`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))
        splitter.takeSplit(token(id = "gc-1", balance = BigDecimal("5.00"), usageLimit = 2))
        splitter.takeSplit(token(id = "gc-2", balance = BigDecimal("5.00"), usageLimit = 2))

        val thrown = assertThrows(PurseCheckoutException.InvalidTake::class.java) {
            splitter.takeSplit(token(id = "gc-3", balance = BigDecimal("5.00"), usageLimit = 2))
        }
        assertEquals(PurseErrorCode.USAGE_LIMIT_ERROR, thrown.code)

        // Re-applying a token already in the split is not a new usage.
        splitter.takeSplit(token(id = "gc-2", balance = BigDecimal("5.00"), usageLimit = 2), requestedAmount = BigDecimal("3.00"))
        assertEquals(BigDecimal("8.00"), splitter.secondaryContribution)
    }

    @Test
    fun `a partial secondary reduces the remaining amount without fulfilling it`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))

        splitter.takeSplit(token(balance = BigDecimal("20.00")))

        assertEquals(BigDecimal("20.00"), splitter.secondaryContribution)
        assertEquals(BigDecimal("30.00"), splitter.remaining)
        assertFalse(splitter.fulfilled)
    }

    @Test
    fun `secondaries accumulate across distinct instruments`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))

        splitter.takeSplit(token(id = "gc-1", balance = BigDecimal("20.00")))
        splitter.takeSplit(token(id = "gc-2", balance = BigDecimal("15.00")))

        assertEquals(BigDecimal("35.00"), splitter.secondaryContribution)
        assertEquals(BigDecimal("15.00"), splitter.remaining)
    }

    @Test
    fun `a secondary covering the whole amount fulfills the split`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))

        splitter.takeSplit(token(balance = BigDecimal("50.00")))

        assertTrue(splitter.fulfilled)
        assertEquals(BigDecimal("0.00"), splitter.remaining)
    }

    @Test
    fun `a splittable secondary never draws more than the remaining amount`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))

        // A 90.00 gift card against a 50.00 order only draws down the 50.00 owed.
        splitter.takeSplit(token(balance = BigDecimal("90.00")))

        assertEquals(BigDecimal("50.00"), splitter.secondaryContribution)
        assertEquals(BigDecimal("0.00"), splitter.remaining)
    }

    @Test
    fun `a splittable secondary honours an explicit requested amount`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))

        splitter.takeSplit(token(balance = BigDecimal("40.00")), requestedAmount = BigDecimal("12.00"))

        assertEquals(BigDecimal("12.00"), splitter.secondaryContribution)
        assertEquals(BigDecimal("38.00"), splitter.remaining)
    }

    @Test
    fun `a MAX-policy secondary takes the full cap and ignores a requested amount`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))

        // Loyalty-style: non-splittable, so the 12.00 request is ignored — it takes its 30.00 balance.
        splitter.takeSplit(
            token(balance = BigDecimal("30.00"), policy = SecondaryTakePolicy.MAX),
            requestedAmount = BigDecimal("12.00"),
        )

        assertEquals(BigDecimal("30.00"), splitter.secondaryContribution)
        assertEquals(BigDecimal("20.00"), splitter.remaining)
    }

    @Test
    fun `selecting a primary fulfills the split and adds a primary slice for the remainder`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))
        splitter.takeSplit(token(balance = BigDecimal("20.00")))

        splitter.setPrimary(primary())

        assertTrue(splitter.fulfilled)
        val primarySplit = splitter.splits.single { it.source is PrimaryMethod }
        assertEquals(BigDecimal("30.00"), primarySplit.amount)
    }

    @Test
    fun `clearing a secondary restores the amount it covered`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))
        val gc = token(balance = BigDecimal("20.00"))
        splitter.takeSplit(gc)

        splitter.clearSplit(gc)

        assertEquals(BigDecimal("50.00"), splitter.remaining)
        assertEquals(BigDecimal("0.00"), splitter.secondaryContribution)
    }
}
