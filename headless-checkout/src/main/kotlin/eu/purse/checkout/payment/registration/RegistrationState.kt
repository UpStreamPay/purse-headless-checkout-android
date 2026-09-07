// Web: packages/headless-checkout `registration` stores
// Same: registered + optional name.
// Differs: nothing.
package eu.purse.checkout.payment.registration

/**
 * Whether a method's fresh card should be saved (registered) at `/validate`, and under which label
 * — web's `RegistrationState` store. Defaults come from the method's resolved
 * [eu.purse.core.payment.SaveTokenMode] (`ALWAYS`/`PREFER` start registered).
 */
data class RegistrationState(
    val registered: Boolean,
    val name: String? = null,
)
