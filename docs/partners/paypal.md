# Partner: `paypal`

Backs **PayPal** (`wallet`) in this POC — "starter" depth, since it's the one method requiring a
redirect out of and back into the app (the ticket's own open question).

---

## Table of Contents

- [Role in this POC](#role-in-this-poc)
- [Documentation](#documentation)
- [Redirection study](#redirection-study)
- [See also](#see-also)

---

## Role in this POC

`packages/partner-ui/src/plugins/partners/paypal/wallet/index.ts` and
`.../paypal/bnpl/index.ts` — this POC only targets `wallet` (see
[POC Scope](../decisions/poc-scope.md#explicitly-out-of-scope) for why `bnpl`, "Pay Later," is
excluded).

## Documentation

**[developer.paypal.com](https://developer.paypal.com/)** — verified live; the same root the
codebase itself references (`packages/partner-ui/src/commons/PartnerUI/types.ts` links into
`developer.paypal.com/sdk/js/reference/`). Covers the JS SDK, Orders v2 API, and the classic
redirect-based Checkout flow this POC's proposed Android flow is modeled on (see [Redirect
Handling](../architecture/redirect-handling.md#proposed-paypal-flow-for-this-poc)).

## Redirection study

**Today:** PayPal wallet **does** call a redirect on success —
`context.redirection?.redirectToUrl(context.session!.success!)`, in both
`paypal/wallet/index.ts` (line ~387) and `paypal/bnpl/index.ts` (line ~326) — but only to
navigate to the **merchant's own** `session.success` page, never to a PayPal-hosted URL. The
actual buyer approval happens earlier, in-page, via the PayPal JS SDK's popup — by the time this
redirect fires, approval and capture are already done; it's a plain "go to the confirmation page"
navigation, structurally identical to what CB or Illicado would do on a successful, no-redirect
payment.

**This confirms what [Redirect Handling](../architecture/redirect-handling.md) already
concluded**: there is no PayPal-hosted-page redirect/return contract anywhere in this codebase to
copy. The `settings.merchant_specifics.integration_mode: "redirection"` flag
(`MethodConfiguration`) is read only by `bnpl`'s `ensureClientCompatibility` (lines ~232-245) as
an eligibility gate — it calls `/init` and checks for `NOT_ELIGIBLE`, it does **not** change how
the approval UI itself renders. Even in `"redirection"` mode, `bnpl` still renders its button
in-page today.

**Hypothetical redirect trigger, specific to PayPal:** the actual buyer-facing approval step
itself, which is exactly what has no page-navigation precedent to reuse on native Android (no
popup equivalent exists). This is the one partner in this POC where "hypothetical redirect" isn't
an edge case on top of an otherwise-synchronous flow (as with CB/Google Pay's 3DS) — it's the
*primary* flow needing a redirect-shaped answer at all, which is why [Redirect
Handling](../architecture/redirect-handling.md#proposed-paypal-flow-for-this-poc) proposes Custom
Tabs + an app-returning deep link as the working design, flagged as needing backend confirmation.

**Implication for Android:** once a PayPal order is approved (via whichever mechanism — Custom
Tabs/deep link, or PayPal's own Android SDK), the follow-up `/validate` call and its own
possible redirect (e.g. a chained 3DS step) still goes through the exact same generic contract as
every other method — PayPal is unusual in *needing* a redirect for approval itself, not in how
it's resolved afterward.

---

## See also

- [Payment Methods — PayPal](../integration/payment-methods.md#paypal-starter)
- [Redirect Handling](../architecture/redirect-handling.md)
- [POC Scope](../decisions/poc-scope.md)
