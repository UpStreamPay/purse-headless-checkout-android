##
## Purse Headless Checkout — :partner-paypal consumer R8 / ProGuard rules
##
## `PaypalModule` is discovered through ServiceLoader: :partner-ui's own consumer rules keep every
## `PartnerModule` implementation. The PayPal Android SDK ships its own rules.
##

# Declared in this AAR's manifest and instantiated by the framework — defence-in-depth, see :partner-ui.
-keep class eu.purse.partnerui.plugins.partners.paypal.wallet.PaypalCheckoutActivity { <init>(...); }
