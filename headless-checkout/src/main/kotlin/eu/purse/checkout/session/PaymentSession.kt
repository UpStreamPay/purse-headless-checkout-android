// Web: packages/headless-checkout/src/session/payment/payment.ts (session in force)
// Same: id, amount, currency, expiry, vault configuration, means of payment.
// Differs: built once by `PaymentSessionBuilder`.
package eu.purse.checkout.session

import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.core.vault.VendorFrontData
import java.math.BigDecimal
import java.time.Instant

/**
 * The built session — the raw [LegacyPaymentSession] plus the derived, ready-to-consume payment
 * model. The Kotlin analog of web headless-checkout's `PaymentSession` (`session/payment/types.ts`),
 * built by [PaymentSessionBuilder].
 */
data class PaymentSession(
    val id: String,
    val source: LegacyPaymentSession,
    val amount: BigDecimal,
    /** [amount] in integer cents (×100) — the unit the splitter works in. */
    val amountCts: Long,
    val isInformationRequest: Boolean,
    val currency: String,
    val expirationDate: Instant,
    /** Session-level vault config (`vault.vendor_front_data`) — tenant / merchant ids for capture. */
    val vaultsConfiguration: VendorFrontData?,
    val meansOfPayment: List<PaymentItem>,
)
