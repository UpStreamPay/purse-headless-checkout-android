// Web: packages/headless-checkout splitter source
// Same: id, partner, method.
// Differs: nothing.
package eu.purse.checkout.payment.split

import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner

/**
 * Anything that can back a [PaymentSplit]: a primary [eu.purse.checkout.payment.item.PrimaryMethod]
 * (which absorbs the remainder) or an applied
 * [eu.purse.checkout.payment.secondary.SecondaryToken] (a drawn-down co-payment instrument). The
 * "create payment" pipeline dispatches on the concrete type — primaries go through a `:partner-ui`
 * plugin, secondaries through their `SecondaryConfig`.
 */
interface SplitSource {
    val id: String
    val partner: Partner
    val method: Method
}
