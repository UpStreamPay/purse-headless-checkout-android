package eu.purse.dropin_android

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wallet.AutoResolveHelper
import com.google.android.gms.wallet.PaymentData
import com.google.android.gms.wallet.contract.TaskResultContracts.GetPaymentDataResult
import eu.purse.dropin.DropinAndroid
import eu.purse.dropin.core.environment.PurseEnvironment
import eu.purse.dropin.partnerui.generic.googlepay.GooglePayClient
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

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
    private var primarySubmit: (suspend () -> Pair<SubmitResult, String?>)? = null

    // The active decoded session, plus the PayPal handoff state — the init's step_id links the
    // /init that created the order to the /validate that captures it.
    private var activeDropin: DropinAndroid? = null
    private var paypalStepId: String? = null

    // The partner wire value ("adyen") from the last Google Pay request created — carried into the
    // follow-up /validate call, the same role [paypalStepId] plays for PayPal.
    private var googlePayPartnerWire: String? = null

    // Registered up front (before the Activity is started) so the PayPal approval result routes back
    // here. The return Activity + its deep-link filter live in :partner-ui — no manifest work here.
    private val paypalLauncher = registerForActivityResult(PaypalCheckoutContract()) { onPaypalResult(it) }

    // Registered up front, same rationale as [paypalLauncher] — but Google Pay needs no dedicated
    // return Activity/manifest work at all: `GetPaymentDataResult` is Google's own
    // ActivityResultContract, resolving the `Task<PaymentData>` the sheet produces.
    private val googlePayLauncher = registerForActivityResult(GetPaymentDataResult()) { taskResult ->
        when (taskResult.status.statusCode) {
            CommonStatusCodes.SUCCESS -> onGooglePaySuccess(taskResult.result)
            CommonStatusCodes.CANCELED -> showPayStatus(getString(R.string.googlepay_canceled))
            AutoResolveHelper.RESULT_ERROR ->
                showPayStatus(getString(R.string.googlepay_failed, taskResult.status.statusMessage))
            else -> showPayStatus(getString(R.string.googlepay_error, "status ${taskResult.status.statusCode}"))
        }
    }

    private val primaryMethods by lazy {
        PrimaryMethodsView(
            activity = this,
            onPrimaryValidChanged = { valid -> primaryValid = valid; updatePayState() },
            onPrimarySubmitReady = { submit -> primarySubmit = submit },
            onPaypalWallet = { startPaypal() },
            onGooglePay = { startGooglePay() },
        )
    }
    private val secondaryMethods by lazy {
        SecondaryMethodsView(
            activity = this,
            onSecondaryApplied = { balance -> onSecondaryApplied(balance) },
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

        // Debug tool mirroring usp-widget's demo confirmation page — see [getSessionViaAlfred].
        binding.alfredSessionIdInput.setText(resolveDefaultAlfredSessionId(widgetData))
        binding.getSessionViaAlfredButton.setOnClickListener { getSessionViaAlfred() }
    }

    /**
     * Mirrors `resolveDefaultSessionId` in `usp-widget/sites/demo/src/routes/widgets/confirmation/+page.svelte`:
     * prefer a `legacy_payment_session_id` field on the decoded `widget.data` blob (a field this
     * POC's own `SessionCodec` doesn't model — it isn't in the SDK's published types either, see
     * docs/decisions/pain-points-recap.md) and fall back to the *orchestration* session's own `id`
     * (`POST /orchestration_session`'s top-level `id` — the v2 ClientSession id, distinct from
     * `payment_session.id`, which this function deliberately does **not** fall back to, matching
     * the reference page). Empty if neither is available — the field stays editable either way.
     */
    private fun resolveDefaultAlfredSessionId(widgetData: String): String {
        val legacyId = try {
            val decoded = String(Base64.getDecoder().decode(widgetData), Charsets.UTF_8)
            JSONObject(decoded).optStringOrNull("legacy_payment_session_id")
        } catch (e: Exception) {
            null
        }
        return legacyId ?: intent.getStringExtra(EXTRA_ORCHESTRATION_SESSION_ID).orEmpty()
    }

    /**
     * The alfred call the reference confirmation page's "Get Transactions" button makes:
     * `GET {alfredBaseUrl}/payment_session/{id}`, which alfred forwards to the real Payment API's
     * `GET /{entity}/sessions/{session}` using its own server-side credentials — see
     * `usp-widget/packages/alfred/routes/payment.mjs` + `services/fetcher.mjs`. The session id is
     * whatever is currently typed into the field (editable, not necessarily this Activity's own
     * session). Result renders in its own view directly below the row, not in [showPayStatus].
     */
    private fun getSessionViaAlfred() {
        val sessionId = binding.alfredSessionIdInput.text?.toString()?.trim().orEmpty()
        if (sessionId.isEmpty()) {
            showAlfredResult(getString(R.string.alfred_session_id_required))
            return
        }
        showAlfredResult(getString(R.string.alfred_session_fetching))
        Thread {
            try {
                val response = alfredHttpGet("${alfredBaseUrl()}/payment_session/$sessionId")
                val pretty = JSONObject(response).toString(2)
                runOnUiThread { showAlfredResult("${getString(R.string.alfred_session_result, sessionId)}\n$pretty") }
            } catch (e: Exception) {
                runOnUiThread { showAlfredResult(getString(R.string.alfred_session_error, e.message)) }
            }
        }.start()
    }

    private fun showAlfredResult(text: String) {
        binding.alfredSessionResult.text = text
        binding.alfredSessionResult.isVisible = true
    }

    /** Same env-driven base URL as [MainActivity.alfredBaseUrl] — see docs/internal/testing-with-alfred.md. */
    private fun alfredBaseUrl(): String =
        BuildConfig.ALFRED_BASE_URL.trim().trimEnd('/').takeIf { it.isNotBlank() }
            ?: "http://10.0.2.2:9001"

    private fun alfredHttpGet(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code from $urlString: $body")
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    private fun renderSession(dropin: DropinAndroid) {
        // Hidden by default — this raw session dump is debug-only noise for the checkout UI; the
        // pay/PayPal/Google Pay/Get Transactions buttons below are unaffected.
        binding.sessionSummaryText.isVisible = false

        // Reset payment state for this session (a fresh splitter owes the full amount).
        split = PaymentSplitter(dropin.session.amount)
        activeDropin = dropin
        paypalStepId = null
        googlePayPartnerWire = null
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
        // Fresh render pass — drop any disabled-state hooks registered for the previous session.
        primaryMethods.reset()
        secondaryMethods.reset()

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

    /**
     * A secondary (gift card) was tokenized and applied: fold its balance into the [split], then
     * recompute every method's disabled state against the new remaining amount and push it to the
     * rendered rows — the app-side mirror of web re-running `updateMopDisabledState` on each
     * `primaryReach` change. A primary now below its min shows `MinAmountNotMet`; once the split is
     * fulfilled, everything shows `PaymentFulfilled` and the pay button pays with secondaries alone.
     */
    private fun onSecondaryApplied(balance: BigDecimal) {
        split.applySecondary(balance)
        activeDropin?.let { dropin ->
            val methods = dropin.methodsForPrimaryReach(split.remaining)
            secondaryMethods.applyDisabledStates(methods)
            primaryMethods.applyDisabledStates(methods)
        }
        updatePayState()
    }

    private fun onPay() {
        if (split.isFulfilled) {
            // Secondaries alone cover the amount — no primary needed.
            showPayStatus(getString(R.string.pay_secondaries_cover))
            return
        }
        val submit = primarySubmit ?: return
        val dropin = activeDropin ?: return
        if (!primaryValid) return
        binding.payButton.isEnabled = false
        showPayStatus(getString(R.string.pay_processing))
        lifecycleScope.launch {
            val (result, selectedNetwork) = submit()
            when (result) {
                is SubmitResult.Success -> captureCard(dropin, result, selectedNetwork)
                is SubmitResult.Error -> {
                    showPayStatus(getString(R.string.pay_primary_error, result.message))
                    updatePayState()
                }
            }
        }
    }

    /**
     * CB, steps 2–3 after SecureFields tokenizes: `/init` (registers the method, returns a
     * `step_id`) then `/validate` (carrying the vault token) — mirrors usp-widget's `purse` vault
     * bridge (`packages/partner-ui/src/plugins/vaults/purse/creditcard/bridge.ts`'s
     * `submitForResult`/`postValidate`), simplified to run both calls right after tokenization
     * instead of `/init` at session-render time — see
     * [eu.purse.dropin.partnerui.vault.purse.creditcard.CardMapper]. [selectedNetwork] is the same
     * resolved gateway scheme (e.g. `"CARTE_BANCAIRE"`) already submitted to SecureFields' own
     * tokenization call — see [PrimaryMethodsView.addSecureFieldsForm], **not**
     * `tokenized.card?.detectedBrands` (a co-badged card can match more than one brand; that list
     * is what's detected, not necessarily what's resolved/submitted). A chained 3-D Secure redirect
     * (`CardValidationResult.redirectionUrl`) isn't followed in this POC — same limitation as
     * PayPal/Google Pay, see docs/architecture/redirect-handling.md.
     */
    private suspend fun captureCard(dropin: DropinAndroid, tokenized: SubmitResult.Success, selectedNetwork: String?) {
        showPayStatus(getString(R.string.pay_card_capturing))
        try {
            val result = withContext(Dispatchers.IO) {
                val stepId = dropin.createCardInit().stepId
                dropin.validateCard(
                    vaultVendorTokenId = tokenized.vaultFormToken,
                    bin = tokenized.card?.bin,
                    selectedNetwork = selectedNetwork,
                    stepId = stepId,
                )
            }
            if (result.isSuccess) {
                showPayStatus(getString(R.string.pay_card_success))
            } else {
                showPayStatus(getString(R.string.pay_card_declined, result.globalStatus))
                updatePayState()
            }
        } catch (e: Exception) {
            showPayStatus(getString(R.string.pay_card_error, e.message))
            updatePayState()
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
                    val summary = if (result.isSuccess) {
                        getString(R.string.paypal_success)
                    } else {
                        getString(R.string.paypal_declined, result.globalStatus)
                    }
                    showPayStatus(summary)
                }
            } catch (e: Exception) {
                runOnUiThread { showPayStatus(getString(R.string.paypal_error, e.message)) }
            }
        }.start()
    }

    /**
     * Google Pay, step 1: create the request (`/init`) off the main thread — the gateway config
     * (`gatewayMerchantId`, allowed networks) comes back from the backend, the same way PayPal's
     * `order_id` does — then show the native sheet via `loadPaymentData`. Unlike PayPal there's no
     * separate approval Activity: the sheet itself is the whole "approval" step, and its result
     * routes back through [googlePayLauncher].
     */
    private fun startGooglePay() {
        val dropin = activeDropin ?: return
        showPayStatus(getString(R.string.googlepay_creating_request))
        Thread {
            try {
                val request = dropin.createGooglePayRequest()
                val client = GooglePayClient.create(this, dropin.sessionParams.environment)
                runOnUiThread {
                    googlePayPartnerWire = request.partnerWireValue
                    client.loadPaymentDataTask(request.paymentDataRequestJson)
                        .addOnCompleteListener(googlePayLauncher::launch)
                }
            } catch (e: Exception) {
                runOnUiThread { showPayStatus(getString(R.string.googlepay_error, e.message)) }
            }
        }.start()
    }

    /**
     * Google Pay, step 2: the sheet succeeded — parse the token and selected card network out of
     * `PaymentData.toJson()` and capture via `/validate`. Mirrors [onPaypalResult] +
     * [capturePaypal] collapsed into one step, since Google Pay has no separate
     * approval-then-capture activities the way PayPal's browser-switch does.
     */
    private fun onGooglePaySuccess(paymentData: PaymentData?) {
        if (paymentData == null) {
            showPayStatus(getString(R.string.googlepay_error, "no payment data"))
            return
        }
        try {
            val methodData = JSONObject(paymentData.toJson()).getJSONObject("paymentMethodData")
            val token = methodData.getJSONObject("tokenizationData").getString("token")
            val network = methodData.optJSONObject("info")?.optString("cardNetwork")
            captureGooglePay(token, network)
        } catch (e: JSONException) {
            showPayStatus(getString(R.string.googlepay_error, e.message))
        }
    }

    /**
     * Google Pay, step 3: capture the token (`/validate`) off the main thread. A chained 3-D
     * Secure redirect ([eu.purse.dropin.partnerui.generic.googlepay.GooglePayValidationResult.redirectionUrl])
     * isn't followed in this POC — see docs/architecture/redirect-handling.md, the same limitation
     * as PayPal's [capturePaypal].
     */
    private fun captureGooglePay(token: String, selectedNetwork: String?) {
        val dropin = activeDropin ?: return
        val partnerWire = googlePayPartnerWire ?: return
        showPayStatus(getString(R.string.googlepay_capturing))
        Thread {
            try {
                val result = dropin.validateGooglePay(partnerWire, token, selectedNetwork)
                runOnUiThread {
                    val summary = if (result.isSuccess) {
                        getString(R.string.googlepay_success)
                    } else {
                        getString(R.string.googlepay_declined, result.globalStatus)
                    }
                    showPayStatus(summary)
                }
            } catch (e: Exception) {
                runOnUiThread { showPayStatus(getString(R.string.googlepay_error, e.message)) }
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

        /** The orchestration session's own `id`, passed by [MainActivity] — see
         * [resolveDefaultAlfredSessionId]. Absent when `widget.data` was pasted in by hand rather
         * than minted via "Create session". */
        const val EXTRA_ORCHESTRATION_SESSION_ID = "orchestration_session_id"

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
