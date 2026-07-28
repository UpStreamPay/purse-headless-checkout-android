# Headless checkout architecture

This SDK is a **headless** port of Purse's web `usp-widget` — it owns the *concept of a purse
payment* and lets a merchant build their own checkout UI on top. It mirrors the web packages
(`../../../Development/purse/usp-widget/packages`) rather than the older native drop-in.

## Modules

| Module | Package | Mirrors web | Responsibility |
|---|---|---|---|
| `:core` | `eu.purse.core` | `sdk` + `model` + `common` | Payment API client, environment targets, the `Partner`/`Method`/`VaultVendor`/`CardBrand` enums, `MethodConfiguration`, the raw `LegacyPaymentSession` DTO + its base64 `widget.data` decode, and the typed `ItemPayload`/`ValidationResult`. No orchestration. |
| `:partner-ui` | `eu.purse.partnerui` + `.plugins.generics.googlepay` + `.plugins.partners.klarna` + `.plugins.vaults.purse.creditcard` | `partner-ui` (contract + `plugins/generics/googlepay` + `plugins/partners/klarna-installments` + `plugins/vaults/purse/creditcard`) | The plugin lifecycle **contract** — `PartnerUiPlugin`, `PartnerUiContext`, `PluginRegistry` (with runtime `register`/`registerVault`) — **and** the concrete **Google Pay (Ingenico/Worldline)**, **Klarna**, and **Purse vault credit-card** plugins living in the same module: pure protocol plus, respectively, the Play-Services sheet coordinator + 3-D Secure redirect, the native `KlarnaPaymentView` coordinator, and a Compose-first coordinator wrapping the SecureFields Android SDK. Registered by default (`PluginRegistry`'s own `init` block). Each reaches `:headless-checkout` only through the `GooglePayHost` / `KlarnaHost` / `CardHost` interface it defines — implemented by `PurseHeadlessCheckout.googlePayHost` / `.klarnaHost` / `.cardHost` — so the module dependency never points from `:partner-ui` to `:headless-checkout`. |
| `:headless-checkout` | `eu.purse.checkout` | `headless-checkout` | `PurseHeadlessCheckout`: session decode, the built payment model, the splitter, and the plugin-driven "create payment" pipeline. |
| `:app` | `eu.purse.checkout.demo` | playground | Smoke test: mint a session via alfred → `init` → print methods/split; drives Google Pay, Klarna, or a Purse-vaulted card. |

Dependency direction: `app → { headless-checkout, partner-ui }`, `headless-checkout →
{ core, partner-ui }`, `partner-ui → core`.

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

Concrete plugins live inside `:partner-ui` (alongside the contract, like web) and are registered by
default — `PluginRegistry`'s own `init` block calls `GooglePayModule.register()` /
`KlarnaModule.register()`, mirroring web's `getPlugin`, which needs no registration step at all. The
session (checked in `getPlugin` against `protocols`) is the real gate on whether a method is
offered — that's already decided server-side, so no app-side setup call is required for a built-in
plugin to work. `PluginRegistry.disable`/`enable` opt a built-in method out/back in for a specific
app build; `register`/`registerFallback` remain for a plugin that isn't bundled in `:partner-ui`
itself. A `(partner, method)` with no plugin at all (e.g. card/CB, PayPal — not yet built) still
makes `submitPayment` throw `PurseCheckoutException.NoPluginRegistered`.

### Google Pay (`eu.purse.partnerui.plugins.generics.googlepay`, inside `:partner-ui`)

The first primary plugin. Because Google Pay is interactive, it splits in two:

- **Protocol** (`GooglePayPlugin : PartnerUiPlugin`, pure JSON): `buildRequest` does `POST /init`,
  then `GooglePayMapper.parseInitConfig` dispatches `plugin_result.logs` to the matching `partners/`
  builder — `partners.ingenico.Ingenico`, `partners.Adyen`, or `partners.Default` — ports web's
  `plugins/generics/googlepay/partners/{ingenico,adyen,default}.ts` split. Ingenico additionally
  refines its networks + gateway via a live Worldline product-320 call
  (`partners.ingenico.WorldlineProductClient`, ≈ web's `libs/ingenico-onlinepayments`); `preValidation`
  maps the sheet token (from `PartnerUiContext.collected`) into the `/validate` payload
  (`partner_data.session`, `payment_data.card.selected_network`).
- **Interactive** (`GooglePayCoordinator`, Play-Services + Activity): drives `isReadyToPay`, the
  payment sheet (`GetPaymentDataResult`), hands the token back and calls `submitPayment` through the
  `GooglePayHost` seam (implemented by `PurseHeadlessCheckout.googlePayHost` in `:headless-checkout`
  — `GooglePayCoordinator` never imports `:headless-checkout` directly). If `/validate` needs genuine
  3-D Secure, `RedirectionResolver` resolves it (per-item `redirection_json`/`redirection_url`, or a
  global `redirection.url`/`form`) and the coordinator launches `RedirectionCheckoutActivity` — an
  **in-app WebView** for both GET and POST (a system Custom Tab can't POST or be observed navigating),
  which finishes once the shopper reaches either this app's own deep link or the session's own return
  URL (`GooglePayHost.redirectionReturnUrl`, attached as `RedirectAction.returnUrlPrefix` — a "known
  terminal page" hint, in case a genuine multi-hop redirect ends there). `RedirectionResolver`
  deliberately drops two shapes that are never a genuine PSP/bank redirect: web's
  `should_submit_widget` fallback (a "resubmit the widget's own page" signal, meaningless headless),
  and — confirmed against a real response — a plain global `redirection.url` that itself starts with
  the session's own return URL (the widget's *confirmation* page, e.g.
  `…/widgets/confirmation?purse-redirection-data=<jwt>` — a route in `usp-widget/sites/demo`, not a
  bank domain; its JWT decodes to the very `authorization_status` the same `/validate` call already
  returned in `global_status`). Both resolve to `null`: the coordinator reports `global_status`
  directly instead of opening anything. That same JWT also carries `payment_id` — read (unsigned;
  the JWT arrived over the already-authenticated `/validate` response, so no signature check is
  needed just to read a field) via `RedirectionResolver.extractWidgetConfirmationPaymentId`,
  independent of whether the redirect itself gets suppressed, and surfaced through
  `GooglePayCoordinator.Listener.onPaymentId` — purely informational (e.g. prefilling the demo's own
  "Capture" tool below), never required for the payment to resolve.
- **On a genuine redirect's return** (`RedirectionCheckoutResult.Approved`), the coordinator
  deliberately does **not** chase the payment outcome itself — it only reports that the shopper came
  back. A confirmed backend pain point (`docs/decisions/pain-points-recap.md` in the pre-headless
  `master` branch, "Pain point 2"): a real 3-D Secure challenge's own final redirect is constructed
  server-side by the bank/PSP, and can land on a Purse API gateway URL that 401s on the session's own
  client-facing `api_key` — nothing fixable client-side. `master`'s own resolution was the same one
  this repo's demo now ships: an independent, on-demand "Get Transactions" tool
  (`MainActivity.getSessionViaAlfred`) that checks status via alfred rather than auto-chaining another
  API call after every redirect return.

No registration call needed — hand the coordinator a `checkout.googlePayHost(item)` per Google Pay
item; `PluginRegistry` already has `GooglePayModule` registered by default.

### Klarna (`eu.purse.partnerui.plugins.partners.klarna`, inside `:partner-ui`)

The second primary plugin, and the first **dedicated** (not gateway-generic) partner plugin — unlike
Google Pay, Klarna is one PSP (`Partner.Klarna`) offering one method (`Method.Installments`, wire value
`installments`), so `KlarnaModule.register()` (called by `PluginRegistry`'s `init` block by default)
only registers `PluginRegistry.register(Partner.Klarna, Method.Installments)`, no fallback. Ports
web's `plugins/partners/klarna-installments`, using Klarna's native Android
`KlarnaPaymentView` (see
https://docs.klarna.com/acquirer/klarna/mobile-payments/integrate-with-mobile-sdk/android/native-view/)
instead of web's `Klarna.Payments` JS SDK:

- **Protocol** (`KlarnaPlugin : PartnerUiPlugin`, pure JSON): `buildRequest` does `POST /init`,
  `KlarnaMapper.parseInitConfig` extracts `plugin_result.logs.klarna_client_token`, and
  `KlarnaMapper.extractStepId` reads the top-level `step_id` `/init` returns (web's
  `context.dmz.initialStepId`). `preValidation` builds an otherwise-empty `/validate` payload carrying
  only that `step_id` — ported verbatim from web's `klarna_installments.preValidation`. `postValidation`
  is where Klarna's own `authorize()` step actually happens — confirmed against both the plugin
  itself and the orchestrator that calls it (`headless-checkout-validation.ts`'s `preValidateItems` →
  `validate` → `postValidateItems`, run in that order, `postValidateItems` only once `/validate`
  returns `SUCCESS`). `postValidation` calls the live `authorize()` callback `KlarnaCoordinator`
  stashed in `PartnerUiContext.collected` (keyed `KlarnaPlugin.COLLECTED_AUTHORIZE`, alongside the
  `step_id`) — since this plugin has no Klarna SDK types of its own, matching how the sheet token
  reaches Google Pay's `preValidation`. This is why one `checkout.submitPayment()` call is enough to
  both submit and open Klarna's own payment step — no separate coordinator-driven call needed after
  the fact.
- **Interactive** (`KlarnaCoordinator`, the native widget): inflates a `KlarnaPaymentView` into a host
  container view, `initialize(clientToken)` → `load(null)`, and reports "ready" off
  `onLoadPaymentReview`'s `showForm` flag — the native analog of web's `e.show_form` check.
  `KlarnaCoordinator.submit()` is now just `KlarnaHost.submitPayment()` plus resolving whatever
  redirect the (already-authorized) result carries — `authorizeWidget()` is a private suspend
  function `postValidation` calls, not something `submit()` drives directly. Redirects resolve via
  the same `RedirectionResolver` (and shared `RedirectionCheckoutContract`/Activity) Google Pay uses,
  keyed by `KlarnaMapper.METHOD` (`installments`) instead of `googlepay`. A declined authorization
  throws `KlarnaAuthorizationDeclined`, which `submit()` reports as `Listener.onCanceled()` rather
  than `onError()`.
- **Return URL**: `KlarnaPaymentView.returnURL` needs a deep link back into the **same Activity**
  hosting the live widget (unlike Google Pay's 3-D Secure, which uses a self-contained Activity this
  module ships) — so the host app declares its own `singleTask` intent-filter (see the demo's
  `MainActivity` manifest entry) rather than `:partner-ui` shipping one.

No registration call needed — hand the coordinator a `checkout.klarnaHost(item, returnUrl)` per
Klarna item; `PluginRegistry` already has `KlarnaModule` registered by default.

### Card / Purse vault (`eu.purse.partnerui.plugins.vaults.purse.creditcard`, inside `:partner-ui`)

The third primary plugin, and the first **vault** plugin (`PluginRegistry.registerVault`, keyed by
`VaultVendor` rather than `(partner, method)` — any partner's `creditcard` method can be
Purse-vaulted). Ports web's `plugins/vaults/purse/creditcard` bridge plus the generic
`plugins/generics/hostedfields` plugin it plugs into, using the SecureFields Android SDK
(`eu.purse.securefields`, published separately — see SDK-11769) for the actual card fields.

- **Protocol** (`CardPlugin : PartnerUiPlugin`, pure JSON): unlike Google Pay/Klarna, `/init` gates
  nothing about rendering — SecureFields tokenizes independently of it — so `preValidation` does
  both steps itself: `POST /init` for a `step_id` (`CardMapper.parseInit`), then builds the
  `/validate` payload from the vault token `CardCoordinator` already collected via
  `PartnerUiContext.collected`. No separate `buildRequest` phase, unlike the other two plugins.
- **Interactive** (`CardCoordinator`, **Compose-first** — unlike `GooglePayCoordinator`/
  `KlarnaCoordinator`, which are imperative classes driving View-system widgets): a composable,
  `rememberCardCoordinator(host, listener)`, wraps SecureFields' own `rememberSecureFields` (its
  Compose entry point) and exposes the live `SecureFieldsAndroid` instance for the host to render
  with SecureFields' `VaultForm`/`VaultField` composables. `submit()` tokenizes
  (`sdk.submit(SubmitOptions(selectedNetwork = …))`), hands the vault token + bin + resolved
  gateway scheme to `CardHost.submitPayment()` through the same `setCollected` seam Google
  Pay/Klarna use, then resolves any 3-D Secure redirect via the same shared `RedirectionResolver`/
  `RedirectionCheckoutContract` — but launched through Compose's
  `rememberLauncherForActivityResult` rather than `ComponentActivity.registerForActivityResult`,
  which sidesteps the "construct before STARTED" lifecycle caveat the other two coordinators carry.
- **Brand resolution**: the resolved co-badged brand (e.g. a French card that's both CB and VISA)
  is converted between three distinct vocabularies — the session's `CardBrand`
  (`vault.supported_brands`), SecureFields' own `Brand` (its BIN-lookup vocabulary), and the
  Payment API's gateway scheme string (`payment_data.card.selected_network`, e.g.
  `"CARTE_BANCAIRE"`) — `CardCoordinator`'s private `toGatewayScheme()` mirrors SecureFields
  Android's own internal (non-public) `schemeFor()`.

No registration call needed — render `coordinator.sdk` with SecureFields' `VaultForm`/`VaultField`
and call `coordinator.submit()` from your own "Pay" trigger once `coordinator.formValid` is `true`;
`PluginRegistry` already has `CardModule` registered by default (see `PluginRegistry.registerVault`).

## Deferred (intentionally not built yet)

Other primary plugins (PayPal), Apple Pay (same shape as Google Pay), the `adyen`/`stripe` Google
Pay gateway builders beyond the `default` fallback, the rendering host (web's `<purse-partner-ui>`
element) for anything other than the card plugin's own Compose surface, the `ancv-connect`
two-phase secondary flow, the splitter's voucher→loyalty→primary autofill middleware, and wallet
(saved-token) sessions.
