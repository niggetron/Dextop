package moe.n4tsu.dextop

import android.app.Application
import android.content.Context
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Earliest, process-wide hidden-API initialization.
 *
 * Dextop reflects non-SDK display, window and input APIs from the main process,
 * the Linux runtime process and the privileged helpers. Content providers and
 * the accessibility service can start before any Activity or Service `onCreate`,
 * so the exemption has to be installed in `attachBaseContext()` of every process.
 * `addHiddenApiExemptions("")` is idempotent, so the existing calls in
 * MirrorService and DisplayTopologyController remain as a safety net.
 */
class DextopApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        HiddenApiBypass.addHiddenApiExemptions("")
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        isReady = true
        OperationLog.i(this, "HiddenApi", "process-wide bypass initialized")
    }

    companion object {
        lateinit var appContext: Context
            private set

        @Volatile
        var isReady: Boolean = false
            private set
    }
}
