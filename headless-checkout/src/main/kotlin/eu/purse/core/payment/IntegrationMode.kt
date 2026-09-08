// Web: `merchant_specifics.integration_mode`
// Same: `redirection` / `popup`.
// Differs: nothing.
package eu.purse.core.payment

/**
 * `settings.merchant_specifics.integration_mode`. Purely informational for PayPal today — see
 * docs/architecture/headless-checkout.md.
 */
enum class IntegrationMode(val wireValue: String) {
    POPUP("popup"),
    REDIRECTION("redirection");

    companion object {
        fun fromWire(value: String?): IntegrationMode? = entries.firstOrNull { it.wireValue == value }
    }
}
