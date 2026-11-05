package dev.freedesk

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.system.Os
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Runs inside the Shizuku user service process (shell uid). Everything here that touches
 * hidden framework API goes through reflection so the project compiles against the public SDK,
 * and every step records why it failed so diagnose() can tell us what this ROM allows.
 */
class DeskService : IDeskService.Stub() {

    private companion object {
        const val TAG = "FreeDeskSvc"
        val INT: Class<*> = Int::class.javaPrimitiveType!!
        val FLOAT: Class<*> = Float::class.javaPrimitiveType!!

        // VirtualDisplay flags (DisplayManager.VIRTUAL_DISPLAY_FLAG_*; most are @hide).
        const val OWN_CONTENT_ONLY = 1 shl 3
        const val SUPPORTS_TOUCH = 1 shl 6
        const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val TRUSTED = 1 shl 10
        const val OWN_DISPLAY_GROUP = 1 shl 11
        const val ALWAYS_UNLOCKED = 1 shl 12
        const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val OWN_FOCUS = 1 shl 14
        const val DEVICE_DISPLAY_GROUP = 1 shl 15
        const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16
        const val SECURE = 1 shl 2 // lets protected (DRM / FLAG_SECURE) layers render here

        const val MINIMAL = OWN_CONTENT_ONLY or SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL or
            TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        const val FULL = MINIMAL or OWN_FOCUS or DEVICE_DISPLAY_GROUP or STEAL_TOP_FOCUS_DISABLED

        // WindowManager display IME policy: 0 local, 1 show on the default display, 2 hide.
        const val IME_POLICY_FALLBACK_DISPLAY = 1

        val SETTINGS_KEYS = listOf(
            "force_resizable_activities",
            "enable_freeform_support",
            "freeform_window_management",
            "force_desktop_mode_on_external_displays",
        )
        val COMPONENT_RE = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$")
    }

    private val ctx: Context by lazy { ShellContext.get() }

    private val displayManager: DisplayManager by lazy {
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(ctx)
    }

    private val displays = HashMap<Int, VirtualDisplay>()
    private var counter = 0
    private var ownerUid = -1

    private var lastTier = "none yet"
    private var lastSecure = "not requested"
    private var lastRefreshNote = "not requested"
    private var lastImeNote = "not applied"
    private val failures = ArrayList<String>()

    init {
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (t: Throwable) {
            Log.w(TAG, "hidden api bypass failed: $t")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun requireOwner() {
        val uid = Binder.getCallingUid()
        synchronized(this) { if (ownerUid == -1) ownerUid = uid }
        if (uid != ownerUid) throw SecurityException("unexpected caller $uid")
    }

    private fun unwrap(t: Throwable): String {
        var e: Throwable = t
        while (e is InvocationTargetException && e.cause != null) e = e.cause!!
        return "${e.javaClass.simpleName}: ${e.message}"
    }

    private fun note(msg: String) {
        Log.w(TAG, msg)
        synchronized(failures) {
            if (failures.size >= 30) failures.removeAt(0)
            failures.add(msg)
        }
    }

    private fun run(cmd: List<String>, timeoutMs: Long = 5000): String {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val sb = StringBuilder()
        val reader = Thread {
            try { sb.append(p.inputStream.bufferedReader().readText()) } catch (_: Throwable) {}
        }
        reader.start()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return "Error: timeout running ${cmd.first()}"
        }
        reader.join(500)
        return sb.toString().trim()
    }

    // -------------------------------------------------------- virtual display

    private fun createViaConfig(
        name: String, w: Int, h: Int, dpi: Int, surface: Surface?, flags: Int, rate: Float,
    ): VirtualDisplay? {
        if (rate <= 0f) return null
        return try {
            val b = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val builder = b.getConstructor(String::class.java, INT, INT, INT).newInstance(name, w, h, dpi)
            b.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
            b.getMethod("setFlags", INT).invoke(builder, flags)
            b.getMethod("setRequestedRefreshRate", FLOAT).invoke(builder, rate)
            val config = b.getMethod("build").invoke(builder)
            val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val m: Method = DisplayManager::class.java.methods.first {
                it.name == "createVirtualDisplay" && it.parameterTypes.isNotEmpty() &&
                    it.parameterTypes[0] == configClass
            }
            val args = arrayOfNulls<Any>(m.parameterTypes.size)
            args[0] = config
            val vd = m.invoke(displayManager, *args) as VirtualDisplay?
            lastRefreshNote = if (vd != null) "VirtualDisplayConfig accepted rate=$rate" else "config API returned null"
            vd
        } catch (t: Throwable) {
            lastRefreshNote = "VirtualDisplayConfig path failed: ${unwrap(t)}"
            null
        }
    }

    private fun createWithTiers(
        name: String, w: Int, h: Int, dpi: Int, surface: Surface?, rate: Float, secure: Boolean,
    ): VirtualDisplay {
        val extra = if (secure) SECURE else 0
        val tiers = listOf(
            "full" to (FULL or extra),
            "no-own-focus" to ((FULL and (OWN_FOCUS or DEVICE_DISPLAY_GROUP or STEAL_TOP_FOCUS_DISABLED).inv()) or extra),
            "minimal" to (OWN_CONTENT_ONLY or SUPPORTS_TOUCH or TRUSTED or extra),
        )
        for ((label, flags) in tiers) {
            try {
                val vd = createViaConfig(name, w, h, dpi, surface, flags, rate)
                    ?: displayManager.createVirtualDisplay(name, w, h, dpi, surface, flags)
                if (vd != null) {
                    lastTier = "$label (0x${flags.toString(16)})"
                    lastSecure = if (secure) "SECURE flag accepted" else "not requested"
                    return vd
                }
            } catch (t: Throwable) {
                note("createVirtualDisplay tier=$label failed: ${unwrap(t)}")
            }
        }
        if (secure) {
            note("all tiers failed with SECURE; retrying without it (DRM apps will stay black)")
            val vd = createWithTiers(name, w, h, dpi, surface, rate, false)
            lastSecure = "SECURE REFUSED by system, created non-secure"
            return vd
        }
        throw IllegalStateException("createVirtualDisplay failed; see Diagnostics")
    }

    @Synchronized
    override fun createDisplay(surface: Surface?, width: Int, height: Int, dpi: Int, refreshRate: Float, secure: Boolean): Int {
        requireOwner()
        val vd = createWithTiers("FreeDesk-${++counter}", width, height, dpi, surface, refreshRate, secure)
        val id = vd.display.displayId
        displays[id] = vd
        applyImePolicy(id)
        return id
    }

    @Synchronized
    override fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int) {
        requireOwner()
        displays[displayId]?.resize(width, height, dpi)
    }

    @Synchronized
    override fun setSurface(displayId: Int, surface: Surface?) {
        requireOwner()
        displays[displayId]?.surface = surface
    }

    @Synchronized
    override fun releaseDisplay(displayId: Int) {
        requireOwner()
        displays.remove(displayId)?.release()
    }

    @Synchronized
    override fun destroy() {
        displays.values.forEach { try { it.release() } catch (_: Throwable) {} }
        displays.clear()
        System.exit(0)
    }

    // ------------------------------------------------------------------ IME

    private fun applyImePolicy(displayId: Int) {
        lastImeNote = try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "window") as IBinder
            val wm = Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val set = wm.javaClass.methods.first { it.name == "setDisplayImePolicy" && it.parameterTypes.size == 2 }
            set.invoke(wm, displayId, IME_POLICY_FALLBACK_DISPLAY)
            val get = wm.javaClass.methods.firstOrNull { it.name == "getDisplayImePolicy" && it.parameterTypes.size == 1 }
            "display $displayId policy set to FALLBACK, now=${get?.invoke(wm, displayId)}"
        } catch (t: Throwable) {
            "setDisplayImePolicy failed: ${unwrap(t)}"
        }
    }

    // ---------------------------------------------------------------- input

    private class Injector(val target: Any?, val method: Method, val threeArgs: Boolean, val label: String)

    private val injector: Injector by lazy { resolveInjector() }

    private val setDisplayId: Method? by lazy {
        try { InputEvent::class.java.getMethod("setDisplayId", INT) } catch (t: Throwable) {
            note("InputEvent.setDisplayId missing: ${unwrap(t)}"); null
        }
    }

    private fun resolveInjector(): Injector {
        val sigs = listOf(arrayOf(InputEvent::class.java, INT), arrayOf(InputEvent::class.java, INT, INT))
        val im = ctx.getSystemService(Context.INPUT_SERVICE)
        if (im != null) {
            for (s in sigs) try {
                return Injector(im, im.javaClass.getMethod("injectInputEvent", *s), s.size == 3, "InputManager/${s.size}")
            } catch (_: NoSuchMethodException) {}
        }
        val g = Class.forName("android.hardware.input.InputManagerGlobal")
        val inst = g.getMethod("getInstance").invoke(null)
        for (s in sigs) try {
            return Injector(inst, g.getMethod("injectInputEvent", *s), s.size == 3, "InputManagerGlobal/${s.size}")
        } catch (_: NoSuchMethodException) {}
        throw IllegalStateException("no injectInputEvent signature found")
    }

    private fun inject(displayId: Int, event: InputEvent) {
        if (!displays.containsKey(displayId)) return
        try {
            requireOwner()
            setDisplayId?.invoke(event, displayId)
            val inj = injector
            val ok = if (inj.threeArgs) inj.method.invoke(inj.target, event, 0, -1)
            else inj.method.invoke(inj.target, event, 0)
            if (ok == false) note("inject returned false via ${inj.label}")
        } catch (t: Throwable) {
            note("inject failed: ${unwrap(t)}")
        }
    }

    override fun injectMotion(displayId: Int, event: MotionEvent?) {
        if (event != null) inject(displayId, event)
    }

    override fun injectKey(displayId: Int, event: KeyEvent?) {
        if (event != null) inject(displayId, event)
    }

    // --------------------------------------------------------------- launch

    override fun launch(displayId: Int, component: String?, newTask: Boolean): String {
        requireOwner()
        if (component == null || !COMPONENT_RE.matches(component)) return "Error: bad component"
        if (!displays.containsKey(displayId)) return "Error: unknown display $displayId"
        val cmd = ArrayList<String>()
        cmd += listOf("am", "start", "--display", displayId.toString())
        if (newTask) cmd += listOf("-f", "0x18000000") // NEW_TASK | MULTIPLE_TASK
        cmd += listOf("-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", component)
        val out = run(cmd)
        Log.i(TAG, "am start $component -> $out")
        return if (out.contains("Error", ignoreCase = true) || out.contains("Exception")) out else ""
    }

    // ----------------------------------------------------------- diagnostics

    override fun applySettings(enable: Boolean): String {
        requireOwner()
        val v = if (enable) "1" else "0"
        val sb = StringBuilder()
        for (k in listOf("force_resizable_activities", "enable_freeform_support")) {
            val out = run(listOf("settings", "put", "global", k, v))
            sb.append("$k=$v ${if (out.isEmpty()) "ok" else out}\n")
        }
        return sb.toString().trim()
    }

    override fun diagnose(): String {
        requireOwner()
        val sb = StringBuilder()
        fun line(s: String) { sb.append(s).append('\n') }
        line("== service ==")
        line("uid=${Os.getuid()} sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
        line("device=${Build.MANUFACTURER} ${Build.MODEL} display=${Build.DISPLAY}")
        line("== settings ==")
        for (k in SETTINGS_KEYS) line("$k=${run(listOf("settings", "get", "global", k))}")
        line("== virtual display ==")
        line("flags tier used: $lastTier")
        line("secure/DRM: $lastSecure")
        line("refresh: $lastRefreshNote")
        line("ime: $lastImeNote")
        line("injector: " + try { injector.label } catch (t: Throwable) { "NONE (${unwrap(t)})" })
        line("setDisplayId: " + if (setDisplayId != null) "ok" else "missing")
        line("== displays ==")
        try {
            for (d in displayManager.displays) {
                val modes = d.supportedModes
                    .map { "${it.physicalWidth}x${it.physicalHeight}@${"%.0f".format(it.refreshRate)}" }
                    .distinct().joinToString(", ")
                line("#${d.displayId} ${d.name} flags=0x${d.flags.toString(16)} " +
                    "now=${"%.0f".format(d.refreshRate)}Hz modes=[$modes]")
                if (d.flags and 64 != 0) line("   (has system decorations)")
            }
        } catch (t: Throwable) {
            line("display list failed: ${unwrap(t)}")
        }
        line("== recent failures ==")
        synchronized(failures) { if (failures.isEmpty()) line("none") else failures.forEach { line(it) } }
        return sb.toString()
    }
}
