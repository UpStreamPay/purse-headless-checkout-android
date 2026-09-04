# Payment methods by platform — web vs Android

Source of truth, checked on 2026-09-04:

- **Web**: `usp-widget/packages/partner-ui/src/plugins/{vaults,generics,partners}/index.ts` and
  `packages/headless-checkout/src/session/payment/mops/secondaries/configs/configs.ts`.
- **Android**: `partner-ui/.../PluginRegistry.kt` (`init` block) and
  `headless-checkout/.../payment/secondary/SecondaryConfig.kt` (`SecondaryConfigs`).

Legend: ✅ shipped · 🧪 ported, not validated on a sandbox · ❌ not available · — not applicable to
the platform.

## How a method gets a plugin (same order on both platforms)

| Step | Key | Web | Android |
|---|---|---|---|
| 1 | `configurations.vault.vendor` | `purse`, `pci_proxy` → hosted-fields card | `purse` → `CardPlugin` (SecureFields). `pci_proxy` ❌ |
| 2 | `configurations.template` | `redirection`, `sdpx`, `lyra`, `alma`, `applepay`, `googlepay` | `redirection` only |
| 3 | `merchant_specifics.integration_mode = redirection` | → generic redirection | → generic redirection |
| 4 | exact `(partner, method)` | 17 partners (table below) | `(klarna, installments)`, `(paypal, wallet)`, `(ingenico, googlepay)` |
| 5 | method fallback | none | `googlepay` → `GooglePayPlugin` (any partner) |

Note the one structural difference: web keys Google Pay on the `googlepay` **template**; Android
keys it on the pair plus a method fallback. Same outcome for today's sessions, different if a
merchant ever sets `template = googlepay` on a non-Google-Pay method.

## Primary methods

### Cards

| Method | Web | Android | Notes |
|---|---|---|---|
| Card, Purse vault (`vault.vendor = purse`, any partner) | ✅ hosted fields | ✅ SecureFields Android, Compose form, 3-D Secure via redirect WebView | Android's only card path. Saved-card CVV re-entry ✅ both. |
| Card, PCI Proxy vault (`vault.vendor = pci_proxy`) | ✅ | ❌ | A `pci_proxy` saved token needing a CVV has no form on Android. |
| Card, PSP-hosted fields without a vault: aci, adyen, checkout, dalenys, hipay, ingenico, payline, stripe, worldpay `creditcard` | ✅ one plugin each | ❌ `NoPluginRegistered` | By design: Android only pays cards through the Purse vault. |
| Card, `sdpx` template: sipsv2, sogenactif, mercanet | ✅ | ❌ | |
| Card, `lyra` template: lyra, payzen, sogecommerce, systempay, scellius | ✅ Krypton form | ❌ | |
| Card, external 3DS extras: payline, centralpay, axepta, worldpay, lyra family | ✅ via `vaultExtras` | ❌ | Android has no `vaultExtras` equivalent. |
| Any `creditcard` with `integration_mode = redirection` (e.g. ACI) | ✅ | ✅ generic redirection | |

### Wallets and x-pays

| Method | Web | Android | Notes |
|---|---|---|---|
| Google Pay, gateway ingenico | ✅ template | ✅ Play Services sheet + Worldline product-320 refinement | |
| Google Pay, gateway adyen | ✅ | 🧪 parser ported (`partners/Adyen.kt`), no sandbox run | |
| Google Pay, other gateways (default builder) | ✅ | 🧪 `partners/Default.kt` | |
| Google Pay via Stripe.js (`stripe-googlepay`) | ✅ | ❌ | Stripe-rendered button, web-only concept. |
| Google Pay via Lyra Krypton | ✅ | ❌ | |
| Apple Pay (template, adyen/ingenico/default; `adyen-applepay`, `stripe-applepay`, Lyra) | ✅ | — | iOS only. |
| PayPal wallet, pay now | ✅ JS popup | ✅ PayPal Android SDK browser switch | Android drops the echoed approval URL in `/validate`; see [paypal.md](plugins/paypal.md). |
| PayPal wallet, saved token | ✅ | ✅ | No CVV, captures on submit. |
| PayPal wallet, vaulting (create a token) | ✅ | ❌ | Deferred. |
| PayPal BNPL (`bnpl`) | ✅ popup or redirection; has `ensureClientCompatibility` | ❌ dedicated; ✅ only if `integration_mode = redirection` | |
| PayPal Fraudnet fingerprinting on redirection | ✅ | ❌ | |

### BNPL, instalments, invoice

| Method | Web | Android | Notes |
|---|---|---|---|
| Klarna `installments` | ✅ Klarna JS SDK | ✅ native `KlarnaPaymentView`; `authorize()` after `/validate` on both | Android needs a host-app deep link, see consumer API §7. |
| Alma (template `alma`) | ✅ Alma widget | ❌ | Redirection mode would work. |
| Riverty `invoice`, `3x`, `6x`, `9x`, `12x` | ✅ form (IBAN) + client-side simulation text | ❌ dedicated; ✅ if `template/integration_mode = redirection` | |
| Younited `installments` | ✅ | ❌ | |
| Scalapay `cb3x-paybylink`, `cb4x-paybylink` | ✅ email form | ❌ | |
| Loan simulation (`simulable` label, `POST /simulation`, `/multi`) | ✅ | ✅ `simulateLoan` / `simulateLoans` | Counts and amounts only, no per-instalment dates, both platforms. |

### Other

| Method | Web | Android | Notes |
|---|---|---|---|
| HiPay MB Way (`mbway`) | ✅ phone form | ❌ | |
| Any method with `template = redirection` (Fintecture, Bizum, Oney, Sequra, …) | ✅ | ✅ generic redirection, `partner_data.browser` from the WebView | US-53 sandbox validation still open. |
| Saved-card management: rename, delete, favorite, delete all (`wallet_session`) | ✅ | ✅ | |
| Save a fresh card (`payment_preferences.registration`, `token_label`) | ✅ | ✅ | |

## Secondary methods (gift cards, loyalty, vouchers)

Same keys on both platforms; each is a config, not a plugin.

| Key | CVV | Take policy | Web | Android |
|---|---|---|---|---|
| `illicado-giftcard` | always | none (splittable) | ✅ | ✅ |
| `buybox-giftcard` | unless Storeland PAN | none | ✅ | ✅ |
| `maxxing-loyaltycard` | no | max | ✅ | ✅ |
| `ogloba-giftcard` | no | none | ✅ | ✅ |
| `mybeezbox-giftcard` | no | none | ✅ | ✅ |
| `easy2play-giftcard` | always | none | ✅ | ✅ |
| `easy2play-voucher` | no | max | ✅ | ✅ |
| `ancv-connect` (holder → poll every 10 s, 5 min) | no | max | ✅ | ❌ config not shipped; `pollForToken` / `classifyInitError` hooks exist so a host can register one |
| Saved gift cards (`integrated_tokens` on a secondary) | ✅ | ✅ | |
| Keep a temporary gift card (registration) | ✅ | ✅ | |
| Host-registered issuer config | — (static table) | ✅ `SecondaryConfigs.register` | Android-only extension point. |
| Splitter autofill middlewares (voucher → loyalty → primary) | ✅ | ❌ | |

## Reading the matrix

- **Android covers every method a merchant can run through the Purse vault, a redirection, or one
  of three native SDKs** (Google Pay, PayPal, Klarna). Everything else web supports is a PSP-hosted
  form living in the browser, which has no native equivalent and is not planned.
- **The three gaps worth a decision** are `pci_proxy` cards, `ancv-connect`, and the PayPal BNPL /
  vaulting pair. The rest of the ❌ column is "works on Android if the merchant switches the method
  to `redirection` mode".
- Rows marked 🧪 are the scope of US-53 (sandbox validation per PSP).
