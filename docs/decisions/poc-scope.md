# POC Scope

What [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039) asks for, and how depth is
split across the four payment methods.

---

## Table of Contents

- [Acceptance criteria (from the ticket)](#acceptance-criteria-from-the-ticket)
- [Depth per payment method](#depth-per-payment-method)
- [Why CB is "full" and PayPal/Illicado are "starter"](#why-cb-is-full-and-paypalillicado-are-starter)
- [Explicitly out of scope](#explicitly-out-of-scope)
- [Open question carried from the ticket](#open-question-carried-from-the-ticket)
- [See also](#see-also)

---

## Acceptance criteria (from the ticket)

> Une app Android de démo initialise une session Purse et affiche les méthodes CB / Google Pay /
> PayPal, Illicado, disponibles. Paiement CB abouti de bout en bout via le SDK Secure Fields
> Android existant. Paiement Google Pay abouti de bout en bout. Paiement PayPal abouti de bout en
> bout.

In English: the demo app must (1) initialize a Purse session and display which of CB / Google Pay
/ PayPal / Illicado are available for that session, (2) complete a CB payment end-to-end through
the existing SecureFields Android SDK, (3) complete a Google Pay payment end-to-end, and
(4) complete a PayPal payment end-to-end. Illicado must appear in the method list; the ticket
does not require an end-to-end Illicado payment.

The goal per the ticket's own framing is architectural validation — deciding whether a native
Drop-in is feasible — not a production-ready release.

---

## Depth per payment method

| Method | `protocols` key | Native mechanism | Depth | Redirect involved? |
|---|---|---|---|---|
| **CB** | `{partner}.creditcard` (`vault.vendor: "purse"`) | [SecureFields Android SDK](../integration/getting-started.md) (Maven) | **Full** — primary focus of this POC | No — SecureFields tokenizes in-app |
| **Google Pay** | `{partner}.googlepay` | Google Pay API (`PaymentsClient`) — **implemented**, see [Partner: adyen](../partners/adyen.md#implemented-android) | **Full** — required by acceptance criteria | Not for the sheet itself — but `/validate` can chain a 3DS redirect (surfaced, not yet followed) |
| **PayPal** | `paypal.wallet` | Custom Tabs / WebView redirect (see [Redirect Handling](../architecture/redirect-handling.md)) | **Starter** — end-to-end but not hardened | Yes — this is the open architectural question |
| **Illicado** | `illicado.giftcard` | Native PAN + CVV form, no SDK | **Starter** — listed and capturable; full submit is a stretch goal | No |

"Full" means the method is intended to work end-to-end against a real sandbox session using the
production-shaped API calls. "Starter" means the method is wired enough to prove the pattern
(method appears in the list, its form/redirect renders, a submit attempt reaches the Payment API)
without the hardening a production Drop-in would need — see the sections below.

---

## Why CB is "full" and PayPal/Illicado are "starter"

The ticket's own framing is to validate the SecureFields Android SDK integration first — everything
else exists to prove the Drop-in can host multiple payment method *shapes* (native fields,
platform pay sheet, redirect, plain form) side by side, not to fully productionize each one.

- **CB** is the one method with an existing, Maven-published native SDK
  ([SDK-11769](https://upstreampay.atlassian.net/browse/SDK-11769)) purpose-built for this. It's
  the direct object of the POC's question ("réaliser un POC ... en m'appuyant sur le SDK Secure
  Fields Android existant").
- **Google Pay** has no redirect for its sheet and no Purse-specific SDK to build — it's Google's
  own `PaymentsClient` API returning a token, then the existing generic `/init` + `/validate`
  calls (its `/validate` can still chain a 3DS redirect, same as CB/PayPal — see [Redirect
  Handling](../architecture/redirect-handling.md)). Low incremental cost to take to "full" once CB
  and the session/protocols layer exist — now **implemented**, see [Partner:
  adyen](../partners/adyen.md#implemented-android).
- **PayPal** is the one method that requires a redirect out of the app and back — which is
  exactly the open question the ticket asks about. It gets "starter" depth: enough to exercise
  the redirect mechanism end-to-end, but see
  [Redirect Handling](../architecture/redirect-handling.md) for what's still unconfirmed with the
  backend team.
- **Illicado** is a secondary method (PAN + CVV, no SDK, no redirect — see
  [Payment Methods](../integration/payment-methods.md#illicado-starter)) and only needs to
  *appear* per the acceptance criteria. It's included at starter depth because it's cheap once
  the session/protocols model exists, and it demonstrates the Drop-in can host a secondary method
  alongside primaries.

---

## Explicitly out of scope

Anything present in a real session's `protocols` map that isn't one of the four methods above:

- Other card PSPs / vendors — `vault.vendor: "pci_proxy"` (PSP-hosted fields, e.g. some `aci`
  configurations) is a different integration shape than SecureFields and is not covered by this
  POC. CB support here only targets sessions where the card method's vault vendor is `"purse"`.
- Installment methods (`cb10x`, `cb12x`, `3x`, `6x`, Oney)
- Other secondary methods (vouchers, ANCV, loyalty tokens, buybox/maxxing/ogloba/mybeezbox/easy2play)
- Saved cards / tokens (`wallet_session`, `integrated_tokens`) — "one-click" flows
- Apple Pay (not applicable on Android) and PayPal BNPL ("Pay Later")
- `information_request` sessions (registering a payment method with no charge — see [Session &
  Protocols](../architecture/session-and-protocols.md#information_request-sessions)) — this POC
  assumes every session carries a real, non-zero `amount`
- Split payments (combining a secondary method's balance with a primary method in one payment)
  and the full amount-based enable/disable rule set — see [Session & Protocols — Method
  availability rules](../architecture/session-and-protocols.md#method-availability-rules-disabled-states);
  implementing at least `PaymentFulfilled`/`MinAmountNotMet`/`MaxAmountExceeded` is recommended
  but the rest of that taxonomy (token errors, plugin incompatibility, etc.) is not
- Any monitoring/telemetry equivalent to SecureFields' remote log monitoring for the Drop-in
  orchestration layer itself (SecureFields' own monitoring still applies to the CB path)

---

## Open question carried from the ticket

The ticket lists one open question verbatim: **"comment gérer la redirection (intent)"** — how to
handle redirection via an Android Intent. This is answered as far as it can be from the web
reference implementation in [Redirect Handling](../architecture/redirect-handling.md), which also
states precisely what needs backend-team confirmation before PayPal can be taken past "starter"
depth.

---

## See also

- [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039) — this ticket
- [SDK-11769](https://upstreampay.atlassian.net/browse/SDK-11769) — SecureFields Android SDK
- [SDK-7005](https://upstreampay.atlassian.net/browse/SDK-7005) — prior Android widget SDK (done; historical context, different architecture)
- [Payment Methods](../integration/payment-methods.md)
- [Session & Protocols](../architecture/session-and-protocols.md)
