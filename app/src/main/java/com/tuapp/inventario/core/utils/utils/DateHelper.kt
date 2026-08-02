package com.tuapp.inventario.utils

import java.text.SimpleDateFormat
import java.util.*

/**
 * Helper para manejar fechas con zona horaria de Argentina
 */
object DateHelper {
    
    // Zona horaria de Argentina
    private val ARGENTINA_TIMEZONE = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
    
    /**
     * Obtiene un SimpleDateFormat con zona horaria de Argentina
     */
    fun getDateFormat(pattern: String, locale: Locale = Locale.getDefault()): SimpleDateFormat {
        return SimpleDateFormat(pattern, locale).apply {
            timeZone = ARGENTINA_TIMEZONE
        }
    }
    
    /**
     * Obtiene un Calendar con zona horaria de Argentina
     */
    fun getCalendar(): Calendar {
        return Calendar.getInstance(ARGENTINA_TIMEZONE)
    }
    
    /**
     * Obtiene la fecha actual en formato yyyy-MM-dd con zona horaria de Argentina
     */
    fun getFechaActual(): String {
        return getDateFormat("yyyy-MM-dd").format(Date())
    }
    
    /**
     * Obtiene la fecha y hora actual con zona horaria de Argentina
     */
    fun getFechaHoraActual(): Date {
        return getCalendar().time
    }
    
    /**
     * Obtiene un timestamp en milisegundos (siempre UTC, pero para colculos)
     */
    fun getCurrentTimeMillis(): Long {
        return System.currentTimeMillis()
    }
    
    /**
     * Obtiene la fecha anterior a la fecha dada (en zona horaria de Argentina)
     */
    fun obtenerFechaAnterior(fecha: String): String {
        return try {
            val sdf = getDateFormat("yyyy-MM-dd")
            val calendar = getCalendar()
            calendar.time = sdf.parse(fecha) ?: Date()
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            sdf.format(calendar.time)
        } catch (e: Exception) {
            fecha
        }
    }
    
    /**
     * Obtiene la fecha siguiente a la fecha dada (en zona horaria de Argentina)
     */
    fun obtenerFechaSiguiente(fecha: String): String {
        return try {
            val sdf = getDateFormat("yyyy-MM-dd")
            val calendar = getCalendar()
            calendar.time = sdf.parse(fecha) ?: Date()
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            sdf.format(calendar.time)
        } catch (e: Exception) {
            fecha
        }
    }
    
    /**
     * Formatea una fecha de yyyy-MM-dd a dd/MM/yy con zona horaria de Argentina
     */
    fun formatearFecha(fecha: String, formatoSalida: String = "dd/MM/yy"): String {
        return try {
            val sdfEntrada = getDateFormat("yyyy-MM-dd")
            val sdfSalida = getDateFormat(formatoSalida)
            val date = sdfEntrada.parse(fecha) ?: Date()
            sdfSalida.format(date)
        } catch (e: Exception) {
            fecha
        }
    }
    
    /**
     * Parsea una fecha desde string con zona horaria de Argentina
     */
    fun parsearFecha(fecha: String, formato: String = "yyyy-MM-dd"): Date? {
        return try {
            getDateFormat(formato).parse(fecha)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Verifica si una fecha está vencida (antes de hoy)
     */
    fun isDateExpired(fecha: Date): Boolean {
        val hoy = getCalendar()
        val fechaCalendar = getCalendar()
        fechaCalendar.time = fecha
        
        // Resetear hora, minutos, segundos para comparar solo fechas
        hoy.set(Calendar.HOUR_OF_DAY, 0)
        hoy.set(Calendar.MINUTE, 0)
        hoy.set(Calendar.SECOND, 0)
        hoy.set(Calendar.MILLISECOND, 0)
        fechaCalendar.set(Calendar.HOUR_OF_DAY, 0)
        fechaCalendar.set(Calendar.MINUTE, 0)
        fechaCalendar.set(Calendar.SECOND, 0)
        fechaCalendar.set(Calendar.MILLISECOND, 0)
        
        return fechaCalendar.before(hoy)
    }
    
    /**
     * Obtiene los días hasta una fecha (positivo si está en el futuro, negativo si está en el pasado)
     */
    fun getDaysUntilDate(fecha: Date): Int {
        val hoy = getCalendar()
        val fechaCalendar = getCalendar()
        fechaCalendar.time = fecha
        
        // Resetear hora, minutos, segundos para comparar solo fechas
        hoy.set(Calendar.HOUR_OF_DAY, 0)
        hoy.set(Calendar.MINUTE, 0)
        hoy.set(Calendar.SECOND, 0)
        hoy.set(Calendar.MILLISECOND, 0)
        fechaCalendar.set(Calendar.HOUR_OF_DAY, 0)
        fechaCalendar.set(Calendar.MINUTE, 0)
        fechaCalendar.set(Calendar.SECOND, 0)
        fechaCalendar.set(Calendar.MILLISECOND, 0)
        
        val hoyTime = hoy.timeInMillis
        val fechaTime = fechaCalendar.timeInMillis
        val diffInMillis = fechaTime - hoyTime
        val dias = (diffInMillis / (1000 * 60 * 60 * 24)).toInt()
        
        // Logs de depuración
        println("=== DEBUG DateHelper.getDaysUntilDate ===")
        println("Fecha objetivo: $fecha")
        println("Fecha objetivo timestamp: $fechaTime")
        println("Hoy timestamp: $hoyTime")
        println("Diferencia en ms: $diffInMillis")
        println("Días calculados: $dias")
        println("=====================================")
        
        return dias
    }
}
