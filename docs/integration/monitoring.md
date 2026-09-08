# Monitoring and analytics: what the SDK sends

The SDK reports what it does to Purse's monitoring pipeline — the same collector, the same event
names and the same envelope as the web Headless Checkout SDK, so Android journeys show up in the
dashboards that already exist (Datadog, batch analytics) instead of a parallel chain. This page is
the contract: what leaves the device, when, and what never does. Jira: SDK-12220.

## Where it goes

`POST {api host}/widget/headless_checkout?api-key=…` — the host follows the session's environment
(`PurseEnvironment.monitoringApiRoot`, e.g. `https://api.purse-sandbox.com/…`). The body is a JSON
array of messages, sent as `text/plain` like web's `sendBeacon`. Events are batched (16 events,
10 s, 64 KB), flushed when the app goes to background and on `teardown()`. A failed batch is
dropped silently: telemetry never breaks a payment. Nothing is sent when the session carries no
`api_key`.

In Datadog the events land as `service:cf-headless-checkout-logger`; filter Android traffic with
`@platform:android` (web is `web`). The message is `{dateString} {code}`; the level becomes
`status` (`ERROR` → error, `WARNING` → warn, `SUCCESS` → ok, the rest info).

## Envelope

One message per event, identical to web's `MonitoringApiEventMessage` plus `platform`:

| Field | Value |
|---|---|
| `event` | `{ code, type, dateString, payload }` — see below |
| `instanceId` | one UUID per app process (web: per page) |
| `entityId` | the merchant entity of the session |
| `paymentSessionId` | the current session — re-stamped by `setSession` |
| `walletSessionId` | when the session carries a wallet session |
| `version` | the SDK version the app was built against (`PurseHeadlessCheckout.VERSION`, from the release tag) |
| `wrapper` | `direct` (web's meaning: which product embeds the SDK) |
| `env` | `development` / `test` / `sandbox` / `production` |
| `platform` | `android` |

## Events

Codes are `CheckoutEventCode`; levels are `EventLevel`. Web's names and payload keys, with the
Android additions marked.

| Code | Level | When | Payload |
|---|---|---|---|
| `HEADLESS_CHECKOUT_CREATE` | INFO | `init` / `restore` | `sessionType`, `user_agent`, **`device`** `{os, os_version, sdk_int, manufacturer, model}`, `screen` `{width, height, devicePixelRatio}` |
| `HEADLESS_CHECKOUT_SET_SESSION` | INFO | `init`, `setSession` | `paymentSessionId`, `sessionType`, `nb_methods`; on refresh `previousPaymentSessionId`, `nb_surviving_methods` (no amount — web sends one) |
| **`HEADLESS_CHECKOUT_METHOD_DISABLED`** | INFO / WARNING | a method enters a `DisabledState` | `partner`, `method`, `code` (the state's code, or `MISSING_PARTNER_ARTIFACT` + `artifact`), `filtered`, `recoverable`. WARNING for a technical failure (`TOKEN_INIT_FAILED`, `PARTNER_UI_FATAL_ERROR`, `FATAL_ERROR_BEFORE_RENDER`, missing artifact), INFO for a nominal one (platform suspension, amount rule, incompatible plugin) |
| `HEADLESS_CHECKOUT_READY` | INFO | end of construction | — |
| `HEADLESS_CHECKOUT_EXPIRED_SESSION` | WARNING | watchdog, `setSession`, expired-on-submit | `paymentSessionId` |
| `HEADLESS_CHECKOUT_SET_WALLET_SESSION` / `_FAILED` | INFO / ERROR | wallet token fetch | `walletSessionId` / error payload |
| `HEADLESS_CHECKOUT_METHOD_SET_PRIMARY`, `PRIMARY_TOKEN_SET_AS_PRIMARY`, `CLEAR_PRIMARY_SPLIT` | INFO | selection | `partner`, `method` |
| `HEADLESS_CHECKOUT_SECONDARY_TOKEN_TAKE` / `_REMOVE_FROM_SPLIT` / `_REGISTER`, `METHOD_GET_SECONDARY_TOKEN` | INFO | gift cards | `partner`, `method`, `partial` (a chosen slice, never its amount), `tokenId`, `saved` |
| `HEADLESS_CHECKOUT_TOKEN_EDIT` / `_DELETE` / `_SET_FAVORITE`, `DELETE_ALL_TOKENS` | INFO | wallet management | `partner`, `method`, `tokenId`, `favorite` |
| `HEADLESS_CHECKOUT_METHOD_LOAN_SIM`, `METHOD_REGISTER` | INFO | BNPL simulation, save decision | `partner`, `method`, `saveToken` |
| `HEADLESS_CHECKOUT_PAYMENT_FULFILLED` | INFO | the split covers the amount (or stops to) | `isFulfilled` |
| `HEADLESS_CHECKOUT_SUBMIT_PAYMENT` | INFO | `submitPayment` entered | `trigger` (`api` / `xpay`), `nb_splits` |
| `HEADLESS_CHECKOUT_SUBMIT_BLOCKED` | WARNING | second submit refused | `state` |
| `HEADLESS_CHECKOUT_VALIDATION_STARTED` | INFO | before `/validate` | `session_id`, `nb_items` |
| `HEADLESS_CHECKOUT_VALIDATION_SUCCESS` / `_FAILED` | SUCCESS / WARNING | `/validate` answered | `global_status`, `results[]` |
| `HEADLESS_CHECKOUT_PAYMENT_ERROR` | ERROR | a refused `/validate`, a thrown submit error; WARNING for a saved gift card that failed to `/init` | see below |
| `HEADLESS_CHECKOUT_ON_BEFORE_VALIDATE_HOOK_FAILED`, `CLIENT_HOOK_ERROR` | ERROR | a merchant hook threw | `hook`, `message` |
| `HEADLESS_CHECKOUT_PARTNER_UI_EVENT` | INFO / WARNING / ERROR | every plugin event (`PluginEvent`), and a plugin refusing the client (`code: pluginIsIncompatibleWithClient`, WARNING — SDK-12071) | `code`, `partner`, `method`, `reason` (capped) |
| `HEADLESS_CHECKOUT_PARTNER_API_CALL_START` / `_END` | NETWORK / PERFORMANCE | every Payment API call | `call`, `path`, `durationMs`, `success` |
| `HEADLESS_CHECKOUT_UNCAUGHT_ERROR` | ERROR | an SDK coroutine died | `stack` (1 000 chars) |
| `HEADLESS_CHECKOUT_PAGE_UNLOAD` | INFO | app to background | — |

### `PAYMENT_ERROR` and the per-attempt detail (SDK-12197)

A payment failure is diagnosable from one line. `results[]` summarises the `/validate` response
attempt by attempt — `method`, `partner`, `status { state, code, description }` (description
capped at 200 characters) and `plugin_status` (the partner-side outcome, e.g. `TIMEOUT` behind a
`TECHNICAL_ISSUE_TO_CONTACT_PARTNER`) — at most 5 attempts, failed ones only on the error path.
`VALIDATION_SUCCESS` / `VALIDATION_FAILED` carry the same summary, all attempts.

| Situation | Payload |
|---|---|
| `/validate` answered a non-`SUCCESS` `global_status` | `message`, `api_status` (the global status), `has_redirection`, `results` |
| the submit threw | `message` (500 chars), `code` (`PurseErrorCode`), `stack` (1 000 chars); when a Payment API call is the cause: `api_path`, `api_status` (HTTP), `api_error` (the body's `error`), `outcome_unknown` (no answer came back) |

## What never leaves the device

Not in any event, by construction:

- card data (PAN, expiry, CVV — the card form is SecureFields', its values never reach the SDK),
  form content, shopper identity;
- raw amounts (`SET_SESSION` says how many methods, `SECONDARY_TOKEN_TAKE` whether the slice was
  partial — web sends the amounts; Android does not);
- transaction ids and partner payloads: `results[]` is a field allowlist, `plugin_result` travels as
  its `status` only, and a Payment API error message never quotes the response body;
- a `DisabledState`'s message (it can quote amounts) — only its code.

What is collected: the entity, session and wallet-session ids, partner/method, the SDK version, the
device model and OS version, error codes, capped stack traces and messages.

There is no opt-out yet (Notion US-32): telemetry is on whenever the session has an `api_key`.
Merchants observe the same stream locally with `CheckoutEventBus.getInstance().subscribe { … }`
([recipe 11](recipes.md#11-events-and-monitoring)).
