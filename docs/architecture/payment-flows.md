# Payment Flow Diagrams

Sequence diagrams for the four methods this POC handles — CB, Google Pay, PayPal, Illicado — see
[Payment Methods](../integration/payment-methods.md) for the prose version of each and [Redirect
Handling](redirect-handling.md) for the redirect contract each diagram's dashed `alt` branch
refers to. Solid arrows are implemented calls; the `alt`/`note` branches marked **not yet
followed** are surfaced today ([`WalletValidationResult`](../../partner-ui/src/main/kotlin/eu/purse/dropin/partnerui/partner/paypal/wallet/WalletValidationResult.kt),
[`GooglePayValidationResult`](../../partner-ui/src/main/kotlin/eu/purse/dropin/partnerui/generic/googlepay/GooglePayValidationResult.kt))
but not acted on — see [Pain Points Recap](../decisions/pain-points-recap.md).

---

## CB (`purse`/`creditcard`, via SecureFields Android)

```mermaid
sequenceDiagram
    actor Shopper
    participant App as Drop-in (:app)
    participant SF as SecureFields Android SDK
    participant Vault as Purse Vault API
    participant PayAPI as Payment API

    Shopper->>App: Enter card fields
    App->>SF: secureFields.submit()
    SF->>Vault: POST /forms/secure-fields (tokenize)
    Vault-->>SF: vaultFormToken + CardInfo (bin, brands)
    SF-->>App: SubmitResult.Success
    App->>PayAPI: POST /init (payment_data.vault_vendor)
    PayAPI-->>App: step_id
    App->>PayAPI: POST /validate (payment_data.vault_vendor_token_id, card.bin/selected_network, step_id)
    PayAPI-->>App: global_status, redirection?

    alt 3DS step-up required
        note over App,SF: Planned — SecureFields' own upcoming<br/>3DS module owns the challenge natively,<br/>not a generic WebView/Custom Tab
        App->>SF: hand off 3DS challenge
        SF-->>App: challenge result
        App->>PayAPI: POST /validate (resubmit)
        PayAPI-->>App: global_status
    end
```

**Status:** implemented end to end — capture (SecureFields tokenization) then `/init`+`/validate`
via `CardMapper`, mirroring usp-widget's `purse` vault bridge (see [Payment Methods —
CB](../integration/payment-methods.md#cb-full)). The `vault_vendor_token_id` payload shape (a
top-level `payment_data` field, not nested under `card`) is confirmed against
`purseCreditCardBridge.submitForResult` in `usp-widget/packages/partner-ui/src/plugins/vaults/purse/creditcard/bridge.ts` —
no longer a guess, see [Pain Points Recap, point 9](../decisions/pain-points-recap.md).

---

## Google Pay (`{partner}/googlepay`, e.g. `adyen`)

```mermaid
sequenceDiagram
    actor Shopper
    participant App as Drop-in (:app)
    participant GPay as Google PaymentsClient
    participant PayAPI as Payment API
    participant Adyen as Adyen (server-side)

    App->>PayAPI: POST /init (partner, method=googlepay)
    PayAPI->>Adyen: resolve gateway config
    Adyen-->>PayAPI: gatewayMerchantId, allowed networks
    PayAPI-->>App: plugin_result.logs (gateway config)
    App->>GPay: loadPaymentData(PaymentDataRequest)
    GPay-->>Shopper: native Google Pay sheet
    Shopper->>GPay: pick card, authorize
    GPay-->>App: PaymentData (tokenizationData.token)
    App->>PayAPI: POST /validate (token, selected_network)
    PayAPI->>Adyen: capture
    Adyen-->>PayAPI: result

    alt 3DS / SCA step-up (confirmed real trigger)
        PayAPI-->>App: redirection.url
        note over App: Not yet followed — Adyen's own Android<br/>RedirectComponent is the intended native answer,<br/>not a generic Custom Tab/WebView
        App->>PayAPI: POST /validate (post-challenge, planned)
    else success
        PayAPI-->>App: global_status = SUCCESS
    end
```

**Status:** implemented end-to-end for the non-3DS path (see [Partner:
adyen](../partners/adyen.md#implemented-android)). This is the one method in scope with a
**confirmed, not hypothetical**, live redirect trigger on web today. `DropinAndroid.getSessionTransactions()`
(`GET /sessions/{sessionId}`, Purse's own published re-query-as-source-of-truth pattern) exists in
the SDK but isn't auto-fired after this flow — see [Session &
Protocols](session-and-protocols.md#init-and-validate) for the standalone alfred debug tool instead.

---

## PayPal (`paypal`/`wallet`, via the official PayPal Android SDK)

```mermaid
sequenceDiagram
    actor Shopper
    participant App as Drop-in (:app)
    participant PP as PaypalCheckoutActivity<br/>(PayPalWebCheckoutClient)
    participant PayAPI as Payment API
    participant Paypal as PayPal (Orders API)

    App->>PayAPI: POST /init (partner=paypal, method=wallet)
    PayAPI->>Paypal: create order (server-side)
    Paypal-->>PayAPI: order_id
    PayAPI-->>App: order_id, step_id
    App->>PP: launch(orderId)
    PP->>Paypal: browser-switch (Custom Tab / PayPal app)
    Shopper->>Paypal: approve
    Paypal-->>PP: return via app-scheme deep link
    PP-->>App: approved (orderId, payerId)
    App->>PayAPI: POST /validate (step_id)
    PayAPI->>Paypal: capture (server-side)
    PayAPI-->>App: global_status

    alt chained 3DS (rare, not yet observed)
        PayAPI-->>App: redirection.url
        note over App: Surfaced, not followed
    end
```

**Status:** implemented end-to-end on a device against a PayPal-enabled sandbox order — see
[Partner: paypal](../partners/paypal.md#implemented-android). Whether the backend's order creation
actually threads an app-return target through (vs. a fixed merchant web URL) is unconfirmed — [Pain
Points Recap, point 4](../decisions/pain-points-recap.md). `DropinAndroid.getSessionTransactions()`
(`GET /sessions/{sessionId}`, Purse's own published re-query-as-source-of-truth pattern) exists in
the SDK but isn't auto-fired after this flow — see [Session &
Protocols](session-and-protocols.md#init-and-validate) for the standalone alfred debug tool instead.

---

## Illicado (`illicado`/`giftcard`, secondary — no SDK, no redirect)

```mermaid
sequenceDiagram
    actor Shopper
    participant App as Drop-in (:app)
    participant PayAPI as Payment API
    participant Illicado as Illicado (intermediary, opaque)

    Shopper->>App: Enter PAN + CVV
    App->>App: validatePan / validateCvv (client-side regex)
    App->>PayAPI: POST /init (payment_data.card: number, csc)
    PayAPI->>Illicado: resolve balance (server-side)
    Illicado-->>PayAPI: balance, name, expiry
    PayAPI-->>App: SecondaryToken
    App->>PayAPI: POST /validate
    PayAPI-->>App: global_status
```

**Status:** `/init` (tokenization) works today; no redirect code path exists anywhere in the
reference implementation for this method, and none is expected — but that's an acknowledged blind
spot behind the Illicado intermediary, not a confirmed absence — see [Partner:
illicado](../partners/illicado.md#redirection-study).

---

## See also

- [Payment Methods](../integration/payment-methods.md)
- [Redirect Handling](redirect-handling.md)
- [Pain Points Recap](../decisions/pain-points-recap.md)
