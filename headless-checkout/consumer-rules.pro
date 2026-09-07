##
## Purse Headless Checkout — consumer R8 / ProGuard rules
## Bundled into the AAR and applied automatically to any app that depends on this library.
##
## The SDK reads JSON with `org.json` by string key and keeps no reflection-based serialisation,
## no string-keyed class dispatch (partner event monitoring codes are `PluginEvent.code`, an explicit
## constant) and no `Class.forName`, so merchant code referencing the public API by name is all R8
## needs to keep — with the two exceptions below. SecureFields ships its own consumer rules.
##

# ── Optional partner artifacts ───────────────────────────────────────────────
# `PluginRegistry` discovers each partner artifact (Google Pay, Klarna, PayPal) through
# `ServiceLoader` + `META-INF/services/eu.purse.partnerui.PartnerModule`. R8 rewrites the
# `ServiceLoader.load(X.class, X.class.getClassLoader())` call into direct instantiation when it can
# see every implementation; either way the implementations, their no-arg constructor and their names
# (the service file lists them by name) must survive.
-keep class * implements eu.purse.partnerui.PartnerModule { <init>(); }
-keepnames class * implements eu.purse.partnerui.PartnerModule

# ── Return Activity ──────────────────────────────────────────────────────────
# Declared in this AAR's manifest and instantiated by the framework — AGP's manifest rules keep it,
# this is defence-in-depth for hosts that post-process the merged manifest.
-keep class eu.purse.partnerui.redirect.RedirectionCheckoutActivity { <init>(...); }
