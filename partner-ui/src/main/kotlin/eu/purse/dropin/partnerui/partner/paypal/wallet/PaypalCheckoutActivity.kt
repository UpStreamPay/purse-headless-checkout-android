package eu.purse.dropin.partnerui.partner.paypal.wallet

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.paypal.android.corepayments.CoreConfig
import com.paypal.android.corepayments.Environment
import com.paypal.android.paypalwebpayments.PayPalPresentAuthChallengeResult
import com.paypal.android.paypalwebpayments.PayPalWebCheckoutClient
import com.paypal.android.paypalwebpayments.PayPalWebCheckoutFinishStartResult
import com.paypal.android.paypalwebpayments.PayPalWebCheckoutFundingSource
import com.paypal.android.paypalwebpayments.PayPalWebCheckoutRequest

/**
 * Runs the PayPal web-checkout browser switch for one already-created order and hands the outcome
 * back as an Activity result. Launch it via [PaypalCheckoutContract] — never directly.
 *
 * `PayPalWebCheckoutClient.start()` opens the approval (Custom Tab / PayPal app). Because this
 * Activity is `singleTop` with the return deep-link intent-filter (declared in this module's
 * manifest, scheme `${applicationId}`), PayPal's redirect re-delivers here via [onNewIntent], and
 * `finishStart()` in [onResume] yields the result.
 *
 * The resume/cancel edges are handled below, but are only fully exercisable on a device against a
 * PayPal-enabled order whose `return_url` matches this app's scheme — see
 * docs/architecture/redirect-handling.md for that backend dependency.
 */
class PaypalCheckoutActivity : AppCompatActivity() {

    private lateinit var client: PayPalWebCheckoutClient
    private lateinit var orderId: String
    private var launched = false
    private var leftForBrowser = false
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        orderId = intent.getStringExtra(PaypalCheckoutContract.EXTRA_ORDER_ID).orEmpty()
        val clientId = intent.getStringExtra(PaypalCheckoutContract.EXTRA_CLIENT_ID).orEmpty()
        val sandbox = intent.getBooleanExtra(PaypalCheckoutContract.EXTRA_SANDBOX, true)

        if (orderId.isEmpty() || clientId.isEmpty()) {
            finishWith(PaypalCheckoutActivity.STATUS_FAILED, message = "missing PayPal orderId/clientId")
            return
        }

        val config = CoreConfig(clientId, if (sandbox) Environment.SANDBOX else Environment.LIVE)
        // urlScheme must equal the manifest intent-filter scheme (= ${applicationId} = packageName).
        client = PayPalWebCheckoutClient(this, config, packageName)

        launched = savedInstanceState?.getBoolean(STATE_LAUNCHED) ?: false
        leftForBrowser = savedInstanceState?.getBoolean(STATE_LEFT) ?: false

        if (savedInstanceState == null) startCheckout()
    }

    private fun startCheckout() {
        launched = true
        val request = PayPalWebCheckoutRequest(orderId, PayPalWebCheckoutFundingSource.PAYPAL)
        client.start(this, request) { authChallenge ->
            // Success here only means the browser opened; the outcome arrives via finishStart().
            if (authChallenge is PayPalPresentAuthChallengeResult.Failure) {
                finishWith(STATUS_FAILED, message = authChallenge.error.toString())
            }
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent) // required for singleTop so finishStart() sees the return deep link
    }

    override fun onResume() {
        super.onResume()
        if (!launched) return
        when (val result = client.finishStart(intent)) {
            is PayPalWebCheckoutFinishStartResult.Success ->
                finishWith(STATUS_APPROVED, orderId = result.orderId, payerId = result.payerId)
            is PayPalWebCheckoutFinishStartResult.Canceled ->
                finishWith(STATUS_CANCELED)
            is PayPalWebCheckoutFinishStartResult.Failure ->
                finishWith(STATUS_FAILED, message = result.error.toString())
            // No deep link this resume. If we've already been out to the browser, the shopper
            // dismissed the Custom Tab without approving → treat as a cancel.
            PayPalWebCheckoutFinishStartResult.NoResult, null ->
                if (leftForBrowser) finishWith(STATUS_CANCELED)
        }
    }

    override fun onPause() {
        super.onPause()
        if (launched) leftForBrowser = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LAUNCHED, launched)
        outState.putBoolean(STATE_LEFT, leftForBrowser)
    }

    private fun finishWith(
        status: String,
        orderId: String? = null,
        payerId: String? = null,
        message: String? = null,
    ) {
        if (delivered) return
        delivered = true
        val data = Intent()
            .putExtra(PaypalCheckoutContract.EXTRA_STATUS, status)
            .putExtra(PaypalCheckoutContract.EXTRA_ORDER_ID, orderId)
            .putExtra(PaypalCheckoutContract.EXTRA_PAYER_ID, payerId)
            .putExtra(PaypalCheckoutContract.EXTRA_MESSAGE, message)
        setResult(RESULT_OK, data)
        finish()
    }

    companion object {
        const val STATUS_APPROVED = "approved"
        const val STATUS_CANCELED = "canceled"
        const val STATUS_FAILED = "failed"
        private const val STATE_LAUNCHED = "launched"
        private const val STATE_LEFT = "left_for_browser"
    }
}
