package com.rustech.textgrab

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

data class Item(val text: String, val rect: Rect)

class GrabService : AccessibilityService() {

    companion object {
        const val PREFS = "cfg"
        const val KEY_OPACITY = "opacity"
        const val KEY_SIZE = "size"
        private const val KEY_X = "bx"
        private const val KEY_Y = "by"

        @Volatile
        var instance: GrabService? = null
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: FrameLayout? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var overlay: View? = null
    private var picker: View? = null
    private var opacityPanel: View? = null

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_OPACITY || key == KEY_SIZE) applyBubbleStyle()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (::wm.isInitialized) {
            hideOverlay()
            hideOpacityPanel()
            bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        }
        bubble = null
        instance = null
    }

    // ───────────────────────── floating bubble ─────────────────────────

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(prefs.getInt(KEY_SIZE, 56))

        val v = FrameLayout(this)
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF1E88E5.toInt())
            setStroke(dp(2), Color.WHITE)
        }
        v.addView(
            TextView(this).apply {
                text = "Aa"
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, 0)
            y = prefs.getInt(KEY_Y, dp(240))
        }

        v.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false
            var longPressed = false
            val longRun = Runnable {
                longPressed = true
                showOpacityPanel()
            }

            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x; startY = lp.y
                        touchX = e.rawX; touchY = e.rawY
                        moved = false; longPressed = false
                        handler.postDelayed(longRun, 600)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - touchX
                        val dy = e.rawY - touchY
                        if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                            moved = true
                            handler.removeCallbacks(longRun)
                        }
                        if (moved) {
                            lp.x = (startX + dx).toInt().coerceAtLeast(0)
                            lp.y = (startY + dy).toInt().coerceAtLeast(0)
                            try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longRun)
                        if (moved) {
                            prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                        } else if (!longPressed) {
                            onBubbleTap()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longRun)
                }
                return true
            }
        })

        bubble = v
        bubbleLp = lp
        wm.addView(v, lp)
        applyBubbleStyle()
    }

    private fun applyBubbleStyle() {
        val b = bubble ?: return
        val lp = bubbleLp ?: return
        b.alpha = prefs.getInt(KEY_OPACITY, 70) / 100f
        val s = dp(prefs.getInt(KEY_SIZE, 56))
        if (lp.width != s) {
            lp.width = s; lp.height = s
            try { wm.updateViewLayout(b, lp) } catch (_: Exception) {}
        }
    }

    private fun onBubbleTap() {
        if (overlay != null) hideOverlay() else startTextMode()
    }

    // ───────────────────────── opacity panel (long-press bubble) ─────────────────────────

    private fun showOpacityPanel() {
        hideOpacityPanel()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16).toFloat()
            }
        }
        val cur = prefs.getInt(KEY_OPACITY, 70)
        val label = TextView(this).apply {
            text = "Opacity: $cur%"
            setTextColor(Color.BLACK)
            textSize = 16f
        }
        val sb = SeekBar(this).apply {
            max = 80
            progress = cur - 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = p + 20
                    label.text = "Opacity: $v%"
                    if (fromUser) prefs.edit().putInt(KEY_OPACITY, v).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val ok = Button(this).apply {
            text = "OK"
            isAllCaps = false
            setOnClickListener { hideOpacityPanel() }
        }
        card.addView(label)
        card.addView(sb)
        card.addView(ok)

        val lp = WindowManager.LayoutParams(
            (realMetrics().widthPixels * 0.8).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        opacityPanel = card
        wm.addView(card, lp)
    }

    private fun hideOpacityPanel() {
        opacityPanel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        opacityPanel = null
    }

    // ───────────────────────── reading text ─────────────────────────

    private fun startTextMode() {
        val items = collectNodes()
        if (items.isEmpty()) toast("Walang nabasang text. Subukan ang OCR.")
        showOverlay(items, false)
    }

    private fun collectNodes(): List<Item> {
        val out = ArrayList<Item>()
        val root = rootInActiveWindow ?: return out
        walk(root, out, 0)
        return out
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<Item>, depth: Int) {
        if (n == null || depth > 60) return
        if (!n.isVisibleToUser) return
        if (!n.isPassword) {
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty()) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) out.add(Item(t, r))
            }
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), out, depth + 1)
    }

    // ───────────────────────── OCR mode ─────────────────────────

    private fun startOcr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("Android 11+ lang ang OCR")
            return
        }
        doOcr()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doOcr() {
        hideOverlay()
        bubble?.visibility = View.INVISIBLE
        handler.postDelayed({
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    bubble?.visibility = View.VISIBLE
                    val hb = result.hardwareBuffer
                    val bmp: Bitmap? = try {
                        Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (_: Exception) { null }
                    hb.close()
                    if (bmp == null) {
                        toast("Hindi makuha ang screenshot")
                        return
                    }
                    recognizer.process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener { text ->
                            val items = text.textBlocks
                                .flatMap { it.lines }
                                .mapNotNull { line ->
                                    line.boundingBox?.let { Item(line.text, Rect(it)) }
                                }
                            if (items.isEmpty()) toast("Walang nabasa ang OCR")
                            showOverlay(items, true)
                        }
                        .addOnFailureListener { toast("OCR error: ${it.message}") }
                }

                override fun onFailure(errorCode: Int) {
                    bubble?.visibility = View.VISIBLE
                    toast("Screenshot failed (code $errorCode). Subukan ulit pagkatapos ng 1 segundo.")
                }
            })
        }, 250)
    }

    // ───────────────────────── selection overlay ─────────────────────────

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun showOverlay(raw: List<Item>, ocr: Boolean) {
        hideOverlay()
        val items = raw.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        val dm = realMetrics()

        val root = FrameLayout(this)
        root.setBackgroundColor(0x88000000.toInt())
        root.setOnClickListener { hideOverlay() }

        for (item in items) {
            val r = item.rect
            val box = View(this)
            box.background = GradientDrawable().apply {
                setColor(0x44FFEB3B)
                setStroke(dp(1), 0xFFFFC107.toInt())
                cornerRadius = dp(3).toFloat()
            }
            val lp = FrameLayout.LayoutParams(maxOf(r.width(), dp(20)), maxOf(r.height(), dp(20)))
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = r.left
            lp.topMargin = r.top
            box.setOnClickListener { copyText(item.text) }
            box.setOnLongClickListener { showPicker(item.text); true }
            root.addView(box, lp)
        }

        // toolbar sa taas
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(0xEE212121.toInt()); cornerRadius = dp(28).toFloat()
            }
        }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        bar.addView(btn("Copy lahat") {
            if (items.isEmpty()) toast("Walang ma-copy")
            else copyText(items.joinToString("\n") { it.text })
        })
        bar.addView(btn(if (ocr) "Text" else "OCR") {
            if (ocr) startTextMode() else startOcr()
        })
        bar.addView(btn("✕") { hideOverlay() })
        top.addView(bar)
        top.addView(TextView(this).apply {
            text = if (items.isEmpty()) "Walang nabasang text."
            else "${items.size} nakita  •  tap = kopyahin  •  hawakan = pumili ng salita"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(
            top,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = statusBarHeight() + dp(8)
            }
        )

        val lp = WindowManager.LayoutParams(
            dm.widthPixels, dm.heightPixels,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        overlay = root
        wm.addView(root, lp)
    }

    private fun hideOverlay() {
        hidePicker()
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    // ───────────────────────── word picker ─────────────────────────

    private fun chipBg(selected: Boolean) = GradientDrawable().apply {
        setColor(if (selected) 0xFF1E88E5.toInt() else 0xFFE0E0E0.toInt())
        cornerRadius = dp(14).toFloat()
    }

    private fun showPicker(text: String) {
        hidePicker()
        val dm = realMetrics()
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val selected = BooleanArray(words.size)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16).toFloat()
            }
        }
        card.addView(TextView(this).apply {
            this.text = "Pumili ng salita (tap para i-select)"
            setTextColor(Color.DKGRAY)
            textSize = 14f
        })

        val flow = FlowLayout(this).apply {
            gap = dp(6)
            setPadding(0, dp(10), 0, dp(10))
        }
        words.forEachIndexed { i, w ->
            val tv = TextView(this).apply {
                this.text = w
                textSize = 16f
                setTextColor(Color.BLACK)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = chipBg(false)
            }
            tv.setOnClickListener {
                selected[i] = !selected[i]
                tv.background = chipBg(selected[i])
                tv.setTextColor(if (selected[i]) Color.WHITE else Color.BLACK)
            }
            flow.addView(tv)
        }
        val scroll = MaxHeightScroll(this, (dm.heightPixels * 0.45).toInt())
        scroll.addView(flow)
        card.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            this.text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        row.addView(btn("Copy napili") {
            val chosen = words.filterIndexed { i, _ -> selected[i] }
            if (chosen.isEmpty()) toast("Wala pang napili")
            else { copyText(chosen.joinToString(" ")); hidePicker() }
        })
        row.addView(btn("Copy buo") { copyText(text); hidePicker() })
        row.addView(btn("Isara") { hidePicker() })
        card.addView(row)

        val lp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        picker = card
        wm.addView(card, lp)
    }

    private fun hidePicker() {
        picker?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        picker = null
    }

    // ───────────────────────── clipboard ─────────────────────────

    private fun copyText(t: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("TextGrab", t))
        val preview = if (t.length > 40) t.take(40) + "…" else t
        toast("Na-copy: $preview")
    }
}
