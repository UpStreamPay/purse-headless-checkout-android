package eu.purse.dropin.payment.method.secondary

import eu.purse.dropin.payment.method.Method
import eu.purse.dropin.payment.partner.Partner
import java.math.BigDecimal

/**
 * A tokenized secondary (co-payment) instrument returned by the Payment API `/init` call — e.g. an
 * Illicado gift card the shopper has added. Mirrors the balance/name/expiry the web extracts from
 * the init response (see usp-widget headless-checkout `secondaries/token-mappers.ts`).
 *
 * [balance] is in major currency units (same convention as the session amounts).
 */
data class SecondaryToken(
    val partner: Partner,
    val method: Method,
    /** Human label for the card, e.g. the masked PAN from the init `logs`. May be blank. */
    val name: String,
    val balance: BigDecimal,
    /** Raw expiry as returned by the partner (format varies); blank when absent. */
    val expirationDate: String,
)
