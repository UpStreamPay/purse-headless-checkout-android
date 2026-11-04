# Web ↔ Android map

One-page cross-reference between `usp-widget` (web, the reference implementation) and this SDK.
Paths on the web side are under `usp-widget/packages/`. Update this file in the same commit that
adds or removes a divergence.

## Modules

| Web package | Android module |
|---|---|
| `sdk` + `model` + `common` | package `eu.purse.core` |
| `partner-ui` | package `eu.purse.partnerui` in `:headless-checkout` (contract, redirect, card, redirection template) + `:partner-googlepay`, `:partner-klarna`, `:partner-paypal` (optional artifacts, one plugin each) |
| `headless-checkout` | `:headless-checkout` |
| `dropin` (Svelte UI on top of headless) | none; `rememberPaymentElement` is the closest thing |
| playground / `alfred` | `:app` (demo) |

## File-level map

| Web | Android | Same? |
|---|---|---|
| `headless-checkout/src/headless-checkout.ts` | `checkout/PurseHeadlessCheckout.kt` (+ `wallet/WalletTokens.kt`, `simulation/LoanSimulator.kt`, `monitoring/CheckoutMonitoring.kt`) | yes |
| `headless-checkout-validation.ts` (`executePayment`, `preValidateItems`) | `checkout/validation/PaymentValidator.kt` | yes |
| `session/payment/payment.ts` (stores) | `StateFlow`s on `PurseHeadlessCheckout` | Svelte stores → `StateFlow` |
| `session/payment/mops/item-factory/{primaries,secondaries,factories}.ts` | `checkout/session/PaymentSessionBuilder.kt`, `payment/item/PaymentItem.kt` | yes |
| `mops/disabled.ts` | `payment/item/DisabledState.kt`, `MethodAvailability.kt` | yes |
| `mops/secondaries/configs/*.ts` (one file per issuer) | `payment/secondary/SecondaryConfig.kt` (one file) | same keys; `ancv-connect` missing; Android adds `SecondaryConfigs.register` |
| `mops/secondaries/{init,token,validate}-mappers.ts` | `SecondaryConfig.kt`, `SecondaryTokenizer.kt` | yes |
| `splitter/payment-splitter.ts` | `payment/split/PursePaymentSplitter.kt` | yes, integer cents |
| `splitter/middlewares/*` (autofill) | not ported | |
| `payment-element/payment-element.ts` | `checkout/element/PaymentElement.kt` + `partnerui/PaymentCoordinator.kt` and one coordinator per plugin | see divergences |
| none | `partnerui/PluginHost.kt` | the seam through which a coordinator reaches the checkout |
| none (web bundles every plugin) | `partnerui/PartnerModule.kt`, `PartnerArtifacts.kt`, `META-INF/services/eu.purse.partnerui.PartnerModule` in each partner module | optional partner artifacts discovered with `ServiceLoader`; a missing one is named at `init` |
| `monitoring/*`, `monitoringConfigs.ts` | `checkout/monitoring/CheckoutEventBus.kt`, `CheckoutEventCode.kt`, `MonitoringPayloads.kt`, `core/monitoring/*` | web's 40 codes + `METHOD_DISABLED`, envelope + `platform: android` — see [monitoring](integration/monitoring.md) |
| `utils/error/*` | `checkout/PurseCheckoutException.kt`, `core/error/PurseErrorCode.kt` | same codes |
| `sdk/src/API/payment-api.ts` | `core/api/PaymentApiClient.kt` | yes |
| wallet API | `core/api/WalletApiClient.kt` | yes |
| `server-datetime-checker.ts` | `core/datetime/ServerDatetimeChecker.kt`, `DatetimeApiClient.kt` | yes |
| `sdk` `Partner` / `Method` unions | `core/payment/Partner.kt`, `Method.kt` (sealed + `Other`) | yes |
| `LegacyPaymentSession` type + `widget.data` decode | `core/session/LegacyPaymentSession.kt`, `LegacyPaymentSessionParser.kt`, `SessionCodec.kt` | yes |
| `partner-ui/src/commons/Plugin/types.ts` (`Plugin<Context>`) | `partnerui/PartnerUiPlugin.kt` | `Promise` → `suspend` |
| `commons/PartnerUI/types.ts` (`PartnerUIContext`) | `partnerui/PartnerUiContext.kt` | immutable on Android |
| `plugins/index.ts` (`getPlugin`) + `partner_plugins.ts` | `partnerui/PluginRegistry.kt` | static table → registry with the same order |
| `commons/PartnerUI/initGuard.ts` | `core/api/InitGuard.kt` | yes |
| `partner-ui/src/events.ts` (`UnifiedEvent`) | `partnerui/PluginEvent.kt` | trimmed |
| `plugins/utils/noCVV.ts`, `patchItemPayloadWithToken` | `PayToken.requiresCvv`, `PayToken.patchPaymentData` | yes |
| `commons/PartnerUI/theming/*`, `hostedForm` translations | `partnerui/PurseTheme.kt`, `CardFormTexts` | same scopes and fallback chain |
| `plugins/generics/googlepay/*` + `partners/{adyen,ingenico,default}` + `libs/ingenico-onlinepayments` | `plugins/generics/googlepay/*`, `partners/*`, `WorldlineProductClient.kt` (module `:partner-googlepay`, artifact `headless-checkout-googlepay`) | protocol yes; sheet via Play Services |
| `plugins/generics/redirection/*` + `manageRedirection` | `plugins/generics/redirection/*`, `partnerui/redirect/RedirectionResolver.kt` + WebView Activity | yes; return handled in-app |
| `plugins/partners/klarna-installments` | `plugins/partners/klarna/*` (module `:partner-klarna`, artifact `headless-checkout-klarna`) | JS SDK → `KlarnaPaymentView`; optional artifact |
| `plugins/partners/paypal/wallet` | `plugins/partners/paypal/wallet/*` (module `:partner-paypal`, artifact `headless-checkout-paypal`) | JS popup → PayPal Android SDK; optional artifact |
| `plugins/partners/paypal/bnpl` `ensureClientCompatibility` + `getEligibilityCheck` (`item-factory/primaries.ts`) | `RedirectionPlugin.ensureClientCompatibility` + `PurseHeadlessCheckout.runEligibilityChecks` | probe only; the popup plugin is not ported |
| `plugins/vaults/purse/creditcard` + `generics/hostedfields` | `plugins/vaults/purse/creditcard/*` | iframes → SecureFields Android |

## Vocabulary

| Web | Android |
|---|---|
| `PurseHeadlessCheckout.init(widgetData)` | `PurseHeadlessCheckout.init(widgetData, lockedEnvironment, hooks, options)` |
| `mops` store | `paymentMethods` (primaries + secondaries), `paymentTokens`, `savedSecondaryTokens` |
| `type: 'token'` secondary in `mops` | `SavedSecondaryToken` on its own flow |
| `method.setAsPrimarySource()` | `setPrimary(method)` / `setPrimaryToken(token)` |
| `element.isFulfilled()` refused by `setAsPrimarySource` | `isPaymentFulfilled` flow + `setFulfilled(item, …)` gate |
| `getPaymentElement()`, `element.appendTo()`, `hasUI()` | `rememberPaymentElement()`, `PaymentElementUi()`, `PaymentElement.hasUi` |
| `element.on('ready' …)` | `PaymentElementListener.onReady` and `pluginEvents` flow |
| `redirectionHandler` | `PurseCheckoutHooks.onRedirection` (through `RedirectionDelegate`) |
| `context.dmz.initialStepId` | `PartnerUiContext.collected[COLLECTED_STEP_ID]` |
| `temporaryToken.register()` | `registerSecondary(token, …)` |
| `withEvents(plugin)` proxy | `PartnerUiContext.dispatchEvent` + `PurseHeadlessCheckout.onPluginEvent` |
| `teardown()` | `teardown()` |

## Divergences and why

| Topic | Web | Android | Why |
|---|---|---|---|
| Plugin activation | static lookup table | `PluginRegistry` with all built-ins registered in `init` | same effect; registry kept for `Partner.Other` and host-registered plugins |
| Google Pay resolution | template `googlepay` | pair `(ingenico, googlepay)` + method fallback | historical; harmless for today's sessions |
| Interactive step | plugin renders into the DOM and owns its client | a `PaymentCoordinator` subclass owns the native SDK; the base owns `/validate`, redirects and the return; the plugin stays pure protocol | Activity lifecycle and result contracts have no web analogue |
| Plugin ↔ checkout access | plugin holds the checkout context | one `PluginHost` interface, implemented by `PurseHeadlessCheckout.host(item)` | keeps `eu.purse.partnerui` from depending on `eu.purse.checkout` |
| Fulfilment | `setAsPrimarySource` refuses an unfulfilled element | reactive `isPaymentFulfilled`, selectable before the form is filled | fits `StateFlow` UIs |
| Card `/init` timing | at `onSessionSet` | inside `preValidation` | nothing about rendering depends on it on Android |
| PayPal `/init` timing | at render | on button tap | avoids orphan PayPal orders |
| PayPal bnpl eligibility probe | on the dedicated `paypal/bnpl` plugin, looked up by pair whatever plugin renders | on the generic `RedirectionPlugin`, the plugin that serves bnpl (redirection mode only) | no dedicated bnpl plugin on Android; the probe needs no PayPal SDK, so no artifact either (iOS does the same) |
| Eligibility probes on a spent session | run at every primary build | skipped when the session is not `READY` or its `/validate` was consumed, so a restore after a submit never re-`/init`s | web has no restore; SDK-12461 (never `/init` a spent session) |
| PayPal `redirection.url` in `/validate` | never followed | explicitly dropped when it is a PayPal approval URL | it is the web popup URL echoed back; following it re-opens PayPal |
| Klarna return | page URL | host app must declare a `singleTask` deep link | `KlarnaPaymentView` resumes the same Activity |
| Payment confirmation after redirect | merchant success page | `paymentStatus()` / `awaitPaymentOutcome()` polling | the app returns to the launching screen |
| `setSession` survivors | resets the split | keeps an unchanged primary selected | AC of US-24 |
| Second submit | latch posts `HEADLESS_CHECKOUT_SUBMIT_BLOCKED` and returns silently | same latch, atomic, **throws** `SubmitBlocked` (`SUBMIT_BLOCKED`) and re-checks expiry first | a merchant needs a code to react to, not a silent no-op (SDK-12218) |
| `SUBMITTING` disabled state | declared, never set | overlaid on every selectable item while `submitPayment` runs | US-22 |
| `teardown()` | unsubscribes the event bus; instance stays usable | releases everything, `isTornDown`, later calls throw `CheckoutTornDown` | US-25 |
| Redirect WebView | page navigation | `https`-only navigation, file/content access off, return intent checked against the app's own scheme | US-08 / US-09 |
| Process death during a redirect | n/a | recreation reports a *return* (check the session), never a cancel | US-27 |
| Saved gift cards | listed in `mops` | `savedSecondaryTokens` flow, balances re-`/init`ed sequentially | `InitGuard` would coalesce parallel `/init`s of one issuer |
| Monitoring envelope | `wrapper`, `env`, … | same plus `platform: "android"` | matches the native vault SDKs |
| `HEADLESS_CHECKOUT_CREATE` payload | `user_agent`, `screen` | same plus `device` (OS version, API level, manufacturer, model) | what RUN parses out of a web user agent (SDK-12189) |
| Amounts in telemetry | `SET_SESSION.amount`, `SECONDARY_TOKEN_TAKE.amount` | never — `nb_methods`, `partial` | SDK-12220 AC: no raw amount in any event |
| A method's disabled state | only `pluginIsIncompatibleWithClient` and partner errors, through `PARTNER_UI_EVENT` | `HEADLESS_CHECKOUT_METHOD_DISABLED` on every transition (code, filtered, recoverable), plus web's partner-UI event for an incompatible plugin | the suspension model as events (SDK-12220) |
| `PAYMENT_ERROR` on a thrown error | `message`, `stack` | plus `code`, `api_path`, `api_status`, `api_error`, `outcome_unknown` | a minified Android stack says nothing; the codes do |
| `instanceId` on the envelope | one per page load (`window.__PURSE_INSTANCE_ID__`) | one per checkout, exposed as `instanceId`, saved with `saveState` and kept by `restore`; CREATE says `restored: true` | a shopper's journey survives process death as one instance |
| SDK `version` on the envelope | `package.json` version | `SdkVersion.VALUE`, generated from the release tag at build time | `0.0.0-SNAPSHOT` for a local build |
| UI toolkit | DOM | Compose only: `rememberPaymentElement`, `remember*Coordinator`, `PurseCardForm` | SecureFields Android is Compose-first; a View app hosts a `ComposeView` |
| Secondaries extension | static configs | `SecondaryConfigs.register`, `pollForToken`, `classifyInitError` hooks | lets a host add an issuer without an SDK release |
