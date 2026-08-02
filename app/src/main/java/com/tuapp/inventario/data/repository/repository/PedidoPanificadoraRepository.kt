package com.tuapp.inventario.repository

import com.tuapp.inventario.model.ProductoFabrica
import com.tuapp.inventario.model.ConfiguracionPedidoFabrica
import com.tuapp.inventario.model.AlertaStock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import com.tuapp.inventario.utils.DateHelper
import java.util.*

class PedidoPanificadoraRepository(
    private val localRepository: LocalInventarioRepository,
    private val localId: String
) {
    
    private val productosPanificadora = mutableListOf<ProductoFabrica>()
    private var configuracion = ConfiguracionPedidoFabrica()
    private var promediosCalculados = mapOf<String, Map<String, Double>>()
    
    init {
        // Ya no inicializamos productos bosicos, se cargaron desde los promedios reales
    }
    
    /**
     * Carga los productos de panificadora basondose en los promedios reales de ventas
     * SOLO incluye: Medialunas de Manteca, Medialunas de Grasa, Chipa, Criollitos
     */
    suspend fun cargarProductosDesdePromedios() {
        try {
            println(" DEBUG PedidoPanificadoraRepository: Iniciando carga de promedios para localId: $localId")
            // Obtener todos los registros del local
            localRepository.getAllRegistros(localId).collect { registros ->
                println(" DEBUG PedidoPanificadoraRepository: Registros obtenidos: ${registros.size}")
                if (registros.isNotEmpty()) {
                    println(" DEBUG PedidoPanificadoraRepository: Calculando promedios...")
                    // Calcular promedios usando la misma logica que PromedioVentasActivity
                    promediosCalculados = calcularPromediosPorDia(registros)
                    println(" DEBUG PedidoPanificadoraRepository: Promedios calculados: ${promediosCalculados.size} doas")
                    crearProductosDesdePromedios()
                    println(" DEBUG PedidoPanificadoraRepository: Productos creados: ${productosPanificadora.size}")
                    println(" DEBUG PedidoPanificadoraRepository: Lista de productos final:")
                    productosPanificadora.forEach { producto ->
                        println(" DEBUG PedidoPanificadoraRepository:   - ${producto.nombre}")
                    }
                } else {
                    println(" DEBUG PedidoPanificadoraRepository: No hay registros para el local $localId")
                }
            }
        } catch (e: Exception) {
            println(" DEBUG PedidoPanificadoraRepository: Error cargando promedios: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun calcularPromediosPorDia(registros: List<com.tuapp.inventario.RegistroInventario>): Map<String, Map<String, Double>> {
        val promediosPorDia = mutableMapOf<String, MutableMap<String, Double>>()
        val diasSemana = listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        
        println(" DEBUG: Iniciando colculo de promedios con ${registros.size} registros")
        
        // Inicializar estructura
        diasSemana.forEach { dia ->
            promediosPorDia[dia] = mutableMapOf()
        }
        
        // Obtener todas las fechas ordenadas
        val fechasOrdenadas = registros.map { it.fecha }.distinct().sorted()
        
        if (fechasOrdenadas.isEmpty()) {
            return promediosPorDia
        }
        
        println(" DEBUG: Fechas onicas encontradas: $fechasOrdenadas")
        
        // Agrupar por doa de la semana
        val ventasPorDia = mutableMapOf<String, MutableList<Map<String, Int>>>()
        
        fechasOrdenadas.forEach { fecha ->
            val dia = obtenerDiaSemana(fecha)
            if (dia in diasSemana) {
                println(" DEBUG: Calculando ventas para fecha $fecha (doa: $dia)")
                val ventasDia = calcularVentasDia(fecha, registros)
                if (ventasDia.isNotEmpty()) {
                    println(" DEBUG: Ventas calculadas para $fecha: ${ventasDia.size} productos")
                    ventasPorDia.getOrPut(dia) { mutableListOf() }.add(ventasDia)
                }
            }
        }
        
        // Calcular promedios
        ventasPorDia.forEach { (dia, listaVentas) ->
            if (listaVentas.isNotEmpty()) {
                println(" DEBUG: Calculando promedios para $dia con ${listaVentas.size} doas de datos")
                val productos = listaVentas.first().keys
                productos.forEach { producto ->
                    // Filtrar solo los doas donde este producto tiene ventas > 0
                    val ventasConDatos = listaVentas.mapNotNull { it[producto] }.filter { it > 0 }
                    
                    if (ventasConDatos.isNotEmpty()) {
                        val suma = ventasConDatos.sum()
                        val promedio = suma.toDouble() / ventasConDatos.size
                        promediosPorDia[dia]?.set(producto, promedio)
                    } else {
                        promediosPorDia[dia]?.set(producto, 0.0)
                    }
                }
            }
        }
        
        return promediosPorDia
    }
    
    private fun calcularVentasDia(fecha: String, registros: List<com.tuapp.inventario.RegistroInventario>): Map<String, Int> {
        val registrosFecha = registros.filter { it.fecha == fecha }
        val ingreso = registrosFecha.find { it.tipo == "Ingreso de Mercaderoa" }?.productos ?: emptyList()
        val stockFinal = registrosFecha.find { it.tipo == "Stock Final" }?.productos ?: emptyList()
        
        // Obtener stock final del doa anterior
        val fechaAnterior = obtenerFechaAnterior(fecha)
        val stockAnterior = registros.filter { it.fecha == fechaAnterior }
            .find { it.tipo == "Stock Final" }?.productos ?: emptyList()
        
        val ventas = mutableMapOf<String, Int>()
        
        // Si no hay stock final del doa actual, no podemos calcular ventas
        if (stockFinal.isEmpty()) {
            return ventas
        }
        
        //  OPTIMIZACIoN: Solo calcular ventas para productos de panificadora
        // Productos relevantes: DULCES, SALADO, CHIPA, CRIOLLITO
        val productosPanificadora = listOf("DULCES", "SALADO", "CHIPA", "CRIOLLITO")
        
        // Calcular ventas solo para productos de panificadora
        stockFinal.forEach { productoStock ->
            val nombreProducto = productoStock.nombre
            
            // Verificar si es un producto de panificadora
            val esProductoPanificadora = productosPanificadora.any { codigo ->
                nombreProducto.startsWith(codigo, ignoreCase = true) ||
                nombreProducto.contains(codigo, ignoreCase = true) ||
                (codigo == "DULCES" && (nombreProducto.contains("Dulce", ignoreCase = true) || nombreProducto.contains("Dulces", ignoreCase = true))) ||
                (codigo == "SALADO" && nombreProducto.contains("Salado", ignoreCase = true)) ||
                (codigo == "CHIPA" && nombreProducto.contains("Chipa", ignoreCase = true)) ||
                (codigo == "CRIOLLITO" && nombreProducto.contains("Criollito", ignoreCase = true))
            }
            
            // Solo procesar si es producto de panificadora
            if (esProductoPanificadora) {
                val ingresoCantidad = ingreso.find { it.nombre == nombreProducto }?.cantidad ?: 0
                val stockFinalCantidad = productoStock.cantidad
                val stockAnteriorCantidad = stockAnterior.find { it.nombre == nombreProducto }?.cantidad ?: 0
                
                val venta = stockAnteriorCantidad + ingresoCantidad - stockFinalCantidad
                
                if (venta > 0) {
                    ventas[nombreProducto] = venta
                }
            }
        }
        
        return ventas
    }
    
    private fun obtenerDiaSemana(fecha: String): String {
        return try {
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val date = formato.parse(fecha) ?: Date()
            val calendar = DateHelper.getCalendar().apply { time = date }
            
            when (calendar.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "LUNES"
                Calendar.TUESDAY -> "MARTES"
                Calendar.WEDNESDAY -> "MIERCOLES"
                Calendar.THURSDAY -> "JUEVES"
                Calendar.FRIDAY -> "VIERNES"
                Calendar.SATURDAY -> "SABADO"
                Calendar.SUNDAY -> "DOMINGO"
                else -> "LUNES"
            }
        } catch (e: Exception) {
            "LUNES"
        }
    }
    
    private fun obtenerFechaAnterior(fecha: String): String {
        return try {
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val date = formato.parse(fecha) ?: Date()
            val calendar = DateHelper.getCalendar().apply { time = date }
            calendar.add(Calendar.DAY_OF_MONTH, -1)
            formato.format(calendar.time)
        } catch (e: Exception) {
            fecha
        }
    }
    
    /**
     * Crea productos SOLO de panificadora: Medialunas de Manteca, Medialunas de Grasa, Chipa, Criollitos
     */
    private fun crearProductosDesdePromedios() {
        println(" DEBUG: Iniciando crearProductosDesdePromedios() - SOLO PANIFICADORA")
        productosPanificadora.clear()
        
        // Obtener todos los codigos onicos de los promedios
        val codigosDisponibles = promediosCalculados.values
            .flatMap { it.keys }
            .distinct()
            .sorted()
        
        println(" DEBUG: Codigos onicos encontrados: $codigosDisponibles")
        
        // Mapear codigos cortos a nombres completos encontrados en los promedios
        val mapeoCodigos = mutableMapOf<String, String>()
        codigosDisponibles.forEach { nombreCompleto ->
            // Extraer codigo corto del nombre completo (ej: "JQ - Jamon y Queso" -> "JQ")
            val codigoCorto = nombreCompleto.split(" - ").firstOrNull() ?: nombreCompleto
            mapeoCodigos[codigoCorto] = nombreCompleto
        }
        
        println(" DEBUG: Mapeo de codigos: $mapeoCodigos")
        
        // SOLO productos de panificadora
        // Mapeo especofico: MEDIALUNA_MANTECA debe usar "DULCES - Dulces" de Firebase
        var productosCreados = 0
        
        // 1. MEDIALUNA_MANTECA (Dulces) - Buscar "DULCES - Dulces" en Firebase
        val nombreDulces = codigosDisponibles.find { nombre ->
            nombre.startsWith("DULCES", ignoreCase = true) || 
            (nombre.contains("Dulce", ignoreCase = true) && nombre.contains("DULCES", ignoreCase = true))
        } ?: codigosDisponibles.find { nombre ->
            nombre.contains("Dulce", ignoreCase = true)
        }
        
        if (nombreDulces != null) {
            val promediosDulces = mutableMapOf<String, Int>()
            promediosCalculados.forEach { (dia, promediosDia) ->
                val promedio = promediosDia[nombreDulces]?.toInt() ?: 0
                promediosDulces[dia] = promedio
            }
            
            println(" DEBUG: MEDIALUNA_MANTECA usando '$nombreDulces' - Promedios: $promediosDulces")
            
            if (promediosDulces.values.any { it > 0 }) {
                val producto = ProductoFabrica(
                    codigo = "MEDIALUNA_MANTECA",
                    nombre = "Medialunas de Manteca",
                    categoria = "PANIFICADORA",
                    promediosVenta = promediosDulces
                )
                productosPanificadora.add(producto)
                productosCreados++
                println(" DEBUG:  Producto MEDIALUNA_MANTECA creado con promedios de '$nombreDulces'")
            }
        } else {
            println(" DEBUG:  No se encontro 'DULCES - Dulces' en los promedios")
        }
        
        // 2. MEDIALUNA_GRASA (Salado) - Buscar "SALADO - Salado" en Firebase
        val nombreSalado = codigosDisponibles.find { nombre ->
            nombre.startsWith("SALADO", ignoreCase = true) || 
            (nombre.contains("Salado", ignoreCase = true) && nombre.contains("SALADO", ignoreCase = true))
        } ?: codigosDisponibles.find { nombre ->
            nombre.contains("Salado", ignoreCase = true)
        }
        
        // Fallback: buscar por "Grasa" si no se encuentra "SALADO"
        val nombreGrasa = nombreSalado ?: codigosDisponibles.find { nombre ->
            nombre.contains("Grasa", ignoreCase = true) && 
            (nombre.contains("Medialuna", ignoreCase = true) || nombre.contains("MEDIALUNA", ignoreCase = true))
        } ?: codigosDisponibles.find { nombre ->
            nombre.startsWith("GRASA", ignoreCase = true) || nombre.contains("Grasa", ignoreCase = true)
        }
        
        if (nombreGrasa != null) {
            val promediosGrasa = mutableMapOf<String, Int>()
            promediosCalculados.forEach { (dia, promediosDia) ->
                val promedio = promediosDia[nombreGrasa]?.toInt() ?: 0
                promediosGrasa[dia] = promedio
            }
            
            val nombreUsado = nombreSalado ?: nombreGrasa
            println(" DEBUG: MEDIALUNA_GRASA usando '$nombreUsado' - Promedios: $promediosGrasa")
            
            if (promediosGrasa.values.any { it > 0 }) {
                val producto = ProductoFabrica(
                    codigo = "MEDIALUNA_GRASA",
                    nombre = "Medialunas de Grasa",
                    categoria = "PANIFICADORA",
                    promediosVenta = promediosGrasa
                )
                productosPanificadora.add(producto)
                productosCreados++
                println(" DEBUG:  Producto MEDIALUNA_GRASA creado con promedios de '$nombreUsado'")
            }
        }
        
        // 3. CHIPA
        val nombreChipa = codigosDisponibles.find { nombre ->
            nombre.contains("Chipa", ignoreCase = true) || nombre.contains("CHIPA", ignoreCase = true)
        }
        
        if (nombreChipa != null) {
            val promediosChipa = mutableMapOf<String, Int>()
            promediosCalculados.forEach { (dia, promediosDia) ->
                val promedio = promediosDia[nombreChipa]?.toInt() ?: 0
                promediosChipa[dia] = promedio
            }
            
            if (promediosChipa.values.any { it > 0 }) {
                val producto = ProductoFabrica(
                    codigo = "CHIPA",
                    nombre = "Chipa",
                    categoria = "PANIFICADORA",
                    promediosVenta = promediosChipa
                )
                productosPanificadora.add(producto)
                productosCreados++
                println(" DEBUG:  Producto CHIPA creado con promedios de '$nombreChipa'")
            }
        }
        
        // 4. CRIOLLITO
        val nombreCriollito = codigosDisponibles.find { nombre ->
            nombre.contains("Criollito", ignoreCase = true) || nombre.contains("CRIOLLITO", ignoreCase = true)
        }
        
        if (nombreCriollito != null) {
            val promediosCriollito = mutableMapOf<String, Int>()
            promediosCalculados.forEach { (dia, promediosDia) ->
                val promedio = promediosDia[nombreCriollito]?.toInt() ?: 0
                promediosCriollito[dia] = promedio
            }
            
            if (promediosCriollito.values.any { it > 0 }) {
                val producto = ProductoFabrica(
                    codigo = "CRIOLLITO",
                    nombre = "Criollitos",
                    categoria = "PANIFICADORA",
                    promediosVenta = promediosCriollito
                )
                productosPanificadora.add(producto)
                productosCreados++
                println(" DEBUG:  Producto CRIOLLITO creado con promedios de '$nombreCriollito'")
            }
        }
        
        println(" DEBUG: Total productos panificadora creados: $productosCreados")
    }
    
    fun getAllProductos(): Flow<List<ProductoFabrica>> {
        println(" DEBUG: getAllProductos() llamado - productos en memoria: ${productosPanificadora.size}")
        productosPanificadora.forEach { producto ->
            println(" DEBUG: Producto en memoria: ${producto.codigo} - ${producto.nombre}")
        }
        return flow {
            emit(productosPanificadora.toList())
        }
    }
    
    fun getProductosDirectos(): List<ProductoFabrica> {
        println(" DEBUG: getProductosDirectos() llamado - productos en memoria: ${productosPanificadora.size}")
        return productosPanificadora.toList()
    }
    
    fun actualizarStockProducto(codigo: String, stockActual: Int) {
        val index = productosPanificadora.indexOfFirst { it.codigo == codigo }
        if (index != -1) {
            productosPanificadora[index] = productosPanificadora[index].copy(stockActual = stockActual)
        }
    }
    
    fun actualizarVentaHasta1530(codigo: String, venta: Int) {
        val index = productosPanificadora.indexOfFirst { it.codigo == codigo }
        if (index != -1) {
            productosPanificadora[index] = productosPanificadora[index].copy(ventaHasta1530 = venta)
        }
    }
    
    fun actualizarPedidoFinal(codigo: String, pedido: Int) {
        val index = productosPanificadora.indexOfFirst { it.codigo == codigo }
        if (index != -1) {
            productosPanificadora[index] = productosPanificadora[index].copy(pedidoFinal = pedido)
        }
    }
    
    fun getConfiguracion(): ConfiguracionPedidoFabrica = configuracion
    
    fun actualizarConfiguracion(nuevaConfig: ConfiguracionPedidoFabrica) {
        configuracion = nuevaConfig
    }
    
    fun calcularPedidoSugerido(producto: ProductoFabrica, diaActual: String): Int {
        val promedioDiaActual = producto.promediosVenta[diaActual] ?: 0
        val porcentajeExtra = configuracion.porcentajeExtra / 100.0
        
        return if (diaActual == "VIERNES") {
            // Logica especial para viernes (incluye sobado y domingo)
            val promedioSabado = producto.promediosVenta["SABADO"] ?: 0
            val promedioDomingo = producto.promediosVenta["DOMINGO"] ?: 0
            
            val ventaRestanteHoy = (promedioDiaActual / 2.0)
            val ventaSabado = promedioSabado.toDouble()
            val ventaDomingo = promedioDomingo * (1 + porcentajeExtra)
            
            val totalNecesario = ventaRestanteHoy + ventaSabado + ventaDomingo
            val stockDisponible = producto.stockActual - ventaRestanteHoy
            
            val pedido = Math.max(0.0, totalNecesario - stockDisponible)
            redondearAMultiploDe50(pedido)
        } else {
            // Logica para otros doas
            val diaSiguiente = obtenerDiaSiguiente(diaActual)
            val promedioDiaSiguiente = producto.promediosVenta[diaSiguiente] ?: 0
            
            val ventaRestanteHoy = (promedioDiaActual / 2.0)
            val ventaDiaSiguiente = promedioDiaSiguiente * (1 + porcentajeExtra)
            
            val totalNecesario = ventaRestanteHoy + ventaDiaSiguiente
            val stockDisponible = producto.stockActual - ventaRestanteHoy
            
            val pedido = Math.max(0.0, totalNecesario - stockDisponible)
            redondearAMultiploDe50(pedido)
        }
    }
    
    private fun obtenerDiaSiguiente(diaActual: String): String {
        val dias = listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        val indexActual = dias.indexOf(diaActual)
        return if (indexActual == dias.size - 1) "LUNES" else dias[indexActual + 1]
    }
    
    private fun redondearAMultiploDe50(valor: Double): Int {
        return ((Math.ceil(valor / 50.0)) * 50).toInt()
    }
    
    fun generarAlertasStock(): List<AlertaStock> {
        val alertas = mutableListOf<AlertaStock>()
        val calendar = DateHelper.getCalendar()
        val formato = DateHelper.getDateFormat("dd/MM/yy")
        
        // Simular alertas para los proximos doas
        productosPanificadora.forEach { producto ->
            val promedioHoy = producto.promediosVenta[obtenerDiaActual()] ?: 0
            val stockPredicho = producto.stockActual - (promedioHoy / 2)
            
            if (stockPredicho < 20) { // Umbral de stock bajo
                calendar.add(Calendar.DAY_OF_MONTH, 1)
                alertas.add(AlertaStock(
                    producto = producto.nombre,
                    stockPredicho = stockPredicho,
                    semana = "SEMANA 1",
                    fecha = formato.format(calendar.time)
                ))
                calendar.add(Calendar.DAY_OF_MONTH, -1)
            }
        }
        
        return alertas
    }
    
    private fun obtenerDiaActual(): String {
        val calendar = DateHelper.getCalendar()
        val dias = arrayOf("DOMINGO", "LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO")
        return dias[calendar.get(Calendar.DAY_OF_WEEK) - 1]
    }
}

