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
    val ui: PluginInterface = PluginInterface.FORM,
    val register: Boolean = false,
    val token: String? = null,
    val options: PluginOptions = PluginOptions(),
    val dispatchEvent: (PluginEvent) -> Unit = {},
)

/** Presentation preferences forwarded to a plugin — web's `Options` (theme/locale/…), trimmed. */
data class PluginOptions(
    val locale: String? = null,
    val theme: String? = null,
)
