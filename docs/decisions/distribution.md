# Distribution

How the SDK is published and why (SDK-12219, lots L0/L2 of the story map).

## Channel: Maven Central, not a private Nexus

The story's wording ("le Nexus privé UpStreamPay") predates the sibling
[securefields-android](https://github.com/UpStreamPay/purse-securefields-android), whose
distribution review settled the channel for Purse's Android SDKs: **Maven Central** under the
`eu.purse` namespace, MIT-licensed, signed, with sources and Javadoc — the way every payment SDK
merchants already use (Stripe, Adyen, Checkout.com, Primer) ships. Its US-10 acceptance criterion
says it plainly: *the configuration copies the sibling repository that already publishes
correctly*. This repository therefore reuses securefields-android's publishing stack verbatim
(vanniktech `maven-publish` → Central Portal, same GPG key, same secrets, same three workflows),
and no Nexus exists in the organisation's Android tooling to publish to.

What a private registry would have cost: every merchant configures credentials and a repository
URL before the first `implementation(...)` compiles, and the artifact is invisible to IDE
completion and dependency scanners. What Central costs us: a verified namespace (done, shared),
signing (done, shared) and a complete POM (done, root `build.gradle.kts`).

The client library holds no secret — the moat is the Payment API, session minting and the vault,
all server-side — so, like the sibling, the code is MIT ([LICENSE](../../LICENSE)) and the POM says
so. The GitHub repository itself stays private for now; Maven Central does not require a public
source repository, only the sources jar the build attaches.

## Shape: one engine artifact, plus one optional artifact per partner SDK

```
eu.purse:headless-checkout-android     ← every merchant: engine, payment elements, plugin contract,
                                         redirect WebView, card (SecureFields), redirection template
eu.purse:headless-checkout-googlepay   ← only if you offer Google Pay: plugin + Play Services Wallet
eu.purse:headless-checkout-klarna      ← only if you offer Klarna: plugin + Klarna Mobile SDK (+ Klarna's Maven repository)
eu.purse:headless-checkout-paypal      ← only if you offer PayPal: plugin + PayPal Android SDK
```

Two decisions, taken on 2026-09-07 after building both alternatives:

**The engine is one artifact.** It started as three Gradle modules (`:core`, `:partner-ui`,
`:headless-checkout`) mirroring the web packages; publishing them as three artifacts made two of
them invisible-but-present dependencies merchants never type. They are now three packages of one
module, `:headless-checkout`, with unchanged names. The dependency direction between the packages
(`checkout → partnerui → core`, never back) is a documented convention instead of a module boundary.

**Partners that bring their own SDK are optional artifacts.** The line that decided it: the Klarna
Mobile SDK is not on Maven Central, so bundling it means *every* merchant declares Klarna's Maven
repository, Klarna customer or not. Google Pay and PayPal follow the same rule for consistency — a
card-only merchant embeds none of Play Services Wallet, the Klarna SDK or the PayPal SDK (nor their
manifest entries and transitive Braintree/OkHttp). A partner artifact contributes a `PartnerModule`
(its plugins for `PluginRegistry`, and how its methods render) discovered through `ServiceLoader`
from `META-INF/services`, so adding the Gradle dependency is the whole integration. Card and
redirection stay in the engine because every merchant has a card method and the redirection template
costs nothing (no partner SDK).

The price is that a merchant must know that offering Google Pay means one more dependency. Getting
started says it in the install block, and the SDK says it at runtime: `PurseHeadlessCheckout.init`
warns in Logcat, naming the dependency, when the session offers a method whose artifact is absent,
and that method's payment element has no UI while `submit` reports the same message. A fully
bundled single artifact was also built and rejected because of the Klarna repository.

**No API break.** Kotlin packages, classes and `rememberPaymentElement`'s signature are unchanged;
the SDK had not been published yet, so the only "existing integration" is the demo, whose dependency
list grew by three lines.

## Versioning

Semantic, computed from conventional commits by release-please through a reviewed release PR, and
carried by the git tag (see [release.md](../contributing/release.md#versioning)).
All four artifacts share one version and are published together: a merchant pins one version for
all of them (a BOM is not worth its weight at four artifacts). `apiCheck` makes the promise
verifiable: a change to a `*/api/*.api` dump that removes or alters a signature must ship as a breaking change (a major from 1.0.0, a minor while on 0.x).
