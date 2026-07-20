# Getting Started

Add the SecureFields Android SDK, decode a Purse session, and get to the point where the CB
(card) method can render. This is the primary integration path for this POC — see
[POC Scope](../decisions/poc-scope.md) for why CB gets full depth while PayPal/Illicado are
"starter."

---

## Table of Contents

- [Step 1 — Add the SecureFields Android SDK dependency](#step-1--add-the-securefields-android-sdk-dependency)
- [Step 2 — Add the Internet permission](#step-2--add-the-internet-permission)
- [Step 3 — Obtain a session](#step-3--obtain-a-session)
- [Step 4 — Resolve the environment](#step-4--resolve-the-environment)
- [Step 5 — Find the CB method in the session](#step-5--find-the-cb-method-in-the-session)
- [Step 6 — Initialize SecureFields for that method](#step-6--initialize-securefields-for-that-method)
- [See also](#see-also)

---

## Step 1 — Add the SecureFields Android SDK dependency

The SDK is published to **Maven Central** — no repository configuration or credentials are
needed, unlike this project's own `:core` / `:dropin` modules (see
[Project Setup](../internal/project-setup.md)), which are internal-only for now.

In `app/build.gradle.kts` (CB capture lives in the demo `:app`, in `ui.primaries`):

```kotlin
dependencies {
    implementation("eu.purse:securefields-android:<latest-version>")
}
```

> **Recommended — enable GPG signature verification.** Gradle verifies checksums automatically;
> GPG signature verification (confirming the artifact was signed by Purse's key) is opt-in but
> strongly recommended for a payment integration:
>
> ```bash
> ./gradlew --write-verification-metadata pgp,sha256 help
> ```
>
> Commit the generated `gradle/verification-metadata.xml`.

The current `gradle/libs.versions.toml` (see the repo root) doesn't yet have an entry for this —
add one under `[libraries]` when the dependency is wired in:

```toml
[libraries]
securefields-android = { group = "eu.purse", name = "securefields-android", version = "<latest-version>" }
```

---

## Step 2 — Add the Internet permission

The SDK's AAR cannot add this for you — declare it in the app's own manifest:

```xml
<!-- app/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
```

---

## Step 3 — Obtain a session

Unlike SecureFields (which only needs a `tenantId`), the Drop-in needs the full session payload
described in [Session & Protocols](../architecture/session-and-protocols.md) — `widget.data`,
base64-decoded, giving `apiKey`, `apiUrl`, `environment`, `entityId`, and `paymentSession`
(which contains `protocols`, the list of available methods).

For this POC, the demo app can accept that base64 string directly (pasted in, or hardcoded from a
sandbox session created via the merchant backend / Postman) rather than implementing a full
session-retrieval flow — how a real merchant app obtains it is outside this POC's scope.

---

## Step 4 — Resolve the environment

```kotlin
enum class PurseEnvironment(val paymentApiRoot: String) {
    DEVELOPMENT("https://api.purse-dev.com/payment/v1"),
    TEST("https://api.purse-test.com/payment/v1"),
    SANDBOX("https://api.purse-sandbox.com/payment/v1"),
    PRODUCTION("https://api.purse-live.com/payment/v1"),
}
```

This is a **different environment enum than SecureFields' own `VaultEnvironment`** — the Drop-in
talks to the Payment/Orchestration API (`/init`, `/validate`), while SecureFields talks to the
Vault tokenization API (`/forms/secure-fields`). Both need to be set consistently to the same
tier (sandbox with sandbox, production with production) but they are separate configuration
values — see [Session & Protocols](../architecture/session-and-protocols.md#payment-api--environments-and-endpoints).

---

## Step 5 — Find the CB method in the session

Walk `paymentSession.protocols` for an entry whose `configurations.vault.vendor == "purse"` — that's
the one this POC's CB integration targets (see
[Session & Protocols — MethodConfiguration](../architecture/session-and-protocols.md#methodconfiguration)
for why other vendors are out of scope). Its `configurations.vault.supported_brands` (e.g.
`["CARTE_BANCAIRE", "VISA", "MASTERCARD"]`) is what to pass as SecureFields' `brands` config.

---

## Step 6 — Initialize SecureFields for that method

From here, this is exactly the SecureFields Android SDK's own Getting Started flow (see the
sibling `vault-android` repo's `docs/integration/getting-started.md`), with `brands` sourced from
the method found in Step 5, and `tenantId` sourced from a **different, session-level** field:
`paymentSession.vault.vendor_front_data.purse.tenant_id` — not `entityId`. See [Session &
Protocols — Session-level vault
config](../architecture/session-and-protocols.md#session-level-vault-config--where-tenantid-actually-comes-from)
for why these are two distinct identifiers.

```kotlin
@Composable
fun CardBForm(cbMethod: PrimaryMethod, vaultTenantId: String, environment: VaultEnvironment) {
    val secureFields = rememberSecureFields(
        config = SecureFieldsConfig(
            brands = cbMethod.supportedBrands.map { it.toVaultBrand() },
            fields = SecureFieldsFieldsConfig(
                cardNumber = VaultFieldConfig(placeholder = "Card number"),
                expDate    = VaultFieldConfig(placeholder = "MM / YY"),
                cvv        = VaultFieldConfig(placeholder = "CVV"),
            )
        ),
        environment = environment,
        tenantId    = vaultTenantId,   // paymentSession.vault.vendor_front_data.purse.tenant_id
    )
    LaunchedEffect(secureFields) { secureFields.render() }
    VaultForm(sdk = secureFields, modifier = Modifier.fillMaxWidth().height(168.dp))
}
```

Continue in [Payment Methods — CB](payment-methods.md#cb-full) for what happens after
`secureFields.submit()` returns a token — it still needs to be submitted to the Drop-in's own
`/init` + `/validate` calls, which is a separate step from SecureFields' own tokenization POST.

---

## See also

- [Payment Methods](payment-methods.md)
- [Session & Protocols](../architecture/session-and-protocols.md)
- [Project Setup](../internal/project-setup.md)
- `../vault-android/docs/integration/getting-started.md` — the SecureFields SDK's own guide (Compose and View-system variants, remote log monitoring)
