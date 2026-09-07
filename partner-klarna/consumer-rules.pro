##
## Purse Headless Checkout — :partner-klarna consumer R8 / ProGuard rules
##
## `KlarnaModule` is discovered through ServiceLoader: :partner-ui's own consumer rules keep every
## `PartnerModule` implementation. The Klarna Mobile SDK ships its own rules (JavascriptInterface,
## its api.* names). Nothing else here uses reflection.
##
