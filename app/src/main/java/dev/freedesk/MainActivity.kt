package dev.freedesk

import android.app.Activity
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Display
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var dpiField: EditText
    private lateinit var hzField: EditText
    private lateinit var displayBox: LinearLayout
    private lateinit var secureBox: CheckBox

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        status = TextView(this).apply { textSize = 15f }
        col.addView(status)

        col.addView(button("1. Grant Shizuku permission") { requestPermission() })
        col.addView(button("2. Run diagnostics") { runDiagnostics() })
        col.addView(button("Copy report") { copyReport() })
        col.addView(button("Enable resizable/freeform settings") { applySettings(true) })
        col.addView(button("Revert those settings") { applySettings(false) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dpiField = EditText(this).apply {
            hint = "dpi"; setText("240"); inputType = InputType.TYPE_CLASS_NUMBER
        }
        hzField = EditText(this).apply {
            hint = "Hz"; setText("120"); inputType = InputType.TYPE_CLASS_NUMBER
        }
        row.addView(dpiField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(hzField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        secureBox = CheckBox(this).apply { text = "Secure / DRM mode (experimental)" }
        col.addView(secureBox)
        col.addView(button("Open desktop on THIS screen (test)") { openDesktop(Display.DEFAULT_DISPLAY) })
        displayBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(displayBox)

        output = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, 0)
        }
        col.addView(output)

        setContentView(ScrollView(this).apply { addView(col) })

        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        super.onDestroy()
    }

    private fun refresh() {
        val running = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        val granted = ServiceClient.shizukuReady()
        status.text = buildString {
            append("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ${Build.MODEL}\n")
            append("Shizuku running: $running\n")
            append("Permission granted: $granted\n")
            if (running) append("Shizuku uid: ${try { Shizuku.getUid() } catch (_: Throwable) { "?" }}\n")
            if (!running) append("\nStart Shizuku first (Wireless debugging -> Start in the Shizuku app).")
        }
        listDisplays()
    }

    private fun listDisplays() {
        displayBox.removeAllViews()
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        for (d in dm.displays) {
            if (d.displayId == Display.DEFAULT_DISPLAY || d.name.startsWith("FreeDesk-")) continue
            val m = d.mode
            val label = "Open desktop on display #${d.displayId} ${d.name} " +
                "(${m.physicalWidth}x${m.physicalHeight}@${"%.0f".format(d.refreshRate)}Hz)"
            displayBox.addView(button(label) { openDesktop(d.displayId) })
        }
    }

    private fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) { toast("Shizuku is not running"); return }
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                toast("Already granted"); refresh(); return
            }
            Shizuku.requestPermission(1)
        } catch (t: Throwable) {
            toast("Shizuku error: ${t.message}")
        }
    }

    private fun runDiagnostics() {
        output.text = "Running..."
        ServiceClient.get { svc ->
            if (svc == null) { output.text = "Service unavailable (is Shizuku running and permitted?)"; return@get }
            Thread {
                val text = try { svc.diagnose() } catch (t: Throwable) { "diagnose failed: $t" }
                runOnUiThread { output.text = text }
            }.start()
        }
    }

    private fun applySettings(enable: Boolean) {
        ServiceClient.get { svc ->
            if (svc == null) { toast("Service unavailable"); return@get }
            Thread {
                val text = try { svc.applySettings(enable) } catch (t: Throwable) { "failed: $t" }
                runOnUiThread { output.text = text }
            }.start()
        }
    }

    private fun copyReport() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FreeDesk report", output.text))
        toast("Copied")
    }

    private fun openDesktop(displayId: Int) {
        val intent = Intent(this, DesktopActivity::class.java)
            .putExtra("dpi", dpiField.text.toString().toIntOrNull() ?: 240)
            .putExtra("hz", (hzField.text.toString().toFloatOrNull() ?: 120f))
            .putExtra("secure", secureBox.isChecked)
        if (displayId == Display.DEFAULT_DISPLAY) {
            startActivity(intent)
        } else {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            try { startActivity(intent, opts.toBundle()) } catch (t: Throwable) { toast("Launch failed: ${t.message}") }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

}
