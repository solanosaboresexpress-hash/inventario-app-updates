package com.tuapp.inventario.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.tuapp.inventario.model.*
import kotlinx.coroutines.tasks.await
import com.tuapp.inventario.utils.DateHelper
import com.tuapp.inventario.utils.FirebaseRegionManager
import java.util.*
import android.util.Log

class BebidasRepository(private val localId: String) {
    
    private val firestore: FirebaseFirestore
        get() = FirebaseRegionManager.getFirestore()
    private val stockCollection 
        get() = firestore.collection("locales").document(localId).collection("stock_bebidas")
    private val pedidosCollection 
        get() = firestore.collection("locales").document(localId).collection("pedidos_bebidas")
    
    // Lista de productos de bebidas segon la especificacion
    val productosBebidas = listOf(
        // Presentacion 500 ml
        "Coca Cola 500 ml", "Sprite 500 ml", "Fanta 500 ml", "Aquarius Naranja 500 ml",
        "Aquarius Manzana 500 ml", "Aquarius Uva 500 ml", "Aquarius Pera 500 ml", 
        "Aquarius Pomelo 500 ml", "Agua 500 ml", "Coca Cola Zero 500 ml", "Sprite Zero 500 ml",
        // Presentacion 1.5 L
        "Coca Cola 1.5 L", "Sprite 1.5 L", "Fanta 1.5 L", "Aquarius Naranja 1.5 L",
        "Aquarius Manzana 1.5 L", "Aquarius Uva 1.5 L", "Aquarius Pera 1.5 L", 
        "Aquarius Pomelo 1.5 L", "Agua 1.5 L", "Coca Cola Zero 1.5 L", "Sprite Zero 1.5 L"
    )
    
    // Guardar stock e ingreso de bebidas
    suspend fun guardarStockBebidas(stockBebida: StockBebida) {
        try {
            stockCollection.document(stockBebida.fecha).set(stockBebida).await()
            Log.d("BebidasRepository", "Stock bebidas guardado para fecha: ${stockBebida.fecha}")
        } catch (e: Exception) {
            Log.e("BebidasRepository", "Error guardando stock bebidas", e)
            throw e
        }
    }
    
    // Guardar pedido de bebidas
    suspend fun guardarPedidoBebidas(pedidoBebida: PedidoBebida) {
        try {
            pedidosCollection.document(pedidoBebida.fecha).set(pedidoBebida).await()
            Log.d("BebidasRepository", "Pedido bebidas guardado para fecha: ${pedidoBebida.fecha}")
        } catch (e: Exception) {
            Log.e("BebidasRepository", "Error guardando pedido bebidas", e)
            throw e
        }
    }
    
    // Obtener stock de bebidas por fecha
    suspend fun obtenerStockBebidas(fecha: String): StockBebida? {
        return try {
            val document = stockCollection.document(fecha).get().await()
            if (document.exists()) {
                document.toObject(StockBebida::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("BebidasRepository", "Error obteniendo stock bebidas", e)
            null
        }
    }
    
    // Obtener pedido de bebidas por fecha
    suspend fun obtenerPedidoBebidas(fecha: String): PedidoBebida? {
        return try {
            val document = pedidosCollection.document(fecha).get().await()
            if (document.exists()) {
                document.toObject(PedidoBebida::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("BebidasRepository", "Error obteniendo pedido bebidas", e)
            null
        }
    }
    
    // Obtener los dos oltimos registros de stock para colculos
    suspend fun obtenerUltimosDosStocks(): Pair<StockBebida?, StockBebida?> {
        return try {
            val snapshot = stockCollection.orderBy("fecha", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(2)
                .get()
                .await()
            
            val stocks = snapshot.documents.mapNotNull { it.toObject(StockBebida::class.java) }
            
            when (stocks.size) {
                2 -> Pair(stocks[0], stocks[1])
                1 -> Pair(stocks[0], null)
                else -> Pair(null, null)
            }
        } catch (e: Exception) {
            Log.e("BebidasRepository", "Error obteniendo oltimos stocks", e)
            Pair(null, null)
        }
    }
    
    // Calcular consumo y sugerencias para pedidos
    fun calcularConsumoYSugerencias(
        stockActual: StockBebida?, 
        stockAnterior: StockBebida?
    ): List<ProductoPedidoBebida> {
        
        val productosPedido = mutableListOf<ProductoPedidoBebida>()
        
        productosBebidas.forEach { nombreProducto ->
            val productoActual = stockActual?.productos?.find { it.nombre == nombreProducto }
            val productoAnterior = stockAnterior?.productos?.find { it.nombre == nombreProducto }
            
            val stockUltimoIngreso = productoActual?.stock ?: 0
            val ingresoAnterior = productoAnterior?.ingreso ?: 0
            val stockAnteriorValue = productoAnterior?.stock ?: 0
            
            // Calcular consumo real
            val consumoReal = stockAnteriorValue + ingresoAnterior - stockUltimoIngreso
            val diasTranscurridos = calcularDiasTranscurridos(stockAnterior?.let { stockActual?.fecha }, stockActual?.fecha)
            val diasHastaProximoIngreso = calcularDiasHastaProximoIngreso(stockActual?.fecha)
            
            // Calcular consumo diario
            val consumoDiario = if (diasTranscurridos > 0) consumoReal.toDouble() / diasTranscurridos else 0.0
            
            // Calcular estimaciones
            val consumoEstimado = (consumoDiario * diasHastaProximoIngreso).toInt()
            val stockEstimadoActual = stockUltimoIngreso - consumoEstimado
            val stockSeguridad = (consumoDiario * 0.5).toInt()
            
            // Calcular pedido sugerido
            val pedidoSugerido = maxOf(0, (consumoDiario * diasHastaProximoIngreso + stockSeguridad - stockEstimadoActual).toInt())
            
            productosPedido.add(
                ProductoPedidoBebida(
                    nombre = nombreProducto,
                    stockUltimoIngreso = stockUltimoIngreso,
                    consumoEstimado = consumoEstimado,
                    stockEstimadoActual = stockEstimadoActual,
                    pedidoSugerido = pedidoSugerido,
                    pedidoFinal = pedidoSugerido // Inicialmente igual al sugerido
                )
            )
        }
        
        return productosPedido
    }
    
    private fun calcularDiasTranscurridos(fechaAnterior: String?, fechaActual: String?): Int {
        if (fechaAnterior == null || fechaActual == null) return 1
        
        return try {
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val fechaAnteriorDate = formato.parse(fechaAnterior) ?: return 1
            val fechaActualDate = formato.parse(fechaActual) ?: return 1
            
            val diferencia = fechaActualDate.time - fechaAnteriorDate.time
            val dias = diferencia / (1000 * 60 * 60 * 24)
            maxOf(1, dias.toInt())
        } catch (e: Exception) {
            Log.e("BebidasRepository", "Error calculando doas transcurridos", e)
            1
        }
    }
    
    private fun calcularDiasHastaProximoIngreso(fechaActual: String?): Int {
        if (fechaActual == null) return 2
        
        return try {
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val fecha = formato.parse(fechaActual) ?: return 2
            val calendar = DateHelper.getCalendar().apply { time = fecha }
            
            val diaSemana = calendar.get(Calendar.DAY_OF_WEEK)
            
            // Si es martes, proximo ingreso es viernes (3 doas)
            // Si es viernes, proximo ingreso es martes (4 doas, pero solo 2 doas hasta el pedido del jueves)
            when (diaSemana) {
                Calendar.TUESDAY -> 2 // Martes -> Jueves (pedido)
                Calendar.THURSDAY -> 3 // Jueves -> Domingo (pedido del lunes)
                Calendar.FRIDAY -> 2 // Viernes -> Domingo (pedido del lunes)
                Calendar.MONDAY -> 2 // Lunes -> Miorcoles (pedido del jueves)
                else -> 2 // Por defecto
            }
        } catch (e: Exception) {
            Log.e("BebidasRepository", "Error calculando doas hasta proximo ingreso", e)
            2
        }
    }
    
    // Obtener doa de la semana
    fun obtenerDiaSemana(fecha: String): String {
        return try {
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val date = formato.parse(fecha) ?: Date()
            val calendar = DateHelper.getCalendar().apply { time = date }
            
            when (calendar.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SUNDAY -> "Domingo"
                Calendar.MONDAY -> "Lunes"
                Calendar.TUESDAY -> "Martes"
                Calendar.WEDNESDAY -> "Miorcoles"
                Calendar.THURSDAY -> "Jueves"
                Calendar.FRIDAY -> "Viernes"
                Calendar.SATURDAY -> "Sobado"
                else -> "Lunes"
            }
        } catch (e: Exception) {
            "Lunes"
        }
    }
    
    // Verificar si es un doa volido para pedidos (Lunes y Jueves)
    fun esDiaValidoParaPedido(fecha: String): Boolean {
        val diaSemana = obtenerDiaSemana(fecha)
        return diaSemana == "Lunes" || diaSemana == "Jueves"
    }
    
    // Verificar si es un doa volido para ingreso (Martes y Viernes)
    fun esDiaValidoParaIngreso(fecha: String): Boolean {
        val diaSemana = obtenerDiaSemana(fecha)
        return diaSemana == "Martes" || diaSemana == "Viernes"
    }
}
