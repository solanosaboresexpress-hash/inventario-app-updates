package com.tuapp.inventario.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Vista personalizada para mostrar marca de agua "SABORES EXPRESS" en el fondo
 */
class WatermarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.parseColor("#15C59B34") // Color dorado muy transparente
        textSize = 120f // Tamano grande para marca de agua
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
        alpha = 30 // Muy transparente (0-255)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val text = "SABORES EXPRESS"
        val textWidth = paint.measureText(text)
        
        // Calcular posicion para centrar el texto
        val centerX = width / 2f
        val centerY = height / 2f
        
        // Rotar el canvas 45 grados para efecto diagonal
        canvas.save()
        canvas.rotate(-45f, centerX, centerY)
        
        // Dibujar el texto centrado
        val x = centerX - textWidth / 2f
        val y = centerY + (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, x, y, paint)
        
        // Dibujar texto adicional en otras posiciones para efecto de patron
        val offset = 300f
        canvas.drawText(text, x - offset, y - offset, paint)
        canvas.drawText(text, x + offset, y + offset, paint)
        
        canvas.restore()
    }
}

