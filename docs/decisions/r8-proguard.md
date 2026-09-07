# R8 / ProGuard

What a merchant's minified release build does to the SDK, and what the SDK ships to survive it.

> **R8** shrinks, optimises and obfuscates a release APK. **Consumer rules** travel inside an AAR
> (`consumer-rules.pro`) and are applied automatically to the app that depends on it. The demo's
> `app/proguard-rules.pro` is *not* a place to fix SDK problems: merchants never see it.

## Rules by artifact

| Artifact | Consumer rules | Why |
|---|---|---|
| `headless-checkout-android` | keep `PartnerModule` implementations + their names and no-arg constructor; keep `RedirectionCheckoutActivity`'s constructor | `PluginRegistry` finds the optional partner artifacts with `ServiceLoader` + `META-INF/services` — the service file names classes, so their names must survive; the Activity is instantiated by the framework (AGP's manifest rules already keep it — defence in depth). Nothing else: JSON is read by string key through `org.json`, there is no reflection, no serialisation library, and the monitoring `code` of a partner event is `PluginEvent.code`, an explicit constant, never a class `simpleName` (which R8 would rename) |
| `headless-checkout-googlepay` | none of its own | its `GooglePayModule` is covered by the SDK's rule; Play Services Wallet ships its rules |
| `headless-checkout-klarna` | none of its own | idem; the Klarna Mobile SDK ships its own (`JavascriptInterface`, `api.*` names) |
| `headless-checkout-paypal` | keep `PaypalCheckoutActivity`'s constructor | manifest-declared Activity, defence in depth; the PayPal SDK ships its own rules |

The `ServiceLoader.load(PartnerModule::class.java, PartnerModule::class.java.classLoader)` call is
the two-argument form R8 recognises and rewrites into direct instantiation when it can see every
implementation, so a minified app keeps working even when a build tool strips
`META-INF/services`. `PluginRegistry.install(module)` remains as the explicit fallback.

## What is verified

- `./gradlew :app:assembleRelease` on every pull request — the demo, with every partner artifact,
  minified and resource-shrunk against these consumer rules. R8 produced no `missing_rules.txt` on
  the current dependency set.
- The demo's release build is signed with the debug key so the CI artifact installs on any device.

## What is rehearsed by hand

An end-to-end sandbox payment on the minified demo, for each method family, at each release
candidate (SDK-12219 AC 5) — the only proof that no reflective path was missed at runtime. See
[ci.md](../contributing/ci.md#what-is-not-automated).

## Why so few rules

Same design as securefields-android: `org.json` by key (string literals are never renamed),
typed listener interfaces (direct virtual dispatch), no Gson/Moshi/kotlinx-serialization, no
string-keyed class dispatch. The one `simpleName` use the codebase had (partner event monitoring
codes) was replaced by an explicit property when this review was done.
