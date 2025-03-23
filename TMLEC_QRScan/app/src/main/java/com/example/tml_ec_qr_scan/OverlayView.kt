package com.example.tml_ec_qr_scan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val points = mutableListOf<PointF>()
    private val paint = Paint().apply {
        color = 0xFFFF0000.toInt() // Red color
        style = Paint.Style.FILL
        strokeWidth = 15f
    }

    fun updatePoints(newPoints: List<PointF>) {
        points.clear()
        points.addAll(newPoints)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        points.forEach { point ->
            canvas.drawCircle(point.x, point.y, 20f, paint)
        }
    }
}