package eu.purse.dropin_android.ui.primaries

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.paypal.android.paymentbuttons.PayPalButton
import eu.purse.dropin.DropinAndroid
import eu.purse.dropin.core.environment.PurseEnvironment
import eu.purse.dropin.payment.method.DisabledReason
import eu.purse.dropin.payment.method.DropinPaymentMethod
import eu.purse.dropin.payment.method.Method
import eu.purse.dropin.payment.partner.Partner
import eu.purse.dropin.vault.VaultVendor
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
) {

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
                // PayPal renders as the official branded PayPalButton (gold, PayPal wordmark) wired
                // to the checkout flow. The other xpay methods (Google Pay) aren't implemented in
                // this POC, so they stay plain, inert Material buttons.
                val button: View = if (method.method == Method.Wallet && method.partner == Partner.Paypal) {
                    PayPalButton(activity).apply {
                        layoutParams = buttonLayout
                        isEnabled = method.disabled == null
                        setOnClickListener { onPaypalWallet() }
                    }
                } else {
                    MaterialButton(activity).apply {
                        text = methodTitle(method)
                        isEnabled = method.disabled == null
                        layoutParams = buttonLayout
                    }
                }
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

            val details = buildList {
                method.minAmount?.let { add(activity.getString(R.string.method_amount_min, it.toPlainString())) }
                method.maxAmount?.let { add(activity.getString(R.string.method_amount_max, it.toPlainString())) }
                method.disabled?.let { add(describeDisabled(it)) }
            }
            item.primaryDetails.isVisible = details.isNotEmpty()
            item.primaryDetails.text = details.joinToString("\n")

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

            val disabled = method.disabled != null
            item.primaryRadio.isEnabled = !disabled
            item.root.alpha = if (disabled) 0.6f else 1f
            item.primaryContent.isVisible = false
            item.primaryHeader.setOnClickListener {
                if (disabled) return@setOnClickListener
                select(if (rows.any { it.id == method.id && it.content.isVisible }) null else method.id)
            }
            rows.add(PrimaryRow(method.id, item.primaryContent, item.primaryRadio))
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
                onPrimaryValidChanged(!payload.hasErrors)
            }
        })
        sdk.render()
        onPrimarySubmitReady { sdk.submit() }
        content.addView(form.root)
    }

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
