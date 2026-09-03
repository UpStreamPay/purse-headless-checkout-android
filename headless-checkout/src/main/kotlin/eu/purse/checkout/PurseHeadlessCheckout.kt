package eu.purse.checkout

import android.util.Log
import eu.purse.checkout.payment.item.DisabledState
import eu.purse.checkout.payment.item.MethodAvailability
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.checkout.payment.item.PaymentToken
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SavedSecondaryToken
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.checkout.payment.registration.RegistrationState
import eu.purse.checkout.payment.secondary.SecondaryConfig
import eu.purse.checkout.payment.secondary.SecondaryConfigs
import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.checkout.payment.secondary.SecondaryTokenizer
import eu.purse.checkout.payment.split.PaymentSplit
import eu.purse.checkout.payment.split.PrimarySource
import eu.purse.checkout.payment.split.PursePaymentSplitter
import eu.purse.checkout.session.PaymentSession
import eu.purse.checkout.session.PaymentSessionBuilder
import eu.purse.checkout.state.SessionState
import eu.purse.checkout.validation.PaymentValidator
import eu.purse.core.api.PaymentApiClient
import eu.purse.core.environment.PurseEnvironment
import eu.purse.core.payload.ItemPayload
import eu.purse.core.payload.ValidationResult
import eu.purse.core.payment.LoanProduct
import eu.purse.core.payment.LoanSimulation
import eu.purse.core.payment.Partner
import eu.purse.core.payment.Method
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.core.session.PurseSessionParams
import eu.purse.core.session.SessionCodec
import eu.purse.core.assets.PurseAssets
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PartnerUiPlugin
import eu.purse.partnerui.PluginOptions
import eu.purse.partnerui.PluginEvent
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayHost
import eu.purse.partnerui.plugins.generics.redirection.RedirectionHost
import eu.purse.partnerui.plugins.partners.klarna.KlarnaHost
import eu.purse.partnerui.plugins.partners.paypal.wallet.PaypalHost
import eu.purse.partnerui.plugins.vaults.purse.creditcard.CardHost
import eu.purse.partnerui.redirect.RedirectAction
import eu.purse.checkout.confirmation.PaymentSessionStatus
import eu.purse.checkout.monitoring.CheckoutEventBus
import eu.purse.checkout.monitoring.CheckoutEventCode
import eu.purse.checkout.monitoring.EventLevel
import eu.purse.core.api.PaymentApiException
import eu.purse.core.api.WalletApiClient
import eu.purse.core.datetime.DatetimeApiClient
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.datetime.ServerDatetimeChecker
import eu.purse.core.monitoring.ApiCallMonitor
import eu.purse.core.monitoring.MonitoringApiClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant

/**
 * The headless purse-payment SDK — the Kotlin analog of web's `PurseHeadlessCheckout`. It owns the
 * purse-payment concept and lets a merchant build their own checkout UI on top:
 *
 * - **session (V2)**: [init] decodes the base64 `widget.data` blob (via `:core`'s [SessionCodec])
 *   down to a [PaymentSession];
 * - **payment model**: [paymentMethods] — primaries + secondaries, with live disabled states;
 * - **splits**: pick a primary ([setPrimary]) and/or draw down secondaries ([takeSecondary]);
 *   [remainingAmountToPay], [amountSplit] and [isPaymentFulfilled] track the split reactively;
 * - **create payment**: [submitPayment] runs the plugin-driven `/validate` pipeline over the split;
 * - **session params / vault / merchant_specifics** are handed to `:partner-ui` plugins through the
 *   [PaymentValidator] → `PartnerUiContext`.
 *
 * The reactive surface is exposed as [StateFlow] (the analog of web's Svelte stores). Collect it
 * from a coroutine scope. The Payment API is blocking, so [submitPayment] is `suspend`.
 */
class PurseHeadlessCheckout private constructor(
    initialParams: PurseSessionParams,
    initialSession: PaymentSession,
    initialPaymentApiRoot: String,
    private val hooks: PurseCheckoutHooks?,
    /** Presentation preferences (locale, theme, text overrides) forwarded to every plugin. */
    val options: PluginOptions,
    private val datetimeChecker: ServerDatetimeChecker,
    private val watchdogIntervalMs: Long,
    /** The build-locked environment [init] was given, re-applied by [setSession]. */
    private val lockedEnvironment: PurseEnvironment?,
) {
    /** The decoded widget data behind [session]; replaced by [setSession]. */
    var params: PurseSessionParams = initialParams
        private set

    /**
     * The session this checkout currently pays — replaced in place by [setSession] (web's
     * `setSession`), so hold the checkout, not the session. Observe replacements on [paymentSession].
     */
    var session: PaymentSession = initialSession
        private set

    /** Resolved Payment API root; calls go to `{root}/{entityId}/sessions/{sessionId}/…`. */
    var paymentApiRoot: String = initialPaymentApiRoot
        private set

    private val bus = CheckoutEventBus.getInstance()

    /** Owns the expiry watchdog (and later background work); cancelled by [teardown]. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e ->
                // Web's WindowErrorWatcher analog, scoped to the SDK's own coroutines — an SDK
                // must never install a process-global uncaught-exception handler.
                bus.post(
                    CheckoutEventCode.HEADLESS_CHECKOUT_UNCAUGHT_ERROR,
                    EventLevel.ERROR,
                    monitoringPayload("stack" to e.stackTraceToString().take(4_000)),
                )
            },
    )
    private val splitter = PursePaymentSplitter(session.amount)

    /**
     * The live means-of-payment list — starts as the session's build output and is recomputed on
     * every split change, preserving non-recomputable disabled states (a suspension or a plugin
     * failure survives amount changes). Items whose disabled state is a filtered code
     * ([DisabledState.isFiltered]) are kept here but never exposed — web removes them from `mops`
     * outright (`payment.ts:88-97`).
     */
    private var meansOfPayment: List<PaymentItem> = session.meansOfPayment

    /** Interactively-collected data per method id (e.g. a Google Pay token) — see [setCollected]. */
    private val collectedByMethod = mutableMapOf<String, Map<String, Any?>>()

    /**
     * Per-item form fulfillment, keyed by method id — the analog of web's per-element `fulfilled`
     * flag (`PaymentElement.isFulfilled()`), fed by [setFulfilled]. An **absent** entry means the
     * item declares no such gate (a redirection, or an xpay like Google Pay whose sheet *is* the
     * form) and so never blocks; `false` blocks until the item says otherwise. See [setFulfilled].
     */
    private val fulfilledByMethod = mutableMapOf<String, Boolean>()

    private val _sessionState = MutableStateFlow(
        if (session.expirationDate.isBefore(Instant.now())) SessionState.EXPIRED else SessionState.READY,
    )
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    /** The current [PaymentSession]; emits again whenever [setSession] swaps it. */
    private val _paymentSession = MutableStateFlow(initialSession)
    val paymentSession: StateFlow<PaymentSession> = _paymentSession.asStateFlow()

    /**
     * Per-item generation counters, bumped by [setSession] for every item that did **not** survive
     * the refresh (its configuration changed or it disappeared). Anything remembered per item —
     * a `PaymentElement`, a coordinator, a form — should be keyed on `(item.id, generation)` so a
     * changed method rebuilds its UI while an unchanged one keeps it, and whatever the shopper typed.
     * `rememberPaymentElement` does this for you.
     */
    private val _itemGenerations = MutableStateFlow<Map<String, Int>>(emptyMap())
    val itemGenerations: StateFlow<Map<String, Int>> = _itemGenerations.asStateFlow()

    /** The current generation of [item] — see [itemGenerations]. */
    fun generationOf(item: PaymentItem): Int = _itemGenerations.value[item.id] ?: 0

    private val _paymentMethods = MutableStateFlow(visibleMethods())
    val paymentMethods: StateFlow<List<PaymentItem>> = _paymentMethods.asStateFlow()

    /**
     * The saved cards (card-on-file) this session offers — web's `paymentTokens` store, from each
     * method's `integrated_tokens`. Empty for information-request sessions. Managing them (rename /
     * delete / favorite) additionally needs a `wallet_session` — see [editToken] / [deleteToken] /
     * [setTokenFavorite].
     */
    private val _paymentTokens = MutableStateFlow(visibleTokens())
    val paymentTokens: StateFlow<List<PaymentToken>> = _paymentTokens.asStateFlow()

    /**
     * The saved gift cards / vouchers (secondary card-on-file) this session offers — web keeps them
     * in `mops` as `type: 'token'` secondaries; here they get their own flow, like [paymentTokens],
     * and never appear on [paymentMethods]. Balances are unknown at build time and resolved in the
     * background right after construction (one `/init` per token, with its stored number + pin);
     * watch [SavedSecondaryToken.balanceResolved]. A token whose `/init` fails stays listed but
     * disabled with [DisabledState.TokenInitFailed], as on web. Apply one with
     * [applySecondary]; rename / delete need a `wallet_session` — see [editToken] / [deleteToken].
     */
    private val _savedSecondaryTokens = MutableStateFlow(visibleSavedSecondaryTokens())
    val savedSecondaryTokens: StateFlow<List<SavedSecondaryToken>> = _savedSecondaryTokens.asStateFlow()

    /** Wallet API access, when the widget data carried a `wallet_session`. */
    private val walletClient: WalletApiClient? = params.walletSession?.let { wallet ->
        WalletApiClient(
            apiKey = params.apiKey,
            walletApiRoot = params.environment.walletApiRoot,
            ownerReference = wallet.ownerReference,
            walletSessionId = wallet.sessionId,
            merchantId = wallet.merchant,
        )
    }

    /** The wallet's own token list — gates edit/delete/favorite (web's `ensureEditionCapability`). */
    @Volatile
    private var walletTokens: List<WalletApiClient.WalletToken> = emptyList()

    /** Per-method save-token decisions — web's `registration` stores. */
    private val registrationByMethod = mutableMapOf<String, RegistrationState>()

    /** Per-temporary-gift-card save decisions, keyed by token id — web's temporary token `registration`. */
    private val registrationBySecondaryToken = mutableMapOf<String, RegistrationState>()

    private val _remainingAmountToPay = MutableStateFlow(splitter.remaining)
    val remainingAmountToPay: StateFlow<BigDecimal> = _remainingAmountToPay.asStateFlow()

    /**
     * `true` once the payment can actually be submitted: the split covers the amount **and** every
     * item in it whose plugin collects a form (the card's SecureFields form) reports that form
     * complete and valid. Web reaches the same guarantee from the other end — `setAsPrimarySource`
     * refuses an element that isn't fulfilled — but the reactive shape suits Kotlin's flow surface
     * better, and it lets a merchant select a method *before* its form is filled in.
     */
    private val _isPaymentFulfilled = MutableStateFlow(splitter.fulfilled)
    val isPaymentFulfilled: StateFlow<Boolean> = _isPaymentFulfilled.asStateFlow()

    private val _amountSplit = MutableStateFlow(splitter.splits)
    val amountSplit: StateFlow<List<PaymentSplit>> = _amountSplit.asStateFlow()

    /**
     * Every [PluginEvent] any plugin dispatched, tagged with its item id — the analog of web's
     * `element.on(...)` streams, flattened. The checkout itself already reacts to the load-bearing
     * ones (see [onPluginEvent]); collect this for rendering concerns (spinners, dim states, …).
     */
    private val _pluginEvents = MutableSharedFlow<CheckoutPluginEvent>(extraBufferCapacity = 64)
    val pluginEvents: SharedFlow<CheckoutPluginEvent> = _pluginEvents.asSharedFlow()

    /**
     * Plugins with a live host, keyed by item id — the checkout drives their web-parity lifecycle
     * (`onSessionSet` at host creation, `onAmountUpdate` when the split moves, `destroyClient` at
     * [teardown]).
     */
    private val activePlugins = mutableMapOf<String, Pair<PaymentItem, PartnerUiPlugin>>()

    /** The last remaining amount plugins were told about — gates [notifyAmountUpdate]. */
    private var lastNotifiedRemaining: BigDecimal = splitter.remaining

    init {
        // Web emits CREATE + SET_SESSION + READY across init/setSession; one construction here.
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_CREATE,
            payload = monitoringPayload("sessionType" to "v2", "user_agent" to "android"),
        )
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_SET_SESSION,
            payload = monitoringPayload(
                "paymentSessionId" to session.id,
                "sessionType" to "v2",
                "amount" to session.amount.toPlainString(),
                "nb_methods" to session.meansOfPayment.size,
            ),
        )
        bus.post(CheckoutEventCode.HEADLESS_CHECKOUT_READY)

        startExpiryWatchdog()

        // Wallet session — fetch the shopper's token list once (web's `setWalletSession`); it only
        // gates management operations and syncs names/favorites, so a failure is reported but never
        // breaks the checkout.
        if (walletClient != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val tokens = walletClient.getTokens()
                    walletTokens = tokens
                    syncWalletTokens(tokens)
                    bus.post(
                        CheckoutEventCode.HEADLESS_CHECKOUT_SET_WALLET_SESSION,
                        payload = monitoringPayload("walletSessionId" to params.walletSession?.sessionId),
                    )
                } catch (e: Exception) {
                    bus.post(
                        CheckoutEventCode.HEADLESS_CHECKOUT_SET_WALLET_SESSION_FAILED,
                        EventLevel.ERROR,
                        monitoringPayload("message" to e.message),
                    )
                }
            }
        }

        runEligibilityChecks()

        // Saved gift cards carry no balance in the session — re-`/init` each one now (web's
        // `buildSecondaryPaymentToken` fires the same call as the token is built).
        resolveSavedSecondaryBalances()
    }


    /** The running expiry watchdog, so [setSession] can restart it against the new expiry. */
    private var watchdogJob: Job? = null

    /**
     * Expiry watchdog — web's `onExpires` (5s poll against the server-offset-corrected clock,
     * `server-datetime-checker.ts`). The one-shot clock sync happens on the watchdog's thread so
     * construction stays synchronous and network-free.
     */
    private fun startExpiryWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        if (_sessionState.value == SessionState.READY) {
            watchdogJob = scope.launch(Dispatchers.IO) {
                delay(watchdogIntervalMs)
                datetimeChecker.sync()
                while (isActive) {
                    if (datetimeChecker.isExpiredForServer(session.expirationDate)) {
                        _sessionState.value = SessionState.EXPIRED
                        bus.post(
                            CheckoutEventCode.HEADLESS_CHECKOUT_EXPIRED_SESSION,
                            EventLevel.WARNING,
                            monitoringPayload("paymentSessionId" to session.id),
                        )
                        break
                    }
                    delay(watchdogIntervalMs)
                }
            }
        } else if (_sessionState.value == SessionState.EXPIRED) {
            bus.post(
                CheckoutEventCode.HEADLESS_CHECKOUT_EXPIRED_SESSION,
                EventLevel.WARNING,
                monitoringPayload("paymentSessionId" to session.id),
            )
        }
    }

    /**
     * Eligibility pre-check — web's `getEligibilityCheck` at session build
     * (`item-factory/primaries.ts:118`): a plugin that rejects the current client/session gets its
     * method deactivated with the filtered PLUGIN_INCOMPATIBLE code before it is rendered.
     */
    private fun runEligibilityChecks() {
        meansOfPayment.forEach { item ->
            val plugin = PluginRegistry.getPlugin(item.partner, item.method, session.source) ?: return@forEach
            scope.launch {
                try {
                    plugin.ensureClientCompatibility(pluginContext(item))
                } catch (_: Exception) {
                    deactivate(item, DisabledState.PluginIncompatible)
                }
            }
        }
    }

    // --- session refresh (SDK-12216 / US-24) ----------------------------------------------------------

    /**
     * Hand this checkout a **new session** without rebuilding it — web's `setSession`. Use it when
     * the cart changed (the merchant backend minted a new Client Session for the new amount) or the
     * current session expired ([sessionState] is `EXPIRED`) and a fresh one was issued.
     *
     * What survives, mirroring web's `payment.setSession`: a **primary method whose configuration is
     * unchanged** between the two sessions (`protocols[partner][method]` deep-equal) keeps its
     * collected data, its form-fulfilment gate, its save-token decision and its live plugin — and,
     * beyond web, its place as the split's primary when it was selected. Everything else is torn
     * down: methods that changed or disappeared lose their plugin (`destroyClient`) and get a new
     * [itemGenerations] generation so any UI remembered for them rebuilds; applied gift cards and
     * their registrations are dropped (they were tokenized against the old session); saved cards and
     * saved gift cards are rebuilt from the new session. The amount is the new session's; the
     * watchdog restarts against its expiry; [sessionState] returns to `READY`.
     *
     * Same session id → ignored (a warning is logged), as on web.
     *
     * @throws PurseCheckoutException.SessionExpired when the new session is already expired.
     * @throws IllegalStateException while a payment is being submitted.
     * @throws eu.purse.core.session.PurseSessionException.SessionMalformed if [widgetData] can't be decoded.
     */
    suspend fun setSession(widgetData: String) {
        check(_sessionState.value != SessionState.SUBMITTING) {
            "cannot replace the session while a payment is being submitted"
        }
        val (newParams, newSession, newRoot) = resolve(widgetData, lockedEnvironment)
        if (newSession.id == session.id) {
            Log.w("PurseHeadlessCheckout", "setSession: attempting to set the same payment session again, ignoring.")
            return
        }
        withContext(Dispatchers.IO) { runCatching { datetimeChecker.sync() } }
        if (datetimeChecker.isExpiredForServer(newSession.expirationDate)) {
            bus.post(
                CheckoutEventCode.HEADLESS_CHECKOUT_EXPIRED_SESSION,
                EventLevel.WARNING,
                monitoringPayload("paymentSessionId" to newSession.id),
            )
            throw PurseCheckoutException.SessionExpired(newSession.id)
        }
        applySession(newParams, newSession, newRoot)
    }

    private fun applySession(newParams: PurseSessionParams, newSession: PaymentSession, newRoot: String) {
        val previous = session
        val previousItems = meansOfPayment
        // A primary method survives (keeps its plugin and any in-progress input) only when its own
        // configuration is unchanged — web's `hasSameMethodConfiguration`. Tokens ride along their
        // method: a saved card survives when its method does and the new session still lists it.
        val survivingMethodIds = previousItems
            .filterIsInstance<PrimaryMethod>()
            .filter { previous.source.sameMethodConfiguration(newSession.source, it.partner, it.method) }
            .map { it.id }
            .toSet()
        val survivingTokenIds = previousItems
            .filterIsInstance<PaymentToken>()
            .filter { "${it.partner.wireValue}-${it.method.wireValue}" in survivingMethodIds }
            .filter { token -> newSession.meansOfPayment.any { it is PaymentToken && it.id == token.id } }
            .map { it.id }
            .toSet()
        val survivors = survivingMethodIds + survivingTokenIds
        val previousPrimaryId = splitter.primaryMethod?.id

        // Tear down what does not survive.
        val dropped = activePlugins.filterKeys { it !in survivors }
        dropped.forEach { (id, tracked) ->
            activePlugins.remove(id)
            val (item, plugin) = tracked
            scope.launch { runCatching { plugin.destroyClient(pluginContext(item)) } }
        }
        collectedByMethod.keys.retainAll(survivors)
        fulfilledByMethod.keys.retainAll(survivors)
        registrationByMethod.keys.retainAll(survivors)
        registrationBySecondaryToken.clear()
        val generations = _itemGenerations.value.toMutableMap()
        previousItems.map { it.id }.filter { it !in survivors }.forEach { id ->
            generations[id] = (generations[id] ?: 0) + 1
        }
        _itemGenerations.value = generations

        // Swap the session and rebuild the list from it.
        params = newParams
        session = newSession
        paymentApiRoot = newRoot
        meansOfPayment = newSession.meansOfPayment
        splitter.reset(newSession.amount)
        // Beyond web (which resets the whole split): an unchanged, still-selectable primary stays
        // selected — the shopper's choice is "input still valid" in the AC's sense.
        if (previousPrimaryId != null && previousPrimaryId in survivors) {
            (meansOfPayment.firstOrNull { it.id == previousPrimaryId && it.disabled == null } as? PrimarySource)
                ?.let { splitter.setPrimary(it) }
        }
        _paymentSession.value = newSession
        _sessionState.value =
            if (newSession.expirationDate.isBefore(Instant.now())) SessionState.EXPIRED else SessionState.READY

        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_SET_SESSION,
            payload = monitoringPayload(
                "paymentSessionId" to newSession.id,
                "previousPaymentSessionId" to previous.id,
                "sessionType" to "v2",
                "amount" to newSession.amount.toPlainString(),
                "nb_methods" to newSession.meansOfPayment.size,
                "nb_surviving_methods" to survivingMethodIds.size,
            ),
        )

        refresh()
        _savedSecondaryTokens.value = visibleSavedSecondaryTokens()
        syncWalletTokens(walletTokens)
        startExpiryWatchdog()
        runEligibilityChecks()
        resolveSavedSecondaryBalances()
    }

    /** Web's `hasSameMethodConfiguration`: the two sessions describe `(partner, method)` identically. */
    private fun LegacyPaymentSession.sameMethodConfiguration(
        other: LegacyPaymentSession,
        partner: Partner,
        method: Method,
    ): Boolean {
        val mine = protocols[partner]?.get(method) ?: return false
        return mine == other.protocols[partner]?.get(method)
    }

    val currency: String get() = session.currency
    val isInformationRequest: Boolean get() = session.isInformationRequest
    val currentSessionId: String get() = session.id

    val primaryMethods: List<PrimaryMethod> get() = _paymentMethods.value.filterIsInstance<PrimaryMethod>()
    val secondaryMethods: List<SecondaryMethod> get() = _paymentMethods.value.filterIsInstance<SecondaryMethod>()

    /** Applied secondary tokens, in the order they were added. */
    val appliedSecondaries: List<SecondaryToken> get() = splitter.appliedSecondaries

    /** Choose (or clear, with `null`) the primary method that covers the remaining amount. */
    fun setPrimary(method: PrimaryMethod?) {
        if (method != null) {
            bus.post(
                CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_SET_PRIMARY,
                payload = monitoringPayload("partner" to method.partner.wireValue, "method" to method.method.wireValue),
            )
        } else {
            bus.post(CheckoutEventCode.HEADLESS_CHECKOUT_CLEAR_PRIMARY_SPLIT)
        }
        splitter.setPrimary(method)
        refresh()
    }

    /** Clear the primary from the split, keeping applied secondaries — web's `clearPrimarySplit`. */
    fun clearPrimarySplit() = setPrimary(null)

    // --- saved cards (wallet) ---------------------------------------------------------------------

    /**
     * Pay with a saved card — web's `token.setAsPrimarySource()`.
     *
     * A token that still needs its CVV ([PaymentToken.requiresCvv]) is **not** payable as it
     * stands: selecting it arms the same per-item fulfillment gate a fresh card form arms at mount,
     * so [isPaymentFulfilled] stays `false` — and [submitPayment] throws
     * [PurseCheckoutException.ItemNotFulfilled] — until the CVV-only form rendered for it (the card
     * element, or `rememberCardCoordinator(cardHost(token), …)`) reports valid through
     * [setFulfilled]. A token behind the no-CVV gates is payable immediately, as before.
     */
    fun setPrimaryToken(token: PaymentToken) {
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_PRIMARY_TOKEN_SET_AS_PRIMARY,
            payload = monitoringPayload("partner" to token.partner.wireValue, "method" to token.method.wireValue),
        )
        // Armed at selection rather than at form mount so a host that never renders the CVV form
        // cannot submit a token the PSP wants a CVV for; a mounted form then owns the flag.
        if (token.requiresCvv) fulfilledByMethod.putIfAbsent(token.id, false)
        splitter.setPrimary(token)
        refresh()
    }

    /** The current save-token decision for [method] (defaults from its `SaveTokenMode`). */
    fun registrationOf(method: PrimaryMethod): RegistrationState =
        registrationByMethod[method.id] ?: RegistrationState(method.saveTokenMode.registeredByDefault)

    /**
     * Decide whether [method]'s fresh card should be saved at `/validate`, optionally under [name]
     * — web's `method.register(value, {name})`. The label must match web's
     * `^[a-zA-Z0-9\s]{1,25}$` and be unique across saved tokens.
     *
     * @throws PurseCheckoutException.WalletOperationFailed METHOD_NOT_REGISTERABLE /
     * INVALID_TOKEN_NAME / TOKEN_NAME_EXISTS.
     */
    fun register(method: PrimaryMethod, registered: Boolean = true, name: String? = null) {
        if (!method.canBeRegistered) {
            throw PurseCheckoutException.WalletOperationFailed(
                "${method.id} cannot be registered (save_token_mode=${method.saveTokenMode})",
                PurseErrorCode.METHOD_NOT_REGISTERABLE,
            )
        }
        if (name != null) validateTokenLabel(name, excludingTokenId = null)
        registrationByMethod[method.id] =
            RegistrationState(registered, name ?: registrationByMethod[method.id]?.name)
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_REGISTER,
            payload = monitoringPayload(
                "partner" to method.partner.wireValue,
                "method" to method.method.wireValue,
                "saveToken" to registered,
            ),
        )
    }

    /**
     * The current save decision for a **temporary** gift card (one the shopper just typed and
     * [getSecondaryToken] tokenized) — defaults from its method's `SaveTokenMode`, like primaries.
     * Always `registered = false` for a saved instrument.
     */
    fun registrationOf(token: SecondaryToken): RegistrationState =
        if (token.isSaved) RegistrationState(registered = false)
        else registrationBySecondaryToken[token.id] ?: RegistrationState(token.saveTokenMode.registeredByDefault)

    /**
     * Ask to keep a temporary gift card for the next session, optionally under [name] — web's
     * `temporaryToken.register(value, {name})`. Travels into `/validate` as
     * `payment_preferences.registration` + `payment_data.card.token_label` on that card's item.
     * Same label rules as [register].
     *
     * @throws PurseCheckoutException.WalletOperationFailed TOKEN_NOT_REGISTERABLE (the method's
     * policy doesn't let the shopper choose, or the token is already saved) / INVALID_TOKEN_NAME /
     * TOKEN_NAME_EXISTS.
     */
    fun registerSecondary(token: SecondaryToken, registered: Boolean = true, name: String? = null) {
        if (!token.canBeRegistered) {
            throw PurseCheckoutException.WalletOperationFailed(
                if (token.isSaved) "${token.id} is already a saved instrument"
                else "${token.id} cannot be registered (save_token_mode=${token.saveTokenMode})",
                PurseErrorCode.TOKEN_NOT_REGISTERABLE,
            )
        }
        if (name != null) validateTokenLabel(name, excludingTokenId = null)
        registrationBySecondaryToken[token.id] =
            RegistrationState(registered, name ?: registrationBySecondaryToken[token.id]?.name)
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_SECONDARY_TOKEN_REGISTER,
            payload = monitoringPayload(
                "partner" to token.partner.wireValue,
                "method" to token.method.wireValue,
                "saveToken" to registered,
            ),
        )
    }

    /**
     * Rename a saved card (`PATCH …/tokens/{id}/name`) — web's `token.edit({name})`. Needs a
     * wallet session and the token present in the wallet. Blocking network; call off main.
     */
    suspend fun editToken(token: PaymentToken, name: String) {
        val client = requireWallet()
        requireInWallet(token)
        validateTokenLabel(name, excludingTokenId = token.id)
        client.editTokenName(token.id, name)
        updateTokenItem(token.id) { it.copy(name = name) }
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_TOKEN_EDIT,
            payload = monitoringPayload(
                "partner" to token.partner.wireValue,
                "method" to token.method.wireValue,
                "tokenId" to token.id,
            ),
        )
    }

    /**
     * Delete a saved card (`DELETE …/tokens/{id}`) — web's `token.delete()`: also clears it from
     * the split and removes it from [paymentTokens].
     */
    suspend fun deleteToken(token: PaymentToken) {
        val client = requireWallet()
        requireInWallet(token)
        client.deleteToken(token.id)
        if (splitter.primaryMethod?.id == token.id) splitter.setPrimary(null)
        meansOfPayment = meansOfPayment.filterNot { it.id == token.id }
        walletTokens = walletTokens.filterNot { it.id == token.id }
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_TOKEN_DELETE,
            payload = monitoringPayload(
                "partner" to token.partner.wireValue,
                "method" to token.method.wireValue,
                "tokenId" to token.id,
            ),
        )
        refresh()
    }

    /**
     * Toggle a saved card's favorite flag — web's `token.setAsFavorite()`: optimistic local update,
     * reverted if the wallet call fails.
     */
    suspend fun setTokenFavorite(token: PaymentToken) {
        val client = requireWallet()
        requireInWallet(token)
        val newFavorite = !token.favorite
        updateTokenItem(token.id) { it.copy(favorite = newFavorite) }
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_TOKEN_SET_FAVORITE,
            payload = monitoringPayload(
                "partner" to token.partner.wireValue,
                "method" to token.method.wireValue,
                "tokenId" to token.id,
                "favorite" to newFavorite,
            ),
        )
        try {
            client.setAsFavorite(token.id, newFavorite)
            val tokens = client.getTokens()
            walletTokens = tokens
            syncWalletTokens(tokens)
        } catch (e: Exception) {
            updateTokenItem(token.id) { it.copy(favorite = !newFavorite) }
            throw e
        }
    }

    /** Delete every wallet token — web's `deleteAllTokens()`. */
    suspend fun deleteAllTokens() {
        val client = requireWallet()
        client.deleteAllTokens()
        val tokenIds = visibleTokens().map { it.id }.toSet()
        if (splitter.primaryMethod?.id in tokenIds) splitter.setPrimary(null)
        // Saved gift cards are wallet tokens too — gone with the rest, and out of the split.
        meansOfPayment.filterIsInstance<SavedSecondaryToken>().forEach { splitter.clearSplit(it.asSecondaryToken()) }
        meansOfPayment = meansOfPayment.filterNot { it is PaymentToken || it is SavedSecondaryToken }
        walletTokens = emptyList()
        bus.post(CheckoutEventCode.HEADLESS_CHECKOUT_DELETE_ALL_TOKENS)
        refresh()
    }

    private fun requireWallet(): WalletApiClient =
        walletClient ?: throw PurseCheckoutException.WalletOperationFailed(
            "no wallet_session was attached to this checkout",
            PurseErrorCode.MISSING_WALLET_SESSION,
        )

    private fun requireInWallet(token: PaymentToken) {
        if (walletTokens.none { it.id == token.id }) {
            throw PurseCheckoutException.WalletOperationFailed(
                "token ${token.id} is not present in the wallet",
                PurseErrorCode.MISSING_TOKEN,
            )
        }
    }

    /** Web's token-name rules: `^[a-zA-Z0-9\s]{1,25}$` + unique across saved tokens. */
    private fun validateTokenLabel(name: String, excludingTokenId: String?) {
        if (!TOKEN_LABEL_REGEX.matches(name)) {
            throw PurseCheckoutException.WalletOperationFailed(
                "invalid token name '$name' — 1-25 alphanumeric/space characters",
                PurseErrorCode.INVALID_TOKEN_NAME,
            )
        }
        val existsInSession = visibleTokens().any { it.id != excludingTokenId && it.name == name } ||
            visibleSavedSecondaryTokens().any { it.id != excludingTokenId && it.name == name }
        val existsInWallet = walletTokens.any { it.id != excludingTokenId && it.name == name }
        if (existsInSession || existsInWallet) {
            throw PurseCheckoutException.WalletOperationFailed(
                "a saved card is already named '$name'",
                PurseErrorCode.TOKEN_NAME_EXISTS,
            )
        }
    }

    /** Overlay the wallet's names/favorites onto the session's token items (web's subscription). */
    private fun syncWalletTokens(tokens: List<WalletApiClient.WalletToken>) {
        val byId = tokens.associateBy { it.id }
        meansOfPayment = meansOfPayment.map { item ->
            when (item) {
                is PaymentToken -> {
                    val walletToken = byId[item.id] ?: return@map item
                    item.copy(
                        favorite = walletToken.favorite ?: item.favorite,
                        name = walletToken.name ?: item.name,
                    )
                }
                is SavedSecondaryToken -> {
                    val walletToken = byId[item.id] ?: return@map item
                    item.copy(
                        favorite = walletToken.favorite ?: item.favorite,
                        name = walletToken.name ?: item.name,
                    )
                }
                else -> item
            }
        }
        _paymentTokens.value = visibleTokens()
        _savedSecondaryTokens.value = visibleSavedSecondaryTokens()
    }

    private fun updateTokenItem(tokenId: String, transform: (PaymentToken) -> PaymentToken) {
        meansOfPayment = meansOfPayment.map {
            if (it is PaymentToken && it.id == tokenId) transform(it) else it
        }
        _paymentTokens.value = visibleTokens()
    }

    /**
     * Release everything this checkout owns — stops the expiry watchdog and any background work.
     * The instance is unusable afterwards; web's `teardown()`. Call it when the checkout screen is
     * dismissed for good (not on configuration changes).
     */
    fun teardown() {
        // Give every tracked plugin its destroyClient (web parity), then cancel everything.
        val plugins = activePlugins.values.toList()
        activePlugins.clear()
        scope.launch {
            plugins.forEach { (item, plugin) -> runCatching { plugin.destroyClient(pluginContext(item)) } }
        }.invokeOnCompletion { scope.cancel() }
        bus.flush()
    }

    /** The per-partner config for a secondary method (PAN/CVV rules, take policy) — `null` if none. */
    fun secondaryConfig(method: SecondaryMethod): SecondaryConfig? = SecondaryConfigs.forMethod(method)

    /**
     * Tokenize a secondary card — `POST /init` — against the amount a primary would still cover,
     * returning the [SecondaryToken] (balance/name/expiry) to hand to [applySecondary]. Validate
     * PAN/CVV with [secondaryConfig] first. Blocking API work happens via `suspend`; move it to
     * `Dispatchers.IO` at the call site.
     *
     * @throws PurseCheckoutException.NoSecondaryConfig / InvalidSecondaryCard / SecondaryTokenizationFailed
     * @throws eu.purse.core.api.PaymentApiException on network/API failure.
     */
    suspend fun getSecondaryToken(method: SecondaryMethod, pan: String, cvv: String? = null): SecondaryToken =
        try {
            bus.post(CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_GET_SECONDARY_TOKEN)
            SecondaryTokenizer(apiClient()).tokenize(method, pan, cvv, splitter.remaining)
        } catch (e: Exception) {
            // A terminal API answer (already submitted / expired / gone) ends the whole session,
            // not just this tokenization — web's `onSessionTerminated` path (`secondaries.ts:176`).
            if (e.sessionTerminal()) _sessionState.value = SessionState.TERMINATED
            throw e
        }

    /**
     * Draw an applied [token] down against what's left. For a splittable (gift-card) token, [amount]
     * caps the draw (defaults to the most it can cover); non-splittable tokens ignore it. See
     * [PursePaymentSplitter].
     */
    fun applySecondary(token: SecondaryToken, amount: BigDecimal? = null) {
        splitter.takeSplit(token, amount)
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_SECONDARY_TOKEN_TAKE,
            payload = monitoringPayload(
                "partner" to token.partner.wireValue,
                "method" to token.method.wireValue,
                "amount" to (amount ?: token.balance).toPlainString(),
            ),
        )
        refresh()
    }

    fun removeSecondary(token: SecondaryToken) {
        splitter.clearSplit(token)
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_SECONDARY_TOKEN_REMOVE_FROM_SPLIT,
            payload = monitoringPayload(
                "partner" to token.partner.wireValue,
                "method" to token.method.wireValue,
                "id" to token.id,
            ),
        )
        refresh()
    }

    // --- saved secondary tokens (gift cards on file) -----------------------------------------------

    /**
     * Draw a **saved** gift card into the split — the saved-token counterpart of
     * [applySecondary] for a [SecondaryToken]. The latest known state of the token is used, so a
     * stale instance from an earlier [savedSecondaryTokens] emission is fine.
     *
     * @throws PurseCheckoutException.SecondaryTokenizationFailed with
     * [PurseErrorCode.SECONDARY_TOKEN_EMPTY_BALANCE] while the balance isn't resolved yet or is
     * zero, or with [PurseErrorCode.FAILED_TO_INIT_SECONDARY_TOKEN] when the token's own `/init`
     * failed (it is [DisabledState.TokenInitFailed]).
     */
    fun applySecondary(token: SavedSecondaryToken, amount: BigDecimal? = null) {
        val current = savedSecondary(token.id) ?: token
        if (current.disabled != null) {
            throw PurseCheckoutException.SecondaryTokenizationFailed(
                "saved token ${token.id} is disabled (${current.disabled.code})",
                PurseErrorCode.FAILED_TO_INIT_SECONDARY_TOKEN,
            )
        }
        if (!current.balanceResolved || current.balance.signum() <= 0) {
            throw PurseCheckoutException.SecondaryTokenizationFailed(
                if (current.balanceResolved) "saved token ${token.id} has no balance"
                else "saved token ${token.id} balance is not resolved yet",
                PurseErrorCode.SECONDARY_TOKEN_EMPTY_BALANCE,
            )
        }
        applySecondary(current.asSecondaryToken(), amount)
    }

    /** Put a saved gift card's amount back — see [removeSecondary]. */
    fun removeSecondary(token: SavedSecondaryToken) {
        removeSecondary((savedSecondary(token.id) ?: token).asSecondaryToken())
    }

    /**
     * Rename a saved gift card (`PATCH …/tokens/{id}/name`) — same rules as the card overload of
     * [editToken]: wallet session required, token present in the wallet, label valid and unique.
     */
    suspend fun editToken(token: SavedSecondaryToken, name: String) {
        val client = requireWallet()
        requireWalletToken(token.id)
        validateTokenLabel(name, excludingTokenId = token.id)
        client.editTokenName(token.id, name)
        updateSavedSecondary(token.id) { it.copy(name = name) }
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_TOKEN_EDIT,
            payload = monitoringPayload(
                "partner" to token.partner.wireValue,
                "method" to token.method.wireValue,
                "tokenId" to token.id,
            ),
        )
    }

    /**
     * Delete a saved gift card (`DELETE …/tokens/{id}`) — web's secondary `token.delete()`: also
     * clears it from the split and removes it from [savedSecondaryTokens].
     */
    suspend fun deleteToken(token: SavedSecondaryToken) {
        val client = requireWallet()
        requireWalletToken(token.id)
        client.deleteToken(token.id)
        splitter.clearSplit(token.asSecondaryToken())
        meansOfPayment = meansOfPayment.filterNot { it.id == token.id }
        walletTokens = walletTokens.filterNot { it.id == token.id }
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_TOKEN_DELETE,
            payload = monitoringPayload(
                "partner" to token.partner.wireValue,
                "method" to token.method.wireValue,
                "tokenId" to token.id,
            ),
        )
        refresh()
    }

    /**
     * Re-`/init` every saved gift card to learn its balance — sequentially on one IO coroutine,
     * because `InitGuard` coalesces concurrent `/init`s of the same `(partner, method, amount)`
     * and two saved cards of one issuer would otherwise share a single answer.
     */
    private fun resolveSavedSecondaryBalances() {
        val saved = meansOfPayment.filterIsInstance<SavedSecondaryToken>()
        if (saved.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            saved.forEach { token -> resolveSavedSecondaryBalance(token) }
        }
    }

    private fun resolveSavedSecondaryBalance(token: SavedSecondaryToken) {
        try {
            val config = SecondaryConfigs.forMethod(token.partner, token.method)
                ?: throw PurseCheckoutException.NoSecondaryConfig("no secondary config for saved token ${token.id}")
            val method = SecondaryMethod(
                "${token.partner.wireValue}-${token.method.wireValue}",
                token.partner, token.method, token.configuration, disabled = null,
            )
            val payload = ItemPayload(
                partner = token.partner,
                method = token.method,
                amount = splitter.remaining,
                paymentData = config.buildInitPaymentData(token.pan, token.cvv),
            ).toJson()
            val parsed = config.parseToken(method, apiClient().init(payload), token.pan, token.cvv)
            updateSavedSecondary(token.id) {
                it.copy(
                    balance = parsed.balance,
                    totalBalance = parsed.totalBalance,
                    balanceResolved = true,
                    name = it.name.ifBlank { parsed.name },
                    expirationDate = it.expirationDate ?: parsed.expirationDate.ifBlank { null },
                )
            }
            bus.post(
                CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_GET_SECONDARY_TOKEN,
                payload = monitoringPayload(
                    "partner" to token.partner.wireValue,
                    "method" to token.method.wireValue,
                    "tokenId" to token.id,
                    "saved" to true,
                ),
            )
        } catch (e: Exception) {
            // A terminal API answer ends the whole session (web's `onSessionTerminated`); anything
            // else dims just this token — it stays listed, technically disabled.
            if (e.sessionTerminal()) {
                _sessionState.value = SessionState.TERMINATED
                return
            }
            deactivate(token, DisabledState.TokenInitFailed)
            _savedSecondaryTokens.value = visibleSavedSecondaryTokens()
            bus.post(
                CheckoutEventCode.HEADLESS_CHECKOUT_PAYMENT_ERROR,
                EventLevel.WARNING,
                monitoringPayload("message" to "saved secondary token init failed: ${e.message}", "tokenId" to token.id),
            )
        }
    }

    private fun savedSecondary(tokenId: String): SavedSecondaryToken? =
        meansOfPayment.firstOrNull { it is SavedSecondaryToken && it.id == tokenId } as SavedSecondaryToken?

    private fun updateSavedSecondary(tokenId: String, transform: (SavedSecondaryToken) -> SavedSecondaryToken) {
        meansOfPayment = meansOfPayment.map {
            if (it is SavedSecondaryToken && it.id == tokenId) transform(it) else it
        }
        _savedSecondaryTokens.value = visibleSavedSecondaryTokens()
    }

    private fun requireWalletToken(tokenId: String) {
        if (walletTokens.none { it.id == tokenId }) {
            throw PurseCheckoutException.WalletOperationFailed(
                "token $tokenId is not present in the wallet",
                PurseErrorCode.MISSING_TOKEN,
            )
        }
    }

    /** The saved gift cards, minus filtered states — what [savedSecondaryTokens] renders. */
    private fun visibleSavedSecondaryTokens(): List<SavedSecondaryToken> =
        meansOfPayment.filterIsInstance<SavedSecondaryToken>().filterNot { it.disabled?.isFiltered == true }

    // --- interactive plugins (e.g. Google Pay) ---------------------------------------------------

    /**
     * Stash data an interactive plugin collected outside the headless flow — e.g. the Google Pay
     * token + selected network from the payment sheet — keyed to [item]. It's threaded into that
     * item's `preValidation` on the next [submitPayment] (via `PartnerUiContext.collected`).
     */
    fun setCollected(item: PaymentItem, data: Map<String, Any?>) {
        collectedByMethod[item.id] = data
    }

    /**
     * Report whether [item]'s own interactive form is complete and valid — the Kotlin analog of web
     * partner-ui's `fulfilled` event (`{ isFulfilled }`), which its `PaymentElement` mirrors as
     * `isFulfilled()`. While any item in the current split reports `false`, [isPaymentFulfilled] is
     * `false` and [submitPayment] throws [PurseCheckoutException.ItemNotFulfilled].
     *
     * A plugin coordinator that collects a form calls this itself — `CardCoordinator` reports
     * `false` the moment its SecureFields form mounts and then tracks the form's validity, so an
     * empty card form can never look payable. Items that never call it are ungated, matching web,
     * where a redirection plugin or an xpay button dispatches no `fulfilled` event either.
     */
    fun setFulfilled(item: PaymentItem, fulfilled: Boolean) {
        fulfilledByMethod[item.id] = fulfilled
        refresh()
    }

    /**
     * A [PartnerUiContext] for [item] against the current split — for a plugin coordinator (in an
     * interactive plugin module) that needs to drive plugin-specific steps (e.g. building the Google
     * Pay request) before submit. `amount` is what a primary would still cover.
     */
    fun pluginContext(item: PaymentItem): PartnerUiContext = PartnerUiContext(
        partner = item.partner,
        method = item.method,
        session = session.source,
        amount = splitter.remaining,
        api = apiClient(),
        collected = collectedByMethod[item.id] ?: emptyMap(),
        options = options,
        register = (item as? PrimaryMethod)?.let { registrationOf(it).registered } ?: false,
        // A saved card rides into the plugin as web's `token` attribute (and decides whether the
        // card element renders a CVV-only form, a full one, or none).
        token = (item as? PaymentToken)?.toPayToken(),
        dispatchEvent = { event -> onPluginEvent(item, event) },
    )

    /** The CDN badge SVG for [item] — web's `iconUrl` on every payment item. */
    fun iconUrl(item: PaymentItem): String =
        PurseAssets.resolveIconUrl(item.partner, item.method, item.isSecondary, params.environment)

    /** Card-scheme badges + labels for a vaulted card method — web's `additionalAssets`. */
    fun schemeAssets(method: PrimaryMethod): List<PurseAssets.CardSchemeAsset> =
        PurseAssets.buildCardSchemeAssets(method.supportedBrands, params.environment)

    /**
     * The host-side reaction to a plugin's lifecycle events — the Kotlin analog of web's
     * `item-factory/utils.ts:109-123` wiring plus `payment-element.ts`'s `endOfSession`. Every
     * event is then re-exposed on [pluginEvents].
     */
    private fun onPluginEvent(item: PaymentItem, event: PluginEvent) {
        when (event) {
            // Web clears a recoverable disabled state when the partner comes back up.
            PluginEvent.Ready -> clearRecoverableDisabled(item)
            is PluginEvent.Fulfilled -> setFulfilled(item, event.isFulfilled)
            // Web's `validationRequested` — an xpay-style sheet approved: the approval both
            // selects the method and pays with it (`setPrimary(mop)` then submit).
            is PluginEvent.RequestValidate -> {
                makePrimary(item)
                scope.launch { runCatching { submitPayment(event.skipOnBeforeValidate) } }
            }
            // Stays in the list but technically disabled (web dims it), not filtered away —
            // an /init failure is partner trouble the shopper should still see the method for.
            is PluginEvent.InitFailed ->
                deactivate(item, DisabledState.PartnerUiFatalError(event.message))
            is PluginEvent.ClientError ->
                deactivate(item, DisabledState.PartnerUiFatalError(event.message))
            is PluginEvent.PartnerError -> deactivate(
                item,
                if (event.recoverable) DisabledState.PartnerUiRecoverableError(event.message)
                else DisabledState.PartnerUiFatalError(event.message),
            )
            PluginEvent.EndOfSession -> _sessionState.value = SessionState.TERMINATED
            // Informational — merchants observe these on [pluginEvents].
            PluginEvent.Redirect, PluginEvent.Cancelled -> Unit
        }
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_PARTNER_UI_EVENT,
            payload = monitoringPayload(
                "code" to event::class.simpleName,
                "partner" to item.partner.wireValue,
                "method" to item.method.wireValue,
            ),
        )
        _pluginEvents.tryEmit(CheckoutPluginEvent(item.id, event))
    }

    /**
     * Disable [item] with [reason], clearing it from the split first when the reason requires the
     * UI cleared — web's `fatalError → clearSplit + deactivateMop`. A filtered reason removes the
     * item from [paymentMethods] entirely.
     */
    private fun deactivate(item: PaymentItem, reason: DisabledState) {
        meansOfPayment = meansOfPayment.map { if (it.id == item.id) it.withDisabled(reason) else it }
        if (reason.requiresUiClear || reason.isFiltered) {
            if (splitter.primaryMethod?.id == item.id) {
                // The amount really changed, so a full recompute is web-accurate here; the fresh
                // state survives it (fatal/filtered codes are not recomputable).
                splitter.setPrimary(null)
                refresh()
                return
            }
        }
        // No amount change — publish without recompute, mirroring web where recompute only runs on
        // a `primaryReach` change (a recoverable state must not be wiped by its own deactivation).
        _paymentMethods.value = visibleMethods()
    }

    private fun clearRecoverableDisabled(item: PaymentItem) {
        meansOfPayment = meansOfPayment.map {
            if (it.id == item.id && it.disabled?.isRecoverable == true) it.withDisabled(null) else it
        }
        _paymentMethods.value = visibleMethods()
    }

    /**
     * Commit [item] as the split's primary when it can be one — the SDK-side half of web's
     * `validationRequested` wiring, used by self-submitting flows (an approved wallet sheet /
     * PayPal order chooses its own method; the merchant never pre-selects it).
     */
    private fun makePrimary(item: PaymentItem) {
        val source = item as? PrimarySource ?: return
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_SET_PRIMARY,
            payload = monitoringPayload("partner" to item.partner.wireValue, "method" to item.method.wireValue),
        )
        splitter.setPrimary(source)
        refresh()
    }

    /**
     * Track the plugin behind [item] once its host exists, and give it web's `onSessionSet` — the
     * lifecycle point plugins use to create their vendor client.
     */
    private fun trackPlugin(item: PaymentItem) {
        if (activePlugins.containsKey(item.id)) return
        val plugin = PluginRegistry.getPlugin(item.partner, item.method, session.source) ?: return
        activePlugins[item.id] = item to plugin
        scope.launch { runCatching { plugin.onSessionSet(pluginContext(item)) } }
    }

    /**
     * Re-query the session's transactions (`GET /{entityId}/sessions/{sessionId}`) — the source of
     * truth to check after a redirect/3-D Secure return rather than trusting a locally-held result.
     * Blocking; call off the main thread. For a typed answer use [paymentStatus] /
     * [awaitPaymentOutcome].
     */
    fun sessionTransactions(): JSONArray = apiClient().getSession()

    /**
     * One typed status check against the session's own transactions. Blocking network; call off
     * the main thread (or use [awaitPaymentOutcome]).
     */
    fun paymentStatus(): PaymentSessionStatus = PaymentSessionStatus.from(sessionTransactions())

    /**
     * Poll [paymentStatus] until the payment settles — the mobile-specific confirmation step web
     * doesn't need (its redirect lands on the merchant's own success/failure page, while an app
     * returns to the very screen that launched the redirect). Returns the last status on timeout
     * (typically [PaymentSessionStatus.Pending]); cancellable like any suspend function.
     */
    suspend fun awaitPaymentOutcome(
        timeoutMs: Long = 60_000,
        pollIntervalMs: Long = 3_000,
    ): PaymentSessionStatus = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var status = paymentStatus()
        while (status is PaymentSessionStatus.Pending && System.currentTimeMillis() < deadline) {
            delay(pollIntervalMs)
            status = paymentStatus()
        }
        status
    }

    /**
     * The [GooglePayHost] for [item] — everything `:partner-ui`'s `GooglePayCoordinator` needs from
     * this checkout, without that module depending on `:headless-checkout` directly.
     */
    fun googlePayHost(item: PaymentItem): GooglePayHost {
        trackPlugin(item)
        return object : GooglePayHost {
            override val environment get() = params.environment
            override val redirectionReturnUrl get() = session.source.redirection
            override fun buildContext() = pluginContext(item)
            override fun setCollected(data: Map<String, Any?>) = setCollected(item, data)
            override fun setAsPrimary() = makePrimary(item)
            override suspend fun submitPayment() = this@PurseHeadlessCheckout.submitPayment()
            override suspend fun delegateRedirection(action: RedirectAction) =
                this@PurseHeadlessCheckout.delegateRedirection(action)
        }
    }

    /**
     * The [KlarnaHost] for [item] — everything `:partner-ui`'s `KlarnaCoordinator` needs from this
     * checkout, without that module depending on `:headless-checkout` directly. [returnUrl] falls back
     * to the session's own configured return URL (like Google Pay's redirect return) since Klarna's
     * `KlarnaPaymentView` needs one regardless of whether the session declared its own.
     */
    fun klarnaHost(item: PaymentItem, klarnaReturnUrl: String): KlarnaHost {
        trackPlugin(item)
        return object : KlarnaHost {
            override val returnUrl get() = klarnaReturnUrl
            override fun buildContext() = pluginContext(item)
            override fun setCollected(data: Map<String, Any?>) = setCollected(item, data)
            override suspend fun submitPayment() = this@PurseHeadlessCheckout.submitPayment()
            override suspend fun delegateRedirection(action: RedirectAction) =
                this@PurseHeadlessCheckout.delegateRedirection(action)
        }
    }

    /**
     * The [PaypalHost] for [item] — everything `:partner-ui`'s `PaypalCoordinator` needs from this
     * checkout, without that module depending on `:headless-checkout` directly.
     *
     * Note [PaypalHost.redirectionReturnUrl] is *not* PayPal's approval return — the PayPal SDK
     * supplies that itself, client-side. It only covers a chained 3-D Secure step after `/validate`,
     * exactly as it does for Google Pay.
     */
    fun paypalHost(item: PaymentItem): PaypalHost {
        trackPlugin(item)
        return object : PaypalHost {
            override val environment get() = params.environment
            override val redirectionReturnUrl get() = session.source.redirection
            override fun buildContext() = pluginContext(item)
            override fun setCollected(data: Map<String, Any?>) = setCollected(item, data)
            override fun setAsPrimary() = makePrimary(item)
            override suspend fun submitPayment() = this@PurseHeadlessCheckout.submitPayment()
            override suspend fun delegateRedirection(action: RedirectAction) =
                this@PurseHeadlessCheckout.delegateRedirection(action)
        }
    }

    /**
     * The [RedirectionHost] for [item] — everything `:partner-ui`'s `RedirectionCoordinator` needs
     * from this checkout, without that module depending on `:headless-checkout` directly.
     *
     * Unlike the three hosts above, this one is not tied to a single partner or method: it serves any
     * item whose configuration resolves to the generic `redirection` template (see
     * `PluginRegistry.getPlugin`). Ask for one per item — the coordinator rebinds on each `prepare`.
     */
    fun redirectionHost(item: PaymentItem): RedirectionHost {
        trackPlugin(item)
        return object : RedirectionHost {
            override val redirectionReturnUrl get() = session.source.redirection
            override fun buildContext() = pluginContext(item)
            override fun setCollected(data: Map<String, Any?>) = setCollected(item, data)
            override suspend fun submitPayment() = this@PurseHeadlessCheckout.submitPayment()
            override suspend fun delegateRedirection(action: RedirectAction) =
                this@PurseHeadlessCheckout.delegateRedirection(action)
        }
    }

    /**
     * The [CardHost] for [item] — everything `:partner-ui`'s `CardCoordinator` needs from this
     * checkout, without that module depending on `:headless-checkout` directly. [item] is either
     * the card [PrimaryMethod] (a fresh card: the full form) or one of its saved [PaymentToken]s
     * (a CVV-only form when [PaymentToken.requiresCvv], no form otherwise — the token reaches the
     * plugin as `PartnerUiContext.token`).
     *
     * @throws IllegalArgumentException for a secondary method or any other item that cannot pay
     * as a card.
     */
    fun cardHost(item: PaymentItem): CardHost {
        require(item is PrimaryMethod || item is PaymentToken) { "${item.id} cannot host a card form" }
        trackPlugin(item)
        return object : CardHost {
            override val environment get() = params.environment
            override val vaultTenantId get() = session.vaultsConfiguration?.purse?.tenantId
            override val supportedBrands get() = when (item) {
                is PrimaryMethod -> item.supportedBrands
                else -> item.configuration.vault?.supportedBrands ?: emptyList()
            }
            override val redirectionReturnUrl get() = session.source.redirection
            override fun buildContext() = pluginContext(item)
            override fun setCollected(data: Map<String, Any?>) = setCollected(item, data)
            override fun setFulfilled(fulfilled: Boolean) = setFulfilled(item, fulfilled)
            override suspend fun submitPayment() = this@PurseHeadlessCheckout.submitPayment()
            override suspend fun delegateRedirection(action: RedirectAction) =
                this@PurseHeadlessCheckout.delegateRedirection(action)
        }
    }

    /**
     * The merchant's [PurseCheckoutHooks.onRedirection] hook, as every coordinator host sees it
     * (`RedirectionDelegate`). No hook → `false` (the SDK opens its WebView). A throwing hook is
     * reported as `HEADLESS_CHECKOUT_CLIENT_HOOK_ERROR` and rethrown typed, so the coordinator opens
     * nothing and surfaces the failure without the checkout's own state having moved — web runs its
     * `redirectionHandler` inside `postValidation`, hence `POST_VALIDATE_FAILED`.
     */
    private suspend fun delegateRedirection(action: RedirectAction): Boolean {
        val hook = hooks?.onRedirection ?: return false
        return try {
            hook(action)
        } catch (e: Exception) {
            bus.post(
                CheckoutEventCode.HEADLESS_CHECKOUT_CLIENT_HOOK_ERROR,
                EventLevel.ERROR,
                monitoringPayload("hook" to "onRedirection", "message" to e.message),
            )
            throw PurseCheckoutException.HookFailed("onRedirection", e, PurseErrorCode.POST_VALIDATE_FAILED)
        }
    }

    /**
     * Create the payment — run the plugin-driven `/validate` pipeline over the current split. Must
     * be called once [isPaymentFulfilled] is `true`. Blocking network work happens off the caller's
     * dispatcher via `suspend`; move it to `Dispatchers.IO` at the call site.
     *
     * @throws PurseCheckoutException.PaymentNotFulfilled if the split doesn't cover the amount.
     * @throws PurseCheckoutException.ItemNotFulfilled if an item in the split has an incomplete or
     * invalid form (see [setFulfilled]).
     * @throws PurseCheckoutException.NoPluginRegistered while no `:partner-ui` plugins exist yet.
     */
    suspend fun submitPayment(skipOnBeforeValidate: Boolean = false): ValidationResult {
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_SUBMIT_PAYMENT,
            payload = monitoringPayload(
                "trigger" to if (skipOnBeforeValidate) "xpay" else "api",
                "nb_splits" to splitter.splits.size,
            ),
        )
        if (!splitter.fulfilled) throw PurseCheckoutException.PaymentNotFulfilled()
        unfulfilledItemId()?.let { throw PurseCheckoutException.ItemNotFulfilled(it) }
        _sessionState.value = SessionState.SUBMITTING
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_VALIDATION_STARTED,
            payload = monitoringPayload("session_id" to session.id, "nb_items" to splitter.splits.size),
        )
        return try {
            // Every primary method in the split carries its save-token decision (defaults from its
            // SaveTokenMode) — web's preValidateItems enhancement.
            // Temporary gift cards ride the same map, keyed by their token id (never a method id).
            val registrations = splitter.splits
                .mapNotNull { it.source as? PrimaryMethod }
                .associate { it.id to registrationOf(it) } +
                splitter.splits
                    .mapNotNull { it.source as? SecondaryToken }
                    .filter { it.canBeRegistered }
                    .associate { it.id to registrationOf(it) }
            val result = PaymentValidator(apiClient()).execute(
                splitter.splits,
                session.source,
                collectedByMethod,
                hooks = hooks,
                skipOnBeforeValidate = skipOnBeforeValidate,
                registrationByMethod = registrations,
            )
            _sessionState.value = if (result.isSuccess) SessionState.SUBMITTED else SessionState.ERROR
            bus.post(
                if (result.isSuccess) CheckoutEventCode.HEADLESS_CHECKOUT_VALIDATION_SUCCESS
                else CheckoutEventCode.HEADLESS_CHECKOUT_VALIDATION_FAILED,
                if (result.isSuccess) EventLevel.SUCCESS else EventLevel.ERROR,
                monitoringPayload("global_status" to result.globalStatus),
            )
            result
        } catch (e: Throwable) {
            _sessionState.value = when {
                e.sessionTerminal() -> SessionState.TERMINATED
                // A merchant onBeforeValidate hook aborted the submit before anything was sent:
                // no payment was attempted, so the checkout is exactly as submittable as before.
                e is PurseCheckoutException.HookFailed -> SessionState.READY
                else -> SessionState.ERROR
            }
            bus.post(
                CheckoutEventCode.HEADLESS_CHECKOUT_PAYMENT_ERROR,
                EventLevel.ERROR,
                monitoringPayload("message" to e.message),
            )
            throw e
        }
    }

    /** Whether [this] (or its cause chain) is a terminal Payment API answer for the session. */
    private fun Throwable.sessionTerminal(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if ((current as? PaymentApiException)?.isSessionTerminal == true) return true
            current = current.cause
        }
        return false
    }

    /** The first item in the current split that reports its form unfulfilled, if any. */
    private fun unfulfilledItemId(): String? =
        splitter.splits.map { it.source.id }.firstOrNull { fulfilledByMethod[it] == false }

    private fun refresh() {
        _remainingAmountToPay.value = splitter.remaining
        val fulfilled = splitter.fulfilled && unfulfilledItemId() == null
        if (fulfilled != _isPaymentFulfilled.value) {
            bus.post(
                CheckoutEventCode.HEADLESS_CHECKOUT_PAYMENT_FULFILLED,
                payload = monitoringPayload("isFulfilled" to fulfilled),
            )
        }
        _isPaymentFulfilled.value = fulfilled
        _amountSplit.value = splitter.splits
        meansOfPayment = MethodAvailability.recompute(
            meansOfPayment,
            session.isInformationRequest,
            splitter.remaining,
            splitter.splits.map { it.source },
        )
        _paymentMethods.value = visibleMethods()
        _paymentTokens.value = visibleTokens()
        _savedSecondaryTokens.value = visibleSavedSecondaryTokens()
        notifyAmountUpdate()
    }

    /**
     * Tell every tracked plugin the amount it must cover moved — web's `onAmountUpdate`, driven by
     * the splitter's `primaryReach` store. Gated on an actual change so form-validity refreshes
     * (which also [refresh]) don't spam plugins.
     */
    private fun notifyAmountUpdate() {
        val remaining = splitter.remaining
        if (remaining.compareTo(lastNotifiedRemaining) == 0) return
        lastNotifiedRemaining = remaining
        activePlugins.values.forEach { (item, plugin) ->
            scope.launch { runCatching { plugin.onAmountUpdate(pluginContext(item)) } }
        }
    }

    /** [meansOfPayment] minus filtered states and saved instruments — what [paymentMethods] renders. */
    private fun visibleMethods(): List<PaymentItem> =
        meansOfPayment.filterNot { it.disabled?.isFiltered == true || it is PaymentToken || it is SavedSecondaryToken }

    /** The saved tokens, minus filtered states — what [paymentTokens] renders. */
    private fun visibleTokens(): List<PaymentToken> =
        meansOfPayment.filterIsInstance<PaymentToken>().filterNot { it.disabled?.isFiltered == true }

    // --- BNPL loan simulation (SDK-12216 / US-51) ---------------------------------------------------

    /**
     * Quote a BNPL instalment schedule for [product] with [method] — web's
     * `method.simulateLoan(product)`. Only methods flagged [PrimaryMethod.simulable] (the
     * `simulable` configuration label) answer; every other method fails fast with a dedicated code
     * instead of a partner error. Runs the blocking call on `Dispatchers.IO`.
     *
     * The schedule carries counts and amounts only (no per-instalment dates) — see
     * [LoanSimulation].
     *
     * @throws PurseCheckoutException.MethodNotSimulable `METHOD_IS_NOT_SIMULABLE` — [method] does
     * not offer simulation.
     * @throws PurseCheckoutException.LoanSimulationFailed `INVALID_LOAN_SIMULATION` — the API
     * answered without usable schedule data.
     * @throws PaymentApiException on network / HTTP failure.
     */
    suspend fun simulateLoan(method: PrimaryMethod, product: LoanProduct): LoanSimulation {
        requireSimulable(method, product)
        return withContext(Dispatchers.IO) {
            try {
                apiClient().getSimulation(method.partner, method.method, product)
            } catch (e: PaymentApiException) {
                throw e.asLoanSimulationFailureOrSelf()
            }
        }
    }

    /**
     * Every schedule the partner offers for [product] (`…/simulation/…/multi`) — web's
     * `getSimulationMulti`. Same preconditions and errors as [simulateLoan]; candidates the API
     * returns without schedule data are skipped, so the list may be empty.
     */
    suspend fun simulateLoans(method: PrimaryMethod, product: LoanProduct): List<LoanSimulation> {
        requireSimulable(method, product)
        return withContext(Dispatchers.IO) {
            try {
                apiClient().getSimulationMulti(method.partner, method.method, product)
            } catch (e: PaymentApiException) {
                throw e.asLoanSimulationFailureOrSelf()
            }
        }
    }

    private fun requireSimulable(method: PrimaryMethod, product: LoanProduct) {
        if (!method.simulable) throw PurseCheckoutException.MethodNotSimulable(method.id)
        // Web's monitoring proxy posts HEADLESS_CHECKOUT_METHOD_LOAN_SIM with the product on every
        // simulateLoan call (`monitoringConfigs.ts`).
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_LOAN_SIM,
            payload = monitoringPayload(
                "partner" to method.partner.wireValue,
                "method" to method.method.wireValue,
                "amount" to product.amount.toPlainString(),
                "currencyCode" to product.currencyCode,
            ),
        )
    }

    /**
     * Web's `getSimulation` reads the JSON body whatever the HTTP status and throws
     * `INVALID_LOAN_SIMULATION` whenever it holds no `plugin_result` — so a partner that answers
     * `409 EMPTY_SIMULATION` (Riverty when the method isn't offered for this order) or any other
     * API-level refusal surfaces under that one dedicated code, with the API's own `errorCode` kept
     * on the cause. Only a transport failure (no status at all) stays a raw [PaymentApiException].
     */
    private fun PaymentApiException.asLoanSimulationFailureOrSelf(): Exception =
        if (errorCode == PaymentApiClient.INVALID_LOAN_SIMULATION || status != null) {
            PurseCheckoutException.LoanSimulationFailed(
                message ?: "invalid loan simulation${errorCode?.let { " ($it)" } ?: ""}",
                this,
            )
        } else {
            this
        }

    private fun apiClient(): PaymentApiClient =
        PaymentApiClient(params.apiKey, paymentApiRoot, params.entityId, session.id)

    companion object {
        /**
         * @param widgetData the base64-encoded `widget.data` string from the merchant backend's
         * Client Session response (the "V2" params).
         * @param lockedEnvironment when non-null, overrides whatever [widgetData] declares — a
         * release build should always pass [PurseEnvironment.PRODUCTION] rather than trusting a
         * session-declared environment.
         * @throws eu.purse.core.session.PurseSessionException.SessionMalformed if [widgetData]
         * can't be decoded, or the session is missing `id` / `expiration_date`.
         */
        fun init(
            widgetData: String,
            lockedEnvironment: PurseEnvironment? = null,
            hooks: PurseCheckoutHooks? = null,
            options: PluginOptions = PluginOptions(),
        ): PurseHeadlessCheckout = init(widgetData, lockedEnvironment, hooks, options, datetimeChecker = null)

        /**
         * Test seam: inject a [datetimeChecker] (and a fast watchdog) so unit tests never touch the
         * network. Production callers use the public overload, which builds the real checker.
         */
        internal fun init(
            widgetData: String,
            lockedEnvironment: PurseEnvironment? = null,
            hooks: PurseCheckoutHooks? = null,
            options: PluginOptions = PluginOptions(),
            datetimeChecker: ServerDatetimeChecker?,
            watchdogIntervalMs: Long = WATCHDOG_INTERVAL_MS,
        ): PurseHeadlessCheckout {
            val (params, session, apiRoot) = resolve(widgetData, lockedEnvironment)
            val checker = datetimeChecker ?: ServerDatetimeChecker(
                fetchServerTime = {
                    DatetimeApiClient(params.apiKey, params.environment.datetimeApiRoot).datetime()
                },
            )
            // Monitoring — only against a real checker (i.e. never from unit tests): route the
            // event bus's batches to the monitoring API with this session's context attached.
            if (datetimeChecker == null) {
                installMonitoring(params, sessionId = params.paymentSession.id)
            }
            return PurseHeadlessCheckout(
                params, session, apiRoot, hooks, options, checker, watchdogIntervalMs, lockedEnvironment,
            )
        }

        /** Decoded params, built session and Payment API root for [widgetData] — shared by [init] and [setSession]. */
        private fun resolve(widgetData: String, lockedEnvironment: PurseEnvironment?): Triple<PurseSessionParams, PaymentSession, String> {
            val decoded = SessionCodec.decode(widgetData)
            val params = if (lockedEnvironment != null && lockedEnvironment != decoded.environment) {
                decoded.copy(environment = lockedEnvironment)
            } else {
                decoded
            }
            // A build-locked (release) environment trusts its own known root and ignores the
            // session-declared `api_url`; otherwise use `api_url`, falling back to the env root.
            val apiRoot = if (lockedEnvironment != null) {
                params.environment.paymentApiRoot
            } else {
                params.apiUrl.ifBlank { params.environment.paymentApiRoot }
            }
            return Triple(params, PaymentSessionBuilder.build(params.paymentSession), apiRoot)
        }

        /** Web's `ServerDatetimeChecker.WATCHDOG_DURATION`. */
        const val WATCHDOG_INTERVAL_MS = 5_000L

        /** SDK version stamped onto every monitoring message — web's `version` extra. */
        const val VERSION = "0.1.0"

        @Volatile
        private var backgroundFlushInstalled = false

        /**
         * Point the singleton [CheckoutEventBus] at the monitoring API for this session — web's
         * `init` building the `ApiLogger`, patching extras and handing `sendLogs` to the bus —
         * plus the Android analog of `beforeunload`: flush when the app goes to background.
         */
        private fun installMonitoring(params: PurseSessionParams, sessionId: String) {
            val logger = MonitoringApiClient(
                apiKey = params.apiKey,
                monitoringApiRoot = params.environment.monitoringApiRoot,
                envWireValue = params.environment.wireValue,
            )
            logger.patchExtras(
                entityId = params.entityId,
                paymentSessionId = sessionId,
                version = VERSION,
                wrapper = "android",
            )
            val bus = CheckoutEventBus.getInstance()
            bus.setLogProcessor { batch -> logger.sendLogs(batch.map { it.toJson() }) }

            ApiCallMonitor.listener = object : ApiCallMonitor.Listener {
                override fun onCallStart(method: String, path: String) {
                    bus.post(
                        CheckoutEventCode.HEADLESS_CHECKOUT_PARTNER_API_CALL_START,
                        EventLevel.NETWORK,
                        monitoringPayload("call" to method, "path" to path),
                    )
                }

                override fun onCallEnd(method: String, path: String, durationMs: Long, success: Boolean) {
                    bus.post(
                        CheckoutEventCode.HEADLESS_CHECKOUT_PARTNER_API_CALL_END,
                        EventLevel.PERFORMANCE,
                        monitoringPayload(
                            "call" to method,
                            "path" to path,
                            "durationMs" to durationMs,
                            "success" to success,
                        ),
                    )
                }
            }

            installBackgroundFlush(bus)
        }

        /**
         * Flush the analytics queue when the whole app goes to background — the Android analog of
         * web's `beforeunload` + `sendBeacon`. Guarded: `ProcessLifecycleOwner` needs a real
         * Android process (androidx startup), so on the JVM test classpath this quietly no-ops.
         */
        private fun installBackgroundFlush(bus: CheckoutEventBus) {
            if (backgroundFlushInstalled) return
            runCatching {
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                mainHandler.post {
                    runCatching {
                        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                            androidx.lifecycle.LifecycleEventObserver { _, event ->
                                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                                    bus.post(CheckoutEventCode.HEADLESS_CHECKOUT_PAGE_UNLOAD)
                                    bus.flush()
                                }
                            },
                        )
                    }
                }
                backgroundFlushInstalled = true
            }
        }

        /** A monitoring payload from non-null pairs. */
        internal fun monitoringPayload(vararg pairs: Pair<String, Any?>): JSONObject =
            JSONObject().apply { pairs.forEach { (key, value) -> if (value != null) put(key, value) } }

        /** Web's token-name rule (`validateTokenName`). */
        private val TOKEN_LABEL_REGEX = Regex("^[a-zA-Z0-9\\s]{1,25}$")
    }
}
