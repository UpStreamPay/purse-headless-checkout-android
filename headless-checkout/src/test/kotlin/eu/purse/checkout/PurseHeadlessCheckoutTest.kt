package eu.purse.checkout

import eu.purse.checkout.monitoring.CheckoutEventBus
import eu.purse.checkout.monitoring.CheckoutEventCode
import eu.purse.checkout.payment.item.DisabledState
import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.checkout.state.SessionState
import eu.purse.core.api.PaymentApiException
import eu.purse.core.datetime.ServerDatetimeChecker
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.payment.IntegrationMode
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PluginEvent
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.redirect.RedirectAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

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

        assertFalse(runBlocking { checkout.cardHost(card).delegateRedirection(action) })
        assertFalse(runBlocking { checkout.paypalHost(paypal).delegateRedirection(action) })
        assertFalse(runBlocking { checkout.redirectionHost(paypal).delegateRedirection(action) })
    }

    @Test
    fun `a handling onRedirection hook takes the redirect over`() {
        var handed: RedirectAction? = null
        val checkout = checkout(hooks = PurseCheckoutHooks(onRedirection = { action -> handed = action; true }))
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }
        val action = RedirectAction.PostForm("https://acs.example/challenge", "PaReq=abc")

        assertTrue(runBlocking { checkout.paypalHost(paypal).delegateRedirection(action) })
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
                    checkout.paypalHost(paypal).delegateRedirection(RedirectAction.Get("https://acs.example/challenge"))
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
    fun `a RequestValidate event selects its method as primary before submitting - web's validationRequested`() {
        val checkout = checkout()
        val googlePay = checkout.primaryMethods.single { it.method == Method.GooglePay }
        assertFalse(checkout.isPaymentFulfilled.value)

        // What an approved wallet sheet dispatches: the merchant never called setPrimary.
        checkout.pluginContext(googlePay).dispatchEvent(PluginEvent.RequestValidate())

        assertTrue("the approval itself committed the method", checkout.isPaymentFulfilled.value)
        assertEquals(googlePay.id, checkout.amountSplit.value.single().source.id)
        // Stop the async submit the event also launched before it can touch the network.
        checkout.teardown()
    }

    @Test
    fun `the wallet hosts commit their item on approval, not before`() {
        val checkout = checkout()
        val googlePay = checkout.primaryMethods.single { it.method == Method.GooglePay }
        val host = checkout.googlePayHost(googlePay)
        assertFalse("prepare/tap must not touch the split", checkout.isPaymentFulfilled.value)

        host.setAsPrimary() // what the coordinator calls once the sheet is approved

        assertTrue(checkout.isPaymentFulfilled.value)
        assertEquals(googlePay.id, checkout.amountSplit.value.single().source.id)
    }

    @Test
    fun `a Fulfilled plugin event gates isPaymentFulfilled like setFulfilled`() {
        val checkout = checkout()
        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }
        val context = checkout.pluginContext(card)

        context.dispatchEvent(PluginEvent.Fulfilled(false))
        checkout.setPrimary(card)
        assertFalse(checkout.isPaymentFulfilled.value)

        context.dispatchEvent(PluginEvent.Fulfilled(true))
        assertTrue(checkout.isPaymentFulfilled.value)
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
        assertTrue("network token + network_token setting absent -> depends on settings", !token.paysWithoutCvv || token.networkTokenAvailable)
        assertTrue(checkout.paymentMethods.value.none { it.id == "tok-1" })
        // The method list is unchanged by the token's presence.
        assertEquals(3, checkout.primaryMethods.size)
    }

    @Test
    fun `a saved token can be the primary and fulfills the payment`() {
        val checkout = checkout()

        checkout.setPrimaryToken(checkout.paymentTokens.value.single())

        assertTrue(checkout.isPaymentFulfilled.value)
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
    fun `submitPayment fails while no partner-ui plugin is registered`() {
        val checkout = checkout()
        // Every primary this fixture offers now resolves to a shipped plugin, so opt one out to
        // reach the no-plugin path (that's exactly what `disable` is for — see PluginRegistry).
        checkout.setPrimary(checkout.primaryMethods.single { it.partner == Partner.Paypal })
        PluginRegistry.disable(Partner.Paypal, Method.Wallet)

        try {
            assertThrows(PurseCheckoutException.NoPluginRegistered::class.java) {
                runBlocking { checkout.submitPayment() }
            }
        } finally {
            // PluginRegistry is a singleton — leaving this set would leak into every later test.
            PluginRegistry.enable(Partner.Paypal, Method.Wallet)
        }
    }
}
