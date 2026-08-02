package com.tuapp.inventario.model

import com.tuapp.inventario.utils.DateHelper
import java.util.Calendar
import java.util.UUID

/**
 * Representa un lote de productos con su fecha de vencimiento
 */
data class VencimientoLote(
    val id: String = UUID.randomUUID().toString(),
    val localId: String = "",
    val productoCodigo: String = "",
    val productoNombre: String = "",
    val cantidad: Int = 0,
    val cantidadInicial: Int = 0,  // Para saber cuonto haboa originalmente
    val fechaVencimiento: String = "",  // Formato: yyyy-MM-dd
    val fechaCarga: String = "",  // Formato: yyyy-MM-dd
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Convierte el lote a un Map para guardar en Firebase
     */
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "localId" to localId,
        "productoCodigo" to productoCodigo,
        "productoNombre" to productoNombre,
        "cantidad" to cantidad,
        "cantidadInicial" to cantidadInicial,
        "fechaVencimiento" to fechaVencimiento,
        "fechaCarga" to fechaCarga,
        "timestamp" to timestamp
    )
    
    companion object {
        /**
         * Crea un VencimientoLote desde un Map de Firebase
         */
        fun fromMap(map: Map<String, Any>): VencimientoLote {
            return VencimientoLote(
                id = map["id"] as? String ?: "",
                localId = map["localId"] as? String ?: "",
                productoCodigo = map["productoCodigo"] as? String ?: "",
                productoNombre = map["productoNombre"] as? String ?: "",
                cantidad = (map["cantidad"] as? Number)?.toInt() ?: 0,
                cantidadInicial = (map["cantidadInicial"] as? Number)?.toInt() ?: 0,
                fechaVencimiento = map["fechaVencimiento"] as? String ?: "",
                fechaCarga = map["fechaCarga"] as? String ?: "",
                timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Calcula la urgencia del vencimiento
     * @return 0 = vence hoy, 1 = vence maoana, 2 = vence en 2 doas, etc.
     */
    fun diasHastaVencimiento(): Int {
        return try {
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val fechaVto = formato.parse(fechaVencimiento) ?: return 999
            val hoy = DateHelper.getCalendar().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time
            
            val diff = fechaVto.time - hoy.time
            (diff / (1000 * 60 * 60 * 24)).toInt()
        } catch (e: Exception) {
            999 // Error al parsear, devolver valor alto
        }
    }
    
    /**
     * Retorna el color segon la urgencia del vencimiento
     */
    fun getColorUrgencia(): String {
        return when (diasHastaVencimiento()) {
            0 -> "ROJO"     // Vence hoy
            1 -> "AMARILLO" // Vence maoana
            else -> "VERDE" // Vence en 2+ doas
        }
    }
}

/**
 * Representa un descuento realizado en un lote (para historico)
 */
data class DescuentoVencimiento(
    val id: String = UUID.randomUUID().toString(),
    val localId: String = "",
    val productoCodigo: String = "",
    val productoNombre: String = "",
    val loteId: String = "",
    val cantidadDescontada: Int = 0,
    val fechaVencimiento: String = "",
    val fechaDescuento: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "localId" to localId,
        "productoCodigo" to productoCodigo,
        "productoNombre" to productoNombre,
        "loteId" to loteId,
        "cantidadDescontada" to cantidadDescontada,
        "fechaVencimiento" to fechaVencimiento,
        "fechaDescuento" to fechaDescuento,
        "timestamp" to timestamp
    )
}

/**
 * Representa el registro historico diario de vencimientos
 * Guarda el estado inicial (inicio del doa) y final (despuos del stock final) de vencimientos
 */
data class RegistroDiarioVencimientos(
    val id: String = UUID.randomUUID().toString(),
    val localId: String = "",
    val fecha: String = "",  // Formato: yyyy-MM-dd
    val lotesIniciales: List<VencimientoLote> = emptyList(),  // Vencimientos al inicio del doa
    val lotesFinales: List<VencimientoLote> = emptyList(),    // Vencimientos despuos del stock final
    val timestampInicial: Long = System.currentTimeMillis(),
    val timestampFinal: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "localId" to localId,
        "fecha" to fecha,
        "lotesIniciales" to lotesIniciales.map { it.toMap() },
        "lotesFinales" to lotesFinales.map { it.toMap() },
        "timestampInicial" to timestampInicial,
        "timestampFinal" to timestampFinal
    )
    
    companion object {
        fun fromMap(map: Map<String, Any>): RegistroDiarioVencimientos {
            @Suppress("UNCHECKED_CAST")
            val lotesInicialesList = (map["lotesIniciales"] as? List<*>)?.mapNotNull { 
                if (it is Map<*, *>) {
                    VencimientoLote.fromMap(it as Map<String, Any>)
                } else null
            } ?: emptyList()
            
            @Suppress("UNCHECKED_CAST")
            val lotesFinalesList = (map["lotesFinales"] as? List<*>)?.mapNotNull { 
                if (it is Map<*, *>) {
                    VencimientoLote.fromMap(it as Map<String, Any>)
                } else null
            } ?: emptyList()
            
            return RegistroDiarioVencimientos(
                id = map["id"] as? String ?: "",
                localId = map["localId"] as? String ?: "",
                fecha = map["fecha"] as? String ?: "",
                lotesIniciales = lotesInicialesList,
                lotesFinales = lotesFinalesList,
                timestampInicial = (map["timestampInicial"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                timestampFinal = (map["timestampFinal"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}

