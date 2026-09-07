// Web: packages/model registration settings
// Same: same values.
// Differs: nothing.
package eu.purse.core.payment

/**
 * `settings.registration_mode`. `OFF_SESSION` is the one value the parser's own logic depends
 * on — see docs/architecture/headless-checkout.md.
 */
enum class RegistrationMode(val wireValue: String) {
    ON_SESSION("ON_SESSION"),
    OFF_SESSION("OFF_SESSION"),
    UNKNOWN("");

    companion object {
        fun fromWire(value: String?): RegistrationMode = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}
