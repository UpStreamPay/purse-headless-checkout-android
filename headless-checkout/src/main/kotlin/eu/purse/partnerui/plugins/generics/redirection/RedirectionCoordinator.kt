// Web: packages/partner-ui/src/plugins/generics/redirection/index.ts
// Same: /init at render, /validate on submit, follow the redirect the answer carries.
// Differs: `partner_data.browser` describes the redirect WebView; a missing redirect is reported,
//          since the PSP page *is* the payment step here.
package eu.purse.partnerui.plugins.generics.redirection

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import eu.purse.core.payload.ValidationResult
import eu.purse.partnerui.PaymentCoordinator
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PaymentOutcome
import eu.purse.partnerui.PluginEvent
import eu.purse.partnerui.PluginHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The generic redirection template's interactive half: no form and no native SDK. [prepare] runs
 * `/init`, [submit] runs `/validate` and opens the PSP's page. Obtain one with
 * `rememberRedirectionCoordinator`.
 */
class RedirectionCoordinator internal constructor(
    private val activity: ComponentActivity,
    listener: PaymentElementListener,
) : PaymentCoordinator(activity.lifecycleScope, listener) {

    override val tag = "RedirectionCoordinator"

    private var prepared = false

    fun prepare(host: PluginHost) {
        attach(host)
        prepared = false
        activity.lifecycleScope.launch {
            try {
                val context = host.buildContext()
                val browser = RedirectionMapper.browserData(activity, context.options.locale)
                val request = withContext(Dispatchers.IO) {
                    RedirectionPlugin(context.partner, context.method).buildRequest(context, browser)
                }
                Log.d(tag, "prepared ${request.partnerWireValue}-${request.methodWireValue} step_id=${request.stepId}")
                host.setCollected(
                    mapOf(
                        RedirectionPlugin.COLLECTED_STEP_ID to request.stepId,
                        RedirectionPlugin.COLLECTED_BROWSER to browser,
                    ),
                )
                prepared = true
                listener.onReady(true)
            } catch (e: Exception) {
                dispatch(PluginEvent.InitFailed(e.message))
                fail(e)
            }
        }
    }

    fun submit() {
        if (submitting) return
        val host = host ?: return fail("call prepare() first")
        if (!prepared) return fail("call prepare() first")
        submitting = true
        capture(host)
    }

    /** For this template, no redirect means the PSP never handed over its page — say so. */
    override fun onSettled(result: ValidationResult) {
        Log.w(tag, "no redirect to follow — a redirection method's /validate normally carries one")
        listener.onOutcome(PaymentOutcome.Completed(result))
    }
}
