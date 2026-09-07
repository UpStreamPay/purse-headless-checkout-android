// Web: none — web opens the JS SDK popup in-page
// Same: —
// Differs: self-contained Activity running `PayPalWebCheckoutClient`; deep link `${applicationId}.paypal` ships here.
package eu.purse.partnerui.plugins.partners.paypal.wallet

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
 * `PayPalWebCheckoutClient.start()` opens the approval (the PayPal app when installed, else a
 * Custom Tab). Because this Activity is `singleTop` and carries the return deep-link intent-filter
 * (declared in this module's manifest, scheme `${applicationId}.paypal`), PayPal's redirect
 * re-delivers here via [onNewIntent], and `finishStart()` in [onResume] yields the result.
 *
 * ### Why no backend return-URL configuration is needed
 *
 * The SDK does not use the `return_url` the backend set on the order. It builds the approval URL
 * itself as `…/checkoutnow?token=<orderId>&redirect_uri=<urlScheme>://x-callback-url/paypal-sdk/…`,
 * i.e. the return target comes from the [urlScheme] passed to [PayPalWebCheckoutClient] — which is
 * why that must equal the manifest's scheme, and why integrators do no manifest work.
 */
class PaypalCheckoutActivity : AppCompatActivity() {

    private lateinit var client: PayPalWebCheckoutClient
    private lateinit var orderId: String

    /** Set once [startCheckout] has run, so a resume before that never tries to finish anything. */
    private var launched = false

    /**
     * Set in [onPause] once launched — i.e. we handed off to the browser / PayPal app. Without it, a
     * `NoResult` on the very first resume after `start()` would look identical to one after the
     * shopper dismissed the Custom Tab.
     */
    private var leftForBrowser = false

    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        orderId = intent.getStringExtra(PaypalCheckoutContract.EXTRA_ORDER_ID).orEmpty()
        val clientId = intent.getStringExtra(PaypalCheckoutContract.EXTRA_CLIENT_ID).orEmpty()
        val sandbox = intent.getBooleanExtra(PaypalCheckoutContract.EXTRA_SANDBOX, true)

        if (orderId.isEmpty() || clientId.isEmpty()) {
            finishWith(STATUS_FAILED, message = "missing PayPal orderId/clientId")
            return
        }

        val config = CoreConfig(clientId, if (sandbox) Environment.SANDBOX else Environment.LIVE)
        // urlScheme must equal the manifest intent-filter scheme — see the class doc.
        client = PayPalWebCheckoutClient(this, config, "$packageName.paypal")

        if (savedInstanceState == null) {
            startCheckout()
        } else {
            launched = savedInstanceState.getBoolean(STATE_LAUNCHED)
            leftForBrowser = savedInstanceState.getBoolean(STATE_LEFT)
            // Restore the SDK's own pending auth-challenge session. Without this, a config change or
            // process death while the shopper is in the browser leaves a freshly-constructed client
            // with no session to match the return against — finishStart() would answer NoResult and,
            // with leftForBrowser already true, a *successful* approval would be reported as a
            // cancel. `restore` is the SDK's supported counterpart to `instanceState`.
            savedInstanceState.getString(STATE_SDK)?.let(client::restore)
        }
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

            // No deep link this resume (`null` because finishStart is a platform type). If we've
            // already been out to the browser, the shopper dismissed it without approving → cancel.
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
        if (::client.isInitialized) outState.putString(STATE_SDK, client.instanceState)
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
        private const val STATE_SDK = "paypal_sdk_state"
    }
}
