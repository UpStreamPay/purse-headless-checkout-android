package eu.purse.checkout.payment.secondary

import eu.purse.checkout.PurseCheckoutException
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.MethodSettings
import eu.purse.core.payment.Partner
import eu.purse.core.payment.RegistrationMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SecondaryConfigTest {

    private val illicadoMethod = SecondaryMethod(
        "illicado-giftcard", Partner.Illicado, Method.GiftCard,
        MethodConfiguration(
            stable = true, template = "secondary", labels = listOf("secondary"), position = null, vault = null,
            settings = MethodSettings(
                RegistrationMode.OFF_SESSION, false, null, false, null, null, null, null,
            ),
        ),
        disabled = null,
    )

    private val validPan = "9250004781234567890"

    @Test
    fun `illicado validates its PAN prefix and length`() {
        assertTrue(IllicadoConfig.validatePan(validPan))
        assertFalse(IllicadoConfig.validatePan("4111111111111111"))
        assertFalse(IllicadoConfig.validatePan("92500047812345"))
    }

    @Test
    fun `illicado requires a 3-digit CVV`() {
        assertTrue(IllicadoConfig.requiresCvv(validPan))
        assertTrue(IllicadoConfig.validateCvv("123"))
        assertFalse(IllicadoConfig.validateCvv("12"))
        assertFalse(IllicadoConfig.validateCvv("abc"))
    }

    @Test
    fun `illicado is splittable`() {
        assertEquals(SecondaryTakePolicy.NONE, IllicadoConfig.takePolicy)
    }

    @Test
    fun `the registry resolves illicado gift cards and nothing else`() {
        assertEquals(IllicadoConfig, SecondaryConfigs.forMethod(Partner.Illicado, Method.GiftCard))
        assertEquals(null, SecondaryConfigs.forMethod(Partner.Purse, Method.CreditCard))
    }

    @Test
    fun `init payload carries the card number and csc`() {
        val paymentData = IllicadoConfig.buildInitPaymentData(validPan, "123")
        val card = paymentData.getJSONObject("card")
        assertEquals(validPan, card.getString("number"))
        assertEquals("123", card.getString("csc"))
    }

    @Test
    fun `parseToken extracts balance, label and expiry from a successful init`() {
        val response = JSONObject(
            """
            {
              "status": { "state": "SUCCESS" },
              "plugin_result": {
                "payment_data": { "cards": [ { "balance": 25.50, "expiry_date": "2030-12" } ] },
                "logs": { "masked_card": "9250 **** **** 7890" }
              }
            }
            """.trimIndent(),
        )

        val token = IllicadoConfig.parseToken(illicadoMethod, response, validPan, "123")

        assertEquals("illicado-giftcard-7890", token.id)
        assertEquals(0, BigDecimal("25.50").compareTo(token.balance))
        assertEquals("9250 **** **** 7890", token.name)
        assertEquals("2030-12", token.expirationDate)
        assertEquals(SecondaryTakePolicy.NONE, token.takePolicy)
    }

    @Test
    fun `parseToken throws when the init was not successful`() {
        val response = JSONObject("""{ "status": { "state": "FAILED", "description": "nope" } }""")
        assertThrows(PurseCheckoutException.SecondaryTokenizationFailed::class.java) {
            IllicadoConfig.parseToken(illicadoMethod, response, validPan, "123")
        }
    }

    @Test
    fun `parseToken throws when the card has no balance`() {
        val response = JSONObject(
            """{ "status": { "state": "SUCCESS" }, "plugin_result": { "payment_data": { "cards": [ { "balance": 0 } ] } } }""",
        )
        assertThrows(PurseCheckoutException.SecondaryTokenizationFailed::class.java) {
            IllicadoConfig.parseToken(illicadoMethod, response, validPan, "123")
        }
    }

    @Test
    fun `easy2play gift card requires a 3-digit CVV and accepts any PAN`() {
        assertTrue(Easy2playGiftcardConfig.requiresCvv("anything"))
        assertTrue(Easy2playGiftcardConfig.validatePan("anything"))
        assertTrue(Easy2playGiftcardConfig.validateCvv("123"))
        assertFalse(Easy2playGiftcardConfig.validateCvv("12"))
        assertEquals(SecondaryTakePolicy.NONE, Easy2playGiftcardConfig.takePolicy)
    }

    @Test
    fun `ogloba needs no CVV and requires a 6-digit prefix`() {
        assertFalse(OglobaConfig.requiresCvv("123456"))
        assertTrue(OglobaConfig.validatePan("1234567890"))
        assertFalse(OglobaConfig.validatePan("12345"))
    }

    @Test
    fun `buybox storeland cards carry no CVV, other buybox cards do`() {
        val storeland = "5330123456789" // ^533\d{10}$
        assertFalse(BuyboxConfig.requiresCvv(storeland))
        assertTrue(BuyboxConfig.validatePan(storeland))
        assertTrue(BuyboxConfig.requiresCvv("1234567890123456789")) // 19-digit Oney card
    }

    @Test
    fun `maxxing is non-splittable and reads its balance from logs`() {
        assertEquals(SecondaryTakePolicy.MAX, MaxxingConfig.takePolicy)
        assertFalse(MaxxingConfig.requiresCvv(""))

        val method = SecondaryMethod(
            "maxxing-loyaltycard", Partner.Other("maxxing"), Method.Other("loyaltycard"),
            illicadoMethod.configuration, disabled = null,
        )
        val response = JSONObject(
            """
            { "status": { "state": "SUCCESS" },
              "plugin_result": { "logs": { "customer.reference": "cust-1", "eligible_euros": "12.34", "total_euros": "50.00" } } }
            """.trimIndent(),
        )
        val token = MaxxingConfig.parseToken(method, response, "0000", null)
        assertEquals(0, BigDecimal("12.34").compareTo(token.balance))
        assertEquals(0, BigDecimal("50.00").compareTo(token.totalBalance))
        assertEquals("cust-1", token.name)
        assertEquals(SecondaryTakePolicy.MAX, token.takePolicy)
    }

    @Test
    fun `easy2play voucher builds a voucher_number body, not a card`() {
        val paymentData = Easy2playVoucherConfig.buildInitPaymentData("V-123", null)
        assertEquals("V-123", paymentData.getString("voucher_number"))
        assertFalse(paymentData.has("card"))
        assertEquals(SecondaryTakePolicy.MAX, Easy2playVoucherConfig.takePolicy)
    }

    @Test
    fun `the registry resolves every ported partner-method key`() {
        assertEquals(IllicadoConfig, SecondaryConfigs.forMethod(Partner.Illicado, Method.GiftCard))
        assertEquals(Easy2playGiftcardConfig, SecondaryConfigs.forMethod(Partner.Other("easy2play"), Method.GiftCard))
        assertEquals(Easy2playVoucherConfig, SecondaryConfigs.forMethod(Partner.Other("easy2play"), Method.Other("voucher")))
        assertEquals(OglobaConfig, SecondaryConfigs.forMethod(Partner.Other("ogloba"), Method.GiftCard))
        assertEquals(MaxxingConfig, SecondaryConfigs.forMethod(Partner.Other("maxxing"), Method.Other("loyaltycard")))
        assertEquals(BuyboxConfig, SecondaryConfigs.forMethod(Partner.Other("buybox"), Method.GiftCard))
        assertEquals(MybeezboxConfig, SecondaryConfigs.forMethod(Partner.Other("mybeezbox"), Method.GiftCard))
        // ANCV is intentionally not registered yet (two-phase polling deferred).
        assertEquals(null, SecondaryConfigs.forMethod(Partner.Other("ancv"), Method.Other("connect")))
    }

    @Test
    fun `tokenizer distinguishes a missing CVV from a malformed one via error codes`() {
        val tokenizer = SecondaryTokenizer(
            eu.purse.core.api.PaymentApiClient("k", "http://localhost:1", "e", "s"),
        )

        val missing = assertThrows(PurseCheckoutException.InvalidSecondaryCard::class.java) {
            tokenizer.tokenize(illicadoMethod, validPan, cvv = null, amount = BigDecimal.ONE)
        }
        assertEquals(PurseErrorCode.SECONDARY_METHOD_REQUIRED_CVV, missing.code)

        val malformed = assertThrows(PurseCheckoutException.InvalidSecondaryCard::class.java) {
            tokenizer.tokenize(illicadoMethod, validPan, cvv = "12", amount = BigDecimal.ONE)
        }
        assertEquals(PurseErrorCode.SECONDARY_METHOD_WRONG_CVV_FORMAT, malformed.code)

        val badPan = assertThrows(PurseCheckoutException.InvalidSecondaryCard::class.java) {
            tokenizer.tokenize(illicadoMethod, "0000", cvv = "123", amount = BigDecimal.ONE)
        }
        assertEquals(PurseErrorCode.SECONDARY_METHOD_WRONG_PAN_FORMAT, badPan.code)
    }
}
