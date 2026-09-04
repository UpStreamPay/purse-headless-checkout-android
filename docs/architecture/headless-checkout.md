# Headless checkout architecture

This SDK is a **headless** port of Purse's web `usp-widget` — it owns the *concept of a purse
payment* and lets a merchant build their own checkout UI on top. It mirrors the web packages
(`../../../Development/purse/usp-widget/packages`) rather than the older native drop-in.

## Modules

| Module | Package | Mirrors web | Responsibility |
|---|---|---|---|
| `:core` | `eu.purse.core` | `sdk` + `model` + `common` | Payment API client, environment targets, the `Partner`/`Method`/`VaultVendor`/`CardBrand` enums, `MethodConfiguration`, the raw `LegacyPaymentSession` DTO + its base64 `widget.data` decode, and the typed `ItemPayload`/`ValidationResult`. No orchestration. |
| `:partner-ui` | `eu.purse.partnerui` + `.redirect` + `.plugins.generics.googlepay` + `.plugins.generics.redirection` + `.plugins.partners.klarna` + `.plugins.partners.paypal.wallet` + `.plugins.vaults.purse.creditcard` | `partner-ui` (contract + `plugins/generics/googlepay` + `plugins/generics/redirection` + `plugins/partners/klarna-installments` + `plugins/partners/paypal/wallet` + `plugins/vaults/purse/creditcard`) | The plugin lifecycle **contract** — `PartnerUiPlugin`, `PartnerUiContext`, `PluginRegistry` (with runtime `register`/`registerVault`/`registerGeneric`) — the **shared redirect machinery** (`eu.purse.partnerui.redirect`: `RedirectionResolver` + the in-app-WebView return Activity, a sibling of `plugins/` because all five plugins use it) — **and** the concrete **Google Pay (Ingenico/Worldline)**, **Klarna**, **PayPal (wallet)**, **Purse vault credit-card** and **generic redirection template** plugins living in the same module: pure protocol plus, respectively, the Play-Services sheet coordinator, the native `KlarnaPaymentView` coordinator, a coordinator driving PayPal's own browser switch, a Compose-first coordinator wrapping the SecureFields Android SDK, and a coordinator that does nothing but `/init` → `/validate` → follow the redirect. Registered by default (`PluginRegistry`'s own `init` block). Each reaches `:headless-checkout` only through the `GooglePayHost` / `KlarnaHost` / `PaypalHost` / `CardHost` / `RedirectionHost` interface it defines — implemented by `PurseHeadlessCheckout.googlePayHost` / `.klarnaHost` / `.paypalHost` / `.cardHost` / `.redirectionHost` — so the module dependency never points from `:partner-ui` to `:headless-checkout`. |
| `:headless-checkout` | `eu.purse.checkout` | `headless-checkout` | `PurseHeadlessCheckout`: session decode, the built payment model, the splitter, and the plugin-driven "create payment" pipeline. |
| `:app` | `eu.purse.checkout.demo` | playground | Smoke test: mint a session via alfred → `init` → print methods/split; drives Google Pay, PayPal, Klarna, or a Purse-vaulted card. |

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
   A covered split is necessary but not sufficient: an item whose plugin collects a form reports its
   completeness through `setFulfilled(item, …)`, and `isPaymentFulfilled` stays `false` (and
   `submitPayment` throws `ItemNotFulfilled`) while any item in the split reports `false`. Web's
   equivalent is `setAsPrimarySource` refusing an element that hasn't dispatched `fulfilled`.
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
// keeping a temporary gift card (web's temporary token `registration`)
fun registrationOf(token: SecondaryToken): RegistrationState
fun registerSecondary(token: SecondaryToken, registered: Boolean = true, name: String? = null)
// saved gift cards (web's `type: 'token'` secondaries)
val savedSecondaryTokens: StateFlow<List<SavedSecondaryToken>>
fun applySecondary(token: SavedSecondaryToken, amount: BigDecimal? = null)
fun removeSecondary(token: SavedSecondaryToken)
suspend fun editToken(token: SavedSecondaryToken, name: String)
suspend fun deleteToken(token: SavedSecondaryToken)
```

- **Saved gift cards.** A secondary method's `integrated_tokens[]` become `SavedSecondaryToken`
  items (never `PaymentToken`s — that was a bug the parity pass fixed), exposed on
  `savedSecondaryTokens` and excluded from `paymentMethods`. The session carries no balance, so the
  checkout re-`/init`s each one right after construction with its stored `uniqueness_token` +
  `pin_code` (web's `buildSecondaryPaymentToken`), **sequentially on one IO coroutine** — `InitGuard`
  coalesces concurrent `/init`s of the same `(partner, method, amount)`, so two saved cards of one
  issuer fired in parallel would share a single answer. Success fills `balance` /
  `balanceResolved`; a failure leaves the token listed with `TokenInitFailed` (technically disabled,
  like web's `TOKEN_INIT_FAILED`); a session-terminal answer terminates the checkout.
  `applySecondary(saved)` converts through `asSecondaryToken()` (`isSaved = true`) so the splitter
  and `/validate` pipeline see an ordinary `SecondaryToken`.
- **Keeping a temporary gift card.** `SecondaryToken` carries its method's `saveTokenMode`;
  `registerSecondary` records the decision per token id, and `PaymentValidator` adds
  `payment_preferences.registration` + `payment_data.card.token_label` to that item **only when
  registered and not already saved** (web's `buildSecondaryValidateItemPayload`).

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
formatter (name log-key / custom parse).

**`SecondaryConfig` is the public extension point.** The registry is host-extensible —
`SecondaryConfigs.register(partner, method, config)` / `unregister` / `isRegistered` / `reset` —
keyed by wire values so `Partner.Other` / `Method.Other` work: an issuer that isn't in the catalogue
is added by the merchant app, with no SDK change, and the tokenizer, saved-token balance resolution
and `/validate` pipeline resolve it per call like a shipped one. The interface also carries web's
two-phase hooks — `shouldPoll` / `pollForToken` (a `SUCCESS` that only says `PROCESSING` is polled
through the config) and `classifyInitError` (a partner refusal → typed exception) — with no-op
defaults. **`ancv-connect` itself is still not shipped**, but its holder→poll flow is now
expressible through those hooks rather than blocked on an SDK change.

## Plugin lifecycle (`:partner-ui`)

A plugin integrates one `(partner, method)` pair — except a **generic template** plugin, which serves
whichever pairs the session labels with its template. `getPlugin` resolves **vault → generic template
→ `integration_mode = redirection` → partner → method fallback** (by `configurations.vault.vendor`,
then `configurations.template`, then `settings.merchant_specifics.integration_mode`, then
`(partner, method)`, then `method`) — the full chain, with its rationale, is in
[docs/plugins/README.md](../plugins/README.md#resolution--which-plugin-handles-an-item). The
lifecycle (`suspend` standing in for web's `Promise` hooks):

```
init:      onSessionSet → ensureClientCompatibility(optional)
react:     onAmountUpdate / onDataUpdate
validate:  validateUi(optional) → preValidation ─ host POSTs /validate ─▶ postValidation
teardown:  destroyClient
```

Concrete plugins live inside `:partner-ui` (alongside the contract, like web) and are registered by
default — `PluginRegistry`'s own `init` block calls `GooglePayModule.register()` /
`KlarnaModule.register()` / `PaypalModule.register()` / `CardModule.register()` /
`RedirectionModule.register()`, mirroring web's
`getPlugin`, which needs no
registration step at all. The
session (checked in `getPlugin` against `protocols`) is the real gate on whether a method is
offered — that's already decided server-side, so no app-side setup call is required for a built-in
plugin to work. `PluginRegistry.disable`/`enable` opt a built-in method out/back in for a specific
app build; `register`/`registerFallback`/`registerGeneric` remain for a plugin that isn't bundled in
`:partner-ui` itself. A `(partner, method)` with no plugin at all (e.g. a non-Purse-vaulted card/CB)
still makes `submitPayment` throw `PurseCheckoutException.NoPluginRegistered`.

Each shipped plugin has its own reference page — `/init` contract, sequence diagram, quirks, failure
modes, host integration: **[docs/plugins](../plugins/README.md)**. What follows is only the shape of
each, and how they differ from one another.

Everything the four have in common — the protocol/interactive split, the `*Host` seam, `setCollected`,
the shared `RedirectionResolver` + in-app-WebView redirect Activity (`eu.purse.partnerui.redirect`,
a sibling of `plugins/` since no one plugin owns it), and why a returned redirect is
never treated as an outcome — is written up once in
[docs/plugins/README.md](../plugins/README.md#what-every-plugin-has-in-common).

### Google Pay (`plugins.generics.googlepay`) → [full page](../plugins/google-pay.md)

The **generic** family: one protocol reused across gateways, registered for `(ingenico, googlepay)`
plus as the `Method.GooglePay` fallback. `GooglePayPlugin.buildRequest` does `POST /init` and
dispatches `plugin_result.logs` to the matching `partners/` parser (`Ingenico` / `Adyen` / `Default`,
ports web's same three-way split); Ingenico additionally refines networks + gateway through a live
Worldline product-320 call (`WorldlineProductClient`, ≈ web's `libs/ingenico-onlinepayments`).
`GooglePayCoordinator` runs `isReadyToPay`, the sheet, and the 3-D Secure redirect.

It is an **xpay**: the branded button *is* the submit action, so it never becomes a selectable row and
`preValidation` maps the sheet token (`partner_data.session`) + chosen network into `/validate`. The
sheet runs **before** `/validate`.

### Klarna (`plugins.partners.klarna`) → [full page](../plugins/klarna.md)

The **partner** family: one PSP, one method (`(klarna, installments)`), no fallback. Uses Klarna's
native `KlarnaPaymentView` instead of web's `Klarna.Payments` JS SDK. `/init` yields a
`klarna_client_token` (a JWT the coordinator logs decoded — the fastest way to see why the widget
renders a generic info card instead of an installment breakdown) plus a `step_id`, and
`preValidation` sends an otherwise-empty payload carrying only that `step_id`.

Its defining asymmetry: **Klarna's own `authorize()` runs after `/validate`**, inside
`postValidation`, via a live `suspend` callback the coordinator stashed in `PartnerUiContext.collected`
— ported verbatim from web (`preValidateItems` → `validate` → `postValidateItems`, the last only on
success). One `submitPayment()` therefore both submits and opens Klarna's payment step. A declined
authorization throws `KlarnaAuthorizationDeclined`, reported as `onCanceled()` rather than an error.
Its `returnURL` must resume the **same Activity** hosting the live widget, so the host app declares
that deep link itself (see [§7 of the consumer API](../integration/consumer-api.md#7-declaring-your-own-deep-link-for-klarna)).

### PayPal (`plugins.partners.paypal.wallet`) → [full page](../plugins/paypal.md)

The other **partner** family entry: `register(Paypal, Wallet)`, no fallback. Ports web's
`plugins/partners/paypal/wallet`, swapping its in-page JS-SDK popup for the official PayPal Android
SDK's browser switch (`com.paypal.android:paypal-web-payments`). Wallet pay-now only.

It is the one method whose payment step takes the shopper **out of the app and back to a live
plugin**: `/init` creates a PayPal order, `PaypalCheckoutActivity` runs
`PayPalWebCheckoutClient.start()` (the PayPal app when installed, else a Custom Tab), and only once
the buyer approves does `/validate` capture — the native step therefore sits *before* `/validate`,
like Google Pay's, not after it like Klarna's, so there is no `postValidation`.

Three things set it apart:

- **`/init` runs on the button tap, not at prepare.** Creating the PayPal order any earlier would
  leave an orphan order behind whenever the shopper doesn't go through with it, so unlike Klarna
  `prepare` does no network work at all.
- **The return URL needs no configuration, from the integrator or the backend.** The SDK puts its own
  `redirect_uri` in the approval URL, built from the `urlScheme` it is handed; the matching Activity
  and deep link (`${applicationId}.paypal`) ship in `:partner-ui`'s manifest. This is the one place
  the redirect-return gap in [pain-points-recap](../decisions/pain-points-recap.md) turns out not to
  bite.
- **Its client id comes from the session, not `/init`** — `merchant_specifics.client_id`, the inverse
  of Google Pay's gateway config. And a `redirection`-mode PayPal is claimed by the generic template
  instead, since `integration_mode` outranks the exact pair.

### Card / Purse vault (`plugins.vaults.purse.creditcard`) → [full page](../plugins/card-purse-vault.md)

The **vault** family: `registerVault(VaultVendor.PURSE)`, keyed by vault vendor rather than
`(partner, method)` and resolved first, so any partner's Purse-vaulted `creditcard` lands here. Ports
web's `plugins/vaults/purse/creditcard` bridge plus the generic `plugins/generics/hostedfields` plugin
it plugs into, over the SecureFields Android SDK (`eu.purse.securefields`, published separately —
SDK-11769). Alone among the three it is **Compose-first**: `rememberCardCoordinator` owns the
SecureFields instance and the host renders it with `VaultForm`/`VaultField`.

Two things set it apart:

- **No `buildRequest`.** Nothing about rendering depends on `/init` (unlike Google Pay's gateway config
  or Klarna's client token), so `preValidation` does both steps itself — `POST /init` for a `step_id`,
  then the `/validate` payload built from the vault token the coordinator collected. Web makes the same
  two calls in the same order, just earlier (at `onSessionSet`).
- **It gates fulfillment.** SecureFields owns the form state web models in `final-form` and emits it as
  `onFormValid(hasErrors)`, so the coordinator reports `false` through `CardHost.setFulfilled` at mount,
  mirrors every change, and refuses to tokenize an invalid form — which is what keeps
  `isPaymentFulfilled` `false` and `submitPayment` throwing `ItemNotFulfilled` until the card is
  actually filled in (see the split step above). `CardPlugin.validateUi` is the last line of defence:
  no collected vault token means the form was never successfully submitted.

### Redirection (`plugins.generics.redirection`) → [full page](../plugins/redirection.md)

The **generic template** family, and the first plugin here keyed purely off the session:
`registerGeneric("redirection", …)` claims every `(partner, method)` whose `configurations.template`
says `redirection` — plus, mirroring web's `getPartnerPlugin`, any whose
`merchant_specifics.integration_mode` does, even ahead of a dedicated plugin for that pair. So
`RedirectionPlugin` hardcodes neither `partner` nor `method`; it takes both from whatever the session
resolved, which is what lets one registration serve Fintecture, Bizum, Oney, Sequra, an ACI
`creditcard`, a `redirection`-mode PayPal `wallet`, and anything else the merchant enables without a
line of code naming it.

There is **no native step and no form**: `/init` yields only a `step_id`, `preValidation` sends that
plus a `partner_data.browser` block (ported field-for-field from web's `getBrowserData`, but describing
the redirect WebView that will actually render the challenge), and the PSP's own hosted page *is* the
payment step. Consequently a `/validate` carrying **no** redirect — the happy path for every other
plugin — means here that the PSP never handed over a page; the coordinator logs it and says so in its
summary rather than reporting a silent success.

## Shipped since the POC (web-parity work)

The MVP-parity pass added: suspensions (`stable=false` → `METHOD_UNSTABLE`) and the full
disabled-state classification sets; the server-clock expiry watchdog (`GET /datetime`);
`TERMINATED` detection on terminal API answers; the `/init` retry/coalescing guard (web's
`initGuard`); typed error codes (`PurseErrorCode`) on every checkout exception; the live plugin
event system (`PluginEvent` sealed class, dispatched by every coordinator, reacted to by the
checkout, re-exposed on `pluginEvents`); driven lifecycle hooks (`onSessionSet`, `onAmountUpdate`,
`destroyClient`, `ensureClientCompatibility`); full monitoring parity (`CheckoutEventBus`, all 40
codes, batched to the same `/widget/headless_checkout` route as web in web's envelope
`{event, instanceId, entityId, paymentSessionId, version, wrapper, env}` plus `platform: "android"`
— the top-level field the native SecureFields loggers already send, and the one to filter a
platform on; `wrapper` keeps web's meaning and is `direct` — flushed on app-background); wallet / saved cards
(`wallet_session`, `paymentTokens`, pay-with-token, rename/delete/favorite, `SaveTokenMode`
registration incl. `payment_preferences.registration` + `card.token_label`); saved gift cards
(`SavedSecondaryToken` / `savedSecondaryTokens`, background balance `/init`, apply/remove,
rename/delete), keeping a temporary gift card (`registerSecondary`, registration + `token_label` on
its `/validate` item) and host-registrable issuer configs (`SecondaryConfigs.register`,
`pollForToken` / `classifyInitError` hooks); merchant hooks
(`onBeforeValidate` / `onAfterValidate` / `onRedirection` — the last is web's `redirectionHandler`,
threaded to every coordinator through the `RedirectionDelegate` seam on its host; a throwing hook
surfaces as a typed `HookFailed` without moving the split or the session state); locale-aware
overridable card-form texts (`CardFormTexts`, one `FieldTexts` per field: label / placeholder / help
text / required and format errors, web's `hostedForm` translations as defaults) + a `PurseTheme` with
web's scopes (`global` / `input` / `label` / `helperText`, `:focus` / `:invalid` / `:valid` variants)
and web's fallback chain, applied to SecureFields' `VaultStyles` **and** to the SDK's own
`PurseCardForm` (labels, help texts, error messages around the secure fields — what
`PaymentElementUi` renders for a card); session refresh (`setSession` — web's `setSession`: a
primary whose `protocols[partner][method]` is deep-equal survives with its collected data,
fulfilment gate, registration, live plugin and — beyond web — its place as primary; the rest is
torn down and gets a new `itemGenerations` generation so `rememberPaymentElement` rebuilds it;
applied gift cards are dropped; the watchdog restarts; `paymentSession` emits the swap); CDN method
icons (`PurseAssets`); typed
payment confirmation (`paymentStatus()` / `awaitPaymentOutcome()` over `sessionTransactions()`);
and a hardened redirect WebView (progress, first-page watchdog, network-failure surfacing,
process-death safety); BNPL loan simulation (`PrimaryMethod.simulable` off the `simulable` label,
`simulateLoan` / `simulateLoans` over the entity-scoped `POST /simulation/…` endpoints, `LoanProduct` /
`LoanSimulation` mapped as web's `mapPluginResultToSimulation` — counts and amounts, no per-instalment
dates — and the `METHOD_IS_NOT_SIMULABLE` / `INVALID_LOAN_SIMULATION` codes).

Since then (SDK-12216): **CVV re-entry on a saved card** — `PaymentToken.requiresCvv` (the inverse
of web's no-CVV gates) arms the per-item fulfillment gate at `setPrimaryToken`, `cardHost(token)`
puts the card coordinator in `CardCoordinator.Mode.TOKEN_CVV`, which renders a CVV-only
SecureFields form (`securefields-android` ≥ 1.4.0 omits the `card` object when no PAN field is
mounted), and the resulting `vault_vendor_token_id` rides alongside `payment_data.token` on
`/validate` exactly like web's `submitForResult` over a token. A token behind the gates
(`pay_token_no_cvv` / network token) runs in `Mode.TOKEN_NO_CVV`: no form, payable at selection.
`rememberPaymentElement(checkout, token)` wraps both behind the element shape.

Since then: **saved PayPal accounts** — a PayPal `integrated_tokens[]` row surfaces as a
`PaymentToken` too, and a CVV being a card-form concept, `PaymentToken.isVaultedCard` (the method's
`vault.vendor ≠ none`, i.e. `PluginRegistry`'s vault step resolves a card plugin) now gates the whole
CVV question: a non-card token's `requiresCvv` is always `false`, `setPrimaryToken` arms no gate, and
it is payable at selection — web's `hasUI()` returns `false` for a paypal or redirection-template
token before any no-CVV gate is consulted. `PaypalPlugin.preValidation` with a `token` runs the
pay-now `/init` itself and sends `payment_data.token` + the init's `step_id` (web's token-mode
`initSession` + `patchItemPayloadWithToken`); `PaypalCoordinator.submit` on a `paypalHost(token)`
skips the approval and captures straight away; `rememberPaymentElement(checkout, token)` resolves the
paypal (and redirection) plugin for a token with `hasUi = false`. `PayToken.patchPaymentData` is the
one shared `patchItemPayloadWithToken` the card, redirection and PayPal plugins now all use.

## Deferred (intentionally not built yet)

PayPal BNPL ("Pay Later") and PayPal vaulting — *creating* a PayPal token (registration); paying with
an already-vaulted one ships (the wallet pay-now plugin ships too; see
[docs/plugins/paypal.md](../plugins/paypal.md)), Apple Pay (same shape as Google Pay), the
`adyen`/`stripe` Google
Pay gateway builders beyond the `default` fallback, the `ancv-connect`
two-phase secondary flow, the splitter's voucher→loyalty→primary autofill middleware, and the
`pci_proxy` vault vendor (only the `purse` vault resolves a card plugin — so a `pci_proxy`-vaulted
saved token that requires a CVV has no form to collect it on Android yet).

The rendering host (web's `<purse-partner-ui>` element) is **no longer deferred**: the unified
payment element (`eu.purse.checkout.element.rememberPaymentElement` + `PaymentElementUi`) is its
Compose analog, dispatching to each plugin's rendered surface — the SecureFields card form, the
official Google Pay / PayPal buttons, Klarna's native widget — behind one shape and one
`PaymentElementListener` contract, with launchers and `prepare` wired automatically.
