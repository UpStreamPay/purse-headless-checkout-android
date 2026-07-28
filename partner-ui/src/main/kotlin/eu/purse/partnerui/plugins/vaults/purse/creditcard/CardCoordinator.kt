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
import eu.purse.core.environment.PurseEnvironment
import eu.purse.partnerui.plugins.generics.googlepay.redirect.RedirectAction
import eu.purse.partnerui.plugins.generics.googlepay.redirect.RedirectionCheckoutContract
import eu.purse.partnerui.plugins.generics.googlepay.redirect.RedirectionCheckoutResult
import eu.purse.partnerui.plugins.generics.googlepay.redirect.RedirectionResolver
import eu.purse.partnerui.plugins.generics.googlepay.redirect.withReturnUrlPrefix
import eu.purse.securefields.SecureFieldsAndroid
import eu.purse.securefields.SecureFieldsListener
import eu.purse.securefields.config.SecureFieldsConfig
import eu.purse.securefields.config.SecureFieldsFieldsConfig
import eu.purse.securefields.config.VaultEnvironment
import eu.purse.securefields.config.VaultFieldConfig
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
 */
@Composable
fun rememberCardCoordinator(host: CardHost, listener: CardCoordinator.Listener): CardCoordinator {
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
            .ifEmpty { listOf(Brand.VISA, Brand.MASTERCARD, Brand.CB) }
    }
    val config = remember(brands) {
        SecureFieldsConfig(
            brands = brands,
            fields = SecureFieldsFieldsConfig(
                // No `view` targets — SecureFields builds its own composited form (`sdk.formView`)
                // for `VaultForm`/`VaultField` to host, instead of the View-system's per-field
                // `VaultFieldView` targets.
                cardNumber = VaultFieldConfig(placeholder = "Card number"),
                expDate = VaultFieldConfig(placeholder = "MM / YY"),
                cvv = VaultFieldConfig(placeholder = "CVV"),
                holderName = VaultFieldConfig(placeholder = "Name on card"),
            ),
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
        // Tracks which brand to submit as, for a co-badged card (e.g. a French card that's both CB
        // and VISA) — mirrors web vault-front's BrandSelection: default to the first brand
        // SecureFields' own BIN lookup detects/matches, overridden if the host renders a brand
        // picker calling `sdk.setBrandSelection` (none is built here, so onBrandUserSelection is
        // future-proofing rather than something this coordinator exercises itself).
        val unregister = sdk.addListener(object : SecureFieldsListener {
            override fun onFormValid(payload: FormValidPayload) {
                coordinator.formValid = !payload.hasErrors
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
    private val listener: Listener,
) {
    interface Listener {
        /** The SecureFields form's validity changed — mirrors `onFormValid`. */
        fun onReady(ready: Boolean) {}

        /**
         * Terminal success — [summary] is a human-readable status to display, [globalStatus] the
         * `/validate` response's own `global_status` when this outcome came from one. `null` after a
         * 3-D Secure return, where the real outcome is deliberately not chased.
         */
        fun onResult(summary: String, globalStatus: String? = null) {}

        /** A 3-D Secure step is being launched (informational). */
        fun onRequiresAction() {}

        /**
         * The `payment_id` behind this payment, when the `/validate` response carries one — same
         * source and caveats as
         * [eu.purse.partnerui.plugins.generics.googlepay.GooglePayCoordinator.Listener.onPaymentId]:
         * purely informational (e.g. prefilling a "Capture" tool), never required for the payment
         * itself to resolve.
         */
        fun onPaymentId(paymentId: String) {}

        fun onCanceled() {}
        fun onError(message: String?) {}
    }

    /** The SecureFields form's current validity. */
    var formValid: Boolean by mutableStateOf(false)
        internal set

    /** Which co-badged brand to submit as (see [rememberCardCoordinator]'s `DisposableEffect` doc). */
    internal var selectedBrand: Brand? by mutableStateOf(null)

    /** Re-entrancy guard — same rationale as `GooglePayCoordinator.submitting`/`KlarnaCoordinator.submitting`. */
    private var submitting = false

    /** Tokenize via SecureFields, then run `/init`+`/validate` through [host]. Call once [formValid]
     * is `true` and the shopper tapped "Pay". No-op while a previous tap's flow is still in progress. */
    fun submit() {
        if (submitting) return
        submitting = true
        scope.launch {
            try {
                // The resolved gateway scheme (e.g. "CARTE_BANCAIRE") — SecureFields' own `Brand`
                // enum is a different, vault-internal vocabulary than what the Payment API's
                // `selected_network` expects (matching session `vault.supported_brands`).
                val gatewayScheme = selectedBrand?.toGatewayScheme()
                when (val tokenized = withContext(Dispatchers.IO) { sdk.submit(SubmitOptions(selectedNetwork = gatewayScheme)) }) {
                    is SubmitResult.Success -> capture(tokenized, gatewayScheme)
                    is SubmitResult.Error -> {
                        submitting = false
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
    Brand.CB -> "CARTE_BANCAIRE"
    Brand.DISCOVER -> "DISCOVER"
    Brand.DINERS -> "DINERS_CLUB"
    Brand.JCB -> "JCB"
    Brand.MAESTRO -> "MAESTRO"
    Brand.UNIONPAY -> "UNIONPAY"
    Brand.ONEY -> "ONEY"
    Brand.UNKNOWN -> null
}

/** Purse's [PurseEnvironment] (Payment API) → SecureFields' own [VaultEnvironment] (vault gateway)
 * — distinct enums, see [CardBrand][eu.purse.core.vault.CardBrand]'s doc on the parallel `Brand` split. */
private fun PurseEnvironment.toVaultEnvironment(): VaultEnvironment = when (this) {
    PurseEnvironment.PRODUCTION -> VaultEnvironment.PRODUCTION
    PurseEnvironment.SANDBOX -> VaultEnvironment.SANDBOX
    PurseEnvironment.TEST -> VaultEnvironment.TEST
    PurseEnvironment.DEVELOPMENT -> VaultEnvironment.SANDBOX
}
