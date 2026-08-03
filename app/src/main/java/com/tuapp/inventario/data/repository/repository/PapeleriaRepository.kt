package com.tuapp.inventario.repository

import com.tuapp.inventario.model.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
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
    private val ingresoCollection
        get() = firestore.collection("locales").document(localId).collection("ingreso_papeleria")
    private val configDocument
        get() = firestore.collection("locales").document(localId).collection("configuracion_papeleria").document("config")

    // ==================== STOCK ====================

    suspend fun guardarStockPapeleria(stock: StockPapeleria) {
        try {
            stockCollection.document(stock.fecha).set(stock).await()
        } catch (e: Exception) {
            throw Exception("Error guardando stock de papeleria: ${e.message}")
        }
    }

    suspend fun obtenerStockPapeleria(fecha: String): StockPapeleria? {
        return try {
            val docRef = stockCollection.document(fecha)
            android.util.Log.d("PapeleriaRepo", "Buscando stock en: locales/$localId/stock_papeleria/$fecha")
            val document = docRef.get().await()
            android.util.Log.d("PapeleriaRepo", "Stock doc existe: ${document.exists()}")
            if (document.exists()) document.toObject(StockPapeleria::class.java) else null
        } catch (e: Exception) {
            android.util.Log.e("PapeleriaRepo", "Error obteniendo stock: ${e.message}")
            null
        }
    }

    suspend fun obtenerUltimosStocks(cantidad: Int = 5): List<StockPapeleria> {
        return try {
            val documentos = stockCollection
                .orderBy("fecha", Query.Direction.DESCENDING)
                .limit(cantidad.toLong())
                .get()
                .await()
            documentos.documents.mapNotNull { it.toObject(StockPapeleria::class.java) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== PEDIDOS ====================

    suspend fun guardarPedidoPapeleria(pedido: PedidoPapeleria) {
        try {
            pedidosCollection.document(pedido.fecha).set(pedido).await()
        } catch (e: Exception) {
            throw Exception("Error guardando pedido de papeleria: ${e.message}")
        }
    }

    suspend fun obtenerPedidoPapeleria(fecha: String): PedidoPapeleria? {
        return try {
            android.util.Log.d("PapeleriaRepo", "Buscando pedido en: locales/$localId/pedidos_papeleria/$fecha")
            val document = pedidosCollection.document(fecha).get().await()
            android.util.Log.d("PapeleriaRepo", "Pedido doc existe: ${document.exists()}")
            if (document.exists()) document.toObject(PedidoPapeleria::class.java) else null
        } catch (e: Exception) {
            android.util.Log.e("PapeleriaRepo", "Error obteniendo pedido: ${e.message}")
            null
        }
    }

    suspend fun obtenerUltimosPedidos(cantidad: Int = 5): List<PedidoPapeleria> {
        return try {
            val documentos = pedidosCollection
                .orderBy("fecha", Query.Direction.DESCENDING)
                .limit(cantidad.toLong())
                .get()
                .await()
            documentos.documents.mapNotNull { it.toObject(PedidoPapeleria::class.java) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== INGRESO ====================

    suspend fun guardarIngresoPapeleria(ingreso: IngresoPapeleria) {
        try {
            ingresoCollection.document(ingreso.fecha).set(ingreso).await()
        } catch (e: Exception) {
            throw Exception("Error guardando ingreso de papeleria: ${e.message}")
        }
    }

    suspend fun obtenerIngresoPapeleria(fecha: String): IngresoPapeleria? {
        return try {
            val document = ingresoCollection.document(fecha).get().await()
            if (document.exists()) document.toObject(IngresoPapeleria::class.java) else null
        } catch (e: Exception) {
            null
        }
    }

    // ==================== CONFIGURACION ====================

    suspend fun obtenerConfiguracion(): ConfiguracionPedidoPapeleria {
        return try {
            val document = configDocument.get().await()
            if (document.exists()) document.toObject(ConfiguracionPedidoPapeleria::class.java)
                ?: ConfiguracionPedidoPapeleria()
            else ConfiguracionPedidoPapeleria()
        } catch (e: Exception) {
            ConfiguracionPedidoPapeleria()
        }
    }

    suspend fun guardarConfiguracion(config: ConfiguracionPedidoPapeleria) {
        try {
            configDocument.set(config).await()
        } catch (e: Exception) {
            throw Exception("Error guardando configuracion: ${e.message}")
        }
    }

    // ==================== CALCULO DE SUGERENCIAS ====================

    /**
     * Calcula el pedido sugerido basado en consumo historico por diferencia semanal.
     *
     * Formula: consumo = stock_anterior + ingreso - stock_actual
     * Motor de prediccion: 50% ultimas 4 semanas, 30% ultimas 8 semanas, 20% ultima semana
     */
    suspend fun calcularSugerencias(
        stockActual: StockPapeleria?,
        configuracion: ConfiguracionPedidoPapeleria
    ): List<ProductoPedidoPapeleria> {

        val catalogo = ProductoPapeleria.getProductosPapeleria()

        if (stockActual == null) {
            return catalogo.map { producto ->
                ProductoPedidoPapeleria(
                    codigo = producto.codigo,
                    nombre = producto.nombre,
                    categoria = producto.categoria,
                    stockActual = 0,
                    unidadesPorBulto = producto.unidadesPorBulto,
                    pedidoSugerido = 0,
                    pedidoFinal = 0
                )
            }
        }

        // Obtener historial de stocks y pedidos para calcular consumo
        val historialStocks = obtenerUltimosStocks(9) // ultimas 9 semanas (necesitamos 8 diferencias)
        val historialPedidos = obtenerUltimosPedidos(9)

        // Mapear pedidos por fecha para obtener ingreso
        val pedidosPorFecha = historialPedidos.associateBy { it.fecha }

        // Calcular consumo por producto usando diferencias entre stocks consecutivos
        // historialStocks[0] = mas reciente, historialStocks[1] = anterior, etc.
        val consumosPorCodigo = mutableMapOf<String, MutableList<Int>>()

        for (i in 0 until historialStocks.size - 1) {
            val stockReciente = historialStocks[i]
            val stockAnterior = historialStocks[i + 1]

            // Buscar el pedido que se hizo en la fecha del stock anterior (viernes previo)
            val pedido = pedidosPorFecha[stockAnterior.fecha]
            val ingresoPorCodigo = mutableMapOf<String, Int>()

            // El ingreso viene del pedido final confirmado
            if (pedido != null) {
                pedido.productos.forEach { pp ->
                    ingresoPorCodigo[pp.codigo] = pp.pedidoFinal
                }
            }

            // Tambien buscar ingreso confirmado en la fecha del stock anterior
            val ingresoDoc = obtenerIngresoPapeleria(stockAnterior.fecha)
            if (ingresoDoc != null && ingresoDoc.confirmado) {
                ingresoDoc.productos.forEach { ip ->
                    ingresoPorCodigo[ip.codigo] = ip.cantidadRecibida
                }
            }

            // Calcular consumo por producto
            // consumo = stock_anterior + ingreso - stock_actual
            for (productoActual in stockReciente.productos) {
                val productoAnterior = stockAnterior.productos.find { it.codigo == productoActual.codigo }
                if (productoAnterior != null) {
                    val ingreso = ingresoPorCodigo[productoActual.codigo] ?: 0
                    val consumo = productoAnterior.stock + ingreso - productoActual.stock
                    if (consumo >= 0) {
                        consumosPorCodigo.getOrPut(productoActual.codigo) { mutableListOf() }.add(consumo)
                    }
                }
            }
        }

        // Motor de prediccion: 50% ultimas 4 semanas, 30% ultimas 8 semanas, 20% ultima semana
        val promedioPonderadoPorCodigo = mutableMapOf<String, Double>()
        for ((codigo, consumos) in consumosPorCodigo) {
            if (consumos.isEmpty()) continue

            // consumos[0] = mas reciente, consumos[1] = anterior, etc.
            val ultimaSemana = consumos.firstOrNull() ?: 0

            val ultimas4 = consumos.take(4)
            val promedio4 = if (ultimas4.isNotEmpty()) ultimas4.average() else 0.0

            val ultimas8 = consumos.take(8)
            val promedio8 = if (ultimas8.isNotEmpty()) ultimas8.average() else 0.0

            // Promedio ponderado: 50% ultimas 4, 30% ultimas 8, 20% ultima semana
            val ponderado = promedio4 * 0.5 + promedio8 * 0.3 + ultimaSemana * 0.2
            promedioPonderadoPorCodigo[codigo] = ponderado
        }

        // Generar sugerencias
        val porcentajeExtra = configuracion.porcentajeExtra / 100.0

        return stockActual.productos.map { producto ->
            val catalogoProd = catalogo.find { it.codigo == producto.codigo }
            val unidadesPorBulto = catalogoProd?.unidadesPorBulto ?: 1

            val promedioConsumo = promedioPonderadoPorCodigo[producto.codigo] ?: 0.0
            val stockEfectivo = producto.stock

            // Consumo real de la ultima semana (para mostrar y comparar)
            val consumoReal = consumosPorCodigo[producto.codigo]?.firstOrNull() ?: 0

            // Detectar anomalia: si consumo real > 2x el promedio ponderado
            val esAnomalia = consumoReal > 0 && promedioConsumo > 0 && consumoReal > promedioConsumo * 2.0

            // Sugerido = promedio ponderado + stock seguridad - stock efectivo
            val stockSeguridad = (promedioConsumo * porcentajeExtra).toInt()
            val pedidoSugerido = (promedioConsumo + stockSeguridad - stockEfectivo).toInt().coerceAtLeast(0)

            ProductoPedidoPapeleria(
                codigo = producto.codigo,
                nombre = producto.nombre,
                categoria = producto.categoria,
                stockActual = producto.stock,
                unidadesPorBulto = unidadesPorBulto,
                consumoReal = consumoReal,
                consumoPromedio = promedioConsumo,
                esAnomalia = esAnomalia,
                pedidoSugerido = pedidoSugerido,
                pedidoFinal = pedidoSugerido
            )
        }
    }

    /**
     * Genera un ingreso automatico desde el pedido confirmado.
     * Se llama el lunes cuando llega el pedido.
     */
    suspend fun generarIngresoDesdePedido(fechaPedido: String, fechaIngreso: String): IngresoPapeleria? {
        val pedido = obtenerPedidoPapeleria(fechaPedido) ?: return null

        val productos = pedido.productos.map { pp ->
            IngresoProductoPapeleria(
                codigo = pp.codigo,
                nombre = pp.nombre,
                cantidadPedida = pp.pedidoFinal,
                cantidadRecibida = pp.pedidoFinal // Asumir que llego todo
            )
        }

        val ingreso = IngresoPapeleria(
            fecha = fechaIngreso,
            local = pedido.local,
            productos = productos,
            confirmado = false // Pendiente de confirmacion
        )

        guardarIngresoPapeleria(ingreso)
        return ingreso
    }

    /**
     * Confirma o corrige un ingreso de papeleria.
     */
    suspend fun confirmarIngreso(fecha: String, correcciones: Map<String, Int>): Boolean {
        val ingreso = obtenerIngresoPapeleria(fecha) ?: return false

        val productosCorregidos = ingreso.productos.map { p ->
            val cantidadCorregida = correcciones[p.codigo] ?: p.cantidadRecibida
            p.copy(cantidadRecibida = cantidadCorregida)
        }

        val ingresoCorregido = ingreso.copy(
            productos = productosCorregidos,
            confirmado = true
        )

        guardarIngresoPapeleria(ingresoCorregido)
        return true
    }

    // ==================== ALERTAS ====================

    /**
     * Verifica si hay productos con stock bajo (no alcanzan para la semana)
     */
    suspend fun verificarStockBajo(): List<Pair<String, Int>> {
        val stocks = obtenerUltimosStocks(2)
        if (stocks.size < 2) return emptyList()

        val stockActual = stocks[0]
        val stockAnterior = stocks[1]
        val pedidos = obtenerUltimosPedidos(2)
        val pedidoAnterior = pedidos.find { it.fecha == stockAnterior.fecha }

        val alertas = mutableListOf<Pair<String, Int>>()

        for (producto in stockActual.productos) {
            val productoAnterior = stockAnterior.productos.find { it.codigo == producto.codigo }
            if (productoAnterior != null) {
                val ingreso = pedidoAnterior?.productos?.find { it.codigo == producto.codigo }?.pedidoFinal ?: 0
                val consumo = productoAnterior.stock + ingreso - producto.stock
                val consumoDiario = if (consumo > 0) consumo / 7.0 else 0.0
                val stockProyectadoViernes = producto.stock - (consumoDiario * 5).toInt()

                if (stockProyectadoViernes < 0) {
                    alertas.add(Pair(producto.nombre, stockProyectadoViernes))
                }
            }
        }

        return alertas
    }

    // ==================== FECHAS ====================

    fun obtenerFechasConStock(): Flow<List<String>> = flow {
        try {
            val documentos = stockCollection
                .orderBy("fecha", Query.Direction.DESCENDING)
                .get()
                .await()
            emit(documentos.documents.mapNotNull { it.id })
        } catch (e: Exception) {
            emit(emptyList())
        }
    }

    fun obtenerFechasConPedidos(): Flow<List<String>> = flow {
        try {
            val documentos = pedidosCollection
                .orderBy("fecha", Query.Direction.DESCENDING)
                .get()
                .await()
            emit(documentos.documents.mapNotNull { it.id })
        } catch (e: Exception) {
            emit(emptyList())
        }
    }
}
