package eu.purse.partnerui.plugins.vaults.purse.creditcard

import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import eu.purse.core.environment.PurseEnvironment
import eu.purse.partnerui.PayToken
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PluginEvent
import eu.purse.partnerui.PluginHost
import eu.purse.partnerui.CardFormTexts
import eu.purse.partnerui.PurseTheme
import eu.purse.partnerui.R
import eu.purse.partnerui.redirect.RedirectAction
import eu.purse.partnerui.redirect.RedirectionCheckoutContract
import eu.purse.partnerui.redirect.RedirectionCheckoutResult
import eu.purse.partnerui.redirect.RedirectDispatcher
import eu.purse.partnerui.redirect.RedirectionResolver
import eu.purse.partnerui.redirect.withReturnUrlPrefix
import eu.purse.securefields.SecureFieldsAndroid
import eu.purse.securefields.SecureFieldsListener
import eu.purse.securefields.config.SecureFieldsConfig
import eu.purse.securefields.config.SecureFieldsFieldsConfig
import eu.purse.securefields.config.VaultEnvironment
import eu.purse.securefields.config.VaultFieldConfig
import eu.purse.securefields.config.VaultStyles
import eu.purse.securefields.events.BrandDetectedPayload
import eu.purse.securefields.events.BrandUserSelectionPayload
import eu.purse.securefields.events.FormValidPayload
import eu.purse.securefields.model.Brand
import eu.purse.securefields.model.SubmitOptions
import eu.purse.securefields.model.SubmitResult
import eu.purse.securefields.rememberSecureFields
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Creates and remembers the interactive half of the Purse vault credit-card flow: a
 * [SecureFieldsAndroid] instance (via SecureFields' own `rememberSecureFields`, auto-destroyed on
 * disposal) plus the glue that tokenizes on [CardCoordinator.submit] and drives `/init`+`/validate`
 * through [host].
 *
 * **Compose-first**, unlike [eu.purse.partnerui.plugins.generics.googlepay.GooglePayCoordinator] /
 * [eu.purse.partnerui.plugins.partners.klarna.KlarnaCoordinator] — SecureFields ships its own
 * Compose surface (`rememberSecureFields`/`VaultForm`/`VaultField`), and
 * `rememberLauncherForActivityResult` handles the 3-D Secure redirect launcher's lifecycle without
 * the "construct in `onCreate`, before STARTED" caveat those two imperative coordinators carry.
 *
 * The host decides which of three shapes the flow takes — see [CardCoordinator.Mode]: a **fresh
 * card** (the full form), a **saved card that still needs its CVV** (a CVV-only form, web's
 * `cvvOnly` hosted fields), or a **saved card that pays without one** (no form at all — web's
 * no-CVV gates). The host's `buildContext().token` (and its `requiresCvv`) is what selects it.
 *
 * Render the returned [CardCoordinator]'s [CardCoordinator.sdk] with SecureFields' own `VaultForm`
 * (or `VaultField` per field, for a custom layout) whenever [CardCoordinator.mode] renders a form,
 * and call [CardCoordinator.submit] from your own "Pay" button's `onClick` once
 * [CardCoordinator.formValid] is `true`.
 *
 * That last condition is enforced, not just advised: mounting a form reports the item unfulfilled
 * through [PluginHost.setFulfilled], so `PurseHeadlessCheckout.isPaymentFulfilled` is `false` — and
 * `submitPayment` throws `ItemNotFulfilled` — until the form is complete and valid.
 */
@Composable
fun rememberCardCoordinator(host: PluginHost, listener: PaymentElementListener): CardCoordinator {
    val scope = rememberCoroutineScope()
    val partnerWireValue = remember(host) { host.buildContext().partner.wireValue }
    val supportedBrands = remember(host) { host.buildContext().configuration?.vault?.supportedBrands.orEmpty() }
    val vaultTenantId = remember(host) { host.buildContext().vaultTenantId }
    // The saved card this host pays with, if any — decides the mode (see CardCoordinator.Mode).
    val payToken = remember(host) { host.buildContext().token }
    val mode = remember(payToken) { CardCoordinator.Mode.of(payToken) }

    val redirectLauncher = rememberLauncherForActivityResult(RedirectionCheckoutContract()) { result ->
        when (result) {
            // Approved only means the shopper came back — NOT the payment outcome, same caveat as
            // GooglePayCoordinator/KlarnaCoordinator's own onRedirectReturn.
            RedirectionCheckoutResult.Approved ->
                listener.onResult("returned from 3-D Secure — check the session status independently to confirm the outcome")
            RedirectionCheckoutResult.Canceled -> listener.onCanceled()
            is RedirectionCheckoutResult.Failed -> listener.onError(result.message)
        }
    }

    val brands = remember(supportedBrands, payToken) {
        // A saved card's own scheme first (the CVV-only form has no PAN to detect a brand from);
        // otherwise the session's supported_brands (CardBrand) → SecureFields' Brand, with a sane
        // default when the session names none.
        val tokenBrand = payToken?.selectedNetwork?.let { Brand.fromString(it) }?.takeIf { it != Brand.UNKNOWN }
        listOfNotNull(tokenBrand).ifEmpty {
            supportedBrands.map { Brand.fromString(it.wireValue) }
                .filter { it != Brand.UNKNOWN }
                .distinct()
                .ifEmpty { listOf(Brand.VISA, Brand.MASTERCARD, Brand.CARTE_BANCAIRE) }
        }
    }
    // Field copy: merchant overrides (PluginOptions.cardFormTexts) win; otherwise the SDK's
    // localized resources (values/, values-fr/, …) — web's HostedFormUIOptions-over-translations.
    val options = remember(host) { host.buildContext().options }
    val texts = rememberCardTexts(options.cardFormTexts)
    // Web's Theme.build(): merchant values over the default theme, then the scope → global
    // fallback chain, resolved once so the form and SecureFields read the same values.
    val theme = remember(options.theme) { (options.theme ?: PurseTheme()).resolve() }
    val config = remember(brands, mode, texts, theme) {
        SecureFieldsConfig(
            brands = brands,
            // No `view` targets — SecureFields builds its own composited form (`sdk.formView`) for
            // `VaultForm`/`VaultField` to host, instead of the View-system's per-field
            // `VaultFieldView` targets.
            fields = when (mode) {
                CardCoordinator.Mode.FRESH_CARD -> SecureFieldsFieldsConfig(
                    cardNumber = VaultFieldConfig(placeholder = texts.cardNumber.placeholder),
                    expDate = VaultFieldConfig(placeholder = texts.expDate.placeholder),
                    cvv = VaultFieldConfig(placeholder = texts.cvv.placeholder),
                    holderName = VaultFieldConfig(placeholder = texts.holderName.placeholder),
                )
                // CVV-only (web's `cvvOnly` hosted fields): `cvv` is SecureFields' one mandatory
                // field, and with no card-number field mounted its tokenizer omits the `card` object
                // entirely — which is what the Vault reads as a CVV-only submission.
                CardCoordinator.Mode.TOKEN_CVV,
                CardCoordinator.Mode.TOKEN_NO_CVV,
                -> SecureFieldsFieldsConfig(cvv = VaultFieldConfig(placeholder = texts.cvv.placeholder))
            },
            styles = theme.toVaultStyles(),
        )
    }
    val sdk = rememberSecureFields(
        config = config,
        environment = host.environment.toVaultEnvironment(),
        tenantId = vaultTenantId.orEmpty(),
        monitoringEnabled = false,
    )

    val coordinator = remember(sdk, host, partnerWireValue) {
        CardCoordinator(sdk, host, partnerWireValue, mode, payToken, theme, texts, scope, redirectLauncher, listener)
    }

    DisposableEffect(sdk, coordinator) {
        if (mode == CardCoordinator.Mode.TOKEN_NO_CVV) {
            // Nothing to render and nothing to gate — the saved card is payable as it stands, so
            // the SDK instance is never rendered (SecureFields builds its native fields lazily, at
            // `render()`), and readiness is immediate.
            listener.onReady(true)
            return@DisposableEffect onDispose { }
        }
        // Arm the fulfillment gate before anything can be typed: a mounted-but-empty form means
        // the payment is NOT fulfilled, so `isPaymentFulfilled` stays false (and `submitPayment`
        // throws) until SecureFields reports every configured field valid. Web gets there from the
        // other end — its `setAsPrimarySource` throws ELEMENT_NOT_FULFILLED — but the guarantee is
        // the same one.
        host.setFulfilled(false)
        // Tracks which brand to submit as, for a co-badged card (e.g. a French card that's both CB
        // and VISA) — mirrors web vault-front's BrandSelection: default to the first brand
        // SecureFields' own BIN lookup detects/matches, overridden if the host renders a brand
        // picker calling `sdk.setBrandSelection` (none is built here, so onBrandUserSelection is
        // future-proofing rather than something this coordinator exercises itself).
        val unregister = sdk.addListener(object : SecureFieldsListener {
            override fun onFormValid(payload: FormValidPayload) {
                coordinator.formValid = !payload.hasErrors
                host.setFulfilled(coordinator.formValid)
                listener.onReady(coordinator.formValid)
            }
            override fun onBrandDetected(payload: BrandDetectedPayload) {
                coordinator.selectedBrand = payload.brands.firstOrNull()
            }
            override fun onBrandNotDetected() {
                coordinator.selectedBrand = null
            }
            override fun onBrandUserSelection(payload: BrandUserSelectionPayload) {
                coordinator.selectedBrand = payload.selected
            }
        })
        sdk.render()
        onDispose {
            unregister()
            // The form is gone, so whatever it had validated no longer stands — an item can only
            // be fulfilled by a form that is actually on screen.
            host.setFulfilled(false)
        }
    }

    return coordinator
}

/**
 * The state holder [rememberCardCoordinator] returns. [sdk] is the live `SecureFieldsAndroid`
 * instance — render it with SecureFields' own `VaultForm(coordinator.sdk)` /
 * `VaultField(coordinator.sdk.cvv)` whenever [mode] renders a form ([Mode.rendersForm]).
 * [formValid] and [selectedBrand] are Compose state, so reading them in a composable recomposes
 * correctly.
 */
class CardCoordinator internal constructor(
    val sdk: SecureFieldsAndroid,
    private val host: PluginHost,
    private val partnerWireValue: String,
    /** Which of the three card flows this coordinator drives — decided by the host's token. */
    val mode: Mode,
    private val payToken: PayToken?,
    /** The merchant's [PurseTheme] resolved over the defaults — what [PurseCardForm] styles with. */
    val theme: PurseTheme.Resolved,
    /** Field copy with every override applied and every gap filled from resources. */
    internal val texts: ResolvedCardTexts,
    private val scope: CoroutineScope,
    private val redirectLauncher: ManagedActivityResultLauncher<RedirectAction, RedirectionCheckoutResult>,
    private val listener: PaymentElementListener,
) {
    /**
     * Same contract as [PaymentElementListener] — kept as a nested name for source compatibility.
     * `onReady` mirrors SecureFields' `onFormValid`; `onResult` with a `null` globalStatus is a
     * 3-D Secure return whose real outcome must be re-checked against the session.
     */
    interface Listener : PaymentElementListener

    /**
     * The three shapes of a Purse-vaulted card payment, mirroring web's hosted-fields plugin
     * rendered without a token, with a token in `cvvOnly` mode, or with a token behind the no-CVV
     * gates (`plugins/utils/noCVV.ts`).
     */
    enum class Mode {
        /** No saved card: the full PAN / expiry / CVV / holder form, tokenized on submit. */
        FRESH_CARD,

        /**
         * A saved card whose PSP still wants the CVV ([PayToken.requiresCvv]): a CVV-only form,
         * tokenized on submit into a `vault_vendor_token_id` that `/validate` pairs with the saved
         * `token`.
         */
        TOKEN_CVV,

        /** A saved card that pays without a CVV (`pay_token_no_cvv` / network token): no form. */
        TOKEN_NO_CVV;

        /** Whether this mode puts a SecureFields form on screen (and gates fulfillment on it). */
        val rendersForm: Boolean get() = this != TOKEN_NO_CVV

        companion object {
            fun of(token: PayToken?): Mode = when {
                token == null -> FRESH_CARD
                token.requiresCvv -> TOKEN_CVV
                else -> TOKEN_NO_CVV
            }
        }
    }

    /**
     * The SecureFields form's current validity — `false` until every configured field is valid, so
     * an untouched form never reads as payable. Mirrored into the checkout's own
     * `isPaymentFulfilled` via [PluginHost.setFulfilled]. Always `true` in [Mode.TOKEN_NO_CVV],
     * where there is no form to validate.
     */
    var formValid: Boolean by mutableStateOf(!mode.rendersForm)
        internal set

    /** Which co-badged brand to submit as (see [rememberCardCoordinator]'s `DisposableEffect` doc). */
    internal var selectedBrand: Brand? by mutableStateOf(null)

    /** Re-entrancy guard — same rationale as `GooglePayCoordinator.submitting`/`KlarnaCoordinator.submitting`. */
    private var submitting = false

    /** Report a lifecycle event to the headless host (which deactivates/reacts, web-parity). */
    private fun dispatch(event: PluginEvent) {
        runCatching { host.buildContext().dispatchEvent(event) }
    }

    /**
     * Tokenize via SecureFields (the full card, or just the CVV of a saved one), then run
     * `/init`+`/validate` through [host]; a saved card that needs no CVV skips straight to
     * `/init`+`/validate`. Call once [formValid] is `true` and the shopper tapped "Pay". No-op while
     * a previous tap's flow is still in progress, and no-op (never an error) while the form isn't
     * complete and valid.
     */
    fun submit() {
        if (submitting) return
        if (mode == Mode.TOKEN_NO_CVV) {
            // Nothing to tokenize: the saved token alone is the payment instrument (web renders the
            // element with a `token` attribute and no fields behind the no-CVV gates).
            submitting = true
            scope.launch { capture(tokenized = null, selectedNetwork = payToken?.selectedNetwork) }
            return
        }
        if (!formValid) {
            // The web analog of this gate is the plugin's own `validateUi`, which submits the form
            // and returns its per-field errors before `preValidation` may tokenize. Here SecureFields
            // is already showing those errors itself, so there's nothing to surface — just refuse to
            // tokenize, and re-report readiness so a host that let its button through corrects itself.
            // Deliberately not `listener.onError`: an unfilled form is not a failed payment.
            Log.d(TAG, "submit() ignored — the card form is not complete and valid yet")
            listener.onReady(false)
            return
        }
        submitting = true
        scope.launch {
            try {
                val submitOptions = when (mode) {
                    // The resolved gateway scheme (e.g. "CARTE_BANCAIRE") — SecureFields' own `Brand`
                    // enum is a different, vault-internal vocabulary than what the Payment API's
                    // `selected_network` expects (matching session `vault.supported_brands`).
                    // saveToken forwards the checkout's registration decision to the vault — web's
                    // `client.submit({saveToken: context.register})`.
                    Mode.FRESH_CARD -> SubmitOptions(
                        selectedNetwork = selectedBrand?.toGatewayScheme(),
                        saveToken = host.buildContext().register,
                    )
                    // CVV-only: the card is already saved, so nothing to (re-)register, and no PAN
                    // for a network to apply to — SecureFields drops the `card` object entirely.
                    Mode.TOKEN_CVV, Mode.TOKEN_NO_CVV -> SubmitOptions(selectedNetwork = null, saveToken = false)
                }
                when (val tokenized = withContext(Dispatchers.IO) { sdk.submit(submitOptions) }) {
                    is SubmitResult.Success -> capture(
                        tokenized,
                        selectedNetwork = if (mode == Mode.FRESH_CARD) submitOptions.selectedNetwork else payToken?.selectedNetwork,
                    )
                    is SubmitResult.Error -> {
                        submitting = false
                        dispatch(PluginEvent.ClientError(tokenized.message))
                        listener.onError(tokenized.message)
                    }
                }
            } catch (e: Exception) {
                submitting = false
                listener.onError(e.message)
            }
        }
    }

    /**
     * Hand the tokenization result (if any) to the host and run `/init`+`/validate`, following a
     * 3-D Secure redirect when `/validate` asks for one. [tokenized] is `null` only in
     * [Mode.TOKEN_NO_CVV], where nothing was tokenized.
     */
    private suspend fun capture(tokenized: SubmitResult.Success?, selectedNetwork: String?) {
        if (tokenized != null) {
            host.setCollected(
                mapOf(
                    CardPlugin.COLLECTED_TOKEN to tokenized.vaultFormToken,
                    CardPlugin.COLLECTED_BIN to tokenized.card?.bin,
                    CardPlugin.COLLECTED_NETWORK to selectedNetwork,
                ),
            )
        }
        try {
            val result = withContext(Dispatchers.IO) { host.submitPayment() }
            Log.d(
                TAG,
                "validate response: global_status=${result.globalStatus} " +
                    "redirection=${result.raw.optJSONObject("redirection")}",
            )
            RedirectionResolver.extractWidgetConfirmationPaymentId(
                validateResponse = result.raw,
                partnerWireValue = partnerWireValue,
                methodWireValue = CardMapper.METHOD,
            )?.let { listener.onPaymentId(it) }
            val action = RedirectionResolver.resolve(
                validateResponse = result.raw,
                partnerWireValue = partnerWireValue,
                methodWireValue = CardMapper.METHOD,
                sessionReturnUrl = host.redirectionReturnUrl,
            )?.withReturnUrlPrefix(host.redirectionReturnUrl)
            if (action != null) {
                // The merchant's onRedirection hook goes first (web's redirectionHandler); the SDK's
                // WebView only opens when it declines.
                val outcome = RedirectDispatcher.dispatch(action, host) { redirectLauncher.launch(it) }
                when (outcome) {
                    is RedirectDispatcher.Outcome.HookFailed -> {
                        // Nothing was opened — the shopper can retry once the merchant fixes it.
                        submitting = false
                        listener.onError("onRedirection hook failed: ${outcome.error.message}")
                    }
                    else -> {
                        dispatch(PluginEvent.Redirect)
                        listener.onRequiresAction()
                    }
                }
            } else {
                listener.onResult("global_status=${result.globalStatus}", result.globalStatus)
            }
        } catch (e: Exception) {
            // `/validate` never landed (or errored) — safe to let the shopper retry.
            submitting = false
            listener.onError(e.message)
        }
    }

    private companion object {
        const val TAG = "CardCoordinator"
    }
}

/**
 * SecureFields' `Brand` (its own BIN-lookup vocabulary, e.g. `CB`/`AMEX`) → the gateway scheme
 * string the Payment API expects for `payment_data.card.selected_network`. Mirrors SecureFields
 * Android's own internal (non-public) `schemeFor()`.
 */
private fun Brand.toGatewayScheme(): String? = when (this) {
    Brand.VISA -> "VISA"
    Brand.MASTERCARD -> "MASTERCARD"
    Brand.AMEX -> "AMERICAN_EXPRESS"
    Brand.CARTE_BANCAIRE -> "CARTE_BANCAIRE"
    Brand.DISCOVER -> "DISCOVER"
    Brand.DINERS -> "DINERS_CLUB"
    Brand.JCB -> "JCB"
    Brand.MAESTRO -> "MAESTRO"
    Brand.UNIONPAY -> "UNIONPAY"
    Brand.ONEY -> "ONEY"
    Brand.UNKNOWN -> null
}

/**
 * Resolve [CardFormTexts] against the SDK's localized `purse_card_*` resources — one string per
 * field per role, merchant override first.
 */
@Composable
private fun rememberCardTexts(texts: CardFormTexts): ResolvedCardTexts {
    @Composable
    fun field(
        t: CardFormTexts.FieldTexts,
        label: Int, placeholder: Int, helper: Int, required: Int, invalid: Int,
    ) = ResolvedFieldTexts(
        label = t.label ?: stringResource(label),
        placeholder = t.placeholder ?: stringResource(placeholder),
        helperText = t.helperText ?: stringResource(helper).ifBlank { null },
        requiredError = t.requiredError ?: stringResource(required),
        formatError = t.formatError ?: stringResource(invalid),
    )
    val cardNumber = field(
        texts.cardNumber, R.string.purse_card_number_label, R.string.purse_card_number_placeholder,
        R.string.purse_card_number_helper, R.string.purse_card_number_required, R.string.purse_card_number_invalid,
    )
    val expDate = field(
        texts.expDate, R.string.purse_card_exp_date_label, R.string.purse_card_exp_date_placeholder,
        R.string.purse_card_exp_date_helper, R.string.purse_card_exp_date_required, R.string.purse_card_exp_date_invalid,
    )
    val cvv = field(
        texts.cvv, R.string.purse_card_cvv_label, R.string.purse_card_cvv_placeholder,
        R.string.purse_card_cvv_helper, R.string.purse_card_cvv_required, R.string.purse_card_cvv_invalid,
    )
    val holderName = field(
        texts.holderName, R.string.purse_card_holder_name_label, R.string.purse_card_holder_name_placeholder,
        R.string.purse_card_holder_name_helper, R.string.purse_card_holder_name_required, R.string.purse_card_holder_name_invalid,
    )
    return remember(cardNumber, expDate, cvv, holderName) { ResolvedCardTexts(cardNumber, expDate, cvv, holderName) }
}

/**
 * The resolved [PurseTheme] `input` scope and its variants → SecureFields' [VaultStyles]. Only the
 * keys `VaultStyles` knows travel; web's border/outline/padding keys have no SecureFields analog.
 */
private fun PurseTheme.Resolved.toVaultStyles(): VaultStyles = input.toVaultStyles(
    focus = inputFocus.toVaultStyles(),
    invalid = inputInvalid.toVaultStyles(),
    valid = inputValid.toVaultStyles(),
)

private fun PurseTheme.FieldStyle.toVaultStyles(
    focus: VaultStyles? = null,
    invalid: VaultStyles? = null,
    valid: VaultStyles? = null,
): VaultStyles = VaultStyles(
    fontFamily = fontFamily,
    fontSize = fontSize,
    lineHeight = lineHeight,
    fontWeight = fontWeight,
    color = color,
    placeholderColor = placeholderColor,
    backgroundColor = backgroundColor,
    focus = focus,
    invalid = invalid,
    valid = valid,
)

/** Purse's [PurseEnvironment] (Payment API) → SecureFields' own [VaultEnvironment] (vault gateway)
 * — distinct enums, see [CardBrand][eu.purse.core.vault.CardBrand]'s doc on the parallel `Brand` split. */
private fun PurseEnvironment.toVaultEnvironment(): VaultEnvironment = when (this) {
    PurseEnvironment.PRODUCTION -> VaultEnvironment.PRODUCTION
    PurseEnvironment.SANDBOX -> VaultEnvironment.SANDBOX
    PurseEnvironment.TEST -> VaultEnvironment.TEST
    PurseEnvironment.DEVELOPMENT -> VaultEnvironment.SANDBOX
}
