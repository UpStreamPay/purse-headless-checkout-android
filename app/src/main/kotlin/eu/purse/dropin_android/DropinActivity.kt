package eu.purse.dropin_android

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import eu.purse.dropin.DropinAndroid
import eu.purse.dropin.core.environment.PurseEnvironment
import eu.purse.dropin.partnerui.partner.paypal.wallet.PaypalCheckoutContract
import eu.purse.dropin.partnerui.partner.paypal.wallet.PaypalCheckoutInput
import eu.purse.dropin.partnerui.partner.paypal.wallet.PaypalCheckoutResult
import eu.purse.dropin.payment.method.DropinPaymentMethod
import eu.purse.dropin.payment.method.IntegrationMode
import eu.purse.dropin.payment.method.Method
import eu.purse.dropin.payment.method.SecondaryMethod
import eu.purse.dropin.payment.partner.Partner
import eu.purse.dropin.payment.split.PaymentSplitter
import eu.purse.dropin.session.DropinSessionException
import eu.purse.dropin_android.databinding.ActivityDropinBinding
import eu.purse.dropin_android.ui.components.dividerLine
import eu.purse.dropin_android.ui.components.dp
import eu.purse.dropin_android.ui.components.emptyMethodsView
import eu.purse.dropin_android.ui.components.orDivider
import eu.purse.dropin_android.ui.components.sectionTitle
import eu.purse.dropin_android.ui.primaries.PrimaryMethodsView
import eu.purse.dropin_android.ui.secondaries.SecondaryMethodsView
import eu.purse.securefields.model.SubmitResult
import kotlinx.coroutines.launch
import java.math.BigDecimal

/**
 * The Drop-in page: decodes the `widget.data` handed over by [MainActivity], groups the session's
 * payment methods, and hands each group to a UI renderer. Grouping and ordering mirror the web
 * Drop-in's `App.svelte` / `getMOPSGroups` (see usp-widget `packages/dropin`): secondaries first,
 * then a "Payment method" section with xpay methods (Google Pay) above the remaining primaries
 * (CB, PayPal).
 *
 * This Activity is only the orchestrator; the rendering lives in [PrimaryMethodsView] and
 * [SecondaryMethodsView], over shared helpers in `ui.components`. Only methods the POC actually
 * implements are shown — anything else is filtered out (see [isSupported]).
 */
class DropinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDropinBinding

    // Payment-completion state. [split] (the SDK's PaymentSplitter) owns the order amount and how
    // much applied secondaries (gift cards) cover; this Activity only adds the UI-capture signals —
    // whether a primary method is currently valid, and how to submit it. The pay button is enabled
    // when the split is fulfilled or a primary is valid — see [updatePayState].
    private var split = PaymentSplitter(BigDecimal.ZERO)
    private var currency: String = ""
    private var primaryValid: Boolean = false
    private var primarySubmit: (suspend () -> SubmitResult)? = null

    // The active decoded session, plus the PayPal handoff state — the init's step_id links the
    // /init that created the order to the /validate that captures it.
    private var activeDropin: DropinAndroid? = null
    private var paypalStepId: String? = null

    // Registered up front (before the Activity is started) so the PayPal approval result routes back
    // here. The return Activity + its deep-link filter live in :partner-ui — no manifest work here.
    private val paypalLauncher = registerForActivityResult(PaypalCheckoutContract()) { onPaypalResult(it) }

    private val primaryMethods by lazy {
        PrimaryMethodsView(
            activity = this,
            onPrimaryValidChanged = { valid -> primaryValid = valid; updatePayState() },
            onPrimarySubmitReady = { submit -> primarySubmit = submit },
            onPaypalWallet = { startPaypal() },
        )
    }
    private val secondaryMethods by lazy {
        SecondaryMethodsView(
            activity = this,
            onSecondaryApplied = { balance -> split.applySecondary(balance); updatePayState() },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Card fields are shown here — keep them out of screenshots / the recents thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityDropinBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets()
        binding.payButton.setOnClickListener { onPay() }

        val widgetData = intent.getStringExtra(EXTRA_WIDGET_DATA).orEmpty()
        try {
            renderSession(DropinAndroid.init(widgetData))
        } catch (e: DropinSessionException) {
            binding.sessionSummaryText.text = getString(R.string.session_error, e.message)
            binding.dropinContainer.removeAllViews()
        }
    }

    private fun renderSession(dropin: DropinAndroid) {
        binding.sessionSummaryText.text = buildString {
            appendLine(getString(R.string.result_header_session))
            appendLine("  id: ${dropin.session.id}")
            appendLine("  amount: ${dropin.session.amount} ${dropin.session.currency}")
            appendLine("  isInformationRequest: ${dropin.isInformationRequest}")
            appendLine("  vaultTenantId: ${dropin.vaultTenantId}")
            appendLine("  entityId: ${dropin.sessionParams.entityId}")
            append("  apiRoot: ${dropin.paymentApiRoot}")
        }

        // Reset payment state for this session (a fresh splitter owes the full amount).
        split = PaymentSplitter(dropin.session.amount)
        activeDropin = dropin
        paypalStepId = null
        currency = dropin.session.currency
        primaryValid = false
        primarySubmit = null
        binding.payButton.isVisible = false
        binding.payStatus.isVisible = false

        // Drop everything the POC doesn't implement, then split into the web's three groups.
        val supported = dropin.paymentMethods.filter { it.isSupported() }
        val secondaries = supported.filter { it is SecondaryMethod }
        val xpay = supported.filter { it !is SecondaryMethod && it.isXPay() }
        val others = supported.filter { it !is SecondaryMethod && !it.isXPay() }

        val container = binding.dropinContainer
        container.removeAllViews()

        if (supported.isEmpty()) {
            container.addView(emptyMethodsView())
            return
        }

        // Secondaries (e.g. Illicado) first, under their own section title, above a divider.
        if (secondaries.isNotEmpty()) {
            container.addView(sectionTitle(getString(R.string.section_secondaries)))
            secondaries.forEach { container.addView(secondaryMethods.accordion(it, container, dropin)) }
            container.addView(dividerLine())
        }

        if (xpay.isNotEmpty() || others.isNotEmpty()) {
            container.addView(sectionTitle(getString(R.string.section_payment_method)))
            if (xpay.isNotEmpty()) {
                primaryMethods.renderXpay(container, xpay)
            }
            if (xpay.isNotEmpty() && others.isNotEmpty()) {
                container.addView(orDivider())
            }
            if (others.isNotEmpty()) {
                primaryMethods.renderPrimaries(container, others, dropin)
            }
        }

        binding.payButton.isVisible = true
        updatePayState()
    }

    /**
     * Pay button state, driven by the [split] (web's `isPaymentFulfilled` / `remainingAmountToPay`):
     * the label shows what's left to pay, and the button is enabled when secondaries already cover
     * the whole amount OR a primary is valid.
     */
    private fun updatePayState() {
        binding.payButton.text = getString(R.string.pay_button, split.remaining.toPlainString(), currency)
        binding.payButton.isEnabled = split.isFulfilled || primaryValid
    }

    private fun onPay() {
        if (split.isFulfilled) {
            // Secondaries alone cover the amount — no primary needed.
            showPayStatus(getString(R.string.pay_secondaries_cover))
            return
        }
        val submit = primarySubmit ?: return
        if (!primaryValid) return
        binding.payButton.isEnabled = false
        showPayStatus(getString(R.string.pay_processing))
        // Capture the card (tokenize). The subsequent /init + /validate submission isn't built yet.
        lifecycleScope.launch {
            when (val result = submit()) {
                is SubmitResult.Success ->
                    showPayStatus(getString(R.string.pay_primary_ok, result.vaultFormToken))
                is SubmitResult.Error -> {
                    showPayStatus(getString(R.string.pay_primary_error, result.message))
                    updatePayState()
                }
            }
        }
    }

    private fun showPayStatus(message: String) {
        binding.payStatus.text = message
        binding.payStatus.isVisible = true
    }

    /**
     * PayPal wallet, step 1: create the order (`/init`) off the main thread, then hand the order id
     * to the :partner-ui PayPal launcher (which opens the approval and returns to [onPaypalResult]).
     */
    private fun startPaypal() {
        val dropin = activeDropin ?: return
        val clientId = dropin.paypalClientId
        if (clientId.isNullOrBlank()) {
            showPayStatus(getString(R.string.paypal_no_client_id))
            return
        }
        showPayStatus(getString(R.string.paypal_creating_order))
        Thread {
            try {
                val order = dropin.createPaypalOrder()
                runOnUiThread {
                    paypalStepId = order.stepId
                    val sandbox = dropin.sessionParams.environment != PurseEnvironment.PRODUCTION
                    paypalLauncher.launch(PaypalCheckoutInput(order.orderId, clientId, sandbox))
                }
            } catch (e: Exception) {
                runOnUiThread { showPayStatus(getString(R.string.paypal_error, e.message)) }
            }
        }.start()
    }

    /** PayPal wallet, step 2: the approval outcome. On approval, capture via `/validate`. */
    private fun onPaypalResult(result: PaypalCheckoutResult) {
        when (result) {
            is PaypalCheckoutResult.Approved -> capturePaypal()
            PaypalCheckoutResult.Canceled -> showPayStatus(getString(R.string.paypal_canceled))
            is PaypalCheckoutResult.Failed -> showPayStatus(getString(R.string.paypal_failed, result.message))
        }
    }

    /**
     * PayPal wallet, step 3: capture the approved order (`/validate`, carrying the init's step id)
     * off the main thread. A chained 3-D Secure redirect ([WalletValidationResult.redirectionUrl])
     * isn't handled in this POC — see docs/architecture/redirect-handling.md.
     */
    private fun capturePaypal() {
        val dropin = activeDropin ?: return
        showPayStatus(getString(R.string.paypal_capturing))
        Thread {
            try {
                val result = dropin.validatePaypal(paypalStepId)
                runOnUiThread {
                    if (result.isSuccess) {
                        showPayStatus(getString(R.string.paypal_success))
                    } else {
                        showPayStatus(getString(R.string.paypal_declined, result.globalStatus))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { showPayStatus(getString(R.string.paypal_error, e.message)) }
            }
        }.start()
    }

    /**
     * targetSdk 35+ draws edge-to-edge by default; pad the scrollable content by the system-bar
     * insets on top of its base 16dp so nothing overlaps the bars.
     */
    private fun applyContentInsets() {
        val base = dp(16)
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentRoot) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = base + bars.left,
                top = base + bars.top,
                right = base + bars.right,
                bottom = base + bars.bottom,
            )
            windowInsets
        }
    }

    companion object {
        const val EXTRA_WIDGET_DATA = "widget_data"

        /** Methods the POC actually implements — everything else is filtered out of the list. */
        private val SUPPORTED_METHODS = setOf(
            Method.CreditCard,
            Method.GooglePay,
            Method.Wallet,
            Method.GiftCard,
        )

        /**
         * xpay grouping, mirroring web's `filterXPays`: wallet-style methods rendered as their own
         * buttons, but only when the integration isn't a redirection (redirect PayPal falls back to
         * the regular primaries list).
         */
        private val XPAY_METHODS = setOf(Method.GooglePay, Method.Wallet, Method.Bnpl)

        private fun DropinPaymentMethod.isSupported(): Boolean =
            method in SUPPORTED_METHODS && partner !is Partner.Other

        private fun DropinPaymentMethod.isXPay(): Boolean =
            method in XPAY_METHODS &&
                configuration.settings.integrationMode != IntegrationMode.REDIRECTION
    }
}
