# Headless checkout architecture

This SDK is a **headless** port of Purse's web `usp-widget` — it owns the *concept of a purse
payment* and lets a merchant build their own checkout UI on top. It mirrors the web packages
(`../../../Development/purse/usp-widget/packages`) rather than the older native drop-in.

## Modules

| Module | Package | Mirrors web | Responsibility |
|---|---|---|---|
| `:core` | `eu.purse.core` | `sdk` + `model` + `common` | Payment API client, environment targets, the `Partner`/`Method`/`VaultVendor`/`CardBrand` enums, `MethodConfiguration`, the raw `LegacyPaymentSession` DTO + its base64 `widget.data` decode, and the typed `ItemPayload`/`ValidationResult`. No orchestration. |
| `:partner-ui` | `eu.purse.partnerui` | `partner-ui` | The plugin lifecycle **contract** only — `PartnerUiPlugin`, `PartnerUiContext`, `PluginRegistry` (with runtime `register`). No concrete plugins; they register themselves. |
| `:headless-checkout` | `eu.purse.checkout` | `headless-checkout` | `PurseHeadlessCheckout`: session decode, the built payment model, the splitter, and the plugin-driven "create payment" pipeline. |
| `:partner-googlepay` | `eu.purse.googlepay` | `partner-ui/plugins/generics/googlepay` | The **Google Pay (Ingenico/Worldline)** plugin: the pure protocol, the Play-Services sheet coordinator, and the 3-D Secure redirect. Registers itself via `GooglePayModule.register()`. |
| `:app` | `eu.purse.checkout.demo` | playground | Smoke test: mint a session via alfred → `init` → print methods/split; drives Google Pay. |

Dependency direction: `app → { headless-checkout, partner-googlepay }`, `partner-googlepay →
headless-checkout → { core, partner-ui }`, `partner-ui → core`.

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

## Secondaries (co-payment)

Secondaries (gift cards, vouchers, loyalty) are a **`:headless-checkout` concern, not a `:partner-ui`
plugin** — matching web. The flow, in `payment.secondary`:

1. `PurseHeadlessCheckout.getSecondaryToken(method, pan, cvv)` validates the card against the
   method's `SecondaryConfig`, `POST /init`s it, and parses a `SecondaryToken` (balance/name/expiry).
2. `applySecondary(token, amount?)` draws it into the split, honouring its `SecondaryTakePolicy`
   (`NONE` = splittable gift card, `MAX` = non-splittable loyalty).
3. At `submitPayment`, secondary splits build their `/validate` payload from the same
   `SecondaryConfig` (no plugin), while primary splits go through a `:partner-ui` plugin.

### API (`PurseHeadlessCheckout`)

```kotlin
val secondaryMethods: List<SecondaryMethod>
fun secondaryConfig(method: SecondaryMethod): SecondaryConfig?
suspend fun getSecondaryToken(method: SecondaryMethod, pan: String, cvv: String? = null): SecondaryToken
fun applySecondary(token: SecondaryToken, amount: BigDecimal? = null)
fun removeSecondary(token: SecondaryToken)
val appliedSecondaries: List<SecondaryToken>
```

- **`getSecondaryToken`** POSTs `/init` and returns the balance **without** modifying the split —
  call it alone to just read a balance. `SecondaryToken` carries `balance`, `totalBalance`, `pan`,
  `cvv`, `name`, `expirationDate`, `takePolicy` (`NONE` splittable / `MAX` non-splittable), `limit`.
  It implements `SplitSource`.
- **`applySecondary`** draws the token into the split, clamped to `min(amount, balance, remaining,
  limit)`; a `MAX`-policy token always takes the full cap. The reactive `remainingAmountToPay`,
  `isPaymentFulfilled` and `amountSplit` update.
- **`secondaryConfig`** exposes the form rules: `requiresCvv(pan)`, `validatePan(pan)`,
  `validateCvv(cvv)`, plus `takePolicy` — `null` when the method has no registered config.
- **Errors:** `getSecondaryToken` throws `PurseCheckoutException.NoSecondaryConfig` /
  `InvalidSecondaryCard` / `SecondaryTokenizationFailed`, or `PaymentApiException` (network).

### Per-partner configs

Configs live in `SecondaryConfigs`, keyed by `"{partner}-{method}"` exactly as web.
Ported: `illicado-giftcard`, `buybox-giftcard`, `maxxing-loyaltycard` (non-splittable, balance from
logs), `ogloba-giftcard`, `mybeezbox-giftcard`, `easy2play-giftcard` (requires CVV), `easy2play-voucher`
(`voucher_number` body, non-splittable). Each declares its CVV/PAN rules, take policy, and token
formatter (name log-key / custom parse). **`ancv-connect` is deferred** — it needs a two-phase
holder→poll `/init` flow. Adding a partner is a new `SecondaryConfig` + registry entry.

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

`:partner-ui` ships **no** implementations — plugins live in their own modules and register
themselves at runtime (`PluginRegistry.register` / `registerFallback`). A `(partner, method)` with no
registered plugin makes `submitPayment` throw `PurseCheckoutException.NoPluginRegistered`.

### Google Pay (`:partner-googlepay`)

The first primary plugin. Because Google Pay is interactive, it splits in two:

- **Protocol** (`GooglePayPlugin : PartnerUiPlugin`, pure JSON): `buildRequest` does `POST /init`,
  parses the gateway config from `plugin_result.logs` (Ingenico / Adyen / default branches), refines
  Ingenico's networks + gateway via a live Worldline product-320 call (`WorldlineProductClient`), and
  builds the Google Pay request JSON; `preValidation` maps the sheet token (from
  `PartnerUiContext.collected`) into the `/validate` payload (`partner_data.session`,
  `payment_data.card.selected_network`).
- **Interactive** (`GooglePayCoordinator`, Play-Services + Activity): drives `isReadyToPay`, the
  payment sheet (`GetPaymentDataResult`), hands the token back via `PurseHeadlessCheckout.setCollected`,
  calls `submitPayment`, and on a `/validate` redirection launches the 3-D Secure
  `RedirectionCheckoutActivity` (Custom Tabs GET / WebView POST), re-querying `sessionTransactions()`
  on return.

Wire it up with `GooglePayModule.register()` at startup.

## Deferred (intentionally not built yet)

Other primary plugins (PayPal, card vaulting), Apple Pay (same shape as Google Pay), the `adyen`/
`stripe` Google Pay gateway builders beyond the `default` fallback, the rendering host (web's
`<purse-partner-ui>` element), the `ancv-connect` two-phase secondary flow, the splitter's
voucher→loyalty→primary autofill middleware, and wallet (saved-token) sessions.
