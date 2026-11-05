# Getting started — Purse Headless Checkout for Android

The SDK owns the payment engine (session, methods, split, `/validate`); you own every pixel. This
page takes you from an empty project to a first payment. Everything else is in the
[recipes](recipes.md) and the [API reference](consumer-api.md).

## 1. Requirements

| | |
|---|---|
| Android | minSdk 26; the libraries compile against API 37 |
| UI | Jetpack Compose (the payment elements are composables; a View-based app hosts them in a `ComposeView`) |
| Backend | your server creates a Purse **Client Session** and hands the app its base64 `widget.data` string |

## 2. Install

The SDK is on **Maven Central** (`eu.purse` group). One artifact carries the engine, the payment
elements and the card and redirection methods; Google Pay, Klarna and PayPal each bring a partner
SDK, so they are separate artifacts you add only if you offer them:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("eu.purse:headless-checkout-android:<version>")      // engine, payment elements, card, redirection methods
    implementation("eu.purse:headless-checkout-googlepay:<version>")    // only if you offer Google Pay
    implementation("eu.purse:headless-checkout-klarna:<version>")       // only if you offer Klarna
    implementation("eu.purse:headless-checkout-paypal:<version>")       // only if you offer PayPal
}
```

Pin one version for all of them (they release together). The current version is on the
[releases page](https://github.com/UpStreamPay/purse-headless-checkout-android/releases), which also
carries the generated API reference; your IDE shows the same KDoc from the published javadoc jar.

A partner artifact registers its payment method on its own once it is on the classpath. If the
session offers a method whose artifact you did not add, the SDK logs a warning at `init` naming the
dependency to add, and the method's payment element has no UI — `submit` reports the same message
as a `PaymentOutcome.Failed(NoPluginRegistered)` — and building that element disables the method
with `PARTNER_UI_FATAL_ERROR` for the rest of the session, as web does.

Everything else ships in the artifacts' own manifests: the `INTERNET` permission, Google Pay's
`meta-data`, the redirect WebView Activity (3-D Secure, redirection methods) and PayPal's return
Activity. R8/ProGuard needs no rules from you — each artifact carries its consumer rules.

**Only if you offer Klarna**, two more things. Klarna's SDK is not on Maven Central:

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

and Klarna's widget returns to the Activity that hosts it, so that Activity declares the deep link:

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

<details>
<summary>Working from a checkout of this repository instead (SDK contributors)</summary>

```kotlin
// settings.gradle.kts of your app
includeBuild("../purse-headless-checkout-android") {
    dependencySubstitution {
        substitute(module("eu.purse:headless-checkout-android")).using(project(":headless-checkout"))
        substitute(module("eu.purse:headless-checkout-googlepay")).using(project(":partner-googlepay"))
        substitute(module("eu.purse:headless-checkout-klarna")).using(project(":partner-klarna"))
        substitute(module("eu.purse:headless-checkout-paypal")).using(project(":partner-paypal"))
    }
}
```

A composite build shares no plugin classpath: keep your Android Gradle Plugin and Kotlin versions
aligned with this repository's `gradle/libs.versions.toml`.
</details>

## 3. Create the checkout

```kotlin
import eu.purse.checkout.PurseHeadlessCheckout
import eu.purse.core.environment.PurseEnvironment

val checkout = PurseHeadlessCheckout.init(
    widgetData,                                          // from your backend
    lockedEnvironment = PurseEnvironment.PRODUCTION,     // release builds: never trust the session's env
)
```

`init` throws `PurseSessionException.SessionMalformed` when the blob cannot be decoded, and
`PurseSessionException.EnvironmentNotAllowed` (`ENVIRONMENT_NOT_ALLOWED`) when a release
(non-debuggable) build is handed the internal `TEST` / `DEVELOPMENT` tier — create sessions on sandbox
or production ([details](consumer-api.md#internal-tiers-in-a-release-app)). Call
`checkout.teardown()` when the checkout screen is dismissed for good. One session pays once; for a
new cart, mint a new session and call `checkout.setSession(widgetData)`. Hold the checkout in a
`ViewModel`, and snapshot it with `saveState()` / `restore()` so a process death mid-redirect does
not lose the payment ([recipe 16](recipes.md#16-survive-process-death)).

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
        override fun onOutcome(outcome: PaymentOutcome) { /* step 6 */ }
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

`onOutcome` receives a sealed `PaymentOutcome`:

```kotlin
override fun onOutcome(outcome: PaymentOutcome) = when (outcome) {
    is PaymentOutcome.Completed -> if (outcome.isSuccess) showSuccess() else showDeclined(outcome.status)
    PaymentOutcome.ReturnedFromRedirect -> confirmWithBackend()          // below
    PaymentOutcome.Canceled -> Unit                                      // the shopper backed out, nothing changed
    is PaymentOutcome.Failed -> showError(outcome.error)                 // a PurseException with a stable code
}
```

- `Completed` — `/validate` answered directly. `status` is the API's verdict: `PaymentStatus.SUCCESS`
  is a payment, anything else a declined payment.
- `ReturnedFromRedirect` — the shopper came back from a redirect (3-D Secure, PayPal, a PSP page).
  That is **not** an outcome. Ask your backend: it reads the session with its merchant credentials,
  which the app cannot (the Payment API answers `401` to the client key). See
  [recipe 9](recipes.md#9-confirm-after-a-redirect-ask-your-backend). `paymentStatus()` /
  `awaitPaymentOutcome()` are deprecated for that reason.

A `Failed` before any submit (a widget that failed to load) is not a failed payment: leave the
shopper on the screen with a message; the method is disabled on `paymentMethods` accordingly.

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
./gradlew :app:installDebug      # Gradle provisions its own JDK; nothing to export
```

## Next

- [Recipes](recipes.md) — one task per section: saved cards, hooks, theme, errors, session refresh, BNPL simulation.
- [API reference](consumer-api.md) — every function, flow and type.
- [Payment methods by platform](../payment-methods-matrix.md) — what this SDK supports versus web.
