package eu.purse.dropin.payment.method

/**
 * `settings.registration_mode`. `OFF_SESSION` is the one value the parser's own logic depends
 * on — see docs/architecture/session-and-protocols.md#information_request-sessions.
 */
enum class RegistrationMode(val wireValue: String) {
    ON_SESSION("ON_SESSION"),
    OFF_SESSION("OFF_SESSION"),
    UNKNOWN("");

    companion object {
        fun fromWire(value: String?): RegistrationMode = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}
