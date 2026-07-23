package eu.purse.partnerui

/**
 * How a plugin renders / captures — web's `Interface` (`'form' | 'hostedfields' | 'payButton'`).
 * The concrete rendering host (the Android analog of web's `<purse-partner-ui>` element) is
 * deferred; a plugin only declares which interfaces it *would* support.
 */
enum class PluginInterface(val wireValue: String) {
    FORM("form"),
    HOSTED_FIELDS("hostedfields"),
    PAY_BUTTON("payButton"),
}
