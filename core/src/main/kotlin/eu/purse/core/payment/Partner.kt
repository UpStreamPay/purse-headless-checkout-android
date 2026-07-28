package eu.purse.core.payment

/**
 * A PSP (or, for [Purse], Purse's own vault) behind a `protocols` entry.
 *
 * The web `Partner` enum has ~38 members; only the ones this POC targets get a named case here
 * (see docs/architecture/headless-checkout.md). Anything else round-trips through [Other] instead of being
 * silently dropped, so an unrecognized partner in a real session doesn't disappear from
 * [eu.purse.core.session.LegacyPaymentSession.protocols].
 */
sealed class Partner {

    abstract val wireValue: String

    /** Purse's own card vault — see docs/architecture/headless-checkout.md. */
    object Purse : Partner() {
        override val wireValue = "purse"
    }

    object Aci : Partner() {
        override val wireValue = "aci"
    }

    object Adyen : Partner() {
        override val wireValue = "adyen"
    }

    object Paypal : Partner() {
        override val wireValue = "paypal"
    }

    object Illicado : Partner() {
        override val wireValue = "illicado"
    }

    /** Klarna — see docs/architecture/headless-checkout.md. */
    object Klarna : Partner() {
        override val wireValue = "klarna"
    }

    data class Other(override val wireValue: String) : Partner()

    companion object {
        // `by lazy` (not an eager initializer) is deliberate — see the matching note in
        // [eu.purse.core.payment.Method]: an eager `listOf(objects)` here can capture a
        // half-constructed `null` when external code touches a `Partner` object before `fromWire`,
        // causing an NPE. Deferring the build to first use breaks that init-order cycle.
        private val known: List<Partner> by lazy { listOf(Purse, Aci, Adyen, Paypal, Illicado, Klarna) }

        fun fromWire(value: String): Partner = known.firstOrNull { it.wireValue == value } ?: Other(value)
    }
}
