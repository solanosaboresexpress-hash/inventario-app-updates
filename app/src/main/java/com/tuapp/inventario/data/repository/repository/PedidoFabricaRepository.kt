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

class PedidoFabricaRepository(
    private val localRepository: LocalInventarioRepository,
    private val localId: String
) {
    
    private val productosFabrica = mutableListOf<ProductoFabrica>()
    private var configuracion = ConfiguracionPedidoFabrica()
    private var promediosCalculados = mapOf<String, Map<String, Double>>()
    
    init {
        // Ya no inicializamos productos bosicos, se cargaron desde los promedios reales
    }
    
    /**
     * Carga los productos de fobrica basondose en los promedios reales de ventas
     */
    suspend fun cargarProductosDesdePromedios() {
        try {
            println(" DEBUG PedidoFabricaRepository: Iniciando carga de promedios para localId: $localId")
            // Obtener todos los registros del local
            localRepository.getAllRegistros(localId).collect { registros ->
                println(" DEBUG PedidoFabricaRepository: Registros obtenidos: ${registros.size}")
                if (registros.isNotEmpty()) {
                    println(" DEBUG PedidoFabricaRepository: Calculando promedios...")
                    // Calcular promedios usando la misma logica que PromedioVentasActivity
                    promediosCalculados = calcularPromediosPorDia(registros)
                    println(" DEBUG PedidoFabricaRepository: Promedios calculados: ${promediosCalculados.size} doas")
                    crearProductosDesdePromedios()
                    println(" DEBUG PedidoFabricaRepository: Productos creados: ${productosFabrica.size}")
                    println(" DEBUG PedidoFabricaRepository: Lista de productos final:")
                    productosFabrica.forEach { producto ->
                        println(" DEBUG PedidoFabricaRepository:   - ${producto.nombre}")
                    }
                } else {
                    println(" DEBUG PedidoFabricaRepository: No hay registros para el local $localId")
                }
            }
        } catch (e: Exception) {
            println(" DEBUG PedidoFabricaRepository: Error cargando promedios: ${e.message}")
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
        
        // Obtener todas las fechas ordenadas (igual que PromedioVentasActivity)
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
                    ventasDia.forEach { (producto, cantidad) ->
                        println(" DEBUG:   $producto: $cantidad")
                    }
                    ventasPorDia.getOrPut(dia) { mutableListOf() }.add(ventasDia)
                } else {
                    println(" DEBUG: No se calcularon ventas para $fecha")
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
                        println(" DEBUG:   $producto: suma=$suma, promedio=$promedio")
                    } else {
                        // Si no hay doas con ventas > 0, el promedio es 0
                        promediosPorDia[dia]?.set(producto, 0.0)
                        println(" DEBUG:   $producto: sin ventas registradas, promedio=0.0")
                    }
                }
            } else {
                println(" DEBUG: No hay datos para $dia")
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
        
        println(" DEBUG: Calculando ventas para $fecha")
        println(" DEBUG:   Registros de la fecha: ${registrosFecha.size}")
        registrosFecha.forEach { registro ->
            println(" DEBUG:     ${registro.tipo}: ${registro.productos.size} productos")
        }
        println(" DEBUG:   Ingreso: ${ingreso.size} productos")
        println(" DEBUG:   Stock Final: ${stockFinal.size} productos")
        println(" DEBUG:   Stock Anterior ($fechaAnterior): ${stockAnterior.size} productos")
        
        // Si no hay stock final del doa actual, no podemos calcular ventas
        if (stockFinal.isEmpty()) {
            println(" DEBUG:   No hay stock final para $fecha, no se pueden calcular ventas")
            return ventas
        }
        
        //  OPTIMIZACIoN: Solo calcular ventas para productos de fobrica
        // Productos relevantes: EMPANADAS, PIZZAS, PASTELITOS (DULCES y CHIPA ya no se usan)
        val productosFabrica = listOf(
            // EMPANADAS
            "JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH",
            // PIZZAS
            "MUZZARE", "JAMON", "PEPPERO",
            // PASTELITOS
            "BATATA", "MEMBRIL"
        )
        
        // Calcular ventas solo para productos de fobrica
        stockFinal.forEach { productoStock ->
            val nombreProducto = productoStock.nombre
            
            // Verificar si es un producto de fobrica
            val esProductoFabrica = productosFabrica.any { codigo ->
                nombreProducto.startsWith("$codigo -", ignoreCase = true) ||
                nombreProducto.startsWith(codigo, ignoreCase = true) ||
                nombreProducto.contains(" - $codigo", ignoreCase = true) ||
                (codigo == "JQ" && (nombreProducto.contains("Jamon y Queso", ignoreCase = true) || nombreProducto.contains("Jamon y Queso", ignoreCase = true))) ||
                (codigo == "PB" && nombreProducto.contains("Pollo BBQ", ignoreCase = true)) ||
                (codigo == "CS" && nombreProducto.contains("Carne Suave", ignoreCase = true)) ||
                (codigo == "PO" && nombreProducto.contains("Pollo", ignoreCase = true) && !nombreProducto.contains("BBQ", ignoreCase = true)) ||
                (codigo == "CP" && nombreProducto.contains("Carne Picante", ignoreCase = true)) ||
                (codigo == "HU" && nombreProducto.contains("Humita", ignoreCase = true)) ||
                (codigo == "ES" && nombreProducto.contains("Espinaca", ignoreCase = true)) ||
                (codigo == "CQ" && nombreProducto.contains("Calabaza", ignoreCase = true)) ||
                (codigo == "RJ" && nombreProducto.contains("Roquefort", ignoreCase = true)) ||
                (codigo == "QC" && nombreProducto.contains("Queso y Cebolla", ignoreCase = true)) ||
                (codigo == "CB" && nombreProducto.contains("Cerdo", ignoreCase = true)) ||
                (codigo == "CH" && nombreProducto.contains("Cheeseburger", ignoreCase = true)) ||
                (codigo == "MUZZARE" && nombreProducto.contains("Muzzarella", ignoreCase = true)) ||
                (codigo == "JAMON" && nombreProducto.contains("Jamon", ignoreCase = true) && !nombreProducto.contains("y Queso", ignoreCase = true)) ||
                (codigo == "PEPPERO" && nombreProducto.contains("Pepperoni", ignoreCase = true)) ||
                (codigo == "BATATA" && nombreProducto.contains("Batata", ignoreCase = true)) ||
                (codigo == "MEMBRIL" && nombreProducto.contains("Membrillo", ignoreCase = true))
            }
            
            // Solo procesar si es producto de fobrica
            if (esProductoFabrica) {
                val ingresoCantidad = ingreso.find { it.nombre == nombreProducto }?.cantidad ?: 0
                val stockFinalCantidad = productoStock.cantidad
                val stockAnteriorCantidad = stockAnterior.find { it.nombre == nombreProducto }?.cantidad ?: 0
                
                val venta = stockAnteriorCantidad + ingresoCantidad - stockFinalCantidad
                
                println(" DEBUG:   $nombreProducto: Stock Anterior=$stockAnteriorCantidad + Ingreso=$ingresoCantidad - Stock Final=$stockFinalCantidad = $venta")
                
                if (venta > 0) {
                    ventas[nombreProducto] = venta
                }
            }
        }
        
        println(" DEBUG:   Total ventas calculadas: ${ventas.size} productos")
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
    
    private fun crearProductosDesdePromedios() {
        println(" DEBUG: Iniciando crearProductosDesdePromedios()")
        productosFabrica.clear()
        
        // Primero, vamos a ver quo codigos eston realmente disponibles en los promedios
        println(" DEBUG: Promedios disponibles por doa:")
        promediosCalculados.forEach { (dia, promediosDia) ->
            println(" DEBUG: $dia: ${promediosDia.keys}")
        }
        
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
        
        // Mapear nombres de productos a categoroas
        val categoriasProductos = mapOf(
            "EMPANADAS" to listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH"),
            "PIZZAS" to listOf("MUZZARE", "JAMON", "PEPPERO"),
            "PASTELITOS" to listOf("BATATA", "MEMBRIL"),
            "OTROS" to listOf("DULCES", "CHIPA")
        )
        
        var productosCreados = 0
        
        categoriasProductos.forEach { (categoria, productos) ->
            productos.forEach { codigo ->
                val promedios = mutableMapOf<String, Int>()
                
                // Buscar el nombre completo en los promedios usando el mapeo
                val nombreCompleto = mapeoCodigos[codigo]
                if (nombreCompleto != null) {
                    // Convertir promedios de Double a Int y mapear doas
                    promediosCalculados.forEach { (dia, promediosDia) ->
                        val promedio = promediosDia[nombreCompleto]?.toInt() ?: 0
                        promedios[dia] = promedio
                    }
                }
                
                println(" DEBUG: Codigo $codigo ($nombreCompleto) - Promedios: $promedios")
                
                // Crear producto solo si tiene promedios
                if (promedios.values.any { it > 0 }) {
                    val producto = ProductoFabrica(
                        codigo = codigo,
                        nombre = nombreCompleto ?: codigo,
                        categoria = categoria,
                        promediosVenta = promedios
                    )
                    productosFabrica.add(producto)
                    productosCreados++
                    println(" DEBUG: Producto creado: $codigo - ${producto.nombre}")
                } else {
                    println(" DEBUG: Producto $codigo no tiene promedios, omitiendo")
                }
            }
        }
        
        println(" DEBUG: Total productos creados: $productosCreados")
        
        // Si no se crearon productos con los codigos predefinidos, crear con los codigos reales
        if (productosCreados == 0) {
            println(" DEBUG: No se encontraron productos con codigos predefinidos, creando con codigos reales")
            codigosDisponibles.forEach { nombreCompleto ->
                val promedios = mutableMapOf<String, Int>()
                
                promediosCalculados.forEach { (dia, promediosDia) ->
                    val promedio = promediosDia[nombreCompleto]?.toInt() ?: 0
                    promedios[dia] = promedio
                }
                
                if (promedios.values.any { it > 0 }) {
                    val codigoCorto = nombreCompleto.split(" - ").firstOrNull() ?: nombreCompleto
                    val categoria = determinarCategoria(codigoCorto)
                    val producto = ProductoFabrica(
                        codigo = codigoCorto,
                        nombre = nombreCompleto,
                        categoria = categoria,
                        promediosVenta = promedios
                    )
                    productosFabrica.add(producto)
                    productosCreados++
                    println(" DEBUG: Producto real creado: $codigoCorto - Categoroa: $categoria")
                }
            }
        }
        
        println(" DEBUG: Total final de productos creados: $productosCreados")
    }
    
    private suspend fun guardarProductosEnBaseDeDatos() {
        try {
            println(" DEBUG: Guardando ${productosFabrica.size} productos en la base de datos...")
            // Convertir ProductoFabrica a Producto para guardar en la base de datos
            val productosParaGuardar = productosFabrica.map { productoFabrica ->
                com.tuapp.inventario.Producto(
                    nombre = productoFabrica.nombre,
                    categoria = productoFabrica.categoria,
                    cantidad = 0
                )
            }
            
            // Usar el repositorio local que ya esto disponible
            localRepository.insertProductos(localId, productosParaGuardar)
            println(" DEBUG: Productos guardados exitosamente en la base de datos")
        } catch (e: Exception) {
            println(" DEBUG: Error guardando productos en la base de datos: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun determinarCategoria(codigo: String): String {
        // Logica simple para determinar categoroa basada en el codigo
        return when {
            codigo.contains("EMPANADA", ignoreCase = true) -> "EMPANADAS"
            codigo.contains("PIZZA", ignoreCase = true) -> "PIZZAS"
            codigo.contains("PASTELITO", ignoreCase = true) -> "PASTELITOS"
            else -> "OTROS"
        }
    }
    
    private fun obtenerNombreCompleto(codigo: String, categoria: String): String {
        return when (categoria) {
            "EMPANADAS" -> "Empanada $codigo"
            "PIZZAS" -> when (codigo) {
                "MUZZA" -> "Pizza Muzzarella"
                "JAMON" -> "Pizza Jamon"
                "PEPPER" -> "Pizza Pepperoni"
                else -> "Pizza $codigo"
            }
            "PASTELITOS" -> when (codigo) {
                "BATATA" -> "Pastelito Batata"
                "MEMBRIL" -> "Pastelito Membrillo"
                else -> "Pastelito $codigo"
            }
            "OTROS" -> when (codigo) {
                "DULCES" -> "Medialuna Dulce"
                "CHIPA" -> "Medialuna Chipa"
                else -> codigo
            }
            else -> codigo
        }
    }
    
    fun getAllProductos(): Flow<List<ProductoFabrica>> {
        println(" DEBUG: getAllProductos() llamado - productos en memoria: ${productosFabrica.size}")
        productosFabrica.forEach { producto ->
            println(" DEBUG: Producto en memoria: ${producto.codigo} - ${producto.nombre}")
        }
        return flow {
            emit(productosFabrica.toList())
        }
    }
    
    fun getProductosDirectos(): List<ProductoFabrica> {
        println(" DEBUG: getProductosDirectos() llamado - productos en memoria: ${productosFabrica.size}")
        return productosFabrica.toList()
    }
    
    private suspend fun cargarProductosDesdeBaseDeDatos() {
        try {
            // Obtener productos de la base de datos usando el repositorio local disponible
            localRepository.getProductosByCategoria("OTROS").collect { productos ->
                println(" DEBUG: Cargados ${productos.size} productos desde base de datos")
                // Convertir Producto a ProductoFabrica (simplificado)
                productosFabrica.clear()
                productos.forEach { producto ->
                    val productoFabrica = ProductoFabrica(
                        codigo = producto.nombre,
                        nombre = producto.nombre,
                        categoria = producto.categoria,
                        promediosVenta = emptyMap() // Se llenaro cuando se recalculen los promedios
                    )
                    productosFabrica.add(productoFabrica)
                }
                println(" DEBUG: ${productosFabrica.size} productos cargados en memoria")
            }
        } catch (e: Exception) {
            println(" DEBUG: Error cargando productos desde base de datos: ${e.message}")
            e.printStackTrace()
        }
    }
    
    fun getProductosPorCategoria(categoria: String): Flow<List<ProductoFabrica>> {
        return flowOf(productosFabrica.filter { it.categoria == categoria })
    }
    
    fun actualizarStockProducto(codigo: String, stockActual: Int) {
        val index = productosFabrica.indexOfFirst { it.codigo == codigo }
        if (index != -1) {
            productosFabrica[index] = productosFabrica[index].copy(stockActual = stockActual)
        }
    }
    
    fun actualizarVentaHasta1530(codigo: String, venta: Int) {
        val index = productosFabrica.indexOfFirst { it.codigo == codigo }
        if (index != -1) {
            productosFabrica[index] = productosFabrica[index].copy(ventaHasta1530 = venta)
        }
    }
    
    fun actualizarPedidoFinal(codigo: String, pedido: Int) {
        val index = productosFabrica.indexOfFirst { it.codigo == codigo }
        if (index != -1) {
            productosFabrica[index] = productosFabrica[index].copy(pedidoFinal = pedido)
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
        productosFabrica.forEach { producto ->
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
