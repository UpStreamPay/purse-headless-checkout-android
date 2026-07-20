# Purse Android Drop-in (POC)

Native Android Drop-in POC for [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039):
initialize a Purse payment session and pay with CB, Google Pay, PayPal, or Illicado, using native
UI throughout — built on top of the existing [SecureFields Android
SDK](https://upstreampay.atlassian.net/browse/SDK-11769) for card capture.

> **Status: early POC, in progress.** The `:core`, `:partner-ui`, and `:dropin` SDK modules
> (session decode + payment-method model in `:dropin`; PayPal in `:partner-ui`; the Payment API
> client + environment targets in `:core`) and a working `:app` demo (View system) exist. The demo
> mints a session via the alfred dev server and renders the method list on its own page; **CB card
> capture uses the SecureFields Android SDK**, Illicado gift-card entry works end to end against
> `/init`, **PayPal (wallet) pays via the official PayPal Android SDK** (`/init` → approval →
> `/validate`), and a **pay button** reflects web's completion rule (secondaries cover the amount,
> or a primary is valid). Still to build: `/validate` for the card pay button (it tokenizes but
> doesn't submit yet), the Google Pay coordinator, live split orchestration, and generic
> redirect/3-D Secure handling — see
> [Still to do](docs/internal/project-setup.md#still-to-do). The `/docs` folder documents the target
> architecture, derived from the web Drop-in (`@upstreampay/dropin` / `@upstreampay/headless-checkout`
> in the sibling `usp-widget` repo) and the SecureFields Android SDK (sibling `vault-android` repo).

---

## Start here

**[docs/README.md](docs/README.md)** — full documentation index.

| If you want to... | Read |
|---|---|
| Understand what this POC does and doesn't cover | [docs/decisions/poc-scope.md](docs/decisions/poc-scope.md) |
| Add the SecureFields Android SDK and initialize a session | [docs/integration/getting-started.md](docs/integration/getting-started.md) |
| See how CB / Google Pay / PayPal / Illicado each work | [docs/integration/payment-methods.md](docs/integration/payment-methods.md) |
| Understand the session/protocols/API model | [docs/architecture/session-and-protocols.md](docs/architecture/session-and-protocols.md) |
| See how PayPal's redirect is meant to work (the ticket's open question) | [docs/architecture/redirect-handling.md](docs/architecture/redirect-handling.md) |
| Set up the module structure from scratch | [docs/internal/project-setup.md](docs/internal/project-setup.md) |
| Run the demo app against a real session | [docs/internal/testing-with-alfred.md](docs/internal/testing-with-alfred.md) |

---

## Repository layout

| Module | Description | Status |
|---|---|---|
| `:app` | Demo app exercising the Drop-in (session builder + Drop-in page, View system; UI split into `ui.components` / `ui.primaries` / `ui.secondaries`) | Working — see [Testing with alfred](docs/internal/testing-with-alfred.md) |
| `:dropin` | Headless engine (≈ web `headless-checkout`): session decode/build, payment-method model, payment splitter, vault config. Depends on `:core` + `:partner-ui` | Built; Google Pay coordinator, `/validate` for card, redirect-mode/3DS still to do |
| `:partner-ui` | Per-partner plugins (≈ web `partner-ui`): PayPal wallet `/init`+`/validate` payloads + the PayPal SDK launch (self-contained return Activity). Depends on `:core` | PayPal wallet built |
| `:core` | Raw layer (≈ web `sdk`): Payment API `/init`+`/validate` client + environment targets, no domain logic | Built |

The `:app` → `:dropin` → `:partner-ui` → `:core` layering mirrors web's `dropin` → `headless-checkout` → `partner-ui` → `sdk`.

CB card capture itself is not reimplemented here — it's delegated to
`eu.purse:securefields-android`, published on Maven Central (see
[Getting Started](docs/integration/getting-started.md)).

---

## Build & run the demo

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

To exercise the demo end to end you must **run alfred** — the dev server in the sibling
`usp-widget` repo that mints real Purse sessions — and point the app at it:

1. From the **`usp-widget` repo root**: `npm run dev:alfred` (listens on port 9001).
2. In **this repo**, create a gitignored `.env` (see [`.env.example`](.env.example)):

   ```dotenv
   ALFRED_BASE_URL=http://10.0.2.2:9001
   ```

   Use **`10.0.2.2`** (not `localhost`) so the emulator reaches alfred on your host, and **`http`**
   (not `https`). On a physical device use your machine's LAN IP instead. `.env` is read at build
   time, so **rebuild** after changing it.

Full walkthrough and troubleshooting: **[docs/internal/testing-with-alfred.md](docs/internal/testing-with-alfred.md)**.

---

## Related

- [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039) — this POC
- [SDK-11769](https://upstreampay.atlassian.net/browse/SDK-11769) — SecureFields Android SDK (sibling repo `vault-android`)
- [SDK-7005](https://upstreampay.atlassian.net/browse/SDK-7005) — prior Android widget SDK (done; different architecture)
- `../usp-widget` — the web Drop-in this design mirrors
