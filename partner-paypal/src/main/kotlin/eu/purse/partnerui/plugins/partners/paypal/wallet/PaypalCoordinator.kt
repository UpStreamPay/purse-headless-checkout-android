// Web: packages/partner-ui/src/plugins/partners/paypal/wallet/index.ts (button, `createOrder`, `onApprove`)
// Same: /init creates the order, the buyer approves, /validate captures with the step_id.
// Differs: PayPal Android SDK browser switch instead of the JS popup; /init runs on the tap, not
//          at render (no orphan order); the approval URL echoed by /validate is never followed.
package eu.purse.partnerui.plugins.partners.paypal.wallet

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import eu.purse.core.environment.PurseEnvironment
import eu.purse.partnerui.PaymentCoordinator
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PluginEvent
import eu.purse.partnerui.PluginHost
import eu.purse.partnerui.redirect.RedirectAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PayPal wallet's interactive half: [submit] runs `/init`, hands the order to
 * [PaypalCheckoutActivity], and an approved order selects the method and captures. With a saved
 * token on the host there is no approval: [submit] captures straight away. Obtain one with
 * `rememberPaypalCoordinator`.
 */
class PaypalCoordinator internal constructor(
    private val activity: ComponentActivity,
    listener: PaymentElementListener,
) : PaymentCoordinator(activity.lifecycleScope, listener) {

    override val tag = "PaypalCoordinator"

    /** Set by `rememberPaypalCoordinator` with the Compose-managed [PaypalCheckoutContract] launcher. */
    internal var launchPaypal: ((PaypalCheckoutInput) -> Unit)? = null

    /** `true` once prepared — what [PaypalButton] shows itself on. */
    var isReady: Boolean by mutableStateOf(false)
        private set

    /** Held until approval: `setCollected` replaces the whole map, so the step id travels with the approval's output. */
    private var pendingStepId: Int? = null

    /** No network work: the `/init` that creates the PayPal order waits for the tap. */
    fun prepare(host: PluginHost) {
        attach(host)
        isReady = true
        listener.onReady(true)
    }

    fun submit() {
        if (submitting) return
        val host = host ?: return fail("call prepare() first")
        submitting = true
        val context = host.buildContext()
        if (context.token != null) {
            capture(host)
            return
        }
        activity.lifecycleScope.launch {
            try {
                // Read the client id BEFORE /init: without it the approval can never open, and
                // failing first leaves no orphan PayPal order behind.
                val config = PaypalMapper.parseSessionConfig(context.session, context.partner, context.method)
                val request = withContext(Dispatchers.IO) { PaypalPlugin(context.partner).buildRequest(context) }
                pendingStepId = request.stepId
                listener.onRequiresAction()
                launchPaypal?.invoke(
                    PaypalCheckoutInput(
                        orderId = request.orderId,
                        clientId = config.clientId,
                        sandbox = host.environment != PurseEnvironment.PRODUCTION,
                    ),
                )
            } catch (e: Exception) {
                submitting = false
                dispatch(PluginEvent.InitFailed(e.message))
                fail(e)
            }
        }
    }

    internal fun onPaypalResult(result: PaypalCheckoutResult) {
        val host = host ?: run {
            submitting = false
            return fail("not prepared")
        }
        when (result) {
            is PaypalCheckoutResult.Approved -> {
                host.setCollected(
                    mapOf(
                        PaypalPlugin.COLLECTED_STEP_ID to pendingStepId,
                        PaypalPlugin.COLLECTED_ORDER_ID to result.orderId,
                        PaypalPlugin.COLLECTED_PAYER_ID to result.payerId,
                    ),
                )
                // The approved order both selects the method and pays with it (web's `validationRequested`).
                selectAndCapture(host)
            }
            PaypalCheckoutResult.Canceled -> {
                submitting = false
                dispatch(PluginEvent.Cancelled)
                cancel()
            }
            is PaypalCheckoutResult.Failed -> {
                submitting = false
                dispatch(PluginEvent.ClientError(result.message))
                fail(result.message)
            }
        }
    }

    /**
     * `/validate` echoes the order's own approval URL in `redirection.url`, even on
     * `global_status = SUCCESS` (confirmed against a real answer). Following it would re-open PayPal
     * on an approved order and book a second step. A genuine chained 3-D Secure hop is never a
     * PayPal-hosted URL, so only those are followed.
     */
    override fun shouldFollow(action: RedirectAction): Boolean {
        val url = when (action) {
            is RedirectAction.Get -> action.url
            is RedirectAction.PostForm -> action.url
            is RedirectAction.PostHtml -> return true
        }
        return !PaypalMapper.isApprovalUrl(url)
    }
}
