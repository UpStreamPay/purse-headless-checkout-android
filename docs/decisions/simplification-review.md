# Simplification review (KISS) — 2026-09-04

**Status (2026-09-04): applied on `refactor/kiss-simplification`**, one commit per finding. Kept on
purpose: `PluginRegistry.disable` / `enable` (documented, tested, cheap). Finding 8's headers are in;
the KDoc trim was applied to every file the refactor touched, not yet to the rest.

Context: the team is not native-first, ~36k lines were added in the last month, and the codebase
has started to feel owned by its history rather than by us. This is an inventory of where the
Android SDK is more complicated than it needs to be, ranked by payoff, with the concrete change
for each. Nothing here is applied yet. Baseline before any of it: 273 unit tests green
(`core` 49, `headless-checkout` 126, `partner-ui` 98).

## Where the weight is

| Module | Lines | Comment lines | Code lines | Biggest file |
|---|---|---|---|---|
| `:core` | 1,762 | 23 % | 1,163 | `PaymentApiClient.kt` 180 |
| `:headless-checkout` | 3,852 | 28 % | 2,426 | `PurseHeadlessCheckout.kt` **1,684** |
| `:partner-ui` | 5,211 | 33 % | 2,981 | `CardCoordinator.kt` 484 |
| `:app` (demo) | 2,749 | 18 % | 2,019 | `DemoController.kt` **1,018** |
| `docs/` | 3,697 | | | `recipes.md` 895, `consumer-api.md` 827 |

Web, for comparison: `headless-checkout` is 6.9k lines and `partner-ui` 20.9k, but web ships ~35
plugins to our 5. Per plugin, Android is heavier.

## Findings, ranked

### 1. Five `*Host` interfaces are one interface — high payoff, low risk

`GooglePayHost` and `PaypalHost` are identical. `RedirectionHost` and `KlarnaHost` are subsets.
`CardHost` adds `setFulfilled`, `vaultTenantId`, `supportedBrands`, but the last two are readable
from `buildContext().session` and the item, so they are not host concerns.

Cost today: five interface files, five ~15-line anonymous objects in `PurseHeadlessCheckout`
(`googlePayHost`, `klarnaHost`, `paypalHost`, `redirectionHost`, `cardHost`), and five imports of
`:partner-ui` plugin packages into the checkout class.

Change: one `PluginHost` in `eu.purse.partnerui` with `environment`, `redirectionReturnUrl`,
`buildContext()`, `setCollected()`, `setAsPrimary()`, `setFulfilled()`, `submitPayment()`,
`delegateRedirection()`. One `PurseHeadlessCheckout.host(item)` replaces the five factories.
Klarna's `returnUrl` moves to `KlarnaCoordinator.prepare(host, returnUrl)`.
Expected: about −120 lines, four files deleted, and the checkout no longer names any plugin.

### 2. Five coordinators share 60 % of their body — high payoff, medium risk

Each of `GooglePayCoordinator`, `PaypalCoordinator`, `KlarnaCoordinator`,
`RedirectionCoordinator`, `CardCoordinator` re-implements: a `submitting` flag, a `host` slot,
`dispatch(event)`, `submit()` → `host.submitPayment()` → `resolveOutcome()` (payment-id
extraction, `RedirectionResolver.resolve`, `RedirectDispatcher.dispatch`, listener calls), and
`onRedirectReturn()`. The "Approved only means the shopper came back, not that the payment
succeeded" caveat is written five times.

Change: a `PaymentCoordinator` base class (or a `SubmitFlow` helper) owning submit / resolve /
redirect-return; each coordinator keeps only its native step (sheet, browser switch, Klarna
widget, SecureFields). PayPal's "drop the echoed approval URL" becomes one overridable filter.
Expected: −300 to −400 lines, and one place to fix redirect handling.

### 3. Two ways to build a coordinator, and the demo uses both — high payoff for ownership

Every coordinator has a public constructor that registers Activity-result launchers in `init`
(must run before `STARTED`) **and** a `forCompose` path used by `remember*Coordinator`. The card
coordinator is Compose-only. `DemoController` holds four View-style coordinators, renders the card
through a `ComposeView`, and uses `rememberPaymentElement` for saved tokens: three integration
styles in one demo.

Change: keep `rememberPaymentElement` as the single public surface, delete the View-system
constructors and the `Coordinator.Listener` alias interfaces (five empty sub-interfaces of
`PaymentElementListener`). Rewrite the demo on `rememberPaymentElement` only. The demo then also
proves the public API instead of bypassing it.
Expected: `DemoController` from 1,018 lines to roughly 400, `RememberCoordinators.kt` simplified,
one fewer chapter in the consumer docs.

### 4. `PurseHeadlessCheckout` does ten jobs — medium payoff, medium risk

1,684 lines: session decode and refresh, the split, the method list, wallet-token management
(edit / delete / favorite / delete all, ~200 lines), saved gift cards and their background
balance, registration decisions, plugin lifecycle tracking, five host factories, BNPL simulation
(~80 lines), monitoring installation and background flush (~100 lines in the companion), the
expiry watchdog.

Web splits this across `headless-checkout.ts`, `payment.ts`, `mops/`, `secondaries/`, `splitter/`.

Change, in order of independence: extract `CheckoutMonitoring` (companion `installMonitoring`,
`installBackgroundFlush`, `ApiCallMonitor` wiring), `WalletTokens` (everything needing
`walletClient`), `LoanSimulations` (`simulateLoan(s)`, `requireSimulable`,
`asLoanSimulationFailureOrSelf`). Finding 1 removes the hosts. The class lands near 900 lines and
its remaining job matches web's `headless-checkout.ts`: state, split, submit.

### 5. Per-plugin file explosion — low risk, easy

Per plugin: `Module.kt` (one `register()` function, five files), `Host.kt` (finding 1),
`Request.kt`, `Config.kt` (e.g. `PaypalConfig` is a one-field data class under 10 lines of KDoc),
`Mapper.kt`, `Plugin.kt`, `Coordinator.kt`, plus `Button.kt` / `Activity.kt` / `Contract.kt`
where needed. Web: two or three files per plugin.

Change: inline the five registrations into `PluginRegistry.init` and delete the `*Module` objects;
nest `*Request` / `*Config` data classes inside their `*Plugin` file. Nine files gone.

### 6. Contract members and events nobody uses — low risk, easy

| Member | Overrides | Callers | Action |
|---|---|---|---|
| `PartnerUiPlugin.sources` | 1 | 0 | delete (web loads scripts; Android has none) |
| `PartnerUiPlugin.onDataUpdate` | 0 | 0 | delete |
| `PartnerUiPlugin.vault` | 2 | 0 | delete (the registry already keys on the vendor) |
| `PluginEvent.Fulfilled` | dispatched 0× | | delete (fulfilment travels via `Host.setFulfilled`) |
| `PluginEvent.RequestValidate` | dispatched 0× | | delete (self-submitting flows call `submitPayment` directly) |
| `PluginRegistry.disable` / `enable` | | 0 | keep only if a merchant asked; otherwise delete |

### 7. `rememberPaymentElement` switches on five concrete plugin classes — medium

Two overloads (method, token) each `when (plugin) { is CardPlugin … is GooglePayPlugin … }`, with
the PayPal and Redirection token branches duplicating the method branches. After findings 1–3 the
two overloads collapse into one `rememberPaymentElement(checkout, item: PaymentItem)`. The type
switch stays: it is the one place the SDK maps a plugin to a Compose surface, and that is
acceptable as long as it is the only one.

### 8. Comments: right content, wrong shape — the "ownership" problem

28–33 % of SDK lines are comments, mostly 10–30-line KDoc narratives, plus 3.7k lines of docs.
The web references are all there, but a reader has to read a paragraph to find the pointer. That
is the opposite of what a non-native team needs: a fast way to answer "what is this in web, and
is it the same?".

Change:

- Adopt a fixed three-line header on every SDK file, and cut the narrative below it to what is
  not derivable from the code:
  ```kotlin
  // Web: packages/partner-ui/src/plugins/partners/paypal/wallet/index.ts
  // Same: /init creates the order, /validate captures with the step_id.
  // Differs: native PayPal SDK browser switch instead of the JS popup; /init on tap, not at prepare.
  ```
- Keep long "why" narratives in `docs/plugins/*.md` (they already exist there), not in KDoc twice.
- Use [web-android-map.md](../web-android-map.md) as the single cross-reference, and keep it
  current in the same commit that adds a divergence.

Target: comment share around 15 %, without losing a single web pointer.

### 9. Smaller items

- `PaypalCoordinator.Listener`, `KlarnaCoordinator.Listener`, etc.: empty aliases, delete
  (see finding 3).
- `PaymentValidator.execute`: the `PrimaryMethod` and `PaymentToken` branches are the same code
  with a different context field; merge into one branch that sets `token` when the source is a
  token.
- `PurseHeadlessCheckout.applySession`: token survival is computed by string-building
  `"${partner}-${method}"` to compare with method ids; give `PaymentToken` a `methodId` property.
- `SecondaryConfig.kt` holds the interface, seven configs, the registry and the parser in 281
  lines. Fine for now; split only when `ancv-connect` lands.

## Suggested order

1. Findings 5, 6, 9 (mechanical, one PR, tests unchanged).
2. Finding 1, then 2 (one PR each; coordinator tests cover the shared flow).
3. Finding 3 (demo rewrite, proves the API).
4. Finding 4 (extractions, one PR per class).
5. Finding 8 as a rolling rule, applied file by file while touching them above.

Each step keeps the 273 tests green; none changes the wire protocol.
