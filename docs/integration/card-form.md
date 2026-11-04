# Card form: two levels

Same split as Stripe (PaymentSheet / `CardInputWidget`) and Adyen (Drop-in / Components), and as
the iOS SDK: a drop-in element that reports only what a checkout screen needs, and a lower level
for full control.

## `rememberPaymentElement` — drop-in

```kotlin
val element = rememberPaymentElement(checkout, cardMethod, listener)
LaunchedEffect(cardMethod.id) { checkout.setPrimary(cardMethod) }
PaymentElementUi(element, Modifier.fillMaxWidth())   // label, field, helper text and error per field, drawn by the SDK
Button(onClick = { element.submit() }, enabled = element.ready) { Text("Pay") }   // tokenize → /init → /validate → 3-D Secure
```

You get `element.ready` (enable your Pay button) and one `PaymentOutcome` per attempt, through the
listener. Field errors are rendered inside the form; the element does not expose them.

## `CardCoordinator` — full control

```kotlin
val host = remember(cardMethod.id) { checkout.host(cardMethod) }   // remember it: the form is keyed on it
LaunchedEffect(cardMethod.id) { checkout.setPrimary(cardMethod) }  // the split must cover the amount, or nothing pays
val card = rememberCardCoordinator(host, listener)
PurseCardForm(card, Modifier.fillMaxWidth())   // or lay out VaultField(card.sdk.cardNumber, …) yourself
Button(onClick = card::submit, enabled = card.formValid) { Text("Pay") }
```

Observe, as Compose state:

| Member | What it tells you |
|---|---|
| `formValid` | Every field the form shows is valid. |
| `fieldStates` | Per `CardField`: `isValid`, `isFocused`, `hasContent`, `wasBlurred`. |
| `errorMessage(field)` | The error `PurseCardForm` shows under that field right now, or `null` — to draw your own messages. |
| `mode` | `FRESH_CARD` (four fields), `TOKEN_CVV` (a saved card's CVV only), `TOKEN_NO_CVV` (nothing to render). |

The detected and selected brand come from SecureFields itself: register a `SecureFieldsListener` on
`card.sdk` (`onBrandDetected`, `onBrandNotDetected`, `onBrandUserSelection`). On a co-badged card
SecureFields renders the brand picker inside the card-number field.

A custom layout renders each field with SecureFields' `VaultField` — and only when
`card.mode.rendersForm`:

```kotlin
val cardNumber = CardField.CARD_NUMBER
Text(stringResource(R.string.card_number))
VaultField(card.sdk.cardNumber, Modifier.fillMaxWidth().height(52.dp).testTag(cardNumber.testTag))
card.errorMessage(cardNumber)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
```

Unlike iOS, paying is **one** call: `card.submit()` tokenizes, runs `/init` + `/validate` through
the host and follows a 3-D Secure redirect, reporting the `PaymentOutcome` on the listener. It is a
no-op while the form is not valid.

## Names, test tags and language

Both levels render the same form, so the same rules hold:

- Each secure input carries its label as `contentDescription` (TalkBack, Espresso) and
  `CardField.testTag` as its test tag: `purse_card_number`, `purse_card_exp_date`, `purse_card_cvv`,
  `purse_card_holder_name` — the iOS identifiers. UiAutomator and Appium see test tags once an
  ancestor sets `Modifier.semantics { testTagsAsResourceId = true }`.
- Copy comes from `PluginOptions.cardFormTexts`, and every text left `null` from the SDK's defaults
  (English, French) in `PluginOptions.locale` — the device language only when the checkout has no
  locale.
