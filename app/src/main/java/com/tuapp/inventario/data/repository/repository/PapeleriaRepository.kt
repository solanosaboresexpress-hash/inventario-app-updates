package com.tuapp.inventario.repository

import com.tuapp.inventario.model.*
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import com.tuapp.inventario.utils.DateHelper
import com.tuapp.inventario.utils.FirebaseRegionManager
import java.util.*

class PapeleriaRepository(private val localId: String) {
    
    private val firestore: FirebaseFirestore
        get() = FirebaseRegionManager.getFirestore()
    private val stockCollection 
        get() = firestore.collection("locales").document(localId).collection("stock_papeleria")
    private val pedidosCollection 
        get() = firestore.collection("locales").document(localId).collection("pedidos_papeleria")
    
    /**
     * Guarda el stock de papeleroa para una fecha especofica (lunes)
     */
    suspend fun guardarStockPapeleria(stock: StockPapeleria) {
        try {
            stockCollection.document(stock.fecha).set(stock).await()
        } catch (e: Exception) {
            throw Exception("Error guardando stock de papeleroa: ${e.message}")
        }
    }
    
    /**
     * Obtiene el stock de papeleroa para una fecha especofica
     */
    suspend fun obtenerStockPapeleria(fecha: String): StockPapeleria? {
        return try {
            val document = stockCollection.document(fecha).get().await()
            if (document.exists()) {
                document.toObject(StockPapeleria::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            throw Exception("Error obteniendo stock de papeleroa: ${e.message}")
        }
    }
    
    /**
     * Obtiene los dos oltimos registros de stock (lunes actual y anterior)
     */
    suspend fun obtenerUltimosDosStocks(): Pair<StockPapeleria?, StockPapeleria?> {
        return try {
            val documentos = stockCollection
                .orderBy("fecha", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(2)
                .get()
                .await()
            
            val stocks = documentos.documents.mapNotNull { it.toObject(StockPapeleria::class.java) }
            
            when (stocks.size) {
                0 -> Pair(null, null)
                1 -> Pair(stocks[0], null)
                else -> Pair(stocks[0], stocks[1])
            }
        } catch (e: Exception) {
            throw Exception("Error obteniendo oltimos stocks: ${e.message}")
        }
    }
    
    /**
     * Guarda un pedido de papeleroa
     */
    suspend fun guardarPedidoPapeleria(pedido: PedidoPapeleria) {
        try {
            pedidosCollection.document(pedido.fecha).set(pedido).await()
        } catch (e: Exception) {
            throw Exception("Error guardando pedido de papeleroa: ${e.message}")
        }
    }
    
    /**
     * Obtiene un pedido de papeleroa para una fecha especofica
     */
    suspend fun obtenerPedidoPapeleria(fecha: String): PedidoPapeleria? {
        return try {
            val document = pedidosCollection.document(fecha).get().await()
            if (document.exists()) {
                document.toObject(PedidoPapeleria::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            throw Exception("Error obteniendo pedido de papeleroa: ${e.message}")
        }
    }
    
    /**
     * Calcula el consumo semanal promedio y genera sugerencias de pedido
     */
    fun calcularConsumoYSugerencias(
        stockActual: StockPapeleria?,
        stockAnterior: StockPapeleria?
    ): List<ProductoPedidoPapeleria> {
        
        if (stockActual == null) {
            // Si no hay stock actual, crear productos con valores por defecto
            return ProductoPapeleria.getProductosPapeleria().map { producto ->
                ProductoPedidoPapeleria(
                    nombre = producto.nombre,
                    stockLunes = 0,
                    consumoEstimadoViernes = 0,
                    stockEstimadoViernes = 0,
                    pedidoSugerido = 0,
                    pedidoFinal = 0
                )
            }
        }
        
        return stockActual.productos.map { productoActual ->
            val productoAnterior = stockAnterior?.productos?.find { it.nombre == productoActual.nombre }
            
            // Calcular consumo semanal
            val consumo = if (productoAnterior != null) {
                productoAnterior.stock + productoAnterior.ingreso - productoActual.stock
            } else {
                0
            }
            
            // Calcular consumo diario promedio
            val consumoDiario = if (consumo > 0) consumo / 7.0 else 0.0
            
            // Calcular consumo estimado hasta el viernes (5 doas)
            val consumoEstimadoViernes = (consumoDiario * 5).toInt()
            
            // Calcular stock estimado para el viernes
            val stockEstimadoViernes = productoActual.stock - consumoEstimadoViernes
            
            // Calcular pedido sugerido
            val stockSeguridad = (consumoDiario * 0.5).toInt()
            val pedidoSugerido = ((consumoDiario * 3) + stockSeguridad - stockEstimadoViernes).toInt().coerceAtLeast(0)
            
            ProductoPedidoPapeleria(
                nombre = productoActual.nombre,
                stockLunes = productoActual.stock,
                consumoEstimadoViernes = consumoEstimadoViernes,
                stockEstimadoViernes = stockEstimadoViernes,
                pedidoSugerido = pedidoSugerido,
                pedidoFinal = pedidoSugerido // Inicialmente igual a la sugerencia
            )
        }
    }
    
    /**
     * Obtiene todas las fechas con stock de papeleroa
     */
    fun obtenerFechasConStock(): Flow<List<String>> = flow {
        try {
            val documentos = stockCollection
                .orderBy("fecha", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .get()
                .await()
            
            val fechas = documentos.documents.mapNotNull { it.id }
            emit(fechas)
        } catch (e: Exception) {
            emit(emptyList())
        }
    }
    
    /**
     * Obtiene todas las fechas con pedidos de papeleroa
     */
    fun obtenerFechasConPedidos(): Flow<List<String>> = flow {
        try {
            val documentos = pedidosCollection
                .orderBy("fecha", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .get()
                .await()
            
            val fechas = documentos.documents.mapNotNull { it.id }
            emit(fechas)
        } catch (e: Exception) {
            emit(emptyList())
        }
    }
}
