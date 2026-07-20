package eu.purse.dropin.payment.method

/**
 * `settings.merchant_specifics.integration_mode`. Purely informational for PayPal today — see
 * docs/partners/paypal.md#redirection-study.
 */
enum class IntegrationMode(val wireValue: String) {
    POPUP("popup"),
    REDIRECTION("redirection");

    companion object {
        fun fromWire(value: String?): IntegrationMode? = entries.firstOrNull { it.wireValue == value }
    }
}
