package eu.purse.dropin

import eu.purse.dropin.payment.partner.Partner
import eu.purse.dropin.core.environment.PurseEnvironment
import eu.purse.dropin.payment.method.SecondaryMethod
import eu.purse.dropin.session.TestSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DropinAndroidTest {

    @Test
    fun `init exposes payment methods from the decoded session`() {
        val dropin = DropinAndroid.init(TestSessions.widgetData())

        val partners = dropin.paymentMethods.map { it.partner }
        assertTrue(partners.containsAll(listOf(Partner.Purse, Partner.Aci, Partner.Adyen, Partner.Paypal, Partner.Illicado)))
        assertTrue(dropin.paymentMethods.single { it.partner == Partner.Illicado } is SecondaryMethod)
    }

    @Test
    fun `init exposes the vault tenant id from the session-level purse vault config`() {
        val dropin = DropinAndroid.init(TestSessions.widgetData())

        assertEquals("tenant-abc", dropin.vaultTenantId)
    }

    @Test
    fun `init without a locked environment uses whatever the session declares`() {
        val dropin = DropinAndroid.init(TestSessions.widgetData(environment = "sandbox"))

        assertEquals(PurseEnvironment.SANDBOX, dropin.sessionParams.environment)
    }

    @Test
    fun `init with a locked environment overrides the session-declared one`() {
        val dropin = DropinAndroid.init(
            TestSessions.widgetData(environment = "sandbox"),
            lockedEnvironment = PurseEnvironment.PRODUCTION,
        )

        assertEquals(PurseEnvironment.PRODUCTION, dropin.sessionParams.environment)
    }

    @Test
    fun `init is not an information request for a session with a real amount`() {
        val dropin = DropinAndroid.init(TestSessions.widgetData())

        assertEquals(false, dropin.isInformationRequest)
    }
}
