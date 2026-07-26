package com.tuapp.inventario.repository

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.tuapp.inventario.model.VencimientoLote
import com.tuapp.inventario.model.DescuentoVencimiento
import com.tuapp.inventario.model.RegistroDiarioVencimientos
import com.tuapp.inventario.model.AlertaReemplazoStock
import kotlinx.coroutines.tasks.await
import com.tuapp.inventario.utils.DateHelper
import com.tuapp.inventario.utils.FirebaseRegionManager
import java.util.Date
import java.util.Calendar
import java.util.Locale

/**
 * Repositorio para manejar vencimientos en Firebase
 */
class VencimientoRepository {
    
    private val firestore: FirebaseFirestore
        get() = FirebaseRegionManager.getFirestore()
    private val TAG = "VencimientoRepository"
    
    //  Cache para lotes activos (reduce lecturas significativamente)
    private var cacheLotesActivos: Map<String, List<VencimientoLote>> = emptyMap() // localId -> lotes
    private var cacheLotesActivosTimestamp: Map<String, Long> = emptyMap() // localId -> timestamp
    private val CACHE_LOTES_DURATION_MS = 5 * 60 * 1000L // 5 minutos
    
    /**
     * Limpiar cache de lotes para un local (llamar cuando se actualiza/agrega/elimina lote)
     */
    fun clearLotesCache(localId: String) {
        cacheLotesActivos = cacheLotesActivos - localId
        cacheLotesActivosTimestamp = cacheLotesActivosTimestamp - localId
        Log.d(TAG, " Cache de lotes limpiado para local: $localId")
    }
    
    /**
     * Limpiar todo el cache de lotes
     */
    fun clearAllLotesCache() {
        cacheLotesActivos = emptyMap()
        cacheLotesActivosTimestamp = emptyMap()
        Log.d(TAG, " Cache de lotes limpiado completamente")
    }
    
    /**
     * Obtiene todos los lotes activos de un local
     *  OPTIMIZACIoN: Usa cache para reducir lecturas de Firebase
     */
    suspend fun obtenerLotesActivos(localId: String): List<VencimientoLote> {
        return try {
            //  Verificar cache primero
            val cacheTimestamp = cacheLotesActivosTimestamp[localId] ?: 0
            val isCacheValid = (System.currentTimeMillis() - cacheTimestamp) < CACHE_LOTES_DURATION_MS
            
            if (isCacheValid && cacheLotesActivos.containsKey(localId)) {
                Log.d(TAG, " Lotes activos - Usando cache para local: $localId (ahorra lecturas de Firebase)")
                return cacheLotesActivos[localId] ?: emptyList()
            }
            
            Log.d(TAG, " Lotes activos - Cache expirado o no existe, consultando Firebase para local: $localId")
            val snapshot = firestore.collection("locales")
                .document(localId)
                .collection("vencimientos_activos")
                .get()
                .await()
            
            val lotes = snapshot.documents.mapNotNull { doc ->
                try {
                    VencimientoLote.fromMap(doc.data ?: emptyMap())
                } catch (e: Exception) {
                    Log.e(TAG, "Error parseando lote: ${e.message}")
                    null
                }
            }
            
            //  Guardar en cache
            cacheLotesActivos = cacheLotesActivos + (localId to lotes)
            cacheLotesActivosTimestamp = cacheLotesActivosTimestamp + (localId to System.currentTimeMillis())
            
            Log.d(TAG, " Obtenidos ${lotes.size} lotes activos para local: $localId (cache actualizado)")
            lotes
        } catch (e: Exception) {
            Log.e(TAG, " Error obteniendo lotes activos: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Obtiene lotes activos de un producto especofico ordenados por fecha de vencimiento (FIFO)
     *  OPTIMIZACIoN: Query directa con filtro en Firebase (mos eficiente que filtrar en memoria)
     */
    suspend fun obtenerLotesActivosProducto(localId: String, productoCodigo: String): List<VencimientoLote> {
        return try {
            //  OPTIMIZACIoN: Si ya tenemos los lotes en cache, filtrar en memoria (mos ropido)
            val cacheTimestamp = cacheLotesActivosTimestamp[localId] ?: 0
            val isCacheValid = (System.currentTimeMillis() - cacheTimestamp) < CACHE_LOTES_DURATION_MS
            
            if (isCacheValid && cacheLotesActivos.containsKey(localId)) {
                Log.d(TAG, " Lotes producto - Usando cache y filtrando en memoria para: $productoCodigo")
                val todosLosLotes = cacheLotesActivos[localId] ?: emptyList()
                return todosLosLotes
                    .filter { it.productoCodigo == productoCodigo && it.cantidad > 0 }
                    .sortedBy { it.fechaVencimiento }  // FIFO: mos viejo primero
            }
            
            //  OPTIMIZACIoN: Query directa con filtro en Firebase (solo si no hay cache)
            Log.d(TAG, " Lotes producto - Query directa en Firebase para: $productoCodigo")
            val snapshot = firestore.collection("locales")
                .document(localId)
                .collection("vencimientos_activos")
                .whereEqualTo("productoCodigo", productoCodigo)
                .get()
                .await()
            
            val lotes = snapshot.documents.mapNotNull { doc ->
                try {
                    val lote = VencimientoLote.fromMap(doc.data ?: emptyMap())
                    if (lote.cantidad > 0) lote else null
                } catch (e: Exception) {
                    Log.e(TAG, "Error parseando lote: ${e.message}")
                    null
                }
            }.sortedBy { it.fechaVencimiento }  // FIFO: mos viejo primero
            
            Log.d(TAG, " Obtenidos ${lotes.size} lotes activos para producto: $productoCodigo")
            lotes
        } catch (e: Exception) {
            Log.e(TAG, " Error obteniendo lotes del producto $productoCodigo: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Guarda un nuevo lote de vencimiento
     */
    suspend fun agregarLote(lote: VencimientoLote): Boolean {
        return try {
            firestore.collection("locales")
                .document(lote.localId)
                .collection("vencimientos_activos")
                .document(lote.id)
                .set(lote.toMap())
                .await()
            
            //  Limpiar cache cuando se agrega un lote
            clearLotesCache(lote.localId)
            
            Log.d(TAG, " Lote agregado: ${lote.productoCodigo} - ${lote.cantidad} uds")
            true
        } catch (e: Exception) {
            Log.e(TAG, " Error agregando lote: ${e.message}")
            false
        }
    }
    
    /**
     * Actualiza la cantidad de un lote existente
     */
    suspend fun actualizarCantidadLote(localId: String, loteId: String, nuevaCantidad: Int): Boolean {
        return try {
            firestore.collection("locales")
                .document(localId)
                .collection("vencimientos_activos")
                .document(loteId)
                .update("cantidad", nuevaCantidad)
                .await()
            
            //  Limpiar cache cuando se actualiza un lote
            clearLotesCache(localId)
            
            Log.d(TAG, " Lote $loteId actualizado a $nuevaCantidad uds")
            true
        } catch (e: Exception) {
            Log.e(TAG, " Error actualizando lote: ${e.message}")
            false
        }
    }
    
    /**
     * Elimina un lote (cuando cantidad llega a 0 o si el usuario lo elimina)
     */
    suspend fun eliminarLote(localId: String, loteId: String): Boolean {
        return try {
            firestore.collection("locales")
                .document(localId)
                .collection("vencimientos_activos")
                .document(loteId)
                .delete()
                .await()
            
            //  Limpiar cache cuando se elimina un lote
            clearLotesCache(localId)
            
            Log.d(TAG, " Lote eliminado: $loteId")
            true
        } catch (e: Exception) {
            Log.e(TAG, " Error eliminando lote: ${e.message}")
            false
        }
    }
    
    /**
     * Guarda el registro de un descuento en el historico
     */
    suspend fun registrarDescuento(descuento: DescuentoVencimiento): Boolean {
        return try {
            firestore.collection("locales")
                .document(descuento.localId)
                .collection("vencimientos_historial")
                .document(descuento.fechaDescuento)
                .collection("descuentos")
                .document(descuento.id)
                .set(descuento.toMap())
                .await()
            
            Log.d(TAG, " Descuento registrado en historico")
            true
        } catch (e: Exception) {
            Log.e(TAG, " Error registrando descuento: ${e.message}")
            false
        }
    }
    
    /**
     * Obtiene el resumen de vencimientos por urgencia
     * OPTIMIZADO: Procesa los lotes de forma eficiente
     */
    suspend fun obtenerResumenVencimientos(localId: String): Map<String, Int> {
        return try {
            // Obtener todos los lotes (necesario para calcular doas hasta vencimiento)
            val lotes = obtenerLotesActivos(localId)
            
            // Contar solo los que necesitamos (mos eficiente que procesar todos)
            var hoy = 0
            var manana = 0
            var proximamente = 0
            
            lotes.forEach { lote ->
                val dias = lote.diasHastaVencimiento()
                when {
                    dias == 0 -> hoy++
                    dias == 1 -> manana++
                    dias in 2..3 -> proximamente++
                }
            }
            
            mapOf(
                "hoy" to hoy,
                "manana" to manana,
                "proximamente" to proximamente
            )
        } catch (e: Exception) {
            Log.e(TAG, " Error obteniendo resumen: ${e.message}")
            mapOf("hoy" to 0, "manana" to 0, "proximamente" to 0)
        }
    }
    
    /**
     * Obtiene la fecha siguiente en formato yyyy-MM-dd
     */
    private fun obtenerFechaSiguiente(fecha: String): String {
        return try {
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val calendar = DateHelper.getCalendar()
            calendar.time = formato.parse(fecha) ?: Date()
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            formato.format(calendar.time)
        } catch (e: Exception) {
            fecha
        }
    }
    
    /**
     * Obtiene la fecha actual en formato yyyy-MM-dd
     */
    fun obtenerFechaActual(): String {
        return DateHelper.getFechaActual()
    }
    
    /**
     * Guarda el estado inicial de vencimientos al inicio del doa
     */
    suspend fun guardarEstadoInicialVencimientos(
        localId: String,
        fecha: String,
        lotes: List<VencimientoLote>
    ): Boolean {
        return try {
            // Verificar si ya existe un registro para esta fecha
            val registroExistente = obtenerRegistroDiarioVencimientos(localId, fecha)
            
            val registro = if (registroExistente != null) {
                // Actualizar solo los lotes iniciales
                registroExistente.copy(
                    lotesIniciales = lotes,
                    timestampInicial = System.currentTimeMillis()
                )
            } else {
                // Crear nuevo registro
                RegistroDiarioVencimientos(
                    localId = localId,
                    fecha = fecha,
                    lotesIniciales = lotes,
                    lotesFinales = emptyList(),
                    timestampInicial = System.currentTimeMillis(),
                    timestampFinal = System.currentTimeMillis()
                )
            }
            
            firestore.collection("locales")
                .document(localId)
                .collection("vencimientos_historial_diario")
                .document(fecha)
                .set(registro.toMap())
                .await()
            
            Log.d(TAG, " Estado inicial de vencimientos guardado para $fecha: ${lotes.size} lotes")
            true
        } catch (e: Exception) {
            Log.e(TAG, " Error guardando estado inicial: ${e.message}")
            false
        }
    }
    
    /**
     * Guarda el estado final de vencimientos despuos del stock final
     */
    suspend fun guardarEstadoFinalVencimientos(
        localId: String,
        fecha: String,
        lotes: List<VencimientoLote>
    ): Boolean {
        return try {
            // Obtener registro existente o crear uno nuevo
            val registroExistente = obtenerRegistroDiarioVencimientos(localId, fecha)
            
            val registro = if (registroExistente != null) {
                // Actualizar solo los lotes finales
                registroExistente.copy(
                    lotesFinales = lotes,
                    timestampFinal = System.currentTimeMillis()
                )
            } else {
                // Crear nuevo registro (si no haboa inicial, usar vacoo)
                RegistroDiarioVencimientos(
                    localId = localId,
                    fecha = fecha,
                    lotesIniciales = emptyList(),
                    lotesFinales = lotes,
                    timestampInicial = System.currentTimeMillis(),
                    timestampFinal = System.currentTimeMillis()
                )
            }
            
            firestore.collection("locales")
                .document(localId)
                .collection("vencimientos_historial_diario")
                .document(fecha)
                .set(registro.toMap())
                .await()
            
            Log.d(TAG, " Estado final de vencimientos guardado para $fecha: ${lotes.size} lotes")
            true
        } catch (e: Exception) {
            Log.e(TAG, " Error guardando estado final: ${e.message}")
            false
        }
    }
    
    /**
     * Obtiene el registro diario de vencimientos para una fecha especofica
     */
    suspend fun obtenerRegistroDiarioVencimientos(
        localId: String,
        fecha: String
    ): RegistroDiarioVencimientos? {
        return try {
            val doc = firestore.collection("locales")
                .document(localId)
                .collection("vencimientos_historial_diario")
                .document(fecha)
                .get()
                .await()
            
            if (doc.exists()) {
                val registro = RegistroDiarioVencimientos.fromMap(doc.data ?: emptyMap())
                Log.d(TAG, " Registro diario obtenido para $fecha")
                registro
            } else {
                Log.d(TAG, " No existe registro diario para $fecha")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, " Error obteniendo registro diario: ${e.message}")
            null
        }
    }
    
    /**
     * Obtiene todos los registros diarios de vencimientos de un local (ordenados por fecha descendente)
     */
    suspend fun obtenerTodosRegistrosDiariosVencimientos(
        localId: String,
        limite: Int = 30
    ): List<RegistroDiarioVencimientos> {
        return try {
            Log.d(TAG, " Obteniendo registros diarios para local: $localId, lomite: $limite")
            
            // Intentar con orderBy primero
            val snapshot = try {
                firestore.collection("locales")
                    .document(localId)
                    .collection("vencimientos_historial_diario")
                    .orderBy("fecha", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(limite.toLong())
                    .get()
                    .await()
            } catch (e: Exception) {
                // Si falla orderBy (por falta de ondice), intentar sin ordenar
                Log.w(TAG, " Error con orderBy, intentando sin ordenar: ${e.message}")
                firestore.collection("locales")
                    .document(localId)
                    .collection("vencimientos_historial_diario")
                    .limit(limite.toLong())
                    .get()
                    .await()
            }
            
            Log.d(TAG, " Documentos encontrados: ${snapshot.documents.size}")
            
            val registros = snapshot.documents.mapNotNull { doc ->
                try {
                    Log.d(TAG, " Procesando documento: ${doc.id}, datos: ${doc.data?.keys}")
                    val data = doc.data ?: emptyMap()
                    
                    // Si el documento no tiene campo 'fecha', usar el ID del documento como fecha
                    val registro = if (data.containsKey("fecha")) {
                        RegistroDiarioVencimientos.fromMap(data)
                    } else {
                        // Intentar usar el ID del documento como fecha (formato yyyy-MM-dd)
                        val fechaDesdeId = doc.id
                        Log.d(TAG, " Documento ${doc.id} no tiene campo 'fecha', usando ID como fecha: $fechaDesdeId")
                        val dataConFecha = data.toMutableMap()
                        dataConFecha["fecha"] = fechaDesdeId
                        if (!dataConFecha.containsKey("localId")) {
                            dataConFecha["localId"] = localId
                        }
                        RegistroDiarioVencimientos.fromMap(dataConFecha)
                    }
                    
                    Log.d(TAG, " Registro parseado: fecha=${registro.fecha}, lotesIniciales=${registro.lotesIniciales.size}, lotesFinales=${registro.lotesFinales.size}")
                    registro
                } catch (e: Exception) {
                    Log.e(TAG, " Error parseando registro diario ${doc.id}: ${e.message}", e)
                    null
                }
            }
            
            // Ordenar manualmente por fecha si no se pudo ordenar en la consulta
            val registrosOrdenados = registros.sortedByDescending { it.fecha }
            
            Log.d(TAG, " Obtenidos ${registrosOrdenados.size} registros diarios para local: $localId")
            registrosOrdenados
        } catch (e: Exception) {
            Log.e(TAG, " Error obteniendo registros diarios: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Detecta productos cuyo stock total vence mañana y no tienen pedido pendiente
     * @param localId ID del local
     * @param stockActualPorProducto Mapa de stock actual por producto (codigo -> cantidad)
     * @param pedidoPendientePorProducto Mapa de pedido pendiente por producto (codigo -> cantidad)
     * @param sugerenciaBasePorProducto Mapa de sugerencia base por producto (codigo -> cantidad) - suele ser el pedidoSugerido del sistema
     * @return Lista de alertas de reemplazo de stock
     */
    suspend fun detectarProductosVencenTotalManana(
        localId: String,
        stockActualPorProducto: Map<String, Int>,
        pedidoPendientePorProducto: Map<String, Int>,
        sugerenciaBasePorProducto: Map<String, Int> = emptyMap()
    ): List<AlertaReemplazoStock> {
        return try {
            Log.d(TAG, " Detectando productos que vencen totalmente mañana para local: $localId")
            
            // Obtener fecha de mañana
            val calendar = DateHelper.getCalendar()
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val fechaManana = formato.format(calendar.time)
            
            Log.d(TAG, " Fecha mañana: $fechaManana")
            
            // Obtener todos los lotes activos
            val lotesActivos = obtenerLotesActivos(localId)
            
            // Agrupar lotes por producto y calcular stock que vence HOY y MAÑANA
            val stockVenceHoyPorProducto = mutableMapOf<String, Int>()
            val stockVenceMananaPorProducto = mutableMapOf<String, Int>()
            val productosConVencimientoProximo = mutableSetOf<String>()
            
            lotesActivos.forEach { lote ->
                val diasHastaVencimiento = lote.diasHastaVencimiento()
                if (diasHastaVencimiento == 0) { // Vence hoy
                    val stockActual = stockVenceHoyPorProducto[lote.productoCodigo] ?: 0
                    stockVenceHoyPorProducto[lote.productoCodigo] = stockActual + lote.cantidad
                    productosConVencimientoProximo.add(lote.productoCodigo)
                    
                    Log.d(TAG, " Lote vence hoy: ${lote.productoNombre} (${lote.productoCodigo}) - Cantidad: ${lote.cantidad} - Fecha: ${lote.fechaVencimiento}")
                } else if (diasHastaVencimiento == 1) { // Vence mañana
                    val stockActual = stockVenceMananaPorProducto[lote.productoCodigo] ?: 0
                    stockVenceMananaPorProducto[lote.productoCodigo] = stockActual + lote.cantidad
                    productosConVencimientoProximo.add(lote.productoCodigo)
                    
                    Log.d(TAG, " Lote vence mañana: ${lote.productoNombre} (${lote.productoCodigo}) - Cantidad: ${lote.cantidad} - Fecha: ${lote.fechaVencimiento}")
                }
            }
            
            // Detectar productos donde TODO el stock vence HOY o MAÑANA
            val alertas = mutableListOf<AlertaReemplazoStock>()
            
            productosConVencimientoProximo.forEach { productoCodigo ->
                val stockTotal = stockActualPorProducto[productoCodigo] ?: 0
                val stockVenceHoy = stockVenceHoyPorProducto[productoCodigo] ?: 0
                val stockVenceManana = stockVenceMananaPorProducto[productoCodigo] ?: 0
                val stockVenceProximo = stockVenceHoy + stockVenceManana
                val pedidoPendiente = pedidoPendientePorProducto[productoCodigo] ?: 0
                
                // Solo alertar si TODO el stock se vence HOY o MAÑANA
                if (stockVenceProximo >= stockTotal && stockTotal > 0) {
                    // Y el pedido normal del sistema no sugiere nada (sugerencia base == 0)
                    val sugerenciaBase = sugerenciaBasePorProducto[productoCodigo] ?: 0
                    if (sugerenciaBase == 0) {
                        // Obtener nombre del producto
                        val productoNombre = lotesActivos
                            .find { it.productoCodigo == productoCodigo }
                            ?.productoNombre ?: productoCodigo
                        
                        // La sugerencia final se calcula en la Activity usando promedios de venta y mínimos por categoría
                        val alerta = AlertaReemplazoStock(
                            productoCodigo = productoCodigo,
                            productoNombre = productoNombre,
                            stockTotal = stockTotal,
                            stockVenceManana = stockVenceProximo,
                            fechaVencimiento = fechaManana,
                            pedidoPendiente = pedidoPendiente,
                            sugerenciaPedido = 0, // Se recalcula en la Activity
                            prioridad = "ALTA"
                        )
                        
                        alertas.add(alerta)
                        Log.d(TAG, " ALERTA: $productoNombre - Stock total: $stockTotal se vence hoy ($stockVenceHoy) o mañana ($stockVenceManana), sugerenciaBase=0")
                    } else {
                        Log.d(TAG, " INFO: $productoCodigo - Stock se vence pronto pero el pedido normal ya sugiere: $sugerenciaBase, no alertar")
                    }
                } else {
                    Log.d(TAG, " INFO: $productoCodigo - Stock a vencer hoy+mañana ($stockVenceProximo) < stock total ($stockTotal), no alertar")
                }
            }
            
            Log.d(TAG, " Detectadas ${alertas.size} alertas de reemplazo de stock")
            alertas
        } catch (e: Exception) {
            Log.e(TAG, " Error detectando productos que vencen totalmente mañana: ${e.message}", e)
            emptyList()
        }
    }
}

