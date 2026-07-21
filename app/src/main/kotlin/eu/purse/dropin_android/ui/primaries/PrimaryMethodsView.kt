package eu.purse.dropin_android.ui.primaries

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.gms.wallet.button.ButtonConstants.ButtonTheme
import com.google.android.gms.wallet.button.ButtonConstants.ButtonType
import com.google.android.gms.wallet.button.ButtonOptions
import com.google.android.gms.wallet.button.PayButton
import com.google.android.material.button.MaterialButton
import com.paypal.android.paymentbuttons.PayPalButton
import eu.purse.dropin.DropinAndroid
import eu.purse.dropin.core.environment.PurseEnvironment
import eu.purse.dropin.partnerui.generic.googlepay.GooglePayMapper
import eu.purse.dropin.payment.method.DisabledReason
import eu.purse.dropin.payment.method.DropinPaymentMethod
import eu.purse.dropin.payment.method.Method
import eu.purse.dropin.payment.partner.Partner
import eu.purse.dropin.vault.VaultVendor
import org.json.JSONException
import eu.purse.dropin_android.R
import eu.purse.dropin_android.databinding.ItemPrimaryMethodBinding
import eu.purse.dropin_android.databinding.ViewSecurefieldsFormBinding
import eu.purse.dropin_android.ui.components.dividerLine
import eu.purse.dropin_android.ui.components.dp
import eu.purse.dropin_android.ui.components.listCard
import eu.purse.dropin_android.ui.components.marginParams
import eu.purse.securefields.SecureFieldsAndroid
import eu.purse.securefields.SecureFieldsListener
import eu.purse.securefields.config.SecureFieldsConfig
import eu.purse.securefields.config.SecureFieldsFieldsConfig
import eu.purse.securefields.config.VaultEnvironment
import eu.purse.securefields.config.VaultFieldConfig
import eu.purse.securefields.events.FormValidPayload
import eu.purse.securefields.model.Brand
import eu.purse.securefields.model.SubmitResult

/**
 * Renders the *primary* payment methods, mirroring web dropin's `ui/primaries`: the xpay wallet
 * buttons (Google Pay / popup PayPal) and the "other primaries" radio list (CB, redirect PayPal).
 *
 * The pay button lives on the host Activity (mirroring web's single PayButton), so this view reports
 * capture state back through two callbacks rather than owning it: [onPrimaryValidChanged] (the
 * SecureFields form's validity) and [onPrimarySubmitReady] (how to tokenize when the shopper pays).
 */
internal class PrimaryMethodsView(
    private val activity: AppCompatActivity,
    private val onPrimaryValidChanged: (Boolean) -> Unit,
    private val onPrimarySubmitReady: (suspend () -> SubmitResult) -> Unit,
    private val onPaypalWallet: () -> Unit,
    private val onGooglePay: () -> Unit,
) {

    // Per-method hooks that re-apply a recomputed disabled state to an already-rendered row/button in
    // place — mirroring web's reactive `updateMopDisabledState` disabling a mop without tearing down
    // its mounted form. Keyed by method id, rebuilt on each full render pass (see [reset]).
    private val disabledUpdaters = linkedMapOf<String, (DisabledReason?) -> Unit>()

    // The single SecureFields (CB) form's state. The form reports its own validity, but a primary is
    // only usable while it's also selectable, so the "valid primary" signal is `formValid && enabled`
    // — otherwise a gift card that drops the remaining amount below CB's min would still leave the
    // pay button live for a disabled method.
    private var cardMethodId: String? = null
    private var cardFormValid = false
    private var cardDisabled = false

    /** Clear the per-method hooks before a fresh render pass (a new session, or a re-render). */
    fun reset() {
        disabledUpdaters.clear()
        cardMethodId = null
        cardFormValid = false
        cardDisabled = false
    }

    /**
     * Re-apply the disabled state carried by each of [methods] to its rendered row/button — called
     * after the split changes (a secondary applied) with `dropin.methodsForPrimaryReach(remaining)`.
     * Methods with no rendered row (e.g. not in this group) are ignored.
     */
    fun applyDisabledStates(methods: List<DropinPaymentMethod>) {
        methods.forEach { disabledUpdaters[it.id]?.invoke(it.disabled) }
    }

    /** xpay methods (Google Pay, popup PayPal) as full-width buttons, two per row. */
    fun renderXpay(container: ViewGroup, methods: List<DropinPaymentMethod>) {
        var index = 0
        while (index < methods.size) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = activity.marginParams(top = activity.dp(8))
            }
            // Up to two buttons per row, each taking an equal share (a lone button fills the row).
            for (offset in 0 until 2) {
                val method = methods.getOrNull(index + offset) ?: break
                val buttonLayout = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (offset == 1) marginStart = activity.dp(8)
                }
                // PayPal renders as the official branded PayPalButton (gold, PayPal wordmark), and
                // Google Pay as Google's own branded PayButton — both wired to their checkout
                // flow. Any other xpay method stays a plain, inert Material button.
                val button: View = when {
                    method.method == Method.Wallet && method.partner == Partner.Paypal ->
                        PayPalButton(activity).apply {
                            layoutParams = buttonLayout
                            isEnabled = method.disabled == null
                            setOnClickListener { onPaypalWallet() }
                        }
                    method.method == Method.GooglePay ->
                        PayButton(activity).apply {
                            layoutParams = buttonLayout
                            isEnabled = method.disabled == null
                            // Chrome-only: the real per-session gateway config (gatewayMerchantId,
                            // networks) only exists after `/init`, which runs on tap — see
                            // DropinAndroid.createGooglePayRequest. This generic list is enough for
                            // Google to decide how to render the button itself.
                            try {
                                initialize(
                                    ButtonOptions.newBuilder()
                                        .setButtonType(ButtonType.PAY)
                                        .setButtonTheme(ButtonTheme.DARK)
                                        .setAllowedPaymentMethods(GooglePayMapper.buttonAllowedPaymentMethodsJson().toString())
                                        .build(),
                                )
                            } catch (e: JSONException) {
                                // Leave the button uninitialized rather than crash the whole row.
                            }
                            setOnClickListener { onGooglePay() }
                        }
                    else ->
                        MaterialButton(activity).apply {
                            text = methodTitle(method)
                            isEnabled = method.disabled == null
                            layoutParams = buttonLayout
                        }
                }
                // Let a later split recompute enable/disable this button in place.
                disabledUpdaters[method.id] = { disabled -> button.isEnabled = disabled == null }
                row.addView(button)
            }
            container.addView(row)
            index += 2
        }
    }

    /**
     * The "other primaries" radio list, mirroring web's PrimaryPaymentMethodList: each method is a
     * collapsed row (radio + name + brand badges); tapping opens its content, and only one is open
     * at a time. The opened content is informational in this POC — the real capture form
     * (SecureFields for CB, PayPal redirect) isn't wired up yet.
     */
    fun renderPrimaries(
        container: ViewGroup,
        methods: List<DropinPaymentMethod>,
        dropin: DropinAndroid,
    ) {
        val card = activity.listCard()
        val list = card.getChildAt(0) as LinearLayout
        val rows = mutableListOf<PrimaryRow>()

        fun select(id: String?) {
            rows.forEach { row ->
                val open = row.id == id
                row.content.isVisible = open
                row.radio.isChecked = open
            }
        }

        methods.forEachIndexed { index, method ->
            if (index > 0) list.addView(activity.dividerLine(top = 0, bottom = 0))
            val item = ItemPrimaryMethodBinding.inflate(activity.layoutInflater, list, false)
            item.primaryName.text = methodTitle(method)

            val brands = method.configuration.vault?.supportedBrands.orEmpty()
            item.primaryBadges.isVisible = brands.isNotEmpty()
            item.primaryBadges.text = brands.joinToString(" · ") { it.wireValue }

            // The CB (creditcard) method is vault-backed when its config vault vendor isn't `none`
            // (see usp-widget headless-checkout primaries.ts). For the Purse vault that means
            // capturing the card with the SecureFields SDK, rendered inline in the opened row.
            if (usesSecureFields(method)) {
                item.primaryNote.isVisible = false
                if (dropin.vaultTenantId.isNullOrBlank()) {
                    item.primaryNote.isVisible = true
                    item.primaryNote.setText(R.string.cc_no_vault)
                } else {
                    addSecureFieldsForm(item.primaryContent, method, dropin)
                }
            } else {
                item.primaryNote.setText(R.string.primary_not_wired)
            }

            // Re-applies a (possibly recomputed) disabled state to this row: detail lines, radio,
            // dimming, and collapsing a now-unusable row. Registered so a later split recompute can
            // call it in place without re-mounting the SecureFields form (see [applyDisabledStates]).
            var rowDisabled: DisabledReason? = method.disabled
            fun applyRowDisabled(disabled: DisabledReason?) {
                rowDisabled = disabled
                val off = disabled != null
                val details = buildList {
                    method.minAmount?.let { add(activity.getString(R.string.method_amount_min, it.toPlainString())) }
                    method.maxAmount?.let { add(activity.getString(R.string.method_amount_max, it.toPlainString())) }
                    disabled?.let { add(describeDisabled(it)) }
                }
                item.primaryDetails.isVisible = details.isNotEmpty()
                item.primaryDetails.text = details.joinToString("\n")
                item.primaryRadio.isEnabled = !off
                item.root.alpha = if (off) 0.6f else 1f
                if (off) {
                    // A row disabled mid-flow (its min no longer met, or payment now fulfilled)
                    // collapses; if it's the card form, it also stops counting as a valid primary.
                    item.primaryContent.isVisible = false
                    item.primaryRadio.isChecked = false
                }
                if (method.id == cardMethodId) {
                    cardDisabled = off
                    reportCardValidity()
                }
            }

            item.primaryContent.isVisible = false
            item.primaryHeader.setOnClickListener {
                if (rowDisabled != null) return@setOnClickListener
                select(if (rows.any { it.id == method.id && it.content.isVisible }) null else method.id)
            }
            rows.add(PrimaryRow(method.id, item.primaryContent, item.primaryRadio))
            disabledUpdaters[method.id] = ::applyRowDisabled
            applyRowDisabled(method.disabled)
            list.addView(item.root)
        }
        container.addView(card)
    }

    /**
     * CB is vault-backed (→ SecureFields) when it's a `creditcard` method whose config vault vendor
     * isn't `none` (the general "uses a vault" check from web's primaries.ts); the Purse vendor
     * specifically maps to the SecureFields SDK.
     */
    private fun usesSecureFields(method: DropinPaymentMethod): Boolean =
        method.method == Method.CreditCard &&
            method.configuration.vault?.vendor == VaultVendor.PURSE

    /**
     * Renders the SecureFields card form into [content] and reports its validity/submit hook back to
     * the host Activity. The SDK ties its own teardown to the Activity's lifecycle.
     */
    private fun addSecureFieldsForm(
        content: LinearLayout,
        method: DropinPaymentMethod,
        dropin: DropinAndroid,
    ) {
        cardMethodId = method.id
        val form = ViewSecurefieldsFormBinding.inflate(activity.layoutInflater, content, false)

        // Session's supported_brands (CardBrand) → SecureFields' Brand; fall back to a sane default.
        val brands = method.configuration.vault?.supportedBrands.orEmpty()
            .map { Brand.fromString(it.wireValue) }
            .filter { it != Brand.UNKNOWN }
            .distinct()
            .ifEmpty { listOf(Brand.VISA, Brand.MASTERCARD, Brand.CB) }

        val sdk = SecureFieldsAndroid.init(
            context = activity,
            lifecycleOwner = activity,
            config = SecureFieldsConfig(
                brands = brands,
                fields = SecureFieldsFieldsConfig(
                    cardNumber = VaultFieldConfig(view = form.sfCardNumber, placeholder = activity.getString(R.string.cc_card_number)),
                    expDate = VaultFieldConfig(view = form.sfExpDate, placeholder = activity.getString(R.string.cc_exp)),
                    cvv = VaultFieldConfig(view = form.sfCvv, placeholder = activity.getString(R.string.cc_cvv)),
                    holderName = VaultFieldConfig(view = form.sfHolderName, placeholder = activity.getString(R.string.cc_holder)),
                ),
            ),
            environment = vaultEnvironment(dropin.sessionParams.environment),
            tenantId = dropin.vaultTenantId.orEmpty(),
            monitoringEnabled = false,
        )
        // The form's validity is the primary's "valid" signal; the Activity's pay button (not a
        // per-form button) drives tokenization via the submit hook — mirroring web's single PayButton.
        sdk.addListener(object : SecureFieldsListener {
            override fun onFormValid(payload: FormValidPayload) {
                cardFormValid = !payload.hasErrors
                reportCardValidity()
            }
        })
        sdk.render()
        onPrimarySubmitReady { sdk.submit() }
        content.addView(form.root)
    }

    /** The CB form is a usable primary only while it's both valid and still selectable. */
    private fun reportCardValidity() = onPrimaryValidChanged(cardFormValid && !cardDisabled)

    /** Drop-in's [PurseEnvironment] (Payment API) → SecureFields' own [VaultEnvironment] (vault
     * gateway). Distinct enums — see docs/integration/getting-started.md. */
    private fun vaultEnvironment(env: PurseEnvironment): VaultEnvironment = when (env) {
        PurseEnvironment.PRODUCTION -> VaultEnvironment.PRODUCTION
        PurseEnvironment.SANDBOX -> VaultEnvironment.SANDBOX
        PurseEnvironment.TEST -> VaultEnvironment.TEST
        PurseEnvironment.DEVELOPMENT -> VaultEnvironment.SANDBOX
    }

    /** Human-readable name for the row; only the POC's implemented methods reach here. */
    private fun methodTitle(method: DropinPaymentMethod): String = when (method.method) {
        Method.CreditCard -> "Card"
        Method.GooglePay -> "Google Pay"
        Method.Wallet -> "PayPal"
        Method.GiftCard -> if (method.partner == Partner.Illicado) "Illicado gift card" else "Gift card"
        else -> method.id
    }

    private fun describeDisabled(reason: DisabledReason): String = when (reason) {
        DisabledReason.PaymentFulfilled ->
            activity.getString(R.string.disabled_fulfilled)
        is DisabledReason.MinAmountNotMet ->
            activity.getString(R.string.disabled_min, reason.minAmount.toPlainString(), reason.restToPay.toPlainString())
        is DisabledReason.MaxAmountExceeded ->
            activity.getString(R.string.disabled_max, reason.maxAmount.toPlainString())
    }

    private class PrimaryRow(val id: String, val content: View, val radio: RadioButton)
}
