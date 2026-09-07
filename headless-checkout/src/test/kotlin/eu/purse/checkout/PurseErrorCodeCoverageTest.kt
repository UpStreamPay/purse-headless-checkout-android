package eu.purse.checkout

import eu.purse.checkout.payment.item.DisabledState
import eu.purse.checkout.state.SessionState
import eu.purse.core.api.PaymentApiException
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.error.PurseException
import eu.purse.core.session.PurseSessionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every [PurseErrorCode] the SDK can raise is produced by one exception type, all of them under the
 * common [PurseException] root, each carrying its code, a message and the shared documentation link.
 */
class PurseErrorCodeCoverageTest {

    private val boom = RuntimeException("boom")

    private fun raised(): Map<PurseErrorCode, PurseException> = listOf<PurseException>(
        PurseCheckoutException.SessionExpired("s-1"),
        PurseSessionException.SessionMalformed("not json"),
        PurseCheckoutException.SubmitBlocked(SessionState.SUBMITTING),
        PurseCheckoutException.CheckoutTornDown(),
        PurseCheckoutException.ValidateFailed("validate call failed"),
        PurseCheckoutException.HookFailed("onRedirection", boom, PurseErrorCode.POST_VALIDATE_FAILED),
        PurseCheckoutException.PaymentNotFulfilled(),
        PurseCheckoutException.ItemNotFulfilled("purse-creditcard"),
        PurseCheckoutException.ValidateFailed("form invalid", code = PurseErrorCode.UI_VALIDATION_FAILED_ERROR),
        PurseCheckoutException.HookFailed("onBeforeValidate", boom, PurseErrorCode.ON_BEFORE_VALIDATE_HOOK_FAILED),
        PurseCheckoutException.NoPluginRegistered("no plugin"),
        PurseCheckoutException.MethodDisabled("paypal-wallet", DisabledState.Submitting),
        PurseCheckoutException.SecondaryTokenizationFailed("init failed"),
        PurseCheckoutException.InvalidSecondaryCard("cvv", PurseErrorCode.SECONDARY_METHOD_REQUIRED_CVV),
        PurseCheckoutException.InvalidSecondaryCard("pan", PurseErrorCode.SECONDARY_METHOD_WRONG_PAN_FORMAT),
        PurseCheckoutException.InvalidSecondaryCard("cvv format", PurseErrorCode.SECONDARY_METHOD_WRONG_CVV_FORMAT),
        PurseCheckoutException.SecondaryTokenizationFailed("empty", PurseErrorCode.SECONDARY_TOKEN_EMPTY_BALANCE),
        PurseCheckoutException.InvalidTake("too much", PurseErrorCode.INVALID_TAKE_AMOUNT),
        PurseCheckoutException.InvalidTake("above max", PurseErrorCode.MAX_AMOUNT_REACHED),
        PurseCheckoutException.InvalidTake("used up", PurseErrorCode.USAGE_LIMIT_ERROR),
        PurseCheckoutException.WalletOperationFailed("no wallet", PurseErrorCode.MISSING_WALLET_SESSION),
        PurseCheckoutException.WalletOperationFailed("no token", PurseErrorCode.MISSING_TOKEN),
        PurseCheckoutException.WalletOperationFailed("wallet fetch", PurseErrorCode.SET_WALLET_SESSION_FAILED),
        PurseCheckoutException.WalletOperationFailed("bad name", PurseErrorCode.INVALID_TOKEN_NAME),
        PurseCheckoutException.WalletOperationFailed("taken", PurseErrorCode.TOKEN_NAME_EXISTS),
        PurseCheckoutException.WalletOperationFailed("saved", PurseErrorCode.TOKEN_NOT_REGISTERABLE),
        PurseCheckoutException.WalletOperationFailed("never", PurseErrorCode.METHOD_NOT_REGISTERABLE),
        PurseCheckoutException.WalletOperationFailed("delete all", PurseErrorCode.FAILED_TO_DELETE_ALL_TOKENS),
        PurseCheckoutException.MethodNotSimulable("purse-creditcard"),
        PurseCheckoutException.LoanSimulationFailed("no plugin_result"),
        PaymentApiException("transport", status = null, path = "/validate"),
    ).associateBy { it.code }

    @Test
    fun `every code the SDK raises is covered, shares the root, and links to its documentation`() {
        val raised = raised()

        // UNKNOWN_ERROR exists for web parity only: this SDK never raises an untyped error.
        val uncovered = PurseErrorCode.values().toSet() - raised.keys
        assertEquals(setOf(PurseErrorCode.UNKNOWN_ERROR), uncovered)

        raised.forEach { (code, e) ->
            assertTrue("$code has a message", !e.message.isNullOrBlank())
            assertEquals(code.documentationLink, e.documentationLink)
            assertTrue(e.documentationLink.endsWith("#${code.name.lowercase()}"))
        }
    }

    @Test
    fun `an API transport failure is outcome-unknown, an answered one is not`() {
        assertTrue(PaymentApiException("timeout", status = null).outcomeUnknown)
        assertTrue(!PaymentApiException("refused", status = 400).outcomeUnknown)
        assertTrue(PurseCheckoutException.ValidateFailed("x", PaymentApiException("timeout")).outcomeUnknown)
        assertTrue(!PurseCheckoutException.ValidateFailed("x", PaymentApiException("refused", status = 500)).outcomeUnknown)
    }

    @Test
    fun `every disabled state carries a displayable message`() {
        val states = listOf(
            DisabledState.MethodUnstable,
            DisabledState.AmountConditionUnreachable(java.math.BigDecimal("10.00"), null, java.math.BigDecimal("5.00")),
            DisabledState.PaymentFulfilled,
            DisabledState.MinAmountNotMet(java.math.BigDecimal("10.00"), java.math.BigDecimal("4.80")),
            DisabledState.MaxAmountExceeded(java.math.BigDecimal("100.00"), java.math.BigDecimal("250.00")),
            DisabledState.PluginIncompatible,
            DisabledState.TokenInitFailed,
            DisabledState.PartnerUiFatalError("x"),
            DisabledState.PartnerUiRecoverableError("x"),
            DisabledState.FatalErrorBeforeRender("x"),
            DisabledState.Submitting,
        )
        assertEquals(DisabledState.Code.values().toSet(), states.map { it.code }.toSet())
        states.forEach { assertTrue("${it.code} has a message", it.message.isNotBlank()) }
        assertEquals(
            "The minimum amount for this payment method is 10.00. Amount is currently 4.80.",
            DisabledState.MinAmountNotMet(java.math.BigDecimal("10.00"), java.math.BigDecimal("4.80")).message,
        )
    }
}
