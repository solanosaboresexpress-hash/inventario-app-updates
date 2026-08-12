package com.tuapp.inventario.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#4FC3F7")
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#4FC3F7")
        isAntiAlias = true
    }

    private val dimPaint = Paint().apply {
        color = Color.parseColor("#80000000")
        isAntiAlias = true
    }

    private val handleRadius = 24f
    private val touchPadding = 48f

    var corners = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        set(value) {
            field = value.copyOf()
            invalidate()
        }

    var imageRect: RectF? = null
        set(value) {
            field = value
            invalidate()
        }

    var onCornersChanged: ((FloatArray) -> Unit)? = null

    private var draggedCorner = -1

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (corners.all { it == 0f }) return

        val imgRect = imageRect ?: return

        // Dim outside the quad
        canvas.drawRect(0f, 0f, width.toFloat(), imgRect.top, dimPaint)
        canvas.drawRect(0f, imgRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, imgRect.top, imgRect.left, imgRect.bottom, dimPaint)
        canvas.drawRect(imgRect.right, imgRect.top, width.toFloat(), imgRect.bottom, dimPaint)

        // Draw quad lines
        val path = Path().apply {
            moveTo(corners[0], corners[1])
            lineTo(corners[2], corners[3])
            lineTo(corners[4], corners[5])
            lineTo(corners[6], corners[7])
            close()
        }

        // Clear dim inside quad
        canvas.save()
        canvas.clipPath(path)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.restore()

        // Draw quad outline
        canvas.drawPath(path, cornerPaint)

        // Draw corner handles
        for (i in 0 until 4) {
            val cx = corners[i * 2]
            val cy = corners[i * 2 + 1]
            canvas.drawCircle(cx, cy, handleRadius, fillPaint)
            canvas.drawCircle(cx, cy, handleRadius, cornerPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                draggedCorner = findCorner(event.x, event.y)
                return draggedCorner != -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggedCorner != -1) {
                    val imgRect = imageRect ?: return true
                    val x = event.x.coerceIn(imgRect.left, imgRect.right)
                    val y = event.y.coerceIn(imgRect.top, imgRect.bottom)
                    corners[draggedCorner * 2] = x
                    corners[draggedCorner * 2 + 1] = y
                    invalidate()
                    onCornersChanged?.invoke(corners)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                draggedCorner = -1
                return true
            }
        }
        return false
    }

    private fun findCorner(x: Float, y: Float): Int {
        for (i in 0 until 4) {
            val cx = corners[i * 2]
            val cy = corners[i * 2 + 1]
            val dx = x - cx
            val dy = y - cy
            if (Math.sqrt((dx * dx + dy * dy).toDouble()) < touchPadding) {
                return i
            }
        }
        return -1
    }
}
