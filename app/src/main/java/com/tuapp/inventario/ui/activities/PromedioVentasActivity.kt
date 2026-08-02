package com.tuapp.inventario

import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.tuapp.inventario.utils.DateHelper
import java.util.*
import com.tuapp.inventario.repository.InventarioRepository
import com.tuapp.inventario.repository.LocalInventarioRepository
import com.tuapp.inventario.repository.FirebaseInventarioRepository
import com.tuapp.inventario.RegistroInventario
import org.json.JSONObject
import android.content.SharedPreferences
import kotlinx.coroutines.tasks.await

class PromedioVentasActivity : AppCompatActivity() {

    private lateinit var layoutPromedio: LinearLayout
    private lateinit var repository: InventarioRepository
    private lateinit var localRepository: LocalInventarioRepository
    private var localId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_promedio_ventas)

        // Inicializar repositorios
        repository = (application as InventarioApplication).repository
        localRepository = (application as InventarioApplication).localRepository
        
        // Obtener localId del usuario actual
        localId = obtenerLocalId()


        layoutPromedio = findViewById(R.id.layoutPromedio)

        calcularPromedioVentas()
    }

    private fun calcularPromedioVentas() {
        lifecycleScope.launch {
            try {
                Log.d("PROMEDIOS_CRIOLLITO", "üîç Iniciando cÛ°lculo de promedios de ventas")
                Log.d("PROMEDIOS_CRIOLLITO", "üîç LocalId obtenido: $localId")
                
                // Usar el repositorio local con localId
                localRepository.getAllRegistros(localId).collect { registros: List<RegistroInventario> ->
                    if (isFinishing || isDestroyed) return@collect
                    
                    Log.d("PROMEDIOS_CRIOLLITO", "üîç Registros obtenidos: ${registros.size}")
                    
                    if (registros.isEmpty()) {
                        Log.d("PROMEDIOS_CRIOLLITO", "üîç No hay registros locales, intentando descargar de Firebase")
                        // Intentar descargar datos de Firebase
                        try {
                            val firebaseRepo = FirebaseInventarioRepository(localId)
                            firebaseRepo.getAllRegistros().collect { registrosFirebase ->
                                if (isFinishing || isDestroyed) return@collect
                                
                                Log.d("PROMEDIOS_CRIOLLITO", "üîç Registros de Firebase obtenidos: ${registrosFirebase.size}")
                                if (registrosFirebase.isNotEmpty()) {
                                    // Guardar registros de Firebase localmente
                                    registrosFirebase.forEach { registro ->
                                        localRepository.saveRegistroInventario(localId, registro)
                                    }
                                    Log.d("PROMEDIOS_CRIOLLITO", "üîç Registros guardados localmente, procesando...")
                                    // Procesar los registros descargados
                                    procesarRegistros(registrosFirebase)
                                } else {
                                    Log.d("PROMEDIOS_CRIOLLITO", "üîç No hay registros en Firebase, mostrando mensaje sin datos")
                                    mostrarMensajeSinDatos()
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("PROMEDIOS_CRIOLLITO", "üîç Error descargando de Firebase: ${e.message}")
                            mostrarMensajeSinDatos()
                        }
                        return@collect
                    }
                    
                    // Procesar registros locales existentes
                    Log.d("PROMEDIOS_CRIOLLITO", "üîç Procesando registros locales existentes")
                    procesarRegistros(registros)
                }
            } catch (e: Exception) {
                mostrarMensajeError("Error calculando promedios: ${e.message}")
            }
        }
    }
    
    private suspend fun procesarRegistros(registros: List<RegistroInventario>) {
        try {
            // üöÄ OPTIMIZACIÛìN: Intentar cargar desde promedios generales guardados primero
            val promediosGuardados = cargarPromediosGeneralesGuardados(localId)
            
            if (promediosGuardados.isNotEmpty()) {
                Log.d("PromedioVentasActivity", "‚úÖ Promedios generales cargados desde cache: ${promediosGuardados.size} dÛ≠as")
                MainActivity.promediosReales = promediosGuardados
                MainActivity.promediosCargados = true
                mostrarPromedios(promediosGuardados)
                return
            }
            
            // üîÑ FALLBACK: Si no hay promedios guardados, calcular desde registros histÛ≥ricos
            Log.d("PromedioVentasActivity", "‚ö†Ô∏è No hay promedios guardados, calculando desde registros histÛ≥ricos...")
            Log.d("PROMEDIOS_CRIOLLITO", "üîç Procesando ${registros.size} registros")
            
            // Log especÛ≠fico para buscar CRIOLLITO en los registros
            val registrosConCriollito = registros.filter { registro ->
                registro.productos.any { producto -> producto.nombre.contains("CRIOLLITO") }
            }
            Log.d("PROMEDIOS_CRIOLLITO", "üîç Registros que contienen CRIOLLITO: ${registrosConCriollito.size}")
            
            if (registrosConCriollito.isNotEmpty()) {
                registrosConCriollito.forEach { registro ->
                    Log.d("PROMEDIOS_CRIOLLITO", "üîç Registro con CRIOLLITO - Fecha: ${registro.fecha}, Tipo: ${registro.tipo}")
                    registro.productos.filter { it.nombre.contains("CRIOLLITO") }.forEach { producto ->
                        Log.d("PROMEDIOS_CRIOLLITO", "üîç CRIOLLITO encontrado: ${producto.nombre} = ${producto.cantidad}")
                    }
                }
            } else {
                Log.d("PROMEDIOS_CRIOLLITO", "üîç NO SE ENCONTRÛì CRIOLLITO EN NINGÛöN REGISTRO")
            }
            
            val promediosPorDia = calcularPromediosPorDia(registros)
            Log.d("PROMEDIOS_CRIOLLITO", "üîç Promedios calculados para ${promediosPorDia.size} dÛ≠as")
            
            // üî• FIX: Guardar automÛ°ticamente en cache global para que Pedido a FÛ°brica los use
            MainActivity.promediosReales = promediosPorDia
            MainActivity.promediosCargados = true
            Log.d("PromedioVentasActivity", "‚úÖ Promedios guardados en cache global: ${promediosPorDia.size} dÛ≠as")
            
            mostrarPromedios(promediosPorDia)
        } catch (e: Exception) {
            Log.d("PROMEDIOS_CRIOLLITO", "üîç Error en procesarRegistros: ${e.message}")
            mostrarMensajeError("Error procesando registros: ${e.message}")
        }
    }
    
    /**
     * üöÄ OPTIMIZACIÛìN: Carga los promedios generales SOLO desde Firebase
     */
    private suspend fun cargarPromediosGeneralesGuardados(localId: String): Map<String, Map<String, Double>> {
        // Cargar SOLO desde Firebase (no SharedPreferences ni datos hardcodeados)
        val promediosFirebase = cargarPromediosGeneralesDesdeFirebase(localId)
        if (promediosFirebase.isNotEmpty()) {
            Log.d("PROMEDIO_GENERAL", "‚úÖ Promedios cargados desde Firebase: ${promediosFirebase.size} dÛ≠as")
            return promediosFirebase
        }
        
        Log.d("PROMEDIO_GENERAL", "‚ö†Ô∏è No hay promedios guardados en Firebase para local $localId")
        return emptyMap()
    }
    
    /**
     * Carga los promedios generales desde Firebase
     * ‚úÖ OPTIMIZACIÛìN: Usa cache compartido con PedidoFabricaActivity
     */
    private suspend fun cargarPromediosGeneralesDesdeFirebase(localId: String): Map<String, Map<String, Double>> {
        return try {
            // ‚úÖ OPTIMIZACIÛìN: Usar cache compartido si estÛ° disponible
            if (PedidoFabricaActivity.isPromediosCacheValid(localId) && PedidoFabricaActivity.cachePromedios != null) {
                Log.d("PROMEDIO_GENERAL", "üì¶ Promedios - Usando cache compartido (ahorra lecturas de Firebase)")
                return PedidoFabricaActivity.cachePromedios!!
            }
            
            Log.d("PROMEDIO_GENERAL", "üì¶ Promedios - Cache expirado o no existe, consultando Firebase...")
            val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
            val docRef = firestore.collection("locales")
                .document(localId)
                .collection("promedios_generales")
                .document("promedios")
            
            val document = docRef.get().await()
            
            if (!document.exists()) {
                Log.d("PROMEDIO_GENERAL", "‚ö†Ô∏è No hay promedios en Firebase para local $localId")
                return emptyMap()
            }
            
            val data = document.data ?: return emptyMap()
            val promediosFirebase = data["promedios"] as? Map<*, *> ?: return emptyMap()
            
            val promedios = mutableMapOf<String, MutableMap<String, Double>>()
            promediosFirebase.forEach { (dia, productosData) ->
                val diaStr = dia.toString()
                val productos = mutableMapOf<String, Double>()
                
                (productosData as? Map<*, *>)?.forEach { (producto, dataMap) ->
                    val productoStr = producto.toString()
                    val dataObj = dataMap as? Map<*, *>
                    val promedio = (dataObj?.get("promedio") as? Number)?.toDouble() ?: 0.0
                    productos[productoStr] = promedio
                }
                
                if (productos.isNotEmpty()) {
                    promedios[diaStr] = productos
                }
            }
            
            // ‚úÖ Guardar en cache compartido
            PedidoFabricaActivity.setPromediosCache(localId, promedios)
            
            Log.d("PROMEDIO_GENERAL", "‚úÖ Promedios parseados desde Firebase: ${promedios.size} dÛ≠as (cache actualizado)")
            promedios
            
        } catch (e: Exception) {
            Log.w("PROMEDIO_GENERAL", "‚ö†Ô∏è Error cargando promedios desde Firebase: ${e.message}")
            emptyMap()
        }
    }
    
    

    private fun calcularPromediosPorDia(registros: List<RegistroInventario>): Map<String, Map<String, Double>> {
        val promediosPorDia = mutableMapOf<String, MutableMap<String, Double>>()
        val diasSemana = listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        
        
        // Inicializar estructura
        diasSemana.forEach { dia ->
            promediosPorDia[dia] = mutableMapOf()
        }

        // üöÄ OPTIMIZACIÛìN: Usar registros de "Venta Diaria" pre-calculados si existen
        val registrosVentaDiaria = registros.filter { it.tipo == "Venta Diaria" }
        
        val ventasPorDia = mutableMapOf<String, MutableList<Map<String, Int>>>()
        
        if (registrosVentaDiaria.isNotEmpty()) {
            Log.d("PROMEDIOS", "‚úÖ Usando ventas diarias pre-calculadas: ${registrosVentaDiaria.size} registros")
            
            // Agrupar por dÛ≠a de la semana usando ventas pre-calculadas
            registrosVentaDiaria.forEach { registro ->
                val diaSemana = obtenerDiaSemana(registro.fecha)
                val ventasDia = registro.productos.associate { it.nombre to it.cantidad }
                if (ventasDia.isNotEmpty()) {
                    ventasPorDia.getOrPut(diaSemana) { mutableListOf() }.add(ventasDia)
                }
            }
        } else {
            // üîÑ FALLBACK: Si no hay ventas pre-calculadas, calcular desde registros histÛ≥ricos (mÛ©todo antiguo)
            Log.d("PROMEDIOS", "‚ö†Ô∏è No hay ventas pre-calculadas, usando mÛ©todo antiguo (mÛ°s lento)")
            
            // Obtener todas las fechas ordenadas
            val fechasOrdenadas = registros.map { it.fecha }.distinct().sorted()
            
            if (fechasOrdenadas.isEmpty()) {
                return promediosPorDia
            }
            
            // Agrupar por dÛ≠a de la semana
            fechasOrdenadas.forEach { fecha ->
                val diaSemana = obtenerDiaSemana(fecha)
                val ventasDia = calcularVentasDia(fecha, registros)
                if (ventasDia.isNotEmpty()) {
                    ventasPorDia.getOrPut(diaSemana) { mutableListOf() }.add(ventasDia)
                }
            }
        }


        // Calcular promedios
        ventasPorDia.forEach { (dia, listaVentas) ->
            if (listaVentas.isNotEmpty()) {
                // Obtener todos los productos Û∫nicos de todas las listas de ventas
                val todosLosProductos = listaVentas.flatMap { it.keys }.distinct()
                
                todosLosProductos.forEach { producto ->
                    // Filtrar solo los dÛ≠as donde este producto tiene ventas > 0
                    val ventasConDatos = listaVentas.mapNotNull { it[producto] }.filter { it > 0 }
                    
                    // Log especÛ≠fico para CRIOLLITO
                    if (producto.contains("CRIOLLITO")) {
                        Log.d("PROMEDIOS_CRIOLLITO", "üîç CRIOLLITO DEBUG - DÛ≠a: $dia")
                        Log.d("PROMEDIOS_CRIOLLITO", "üîç CRIOLLITO DEBUG - Total dÛ≠as en lista: ${listaVentas.size}")
                        Log.d("PROMEDIOS_CRIOLLITO", "üîç CRIOLLITO DEBUG - DÛ≠as con ventas > 0: ${ventasConDatos.size}")
                        Log.d("PROMEDIOS_CRIOLLITO", "üîç CRIOLLITO DEBUG - Ventas encontradas: $ventasConDatos")
                    }
                    
                    if (ventasConDatos.isNotEmpty()) {
                        val suma = ventasConDatos.sum()
                        val promedio = suma.toDouble() / ventasConDatos.size
                        promediosPorDia[dia]?.set(producto, promedio)
                        Log.d("PROMEDIOS_CRIOLLITO", "  $producto: suma=$suma, dÛ≠as con datos=${ventasConDatos.size}, promedio=$promedio")
                    } else {
                        // Si no hay dÛ≠as con ventas > 0, el promedio es 0
                        promediosPorDia[dia]?.set(producto, 0.0)
                        Log.d("PROMEDIOS_CRIOLLITO", "  $producto: sin ventas registradas, promedio=0.0")
                    }
                }
            }
        }

        return promediosPorDia
    }

    private fun calcularVentasDia(fecha: String, registros: List<RegistroInventario>): Map<String, Int> {
        val registrosFecha = registros.filter { it.fecha == fecha }
        val ingreso = registrosFecha.find { it.tipo == "Ingreso de MercaderÛ≠a" }?.productos ?: emptyList()
        val stockFinal = registrosFecha.find { it.tipo == "Stock Final" }?.productos ?: emptyList()

        // Obtener stock final del dÛ≠a anterior
        val fechaAnterior = obtenerFechaAnterior(fecha)
        val stockAnterior = registros.filter { it.fecha == fechaAnterior }
            .find { it.tipo == "Stock Final" }?.productos ?: emptyList()

        val ventas = mutableMapOf<String, Int>()

        // Debug: Log de informaciÛ≥n
        println("=== CALCULANDO VENTAS PARA FECHA: $fecha ===")
        println("Ingreso: ${ingreso.size} productos")
        println("Stock Final: ${stockFinal.size} productos")
        println("Stock Anterior ($fechaAnterior): ${stockAnterior.size} productos")
        
        // Log especÛ≠fico para domingo
        if (fecha == "2024-09-28") {
            println("Stock Final SÛ°bado (27/09): ${stockAnterior.size} productos")
            stockAnterior.forEach { producto ->
                println("  ${producto.nombre}: ${producto.cantidad}")
            }
            println("Ingreso Domingo (28/09): ${ingreso.size} productos")
            ingreso.forEach { producto ->
                println("  ${producto.nombre}: ${producto.cantidad}")
            }
            println("Stock Final Domingo (28/09): ${stockFinal.size} productos")
            stockFinal.forEach { producto ->
                println("  ${producto.nombre}: ${producto.cantidad}")
            }
        }

        // Si no hay stock final del dÛ≠a actual, no podemos calcular ventas
        if (stockFinal.isEmpty()) {
            println("No hay stock final para $fecha, no se pueden calcular ventas")
            return ventas
        }

        // Calcular ventas para cada producto del stock final
        stockFinal.forEach { productoStock ->
            val nombreProducto = productoStock.nombre
            val ingresoCantidad = ingreso.find { it.nombre == nombreProducto }?.cantidad ?: 0
            val stockFinalCantidad = productoStock.cantidad
            val stockAnteriorCantidad = stockAnterior.find { it.nombre == nombreProducto }?.cantidad ?: 0
            
            val venta = stockAnteriorCantidad + ingresoCantidad - stockFinalCantidad
            
            // Debug: Log de cada producto
            println("$nombreProducto: Stock Anterior=$stockAnteriorCantidad + Ingreso=$ingresoCantidad - Stock Final=$stockFinalCantidad = $venta")
            
            if (venta > 0) {
                ventas[nombreProducto] = venta
            }
        }

        println("Total ventas calculadas: ${ventas.size} productos")
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
            val calendar = DateHelper.getCalendar().apply { 
                time = date
                add(Calendar.DAY_OF_MONTH, -1)
            }
            formato.format(calendar.time)
        } catch (e: Exception) {
            fecha
        }
    }

    private fun mostrarPromedios(promediosPorDia: Map<String, Map<String, Double>>) {
        // Debug: Mostrar todos los promedios calculados
        println("=== MOSTRANDO PROMEDIOS ===")
        promediosPorDia.forEach { (dia, productos) ->
            println("DÛ≠a $dia: ${productos.size} productos")
            productos.forEach { (producto, promedio) ->
                if (promedio > 0) {
                    println("  $producto: $promedio")
                }
            }
            
            // Debug especÛ≠fico para empanadas
            if (dia == "LUNES") {
                val productosEmpanadas = listOf("JQ - JamÛ≥n y Queso", "PB - Pollo BBQ", "CS - Carne Suave", "PO - Pollo", "CP - Carne Picante", "HU - Humita", "ES - Espinaca", "CQ - Calabaza y Queso", "RJ - Roquefort y JamÛ≥n", "QC - Queso y Cebolla", "CB - Cerdo y Barbacoa", "CH - Cheeseburger")
                val empanadasLunes = productos.filterKeys { productosEmpanadas.contains(it) }
                empanadasLunes.forEach { (producto, promedio) ->
                    println("  $producto: $promedio")
                }
                val totalEmpanadas = empanadasLunes.values.sum()
                println("  TOTAL EMPANADAS LUNES: $totalEmpanadas")
            }
        }
        
        // Limpiar layout antes de mostrar
        layoutPromedio.removeAllViews()
        
        // Obtener informaciÛ≥n del local actual
        val localNombre = obtenerLocalNombre()
        
        // Indicador de local
        val indicadorLocal = TextView(this).apply {
            text = "LOCAL: $localNombre"
            textSize = 16f
            setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 10, 0, 10)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        layoutPromedio.addView(indicadorLocal)
        
        // TÛ≠tulo principal
        val titulo = TextView(this).apply {
            text = "PROMEDIO DE VENTA POR DÛçA"
            textSize = 24f
            setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 20, 0, 30)
        }
        layoutPromedio.addView(titulo)

        // Crear tabla
        crearTablaPromedios(promediosPorDia)
    }

    private fun crearTablaPromedios(promediosPorDia: Map<String, Map<String, Double>>) {
        val diasSemana = listOf("LUN", "MAR", "MIÛâ", "JUE", "VIE", "SÛÅB", "DOM")
        val diasCompletos = listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        
        // Agrupar productos por categorÛ≠a
        val productosPorCategoria = agruparProductosPorCategoria()
        
        // Crear encabezado de dÛ≠as
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(resources.getColor(android.R.color.holo_blue_light, null))
        }
        
        val tipoHeader = TextView(this).apply {
            text = "TIPO"
            textSize = 12f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setPadding(4, 8, 4, 8)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }
        headerLayout.addView(tipoHeader)
        
        diasSemana.forEach { dia ->
            val diaHeader = TextView(this).apply {
                text = dia
                textSize = 10f
                setTextColor(resources.getColor(R.color.web_text_light, null))
                setPadding(1, 8, 1, 8)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
            }
            headerLayout.addView(diaHeader)
        }
        layoutPromedio.addView(headerLayout)

        // Mostrar productos por categorÛ≠a
        productosPorCategoria.forEach { (categoria, productos) ->
            mostrarCategoria(categoria, productos, diasSemana, diasCompletos, promediosPorDia)
            
            // Agregar total de empanadas despuÛ©s de la secciÛ≥n EMPANADAS
            if (categoria == "EMPANADAS") {
                mostrarTotalEmpanadas(diasSemana, diasCompletos, promediosPorDia)
            }
        }
    }


    private fun mostrarTotalEmpanadas(diasSemana: List<String>, diasCompletos: List<String>, promediosPorDia: Map<String, Map<String, Double>>) {
        val totalEmpanadasLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(resources.getColor(android.R.color.holo_green_light, null))
        }
        
        val totalEmpanadasLabel = TextView(this).apply {
            text = "TOTAL"
            textSize = 12f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setPadding(4, 8, 4, 8)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }
        totalEmpanadasLayout.addView(totalEmpanadasLabel)
        
        // Calcular total de empanadas por dÛ≠a
        val productosEmpanadas = listOf("JQ - JamÛ≥n y Queso", "PB - Pollo BBQ", "CS - Carne Suave", "PO - Pollo", "CP - Carne Picante", "HU - Humita", "ES - Espinaca", "CQ - Calabaza y Queso", "RJ - Roquefort y JamÛ≥n", "QC - Queso y Cebolla", "CB - Cerdo y Barbacoa", "CH - Cheeseburger")
        
        diasSemana.forEachIndexed { index, _ ->
            val diaCompleto = diasCompletos[index]
            val totalDia = promediosPorDia[diaCompleto]?.filterKeys { producto -> 
                productosEmpanadas.contains(producto)
            }?.values?.sum() ?: 0.0
            
            // Debug: Log para verificar el cÛ°lculo
            val productosEncontrados = promediosPorDia[diaCompleto]?.filterKeys { producto -> 
                productosEmpanadas.contains(producto)
            }
            productosEncontrados?.forEach { (producto, valor) ->
                println("  $producto: $valor")
            }
            println("  Total calculado: $totalDia")
            
            val totalText = TextView(this).apply {
                text = String.format("%.0f", totalDia)
                textSize = 10f
                setTextColor(resources.getColor(R.color.web_text_light, null))
                setPadding(1, 8, 1, 8)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
            }
            totalEmpanadasLayout.addView(totalText)
        }
        layoutPromedio.addView(totalEmpanadasLayout)
    }

    private fun mostrarCategoria(categoria: String, productos: List<String>, diasSemana: List<String>, diasCompletos: List<String>, promediosPorDia: Map<String, Map<String, Double>>) {
        // Header de categorÛ≠a
        val categoriaHeader = TextView(this).apply {
            text = categoria
            textSize = 16f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setPadding(8, 8, 8, 8)
            setBackgroundColor(resources.getColor(android.R.color.holo_orange_light, null))
        }
        layoutPromedio.addView(categoriaHeader)

        // Productos de la categorÛ≠a
        productos.forEach { producto ->
            val productoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 4, 8, 4)
                setBackgroundColor(resources.getColor(R.color.surface_color, null))
            }
            
            val productoText = TextView(this).apply {
                text = obtenerAbreviacion(producto)
                textSize = 10f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                setPadding(4, 4, 4, 4)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            }
            productoLayout.addView(productoText)
            
            diasSemana.forEachIndexed { index, dia ->
                // Mapear dÛ≠a abreviado a dÛ≠a completo
                val diaCompleto = diasCompletos[index]
                
                // Buscar el promedio usando el dÛ≠a completo
                val promedio = promediosPorDia[diaCompleto]?.get(producto) ?: 0.0
                
                // Debug: Log para verificar quÛ© se estÛ° buscando
                if (dia == "DOM" && promedio > 0) {
                }
                
                val promedioText = TextView(this).apply {
                    text = String.format("%.0f", promedio)
                    textSize = 10f
                    setTextColor(resources.getColor(R.color.web_text_primary, null))
                    setPadding(1, 4, 1, 4)
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
                }
                productoLayout.addView(promedioText)
            }
            layoutPromedio.addView(productoLayout)
        }
    }

    private fun agruparProductosPorCategoria(): Map<String, List<String>> {
        return mapOf(
            "EMPANADAS" to listOf(
                "JQ - JamÛ≥n y Queso", "PB - Pollo BBQ", "CS - Carne Suave", "PO - Pollo", 
                "CP - Carne Picante", "HU - Humita", "ES - Espinaca", "CQ - Calabaza y Queso", 
                "RJ - Roquefort y JamÛ≥n", "QC - Queso y Cebolla", "CB - Cerdo y Barbacoa", "CH - Cheeseburger"
            ),
            "PIZZAS" to listOf("MUZZARE - Muzzarella", "JAMON - JamÛ≥n", "PEPPERO - Pepperoni"),
            "PASTELITOS" to listOf("BATATA - Batata", "MEMBRIL - Membrillo"),
            "OTROS" to listOf("DULCES - Dulces", "SALADO - Salado", "CHIPA - Chipa", "CRIOLLITO - Criollito")
        )
    }
    
    private fun obtenerAbreviacion(nombreCompleto: String): String {
        return when {
            nombreCompleto.startsWith("JQ") -> "JQ"
            nombreCompleto.startsWith("PB") -> "PB"
            nombreCompleto.startsWith("CS") -> "CS"
            nombreCompleto.startsWith("PO") -> "PO"
            nombreCompleto.startsWith("CP") -> "CP"
            nombreCompleto.startsWith("HU") -> "HU"
            nombreCompleto.startsWith("ES") -> "ES"
            nombreCompleto.startsWith("CQ") -> "CQ"
            nombreCompleto.startsWith("RJ") -> "RJ"
            nombreCompleto.startsWith("QC") -> "QC"
            nombreCompleto.startsWith("CB") -> "CB"
            nombreCompleto.startsWith("CH -") -> "CH"
            nombreCompleto.startsWith("MUZZARE") -> "MUZZA"
            nombreCompleto.startsWith("JAMON") -> "JAMON"
            nombreCompleto.startsWith("PEPPERO") -> "PEPPER"
            nombreCompleto.startsWith("BATATA") -> "BATATA"
            nombreCompleto.startsWith("MEMBRIL") -> "MEMBRIL"
            nombreCompleto.startsWith("DULCES") -> "DULCES"
            nombreCompleto.startsWith("SALADO") -> "SALADO"
            nombreCompleto.startsWith("CHIPA") -> "CHIPA"
            nombreCompleto.startsWith("CRIOLLITO") -> "CRIOLLITO"
            else -> nombreCompleto
        }
    }
    
    private fun obtenerLocalId(): String {
        return try {
            val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
            val localId = prefs.getString("localId", "")
            if (localId.isNullOrEmpty()) {
                println("No se encontrÛ≥ localId en SharedPreferences")
                "default" // Fallback
            } else {
                println("LocalId obtenido: $localId")
                localId
            }
        } catch (e: Exception) {
            println("Error obteniendo localId: ${e.message}")
            "default" // Fallback
        }
    }
    
    private fun obtenerLocalNombre(): String {
        return try {
            val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
            val localNombre = prefs.getString("localNombre", "")
            if (localNombre.isNullOrEmpty()) {
                Log.e("PromedioVentasActivity", "No se encontrÛ≥ localNombre en SharedPreferences")
                "NO SELECCIONADO"
            } else {
                Log.d("PromedioVentasActivity", "LocalNombre obtenido: $localNombre")
                localNombre
            }
        } catch (e: Exception) {
            Log.e("PromedioVentasActivity", "Error obteniendo localNombre", e)
            "NO SELECCIONADO"
        }
    }
    
    private fun mostrarMensajeSinDatos() {
        runOnUiThread {
            layoutPromedio.removeAllViews()
            
            val mensajeLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(32, 64, 32, 64)
            }
            
            val titulo = TextView(this).apply {
                text = "üìä Promedio de Ventas"
                textSize = 20f
                setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
                gravity = android.view.Gravity.CENTER
                setPadding(16, 16, 16, 16)
            }
            
            val mensaje = TextView(this).apply {
                text = "No hay datos de ventas para este local.\n\nLos promedios aparecerÛ°n cuando tengas registros de 'Stock Final'."
                textSize = 16f
                setTextColor(resources.getColor(android.R.color.darker_gray, null))
                gravity = android.view.Gravity.CENTER
                setPadding(16, 16, 16, 16)
            }
            
            mensajeLayout.addView(titulo)
            mensajeLayout.addView(mensaje)
            layoutPromedio.addView(mensajeLayout)
        }
    }
    
    private fun mostrarMensajeError(mensaje: String) {
        runOnUiThread {
            layoutPromedio.removeAllViews()
            
            val mensajeLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(32, 64, 32, 64)
            }
            
            val titulo = TextView(this).apply {
                text = "‚ùå Error"
                textSize = 20f
                setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                gravity = android.view.Gravity.CENTER
                setPadding(16, 16, 16, 16)
            }
            
            val mensajeError = TextView(this).apply {
                text = mensaje
                textSize = 16f
                setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                gravity = android.view.Gravity.CENTER
                setPadding(16, 16, 16, 16)
            }
            
            mensajeLayout.addView(titulo)
            mensajeLayout.addView(mensajeError)
            layoutPromedio.addView(mensajeLayout)
        }
    }
}
