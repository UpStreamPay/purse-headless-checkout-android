package eu.purse.core.environment

import android.content.pm.ApplicationInfo
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.session.PurseSessionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** SDK-12577: the TEST / DEVELOPMENT gate, aligned on iOS's `EnvironmentGateTests` (SDK-12562). */
class PurseEnvironmentGateTest {

    @Test
    fun `a debuggable app gets every tier it asks for`() {
        PurseEnvironment.entries.forEach { assertEquals(it, it.requireAllowed(isDebugHost = true)) }
    }

    @Test
    fun `a release app is refused the internal tiers, never switched to production`() {
        for (tier in listOf(PurseEnvironment.TEST, PurseEnvironment.DEVELOPMENT)) {
            val refused = assertThrows(PurseSessionException.EnvironmentNotAllowed::class.java) {
                tier.requireAllowed(isDebugHost = false)
            }
            assertEquals(PurseErrorCode.ENVIRONMENT_NOT_ALLOWED, refused.code)
            assertTrue(refused.message!!.contains(tier.name))
        }
        assertEquals(PurseEnvironment.SANDBOX, PurseEnvironment.SANDBOX.requireAllowed(isDebugHost = false))
        assertEquals(PurseEnvironment.PRODUCTION, PurseEnvironment.PRODUCTION.requireAllowed(isDebugHost = false))
    }

    @Test
    fun `an api_url names its tier by host only`() {
        assertEquals(PurseEnvironment.DEVELOPMENT, PurseEnvironment.ofApiUrl("https://API.purse-dev.com/payment/v2"))
        assertEquals(PurseEnvironment.TEST, PurseEnvironment.ofApiUrl("http://api.purse-test.com:8443/payment/v1"))
        assertNull(PurseEnvironment.ofApiUrl("https://tenant.example/payment/v1"))
        assertNull(PurseEnvironment.ofApiUrl("https://api.purse-test.com.evil.com/payment/v1"))
        assertNull(PurseEnvironment.ofApiUrl(""))
        assertNull(PurseEnvironment.ofApiUrl("not a url"))
    }

    @Test
    fun `the host app's debuggable flag is what counts`() {
        assertTrue(HostApp.isDebuggable(ApplicationInfo.FLAG_DEBUGGABLE))
        assertTrue(HostApp.isDebuggable(ApplicationInfo.FLAG_DEBUGGABLE or ApplicationInfo.FLAG_HAS_CODE))
        assertFalse(HostApp.isDebuggable(ApplicationInfo.FLAG_HAS_CODE))
        assertFalse(HostApp.isDebuggable(0))
    }
}
