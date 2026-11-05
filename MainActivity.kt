package com.nexus.desktop

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DecimalFormat

class MainActivity : Activity(), DisplayManager.DisplayListener {
    private lateinit var displayManager: DisplayManager
    private lateinit var output: TextView
    private val df = DecimalFormat("0.##")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFF101820.toInt())
        }

        val heading = TextView(this).apply {
            text = "NEXUS Desktop"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val subheading = TextView(this).apply {
            text = "Core + Display Engine  |  Prototype 0.1.0"
            textSize = 13f
            setTextColor(0xFF9FB3C8.toInt())
            setPadding(0, 4, 0, 20)
        }
        output = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFE6EDF3.toInt())
            setTextIsSelectable(true)
            setPadding(0, 16, 0, 16)
        }
        val refresh = Button(this).apply {
            text = "สแกนจออีกครั้ง"
            setOnClickListener { renderDisplays() }
        }
        val note = TextView(this).apply {
            text = "\nรุ่นทดลองนี้ยังไม่ย้ายแอปไปจอภายนอก และยังไม่เปลี่ยน Refresh Rate ของระบบ จุดประสงค์คือเก็บข้อมูลจอและความสามารถที่ OriginOS เปิดให้แอปเข้าถึงได้อย่างปลอดภัย"
            textSize = 12f
            setTextColor(0xFF9FB3C8.toInt())
        }

        page.addView(heading)
        page.addView(subheading)
        page.addView(refresh)
        page.addView(output, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        page.addView(note)

        val scroll = ScrollView(this).apply { addView(page) }
        setContentView(scroll)
        renderDisplays()
    }

    override fun onResume() {
        super.onResume()
        displayManager.registerDisplayListener(this, null)
        renderDisplays()
    }

    override fun onPause() {
        displayManager.unregisterDisplayListener(this)
        super.onPause()
    }

    override fun onDisplayAdded(displayId: Int) = renderDisplays()
    override fun onDisplayRemoved(displayId: Int) = renderDisplays()
    override fun onDisplayChanged(displayId: Int) = renderDisplays()

    private fun renderDisplays() {
        val displays = displayManager.displays
        val text = StringBuilder()
        text.append("อุปกรณ์\\n")
        text.append("Manufacturer: ${android.os.Build.MANUFACTURER}\\n")
        text.append("Model: ${android.os.Build.MODEL}\\n")
        text.append("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\\n")
        text.append("จำนวนจอที่ระบบรายงาน: ${displays.size}\\n")
        text.append("\\n================================\\n")

        if (displays.isEmpty()) {
            text.append("ยังไม่พบจอแสดงผล")
        } else {
            displays.sortedBy { it.displayId }.forEachIndexed { index, display ->
                text.append("\\nจอที่ ${index + 1}\\n")
                text.append("ชื่อ: ${display.name}\\n")
                text.append("Display ID: ${display.displayId}\\n")
                text.append("ประเภท: ${if (display.displayId == Display.DEFAULT_DISPLAY) "จอหลักของโทรศัพท์" else "จอเพิ่มเติม / Virtual / External"}\\n")
                text.append("สถานะ: ${stateName(display.state)}\\n")
                text.append("ขนาดปัจจุบัน: ${display.mode.physicalWidth} × ${display.mode.physicalHeight}\\n")
                text.append("Refresh Rate ปัจจุบัน: ${df.format(display.refreshRate)} Hz\\n")
                text.append("Density: ${display.metrics.densityDpi} dpi\\n")
                text.append("FLAG_PRESENTATION: ${(display.flags and Display.FLAG_PRESENTATION) != 0}\\n")
                text.append("FLAG_SECURE: ${(display.flags and Display.FLAG_SECURE) != 0}\\n")
                text.append("โหมดที่ระบบรายงาน:\\n")
                display.supportedModes.forEach { mode ->
                    text.append("  • ${mode.physicalWidth}×${mode.physicalHeight} @ ${df.format(mode.refreshRate)} Hz (mode ${mode.modeId})\\n")
                }
                text.append("--------------------------------\\n")
            }
        }
        text.append("\\nหมายเหตุ: Android/OriginOS อาจซ่อนหรือจำกัดข้อมูลบางอย่าง แอปนี้แสดงเฉพาะข้อมูลที่ API อนุญาตให้เห็น")
        runOnUiThread { output.text = text.toString() }
    }

    private fun stateName(state: Int): String = when (state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_UNKNOWN -> "UNKNOWN"
        else -> "OTHER ($state)"
    }
}
