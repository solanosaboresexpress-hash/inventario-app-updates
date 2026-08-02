package com.tuapp.inventario.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.tuapp.inventario.utils.FirebaseRegionManager

class NotificationReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("NotificationReceiver", " Notificacion recibida: ${intent.action}")
        Log.d("NotificationReceiver", " Hora actual: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        
        when (intent.action) {
            AlarmScheduler.ACTION_STOCK_REMINDER -> {
                Log.d("NotificationReceiver", " Mostrando recordatorio de stock")
                val notificationHelper = NotificationHelper(context)
                notificationHelper.showStockReminderNotification()
                
                // Reprogramar la alarma para manana (para mantener la alarma diaria)
                val alarmScheduler = AlarmScheduler(context)
                // Obtener la hora configurada desde Firebase o SharedPreferences
                val horaAlerta = obtenerHoraAlertaConfigurada(context)
                alarmScheduler.scheduleStockReminder(horaAlerta)
                Log.d("NotificationReceiver", " Alarma reprogramada para manana a las $horaAlerta")
            }
            
            AlarmScheduler.ACTION_INGRESO_REMINDER -> {
                Log.d("NotificationReceiver", " Mostrando recordatorio de ingreso")
                val notificationHelper = NotificationHelper(context)
                notificationHelper.showIngresoReminderNotification()
            }
            
            AlarmScheduler.ACTION_VENCIMIENTOS_REMINDER -> {
                Log.d("NotificationReceiver", " Mostrando recordatorio de vencimientos")
                // Cargar vencimientos del dia y mostrar notificacion
                val notificationHelper = NotificationHelper(context)
                notificationHelper.showVencimientosNotificationAsync(context)
                
                // Reprogramar la alarma para manana
                val alarmScheduler = AlarmScheduler(context)
                alarmScheduler.scheduleVencimientosReminder()
                Log.d("NotificationReceiver", " Alarma de vencimientos reprogramada para manana a las 7:00 AM")
            }
            
            else -> {
                Log.w("NotificationReceiver", " Accion desconocida: ${intent.action}")
            }
        }
    }
    
    /**
     * Obtiene la hora de alarma configurada desde Firebase o SharedPreferences
     */
    private fun obtenerHoraAlertaConfigurada(context: Context): String {
        // Primero intentar desde SharedPreferences (mas rapido)
        val prefs = context.getSharedPreferences("configuracion_pedido_fabrica", Context.MODE_PRIVATE)
        val horaAlertaPrefs = prefs.getString("hora_alerta", null)
        if (horaAlertaPrefs != null) {
            Log.d("NotificationReceiver", " Hora obtenida desde SharedPreferences: $horaAlertaPrefs")
            return horaAlertaPrefs
        }
        
        // Si no esta en SharedPreferences, usar valor por defecto
        // (Firebase seria asincrono y no podemos esperar aqui)
        Log.d("NotificationReceiver", " Usando hora por defecto: 15:30")
        return "15:30"
    }
}
