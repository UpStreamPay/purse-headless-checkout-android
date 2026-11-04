// Web: packages/partner-ui/src/plugins/vaults/purse/creditcard + generics/hostedfields
// Same: fresh card / token with CVV (`cvvOnly`) / token without CVV; tokenize, then /init + /validate.
// Differs: SecureFields Android (Compose) instead of iframes; the form's validity is pushed into
//          the checkout through `PluginHost.setFulfilled` rather than refused at `setAsPrimarySource`.
package eu.purse.partnerui.plugins.vaults.purse.creditcard

import android.content.Context
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.purse.core.environment.PurseEnvironment
import eu.purse.partnerui.PayToken
import eu.purse.partnerui.PaymentCoordinator
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PluginEvent
import eu.purse.partnerui.PluginHost
import eu.purse.partnerui.PurseTheme
import eu.purse.partnerui.plugins.generics.redirection.RedirectionMapper
import eu.purse.partnerui.redirect.RedirectionCheckoutContract
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
 * Creates and remembers the card flow's interactive half: a [SecureFieldsAndroid] instance plus the
 * [CardCoordinator] that tokenizes on submit and runs `/init` + `/validate` through [host]. The host's
 * token (and its `requiresCvv`) selects the [CardCoordinator.Mode]. Render the form with
 * [PurseCardForm]; mounting it reports the item unfulfilled until SecureFields says every field is valid.
 */
@Composable
fun rememberCardCoordinator(host: PluginHost, listener: PaymentElementListener): CardCoordinator {
    val scope = rememberCoroutineScope()
    val androidContext = LocalContext.current
    val context = remember(host) { host.buildContext() }
    val payToken = context.token
    val mode = remember(payToken) { CardCoordinator.Mode.of(payToken) }

    val brands = remember(context, payToken) {
        // A saved card's own scheme first (a CVV-only form has no PAN to detect from), else the
        // session's supported_brands, else a sane default.
        val tokenBrand = payToken?.selectedNetwork?.let { Brand.fromString(it) }?.takeIf { it != Brand.UNKNOWN }
        listOfNotNull(tokenBrand).ifEmpty {
            context.configuration?.vault?.supportedBrands.orEmpty()
                .map { Brand.fromString(it.wireValue) }
                .filter { it != Brand.UNKNOWN }
                .distinct()
                .ifEmpty { listOf(Brand.VISA, Brand.MASTERCARD, Brand.CARTE_BANCAIRE) }
        }
    }
    val texts = rememberCardTexts(context.options.cardFormTexts, context.options.locale)
    val theme = remember(context.options.theme) { (context.options.theme ?: PurseTheme()).resolve() }
    val config = remember(brands, mode, texts, theme) {
        SecureFieldsConfig(
            brands = brands,
            // A co-badged card (Carte Bancaire + Visa/Mastercard) lets the shopper pick the network,
            // as web's hosted fields do; SecureFields renders the picker in the card-number field
            // and reports the choice through `onBrandUserSelection`.
            brandSelector = mode == CardCoordinator.Mode.FRESH_CARD,
            fields = when (mode) {
                CardCoordinator.Mode.FRESH_CARD -> SecureFieldsFieldsConfig(
                    cardNumber = texts.cardNumber.vaultFieldConfig(),
                    expDate = texts.expDate.vaultFieldConfig(),
                    cvv = texts.cvv.vaultFieldConfig(),
                    holderName = texts.holderName.vaultFieldConfig(),
                )
                // CVV only: with no card-number field mounted SecureFields omits the `card` object,
                // which the vault reads as a CVV-only submission (securefields-android ≥ 1.4.0).
                CardCoordinator.Mode.TOKEN_CVV,
                CardCoordinator.Mode.TOKEN_NO_CVV,
                -> SecureFieldsFieldsConfig(cvv = texts.cvv.vaultFieldConfig())
            },
            styles = theme.toVaultStyles(),
        )
    }
    val sdk = rememberSecureFields(
        config = config,
        environment = host.environment.toVaultEnvironment(),
        tenantId = context.vaultTenantId.orEmpty(),
        monitoringEnabled = false,
    )

    val coordinator = remember(sdk, host) {
        CardCoordinator(sdk, host, androidContext, mode, payToken, theme, texts, scope, listener)
    }
    val redirectLauncher = rememberLauncherForActivityResult(RedirectionCheckoutContract()) { coordinator.onRedirectReturn(it) }
    coordinator.launchRedirect = { action -> redirectLauncher.launch(action) }

    DisposableEffect(sdk, coordinator) {
        if (mode == CardCoordinator.Mode.TOKEN_NO_CVV) {
            // Nothing to render, nothing to gate: the saved card is payable as it stands.
            listener.onReady(true)
            return@DisposableEffect onDispose { }
        }
        // A mounted-but-empty form means the payment is not fulfilled.
        host.setFulfilled(false)
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
            // An item can only be fulfilled by a form that is actually on screen.
            host.setFulfilled(false)
        }
    }

    return coordinator
}

/** The state holder [rememberCardCoordinator] returns; [sdk] is the live SecureFields instance [PurseCardForm] renders. */
class CardCoordinator internal constructor(
    val sdk: SecureFieldsAndroid,
    private val cardHost: PluginHost,
    /** Reads the device for `partner_data.browser` ([RedirectionMapper.browserData]). */
    private val androidContext: Context,
    /** Which of the three card flows this coordinator drives — decided by the host's token. */
    val mode: Mode,
    private val payToken: PayToken?,
    /** The merchant's [PurseTheme] resolved over the defaults — what [PurseCardForm] styles with. */
    val theme: PurseTheme.Resolved,
    internal val texts: ResolvedCardTexts,
    private val scope: CoroutineScope,
    listener: PaymentElementListener,
) : PaymentCoordinator(scope, listener) {

    override val tag = "CardCoordinator"

    init {
        attach(cardHost)
    }

    /** Web's hosted-fields plugin without a token, with a token in `cvvOnly` mode, or behind the no-CVV gates. */
    enum class Mode {
        /** The full PAN / expiry / CVV / holder form, tokenized on submit. */
        FRESH_CARD,

        /** A saved card whose PSP still wants the CVV: a CVV-only form, tokenized into a `vault_vendor_token_id`. */
        TOKEN_CVV,

        /** A saved card that pays without a CVV (`pay_token_no_cvv` / network token): no form. */
        TOKEN_NO_CVV;

        val rendersForm: Boolean get() = this != TOKEN_NO_CVV

        companion object {
            fun of(token: PayToken?): Mode = when {
                token == null -> FRESH_CARD
                token.requiresCvv -> TOKEN_CVV
                else -> TOKEN_NO_CVV
            }
        }
    }

    /** The form's validity; always `true` without a form. Mirrored into the checkout through `setFulfilled`. */
    var formValid: Boolean by mutableStateOf(!mode.rendersForm)
        internal set

    /** Which co-badged brand to submit as: SecureFields' BIN detection, or the shopper's choice. */
    internal var selectedBrand: Brand? by mutableStateOf(null)

    /**
     * Tokenize (the full card, or just the CVV of a saved one), then `/init` + `/validate`. A saved
     * card that needs no CVV skips straight to `/init` + `/validate`. No-op while the form is invalid:
     * SecureFields is already showing the errors, and an unfilled form is not a failed payment.
     */
    fun submit() {
        if (submitting) return
        if (mode == Mode.TOKEN_NO_CVV) {
            submitting = true
            // Runs on the caller's click, not in a launched `try`: building the context throws once
            // the checkout is torn down, and that must reach onSubmitFailed, not the app.
            try {
                collectAndCapture(tokenized = null, selectedNetwork = payToken?.selectedNetwork)
            } catch (e: Exception) {
                submitting = false
                fail(e)
            }
            return
        }
        if (!formValid) {
            Log.d(tag, "submit() ignored — the card form is not complete and valid yet")
            listener.onReady(false)
            return
        }
        submitting = true
        scope.launch {
            try {
                val submitOptions = when (mode) {
                    // `selected_network` is the Payment API's scheme vocabulary, not SecureFields' Brand.
                    // `saveToken` forwards the checkout's registration decision (web's `client.submit({saveToken})`).
                    Mode.FRESH_CARD -> SubmitOptions(
                        selectedNetwork = selectedBrand?.toGatewayScheme(),
                        saveToken = cardHost.buildContext().register,
                    )
                    Mode.TOKEN_CVV, Mode.TOKEN_NO_CVV -> SubmitOptions(selectedNetwork = null, saveToken = false)
                }
                when (val tokenized = withContext(Dispatchers.IO) { sdk.submit(submitOptions) }) {
                    is SubmitResult.Success -> collectAndCapture(
                        tokenized,
                        selectedNetwork = if (mode == Mode.FRESH_CARD) submitOptions.selectedNetwork else payToken?.selectedNetwork,
                    )
                    is SubmitResult.Error -> {
                        submitting = false
                        dispatch(PluginEvent.ClientError(tokenized.message))
                        fail(tokenized.message)
                    }
                }
            } catch (e: Exception) {
                submitting = false
                fail(e)
            }
        }
    }

    /**
     * Hands `preValidation` what it needs, then `/validate`. The browser block is read at submit, in
     * every [Mode]: a 3-D Secure challenge renders in [RedirectionCheckoutContract]'s WebView, the
     * same surface the redirection template describes.
     */
    private fun collectAndCapture(tokenized: SubmitResult.Success?, selectedNetwork: String?) {
        val browser = RedirectionMapper.browserData(androidContext, cardHost.buildContext().options.locale)
        cardHost.setCollected(
            buildMap {
                put(CardPlugin.COLLECTED_BROWSER, browser)
                if (tokenized != null) {
                    put(CardPlugin.COLLECTED_TOKEN, tokenized.vaultFormToken)
                    put(CardPlugin.COLLECTED_BIN, tokenized.card?.bin)
                    put(CardPlugin.COLLECTED_NETWORK, selectedNetwork)
                }
            },
        )
        capture(cardHost)
    }
}

/** SecureFields' `Brand` → the gateway scheme the Payment API expects in `payment_data.card.selected_network`. */
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
 * The placeholder inside the secure input, and its label as the input's `contentDescription` — the
 * name TalkBack and Espresso see, since the label [PurseCardForm] draws above is a separate node
 * (web: `aria-labelledby`; iOS: `SecureFieldConfig.accessibilityLabel`).
 */
private fun ResolvedFieldTexts.vaultFieldConfig() = VaultFieldConfig(
    placeholder = placeholder,
    contentDescription = label?.takeIf { it.isNotBlank() },
)

/** The resolved `input` scope and its variants → SecureFields' [VaultStyles]; keys without an analog don't travel. */
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

/** Purse's Payment API environment → SecureFields' vault environment. */
private fun PurseEnvironment.toVaultEnvironment(): VaultEnvironment = when (this) {
    PurseEnvironment.PRODUCTION -> VaultEnvironment.PRODUCTION
    PurseEnvironment.SANDBOX -> VaultEnvironment.SANDBOX
    PurseEnvironment.TEST -> VaultEnvironment.TEST
    PurseEnvironment.DEVELOPMENT -> VaultEnvironment.SANDBOX
}
