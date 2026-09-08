# 16 KB page-size alignment

Google Play requires apps targeting Android 15 (API 35) and above to support devices with a 16 KB
memory page size. The requirement bites on **native code**: every `.so` an app ships must have its
ELF `LOAD` segments aligned on 16 KB (`p_align ≥ 0x4000`); a 4 KB-aligned library makes the app
fail to install or crash on a 16 KB device. Kotlin/Java code is unaffected.

The question for this SDK (US-01, lot L0 — a gate on the whole initiative): can a merchant who
embeds it still publish on the Play Store, given the partner SDKs it imposes?

## Result: compliant, nothing to arbitrate

The SDK ships no native code of its own. Of every dependency on the demo app's release runtime
classpath (the demo takes all three optional partner artifacts, so this is the worst case), only
two AndroidX libraries carry native code, and both are aligned:

| Dependency | Native code | 16 KB |
|---|---|---|
| `eu.purse:securefields-android:1.4.0` (card) | none | n/a |
| `com.google.android.gms:play-services-wallet:20.0.0` (Google Pay) | none — Google Pay runs in the Play Services APK, not in the app | n/a |
| `com.klarna.mobile:sdk:2.13.1` + `klarna-core`, `klarna-core-webview`, `klarna-payments` | none (WebView-based) | n/a |
| `com.paypal.android:paypal-web-payments:2.3.0`, `core-payments`, `payment-buttons` | none | n/a |
| `com.braintreepayments.api:browser-switch:3.2.0` (PayPal transitive) | none | n/a |
| `com.squareup.okhttp3:okhttp:4.12.0` (PayPal transitive) | none | n/a |
| `androidx.graphics:graphics-path:1.0.1` (Compose transitive) | `libandroidx.graphics.path.so` × 4 ABIs | ✅ `p_align = 0x4000` |
| `androidx.datastore:datastore-core` (Compose/lifecycle transitive) | `libdatastore_shared_counter.so` × 4 ABIs | ✅ `p_align = 0x4000` |

So no partner needs a version bump, no method needs to be dropped, and no limit needs
documenting. `targetSdk` is 36 (demo) and libraries compile against API 37.

## Kept true in CI

`./gradlew verify16kbAlignment` (root `build.gradle.kts`) re-runs this audit on every pull request:
it walks the demo's release runtime classpath, opens each AAR/JAR, parses the program headers of
every `.so` it finds and fails when any `LOAD` segment has `p_align < 16384`. The report is written
to `build/reports/16kb-alignment.txt`. Since Dependabot proposes partner SDK bumps as pull
requests, a partner release that regresses is caught before merge.

If it ever fails, the arbitration is, in order: bump the partner to an aligned release; remove the
method (the optional-artifact split means dropping one dependency, not surgery); or ship with the
limitation documented in the
[payment-methods matrix](../payment-methods-matrix.md) and the partner's plugin page.

Audit date: 2026-09-07, dependency versions as in `gradle/libs.versions.toml` at that date.
