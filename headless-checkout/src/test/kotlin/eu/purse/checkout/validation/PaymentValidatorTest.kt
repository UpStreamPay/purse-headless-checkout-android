package eu.purse.checkout.validation

import eu.purse.checkout.PurseCheckoutException
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.checkout.payment.split.PaymentSplit
import eu.purse.checkout.payment.split.PursePaymentSplitter
import eu.purse.core.api.PaymentApiClient
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.MethodSettings
import eu.purse.core.payment.Partner
import eu.purse.core.payment.RegistrationMode
import eu.purse.core.session.LegacyPaymentSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * What the `/validate` **array** is allowed to contain. One item per split, and never the same split
 * source twice — a repeat would ask the PSP to charge one instrument twice in a single call.
 *
 * No network: the duplicate guard runs before any API call, and the "not a duplicate" cases are
 * pinned by asserting the failure that follows is the API call's, not the guard's (the client points
 * at a closed port).
 */
class PaymentValidatorTest {

    private val session = LegacyPaymentSession(
        id = "s-1",
        amount = BigDecimal("50.00"),
        informationRequest = false,
        currencyCode = "EUR",
        expirationDate = Instant.parse("2999-01-01T00:00:00Z"),
        success = null,
        failure = null,
        redirection = null,
        protocols = emptyMap(),
        vault = null,
    )

    /** Port 1 is never listening — any real call fails fast, so reaching it is itself the assertion. */
    private val api = PaymentApiClient("k", "http://127.0.0.1:1", "e", "s-1")

    private fun primary(id: String = "fintecture-instantpayment") = PrimaryMethod(
        id = id,
        partner = Partner.Other("fintecture"),
        method = Method.Other("instantpayment"),
        configuration = MethodConfiguration(
            stable = true, template = "redirection", labels = emptyList(), position = null, vault = null,
            settings = MethodSettings(RegistrationMode.ON_SESSION, false, null, false, null, null, null, null),
        ),
        disabled = null,
        integrationType = null,
        supportedBrands = emptyList(),
    )

    private fun giftCard(id: String) = SecondaryToken(
        id = id,
        partner = Partner.Illicado,
        method = Method.GiftCard,
        pan = "9250004781234567890",
        cvv = "123",
        name = "gift",
        balance = BigDecimal("10.00"),
    )

    /**
     * Every case here fails *somewhere* — the point is **where**. The guard rejects up front with
     * "repeated split source"; anything that gets past it fails later, on resolving a plugin or on the
     * unreachable API call. So the exception's message is the assertion, and the concrete subtype
     * varies by case.
     */
    private fun submit(splits: List<PaymentSplit>): PurseCheckoutException =
        runCatching { runBlocking { PaymentValidator(api).execute(splits, session) } }
            .exceptionOrNull() as PurseCheckoutException

    // --- the splitter's own output ----------------------------------------------------------------

    @Test
    fun `the splitter never emits the same source twice, so one primary yields one item`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))
        val method = primary()

        // Selecting the same method repeatedly (the demo does this every time a row is re-tapped)
        // replaces the primary, it does not accumulate.
        splitter.setPrimary(method)
        splitter.setPrimary(method)
        splitter.setPrimary(method)

        assertEquals(1, splitter.splits.size)
        assertEquals(listOf(method.id), splitter.splits.map { it.source.id })
    }

    @Test
    fun `re-applying the same secondary token replaces its slice rather than adding a second`() {
        val splitter = PursePaymentSplitter(BigDecimal("50.00"))
        val token = giftCard("illicado-giftcard-7890")

        splitter.takeSplit(token, BigDecimal("4.00"))
        splitter.takeSplit(token, BigDecimal("6.00"))
        splitter.setPrimary(primary())

        assertEquals(listOf("illicado-giftcard-7890", "fintecture-instantpayment"), splitter.splits.map { it.source.id })
        assertEquals(BigDecimal("6.00"), splitter.splits.first().amount)
    }

    // --- the guard at the point the array is built -------------------------------------------------

    @Test
    fun `a repeated split source is refused before anything is sent`() {
        val method = primary()
        val error = submit(
            listOf(
                PaymentSplit(BigDecimal("25.00"), method),
                PaymentSplit(BigDecimal("25.00"), method),
            ),
        )

        assertTrue(error.message!!.contains("repeated split source"))
        assertTrue(error.message!!.contains("fintecture-instantpayment"))
        // Refused up front — the guard runs before any /init or /validate call.
        assertFalse(error.message!!.contains("validate call failed"))
    }

    @Test
    fun `two different gift cards of the same partner and method are NOT a duplicate`() {
        // Same partner/method, different instruments — a real co-payment shape. Deduplicating on
        // (partner, method) would drop one card's slice and under-pay the order.
        val error = submit(
            listOf(
                PaymentSplit(BigDecimal("10.00"), giftCard("illicado-giftcard-1111")),
                PaymentSplit(BigDecimal("10.00"), giftCard("illicado-giftcard-2222")),
            ),
        )

        assertFalse(error.message!!.contains("repeated split source"))
        // It got past the guard and tried the real call, which is all this test needs to prove.
        assertTrue(error.message!!.contains("validate call failed"))
    }

    @Test
    fun `a secondary plus a primary passes the guard`() {
        val error = submit(
            listOf(
                PaymentSplit(BigDecimal("10.00"), giftCard("illicado-giftcard-1111")),
                PaymentSplit(BigDecimal("40.00"), primary()),
            ),
        )

        // Got past the guard; this fixture's session declares no `protocols`, so the primary then
        // fails plugin resolution — which is itself proof the guard let the pair through.
        assertFalse(error.message!!.contains("repeated split source"))
        assertTrue(error is PurseCheckoutException.NoPluginRegistered)
    }
}
