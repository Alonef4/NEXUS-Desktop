package dev.freedesk

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/** Binds the privileged DeskService through Shizuku. Main-thread only. */
object ServiceClient {
    private var service: IDeskService? = null
    private var binding = false
    private val waiting = ArrayList<(IDeskService?) -> Unit>()

    private val args: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, DeskService::class.java.name))
            .daemon(false)
            .processNameSuffix("svc")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) IDeskService.Stub.asInterface(binder) else null
            binding = false
            flush()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private fun flush() {
        val s = service
        val copy = ArrayList(waiting)
        waiting.clear()
        copy.forEach { it(s) }
    }

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Calls [cb] with the connected service, or null if Shizuku is not usable. */
    fun get(cb: (IDeskService?) -> Unit) {
        val s = service
        if (s != null && s.asBinder().pingBinder()) { cb(s); return }
        if (!shizukuReady()) { cb(null); return }
        waiting.add(cb)
        if (!binding) {
            binding = true
            try {
                Shizuku.bindUserService(args, connection)
            } catch (_: Throwable) {
                binding = false
                service = null
                flush()
            }
        }
    }
}
