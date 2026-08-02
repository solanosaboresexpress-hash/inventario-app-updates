package com.tuapp.inventario

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tuapp.inventario.model.VencimientoLote
import com.tuapp.inventario.repository.VencimientoRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.tuapp.inventario.utils.DateHelper
import androidx.core.content.ContextCompat
import android.util.TypedValue
import java.util.*

/**
 * Pantalla para que el supervisor vea el stock y vencimientos de un local
 */
class StockVencimientosActivity : AppCompatActivity() {
    
    private lateinit var txtTitulo: TextView
    private lateinit var txtResumen: TextView
    private lateinit var layoutContenido: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnHistorial: Button
    private lateinit var spinnerFiltro: Spinner
    
    private var lotesActuales: List<VencimientoLote> = emptyList()
    private var stocksActuales: Map<String, Int> = emptyMap()
    private var filtroSeleccionado: String = "ORDEN_DEFECTO"
    
    private val repository = VencimientoRepository()
    private lateinit var localId: String
    private lateinit var localNombre: String
    private lateinit var localRepository: com.tuapp.inventario.repository.LocalInventarioRepository
    
    /**
     * Obtiene un color del tema actual (soporta modo oscuro)
     */
    private fun getThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
    
    /**
     * Verifica si el dispositivo está en modo oscuro
     */
    private fun isDarkMode(): Boolean {
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_vencimientos)
        
        // Obtener datos del intent
        localId = intent.getStringExtra("localId") ?: ""
        localNombre = intent.getStringExtra("localNombre") ?: "Local"
        
        Log.d("StockVencimientos", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d("StockVencimientos", "🚀 Iniciando StockVencimientosActivity")
        Log.d("StockVencimientos", "   localId recibido: '$localId'")
        Log.d("StockVencimientos", "   localNombre recibido: '$localNombre'")
        
        if (localId.isEmpty()) {
            Log.e("StockVencimientos", "❌ ERROR: localId está vacío")
            Toast.makeText(this, "Error: No se encontró el local. Por favor, intente nuevamente.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        try {
            localRepository = (application as InventarioApplication).localRepository
            Log.d("StockVencimientos", "✅ localRepository inicializado correctamente")
        } catch (e: Exception) {
            Log.e("StockVencimientos", "❌ ERROR inicializando localRepository: ${e.message}", e)
            Toast.makeText(this, "Error inicializando repositorio: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        inicializarVistas()
        cargarDatos()
    }
    
    private fun inicializarVistas() {
        txtTitulo = findViewById(R.id.txtTitulo)
        txtResumen = findViewById(R.id.txtResumen)
        layoutContenido = findViewById(R.id.layoutContenido)
        scrollView = findViewById(R.id.scrollView)
        progressBar = findViewById(R.id.progressBar)
        btnHistorial = findViewById(R.id.btnHistorial)
        spinnerFiltro = findViewById(R.id.spinnerFiltro)
        
        txtTitulo.text = "🏪 $localNombre"
        
        // Asegurar que el botón esté visible
        btnHistorial.visibility = View.VISIBLE
        
        // Ocultar contenido y mostrar loading al inicio
        progressBar.visibility = View.VISIBLE
        scrollView.visibility = View.GONE
        txtResumen.text = "⏳ Cargando vencimientos..."
        
        btnHistorial.setOnClickListener {
            mostrarHistorialVencimientos()
        }
        
        // Configurar spinner de filtros
        val opcionesFiltro = listOf(
            "📋 Orden por defecto",
            "⏰ Por vencimiento (más próximo primero)",
            "📦 Por cantidad (mayor primero)"
        )
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, opcionesFiltro)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFiltro.adapter = adapter
        
        spinnerFiltro.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filtroSeleccionado = when (position) {
                    0 -> "ORDEN_DEFECTO"
                    1 -> "POR_VENCIMIENTO"
                    2 -> "POR_CANTIDAD"
                    else -> "ORDEN_DEFECTO"
                }
                // Re-dibujar la vista con el nuevo filtro
                if (lotesActuales.isNotEmpty()) {
                    mostrarStockYVencimientos(lotesActuales, stocksActuales)
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun cargarDatos() {
        // El progressBar ya se muestra en inicializarVistas()
        lifecycleScope.launch {
            try {
                // 1. Cargar lotes activos
                val lotes = repository.obtenerLotesActivos(localId)
                
                Log.d("StockVencimientos", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.d("StockVencimientos", "🔍 LOTES CARGADOS: ${lotes.size}")
                lotes.forEach { lote ->
                    Log.d("StockVencimientos", "   ${lote.productoCodigo} (${lote.productoNombre}): ${lote.cantidad} uds")
                }
                Log.d("StockVencimientos", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                
                // 2. Calcular stock actual (intenta Room primero, luego Firebase directamente como la web)
                val stocksPorProducto = calcularStockActual()
                
                // Log final del estado del stock
                if (stocksPorProducto.isEmpty()) {
                    Log.w("StockVencimientos", "⚠️ Stock vacío después de intentar Room y Firebase")
                    Log.w("StockVencimientos", "   Esto podría indicar que:")
                    Log.w("StockVencimientos", "   1. El local no tiene datos de stock cargados")
                    Log.w("StockVencimientos", "   2. Room aún está procesando las escrituras")
                    Log.w("StockVencimientos", "   3. Realmente no hay stock cargado para este local")
                    
                    // Si el stock está vacío después de todos los reintentos, mostrar mensaje de carga
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        scrollView.visibility = View.VISIBLE
                        txtResumen.text = "⏳ Cargando datos de stock...\n\nPor favor, espere unos segundos y vuelva a entrar a esta sección."
                        layoutContenido.removeAllViews()
                        val txtCargando = TextView(this@StockVencimientosActivity).apply {
                            text = "⏳ Los datos de stock aún se están cargando desde Firebase.\n\nPor favor, salga y vuelva a entrar a esta sección en unos segundos."
                            textSize = 16f
                            setPadding(16, 32, 16, 32)
                            gravity = android.view.Gravity.CENTER
                            setTextColor(getThemeColor(android.R.attr.textColorSecondary))
                        }
                        layoutContenido.addView(txtCargando)
                    }
                    return@launch
                }
                
                Log.d("StockVencimientos", "📊 STOCKS REGISTRADOS (después de reintentos): ${stocksPorProducto.size}")
                stocksPorProducto.forEach { (codigo, cantidad) ->
                    Log.d("StockVencimientos", "   $codigo: $cantidad uds")
                }
                Log.d("StockVencimientos", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                
                // 3. Obtener resumen de alertas
                val resumen = repository.obtenerResumenVencimientos(localId)
                
                // Guardar datos en variables de instancia para que estén disponibles
                lotesActuales = lotes
                stocksActuales = stocksPorProducto
                
                // 4. Detectar productos con problemas DESPUÉS de asegurar que los datos estén cargados
                // Esto se hace en mostrarStockYVencimientos() para garantizar que los datos estén listos
                
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    scrollView.visibility = View.VISIBLE
                    
                    // Detectar problemas DESPUÉS de que los datos estén completamente cargados
                    val productosSinVencimientos = detectarProductosSinVencimientos(stocksPorProducto, lotes)
                    val productosConDiferencias = detectarProductosConDiferencias(stocksPorProducto, lotes)
                    
                    Log.d("StockVencimientos", "🔍 RESULTADOS DE DETECCIÓN:")
                    Log.d("StockVencimientos", "  - Productos sin vencimientos: $productosSinVencimientos")
                    Log.d("StockVencimientos", "  - Productos con diferencias: $productosConDiferencias")
                    Log.d("StockVencimientos", "  - Stocks cargados: ${stocksPorProducto.size}")
                    Log.d("StockVencimientos", "  - Lotes cargados: ${lotes.size}")
                    
                    mostrarResumen(resumen, lotes.size, productosSinVencimientos, productosConDiferencias, stocksPorProducto.isNotEmpty())
                    mostrarStockYVencimientos(lotes, stocksPorProducto)
                }
            } catch (e: Exception) {
                Log.e("StockVencimientos", "❌ ERROR cargando datos: ${e.message}", e)
                e.printStackTrace()
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    scrollView.visibility = View.VISIBLE
                    
                    // Mensaje de error más detallado
                    val mensajeError = when {
                        e.message?.contains("Permission denied", ignoreCase = true) == true -> {
                            "❌ Error: No tienes permisos para acceder a los datos de este local.\n\nPor favor, verifica que tengas acceso como supervisor o maestro."
                        }
                        e.message?.contains("network", ignoreCase = true) == true -> {
                            "❌ Error de conexión: No se pudo conectar a Firebase.\n\nVerifica tu conexión a internet e intenta nuevamente."
                        }
                        e.message?.contains("document", ignoreCase = true) == true -> {
                            "❌ Error: No se encontraron datos para el local '$localNombre'.\n\nEl local puede no tener datos cargados aún."
                        }
                        else -> {
                            "❌ Error cargando datos: ${e.message ?: "Error desconocido"}\n\nPor favor, intenta nuevamente o contacta al administrador."
                        }
                    }
                    
                    txtResumen.text = mensajeError
                    txtResumen.setTextColor(ContextCompat.getColor(this@StockVencimientosActivity, android.R.color.holo_red_dark))
                    
                    // Limpiar contenido y mostrar mensaje de error
                    layoutContenido.removeAllViews()
                    val txtError = TextView(this@StockVencimientosActivity).apply {
                        text = mensajeError
                        textSize = 14f
                        setPadding(16, 32, 16, 32)
                        gravity = android.view.Gravity.CENTER
                        setTextColor(ContextCompat.getColor(this@StockVencimientosActivity, android.R.color.holo_red_dark))
                    }
                    layoutContenido.addView(txtError)
                    
                    Toast.makeText(this@StockVencimientosActivity, "Error cargando datos. Ver detalles en pantalla.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Detecta productos que tienen stock pero no tienen lotes de vencimiento cargados
     * Solo verifica productos que están en el control de vencimientos
     */
    private fun detectarProductosSinVencimientos(
        stocksPorProducto: Map<String, Int>,
        lotes: List<VencimientoLote>
    ): Int {
        // Categorías de productos con control de vencimientos (mismo que MainActivity)
        val categorias = mapOf(
            "EMPANADAS" to listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH"),
            "PIZZAS" to listOf("MUZZA", "JAMON", "PEPPER"),
            "PASTELITOS" to listOf("BATATA", "MEMBRIL")
        )
        
        // Obtener todos los códigos de productos que SÍ están en el control de vencimientos
        val codigosConControlVencimientos = categorias.values.flatten().map { normalizarCodigoProducto(it) }.toSet()
        
        // Códigos de productos que tienen vencimientos (normalizados)
        val codigosConVencimientos = lotes.map { normalizarCodigoProducto(it.productoCodigo) }.distinct().toSet()
        
        Log.d("StockVencimientos", "🔍 Detección productos sin vencimientos:")
        Log.d("StockVencimientos", "  - Códigos con control: ${codigosConControlVencimientos.size}")
        Log.d("StockVencimientos", "  - Códigos con lotes: ${codigosConVencimientos.size}")
        
        // Contar productos con stock pero sin vencimientos (solo los que están en control de vencimientos)
        var productosSinVencimientos = 0
        val productosYaContados = mutableSetOf<String>()
        
        // Verificar TODOS los productos con control de vencimientos que tienen stock
        stocksPorProducto.forEach { (codigo, stock) ->
            val codigoNormalizado = normalizarCodigoProducto(codigo)
            
            // Solo verificar productos que están en el control de vencimientos y tienen stock
            if (codigoNormalizado in codigosConControlVencimientos && stock > 0) {
                // Si NO tiene lotes cargados, es un problema
                if (codigoNormalizado !in codigosConVencimientos && codigoNormalizado !in productosYaContados) {
                    productosSinVencimientos++
                    productosYaContados.add(codigoNormalizado)
                    Log.d("StockVencimientos", "❌ Producto $codigoNormalizado (stock=$stock) NO tiene lotes cargados")
                }
            }
        }
        
        Log.d("StockVencimientos", "📊 Productos sin vencimientos detectados: $productosSinVencimientos")
        return productosSinVencimientos
    }
    
    /**
     * Detecta productos que tienen diferencias entre stock y lotes (cuando stock != suma de lotes)
     * Solo verifica productos que están en el control de vencimientos
     */
    private fun detectarProductosConDiferencias(
        stocksPorProducto: Map<String, Int>,
        lotes: List<VencimientoLote>
    ): Int {
        // Categorías de productos con control de vencimientos (mismo que MainActivity)
        val categorias = mapOf(
            "EMPANADAS" to listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH"),
            "PIZZAS" to listOf("MUZZA", "JAMON", "PEPPER"),
            "PASTELITOS" to listOf("BATATA", "MEMBRIL")
        )
        
        // Obtener todos los códigos de productos que SÍ están en el control de vencimientos
        val codigosConControlVencimientos = categorias.values.flatten().map { normalizarCodigoProducto(it) }.toSet()
        
        // Agrupar lotes por producto (normalizar códigos)
        val lotesPorProducto = lotes.groupBy { normalizarCodigoProducto(it.productoCodigo) }
        
        // Contar productos con diferencias (solo los que están en control de vencimientos)
        var productosConDiferencias = 0
        stocksPorProducto.forEach { (codigo, stock) ->
            val codigoNormalizado = normalizarCodigoProducto(codigo)
            
            // Solo verificar productos que están en el control de vencimientos
            if (codigoNormalizado in codigosConControlVencimientos && stock > 0) {
                val lotesProducto = lotesPorProducto[codigoNormalizado] ?: emptyList()
                
                // Si tiene lotes, verificar si la suma coincide
                if (lotesProducto.isNotEmpty()) {
                    val sumaLotes = lotesProducto.sumOf { it.cantidad }
                    if (sumaLotes != stock) {
                        productosConDiferencias++
                        Log.d("StockVencimientos", "⚠️ Producto $codigo tiene diferencia: Stock=$stock, Lotes=$sumaLotes, Diferencia=${stock - sumaLotes}")
                    }
                }
            }
        }
        
        Log.d("StockVencimientos", "📊 Productos con diferencias detectados: $productosConDiferencias")
        return productosConDiferencias
    }
    
    /**
     * Calcula el stock actual desde Firebase directamente (como la web)
     * Esto es más rápido cuando los datos no están sincronizados en Room
     */
    private suspend fun calcularStockActualDesdeFirebase(): Map<String, Int> {
        return try {
            val firebaseRepo = com.tuapp.inventario.repository.FirebaseInventarioRepository(localId)
            val fechaHoy = DateHelper.getFechaActual()
            val fechaAyer = obtenerFechaAnterior(fechaHoy)
            
            Log.d("StockVencimientos", "🔍 Obteniendo stock desde Firebase (como la web):")
            Log.d("StockVencimientos", "   Fecha hoy: $fechaHoy")
            Log.d("StockVencimientos", "   Fecha ayer: $fechaAyer")
            Log.d("StockVencimientos", "   LocalId: $localId")
            
            // Obtener registros de Firebase directamente (sin depender de Room)
            // Usar withTimeoutOrNull para evitar que se quede colgado si hay problemas
            val registrosAyer = try {
                kotlinx.coroutines.withTimeoutOrNull(10000) { // 10 segundos timeout
                    firebaseRepo.getRegistrosByFecha(fechaAyer).first()
                } ?: run {
                    Log.w("StockVencimientos", "   ⚠️ Timeout obteniendo registros de ayer")
                    emptyList()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // CancellationException es normal cuando se cancela un Flow - ignorar
                Log.d("StockVencimientos", "   ℹ️ Flow cancelado (normal)")
                emptyList()
            } catch (e: Exception) {
                Log.w("StockVencimientos", "   ⚠️ Error obteniendo registros de ayer desde Firebase: ${e.message}")
                Log.w("StockVencimientos", "   Tipo de error: ${e.javaClass.simpleName}")
                emptyList()
            }
            
            val registrosHoy = try {
                kotlinx.coroutines.withTimeoutOrNull(10000) { // 10 segundos timeout
                    firebaseRepo.getRegistrosByFecha(fechaHoy).first()
                } ?: run {
                    Log.w("StockVencimientos", "   ⚠️ Timeout obteniendo registros de hoy")
                    emptyList()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // CancellationException es normal cuando se cancela un Flow - ignorar
                Log.d("StockVencimientos", "   ℹ️ Flow cancelado (normal)")
                emptyList()
            } catch (e: Exception) {
                Log.w("StockVencimientos", "   ⚠️ Error obteniendo registros de hoy desde Firebase: ${e.message}")
                Log.w("StockVencimientos", "   Tipo de error: ${e.javaClass.simpleName}")
                emptyList()
            }
            
            val registrosFirebase = registrosAyer + registrosHoy
            
            Log.d("StockVencimientos", "   ✅ Registros obtenidos de Firebase: ${registrosFirebase.size}")
            
            // PRIORIDAD 1: Intentar obtener Stock Final de HOY (si ya está cargado)
            val stockFinalHoy = registrosFirebase
                .filter { registro -> registro.fecha == fechaHoy && registro.tipo == "Stock Final" }
                .flatMap { registro -> registro.productos }
            
            Log.d("StockVencimientos", "📦 Stock Final HOY: ${stockFinalHoy.size} productos")
            
            // Si hay Stock Final de hoy, usarlo directamente (es más preciso)
            if (stockFinalHoy.isNotEmpty()) {
                val stockMap = mutableMapOf<String, Int>()
                stockFinalHoy.forEach { producto ->
                    val codigo = obtenerCodigoProducto(producto.nombre)
                    stockMap[codigo] = producto.cantidad
                    Log.d("StockVencimientos", "     ✅ Stock Final HOY: $codigo = ${producto.cantidad} uds")
                }
                
                Log.d("StockVencimientos", "   ✅ Usando Stock Final de HOY desde Firebase: ${stockMap.size} productos")
                return stockMap
            }
            
            // PRIORIDAD 2: Si no hay Stock Final de hoy, calcular: Stock Final Ayer + Ingreso Hoy
            Log.d("StockVencimientos", "   ⚠️ No hay Stock Final de hoy, calculando desde ayer...")
            
            val stockAyer = registrosFirebase
                .filter { registro -> registro.fecha == fechaAyer && registro.tipo == "Stock Final" }
                .flatMap { registro -> registro.productos }
            
            Log.d("StockVencimientos", "📦 Stock Final Ayer: ${stockAyer.size} productos")
            
            // Ingreso de hoy
            val ingresoHoy = registrosFirebase
                .filter { registro -> registro.fecha == fechaHoy && registro.tipo == "Ingreso de Mercadería" }
                .flatMap { registro -> registro.productos }
            
            Log.d("StockVencimientos", "📥 Ingreso Hoy: ${ingresoHoy.size} productos")
            
            val stockMap = mutableMapOf<String, Int>()

            // Sumar stock de ayer (deduplicando por código)
            stockAyer
                .groupBy { obtenerCodigoProducto(it.nombre) }
                .mapValues { (_, prods) ->
                    val prodCompleto = prods.find { it.nombre.contains(" - ") } ?: prods.first()
                    prodCompleto.cantidad
                }
                .forEach { (codigo, cantidad) ->
                    stockMap[codigo] = cantidad
                }

            // Sumar ingreso de hoy (deduplicando por código)
            ingresoHoy
                .groupBy { obtenerCodigoProducto(it.nombre) }
                .mapValues { (_, prods) ->
                    val prodCompleto = prods.find { it.nombre.contains(" - ") } ?: prods.first()
                    prodCompleto.cantidad
                }
                .forEach { (codigo, cantidad) ->
                    val cantidadAnterior = stockMap[codigo] ?: 0
                    stockMap[codigo] = cantidadAnterior + cantidad
                }
            
            Log.d("StockVencimientos", "   ✅ Stock obtenido de Firebase: ${stockMap.size} productos")
            stockMap
        } catch (e: Exception) {
            Log.e("StockVencimientos", "❌ Error obteniendo stock de Firebase: ${e.message}")
            emptyMap()
        }
    }
    
    /**
     * Calcula el stock actual basándose en:
     * - PRIORIDAD 1: Intentar desde Room (rápido si está disponible)
     * - PRIORIDAD 2: Si Room no tiene datos, cargar directamente desde Firebase (como la web)
     * 
     * Dentro de cada fuente:
     * - PRIORIDAD 1: Stock Final de HOY (si ya está cargado) - más preciso
     * - PRIORIDAD 2: Stock Final de ayer + Ingreso de Mercadería de hoy (si no hay Stock Final de hoy)
     * 
     * El Stock Final de hoy es más preciso porque ya incluye:
     * Stock Final Hoy = Stock Final Ayer + Ingreso Hoy - Ventas Hoy
     */
    /**
     * Calcula el stock actual desde Firebase directamente (siempre consulta Firebase primero)
     */
    private suspend fun calcularStockActual(): Map<String, Int> {
        return calcularStockActualDesdeFirebase()
    }
    
    private fun mostrarResumen(
        resumen: Map<String, Int>, 
        totalLotes: Int,
        productosSinVencimientos: Int = 0,
        productosConDiferencias: Int = 0,
        datosStockCargados: Boolean = true
    ) {
        val hoy = resumen["hoy"] ?: 0
        val manana = resumen["manana"] ?: 0
        val proximamente = resumen["proximamente"] ?: 0
        
        val fechaActual = DateHelper.formatearFecha(DateHelper.getFechaActual(), "dd/MM/yy")
        
        val textoResumen = buildString {
            append("📅 $fechaActual\n")
            append("📦 Total lotes: $totalLotes\n\n")
            
            if (hoy > 0) append("🔴 $hoy producto(s) vencen HOY\n")
            if (manana > 0) append("🟡 $manana producto(s) vencen mañana\n")
            if (proximamente > 0) append("🟠 $proximamente producto(s) vencen en 2-3 días\n")
            
            if (productosSinVencimientos > 0) {
                append("⚠️ $productosSinVencimientos producto(s) con stock SIN vencimientos cargados\n")
            }
            
            if (productosConDiferencias > 0) {
                append("⚠️ $productosConDiferencias producto(s) con diferencia entre stock y lotes\n")
            }
            
            // CRÍTICO: Solo mostrar "todo en orden" si:
            // 1. Los datos de stock están cargados (datosStockCargados == true)
            // 2. No hay vencimientos próximos
            // 3. No hay productos sin vencimientos
            // 4. No hay diferencias
            val hayProblemas = hoy > 0 || manana > 0 || proximamente > 0 || productosSinVencimientos > 0 || productosConDiferencias > 0
            if (!hayProblemas && datosStockCargados) {
                append("✅ Todo en orden")
            } else if (hoy == 0 && manana == 0 && proximamente == 0) {
                if (productosSinVencimientos > 0 || productosConDiferencias > 0) {
                    append("⚠️ Hay productos con problemas de vencimientos")
                } else if (!datosStockCargados) {
                    append("⏳ Cargando datos de stock...")
                }
            }
        }
        
        txtResumen.text = textoResumen
    }
    
    private fun mostrarStockYVencimientos(
        lotes: List<VencimientoLote>,
        stocksPorProducto: Map<String, Int>
    ) {
        layoutContenido.removeAllViews()
        
        if (lotes.isEmpty()) {
            val txtVacio = TextView(this).apply {
                text = "📭 No hay vencimientos cargados para este local"
                textSize = 16f
                setPadding(16, 32, 16, 32)
                gravity = android.view.Gravity.CENTER
                setTextColor(getThemeColor(android.R.attr.textColorSecondary))
            }
            layoutContenido.addView(txtVacio)
            return
        }
        
        // Detectar productos con problemas ANTES de mostrar el banner
        Log.d("StockVencimientos", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d("StockVencimientos", "🔍 DETECTANDO PROBLEMAS EN mostrarStockYVencimientos()")
        Log.d("StockVencimientos", "  - Stocks cargados: ${stocksPorProducto.size}")
        stocksPorProducto.forEach { (codigo, stock) ->
            Log.d("StockVencimientos", "    Stock: $codigo = $stock uds")
        }
        Log.d("StockVencimientos", "  - Lotes cargados: ${lotes.size}")
        lotes.forEach { lote ->
            Log.d("StockVencimientos", "    Lote: ${lote.productoCodigo} = ${lote.cantidad} uds")
        }
        
        val productosSinVencimientos = detectarProductosSinVencimientos(stocksPorProducto, lotes)
        val productosConDiferencias = detectarProductosConDiferencias(stocksPorProducto, lotes)
        
        // CRÍTICO: Solo considerar que no hay problemas si:
        // 1. Los datos de stock están cargados (stocksPorProducto no está vacío)
        // 2. No hay productos sin vencimientos
        // 3. No hay diferencias
        val datosStockCargados = stocksPorProducto.isNotEmpty()
        val hayProblemas = !datosStockCargados || productosSinVencimientos > 0 || productosConDiferencias > 0
        
        Log.d("StockVencimientos", "  - Productos sin vencimientos: $productosSinVencimientos")
        Log.d("StockVencimientos", "  - Productos con diferencias: $productosConDiferencias")
        Log.d("StockVencimientos", "  - Datos stock cargados: $datosStockCargados")
        Log.d("StockVencimientos", "  - Hay problemas: $hayProblemas")
        Log.d("StockVencimientos", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        // Mostrar banner verde SOLO si NO hay problemas Y los datos están cargados
        if (!hayProblemas && datosStockCargados) {
            Log.d("StockVencimientos", "✅ Mostrando banner VERDE - TODO EN ORDEN")
            val bannerVerde = TextView(this).apply {
                text = "✓ TODO EN ORDEN - Todos los productos con stock tienen lotes cargados"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(16, 12, 16, 12)
                setTextColor(resources.getColor(R.color.web_text_light, null))
                setBackgroundColor(resources.getColor(android.R.color.holo_green_dark, null))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
            }
            layoutContenido.addView(bannerVerde)
        } else {
            // Mostrar banner ROJO si hay productos sin lotes o con diferencias
            Log.d("StockVencimientos", "🔴 Mostrando banner ROJO - HAY PROBLEMAS")
            val bannerError = TextView(this).apply {
                val mensaje = buildString {
                    if (productosSinVencimientos > 0) {
                        append("🔴 $productosSinVencimientos producto(s) con stock SIN lotes cargados")
                    }
                    if (productosConDiferencias > 0) {
                        if (isNotEmpty()) append(" | ")
                        append("🔴 $productosConDiferencias producto(s) con diferencia entre stock y lotes")
                    }
                }
                text = mensaje
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(16, 12, 16, 12)
                setTextColor(resources.getColor(R.color.web_text_light, null))
                setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, null))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
            }
            layoutContenido.addView(bannerError)
        }
        
        // Agrupar por categoría y producto (mismo orden que en CargaActivity)
        val categorias = mapOf(
            "EMPANADAS" to listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH"),
            "PIZZAS" to listOf("MUZZA", "JAMON", "PEPPER"),
            "PASTELITOS" to listOf("BATATA", "MEMBRIL")
        )
        
        categorias.forEach { (categoria, codigosCategoria) ->
            val lotesCategoria = lotes.filter { it.productoCodigo in codigosCategoria }
            
            // Detectar productos de esta categoría con stock pero sin lotes
            // Normalizar códigos para comparar correctamente
            val productosConStockSinLotes = codigosCategoria.filter { codigo ->
                val codigoNormalizado = normalizarCodigoProducto(codigo)
                val stock = stocksPorProducto[codigoNormalizado] ?: 0
                val tieneLotes = lotesCategoria.any { normalizarCodigoProducto(it.productoCodigo) == codigoNormalizado }
                stock > 0 && !tieneLotes
            }
            
            // Mostrar categoría si tiene lotes O productos con stock sin lotes
            if (lotesCategoria.isNotEmpty() || productosConStockSinLotes.isNotEmpty()) {
                // Agrupar por producto (normalizar códigos para que coincidan con stocksPorProducto)
                val lotesPorProducto = lotesCategoria.groupBy { normalizarCodigoProducto(it.productoCodigo) }
                
                // Ordenar productos según el filtro seleccionado
                val productosOrdenados: List<Pair<String, List<VencimientoLote>>> = when (filtroSeleccionado) {
                    "ORDEN_DEFECTO" -> {
                        // Orden por defecto: según el orden de CargaActivity
                        codigosCategoria.mapNotNull { codigo ->
                            val codigoNormalizado = normalizarCodigoProducto(codigo)
                            lotesPorProducto[codigoNormalizado]?.let { codigoNormalizado to it }
                        }
                    }
                    "POR_VENCIMIENTO" -> {
                        // Ordenar por vencimiento más próximo primero
                        lotesPorProducto.map { (codigo, lotesProducto) ->
                            val vencimientoMasProximo = lotesProducto.minOfOrNull { lote ->
                                try {
                                    val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
                                    sdf.parse(lote.fechaVencimiento)?.time ?: Long.MAX_VALUE
                                } catch (e: Exception) {
                                    Long.MAX_VALUE
                                }
                            } ?: Long.MAX_VALUE
                            Triple(codigo, lotesProducto, vencimientoMasProximo)
                        }.sortedBy { it.third }.map { it.first to it.second }
                    }
                    "POR_CANTIDAD" -> {
                        // Ordenar por cantidad total (mayor primero)
                        lotesPorProducto.map { (codigo, lotesProducto) ->
                            val cantidadTotal = lotesProducto.sumOf { it.cantidad }
                            Triple(codigo, lotesProducto, cantidadTotal)
                        }.sortedByDescending { it.third }.map { it.first to it.second }
                    }
                    else -> {
                        // Por defecto
                        codigosCategoria.mapNotNull { codigo ->
                            val codigoNormalizado = normalizarCodigoProducto(codigo)
                            lotesPorProducto[codigoNormalizado]?.let { codigoNormalizado to it }
                        }
                    }
                }
                
                // Calcular total de stock de la categoría
                // Si hay stock en registros, usarlo. Si no, usar suma de lotes
                var totalStockCategoria = 0
                productosOrdenados.forEach { (codigo, lotesProducto) ->
                    // Si el código está en el mapa, usar ese valor (incluso si es 0)
                    // Si no está en el mapa, significa que no hay registros, usar suma de lotes
                    val stockRegistrado = stocksPorProducto[codigo]
                    val sumaLotes = lotesProducto.sumOf { it.cantidad }
                    
                    // Si el código está en el mapa (tiene registros), usar stock registrado
                    // Si no está en el mapa (no hay registros), usar suma de lotes
                    val stockReal = if (stockRegistrado != null) {
                        // Hay registros, usar el valor (puede ser 0 si realmente no hay stock)
                        stockRegistrado
                    } else {
                        // No hay registros, usar suma de lotes como fallback
                        sumaLotes
                    }
                    totalStockCategoria += stockReal
                }
                
                // Sumar también los productos con stock pero sin lotes
                productosConStockSinLotes.forEach { codigo ->
                    val codigoNormalizado = normalizarCodigoProducto(codigo)
                    val stock = stocksPorProducto[codigoNormalizado] ?: 0
                    totalStockCategoria += stock
                }
                
                // Título de categoría
                val txtCategoria = TextView(this).apply {
                    text = "\n📊 $categoria"
                    textSize = 20f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 24, 0, 8)
                    setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
                }
                layoutContenido.addView(txtCategoria)
                
                // Mostrar total ARRIBA de la categoría
                val txtTotalCategoria = TextView(this).apply {
                    text = "📦 TOTAL: $totalStockCategoria unidades"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, 12)
                    setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                }
                layoutContenido.addView(txtTotalCategoria)
                
                // Divisor
                val divisor = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        2
                    )
                    setBackgroundColor(resources.getColor(android.R.color.holo_blue_light, null))
                }
                layoutContenido.addView(divisor)
                
                // Mostrar cada producto en el orden determinado
                productosOrdenados.forEach { (codigo, lotesProducto) ->
                    // Si el código está en el mapa, usar ese valor (incluso si es 0)
                    // Si no está en el mapa, significa que no hay registros, usar suma de lotes
                    val stockRegistrado = stocksPorProducto[codigo]
                    val sumaLotes = lotesProducto.sumOf { it.cantidad }
                    
                    // Si el código está en el mapa (tiene registros), usar stock registrado
                    // Si no está en el mapa (no hay registros), usar suma de lotes
                    val stockReal = if (stockRegistrado != null) {
                        // Hay registros, usar el valor (puede ser 0 si realmente no hay stock)
                        stockRegistrado
                    } else {
                        // No hay registros, usar suma de lotes como fallback
                        sumaLotes
                    }
                    
                    Log.d("StockVencimientos", "🔍 Producto $codigo:")
                    Log.d("StockVencimientos", "   Stock Registrado: $stockRegistrado")
                    Log.d("StockVencimientos", "   Suma Lotes: $sumaLotes")
                    Log.d("StockVencimientos", "   Stock Real usado: $stockReal")
                    
                    mostrarProductoConVencimientos(codigo, lotesProducto, stockReal)
                }
                
                // Mostrar productos con stock pero sin lotes de vencimiento
                productosConStockSinLotes.forEach { codigo ->
                    val stock = stocksPorProducto[codigo] ?: 0
                    mostrarProductoSinVencimientos(codigo, stock)
                }
            }
        }
    }
    
    /**
     * Muestra un producto que tiene stock pero NO tiene lotes de vencimiento cargados
     */
    private fun mostrarProductoSinVencimientos(codigo: String, stockActual: Int) {
        // Obtener nombre del producto (usar el mismo mapa que VencimientosActivity)
        val productos = mapOf(
            // Empanadas
            "JQ" to "JQ - Jamón y Queso",
            "PB" to "PB - Pollo BBQ",
            "CS" to "CS - Carne Suave",
            "PO" to "PO - Pollo",
            "CP" to "CP - Carne Picante",
            "HU" to "HU - Humita",
            "ES" to "ES - Espinaca",
            "CQ" to "CQ - Calabaza y Queso",
            "RJ" to "RJ - Roquefort y Jamón",
            "QC" to "QC - Queso y Cebolla",
            "CB" to "CB - Cerdo y Barbacoa",
            "CH" to "CH - Cheeseburger",
            // Pizzas
            "MUZZA" to "Pizza Muzzarella",
            "JAMON" to "Pizza Jamón",
            "PEPPER" to "Pizza Pepperoni",
            // Pastelitos
            "BATATA" to "Pastelito Batata",
            "MEMBRIL" to "Pastelito Membrillo"
        )
        val productoNombre = productos[codigo] ?: codigo
        
        // Nombre del producto con fondo de advertencia
        val txtProducto = TextView(this).apply {
            text = productoNombre
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(16, 12, 16, 12)
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setBackgroundColor(resources.getColor(android.R.color.holo_orange_dark, null))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 8)
            }
        }
        layoutContenido.addView(txtProducto)
        
        // Stock actual
        val txtStock = TextView(this).apply {
            text = "   Stock actual: $stockActual uds"
            textSize = 14f
            setPadding(16, 4, 16, 8)
            setTextColor(getThemeColor(android.R.attr.textColorPrimary))
        }
        layoutContenido.addView(txtStock)
        
        // Advertencia de falta de vencimientos
        val txtAdvertencia = TextView(this).apply {
            text = "   ⚠️ NO HAY VENCIMIENTOS CARGADOS para este producto"
            textSize = 14f
            setPadding(16, 4, 16, 8)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
        }
        layoutContenido.addView(txtAdvertencia)
        
        val txtInstruccion = TextView(this).apply {
            text = "   💡 Debes cargar los vencimientos desde la sección 'Vencimientos'"
            textSize = 12f
            setPadding(16, 0, 16, 16)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layoutContenido.addView(txtInstruccion)
    }
    
    private fun mostrarProductoConVencimientos(
        codigo: String,
        lotes: List<VencimientoLote>,
        stockActual: Int
    ) {
        val productoNombre = lotes.firstOrNull()?.productoNombre ?: codigo
        
        // Nombre del producto con fondo
        val txtProducto = TextView(this).apply {
            text = productoNombre
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(16, 12, 16, 12)
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark, null))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 8)
            }
        }
        layoutContenido.addView(txtProducto)
        
        // Stock actual
        val txtStock = TextView(this).apply {
            text = "   Stock actual: $stockActual uds"
            textSize = 14f
            setPadding(16, 4, 16, 8)
            setTextColor(getThemeColor(android.R.attr.textColorPrimary))
        }
        layoutContenido.addView(txtStock)
        
        // Lotes con vencimiento (ordenados por fecha)
        val txtLotesHeader = TextView(this).apply {
            text = "   Lotes con vencimiento:"
            textSize = 14f
            setPadding(16, 4, 16, 4)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layoutContenido.addView(txtLotesHeader)
        
        val lotesOrdenados = lotes.sortedBy { it.fechaVencimiento }
        var sumaLotes = 0
        
        lotesOrdenados.forEach { lote ->
            sumaLotes += lote.cantidad
            
            val diasHasta = lote.diasHastaVencimiento()
            val icono = when {
                diasHasta <= 0 -> "🔴"
                diasHasta == 1 -> "🟡"
                diasHasta in 2..3 -> "🟠"
                else -> "🟢"
            }
            
            val urgencia = when {
                diasHasta <= 0 -> " (¡HOY!)"
                diasHasta == 1 -> " (Mañana)"
                diasHasta in 2..3 -> " (${diasHasta} días)"
                else -> ""
            }
            
            val fechaDisplay = try {
                val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
                val sdfDisplay = DateHelper.getDateFormat("dd/MM")
                val fecha = sdf.parse(lote.fechaVencimiento)
                sdfDisplay.format(fecha!!)
            } catch (e: Exception) {
                lote.fechaVencimiento
            }
            
            val txtLote = TextView(this).apply {
                text = "   $icono ${lote.cantidad} uds → Vence $fechaDisplay$urgencia"
                textSize = 14f
                setPadding(32, 4, 16, 4)
                
                // Color del texto según urgencia (adaptado para tema oscuro)
                val color = when {
                    diasHasta <= 0 -> android.R.color.holo_red_dark
                    diasHasta == 1 -> android.R.color.holo_orange_dark
                    else -> {
                        // Usar color que se adapta al tema (blanco en oscuro, negro en claro)
                        if (isDarkMode()) {
                            R.color.web_text_light
                        } else {
                            R.color.web_text_primary
                        }
                    }
                }
                setTextColor(resources.getColor(color, null))
            }
            layoutContenido.addView(txtLote)
        }
        
        // Subtotal y validación
        val divisorSubtotal = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                setMargins(32, 8, 16, 8)
            }
            setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layoutContenido.addView(divisorSubtotal)
        
        // Validación mejorada (como en la web)
        val coincide = sumaLotes == stockActual
        val diferencia = stockActual - sumaLotes
        
        // Mostrar validación principal
        val txtValidacion = TextView(this).apply {
            when {
                coincide -> {
                    text = "   ✅ Stock: $stockActual uds | Lotes: $sumaLotes uds (Coincide)"
                    setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                }
                diferencia > 0 -> {
                    text = "   Stock: $stockActual uds | Lotes: $sumaLotes uds"
                    setTextColor(getThemeColor(android.R.attr.textColorPrimary))
                }
                else -> {
                    text = "   Stock: $stockActual uds | Lotes: $sumaLotes uds"
                    setTextColor(getThemeColor(android.R.attr.textColorPrimary))
                }
            }
            textSize = 14f
            setPadding(32, 4, 16, 4)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        layoutContenido.addView(txtValidacion)
        
            // Mensaje de error en ROJO si NO coincide
        if (!coincide) {
            val txtError = TextView(this).apply {
                val mensajeError = if (diferencia > 0) {
                    "   🔴 FALTAN: +$diferencia unidades | Los lotes NO coinciden con el stock"
                } else {
                    "   🔴 SOBRAN: ${-diferencia} unidades | Los lotes NO coinciden con el stock"
                }
                text = mensajeError
                textSize = 15f
                setPadding(32, 12, 16, 12)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resources.getColor(R.color.web_text_light, null))
                setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, null))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
            }
            layoutContenido.addView(txtError)
            
            // Mensaje adicional explicativo
            val txtMensaje = TextView(this).apply {
                text = "   ⚠️ Debes ajustar los lotes para que coincidan con el stock actual"
                textSize = 12f
                setPadding(32, 0, 16, 16)
                setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
            }
            layoutContenido.addView(txtMensaje)
        }
    }
    
    private fun obtenerFechaAnterior(fecha: String): String {
        return try {
            val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
            val calendar = DateHelper.getCalendar()
            calendar.time = sdf.parse(fecha) ?: Date()
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            sdf.format(calendar.time)
        } catch (e: Exception) {
            fecha
        }
    }
    
    private fun obtenerCodigoProducto(nombre: String): String {
        // Extraer código del nombre (formato: "JQ - Jamón y Queso" -> "JQ")
        val codigo = when {
            nombre.contains(" - ") -> nombre.split(" - ")[0].trim()
            else -> nombre
        }
        // Normalizar código para que coincida con los lotes de vencimientos (PEPPERO -> PEPPER, MUZZARE -> MUZZA)
        return normalizarCodigoProducto(codigo)
    }
    
    /**
     * Normaliza el código de producto para que coincida con los lotes de vencimientos
     * Esta función asegura que los códigos extraídos de los nombres de productos
     * (de Ingreso y Stock Final) coincidan con los códigos usados en VencimientosActivity
     * 
     * Mapeo de normalización:
     * - PIZZAS: MUZZARE -> MUZZA, PEPPERO -> PEPPER
     * - EMPANADAS: Ya coinciden (JQ, PB, CS, PO, CP, HU, ES, CQ, RJ, QC, CB, CH)
     * - PASTELITOS: Ya coinciden (BATATA, MEMBRIL)
     * - OTROS: No se usan en vencimientos (DULCES, CHIPA, CRIOLLITO)
     */
    private fun normalizarCodigoProducto(codigo: String): String {
        return when (codigo) {
            // PIZZAS - Normalizar para que coincida con VencimientosActivity
            "PEPPERO" -> "PEPPER"
            "MUZZARE" -> "MUZZA"
            // EMPANADAS - Ya coinciden, no necesitan normalización
            // PASTELITOS - Ya coinciden, no necesitan normalización
            // OTROS - No se usan en vencimientos
            else -> codigo
        }
    }
    
    /**
     * Muestra el historial de vencimientos por día
     */
    private fun mostrarHistorialVencimientos() {
        progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                Log.d("StockVencimientos", "🔍 Iniciando carga de historial para local: $localId")
                val registros = repository.obtenerTodosRegistrosDiariosVencimientos(localId, 30)
                
                Log.d("StockVencimientos", "📊 Registros obtenidos: ${registros.size}")
                registros.forEachIndexed { index, registro ->
                    Log.d("StockVencimientos", "   [$index] Fecha: ${registro.fecha}, Iniciales: ${registro.lotesIniciales.size}, Finales: ${registro.lotesFinales.size}")
                }
                
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    
                    if (registros.isEmpty()) {
                        Log.w("StockVencimientos", "⚠️ No se encontraron registros históricos")
                        Toast.makeText(this@StockVencimientosActivity, "No hay registros históricos disponibles", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    
                    Log.d("StockVencimientos", "✅ Mostrando diálogo con ${registros.size} registros")
                    mostrarDialogoHistorial(registros)
                }
            } catch (e: Exception) {
                Log.e("StockVencimientos", "❌ Error cargando historial: ${e.message}", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@StockVencimientosActivity, "Error cargando historial: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Muestra un diálogo con el historial de vencimientos (diseño basado en la app web)
     */
    private fun mostrarDialogoHistorial(registros: List<com.tuapp.inventario.model.RegistroDiarioVencimientos>) {
        if (registros.isEmpty()) {
            Toast.makeText(this, "No hay registros históricos disponibles", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Crear diálogo con navegación por día
        var indiceActual: Int
        var dialogActual: AlertDialog? = null
        var soloVencimientosDelDia = false // Estado del filtro compartido
        
        fun mostrarRegistro(indice: Int) {
            if (indice < 0 || indice >= registros.size) return
            
            indiceActual = indice
            val registro = registros[indice]
            
            // Log para diagnóstico
            Log.d("StockVencimientos", "📅 Mostrando registro ${indice + 1}/${registros.size}:")
            Log.d("StockVencimientos", "   Fecha: ${registro.fecha}")
            Log.d("StockVencimientos", "   Lotes iniciales: ${registro.lotesIniciales.size}")
            Log.d("StockVencimientos", "   Lotes finales: ${registro.lotesFinales.size}")
            
            val fechaFormateada = try {
                val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
                val date = sdf.parse(registro.fecha)
                DateHelper.getDateFormat("dd/MM/yy").format(date ?: Date())
            } catch (e: Exception) {
                registro.fecha
            }
            
            val layoutPrincipal = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
            }
            
            // Header con gradiente (similar a la app web)
            val headerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 20, 16, 16)
                // Gradiente morado similar a la app web
                setBackgroundColor(android.graphics.Color.parseColor("#667eea"))
            }
            
            // Título del header
            val tituloHeader = TextView(this).apply {
                text = "📅 Historial de Vencimientos - $localNombre"
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resources.getColor(R.color.web_text_light, null))
                setPadding(0, 0, 0, 12)
            }
            headerLayout.addView(tituloHeader)
            
            // Navegación por fecha (similar a la app web)
            val layoutNavegacion = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            
            // Botón anterior (fechas anteriores = más antiguas)
            val btnAnterior = Button(this).apply {
                text = "◀"
                textSize = 18f
                setTextColor(resources.getColor(R.color.web_text_light, null))
                setBackgroundColor(android.graphics.Color.parseColor("#00000000")) // Transparente
                setPadding(8, 8, 8, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                // Habilitado si hay fechas anteriores (índices mayores)
                isEnabled = indiceActual < registros.size - 1
                if (!isEnabled) {
                    alpha = 0.5f
                }
            }
            
            // Fecha en el medio (con fondo blanco como en la app web)
            val fechaDisplay = TextView(this).apply {
                text = fechaFormateada
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor("#1f2937"))
                setBackgroundColor(resources.getColor(R.color.alt_row_color, null))
                setPadding(16, 12, 16, 12)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    setMargins(8, 0, 8, 0)
                }
            }
            
            // Botón siguiente (fechas posteriores = más recientes)
            val btnSiguiente = Button(this).apply {
                text = "▶"
                textSize = 18f
                setTextColor(resources.getColor(R.color.web_text_light, null))
                setBackgroundColor(android.graphics.Color.parseColor("#00000000")) // Transparente
                setPadding(8, 8, 8, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                // Habilitado si hay fechas posteriores (índices menores)
                isEnabled = indiceActual > 0
                if (!isEnabled) {
                    alpha = 0.5f
                }
            }
            
            layoutNavegacion.addView(btnAnterior)
            layoutNavegacion.addView(fechaDisplay)
            layoutNavegacion.addView(btnSiguiente)
            headerLayout.addView(layoutNavegacion)
            
            // Checkbox filtro (similar a la app web)
            val layoutCheckbox = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(6, 12, 6, 0)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            
            val checkboxFiltro = CheckBox(this).apply {
                isChecked = soloVencimientosDelDia
                setPadding(0, 0, 8, 0)
            }
            
            val labelFiltro = TextView(this).apply {
                text = "🔍 Solo vencimientos del día"
                textSize = 14f
                setTextColor(resources.getColor(R.color.web_text_light, null))
                setOnClickListener {
                    checkboxFiltro.isChecked = !checkboxFiltro.isChecked
                }
            }
            
            checkboxFiltro.setOnCheckedChangeListener { _, isChecked ->
                soloVencimientosDelDia = isChecked
                // Recrear el diálogo con el filtro aplicado
                val indiceActualGuardado = indiceActual
                dialogActual?.dismiss()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    mostrarRegistro(indiceActualGuardado)
                }, 100)
            }
            
            layoutCheckbox.addView(checkboxFiltro)
            layoutCheckbox.addView(labelFiltro)
            headerLayout.addView(layoutCheckbox)
            
            layoutPrincipal.addView(headerLayout)
            
            // Botón cerrar en el header
            val btnCerrar = Button(this).apply {
                text = "✕ Cerrar"
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#667eea"))
                setBackgroundColor(resources.getColor(R.color.surface_color, null))
                setPadding(10, 8, 10, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.END
                    setMargins(0, 8, 16, 0)
                }
            }
            headerLayout.addView(btnCerrar)
            
            // ScrollView para el contenido (con fondo gris claro como en la app web)
            val scrollView = ScrollView(this).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#f9fafb"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.65).toInt()
                )
            }
            
            val layoutContenido = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 20, 20, 20)
            }
            
            // Función para filtrar lotes según la fecha del registro
            fun filtrarLotes(lotes: List<com.tuapp.inventario.model.VencimientoLote>): List<com.tuapp.inventario.model.VencimientoLote> {
                if (!soloVencimientosDelDia) return lotes
                return lotes.filter { lote ->
                    lote.fechaVencimiento == registro.fecha
                }
            }
            
            // Función para obtener icono y colores según días hasta vencimiento
            fun obtenerIconoYColores(lote: com.tuapp.inventario.model.VencimientoLote): Triple<String, Int, Int> {
                val diasHasta = lote.diasHastaVencimiento()
                return when {
                    diasHasta <= 0 -> Triple("🔴", android.graphics.Color.parseColor("#991b1b"), android.graphics.Color.parseColor("#fef2f2"))
                    diasHasta == 1 -> Triple("🟡", android.graphics.Color.parseColor("#854d0e"), android.graphics.Color.parseColor("#fefce8"))
                    diasHasta <= 3 -> Triple("🟠", android.graphics.Color.parseColor("#9a3412"), android.graphics.Color.parseColor("#fff7ed"))
                    else -> Triple("🟢", android.graphics.Color.parseColor("#166534"), android.graphics.Color.parseColor("#f0fdf4"))
                }
            }
            
            // Función para crear un item de lote (diseño similar a la app web)
            fun crearItemLote(lote: com.tuapp.inventario.model.VencimientoLote, index: Int): LinearLayout {
                val fechaVto = try {
                    val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
                    val date = sdf.parse(lote.fechaVencimiento)
                    DateHelper.getDateFormat("dd/MM").format(date ?: Date())
                } catch (e: Exception) {
                    lote.fechaVencimiento
                }
                
                val (icono, _, colorFondo) = obtenerIconoYColores(lote)
                
                val rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(12, 12, 12, 12)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    // Fondo alternado como en la app web
                    setBackgroundColor(if (index % 2 == 0) resources.getColor(R.color.surface_color, null) else resources.getColor(R.color.alt_row_color2, null))
                }
                
                // Icono
                val iconoView = TextView(this).apply {
                    text = icono
                    textSize = 18f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 10, 0)
                    }
                }
                rowLayout.addView(iconoView)
                
                // Información de fecha
                val infoLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
                
                val txtVence = TextView(this).apply {
                    text = "Vence: $fechaVto"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#374151"))
                }
                infoLayout.addView(txtVence)
                
                val txtFechaCompleta = TextView(this).apply {
                    text = lote.fechaVencimiento
                    textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#6b7280"))
                    setPadding(0, 2, 0, 0)
                }
                infoLayout.addView(txtFechaCompleta)
                
                rowLayout.addView(infoLayout)
                
                // Cantidad (badge destacado como en la app web)
                val cantidadLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(8, 0, 0, 0)
                }
                
                val badgeCantidad = TextView(this).apply {
                    text = "${lote.cantidad}"
                    textSize = 20f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#1f2937"))
                    setBackgroundColor(colorFondo)
                    setPadding(12, 6, 12, 6)
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                cantidadLayout.addView(badgeCantidad)
                
                val txtUds = TextView(this).apply {
                    text = "uds"
                    textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#6b7280"))
                    setPadding(8, 0, 0, 0)
                }
                cantidadLayout.addView(txtUds)
                
                rowLayout.addView(cantidadLayout)
                
                return rowLayout
            }
            
            // Función para ordenar y agrupar lotes según el orden estándar
            fun ordenarYAgruparLotes(lotes: List<com.tuapp.inventario.model.VencimientoLote>): List<Pair<String, List<com.tuapp.inventario.model.VencimientoLote>>> {
                // Orden estándar de categorías y productos
                val categorias = mapOf(
                    "EMPANADAS" to listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH"),
                    "PIZZAS" to listOf("MUZZA", "JAMON", "PEPPER"),
                    "PASTELITOS" to listOf("BATATA", "MEMBRIL")
                )
                
                // Agrupar lotes por código de producto
                val lotesPorProducto = lotes.groupBy { it.productoCodigo }
                
                // Crear lista ordenada según el orden estándar
                val productosOrdenados = mutableListOf<Pair<String, List<com.tuapp.inventario.model.VencimientoLote>>>()
                
                categorias.forEach { (_, codigosCategoria) ->
                    codigosCategoria.forEach { codigo ->
                        lotesPorProducto[codigo]?.let { lotesProducto ->
                            // Ordenar lotes del mismo producto por fecha de vencimiento (más próximo primero)
                            val lotesOrdenados = lotesProducto.sortedBy { lote ->
                                try {
                                    val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
                                    sdf.parse(lote.fechaVencimiento)?.time ?: Long.MAX_VALUE
                                } catch (e: Exception) {
                                    Long.MAX_VALUE
                                }
                            }
                            productosOrdenados.add(codigo to lotesOrdenados)
                        }
                    }
                }
                
                // Agregar productos que no están en el orden estándar al final
                val codigosYaAgregados = productosOrdenados.map { it.first }.toSet()
                lotesPorProducto.forEach { (codigo, lotesProducto) ->
                    if (codigo !in codigosYaAgregados) {
                        val lotesOrdenados = lotesProducto.sortedBy { lote ->
                            try {
                                val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
                                sdf.parse(lote.fechaVencimiento)?.time ?: Long.MAX_VALUE
                            } catch (e: Exception) {
                                Long.MAX_VALUE
                            }
                        }
                        productosOrdenados.add(codigo to lotesOrdenados)
                    }
                }
                
                return productosOrdenados
            }
            
            // Función para renderizar una sección (INICIO o FIN)
            fun renderSection(titulo: String, lotes: List<com.tuapp.inventario.model.VencimientoLote>, esInicio: Boolean) {
                val lotesFiltrados = filtrarLotes(lotes)
                val grupos = if (lotesFiltrados.isEmpty()) {
                    emptyList<Pair<String, List<com.tuapp.inventario.model.VencimientoLote>>>()
                } else {
                    ordenarYAgruparLotes(lotesFiltrados)
                }
                
                // Contenedor de la sección (con borde y sombra como en la app web)
                val sectionWrap = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(resources.getColor(R.color.surface_color, null))
                    setPadding(0, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 16, 0, 0)
                    }
                }
                
                // Contenido de la sección (colapsable) - Definir ANTES del header para poder usarlo en el listener
                val contenidoSection = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                    setPadding(16, 16, 16, 16)
                    setBackgroundColor(resources.getColor(R.color.surface_color, null))
                }
                
                // Header de la sección (colapsable, con gradiente como en la app web)
                var sectionExpandida = false
                val icono = if (esInicio) "🌅" else "🌇"
                val colorGradiente = if (esInicio) {
                    android.graphics.Color.parseColor("#f5576c") // Rosa/Rojo
                } else {
                    android.graphics.Color.parseColor("#4facfe") // Azul
                }
                
                val headerSection = TextView(this).apply {
                    val texto = if (sectionExpandida) {
                        "▼ $icono $titulo (${grupos.size} productos)"
                    } else {
                        "▶ $icono $titulo (${grupos.size} productos)"
                    }
                    text = texto
                    textSize = 18f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(resources.getColor(R.color.web_text_light, null))
                    setBackgroundColor(colorGradiente)
                    setPadding(16, 16, 16, 16)
                    setOnClickListener {
                        sectionExpandida = !sectionExpandida
                        contenidoSection.visibility = if (sectionExpandida) View.VISIBLE else View.GONE
                        text = if (sectionExpandida) {
                            "▼ $icono $titulo (${grupos.size} productos)"
                        } else {
                            "▶ $icono $titulo (${grupos.size} productos)"
                        }
                    }
                }
                sectionWrap.addView(headerSection)
                
                if (grupos.isEmpty()) {
                    val txtVacio = TextView(this).apply {
                        text = "📭 Sin datos en esta sección"
                        textSize = 15f
                        setTextColor(android.graphics.Color.parseColor("#9ca3af"))
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, 30, 0, 30)
                    }
                    contenidoSection.addView(txtVacio)
                } else {
                    grupos.forEach { (codigo, lotesProducto) ->
                        // Card del producto (con borde y sombra como en la app web)
                        val cardProducto = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setBackgroundColor(resources.getColor(R.color.surface_color, null))
                            setPadding(0, 0, 0, 0)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(0, 12, 0, 0)
                            }
                        }
                        
                        // Header del card (con gradiente gris como en la app web)
                        // Agregar contador de lotes a la derecha
                        val headerCardLayout = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setBackgroundColor(android.graphics.Color.parseColor("#f9fafb"))
                            setPadding(12, 12, 12, 12)
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }
                        
                        val txtCodigo = TextView(this).apply {
                            text = codigo
                            textSize = 16f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(android.graphics.Color.parseColor("#1f2937"))
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f
                            )
                        }
                        headerCardLayout.addView(txtCodigo)
                        
                        val txtCantidadLotes = TextView(this).apply {
                            text = "${lotesProducto.size} lote${if (lotesProducto.size != 1) "s" else ""}"
                            textSize = 14f
                            setTextColor(android.graphics.Color.parseColor("#6b7280"))
                            setTypeface(null, android.graphics.Typeface.BOLD)
                        }
                        headerCardLayout.addView(txtCantidadLotes)
                        
                        cardProducto.addView(headerCardLayout)
                        
                        // Contenedor de lotes
                        val lotesContainer = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(0, 0, 0, 0)
                        }
                        
                        lotesProducto.forEachIndexed { index, lote ->
                            lotesContainer.addView(crearItemLote(lote, index))
                        }
                        
                        cardProducto.addView(lotesContainer)
                        contenidoSection.addView(cardProducto)
                    }
                }
                
                sectionWrap.addView(contenidoSection)
                layoutContenido.addView(sectionWrap)
            }
            
            // Renderizar secciones
            renderSection("INICIO DEL DÍA", registro.lotesIniciales, true)
            renderSection("FIN DEL DÍA", registro.lotesFinales, false)
            
            scrollView.addView(layoutContenido)
            layoutPrincipal.addView(scrollView)
            
            // Configurar listeners de navegación
            // Los registros están ordenados DESCENDING (más reciente primero)
            // Flecha izquierda (◀) = fechas anteriores (más antiguas) = índice mayor
            // Flecha derecha (▶) = fechas posteriores (más recientes) = índice menor
            btnAnterior.setOnClickListener {
                if (indiceActual < registros.size - 1) {
                    val indiceNuevo = indiceActual + 1 // Ir a fecha anterior (más antigua)
                    dialogActual?.dismiss()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        mostrarRegistro(indiceNuevo)
                    }, 100)
                }
            }
            
            btnSiguiente.setOnClickListener {
                if (indiceActual > 0) {
                    val indiceNuevo = indiceActual - 1 // Ir a fecha posterior (más reciente)
                    dialogActual?.dismiss()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        mostrarRegistro(indiceNuevo)
                    }, 100)
                }
            }
            
            btnCerrar.setOnClickListener {
                dialogActual?.dismiss()
            }
            
            // Crear o actualizar el diálogo
            val dialog = AlertDialog.Builder(this)
                .setView(layoutPrincipal)
                .create()
            
            // Configurar fondo del diálogo para modo oscuro
            dialog.setOnShowListener {
                val dialogBackgroundColor = if (isDarkMode()) {
                    android.graphics.Color.BLACK
                } else {
                    android.graphics.Color.WHITE
                }
                dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
            }
            
            dialogActual = dialog
            dialog.show()
        }
        
        // Mostrar el primer registro (inicializar indiceActual)
        indiceActual = 0
        mostrarRegistro(0)
    }
    
    override fun onBackPressed() {
        finish()
    }
    
}

