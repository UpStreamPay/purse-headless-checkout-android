// Web: packages/partner-ui/src/plugins/generics/redirection + `getBrowserData`
// Same: /init body, `partner_data.browser` field for field.
// Differs: the browser block describes the redirect WebView.
package eu.purse.partnerui.plugins.generics.redirection

import android.content.Context
import android.content.res.Resources
import android.util.DisplayMetrics
import android.webkit.WebSettings
import eu.purse.core.api.PaymentApiException
import java.math.BigDecimal
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * Builds the `/init` and `/validate` bodies for a **redirection** method — ports web's generic
 * `plugins/generics/redirection` plugin (`usp-widget/packages/partner-ui/src/plugins/generics/redirection/index.ts`).
 *
 * Both bodies are deliberately near-empty: a redirection method carries no shopper-entered data at
 * all, so the only things on the wire are the envelope (`partner`/`method`/`amount`), the `step_id`
 * `/init` handed back, and the browser/device block ([browserData]) the PSP's 3-D Secure step wants.
 * That emptiness *is* the template — it's why one plugin can serve every partner/method whose
 * `configurations.template` says `redirection`.
 *
 * Pure JSON — no Android view types. [browserData] is the one exception and takes an explicit
 * [Context]; [RedirectionCoordinator] calls it and hands the result in through
 * `PartnerUiContext.collected`, so [RedirectionPlugin] itself stays unit-testable.
 */
object RedirectionMapper {

    /**
     * `/init` body — mirrors web's `onSessionSet`, which POSTs exactly this: empty `payment_data` and
     * `payment_preferences`, and only [browserData] under `partner_data`.
     */
    fun buildInitPayload(
        amount: BigDecimal,
        partnerWireValue: String,
        methodWireValue: String,
        browser: JSONObject?,
    ): JSONObject =
        JSONObject()
            .put("partner", partnerWireValue)
            .put("method", methodWireValue)
            .put("amount", amount)
            .put("partner_data", partnerDataFor(browser))
            .put("payment_data", JSONObject())
            .put("payment_preferences", JSONObject())

    /** `partner_data` for both `/init` and `/validate` — `{browser: …}`, or empty when uncollected. */
    fun partnerDataFor(browser: JSONObject?): JSONObject =
        JSONObject().also { if (browser != null) it.put("browser", browser) }

    /**
     * The top-level `step_id` `/init` returns for this item (web's `initData.step_id`, stashed as
     * `context.dmz.initialStepId`) — threaded back into the `/validate` payload by [RedirectionPlugin].
     */
    fun extractStepId(initData: JSONObject): Int? =
        if (initData.has("step_id") && !initData.isNull("step_id")) initData.optInt("step_id") else null

    /**
     * Fails loudly on an unsuccessful `/init`, matching the other plugins' mappers. Unlike Google
     * Pay's or Klarna's, a redirection `/init` has **nothing to parse** — no gateway config, no
     * client token — so there is no `plugin_result.logs` requirement here: a `SUCCESS` with an empty
     * body is a perfectly good init for this template.
     *
     * @throws PaymentApiException on an unsuccessful init.
     */
    fun ensureInitSucceeded(initData: JSONObject) {
        val status = initData.optJSONObject("status")
        val state = status?.optString("state").orEmpty()
        if (state.isNotEmpty() && state != "SUCCESS") {
            val description = status?.optString("description").orEmpty()
            throw PaymentApiException("redirection init not successful (state=$state) $description".trim())
        }
    }

    /**
     * The `partner_data.browser` block, ported field-for-field from web's `getBrowserData`
     * (`plugins/utils/index.ts`) — it feeds the PSP's 3-D Secure device data, so the values must
     * describe the surface that will actually render the challenge: [RedirectionCheckoutActivity]'s
     * WebView. The card plugin sends the same block, since its 3-D Secure challenge opens there too.
     *
     * | Field | Web | Here |
     * |---|---|---|
     * | `referrer` | `window.location.href` | `android-app://{packageName}` — there is no page URL |
     * | `user_agent` | `navigator.userAgent` | [WebSettings.getDefaultUserAgent] — the UA that WebView really sends |
     * | `accept_header` | `'text/html'` | same constant |
     * | `color_depth` | `screen.colorDepth` | `32` — Android composites `RGBA_8888` |
     * | `java_enabled` | hardcoded `true` | `false` — a WebView runs no Java applets, and this field means exactly that |
     * | `javascript_enabled` | hardcoded `true` | `true` — genuinely true, the redirect WebView enables JS |
     * | `locale` | `options.locale ?? navigator.language` | [locale] ?? [Locale.getDefault] |
     * | `screen_height` / `screen_width` | `screen.height` / `screen.width` | the display's own metrics |
     * | `utc_time_zone` | `getTimezoneOffset()` | same sign convention: **UTC − local**, in minutes |
     */
    fun browserData(context: Context, locale: String?): JSONObject {
        val userAgent = try {
            WebSettings.getDefaultUserAgent(context)
        } catch (e: Exception) {
            // getDefaultUserAgent loads the WebView provider and can throw if it's missing/updating.
            // Web falls back to 'DESKTOP' when navigator is unavailable — same idea, Android-flavored.
            FALLBACK_USER_AGENT
        }
        return browserBlock(userAgent, locale, context.resources.displayMetrics)
            .put("referrer", "android-app://${context.packageName}")
    }

    /**
     * [browserData] for a caller with no Android [Context] — a saved card paid through
     * `submitPayment()` with no card coordinator mounted, which iOS covers in `preValidation`. The
     * screen comes from the system resources (as `CheckoutMonitoring.screen()` reads it), the user
     * agent is iOS's `"MOBILE"`, and `referrer` is left out: there is no package name to put in it.
     */
    fun fallbackBrowserData(locale: String?): JSONObject =
        browserBlock(
            FALLBACK_USER_AGENT,
            locale,
            runCatching { Resources.getSystem().displayMetrics }.getOrNull(),
        )

    private fun browserBlock(userAgent: String, locale: String?, metrics: DisplayMetrics?): JSONObject =
        JSONObject()
            .put("user_agent", userAgent)
            .put("accept_header", "text/html")
            .put("color_depth", COLOR_DEPTH)
            .put("java_enabled", false)
            .put("javascript_enabled", true)
            .put("locale", locale?.takeIf { it.isNotBlank() } ?: defaultLanguageTag())
            .apply {
                if (metrics != null) {
                    put("screen_height", metrics.heightPixels)
                    put("screen_width", metrics.widthPixels)
                }
            }
            .put("utc_time_zone", utcTimeZoneOffsetMinutes())

    /**
     * The device locale as language-region only (`zh-TW`, not `zh-Hant-TW` or
     * `en-US-u-mu-celsius`): EMV 3-D Secure caps `browserLanguage` at 8 characters, and Android's
     * full tag can carry a script and regional-preference extensions past that.
     */
    internal fun defaultLanguageTag(locale: Locale = Locale.getDefault()): String =
        listOf(locale.language, locale.country).filter { it.isNotEmpty() }.joinToString("-").ifEmpty { "und" }

    /**
     * JavaScript's `Date.getTimezoneOffset()` convention — **UTC minus local**, in minutes, so a zone
     * *ahead* of UTC reports a negative number (Paris in summer: `-120`). Android's
     * `TimeZone.getOffset` uses the opposite sign, hence the negation.
     */
    private fun utcTimeZoneOffsetMinutes(): Int {
        val zone = TimeZone.getDefault()
        return -(zone.getOffset(System.currentTimeMillis()) / 60_000)
    }

    /** Android composites in `RGBA_8888`; there is no per-display colour-depth API to read. */
    private const val COLOR_DEPTH = 32

    /** What iOS sends, and what [browserData] falls back to without a WebView provider. */
    private const val FALLBACK_USER_AGENT = "MOBILE"
}
