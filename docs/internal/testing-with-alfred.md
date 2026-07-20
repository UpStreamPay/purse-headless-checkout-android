# Testing with alfred

How to run the `:app` demo against a real Purse orchestration session, using **alfred** — the dev
server in the sibling `usp-widget` repo (`usp-widget/packages/alfred`) that mints sessions against
the Purse API on your behalf.

> This is for **running the demo app locally**. The `:dropin` SDK itself has no network dependency
> and is unit-tested in isolation (see [Project Setup](project-setup.md)).

---

## Table of Contents

- [Why alfred](#why-alfred)
- [1. Run alfred](#1-run-alfred)
- [2. Point the app at alfred (`.env` + `10.0.2.2`)](#2-point-the-app-at-alfred-env--10022)
- [3. Run the demo](#3-run-the-demo)
- [What "Create session" does](#what-create-session-does)
- [Troubleshooting](#troubleshooting)

---

## Why alfred

`DropinAndroid.init()` takes a base64 `widget.data` string — the payload a merchant backend gets
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
> APK won't pick up the change. The "Alfred: …" label at the top of the screen shows the URL that's
> actually baked in, which is the quickest way to confirm the value.

---

## 3. Run the demo

```bash
./gradlew :app:installDebug
```

Then on the app: **Create session** → `widget.data` populates → **Initialize Dropin SDK** → the
session summary and payment-method list render.

---

## What "Create session" does

Two calls against alfred (see `MainActivity.createSession`):

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
| "Alfred: …" label shows the old URL | `.env` edited but not rebuilt — run `:app:installDebug` again. |

---

## See also

- [Project Setup](project-setup.md)
- [Getting Started](../integration/getting-started.md)
- [`.env.example`](../../.env.example)
