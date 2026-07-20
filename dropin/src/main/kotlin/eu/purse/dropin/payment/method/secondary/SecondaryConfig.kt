package eu.purse.dropin.payment.method.secondary

import eu.purse.dropin.payment.method.DropinPaymentMethod
import eu.purse.dropin.payment.method.Method
import eu.purse.dropin.payment.partner.Partner

/**
 * Client-side validation rules for a secondary (co-payment) method's manual card entry, ported
 * from usp-widget headless-checkout's `secondaries/configs`. These gate the gift-card form before
 * the network tokenization step (`getSecondaryToken` → Payment API `/init`, not yet built in this
 * POC — see docs/internal/project-setup.md "Still to do"). The POC implements Illicado only.
 */
sealed interface SecondaryConfig {

    /**
     * Whether the CVV field must be shown and validated. Web passes the in-progress PAN (some
     * partners key CVV requirement off the card range); Illicado ignores it and always requires
     * one.
     */
    fun requiresCvv(pan: String): Boolean

    fun validatePan(pan: String): Boolean

    fun validateCvv(cvv: String): Boolean

    /** Illicado gift card — mirrors `configs/illicado-config.ts`. */
    data object Illicado : SecondaryConfig {
        // Illicado PANs: 19 digits, one of two issuer prefixes.
        private val PAN = Regex("^(?:925000478|925000410)\\d{10}$")
        private val CVV = Regex("^\\d{3}$")

        override fun requiresCvv(pan: String): Boolean = true
        override fun validatePan(pan: String): Boolean = PAN.matches(pan)
        override fun validateCvv(cvv: String): Boolean = CVV.matches(cvv)
    }

    companion object {
        /** `null` when the POC has no secondary config for this method's partner. */
        fun forMethod(method: DropinPaymentMethod): SecondaryConfig? = when {
            method.partner == Partner.Illicado && method.method == Method.GiftCard -> Illicado
            else -> null
        }
    }
}
