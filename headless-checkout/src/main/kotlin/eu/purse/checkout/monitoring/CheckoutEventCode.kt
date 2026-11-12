// Web: packages/headless-checkout/src/monitoring/monitoringConfigs.ts
// Same: the same 40 codes and levels.
// Differs: `HEADLESS_CHECKOUT_METHOD_DISABLED` — web has no event for a method's disabled state.
package eu.purse.checkout.monitoring

/**
 * Every analytics event the checkout can emit — full parity with web's
 * `PurseHeadlessCheckoutEventPayload` keys (`monitoring/checkout-event-bus.ts`). Codes whose web
 * trigger has no Android feature yet (payment elements, wallet tokens, loan simulation) are still
 * declared so dashboards see one vocabulary; they start firing as those features land.
 *
 * [HEADLESS_CHECKOUT_METHOD_DISABLED] is Android-first: every transition of a method into a
 * [eu.purse.checkout.payment.item.DisabledState] (a platform suspension, an amount rule, a partner
 * failure, an incompatible plugin) as one event with the state's `code` — what the Analytics Events
 * page lists as "Disabled states" and web only exposes through its partner-UI firehose.
 */
enum class CheckoutEventCode {
    HEADLESS_CHECKOUT_CLEAR_PRIMARY_SPLIT,
    HEADLESS_CHECKOUT_CLIENT_HOOK_ERROR,
    HEADLESS_CHECKOUT_CREATE,
    HEADLESS_CHECKOUT_DELETE_ALL_TOKENS,
    HEADLESS_CHECKOUT_EXPIRED_SESSION,
    HEADLESS_CHECKOUT_METHOD_GET_PAYMENT_ELEMENT,
    HEADLESS_CHECKOUT_METHOD_GET_HOSTED_FIELDS,
    HEADLESS_CHECKOUT_METHOD_DISABLED,
    HEADLESS_CHECKOUT_METHOD_GET_SECONDARY_TOKEN,
    HEADLESS_CHECKOUT_METHOD_LOAN_SIM,
    HEADLESS_CHECKOUT_METHOD_REGISTER,
    HEADLESS_CHECKOUT_METHOD_SET_PRIMARY,
    HEADLESS_CHECKOUT_OTHER_ERROR,
    HEADLESS_CHECKOUT_PAGE_UNLOAD,
    HEADLESS_CHECKOUT_PARTNER_UI_EVENT,
    HEADLESS_CHECKOUT_PARTNER_API_CALL_START,
    HEADLESS_CHECKOUT_PARTNER_API_CALL_END,
    HEADLESS_CHECKOUT_PAYMENT_ELEMENT_APPEND_TO,
    HEADLESS_CHECKOUT_PAYMENT_ELEMENT_REMOVE,
    HEADLESS_CHECKOUT_PAYMENT_ELEMENT_SET_OPTIONS,
    HEADLESS_CHECKOUT_PAYMENT_ELEMENT_VALIDATE_UI,
    HEADLESS_CHECKOUT_PAYMENT_ELEMENT_LISTENER_ATTACHED,
    HEADLESS_CHECKOUT_VALIDATION_STARTED,
    HEADLESS_CHECKOUT_PRE_VALIDATION_FAILED,
    HEADLESS_CHECKOUT_VALIDATION_SUCCESS,
    HEADLESS_CHECKOUT_VALIDATION_FAILED,
    HEADLESS_CHECKOUT_PAYMENT_ERROR,
    HEADLESS_CHECKOUT_PAYMENT_FULFILLED,
    HEADLESS_CHECKOUT_PRIMARY_TOKEN_GET_PAYMENT_ELEMENT,
    HEADLESS_CHECKOUT_PRIMARY_TOKEN_SET_AS_PRIMARY,
    HEADLESS_CHECKOUT_REACTIVE_VALUE_SUBSCRIBE,

    /**
     * The shopper leaves the checkout for a PSP page, a 3-D Secure page or the merchant's
     * success/failure page (SDK-12712). INFO. Payload, Widget V3's `REDIRECTION` as web posts it:
     * exactly one of `url` (a [Get][eu.purse.partnerui.redirect.RedirectAction.Get]), `action` (a
     * [PostForm][eu.purse.partnerui.redirect.RedirectAction.PostForm]) — both the **hostname
     * only**, or `couldn't parse the url` — or `string_form: true` (a
     * [PostHtml][eu.purse.partnerui.redirect.RedirectAction.PostHtml], no URL to read); plus
     * `delegated`, true when the merchant's `onRedirection` hook took the redirect over.
     */
    HEADLESS_CHECKOUT_REDIRECTION,
    HEADLESS_CHECKOUT_READY,
    HEADLESS_CHECKOUT_SECONDARY_TOKEN_REGISTER,
    HEADLESS_CHECKOUT_SECONDARY_TOKEN_REMOVE_FROM_SPLIT,
    HEADLESS_CHECKOUT_SECONDARY_TOKEN_TAKE,
    HEADLESS_CHECKOUT_SET_SESSION,
    HEADLESS_CHECKOUT_SET_WALLET_SESSION,
    HEADLESS_CHECKOUT_SET_WALLET_SESSION_FAILED,
    HEADLESS_CHECKOUT_SUBMIT_PAYMENT,
    HEADLESS_CHECKOUT_SUBMIT_BLOCKED,
    HEADLESS_CHECKOUT_TOKEN_DELETE,
    HEADLESS_CHECKOUT_TOKEN_EDIT,
    HEADLESS_CHECKOUT_TOKEN_SET_FAVORITE,
    HEADLESS_CHECKOUT_UNCAUGHT_ERROR,
    HEADLESS_CHECKOUT_ON_BEFORE_VALIDATE_HOOK_FAILED,
}
