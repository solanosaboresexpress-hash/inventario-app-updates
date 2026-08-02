package com.tuapp.inventario.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tuapp.inventario.utils.DateHelper
import java.util.*

class AlarmScheduler(private val context: Context) {
    
    companion object {
        const val ACTION_STOCK_REMINDER = "com.tuapp.inventario.STOCK_REMINDER"
        const val ACTION_INGRESO_REMINDER = "com.tuapp.inventario.INGRESO_REMINDER"
        const val ACTION_VENCIMIENTOS_REMINDER = "com.tuapp.inventario.VENCIMIENTOS_REMINDER"
        const val REQUEST_CODE_STOCK = 2001
        const val REQUEST_CODE_INGRESO = 2002
        const val REQUEST_CODE_VENCIMIENTOS = 2003
    }
    
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    
    fun scheduleStockReminder(horaAlerta: String = "15:30") {
        try {
            // Primero cancelar la alarma anterior para evitar duplicados
            cancelStockReminder()
            
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_STOCK_REMINDER
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_STOCK,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Parsear la hora configurada (formato "HH:mm")
            val timeParts = horaAlerta.split(":")
            val hour = timeParts.getOrNull(0)?.toIntOrNull() ?: 15
            val minute = timeParts.getOrNull(1)?.toIntOrNull() ?: 30
            
            // Programar para la hora configurada todos los doas
            val calendar = DateHelper.getCalendar().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                
                // Si ya paso la hora de hoy, programar para maoana
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }
            
            val triggerTime = calendar.timeInMillis
            val currentTime = System.currentTimeMillis()
            val timeUntilAlarm = triggerTime - currentTime
            
            Log.d("AlarmScheduler", " Programando alarma de stock:")
            Log.d("AlarmScheduler", "   Hora configurada: $horaAlerta")
            Log.d("AlarmScheduler", "   Hora actual: ${DateHelper.getCalendar().apply { timeInMillis = currentTime }.get(Calendar.HOUR_OF_DAY)}:${DateHelper.getCalendar().apply { timeInMillis = currentTime }.get(Calendar.MINUTE)}")
            Log.d("AlarmScheduler", "   Hora de alarma: ${calendar.get(Calendar.HOUR_OF_DAY)}:${calendar.get(Calendar.MINUTE)}")
            Log.d("AlarmScheduler", "   Tiempo hasta alarma: ${timeUntilAlarm / 1000 / 60} minutos")
            
            // Usar motodos modernos segon la version de Android
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6.0+ (API 23+): usar setExactAndAllowWhileIdle para mejor confiabilidad
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+ (API 31+): requiere SCHEDULE_EXACT_ALARM permission
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Log.d("AlarmScheduler", " Alarma programada con setExactAndAllowWhileIdle (Android 12+)")
                    } else {
                        // Fallback a setAlarmClock si no tiene permiso
                        alarmManager.setAlarmClock(
                            AlarmManager.AlarmClockInfo(triggerTime, pendingIntent),
                            pendingIntent
                        )
                        Log.d("AlarmScheduler", " Alarma programada con setAlarmClock (sin permiso exact)")
                    }
                } else {
                    // Android 6.0-11 (API 23-30): usar setExactAndAllowWhileIdle
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.d("AlarmScheduler", " Alarma programada con setExactAndAllowWhileIdle (Android 6-11)")
                }
                
                // La alarma se reprogramaro automoticamente cuando se dispare (en NotificationReceiver)
            } else {
                // Android 5.1 y anteriores: usar setRepeating (deprecado pero funciona)
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    AlarmManager.INTERVAL_DAY,
                    pendingIntent
                )
                Log.d("AlarmScheduler", " Alarma programada con setRepeating (Android < 6.0)")
            }
            
            Log.d("AlarmScheduler", " Recordatorio de stock programado para las $horaAlerta")
            
        } catch (e: Exception) {
            Log.e("AlarmScheduler", " Error programando recordatorio de stock", e)
            e.printStackTrace()
        }
    }
    
    fun scheduleIngresoReminder() {
        try {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_INGRESO_REMINDER
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_INGRESO,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Programar para las 9:00 todos los doas (recordatorio matutino)
            val calendar = DateHelper.getCalendar().apply {
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                
                // Si ya paso la hora de hoy, programar para maoana
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }
            
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pendingIntent
            )
            
            Log.d("AlarmScheduler", " Recordatorio de ingreso programado para las 9:00")
            
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "Error programando recordatorio de ingreso", e)
        }
    }
    
    fun cancelStockReminder() {
        try {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_STOCK_REMINDER
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_STOCK,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            alarmManager.cancel(pendingIntent)
            Log.d("AlarmScheduler", " Recordatorio de stock cancelado")
            
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "Error cancelando recordatorio de stock", e)
        }
    }
    
    fun cancelIngresoReminder() {
        try {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_INGRESO_REMINDER
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_INGRESO,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            alarmManager.cancel(pendingIntent)
            Log.d("AlarmScheduler", " Recordatorio de ingreso cancelado")
            
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "Error cancelando recordatorio de ingreso", e)
        }
    }
    
    fun scheduleAllReminders(horaAlerta: String = "15:30") {
        Log.d("AlarmScheduler", " Programando todos los recordatorios...")
        scheduleStockReminder(horaAlerta)
        scheduleIngresoReminder()
    }
    
    fun cancelAllReminders() {
        Log.d("AlarmScheduler", " Cancelando todos los recordatorios...")
        cancelStockReminder()
        cancelIngresoReminder()
        cancelVencimientosReminder()
    }
    
    /**
     * Programa una alarma diaria a las 7:00 AM para mostrar vencimientos del doa
     */
    fun scheduleVencimientosReminder() {
        try {
            // Primero cancelar la alarma anterior para evitar duplicados
            cancelVencimientosReminder()
            
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_VENCIMIENTOS_REMINDER
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_VENCIMIENTOS,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Programar para las 7:00 AM todos los doas
            val calendar = DateHelper.getCalendar().apply {
                set(Calendar.HOUR_OF_DAY, 7)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                
                // Si ya paso la hora de hoy, programar para maoana
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }
            
            val triggerTime = calendar.timeInMillis
            val currentTime = System.currentTimeMillis()
            val timeUntilAlarm = triggerTime - currentTime
            
            Log.d("AlarmScheduler", " Programando alarma de vencimientos:")
            Log.d("AlarmScheduler", "   Hora: 7:00 AM")
            Log.d("AlarmScheduler", "   Hora actual: ${DateHelper.getCalendar().apply { timeInMillis = currentTime }.get(Calendar.HOUR_OF_DAY)}:${DateHelper.getCalendar().apply { timeInMillis = currentTime }.get(Calendar.MINUTE)}")
            Log.d("AlarmScheduler", "   Hora de alarma: ${calendar.get(Calendar.HOUR_OF_DAY)}:${calendar.get(Calendar.MINUTE)}")
            Log.d("AlarmScheduler", "   Tiempo hasta alarma: ${timeUntilAlarm / 1000 / 60} minutos")
            
            // Usar motodos modernos segon la version de Android
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Log.d("AlarmScheduler", " Alarma de vencimientos programada con setExactAndAllowWhileIdle (Android 12+)")
                    } else {
                        alarmManager.setAlarmClock(
                            AlarmManager.AlarmClockInfo(triggerTime, pendingIntent),
                            pendingIntent
                        )
                        Log.d("AlarmScheduler", " Alarma de vencimientos programada con setAlarmClock (sin permiso exact)")
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.d("AlarmScheduler", " Alarma de vencimientos programada con setExactAndAllowWhileIdle (Android 6-11)")
                }
            } else {
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    AlarmManager.INTERVAL_DAY,
                    pendingIntent
                )
                Log.d("AlarmScheduler", " Alarma de vencimientos programada con setRepeating (Android < 6.0)")
            }
            
            Log.d("AlarmScheduler", " Recordatorio de vencimientos programado para las 7:00 AM")
            
        } catch (e: Exception) {
            Log.e("AlarmScheduler", " Error programando recordatorio de vencimientos", e)
            e.printStackTrace()
        }
    }
    
    fun cancelVencimientosReminder() {
        try {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_VENCIMIENTOS_REMINDER
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_VENCIMIENTOS,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            alarmManager.cancel(pendingIntent)
            Log.d("AlarmScheduler", " Recordatorio de vencimientos cancelado")
            
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "Error cancelando recordatorio de vencimientos", e)
        }
    }
}
