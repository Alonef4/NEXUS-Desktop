package dev.freedesk

import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * The Shizuku user service is a bare app_process (uid 2000) with no Application, so
 * framework managers that need a Context cannot be created normally. This builds a
 * system Context from a hand-made ActivityThread and makes it claim to be
 * com.android.shell, which is the identity the system trusts for these calls.
 */
object ShellContext {
    private const val TAG = "FreeDeskCtx"
    private const val SHELL_PKG = "com.android.shell"
    private const val SHELL_UID = 2000

    @Volatile
    private var cached: Context? = null

    @Synchronized
    fun get(): Context {
        cached?.let { return it }
        val c = build()
        cached = c
        return c
    }

    private class Wrapper(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PKG
        override fun getOpPackageName(): String = SHELL_PKG
        override fun getApplicationContext(): Context = this
        override fun createPackageContext(packageName: String?, flags: Int): Context = this
        override fun getDeviceId(): Int = 0
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PKG).build()
    }

    private fun build(): Context {
        val atClass = Class.forName("android.app.ActivityThread")
        var thread = atClass.getMethod("currentActivityThread").invoke(null)
        val fresh = thread == null
        if (thread == null) {
            thread = atClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            atClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            atClass.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
            try {
                val ccClass = Class.forName("android.app.ConfigurationController")
                val ctor = ccClass.getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"))
                ctor.isAccessible = true
                atClass.getDeclaredField("mConfigurationController").apply { isAccessible = true }
                    .set(thread, ctor.newInstance(thread))
            } catch (t: Throwable) {
                Log.w(TAG, "no ConfigurationController: $t")
            }
        }
        val system = atClass.getMethod("getSystemContext").invoke(thread) as Context
        val ctx = Wrapper(system)

        if (fresh) {
            try {
                val bindClass = Class.forName("android.app.ActivityThread\$AppBindData")
                val bind = bindClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                val info = ApplicationInfo().apply { packageName = SHELL_PKG }
                bindClass.getDeclaredField("appInfo").apply { isAccessible = true }.set(bind, info)
                atClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(thread, bind)
            } catch (t: Throwable) {
                Log.w(TAG, "no AppBindData: $t")
            }
            try {
                val app = Instrumentation.newApplication(Application::class.java, ctx)
                atClass.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(thread, app)
            } catch (t: Throwable) {
                Log.w(TAG, "no initial Application: $t")
            }
        }
        return ctx
    }
}
