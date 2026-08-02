package com.tuapp.inventario.manager

import android.util.Log
import com.tuapp.inventario.model.VencimientoLote
import com.tuapp.inventario.model.DescuentoVencimiento
import com.tuapp.inventario.repository.VencimientoRepository
import com.tuapp.inventario.utils.DateHelper
import java.util.Date
import java.util.Locale

/**
 * Maneja la logica de descuento automatico FIFO de vencimientos
 */
class VencimientoManager(private val repository: VencimientoRepository) {
    
    private val TAG = "VencimientoManager"
    
    /**
     * Procesa el descuento FIFO de vencimientos basado en la venta del dia
     * Se ejecuta automaticamente al guardar el Stock Final
     *  OPTIMIZACION: Carga todos los lotes una vez, luego procesa en memoria
     */
    suspend fun procesarDescuentosFIFO(
        localId: String,
        fecha: String,
        ventasPorProducto: Map<String, Int>  // Mapa de: codigo producto -> cantidad vendida
    ) {
        Log.d(TAG, "")
        Log.d(TAG, " INICIANDO DESCUENTO FIFO")
        Log.d(TAG, " Fecha: $fecha")
        Log.d(TAG, " Local: $localId")
        Log.d(TAG, "")
        
        //  OPTIMIZACION: Cargar todos los lotes una vez (usa cache si esta disponible)
        val todosLosLotes = repository.obtenerLotesActivos(localId)
        Log.d(TAG, " Lotes cargados una vez: ${todosLosLotes.size} lotes totales")
        
        // Agrupar lotes por producto para acceso rapido
        val lotesPorProducto = todosLosLotes
            .filter { it.cantidad > 0 }
            .groupBy { it.productoCodigo }
            .mapValues { (_, lotes) -> lotes.sortedBy { it.fechaVencimiento } }  // FIFO: mas viejo primero
        
        ventasPorProducto.forEach { (productoCodigo, cantidadVendida) ->
            if (cantidadVendida <= 0) {
                Log.d(TAG, " Saltando $productoCodigo (sin venta)")
                return@forEach
            }
            
            Log.d(TAG, "\n Procesando: $productoCodigo")
            Log.d(TAG, "   Vendido: $cantidadVendida uds")
            
            //  Usar lotes ya cargados en memoria
            val lotesProducto = lotesPorProducto[productoCodigo] ?: emptyList()
            descontarVencimientosFIFOConLotes(localId, productoCodigo, cantidadVendida, fecha, lotesProducto)
        }
        
        Log.d(TAG, "")
        Log.d(TAG, " DESCUENTO FIFO COMPLETADO")
        Log.d(TAG, "")
    }
    
    /**
     * Descuenta la cantidad vendida de los lotes mas viejos (FIFO)
     *  OPTIMIZACION: Version que recibe lotes ya cargados (evita lecturas adicionales)
     */
    private suspend fun descontarVencimientosFIFOConLotes(
        localId: String,
        productoCodigo: String,
        cantidadVendida: Int,
        fecha: String,
        lotes: List<VencimientoLote>
    ) {
        if (lotes.isEmpty()) {
            Log.w(TAG, " No hay lotes activos para $productoCodigo")
            return
        }
        
        Log.d(TAG, "    Lotes disponibles: ${lotes.size}")
        lotes.forEachIndexed { index, lote ->
            Log.d(TAG, "      ${index + 1}. ${lote.cantidad} uds  Vto ${lote.fechaVencimiento}")
        }
        
        var pendienteDescontar = cantidadVendida
        
        // 2. Descontar de cada lote (empezando por el mas viejo)
        lotes.forEach { lote ->
            if (pendienteDescontar <= 0) return@forEach
            
            val descontar = minOf(lote.cantidad, pendienteDescontar)
            val cantidadRestante = lote.cantidad - descontar
            
            Log.d(TAG, "    Descontando de lote:")
            Log.d(TAG, "      Vencimiento: ${lote.fechaVencimiento}")
            Log.d(TAG, "      Habia: ${lote.cantidad} uds")
            Log.d(TAG, "      Descontar: $descontar uds")
            Log.d(TAG, "      Quedan: $cantidadRestante uds")
            
            // Actualizar o eliminar el lote
            if (cantidadRestante <= 0) {
                // Lote agotado, eliminarlo
                repository.eliminarLote(localId, lote.id)
                Log.d(TAG, "       Lote eliminado (agotado)")
            } else {
                // Actualizar cantidad
                repository.actualizarCantidadLote(localId, lote.id, cantidadRestante)
                Log.d(TAG, "       Lote actualizado")
            }
            
            // Registrar el descuento en el historico
            val descuento = DescuentoVencimiento(
                localId = localId,
                productoCodigo = productoCodigo,
                productoNombre = lote.productoNombre,
                loteId = lote.id,
                cantidadDescontada = descontar,
                fechaVencimiento = lote.fechaVencimiento,
                fechaDescuento = fecha
            )
            repository.registrarDescuento(descuento)
            
            pendienteDescontar -= descontar
        }
        
        // 3. Verificar si quedo pendiente por descontar (error: se vendio mas de lo que habia registrado)
        if (pendienteDescontar > 0) {
            Log.w(TAG, " ADVERTENCIA: Quedan $pendienteDescontar uds sin descontar de vencimientos")
            Log.w(TAG, "   Posible causa: Venta registrada mayor a lotes disponibles")
        }
    }
    
    /**
     * Calcula la venta por producto basandose en los registros de inventario
     */
    suspend fun calcularVentasPorProducto(
        _localId: String,
        _fecha: String,
        _productos: List<String>
    ): Map<String, Int> {
        // Esta funcion se implementara usando los registros existentes
        // Por ahora retorna un mapa vacio
        return emptyMap()
    }
    
    /**
     * Sincroniza los lotes con el stock final real
     * Si el stock final es 0, elimina TODOS los lotes
     * Si el stock final > 0, ajusta los lotes para que sumen exactamente el stock final
     *  OPTIMIZACION: Carga todos los lotes una vez, luego procesa en memoria
     */
    suspend fun sincronizarLotesConStockFinal(
        localId: String,
        fecha: String,
        stockFinalPorProducto: Map<String, Int>  // Mapa de: codigo producto -> stock final
    ) {
        Log.d(TAG, "")
        Log.d(TAG, " SINCRONIZANDO LOTES CON STOCK FINAL")
        Log.d(TAG, " Fecha: $fecha")
        Log.d(TAG, " Local: $localId")
        Log.d(TAG, "")
        
        //  OPTIMIZACION: Cargar todos los lotes una vez (usa cache si esta disponible)
        val todosLosLotes = repository.obtenerLotesActivos(localId)
        Log.d(TAG, " Lotes cargados una vez: ${todosLosLotes.size} lotes totales")
        
        // Agrupar lotes por producto (normalizando código) para acceso rápido
        val lotesPorProducto = todosLosLotes
            .groupBy { normalizarCodigoProducto(it.productoCodigo) }
            .mapValues { (_, lotes) -> lotes.sortedBy { it.fechaVencimiento } }  // FIFO: más viejo primero

        stockFinalPorProducto.forEach { (rawCodigo, stockFinal) ->
            val productoCodigo = normalizarCodigoProducto(rawCodigo)
            val lotes = lotesPorProducto[productoCodigo] ?: emptyList()
            val sumaLotes = lotes.sumOf { it.cantidad }
            
            Log.d(TAG, "\n Sincronizando: $productoCodigo")
            Log.d(TAG, "   Stock Final: $stockFinal")
            Log.d(TAG, "   Suma Lotes: $sumaLotes")
            Log.d(TAG, "   Cantidad de lotes: ${lotes.size}")
            
            // Log detallado de cada lote
            if (lotes.isNotEmpty()) {
                Log.d(TAG, "    Lotes detallados:")
                lotes.forEachIndexed { index, lote ->
                    Log.d(TAG, "      ${index + 1}. ${lote.cantidad} uds  Vto ${lote.fechaVencimiento} (ID: ${lote.id})")
                }
            }
            
            // Si el stock final es 0, eliminar TODOS los lotes
            if (stockFinal == 0) {
                if (lotes.isNotEmpty()) {
                    Log.d(TAG, "    Stock Final = 0  Eliminando TODOS los lotes")
                    lotes.forEach { lote ->
                        repository.eliminarLote(localId, lote.id)
                        Log.d(TAG, "      Eliminado: ${lote.cantidad} uds  Vto ${lote.fechaVencimiento}")
                        
                        // Registrar descuento por descarte
                        val descuento = DescuentoVencimiento(
                            localId = localId,
                            productoCodigo = productoCodigo,
                            productoNombre = lote.productoNombre,
                            loteId = lote.id,
                            cantidadDescontada = lote.cantidad,
                            fechaVencimiento = lote.fechaVencimiento,
                            fechaDescuento = fecha
                        )
                        repository.registrarDescuento(descuento)
                    }
                } else {
                    Log.d(TAG, "    Ya no hay lotes (correcto)")
                }
            } 
            // Si el stock final > 0 pero hay mas lotes que stock, ajustar
            else if (sumaLotes > stockFinal) {
                val diferencia = sumaLotes - stockFinal
                Log.d(TAG, "    Hay mas lotes ($sumaLotes) que stock final ($stockFinal)")
                Log.d(TAG, "    Descontando diferencia: $diferencia uds")
                
                // Descontar la diferencia de los lotes mas viejos
                var pendienteDescontar = diferencia
                lotes.forEach { lote ->
                    if (pendienteDescontar <= 0) return@forEach
                    
                    val descontar = minOf(lote.cantidad, pendienteDescontar)
                    val cantidadRestante = lote.cantidad - descontar
                    
                    if (cantidadRestante <= 0) {
                        repository.eliminarLote(localId, lote.id)
                        Log.d(TAG, "       Lote eliminado: ${lote.cantidad} uds  Vto ${lote.fechaVencimiento}")
                    } else {
                        repository.actualizarCantidadLote(localId, lote.id, cantidadRestante)
                        Log.d(TAG, "       Lote ajustado: ${lote.cantidad}  $cantidadRestante uds")
                    }
                    
                    // Registrar descuento
                    val descuento = DescuentoVencimiento(
                        localId = localId,
                        productoCodigo = productoCodigo,
                        productoNombre = lote.productoNombre,
                        loteId = lote.id,
                        cantidadDescontada = descontar,
                        fechaVencimiento = lote.fechaVencimiento,
                        fechaDescuento = fecha
                    )
                    repository.registrarDescuento(descuento)
                    
                    pendienteDescontar -= descontar
                }
            }
            // Si el stock final > 0 y coincide con lotes, esta correcto
            else if (sumaLotes == stockFinal) {
                Log.d(TAG, "    Lotes coinciden con stock final (correcto)")
            }
            // Si hay menos lotes que stock final, es porque falta cargar lotes del ingreso de mercaderia
            else if (sumaLotes < stockFinal) {
                val diferencia = stockFinal - sumaLotes
                Log.w(TAG, "    ADVERTENCIA: Hay menos lotes ($sumaLotes) que stock final ($stockFinal)")
                Log.w(TAG, "    Diferencia: $diferencia uds")
                Log.w(TAG, "    ACCION REQUERIDA: Debes cargar los lotes del Ingreso de Mercaderia en VencimientosActivity")
                Log.w(TAG, "    Si ingresaste $diferencia uds nuevas, debes crear un lote con la fecha de vencimiento correcta")
                Log.w(TAG, "    NO se crean lotes automaticamente para evitar fechas incorrectas")
            }
        }
        
        Log.d(TAG, "")
        Log.d(TAG, " SINCRONIZACION COMPLETADA")
        Log.d(TAG, "")
    }
    
    /**
     * Valida que la suma de lotes coincida con el stock actual
     */
    suspend fun validarConsistenciaStock(
        localId: String,
        productoCodigo: String,
        stockActual: Int
    ): Boolean {
        //  OPTIMIZACION: Usar cache si esta disponible (mas rapido)
        val todosLosLotes = repository.obtenerLotesActivos(localId)
        val lotes = todosLosLotes
            .filter { it.productoCodigo == productoCodigo && it.cantidad > 0 }
        val sumaLotes = lotes.sumOf { it.cantidad }
        
        val coincide = sumaLotes == stockActual
        
        if (!coincide) {
            Log.w(TAG, " INCONSISTENCIA en $productoCodigo:")
            Log.w(TAG, "   Stock actual: $stockActual")
            Log.w(TAG, "   Suma lotes: $sumaLotes")
            Log.w(TAG, "   Diferencia: ${stockActual - sumaLotes}")
        }
        
        return coincide
    }

    private fun normalizarCodigoProducto(codigo: String): String {
        return when (codigo) {
            "PEPPERO" -> "PEPPER"
            "MUZZARE" -> "MUZZA"
            else -> codigo
        }
    }
}

