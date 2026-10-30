package eu.purse.checkout

import eu.purse.checkout.monitoring.CheckoutEventBus
import eu.purse.checkout.monitoring.CheckoutEventCode
import eu.purse.checkout.payment.item.DisabledState
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.registration.RegistrationState
import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.checkout.state.CheckoutSavedState
import eu.purse.checkout.state.SessionState
import eu.purse.core.api.InitGuard
import eu.purse.core.api.PaymentApiException
import eu.purse.core.datetime.ServerDatetimeChecker
import eu.purse.core.environment.PurseEnvironment
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.payment.IntegrationMode
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodKey
import eu.purse.core.payment.Partner
import eu.purse.core.payment.SaveTokenMode
import eu.purse.partnerui.PluginEvent
import eu.purse.partnerui.redirect.RedirectAction
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PurseHeadlessCheckoutTest {

    /** A checker that never syncs (no network) and trusts the local clock. */
    private fun offlineChecker(clock: () -> Long = System::currentTimeMillis) =
        ServerDatetimeChecker(fetchServerTime = { throw PaymentApiException("offline") }, clock = clock)

    private fun checkout(
        widgetData: String = CheckoutTestSessions.widgetData(),
        hooks: PurseCheckoutHooks? = null,
        checker: ServerDatetimeChecker = offlineChecker(),
        watchdogIntervalMs: Long = 60_000,
    ) = PurseHeadlessCheckout.init(
        widgetData,
        lockedEnvironment = null,
        hooks = hooks,
        datetimeChecker = checker,
        watchdogIntervalMs = watchdogIntervalMs,
    )

    @Test
    fun `init decodes the session and exposes its methods and split`() {
        val checkout = checkout()

        assertEquals("session-123", checkout.currentSessionId)
        assertEquals("EUR", checkout.currency)
        assertEquals(0, BigDecimal("4.80").compareTo(checkout.session.amount))
        assertEquals("tenant-abc", checkout.session.vaultsConfiguration?.purse?.tenantId)

        // 3 primaries (purse cc, paypal wallet, adyen googlepay) + 1 secondary (illicado).
        assertEquals(3, checkout.primaryMethods.size)
        assertEquals(1, checkout.secondaryMethods.size)

        // Not yet fulfilled: full amount owed, no primary chosen.
        assertFalse(checkout.isPaymentFulfilled.value)
        assertEquals(0, BigDecimal("4.80").compareTo(checkout.remainingAmountToPay.value))
    }

    @Test
    fun `merchant_specifics integration_mode and vault brands surface on the primary`() {
        val checkout = checkout()

        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }
        assertEquals(IntegrationMode.POPUP, paypal.integrationType)

        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }
        assertTrue(card.supportedBrands.isNotEmpty())
    }

    @Test
    fun `choosing a primary fulfills the payment`() {
        val checkout = checkout()

        checkout.setPrimary(checkout.primaryMethods.single { it.method == Method.CreditCard })

        assertTrue(checkout.isPaymentFulfilled.value)
    }

    @Test
    fun `a primary whose form is not complete does not fulfill the payment`() {
        val checkout = checkout()
        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }

        // What CardCoordinator reports the moment its SecureFields form mounts.
        checkout.setFulfilled(card, false)
        checkout.setPrimary(card)

        assertFalse(checkout.isPaymentFulfilled.value)
        assertThrows(PurseCheckoutException.ItemNotFulfilled::class.java) {
            runBlocking { checkout.submitPayment() }
        }

        // …and what it reports once every field validates.
        checkout.setFulfilled(card, true)

        assertTrue(checkout.isPaymentFulfilled.value)
    }

    @Test
    fun `an unfulfilled method that is not in the split does not block`() {
        val checkout = checkout()
        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }

        // The card form was mounted, then the shopper picked another method instead.
        checkout.setFulfilled(card, false)
        checkout.setPrimary(paypal)

        assertTrue(checkout.isPaymentFulfilled.value)
    }

    @Test
    fun `an applied secondary token covering the amount fulfills without a primary`() {
        val checkout = checkout()

        val giftCard = checkout.secondaryMethods.single()
        // A token would normally come from getSecondaryToken (a /init network call); build one
        // directly here to exercise the split path without the network.
        val token = SecondaryToken(
            id = "illicado-giftcard-7890",
            partner = giftCard.partner,
            method = giftCard.method,
            pan = "9250004781234567890",
            cvv = "123",
            name = "gift",
            balance = BigDecimal("10.00"),
        )
        checkout.applySecondary(token)

        assertTrue(checkout.isPaymentFulfilled.value)
        assertEquals(0, BigDecimal.ZERO.compareTo(checkout.remainingAmountToPay.value))
        assertEquals(listOf(token), checkout.appliedSecondaries)
    }

    @Test
    fun `an engaged primary the rest to pay pushes above its maximum stops being payable`() {
        // SDK-12568 (web SDK-12349): younited cb6x capped at 475 on a 480 order, plus a gift card.
        val json = """
            {
              "id": "session-max", "amount": 480.00, "currency_code": "EUR",
              "expiration_date": "2999-01-01T00:00:00Z",
              "protocols": {
                "younited": {
                  "cb6x": {
                    "configurations": {
                      "stable": true, "labels": ["primary"], "position": 1,
                      "settings": { "registration_mode": "ON_SESSION", "payment_max_amount": 475.00 }
                    }
                  }
                },
                "illicado": {
                  "giftcard": {
                    "configurations": {
                      "stable": true, "labels": ["secondary"], "position": 2,
                      "settings": { "registration_mode": "OFF_SESSION", "payment_min_amount": 0.01 }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val checkout = checkout(widgetData = CheckoutTestSessions.widgetData(json))
        val younited = checkout.primaryMethods.single()
        val giftCard = checkout.secondaryMethods.single()
        val token = SecondaryToken(
            id = "illicado-giftcard-7890",
            partner = giftCard.partner,
            method = giftCard.method,
            pan = "9250004781234567890",
            cvv = "123",
            name = "gift",
            balance = BigDecimal("100.00"),
        )

        checkout.applySecondary(token, BigDecimal("10.00"))
        checkout.setPrimary(checkout.primaryMethods.single())
        assertTrue(checkout.isPaymentFulfilled.value)
        assertEquals(younited.id, checkout.amountSplit.value.last().source.id)

        // Re-taking replaces the slice: 479 left, above younited's 475.
        checkout.applySecondary(token, BigDecimal("1.00"))

        val suspended = checkout.paymentMethods.value.single { it.id == younited.id }.disabled
        assertTrue(suspended is DisabledState.MaxAmountExceeded)
        assertFalse(checkout.isPaymentFulfilled.value)
        assertEquals(listOf(token.id), checkout.amountSplit.value.map { it.source.id })
        assertThrows(PurseCheckoutException.PaymentNotFulfilled::class.java) {
            runBlocking { checkout.submitPayment() }
        }
        assertEquals(0, BigDecimal("479.00").compareTo(checkout.remainingAmountToPay.value))

        // Back under the maximum: the code lifts and the method is selectable again.
        checkout.applySecondary(token, BigDecimal("10.00"))
        assertNull(checkout.paymentMethods.value.single { it.id == younited.id }.disabled)
        checkout.setPrimary(checkout.primaryMethods.single())
        assertTrue(checkout.isPaymentFulfilled.value)
    }

    @Test
    fun `a suspended or unreachable method never appears in the public list`() {
        val json = """
            {
              "id": "session-filter", "amount": 4.80, "currency_code": "EUR",
              "expiration_date": "2999-01-01T00:00:00Z",
              "protocols": {
                "purse": {
                  "creditcard": {
                    "configurations": {
                      "stable": true, "labels": ["primary"], "position": 1,
                      "settings": { "registration_mode": "ON_SESSION" }
                    }
                  }
                },
                "klarna": {
                  "installments": {
                    "configurations": {
                      "stable": false, "labels": ["primary"], "position": 2,
                      "settings": { "registration_mode": "ON_SESSION" }
                    }
                  }
                },
                "alma": {
                  "cb3x": {
                    "configurations": {
                      "stable": true, "labels": ["primary"], "position": 3,
                      "settings": { "registration_mode": "ON_SESSION", "payment_min_amount": 50.00 }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val checkout = checkout(widgetData = CheckoutTestSessions.widgetData(json))

        // The suspended (stable=false) and unreachable (min 50 > amount 4.80) methods are filtered.
        assertEquals(listOf("purse-creditcard"), checkout.paymentMethods.value.map { it.id })
        assertEquals(1, checkout.primaryMethods.size)

        // …and a split change never resurrects them (their states are not recomputable).
        checkout.setPrimary(checkout.primaryMethods.single())
        assertEquals(listOf("purse-creditcard"), checkout.paymentMethods.value.map { it.id })
    }

    @Test
    fun `the watchdog flips the session to EXPIRED once the server clock passes expiration`() {
        // The fixture expires in 2999; a clock reading year 3000 makes it expired immediately.
        val year3000 = java.time.Instant.parse("3000-01-01T00:00:00Z").toEpochMilli()
        val checkout = checkout(checker = offlineChecker(clock = { year3000 }), watchdogIntervalMs = 1)

        val deadline = System.currentTimeMillis() + 5_000
        while (checkout.sessionState.value != SessionState.EXPIRED && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(SessionState.EXPIRED, checkout.sessionState.value)
        checkout.teardown()
    }

    @Test
    fun `teardown stops the watchdog`() {
        val year3000 = java.time.Instant.parse("3000-01-01T00:00:00Z").toEpochMilli()
        // A long first tick leaves time to tear down before the expiry check ever runs.
        val checkout = checkout(checker = offlineChecker(clock = { year3000 }), watchdogIntervalMs = 200)

        checkout.teardown()
        Thread.sleep(400)

        assertEquals(SessionState.READY, checkout.sessionState.value)
    }

    @Test
    fun `a throwing onBeforeValidate hook aborts the submit and leaves the checkout submittable`() {
        var hookCalls = 0
        var hookSplits = -1
        val checkout = checkout(
            hooks = PurseCheckoutHooks(onBeforeValidate = { splits ->
                hookCalls++
                hookSplits = splits.size
                throw IllegalStateException("merchant said no")
            }),
        )
        // A secondary-only split reaches the hook without needing a partner plugin or the network.
        val giftCard = checkout.secondaryMethods.single()
        checkout.applySecondary(
            SecondaryToken(
                id = "illicado-giftcard-1", partner = giftCard.partner, method = giftCard.method,
                pan = "9250004781234567890", cvv = "123", name = "gift", balance = BigDecimal("10.00"),
            ),
        )
        val seen = recordEvents()

        try {
            val thrown = assertThrows(PurseCheckoutException.HookFailed::class.java) {
                runBlocking { checkout.submitPayment() }
            }
            // Typed like web's ON_BEFORE_VALIDATE_HOOK_FAILED, the merchant's own exception as cause.
            assertEquals(PurseErrorCode.ON_BEFORE_VALIDATE_HOOK_FAILED, thrown.code)
            assertEquals("onBeforeValidate", thrown.hook)
            assertEquals("merchant said no", thrown.cause?.message)
            assertEquals(1, hookSplits)
            assertTrue(seen.contains(CheckoutEventCode.HEADLESS_CHECKOUT_ON_BEFORE_VALIDATE_HOOK_FAILED))

            // Nothing was sent, so nothing moved: split intact, still fulfilled, back to READY …
            assertEquals(SessionState.READY, checkout.sessionState.value)
            assertTrue(checkout.isPaymentFulfilled.value)
            assertEquals(1, checkout.amountSplit.value.size)
            assertEquals(1, checkout.appliedSecondaries.size)

            // … and a second submit reaches the hook again instead of being refused.
            assertThrows(PurseCheckoutException.HookFailed::class.java) {
                runBlocking { checkout.submitPayment() }
            }
            assertEquals(2, hookCalls)
        } finally {
            seen.stop()
        }
    }

    @Test
    fun `without an onRedirection hook every host lets the SDK open its own WebView`() {
        val checkout = checkout()
        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }
        val action = RedirectAction.Get("https://acs.example/challenge")

        assertFalse(runBlocking { checkout.host(card).delegateRedirection(action) })
        assertFalse(runBlocking { checkout.host(paypal).delegateRedirection(action) })
        assertFalse(runBlocking { checkout.host(paypal).delegateRedirection(action) })
    }

    @Test
    fun `a handling onRedirection hook takes the redirect over`() {
        var handed: RedirectAction? = null
        val checkout = checkout(
            hooks = PurseCheckoutHooks(onRedirection = { action ->
                handed = action
                true
            })
        )
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }
        val action = RedirectAction.PostForm("https://acs.example/challenge", "PaReq=abc")

        assertTrue(runBlocking { checkout.host(paypal).delegateRedirection(action) })
        assertEquals(action, handed)
    }

    @Test
    fun `a throwing onRedirection hook surfaces typed and leaves the checkout intact`() {
        val checkout = checkout(
            hooks = PurseCheckoutHooks(onRedirection = { throw IllegalStateException("no browser available") }),
        )
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }
        checkout.setPrimary(paypal)
        val seen = recordEvents()

        try {
            val thrown = assertThrows(PurseCheckoutException.HookFailed::class.java) {
                runBlocking {
                    checkout.host(paypal).delegateRedirection(RedirectAction.Get("https://acs.example/challenge"))
                }
            }
            assertEquals(PurseErrorCode.POST_VALIDATE_FAILED, thrown.code)
            assertEquals("onRedirection", thrown.hook)
            assertEquals("no browser available", thrown.cause?.message)
            assertTrue(seen.contains(CheckoutEventCode.HEADLESS_CHECKOUT_CLIENT_HOOK_ERROR))

            // The hook failing is the merchant's problem, not the split's.
            assertTrue(checkout.isPaymentFulfilled.value)
            assertEquals(paypal.id, checkout.amountSplit.value.single().source.id)
            assertEquals(SessionState.READY, checkout.sessionState.value)
        } finally {
            seen.stop()
        }
    }

    /** Every code posted on the (singleton) monitoring bus from now until [RecordedEvents.stop]. */
    private class RecordedEvents(private val codes: MutableList<CheckoutEventCode>, val stop: () -> Unit) {
        fun contains(code: CheckoutEventCode) = codes.contains(code)
    }

    private fun recordEvents(): RecordedEvents {
        val codes = mutableListOf<CheckoutEventCode>()
        val unsubscribe = CheckoutEventBus.getInstance().subscribe(CheckoutEventBus.Scope.ALL) { codes.add(it.code) }
        return RecordedEvents(codes, unsubscribe)
    }

    @Test
    fun `clearPrimarySplit removes the primary but keeps applied secondaries`() {
        val checkout = checkout()
        val giftCard = checkout.secondaryMethods.single()
        checkout.applySecondary(
            SecondaryToken(
                id = "illicado-giftcard-1", partner = giftCard.partner, method = giftCard.method,
                pan = "9250004781234567890", cvv = "123", name = "gift", balance = BigDecimal("2.00"),
            ),
        )
        checkout.setPrimary(checkout.primaryMethods.single { it.method == Method.CreditCard })
        assertTrue(checkout.isPaymentFulfilled.value)

        checkout.clearPrimarySplit()

        assertFalse(checkout.isPaymentFulfilled.value)
        assertEquals(1, checkout.appliedSecondaries.size)
    }

    @Test
    fun `a fatal plugin event deactivates the method and clears it from the split`() {
        val checkout = checkout()
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }
        checkout.setPrimary(paypal)
        assertTrue(checkout.isPaymentFulfilled.value)

        checkout.pluginContext(paypal).dispatchEvent(PluginEvent.ClientError("sdk died"))

        val item = checkout.paymentMethods.value.single { it.id == paypal.id }
        assertTrue(item.disabled is DisabledState.PartnerUiFatalError)
        assertFalse("the split was cleared alongside the deactivation", checkout.isPaymentFulfilled.value)
        assertEquals(0, BigDecimal("4.80").compareTo(checkout.remainingAmountToPay.value))
    }

    @Test
    fun `a recoverable partner error dims the method and Ready brings it back`() {
        val checkout = checkout()
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }
        val context = checkout.pluginContext(paypal)

        context.dispatchEvent(PluginEvent.PartnerError("blip", recoverable = true))
        assertTrue(
            checkout.paymentMethods.value.single { it.id == paypal.id }.disabled
                is DisabledState.PartnerUiRecoverableError,
        )

        context.dispatchEvent(PluginEvent.Ready)
        assertNull(checkout.paymentMethods.value.single { it.id == paypal.id }.disabled)
    }

    @Test
    fun `an EndOfSession plugin event terminates the session`() {
        val checkout = checkout()
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }

        checkout.pluginContext(paypal).dispatchEvent(PluginEvent.EndOfSession)

        assertEquals(SessionState.TERMINATED, checkout.sessionState.value)
    }

    @Test
    fun `the wallet hosts commit their item on approval, not before`() {
        val checkout = checkout()
        val googlePay = checkout.primaryMethods.single { it.method == Method.GooglePay }
        val host = checkout.host(googlePay)
        assertFalse("prepare/tap must not touch the split", checkout.isPaymentFulfilled.value)

        host.setAsPrimary() // what the coordinator calls once the sheet is approved

        assertTrue(checkout.isPaymentFulfilled.value)
        assertEquals(googlePay.id, checkout.amountSplit.value.single().source.id)
    }

    // --- saved cards (wallet) ---------------------------------------------------------------------

    @Test
    fun `the session's integrated tokens surface on paymentTokens, not on paymentMethods`() {
        val checkout = checkout()

        val token = checkout.paymentTokens.value.single()
        assertEquals("tok-1", token.id)
        assertEquals("my visa", token.name)
        assertEquals("VISA •••• 4242", token.label)
        assertEquals("424242xxxxxx4242", token.maskedPan)
        assertTrue(token.favorite)
        assertTrue(
            "network token + network_token setting absent -> depends on settings",
            !token.paysWithoutCvv || token.networkTokenAvailable,
        )
        assertTrue(checkout.paymentMethods.value.none { it.id == "tok-1" })
        // The method list is unchanged by the token's presence.
        assertEquals(3, checkout.primaryMethods.size)
    }

    /** The fixture's card method with the merchant's `pay_token_no_cvv` gate switched on. */
    private fun noCvvWidgetData(): String = CheckoutTestSessions.widgetData(
        CheckoutTestSessions.samplePaymentSessionJson.replace(
            "\"save_token\": true, \"save_token_mode\": \"PREFER\"",
            "\"save_token\": true, \"save_token_mode\": \"PREFER\", \"merchant_specifics\": { \"pay_token_no_cvv\": true }",
        ),
    )

    @Test
    fun `a saved token that still needs its CVV is the primary but not fulfilled until the CVV form validates`() {
        val checkout = checkout()
        val token = checkout.paymentTokens.value.single()
        // network_token.available is true on the fixture, but neither pay_token_no_cvv nor the
        // network_token setting is — so the PSP still wants the CVV.
        assertTrue(token.requiresCvv)

        checkout.setPrimaryToken(token)

        assertEquals(token.id, checkout.amountSplit.value.single().source.id)
        assertFalse("the split is covered, but the CVV hasn't been collected", checkout.isPaymentFulfilled.value)
        val refused = assertThrows(PurseCheckoutException.ItemNotFulfilled::class.java) {
            runBlocking { checkout.submitPayment() }
        }
        assertTrue(refused.message!!.contains(token.id))

        // The CVV-only form reports valid (what CardCoordinator does on SecureFields' onFormValid).
        checkout.setFulfilled(token, true)
        assertTrue(checkout.isPaymentFulfilled.value)

        // …and invalid again (the shopper cleared the field, or the form unmounted).
        checkout.setFulfilled(token, false)
        assertFalse(checkout.isPaymentFulfilled.value)
    }

    @Test
    fun `a saved token behind the no-CVV gate is the primary and fulfills the payment at once`() {
        val checkout = checkout(noCvvWidgetData())
        val token = checkout.paymentTokens.value.single()
        assertTrue(token.paysWithoutCvv)
        assertFalse(token.requiresCvv)

        checkout.setPrimaryToken(token)

        assertTrue(checkout.isPaymentFulfilled.value)
    }

    @Test
    fun `the card host for a saved token carries the token and its CVV requirement into the plugin`() {
        val checkout = checkout()
        val token = checkout.paymentTokens.value.single()

        val host = checkout.host(token)
        val context = host.buildContext()

        assertEquals("tok-value-1", context.token?.value)
        assertEquals("VISA", context.token?.selectedNetwork)
        assertEquals("J DOE", context.token?.holderName)
        assertTrue(context.token?.requiresCvv == true)
        // Brands come from the token's method vault, same as for the fresh-card host.
        val cardContext = checkout.host(checkout.primaryMethods.single { it.method == Method.CreditCard }).buildContext()
        assertEquals(cardContext.configuration?.vault?.supportedBrands, context.configuration?.vault?.supportedBrands)
        assertTrue(context.configuration?.vault?.supportedBrands.orEmpty().isNotEmpty())

        val noCvvHost = checkout(noCvvWidgetData()).let { it.host(it.paymentTokens.value.single()) }
        assertTrue(noCvvHost.buildContext().token?.requiresCvv == false)
    }

    /** The fixture plus a vaulted PayPal account under `paypal.wallet` — no card, no vault, hence no CVV concept. */
    @Test
    fun `a saved PayPal token has no CVV to collect and is payable the moment it is selected`() {
        val checkout = checkout(CheckoutTestSessions.widgetData(CheckoutTestSessions.paypalTokenSessionJson()))
        val paypal = checkout.paymentTokens.value.single { it.id == "pp-1" }

        assertEquals(Partner.Paypal, paypal.partner)
        assertEquals("buyer@example.com", paypal.label)
        assertFalse("paypal/wallet carries no vault — not a card-on-file", paypal.isVaultedCard)
        assertFalse("a CVV is a card-form concept: nothing to collect on a PayPal token", paypal.requiresCvv)
        assertTrue(paypal.paysWithoutCvv)
        // The card-on-file next to it keeps asking for its CVV — the rule only bypasses non-card tokens.
        assertTrue(checkout.paymentTokens.value.single { it.id == "tok-1" }.requiresCvv)
        assertEquals(3, checkout.primaryMethods.size)

        checkout.setPrimaryToken(paypal)

        assertEquals("pp-1", checkout.amountSplit.value.single().source.id)
        assertTrue("no CVV gate armed for a PayPal token", checkout.isPaymentFulfilled.value)
        // The PayPal host hands the token to the plugin, CVV-free.
        val context = checkout.host(paypal).buildContext()
        assertEquals("pp-value-1", context.token?.value)
        assertFalse(context.token!!.requiresCvv)
    }

    @Test
    fun `paying with a saved PayPal token inits then validates with the token and the init step id`() {
        FakePaymentApi().use { api ->
            api.respond("init", """{ "status": { "state": "SUCCESS" }, "step_id": 31, "plugin_result": { "logs": {} } }""")
            api.respond("validate", """{"global_status":"SUCCESS","results":[]}""")
            val checkout = checkout(CheckoutTestSessions.widgetData(CheckoutTestSessions.paypalTokenSessionJson(), api.apiRoot))
            val paypal = checkout.paymentTokens.value.single { it.id == "pp-1" }
            checkout.setPrimaryToken(paypal)

            runBlocking { checkout.submitPayment() }

            // The plugin ran the pay-now /init itself — web's token-mode initSession.
            val init = api.initRequests().single().json
            assertEquals("paypal", init.getString("partner"))
            assertEquals("wallet", init.getString("method"))
            assertFalse(init.getJSONObject("payment_preferences").getBoolean("registration"))

            // …and /validate carries the token plus that init's step id, no card block, no CVV token.
            val item = org.json.JSONArray(api.requests.single { it.path.endsWith("/validate") }.body).getJSONObject(0)
            assertEquals("paypal", item.getString("partner"))
            assertEquals("wallet", item.getString("method"))
            assertEquals(31, item.getInt("step_id"))
            val paymentData = item.getJSONObject("payment_data")
            assertEquals("pp-value-1", paymentData.getString("token"))
            assertFalse("no card behind a PayPal token", paymentData.has("card"))
            assertFalse(paymentData.has("vault_vendor_token_id"))
        }
    }

    @Test
    fun `only a primary method or a saved token can be driven by a coordinator`() {
        val checkout = checkout()

        assertThrows(IllegalArgumentException::class.java) {
            checkout.host(checkout.secondaryMethods.single())
        }
    }

    @Test
    fun `registration follows the save-token policy`() {
        val checkout = checkout()
        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }

        // save_token_mode PREFER: shopper can choose, registered by default.
        assertTrue(card.canBeRegistered)
        assertTrue(checkout.registrationOf(card).registered)
        checkout.register(card, registered = false)
        assertFalse(checkout.registrationOf(card).registered)

        // No save_token settings at all: NEVER — registering throws.
        assertFalse(paypal.canBeRegistered)
        val thrown = assertThrows(PurseCheckoutException.WalletOperationFailed::class.java) {
            checkout.register(paypal)
        }
        assertEquals(PurseErrorCode.METHOD_NOT_REGISTERABLE, thrown.code)
    }

    @Test
    fun `a token label must be valid and unique`() {
        val checkout = checkout()
        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }

        val invalid = assertThrows(PurseCheckoutException.WalletOperationFailed::class.java) {
            checkout.register(card, name = "no-dashes-allowed!")
        }
        assertEquals(PurseErrorCode.INVALID_TOKEN_NAME, invalid.code)

        val duplicate = assertThrows(PurseCheckoutException.WalletOperationFailed::class.java) {
            checkout.register(card, name = "my visa")
        }
        assertEquals(PurseErrorCode.TOKEN_NAME_EXISTS, duplicate.code)

        checkout.register(card, name = "holiday card")
        assertEquals("holiday card", checkout.registrationOf(card).name)
    }

    @Test
    fun `token management requires a wallet session`() {
        val checkout = checkout()
        val token = checkout.paymentTokens.value.single()

        val thrown = assertThrows(PurseCheckoutException.WalletOperationFailed::class.java) {
            runBlocking { checkout.editToken(token, "new name") }
        }
        assertEquals(PurseErrorCode.MISSING_WALLET_SESSION, thrown.code)
    }

    @Test
    fun `an information_request session strips saved tokens`() {
        val json = """
            {
              "id": "session-info", "information_request": true, "currency_code": "EUR",
              "expiration_date": "2999-01-01T00:00:00Z",
              "protocols": {
                "purse": {
                  "creditcard": {
                    "configurations": {
                      "stable": true, "labels": ["primary"],
                      "settings": { "registration_mode": "OFF_SESSION" }
                    },
                    "integrated_tokens": [
                      { "id": "tok-9", "value": "v", "favorite": false, "description": {} }
                    ]
                  }
                }
              }
            }
        """.trimIndent()
        val checkout = checkout(widgetData = CheckoutTestSessions.widgetData(json))

        assertTrue(checkout.paymentTokens.value.isEmpty())
    }

    @Test
    fun `submitPayment fails while no plugin is registered for the primary`() {
        // A partner/method pair no shipped plugin claims: no vault, no template, no fallback.
        val json = JSONObject(CheckoutTestSessions.samplePaymentSessionJson)
        json.getJSONObject("protocols").put(
            "acme",
            JSONObject().put(
                "voucher",
                JSONObject().put(
                    "configurations",
                    JSONObject("""{"stable": true, "labels": ["acme", "voucher", "primary"], "position": 9, "settings": {}}"""),
                ),
            ),
        )
        val checkout = checkout(widgetData = CheckoutTestSessions.widgetData(json.toString()))
        checkout.setPrimary(checkout.primaryMethods.single { it.partner == Partner.Other("acme") })

        assertThrows(PurseCheckoutException.NoPluginRegistered::class.java) {
            runBlocking { checkout.submitPayment() }
        }
    }

    @Test
    fun `disabledMethods drops a method the session offers, saved instruments included, on init and setSession`() {
        val disabled = setOf(MethodKey(Partner.Paypal, Method.Wallet), MethodKey.of("purse", "creditcard"))
        val checkout = PurseHeadlessCheckout.init(
            CheckoutTestSessions.widgetData(),
            datetimeChecker = offlineChecker(),
            disabledMethods = disabled,
        )

        assertTrue(checkout.primaryMethods.none { it.partner == Partner.Paypal })
        assertTrue(checkout.primaryMethods.none { it.method == Method.CreditCard })
        assertTrue("the saved card rides with its method", checkout.paymentTokens.value.isEmpty())
        assertTrue(checkout.primaryMethods.any { it.method == Method.GooglePay })
        assertEquals(disabled, checkout.disabledMethods)

        runBlocking { checkout.setSession(CheckoutTestSessions.widgetData(CheckoutTestSessions.sessionWithSavedGiftCard())) }
        assertTrue(checkout.primaryMethods.none { it.partner == Partner.Paypal })
    }

    // --- saved state (process death) -----------------------------------------------------------------

    private fun giftToken(checkout: PurseHeadlessCheckout, balance: String = "2.00") = SecondaryToken(
        id = "illicado-giftcard-0001",
        partner = checkout.secondaryMethods.single().partner,
        method = checkout.secondaryMethods.single().method,
        pan = "9250004781234560001",
        cvv = "123",
        name = "gift",
        balance = BigDecimal(balance),
        saveTokenMode = SaveTokenMode.PREFER,
    )

    @Test
    fun `saveState then restore brings back the primary, the applied gift card, the save decisions and the collected data`() {
        val checkout = checkout()
        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }
        checkout.setPrimary(card)
        checkout.register(card, registered = true, name = "holiday card")
        val gift = giftToken(checkout)
        checkout.applySecondary(gift, BigDecimal("1.50"))
        checkout.registerSecondary(gift, registered = true, name = "gift one")
        checkout.host(card).setCollected(mapOf("token" to "vault-form-token", "network" to "VISA", "attempt" to 2, "skipped" to { }))

        val restored = PurseHeadlessCheckout.restore(
            CheckoutSavedState.fromJson(checkout.saveState().toJson()),
            datetimeChecker = offlineChecker(),
        )

        assertEquals(card.id, restored.amountSplit.value.single { it.source is PrimaryMethod }.source.id)
        assertEquals(0, BigDecimal("1.50").compareTo(restored.amountSplit.value.single { it.source.id == gift.id }.amount))
        assertEquals(0, BigDecimal("3.30").compareTo(restored.amountSplit.value.single { it.source is PrimaryMethod }.amount))
        assertEquals(RegistrationState(true, "holiday card"), restored.registrationOf(restored.primaryMethods.single { it.id == card.id }))
        assertEquals(RegistrationState(true, "gift one"), restored.registrationOf(gift))
        val collected = restored.host(restored.primaryMethods.single { it.id == card.id }).buildContext().collected
        assertEquals("vault-form-token", collected["token"])
        assertEquals(2, collected["attempt"])
        assertFalse("a lambda is not JSON and is left out", collected.containsKey("skipped"))
        assertEquals(SessionState.READY, restored.sessionState.value)
        assertFalse(checkout.saveState().submitted)
    }

    @Test
    fun `a restored snapshot taken after a submit leaves the session spent`() {
        val checkout = checkout()
        checkout.setPrimary(checkout.primaryMethods.single { it.method == Method.CreditCard })
        val json = JSONObject(checkout.saveState().toJson()).put("submitted", true).toString()

        val restored = PurseHeadlessCheckout.restore(CheckoutSavedState.fromJson(json), datetimeChecker = offlineChecker())

        assertTrue(CheckoutSavedState.fromJson(json).submitted)
        assertEquals(SessionState.SUBMITTED, restored.sessionState.value)
        assertThrows(PurseCheckoutException.SubmitBlocked::class.java) { runBlocking { restored.submitPayment() } }
    }

    @Test
    fun `restore skips what the session no longer offers and what the split cannot honour`() {
        val checkout = checkout()
        checkout.setPrimary(checkout.primaryMethods.single { it.partner == Partner.Paypal })
        checkout.applySecondary(giftToken(checkout, balance = "10.00"), BigDecimal("1.00"))
        val json = JSONObject(checkout.saveState().toJson())
        json.put("primaryId", "acme-voucher")
        json.getJSONArray("secondaries").getJSONObject(0).put("amount", "99.00") // above what is left to pay

        val restored = PurseHeadlessCheckout.restore(CheckoutSavedState.fromJson(json.toString()), datetimeChecker = offlineChecker())

        assertTrue(restored.amountSplit.value.isEmpty())
        assertEquals(0, restored.session.amount.compareTo(restored.remainingAmountToPay.value))
    }

    @Test
    fun `saveState carries the lock and the disabled methods, and refuses to run after teardown`() {
        val checkout = PurseHeadlessCheckout.init(
            CheckoutTestSessions.widgetData(),
            lockedEnvironment = PurseEnvironment.SANDBOX,
            datetimeChecker = offlineChecker(),
            disabledMethods = setOf(MethodKey(Partner.Paypal, Method.Wallet)),
        )
        val restored = PurseHeadlessCheckout.restore(checkout.saveState(), datetimeChecker = offlineChecker())
        assertEquals(setOf(MethodKey(Partner.Paypal, Method.Wallet)), restored.disabledMethods)
        assertTrue(restored.primaryMethods.none { it.partner == Partner.Paypal })

        checkout.teardown()
        assertThrows(PurseCheckoutException.CheckoutTornDown::class.java) { checkout.saveState() }
    }

    @Test
    fun `a snapshot from an unknown version or without a session is refused`() {
        assertThrows(IllegalArgumentException::class.java) { CheckoutSavedState.fromJson("""{"version": 99, "widgetData": "x"}""") }
        assertThrows(IllegalArgumentException::class.java) { CheckoutSavedState.fromJson("""{"version": 1}""") }
        assertThrows(IllegalArgumentException::class.java) { CheckoutSavedState.fromJson("not json") }
    }

    // --- saved gift cards (secondary tokens) --------------------------------------------------------

    /**
     * `InitGuard` is process-wide and keys `/init` calls on `(session, partner, method, amount)` —
     * every test here re-inits the same fixture card, so a failure scripted in one test would
     * otherwise fast-reject the next test's call with the stale error.
     */
    @Before
    fun resetInitGuard() = InitGuard.reset()

    /**
     * A checkout on the saved-gift-card fixture whose background balance `/init` is answered by a
     * throwaway fake with a definitive, non-terminal 400 (the fake's default 404 carries `NOT_FOUND`,
     * which the SDK reads as session-terminal), and waited for — so the token is settled (disabled
     * `TOKEN_INIT_FAILED`) before the test body runs and nothing lingers past the test.
     */
    private fun savedGiftCardCheckoutOffline(): PurseHeadlessCheckout {
        val api = FakePaymentApi()
        api.on("init") { 400 to FakePaymentApi.failedInit("offline fixture", errorCode = "OFFLINE_FIXTURE") }
        val checkout = checkout(
            widgetData = CheckoutTestSessions.widgetData(CheckoutTestSessions.sessionWithSavedGiftCard(), api.apiRoot),
        )
        awaitUntil { checkout.savedSecondaryTokens.value.single().let { it.balanceResolved || it.disabled != null } }
        api.close()
        return checkout
    }

    /** Poll [condition] on the checkout's flows — its saved-token `/init` runs on a background IO coroutine. */
    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met within ${timeoutMs}ms")
            Thread.sleep(20)
        }
    }

    @Test
    fun `a saved gift card surfaces on savedSecondaryTokens with a background-resolved balance, and applies to the split`() {
        FakePaymentApi().use { api ->
            api.respond("init", FakePaymentApi.successfulCardInit("12.50"))
            val checkout = checkout(
                widgetData = CheckoutTestSessions.widgetData(CheckoutTestSessions.sessionWithSavedGiftCard(), api.apiRoot),
            )

            val listed = checkout.savedSecondaryTokens.value.single()
            assertEquals("gc-1", listed.id)
            assertEquals("birthday card", listed.name)
            assertEquals("9250004781234567890", listed.pan)
            assertEquals("123", listed.cvv)
            // Never a method, never a primary token.
            assertTrue(checkout.paymentMethods.value.none { it.id == "gc-1" })
            assertTrue(checkout.paymentTokens.value.none { it.id == "gc-1" })
            assertEquals(1, checkout.secondaryMethods.size)

            awaitUntil { checkout.savedSecondaryTokens.value.single().balanceResolved }
            val resolved = checkout.savedSecondaryTokens.value.single()
            assertEquals(0, BigDecimal("12.50").compareTo(resolved.balance))
            assertNull(resolved.disabled)
            // The re-init sent the stored number and pin, as web's buildSecondaryPaymentToken does.
            val sent = api.initRequests().single().json
            assertEquals("illicado", sent.getString("partner"))
            assertEquals("9250004781234567890", sent.getJSONObject("payment_data").getJSONObject("card").getString("number"))
            assertEquals("123", sent.getJSONObject("payment_data").getJSONObject("card").getString("csc"))

            // Applying it draws the whole 4.80 order down — no primary needed.
            checkout.applySecondary(resolved)
            assertEquals(0, BigDecimal.ZERO.compareTo(checkout.remainingAmountToPay.value))
            assertTrue(checkout.isPaymentFulfilled.value)
            val split = checkout.amountSplit.value.single()
            assertEquals("gc-1", split.source.id)
            assertTrue((split.source as SecondaryToken).isSaved)

            checkout.removeSecondary(resolved)
            assertEquals(0, BigDecimal("4.80").compareTo(checkout.remainingAmountToPay.value))
        }
    }

    @Test
    fun `a saved gift card cannot be applied before its balance is resolved`() {
        FakePaymentApi().use { api ->
            api.on("init") {
                Thread.sleep(400)
                200 to FakePaymentApi.successfulCardInit("12.50")
            }
            val checkout = checkout(
                widgetData = CheckoutTestSessions.widgetData(CheckoutTestSessions.sessionWithSavedGiftCard(), api.apiRoot),
            )

            val pending = checkout.savedSecondaryTokens.value.single()
            assertFalse(pending.balanceResolved)
            val thrown = assertThrows(PurseCheckoutException.SecondaryTokenizationFailed::class.java) {
                checkout.applySecondary(pending)
            }
            assertEquals(PurseErrorCode.SECONDARY_TOKEN_EMPTY_BALANCE, thrown.code)
            assertTrue(checkout.amountSplit.value.isEmpty())

            // The stale instance is fine once the balance lands — the latest state is what's applied.
            awaitUntil { checkout.savedSecondaryTokens.value.single().balanceResolved }
            checkout.applySecondary(pending)
            assertEquals("gc-1", checkout.amountSplit.value.single().source.id)
        }
    }

    @Test
    fun `a saved gift card whose init fails stays listed but disabled with TOKEN_INIT_FAILED`() {
        FakePaymentApi().use { api ->
            // A 4xx is a definitive answer (no InitGuard retry), so the failure lands immediately.
            api.on("init") { 400 to FakePaymentApi.failedInit("card blocked", errorCode = "CARD_BLOCKED") }
            val checkout = checkout(
                widgetData = CheckoutTestSessions.widgetData(CheckoutTestSessions.sessionWithSavedGiftCard(), api.apiRoot),
            )

            awaitUntil { checkout.savedSecondaryTokens.value.single().disabled != null }
            val failed = checkout.savedSecondaryTokens.value.single()
            assertEquals(DisabledState.TokenInitFailed, failed.disabled)
            assertFalse(failed.balanceResolved)
            // Not a session-terminal answer: the checkout itself is still usable.
            assertEquals(SessionState.READY, checkout.sessionState.value)

            val thrown = assertThrows(PurseCheckoutException.SecondaryTokenizationFailed::class.java) {
                checkout.applySecondary(failed)
            }
            assertEquals(PurseErrorCode.FAILED_TO_INIT_SECONDARY_TOKEN, thrown.code)
        }
    }

    @Test
    fun `a session-terminal answer on a saved gift card's init terminates the checkout`() {
        FakePaymentApi().use { api ->
            api.on("init") { 400 to FakePaymentApi.failedInit("expired", errorCode = "SESSION_EXPIRED") }
            val checkout = checkout(
                widgetData = CheckoutTestSessions.widgetData(CheckoutTestSessions.sessionWithSavedGiftCard(), api.apiRoot),
            )

            awaitUntil { checkout.sessionState.value == SessionState.TERMINATED }
        }
    }

    @Test
    fun `managing a saved gift card requires a wallet session`() {
        // Route the background balance /init at a fake so this network-free test never reaches the
        // real sandbox host (whose slow transport failure would leak into the next test's guard).
        val checkout = savedGiftCardCheckoutOffline()
        val saved = checkout.savedSecondaryTokens.value.single()

        val rename = assertThrows(PurseCheckoutException.WalletOperationFailed::class.java) {
            runBlocking { checkout.editToken(saved, "new name") }
        }
        assertEquals(PurseErrorCode.MISSING_WALLET_SESSION, rename.code)
        val delete = assertThrows(PurseCheckoutException.WalletOperationFailed::class.java) {
            runBlocking { checkout.deleteToken(saved) }
        }
        assertEquals(PurseErrorCode.MISSING_WALLET_SESSION, delete.code)
    }

    @Test
    fun `a saved gift card's name counts towards label uniqueness`() {
        // Route the background balance /init at a fake so this network-free test never reaches the
        // real sandbox host (whose slow transport failure would leak into the next test's guard).
        val checkout = savedGiftCardCheckoutOffline()
        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }

        // "birthday card" is the saved gift card's name (the fixture carries no display_token).
        val duplicate = assertThrows(PurseCheckoutException.WalletOperationFailed::class.java) {
            checkout.register(card, name = "birthday card")
        }
        assertEquals(PurseErrorCode.TOKEN_NAME_EXISTS, duplicate.code)
    }

    // --- keeping a temporary gift card ---------------------------------------------------------------

    private fun temporaryGiftCard(
        saveTokenMode: eu.purse.core.payment.SaveTokenMode,
        id: String = "illicado-giftcard-7890",
    ) = SecondaryToken(
        id = id,
        partner = Partner.Illicado,
        method = Method.GiftCard,
        pan = "9250004781234567890",
        cvv = "123",
        name = "",
        balance = BigDecimal("10.00"),
        saveTokenMode = saveTokenMode,
    )

    @Test
    fun `a temporary gift card's registration follows its method's save-token policy`() {
        val checkout = checkout()

        // PREFER: the shopper can choose, and it starts registered.
        val keepable = temporaryGiftCard(eu.purse.core.payment.SaveTokenMode.PREFER)
        assertTrue(keepable.canBeRegistered)
        assertTrue(checkout.registrationOf(keepable).registered)
        checkout.registerSecondary(keepable, registered = false)
        assertFalse(checkout.registrationOf(keepable).registered)
        checkout.registerSecondary(keepable, name = "gift one")
        assertEquals(RegistrationState(registered = true, name = "gift one"), checkout.registrationOf(keepable))

        // NEVER (the fixture's illicado has no save_token settings): asking throws.
        val never = temporaryGiftCard(eu.purse.core.payment.SaveTokenMode.NEVER, id = "illicado-giftcard-0000")
        assertFalse(checkout.registrationOf(never).registered)
        val thrown = assertThrows(PurseCheckoutException.WalletOperationFailed::class.java) {
            checkout.registerSecondary(never)
        }
        assertEquals(PurseErrorCode.TOKEN_NOT_REGISTERABLE, thrown.code)

        // Labels obey the same rules as cards.
        val invalid = assertThrows(PurseCheckoutException.WalletOperationFailed::class.java) {
            checkout.registerSecondary(keepable, name = "bad-label!")
        }
        assertEquals(PurseErrorCode.INVALID_TOKEN_NAME, invalid.code)
        val duplicate = assertThrows(PurseCheckoutException.WalletOperationFailed::class.java) {
            checkout.registerSecondary(keepable, name = "my visa")
        }
        assertEquals(PurseErrorCode.TOKEN_NAME_EXISTS, duplicate.code)
    }

    @Test
    fun `a saved gift card is never registered again`() {
        // Route the background balance /init at a fake so this network-free test never reaches the
        // real sandbox host (whose slow transport failure would leak into the next test's guard).
        val checkout = savedGiftCardCheckoutOffline()
        val saved = checkout.savedSecondaryTokens.value.single().asSecondaryToken()

        assertFalse(saved.canBeRegistered)
        assertFalse(checkout.registrationOf(saved).registered)
        val thrown = assertThrows(PurseCheckoutException.WalletOperationFailed::class.java) {
            checkout.registerSecondary(saved)
        }
        assertEquals(PurseErrorCode.TOKEN_NOT_REGISTERABLE, thrown.code)
    }

    @Test
    fun `a registered temporary gift card's decision travels into validate`() {
        FakePaymentApi().use { api ->
            api.respond("validate", """{"global_status":"SUCCESS","results":[]}""")
            val checkout = checkout(widgetData = CheckoutTestSessions.widgetData(apiUrl = api.apiRoot))
            val keepable = temporaryGiftCard(eu.purse.core.payment.SaveTokenMode.PREFER)
            checkout.registerSecondary(keepable, name = "gift one")
            checkout.applySecondary(keepable) // 10.00 covers the 4.80 order alone
            assertTrue(checkout.isPaymentFulfilled.value)

            runBlocking { checkout.submitPayment() }

            val item = org.json.JSONArray(api.requests.single { it.path.endsWith("/validate") }.body).getJSONObject(0)
            assertTrue(item.getJSONObject("payment_preferences").getBoolean("registration"))
            assertEquals("gift one", item.getJSONObject("payment_data").getJSONObject("card").getString("token_label"))
        }
    }

    // --- session refresh (setSession) ---------------------------------------------------------------

    /**
     * A second session: new id and amount, the card method **unchanged**, PayPal's configuration
     * **changed** (a different client id), Google Pay gone, and expiring at [expiration].
     */
    private fun refreshedSessionJson(
        id: String = "session-456",
        amount: String = "9.99",
        expiration: String = "2999-01-01T00:00:00Z",
    ): String {
        val session = org.json.JSONObject(CheckoutTestSessions.samplePaymentSessionJson)
        session.put("id", id).put("amount", BigDecimal(amount)).put("expiration_date", expiration)
        val protocols = session.getJSONObject("protocols")
        protocols.getJSONObject("paypal").getJSONObject("wallet").getJSONObject("configurations")
            .getJSONObject("settings").getJSONObject("merchant_specifics").put("client_id", "paypal-client-NEW")
        protocols.remove("adyen")
        return session.toString()
    }

    @Test
    fun `setSession with the same session id is ignored`() = runBlocking {
        val checkout = checkout()
        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }
        checkout.setPrimary(card)

        checkout.setSession(CheckoutTestSessions.widgetData())

        assertEquals("session-123", checkout.currentSessionId)
        assertEquals(card.id, checkout.amountSplit.value.single().source.id)
        assertEquals(0, checkout.generationOf(card))
    }

    @Test
    fun `setSession keeps an unchanged primary (selection, collected data, registration) and rebuilds the rest`() = runBlocking {
        val checkout = checkout()
        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }
        val paypal = checkout.primaryMethods.single { it.method == Method.Wallet }
        checkout.setPrimary(card)
        checkout.setCollected(card, mapOf("cardVaultToken" to "vault-1"))
        checkout.setCollected(paypal, mapOf("orderId" to "order-1"))
        checkout.register(card, registered = true, name = "holiday card")
        assertEquals(3, checkout.primaryMethods.size)

        checkout.setSession(CheckoutTestSessions.widgetData(refreshedSessionJson()))

        // The new session is the one in force, everywhere.
        assertEquals("session-456", checkout.currentSessionId)
        assertEquals("session-456", checkout.paymentSession.value.id)
        assertEquals(SessionState.READY, checkout.sessionState.value)
        assertEquals(2, checkout.primaryMethods.size) // Google Pay disappeared
        // The amount is the new session's, and the unchanged card is still the primary covering it
        // (remainingAmountToPay is what the primary covers — nothing else chips in here).
        assertEquals(0, BigDecimal("9.99").compareTo(checkout.remainingAmountToPay.value))
        assertTrue(checkout.isPaymentFulfilled.value)
        val split = checkout.amountSplit.value.single()
        assertEquals(card.id, split.source.id)
        assertEquals(0, BigDecimal("9.99").compareTo(split.amount))
        // Survivor: collected data, registration decision and generation all intact.
        val newCard = checkout.primaryMethods.single { it.method == Method.CreditCard }
        assertEquals("vault-1", checkout.pluginContext(newCard).collected["cardVaultToken"])
        assertEquals(RegistrationState(registered = true, name = "holiday card"), checkout.registrationOf(newCard))
        assertEquals(0, checkout.generationOf(newCard))
        // Changed method: collected data dropped, generation bumped so its UI rebuilds.
        val newPaypal = checkout.primaryMethods.single { it.method == Method.Wallet }
        assertTrue(checkout.pluginContext(newPaypal).collected.isEmpty())
        assertEquals(1, checkout.generationOf(newPaypal))
        // Gone method: its generation is bumped too, for anything still holding it.
        assertEquals(1, checkout.itemGenerations.value["adyen-googlepay"])
        // The saved card rides along its unchanged method.
        assertEquals(listOf("tok-1"), checkout.paymentTokens.value.map { it.id })
    }

    @Test
    fun `setSession drops applied gift cards, which were tokenized against the old session`() = runBlocking {
        val checkout = checkout()
        val giftCard = checkout.secondaryMethods.single()
        checkout.applySecondary(
            SecondaryToken(
                id = "illicado-giftcard-7890", partner = giftCard.partner, method = giftCard.method,
                pan = "9250004781234567890", cvv = "123", name = "gift", balance = BigDecimal("2.00"),
            ),
        )
        assertEquals(0, BigDecimal("2.80").compareTo(checkout.remainingAmountToPay.value))

        checkout.setSession(CheckoutTestSessions.widgetData(refreshedSessionJson()))

        assertTrue(checkout.appliedSecondaries.isEmpty())
        assertEquals(0, BigDecimal("9.99").compareTo(checkout.remainingAmountToPay.value))
        assertFalse(checkout.isPaymentFulfilled.value)
    }

    @Test
    fun `setSession recovers an expired checkout with a fresh session`() = runBlocking {
        val expired = refreshedSessionJson(id = "session-old", expiration = "2000-01-01T00:00:00Z")
        val checkout = checkout(widgetData = CheckoutTestSessions.widgetData(expired))
        assertEquals(SessionState.EXPIRED, checkout.sessionState.value)

        checkout.setSession(CheckoutTestSessions.widgetData(refreshedSessionJson()))

        assertEquals(SessionState.READY, checkout.sessionState.value)
        assertEquals("session-456", checkout.currentSessionId)
    }

    @Test
    fun `setSession refuses a session that is already expired`() = runBlocking {
        val checkout = checkout()
        val stale = CheckoutTestSessions.widgetData(refreshedSessionJson(expiration = "2000-01-01T00:00:00Z"))

        val error = assertThrows(PurseCheckoutException.SessionExpired::class.java) {
            runBlocking { checkout.setSession(stale) }
        }
        assertEquals(PurseErrorCode.EXPIRED_SESSION, error.code)
        // Nothing moved.
        assertEquals("session-123", checkout.currentSessionId)
        assertEquals(SessionState.READY, checkout.sessionState.value)
    }

    // --- reliability: one submit per session, locks, teardown (SDK-12218) ------------------------------

    private val SUCCESS_ANSWER = """{"global_status":"SUCCESS","results":[]}"""

    /** A checkout whose whole amount a gift card covers, so a submit needs no plugin. */
    private fun coveredCheckout(api: FakePaymentApi, checker: ServerDatetimeChecker = offlineChecker()): PurseHeadlessCheckout {
        val checkout = checkout(widgetData = CheckoutTestSessions.widgetData(apiUrl = api.apiRoot), checker = checker)
        checkout.applySecondary(temporaryGiftCard(eu.purse.core.payment.SaveTokenMode.NEVER)) // 10.00 covers 4.80
        return checkout
    }

    @Test
    fun `two concurrent submits post one validate and the loser is SUBMIT_BLOCKED`() {
        FakePaymentApi().use { api ->
            api.on("validate") {
                Thread.sleep(300)
                200 to SUCCESS_ANSWER
            }
            val checkout = coveredCheckout(api)

            val outcomes = runBlocking {
                listOf(
                    async(Dispatchers.Default) { runCatching { checkout.submitPayment() } },
                    async(Dispatchers.Default) { runCatching { checkout.submitPayment() } },
                ).awaitAll()
            }

            assertEquals(1, outcomes.count { it.isSuccess })
            val blocked = outcomes.single { it.isFailure }.exceptionOrNull() as PurseCheckoutException.SubmitBlocked
            assertEquals(PurseErrorCode.SUBMIT_BLOCKED, blocked.code)
            assertEquals("exactly one /validate left the device", 1, api.requests.count { it.path.endsWith("/validate") })
            assertEquals(SessionState.SUBMITTED, checkout.sessionState.value)
        }
    }

    @Test
    fun `a spent session refuses a second submit even when the payment was declined`() {
        FakePaymentApi().use { api ->
            api.respond("validate", """{"global_status":"FAILED","results":[]}""")
            val checkout = coveredCheckout(api)
            runBlocking { checkout.submitPayment() }
            assertEquals(SessionState.ERROR, checkout.sessionState.value)

            val thrown = assertThrows(PurseCheckoutException.SubmitBlocked::class.java) {
                runBlocking { checkout.submitPayment() }
            }
            assertEquals(PurseErrorCode.SUBMIT_BLOCKED, thrown.code)
            assertEquals(1, api.requests.count { it.path.endsWith("/validate") })
        }
    }

    @Test
    fun `a validate the API refused leaves the session submittable`() {
        FakePaymentApi().use { api ->
            var calls = 0
            api.on("validate") { if (calls++ == 0) 500 to """{"error":"OUTAGE"}""" else 200 to SUCCESS_ANSWER }
            val checkout = coveredCheckout(api)

            val first = assertThrows(PurseCheckoutException.ValidateFailed::class.java) {
                runBlocking { checkout.submitPayment() }
            }
            assertFalse("the API answered, so the outcome is known", first.outcomeUnknown)
            assertEquals(SessionState.ERROR, checkout.sessionState.value)

            runBlocking { checkout.submitPayment() }
            assertEquals(SessionState.SUBMITTED, checkout.sessionState.value)
        }
    }

    @Test
    fun `a validate that never answered is reported as outcome unknown`() {
        val api = FakePaymentApi()
        val checkout = coveredCheckout(api)
        api.close() // the request leaves the device; nothing answers

        val thrown = assertThrows(PurseCheckoutException.ValidateFailed::class.java) {
            runBlocking { checkout.submitPayment() }
        }
        assertTrue("the payment may exist server-side — the merchant must reconcile", thrown.outcomeUnknown)
        assertEquals(SessionState.ERROR, checkout.sessionState.value)
    }

    @Test
    fun `submit re-evaluates expiry against the server clock and never reaches the PSP once expired`() {
        FakePaymentApi().use { api ->
            api.respond("validate", SUCCESS_ANSWER)
            val year3000 = java.time.Instant.parse("3000-01-01T00:00:00Z").toEpochMilli()
            // The watchdog's first tick is a minute away: only the submit itself can notice.
            val checkout = coveredCheckout(api, checker = offlineChecker(clock = { year3000 }))
            assertEquals(SessionState.READY, checkout.sessionState.value)

            val thrown = assertThrows(PurseCheckoutException.SessionExpired::class.java) {
                runBlocking { checkout.submitPayment() }
            }
            assertEquals(PurseErrorCode.EXPIRED_SESSION, thrown.code)
            assertEquals(SessionState.EXPIRED, checkout.sessionState.value)
            assertTrue(api.requests.none { it.path.endsWith("/validate") })
        }
    }

    @Test
    fun `a zero-amount session is not submittable without a primary`() {
        val zeroAmount = CheckoutTestSessions.samplePaymentSessionJson.replace(""""amount": 4.80""", """"amount": 0""")
        val checkout = checkout(widgetData = CheckoutTestSessions.widgetData(zeroAmount))

        assertFalse(checkout.isPaymentFulfilled.value)
        assertThrows(PurseCheckoutException.PaymentNotFulfilled::class.java) {
            runBlocking { checkout.submitPayment() }
        }
    }

    @Test
    fun `every selectable item reads SUBMITTING while a submit is in flight and is released afterwards`() {
        FakePaymentApi().use { api ->
            lateinit var checkout: PurseHeadlessCheckout
            var duringSubmit: Map<String, DisabledState?> = emptyMap()
            api.on("validate") {
                duringSubmit = checkout.paymentMethods.value.associate { it.id to it.disabled }
                200 to SUCCESS_ANSWER
            }
            checkout = coveredCheckout(api)
            val giftCard = checkout.secondaryMethods.single()
            assertNull(giftCard.disabled)

            runBlocking { checkout.submitPayment() }

            assertEquals(DisabledState.Submitting, duringSubmit[giftCard.id])
            assertTrue("nothing is selectable mid-submit", duringSubmit.values.all { it != null })
            assertNull(checkout.paymentMethods.value.single { it.id == giftCard.id }.disabled)
        }
    }

    @Test
    fun `a disabled method cannot be made primary`() {
        val checkout = checkout()
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }
        checkout.pluginContext(paypal).dispatchEvent(PluginEvent.ClientError("sdk died"))

        // The merchant still holds the pre-failure instance: the refusal is judged on the live state.
        val thrown = assertThrows(PurseCheckoutException.MethodDisabled::class.java) { checkout.setPrimary(paypal) }

        assertEquals(PurseErrorCode.METHOD_DISABLED, thrown.code)
        assertTrue(thrown.disabled is DisabledState.PartnerUiFatalError)
        assertEquals(thrown.disabled.message, DisabledState.PartnerUiFatalError().message)
        assertFalse(checkout.isPaymentFulfilled.value)
    }

    @Test
    fun `after teardown every operation fails with CHECKOUT_TORN_DOWN`() {
        val checkout = checkout()
        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }
        checkout.setPrimary(card)

        checkout.teardown()

        assertTrue(checkout.isTornDown.value)
        val submit = assertThrows(PurseCheckoutException.CheckoutTornDown::class.java) {
            runBlocking { checkout.submitPayment() }
        }
        assertEquals(PurseErrorCode.CHECKOUT_TORN_DOWN, submit.code)
        assertThrows(PurseCheckoutException.CheckoutTornDown::class.java) { checkout.host(card) }
        assertThrows(PurseCheckoutException.CheckoutTornDown::class.java) { runBlocking { checkout.paymentStatus() } }
        assertThrows(PurseCheckoutException.CheckoutTornDown::class.java) {
            runBlocking { checkout.setSession(CheckoutTestSessions.widgetData()) }
        }
        checkout.teardown() // idempotent
    }
}
