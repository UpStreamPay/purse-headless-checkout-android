# Plugins (per-PSP reference)

One plugin integrates one `(partner, method)` pair. This directory holds one page per shipped PSP,
covering what makes *that* PSP different: what its `/init` returns, when its native step runs
relative to `/validate`, its UI surface, its quirks, and its failure modes.

| PSP page | Artifact | Partner / method | Family | UI surface |
|---|---|---|---|---|
| **[Google Pay](google-pay.md)** | `headless-checkout-googlepay` (optional) | any partner / `googlepay` (Ingenico, Adyen, generic) | generic-style (one protocol per gateway; registered per partner + method fallback) | Google's own payment sheet (xpay button) |
| **[Klarna](klarna.md)** | `headless-checkout-klarna` (optional) | `klarna` / `installments` | partner (dedicated) | native `KlarnaPaymentView` in your `ViewGroup` |
| **[PayPal](paypal.md)** | `headless-checkout-paypal` (optional) | `paypal` / `wallet` | partner (dedicated) | PayPal's branded `PayPalButton` (xpay), then PayPal's own approval |
| **[Card — Purse vault](card-purse-vault.md)** | `headless-checkout-android` | any partner / `creditcard` with `vault.vendor = purse` | vault (keyed by vault vendor) | SecureFields Compose form |
| **[Redirection](redirection.md)** | `headless-checkout-android` | **whatever the session marks** `template = redirection` | generic **template** (keyed by `configurations.template`) | none — the PSP's own hosted page |

Card and redirection ship in `eu.purse:headless-checkout-android` itself; Google Pay, Klarna and
PayPal are **optional artifacts** (`eu.purse:headless-checkout-googlepay` / `-klarna` / `-paypal`)
that register their plugin when present — see [getting started](../integration/getting-started.md#2-install).

For the module layout and the purse-payment engine around these, see
[architecture/headless-checkout.md](../architecture/headless-checkout.md); for the consumer-facing
API, [integration/consumer-api.md](../integration/consumer-api.md).

---

## What every plugin has in common

```
init:      onSessionSet → ensureClientCompatibility(optional)
react:     onAmountUpdate
validate:  validateUi(optional) → preValidation ─ SDK POSTs /validate ─▶ postValidation
teardown:  destroyClient
```

An **interactive** plugin (all five shipped ones are) is split in two halves, so the protocol stays
unit-testable and Play-Services/Klarna/SecureFields types never leak into `:headless-checkout`:

| Half | Type | Knows about |
|---|---|---|
| **Protocol** | `XxxPlugin : PartnerUiPlugin` + `XxxMapper` | JSON only — `/init` bodies, `/validate` payloads, response parsing |
| **Interactive** | `XxxCoordinator : PaymentCoordinator` | The vendor SDK and its Activity results; `/validate` and redirects come from the base class |

The two halves talk through three seams:

1. **`PluginHost`** — one interface `eu.purse.partnerui` declares and `PurseHeadlessCheckout.host(item)`
   implements. This is why `eu.purse.partnerui` never depends on `eu.purse.checkout` (which depends on it).
2. **`setCollected(map)`** — whatever the native step produced (a sheet token, a `step_id`, a vault
   token, even a live callback) is stashed against the item and arrives in the plugin's
   `PartnerUiContext.collected` at `preValidation`/`postValidation` time.
3. **`setFulfilled(bool)`** — form completeness, for plugins that collect a form. See below.

### Resolution — which plugin handles an item

`PluginRegistry.getPlugin(partner, method, session)`, mirroring web's `getPlugin` →
`getGenericPlugin` → `getPartnerPlugin` chain:

```
disabled?                                          → null
vault.vendor ≠ none                                → vault plugin
configurations.template                            → generic template plugin   (strict equality)
merchant_specifics.integration_mode = redirection   → the redirection generic
exact (partner, method)                            → partner plugin
method                                             → method fallback
                                                   → null
```

So today's five resolve as: card by **vault vendor**, [redirection](redirection.md) by **template**
(or by `integration_mode`), Klarna and [PayPal](paypal.md) by **exact pair**, Google Pay by exact
pair for Ingenico and by **method fallback** for everyone else.

The `integration_mode` step sits **above** the exact-pair lookup because that's where web puts it —
its `getPartnerPlugin` checks it before consulting its own `partner_plugins` table — so a method the
merchant configured to redirect is driven by the redirect template even when a dedicated plugin for
that pair exists. Template matching is **strict equality**: `redirection_suffix` resolves to no
generic at all (web has a test on exactly that, and so does `PluginRegistryTest`).

All five shipped plugins are **registered by default** (`PluginRegistry`'s own `init` block) — the
session's `protocols` entry is the real gate on whether a method is offered, so no app-side setup
call exists to drift out of sync with it. `PurseHeadlessCheckout.init(disabledMethods = …)` opts a
method out of one checkout. `registerGeneric(template, builder)` registers a *template* rather than
a pair, for a generic that isn't bundled here.

### Form completeness gates the payment

A plugin whose shopper-facing step is a *form* reports its validity through `setFulfilled`, and
`isPaymentFulfilled` stays `false` (and `submitPayment` throws `ItemNotFulfilled`) while any item in
the split reports `false`. Today only the card does this — Google Pay's sheet, Klarna's widget and
redirection's hosted page are not forms this SDK validates, and they report nothing, which leaves them
ungated exactly as in web (where those plugins dispatch no `fulfilled` event either; the redirection
generic explicitly dispatches `fulfilled: true` the moment its `/init` returns). Details:
[consumer-api §6](../integration/consumer-api.md#form-completeness-gates-the-payment).

### Shared: redirects and 3-D Secure

All five coordinators resolve "what does `/validate` want next?" through the same two pieces, shared
verbatim from `eu.purse.partnerui.redirect` — a sibling of `plugins/`, not part of any one plugin,
mirroring web, where the same logic lives in `sdk` and `common` rather than under a plugin folder:

**`RedirectionResolver.resolve(validateResponse, partner, method, sessionReturnUrl)`** — ports web's
`manageRedirection`, same priority order:

| Priority | Response shape | Action |
|---|---|---|
| 1 | per-item `plugin_result.logs.redirection_json` + `redirection_url` | `PostForm` |
| 2 | global `redirection.url` (+ per-item `redirect_url_post_params`) | `Get`, or `PostForm` when post params are present |
| 3 | global `redirection.form` | `PostHtml` (raw HTML to inject and auto-submit) |
| — | none of the above | `null` — the common case; resolve the payment result instead |

Once resolved, every coordinator runs the action through the same **`RedirectDispatcher`** step
before touching its launcher: the host's `RedirectionDelegate.delegateRedirection(action)` — which
`PurseHeadlessCheckout` wires to the merchant's `PurseCheckoutHooks.onRedirection`, web's
`redirectionHandler` — gets first refusal. `true` means the merchant took the redirect over and the
SDK opens nothing; `false` (the default, no hook) opens the shared in-app WebView; a hook that
throws opens nothing either, and the coordinator reports `"onRedirection hook failed: …"` on the
element listener and releases its re-entrancy guard so the shopper can submit again. Only the
delegated and launched paths dispatch `PluginEvent.Redirect` / `onRequiresAction`.

Two shapes are deliberately **dropped**, because neither is a genuine PSP/bank redirect:

- web's `should_submit_widget` fallback — "resubmit the widget's own page", meaningless headless
  (web's own headless-checkout gates it out whenever the split has a primary, which these always are);
- a target that starts with the session's own return URL, or carries the `purse-redirection-data`
  marker — that's the widget's **confirmation page**, whose JWT decodes to the same
  `authorization_status` the very same `/validate` call already returned in `global_status`.

That JWT also carries `payment_id`, read (unsigned — it arrived over the already-authenticated
`/validate` response) by `RedirectionResolver.extractWidgetConfirmationPaymentId` and surfaced on
every coordinator as `Listener.onPaymentId`. Purely informational — a capture tool needs it, a
payment does not — and absent whenever a *genuine* redirect took the confirmation one's place.

**`RedirectionCheckoutActivity`** (launched via `RedirectionCheckoutContract`) renders the action in
an **in-app WebView**, not a Custom Tab: a Custom Tab can neither POST nor be observed navigating. It
finishes once the shopper reaches this app's own deep link (`${applicationId}.redirect`), the flow's
`returnUrlPrefix` (the session's return URL, attached by the caller as a "known terminal page" hint),
or a `purse-redirection-data` URL. It is not exported: the deep link lands on `RedirectReturnActivity`,
which forwards only the URI, so no other app can make the WebView load a page or HTML.
Its settings are a browser's minus what a PSP page never needs: JavaScript and DOM storage
(`localStorage` / `sessionStorage`, which iDEAL and other hosted pages rely on) are on; navigation is
`https` only, and file/content access, popups and mixed content are off.

> **Caveat:** some ACS providers refuse to render inside an embedded WebView (an anti-phishing measure
> some card networks require). If one partner's challenge breaks there, that partner needs its own
> redirect path — reverting this one breaks POST redirects for everyone.

### A returned redirect is not an outcome

Every coordinator reports `RedirectionCheckoutResult.Approved` as
`PaymentOutcome.ReturnedFromRedirect` and **does not chase the real outcome**. This is a confirmed
backend pain point, not laziness: a real 3-D Secure challenge's final redirect is constructed
server-side by the bank/PSP and can land on a Purse API gateway URL that `401`s on the session's own
client-facing `api_key`. Re-check status independently instead — `checkout.sessionTransactions()`, or
the demo's own alfred-backed "Get transactions" tool, whose answer is what settles its result screen.
See [decisions/pain-points-recap.md](../decisions/pain-points-recap.md).

### Reading `sessionTransactions()` — operations, not payments

`GET /{entity}/sessions/{session}` (behind `checkout.sessionTransactions()` and the demo's "Get
transactions") lists **operations**, so a row count is not a payment count. A single auto-captured
payment legitimately shows two rows:

| `action` | `id` | `parent_transaction_id` | `partner_reference` |
|---|---|---|---|
| `CAPTURE` | `6848ec32…` | `28e3c89a…` | `e62cd1dd…` |
| `AUTHORIZE` | `28e3c89a…` | — | `e62cd1dd…` |

The capture's `parent_transaction_id` is the authorize's `id`, and both share one
`partner_reference` — one payment, two steps, milliseconds apart.

**Two things decide whether you see one row or two, and neither is the SDK:**

1. **The Payment API version.** `v2` lists each operation as its own row; `v1` aggregates them into a
   single transaction. The same payment therefore reads as two rows on `v2` and one on `v1` — which
   is the usual explanation for a mobile-vs-web discrepancy, since the version comes from the
   session's own `api_url`.
2. **`capture_mode`** (`AUTOMATIC` vs `MANUAL`), set server-side when the orchestration session is
   created — it decides whether a capture happens at all, not how it is listed.

**No SDK code influences either** — nothing capture-related is ever sent on the wire.

So before treating a row count as a duplicate, check `parent_transaction_id` and `partner_reference`.
Two rows with the *same* action and unrelated references would be a genuine duplicate; anything else
is one payment. Equally, a lone `AUTHORIZE` on an `AUTOMATIC` session is worth a second look in the
other direction — it means the capture hasn't landed.

### Where each PSP's native step sits relative to `/validate`

The single biggest per-PSP difference, and the reason these pages exist:

```
Google Pay   sheet ──▶ token ──▶ /validate                  (native step BEFORE)
Card         form ──▶ vault token ──▶ /init ──▶ /validate   (native step BEFORE)
PayPal       /init ──▶ order ──▶ approve out-of-app ──▶ /validate  (native step BEFORE)
Klarna       /validate ──▶ authorize() (in postValidation)  (native step AFTER, inside submitPayment)
Redirection  /init ──▶ /validate ──▶ hosted page            (NO native step — the redirect IS the step)
```

PayPal is the only one whose step leaves the app **and comes back to a live plugin** — Google Pay's
sheet and the card form stay in-app, and redirection's hosted page never returns to one. Its return
Activity ships in the PayPal artifact's manifest (scheme `${applicationId}.paypal`) and the PayPal SDK supplies the
matching `redirect_uri` itself, so integrators declare nothing.
