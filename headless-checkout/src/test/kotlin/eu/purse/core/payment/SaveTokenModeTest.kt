package eu.purse.core.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports web's `resolveSaveTokenMode` cases (`item-factory/primaries.ts`). */
class SaveTokenModeTest {

    private fun settings(saveToken: Boolean?, saveTokenMode: String?) = MethodSettings(
        registrationMode = RegistrationMode.ON_SESSION,
        saveToken = saveToken,
        saveTokenMode = saveTokenMode,
        directDebit = false,
        paymentMinAmount = null,
        paymentMaxAmount = null,
        usagePerSessionLimit = null,
        integrationMode = null,
    )

    @Test
    fun `an explicit save_token false disables saving even with a mode set`() {
        assertEquals(SaveTokenMode.NEVER, settings(false, "ALWAYS").resolvedSaveTokenMode())
    }

    @Test
    fun `an explicit mode wins otherwise`() {
        assertEquals(SaveTokenMode.ALWAYS, settings(null, "ALWAYS").resolvedSaveTokenMode())
        assertEquals(SaveTokenMode.PREFER, settings(true, "PREFER").resolvedSaveTokenMode())
        assertEquals(SaveTokenMode.PREFER_NO, settings(null, "PREFER_NO").resolvedSaveTokenMode())
    }

    @Test
    fun `fallbacks - save_token true means PREFER_NO, absent means NEVER`() {
        assertEquals(SaveTokenMode.PREFER_NO, settings(true, null).resolvedSaveTokenMode())
        assertEquals(SaveTokenMode.NEVER, settings(null, null).resolvedSaveTokenMode())
    }

    @Test
    fun `only PREFER modes give the shopper a choice, ALWAYS and PREFER register by default`() {
        assertTrue(SaveTokenMode.PREFER.canBeRegistered)
        assertTrue(SaveTokenMode.PREFER_NO.canBeRegistered)
        assertFalse(SaveTokenMode.ALWAYS.canBeRegistered)
        assertFalse(SaveTokenMode.NEVER.canBeRegistered)
        assertTrue(SaveTokenMode.ALWAYS.registeredByDefault)
        assertTrue(SaveTokenMode.PREFER.registeredByDefault)
        assertFalse(SaveTokenMode.PREFER_NO.registeredByDefault)
    }
}
