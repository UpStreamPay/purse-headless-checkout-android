package eu.purse.checkout.payment.secondary

import eu.purse.checkout.PurseCheckoutException
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.core.api.PaymentApiClient
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Per-partner rules for a secondary (co-payment) method — the Kotlin port of web headless-checkout's
 * `SecondarySpecificConfig` (`secondaries/configs`) plus its init formatter
 * (`secondaries/token-mappers.ts`). It gates the manual card form (PAN/CVV validation, whether a CVV
 * is required), declares the [takePolicy], builds the partner `payment_data` bodies for `/init` and
 * `/validate`, and parses the `/init` response into a [SecondaryToken].
 *
 * Secondaries have **no `:partner-ui` plugin** — the whole flow lives here, matching web.
 *
 * **This is the public extension point for issuers the SDK doesn't ship.** A host app implements it
 * for its own gift-card / voucher partner and hands it to [SecondaryConfigs.register] — the
 * tokenizer, the splitter and the `/validate` pipeline then treat that method exactly like a
 * built-in one, with no SDK change. Everything has a default except [takePolicy], [requiresCvv]
 * and [validatePan]; override [parseToken] when the `/init` response isn't the generic card shape,
 * [shouldPoll] / [pollForToken] when the partner answers `PROCESSING` first, and
 * [classifyInitError] to map a partner-specific refusal onto a typed exception.
 */
interface SecondaryConfig {

    val takePolicy: SecondaryTakePolicy

    /** `plugin_result.logs` key holding the display label, for the default card-based parse. */
    val nameLogKey: String? get() = null

    fun requiresCvv(pan: String): Boolean
    fun validatePan(pan: String): Boolean
    fun validateCvv(cvv: String): Boolean = true

    /** `payment_data` body for `POST /init` — `vault_vendor` is added by `ItemPayload.toJson`. */
    fun buildInitPaymentData(pan: String, cvv: String?): JSONObject = cardPaymentData(pan, cvv)

    /** `payment_data` body for `POST /validate` of an applied [token]. */
    fun buildValidatePaymentData(token: SecondaryToken): JSONObject = cardPaymentData(token.pan, token.cvv)

    /**
     * Parse the `/init` response into a [SecondaryToken]. The default reads the generic card shape
     * (`plugin_result.payment_data.cards[0]` for balance/expiry, [nameLogKey] for the label); a
     * partner with a different response shape (e.g. Maxxing) overrides this.
     *
     * @throws PurseCheckoutException.SecondaryTokenizationFailed on an unsuccessful init / no balance.
     */
    fun parseToken(method: SecondaryMethod, initResponse: JSONObject, pan: String, cvv: String?): SecondaryToken =
        parseCardToken(method, initResponse, pan, cvv, takePolicy, nameLogKey)

    /**
     * Whether a successful `/init` answer is only an acknowledgement that must be polled for the
     * real token — web's `shouldPoll` (`status.state == SUCCESS && plugin_result.status ==
     * PROCESSING`). Default: never poll.
     */
    fun shouldPoll(initResponse: JSONObject): Boolean = false

    /**
     * Second phase of a two-step tokenization (web's `pollForToken`): called when [shouldPoll] says
     * so, with the [api] to re-`/init` against and the wire [initPayload] that was sent. Must
     * return the final `/init`-shaped response to hand to [parseToken]. Default: [initResponse].
     */
    fun pollForToken(api: PaymentApiClient, initResponse: JSONObject, initPayload: JSONObject): JSONObject =
        initResponse

    /**
     * Map an unsuccessful `/init` answer onto a partner-specific exception — web's
     * `classifyInitError`. Return `null` to fall back to the generic
     * [PurseCheckoutException.SecondaryTokenizationFailed].
     */
    fun classifyInitError(initResponse: JSONObject): PurseCheckoutException? = null
}

/** Illicado gift card — `configs/illicado-config.ts`. */
object IllicadoConfig : SecondaryConfig {
    private val PAN = Regex("^(?:925000478|925000410)\\d{10}$")
    private val CVV = Regex("^\\d{3}$")
    override val takePolicy = SecondaryTakePolicy.NONE
    override val nameLogKey = "masked_card"
    override fun requiresCvv(pan: String) = true
    override fun validatePan(pan: String) = PAN.matches(pan)
    override fun validateCvv(cvv: String) = CVV.matches(cvv)
}

/** BuyBox / Storeland / Oney gift card — `configs/buybox-config.ts`. */
object BuyboxConfig : SecondaryConfig {
    private val BUYBOX = Regex("^[A-HJ-KM-NP-Z]\\d{4,9}[A-HJ-KM-NP-Z]\\d{5,10}$")
    private val STORELAND = Regex("^533\\d{10}$")
    private val ONEY = Regex("^\\d{19}D\\d{17}$|^\\d{19}$")
    override val takePolicy = SecondaryTakePolicy.NONE
    override val nameLogKey = "card.mask"
    // Storeland cards carry no CVV; every other BuyBox card does.
    override fun requiresCvv(pan: String) = !STORELAND.matches(pan)
    override fun validatePan(pan: String) = STORELAND.matches(pan) || ONEY.matches(pan) || BUYBOX.matches(pan)
}

/** Maxxing loyalty card — `configs/maxxing-config.ts`. Non-splittable; balance comes from logs. */
object MaxxingConfig : SecondaryConfig {
    override val takePolicy = SecondaryTakePolicy.MAX
    override fun requiresCvv(pan: String) = false
    override fun validatePan(pan: String) = true

    override fun parseToken(method: SecondaryMethod, initResponse: JSONObject, pan: String, cvv: String?): SecondaryToken {
        requireSuccessfulInit(initResponse)
        val logs = initResponse.optJSONObject("plugin_result")?.optJSONObject("logs")
        val balance = logs?.optString("eligible_euros").orEmpty().toBigDecimalOrNull() ?: BigDecimal.ZERO
        val total = logs?.optString("total_euros").orEmpty().toBigDecimalOrNull() ?: balance
        if (balance.signum() <= 0) {
            throw PurseCheckoutException.SecondaryTokenizationFailed(
                "loyalty card has no eligible balance",
                PurseErrorCode.SECONDARY_TOKEN_EMPTY_BALANCE,
            )
        }
        return SecondaryToken(
            id = "${method.id}-${pan.takeLast(4)}",
            partner = method.partner,
            method = method.method,
            pan = pan,
            cvv = cvv,
            name = logs?.optString("customer.reference").orEmpty(),
            balance = balance,
            totalBalance = total,
            expirationDate = "",
            takePolicy = takePolicy,
            limit = method.maxAmount,
        )
    }
}

/** Ogloba gift card — `configs/ogloba-config.ts`. */
object OglobaConfig : SecondaryConfig {
    private val PAN = Regex("^\\d{6}")
    override val takePolicy = SecondaryTakePolicy.NONE
    override val nameLogKey = "card_number"
    override fun requiresCvv(pan: String) = false
    override fun validatePan(pan: String) = PAN.containsMatchIn(pan)
}

/** MyBeezBox gift card — `configs/mybeezbox-config.ts`. Any non-empty PAN. */
object MybeezboxConfig : SecondaryConfig {
    override val takePolicy = SecondaryTakePolicy.NONE
    override val nameLogKey = "card_number"
    override fun requiresCvv(pan: String) = false
    override fun validatePan(pan: String) = pan.isNotEmpty()
}

/** Easy2Play gift card — `configs/easy2play-config.ts`. Requires a 3-digit CVV. */
object Easy2playGiftcardConfig : SecondaryConfig {
    private val CVV = Regex("^\\d{3}$")
    override val takePolicy = SecondaryTakePolicy.NONE
    override val nameLogKey = "card_mask"
    override fun requiresCvv(pan: String) = true
    override fun validatePan(pan: String) = true
    override fun validateCvv(cvv: String) = CVV.matches(cvv)
}

/** Easy2Play voucher — `configs/easy2play-config.ts` (voucherConfig). Non-splittable; `voucher_number`. */
object Easy2playVoucherConfig : SecondaryConfig {
    override val takePolicy = SecondaryTakePolicy.MAX
    override fun requiresCvv(pan: String) = false
    override fun validatePan(pan: String) = pan.isNotEmpty()
    override fun buildInitPaymentData(pan: String, cvv: String?) = JSONObject().put("voucher_number", pan)
    override fun buildValidatePaymentData(token: SecondaryToken) = JSONObject().put("voucher_number", token.pan)
}

/**
 * Resolves the [SecondaryConfig] for a secondary method — web's `configs/configs.ts` registry, made
 * **host-extensible**: the shipped partners are registered up front, and a merchant adds an issuer
 * the SDK doesn't know with [register], keyed by `"{partner}-{method}"` wire values so
 * [Partner.Other] / [Method.Other] work. Registration is process-wide, like `PluginRegistry` —
 * do it once at app start.
 */
object SecondaryConfigs {

    // Keyed by "{partner}-{method}" wire values, exactly as web's SecondarySpecificConfigs.
    // NOTE: `ancv-connect` is intentionally absent — it needs a two-phase (holder → poll) init flow
    // that isn't implemented yet; a host can supply one through `register` + `pollForToken`.
    private val builtIn: Map<String, SecondaryConfig> = mapOf(
        "illicado-giftcard" to IllicadoConfig,
        "buybox-giftcard" to BuyboxConfig,
        "maxxing-loyaltycard" to MaxxingConfig,
        "ogloba-giftcard" to OglobaConfig,
        "mybeezbox-giftcard" to MybeezboxConfig,
        "easy2play-giftcard" to Easy2playGiftcardConfig,
        "easy2play-voucher" to Easy2playVoucherConfig,
    )

    private val byKey: MutableMap<String, SecondaryConfig> = java.util.concurrent.ConcurrentHashMap(builtIn)

    private fun key(partner: Partner, method: Method) = "${partner.wireValue}-${method.wireValue}"

    fun forMethod(partner: Partner, method: Method): SecondaryConfig? = byKey[key(partner, method)]

    fun forMethod(method: SecondaryMethod): SecondaryConfig? = forMethod(method.partner, method.method)

    /**
     * Register (or replace) the config driving `(partner, method)` — how a host app adds an issuer
     * that isn't in the catalogue, or overrides a shipped one. Resolution happens per call, so a
     * checkout that already exists sees it on its next tokenization.
     */
    fun register(partner: Partner, method: Method, config: SecondaryConfig) {
        byKey[key(partner, method)] = config
    }

    /** Remove a registration (built-in or host-added); [reset] restores the shipped catalogue. */
    fun unregister(partner: Partner, method: Method) {
        byKey.remove(key(partner, method))
    }

    /** Whether any config — shipped or registered — drives `(partner, method)`. */
    fun isRegistered(partner: Partner, method: Method): Boolean = byKey.containsKey(key(partner, method))

    /** Back to the shipped catalogue only, dropping every host registration. */
    fun reset() {
        byKey.clear()
        byKey.putAll(builtIn)
    }
}

// --- shared helpers -------------------------------------------------------------------------------

/** `{ card: { number, csc? } }` — the common secondary `payment_data` body. */
internal fun cardPaymentData(pan: String, cvv: String?): JSONObject {
    val card = JSONObject().put("number", pan)
    if (!cvv.isNullOrEmpty()) card.put("csc", cvv)
    return JSONObject().put("card", card)
}

internal fun requireSuccessfulInit(initResponse: JSONObject) {
    val status = initResponse.optJSONObject("status")
    val state = status?.optString("state").orEmpty()
    if (state != "SUCCESS") {
        val description = status?.optString("description").orEmpty()
        throw PurseCheckoutException.SecondaryTokenizationFailed(
            "secondary init not successful (state=$state) $description".trim(),
        )
    }
}

/** Generic card-based token parse — web's `fromCard` formatter, parameterized by the name log key. */
internal fun parseCardToken(
    method: SecondaryMethod,
    initResponse: JSONObject,
    pan: String,
    cvv: String?,
    takePolicy: SecondaryTakePolicy,
    nameLogKey: String?,
): SecondaryToken {
    requireSuccessfulInit(initResponse)
    val pluginResult = initResponse.optJSONObject("plugin_result") ?: JSONObject()
    val card = pluginResult.optJSONObject("payment_data")?.optJSONArray("cards")?.optJSONObject(0)
    val balance = card?.let { bigDecimalOrNull(it, "balance") } ?: BigDecimal.ZERO
    if (balance.signum() <= 0) {
        throw PurseCheckoutException.SecondaryTokenizationFailed(
            "secondary card has no balance",
            PurseErrorCode.SECONDARY_TOKEN_EMPTY_BALANCE,
        )
    }
    val name = nameLogKey?.let { pluginResult.optJSONObject("logs")?.optString(it) }.orEmpty()
    return SecondaryToken(
        id = "${method.id}-${pan.takeLast(4)}",
        partner = method.partner,
        method = method.method,
        pan = pan,
        cvv = cvv,
        name = name,
        balance = balance,
        totalBalance = balance,
        expirationDate = card?.optString("expiry_date").orEmpty(),
        takePolicy = takePolicy,
        limit = method.maxAmount,
    )
}

private fun bigDecimalOrNull(obj: JSONObject, key: String): BigDecimal? =
    if (obj.has(key) && !obj.isNull(key)) runCatching { BigDecimal(obj.get(key).toString()) }.getOrNull() else null
