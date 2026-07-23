package eu.purse.partnerui

/**
 * Lifecycle signals a plugin emits back to its host — a trimmed analog of web `partner-ui`'s
 * `Events`. A plugin fires these through [PartnerUiContext.dispatchEvent]; the host decides what to
 * do (e.g. mark the item fulfilled, follow a redirect, surface an error).
 */
enum class PluginEvent {
    READY,
    FULFILLED,
    REDIRECT,
    INIT_FAILED,
    CLIENT_ERROR,
    DIED,
}
