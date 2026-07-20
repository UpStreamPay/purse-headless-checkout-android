# Partner: `adyen`

Backs **Google Pay** (`googlepay`) in this POC.

---

## Table of Contents

- [Role in this POC](#role-in-this-poc)
- [Documentation](#documentation)
- [Redirection study](#redirection-study)
- [See also](#see-also)

---

## Role in this POC

The sample session used to write [Session &
Protocols](../architecture/session-and-protocols.md) has `googlepay` under `adyen`, and that's
the partner this page documents — but note the Google Pay integration on web is not
Adyen-specific code. `packages/partner-ui/src/plugins/generics/googlepay/index.ts` is a **generic
plugin**, reused across any partner that offers `googlepay` (Adyen, or others) — there is no
`adyen-googlepay`-specific folder. Treat this page as documenting "Google Pay as currently wired
in the reference implementation," which happens to be under Adyen in practice, not "something
uniquely true of Adyen."

## Documentation

**[docs.adyen.com](https://docs.adyen.com/)** — verified live. Covers Adyen's full API reference,
Google Pay integration guides, and 3D Secure / SCA handling.

## Redirection study

**Today: Google Pay already redirects — its `/validate` step is a confirmed, not just
hypothetical, redirect trigger** (see [Payment Methods — Google
Pay](../integration/payment-methods.md#google-pay-full), which points back here for the
citation). The generic Google Pay plugin explicitly calls the redirect resolver:

- `packages/partner-ui/src/plugins/generics/googlepay/index.ts` (lines ~108-119) calls
  `manageRedirection`, explicitly passing `context.session?.redirection` (the session-level
  fallback field — see [Session & Protocols — Full `LegacyPaymentSession`
  shape](../architecture/session-and-protocols.md#full-legacypaymentsession-shape)) as the
  fallback parameter.
- The Adyen card plugin (`adyen-creditcard/index.ts`, lines ~250-260) does the same — both flows
  route through the exact same generic redirect contract documented in [Redirect
  Handling](../architecture/redirect-handling.md#the-redirect-contract).

**Trigger:** a 3D Secure / SCA step-up challenge after the Google Pay sheet returns its token —
the token itself doesn't guarantee authentication is complete; Adyen can still require a
redirect-based (or, on web, iframe-based) challenge before authorizing.

**Implication for Android:** the *Google Pay sheet* itself doesn't redirect (it's a native
dialog) — but the payment flow as a whole, via Adyen (or whichever partner backs a given
session's `googlepay` entry), already does today. No Google-Pay-specific redirect handling is
needed beyond what [Redirect Handling](../architecture/redirect-handling.md) already specifies —
the Custom Tabs/WebView translation there applies unchanged; this partner just confirms it isn't
an unlikely edge case to skip.

---

## See also

- [Payment Methods — Google Pay](../integration/payment-methods.md#google-pay-full)
- [Redirect Handling](../architecture/redirect-handling.md)
- [Session & Protocols](../architecture/session-and-protocols.md)
