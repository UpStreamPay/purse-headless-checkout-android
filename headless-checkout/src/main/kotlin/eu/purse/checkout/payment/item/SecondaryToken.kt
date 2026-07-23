package eu.purse.checkout.payment.item

import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import java.math.BigDecimal

/**
 * A tokenized secondary (co-payment) instrument returned by the Payment API `/init` — e.g. a gift
 * card the shopper added. Mirrors the balance/name/expiry web extracts from the init response
 * (`secondaries/token-mappers.ts`). Producing one is a plugin concern (deferred); this is the model
 * the splitter draws its [balance] down against. Amounts are in major currency units.
 */
data class SecondaryToken(
    val partner: Partner,
    val method: Method,
    /** Human label for the instrument, e.g. a masked PAN. May be blank. */
    val name: String,
    val balance: BigDecimal,
    /** Raw expiry as returned by the partner (format varies); blank when absent. */
    val expirationDate: String,
)
