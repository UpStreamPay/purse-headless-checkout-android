# Handled Partners

Every payment method in [Session & Protocols](../architecture/session-and-protocols.md) is
backed by a **partner** — the PSP (or, for CB, Purse's own vault) that actually processes the
transaction. This page lists the partners this POC handles, each with its own doc covering (a) a
link to that partner's own official documentation, where one exists, and (b) a study of what a
hypothetical redirect-based flow would mean for that specific partner — since redirect behavior
turns out to differ a lot partner by partner, not just method by method.

> **Start of a list, not the whole universe.** The web codebase's `Partner` enum
> (`usp-widget/packages/sdk/src/types.ts`) has ~38 entries — aci, adyen, alma, ancv, axepta,
> braintree, centralpay, checkout, cofidis, dalenys, easycollect, easy2play, fintecture, floa,
> gocardless, hipay, illicado, ingenico, klarna, mercanet, oney, oneycard, paybox, payline,
> paypal, payzen, **purse**, redsys, riverty, scalapay, scellius, sipsv2, slimpay, sogecommerce,
> sogenactif, stripe, systempay, worldpay, younited. This POC only goes deep on the four backing
> its four target methods (see [POC Scope](../decisions/poc-scope.md)); add a page per partner
> here as scope grows.

---

## This POC's partners

| Partner | Method (this POC) | Documentation | Redirects today? |
|---|---|---|---|
| [`purse`](purse.md) | `creditcard` (CB) | Internal — sibling `vault-android` repo's own docs, not an external PSP portal | No — SecureFields captures natively; a 3DS step-up is still hypothetically possible |
| [`adyen`](adyen.md) | `googlepay` | [docs.adyen.com](https://docs.adyen.com/) | **Yes, already** — the generic Google Pay plugin calls the redirect resolver today |
| [`paypal`](paypal.md) | `wallet` | [developer.paypal.com](https://developer.paypal.com/) | Partially — redirects to the merchant's own success page after in-page approval, never to a PayPal-hosted page |
| [`illicado`](illicado.md) | `giftcard` | None public — see [illicado.md](illicado.md) | No — no redirect code path exists for it anywhere in the reference implementation |

---

## Partners considered but out of scope

- **`aci`** — backs `creditcard` in the one concrete example payload found
  (`usp-widget/e2e/tests/widget/mocks/session.ts`), but with `vault.vendor: "pci_proxy"` (PSP-hosted
  fields), not `"purse"` — the vendor this POC's CB path actually targets. See
  [purse.md](purse.md) for why `purse`, not `aci`, is this POC's CB partner. ACI Worldwide's own
  docs, verified live, are at [docs.aciworldwide.com](https://docs.aciworldwide.com/), for
  reference if `pci_proxy`-vendor CB is ever brought into scope.
- Every other entry in the `Partner` enum above — not present in this POC's target methods.

---

## See also

- [POC Scope](../decisions/poc-scope.md)
- [Session & Protocols](../architecture/session-and-protocols.md)
- [Redirect Handling](../architecture/redirect-handling.md)
