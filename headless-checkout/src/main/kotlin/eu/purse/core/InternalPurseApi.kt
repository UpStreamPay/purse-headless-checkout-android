// Web: none — a TypeScript package hides internals by not exporting them.
// Same: n/a.
// Differs: Kotlin has no friend modules, so the seams the optional partner artifacts
//          (`:partner-googlepay`, `:partner-klarna`, `:partner-paypal`) need from `:partner-ui`
//          are public but opt-in only.
package eu.purse.core

/**
 * Marks a declaration that is public only so another Purse artifact can reach it. It is **not** part
 * of the SDK's supported API: it may change or disappear in any release without a major bump, and
 * the binary-compatibility check ignores it. Merchant code should never opt in.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal to the Purse SDK artifacts — not a supported API, may change without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY_SETTER,
)
annotation class InternalPurseApi
