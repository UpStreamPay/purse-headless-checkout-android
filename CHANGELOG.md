# Changelog

## [0.2.0](https://github.com/UpStreamPay/purse-headless-checkout-android/compare/v0.1.0...v0.2.0) (2026-11-13)


### Features

* **card:** card form field state, accessible names and checkout locale (SDK-12579) ([#56](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/56)) ([2f80d28](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/2f80d28cc18ed0bc82cb42badfc12f824a10bec0))
* **confirmation:** deprecate the client-side session read (SDK-12574) ([#51](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/51)) ([ff00da3](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/ff00da3e4951186cb6aaa94eb3b972b3fd620aa5))
* **monitoring:** log the redirection destination hostname (SDK-12712) ([#65](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/65)) ([273e75d](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/273e75da617d58d671aca1e5e3787e553dc281f9))
* **paypal:** withdraw an ineligible PayPal bnpl in redirection mode (SDK-12576) ([#53](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/53)) ([2fb36ca](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/2fb36ca9f22d3b57ef0c17f2db1e54d2a6c541a8))


### Bug Fixes

* **card:** name the missing card submit and document the low-level path (SDK-12580) ([#57](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/57)) ([ab667b1](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/ab667b15f00de116961934fef47e78d3b901c8dc))
* **card:** send partner_data.browser on the card /validate (SDK-12569) ([#41](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/41)) ([b329962](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/b3299621bb2bbb769d69da160cdc6e554d6522b0))
* **card:** skip /init for the Purse vault card (SDK-12570) ([#47](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/47)) ([192f330](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/192f330fbef80c25f1db0792b42d8c13ee8a9abd))
* **card:** spell theme colours the way SecureFields parses them (SDK-12578) ([#55](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/55)) ([7ff4d3a](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/7ff4d3af4c12e74d03576d808d6299d49381be05))
* **checkout:** send /validate at most once per session (SDK-12621) ([#58](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/58)) ([04dedb0](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/04dedb067f5f43ab4283400bb197d375667b41cf))
* count every BNPL instalment in the loan simulation total (SDK-12566) ([#39](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/39)) ([24576e2](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/24576e2a32ca6fe79934bef08bd3426d5f52d536))
* **environment:** refuse TEST / DEVELOPMENT in a release app (SDK-12577) ([#54](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/54)) ([a23796d](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/a23796de43f6064797a67f2e3c926bdc48d3f484))
* **headless:** take a primary out of the split when its amount bounds suspend it ([#40](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/40)) ([ff843db](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/ff843db7abef9ef42e068b13e4de54a88cf0db60))
* lock the environment tier, not the Payment API root (SDK-12571) ([#37](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/37)) ([199b8f2](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/199b8f227bb39a84f2acaa27d03ffbadeb980d34))
* **partner-ui:** retire a method no plugin serves when it is engaged (SDK-12575) ([#52](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/52)) ([4634fc2](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/4634fc23f9f684f6aee520f429c4ffd709016789))
* re-prepare Google Pay and Klarna when the amount moves (SDK-12567) ([#48](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/48)) ([7ebd4b3](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/7ebd4b3eea931b3913222cb1c65fd34c87ff37bc))
* **redirect:** enable DOM storage in the redirect WebView (SDK-12572) ([#49](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/49)) ([7a63685](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/7a63685427631c525f058f5c939262dcde44a403))
* **redirect:** stop other apps from opening the redirect WebView (SDK-12625) ([#38](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/38)) ([51bc0f1](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/51bc0f18e2bc779d933528ad43273fef30576d8c))
* **wallet:** send a real PATCH to rename or favorite a saved token (SDK-12573) ([#50](https://github.com/UpStreamPay/purse-headless-checkout-android/issues/50)) ([90d3a79](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/90d3a794308dd17af4cebbca2c6c0355f3d405c5))

## 0.1.0 (2026-09-30)


### ⚠ BREAKING CHANGES

* disabledMethods on init replaces the process-global PluginRegistry.disable
* sealed PaymentOutcome replaces the stringly onResult / onError / onCanceled
* one SDK module, plus one optional artifact per partner SDK

### Features

* **app:** demo as store setup, merchant checkout and receipt ([9704bfc](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/9704bfc2bad7d10de62ea1991e342ca2715967df))
* **checkout:** displayable message on every DisabledState, plus SUBMITTING ([8b98846](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/8b988461e1ee3f3aca6720bdfb600d25dc0d1d07))
* **checkout:** lock every item as SUBMITTING while a submit is in flight ([83277f8](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/83277f895dbfd659e4c7548e4507e046be21af70))
* **checkout:** mount no UI for a technically disabled item ([6395488](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/639548813d1faf729c874dbd9c5915f7d6c43b9f))
* **checkout:** refuse a disabled item as primary (METHOD_DISABLED) ([4b2c69a](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/4b2c69aebe29bdedc55b662849a1a7b377f4a852))
* **checkout:** refuse invalid takes and enforce usage_per_session_limit in the splitter ([b1a9632](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/b1a9632c4c7a9cc6c7bf3f0576b523e67ccd5f26))
* **checkout:** teardown is observable and final (CHECKOUT_TORN_DOWN) ([e0d5318](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/e0d5318aea6e75be8c2f8da8ca8eb078784e0b08))
* co-badged brand picker in the card form ([a518002](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/a5180023302db476bf2320937dc2e45117a1837e))
* **core:** add METHOD_DISABLED, SUBMIT_BLOCKED and CHECKOUT_TORN_DOWN error codes ([037caf0](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/037caf0ffef188b36efad38bd926eaab5bc9052f))
* **core:** flag a validate that left without an answer as outcome unknown ([6f503b5](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/6f503b5b18df2499130a20185e633dde1a54b8d8))
* CREATE carries the device context ([683fcfb](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/683fcfb88a5975b377c8d89f99a108e2017ad81b))
* disabledMethods on init replaces the process-global PluginRegistry.disable ([4aaaa65](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/4aaaa65ca54207c6bb75080013359c6bb0694638))
* frame each secure card field like web's BaseInput ([4a55017](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/4a55017b697c489b2f721c1773bfd671dc94d2b9))
* **googlepay:** rectangular Pay button by default ([0482b7e](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/0482b7e10fd343ac3942234ee01faa12ff161cd0))
* method suspensions are events, nominal ones never errors ([1f6570c](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/1f6570c9a97e3731a9ebeadeffc4498207910814))
* one instanceId per checkout, kept across setSession and restore ([09e0a5d](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/09e0a5d55b27f4f3f5e55658001f75919033cdf1))
* **partner-ui:** add Klarna installments plugin (native KlarnaPaymentView) ([69d3db1](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/69d3db12a061761c519ea432944e34e432515bb9))
* PAYMENT_ERROR says why a payment failed, attempt by attempt ([e911195](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/e911195e01920bd6827d0c2f3a723c7e29dadf58))
* saveState / restore — the checkout survives process death ([2d461d7](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/2d461d7f5d4d8a375f3ac2951dff2988831d50b6))
* sealed PaymentOutcome replaces the stringly onResult / onError / onCanceled ([9f5f1b0](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/9f5f1b0ed7b38c649729b911e89f0e56a65b30a1))
* simulable and installments display on demo app ([18bd74c](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/18bd74c4bad01f0cab0c345597c6e1dfcfe4a0b5))


### Bug Fixes

* **app:** keep payment elements composed while a payment is in flight ([63fb89f](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/63fb89ffb16efc170b4bd8ce30195d07df5838da))
* **app:** the receipt says "Payment method" unless the payment was authorized ([a6a24b6](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/a6a24b631cf298794d88152686b6a24856576c57))
* **checkout:** a zero-amount session needs a primary before it is fulfilled ([8f2f825](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/8f2f82563c6a9eb271a359c08e8641a2741f21df))
* **checkout:** one submit per session — expiry re-check and atomic latch ([749fdd2](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/749fdd2bd6ca175547c37c4a1e6108df78ff3aff))
* **checkout:** run every network call on Dispatchers.IO ([ac966e4](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/ac966e40b24482c4f0bcfe3eaacd27ea1e22063e))
* **core:** loan simulation total counts each instalment once ([938bba9](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/938bba93d8d8ce6b1edb58d3d1f1facd0a0eda03))
* keep the monitoring envelope on the current session ([6ac8f23](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/6ac8f236f9d2e52153ba2853507a9ccace60cb7b))
* no raw amount and shorter stacks in telemetry ([ab901bf](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/ab901bfb9a02b55cb1528dd86b389a80374f953a))
* **partner-ui:** accept a return intent only on the app's own scheme ([8a61376](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/8a6137674fb026861f27119c76663d19ea3fef1f))
* **partner-ui:** allow-list https pages and lock down the redirect WebView ([cc30275](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/cc30275c0d9ae336a66cabd290313e5878539b38))
* **partner-ui:** report recreation after process death as a return, not a cancel ([a2e4409](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/a2e4409a4ec0fd4c67d479f3967401ebefd6bda8))


### Code Refactoring

* one SDK module, plus one optional artifact per partner SDK ([b54a022](https://github.com/UpStreamPay/purse-headless-checkout-android/commit/b54a022bd77126c8063d855a9f5f069844ace617))
