# Project Setup

Module layout for implementing this POC, and how to get from the original bare skeleton to it.

> This guide is for **contributors implementing the POC**. If you're integrating a published
> SDK, this repository doesn't publish one yet — see [POC Scope](../decisions/poc-scope.md) for
> what this repo currently is.

---

## Table of Contents

- [Current state](#current-state)
- [A gotcha: don't apply the JetBrains Kotlin Android plugin](#a-gotcha-dont-apply-the-jetbrains-kotlin-android-plugin)
- [Modules: `:core`, `:dropin`, `:app`](#modules-core-dropin-app)
- [Why org.json instead of kotlinx.serialization](#why-orgjson-instead-of-kotlinxserialization)
- [Package layout](#package-layout)
- [Still to do](#still-to-do)
- [Final project layout](#final-project-layout)
- [See also](#see-also)

---

## Current state

The SDK is split into two modules that mirror the layering of usp-widget's web packages:

- **`:core`** ≈ web `sdk` — the raw layer: the Payment API `/init` client and the environment
  targets, with no session/payment domain logic.
- **`:dropin`** ≈ web `headless-checkout` — the headless engine: session decode/parse/build, the
  payment-method model (primary/secondary), the payment splitter, and the vault config. Depends on
  `:core`.

`app/` (≈ web `dropin`, the presentation layer) depends on `:dropin` and is a working View-system
demo (no Compose, no ViewModel/DI):

- **`MainActivity`** — session builder. Mints an orchestration session against usp-widget's
  **alfred** dev server (base URL from the `ALFRED_BASE_URL` env var / `.env`; two-step
  `GET /order` → `POST /orchestration_session`), then hands the resulting `widget.data` to…
- **`DropinActivity`** — the Drop-in page. Calls `DropinAndroid.init()`, groups the session's
  payment methods, and hands each group to a UI renderer (`ui.primaries` / `ui.secondaries` over
  shared `ui.components`). Grouped/ordered like the web Drop-in: **secondaries** (accordion, under a
  "Gift cards and loyalty account" title) → divider → **"Payment method"** → **xpay** (Google Pay /
  popup PayPal, laid out two-per-row) → "or" → **other primaries** (CB, redirect PayPal, as a radio
  list where tapping a row opens its content — nothing is expanded up front). **Only methods the POC
  implements are shown**; everything else is filtered out. Illicado gift-card entry is functional
  end to end: the form is validated by `SecondaryConfig` and "Add card" calls the real Payment API
  `/init`. **CB (creditcard) renders the SecureFields Android SDK** when it's Purse-vault-backed
  (`vault.vendor == purse`, per web's `vault.vendor !== 'none'` check) — real card fields, captured
  and tokenized by the SDK. A **pay button** at the bottom is driven by the SDK's `PaymentSplitter`
  (enabled when secondaries cover the full amount, or a primary is valid — web's
  `isPaymentFulfilled`). The PayPal redirect is still informational only.

See [Testing with alfred](testing-with-alfred.md) for running it. Redirect handling, `/validate`
(the pay button captures/tokenizes but doesn't submit yet), and the Google Pay / PayPal coordinators
are still future work (see [Still to do](#still-to-do)).

`app`'s namespace stays `eu.purse.dropin_android`; `:dropin`'s is `eu.purse.dropin` and `:core`'s
is `eu.purse.dropin.core`, for parity with `vault-android`'s `eu.purse.securefields`.

---

## A gotcha: don't apply the JetBrains Kotlin Android plugin — and how the Kotlin version is set

AGP 9.x ships **built-in Kotlin support** (`BuiltInKotlinCreationConfig` and friends, in the AGP
jar itself) — it registers its own `kotlin` project extension and compiles `.kt` sources under
`com.android.library`/`com.android.application` automatically, with no separate Kotlin plugin and
no `kotlin { jvmToolchain(...) }` block needed. Applying `org.jetbrains.kotlin.android` on top is
**rejected outright** by AGP 9.3.0:

```
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
```

So none of `:app`, `:dropin`, or `:core` applies a Kotlin plugin.

**Setting the built-in Kotlin compiler version.** The version isn't hardcoded by AGP — it's driven
by the `kotlin` version in `libs.versions.toml`, surfaced through the root
`build.gradle.kts`'s `alias(libs.plugins.kotlin.android) apply false` line (declared-but-not-applied
is how AGP picks up the version). It is currently **`2.4.10`**, and that's not optional: the
published `eu.purse:securefields-android` (both 0.5.0 and 1.0.0) is compiled with Kotlin 2.4.10, so
a lower built-in Kotlin (e.g. AGP 9.3.0's default 2.2.0) **cannot read its class metadata** and the
`:app` build fails with "was compiled with an incompatible version of Kotlin". Keep `kotlin` at ≥
the SecureFields SDK's Kotlin version.

---

## Modules: `:core`, `:dropin`, `:app`

`settings.gradle.kts` has `include(":core")` and `include(":dropin")` alongside `include(":app")`.
Both library modules apply only `alias(libs.plugins.android.library)` (no Kotlin plugin, per the
gotcha above). The dependency direction is `:app` → `:dropin` → `:core`, matching web's
`dropin` → `headless-checkout` → `sdk`.

```kotlin
// core/build.gradle.kts — the raw layer (≈ web sdk)
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "eu.purse.dropin.core"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
dependencies {
    testImplementation(libs.json)   // real org.json on the unit-test classpath — see below
    testImplementation(libs.junit)
}
```

```kotlin
// dropin/build.gradle.kts — the engine (≈ web headless-checkout)
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "eu.purse.dropin"
    /* compileSdk / defaultConfig / compileOptions identical to :core */
}
dependencies {
    // `api` (not `implementation`) so `:app` sees :core types like PurseEnvironment transitively,
    // mirroring how web's headless-checkout re-exports sdk's enums.
    api(project(":core"))
    testImplementation(libs.json)
    testImplementation(libs.junit)
}
```

`libs.versions.toml` additions so far: `json = "20260522"` / `json = { group = "org.json", name =
"json", ... }` (test-only, see below), and an `android-library` plugin alias. Coroutines in
particular are only in `:app` (for SecureFields' `suspend submit()`); `:dropin`/`:core` stay
synchronous and coroutine-free — the parser is synchronous, and the `/init` client blocks on
`HttpURLConnection` (callers invoke it off the main thread).

---

## Why org.json instead of kotlinx.serialization

The session parser (`SessionCodec`, `LegacyPaymentSessionParser`) uses `org.json.JSONObject`,
already part of `android.jar`, rather than `kotlinx.serialization` (which would need its own
Kotlin compiler plugin and a runtime dependency). This matches `vault-android`'s own
"minimize dependencies" philosophy documented in
`vault-android/docs/decisions/networking.md` (`HttpURLConnection` over OkHttp, for the same
reason: zero added APK weight for something the platform already provides).

The one wrinkle: Android's bundled `org.json` throws on the **unit test** classpath by default
(the platform stub is not a working implementation), and lacks `getBigDecimal`/`optBigDecimal`
entirely (session amounts — e.g. `payment_min_amount: 0.01` — are decimal, not integer, so this
matters; see `eu.purse.dropin.session.JsonExtensions.optBigDecimalOrNull`, which reads a numeric
field through `.toString()` into `BigDecimal(...)` instead). Both are handled by adding the
standalone `org.json:json` artifact as `testImplementation` only — it provides a real, working
implementation of the same `org.json.*` package on the unit test classpath, with zero effect on
the production AAR.

---

## Package layout

The layout is domain-shaped, mirroring the folders in usp-widget's `sdk` / `headless-checkout` /
`dropin` packages rather than lumping every data type into one `model/` bag.

**`:core`** (≈ web `sdk`) — raw transport + environment, no domain logic:

```
core/src/main/kotlin/eu/purse/dropin/core/
├── api/
│   ├── PaymentApiClient.kt       ← synchronous HttpURLConnection client: POST /init (mirrors web FetchPaymentAPI)
│   └── PaymentApiException.kt
└── environment/
    └── PurseEnvironment.kt       ← environment tier → paymentApiRoot
```

**`:dropin`** (≈ web `headless-checkout`) — the engine. `session/` owns the session lifecycle and
the models it produces; `payment/` is the domain (organized by the primary/secondary axis, with the
splitter and partner vocabulary alongside); `vault/` is the card-capture config:

```
dropin/src/main/kotlin/eu/purse/dropin/
├── DropinAndroid.kt              ← public entry point: init(), paymentMethods, vaultTenantId, getSecondaryToken()
├── session/
│   ├── SessionCodec.kt           ← decode(widgetData): PurseSessionParams
│   ├── LegacyPaymentSessionParser.kt  ← payment_session JSON → LegacyPaymentSession
│   ├── PaymentSessionBuilder.kt  ← LegacyPaymentSession → PaymentSession (information_request/sort/disabled-state rules)
│   ├── PaymentSession.kt         ← the built session
│   ├── LegacyPaymentSession.kt   ← decoded payment_session (+ MethodEntry)
│   ├── PurseSessionParams.kt     ← decoded widget.data
│   ├── DropinSessionException.kt
│   └── JsonExtensions.kt         ← internal org.json helpers (BigDecimal-safe, null-safe)
├── payment/
│   ├── partner/
│   │   └── Partner.kt            ← sealed class, Other(raw) for anything unrecognized
│   ├── method/
│   │   ├── DropinPaymentMethod.kt    ← PrimaryMethod / SecondaryMethod sealed hierarchy
│   │   ├── Method.kt                 ← same Other(raw) pattern
│   │   ├── MethodConfiguration.kt    ← + VaultInfo, MethodSettings
│   │   ├── DisabledReason.kt         ← amount-driven subset only, see session-and-protocols.md
│   │   ├── IntegrationMode.kt
│   │   ├── RegistrationMode.kt
│   │   └── secondary/
│   │       ├── SecondaryConfig.kt        ← client-side validation (Illicado PAN/CVV rules)
│   │       ├── SecondaryToken.kt         ← a tokenized secondary (balance/name/expiry) from /init
│   │       └── SecondaryTokenMapper.kt   ← builds the /init body + parses the response → SecondaryToken
│   └── split/
│       └── PaymentSplitter.kt    ← runtime split state: remaining / isFulfilled / applySecondary
└── vault/
    ├── SessionVault.kt           ← + VendorFrontData, PurseVaultInfo, PciProxyVaultInfo
    ├── VaultVendor.kt
    └── CardBrand.kt
```

**`:app`** (≈ web `dropin`) — the demo UI. `DropinActivity` is the orchestrator; rendering is split
by the same primary/secondary axis as web's `ui/`, over a payment-agnostic `ui.components` layer:

```
app/src/main/kotlin/eu/purse/dropin_android/
├── MainActivity.kt               ← session builder (alfred dev server)
├── DropinActivity.kt             ← orchestrator: decode, group methods, drive the pay button
└── ui/
    ├── components/Views.kt       ← generic view helpers (section titles, dividers, cards) — no payment logic
    ├── primaries/PrimaryMethodsView.kt      ← xpay buttons + primaries radio list + SecureFields form
    └── secondaries/SecondaryMethodsView.kt  ← gift-card accordion + form + tokenization
```

A few placement notes:

- `PurseEnvironment` lives in `:core` (`environment/`) because it's the raw environment→endpoint
  map the API client needs, and it's exposed transitively to `:app` (for the SecureFields
  `VaultEnvironment` mapping) via `:dropin`'s `api(project(":core"))`.
- `SecondaryTokenMapper` is engine-level (`payment/method/secondary/`), not `:core` — it builds and
  parses `/init` payloads in terms of the domain model, and only *uses* the raw `PaymentApiClient`.
- The runtime split lives in one place, `payment/split/PaymentSplitter`, rather than being smeared
  across the builder and the Activity. `DropinActivity` holds a `PaymentSplitter` and reads
  `remaining`/`isFulfilled` from it, the way web's UI consumes the splitter's stores.

`DropinAndroid.init()` accepts an optional `lockedEnvironment: PurseEnvironment?` — when set, it
overrides whatever the session itself declares. This implements the environment-locking rule in
[Session & Protocols — Validation rules](../architecture/session-and-protocols.md#validation-rules):
a release build should always pass `PurseEnvironment.PRODUCTION` here rather than trusting a
session-supplied value.

Test coverage (`dropin/src/test/kotlin/...` and `core/src/test/kotlin/...`, plain JUnit, no
Robolectric needed — nothing in these modules touches `android.*`): `SessionCodecTest` (decoding,
malformed-session cases, unknown partner/method round-tripping), `PaymentSessionBuilderTest`
(secondary-via-labels, position sorting, `information_request` filtering, all three `DisabledReason`
cases), `PaymentSplitterTest` (the split math: fresh/partial/accumulating/over-cover),
`SecondaryConfigTest` + `SecondaryTokenMapperTest` (under `payment/method/secondary/`), and
`DropinAndroidTest` (the public API surface, including the environment lock).

---

## Still to do

Already built: the `:core` `/init` client (secondary flow), `:app` wired to `:dropin`, the Internet
permission, a working demo harness (`MainActivity` + `DropinActivity`, View system), **CB card
capture via the SecureFields Android SDK**, a **pay button** driven by `PaymentSplitter`, and the
`:core` / `:dropin` module split with a domain-shaped package layout. `securefields-android`,
`lifecycle-runtime-ktx`, and `kotlinx-coroutines-android` are in `libs.versions.toml` / `:app`.
Still unbuilt:

- **`/validate`** — `PaymentApiClient` only implements `/init` so far; the validate call (and its
  array/split request shape) is not built. The pay button captures/tokenizes the card (SecureFields)
  but the `/init` + `/validate` submission that turns a token into an authorized payment isn't wired.
  See [Session & Protocols](../architecture/session-and-protocols.md#init-and-validate).
- **Google Pay & PayPal coordinators** (`methods/`) — `PaymentsClient` (Google Pay) and the PayPal
  redirect flow. CB (SecureFields) and the Illicado secondary `/init` are wired today; these two
  aren't. See [Payment Methods](../integration/payment-methods.md).
- **Live split recomputation** — `PaymentSplitter` tracks a secondary's balance contribution and the
  pay button reads `remaining`/`isFulfilled` from it, but `PaymentSessionBuilder` still computes each
  method's disabled state against the full amount (not against the live splitter as secondaries are
  added), and there's no partial-capture or multi-source submission yet.
- **`redirect/DropinRedirectionHandler.kt`** — Custom Tabs / WebView dispatch and a redirect-return
  `<intent-filter>` in `app/src/main/AndroidManifest.xml`, see
  [Redirect Handling](../architecture/redirect-handling.md).
- **Remaining dependencies** — `androidx.browser` and `play-services-wallet` (and Compose, if the
  demo moves off the View system) are added when the PayPal/Google Pay coordinators need them.

---

## Final project layout

The target shape is what's described in [Package layout](#package-layout), across the three modules:

```
dropinandroid/
├── app/
│   └── src/main/kotlin/eu/purse/dropin_android/
│       ├── MainActivity.kt
│       ├── DropinActivity.kt
│       └── ui/                    ← components / primaries / secondaries
├── dropin/
│   └── src/main/kotlin/eu/purse/dropin/
│       ├── DropinAndroid.kt
│       ├── session/               ← done
│       ├── payment/               ← method (+ secondary), partner, split — done
│       ├── vault/                 ← done
│       ├── redirect/              ← not yet built
│       └── methods/               ← not yet built
├── core/
│   └── src/main/kotlin/eu/purse/dropin/core/
│       ├── api/                   ← done
│       └── environment/           ← done
├── gradle/
│   └── libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
```

---

## See also

- [POC Scope](../decisions/poc-scope.md)
- [Session & Protocols](../architecture/session-and-protocols.md)
- [Payment Methods](../integration/payment-methods.md)
- [Redirect Handling](../architecture/redirect-handling.md)
