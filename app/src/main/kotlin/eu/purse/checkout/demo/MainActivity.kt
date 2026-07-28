package eu.purse.checkout.demo

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wallet.button.ButtonConstants
import com.google.android.gms.wallet.button.ButtonOptions
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import eu.purse.checkout.PurseHeadlessCheckout
import eu.purse.checkout.demo.databinding.ActivityMainBinding
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.core.payment.Method
import eu.purse.core.vault.VaultVendor
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayCoordinator
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayMapper
import eu.purse.partnerui.plugins.partners.klarna.KlarnaCoordinator
import eu.purse.partnerui.plugins.vaults.purse.creditcard.CardCoordinator
import eu.purse.partnerui.plugins.vaults.purse.creditcard.CardHost
import eu.purse.partnerui.plugins.vaults.purse.creditcard.rememberCardCoordinator
import eu.purse.securefields.VaultForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Minimal headless smoke test. "Create session (alfred)" mints a real Purse orchestration session
 * via the usp-widget dev server; "Initialize headless checkout" hands its `widget.data` to
 * [PurseHeadlessCheckout.init] and prints the session, payment methods, and split.
 *
 * When the session carries secondary (co-payment) methods, one collapsible PAN/CVC/amount form is
 * rendered per method — the headless secondaries API: `getSecondaryToken` (→ `/init`) then
 * `applySecondary(token, amount)`. The section stays hidden until such a session exists.
 *
 * Google Pay is an **xpay**: its own branded button is shown as soon as it's ready — never gated
 * behind a "select this row" step — and it's fully self-contained, mirroring `usp-widget`'s own
 * `googlepay` plugin (`onDataUpdate` appends the button once `isReadyToPay` resolves; its
 * `onButtonClicked` dispatches `requestValidate` itself once the sheet returns, with no separate
 * submit action at all) and the pre-headless `master` demo's own `renderXpay` split. See
 * [googlePayListener] / [onCreate].
 *
 * Klarna and the Purse-vaulted card don't have that one-tap shape, so they render as a collapsible
 * list instead (mirroring [renderSecondaryForms]'s shape): no row starts selected, and selecting
 * one is the single action that both commits it via `setPrimary` and mounts its actual payment
 * control — see [renderPrimaryMethods] / [selectPrimary]. Submission for *these* is **one global
 * button** (`submitPaymentButton`), enabled once [PurseHeadlessCheckout.isPaymentFulfilled] AND the
 * selected primary reports itself ready, dispatching to whichever coordinator's `submit()` matches
 * the selection — see [updateSubmitButtonState] / [onSubmitPayment].
 *
 * Deliberately bare: raw [HttpURLConnection] on a background [Thread] for alfred, no ViewModel/DI,
 * no payment UI.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** The current headless checkout, once initialized. Drives the secondary forms below. */
    private var checkout: PurseHeadlessCheckout? = null

    /** Google Pay coordinator — constructed in [onCreate] so its ActivityResult launchers register. */
    private lateinit var googlePay: GooglePayCoordinator

    /** Klarna coordinator — constructed in [onCreate] so its ActivityResult launcher registers. */
    private lateinit var klarna: KlarnaCoordinator

    // Klarna's rendering container must be the SAME instance KlarnaCoordinator was constructed
    // with (its own doc: constructed in onCreate, before STARTED) — so unlike Google Pay's button
    // or the card's ComposeView (both freely recreated per mount below), this one is built once
    // and re-parented into whichever row is currently selected.
    private lateinit var klarnaContainerView: FrameLayout

    /** One row per primary method this demo can drive — see [renderPrimaryMethods]. */
    private class PrimaryRow(
        val method: PrimaryMethod,
        val header: View,
        val radio: RadioButton,
        val caret: TextView,
        val body: FrameLayout,
    )
    private val primaryRows = mutableListOf<PrimaryRow>()

    /** Google Pay's own primary candidate, if the session offers it — resolved once at
     * [initCheckout] and prepared immediately (unlike Klarna/card, it's an xpay: never gated
     * behind row selection). `null` when absent. */
    private var googlePayItem: PrimaryMethod? = null

    /** `true` from the moment the shopper taps the Google Pay button (which commits it via
     * `setPrimary` immediately, fulfilling the split before the sheet has done anything) until a
     * different primary is selected — see [updateSubmitButtonState]'s doc for why this exists:
     * without it, "no row selected" is indistinguishable from "Google Pay just committed itself
     * as primary," and the global button would light up mid-sheet. */
    private var googlePaySelected: Boolean = false

    /** The currently selected primary from [primaryRows] (or `null` — secondaries alone, or
     * nothing yet; Google Pay is tracked separately, see [googlePaySelected]) — mirrors what was
     * last passed to `checkout.setPrimary` *from the row list*; kept here only so
     * [onSubmitPayment] knows which coordinator to dispatch to. */
    private var selectedPrimary: PrimaryMethod? = null

    /** Whether the selected primary is actually ready to submit (Klarna's widget loaded, the card
     * form validates) — `false` the instant a new row is selected, until that method's own
     * callback says otherwise. Not meaningful for Google Pay (excluded from the global button
     * entirely) or when no primary is selected (secondaries-only doesn't need this gate). */
    private var primaryReady: Boolean = false

    /** The card coordinator's live `submit` function, reported up once its composable exists —
     * see [mountCard] / `CardPaymentForm`'s `onSubmitReady`. `null` whenever card isn't selected. */
    private var cardSubmit: (() -> Unit)? = null

    /** Re-entrancy guard for [submitPaymentButton] — same rationale as the individual
     * coordinators' own `submitting` flags, just hoisted here now that one button drives all of
     * them. Cleared on every terminal outcome (result/canceled/error), not on `onRequiresAction`
     * (a redirect is still in flight). */
    private var submitting = false

    /** Restarted every [initCheckout] — keeps [submitPaymentButton]'s enabled state in sync with
     * the split (a secondary applied/removed changes `isPaymentFulfilled` independently of any
     * primary selection). */
    private var fulfilledObserverJob: Job? = null

    // The orchestration session's own top-level `id` (the v2 ClientSession id `POST
    // /orchestration_session` itself returns — distinct from `widget.data`'s `payment_session.id`)
    // — kept around only so the alfred debug tool below has a sensible default to fall back to,
    // mirroring usp-widget's demo confirmation page. See [resolveDefaultAlfredSessionId].
    private var lastOrchestrationSessionId: String? = null

    private val googlePayListener = object : GooglePayCoordinator.Listener {
        override fun onReady(ready: Boolean) {
            binding.googlePayButton.visibility = if (ready) View.VISIBLE else View.GONE
        }
        override fun onResult(summary: String) = appendOutput("Google Pay → $summary")
        override fun onRequiresAction() = appendOutput("Google Pay → 3-D Secure required…")
        override fun onPaymentId(paymentId: String) {
            appendOutput("Google Pay → payment_id=$paymentId")
            binding.capturePaymentIdInput.setText(paymentId)
        }
        override fun onCanceled() {
            binding.googlePayButton.isEnabled = true
            Toast.makeText(this@MainActivity, R.string.gpay_canceled, Toast.LENGTH_LONG).show()
        }
        override fun onError(message: String?) {
            binding.googlePayButton.isEnabled = true
            Toast.makeText(this@MainActivity, getString(R.string.gpay_error, message), Toast.LENGTH_LONG).show()
        }
    }

    private val klarnaListener = object : KlarnaCoordinator.Listener {
        override fun onReady(ready: Boolean) {
            primaryReady = ready
            updateSubmitButtonState()
        }
        override fun onResult(summary: String) {
            appendOutput("Klarna → $summary")
            submitting = false
            updateSubmitButtonState()
        }
        override fun onRequiresAction() = appendOutput("Klarna → redirect required…")
        override fun onCanceled() {
            submitting = false
            updateSubmitButtonState()
            Toast.makeText(this@MainActivity, R.string.klarna_canceled, Toast.LENGTH_LONG).show()
        }
        override fun onError(message: String?) {
            submitting = false
            updateSubmitButtonState()
            // A toast alone disappears before you can read a full KlarnaPaymentsSDKError detail
            // dump (name/action/invalidFields/sessionId/fatal) — keep it in the scrolling output too.
            appendOutput("Klarna → error: $message")
            Toast.makeText(this@MainActivity, getString(R.string.klarna_error, message), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets()

        // No GooglePayModule.register()/KlarnaModule.register() needed — PluginRegistry
        // auto-registers every built-in plugin; the session alone gates what's actually offered.
        googlePay = GooglePayCoordinator(this, googlePayListener)

        // The branded Google Pay button — chrome only (no tokenization), so a generic
        // allowedPaymentMethods is enough here; the real one (with the gateway's tokenization spec)
        // is built per-session by GooglePayCoordinator.prepare() in initCheckout(). It's an xpay
        // (see class doc): always the trigger itself, never gated behind a "select" step.
        binding.googlePayButton.initialize(
            ButtonOptions.newBuilder()
                .setButtonType(ButtonConstants.ButtonType.PAY)
                .setButtonTheme(ButtonConstants.ButtonTheme.DARK)
                .setAllowedPaymentMethods(GooglePayMapper.buttonAllowedPaymentMethodsJson().toString())
                .build(),
        )
        binding.googlePayButton.setOnClickListener {
            val co = checkout
            // Deselect any open Klarna/card row first — Google Pay is taking over as the primary,
            // and leaving a row visually "selected" would misrepresent what's actually about to
            // be submitted.
            if (co != null && selectedPrimary != null) selectPrimary(co, null)
            // Commit Google Pay as the primary only now that the shopper actually chose it.
            googlePaySelected = true
            co?.setPrimary(googlePayItem)
            updateSubmitButtonState() // setPrimary alone can fulfill the split — see its doc.
            // Belt-and-suspenders alongside GooglePayCoordinator's own re-entrancy guard: a double
            // tap here would otherwise fire two payment sheets and POST /validate twice for the
            // same session — the second call comes back 409 (idempotency conflict).
            binding.googlePayButton.isEnabled = false
            googlePay.launchSheet()
        }

        // Built once here (not per-mount, unlike the card's ComposeView below)
        // because KlarnaCoordinator captures this exact container reference at construction time —
        // see its own "construct before STARTED" doc. selectPrimary() re-parents it into whichever
        // row is currently selected.
        klarnaContainerView = FrameLayout(this)
        klarna = KlarnaCoordinator(this, klarnaContainerView, klarnaListener)

        binding.alfredBaseUrlText.text = getString(R.string.label_alfred_base_url, alfredBaseUrl())
        binding.createSessionButton.setOnClickListener { createSession() }
        binding.initCheckoutButton.setOnClickListener { initCheckout() }
        binding.submitPaymentButton.setOnClickListener { onSubmitPayment() }
        binding.getSessionViaAlfredButton.setOnClickListener { getSessionViaAlfred() }
        binding.captureButton.setOnClickListener { captureViaAlfred() }
    }

    private fun appendOutput(line: String) {
        binding.outputText.append("\n$line")
    }

    /** targetSdk 35+ draws edge-to-edge; pad the content by the system-bar insets on top of 16dp. */
    private fun applyContentInsets() {
        val base = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentRoot) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            windowInsets
        }
    }

    private fun createSession() {
        val baseUrl = alfredBaseUrl()
        binding.createSessionButton.isEnabled = false
        Thread {
            try {
                // GET /order returns { "order": <legacyOrder> }; /orchestration_session runs
                // fromLegacyOrder on its request body, so we post the unwrapped legacy order.
                val legacyOrder = JSONObject(httpGet("$baseUrl/order")).getJSONObject("order")
                val session = JSONObject(httpPost("$baseUrl/orchestration_session", legacyOrder.toString()))
                val widgetData = session.getJSONObject("widget").getString("data")
                lastOrchestrationSessionId = session.optString("id").takeIf { it.isNotBlank() }
                runOnUiThread {
                    binding.widgetDataInput.setText(widgetData)
                    binding.widgetDataLayout.error = null
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_create_session_failed, e.message), Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { binding.createSessionButton.isEnabled = true }
            }
        }.start()
    }

    private fun initCheckout() {
        val widgetData = binding.widgetDataInput.text?.toString()?.trim()
        if (widgetData.isNullOrEmpty()) {
            binding.widgetDataLayout.error = getString(R.string.error_required)
            return
        }
        binding.widgetDataLayout.error = null
        checkout = try {
            PurseHeadlessCheckout.init(widgetData)
        } catch (e: Exception) {
            binding.outputText.text = getString(R.string.session_error, e.message)
            renderSecondaryForms(null)
            null
        }
        binding.alfredSessionIdInput.setText(resolveDefaultAlfredSessionId(widgetData))
        binding.alfredSessionResult.visibility = View.GONE
        binding.capturePaymentIdInput.setText("")
        binding.captureAmountInput.setText(checkout?.session?.amount?.toPlainString().orEmpty())
        binding.captureResult.visibility = View.GONE
        fulfilledObserverJob?.cancel()
        selectedPrimary = null
        primaryReady = false
        googlePaySelected = false
        cardSubmit = null
        submitting = false
        binding.googlePayButton.visibility = View.GONE
        binding.googlePayButton.isEnabled = true
        googlePayItem = null
        binding.submitPaymentButton.visibility = if (checkout != null) View.VISIBLE else View.GONE
        checkout?.let {
            binding.outputText.text = summarize(it)
            renderSecondaryForms(it)
            renderPrimaryMethods(it)
            updateSubmitButtonState()
            // Re-checked on every emission — a secondary applied/removed changes isPaymentFulfilled
            // independently of whatever primary (if any) is currently selected.
            fulfilledObserverJob = lifecycleScope.launch { it.isPaymentFulfilled.collect { updateSubmitButtonState() } }
            // Google Pay is an xpay — prepared immediately regardless of anything else the
            // shopper does, unlike Klarna/card which wait for their row to be selected.
            googlePayItem = it.primaryMethods.firstOrNull { p -> p.method == Method.GooglePay }
            googlePayItem?.let { gpay -> googlePay.prepare(it.googlePayHost(gpay)) }
        } ?: renderPrimaryMethods(null)
    }

    /**
     * [submitPaymentButton]'s enabled state: the split must be fulfilled, and — unless no primary
     * is needed at all (secondaries alone cover it) — the selected primary must itself report
     * ready (Klarna's widget loaded, the card form valid). [googlePaySelected] is checked
     * explicitly and disables the button outright: Google Pay's own `setPrimary` call can fulfill
     * the split the instant the shopper taps its button, well before the sheet has done anything —
     * without this check that's indistinguishable from "no primary needed, secondaries alone cover
     * it" (`selectedPrimary == null`), and the global button would light up mid-sheet. Google Pay's
     * own submission is entirely self-contained (see class doc), so this button simply never
     * applies while it's the one in flight.
     */
    private fun updateSubmitButtonState() {
        val co = checkout
        val ready = co != null && co.isPaymentFulfilled.value && (selectedPrimary == null || primaryReady)
        binding.submitPaymentButton.isEnabled = ready && !googlePaySelected && !submitting
    }

    /**
     * Dispatches to whichever coordinator matches [selectedPrimary] — Klarna and the card share
     * this one button. `null` (secondaries alone) calls `submitPayment()` directly since no
     * plugin/coordinator is involved.
     */
    private fun onSubmitPayment() {
        val co = checkout ?: return
        when (selectedPrimary?.method) {
            Method.Installments -> {
                submitting = true
                updateSubmitButtonState()
                klarna.submit()
            }
            Method.CreditCard -> {
                submitting = true
                updateSubmitButtonState()
                cardSubmit?.invoke()
            }
            null -> {
                submitting = true
                updateSubmitButtonState()
                lifecycleScope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { co.submitPayment() }
                        appendOutput("Submit → global_status=${result.globalStatus}")
                    } catch (e: Exception) {
                        appendOutput("Submit → error: ${e.message}")
                        Toast.makeText(this@MainActivity, getString(R.string.submit_payment_error, e.message), Toast.LENGTH_LONG).show()
                    } finally {
                        submitting = false
                        updateSubmitButtonState()
                    }
                }
            }
            else -> Unit // Google Pay never becomes selectedPrimary — see updateSubmitButtonState's doc.
        }
    }

    /**
     * Rebuild the primary-method list: one collapsible row per primary this demo drives *besides*
     * Google Pay (Klarna, a Purse-vaulted card — Google Pay is an xpay, handled entirely by its own
     * branded button, see class doc), mirroring [renderSecondaryForms]'s shape but mutually
     * exclusive — selecting a row commits it via [PurseHeadlessCheckout.setPrimary] and is the
     * only thing that mounts its actual payment control (see [selectPrimary]). No row starts
     * selected, so nothing's `/init`'d or rendered until the shopper picks one.
     */
    private fun renderPrimaryMethods(co: PurseHeadlessCheckout?) {
        val container = binding.primaryContainer
        container.removeAllViews()
        primaryRows.clear()

        // Only methods this demo actually has a coordinator for here — matches the set
        // klarna/card coordinators below know how to mount. Google Pay is excluded on purpose.
        val methods = co?.primaryMethods?.filter {
            it.method == Method.Installments ||
                (it.method == Method.CreditCard && it.configuration.vault?.vendor == VaultVendor.PURSE)
        }.orEmpty()
        binding.primarySection.visibility = if (methods.isEmpty()) View.GONE else View.VISIBLE
        if (co == null) return

        methods.forEach { method ->
            val item = layoutInflater.inflate(R.layout.item_primary_method, container, false)
            val header = item.findViewById<View>(R.id.header)
            val radio = item.findViewById<RadioButton>(R.id.primaryRadio)
            val caret = item.findViewById<TextView>(R.id.expandCaret)
            val body = item.findViewById<FrameLayout>(R.id.primaryBody)
            item.findViewById<TextView>(R.id.methodTitle).text = primaryMethodTitle(method)

            val row = PrimaryRow(method, header, radio, caret, body)
            primaryRows += row
            header.setOnClickListener {
                selectPrimary(co, if (row.body.isVisible) null else row)
            }
            container.addView(item)
        }
    }

    private fun primaryMethodTitle(method: PrimaryMethod): String = when (method.method) {
        Method.Installments -> "Klarna"
        Method.CreditCard -> "Card"
        else -> method.id
    }

    /**
     * Select [row] (or deselect, with `null`, collapsing everything back down) — mutually
     * exclusive across [primaryRows]. This is the one place `setPrimary` is called for a primary:
     * unlike the old always-shown layout, choosing a method and committing it as the primary are
     * now the same action, matching how the gift-card list treats "check balance" as a distinct,
     * deliberate step rather than something that happens just because a form exists on screen.
     * Only the selected row's actual payment control gets mounted (see `mount*` below) — an
     * unselected Klarna/card row does no `/init` or SecureFields work at all.
     */
    private fun selectPrimary(co: PurseHeadlessCheckout, row: PrimaryRow?) {
        primaryRows.forEach { r ->
            val open = r === row
            r.body.isVisible = open
            r.radio.isChecked = open
            r.caret.text = if (open) "▾" else "▸"
            if (!open) r.body.removeAllViews()
        }
        selectedPrimary = row?.method
        primaryReady = false
        googlePaySelected = false
        co.setPrimary(row?.method)
        updateSubmitButtonState()
        if (row == null) return

        row.body.removeAllViews()
        when (row.method.method) {
            Method.Installments -> mountKlarna(row.body, row.method, co)
            Method.CreditCard -> mountCard(row.body, row.method, co)
            else -> Unit
        }
    }

    /** Re-parents the shared Klarna container into [body] and prepares the widget (`/init` +
     * load) — deferred until the row is selected. No pay button of its own: the global
     * [submitPaymentButton] drives submission once [primaryReady] flips (see [klarnaListener]). */
    private fun mountKlarna(body: FrameLayout, method: PrimaryMethod, co: PurseHeadlessCheckout) {
        (klarnaContainerView.parent as? ViewGroup)?.removeView(klarnaContainerView)
        body.addView(klarnaContainerView)
        klarna.prepare(co.klarnaHost(method, klarnaReturnUrl()))
    }

    /** Sets a fresh card ComposeView's content into [body] — Compose builds/renders SecureFields
     * itself on first composition, so "mounted" and "prepared" are the same step here. No pay
     * button inside the form itself: [submitPaymentButton] drives submission, gated on
     * [primaryReady] which the form reports up via `onFormValidChanged`. */
    private fun mountCard(body: FrameLayout, method: PrimaryMethod, co: PurseHeadlessCheckout) {
        val composeView = ComposeView(this)
        body.addView(composeView)
        composeView.setContent {
            MaterialTheme {
                CardPaymentForm(
                    host = co.cardHost(method),
                    onSubmitReady = { submit -> cardSubmit = submit },
                    onFormValidChanged = { valid ->
                        primaryReady = valid
                        updateSubmitButtonState()
                    },
                    onResult = { summary ->
                        appendOutput("Card → $summary")
                        submitting = false
                        updateSubmitButtonState()
                    },
                    onRequiresAction = { appendOutput("Card → 3-D Secure required…") },
                    onCanceled = {
                        submitting = false
                        updateSubmitButtonState()
                    },
                    onError = { message ->
                        appendOutput("Card → error: $message")
                        submitting = false
                        updateSubmitButtonState()
                        Toast.makeText(this@MainActivity, getString(R.string.card_error, message), Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
    }

    /** `KlarnaPaymentView`'s own returnURL — matches the deep link declared on this Activity's manifest entry. */
    private fun klarnaReturnUrl(): String = "$packageName.klarna://return"

    /**
     * Mirrors `resolveDefaultSessionId` in `usp-widget/sites/demo/src/routes/widgets/confirmation/+page.svelte`:
     * prefer a `legacy_payment_session_id` field on the decoded `widget.data` blob (a field
     * [PurseHeadlessCheckout]'s own session decode doesn't model — it isn't in the SDK's published
     * types either) and fall back to the *orchestration* session's own `id`
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
        return legacyId ?: lastOrchestrationSessionId.orEmpty()
    }

    /**
     * The alfred call the reference confirmation page's "Get Transactions" button makes:
     * `GET {alfredBaseUrl}/payment_session/{id}`, which alfred forwards to the real Payment API's
     * `GET /{entity}/sessions/{session}` using its own server-side credentials — see
     * `usp-widget/packages/alfred/routes/payment.mjs` + `services/fetcher.mjs`. The session id is
     * whatever is currently typed into the field (editable, not necessarily this checkout's own
     * session). Result renders in its own view directly below the row, not in [appendOutput].
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
                val response = httpGet("${alfredBaseUrl()}/payment_session/$sessionId")
                val pretty = JSONObject(response).toString(2)
                runOnUiThread { showAlfredResult("${getString(R.string.alfred_session_result, sessionId)}\n$pretty") }
            } catch (e: Exception) {
                runOnUiThread { showAlfredResult(getString(R.string.alfred_session_error, e.message)) }
            }
        }.start()
    }

    private fun showAlfredResult(text: String) {
        binding.alfredSessionResult.text = text
        binding.alfredSessionResult.visibility = View.VISIBLE
    }

    /**
     * `POST {alfredBaseUrl}/payments/{paymentId}/capture` — alfred's own server-side credentials
     * capture the payment (orchestration API's `POST /v2/payments/{id}/captures`), for a
     * `capture_mode: MANUAL` payment or any client whose own client-facing `api_key` isn't
     * authorized to call that endpoint directly (see `usp-widget/packages/alfred/routes/orchestration.mjs`
     * + `services/fetcher.mjs`). [amount] is major currency units (e.g. "4.80"), converted to the
     * API's minor-unit integer (480) — prefilled from the current checkout's own amount by
     * [initCheckout], but editable for a partial capture.
     */
    private fun captureViaAlfred() {
        val paymentId = binding.capturePaymentIdInput.text?.toString()?.trim().orEmpty()
        if (paymentId.isEmpty()) {
            showCaptureResult(getString(R.string.capture_payment_id_required))
            return
        }
        val amountText = binding.captureAmountInput.text?.toString()?.trim().orEmpty()
        val amountMinorUnits = amountText.toBigDecimalOrNull()
            ?.movePointRight(2)?.setScale(0, RoundingMode.HALF_UP)?.longValueExact()
        if (amountMinorUnits == null) {
            showCaptureResult(getString(R.string.capture_amount_invalid))
            return
        }
        showCaptureResult(getString(R.string.capture_capturing))
        Thread {
            try {
                val body = JSONObject().put("amount", amountMinorUnits)
                val response = httpPost("${alfredBaseUrl()}/payments/$paymentId/capture", body.toString())
                val pretty = JSONObject(response).toString(2)
                runOnUiThread { showCaptureResult("${getString(R.string.capture_result, paymentId)}\n$pretty") }
            } catch (e: Exception) {
                runOnUiThread { showCaptureResult(getString(R.string.capture_error, e.message)) }
            }
        }.start()
    }

    private fun showCaptureResult(text: String) {
        binding.captureResult.text = text
        binding.captureResult.visibility = View.VISIBLE
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    /**
     * Rebuild the secondary section: one collapsible form per secondary method. The whole section
     * stays hidden unless the session offers at least one secondary method.
     */
    private fun renderSecondaryForms(co: PurseHeadlessCheckout?) {
        val container = binding.secondaryContainer
        container.removeAllViews()

        // Only methods with a registered SecondaryConfig are actionable (e.g. ANCV's polling flow
        // isn't implemented yet), so skip the rest rather than render a form that can't tokenize.
        val methods = co?.let { c -> c.secondaryMethods.filter { c.secondaryConfig(it) != null } }.orEmpty()
        binding.secondarySection.visibility = if (methods.isEmpty()) View.GONE else View.VISIBLE
        if (co == null) return

        methods.forEach { method ->
            val item = layoutInflater.inflate(R.layout.item_secondary_method, container, false)
            val body = item.findViewById<View>(R.id.formBody)
            val caret = item.findViewById<TextView>(R.id.expandCaret)
            val cvvLayout = item.findViewById<TextInputLayout>(R.id.cvvLayout)

            item.findViewById<TextView>(R.id.methodTitle).text = method.id
            val requiresCvv = co.secondaryConfig(method)?.requiresCvv("") ?: false
            cvvLayout.visibility = if (requiresCvv) View.VISIBLE else View.GONE

            item.findViewById<View>(R.id.header).setOnClickListener {
                val expanded = body.visibility == View.VISIBLE
                body.visibility = if (expanded) View.GONE else View.VISIBLE
                caret.text = if (expanded) "▸" else "▾"
            }
            item.findViewById<MaterialButton>(R.id.checkBalanceButton).setOnClickListener {
                checkBalance(co, method, item, requiresCvv)
            }
            item.findViewById<MaterialButton>(R.id.addButton).setOnClickListener {
                applyToken(co, item)
            }
            container.addView(item)
        }
    }

    /**
     * Tokenize (`/init`) the card entered in [item]'s form and show its balance — WITHOUT touching
     * the split. The resulting [SecondaryToken] is cached on the item so [applyToken] can apply it
     * later; the amount + "Add to payment" controls are revealed only once a balance is known.
     */
    private fun checkBalance(co: PurseHeadlessCheckout, method: SecondaryMethod, item: View, requiresCvv: Boolean) {
        val panLayout = item.findViewById<TextInputLayout>(R.id.panLayout)
        val panInput = item.findViewById<TextInputEditText>(R.id.panInput)
        val cvvInput = item.findViewById<TextInputEditText>(R.id.cvvInput)
        val checkButton = item.findViewById<MaterialButton>(R.id.checkBalanceButton)
        val balanceText = item.findViewById<TextView>(R.id.balanceText)

        val pan = panInput.text?.toString()?.trim().orEmpty()
        if (pan.isEmpty()) {
            panLayout.error = getString(R.string.error_required)
            return
        }
        panLayout.error = null
        val cvv = if (requiresCvv) cvvInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } else null

        checkButton.isEnabled = false
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { co.getSecondaryToken(method, pan, cvv) } }
                .onSuccess { token ->
                    item.tag = token // cache for applyToken; NOT applied to the split
                    val label = token.name.ifBlank { "•••• ${token.pan.takeLast(4)}" }
                    balanceText.text = if (token.totalBalance.compareTo(token.balance) != 0) {
                        getString(R.string.giftcard_balance_partial, label, token.balance.toString(), token.totalBalance.toString())
                    } else {
                        getString(R.string.giftcard_balance, label, token.balance.toString())
                    }
                    balanceText.visibility = View.VISIBLE
                    item.findViewById<View>(R.id.amountLayout).visibility = View.VISIBLE
                    item.findViewById<View>(R.id.addButton).visibility = View.VISIBLE
                }
                .onFailure { e ->
                    item.tag = null
                    balanceText.visibility = View.GONE
                    item.findViewById<View>(R.id.amountLayout).visibility = View.GONE
                    item.findViewById<View>(R.id.addButton).visibility = View.GONE
                    Toast.makeText(this@MainActivity, getString(R.string.giftcard_error, e.message), Toast.LENGTH_LONG).show()
                }
            checkButton.isEnabled = true
        }
    }

    /**
     * Apply the balance-checked token to the split. A gift card may contribute any amount up to its
     * balance: a blank amount takes as much as the order needs, a smaller value contributes part of
     * it (web's `'none'` take policy). Non-splittable tokens ignore the amount.
     */
    private fun applyToken(co: PurseHeadlessCheckout, item: View) {
        val token = item.tag as? SecondaryToken ?: run {
            Toast.makeText(this, R.string.giftcard_check_first, Toast.LENGTH_LONG).show()
            return
        }
        val amountLayout = item.findViewById<TextInputLayout>(R.id.amountLayout)
        val amountInput = item.findViewById<TextInputEditText>(R.id.amountInput)

        val amountText = amountInput.text?.toString()?.trim().orEmpty()
        val requestedAmount: BigDecimal? = if (amountText.isEmpty()) {
            null
        } else {
            amountText.toBigDecimalOrNull() ?: run {
                amountLayout.error = getString(R.string.giftcard_amount_invalid)
                return
            }
        }
        amountLayout.error = null

        co.applySecondary(token, requestedAmount)
        val applied = co.amountSplit.value.firstOrNull { it.source.id == token.id }?.amount ?: BigDecimal.ZERO
        Toast.makeText(
            this,
            getString(R.string.giftcard_added, applied.toString(), token.balance.toString()),
            Toast.LENGTH_LONG,
        ).show()
        binding.outputText.text = summarize(co)
    }

    /** Render the SDK's current reactive state — the smoke test's proof it decoded and split. */
    private fun summarize(co: PurseHeadlessCheckout): String = buildString {
        appendLine("session id:        ${co.currentSessionId}")
        appendLine("currency:          ${co.currency}")
        appendLine("amount:            ${co.session.amount}")
        appendLine("informationReq:    ${co.isInformationRequest}")
        appendLine("state:             ${co.sessionState.value}")
        appendLine("remainingToPay:    ${co.remainingAmountToPay.value}")
        appendLine("fulfilled:         ${co.isPaymentFulfilled.value}")
        appendLine("vault tenant:      ${co.session.vaultsConfiguration?.purse?.tenantId ?: "—"}")
        appendLine()
        appendLine("payment methods (${co.paymentMethods.value.size}):")
        co.paymentMethods.value.forEach { appendLine("  ${describe(it)}") }
        appendLine()
        appendLine("applied secondaries (${co.appliedSecondaries.size}):")
        co.appliedSecondaries.forEach { token ->
            val applied = co.amountSplit.value.firstOrNull { it.source.id == token.id }?.amount ?: BigDecimal.ZERO
            appendLine("  ${token.id}  applied $applied / balance ${token.balance}")
        }
        appendLine()
        appendLine("split (${co.amountSplit.value.size}):")
        co.amountSplit.value.forEach { appendLine("  ${it.source.id} → ${it.amount}") }
    }

    private fun describe(item: PaymentItem): String {
        val kind = if (item.isSecondary) "secondary" else "primary"
        val disabled = item.disabled?.let { " [disabled: ${it.code}]" } ?: ""
        val integration = (item as? PrimaryMethod)?.integrationType?.let { " integration=${it.wireValue}" } ?: ""
        val brands = (item as? PrimaryMethod)?.supportedBrands
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(",") { it.wireValue }
            ?.let { " brands=$it" } ?: ""
        return "${item.id} ($kind)$integration$brands$disabled"
    }

    /**
     * Alfred base URL from `ALFRED_BASE_URL` (env / .env, see app/build.gradle.kts). Falls back to
     * the emulator default so the demo runs out of the box.
     */
    private fun alfredBaseUrl(): String =
        BuildConfig.ALFRED_BASE_URL.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: "http://10.0.2.2:9001"

    private fun httpGet(urlString: String): String = request(urlString, "GET", null)
    private fun httpPost(urlString: String, body: String): String = request(urlString, "POST", body)

    private fun request(urlString: String, method: String, body: String?): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return try {
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                if (body != null) connection.outputStream.use { it.write(body.toByteArray()) } else connection.outputStream.close()
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code from $urlString: $responseBody")
            responseBody
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The card (Purse vault / SecureFields) primary — entirely Compose, unlike Google Pay/Klarna above.
 * `rememberCardCoordinator` renders the SecureFields form via its own `VaultForm` composable; this
 * just adds a status line and reports two things up to [MainActivity] rather than owning its own
 * "Pay" button: [onSubmitReady] (the coordinator's live `submit` function, once it exists) and
 * [onFormValidChanged] (whether the form currently validates) — both drive the single global
 * `submitPaymentButton`, the same way Google Pay's own `onReady` drives its own branded button.
 * Top-level (not a private method on [MainActivity]) since it needs nothing from the Activity
 * beyond the callbacks passed in. `setPrimary` already happened when this row was selected (see
 * `MainActivity.selectPrimary`).
 */
@Composable
private fun CardPaymentForm(
    host: CardHost,
    onSubmitReady: (submit: () -> Unit) -> Unit,
    onFormValidChanged: (Boolean) -> Unit,
    onResult: (String) -> Unit,
    onRequiresAction: () -> Unit,
    onCanceled: () -> Unit,
    onError: (String?) -> Unit,
) {
    var status by remember { mutableStateOf("") }
    val listener = remember {
        object : CardCoordinator.Listener {
            override fun onResult(summary: String) {
                status = summary
                onResult(summary)
            }
            override fun onRequiresAction() {
                status = "3-D Secure required…"
                onRequiresAction()
            }
            override fun onCanceled() = onCanceled()
            override fun onError(message: String?) {
                status = "error: $message"
                onError(message)
            }
        }
    }
    val coordinator = rememberCardCoordinator(host, listener)

    LaunchedEffect(coordinator) { onSubmitReady { coordinator.submit() } }
    LaunchedEffect(coordinator.formValid) { onFormValidChanged(coordinator.formValid) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Card", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        VaultForm(coordinator.sdk, modifier = Modifier.fillMaxWidth().height(200.dp))
        if (status.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(status)
        }
    }
}
