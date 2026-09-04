// Web: packages/headless-checkout/src/session/payment/mops/secondaries/configs/take-policy.ts
// Same: `none` splittable, `max` takes the cap.
// Differs: nothing.
package eu.purse.checkout.payment.secondary

/**
 * How much of a secondary instrument's balance may be drawn into the split — web's
 * `SecondaryTakePolicy`.
 */
enum class SecondaryTakePolicy {
    /** Splittable: any amount up to the balance (and what's left to pay) can be taken. Gift cards. */
    NONE,

    /** Non-splittable: the whole available amount (min of balance and remaining) is taken. Loyalty. */
    MAX,
}
