package eu.purse.core.payment

/**
 * A payment method under a [Partner] in a `protocols` entry.
 *
 * Only the methods this POC targets get a named case (see docs/architecture/headless-checkout.md);
 * anything else round-trips through [Other] rather than being dropped.
 */
sealed class Method {

    abstract val wireValue: String

    /** Carte Bancaire / generic card payment — see docs/architecture/headless-checkout.md. */
    object CreditCard : Method() {
        override val wireValue = "creditcard"
    }

    object GooglePay : Method() {
        override val wireValue = "googlepay"
    }

    /** Classic PayPal — this POC ignores the sibling [Bnpl] ("Pay Later"). */
    object Wallet : Method() {
        override val wireValue = "wallet"
    }

    /** Illicado — see docs/architecture/headless-checkout.md. */
    object GiftCard : Method() {
        override val wireValue = "giftcard"
    }

    object Bnpl : Method() {
        override val wireValue = "bnpl"
    }

    /** Klarna's own pay-later/slice-it method — see docs/architecture/headless-checkout.md. */
    object Installments : Method() {
        override val wireValue = "installments"
    }

    data class Other(override val wireValue: String) : Method()

    companion object {
        // `by lazy` (not an eager initializer) is deliberate: building this list references the
        // object singletons above, and if the companion is initialized *while one of those objects
        // is still constructing* — which happens when external code touches e.g. `Method.GiftCard`
        // before ever calling `fromWire` — an eager list would capture a half-constructed `null`
        // and later NPE in `fromWire`. Deferring the build to first use breaks that init-order cycle.
        private val known: List<Method> by lazy {
            listOf(CreditCard, GooglePay, Wallet, GiftCard, Bnpl, Installments)
        }

        fun fromWire(value: String): Method = known.firstOrNull { it.wireValue == value } ?: Other(value)
    }
}
