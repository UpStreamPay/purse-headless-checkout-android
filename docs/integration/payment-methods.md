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
own `PaymentsClient` API.

> **Implemented** in `:partner-ui` (`eu.purse.dropin.partnerui.generic.googlepay`, a **generic**
> package reused across any partner offering `googlepay` — mirrors web's `plugins/generics/googlepay`)
> — see [Partner: adyen](../partners/adyen.md#implemented-android). Summary of the native flow,
> which turns out to mirror web's `getGooglePayConfiguration`/`onButtonClicked` (and, structurally,
> PayPal wallet's own `/init`-then-approve-then-`/validate` shape) more closely than the order
> below originally assumed:

1. `POST /init` with `partner: "<the googlepay entry's partner, e.g. adyen>", method: "googlepay"`
   (`GooglePayMapper.buildInitPayload`) — same call web makes from `getGooglePayConfiguration`.
   The response's `plugin_result.logs.payment_methods` (Adyen; a JSON-encoded array — ports
   `partners/adyen.ts`) or `logs.payment_method_configuration` (generic fallback; ports
   `partners/default.ts`) carries the allowed card networks and Google Pay's own gateway token
   spec — `gateway`, `gatewayMerchantId`, optional `merchantId`/`merchantName`. **This is the
   Google Pay analogue of PayPal's `order_id`: the gateway config comes from the backend's `/init`
   response, not a session-level `merchant_specifics` field** (unlike PayPal's `client_id`).
2. Build a Google Pay `PaymentDataRequest` from that config plus the session's amount/currency
   (`GooglePayMapper.buildPaymentDataRequestJson`) and call `PaymentsClient.loadPaymentData()`
   (`GooglePayClient`) — this shows Google's native payment sheet; no WebView, no redirect *for
   this step*. The result is resolved through Google's own `TaskResultContracts.GetPaymentDataResult`
   `ActivityResultContract` — no custom return Activity, unlike PayPal's browser-switch.
3. On success, `PaymentData.toJson()`'s `paymentMethodData.tokenizationData.token` (plus the
   selected `cardNetwork`) is submitted as `partner_data.session`/`payment_data.card` in
   `POST /validate` for `method: "googlepay"` (`GooglePayMapper.buildValidatePayload`/
   `DropinAndroid.validateGooglePay`) — ports web's `preValidation`/`getAdyenSession`.
4. **This step is confirmed, not just hypothetically, capable of requiring a redirect** — the
   reference implementation's generic Google Pay plugin
   (`usp-widget/packages/partner-ui/src/plugins/generics/googlepay/index.ts`) already calls the
   redirect resolver on this response today, for a post-tokenization 3DS/SCA step-up. See
   [Partners — Adyen](../partners/adyen.md#redirection-study) for the code citation and what
   this means concretely: the [Redirect Handling](../architecture/redirect-handling.md) flow
   built for PayPal/CB applies to Google Pay's `/validate` call unchanged, and should not be
   treated as an unlikely edge case. This POC surfaces the chained redirect
   (`GooglePayValidationResult.redirectionUrl`) without following it — the same starter-depth
   limitation as PayPal's `WalletValidationResult`, since no generic redirect handler is built yet.

---

## PayPal (starter)

**`protocols.paypal.wallet`**. Web's flow (`usp-widget/packages/partner-ui/src/plugins/partners/paypal/wallet/index.ts`)
uses the PayPal JS SDK's own popup button, which has no Android equivalent.

> **Implemented** in `:partner-ui` via the official PayPal Android SDK — see
> [Partner: paypal](../partners/paypal.md#implemented-android). Summary of the native flow:

1. `POST /init` with `partner: "paypal", method: "wallet"` → response's
   `plugin_result.logs.order_id` is the PayPal order ID (same call web makes from its
   `createOrder` callback).
2. Launch PayPal's approval for that order via the PayPal Android SDK's `PayPalWebCheckoutClient`
   (`:partner-ui`'s `PaypalCheckoutActivity`), which owns the browser-switch and the return.
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
