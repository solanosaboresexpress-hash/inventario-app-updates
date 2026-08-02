package com.tuapp.inventario.utils

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.tuapp.inventario.R

object FooterHelper {
    
    /**
     * Actualiza el footer del desarrollador con la version actual de la aplicacion
     */
    fun actualizarFooter(activity: Activity) {
        try {
            val versionTextView = activity.findViewById<TextView>(R.id.txtVersionFooter)
            if (versionTextView != null) {
                val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val versionName = packageInfo.versionName
                versionTextView.text = "Sistema de Inventario v$versionName"
            }
        } catch (e: Exception) {
            // Si hay error, mantener el texto por defecto
        }
    }
    
    /**
     * Configura el footer en una actividad
     */
    fun configurarFooter(activity: Activity) {
        // El footer se incluye automaticamente en el layout
        // Solo actualizamos la version si es necesario
        actualizarFooter(activity)
    }
}
