package com.rustech.textgrab

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(GrabService.PREFS, MODE_PRIVATE)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }

        col.addView(TextView(this).apply {
            text = "TextGrab"
            textSize = 28f
            setTextColor(Color.BLACK)
        })

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(12), 0, dp(12))
        }
        col.addView(status)

        col.addView(Button(this).apply {
            text = "I-ON ang TextGrab (Accessibility)"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        addSlider(col, "Opacity ng bubble", GrabService.KEY_OPACITY, 20, 100, 70, "%")
        addSlider(col, "Laki ng bubble", GrabService.KEY_SIZE, 40, 96, 56, " dp")

        col.addView(TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(24), 0, 0)
            text = """
Paano gamitin:
1. Pindutin ang button sa taas > hanapin ang "TextGrab" > i-ON.
   (Android 13+: kung grayed out, Settings > Apps > TextGrab > ⋮ > "Allow restricted settings", tapos ulitin.)
2. Lalabas ang floating na bilog. I-drag kahit saan.
3. TAP ang bilog = lalabas ang mga text sa screen na naka-highlight. Tap ang isa = na-copy.
4. HAWAKAN (long-press) ang highlighted na text = pumili ng indibidwal na salita.
5. "Copy lahat" = kopyahin lahat ng text sa screen.
6. Kung walang nabasa (image, game, atbp.), pindutin ang "OCR" sa toolbar (Android 11+).
7. HAWAKAN ang bilog mismo = mabilis na i-adjust ang opacity.
""".trimIndent()
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun addSlider(
        parent: LinearLayout, label: String, key: String,
        min: Int, max: Int, def: Int, unit: String
    ) {
        val cur = prefs.getInt(key, def)
        val tv = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(0, dp(20), 0, 0)
            text = "$label: $cur$unit"
        }
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = cur - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = p + min
                    tv.text = "$label: $v$unit"
                    if (fromUser) prefs.edit().putInt(key, v).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        parent.addView(tv)
        parent.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun onResume() {
        super.onResume()
        val on = GrabService.instance != null
        status.text = if (on) "● Naka-ON — tumatakbo sa background"
        else "○ Naka-OFF — i-enable muna sa Accessibility settings"
        status.setTextColor(if (on) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
    }
}
