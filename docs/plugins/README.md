# Plugins (per-PSP reference)

One plugin integrates one `(partner, method)` pair. This directory holds one page per shipped PSP,
covering what makes *that* PSP different: what its `/init` returns, when its native step runs
relative to `/validate`, its UI surface, its quirks, and its failure modes.

| PSP page | Partner / method | Family | UI surface |
|---|---|---|---|
| **[Google Pay](google-pay.md)** | any partner / `googlepay` (Ingenico, Adyen, generic) | generic-style (one protocol per gateway; registered per partner + method fallback) | Google's own payment sheet (xpay button) |
| **[Klarna](klarna.md)** | `klarna` / `installments` | partner (dedicated) | native `KlarnaPaymentView` in your `ViewGroup` |
| **[Card — Purse vault](card-purse-vault.md)** | any partner / `creditcard` with `vault.vendor = purse` | vault (keyed by vault vendor) | SecureFields Compose form |

For the module layout and the purse-payment engine around these, see
[architecture/headless-checkout.md](../architecture/headless-checkout.md); for the consumer-facing
API, [integration/consumer-api.md](../integration/consumer-api.md).

---

## What every plugin has in common

```
init:      onSessionSet → ensureClientCompatibility(optional)
react:     onAmountUpdate / onDataUpdate
validate:  validateUi(optional) → preValidation ─ SDK POSTs /validate ─▶ postValidation
teardown:  destroyClient
```

An **interactive** plugin (all three shipped ones are) is split in two halves, so the protocol stays
unit-testable and Play-Services/Klarna/SecureFields types never leak into `:headless-checkout`:

| Half | Type | Knows about |
|---|---|---|
| **Protocol** | `XxxPlugin : PartnerUiPlugin` + `XxxMapper` | JSON only — `/init` bodies, `/validate` payloads, response parsing |
| **Interactive** | `XxxCoordinator` (+ `XxxRequest`, `XxxConfig`) | The vendor SDK, Android lifecycle, redirects |

The two halves talk through three seams:

1. **`XxxHost`** — an interface the plugin declares and `PurseHeadlessCheckout` implements
   (`googlePayHost(item)` / `klarnaHost(item, returnUrl)` / `cardHost(item)`). This is why
   `:partner-ui` never depends on `:headless-checkout` (which depends on it).
2. **`setCollected(map)`** — whatever the native step produced (a sheet token, a `step_id`, a vault
   token, even a live callback) is stashed against the item and arrives in the plugin's
   `PartnerUiContext.collected` at `preValidation`/`postValidation` time.
3. **`setFulfilled(bool)`** — form completeness, for plugins that collect a form. See below.

### Resolution — which plugin handles an item

`PluginRegistry.getPlugin(partner, method, session)`, mirroring web's `getPlugin`:

```
disabled? → null
vault vendor (configurations.vault.vendor ≠ NONE) → generic template (configurations.template)
  → exact (partner, method) → method fallback → null
```

Nothing registers a `configurations.template` generic plugin yet (web's `redirection`/`hostedfields`
templates have no Android port), so today's three resolve as: card by **vault vendor**, Klarna by
**exact pair**, Google Pay by exact pair for Ingenico and by **method fallback** for everyone else.

All three shipped plugins are **registered by default** (`PluginRegistry`'s own `init` block) — the
session's `protocols` entry is the real gate on whether a method is offered, so no app-side setup
call exists to drift out of sync with it. `PluginRegistry.disable(partner, method)` opts a built-in
method out of one app build.

### Form completeness gates the payment

A plugin whose shopper-facing step is a *form* reports its validity through `setFulfilled`, and
`isPaymentFulfilled` stays `false` (and `submitPayment` throws `ItemNotFulfilled`) while any item in
the split reports `false`. Today only the card does this — Google Pay's sheet and Klarna's widget are
not forms this SDK validates, and they report nothing, which leaves them ungated exactly as in web
(where those plugins dispatch no `fulfilled` event either). Details:
[consumer-api §6](../integration/consumer-api.md#form-completeness-gates-the-payment).

### Shared: redirects and 3-D Secure

All three coordinators resolve "what does `/validate` want next?" through the same two pieces, which
live under `plugins/generics/googlepay/redirect/` for historical reasons (Google Pay needed them
first) and are shared verbatim:

**`RedirectionResolver.resolve(validateResponse, partner, method, sessionReturnUrl)`** — ports web's
`manageRedirection`, same priority order:

| Priority | Response shape | Action |
|---|---|---|
| 1 | per-item `plugin_result.logs.redirection_json` + `redirection_url` | `PostForm` |
| 2 | global `redirection.url` (+ per-item `redirect_url_post_params`) | `Get`, or `PostForm` when post params are present |
| 3 | global `redirection.form` | `PostHtml` (raw HTML to inject and auto-submit) |
| — | none of the above | `null` — the common case; resolve the payment result instead |

Two shapes are deliberately **dropped**, because neither is a genuine PSP/bank redirect:

- web's `should_submit_widget` fallback — "resubmit the widget's own page", meaningless headless
  (web's own headless-checkout gates it out whenever the split has a primary, which these always are);
- a target that starts with the session's own return URL, or carries the `purse-redirection-data`
  marker — that's the widget's **confirmation page**, whose JWT decodes to the same
  `authorization_status` the very same `/validate` call already returned in `global_status`.

That JWT also carries `payment_id`, read (unsigned — it arrived over the already-authenticated
`/validate` response) by `RedirectionResolver.extractWidgetConfirmationPaymentId` and surfaced on all
three coordinators as `Listener.onPaymentId`. Purely informational — a capture tool needs it, a
payment does not — and absent whenever a *genuine* redirect took the confirmation one's place.

**`RedirectionCheckoutActivity`** (launched via `RedirectionCheckoutContract`) renders the action in
an **in-app WebView**, not a Custom Tab: a Custom Tab can neither POST nor be observed navigating. It
finishes once the shopper reaches this app's own deep link (`${applicationId}.redirect`), the flow's
`returnUrlPrefix` (the session's return URL, attached by the caller as a "known terminal page" hint),
or a `purse-redirection-data` URL.

> **Caveat:** some ACS providers refuse to render inside an embedded WebView (an anti-phishing measure
> some card networks require). If one partner's challenge breaks there, that partner needs its own
> redirect path — reverting this one breaks POST redirects for everyone.

### A returned redirect is not an outcome

Every coordinator reports `RedirectionCheckoutResult.Approved` as
`onResult(summary, globalStatus = null)` and **does not chase the real outcome**. This is a confirmed
backend pain point, not laziness: a real 3-D Secure challenge's final redirect is constructed
server-side by the bank/PSP and can land on a Purse API gateway URL that `401`s on the session's own
client-facing `api_key`. Re-check status independently instead — `checkout.sessionTransactions()`, or
the demo's own alfred-backed "Get transactions" tool, whose answer is what settles its result screen.
See [decisions/pain-points-recap.md](../decisions/pain-points-recap.md).

### Where each PSP's native step sits relative to `/validate`

The single biggest per-PSP difference, and the reason these pages exist:

```
Google Pay   sheet ──▶ token ──▶ /validate                  (native step BEFORE)
Card         form ──▶ vault token ──▶ /init ──▶ /validate   (native step BEFORE)
Klarna       /validate ──▶ authorize() (in postValidation)  (native step AFTER, inside submitPayment)
```
