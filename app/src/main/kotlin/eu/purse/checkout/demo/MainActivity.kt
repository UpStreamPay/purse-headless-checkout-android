package eu.purse.checkout.demo

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import eu.purse.checkout.PurseHeadlessCheckout
import eu.purse.checkout.demo.databinding.ActivityMainBinding
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.checkout.payment.secondary.SecondaryToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal headless smoke test. "Create session (alfred)" mints a real Purse orchestration session
 * via the usp-widget dev server; "Initialize headless checkout" hands its `widget.data` to
 * [PurseHeadlessCheckout.init] and prints the session, payment methods, and split.
 *
 * When the session carries secondary (co-payment) methods, one collapsible PAN/CVC/amount form is
 * rendered per method — the headless secondaries API: `getSecondaryToken` (→ `/init`) then
 * `applySecondary(token, amount)`. The section stays hidden until such a session exists.
 *
 * Deliberately bare: raw [HttpURLConnection] on a background [Thread] for alfred, no ViewModel/DI,
 * no payment UI.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** The current headless checkout, once initialized. Drives the secondary forms below. */
    private var checkout: PurseHeadlessCheckout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets()

        binding.alfredBaseUrlText.text = getString(R.string.label_alfred_base_url, alfredBaseUrl())
        binding.createSessionButton.setOnClickListener { createSession() }
        binding.initCheckoutButton.setOnClickListener { initCheckout() }
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
        checkout?.let {
            binding.outputText.text = summarize(it)
            renderSecondaryForms(it)
        }
    }

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
