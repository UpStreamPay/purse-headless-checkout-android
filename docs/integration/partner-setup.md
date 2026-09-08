# Partner setup

`eu.purse:headless-checkout-android` alone gives you cards and every redirection method. Google Pay,
Klarna and PayPal each bring a partner SDK, so each is a separate artifact you add only if you offer
the method, and one of them needs two settings of its own. Everything else (permissions, return
Activities, R8 rules) ships inside the artifacts.

| Partner | Artifact | Extra setup |
|---|---|---|
| Google Pay | `eu.purse:headless-checkout-googlepay` | none |
| PayPal | `eu.purse:headless-checkout-paypal` | none |
| Klarna | `eu.purse:headless-checkout-klarna` | Klarna's Maven repository and a deep link on your checkout Activity |

Pin the same version as the SDK: the artifacts release together.

## Google Pay

```kotlin
implementation("eu.purse:headless-checkout-googlepay:<version>")
```

Brings Play Services Wallet. The `com.google.android.gms.wallet.api.enabled` manifest `meta-data`
ships in the artifact's manifest; the 3-D Secure return after an approved sheet uses the SDK's own
Activity. Nothing to declare. Details: [plugins/google-pay.md](../plugins/google-pay.md).

## PayPal

```kotlin
implementation("eu.purse:headless-checkout-paypal:<version>")
```

Brings the PayPal Android SDK. The approval opens the PayPal app or a Custom Tab and returns through
an Activity the artifact declares, on the scheme `${applicationId}.paypal`. Nothing to declare.
Details: [plugins/paypal.md](../plugins/paypal.md).

## Klarna

```kotlin
implementation("eu.purse:headless-checkout-klarna:<version>")
```

Brings the Klarna Mobile SDK, which is not on Maven Central. Declare Klarna's repository next to
`mavenCentral()`:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://x.klarnacdn.net/mobile-sdk/")
    }
}
```

Klarna's widget can open a banking or identity app and must return to the **same Activity instance**
that hosts it. Declare the deep link on your checkout Activity, with `singleTask` so no second
instance is created:

```xml
<activity android:name=".CheckoutActivity" android:launchMode="singleTask" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="${applicationId}.klarna" android:host="return" />
    </intent-filter>
</activity>
```

`rememberPaymentElement` passes `"{packageName}.klarna://return"` to Klarna by default, which matches
this filter; its `klarnaReturnUrl` parameter overrides it. Details: [plugins/klarna.md](../plugins/klarna.md).

## A method the session offers but you did not add

The session decides which methods are listed, not your dependencies. If it offers Google Pay,
PayPal or Klarna and the artifact is missing, the SDK logs a warning at `init` naming the
dependency, the method's payment element has no UI, and `submit()` reports the same message as a
`PaymentOutcome.Failed(NoPluginRegistered)`:

```
no plugin registered for klarna/installments — add the `eu.purse:headless-checkout-klarna` dependency to offer this method
```

Add the artifact, or hide the method in this build:

```kotlin
PurseHeadlessCheckout.init(widgetData, disabledMethods = setOf(MethodKey(Partner.Klarna, Method.Installments)))
```
