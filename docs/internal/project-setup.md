# Project Setup

Module layout for implementing this POC, and how to get from the original bare skeleton to it.

> This guide is for **contributors implementing the POC**. If you're integrating a published
> SDK, this repository doesn't publish one yet — see [POC Scope](../decisions/poc-scope.md) for
> what this repo currently is.

---

## Table of Contents

- [Current state](#current-state)
- [A gotcha: don't apply the JetBrains Kotlin Android plugin](#a-gotcha-dont-apply-the-jetbrains-kotlin-android-plugin)
- [Modules: `:core`, `:partner-ui`, `:dropin`, `:app`](#modules-core-partner-ui-dropin-app)
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

## Modules: `:core`, `:partner-ui`, `:dropin`, `:app`

`settings.gradle.kts` has `include(":core")`, `include(":partner-ui")`, and `include(":dropin")`
alongside `include(":app")`. The three library modules apply only
`alias(libs.plugins.android.library)` (no Kotlin plugin, per the gotcha above). The dependency
direction is `:app` → `:dropin` → `:partner-ui` → `:core`, matching web's
`dropin` → `headless-checkout` → `partner-ui` → `sdk`: the engine orchestrates the per-partner
plugins, which in turn sit on the raw transport.

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
    // `api` (not `implementation`) so `:app` sees :core / :partner-ui types transitively, mirroring
    // how web's headless-checkout re-exports sdk's enums and depends on partner-ui.
    api(project(":core"))
    api(project(":partner-ui"))
    testImplementation(libs.json)
    testImplementation(libs.junit)
}
```

`:partner-ui` adds `com.paypal.android:paypal-web-payments` (+ appcompat), so — exactly as in web,
where headless-checkout depends on partner-ui — the engine transitively pulls in the PayPal SDK and
androidx. That's the deliberate trade-off for having the engine orchestrate partner plugins.

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
├── DropinAndroid.kt              ← public entry: init(), paymentMethods, vaultTenantId, getSecondaryToken(), createPaypalOrder()/validatePaypal()
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

**`:partner-ui`** (≈ web `partner-ui`) — per-partner plugins on top of `:core`. PayPal today:

```
partner-ui/src/main/kotlin/eu/purse/dropin/partnerui/
└── partner/paypal/wallet/
    ├── PaypalWalletMapper.kt      ← /init + /validate payload build/parse (order_id, step_id, global_status)
    ├── WalletOrder.kt             ← the created order (order id + step id) to approve
    ├── WalletValidationResult.kt  ← the capture outcome (+ any chained redirect)
    ├── PaypalCheckoutContract.kt  ← ActivityResultContract: launch approval, get the outcome back
    └── PaypalCheckoutActivity.kt  ← self-contained return Activity (PayPalWebCheckoutClient)
```

The return Activity's `<intent-filter>` uses `android:scheme="${applicationId}"` in `:partner-ui`'s
own `AndroidManifest.xml`, so it merges into any integrating app with **no manifest work there** —
the reason PayPal lives in a library module rather than the demo `:app`.

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
- **PayPal lives in `:partner-ui`**, not `:dropin`: the engine (`DropinAndroid.createPaypalOrder` /
  `validatePaypal`) orchestrates it, exactly as web's headless-checkout drives the partner-ui paypal
  plugin. The `/init`+`/validate` payloads, the PayPal SDK launch, and the self-contained return
  Activity all live in `:partner-ui`.

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
`SecondaryConfigTest` + `SecondaryTokenMapperTest` (under `payment/method/secondary/`),
`PaypalWalletMapperTest` (under `:partner-ui` — the PayPal `/init`+`/validate` payload contract), and
`DropinAndroidTest` (the public API surface, including the environment lock).

---

## Still to do

Already built: the `:core` Payment API client (`/init` + `/validate`), `:app` wired to `:dropin`,
the Internet permission, a working demo harness (`MainActivity` + `DropinActivity`, View system),
**CB card capture via the SecureFields Android SDK**, **PayPal (wallet) via the official PayPal
Android SDK** (`/init` → approval → `/validate`, in `:partner-ui`), a **pay button** driven by
`PaymentSplitter`, and the `:core` / `:partner-ui` / `:dropin` module split with a domain-shaped
package layout. `securefields-android`, `lifecycle-runtime-ktx`, `kotlinx-coroutines-android`, and
`paypal-web-payments` are in `libs.versions.toml`. Still unbuilt:

- **Card `/validate`** — the card pay button captures/tokenizes via SecureFields, but the `/init` +
  `/validate` submission that turns that token into an authorized card payment isn't wired (PayPal
  already exercises the `/validate` client). See
  [Session & Protocols](../architecture/session-and-protocols.md#init-and-validate).
- **Google Pay coordinator** — `PaymentsClient` (Google Pay) isn't built; its xpay button stays
  inert. CB (SecureFields), the Illicado secondary `/init`, and PayPal (wallet) are wired today. See
  [Payment Methods](../integration/payment-methods.md).
- **Generic redirect / 3-D Secure** — PayPal uses its own SDK browser-switch, but a generic
  `DropinRedirectionHandler` (Custom Tabs / WebView) for `integration_mode: "redirection"` methods
  and chained `/validate` `redirection` (3DS) responses isn't built. PayPal's own `return_url` →
  app-deep-link also needs backend confirmation. See
  [Redirect Handling](../architecture/redirect-handling.md).
- **Combined split submission** — `PaymentSplitter` tracks a secondary's balance contribution, the
  pay button reads `remaining`/`isFulfilled` from it, and method disabled states now recompute
  against the live splitter as secondaries are added (`MethodAvailability` /
  `DropinAndroid.methodsForPrimaryReach`, mirroring web's `updateMopDisabledState`). What's still
  missing is the actual multi-source `/validate` — capturing a secondary + primary together in one
  payment (the `results` array), and partial capture.
- **Remaining dependencies** — `play-services-wallet` (and Compose, if the demo moves off the View
  system) when the Google Pay coordinator needs them. `androidx.browser` isn't needed for PayPal —
  the PayPal SDK bundles its own browser-switch.

---

## Final project layout

The target shape is what's described in [Package layout](#package-layout), across the four modules:

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
│       └── vault/                 ← done
├── partner-ui/
│   └── src/main/kotlin/eu/purse/dropin/partnerui/
│       └── partner/paypal/wallet/ ← PayPal wallet done (mapper + SDK launch + return Activity)
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
