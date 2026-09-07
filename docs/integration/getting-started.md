# Getting started — Purse Headless Checkout for Android

The SDK owns the payment engine (session, methods, split, `/validate`); you own every pixel. This
page takes you from an empty project to a first payment. Everything else is in the
[recipes](recipes.md) and the [API reference](consumer-api.md).

## 1. Requirements

| | |
|---|---|
| Android | minSdk 26, compileSdk 36 |
| UI | Jetpack Compose (the payment elements are composables; a View-based app hosts them in a `ComposeView`) |
| Backend | your server creates a Purse **Client Session** and hands the app its base64 `widget.data` string |

## 2. Install

The SDK is not published to a Maven repository yet. Consume it as Gradle source modules from this
repository (a checkout or a git submodule):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://x.klarnacdn.net/mobile-sdk/")   // Klarna's SDK is not on Maven Central
    }
}
includeBuild("../purse-headless-checkout-android") {
    dependencySubstitution {
        substitute(module("eu.purse:headless-checkout")).using(project(":headless-checkout"))
        substitute(module("eu.purse:partner-ui")).using(project(":partner-ui"))
        substitute(module("eu.purse:core")).using(project(":core"))
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("eu.purse:headless-checkout")   // pulls :partner-ui and :core transitively
}
```

A composite build shares no plugin classpath: keep the Android Gradle Plugin and Kotlin versions of
your app aligned with this repository's `gradle/libs.versions.toml`.

Everything the plugins need (Play Services Wallet, PayPal SDK, Klarna SDK, SecureFields) comes
with `:partner-ui`. The `INTERNET` permission, Google Pay's `meta-data`, the redirect WebView
Activity and PayPal's return Activity ship in the SDK's manifest.

**Manifest: one thing, and only if you offer Klarna.** Klarna's widget returns to the Activity that
hosts it, so that Activity declares the deep link:

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

## 3. Create the checkout

```kotlin
import eu.purse.checkout.PurseHeadlessCheckout
import eu.purse.core.environment.PurseEnvironment

val checkout = PurseHeadlessCheckout.init(
    widgetData,                                          // from your backend
    lockedEnvironment = PurseEnvironment.PRODUCTION,     // release builds: never trust the session's env
)
```

`init` throws `PurseSessionException.SessionMalformed` when the blob cannot be decoded. Call
`checkout.teardown()` when the checkout screen is dismissed for good. One session pays once; for a
new cart, mint a new session and call `checkout.setSession(widgetData)`.

## 4. Show the methods

Reactive state is `StateFlow`. In Compose, `collectAsState()`:

```kotlin
val methods by checkout.paymentMethods.collectAsState()      // primaries + gift cards, live disabled states
val tokens by checkout.paymentTokens.collectAsState()        // saved cards / saved PayPal accounts
val remaining by checkout.remainingAmountToPay.collectAsState()
val fulfilled by checkout.isPaymentFulfilled.collectAsState()

methods.filterIsInstance<PrimaryMethod>().forEach { method ->
    MethodRow(label = method.id, icon = checkout.iconUrl(method), enabled = method.disabled == null)
}
```

## 5. Render the selected method and pay

One composable renders any method or saved token, wiring the native SDK, its launchers and its
`/init` for you:

```kotlin
@Composable
fun SelectedMethod(checkout: PurseHeadlessCheckout, item: PaymentItem) {
    val element = rememberPaymentElement(checkout, item, listener = object : PaymentElementListener {
        override fun onResult(summary: String, globalStatus: String?) { /* step 6 */ }
        override fun onCanceled() { }
        override fun onError(message: String?) { }
    })
    LaunchedEffect(item.id) {
        when (item) {
            is PrimaryMethod -> checkout.setPrimary(item)      // not for Google Pay / PayPal: they select themselves
            is PaymentToken -> checkout.setPrimaryToken(item)
            else -> Unit
        }
    }

    PaymentElementUi(element, Modifier.fillMaxWidth())        // card form / wallet button / Klarna widget / nothing
    if (!element.selfSubmitting) {
        Button(onClick = { element.submit() }, enabled = element.ready) { Text("Pay") }
    }
}
```

| Method | `hasUi` | `selfSubmitting` | You render |
|---|---|---|---|
| Card (Purse vault), saved card needing its CVV | ✓ | — | the form + your Pay button |
| Google Pay, PayPal | ✓ | ✓ | their branded button, nothing else |
| Klarna | ✓ | — | the widget + your Pay button |
| Redirection method, saved card without CVV, saved PayPal | — | — | just your Pay button |

Create the element **when the shopper selects the row**, not for every row: for Klarna and
redirection methods creation runs a `POST /init`.

## 6. Read the outcome

`onResult(summary, globalStatus)`:

- `globalStatus != null` — `/validate` answered directly. `"SUCCESS"` is a success, anything else a
  declined payment.
- `globalStatus == null` — the shopper came back from a redirect (3-D Secure, PayPal, a PSP page).
  That is **not** an outcome. Ask the session:

```kotlin
when (checkout.awaitPaymentOutcome()) {              // polls the session's transactions, 60 s max
    is PaymentSessionStatus.Confirmed -> showSuccess()
    is PaymentSessionStatus.Failed -> showFailure()
    is PaymentSessionStatus.Pending -> showStillProcessing()
}
```

`onError` before any submit (a widget that failed to load) is not a failed payment: leave the
shopper on the screen with a message.

## 7. Gift cards

```kotlin
val giftCard = checkout.secondaryMethods.first()
val token = withContext(Dispatchers.IO) { checkout.getSecondaryToken(giftCard, pan, cvv) }   // reads the balance
checkout.applySecondary(token)                       // draws it into the split; remainingAmountToPay moves
```

When gift cards cover the whole amount, `isPaymentFulfilled` is `true` with no primary and
`checkout.submitPayment()` sends them alone.

## 8. Try it

The `:app` module is a complete reference: session → pay → result, every method above, gift cards,
saved cards, BNPL simulation. It mints sessions through `alfred` (the `usp-widget` dev server), see
[testing with alfred](../internal/testing-with-alfred.md).

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
```

## Next

- [Recipes](recipes.md) — one task per section: saved cards, hooks, theme, errors, session refresh, BNPL simulation.
- [API reference](consumer-api.md) — every function, flow and type.
- [Payment methods by platform](../payment-methods-matrix.md) — what this SDK supports versus web.
