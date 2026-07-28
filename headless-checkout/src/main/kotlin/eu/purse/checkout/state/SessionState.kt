package eu.purse.checkout.state

/**
 * The session lifecycle state exposed by [eu.purse.checkout.PurseHeadlessCheckout.sessionState] —
 * web's `PurseHeadlessCheckoutSessionState`.
 */
enum class SessionState {
    IDLE,
    READY,
    SUBMITTING,
    SUBMITTED,
    ERROR,
    EXPIRED,
    TERMINATED,
}
