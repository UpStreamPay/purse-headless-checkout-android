package eu.purse.checkout.payment.secondary

import eu.purse.checkout.FakePaymentApi
import eu.purse.checkout.PurseCheckoutException
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.core.api.PaymentApiClient
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.MethodSettings
import eu.purse.core.payment.Partner
import eu.purse.core.payment.RegistrationMode
import eu.purse.core.payment.SaveTokenMode
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

    // --- host-registered issuers (the public extension point) --------------------------------------

    private val acmePartner = Partner.Other("acme")
    private val acmeVoucher = Method.Other("voucher")

    private fun acmeMethod(saveTokenMode: String? = null) = SecondaryMethod(
        "acme-voucher", acmePartner, acmeVoucher,
        MethodConfiguration(
            stable = true, template = "secondary", labels = listOf("secondary"), position = null, vault = null,
            settings = MethodSettings(
                registrationMode = RegistrationMode.OFF_SESSION,
                saveToken = saveTokenMode != null,
                saveTokenMode = saveTokenMode,
                directDebit = false,
                paymentMinAmount = null,
                paymentMaxAmount = null,
                usagePerSessionLimit = null,
                integrationMode = null,
            ),
        ),
        disabled = null,
    )

    /** An issuer the SDK doesn't ship: voucher codes, no CVV, non-splittable, its own init body. */
    private val acmeConfig = object : SecondaryConfig {
        override val takePolicy = SecondaryTakePolicy.MAX
        override val nameLogKey = "masked_card"
        override fun requiresCvv(pan: String) = false
        override fun validatePan(pan: String) = pan.startsWith("ACME-")
        override fun buildInitPaymentData(pan: String, cvv: String?) = JSONObject().put("voucher_code", pan)
        override fun buildValidatePaymentData(token: SecondaryToken) = JSONObject().put("voucher_code", token.pan)
    }

    @Test
    fun `a host-registered issuer resolves in the registry and drives the tokenizer without an SDK change`() {
        SecondaryConfigs.register(acmePartner, acmeVoucher, acmeConfig)
        try {
            assertTrue(SecondaryConfigs.isRegistered(acmePartner, acmeVoucher))
            assertEquals(acmeConfig, SecondaryConfigs.forMethod(acmeMethod()))

            FakePaymentApi().use { api ->
                api.respond("init", FakePaymentApi.successfulCardInit("12.50", maskedCard = "ACME-••42"))
                val tokenizer = SecondaryTokenizer(PaymentApiClient("k", api.apiRoot, "e", "s-acme"))

                val token = tokenizer.tokenize(acmeMethod(), "ACME-42", cvv = null, amount = BigDecimal.TEN)

                assertEquals(0, BigDecimal("12.50").compareTo(token.balance))
                assertEquals(SecondaryTakePolicy.MAX, token.takePolicy)
                assertEquals("ACME-••42", token.name)
                // The custom init body went over the wire, not the generic card shape.
                val sent = api.initRequests().single().json
                assertEquals("ACME-42", sent.getJSONObject("payment_data").getString("voucher_code"))
                assertEquals("acme", sent.getString("partner"))
                assertEquals("voucher", sent.getString("method"))
            }

            // The config's own PAN rule applies too.
            val badPan = assertThrows(PurseCheckoutException.InvalidSecondaryCard::class.java) {
                SecondaryTokenizer(PaymentApiClient("k", "http://localhost:1", "e", "s"))
                    .tokenize(acmeMethod(), "NOPE", cvv = null, amount = BigDecimal.ONE)
            }
            assertEquals(PurseErrorCode.SECONDARY_METHOD_WRONG_PAN_FORMAT, badPan.code)
        } finally {
            SecondaryConfigs.unregister(acmePartner, acmeVoucher)
        }
        assertFalse(SecondaryConfigs.isRegistered(acmePartner, acmeVoucher))
        assertEquals(null, SecondaryConfigs.forMethod(acmePartner, acmeVoucher))
    }

    @Test
    fun `reset drops host registrations and restores the shipped catalogue`() {
        SecondaryConfigs.register(acmePartner, acmeVoucher, acmeConfig)
        SecondaryConfigs.unregister(Partner.Illicado, Method.GiftCard)
        assertEquals(null, SecondaryConfigs.forMethod(Partner.Illicado, Method.GiftCard))

        SecondaryConfigs.reset()

        assertEquals(IllicadoConfig, SecondaryConfigs.forMethod(Partner.Illicado, Method.GiftCard))
        assertFalse(SecondaryConfigs.isRegistered(acmePartner, acmeVoucher))
    }

    @Test
    fun `a two-phase issuer is polled through its config until the token lands`() {
        val polling = object : SecondaryConfig by acmeConfig {
            override fun shouldPoll(initResponse: JSONObject) =
                initResponse.optJSONObject("plugin_result")?.optString("status") == "PROCESSING"
            override fun pollForToken(api: PaymentApiClient, initResponse: JSONObject, initPayload: JSONObject) =
                api.init(initPayload)
        }
        SecondaryConfigs.register(acmePartner, acmeVoucher, polling)
        try {
            FakePaymentApi().use { api ->
                var calls = 0
                api.on("init") {
                    calls += 1
                    200 to if (calls == 1) {
                        """{"status":{"state":"SUCCESS"},"plugin_result":{"status":"PROCESSING"}}"""
                    } else {
                        FakePaymentApi.successfulCardInit("7.00")
                    }
                }
                val token = SecondaryTokenizer(PaymentApiClient("k", api.apiRoot, "e", "s-poll"))
                    .tokenize(acmeMethod(), "ACME-1", cvv = null, amount = BigDecimal.TEN)

                assertEquals(2, api.initRequests().size)
                assertEquals(0, BigDecimal("7.00").compareTo(token.balance))
            }
        } finally {
            SecondaryConfigs.unregister(acmePartner, acmeVoucher)
        }
    }

    @Test
    fun `an issuer can classify its own init refusal, otherwise the generic failure applies`() {
        val classifying = object : SecondaryConfig by acmeConfig {
            override fun classifyInitError(initResponse: JSONObject): PurseCheckoutException? =
                if (initResponse.optString("error") == "VOUCHER_ALREADY_USED") {
                    PurseCheckoutException.InvalidSecondaryCard("voucher already used", PurseErrorCode.USAGE_LIMIT_ERROR)
                } else {
                    null
                }
        }
        SecondaryConfigs.register(acmePartner, acmeVoucher, classifying)
        try {
            FakePaymentApi().use { api ->
                val tokenizer = SecondaryTokenizer(PaymentApiClient("k", api.apiRoot, "e", "s-classify"))

                api.respond("init", FakePaymentApi.failedInit(errorCode = "VOUCHER_ALREADY_USED"))
                val specific = assertThrows(PurseCheckoutException.InvalidSecondaryCard::class.java) {
                    tokenizer.tokenize(acmeMethod(), "ACME-used", cvv = null, amount = BigDecimal.TEN)
                }
                assertEquals(PurseErrorCode.USAGE_LIMIT_ERROR, specific.code)

                api.respond("init", FakePaymentApi.failedInit("card refused"))
                val generic = assertThrows(PurseCheckoutException.SecondaryTokenizationFailed::class.java) {
                    tokenizer.tokenize(acmeMethod(), "ACME-other", cvv = null, amount = BigDecimal.TEN)
                }
                assertEquals(PurseErrorCode.FAILED_TO_INIT_SECONDARY_TOKEN, generic.code)
            }
        } finally {
            SecondaryConfigs.unregister(acmePartner, acmeVoucher)
        }
    }

    @Test
    fun `a temporary token carries its method's save-token policy`() {
        SecondaryConfigs.register(acmePartner, acmeVoucher, acmeConfig)
        try {
            FakePaymentApi().use { api ->
                api.respond("init", FakePaymentApi.successfulCardInit("5.00"))
                val tokenizer = SecondaryTokenizer(PaymentApiClient("k", api.apiRoot, "e", "s-save"))

                val keepable = tokenizer.tokenize(acmeMethod(saveTokenMode = "PREFER"), "ACME-a", null, BigDecimal.TEN)
                assertEquals(SaveTokenMode.PREFER, keepable.saveTokenMode)
                assertTrue(keepable.canBeRegistered)
                assertFalse(keepable.isSaved)

                val never = tokenizer.tokenize(acmeMethod(), "ACME-b", null, BigDecimal.TEN)
                assertEquals(SaveTokenMode.NEVER, never.saveTokenMode)
                assertFalse(never.canBeRegistered)
            }
        } finally {
            SecondaryConfigs.unregister(acmePartner, acmeVoucher)
        }
    }
}
