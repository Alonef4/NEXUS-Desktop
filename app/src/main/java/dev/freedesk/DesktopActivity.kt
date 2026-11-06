package dev.freedesk

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * The "monitor": a SurfaceView that a virtual display (owned by the shell-uid service) renders
 * into with the GPU, so there is no encode/decode step. Touch, mouse and keyboard events that hit
 * this window are forwarded to that display.
 */
class DesktopActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var thaiButton: Button
    private val ui = Handler(Looper.getMainLooper())

    private var service: IDeskService? = null
    private var displayId = -1
    private var dpi = 240
    private var hz = 120f
    private var thai = false
    private var secure = false
    private var freeform = false
    private var game = false
    private lateinit var gameButton: Button
    private var surfaceW = 0
    private var surfaceH = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpi = intent.getIntExtra("dpi", 240)
        hz = intent.getFloatExtra("hz", 120f)
        secure = intent.getBooleanExtra("secure", false)
        freeform = intent.getBooleanExtra("freeform", false)
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        pickDisplayMode()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        surfaceView = SurfaceView(this).apply {
            if (secure) setSecure(true)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(surfaceView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildTaskbar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        // Edge-to-edge is enforced on Android 15+: keep the display and taskbar clear of bars/camera cutout.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(root)

        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { _, ev -> forwardMotion(ev); true }
        surfaceView.setOnGenericMotionListener { _, ev -> forwardMotion(ev); true }
        // Delivered only while pointer capture is on (game mode): raw relative mouse deltas.
        surfaceView.setOnCapturedPointerListener { _, ev -> forwardMotion(ev); true }

        ServiceClient.get { svc ->
            service = svc
            if (svc == null) {
                toast("Shizuku service unavailable")
            } else {
                try {
                    svc.asBinder().linkToDeath(IBinder.DeathRecipient {
                        runOnUiThread { toast("Shizuku service stopped (restart Shizuku, then reopen)") }
                    }, 0)
                } catch (_: Throwable) {}
                syncDisplay()
            }
        }
    }

    /** Ask the real screen to run at the requested refresh rate (the host side of "smooth"). */
    private fun pickDisplayMode() {
        try {
            val d = display ?: return
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .minByOrNull { abs(it.refreshRate - hz) } ?: return
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            lp.preferredRefreshRate = best.refreshRate
            window.attributes = lp
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- surface

    override fun surfaceCreated(holder: SurfaceHolder) {
        try { holder.surface.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) } catch (_: Throwable) {}
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        syncDisplay()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Keep the virtual display (and the apps on it) alive; just detach the output.
        try { if (displayId >= 0) service?.setSurface(displayId, null) } catch (_: Throwable) {}
    }

    private fun syncDisplay() {
        val svc = service ?: return
        val s = surfaceView.holder.surface
        if (!s.isValid || surfaceW <= 0) return
        try {
            if (displayId < 0) {
                displayId = svc.createDisplay(s, surfaceW, surfaceH, dpi, hz, secure)
                toast("Virtual display #$displayId ${surfaceW}x$surfaceH @${dpi}dpi")
            } else {
                svc.resizeDisplay(displayId, surfaceW, surfaceH, dpi)
                svc.setSurface(displayId, s)
            }
        } catch (t: Throwable) {
            toast("Display error: ${t.message}")
        }
    }

    override fun onDestroy() {
        if (isFinishing && displayId >= 0) {
            try { service?.releaseDisplay(displayId) } catch (_: Throwable) {}
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ input

    private fun forwardMotion(ev: MotionEvent) {
        val svc = service ?: return
        if (displayId < 0) return
        try { svc.injectMotion(displayId, ev) } catch (_: Throwable) {}
    }

    private fun injectKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            try { service?.injectKey(displayId, KeyEvent(t, t, action, code, 0)) } catch (_: Throwable) {}
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER -> return super.dispatchKeyEvent(e)
        }
        if (displayId < 0) return super.dispatchKeyEvent(e)

        // Ctrl+Alt+G leaves/enters game mode even when the mouse is captured.
        if (e.keyCode == KeyEvent.KEYCODE_G && e.isCtrlPressed && e.isAltPressed) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) toggleGame()
            return true
        }
        // Caps Lock flips EN/TH when the experimental Thai mode is in use.
        if (e.keyCode == KeyEvent.KEYCODE_CAPS_LOCK) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) toggleThai()
            return true
        }
        if (thai && handleThai(e)) return true

        try { service?.injectKey(displayId, e) } catch (_: Throwable) {}
        return true
    }

    private fun handleThai(e: KeyEvent): Boolean {
        if (e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return false
        val pair = Kedmanee.map[e.keyCode] ?: return false
        if (e.action == KeyEvent.ACTION_DOWN) {
            pasteText(if (e.isShiftPressed) pair.second else pair.first)
        }
        return true
    }

    /** Experimental: put the character on the clipboard, then send Ctrl+V to the guest. */
    private fun pasteText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("freedesk", text))
        ui.postDelayed({ injectPaste() }, 30)
    }

    private fun injectPaste() {
        val t = SystemClock.uptimeMillis()
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val seq = listOf(
            KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0, ctrl),
            KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, ctrl),
            KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, 0, ctrl),
            KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0),
        )
        for (k in seq) try { service?.injectKey(displayId, k) } catch (_: Throwable) {}
    }

    /** Game mode: capture the physical mouse so the host cursor hides and relative deltas reach the game. */
    private fun toggleGame() {
        game = !game
        gameButton.text = if (game) "Game ON" else "Game"
        if (game) {
            surfaceView.requestFocus()
            surfaceView.requestPointerCapture()
        } else {
            surfaceView.releasePointerCapture()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Capture is dropped by the system when focus is lost; take it back when we return.
        if (hasFocus && game) surfaceView.requestPointerCapture()
    }

    private fun toggleThai() {
        thai = !thai
        thaiButton.text = if (thai) "TH" else "EN"
    }

    // ---------------------------------------------------------------- taskbar

    private fun buildTaskbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF202124.toInt())
        }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        bar.addView(btn("Apps") { showApps() })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        gameButton = btn("Game") { toggleGame() }
        bar.addView(gameButton)
        thaiButton = btn("EN") { toggleThai() }
        bar.addView(thaiButton)
        bar.addView(btn("Back") { injectKey(KeyEvent.KEYCODE_BACK) })
        bar.addView(btn("Exit") { finish() })
        return bar
    }

    private fun showApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(i: Int) = apps[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
                val r = apps[i]
                val row = LinearLayout(this@DesktopActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }
                row.addView(ImageView(this@DesktopActivity).apply { setImageDrawable(r.loadIcon(pm)) },
                    LinearLayout.LayoutParams(dp(36), dp(36)))
                row.addView(TextView(this@DesktopActivity).apply {
                    text = r.loadLabel(pm)
                    textSize = 16f
                    setPadding(dp(16), 0, 0, 0)
                })
                return row
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Open on virtual display")
            .setAdapter(adapter) { _, which ->
                val a = apps[which].activityInfo
                launch("${a.packageName}/${a.name}")
            }
            .show()
    }

    private fun launch(component: String) {
        val svc = service ?: return
        val id = displayId
        if (id < 0) { toast("Display not ready"); return }
        Thread {
            val result = try { svc.launch(id, component, false, freeform) } catch (t: Throwable) { "Error: ${t.message}" }
            if (result.isNotEmpty()) runOnUiThread { toast(result.take(200)) }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
