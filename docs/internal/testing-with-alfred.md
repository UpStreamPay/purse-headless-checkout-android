# Testing with alfred

How to run the `:app` demo against a real Purse orchestration session, using **alfred** — the dev
server in the sibling `usp-widget` repo (`usp-widget/packages/alfred`) that mints sessions against
the Purse API on your behalf.

> This is for **running the demo app locally**. The `:headless-checkout` SDK itself is unit-tested
> in isolation (see [Headless checkout architecture](../architecture/headless-checkout.md)).

---

## Table of Contents

- [Why alfred](#why-alfred)
- [1. Run alfred](#1-run-alfred)
- [2. Point the app at alfred (`.env` + `10.0.2.2`)](#2-point-the-app-at-alfred-env--10022)
- [3. Run the demo](#3-run-the-demo)
- [The three screens](#the-three-screens)
- [What minting a session does](#what-minting-a-session-does)
- [Troubleshooting](#troubleshooting)

---

## Why alfred

`PurseHeadlessCheckout.init()` takes a base64 `widget.data` string — the payload a merchant backend gets
back from Purse's Client Session API. Rather than reimplement that backend call (auth token,
entity id, order schema) in the demo, the app talks to alfred, which already does it. alfred
converts a demo "legacy order" fixture into the orchestration order shape and calls Purse's
`/client-sessions` endpoint, returning the session (including `widget.data`) straight to the app.

---

## 1. Run alfred

From the **`usp-widget` repo root** (not this repo):

```bash
npm run dev:alfred
```

alfred listens on **port 9001**. It needs its own `.env` (at the `usp-widget` root) with the Purse
API credentials it uses to create sessions — see that repo's setup. When it's up you'll see:

```
Alfred is waiting for your orders on port 9001
```

Leave it running.

---

## 2. Point the app at alfred (`.env` + `10.0.2.2`)

The app reads alfred's base URL from `ALFRED_BASE_URL`, baked into `BuildConfig` at build time from
a gitignored **`.env` at this repo's root** (see [`.env.example`](../../.env.example) and
`app/build.gradle.kts`). Copy the example and set the URL:

```bash
cp .env.example .env
```

```dotenv
# .env
ALFRED_BASE_URL=http://10.0.2.2:9001
```

Two things trip people up here:

- **Use `10.0.2.2`, not `localhost`.** The Android emulator is a separate virtual device, so
  `localhost` / `127.0.0.1` resolves to the *emulator itself*, not the Mac running alfred. `10.0.2.2`
  is the emulator's special alias for the host loopback. On a **physical device**, use your Mac's
  LAN IP instead (e.g. `http://192.168.1.42:9001`, phone on the same Wi-Fi).
- **Use `http`, not `https`.** alfred serves plain HTTP; `https` fails the TLS handshake. Cleartext
  to this host is already allowed by `app/src/main/res/xml/network_security_config.xml` (this is a
  demo app, never distributed).

If `ALFRED_BASE_URL` is unset, the app falls back to `http://10.0.2.2:9001`, so on an emulator the
demo works out of the box with an empty `.env`.

> **`.env` is read at build time.** After editing it you must **rebuild** — a reinstall of the old
> APK won't pick up the change. The `alfred` line on the app's first screen shows the URL that's
> actually baked in, which is the quickest way to confirm the value.

---

## 3. Run the demo

```bash
./gradlew :app:installDebug
```

The app mints a session as soon as it opens (alfred + `PurseHeadlessCheckout.init` in one go) and
shows the store, the basket and the amount. Switch the payment methods the store should offer, then
**Open Denim & Co. checkout**.

---

## The three screens

The demo is one Activity (`MainActivity`) rendering three Compose screens off a single
`DemoController`. It tells a two-actor story: the first screen is **Purse's** control panel (Purse
teal), the other two are the **merchant's** store ("Denim & Co.", in its own indigo), with the
partners' branded controls untouched inside it.

| | Screen | What it's for |
|---|---|---|
| 1 | **Store setup** (`SetupScreen`) | The session is minted on arrival. One switch per method the session offers (with its CDN logo), plus presets: *Everything*, *Card only*, *Wallets*, *No express*. Under *Developer*: alfred's URL, paste a `widget.data` by hand, mint a new session, the SDK's decoded state. |
| 2 | **Checkout** (`CheckoutScreen`) | A store checkout: the basket from alfred's order (`GET /order` — items, delivery, customer), then *Payment*: the express buttons (Google Pay, PayPal), saved cards, the method rows the store switched on (logo, radio, the element opens in place), and a *Have a gift card?* fold. One **Pay** button submits the selected element. The `</>` toggle in the header opens the developer panel: session id, state, split, refresh session, session details, event log. |
| 3 | **Result** (`ResultScreen`) | The store's confirmation page: *Thank you, James!* with the order recap; *Payment declined* with a way back to the checkout; or *Confirming your payment…* after a redirect while the controller polls the session's transactions itself. The `</>` panel keeps **Get transactions** and **Capture**. |

Two things worth knowing about screen 3:

- **The `payment_id` is prefilled.** Every coordinator reports one via `Listener.onPaymentId`
  whenever `/validate` carries it, so *Capture* is usable without hunting an id out of a log. It stays
  editable, and some flows genuinely have none to report (see below).
- **A redirect return is not a verdict.** After a partner redirect the coordinators only report that
  the shopper came back — never the payment's fate (see
  [pain points recap](../decisions/pain-points-recap.md)). The result screen therefore polls
  `GET /payment_session/{id}` a few times (`DemoController.confirmInBackground`); a successful
  `AUTHORIZE`/`CAPTURE` promotes it to *Thank you*. **Get transactions** in the developer panel is the
  manual retry. That path also has no `payment_id` from `/validate`, because the widget-confirmation
  redirect that carries one is exactly what a genuine redirect replaces.

Every raw JSON dump (`widget.data`, the session details, the transactions response) sits behind an
expander in the developer panel, and the *Event log* keeps every coordinator callback in order — the
place to read a full `KlarnaPaymentsSDKError`.

---

## What minting a session does

Two calls against alfred (see `AlfredClient.createSession`):

1. `GET {ALFRED_BASE_URL}/order` — returns alfred's demo legacy order, wrapped as
   `{ "order": <legacyOrder> }`. The app unwraps the `order` field.
2. `POST {ALFRED_BASE_URL}/orchestration_session` with that legacy order as the body — alfred runs
   its own `fromLegacyOrder` conversion on the body and creates the session, returning JSON from
   which the app reads `widget.data`.

The legacy order shape from step 1 is exactly what `/orchestration_session` expects as input, so the
two compose cleanly. (Posting the *output* of alfred's `/orchestration_order` endpoint into
`/orchestration_session` instead would double-convert and fail — that's why the flow uses `/order`.)

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Failed to connect to localhost/127.0.0.1:9001` | `ALFRED_BASE_URL` is `localhost` — use `10.0.2.2` (emulator) or your LAN IP (device), then **rebuild**. |
| TLS / SSL handshake error | URL is `https` — alfred is `http`. |
| Empty or very short method list | Session came back with few methods — the partner/method availability is entity-wide config on alfred's side; check alfred's setup in `usp-widget`. |
| The `alfred` line (under *Developer* on the setup page) shows the old URL | `.env` edited but not rebuilt — run `:app:installDebug` again. |
| No Google Pay button on screen 2 | `prepare()` reported not-ready or failed (the reason lands in a snackbar and the *Event log*) — e.g. this session's Google Pay config has no `payment_method_configuration`, or the device/emulator has no Play Services wallet. Every other method still works. |
| `ENVIRONMENT_NOT_ALLOWED` on screen 1 with the **release** variant | alfred mints on `test` by default (`PUBLIC_API_ENV`), and a non-debuggable app is refused the internal tiers. Use `installDebug`, or point alfred at sandbox to rehearse on the release variant. |
| A method offered by the session isn't in the list | Screen 2 only offers what this demo has a plugin for (card, Google Pay, PayPal, Klarna, redirection methods, and the secondaries with a registered `SecondaryConfig`) **and** what the setup page has switched on. `Session details` lists everything the session actually carries. |

---

## See also

- [Headless checkout architecture](../architecture/headless-checkout.md)
- [`.env.example`](../../.env.example)
