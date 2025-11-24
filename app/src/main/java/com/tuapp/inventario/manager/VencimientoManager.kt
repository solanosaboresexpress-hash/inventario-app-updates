package com.tuapp.inventario.manager

import android.util.Log
import com.tuapp.inventario.model.VencimientoLote
import com.tuapp.inventario.model.DescuentoVencimiento
import com.tuapp.inventario.repository.VencimientoRepository
import com.tuapp.inventario.util.DateHelper
import java.util.Date
import java.util.Locale

/**
 * Maneja la lógica de descuento automático FIFO de vencimientos
 */
class VencimientoManager(private val repository: VencimientoRepository) {
    
    private val TAG = "VencimientoManager"
    
    /**
     * Procesa el descuento FIFO de vencimientos basado en la venta del día
     * Se ejecuta automáticamente al guardar el Stock Final
     */
    suspend fun procesarDescuentosFIFO(
        localId: String,
        fecha: String,
        ventasPorProducto: Map<String, Int>  // Mapa de: código producto -> cantidad vendida
    ) {
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "🔥 INICIANDO DESCUENTO FIFO")
        Log.d(TAG, "📅 Fecha: $fecha")
        Log.d(TAG, "🏪 Local: $localId")
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        ventasPorProducto.forEach { (productoCodigo, cantidadVendida) ->
            if (cantidadVendida <= 0) {
                Log.d(TAG, "⏭️ Saltando $productoCodigo (sin venta)")
                return@forEach
            }
            
            Log.d(TAG, "\n🔍 Procesando: $productoCodigo")
            Log.d(TAG, "   Vendido: $cantidadVendida uds")
            
            descontarVencimientosFIFO(localId, productoCodigo, cantidadVendida, fecha)
        }
        
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "✅ DESCUENTO FIFO COMPLETADO")
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }
    
    /**
     * Descuenta la cantidad vendida de los lotes más viejos (FIFO)
     */
    private suspend fun descontarVencimientosFIFO(
        localId: String,
        productoCodigo: String,
        cantidadVendida: Int,
        fecha: String
    ) {
        // 1. Obtener lotes ordenados por fecha de vencimiento (más viejo primero)
        val lotes = repository.obtenerLotesActivosProducto(localId, productoCodigo)
        
        if (lotes.isEmpty()) {
            Log.w(TAG, "⚠️ No hay lotes activos para $productoCodigo")
            return
        }
        
        Log.d(TAG, "   📦 Lotes disponibles: ${lotes.size}")
        lotes.forEachIndexed { index, lote ->
            Log.d(TAG, "      ${index + 1}. ${lote.cantidad} uds → Vto ${lote.fechaVencimiento}")
        }
        
        var pendienteDescontar = cantidadVendida
        
        // 2. Descontar de cada lote (empezando por el más viejo)
        lotes.forEach { lote ->
            if (pendienteDescontar <= 0) return@forEach
            
            val descontar = minOf(lote.cantidad, pendienteDescontar)
            val cantidadRestante = lote.cantidad - descontar
            
            Log.d(TAG, "   🔄 Descontando de lote:")
            Log.d(TAG, "      Vencimiento: ${lote.fechaVencimiento}")
            Log.d(TAG, "      Había: ${lote.cantidad} uds")
            Log.d(TAG, "      Descontar: $descontar uds")
            Log.d(TAG, "      Quedan: $cantidadRestante uds")
            
            // Actualizar o eliminar el lote
            if (cantidadRestante <= 0) {
                // Lote agotado, eliminarlo
                repository.eliminarLote(localId, lote.id)
                Log.d(TAG, "      🗑️ Lote eliminado (agotado)")
            } else {
                // Actualizar cantidad
                repository.actualizarCantidadLote(localId, lote.id, cantidadRestante)
                Log.d(TAG, "      ✅ Lote actualizado")
            }
            
            // Registrar el descuento en el histórico
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
        
        // 3. Verificar si quedó pendiente por descontar (error: se vendió más de lo que había registrado)
        if (pendienteDescontar > 0) {
            Log.w(TAG, "⚠️ ADVERTENCIA: Quedan $pendienteDescontar uds sin descontar de vencimientos")
            Log.w(TAG, "   Posible causa: Venta registrada mayor a lotes disponibles")
        }
    }
    
    /**
     * Calcula la venta por producto basándose en los registros de inventario
     */
    suspend fun calcularVentasPorProducto(
        localId: String,
        fecha: String,
        productos: List<String>
    ): Map<String, Int> {
        // Esta función se implementará usando los registros existentes
        // Por ahora retorna un mapa vacío
        return emptyMap()
    }
    
    /**
     * Sincroniza los lotes con el stock final real
     * Si el stock final es 0, elimina TODOS los lotes
     * Si el stock final > 0, ajusta los lotes para que sumen exactamente el stock final
     */
    suspend fun sincronizarLotesConStockFinal(
        localId: String,
        fecha: String,
        stockFinalPorProducto: Map<String, Int>  // Mapa de: código producto -> stock final
    ) {
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "🔄 SINCRONIZANDO LOTES CON STOCK FINAL")
        Log.d(TAG, "📅 Fecha: $fecha")
        Log.d(TAG, "🏪 Local: $localId")
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        stockFinalPorProducto.forEach { (productoCodigo, stockFinal) ->
            val lotes = repository.obtenerLotesActivosProducto(localId, productoCodigo)
            val sumaLotes = lotes.sumOf { it.cantidad }
            
            Log.d(TAG, "\n🔍 Sincronizando: $productoCodigo")
            Log.d(TAG, "   Stock Final: $stockFinal")
            Log.d(TAG, "   Suma Lotes: $sumaLotes")
            
            // Si el stock final es 0, eliminar TODOS los lotes
            if (stockFinal == 0) {
                if (lotes.isNotEmpty()) {
                    Log.d(TAG, "   🗑️ Stock Final = 0 → Eliminando TODOS los lotes")
                    lotes.forEach { lote ->
                        repository.eliminarLote(localId, lote.id)
                        Log.d(TAG, "      Eliminado: ${lote.cantidad} uds → Vto ${lote.fechaVencimiento}")
                        
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
                    Log.d(TAG, "   ✅ Ya no hay lotes (correcto)")
                }
            } 
            // Si el stock final > 0 pero hay más lotes que stock, ajustar
            else if (sumaLotes > stockFinal) {
                val diferencia = sumaLotes - stockFinal
                Log.d(TAG, "   ⚠️ Hay más lotes ($sumaLotes) que stock final ($stockFinal)")
                Log.d(TAG, "   🔄 Descontando diferencia: $diferencia uds")
                
                // Descontar la diferencia de los lotes más viejos
                var pendienteDescontar = diferencia
                lotes.forEach { lote ->
                    if (pendienteDescontar <= 0) return@forEach
                    
                    val descontar = minOf(lote.cantidad, pendienteDescontar)
                    val cantidadRestante = lote.cantidad - descontar
                    
                    if (cantidadRestante <= 0) {
                        repository.eliminarLote(localId, lote.id)
                        Log.d(TAG, "      🗑️ Lote eliminado: ${lote.cantidad} uds → Vto ${lote.fechaVencimiento}")
                    } else {
                        repository.actualizarCantidadLote(localId, lote.id, cantidadRestante)
                        Log.d(TAG, "      ✅ Lote ajustado: ${lote.cantidad} → $cantidadRestante uds")
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
            // Si el stock final > 0 y coincide con lotes, está correcto
            else if (sumaLotes == stockFinal) {
                Log.d(TAG, "   ✅ Lotes coinciden con stock final (correcto)")
            }
            // Si hay menos lotes que stock final, es porque falta cargar lotes del ingreso de mercadería
            else if (sumaLotes < stockFinal) {
                val diferencia = stockFinal - sumaLotes
                Log.w(TAG, "   ⚠️ ADVERTENCIA: Hay menos lotes ($sumaLotes) que stock final ($stockFinal)")
                Log.w(TAG, "   📋 Diferencia: $diferencia uds")
                Log.w(TAG, "   💡 ACCIÓN REQUERIDA: Debes cargar los lotes del Ingreso de Mercadería en VencimientosActivity")
                Log.w(TAG, "   💡 Si ingresaste $diferencia uds nuevas, debes crear un lote con la fecha de vencimiento correcta")
                Log.w(TAG, "   💡 NO se crean lotes automáticamente para evitar fechas incorrectas")
            }
        }
        
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "✅ SINCRONIZACIÓN COMPLETADA")
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }
    
    /**
     * Valida que la suma de lotes coincida con el stock actual
     */
    suspend fun validarConsistenciaStock(
        localId: String,
        productoCodigo: String,
        stockActual: Int
    ): Boolean {
        val lotes = repository.obtenerLotesActivosProducto(localId, productoCodigo)
        val sumaLotes = lotes.sumOf { it.cantidad }
        
        val coincide = sumaLotes == stockActual
        
        if (!coincide) {
            Log.w(TAG, "⚠️ INCONSISTENCIA en $productoCodigo:")
            Log.w(TAG, "   Stock actual: $stockActual")
            Log.w(TAG, "   Suma lotes: $sumaLotes")
            Log.w(TAG, "   Diferencia: ${stockActual - sumaLotes}")
        }
        
        return coincide
    }
    
}

