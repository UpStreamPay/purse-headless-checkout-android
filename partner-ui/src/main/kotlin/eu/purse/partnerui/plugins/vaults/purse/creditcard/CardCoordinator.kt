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
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PluginEvent
import eu.purse.partnerui.PurseTheme
import eu.purse.partnerui.R
import eu.purse.partnerui.redirect.RedirectAction
import eu.purse.partnerui.redirect.RedirectionCheckoutContract
import eu.purse.partnerui.redirect.RedirectionCheckoutResult
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
 * Render the returned [CardCoordinator]'s [CardCoordinator.sdk] with SecureFields' own `VaultForm`
 * (or `VaultField` per field, for a custom layout), and call [CardCoordinator.submit] from your own
 * "Pay" button's `onClick` once [CardCoordinator.formValid] is `true`.
 *
 * That last condition is enforced, not just advised: mounting this form reports the item unfulfilled
 * through [CardHost.setFulfilled], so `PurseHeadlessCheckout.isPaymentFulfilled` is `false` — and
 * `submitPayment` throws `ItemNotFulfilled` — until the form is complete and valid.
 */
@Composable
fun rememberCardCoordinator(host: CardHost, listener: PaymentElementListener): CardCoordinator {
    val scope = rememberCoroutineScope()
    val partnerWireValue = remember(host) { host.buildContext().partner.wireValue }

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

    val brands = remember(host.supportedBrands) {
        // Session's supported_brands (CardBrand) → SecureFields' Brand; fall back to a sane default.
        host.supportedBrands.map { Brand.fromString(it.wireValue) }
            .filter { it != Brand.UNKNOWN }
            .distinct()
            .ifEmpty { listOf(Brand.VISA, Brand.MASTERCARD, Brand.CARTE_BANCAIRE) }
    }
    // Placeholder copy: merchant overrides (PluginOptions.cardFormTexts) win; otherwise the SDK's
    // localized resources (values/, values-fr/, …) — web's HostedFormUIOptions-over-translations.
    val options = remember(host) { host.buildContext().options }
    val texts = options.cardFormTexts
    val cardNumberPlaceholder =
        texts.cardNumberPlaceholder ?: stringResource(R.string.purse_card_number_placeholder)
    val expDatePlaceholder =
        texts.expDatePlaceholder ?: stringResource(R.string.purse_card_exp_date_placeholder)
    val cvvPlaceholder = texts.cvvPlaceholder ?: stringResource(R.string.purse_card_cvv_placeholder)
    val holderNamePlaceholder =
        texts.holderNamePlaceholder ?: stringResource(R.string.purse_card_holder_name_placeholder)
    val config = remember(
        brands, cardNumberPlaceholder, expDatePlaceholder, cvvPlaceholder, holderNamePlaceholder, options.theme,
    ) {
        SecureFieldsConfig(
            brands = brands,
            fields = SecureFieldsFieldsConfig(
                // No `view` targets — SecureFields builds its own composited form (`sdk.formView`)
                // for `VaultForm`/`VaultField` to host, instead of the View-system's per-field
                // `VaultFieldView` targets.
                cardNumber = VaultFieldConfig(placeholder = cardNumberPlaceholder),
                expDate = VaultFieldConfig(placeholder = expDatePlaceholder),
                cvv = VaultFieldConfig(placeholder = cvvPlaceholder),
                holderName = VaultFieldConfig(placeholder = holderNamePlaceholder),
            ),
            styles = options.theme?.toVaultStyles(),
        )
    }
    val sdk = rememberSecureFields(
        config = config,
        environment = host.environment.toVaultEnvironment(),
        tenantId = host.vaultTenantId.orEmpty(),
        monitoringEnabled = false,
    )

    val coordinator = remember(sdk, host, partnerWireValue) {
        CardCoordinator(sdk, host, partnerWireValue, scope, redirectLauncher, listener)
    }

    DisposableEffect(sdk, coordinator) {
        // Arm the fulfillment gate before anything can be typed: a mounted-but-empty card form means
        // the payment is NOT fulfilled, so `isPaymentFulfilled` stays false (and `submitPayment`
        // throws) until SecureFields reports every field valid. Web gets there from the other end —
        // its `setAsPrimarySource` throws ELEMENT_NOT_FULFILLED — but the guarantee is the same one.
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
        onDispose { unregister() }
    }

    return coordinator
}

/**
 * The state holder [rememberCardCoordinator] returns. [sdk] is the live `SecureFieldsAndroid`
 * instance — render it with SecureFields' own `VaultForm(coordinator.sdk)` /
 * `VaultField(coordinator.sdk.cardNumber)`. [formValid] and [selectedBrand] are Compose state, so
 * reading them in a composable recomposes correctly.
 */
class CardCoordinator internal constructor(
    val sdk: SecureFieldsAndroid,
    private val host: CardHost,
    private val partnerWireValue: String,
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
     * The SecureFields form's current validity — `false` until every configured field is valid, so
     * an untouched form never reads as payable. Mirrored into the checkout's own
     * `isPaymentFulfilled` via [CardHost.setFulfilled].
     */
    var formValid: Boolean by mutableStateOf(false)
        internal set

    /** Which co-badged brand to submit as (see [rememberCardCoordinator]'s `DisposableEffect` doc). */
    internal var selectedBrand: Brand? by mutableStateOf(null)

    /** Re-entrancy guard — same rationale as `GooglePayCoordinator.submitting`/`KlarnaCoordinator.submitting`. */
    private var submitting = false

    /** Report a lifecycle event to the headless host (which deactivates/reacts, web-parity). */
    private fun dispatch(event: PluginEvent) {
        runCatching { host.buildContext().dispatchEvent(event) }
    }

    /** Tokenize via SecureFields, then run `/init`+`/validate` through [host]. Call once [formValid]
     * is `true` and the shopper tapped "Pay". No-op while a previous tap's flow is still in progress,
     * and no-op (never an error) while the form isn't complete and valid. */
    fun submit() {
        if (submitting) return
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
                // The resolved gateway scheme (e.g. "CARTE_BANCAIRE") — SecureFields' own `Brand`
                // enum is a different, vault-internal vocabulary than what the Payment API's
                // `selected_network` expects (matching session `vault.supported_brands`).
                val gatewayScheme = selectedBrand?.toGatewayScheme()
                // saveToken forwards the checkout's registration decision to the vault — web's
                // `client.submit({saveToken: context.register})`.
                val submitOptions = SubmitOptions(
                    selectedNetwork = gatewayScheme,
                    saveToken = host.buildContext().register,
                )
                when (val tokenized = withContext(Dispatchers.IO) { sdk.submit(submitOptions) }) {
                    is SubmitResult.Success -> capture(tokenized, gatewayScheme)
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

    private suspend fun capture(tokenized: SubmitResult.Success, selectedNetwork: String?) {
        host.setCollected(
            mapOf(
                CardPlugin.COLLECTED_TOKEN to tokenized.vaultFormToken,
                CardPlugin.COLLECTED_BIN to tokenized.card?.bin,
                CardPlugin.COLLECTED_NETWORK to selectedNetwork,
            ),
        )
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
                dispatch(PluginEvent.Redirect)
                listener.onRequiresAction()
                redirectLauncher.launch(action)
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

/** The SDK's [PurseTheme] (input scope + pseudo-variants) → SecureFields' [VaultStyles]. */
private fun PurseTheme.toVaultStyles(): VaultStyles = (input ?: PurseTheme.FieldStyle()).toVaultStyles(
    focus = inputFocus?.toVaultStyles(),
    invalid = inputInvalid?.toVaultStyles(),
)

private fun PurseTheme.FieldStyle.toVaultStyles(
    focus: VaultStyles? = null,
    invalid: VaultStyles? = null,
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
)

/** Purse's [PurseEnvironment] (Payment API) → SecureFields' own [VaultEnvironment] (vault gateway)
 * — distinct enums, see [CardBrand][eu.purse.core.vault.CardBrand]'s doc on the parallel `Brand` split. */
private fun PurseEnvironment.toVaultEnvironment(): VaultEnvironment = when (this) {
    PurseEnvironment.PRODUCTION -> VaultEnvironment.PRODUCTION
    PurseEnvironment.SANDBOX -> VaultEnvironment.SANDBOX
    PurseEnvironment.TEST -> VaultEnvironment.TEST
    PurseEnvironment.DEVELOPMENT -> VaultEnvironment.SANDBOX
}
