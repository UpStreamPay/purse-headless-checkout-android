// Web: none — web identifies a method by its `(partner, method)` pair inline.
// Same: n/a.
// Differs: a named pair, so an option like `disabledMethods` reads as what it is.
package eu.purse.core.payment

/** One payment method of a session: its [partner] and [method] — the pair every plugin is keyed on. */
data class MethodKey(val partner: Partner, val method: Method) {
    /** The item id the checkout gives this method (`"paypal-wallet"`). */
    val id: String get() = "${partner.wireValue}-${method.wireValue}"

    companion object {
        /**
         * From the wire names the session uses (`"purse"`, `"creditcard"`), resolving known partners
         * and methods to their objects so the key equals what the session decodes to —
         * `MethodKey(Partner.Other("purse"), …)` would not.
         */
        fun of(partner: String, method: String): MethodKey = MethodKey(Partner.fromWire(partner), Method.fromWire(method))
    }
}
