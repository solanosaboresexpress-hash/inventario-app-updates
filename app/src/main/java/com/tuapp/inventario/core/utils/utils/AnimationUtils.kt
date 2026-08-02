package com.tuapp.inventario.utils

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Utilidad para animaciones fluidas y consistentes en toda la app
 */
object AnimationUtils {
    
    /**
     * Animación de entrada suave para vistas (fade + scale)
     */
    fun animateViewIn(view: View, duration: Long = 250L) {
        view.alpha = 0f
        view.scaleX = 0.8f
        view.scaleY = 0.8f
        view.visibility = View.VISIBLE
        
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }
    
    /**
     * Animación de salida suave para vistas (fade + scale)
     */
    fun animateViewOut(view: View, duration: Long = 200L, onComplete: (() -> Unit)? = null) {
        view.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(duration)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.visibility = View.GONE
                onComplete?.invoke()
            }
            .start()
    }
    
    /**
     * Animación de pulsación para botones y elementos clickeables
     */
    fun animatePress(view: View) {
        view.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }
    
    /**
     * Animación de shake para errores o alertas
     */
    fun animateShake(view: View) {
        view.animate()
            .translationX(20f)
            .setDuration(50)
            .withEndAction {
                view.animate()
                    .translationX(-20f)
                    .setDuration(50)
                    .withEndAction {
                        view.animate()
                            .translationX(10f)
                            .setDuration(50)
                            .withEndAction {
                                view.animate()
                                    .translationX(0f)
                                    .setDuration(50)
                                    .start()
                            }
                            .start()
                    }
                    .start()
            }
            .start()
    }
    
    /**
     * Animación de fade suave para cambiar contenido
     */
    fun crossFade(viewOut: View, viewIn: View, duration: Long = 300L) {
        viewOut.animate()
            .alpha(0f)
            .setDuration(duration / 2)
            .withEndAction {
                viewOut.visibility = View.GONE
                viewIn.alpha = 0f
                viewIn.visibility = View.VISIBLE
                viewIn.animate()
                    .alpha(1f)
                    .setDuration(duration / 2)
                    .start()
            }
            .start()
    }
}
