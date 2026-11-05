// Web: none — a web page has no release build.
// Same: nothing.
// Differs: iOS reads the provisioning profile's `get-task-allow` (`PurseEnvironment.isDebugHost`); Android reads the host app's FLAG_DEBUGGABLE, as SecureFields Android does.
package eu.purse.core.environment

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.startup.Initializer

/**
 * Whether the app embedding the SDK is a debuggable build — the gate on [PurseEnvironment.TEST] and
 * [PurseEnvironment.DEVELOPMENT]. Read from the **host app's** `ApplicationInfo.FLAG_DEBUGGABLE`, never
 * from the SDK's own build (always published as release). `PurseHeadlessCheckout.init` takes no
 * `Context`, so [HostAppInitializer] records the flag at process start through androidx.startup's
 * `InitializationProvider`, which the SDK already relies on (`ProcessLifecycleOwner`).
 */
internal object HostApp {
    @Volatile
    private var recorded: Boolean? = null

    /**
     * `false` until [HostAppInitializer] has run — an app that strips androidx.startup's provider is
     * treated as a release build, like an iOS app whose provisioning profile can't be read.
     */
    val isDebuggable: Boolean get() = recorded ?: false

    fun record(applicationInfoFlags: Int) {
        recorded = isDebuggable(applicationInfoFlags)
    }

    fun isDebuggable(applicationInfoFlags: Int): Boolean = applicationInfoFlags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}

/** Records [HostApp.isDebuggable] at process start — registered in this library's manifest. */
internal class HostAppInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        HostApp.record(context.applicationInfo.flags)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
