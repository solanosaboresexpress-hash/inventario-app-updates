package com.tuapp.inventario

import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.tuapp.inventario.repository.InventarioRepository
import com.tuapp.inventario.repository.LocalInventarioRepository
import com.tuapp.inventario.repository.FirebaseInventarioRepository
import com.tuapp.inventario.RegistroInventario
import com.tuapp.inventario.utils.DateHelper
import java.util.*

class VentaVolumenActivity : AppCompatActivity() {

    private lateinit var layoutVentaVolumen: LinearLayout
    private lateinit var switchVista: SwitchCompat
    private lateinit var repository: InventarioRepository
    private lateinit var localRepository: LocalInventarioRepository
    private var localId: String = ""
    private var registrosCache: List<RegistroInventario> = emptyList()
    private var registrosFiltrados: List<RegistroInventario> = emptyList()
    private var periodoActual: String = "General"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_venta_volumen)

        // Inicializar repositorios
        repository = (application as InventarioApplication).repository
        localRepository = (application as InventarioApplication).localRepository
        
        // Obtener localId del usuario actual
        localId = obtenerLocalId()

        layoutVentaVolumen = findViewById(R.id.layoutVentaVolumen)
        switchVista = findViewById(R.id.switchVista)

        // Configurar listener del switch
        switchVista.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                mostrarMenuPromedios()
            } else {
                // Mostrar vista de tabla con los datos filtrados actuales
                val datosAMostrar = if (registrosFiltrados.isNotEmpty()) registrosFiltrados else registrosCache
                mostrarVentaVolumen(datosAMostrar)
            }
        }

        cargarDatosVentaVolumen()
    }
    
    private fun obtenerLocalId(): String {
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        return prefs.getString("localId", "default") ?: "default"
    }
    
    /**
     * Verifica si el dispositivo está en modo oscuro
     */
    private fun isDarkMode(): Boolean {
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun cargarDatosVentaVolumen() {
        lifecycleScope.launch {
            try {
                Log.d("VentaVolumenActivity", "Cargando datos de venta por volumen...")
                
                // Usar el repositorio local con localId
                localRepository.getAllRegistros(localId).collect { registros: List<RegistroInventario> ->
                    if (isFinishing || isDestroyed) return@collect
                    
                    Log.d("VentaVolumenActivity", "Registros obtenidos: ${registros.size}")
                    
                    if (registros.isEmpty()) {
                        Log.d("VentaVolumenActivity", "No hay registros locales, intentando descargar de Firebase")
                        // Intentar descargar datos de Firebase
                        try {
                            val firebaseRepo = FirebaseInventarioRepository(localId)
                            firebaseRepo.getAllRegistros().collect { registrosFirebase ->
                                if (isFinishing || isDestroyed) return@collect
                                
                                Log.d("VentaVolumenActivity", "Registros de Firebase obtenidos: ${registrosFirebase.size}")
                                if (registrosFirebase.isNotEmpty()) {
                                    // Guardar registros de Firebase localmente
                                    registrosFirebase.forEach { registro ->
                                        localRepository.saveRegistroInventario(localId, registro)
                                    }
                                    Log.d("VentaVolumenActivity", "Registros guardados localmente, procesando...")
                                    registrosCache = registrosFirebase
                                    registrosFiltrados = registrosFirebase
                                    mostrarVentaVolumen(registrosFirebase)
                                } else {
                                    Log.d("VentaVolumenActivity", "No hay registros en Firebase, mostrando mensaje sin datos")
                                    mostrarMensajeSinDatos()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("VentaVolumenActivity", "Error descargando de Firebase: ${e.message}")
                            mostrarMensajeSinDatos()
                        }
                        return@collect
                    }
                    
                    // Procesar registros locales existentes
                    Log.d("VentaVolumenActivity", "Procesando registros locales existentes")
                    registrosCache = registros
                    registrosFiltrados = registros
                    mostrarVentaVolumen(registros)
                }
                
            } catch (e: Exception) {
                Log.e("VentaVolumenActivity", "Error cargando datos de venta por volumen", e)
                mostrarMensajeError("Error cargando datos: ${e.message}")
            }
        }
    }
    
    private fun mostrarVentaVolumen(registros: List<RegistroInventario>) {
        layoutVentaVolumen.removeAllViews()
        
        // Mostrar información del período actual
        val isDark = isDarkMode()
        val infoPeriodo = TextView(this).apply {
            text = "PROMEDIO: $periodoActual"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.brand_gold, null))
            gravity = android.view.Gravity.CENTER
            setPadding(16, 16, 16, 8)
        }
        layoutVentaVolumen.addView(infoPeriodo)
        
        // Obtener información del local actual
        val localNombre = obtenerLocalNombre()
        
        // Indicador de local
        val indicadorLocal = TextView(this).apply {
            text = "LOCAL: $localNombre"
            textSize = 16f
            setTextColor(if (isDark) {
                android.graphics.Color.parseColor("#4A90E2")
            } else {
                resources.getColor(android.R.color.holo_blue_dark, null)
            })
            gravity = android.view.Gravity.CENTER
            setPadding(0, 10, 0, 10)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        layoutVentaVolumen.addView(indicadorLocal)
        

        // Calcular promedios de ventas de todos los días con registros
        Log.d("CRIOLLITO_DEBUG", "🔍 PROMEDIO GENERAL - Llamando a calcularPromediosPorDia con ${registros.size} registros")
        val promediosPorDia = calcularPromediosPorDia(registros)
        Log.d("CRIOLLITO_DEBUG", "🔍 PROMEDIO GENERAL - Resultado de calcularPromediosPorDia: ${promediosPorDia.size} días")
        
        // Crear tabla con el mismo estilo que PromedioVentasActivity
        crearTablaPromedios(promediosPorDia)
    }
    
    private fun calcularPromediosPorDia(registros: List<RegistroInventario>): Map<String, Map<String, Double>> {
        Log.d("CRIOLLITO_DEBUG", "🔍 Iniciando cálculo de promedios en VentaVolumen")
        Log.d("CRIOLLITO_DEBUG", "🔍 Total registros: ${registros.size}")
        
        // Log específico para buscar CRIOLLITO en los registros
        val registrosConCriollito = registros.filter { registro ->
            registro.productos.any { producto -> producto.nombre.contains("CRIOLLITO") }
        }
        Log.d("CRIOLLITO_DEBUG", "🔍 Registros que contienen CRIOLLITO: ${registrosConCriollito.size}")
        
        if (registrosConCriollito.isNotEmpty()) {
            registrosConCriollito.forEach { registro ->
                Log.d("CRIOLLITO_DEBUG", "🔍 Registro con CRIOLLITO - Fecha: ${registro.fecha}, Tipo: ${registro.tipo}")
                registro.productos.filter { it.nombre.contains("CRIOLLITO") }.forEach { producto ->
                    Log.d("CRIOLLITO_DEBUG", "🔍 CRIOLLITO encontrado: ${producto.nombre} = ${producto.cantidad}")
                }
            }
        } else {
            Log.d("CRIOLLITO_DEBUG", "🔍 NO SE ENCONTRÓ CRIOLLITO EN NINGÚN REGISTRO")
        }
        
        val promediosPorDia = mutableMapOf<String, MutableMap<String, Double>>()
        val diasSemana = listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        
        Log.d("CRIOLLITO_DEBUG", "🔍 Inicializando estructura de promedios")
        
        // Inicializar estructura
        diasSemana.forEach { dia ->
            promediosPorDia[dia] = mutableMapOf()
        }

        // Obtener todas las fechas ordenadas
        val fechasOrdenadas = registros.map { it.fecha }.distinct().sorted()
        Log.d("CRIOLLITO_DEBUG", "🔍 Fechas encontradas: ${fechasOrdenadas.size} - $fechasOrdenadas")
        
        if (fechasOrdenadas.isEmpty()) {
            return promediosPorDia
        }

        // Agrupar por día de la semana
        val ventasPorDia = mutableMapOf<String, MutableList<Map<String, Int>>>()
        Log.d("CRIOLLITO_DEBUG", "🔍 Iniciando agrupación por día de la semana")
        
        fechasOrdenadas.forEach { fecha ->
            val diaSemana = obtenerDiaSemana(fecha)
            val ventasDia = calcularVentasDia(fecha, registros)
            Log.d("CRIOLLITO_DEBUG", "🔍 Procesando fecha: $fecha -> $diaSemana, ventas: ${ventasDia.size}")
            if (ventasDia.isNotEmpty()) {
                ventasPorDia.getOrPut(diaSemana) { mutableListOf() }.add(ventasDia)
            }
        }

        // Calcular promedios
        Log.d("CRIOLLITO_DEBUG", "🔍 Iniciando cálculo de promedios - Días con datos: ${ventasPorDia.size}")
        ventasPorDia.forEach { (dia, listaVentas) ->
            Log.d("CRIOLLITO_DEBUG", "🔍 Procesando día: $dia con ${listaVentas.size} registros")
            if (listaVentas.isNotEmpty()) {
                // Obtener TODOS los productos únicos de TODOS los registros de este día
                val productos = listaVentas.flatMap { it.keys }.distinct()
                Log.d("CRIOLLITO_DEBUG", "🔍 Productos encontrados en $dia: ${productos.size} - $productos")
                productos.forEach { producto ->
                    // Filtrar solo los días donde este producto tiene ventas > 0
                    val ventasConDatos = listaVentas.mapNotNull { it[producto] }.filter { it > 0 }
                    
                       // Log específico para CRIOLLITO
                       if (producto.contains("CRIOLLITO")) {
                           Log.d("CRIOLLITO_DEBUG", "🔍 CRIOLLITO DEBUG - Día: $dia")
                           Log.d("CRIOLLITO_DEBUG", "🔍 CRIOLLITO DEBUG - Total días en lista: ${listaVentas.size}")
                           Log.d("CRIOLLITO_DEBUG", "🔍 CRIOLLITO DEBUG - Días con ventas > 0: ${ventasConDatos.size}")
                           Log.d("CRIOLLITO_DEBUG", "🔍 CRIOLLITO DEBUG - Ventas encontradas: $ventasConDatos")
                       }
                    
                    if (ventasConDatos.isNotEmpty()) {
                        val suma = ventasConDatos.sum()
                        val promedio = suma.toDouble() / ventasConDatos.size
                        promediosPorDia[dia]?.set(producto, promedio)
                        
                           if (producto.contains("CRIOLLITO")) {
                               Log.d("CRIOLLITO_DEBUG", "🔍 CRIOLLITO DEBUG - Suma total: $suma")
                               Log.d("CRIOLLITO_DEBUG", "🔍 CRIOLLITO DEBUG - Promedio calculado: $promedio")
                           }
                    } else {
                        // Si no hay días con ventas > 0, el promedio es 0
                        promediosPorDia[dia]?.set(producto, 0.0)
                        
                        if (producto.contains("CRIOLLITO")) {
                            Log.d("CRIOLLITO_DEBUG", "🔍 CRIOLLITO DEBUG - Sin ventas registradas, promedio=0.0")
                        }
                    }
                }
            }
        }

        return promediosPorDia
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
    
    
    private fun calcularVentasDia(fecha: String, registros: List<RegistroInventario>): Map<String, Int> {
        val registrosFecha = registros.filter { it.fecha == fecha }
        val ingreso = registrosFecha.find { it.tipo == "Ingreso de Mercadería" }?.productos ?: emptyList()
        val stockFinal = registrosFecha.find { it.tipo == "Stock Final" }?.productos ?: emptyList()

        // Obtener stock final del día anterior - BUSCAR EN TODOS LOS REGISTROS, NO SOLO EN LOS FILTRADOS
        val fechaAnterior = obtenerFechaAnterior(fecha)
        val stockAnterior = registrosCache.filter { it.fecha == fechaAnterior }
            .find { it.tipo == "Stock Final" }?.productos ?: emptyList()

        val ventas = mutableMapOf<String, Int>()

        // Si no hay stock final del día actual, no podemos calcular ventas
        if (stockFinal.isEmpty()) {
            return ventas
        }

        // Calcular ventas para cada producto del stock final
        stockFinal.forEach { productoStock ->
            val nombreProducto = productoStock.nombre
            val ingresoCantidad = ingreso.find { it.nombre == nombreProducto }?.cantidad ?: 0
            val stockFinalCantidad = productoStock.cantidad
            val stockAnteriorCantidad = stockAnterior.find { it.nombre == nombreProducto }?.cantidad ?: 0
            
            val venta = stockAnteriorCantidad + ingresoCantidad - stockFinalCantidad
            
            // Agregar TODOS los productos al map, incluso si la venta es 0
            // Esto asegura que nuevos productos como CRIOLLITO aparezcan en la lista
            ventas[nombreProducto] = if (venta > 0) venta else 0
        }

        return ventas
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
    
    private fun crearTablaPromedios(promediosPorDia: Map<String, Map<String, Double>>) {
        val diasSemana = listOf("LUN", "MAR", "MIÉ", "JUE", "VIE", "SÁB", "DOM")
        val diasCompletos = listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        
        // Agrupar productos por categoría
        val productosPorCategoria = agruparProductosPorCategoria()
        
        // Crear encabezado de días
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(resources.getColor(R.color.brand_gold, null))
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
        layoutVentaVolumen.addView(headerLayout)

        // Mostrar productos por categoría
        productosPorCategoria.forEach { (categoria, productos) ->
            mostrarCategoria(categoria, productos, diasSemana, diasCompletos, promediosPorDia)
            
            // Agregar total de empanadas después de la sección EMPANADAS
            if (categoria == "EMPANADAS") {
                mostrarTotalEmpanadas(diasSemana, diasCompletos, promediosPorDia)
            }
        }
    }
    
    private fun mostrarTotalEmpanadas(diasSemana: List<String>, diasCompletos: List<String>, promediosPorDia: Map<String, Map<String, Double>>) {
        val totalEmpanadasLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 12, 12)
            setBackgroundResource(R.drawable.category_empanadas_header)
        }
        
        val totalEmpanadasLabel = TextView(this).apply {
            text = "TOTAL"
            textSize = 12f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setPadding(4, 8, 4, 8)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }
        totalEmpanadasLayout.addView(totalEmpanadasLabel)
        
        // Calcular total de empanadas por día
        val productosEmpanadas = listOf("JQ - Jamón y Queso", "PB - Pollo BBQ", "CS - Carne Suave", "PO - Pollo", "CP - Carne Picante", "HU - Humita", "ES - Espinaca", "CQ - Calabaza y Queso", "RJ - Roquefort y Jamón", "QC - Queso y Cebolla", "CB - Cerdo y Barbacoa", "CH - Cheeseburger")
        
        diasSemana.forEachIndexed { index, _ ->
            val diaCompleto = diasCompletos[index]
            val totalDia = promediosPorDia[diaCompleto]?.filterKeys { producto -> 
                productosEmpanadas.contains(producto)
            }?.values?.sum() ?: 0.0
            
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
        layoutVentaVolumen.addView(totalEmpanadasLayout)
    }

    private fun mostrarCategoria(categoria: String, productos: List<String>, diasSemana: List<String>, diasCompletos: List<String>, promediosPorDia: Map<String, Map<String, Double>>) {
        // Definir colores según modo oscuro
        val isDark = isDarkMode()
        val textColor = if (isDark) {
            android.graphics.Color.WHITE // Texto blanco en modo oscuro
        } else {
            android.graphics.Color.BLACK // Texto negro en modo claro
        }
        val contentBackgroundColor = if (isDark) {
            android.graphics.Color.parseColor("#1E1E1E") // Gris oscuro
        } else {
            android.graphics.Color.parseColor("#FFF5F5") // Blanco rosado (original)
        }
        val itemBackgroundColor = if (isDark) {
            android.graphics.Color.parseColor("#2D2D2D") // Gris más oscuro para items
        } else {
            android.graphics.Color.WHITE
        }
        val itemBorderColor = if (isDark) {
            android.graphics.Color.parseColor("#404040") // Borde gris en modo oscuro
        } else {
            android.graphics.Color.parseColor("#E0E0E0") // Borde gris claro en modo claro
        }
        
        // Header de categoría con icono y estilo redondeado
        val categoriaHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 12, 12)
            gravity = android.view.Gravity.CENTER_VERTICAL
            
            // Aplicar el drawable específico según la categoría
        val headerBackground = when (categoria) {
                "EMPANADAS" -> R.drawable.category_empanadas_header
                "PIZZAS" -> R.drawable.category_pizzas_header
                "PASTELITOS" -> R.drawable.category_pastelitos_header
                "OTROS" -> R.drawable.category_otros_header
            else -> R.drawable.category_header_background
        }
            setBackgroundResource(headerBackground)
        }
        
        // Icono de categoría
        val iconoCategoria = TextView(this).apply {
            text = when (categoria) {
                "EMPANADAS" -> "🥟"
                "PIZZAS" -> "🍕"
                "PASTELITOS" -> "🥧"
                "OTROS" -> "🥐"
            else -> "📦"
        }
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
                marginEnd = 12
            }
        }
        
        val categoriaText = TextView(this).apply {
            text = categoria
            textSize = 18f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        categoriaHeader.addView(iconoCategoria)
        categoriaHeader.addView(categoriaText)
        layoutVentaVolumen.addView(categoriaHeader)

        // Contenedor de productos con fondo dinámico
        val productosLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(contentBackgroundColor)
            setPadding(8, 8, 8, 8)
        }

        // Productos de la categoría con fondo redondeado
        productos.forEach { producto ->
            // Crear drawable con borde para la fila
            val filaDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(itemBackgroundColor)
                cornerRadius = 6f * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt(), itemBorderColor)
            }
            
            val productoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 6, 8, 6)
                background = filaDrawable
            }
            
            val productoText = TextView(this).apply {
                text = obtenerAbreviacion(producto)
                textSize = 10f
                setTextColor(textColor)
                setPadding(4, 4, 4, 4)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            }
            productoLayout.addView(productoText)
            
            diasSemana.forEachIndexed { index, _ ->
                // Mapear día abreviado a día completo
                val diaCompleto = diasCompletos[index]
                
                // Buscar el promedio usando el día completo
                val promedio = promediosPorDia[diaCompleto]?.get(producto) ?: 0.0
                
                val promedioText = TextView(this).apply {
                    text = String.format("%.0f", promedio)
                    textSize = 10f
                    setTextColor(textColor)
                    setPadding(1, 4, 1, 4)
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
                }
                productoLayout.addView(promedioText)
            }
            productosLayout.addView(productoLayout)
        }
        
        layoutVentaVolumen.addView(productosLayout)
    }
    
    private fun agruparProductosPorCategoria(): Map<String, List<String>> {
        return mapOf(
            "EMPANADAS" to listOf(
                "JQ - Jamón y Queso", "PB - Pollo BBQ", "CS - Carne Suave", "PO - Pollo", 
                "CP - Carne Picante", "HU - Humita", "ES - Espinaca", "CQ - Calabaza y Queso", 
                "RJ - Roquefort y Jamón", "QC - Queso y Cebolla", "CB - Cerdo y Barbacoa", "CH - Cheeseburger"
            ),
            "PIZZAS" to listOf("MUZZARE - Muzzarella", "JAMON - Jamón", "PEPPERO - Pepperoni"),
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
    
    private fun obtenerLocalNombre(): String {
        return try {
            val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
            val localNombre = prefs.getString("localNombre", "")
            if (localNombre.isNullOrEmpty()) {
                Log.e("VentaVolumenActivity", "No se encontró localNombre en SharedPreferences")
                "NO SELECCIONADO"
            } else {
                Log.d("VentaVolumenActivity", "LocalNombre obtenido: $localNombre")
                localNombre
            }
        } catch (e: Exception) {
            Log.e("VentaVolumenActivity", "Error obteniendo localNombre", e)
            "NO SELECCIONADO"
        }
    }
    
    private fun mostrarMensajeSinDatos() {
        runOnUiThread {
            layoutVentaVolumen.removeAllViews()
            
            val isDark = isDarkMode()
            val secondaryTextColor = if (isDark) {
                android.graphics.Color.parseColor("#CCCCCC")
            } else {
                android.graphics.Color.parseColor("#666666")
            }
            
            val mensajeLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(32, 64, 32, 64)
            }
            
            val titulo = TextView(this).apply {
                text = "📊 Venta por Volumen"
                textSize = 20f
                setTextColor(if (isDark) {
                    android.graphics.Color.parseColor("#4A90E2")
                } else {
                    resources.getColor(android.R.color.holo_blue_dark, null)
                })
                gravity = android.view.Gravity.CENTER
                setPadding(16, 16, 16, 16)
            }
            
            val mensaje = TextView(this).apply {
                text = "No hay datos de ventas para este local.\n\nLos datos aparecerán cuando tengas registros de 'Stock Final'."
                textSize = 16f
                setTextColor(secondaryTextColor)
                gravity = android.view.Gravity.CENTER
                setPadding(16, 16, 16, 16)
            }
            
            mensajeLayout.addView(titulo)
            mensajeLayout.addView(mensaje)
            layoutVentaVolumen.addView(mensajeLayout)
        }
    }
    
    private fun mostrarMensajeError(mensaje: String) {
        runOnUiThread {
            layoutVentaVolumen.removeAllViews()
            
            val isDark = isDarkMode()
            val secondaryTextColor = if (isDark) {
                android.graphics.Color.parseColor("#CCCCCC")
            } else {
                android.graphics.Color.parseColor("#666666")
            }
            
            val errorLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(32, 64, 32, 64)
            }
            
            val titulo = TextView(this).apply {
                text = "❌ Error"
                textSize = 20f
                setTextColor(resources.getColor(R.color.status_error, null))
                gravity = android.view.Gravity.CENTER
                setPadding(16, 16, 16, 16)
            }
            
            val errorText = TextView(this).apply {
                text = mensaje
                textSize = 16f
                setTextColor(secondaryTextColor)
                gravity = android.view.Gravity.CENTER
                setPadding(16, 16, 16, 16)
            }
            
            errorLayout.addView(titulo)
            errorLayout.addView(errorText)
            layoutVentaVolumen.addView(errorLayout)
        }
    }
    
    private fun formatearDiaCorto(fecha: String): String {
        return try {
            val formatoEntrada = DateHelper.getDateFormat("yyyy-MM-dd")
            val formatoSalida = DateHelper.getDateFormat("EEE\ndd/MM", Locale("es", "ES"))
            val fechaObj = formatoEntrada.parse(fecha)
            formatoSalida.format(fechaObj ?: Date())
        } catch (e: Exception) {
            fecha
        }
    }
    
    /**
     * Muestra el menú de promedios con opciones
     */
    private fun mostrarMenuPromedios() {
        layoutVentaVolumen.removeAllViews()
        
        // Título
        val titulo = TextView(this).apply {
            text = "📊 MENÚ DE PROMEDIOS"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.brand_gold, null))
            gravity = android.view.Gravity.CENTER
            setPadding(16, 24, 16, 24)
        }
        layoutVentaVolumen.addView(titulo)
        
        // Descripción
        val isDark = isDarkMode()
        val descripcion = TextView(this).apply {
            text = "Selecciona una opción para ver los promedios de ventas"
            textSize = 14f
            setTextColor(if (isDark) {
                android.graphics.Color.parseColor("#CCCCCC")
            } else {
                resources.getColor(android.R.color.darker_gray, null)
            })
            gravity = android.view.Gravity.CENTER
            setPadding(16, 0, 16, 24)
        }
        layoutVentaVolumen.addView(descripcion)
        
        // Botón: Promedio General
        val btnPromedioGeneral = Button(this).apply {
            text = "📈 PROMEDIO GENERAL"
            textSize = 16f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setBackgroundColor(resources.getColor(R.color.brand_gold, null))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 8, 16, 8)
            }
            setOnClickListener {
                mostrarPromedioGeneral()
            }
        }
        layoutVentaVolumen.addView(btnPromedioGeneral)
        
        // Botón: Última Semana
        val btnUltimaSemana = Button(this).apply {
            text = "📅 ÚLTIMA SEMANA"
            textSize = 16f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setBackgroundColor(resources.getColor(R.color.status_success, null))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 8, 16, 8)
            }
            setOnClickListener {
                mostrarPromedioUltimaSemana()
            }
        }
        layoutVentaVolumen.addView(btnUltimaSemana)
        
        // Botón: Semana Específica
        val btnSemanaEspecifica = Button(this).apply {
            text = "🗓️ SEMANA ESPECÍFICA"
            textSize = 16f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setBackgroundColor(resources.getColor(R.color.brand_gold, null))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 8, 16, 8)
            }
            setOnClickListener {
                mostrarPromedioSemanaEspecifica()
            }
        }
        layoutVentaVolumen.addView(btnSemanaEspecifica)
        
        // Botón: Mes Actual
        val btnMesActual = Button(this).apply {
            text = "📆 MES ACTUAL"
            textSize = 16f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setBackgroundColor(resources.getColor(R.color.status_error, null))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 8, 16, 8)
            }
            setOnClickListener {
                mostrarPromedioMesActual()
            }
        }
        layoutVentaVolumen.addView(btnMesActual)
    }
    
    /**
     * Muestra el promedio general de todos los registros
     */
    private fun mostrarPromedioGeneral() {
        // Filtrar datos para período general
        registrosFiltrados = registrosCache
        periodoActual = "General - Todos los registros"
        
        Log.d("CRIOLLITO_DEBUG", "🔍 PROMEDIO GENERAL - Total registros: ${registrosFiltrados.size}")
        
        // Log específico para buscar CRIOLLITO en los registros
        val registrosConCriollito = registrosFiltrados.filter { registro ->
            registro.productos.any { producto -> producto.nombre.contains("CRIOLLITO") }
        }
        Log.d("CRIOLLITO_DEBUG", "🔍 PROMEDIO GENERAL - Registros que contienen CRIOLLITO: ${registrosConCriollito.size}")
        
        if (registrosConCriollito.isNotEmpty()) {
            registrosConCriollito.forEach { registro ->
                Log.d("CRIOLLITO_DEBUG", "🔍 PROMEDIO GENERAL - Registro con CRIOLLITO - Fecha: ${registro.fecha}, Tipo: ${registro.tipo}")
                registro.productos.filter { it.nombre.contains("CRIOLLITO") }.forEach { producto ->
                    Log.d("CRIOLLITO_DEBUG", "🔍 PROMEDIO GENERAL - CRIOLLITO encontrado: ${producto.nombre} = ${producto.cantidad}")
                }
            }
        } else {
            Log.d("CRIOLLITO_DEBUG", "🔍 PROMEDIO GENERAL - NO SE ENCONTRÓ CRIOLLITO EN NINGÚN REGISTRO")
        }
        
        // Cambiar switch a vista de tabla
        switchVista.isChecked = false
        
        // Mostrar vista de tabla con datos filtrados
        mostrarVentaVolumen(registrosFiltrados)
    }
    
    /**
     * Muestra el promedio de la última semana
     */
    private fun mostrarPromedioUltimaSemana() {
        // Filtrar registros de la semana calendario anterior (lunes a domingo)
        val calendar = DateHelper.getCalendar()
        
        // Ir al lunes de la semana actual
        val diaSemanaActual = calendar.get(Calendar.DAY_OF_WEEK)
        val diasHastaLunes = if (diaSemanaActual == Calendar.SUNDAY) 6 else diaSemanaActual - Calendar.MONDAY
        calendar.add(Calendar.DAY_OF_YEAR, -diasHastaLunes)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        
        // Ir al domingo de la semana anterior (7 días antes del lunes actual)
        calendar.add(Calendar.DAY_OF_YEAR, -7)
        val lunesAnterior = calendar.time
        
        calendar.add(Calendar.DAY_OF_YEAR, 6)
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val domingoAnterior = calendar.time
        
        val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
        registrosFiltrados = registrosCache.filter { registro ->
            try {
                val fechaRegistro = sdf.parse(registro.fecha)
                fechaRegistro != null && 
                !fechaRegistro.before(lunesAnterior) && 
                !fechaRegistro.after(domingoAnterior)
            } catch (e: Exception) {
                false
            }
        }
        
        periodoActual = "Última Semana"
        
        // Cambiar switch a vista de tabla
        switchVista.isChecked = false
        
        // Mostrar vista de tabla con datos filtrados
        mostrarVentaVolumen(registrosFiltrados)
    }
    
    /**
     * Muestra selector de semana específica
     */
    private fun mostrarPromedioSemanaEspecifica() {
        mostrarSelectorRangoFechas()
    }
    
    /**
     * Muestra el promedio del mes actual
     */
    private fun mostrarPromedioMesActual() {
        // Filtrar registros del mes actual
        val calendar = DateHelper.getCalendar()
        val mesActual = calendar.get(Calendar.MONTH)
        val añoActual = calendar.get(Calendar.YEAR)
        
        val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
        registrosFiltrados = registrosCache.filter { registro ->
            try {
                val fechaRegistro = sdf.parse(registro.fecha)
                if (fechaRegistro != null) {
                    calendar.time = fechaRegistro
                    calendar.get(Calendar.MONTH) == mesActual && calendar.get(Calendar.YEAR) == añoActual
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }
        
        val nombreMes = DateHelper.getDateFormat("MMMM yyyy").format(Date())
        periodoActual = "$nombreMes"
        
        // Cambiar switch a vista de tabla
        switchVista.isChecked = false
        
        // Mostrar vista de tabla con datos filtrados
        mostrarVentaVolumen(registrosFiltrados)
    }
    
    /**
     * Calcula y muestra los promedios por categoría
     */
    private fun calcularYMostrarPromedios(registros: List<RegistroInventario>, periodo: String) {
        if (registros.isEmpty()) {
            val mensaje = TextView(this).apply {
                text = "No hay registros para el período seleccionado"
                textSize = 14f
                setTextColor(resources.getColor(android.R.color.darker_gray, null))
                gravity = android.view.Gravity.CENTER
                setPadding(16, 32, 16, 32)
            }
            layoutVentaVolumen.addView(mensaje)
            return
        }
        
        // Mostrar información del período
        val infoPeriodo = TextView(this).apply {
            text = "Período: $periodo\nTotal de registros: ${registros.size}"
            textSize = 12f
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
            gravity = android.view.Gravity.CENTER
            setPadding(16, 8, 16, 16)
        }
        layoutVentaVolumen.addView(infoPeriodo)
        
        // Agrupar productos por categoría
        val categorias = mapOf(
            "EMPANADAS" to listOf("Jamón y Queso", "Pollo BBQ", "Carne Suave", "Pollo", "Carne Picante", 
                                  "Humita", "Espinaca", "Calabaza y Queso", "Roquefort y Jamón", 
                                  "Queso y Cebolla", "Cerdo y Barbacoa", "Cheeseburger"),
            "PIZZAS" to listOf("Muzzarella", "Jamón", "Pepperoni"),
            "PASTELITOS" to listOf("Batata", "Membrillo"),
            "OTROS" to listOf("Dulces", "Salado", "Chipa")
        )
        
        // Calcular promedios por categoría
        for ((categoria, productos) in categorias) {
            mostrarCategoriaPromedio(categoria, productos, registros)
        }
    }
    
    /**
     * Muestra una categoría con sus promedios
     */
    private fun mostrarCategoriaPromedio(categoria: String, productos: List<String>, registros: List<RegistroInventario>) {
        // Header de categoría
        val categoriaHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 12, 12)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(when (categoria) {
                "EMPANADAS" -> R.drawable.category_empanadas_header
                "PIZZAS" -> R.drawable.category_pizzas_header
                "PASTELITOS" -> R.drawable.category_pastelitos_header
                "OTROS" -> R.drawable.category_otros_header
                else -> R.drawable.category_header_background
            })
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 0)
            }
        }
        
        // Icono de categoría
        val iconoCategoria = TextView(this).apply {
            text = when (categoria) {
                "EMPANADAS" -> "🥟"
                "PIZZAS" -> "🍕"
                "PASTELITOS" -> "🥧"
                "OTROS" -> "🥐"
                else -> "📦"
            }
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 12
            }
        }
        
        val txtCategoria = TextView(this).apply {
            text = categoria
            textSize = 18f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        categoriaHeader.addView(iconoCategoria)
        categoriaHeader.addView(txtCategoria)
        layoutVentaVolumen.addView(categoriaHeader)
        
        // Contenedor de productos
        val productosLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.category_content_background)
            setPadding(8, 8, 8, 8)
        }
        
        // Calcular promedio para cada producto
        for (nombreProducto in productos) {
            val totalVentas = registros.flatMap { it.productos }
                .filter { 
                    // Buscar por nombre exacto o por nombre completo (ej: "Dulces" busca "DULCES - Dulces")
                    it.nombre.equals(nombreProducto, ignoreCase = true) ||
                    it.nombre.contains(nombreProducto, ignoreCase = true) ||
                    (it.nombre.contains(" - ") && it.nombre.split(" - ").getOrNull(1)?.equals(nombreProducto, ignoreCase = true) == true)
                }
                .sumOf { it.cantidad }
            
            val promedio = if (registros.isNotEmpty()) totalVentas.toDouble() / registros.size else 0.0
            
            if (promedio > 0) {
                val filaProducto = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(8, 8, 8, 8)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                
                val txtNombre = TextView(this).apply {
                    text = nombreProducto
                    textSize = 14f
                    setTextColor(resources.getColor(R.color.web_text_primary, null))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                
                val txtPromedio = TextView(this).apply {
                    text = String.format("%.1f", promedio)
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(resources.getColor(R.color.brand_gold, null))
                    gravity = android.view.Gravity.END
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                
                filaProducto.addView(txtNombre)
                filaProducto.addView(txtPromedio)
                productosLayout.addView(filaProducto)
            }
        }
        
        layoutVentaVolumen.addView(productosLayout)
    }
    
    /**
     * Muestra el diálogo selector de rango de fechas
     */
    private fun mostrarSelectorRangoFechas() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_date_range_selector, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        // Referencias a los elementos del diálogo
        val btnUltimos7Dias = dialogView.findViewById<Button>(R.id.btnUltimos7Dias)
        val btnUltimos30Dias = dialogView.findViewById<Button>(R.id.btnUltimos30Dias)
        val txtFechaDesde = dialogView.findViewById<TextView>(R.id.txtFechaDesde)
        val txtFechaHasta = dialogView.findViewById<TextView>(R.id.txtFechaHasta)
        val btnMesAnterior = dialogView.findViewById<Button>(R.id.btnMesAnterior)
        val btnMesSiguiente = dialogView.findViewById<Button>(R.id.btnMesSiguiente)
        val txtMesActual = dialogView.findViewById<TextView>(R.id.txtMesActual)
        val layoutCalendario = dialogView.findViewById<LinearLayout>(R.id.layoutCalendario)
        val btnCancelar = dialogView.findViewById<Button>(R.id.btnCancelar)
        val btnAplicar = dialogView.findViewById<Button>(R.id.btnAplicar)
        
        // Variables para controlar el calendario
        val calendar = DateHelper.getCalendar()
        var fechaDesde: Calendar? = null
        var fechaHasta: Calendar? = null
        var seleccionandoDesde = true
        
        // Función para actualizar el texto de las fechas
        fun actualizarFechasTexto() {
            val sdf = DateHelper.getDateFormat("dd/MM/yy", Locale("es", "ES"))
            
            if (fechaDesde != null) {
                txtFechaDesde.text = sdf.format(fechaDesde!!.time)
            } else {
                txtFechaDesde.text = "Seleccionar fecha"
            }
            
            if (fechaHasta != null) {
                txtFechaHasta.text = sdf.format(fechaHasta!!.time)
            } else {
                txtFechaHasta.text = "Seleccionar fecha"
            }
        }
        
        // Función para actualizar el calendario
        fun actualizarCalendario() {
            layoutCalendario.removeAllViews()
            
            // Actualizar texto del mes
            val sdfMes = DateHelper.getDateFormat("MMMM yyyy", Locale("es", "ES"))
            txtMesActual.text = sdfMes.format(calendar.time)
            
            // Crear grid de días
            val calendarMes = DateHelper.getCalendar()
            calendarMes.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), 1)
            
            // Obtener primer día del mes y día de la semana
            val primerDia = calendarMes.get(Calendar.DAY_OF_WEEK)
            val diasEnMes = calendarMes.getActualMaximum(Calendar.DAY_OF_MONTH)
            
            // Ajustar para que domingo sea 1
            val primerDiaAjustado = if (primerDia == 1) 7 else primerDia - 1
            
            // Crear filas de días
            var diaActual = 1
            var filaActual: LinearLayout? = null
            
            // Agregar días del mes anterior si es necesario
            val mesAnterior = DateHelper.getCalendar()
            mesAnterior.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) - 1, 1)
            val diasMesAnterior = mesAnterior.getActualMaximum(Calendar.DAY_OF_MONTH)
            
            for (i in 1 until primerDiaAjustado) {
                if (filaActual == null) {
                    filaActual = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                }
                
                val diaAnterior = diasMesAnterior - (primerDiaAjustado - i - 1)
                val btnDiaAnterior = Button(this).apply {
                    text = diaAnterior.toString()
                    textSize = 12f
                    setTextColor(resources.getColor(android.R.color.darker_gray, null))
                    background = null
                    isEnabled = false
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        setMargins(2, 2, 2, 2)
                    }
                }
                filaActual.addView(btnDiaAnterior)
            }
            
            // Agregar días del mes actual
            while (diaActual <= diasEnMes) {
                if (filaActual == null || filaActual.childCount >= 7) {
                    if (filaActual != null) {
                        layoutCalendario.addView(filaActual)
                    }
                    filaActual = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                }
                
                val dia = diaActual
                val btnDia = Button(this).apply {
                    text = dia.toString()
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        setMargins(2, 2, 2, 2)
                    }
                    
                    // Determinar si está en el rango seleccionado
                    val fechaActual = DateHelper.getCalendar()
                    fechaActual.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), dia)
                    
                    val fechaDesdeActual = fechaDesde
                    val fechaHastaActual = fechaHasta
                    
                    when {
                        fechaDesdeActual != null && fechaHastaActual != null && 
                        fechaActual.timeInMillis >= fechaDesdeActual.timeInMillis && 
                        fechaActual.timeInMillis <= fechaHastaActual.timeInMillis -> {
                            // En el rango
                            if (fechaActual.timeInMillis == fechaDesdeActual.timeInMillis || 
                                fechaActual.timeInMillis == fechaHastaActual.timeInMillis) {
                                setBackgroundColor(resources.getColor(R.color.brand_gold, null))
                                setTextColor(resources.getColor(R.color.web_text_light, null))
                            } else {
                                setBackgroundColor(resources.getColor(R.color.brand_gold, null))
                                setTextColor(resources.getColor(R.color.web_text_light, null))
                            }
                        }
                        fechaDesdeActual != null && fechaActual.timeInMillis == fechaDesdeActual.timeInMillis -> {
                            // Solo fecha desde seleccionada
                            setBackgroundColor(resources.getColor(R.color.brand_gold, null))
                            setTextColor(resources.getColor(R.color.web_text_light, null))
                        }
                        else -> {
                            // Día normal
                            setTextColor(resources.getColor(R.color.web_text_primary, null))
                            background = null
                        }
                    }
                    
                    setOnClickListener {
                        if (seleccionandoDesde || fechaDesde == null) {
                            fechaDesde = DateHelper.getCalendar()
                            fechaDesde?.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), dia)
                            // Configurar al inicio del día
                            fechaDesde?.set(Calendar.HOUR_OF_DAY, 0)
                            fechaDesde?.set(Calendar.MINUTE, 0)
                            fechaDesde?.set(Calendar.SECOND, 0)
                            fechaDesde?.set(Calendar.MILLISECOND, 0)
                            fechaHasta = null
                            seleccionandoDesde = false
                            actualizarFechasTexto()
                            actualizarCalendario()
                        } else {
                            fechaHasta = DateHelper.getCalendar()
                            fechaHasta?.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), dia)
                            // Configurar al final del día para incluir todo el día seleccionado
                            fechaHasta?.set(Calendar.HOUR_OF_DAY, 23)
                            fechaHasta?.set(Calendar.MINUTE, 59)
                            fechaHasta?.set(Calendar.SECOND, 59)
                            fechaHasta?.set(Calendar.MILLISECOND, 999)
                            
                            // Asegurar que fechaDesde <= fechaHasta
                            if (fechaDesde!!.timeInMillis > fechaHasta!!.timeInMillis) {
                                val temp = fechaDesde
                                fechaDesde = fechaHasta
                                fechaHasta = temp
                            }
                            
                            seleccionandoDesde = true
                            actualizarFechasTexto()
                            actualizarCalendario()
                        }
                    }
                }
                
                filaActual.addView(btnDia)
                diaActual++
            }
            
            // Agregar días del mes siguiente si es necesario
            var diasRestantes = 7 - (filaActual?.childCount ?: 0)
            while (diasRestantes > 0 && filaActual != null) {
                val btnDiaSiguiente = Button(this).apply {
                    text = (diasRestantes).toString()
                    textSize = 12f
                    setTextColor(resources.getColor(android.R.color.darker_gray, null))
                    background = null
                    isEnabled = false
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        setMargins(2, 2, 2, 2)
                    }
                }
                filaActual.addView(btnDiaSiguiente)
                diasRestantes--
            }
            
            if (filaActual != null) {
                layoutCalendario.addView(filaActual)
            }
        }
        
        // Listeners
        btnUltimos7Dias.setOnClickListener {
            val hoy = DateHelper.getCalendar()
            fechaHasta = DateHelper.getCalendar()
            fechaHasta?.set(hoy.get(Calendar.YEAR), hoy.get(Calendar.MONTH), hoy.get(Calendar.DAY_OF_MONTH))
            // Configurar al final del día
            fechaHasta?.set(Calendar.HOUR_OF_DAY, 23)
            fechaHasta?.set(Calendar.MINUTE, 59)
            fechaHasta?.set(Calendar.SECOND, 59)
            fechaHasta?.set(Calendar.MILLISECOND, 999)
            
            fechaDesde = DateHelper.getCalendar()
            fechaDesde?.add(Calendar.DAY_OF_MONTH, -6)
            // Configurar al inicio del día
            fechaDesde?.set(Calendar.HOUR_OF_DAY, 0)
            fechaDesde?.set(Calendar.MINUTE, 0)
            fechaDesde?.set(Calendar.SECOND, 0)
            fechaDesde?.set(Calendar.MILLISECOND, 0)
            
            seleccionandoDesde = true
            actualizarFechasTexto()
            actualizarCalendario()
        }
        
        btnUltimos30Dias.setOnClickListener {
            val hoy = DateHelper.getCalendar()
            fechaHasta = DateHelper.getCalendar()
            fechaHasta?.set(hoy.get(Calendar.YEAR), hoy.get(Calendar.MONTH), hoy.get(Calendar.DAY_OF_MONTH))
            // Configurar al final del día
            fechaHasta?.set(Calendar.HOUR_OF_DAY, 23)
            fechaHasta?.set(Calendar.MINUTE, 59)
            fechaHasta?.set(Calendar.SECOND, 59)
            fechaHasta?.set(Calendar.MILLISECOND, 999)
            
            fechaDesde = DateHelper.getCalendar()
            fechaDesde?.add(Calendar.DAY_OF_MONTH, -29)
            // Configurar al inicio del día
            fechaDesde?.set(Calendar.HOUR_OF_DAY, 0)
            fechaDesde?.set(Calendar.MINUTE, 0)
            fechaDesde?.set(Calendar.SECOND, 0)
            fechaDesde?.set(Calendar.MILLISECOND, 0)
            
            seleccionandoDesde = true
            actualizarFechasTexto()
            actualizarCalendario()
        }
        
        btnMesAnterior.setOnClickListener {
            calendar.add(Calendar.MONTH, -1)
            actualizarCalendario()
        }
        
        btnMesSiguiente.setOnClickListener {
            calendar.add(Calendar.MONTH, 1)
            actualizarCalendario()
        }
        
        btnCancelar.setOnClickListener {
            dialog.dismiss()
        }
        
        btnAplicar.setOnClickListener {
            if (fechaDesde != null && fechaHasta != null) {
                // Filtrar registros por rango de fechas
                val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
                registrosFiltrados = registrosCache.filter { registro ->
                    try {
                        val fechaRegistro = sdf.parse(registro.fecha)
                        if (fechaRegistro != null) {
                            fechaRegistro.after(fechaDesde!!.time) && fechaRegistro.before(fechaHasta!!.time) ||
                            sdf.format(fechaRegistro) == sdf.format(fechaDesde!!.time) ||
                            sdf.format(fechaRegistro) == sdf.format(fechaHasta!!.time)
                        } else {
                            false
                        }
                    } catch (e: Exception) {
                        false
                    }
                }
                
                val sdfMostrar = DateHelper.getDateFormat("dd/MM/yy", Locale("es", "ES"))
                periodoActual = "${sdfMostrar.format(fechaDesde!!.time)} a ${sdfMostrar.format(fechaHasta!!.time)}"
                
                // Cambiar switch a vista de tabla
                switchVista.isChecked = false
                
                // Mostrar vista de tabla con datos filtrados
                mostrarVentaVolumen(registrosFiltrados)
                
                dialog.dismiss()
            } else {
                android.widget.Toast.makeText(this, "Por favor selecciona ambas fechas", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        
        // Inicializar calendario
        actualizarFechasTexto()
        actualizarCalendario()
        
        dialog.show()
    }
    
}
