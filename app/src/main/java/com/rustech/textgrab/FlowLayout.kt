package com.rustech.textgrab

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView

/** Simpleng wrap layout para sa mga word chips. */
class FlowLayout(ctx: Context) : ViewGroup(ctx) {
    var gap = 0

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(
                MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val w = c.measuredWidth
            val h = c.measuredHeight
            if (x > 0 && x + w > maxW) {
                x = 0; y += rowH + gap; rowH = 0
            }
            x += w + gap
            rowH = maxOf(rowH, h)
        }
        val total = y + rowH + paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(total, heightSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = (r - l) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val w = c.measuredWidth
            val h = c.measuredHeight
            if (x > 0 && x + w > maxW) {
                x = 0; y += rowH + gap; rowH = 0
            }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + w, paddingTop + y + h)
            x += w + gap
            rowH = maxOf(rowH, h)
        }
    }
}

/** ScrollView na may maximum height. */
class MaxHeightScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }
}
