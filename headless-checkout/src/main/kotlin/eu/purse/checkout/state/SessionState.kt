package eu.purse.checkout.state

/**
 * The session lifecycle state exposed by [eu.purse.checkout.PurseHeadlessCheckout.sessionState] —
 * web's `PurseHeadlessCheckoutSessionState`, minus `idle`: web instantiates the checkout before
 * `setSession`, whereas here a checkout can't exist without a decoded session, so an idle state is
 * unrepresentable by construction.
 *
 * A session can be `/init`-ed many times but `/validate`-d only once: [SUBMITTED] means spent,
 * [EXPIRED] and [TERMINATED] are fatal, [ERROR] is transient (the submit failed; the session may
 * still be usable).
 */
enum class SessionState {
    READY,
    SUBMITTING,
    SUBMITTED,
    ERROR,
    EXPIRED,

    /**
     * The Payment API said the session is finished for good — already submitted, expired
     * server-side, or gone (web's `SESSION_TERMINAL_ERRORS` raised during `/init`/`/validate`).
     */
    TERMINATED,
}
