# Payment Methods

How each of CB, Google Pay, PayPal, and Illicado maps to the session's `protocols` object, and how
each one is captured, authorized, and submitted. Read
[Session & Protocols](../architecture/session-and-protocols.md) first for the shared vocabulary
(`protocols`, `MethodConfiguration`, `/init` + `/validate`).

---

## Table of Contents

- [Shared submit flow](#shared-submit-flow)
- [CB (full)](#cb-full)
- [Google Pay (full)](#google-pay-full)
- [PayPal (starter)](#paypal-starter)
- [Illicado (starter)](#illicado-starter)
- [Method list ordering](#method-list-ordering)
- [See also](#see-also)

---

## Shared submit flow

Every method — including Illicado, which has no SDK at all — goes through the same two calls
described in [Session & Protocols](../architecture/session-and-protocols.md#init-and-validate):

```
1. Capture   — method-specific: SecureFields form, Google Pay sheet, PayPal redirect, or a plain PAN/CVV form
2. /init     — POST {paymentApiRoot}/{entityId}/sessions/{sessionId}/init     (partner, method, amount, payment_data)
3. /validate — POST {paymentApiRoot}/{entityId}/sessions/{sessionId}/validate (confirms the attempt; may return a redirect)
```

What differs per method is what goes into `payment_data` at step 2, and whether step 3's response
carries a `redirection` object that needs handling (see
[Redirect Handling](../architecture/redirect-handling.md)).

---

## CB (full)

**`protocols.{partner}.creditcard`**, `vault.vendor: "purse"`. See
[Getting Started](getting-started.md) for the full setup.

1. Render SecureFields for the `creditcard` method's `supported_brands`
   ([Getting Started, Step 6](getting-started.md#step-6--initialize-securefields-for-that-method)).
2. Call `secureFields.submit()`. On `SubmitResult.Success`, you get `vaultFormToken` and a
   `CardInfo` (BIN, detected brands, last four) — never the raw PAN or CVV. This is a call to
   SecureFields' own Vault tokenization endpoint (`/forms/secure-fields`), **separate from** the
   Drop-in's `/init`/`/validate` pair.
3. Call the Drop-in's `POST /init` with `partner`/`method` for this entry and
   `payment_data: { card: { vault_token: vaultFormToken } }` (exact field name to confirm against
   the Orchestration API's card-payment schema for vault-backed tokens — the web codebase's
   PSP-hosted (`pci_proxy`) card flow uses a differently-shaped `payment_data.card` object since it
   never goes through a separate vault tokenization step; the `purse`-vendor shape doesn't have a
   web precedent to copy verbatim since web's own vault-backed card capture uses the same
   vault-front SDK inline, not a separate token hand-off).
4. Call `POST /validate`. A CB payment normally resolves synchronously unless 3DS is triggered, in
   which case handle the response's `redirection` object per
   [Redirect Handling](../architecture/redirect-handling.md).

---

## Google Pay (full)

**`protocols.{partner}.googlepay`** (e.g. under `adyen`). No Purse-specific SDK — this is Google's
own `PaymentsClient` API:

1. Build a Google Pay `PaymentDataRequest` (allowed card networks derived the same way as CB's
   `supported_brands`, plus Google Pay's own gateway token spec — typically
   `gateway: "<the PSP behind this googlepay entry>"`, `gatewayMerchantId` from the session's
   entity/merchant config).
2. Call `PaymentsClient.loadPaymentData()` — this shows Google's native payment sheet; no
   WebView, no redirect *for this step*.
3. On success, `PaymentData.toJson()` contains a PSP-specific payment token (the Google Pay
   token). Submit it as `payment_data` in `POST /init` for `method: "googlepay"`.
4. Call `POST /validate`. **This step is confirmed, not just hypothetically, capable of
   requiring a redirect** — the reference implementation's generic Google Pay plugin
   (`usp-widget/packages/partner-ui/src/plugins/generics/googlepay/index.ts`) already calls the
   redirect resolver on this response today, for a post-tokenization 3DS/SCA step-up. See
   [Partners — Adyen](../partners/adyen.md#redirection-study) for the code citation and what
   this means concretely: the [Redirect Handling](../architecture/redirect-handling.md) flow
   built for PayPal/CB applies to Google Pay's `/validate` call unchanged, and should not be
   treated as an unlikely edge case.

---

## PayPal (starter)

**`protocols.paypal.wallet`**. Web's flow (`usp-widget/packages/partner-ui/src/plugins/partners/paypal/wallet/index.ts`)
uses the PayPal JS SDK's own popup button, which has no Android equivalent — see
[Redirect Handling](../architecture/redirect-handling.md#proposed-paypal-flow-for-this-poc) for
the proposed native flow. Summary:

1. `POST /init` with `partner: "paypal", method: "wallet"` → response's
   `plugin_result.logs.order_id` is the PayPal order ID (same call web makes from its
   `createOrder` callback).
2. Open PayPal's hosted approval page for that order in a Custom Tab (or PayPal's own Android SDK
   — see the open question in [Redirect Handling](../architecture/redirect-handling.md#what-needs-backend-confirmation)).
3. On return to the app, `POST /validate` with `partner: "paypal", method: "wallet"` — same call
   web makes after `onApprove` fires, no PayPal-specific data needed in the request since the
   order was already approved server-side by PayPal at this point.
4. Web's flow ends every successful PayPal payment by navigating to `session.success` — Android
   has no equivalent page to navigate to; instead, resume the Drop-in UI and show the result from
   `/validate`'s response directly.

Registration (saving PayPal for one-click reuse — web's `createBillingAgreement`/
`createVaultSetupToken` path) is out of scope for this POC; see
[POC Scope](../decisions/poc-scope.md#explicitly-out-of-scope).

---

## Illicado (starter)

**`protocols.illicado.giftcard`**, a **secondary** method — no SDK, no iframe, no redirect. Web
handles it entirely in the headless-checkout layer with a generic PAN + CVV form
(`usp-widget/packages/headless-checkout/src/session/payment/mops/secondaries/configs/illicado-config.ts`),
reused as-is here:

```kotlin
fun validateIllicadoPan(pan: String): Boolean =
    Regex("^(?:925000478|925000410)\\d{10}\$").matches(pan)

fun validateIllicadoCvv(cvv: String): Boolean =
    Regex("^\\d{3}\$").matches(cvv)
```

1. Render two plain text fields — PAN and CVV — no SecureFields, no third-party SDK. Validate
   client-side with the regexes above before enabling submit, matching web's `validatePan`/
   `validateCVV`.
2. `POST /init` with `partner: "illicado", method: "giftcard"`,
   `payment_data: { card: { number: pan, csc: cvv } }` — mirrors web's
   `SecondaryPluginInitPayload` shape.
3. `POST /validate` the same way as any other method. No `postValidation`/redirect step exists for
   Illicado on web, and none is expected here.

Because Illicado is a **secondary** method, on web it's presented as a balance-bearing token that
can be combined with a primary method to split the payment (`takePolicy: "none"` = partial
consumption allowed). Full split-payment support is out of scope for this POC (see
[POC Scope](../decisions/poc-scope.md#explicitly-out-of-scope)) — the acceptance criteria only
require Illicado to appear in the method list; treating it as a full, standalone payment (rather
than a split contributor) is an acceptable simplification for "starter" depth.

---

## Method list ordering

Matching web's `getMOPSGroups` (`usp-widget/packages/dropin/src/utils/MeansOfPayment/lists.ts`):
secondary methods (Illicado) render first as an accordion, above the primary methods list (CB,
Google Pay, PayPal). See
[Session & Protocols — Turning protocols into a method list](../architecture/session-and-protocols.md#turning-protocols-into-a-method-list)
for the underlying data model.

---

## See also

- [Getting Started](getting-started.md)
- [Session & Protocols](../architecture/session-and-protocols.md)
- [Redirect Handling](../architecture/redirect-handling.md)
- [POC Scope](../decisions/poc-scope.md)
