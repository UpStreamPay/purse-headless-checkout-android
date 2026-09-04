# Web ↔ Android map

One-page cross-reference between `usp-widget` (web, the reference implementation) and this SDK.
Paths on the web side are under `usp-widget/packages/`. Update this file in the same commit that
adds or removes a divergence.

## Modules

| Web package | Android module |
|---|---|
| `sdk` + `model` + `common` | `:core` |
| `partner-ui` | `:partner-ui` |
| `headless-checkout` | `:headless-checkout` |
| `dropin` (Svelte UI on top of headless) | none; `rememberPaymentElement` is the closest thing |
| playground / `alfred` | `:app` (demo) |

## File-level map

| Web | Android | Same? |
|---|---|---|
| `headless-checkout/src/headless-checkout.ts` | `checkout/PurseHeadlessCheckout.kt` | yes, plus hosts, wallet, simulation in the same class (see review §4) |
| `headless-checkout-validation.ts` (`executePayment`, `preValidateItems`) | `checkout/validation/PaymentValidator.kt` | yes |
| `session/payment/payment.ts` (stores) | `StateFlow`s on `PurseHeadlessCheckout` | Svelte stores → `StateFlow` |
| `session/payment/mops/item-factory/{primaries,secondaries,factories}.ts` | `checkout/session/PaymentSessionBuilder.kt`, `payment/item/PaymentItem.kt` | yes |
| `mops/disabled.ts` | `payment/item/DisabledState.kt`, `MethodAvailability.kt` | yes |
| `mops/secondaries/configs/*.ts` (one file per issuer) | `payment/secondary/SecondaryConfig.kt` (one file) | same keys; `ancv-connect` missing; Android adds `SecondaryConfigs.register` |
| `mops/secondaries/{init,token,validate}-mappers.ts` | `SecondaryConfig.kt`, `SecondaryTokenizer.kt` | yes |
| `splitter/payment-splitter.ts` | `payment/split/PursePaymentSplitter.kt` | yes, integer cents |
| `splitter/middlewares/*` (autofill) | not ported | |
| `payment-element/payment-element.ts` | `checkout/element/PaymentElement.kt` + `:partner-ui` coordinators | see divergences |
| `monitoring/*`, `monitoringConfigs.ts` | `checkout/monitoring/CheckoutEventBus.kt`, `CheckoutEventCode.kt`, `core/monitoring/*` | same 40 codes, envelope + `platform: android` |
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
| `plugins/generics/googlepay/*` + `partners/{adyen,ingenico,default}` + `libs/ingenico-onlinepayments` | `plugins/generics/googlepay/*`, `partners/*`, `WorldlineProductClient.kt` | protocol yes; sheet via Play Services |
| `plugins/generics/redirection/*` + `manageRedirection` | `plugins/generics/redirection/*`, `partnerui/redirect/RedirectionResolver.kt` + WebView Activity | yes; return handled in-app |
| `plugins/partners/klarna-installments` | `plugins/partners/klarna/*` | JS SDK → `KlarnaPaymentView` |
| `plugins/partners/paypal/wallet` | `plugins/partners/paypal/wallet/*` | JS popup → PayPal Android SDK |
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
| Interactive step | plugin renders into the DOM and owns its client | `*Coordinator` owns the native SDK, Activity-result launchers and the redirect WebView; the plugin stays pure protocol | Activity lifecycle and result contracts have no web analogue |
| Plugin ↔ checkout access | plugin holds the checkout context | `*Host` interface implemented by the checkout | keeps `:partner-ui` from depending on `:headless-checkout` |
| Fulfilment | `setAsPrimarySource` refuses an unfulfilled element | reactive `isPaymentFulfilled`, selectable before the form is filled | fits `StateFlow` UIs |
| Card `/init` timing | at `onSessionSet` | inside `preValidation` | nothing about rendering depends on it on Android |
| PayPal `/init` timing | at render | on button tap | avoids orphan PayPal orders |
| PayPal `redirection.url` in `/validate` | never followed | explicitly dropped when it is a PayPal approval URL | it is the web popup URL echoed back; following it re-opens PayPal |
| Klarna return | page URL | host app must declare a `singleTask` deep link | `KlarnaPaymentView` resumes the same Activity |
| Payment confirmation after redirect | merchant success page | `paymentStatus()` / `awaitPaymentOutcome()` polling | the app returns to the launching screen |
| `setSession` survivors | resets the split | keeps an unchanged primary selected | AC of US-24 |
| Saved gift cards | listed in `mops` | `savedSecondaryTokens` flow, balances re-`/init`ed sequentially | `InitGuard` would coalesce parallel `/init`s of one issuer |
| Monitoring envelope | `wrapper`, `env`, … | same plus `platform: "android"` | matches the native vault SDKs |
| Card UI toolkit | DOM | Compose (`PurseCardForm`); Google Pay / Klarna also have View-system entry points | SecureFields Android is Compose-first; see review §3 |
| Secondaries extension | static configs | `SecondaryConfigs.register`, `pollForToken`, `classifyInitError` hooks | lets a host add an issuer without an SDK release |
