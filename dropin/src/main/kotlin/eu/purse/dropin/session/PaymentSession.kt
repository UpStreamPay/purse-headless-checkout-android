package eu.purse.dropin.session

import eu.purse.dropin.payment.method.DropinPaymentMethod
import eu.purse.dropin.vault.VendorFrontData
import java.math.BigDecimal
import java.time.Instant

/**
 * The built session — [LegacyPaymentSession] plus the derived, ready-to-render
 * [meansOfPayment] list. Built by `eu.purse.dropin.session.PaymentSessionBuilder`.
 */
data class PaymentSession(
    val id: String,
    val source: LegacyPaymentSession,
    val amount: BigDecimal,
    val isInformationRequest: Boolean,
    val currency: String,
    val expirationDate: Instant,
    val vaultsConfiguration: VendorFrontData?,
    val meansOfPayment: List<DropinPaymentMethod>,
)
