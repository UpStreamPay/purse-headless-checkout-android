package eu.purse.core.payment

/**
 * The effective save-token (card registration) policy for a method — web's `SaveTokenMode`.
 * `ALWAYS`/`NEVER` are backend-enforced; only `PREFER`/`PREFER_NO` give the shopper a choice
 * (a checkbox, checked resp. unchecked by default).
 */
enum class SaveTokenMode {
    ALWAYS,
    PREFER,
    PREFER_NO,
    NEVER;

    /** Whether the shopper can choose — web's `canBeRegisteredFromMode`. */
    val canBeRegistered: Boolean get() = this == PREFER || this == PREFER_NO

    /** Whether registration starts on — web defaults `registered` for `ALWAYS`/`PREFER`. */
    val registeredByDefault: Boolean get() = this == ALWAYS || this == PREFER

    companion object {
        fun fromWire(value: String?): SaveTokenMode? = entries.firstOrNull { it.name == value }
    }
}

/**
 * Resolves the effective mode from `settings.save_token` / `save_token_mode` — the exact port of
 * web's `resolveSaveTokenMode` (`item-factory/primaries.ts`):
 * - an explicit `save_token: false` disables saving unconditionally;
 * - otherwise an explicit `save_token_mode` wins;
 * - fallback: `save_token: true` → `PREFER_NO`, anything else → `NEVER`.
 */
fun MethodSettings.resolvedSaveTokenMode(): SaveTokenMode = when {
    saveToken == false -> SaveTokenMode.NEVER
    saveTokenMode != null -> SaveTokenMode.fromWire(saveTokenMode) ?: SaveTokenMode.NEVER
    saveToken == true -> SaveTokenMode.PREFER_NO
    else -> SaveTokenMode.NEVER
}
