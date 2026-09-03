package eu.purse.partnerui

import eu.purse.core.api.PaymentApiClient
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.session.LegacyPaymentSession
import java.math.BigDecimal

/**
 * The single object every [PartnerUiPlugin] lifecycle hook receives — the Kotlin analog of web's
 * `PartnerUIContext`. It is how a plugin reaches everything it needs without the host knowing
 * anything PSP-specific:
 *
 * - its own configuration, via `session.protocols[partner][method].configurations` — which carries
 *   the per-method `vault` settings and `settings.merchant_specifics`;
 * - the [amount] this item must cover, handed down from the splitter;
 * - the [api] client to call `/init` / `/validate` against the current session.
 *
 * Kept immutable here; the reactive "updateData → onDataUpdate" scratchpad web exposes belongs to
 * the rendering host, which is deferred.
 */
data class PartnerUiContext(
    val partner: Partner,
    val method: Method,
    val session: LegacyPaymentSession,
    val amount: BigDecimal,
    val api: PaymentApiClient,
    val register: Boolean = false,
    /** Set when this item pays with a saved instrument (card-on-file) — web's `token` attribute. */
    val token: PayToken? = null,
    /**
     * Data an interactive plugin collected outside the headless flow (e.g. a Google Pay token +
     * selected network from the payment sheet), threaded in so `preValidation` can build its payload.
     * Populated by the SDK from what the merchant handed back via `setCollected`.
     */
    val collected: Map<String, Any?> = emptyMap(),
    val options: PluginOptions = PluginOptions(),
    val dispatchEvent: (PluginEvent) -> Unit = {},
)

/**
 * Presentation preferences forwarded to a plugin — web's `Options` (theme/locale/hostedForm…),
 * trimmed to what the Android plugins render themselves.
 */
data class PluginOptions(
    /** BCP-47 locale (e.g. `"fr-FR"`); `null` falls back to the device locale. */
    val locale: String? = null,
    /** Styling for partner-rendered fields (the SecureFields card form). */
    val theme: PurseTheme? = null,
    /** Text overrides for the card form — the card subset of web's `HostedFormUIOptions`. */
    val cardFormTexts: CardFormTexts = CardFormTexts(),
)

/**
 * Overridable copy for the card form — the card subset of web's `HostedFormUIOptions`
 * (`panInputLabel`, `panPlaceholder`, `panRequiredError`, `panFormatError`, `panTooltipText`, …),
 * one [FieldTexts] per secure field. `null` falls back to the SDK's localized resources
 * (`purse_card_*` strings, `values/` + `values-fr/`) — web falls back to its translation table the
 * same way.
 */
data class CardFormTexts(
    val cardNumber: FieldTexts = FieldTexts(),
    val expDate: FieldTexts = FieldTexts(),
    val cvv: FieldTexts = FieldTexts(),
    val holderName: FieldTexts = FieldTexts(),
) {
    /**
     * The five texts a secure field can show: its [label] above the field, the [placeholder]
     * inside it, the [helperText] under it (web's tooltip text), and the two messages that replace
     * the helper text when the field is invalid — [requiredError] for an empty field the shopper
     * left, [formatError] for content SecureFields rejects.
     */
    data class FieldTexts(
        val label: String? = null,
        val placeholder: String? = null,
        val helperText: String? = null,
        val requiredError: String? = null,
        val formatError: String? = null,
    )
}

/**
 * A saved instrument to pay with — the fields of web partner-ui's `Token` attribute that
 * `patchItemPayloadWithToken` reads: the opaque [value] plus the card metadata that travels into
 * `payment_data.card` (`expiry_date`, `selected_network`) and `payment_data.holder`.
 */
data class PayToken(
    val value: String,
    /** ISO expiration of the underlying card; formatted to `yyyy-MM-dd` on the wire. */
    val expirationDate: String? = null,
    val holderName: String? = null,
    /** The card scheme in session vocabulary (e.g. `VISA`), when known. */
    val selectedNetwork: String? = null,
    /**
     * Whether paying with this token still needs the shopper's CVV — the inverse of web's no-CVV
     * gates (`plugins/utils/noCVV.ts`: the merchant's `pay_token_no_cvv` setting, or an available
     * network token when the experience enables them). When `true`, the card plugin renders a
     * CVV-only SecureFields form and `/validate` carries the resulting `vault_vendor_token_id`
     * alongside the saved `token`; when `false`, no form is involved at all.
     */
    val requiresCvv: Boolean = false,
) {
    /**
     * `expiry_date` as the Payment API expects it — web's `formatUTCToExpiration` (`yyyy-MM-dd`),
     * or `null` when the ISO value can't be read.
     */
    fun wireExpiryDate(): String? {
        val raw = expirationDate ?: return null
        return runCatching { java.time.Instant.parse(raw).atOffset(java.time.ZoneOffset.UTC).toLocalDate().toString() }
            .recoverCatching { java.time.LocalDate.parse(raw.take(10)).toString() }
            .getOrNull()
    }
}
