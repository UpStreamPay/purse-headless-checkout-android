package eu.purse.checkout.validation

import android.util.Log
import eu.purse.checkout.PurseCheckoutException
import eu.purse.checkout.PurseCheckoutHooks
import eu.purse.checkout.monitoring.CheckoutEventBus
import eu.purse.checkout.monitoring.CheckoutEventCode
import eu.purse.checkout.monitoring.EventLevel
import eu.purse.checkout.monitoring.monitoringPayload
import eu.purse.checkout.payment.item.PaymentToken
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.registration.RegistrationState
import eu.purse.checkout.payment.secondary.SecondaryConfigs
import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.checkout.payment.split.PaymentSplit
import eu.purse.core.api.PaymentApiClient
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.payload.ItemPayload
import eu.purse.core.payload.ValidateItemPayload
import eu.purse.core.payload.ValidationResult
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PartnerUiPlugin
import eu.purse.partnerui.PluginRegistry

/**
 * The "create payment" pipeline — the Kotlin analog of web's `executePayment`
 * (`headless-checkout-validation.ts`). For each split it builds a [ValidateItemPayload], POSTs them
 * all to `/validate` in one call, then dispatches the result back to any plugins:
 *
 * - **secondary** splits (an applied [SecondaryToken]) build their payload from the method's
 *   `SecondaryConfig` — no plugin involved, matching web's `buildSecondaryValidateItemPayload`;
 * - **primary** splits (a [PrimaryMethod]) resolve a `:partner-ui` plugin, optionally `validateUi`,
 *   then `preValidation` → payload, and `postValidation` after the call.
 *
 * While no primary plugins are registered, a primary split throws
 * [PurseCheckoutException.NoPluginRegistered] — secondaries submit fine on their own.
 */
class PaymentValidator(private val api: PaymentApiClient) {

    private class Prepared(val plugin: PartnerUiPlugin, val context: PartnerUiContext)

    suspend fun execute(
        splits: List<PaymentSplit>,
        session: LegacyPaymentSession,
        collectedByMethod: Map<String, Map<String, Any?>> = emptyMap(),
        hooks: PurseCheckoutHooks? = null,
        skipOnBeforeValidate: Boolean = false,
        registrationByMethod: Map<String, RegistrationState> = emptyMap(),
    ): ValidationResult {
        requireDistinctSources(splits)

        val prepared = mutableListOf<Prepared>()
        val payloads = mutableListOf<ValidateItemPayload>()

        for (split in splits) {
            when (val source = split.source) {
                is SecondaryToken -> {
                    val config = SecondaryConfigs.forMethod(source.partner, source.method)
                        ?: throw PurseCheckoutException.NoSecondaryConfig("no secondary config for ${source.id}")
                    val payload = ItemPayload(
                        partner = source.partner,
                        method = source.method,
                        amount = split.amount,
                        paymentData = config.buildValidatePaymentData(source),
                    )
                    // A gift card the shopper asked to keep — web's `buildSecondaryValidateItemPayload`:
                    // `payment_preferences.registration` + `card.token_label` only when registered,
                    // and never for an instrument that is already saved.
                    val registration = registrationByMethod[source.id]?.takeIf { it.registered && !source.isSaved }
                    payloads += if (registration != null) enhanceWithRegistration(payload, registration) else payload
                }

                // A primary method, or a saved token paying through the same plugin — the context then
                // carries the token instead of fresh form data (web renders the element with a `token`).
                is PrimaryMethod, is PaymentToken -> {
                    val plugin = PluginRegistry.getPlugin(source.partner, source.method, session)
                        ?: throw PurseCheckoutException.NoPluginRegistered(
                            "no :partner-ui plugin registered for ${source.id} — implement one to complete payment",
                        )
                    val registration = registrationByMethod[source.id].takeIf { source is PrimaryMethod }
                    val context = PartnerUiContext(
                        partner = source.partner,
                        method = source.method,
                        session = session,
                        amount = split.amount,
                        api = api,
                        collected = collectedByMethod[source.id] ?: emptyMap(),
                        register = registration?.registered == true,
                        token = (source as? PaymentToken)?.toPayToken(),
                    )
                    if (!plugin.validateUi(context)) {
                        throw PurseCheckoutException.ValidateFailed("client-side validation failed for ${source.id}")
                    }
                    payloads += enhanceWithRegistration(plugin.preValidation(context), registration)
                    prepared += Prepared(plugin, context)
                }

                else -> throw PurseCheckoutException.ValidateFailed("unsupported split source ${source.id}")
            }
        }

        // Merchant hook between payload preparation and the POST — throwing aborts the submit,
        // wrapped as ON_BEFORE_VALIDATE_HOOK_FAILED like web (`executePayment`'s catch) so the
        // merchant's own exception is the cause, not the surface. Nothing has been sent at this
        // point, so the caller can restore the checkout to a submittable state. Skipped when a
        // plugin auto-submits.
        if (!skipOnBeforeValidate) {
            try {
                hooks?.onBeforeValidate?.invoke(splits)
            } catch (e: Exception) {
                CheckoutEventBus.getInstance().post(
                    CheckoutEventCode.HEADLESS_CHECKOUT_ON_BEFORE_VALIDATE_HOOK_FAILED,
                    EventLevel.ERROR,
                    monitoringPayload("hook" to "onBeforeValidate", "message" to e.message),
                )
                throw PurseCheckoutException.HookFailed(
                    "onBeforeValidate", e, PurseErrorCode.ON_BEFORE_VALIDATE_HOOK_FAILED,
                )
            }
        }

        // The one ground-truth point for "what did we actually send to /validate" — one item per
        // split, so the count is also the split size. Logged because a duplicate or a dropped
        // `step_id` is otherwise invisible from the outside and only shows up as an extra
        // transaction server-side.
        Log.d(
            TAG,
            "POST /validate: ${payloads.size} item(s) — " +
                payloads.joinToString { item ->
                    "${item.partner.wireValue}/${item.method.wireValue} " +
                        "amount=${item.amount} step_id=${item.stepId}"
                },
        )

        val result = try {
            api.validate(payloads)
        } catch (e: Exception) {
            throw PurseCheckoutException.ValidateFailed("validate call failed: ${e.message}", e)
        }

        // Runs before postValidation (and so before any redirection); errors swallowed, like web
        // (which reports them as HEADLESS_CHECKOUT_CLIENT_HOOK_ERROR).
        try {
            hooks?.onAfterValidate?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "onAfterValidate hook failed (ignored): ${e.message}")
            CheckoutEventBus.getInstance().post(
                CheckoutEventCode.HEADLESS_CHECKOUT_CLIENT_HOOK_ERROR,
                EventLevel.ERROR,
                monitoringPayload("hook" to "onAfterValidate", "message" to e.message),
            )
        }

        prepared.forEach { it.plugin.postValidation(result, it.context) }
        return result
    }

    /**
     * Enhance a fresh-card payload with the save-token decision — web's `preValidateItems`
     * (`headless-checkout-validation.ts:25-43`): `payment_preferences.registration` plus
     * `payment_data.card.token_label` when the shopper named the card.
     */
    private fun enhanceWithRegistration(
        payload: ValidateItemPayload,
        registration: RegistrationState?,
    ): ValidateItemPayload {
        if (registration == null) return payload
        payload.paymentPreferences.put("registration", registration.registered)
        if (registration.registered && !registration.name.isNullOrBlank()) {
            val card = payload.paymentData.optJSONObject("card")
                ?: org.json.JSONObject().also { payload.paymentData.put("card", it) }
            card.put("token_label", registration.name)
        }
        return payload
    }

    /**
     * One `/validate` item per split, and never the same split source twice — a duplicate would ask
     * the PSP to charge the same instrument twice in a single call.
     *
     * [PursePaymentSplitter][eu.purse.checkout.payment.split.PursePaymentSplitter] already makes that
     * structurally impossible (at most one primary, plus secondaries in a map keyed by token id), so
     * this is a tripwire, not a recovery path: it exists because [execute] accepts a plain
     * `List<PaymentSplit>` and so cannot rely on that guarantee holding forever.
     *
     * Deliberately keyed on **`source.id`**, not on `(partner, method)`. Two different gift cards from
     * the same partner (two `illicado-giftcard` tokens, say) legitimately contribute two items with
     * identical `partner`/`method` — that's a real co-payment shape web submits too, and deduplicating
     * on the pair would silently drop one card's slice and under-pay the order.
     *
     * @throws PurseCheckoutException.ValidateFailed naming the repeated source.
     */
    private fun requireDistinctSources(splits: List<PaymentSplit>) {
        val repeated = splits.groupingBy { it.source.id }.eachCount().filterValues { it > 1 }.keys
        if (repeated.isNotEmpty()) {
            throw PurseCheckoutException.ValidateFailed(
                "refusing to submit /validate with a repeated split source: ${repeated.joinToString()} — " +
                    "each source must contribute exactly one item",
            )
        }
    }

    private companion object {
        const val TAG = "PaymentValidator"
    }
}
