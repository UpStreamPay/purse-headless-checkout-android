# Headless checkout architecture

This SDK is a **headless** port of Purse's web `usp-widget` — it owns the *concept of a purse
payment* and lets a merchant build their own checkout UI on top. It mirrors the web packages
(`../../../Development/purse/usp-widget/packages`) rather than the older native drop-in.

## Modules

| Module | Package | Mirrors web | Responsibility |
|---|---|---|---|
| `:core` | `eu.purse.core` | `sdk` + `model` + `common` | Payment API client, environment targets, the `Partner`/`Method`/`VaultVendor`/`CardBrand` enums, `MethodConfiguration`, the raw `LegacyPaymentSession` DTO + its base64 `widget.data` decode, and the typed `ItemPayload`/`ValidationResult`. No orchestration. |
| `:partner-ui` | `eu.purse.partnerui` | `partner-ui` | The plugin lifecycle **contract** only — `PartnerUiPlugin`, `PartnerUiContext`, `PluginRegistry.getPlugin`. **No concrete plugins yet.** |
| `:headless-checkout` | `eu.purse.checkout` | `headless-checkout` | `PurseHeadlessCheckout`: session decode, the built payment model, the splitter, and the plugin-driven "create payment" pipeline. |
| `:app` | `eu.purse.checkout.demo` | playground | Minimal smoke test: mint a session via alfred → `init` → print methods/split. |

Dependency direction: `app → headless-checkout → { core, partner-ui }`, `partner-ui → core`.

## The purse-payment concept

1. **Session (V2).** A merchant backend hands the app a base64 `widget.data` blob wrapping
   `{ api_key, api_url, environment, entity_id, payment_session }`. `SessionCodec.decode` (in
   `:core`) canonicalizes it to a `LegacyPaymentSession`; there is no separate V2 domain model.
2. **Build.** `PaymentSessionBuilder` turns `protocols.{partner}.{method}` into a means-of-payment
   list (`PaymentItem`: `PrimaryMethod` / `SecondaryMethod`), applying the availability rules.
3. **Split.** `PursePaymentSplitter` models **one primary + N secondaries** in integer cents.
   Secondaries (gift cards) are drawn down first; the primary absorbs `primaryReach` (what's left).
   `PurseHeadlessCheckout` exposes the split reactively as `StateFlow`
   (`remainingAmountToPay`, `isPaymentFulfilled`, `amountSplit`, `paymentMethods`, `sessionState`).
4. **Create payment.** `submitPayment` runs the `PaymentValidator` pipeline: for each split, resolve
   its plugin, `preValidation` → `ValidateItemPayload`, POST all to `/validate`, then dispatch the
   `ValidationResult` back to each plugin's `postValidation`. <a name="init-and-validate"></a>The
   Payment API is `{root}/{entityId}/sessions/{sessionId}/init|validate`.

## Vault settings & merchant_specifics

- Session-level `vault.vendor_front_data` (Purse `tenant_id`, PCI-proxy `merchant_id`) →
  `PaymentSession.vaultsConfiguration`.
- Per-method `configurations.vault` (`supported_brands`, `vendor`, `external_three_ds_enabled`) →
  `PrimaryMethod.supportedBrands`.
- `configurations.settings.merchant_specifics.integration_mode` → `PrimaryMethod.integrationType`
  (`redirection` / `popup`).

Amounts are decimal major-currency units; the splitter works in integer cents (×100) internally.

## Plugin lifecycle (`:partner-ui`)

A plugin integrates one `(partner, method)` pair. `getPlugin` resolves **vault → generic → partner**
(by `configurations.vault.vendor`, then `configurations.template`, then `(partner, method)`). The
lifecycle (`suspend` standing in for web's `Promise` hooks):

```
init:      onSessionSet → ensureClientCompatibility(optional)
react:     onAmountUpdate / onDataUpdate
validate:  validateUi(optional) → preValidation ─ host POSTs /validate ─▶ postValidation
teardown:  destroyClient
```

The registries are intentionally **empty** — the contract is fixed so `:headless-checkout` builds
against it, but no PSP is implemented yet. `submitPayment` therefore throws
`PurseCheckoutException.NoPluginRegistered` until a plugin is registered.

## Deferred (intentionally not built yet)

Concrete plugins (PayPal, Google Pay, card vaulting, redirect), the rendering host (web's
`<purse-partner-ui>` element), secondary-token `/init`, the splitter's voucher→loyalty→primary
autofill middleware, and wallet (saved-token) sessions.
