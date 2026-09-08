##
## Purse Headless Checkout — :partner-googlepay consumer R8 / ProGuard rules
##
## `GooglePayModule` is discovered through ServiceLoader: :partner-ui's own consumer rules keep every
## `PartnerModule` implementation. Play Services Wallet ships its own rules. Nothing else here uses
## reflection.
##
