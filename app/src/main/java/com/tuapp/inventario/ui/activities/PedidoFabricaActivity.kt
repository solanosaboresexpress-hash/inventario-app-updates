package com.tuapp.inventario

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tuapp.inventario.model.ProductoFabrica
import com.tuapp.inventario.model.ConfiguracionPedidoFabrica
import com.tuapp.inventario.repository.PedidoFabricaRepository
import com.tuapp.inventario.repository.LocalInventarioRepository
import com.tuapp.inventario.repository.FirebaseInventarioRepository
import com.tuapp.inventario.RegistroInventario
import com.tuapp.inventario.utils.FirebaseRegionManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.tuapp.inventario.utils.DateHelper
import com.tuapp.inventario.utils.DemandaManianaContexto
import com.tuapp.inventario.utils.PedidoFabricaDemandaContextFetcher
import com.tuapp.inventario.notification.NotificationHelper
import com.google.android.material.card.MaterialCardView
import java.util.*

/**
 * Data class para almacenar pedidos de fábrica guardados
 */
data class PedidoFabricaGuardado(
    val productos: List<ProductoFabrica>,
    val configuracion: ConfiguracionPedidoFabrica
)

class PedidoFabricaActivity : AppCompatActivity() {
    
    // ✅ Cache para promedios (cambian raramente) - COMPARTIDO entre actividades
    companion object {
        @JvmStatic
        var cachePromedios: Map<String, Map<String, Double>>? = null
            private set
        private var cachePromediosLocalId: String? = null
        private var cachePromediosTimestamp: Long = 0
        private const val CACHE_PROMEDIOS_DURATION_MS = 30 * 60 * 1000L // 30 minutos
        
        @JvmStatic
        fun isPromediosCacheValid(localId: String): Boolean {
            return cachePromediosLocalId == localId && 
                   (System.currentTimeMillis() - cachePromediosTimestamp) < CACHE_PROMEDIOS_DURATION_MS &&
                   cachePromedios != null
        }
        
        @JvmStatic
        fun setPromediosCache(localId: String, promedios: Map<String, Map<String, Double>>) {
            cachePromedios = promedios
            cachePromediosLocalId = localId
            cachePromediosTimestamp = System.currentTimeMillis()
        }
        
        @JvmStatic
        fun clearPromediosCache() {
            cachePromedios = null
            cachePromediosLocalId = null
            cachePromediosTimestamp = 0
        }
    }

    private lateinit var btnVolver: Button
    private lateinit var txtTitulo: TextView
    private lateinit var layoutContenido: LinearLayout
    private lateinit var btnConfiguracion: Button
    private lateinit var btnAnterior: Button
    private lateinit var btnSiguiente: Button
    private lateinit var txtDiaActual: TextView
    private lateinit var txtPorcentajeExtra: TextView
    private lateinit var txtTendenciaVentas: TextView
    private lateinit var txtHoraAlerta: TextView
    private lateinit var txtDiasExtra: TextView
    private lateinit var txtFechaActual: TextView
    
    private lateinit var repository: PedidoFabricaRepository
    private lateinit var localRepository: LocalInventarioRepository
    private var productos: List<ProductoFabrica> = emptyList()
    private var configuracion = ConfiguracionPedidoFabrica()
    private var configuracionModificada = false
    private var diasEntrega = ConfiguracionDiasEntrega()
    private var primeraVezPedidoFabrica = true
    private var inicializandoProductos = false
    private val txtSugerencias = mutableMapOf<String, TextView>()
    
    // Variables para la integración de fecha
    private lateinit var fechaSeleccionada: String
    // Firebase (usar FirebaseRegionManager para obtener el Firestore de la región actual)
    private val firestore: FirebaseFirestore
        get() = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
    private lateinit var localId: String
    
    // Skeleton loading
    private lateinit var offlineOverlay: LinearLayout
    private lateinit var progressSkeleton: android.widget.ProgressBar
    private lateinit var txtOfflineMsg: TextView
    private lateinit var cardDemandaManiana: MaterialCardView
    private lateinit var txtDemandaManianaTitulo: TextView
    private lateinit var txtDemandaManianaCuerpo: TextView
    private lateinit var txtClimaIcono: TextView
    private lateinit var webClimaIcono: android.webkit.WebView
    private var climaAnimator: android.animation.Animator? = null
    private var demandaManianaCacheKey: String? = null
    private var demandaManianaCache: DemandaManianaContexto? = null
    private var demandaManianaRequestInflight = false
    private var skeletonTimeout: kotlinx.coroutines.Job? = null
    private var porcentajeSeleccionado: Double = 1.0
    private var diasSeleccionados: List<String> = emptyList()
    private var modoSoloLectura: Boolean = false
    private val stockInicialBatchMap = mutableMapOf<String, Int>()
    private val vencimientosBatchMap = mutableMapOf<String, VencimientoMermaInfo>()
    private var modoAdmin: Boolean = false
    
    // Usar el cache global de promedios reales desde MainActivity
    
    // Mapa para guardar el estado de los EditText durante la rotación
    private val estadoEditTexts = mutableMapOf<String, String>()
    
    /**
     * Obtiene un color del tema actual (se adapta a modo oscuro/claro)
     */
    private fun getThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
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
        setContentView(R.layout.activity_pedido_fabrica)

        // Inicializar variables para la integración de fecha
        // Firebase se inicializa dinámicamente según la región seleccionada en LoginActivity
        // 🔧 FORZAR ZONA HORARIA DE ARGENTINA PARA FECHA INICIAL
        val timeZoneInicial = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
        val sdfInicial = DateHelper.getDateFormat("yyyy-MM-dd")
        sdfInicial.timeZone = timeZoneInicial
        fechaSeleccionada = intent.getStringExtra("fechaSeleccionada")
            ?: sdfInicial.format(Date())

        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        localId = prefs.getString("localId", "default") ?: "default"

        Log.d("PedidoFabricaActivity", "📅 Fecha recibida: $fechaSeleccionada")

        // Verificar si es modo administrador (desde gestión de locales)
        modoAdmin = intent.getBooleanExtra("modoAdmin", false)
        
        // Verificar si es modo solo lectura (fecha anterior a hoy Y no es modo admin)
        // 🔧 FORZAR ZONA HORARIA DE ARGENTINA (UTC-3)
        val timeZoneHoy = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
        val sdfHoy = DateHelper.getDateFormat("yyyy-MM-dd")
        sdfHoy.timeZone = timeZoneHoy
        val fechaHoy = sdfHoy.format(Date())
        modoSoloLectura = (fechaSeleccionada < fechaHoy) && !modoAdmin
        
        // 🔍 DEBUG: Verificar zona horaria
        val calendar = DateHelper.getCalendar()
        val zonaHoraria = calendar.timeZone.id
        val offset = calendar.timeZone.getOffset(Date().time) / (1000 * 60 * 60) // en horas
        
        Log.d("CONFIG_LOAD", "🕐 DEBUG ZONA HORARIA:")
        Log.d("CONFIG_LOAD", "🕐 Zona horaria del sistema: $zonaHoraria")
        Log.d("CONFIG_LOAD", "🕐 Offset UTC: $offset horas")
        Log.d("CONFIG_LOAD", "🕐 Hora actual: ${DateHelper.getDateFormat("HH:mm:ss").format(Date())}")
        Log.d("CONFIG_LOAD", "🕐 Fecha seleccionada: $fechaSeleccionada")
        Log.d("CONFIG_LOAD", "🕐 Fecha hoy calculada: $fechaHoy")
        Log.d("ADMIN_FLOW", "🔧 DIAGNÓSTICO RECIBIDO:")
        Log.d("ADMIN_FLOW", "🔧 modoAdmin recibido: $modoAdmin")
        Log.d("ADMIN_FLOW", "🔧 fechaSeleccionada != fechaHoy: ${fechaSeleccionada != fechaHoy}")
        Log.d("ADMIN_FLOW", "🔧 Modo solo lectura calculado: $modoSoloLectura")
        Log.d("PedidoFabricaActivity", "🏪 Local ID: $localId")

        // Restaurar estado de EditText si existe (después de rotación)
        if (savedInstanceState != null) {
            @Suppress("DEPRECATION", "UNCHECKED_CAST")
            val estadoGuardado = savedInstanceState.getSerializable("estadoEditTexts") as? HashMap<String, String>
            estadoGuardado?.let {
                estadoEditTexts.clear()
                estadoEditTexts.putAll(it)
                Log.d("PedidoFabricaActivity", "📦 Estado restaurado: ${estadoEditTexts.size} EditText guardados")
            }
        }
        
        initViews()
        setupListeners()
        inicializarRepositorio()
        
        // 📈 Cargar cache de tendencias desde Firebase al inicio (una sola vez)
        cargarCacheTendenciasDesdeFirebase()
        
        // 📊 Los promedios se cargarán bajo demanda al hacer clic en un indicador (más eficiente)
        
        // Mostrar la fecha actual en la barra de navegación
        txtFechaActual.text = fechaSeleccionada
        
        // 🔧 NO cargar datos aquí - se cargarán después de obtener la configuración de Firebase
        Log.d("CONFIG_LOAD", "⏳ Saltando carga inicial de datos - se ejecutará después de Firebase")
        
        // 🔧 NO mostrar contenido inmediatamente - se mostrará después de cargar los datos
        Log.d("ADMIN_FLOW", "⏳ Esperando a que se carguen los datos antes de mostrar contenido")
        
        // Solo abrir automáticamente si no es modo solo lectura Y no es modo admin en fecha histórica
        if (!modoSoloLectura && !(modoAdmin && fechaSeleccionada != obtenerFechaHoyArgentina())) {
            Log.d("PedidoFabricaActivity", "📊 Abriendo automáticamente sección de carga de stock 15:30")
            // Abrir la sección después de un pequeño delay para que se carguen los datos
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1000) // Esperar 1 segundo para que se carguen los datos
                abrirSeccionCargarStock()
            }
        } else {
            Log.d("ADMIN_FLOW", "⏭️ NO abriendo sección de carga automáticamente - Modo admin en fecha histórica")
        }
    }

    private fun initViews() {
        btnVolver = findViewById(R.id.btnVolver)
        txtTitulo = findViewById(R.id.txtTitulo)
        layoutContenido = findViewById(R.id.layoutContenido)
        btnConfiguracion = findViewById(R.id.btnConfiguracion)
        btnAnterior = findViewById(R.id.btnAnterior)
        btnSiguiente = findViewById(R.id.btnSiguiente)
        txtDiaActual = findViewById(R.id.txtDiaActual)
        txtPorcentajeExtra = findViewById(R.id.txtPorcentajeExtra)
        txtTendenciaVentas = findViewById(R.id.txtTendenciaVentas)
        txtHoraAlerta = findViewById(R.id.txtHoraAlerta)
        txtDiasExtra = findViewById(R.id.txtDiasExtra)
        txtFechaActual = findViewById(R.id.txtFechaActual)
        
        // Inicializar skeleton loading
        offlineOverlay = findViewById(R.id.offlineOverlay)
        progressSkeleton = findViewById(R.id.progressSkeleton)
        txtOfflineMsg = findViewById(R.id.txtOfflineMsg)
        cardDemandaManiana = findViewById(R.id.cardDemandaManiana)
        txtDemandaManianaTitulo = findViewById(R.id.txtDemandaManianaTitulo)
        txtDemandaManianaCuerpo = findViewById(R.id.txtDemandaManianaCuerpo)
        txtClimaIcono = findViewById(R.id.txtClimaIcono)
        webClimaIcono = findViewById(R.id.webClimaIcono)
        setupWebClimaIcono()
    }

    private fun setupListeners() {
        btnVolver.setOnClickListener {
            @Suppress("DEPRECATION")
            onBackPressed()
        }
        
        btnConfiguracion.setOnClickListener {
            mostrarConfiguracion()
        }
        
        // 🔧 AGREGAR listeners para navegación de fechas
        btnAnterior.setOnClickListener {
            navegarFechaAnterior()
        }
        
        btnSiguiente.setOnClickListener {
            navegarFechaSiguiente()
        }
    }

    private fun inicializarRepositorio() {
        localRepository = (application as InventarioApplication).localRepository
        val localId = obtenerLocalId()
        repository = PedidoFabricaRepository(localRepository, localId)
        configuracion = repository.getConfiguracion()
        
        // Cargar configuración desde Firebase al inicio
        Log.d("CONFIG_LOAD", "🚀 ONCREATE - INICIANDO CARGA DE CONFIGURACIÓN")
        Log.d("CONFIG_LOAD", "📍 LocalId desde onCreate: ${obtenerLocalId()}")
        cargarConfiguracionDesdeFirebase()
        cargarDiasEntrega()
        
        // 🔍 DEBUG: Verificar valores después de cargar
        Log.d("CONFIG_LOAD", "🔍 DESPUÉS DE CARGAR - Configuración actual:")
        Log.d("CONFIG_LOAD", "📊 configuracion.porcentajeExtra: ${configuracion.porcentajeExtra}")
        Log.d("CONFIG_LOAD", "⏰ configuracion.horaAlerta: ${configuracion.horaAlerta}")
        Log.d("CONFIG_LOAD", "📅 diasEntrega: $diasEntrega")
        
        // 🔧 NO LLAMAR cargarDatosParaFecha aquí - se llamará después de cargar Firebase
        Log.d("CONFIG_LOAD", "⏳ Esperando a que termine la carga de Firebase antes de cargar datos")
    }
    
    /**
     * Navega a la fecha anterior
     */
    private fun navegarFechaAnterior() {
        try {
            val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
            val fechaActual = sdf.parse(fechaSeleccionada) ?: Date()
            val calendar = DateHelper.getCalendar()
            calendar.time = fechaActual
            calendar.add(Calendar.DAY_OF_MONTH, -1)
            
            val nuevaFecha = sdf.format(calendar.time)
            Log.d("ADMIN_FLOW", "🔙 Navegando a fecha anterior: $fechaSeleccionada -> $nuevaFecha")
            
            fechaSeleccionada = nuevaFecha
            cargarDatosParaNuevaFecha()
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error navegando a fecha anterior", e)
        }
    }
    
    /**
     * Navega a la fecha siguiente
     */
    private fun navegarFechaSiguiente() {
        try {
            val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
            val fechaActual = sdf.parse(fechaSeleccionada) ?: Date()
            val calendar = DateHelper.getCalendar()
            calendar.time = fechaActual
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            
            val nuevaFecha = sdf.format(calendar.time)
            Log.d("ADMIN_FLOW", "🔜 Navegando a fecha siguiente: $fechaSeleccionada -> $nuevaFecha")
            
            fechaSeleccionada = nuevaFecha
            cargarDatosParaNuevaFecha()
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error navegando a fecha siguiente", e)
        }
    }
    
    /**
     * Carga datos para la nueva fecha después de navegar
     */
    private fun cargarDatosParaNuevaFecha() {
        Log.d("ADMIN_FLOW", "🔄 Cargando datos para nueva fecha: $fechaSeleccionada")
        
        // Actualizar la fecha en la barra de navegación
        txtFechaActual.text = fechaSeleccionada
        
        // Actualizar modo solo lectura según la nueva fecha
        // 🔧 FORZAR ZONA HORARIA DE ARGENTINA (UTC-3)
        val timeZoneNavegacion = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
        val sdfNavegacion = DateHelper.getDateFormat("yyyy-MM-dd")
        sdfNavegacion.timeZone = timeZoneNavegacion
        val fechaHoy = sdfNavegacion.format(Date())
        modoSoloLectura = (fechaSeleccionada < fechaHoy) && !modoAdmin
        
        // 🔧 RESTAURAR CONFIGURACIÓN ACTUAL SI VUELVE AL DÍA DE HOY
        if (fechaSeleccionada == fechaHoy && !modoAdmin) {
            Log.d("CONFIG_LOAD", "🔄 Volviendo al día actual - Restaurando configuración desde Firebase")
            cargarConfiguracionDesdeFirebase()
        }
        
        Log.d("ADMIN_FLOW", "🔧 Modo solo lectura actualizado: $modoSoloLectura")
        
        // Limpiar contenido actual
        layoutContenido.removeAllViews()
        
        // Cargar datos para la nueva fecha
        lifecycleScope.launch {
            cargarDatosParaFecha(fechaSeleccionada)
        }
    }

    private fun obtenerLocalId(): String {
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        return prefs.getString("localId", "") ?: ""
    }
    
    private fun obtenerLocalNombre(): String {
        return try {
            val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
            val localNombre = prefs.getString("localNombre", "")
            if (localNombre.isNullOrEmpty()) {
                Log.e("PedidoFabricaActivity", "No se encontró localNombre en SharedPreferences")
                "NO SELECCIONADO"
            } else {
                Log.d("PedidoFabricaActivity", "LocalNombre obtenido: $localNombre")
                localNombre
            }
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error obteniendo localNombre", e)
            "NO SELECCIONADO"
        }
    }

    private fun cargarDatos() {
        lifecycleScope.launch {
            try {
                println("🔍 DEBUG: Cargando datos automáticamente...")
                showSkeletonLoading("Cargando productos...")
                
                // Primero, asegurar que los promedios reales estén cargados para este local
                cargarPromediosRealesDelLocal()
                
                // 🔧 En modo admin O modo solo lectura con fecha histórica, NO cargar productos desde promedios
                if (!((modoAdmin || modoSoloLectura) && fechaSeleccionada != obtenerFechaHoyArgentina())) {
                    // Cargar los promedios desde los datos reales (solo para fechas actuales)
                    repository.cargarProductosDesdePromedios()
                } else {
                    println("🔍 DEBUG: Modo admin/solo lectura en fecha histórica - NO cargando productos desde promedios en cargarDatos()")
                }
                
                // Esperar un poco para que se completen los promedios
                kotlinx.coroutines.delay(2000)
                
                // 🔧 En modo admin O modo solo lectura con fecha histórica, NO cargar productos ficticios
                if ((modoAdmin || modoSoloLectura) && fechaSeleccionada != obtenerFechaHoyArgentina()) {
                    println("🔍 DEBUG: Modo admin/solo lectura en fecha histórica - NO cargando productos ficticios")
                    return@launch
                }
                
                // 🔧 FIX: Solo cargar productos si no hay productos ya cargados desde Firebase
                if (productos.isEmpty()) {
                // Obtener los productos directamente (solo para fechas actuales)
                productos = repository.getProductosDirectos()
                println("🔍 DEBUG: Productos cargados automáticamente: ${productos.size}")
                
                // Si no hay productos, crear productos básicos como fallback
                if (productos.isEmpty()) {
                    println("🔍 DEBUG: No hay productos cargados, creando productos básicos como fallback")
                    productos = crearProductosBasicos()
                    }
                } else {
                    println("🔍 DEBUG: Productos ya cargados desde Firebase, no sobrescribiendo: ${productos.size}")
                }
                
                productos.forEach { producto ->
                    println("🔍 DEBUG: Producto cargado: ${producto.codigo} - ${producto.nombre}")
                }
                mostrarContenidoPedido()
            } catch (e: Exception) {
                println("🔍 DEBUG: Error cargando datos automáticamente: ${e.message}")
                e.printStackTrace()
            } finally {
                hideSkeletonLoading()
            }
        }
    }
    
    /**
     * Función principal para cargar datos para una fecha específica
     * Intenta leer desde pedidos_fabrica/{fecha}, si no existe reconstruye desde registros
     */
    private suspend fun cargarDatosParaFecha(fecha: String) {
        try {
            Log.d("ADMIN_FLOW", "📅 Cargando datos para fecha: $fecha")
            
            // Intentar cargar desde pedidos_fabrica/{fecha}
            Log.d("ADMIN_FLOW", "🔍 BUSCANDO pedido en Firebase para fecha: $fecha")
            Log.d("ADMIN_FLOW", "📍 Ruta buscada: locales/$localId/pedidos_fabrica/$fecha")
            
            val pedidoExistente = cargarPedidoExistente(fecha)
            if (pedidoExistente != null) {
                Log.d("ADMIN_FLOW", "✅ Pedido existente encontrado para $fecha")
                Log.d("ADMIN_FLOW", "📊 Productos cargados: ${pedidoExistente.productos.size}")
                
                // 🔍 DEBUG: Mostrar detalles de los primeros productos
                pedidoExistente.productos.take(3).forEach { producto ->
                    Log.d("ADMIN_FLOW", "🔍 Producto: ${producto.nombre} - Stock: ${producto.stockActual} - Sugerencia: ${producto.pedidoSugerido} - Pedido: ${producto.pedidoFinal}")
                }
                
                // 🔧 ORDENAR productos correctamente - SIN PANIFICADORA (movidos a PedidoPanificadoraActivity)
                val ordenProductos = listOf(
                    "JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH",  // EMPANADAS
                    "MUZZARE", "JAMON", "PEPPERO",  // PIZZAS
                    "BATATA", "MEMBRIL"  // PASTELITOS
                    // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity
                )
                
                val productosOrdenados = ordenProductos.mapNotNull { codigoOrden ->
                    pedidoExistente.productos.find { it.codigo == codigoOrden }
                }.plus(
                    pedidoExistente.productos.filter { !ordenProductos.contains(it.codigo) }
                )
                
                productos = productosOrdenados
                Log.d("ADMIN_FLOW", "🔧 Productos ordenados correctamente: ${productos.size}")
                
                porcentajeSeleccionado = pedidoExistente.configuracion.porcentajeExtra.toDouble()
                // diasSeleccionados se mantiene con el valor por defecto
                
                // 🔧 ACTUALIZAR UI con datos históricos después de cargar
                runOnUiThread {
                    // Actualizar información de días extra con datos históricos
                    actualizarInformacionDiasExtra()
                    
                    Log.d("CONFIG_LOAD", "📅 Pedido existente encontrado")
                    Log.d("CONFIG_LOAD", "📅 Fecha del pedido: $fecha")
                    Log.d("CONFIG_LOAD", "📅 Fecha de hoy: $fechaSeleccionada")
                    Log.d("CONFIG_LOAD", "📊 configuracion.porcentajeExtra: ${configuracion.porcentajeExtra}")
                    
                    // 🔧 SOLO actualizar UI si NO es el día de hoy (es histórico)
                    if (fecha != fechaSeleccionada) {
                        Log.d("CONFIG_LOAD", "📜 Es fecha histórica - Actualizando UI con configuración del pedido")
                        // Actualizar porcentaje extra con datos históricos
                        txtPorcentajeExtra.text = "% Extra: ${configuracion.porcentajeExtra}%"
                        
                        // Actualizar hora de alerta con datos históricos
                        txtHoraAlerta.text = "🔔 Alerta: ${configuracion.horaAlerta}"
                        
                        Log.d("CONFIG_LOAD", "📅 UI actualizada con datos históricos:")
                        Log.d("CONFIG_LOAD", "📊 Porcentaje: ${configuracion.porcentajeExtra}%")
                        Log.d("CONFIG_LOAD", "⏰ Hora: ${configuracion.horaAlerta}")
                        Log.d("CONFIG_LOAD", "📅 Días: $diasEntrega")
                    } else {
                        Log.d("CONFIG_LOAD", "📅 Es fecha actual - NO sobrescribiendo UI (mantiene valores de Firebase)")
                        Log.d("CONFIG_LOAD", "✅ txtPorcentajeExtra mantiene: ${txtPorcentajeExtra.text}")
                        Log.d("CONFIG_LOAD", "✅ txtHoraAlerta mantiene: ${txtHoraAlerta.text}")
                    }
                }
                
                mostrarContenidoPedido()
                return
            } else {
                // 🔧 NO se encontró pedido - INICIALIZAR productos con stock calculado (igual que en la web)
                Log.d("ADMIN_FLOW", "❌ No se encontró pedido existente para $fecha")
                Log.d("ADMIN_FLOW", "🔄 Inicializando productos con stock calculado automáticamente")
                inicializarProductosConStockCalculado()
                Log.d("ADMIN_FLOW", "✅ Productos inicializados con stock calculado")
            }
            
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "❌ Error cargando datos para fecha $fecha", e)
            // 🔧 SIEMPRE mostrar mensaje de sin datos si hay error - NO reconstruir nada
            mostrarMensajeSinDatosParaFecha(fecha)
        }
    }

    /**
     * Inicializa productos con stock calculado automáticamente (igual que en la web)
     */
    private fun inicializarProductosConStockCalculado() {
        lifecycleScope.launch {
            try {
                // Orden específico de productos
                val ordenProductos = listOf(
                    "JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH",  // EMPANADAS
                    "MUZZARE", "JAMON", "PEPPERO",  // PIZZAS
                    "BATATA", "MEMBRIL"  // PASTELITOS
                )

                // Calcular stock inicial para cada producto
                val productosCalculados = ordenProductos.map { codigo ->
                    val nombreProducto = obtenerNombreProducto(codigo)
                    val stockInicial = calcularStockInicialProducto(codigo, nombreProducto)
                    val sugerencia = calcularSugerenciaPedido(codigo, stockInicial)

                    ProductoFabrica(
                        codigo = codigo,
                        nombre = nombreProducto,
                        categoria = obtenerCategoriaProducto(codigo),
                        promediosVenta = emptyMap(),
                        stockActual = stockInicial,
                        pedidoSugerido = sugerencia,
                        pedidoFinal = 0
                    )
                }

                productos = productosCalculados
                Log.d("ADMIN_FLOW", "📊 Productos inicializados: ${productos.size}")
                productos.forEach { producto ->
                    Log.d("ADMIN_FLOW", "  - ${producto.codigo}: stock=${producto.stockActual}, sugerencia=${producto.pedidoSugerido}")
                }

                runOnUiThread {
                    mostrarContenidoPedido()
                }
            } catch (e: Exception) {
                Log.e("ADMIN_FLOW", "❌ Error inicializando productos con stock calculado", e)
                mostrarMensajeSinDatosParaFecha(fechaSeleccionada)
            }
        }
    }

    /**
     * Obtiene la categoría de un producto según su código
     */
    private fun obtenerCategoriaProducto(codigo: String): String {
        return when (codigo) {
            "JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH" -> "EMPANADAS"
            "MUZZARE", "JAMON", "PEPPERO" -> "PIZZAS"
            "BATATA", "MEMBRIL" -> "PASTELITOS"
            else -> "OTROS"
        }
    }

    /**
     * Muestra un mensaje cuando no se encuentran datos para una fecha específica
     */
    private fun mostrarMensajeSinDatosParaFecha(fecha: String) {
        Log.d("ADMIN_FLOW", "🔍 EJECUTANDO mostrarMensajeSinDatosParaFecha($fecha)")
        
        // Obtener el día de la semana de la fecha
        val diaSemana = obtenerDiaSemana(fecha)
        val fechaFormateada = try {
            val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
            val fechaParseada = sdf.parse(fecha)
            val sdfMostrar = DateHelper.getDateFormat("dd/MM/yyyy")
            sdfMostrar.format(fechaParseada ?: Date())
        } catch (e: Exception) {
            fecha
        }
        
        // Configurar título
        val tituloBase = when {
            modoAdmin -> "🔧 Pedido Fábrica - $fechaSeleccionada (MODO ADMINISTRADOR)"
            modoSoloLectura -> "🏭 Pedido Fábrica - $fechaSeleccionada (Solo Lectura)"
            else -> "🏭 Pedido Fábrica"
        }
        txtTitulo.text = tituloBase
        
        // Mostrar información básica
        txtDiaActual.text = "Día: $diaSemana"
        Log.d("CONFIG_LOAD", "🚨 EN mostrarMensajeSinDatosParaFecha()")
        Log.d("CONFIG_LOAD", "🚨 configuracion.porcentajeExtra ACTUAL: ${configuracion.porcentajeExtra}")
        Log.d("CONFIG_LOAD", "🚨 txtPorcentajeExtra.text ACTUAL: ${txtPorcentajeExtra.text}")
        
        // 🔧 NO SOBRESCRIBIR - La UI ya se actualizó en cargarConfiguracionDesdeFirebase
        // txtPorcentajeExtra.text = "% Extra: ${configuracion.porcentajeExtra}%"
        Log.d("CONFIG_LOAD", "✅ NO sobrescribiendo txtPorcentajeExtra - mantiene valor de Firebase")
        
        // Limpiar contenido anterior
        layoutContenido.removeAllViews()
        
        // Crear mensaje principal
        val mensajeView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(getThemeColor(android.R.attr.colorBackground))
        }
        
        // Icono
        val iconoView = TextView(this).apply {
            text = "📋"
            textSize = 64f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        mensajeView.addView(iconoView)
        
        // Título del mensaje
        val tituloMensaje = TextView(this).apply {
            text = "No hay datos disponibles"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
            setPadding(0, 0, 0, 16)
        }
        mensajeView.addView(tituloMensaje)
        
        // Fecha
        val fechaView = TextView(this).apply {
            text = "Fecha: $fechaFormateada ($diaSemana)"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
            setPadding(0, 0, 0, 24)
        }
        mensajeView.addView(fechaView)
        
        // Mensaje explicativo
        val mensajeExplicativo = TextView(this).apply {
            text = if (modoAdmin) {
                "No se encontraron datos de pedido para esta fecha.\n\n" +
                "Esto puede ocurrir si:\n" +
                "• No se realizó pedido ese día\n" +
                "• Los datos no se guardaron correctamente\n" +
                "• No hay registros de inventario para esa fecha\n\n" +
                "En modo administrador puedes crear un pedido nuevo para esta fecha."
            } else {
                "No se encontraron datos de pedido para esta fecha.\n\n" +
                "Esto puede ocurrir si:\n" +
                "• No se realizó pedido ese día\n" +
                "• Los datos no se guardaron correctamente\n" +
                "• No hay registros de inventario para esa fecha"
            }
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
            setPadding(0, 0, 0, 32)
        }
        mensajeView.addView(mensajeExplicativo)
        
        // Botón para crear pedido (solo en modo admin, NO en modo solo lectura)
        if (modoAdmin && !modoSoloLectura) {
            val btnCrearPedido = Button(this).apply {
                text = "➕ Crear Pedido para esta Fecha"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark, null))
                setTextColor(resources.getColor(android.R.color.white, null))
                setPadding(32, 16, 32, 16)
                
                setOnClickListener {
                    // 🔧 En modo admin, permitir cargar datos para crear nuevo pedido
                    Log.d("ADMIN_FLOW", "🔧 Admin creando nuevo pedido para $fecha")
                    layoutContenido.removeAllViews()
                    cargarDatos() // Cargar productos para crear nuevo pedido
                }
            }
            mensajeView.addView(btnCrearPedido)
        }
        
        layoutContenido.addView(mensajeView)
        
        Log.d("PedidoFabricaActivity", "📋 Mostrando mensaje de sin datos para fecha $fecha")

        if (!modoSoloLectura) {
            val tz = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
            val sdfHoy = DateHelper.getDateFormat("yyyy-MM-dd").apply { timeZone = tz }
            if (fechaSeleccionada == sdfHoy.format(Date())) {
                solicitarContextoDemandaManiana()
            }
        }
    }

    /**
     * Muestra un mensaje explicando que no se pueden crear pedidos ficticios para fechas históricas
     */
    private fun mostrarMensajeCrearPedidoNoDisponible() {
        // Limpiar contenido anterior
        layoutContenido.removeAllViews()
        
        // Crear mensaje principal
        val mensajeView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(getThemeColor(android.R.attr.colorBackground))
        }
        
        // Icono
        val iconoView = TextView(this).apply {
            text = "⚠️"
            textSize = 64f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        mensajeView.addView(iconoView)
        
        // Título del mensaje
        val tituloMensaje = TextView(this).apply {
            text = "No se pueden crear pedidos ficticios"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
            setPadding(0, 0, 0, 16)
        }
        mensajeView.addView(tituloMensaje)
        
        // Mensaje explicativo
        val mensajeExplicativo = TextView(this).apply {
            text = "Para fechas históricas, los pedidos deben crearse con datos reales de inventario.\n\n" +
                   "Si necesitas crear un pedido para esta fecha:\n" +
                   "• Debe haber registros de inventario reales\n" +
                   "• Debe haber datos de stock final del día\n" +
                   "• Los datos deben estar guardados en Firebase\n\n" +
                   "No se pueden generar pedidos ficticios sin datos históricos reales."
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
            setPadding(0, 0, 0, 32)
        }
        mensajeView.addView(mensajeExplicativo)
        
        // Botón para volver
        val btnVolver = Button(this).apply {
            text = "🔙 Volver"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark, null))
            setTextColor(resources.getColor(android.R.color.white, null))
            setPadding(32, 16, 32, 16)
            
            setOnClickListener {
                // Volver al mensaje anterior
                mostrarMensajeSinDatosParaFecha(fechaSeleccionada)
            }
        }
        mensajeView.addView(btnVolver)
        
        layoutContenido.addView(mensajeView)
        
        Log.d("PedidoFabricaActivity", "⚠️ Mostrando mensaje de que no se pueden crear pedidos ficticios")
        Log.d("ADMIN_FLOW", "✅ mostrarMensajeSinDatosParaFecha() COMPLETADO - Mensaje mostrado en pantalla")
    }
    
    /**
     * Carga un pedido existente desde Firestore
     */
    private suspend fun cargarPedidoExistente(fecha: String): PedidoFabricaGuardado? {
        return try {
            Log.d("PedidoFabricaActivity", "🔍 CONSULTANDO Firebase...")
            Log.d("PedidoFabricaActivity", "📍 Colección: locales/$localId/pedidos_fabrica")
            Log.d("PedidoFabricaActivity", "📄 Documento: $fecha")
            
            val document = firestore
                .collection("locales")
                .document(localId)
                .collection("pedidos_fabrica")
                .document(fecha)
                .get()
                .await()
            
            Log.d("PedidoFabricaActivity", "📋 Documento existe: ${document.exists()}")
            
            if (document.exists()) {
                Log.d("PedidoFabricaActivity", "📄 Documento encontrado para fecha $fecha")
                @Suppress("UNCHECKED_CAST")
                val productosData = document.get("productos") as? List<Map<String, Any>> ?: emptyList()
                @Suppress("UNCHECKED_CAST")
                val configuracionData = document.get("configuracion") as? Map<String, Any> ?: emptyMap()
                Log.d("PedidoFabricaActivity", "📊 Datos de productos: ${productosData.size} elementos")
                Log.d("PedidoFabricaActivity", "⚙️ Configuración encontrada: ${configuracionData.isNotEmpty()}")
                
                // Pre-calculo en lote de stock inicial para evitar bloqueos
                val codigosYNombres = productosData.map { productoMap ->
                    val loadedNombre = productoMap["nombre"] as? String ?: ""
                    val cleanedNombre = if (loadedNombre.contains(" - ")) loadedNombre.split(" - ").lastOrNull() ?: loadedNombre else loadedNombre
                    val cleanedCodigo = obtenerCodigoProducto(cleanedNombre)
                    Pair(cleanedCodigo, cleanedNombre)
                }
                precalcularStockInicialLote(codigosYNombres)

                val productos = productosData.map { productoMap ->
                    val loadedNombre = productoMap["nombre"] as? String ?: ""
                    
                    // 🔧 LIMPIAR los valores cargados para asegurar el formato correcto
                    val cleanedNombre = if (loadedNombre.contains(" - ")) {
                        loadedNombre.split(" - ").lastOrNull() ?: loadedNombre
                    } else {
                        loadedNombre
                    }
                    val cleanedCodigo = obtenerCodigoProducto(cleanedNombre) // Obtener el código correcto del nombre limpio
                    
                    // 🔧 RECALCULAR stock actual siempre (igual que en la web)
                    val stockActual = calcularStockInicialProducto(cleanedCodigo, cleanedNombre)
                    
                    ProductoFabrica(
                        codigo = cleanedCodigo, // Usar el código limpio
                        nombre = cleanedNombre, // Usar el nombre limpio
                        categoria = "EMPANADAS", // Default category
                        promediosVenta = emptyMap(), // Will be populated later if needed
                        stockActual = stockActual, // Usar stock recalculado en lugar del guardado
                        pedidoSugerido = (productoMap["sugerencia"] as? Number)?.toInt() ?: 0,
                        pedidoFinal = (productoMap["pedido"] as? Number)?.toInt() ?: 0
                    )
                }
                
                // Cargar días de entrega históricos
                @Suppress("UNCHECKED_CAST")
                val diasEntregaData = configuracionData["diasEntrega"] as? Map<String, Boolean> ?: emptyMap()
                val diasEntregaHistoricos = ConfiguracionDiasEntrega(
                    lunes = diasEntregaData["lunes"] ?: true,
                    martes = diasEntregaData["martes"] ?: true,
                    miercoles = diasEntregaData["miercoles"] ?: true,
                    jueves = diasEntregaData["jueves"] ?: true,
                    viernes = diasEntregaData["viernes"] ?: true,
                    sabado = diasEntregaData["sabado"] ?: true,
                    domingo = diasEntregaData["domingo"] ?: true
                )
                
                val configuracion = ConfiguracionPedidoFabrica(
                    porcentajeExtra = (configuracionData["porcentajeExtra"] as? Number)?.toInt() ?: 30,
                    horaAlerta = configuracionData["horaAlerta"] as? String ?: "15:30",
                    usarAjusteTendencia = configuracionData["usarAjusteTendencia"] as? Boolean ?: true
                )
                
                // 🔥 CARGAR información de "para cuándo es el pedido" y fecha de creación
                val paraFecha = configuracionData["paraFecha"] as? String ?: ""
                val fechaCreacion = configuracionData["fechaCreacion"] as? com.google.firebase.Timestamp
                
                // Guardar la configuración histórica para usar en la UI
                this.configuracion = configuracion
                this.diasEntrega = diasEntregaHistoricos
                
                // 🔥 SINCRONIZAR porcentajeSeleccionado con la configuración histórica
                this.porcentajeSeleccionado = configuracion.porcentajeExtra.toDouble()
                
                // 🔥 MOSTRAR información de fecha en la UI si estamos viendo un pedido histórico
                runOnUiThread {
                    if (paraFecha.isNotEmpty()) {
                        txtDiasExtra.text = paraFecha
                        txtDiasExtra.visibility = View.VISIBLE
                    }
                    
                    // Actualizar el título con información de fecha de creación si existe
                    if (fechaCreacion != null) {
                        val sdf = DateHelper.getDateFormat("dd/MM/yyyy HH:mm")
                        val fechaCreacionStr = sdf.format(fechaCreacion.toDate())
                        Log.d("PedidoFabricaActivity", "📅 Pedido creado el: $fechaCreacionStr")
                    }
                }
                
                Log.d("PedidoFabricaActivity", "📅 Configuración histórica cargada:")
                Log.d("PedidoFabricaActivity", "📊 Porcentaje extra: ${configuracion.porcentajeExtra}")
                Log.d("PedidoFabricaActivity", "⏰ Hora alerta: ${configuracion.horaAlerta}")
                Log.d("PedidoFabricaActivity", "📅 Días de entrega: $diasEntregaHistoricos")
                Log.d("PedidoFabricaActivity", "📅 Para fecha: $paraFecha")
                Log.d("PedidoFabricaActivity", "🔄 porcentajeSeleccionado sincronizado: ${this.porcentajeSeleccionado}")
                
                PedidoFabricaGuardado(productos, configuracion)
            } else {
                Log.d("PedidoFabricaActivity", "❌ Documento NO existe para fecha $fecha")
                null
            }
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error cargando pedido existente", e)
            null
        }
    }
    
    /**
     * Reconstruye un pedido desde los registros de inventario
     */
    private suspend fun reconstruirPedidoDesdeRegistros(fecha: String): PedidoFabricaGuardado? {
        return try {
            val localRepository = (application as InventarioApplication).localRepository
            
            // Obtener registros del día específico
            val registros = localRepository.getAllRegistros(localId).first()
            val registrosDelDia = registros.filter { it.fecha == fecha }
            
            if (registrosDelDia.isEmpty()) {
                Log.w("PedidoFabricaActivity", "⚠️ No hay registros para la fecha $fecha")
                Log.d("ADMIN_FLOW", "❌ No se encontraron registros para $fecha - mostrando mensaje de sin datos")
                return null
            }
            
            Log.d("ADMIN_FLOW", "📊 Registros encontrados para $fecha: ${registrosDelDia.size}")
            
            // Buscar Stock Final del día
            val stockFinal = registrosDelDia.filter { it.tipo == "Stock Final" }.flatMap { it.productos }
            if (stockFinal.isEmpty()) {
                Log.w("PedidoFabricaActivity", "⚠️ No hay Stock Final para la fecha $fecha")
                Log.d("ADMIN_FLOW", "❌ No se encontró Stock Final para $fecha - mostrando mensaje de sin datos")
                return null
            }
            
            Log.d("ADMIN_FLOW", "📦 Stock Final encontrado para $fecha: ${stockFinal.size} productos")
            
            // Orden específico de productos (igual que en mostrarContenidoPedido)
            val ordenProductos = listOf(
                "JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH",  // EMPANADAS
                "MUZZARE", "JAMON", "PEPPERO",  // PIZZAS
                "BATATA", "MEMBRIL"  // PASTELITOS
                // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity
            )
            
            // Calcular sugerencias basadas en promedios reales y ordenar correctamente
            val productosCalculados = stockFinal.map { productoStock ->
                // 🔧 LIMPIAR nombre del producto PRIMERO (quitar duplicación)
                val nombreLimpio = if (productoStock.nombre.contains(" - ")) {
                    productoStock.nombre.split(" - ")[1] // Tomar solo la parte después del código
                } else {
                    productoStock.nombre
                }
                
                // Buscar el código real del producto usando el nombre LIMPIO
                val codigoReal = obtenerCodigoProducto(nombreLimpio)
                // Calcular stock inicial inteligente: Stock Final ayer + Ingreso hoy (o + Pedido ayer si no hay ingreso)
                val stockInicial = calcularStockInicialProducto(codigoReal, nombreLimpio)
                val sugerencia = calcularSugerenciaParaProducto(nombreLimpio, fecha, stockInicial)
                
                ProductoFabrica(
                    codigo = codigoReal,
                    nombre = nombreLimpio,
                    categoria = productoStock.categoria,
                    promediosVenta = emptyMap(), // Will be populated later if needed
                    stockActual = stockInicial,
                    pedidoSugerido = sugerencia,
                    pedidoFinal = sugerencia
                )
            }
            
            // Ordenar productos según el orden específico
            val productos = ordenProductos.mapNotNull { codigoOrden ->
                productosCalculados.find { it.codigo == codigoOrden }
            }.plus(
                // Agregar productos que no están en el orden específico al final
                productosCalculados.filter { !ordenProductos.contains(it.codigo) }
            )
            
            PedidoFabricaGuardado(productos, ConfiguracionPedidoFabrica())
            
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error reconstruyendo pedido desde registros", e)
            null
        }
    }
    
    /**
     * Obtiene el código real de un producto basado en su nombre
     */
    private fun obtenerCodigoProducto(nombreProducto: String): String {
        // Mapeo de nombres a códigos (esto debería venir de la base de datos)
        val mapeoNombres = mapOf(
            "Jamón y Queso" to "JQ",
            "Pollo BBQ" to "PB",
            "Carne Suave" to "CS",
            "Pollo" to "PO",
            "Carne Picante" to "CP",
            "Humita" to "HU",
            "Espinaca" to "ES",
            "Calabaza y Queso" to "CQ",
            "Roquefort y Jamón" to "RJ",
            "Queso y Cebolla" to "QC",
            "Cerdo y Barbacoa" to "CB",
            "Cheeseburger" to "CH",
            "Muzzarella" to "MUZZARE",
            "Jamón" to "JAMON",
            "Pepperoni" to "PEPPERO",
            "Batata" to "BATATA",
            "Membrillo" to "MEMBRIL"
            // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity - ya no se mapean aquí
        )
        return mapeoNombres[nombreProducto] ?: nombreProducto
    }
    
    /**
     * Calcula sugerencia para un producto específico basada en promedios reales
     */
    private fun calcularSugerenciaParaProducto(nombreProducto: String, fecha: String, stockActual: Int): Int {
        try {
            val codigo = obtenerCodigoProducto(nombreProducto)
            Log.d("PedidoFabricaActivity", "🔍 Calculando sugerencia para $nombreProducto (código: $codigo) en fecha $fecha con stock $stockActual")
            
            // Usar la función completa de cálculo de sugerencias con el stock real del día
            return calcularSugerenciaPedido(codigo, stockActual)
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error calculando sugerencia para $nombreProducto", e)
            return 0
        }
    }
    
    /**
     * Obtiene el día de la semana de una fecha
     */
    private fun obtenerDiaSemana(fecha: String): String {
        return try {
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val date = formato.parse(fecha) ?: Date()
            val calendar = DateHelper.getCalendar().apply { time = date }
            
            when (calendar.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SUNDAY -> "DOMINGO"
                Calendar.MONDAY -> "LUNES"
                Calendar.TUESDAY -> "MARTES"
                Calendar.WEDNESDAY -> "MIERCOLES"
                Calendar.THURSDAY -> "JUEVES"
                Calendar.FRIDAY -> "VIERNES"
                Calendar.SATURDAY -> "SABADO"
                else -> "LUNES"
            }
        } catch (e: Exception) {
            "LUNES"
        }
    }
    
    /**
     * Carga los promedios reales específicos del local actual en el cache global
     */
    private suspend fun cargarPromediosRealesDelLocal() {
        try {
            val localId = obtenerLocalId()
            
            Log.d("PedidoFabricaActivity", "🔄 Cargando promedios reales para local: $localId")
            
            // 🚀 OPTIMIZACIÓN: Intentar cargar desde promedios generales guardados primero
            val promediosGuardados = cargarPromediosGeneralesGuardados(localId)
            
            if (promediosGuardados.isNotEmpty()) {
                Log.d("PedidoFabricaActivity", "✅ Promedios generales cargados desde cache: ${promediosGuardados.size} días")
                MainActivity.promediosReales = promediosGuardados
                MainActivity.promediosCargados = true
                return
            }
            
            // 🔄 FALLBACK: Si no hay promedios guardados, calcular desde registros históricos
            Log.d("PedidoFabricaActivity", "⚠️ No hay promedios guardados, calculando desde registros históricos...")
            val localRepository = (application as InventarioApplication).localRepository
            
            localRepository.getAllRegistros(localId).collect { registros ->
                if (isFinishing || isDestroyed) return@collect
                
                if (registros.isNotEmpty()) {
                    val promediosCalculados = calcularPromediosPorDia(registros)
                    
                    // Actualizar el cache global con los datos de este local específico
                    MainActivity.promediosReales = promediosCalculados
                    MainActivity.promediosCargados = true
                    
                    Log.d("PedidoFabricaActivity", "✅ Promedios calculados desde registros: ${promediosCalculados.size} días")
                    
                    // Log detallado de los datos cargados
                    promediosCalculados.forEach { (dia, productos) ->
                        Log.d("PedidoFabricaActivity", "📊 $dia: ${productos.size} productos con datos")
                        productos.forEach { (producto, promedio) ->
                            if (promedio > 0) {
                                Log.d("PedidoFabricaActivity", "  - $producto: $promedio")
                            }
                        }
                    }
                } else {
                    Log.w("PedidoFabricaActivity", "⚠️ No hay registros para el local $localId")
                }
                return@collect // Solo tomar la primera emisión
            }
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "❌ Error cargando promedios reales del local: ${e.message}")
        }
    }
    
    /**
     * 🚀 OPTIMIZACIÓN: Carga los promedios generales SOLO desde Firebase
     */
    private suspend fun cargarPromediosGeneralesGuardados(localId: String): Map<String, Map<String, Double>> {
        // Cargar SOLO desde Firebase (no SharedPreferences ni datos hardcodeados)
        val promediosFirebase = cargarPromediosGeneralesDesdeFirebase(localId)
        if (promediosFirebase.isNotEmpty()) {
            Log.d("PROMEDIO_GENERAL", "✅ Promedios cargados desde Firebase: ${promediosFirebase.size} días")
            return promediosFirebase
        }
        
        Log.d("PROMEDIO_GENERAL", "⚠️ No hay promedios guardados en Firebase para local $localId")
        return emptyMap()
    }
    
    /**
     * Carga los promedios generales desde Firebase
     */
    private suspend fun cargarPromediosGeneralesDesdeFirebase(localId: String): Map<String, Map<String, Double>> {
        return try {
            // ✅ OPTIMIZACIÓN: Usar cache si está disponible
            if (isPromediosCacheValid(localId) && cachePromedios != null) {
                Log.d("PROMEDIO_GENERAL", "📦 Promedios - Usando cache (ahorra lecturas de Firebase)")
                return cachePromedios!!
            }
            
            Log.d("PROMEDIO_GENERAL", "📦 Promedios - Cache expirado o no existe, consultando Firebase...")
            val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
            val docRef = firestore.collection("locales")
                .document(localId)
                .collection("promedios_generales")
                .document("promedios")
            
            val document = docRef.get().await()
            
            if (!document.exists()) {
                Log.d("PROMEDIO_GENERAL", "⚠️ No hay promedios en Firebase para local $localId")
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
            
            // ✅ Guardar en cache
            cachePromedios = promedios
            cachePromediosLocalId = localId
            cachePromediosTimestamp = System.currentTimeMillis()
            
            Log.d("PROMEDIO_GENERAL", "✅ Promedios parseados desde Firebase: ${promedios.size} días (cache actualizado)")
            
            // Log detallado de los productos cargados
            promedios.forEach { (dia, productos) ->
                Log.d("PROMEDIO_GENERAL", "  📊 $dia: ${productos.size} productos")
                productos.entries.take(5).forEach { (producto, promedio) ->
                    Log.d("PROMEDIO_GENERAL", "    - $producto: $promedio")
                }
            }
            
            promedios
            
        } catch (e: Exception) {
            Log.w("PROMEDIO_GENERAL", "⚠️ Error cargando promedios desde Firebase: ${e.message}")
            e.printStackTrace()
            emptyMap()
        }
    }
    
    
    /**
     * Crea productos básicos como fallback cuando no hay datos históricos
     */
    private fun crearProductosBasicos(): List<ProductoFabrica> {
        val productosBasicos = mutableListOf<ProductoFabrica>()
        
        // Empanadas
        val empanadas = listOf(
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
            "CB" to "CB - Queso y Albahaca",
            "CH" to "CH - Cheeseburger"
        )
        
        empanadas.forEach { (codigo, nombre) ->
            productosBasicos.add(ProductoFabrica(
                codigo = codigo,
                nombre = nombre,
                categoria = "EMPANADAS",
                promediosVenta = emptyMap() // Sin promedios históricos
            ))
        }
        
        // Pizzas
        val pizzas = listOf(
            "MUZZA" to "Pizza Muzzarella",
            "JAMON" to "Pizza Jamón",
            "PEPPER" to "Pizza Pepperoni"
        )
        
        pizzas.forEach { (codigo, nombre) ->
            productosBasicos.add(ProductoFabrica(
                codigo = codigo,
                nombre = nombre,
                categoria = "PIZZAS",
                promediosVenta = emptyMap()
            ))
        }
        
        // Pastelitos
        val pastelitos = listOf(
            "BATATA" to "Pastelito Batata",
            "MEMBRIL" to "Pastelito Membrillo"
        )
        
        pastelitos.forEach { (codigo, nombre) ->
            productosBasicos.add(ProductoFabrica(
                codigo = codigo,
                nombre = nombre,
                categoria = "PASTELITOS",
                promediosVenta = emptyMap()
            ))
        }
        
        // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity - ya no se crean aquí
        
        Log.d("PedidoFabricaActivity", "✅ Creados ${productosBasicos.size} productos básicos como fallback")
        return productosBasicos
    }
    
    private fun cargarPromedios() {
        println("🔍 DEBUG: cargarPromedios() llamado")
        
        // 🔧 En modo admin O modo solo lectura con fecha histórica, NO cargar productos ficticios
        if ((modoAdmin || modoSoloLectura) && fechaSeleccionada != obtenerFechaHoyArgentina()) {
            println("🔍 DEBUG: Modo admin/solo lectura en fecha histórica - NO cargando productos en cargarPromedios()")
            return
        }
        
        lifecycleScope.launch {
            try {
                println("🔍 DEBUG: Mostrando toast de carga...")
                Toast.makeText(this@PedidoFabricaActivity, "Cargando promedios de ventas...", Toast.LENGTH_SHORT).show()
                
                println("🔍 DEBUG: Llamando a repository.cargarProductosDesdePromedios()...")
                // 🔧 En modo admin O modo solo lectura con fecha histórica, NO cargar productos desde promedios
                if (!((modoAdmin || modoSoloLectura) && fechaSeleccionada != obtenerFechaHoyArgentina())) {
                    // Cargar los promedios desde los datos reales (solo para fechas actuales)
                    repository.cargarProductosDesdePromedios()
                } else {
                    println("🔍 DEBUG: Modo admin/solo lectura en fecha histórica - NO cargando productos desde promedios")
                }
                
                // Esperar un poco para que se completen los promedios
                kotlinx.coroutines.delay(3000)
                
                println("🔍 DEBUG: Obteniendo productos del repositorio...")
                // 🔧 En modo admin O modo solo lectura con fecha histórica, NO cargar productos ficticios
                if ((modoAdmin || modoSoloLectura) && fechaSeleccionada != obtenerFechaHoyArgentina()) {
                    println("🔍 DEBUG: Modo admin/solo lectura en fecha histórica - NO cargando productos en cargarPromedios()")
                    return@launch // Salir del launch
                }
                
                // Obtener los productos directamente (solo para fechas actuales)
                productos = repository.getProductosDirectos()
                println("🔍 DEBUG: Productos recibidos del repositorio: ${productos.size}")
                productos.forEach { producto ->
                    println("🔍 DEBUG: Producto: ${producto.nombre} - Promedios: ${producto.promediosVenta}")
                }
                
                if (productos.isNotEmpty()) {
                    println("🔍 DEBUG: Productos cargados exitosamente: ${productos.size}")
                    Toast.makeText(this@PedidoFabricaActivity, "Promedios cargados: ${productos.size} productos", Toast.LENGTH_LONG).show()
                } else {
                    println("🔍 DEBUG: No se encontraron productos")
                    Toast.makeText(this@PedidoFabricaActivity, "No se encontraron datos de ventas. Asegúrate de tener registros de inventario.", Toast.LENGTH_LONG).show()
                }
                println("🔍 DEBUG: Llamando a mostrarContenidoPedido()...")
                mostrarContenidoPedido()
            } catch (e: Exception) {
                println("🔍 DEBUG: Error en cargarPromedios: ${e.message}")
                e.printStackTrace()
                Toast.makeText(this@PedidoFabricaActivity, "Error cargando promedios: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun mostrarContenidoPedido() {
        Log.d("ADMIN_FLOW", "🔍 EJECUTANDO mostrarContenidoPedido()")
        Log.d("ADMIN_FLOW", "📊 Productos disponibles: ${productos.size}")
        
        // 🔍 DEBUG: Mostrar los primeros 3 productos que se van a mostrar
        productos.take(3).forEach { producto ->
            Log.d("ADMIN_FLOW", "🔍 Mostrando producto: ${producto.codigo} - ${producto.nombre} - Stock: ${producto.stockActual} - Sugerencia: ${producto.pedidoSugerido}")
        }
        
        // Activar bandera de inicialización para evitar cálculos automáticos
        inicializandoProductos = true
        
        // 🔥 FIX: Asegurar que los promedios estén cargados antes de mostrar
        Log.d("PROMEDIOS", "🔍 Verificando cache de promedios antes de mostrar contenido")
        if (MainActivity.promediosReales.isEmpty()) {
            Log.w("PROMEDIOS", "⚠️ Cache de promedios vacío - Cargando promedios desde Firebase...")
            lifecycleScope.launch {
                try {
                    val localId = obtenerLocalId()
                    // Cargar directamente desde Firebase
                    val promediosFirebase = cargarPromediosGeneralesDesdeFirebase(localId)
                    if (promediosFirebase.isNotEmpty()) {
                        MainActivity.promediosReales = promediosFirebase
                        MainActivity.promediosCargados = true
                        Log.d("PROMEDIOS", "✅ Promedios cargados desde Firebase: ${promediosFirebase.size} días")
                        promediosFirebase.forEach { (dia, productos) ->
                            Log.d("PROMEDIOS", "  📊 $dia: ${productos.size} productos")
                        }
                    } else {
                        Log.w("PROMEDIOS", "⚠️ No hay promedios en Firebase, intentando calcular desde registros...")
                        cargarPromediosRealesDelLocal()
                    }
                    
                    // Esperar un poco para que se carguen los promedios
                    kotlinx.coroutines.delay(500)
                    // Recalcular sugerencias con los promedios cargados
                    recalcularSugerenciasConPromedios()
                    // Mostrar contenido después de cargar promedios
                    runOnUiThread {
                        mostrarContenidoPedidoInterno()
                    }
                } catch (e: Exception) {
                    Log.e("PROMEDIOS", "❌ Error cargando promedios: ${e.message}")
                    e.printStackTrace()
                    // Mostrar contenido de todas formas
                    runOnUiThread {
                        mostrarContenidoPedidoInterno()
                    }
                }
            }
            return
        } else {
            Log.d("PROMEDIOS", "✅ Cache de promedios cargado: ${MainActivity.promediosReales.keys}")
            // Recalcular sugerencias antes de mostrar
            recalcularSugerenciasConPromedios()
        }
        
        mostrarContenidoPedidoInterno()
    }
    
    private fun mostrarContenidoPedidoInterno() {
        // Obtener información del local actual
        val localNombre = obtenerLocalNombre()
        
        // Configurar título con indicador de local y modo
        val tituloBase = when {
            modoAdmin -> "🔧 Pedido Fábrica - $fechaSeleccionada (MODO ADMINISTRADOR)"
            modoSoloLectura -> "🏭 Pedido Fábrica - $fechaSeleccionada (Solo Lectura)"
            else -> "🏭 Pedido Fábrica"
        }
        txtTitulo.text = tituloBase
        
        // Mostrar día actual - FORZAR ACTUALIZACIÓN
        val diaActual = if (modoAdmin && fechaSeleccionada != obtenerFechaHoyArgentina()) {
            // En modo admin con fecha histórica, mostrar el día de esa fecha
            obtenerDiaSemana(fechaSeleccionada)
        } else {
            obtenerDiaActual()
        }
        Log.d("PedidoFabricaActivity", "🔍 FORZANDO ACTUALIZACIÓN: Día calculado = $diaActual")
        
        // Mostrar día actual
        val textoCompleto = "Día: $diaActual"
        Log.d("PedidoFabricaActivity", "🔍 ASIGNANDO TEXTO: $textoCompleto")
        txtDiaActual.text = textoCompleto
        
        // Forzar actualización de la UI
        txtDiaActual.invalidate()
        txtDiaActual.requestLayout()
        
        // Mostrar información de días extra
        actualizarInformacionDiasExtra()
        
        // 📈 Actualizar indicador de tendencia de ventas
        actualizarIndicadorTendencia()
        
        // Mostrar porcentaje extra
        val porcentajeMostrar = if (modoAdmin && fechaSeleccionada != obtenerFechaHoyArgentina()) {
            // En modo admin con fecha histórica, usar el porcentaje guardado en los datos históricos
            // Si no hay configuración histórica, usar el porcentaje actual
            configuracion.porcentajeExtra
        } else {
            configuracion.porcentajeExtra
        }
        txtPorcentajeExtra.text = "% Extra: ${porcentajeMostrar}%"
        
        // 🔥 FIX: NO limpiar contenido hasta tener el nuevo contenido listo
        
        // Limpiar indicadores de local anteriores para evitar duplicados
        val rootLayout = findViewById<LinearLayout>(R.id.rootLinearLayout)
        // Buscar y eliminar indicadores de local anteriores (que empiecen con "LOCAL:")
        for (i in rootLayout.childCount - 1 downTo 0) {
            val child = rootLayout.getChildAt(i)
            if (child is TextView && child.text.toString().startsWith("LOCAL:")) {
                rootLayout.removeViewAt(i)
            }
        }
        
        // Crear y agregar indicador de local ARRIBA del título principal
        val indicadorLocal = TextView(this).apply {
            text = "LOCAL: $localNombre"
            textSize = 16f
            setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 5, 0, 15)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        
        // Mostrar banner de modo administrador si está activo
        if (modoAdmin) {
            val bannerAdmin = TextView(this).apply {
                text = "🔧 MODO ADMINISTRADOR - EDICIÓN HABILITADA"
                textSize = 14f
                setTextColor(resources.getColor(android.R.color.white, null))
                setBackgroundColor(resources.getColor(android.R.color.holo_orange_dark, null))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 12, 0, 12)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            rootLayout.addView(bannerAdmin)
        }
        
        // Agregar el indicador VISUALMENTE ARRIBA de todo (al inicio del layout principal)
        rootLayout.addView(indicadorLocal, 0)
        
        // 🔥 FIX: Limpiar contenido SOLO después de preparar el nuevo contenido
        layoutContenido.removeAllViews()
        
        // 🔥 FIX: Mostrar alertas de stock SIEMPRE que haya productos con stock bajo
        // No solo a las 15:30, sino siempre que haya alertas disponibles
        mostrarAlertasStock()
        
        // Mostrar resumen de productos por categoría o mensaje de ayuda
        Log.d("PedidoFabricaActivity", "🔍 Estado de productos: ${productos.size} productos, modoSoloLectura: $modoSoloLectura")
        if (productos.isEmpty()) {
            if (modoSoloLectura) {
                // En modo solo lectura, mostrar mensaje específico si no hay datos
                mostrarMensajeSinDatosLectura()
            } else {
                mostrarMensajeSinProductos()
            }
        } else {
            mostrarResumenProductos()
        }
        
        // Desactivar bandera de inicialización - ahora el usuario puede modificar campos
        inicializandoProductos = false
        Log.d("PedidoFabricaActivity", "✅ Inicialización completada - campos listos para edición")
        
        solicitarContextoDemandaManiana()
        
        // Restaurar estado de EditText después de rotación
        restaurarEstadoEditTexts()
    }

    /**
     * Clima y partidos de mañana (día objetivo del pedido) para anticipar demanda.
     * Solo con fecha seleccionada = hoy (Argentina) y en modo editable.
     */
        private fun obtenerFechaHoyArgentina(): String {
        val tz = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
        val sdf = DateHelper.getDateFormat("yyyy-MM-dd").apply { timeZone = tz }
        return sdf.format(Date())
    }

    
    private fun setupWebClimaIcono() {
        try {
            webClimaIcono.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            webClimaIcono.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            webClimaIcono.settings.apply {
                javaScriptEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                allowFileAccess = false
            }
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error configurando webClimaIcono", e)
        }
    }

    private fun cargarSVGClimaAnimado(weatherCode: Int) {
        try {
            val svgContent = when {
                weatherCode == 0 || weatherCode == 1 -> """
                <svg width="48" height="48" viewBox="0 0 64 64" style="filter:drop-shadow(0 0 8px rgba(251,191,36,0.6));">
                  <g transform="translate(32,32)">
                    <g style="animation: weatherSunRotate 8s linear infinite;">
                      <line x1="0" y1="-26" x2="0" y2="-20" stroke="#F59E0B" stroke-width="3.5" stroke-linecap="round"/>
                      <line x1="18.4" y1="-18.4" x2="14.1" y2="-14.1" stroke="#F59E0B" stroke-width="3.5" stroke-linecap="round"/>
                      <line x1="26" y1="0" x2="20" y2="0" stroke="#F59E0B" stroke-width="3.5" stroke-linecap="round"/>
                      <line x1="18.4" y1="18.4" x2="14.1" y2="14.1" stroke="#F59E0B" stroke-width="3.5" stroke-linecap="round"/>
                      <line x1="0" y1="26" x2="0" y2="20" stroke="#F59E0B" stroke-width="3.5" stroke-linecap="round"/>
                      <line x1="-18.4" y1="18.4" x2="-14.1" y2="14.1" stroke="#F59E0B" stroke-width="3.5" stroke-linecap="round"/>
                      <line x1="-26" y1="0" x2="-20" y2="0" stroke="#F59E0B" stroke-width="3.5" stroke-linecap="round"/>
                      <line x1="-18.4" y1="-18.4" x2="-14.1" y2="-14.1" stroke="#F59E0B" stroke-width="3.5" stroke-linecap="round"/>
                    </g>
                    <circle r="13" fill="url(#sunGrad)" style="animation: weatherSunPulse 2.5s ease-in-out infinite;"/>
                  </g>
                  <defs>
                    <radialGradient id="sunGrad" cx="35%" cy="35%" r="65%">
                      <stop offset="0%" stop-color="#FDE047"/>
                      <stop offset="100%" stop-color="#F59E0B"/>
                    </radialGradient>
                  </defs>
                </svg>
                """
                weatherCode in listOf(95, 96, 99) -> """
                <svg width="48" height="48" viewBox="0 0 64 64" style="filter:drop-shadow(0 4px 10px rgba(124,58,237,0.4));">
                  <g style="animation: weatherCloudFloat 3s ease-in-out infinite;">
                    <path d="M46 36c3.3 0 6-2.7 6-6 0-3-2.2-5.5-5.1-5.9C45.9 19 41.6 15 36.5 15c-4.1 0-7.7 2.6-9.1 6.3C26.5 21.1 25.8 21 25 21c-3.9 0-7 3.1-7 7 0 .7.1 1.4.3 2C15.6 30.6 14 33.1 14 36c0 3.9 3.1 7 7 7h25c3.3 0 6-2.7 6-6z" fill="url(#stormCloudGrad)"/>
                  </g>
                  <polygon points="30,36 24,46 31,46 27,56 38,42 31,42" fill="#FDE047" style="animation: weatherFlash 1.2s infinite;"/>
                  <defs>
                    <linearGradient id="stormCloudGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                      <stop offset="0%" stop-color="#475569"/>
                      <stop offset="100%" stop-color="#1E293B"/>
                    </linearGradient>
                  </defs>
                </svg>
                """
                weatherCode in listOf(51,53,55,56,57,61,63,65,66,67,80,81,82) -> """
                <svg width="48" height="48" viewBox="0 0 64 64" style="filter:drop-shadow(0 4px 8px rgba(59,130,246,0.4));">
                  <g style="animation: weatherCloudFloat 3s ease-in-out infinite;">
                    <path d="M46 34c3.3 0 6-2.7 6-6 0-3-2.2-5.5-5.1-5.9C45.9 17 41.6 13 36.5 13c-4.1 0-7.7 2.6-9.1 6.3C26.5 19.1 25.8 19 25 19c-3.9 0-7 3.1-7 7 0 .7.1 1.4.3 2C15.6 28.6 14 31.1 14 34c0 3.9 3.1 7 7 7h25z" fill="url(#rainCloudGrad)"/>
                  </g>
                  <g stroke="#60A5FA" stroke-width="2.5" stroke-linecap="round">
                    <line x1="22" y1="44" x2="19" y2="52" style="animation: weatherRainDrop 0.8s linear infinite;"/>
                    <line x1="32" y1="44" x2="29" y2="54" style="animation: weatherRainDrop 0.8s linear infinite 0.3s;"/>
                    <line x1="42" y1="44" x2="39" y2="52" style="animation: weatherRainDrop 0.8s linear infinite 0.6s;"/>
                  </g>
                  <defs>
                    <linearGradient id="rainCloudGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                      <stop offset="0%" stop-color="#94A3B8"/>
                      <stop offset="100%" stop-color="#475569"/>
                    </linearGradient>
                  </defs>
                </svg>
                """
                weatherCode in listOf(71,73,75,77,85,86) -> """
                <svg width="48" height="48" viewBox="0 0 64 64" style="filter:drop-shadow(0 4px 8px rgba(186,230,253,0.5));">
                  <g style="animation: weatherCloudFloat 3s ease-in-out infinite;">
                    <path d="M46 34c3.3 0 6-2.7 6-6 0-3-2.2-5.5-5.1-5.9C45.9 17 41.6 13 36.5 13c-4.1 0-7.7 2.6-9.1 6.3C26.5 19.1 25.8 19 25 19c-3.9 0-7 3.1-7 7 0 .7.1 1.4.3 2C15.6 28.6 14 31.1 14 34c0 3.9 3.1 7 7 7h25z" fill="url(#snowCloudGrad)"/>
                  </g>
                  <g fill="#BAE6FD">
                    <circle cx="22" cy="46" r="2.5" style="animation: weatherSnowDrop 1.5s ease-in-out infinite;"/>
                    <circle cx="32" cy="48" r="2" style="animation: weatherSnowDrop 1.5s ease-in-out infinite 0.5s;"/>
                    <circle cx="42" cy="45" r="2.5" style="animation: weatherSnowDrop 1.5s ease-in-out infinite 1s;"/>
                  </g>
                  <defs>
                    <linearGradient id="snowCloudGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                      <stop offset="0%" stop-color="#CBD5E1"/>
                      <stop offset="100%" stop-color="#64748B"/>
                    </linearGradient>
                  </defs>
                </svg>
                """
                else -> """
                <svg width="48" height="48" viewBox="0 0 64 64" style="filter:drop-shadow(0 4px 8px rgba(148,163,184,0.4));">
                  <circle cx="24" cy="24" r="9" fill="#F59E0B" style="animation: weatherSunPulse 3s ease-in-out infinite;"/>
                  <g style="animation: weatherCloudFloat 3.5s ease-in-out infinite;">
                    <path d="M46 36c3.3 0 6-2.7 6-6 0-3-2.2-5.5-5.1-5.9C45.9 19 41.6 15 36.5 15c-4.1 0-7.7 2.6-9.1 6.3C26.5 21.1 25.8 21 25 21c-3.9 0-7 3.1-7 7 0 .7.1 1.4.3 2C15.6 30.6 14 33.1 14 36c0 3.9 3.1 7 7 7h25z" fill="url(#partlyCloudGrad)"/>
                  </g>
                  <defs>
                    <linearGradient id="partlyCloudGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                      <stop offset="0%" stop-color="#E2E8F0"/>
                      <stop offset="100%" stop-color="#94A3B8"/>
                    </linearGradient>
                  </defs>
                </svg>
                """
            }

            val html = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body { margin:0; padding:0; background:transparent; display:flex; align-items:center; justify-content:center; width:100%; height:100%; overflow:hidden; }
                @keyframes weatherSunRotate { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
                @keyframes weatherSunPulse { 0%, 100% { transform: scale(1); } 50% { transform: scale(1.12); } }
                @keyframes weatherCloudFloat { 0%, 100% { transform: translateY(0px); } 50% { transform: translateY(-4px); } }
                @keyframes weatherRainDrop { 0% { transform: translateY(0); opacity: 1; } 100% { transform: translateY(10px); opacity: 0; } }
                @keyframes weatherSnowDrop { 0% { transform: translateY(0) scale(1); opacity: 1; } 50% { transform: translateY(6px) scale(1.2); opacity: 0.8; } 100% { transform: translateY(12px) scale(0.8); opacity: 0.2; } }
                @keyframes weatherFlash { 0%, 100% { opacity: 0; } 30%, 35% { opacity: 1; } 40% { opacity: 0; } 80%, 82% { opacity: 1; } }
            </style>
            </head>
            <body>
                $svgContent
            </body>
            </html>
            """.trimIndent()

            webClimaIcono.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            webClimaIcono.visibility = View.VISIBLE
            txtClimaIcono.visibility = View.GONE
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error cargando SVG clima animado: ${e.message}")
            txtClimaIcono.visibility = View.VISIBLE
            webClimaIcono.visibility = View.GONE
        }
    }

    
    private fun mostrarDetalleVencimientoMerma(codigo: String, info: VencimientoMermaInfo) {
        val titulo = if (info.todoVenceHoy) "⏰ MERMA TOTAL DE STOCK ($codigo)" else "⏰ VENCIMIENTO DE LOTES ($codigo)"
        val mensaje = buildString {
            append("El producto $codigo tiene ${info.cantidadVenceHoy} unidad(es) con fecha de vencimiento al $fechaSeleccionada.\n\n")
            if (info.todoVenceHoy) {
                append("⚠️ VENCIMIENTO COMPLETO: Todo el stock activo de este producto vencerá hoy. Al cierre de jornada la mercadería no vendida se descartará como merma (sobrante = 0).\n\n")
                append("💡 El pedido sugerido fue calculado considerando sobrante 0 al cierre para garantizar la reposición total del producto.\n\n")
            } else {
                append("ℹ️ El sobrante estimado al cierre fue ajustado descontando las unidades que mermarán hoy para pedir la cantidad de reposición adecuada.\n\n")
            }
            if (info.lotesVencenHoy.isNotEmpty()) {
                append("📦 Detalle de lotes afectados:\n")
                info.lotesVencenHoy.forEach { lote ->
                    append(" • Cantidad: ${lote.cantidad} u - Vence: ${lote.fechaVencimiento}\n")
                }
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun solicitarContextoDemandaManiana() {
        val tz = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
        val sdf = DateHelper.getDateFormat("yyyy-MM-dd").apply { timeZone = tz }

        // Calcular mañana a partir de fechaSeleccionada (permite ver clima/partidos de cualquier fecha)
        val fechaSelDate = try { sdf.parse(fechaSeleccionada) } catch (e: Exception) { Date() }
        val cal = Calendar.getInstance(tz)
        cal.time = fechaSelDate
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val maniana = sdf.format(cal.time)

        if (demandaManianaCacheKey == maniana && demandaManianaCache != null) {
            aplicarDemandaManianaUi(demandaManianaCache!!)
            cardDemandaManiana.visibility = View.VISIBLE
            return
        }

        if (demandaManianaRequestInflight) return
        demandaManianaRequestInflight = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ctx = PedidoFabricaDemandaContextFetcher.fetch(maniana)
                withContext(Dispatchers.Main) {
                    demandaManianaRequestInflight = false
                    if (isFinishing || isDestroyed) return@withContext
                    demandaManianaCacheKey = maniana
                    demandaManianaCache = ctx
                    aplicarDemandaManianaUi(ctx)
                    cardDemandaManiana.visibility = View.VISIBLE
                    if (ctx.hayAlertaFuerte) {
                        notificarDemandaManianaSiCorresponde(ctx, maniana)
                    }
                }
            } catch (e: Exception) {
                Log.e("DemandaManiana", "Error cargando contexto mañana", e)
                withContext(Dispatchers.Main) {
                    demandaManianaRequestInflight = false
                    if (!isFinishing && !isDestroyed) {
                        mostrarDemandaManianaErrorUi(
                            "No se pudo cargar el clima ni los partidos (red o servidor). " +
                                "Revisá la conexión e intentá de nuevo más tarde.\n\n${e.message ?: ""}"
                        )
                    }
                }
            }
        }
    }

    private fun mostrarDemandaManianaErrorUi(cuerpo: String) {
        txtDemandaManianaTitulo.text = "📣 Mañana · sin datos en línea"
        txtDemandaManianaCuerpo.text = cuerpo.trim()
        val isDark = isDarkMode()
        val bg = if (isDark) android.graphics.Color.parseColor("#3E2E1A") else android.graphics.Color.parseColor("#FFF8E1")
        val stroke = if (isDark) android.graphics.Color.parseColor("#40C59B34") else android.graphics.Color.parseColor("#FF9800")
        cardDemandaManiana.setCardBackgroundColor(bg)
        cardDemandaManiana.setStrokeColor(stroke)
        climaAnimator?.cancel()
        txtClimaIcono.visibility = View.GONE
        cardDemandaManiana.visibility = View.VISIBLE
    }

    private fun aplicarDemandaManianaUi(ctx: DemandaManianaContexto) {
        txtDemandaManianaTitulo.text = "📣 Mañana (${ctx.fechaMostrar})"
        val futbolBlock = if (ctx.lineasFutbol.isEmpty()) {
            "⚽ Primera División: no hay partidos para esa fecha."
        } else {
            ctx.lineasFutbol.joinToString("\n")
        }
        txtDemandaManianaCuerpo.text = buildString {
            append(ctx.lineaClima)
            append("\n\n")
            append(futbolBlock)
        }

        // Cancelar animación previa
        climaAnimator?.cancel()
        txtClimaIcono.rotation = 0f
        txtClimaIcono.translationX = 0f
        txtClimaIcono.translationY = 0f
        txtClimaIcono.scaleX = 1f
        txtClimaIcono.scaleY = 1f

        val isDark = isDarkMode()
        val code = ctx.weatherCode
        
        if (code != -1) {
            val emoji = when (code) {
                0 -> "☀️" // Soleado
                1, 2, 3 -> "⛅" // Nublado
                45, 48 -> "🌫️" // Niebla
                51, 53, 55, 56, 57 -> "🌧️" // Llovizna
                61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️" // Lluvia
                71, 73, 75, 77, 85, 86 -> "❄️" // Nieve / Granizo
                95, 96, 99 -> "⛈️" // Tormenta
                else -> "✨"
            }
            
            val bgColorStr = if (isDark) {
                when (code) {
                    0 -> "#2D1D00" // Oro oscuro
                    1, 2, 3 -> "#1B2228" // Gris azulado oscuro
                    45, 48 -> "#212121" // Gris neutro oscuro
                    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "#0E1A30" // Azul tormenta oscuro
                    71, 73, 75, 77, 85, 86 -> "#0A282D" // Verde azulado gélido oscuro
                    95, 96, 99 -> "#210A30" // Púrpura tormentoso oscuro
                    else -> "#212121"
                }
            } else {
                when (code) {
                    0 -> "#FFFDE7" // Amarillo sol claro
                    1, 2, 3 -> "#ECEFF1" // Gris azulado claro
                    45, 48 -> "#F5F5F5" // Gris claro
                    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "#E1F5FE" // Azul lluvia claro
                    71, 73, 75, 77, 85, 86 -> "#E0F7FA" // Celeste nieve claro
                    95, 96, 99 -> "#F3E5F5" // Púrpura claro
                    else -> "#FFF8E1"
                }
            }
            
            val strokeColorStr = when (code) {
                0 -> "#FFB300" // Amarillo/Dorado
                1, 2, 3 -> "#78909C" // Gris azulado
                45, 48 -> "#9E9E9E" // Gris
                51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "#0288D1" // Azul lluvia
                71, 73, 75, 77, 85, 86 -> "#00ACC1" // Celeste
                95, 96, 99 -> "#8E24AA" // Púrpura
                else -> "#FF9800"
            }
            
            val bgColor = android.graphics.Color.parseColor(bgColorStr)
            val strokeColor = android.graphics.Color.parseColor(strokeColorStr)
            
            cardDemandaManiana.setCardBackgroundColor(bgColor)
            cardDemandaManiana.setStrokeColor(strokeColor)
            
            txtClimaIcono.text = emoji
            cargarSVGClimaAnimado(code)
            
            // Iniciar animación correspondiente
            when (code) {
                0 -> {
                    // Rotación lenta del sol
                    val anim = android.animation.ObjectAnimator.ofFloat(txtClimaIcono, "rotation", 0f, 360f).apply {
                        duration = 8000
                        repeatCount = android.animation.ObjectAnimator.INFINITE
                        interpolator = android.view.animation.LinearInterpolator()
                    }
                    anim.start()
                    climaAnimator = anim
                }
                1, 2, 3, 45, 48 -> {
                    // Desplazamiento horizontal de nubes/niebla
                    val anim = android.animation.ObjectAnimator.ofFloat(txtClimaIcono, "translationX", -8f, 8f).apply {
                        duration = 3000
                        repeatCount = android.animation.ObjectAnimator.INFINITE
                        repeatMode = android.animation.ObjectAnimator.REVERSE
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    }
                    anim.start()
                    climaAnimator = anim
                }
                51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99 -> {
                    // Rebote vertical para lluvia/tormenta
                    val anim = android.animation.ObjectAnimator.ofFloat(txtClimaIcono, "translationY", -6f, 6f).apply {
                        duration = 1500
                        repeatCount = android.animation.ObjectAnimator.INFINITE
                        repeatMode = android.animation.ObjectAnimator.REVERSE
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    }
                    anim.start()
                    climaAnimator = anim
                }
                71, 73, 75, 77, 85, 86 -> {
                    // Escalado oscilante para nieve
                    val animX = android.animation.ObjectAnimator.ofFloat(txtClimaIcono, "scaleX", 0.9f, 1.1f)
                    val animY = android.animation.ObjectAnimator.ofFloat(txtClimaIcono, "scaleY", 0.9f, 1.1f)
                    
                    val set = android.animation.AnimatorSet().apply {
                        playTogether(animX, animY)
                        duration = 2000
                    }
                    
                    animX.repeatCount = android.animation.ValueAnimator.INFINITE
                    animX.repeatMode = android.animation.ValueAnimator.REVERSE
                    animY.repeatCount = android.animation.ValueAnimator.INFINITE
                    animY.repeatMode = android.animation.ValueAnimator.REVERSE
                    
                    set.start()
                    climaAnimator = set
                }
            }
        } else {
            // Sin datos de clima válidos
            val bg = if (isDark) android.graphics.Color.parseColor("#1E293B") else android.graphics.Color.parseColor("#FFF8E1")
            val stroke = if (isDark) android.graphics.Color.parseColor("#40C59B34") else android.graphics.Color.parseColor("#FF9800")
            cardDemandaManiana.setCardBackgroundColor(bg)
            cardDemandaManiana.setStrokeColor(stroke)
            txtClimaIcono.visibility = View.GONE
        }
    }

    private fun notificarDemandaManianaSiCorresponde(ctx: DemandaManianaContexto, manianaIso: String) {
        val prefs = getSharedPreferences("pedido_fabrica_prefs", MODE_PRIVATE)
        val key = "demanda_notif_$manianaIso"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        val titulo = "Pedido fábrica · revisá mañana (${ctx.fechaMostrar})"
        val texto = buildString {
            append(ctx.lineaClima)
            if (ctx.lineasFutbol.isNotEmpty()) {
                append("\n\n")
                append(ctx.lineasFutbol.joinToString("\n"))
            }
        }
        try {
            NotificationHelper(this).showPedidoDemandaManianaNotification(titulo, texto)
        } catch (e: Exception) {
            Log.w("DemandaManiana", "No se pudo mostrar notificación: ${e.message}")
        }
    }
    
    /**
     * Recalcula las sugerencias de todos los productos usando los promedios cargados
     */
    private fun recalcularSugerenciasConPromedios() {
        Log.d("PROMEDIOS", "🔄 Recalculando sugerencias con promedios cargados")
        productos.forEach { producto ->
            val nuevaSugerencia = calcularSugerenciaPedido(producto.codigo, producto.stockActual)
            Log.d("PROMEDIOS", "🔄 Producto ${producto.codigo}: Stock=${producto.stockActual}, Nueva sugerencia=$nuevaSugerencia")
            // Actualizar el producto con la nueva sugerencia
            val index = productos.indexOfFirst { it.codigo == producto.codigo }
            if (index != -1) {
                productos = productos.toMutableList().apply {
                    set(index, producto.copy(pedidoSugerido = nuevaSugerencia))
                }
            }
        }
        Log.d("PROMEDIOS", "✅ Sugerencias recalculadas para ${productos.size} productos")
    }

    private fun esHoraDeAlerta(): Boolean {
        val calendar = DateHelper.getCalendar()
        val hora = calendar.get(Calendar.HOUR_OF_DAY)
        val minuto = calendar.get(Calendar.MINUTE)
        return hora == 15 && minuto >= 30
    }

    private fun mostrarAlertasStock() {
        val alertas = repository.generarAlertasStock()
        if (alertas.isNotEmpty()) {
            val alertaView = LayoutInflater.from(this).inflate(R.layout.item_alerta_stock, null)
            val txtAlerta = alertaView.findViewById<TextView>(R.id.txtAlerta)
            
            val mensajeAlerta = "⚠️ ALERTAS DE STOCK\n\n" + 
                alertas.joinToString("\n") { 
                    "${it.producto} → ${it.stockPredicho} (${it.semana})" 
                }
            
            txtAlerta.text = mensajeAlerta
            layoutContenido.addView(alertaView)
        }
    }

    private fun mostrarMensajeSinDatosLectura() {
        val mensajeView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            gravity = android.view.Gravity.CENTER
        }
        
        val iconoView = TextView(this).apply {
            text = "📋"
            textSize = 48f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        
        val tituloView = TextView(this).apply {
            text = "No hay datos para esta fecha"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 8)
            setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
        }
        
        val descripcionView = TextView(this).apply {
            text = "No se encontró ningún pedido guardado para el $fechaSeleccionada.\n\nEsto puede deberse a:\n\n• No se realizó ningún pedido ese día\n• Los datos fueron eliminados\n• Error al cargar desde la base de datos"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        
        mensajeView.addView(iconoView)
        mensajeView.addView(tituloView)
        mensajeView.addView(descripcionView)
        
        layoutContenido.addView(mensajeView)
    }

    private fun mostrarMensajeSinProductos() {
        val mensajeView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            gravity = android.view.Gravity.CENTER
        }
        
        val iconoView = TextView(this).apply {
            text = "📊"
            textSize = 48f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        
        val tituloView = TextView(this).apply {
            text = "Sin datos históricos de ventas"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 8)
            setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
        }
        
        val descripcionView = TextView(this).apply {
            text = "Para generar sugerencias de pedido necesitas:\n\n1. ✅ Tener registros de inventario históricos\n2. ✅ Ir a 'Promedio de Ventas' y cargar los datos\n3. ✅ Volver aquí para ver las sugerencias reales\n\n⚠️ Sin datos históricos no se pueden calcular sugerencias precisas"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        
        val botonPromedios = Button(this).apply {
            text = "📈 Ir a Promedio de Ventas"
            textSize = 14f
            setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark, null))
            setTextColor(resources.getColor(android.R.color.white, null))
            setPadding(16, 12, 16, 12)
            setOnClickListener {
                // Ir a la actividad de Promedio de Ventas
                val intent = Intent(this@PedidoFabricaActivity, PromedioVentasActivity::class.java)
                startActivity(intent)
            }
        }
        
        val botonRecargar = Button(this).apply {
            text = "🔄 Recargar Datos del Local"
            textSize = 12f
            setBackgroundColor(resources.getColor(android.R.color.holo_orange_dark, null))
            setTextColor(resources.getColor(android.R.color.white, null))
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                // Recargar los datos específicos del local actual
                lifecycleScope.launch {
                    cargarPromediosRealesDelLocal()
                    Toast.makeText(this@PedidoFabricaActivity, "Recargando datos del local...", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        val botonCargarStock = Button(this).apply {
            text = "📦 Cargar Stock Actual (Sin Sugerencias)"
            textSize = 12f
            setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
            setTextColor(resources.getColor(android.R.color.white, null))
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                // Mostrar la sección de carga sin sugerencias
                mostrarSeccionCargarStockIntegrada()
            }
        }
        
        mensajeView.addView(iconoView)
        mensajeView.addView(tituloView)
        mensajeView.addView(descripcionView)
        mensajeView.addView(botonPromedios)
        mensajeView.addView(botonRecargar)
        mensajeView.addView(botonCargarStock)
        
        layoutContenido.addView(mensajeView)
    }

    private fun mostrarResumenProductos() {
        // 🔧 ORDENAR productos por categorías usando el orden específico
        val ordenProductos = listOf(
            "JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH",  // EMPANADAS
            "MUZZARE", "JAMON", "PEPPERO",  // PIZZAS
            "BATATA", "MEMBRIL"  // PASTELITOS
            // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity
        )
        
        val productosOrdenados = ordenProductos.mapNotNull { codigoOrden ->
            productos.find { it.codigo == codigoOrden }
        }.plus(
            productos.filter { !ordenProductos.contains(it.codigo) }
        )
        
        // 🔧 AGRUPAR productos por categorías correctamente
        Log.d("ADMIN_FLOW", "🔍 Productos disponibles para categorizar: ${productosOrdenados.map { "${it.codigo}-${it.nombre}" }}")
        
        val empanadas = productosOrdenados.filter { it.codigo in listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH") }
        val pizzas = productosOrdenados.filter { it.codigo in listOf("MUZZARE", "JAMON", "PEPPERO") }
        val pastelitos = productosOrdenados.filter { it.codigo in listOf("BATATA", "MEMBRIL") }
        // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity - ya no se muestran aquí
        val otros = productosOrdenados.filter { false } // Vacío - productos de panificadora movidos
        
        Log.d("ADMIN_FLOW", "🔍 EMPANADAS encontradas: ${empanadas.size} - ${empanadas.map { it.codigo }}")
        Log.d("ADMIN_FLOW", "🔍 PIZZAS encontradas: ${pizzas.size} - ${pizzas.map { it.codigo }}")
        Log.d("ADMIN_FLOW", "🔍 PASTELITOS encontrados: ${pastelitos.size} - ${pastelitos.map { it.codigo }}")
        Log.d("ADMIN_FLOW", "🔍 OTROS encontrados: ${otros.size} - ${otros.map { it.codigo }} (movidos a Panificadora)")
        
        // 🔧 MOSTRAR por categorías en el orden correcto
        if (empanadas.isNotEmpty()) mostrarCategoria("EMPANADAS", empanadas)
        if (pizzas.isNotEmpty()) mostrarCategoria("PIZZAS", pizzas)
        if (pastelitos.isNotEmpty()) mostrarCategoria("PASTELITOS", pastelitos)
        // OTROS ya no se muestran - productos de panificadora movidos
        
        Log.d("ADMIN_FLOW", "🔧 Productos mostrados por categorías en orden correcto")
    }

    private fun mostrarCategoria(categoria: String, productos: List<ProductoFabrica>) {
        // 🔧 Título de categoría con iconos específicos
        val iconoCategoria = when (categoria) {
            "EMPANADAS" -> "🥟"
            "PIZZAS" -> "🍕"
            "PASTELITOS" -> "🥧"
            "OTROS" -> "📦"
            else -> "📦"
        }
        
        val tituloCategoria = TextView(this).apply {
            text = "$iconoCategoria $categoria (${productos.size} productos)"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(16, 16, 16, 8)
            setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
            setBackgroundResource(R.drawable.category_header_background)
        }
        layoutContenido.addView(tituloCategoria)
        
        Log.d("ADMIN_FLOW", "🔧 Mostrando categoría: $categoria con ${productos.size} productos")
        
        // 🔧 ORDENAR productos dentro de la categoría según el orden específico
        val ordenEspecifico = when (categoria) {
            "EMPANADAS" -> listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH")
            "PIZZAS" -> listOf("MUZZARE", "JAMON", "PEPPERO")
            "PASTELITOS" -> listOf("BATATA", "MEMBRIL")
            "OTROS" -> emptyList() // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity
            else -> productos.map { it.codigo }
        }
        
        val productosOrdenadosCategoria = ordenEspecifico.mapNotNull { codigo ->
            productos.find { it.codigo == codigo }
        }.plus(
            productos.filter { !ordenEspecifico.contains(it.codigo) }
        )
        
        Log.d("ADMIN_FLOW", "🔧 Productos en $categoria ordenados: ${productosOrdenadosCategoria.map { it.codigo }}")
        
        // Tabla de productos
        val tablaLayout = TableLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 0, 16, 16)
        }
        
        // Definir colores de texto según modo oscuro
        val isDark = isDarkMode()
        val textColor = if (isDark) {
            android.graphics.Color.WHITE // Texto blanco en modo oscuro
        } else {
            android.graphics.Color.BLACK // Texto negro en modo claro
        }
        
        // Header de la tabla
        val headerRow = TableRow(this)
        val headers = listOf("Producto", "Stock 15:30", "Sugerido", "Pedido Final")
        
        headers.forEach { header ->
            val textView = TextView(this).apply {
                text = header
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(8, 8, 8, 8)
                setBackgroundColor(if (isDarkMode()) getThemeColor(android.R.attr.colorBackground) else resources.getColor(android.R.color.holo_blue_light, null))
                setTextColor(textColor)
                gravity = android.view.Gravity.CENTER
            }
            val layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            textView.layoutParams = layoutParams
            headerRow.addView(textView)
        }
        tablaLayout.addView(headerRow)
        
        // Filas de productos
        
        productosOrdenadosCategoria.forEach { producto ->
            val fila = TableRow(this)
            
            // Código del producto (se actualizará con tendencia en background)
            val codigoView = TextView(this).apply {
                text = producto.codigo
                textSize = 12f
                setPadding(8, 8, 8, 8)
                setTextColor(textColor)
                gravity = android.view.Gravity.CENTER
            }
            val codigoLayoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            codigoView.layoutParams = codigoLayoutParams
            fila.addView(codigoView)
            
            // Stock actual - EditText si es modo admin, TextView si no
            if (modoAdmin) {
                val stockView = EditText(this).apply {
                    // Restaurar valor guardado si existe, sino usar el valor del producto
                    val valorGuardado = estadoEditTexts["${producto.codigo}_stock"]
                    val valorInicial = valorGuardado ?: producto.stockActual.toString()
                    setText(valorInicial)
                    textSize = 12f
                    setPadding(8, 8, 8, 8)
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setTextColor(textColor)
                    setBackgroundColor(if (isDarkMode()) resources.getColor(android.R.color.black, null) else resources.getColor(android.R.color.white, null))
                    gravity = android.view.Gravity.CENTER
                    tag = "${producto.codigo}_stock" // Tag para identificar el campo
                    Log.d("ADMIN_FLOW", "🔧 EditText Stock para ${producto.codigo}: valor asignado = '$valorInicial' (guardado: ${valorGuardado != null})")
                }
                configurarSeleccionTexto(stockView)
                val stockLayoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
                stockView.layoutParams = stockLayoutParams
                fila.addView(stockView)
            } else {
                val stockView = TextView(this).apply {
                    text = producto.stockActual.toString()
                    textSize = 12f
                    setPadding(8, 8, 8, 8)
                    setTextColor(textColor)
                    gravity = android.view.Gravity.CENTER
                }
                val stockLayoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
                stockView.layoutParams = stockLayoutParams
                fila.addView(stockView)
            }
            
            // Pedido sugerido - EditText si es modo admin, TextView si no
            if (modoAdmin) {
                val pedidoSugeridoView = EditText(this).apply {
                    // Restaurar valor guardado si existe, sino usar el valor del producto
                    val valorGuardado = estadoEditTexts["${producto.codigo}_sugerido"]
                    val valorInicial = valorGuardado ?: producto.pedidoSugerido.toString()
                    setText(valorInicial)
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(8, 8, 8, 8)
                    setTextColor(if (isDarkMode()) resources.getColor(android.R.color.holo_green_light, null) else resources.getColor(android.R.color.holo_green_dark, null))
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setBackgroundColor(if (isDarkMode()) getThemeColor(android.R.attr.colorBackground) else getThemeColor(android.R.attr.colorBackground))
                    gravity = android.view.Gravity.CENTER
                    tag = "${producto.codigo}_sugerido" // Tag para identificar el campo
                    Log.d("ADMIN_FLOW", "🔧 EditText Sugerencia para ${producto.codigo}: valor asignado = '$valorInicial' (guardado: ${valorGuardado != null})")
                }
                configurarSeleccionTexto(pedidoSugeridoView)
                val sugeridoLayoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
                pedidoSugeridoView.layoutParams = sugeridoLayoutParams
                fila.addView(pedidoSugeridoView)
            } else {
                val pedidoSugeridoView = TextView(this).apply {
                    text = producto.pedidoSugerido.toString()
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(8, 8, 8, 8)
                    setTextColor(if (isDarkMode()) resources.getColor(android.R.color.holo_green_light, null) else resources.getColor(android.R.color.holo_green_dark, null))
                    gravity = android.view.Gravity.CENTER
                }
                val sugeridoLayoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
                pedidoSugeridoView.layoutParams = sugeridoLayoutParams
                fila.addView(pedidoSugeridoView)
            }
            
            // Pedido final - EditText editable solo si NO es modo solo lectura
            val pedidoFinalView = if (modoSoloLectura && !modoAdmin) {
                // En modo solo lectura: TextView no editable
                TextView(this).apply {
                text = producto.pedidoFinal.toString()
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(8, 8, 8, 8)
                setTextColor(textColor)
                gravity = android.view.Gravity.CENTER
                    setBackgroundColor(if (isDarkMode()) resources.getColor(android.R.color.black, null) else resources.getColor(android.R.color.darker_gray, null))
                    tag = "${producto.codigo}_pedido" // Tag para identificar el campo
                    Log.d("PEDIDO_FINAL", "🔧 TextView Pedido Final (solo lectura) para ${producto.codigo}: valor = '${producto.pedidoFinal}'")
                }
            } else {
                // En modo normal o admin: EditText editable
                EditText(this).apply {
                    // Restaurar valor guardado si existe, sino usar el valor del producto
                    val valorGuardado = estadoEditTexts["${producto.codigo}_pedido"]
                    val valorInicial = valorGuardado ?: producto.pedidoFinal.toString()
                    setText(valorInicial)
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(8, 8, 8, 8)
                    setTextColor(textColor)
                    gravity = android.view.Gravity.CENTER
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setBackgroundColor(if (isDarkMode()) getThemeColor(android.R.attr.colorBackground) else getThemeColor(android.R.attr.colorBackground))
                    tag = "${producto.codigo}_pedido" // Tag para identificar el campo
                    Log.d("PEDIDO_FINAL", "🔧 EditText Pedido Final (editable) para ${producto.codigo}: valor = '$valorInicial' (guardado: ${valorGuardado != null})")
                }.also { editText ->
                    configurarSeleccionTexto(editText)
                }
            }
            val finalLayoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            pedidoFinalView.layoutParams = finalLayoutParams
            fila.addView(pedidoFinalView)
            
            tablaLayout.addView(fila)
        }
        
        layoutContenido.addView(tablaLayout)
    }

    private fun mostrarConfiguracionDiasEntrega() {
        val backgroundColor = if (isDarkMode()) {
            resources.getColor(android.R.color.black, null)
        } else {
            resources.getColor(android.R.color.white, null)
        }
        val textColor = if (isDarkMode()) {
            resources.getColor(android.R.color.white, null)
        } else {
            resources.getColor(android.R.color.black, null)
        }
        val textColorSecondary = if (isDarkMode()) {
            resources.getColor(android.R.color.darker_gray, null)
        } else {
            getThemeColor(android.R.attr.textColorSecondary)
        }
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(backgroundColor)
        }
        
        val titulo = TextView(this).apply {
            text = "📅 Configurar Días de Entrega"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 24)
            setTextColor(textColor)
        }
        
        val descripcion = TextView(this).apply {
            text = "Marca los días en que la fábrica entrega mercadería a tu local. Esto afectará el cálculo de sugerencias de pedido."
            textSize = 14f
            setPadding(0, 0, 0, 24)
            setTextColor(textColorSecondary)
        }
        
        val checkboxes = mutableMapOf<String, CheckBox>()
        val dias = listOf(
            "lunes" to "Lunes",
            "martes" to "Martes", 
            "miercoles" to "Miércoles",
            "jueves" to "Jueves",
            "viernes" to "Viernes",
            "sabado" to "Sábado",
            "domingo" to "Domingo"
        )
        
        // Agregar título y descripción primero
        layout.addView(titulo)
        layout.addView(descripcion)
        
        dias.forEach { (clave, nombre) ->
            val checkbox = CheckBox(this).apply {
                text = nombre
                textSize = 16f
                setPadding(16, 8, 16, 8)
                setTextColor(textColor)
                
                // Establecer el estado actual
                isChecked = when (clave) {
                    "lunes" -> diasEntrega.lunes
                    "martes" -> diasEntrega.martes
                    "miercoles" -> diasEntrega.miercoles
                    "jueves" -> diasEntrega.jueves
                    "viernes" -> diasEntrega.viernes
                    "sabado" -> diasEntrega.sabado
                    "domingo" -> diasEntrega.domingo
                    else -> true
                }
            }
            checkboxes[clave] = checkbox
            layout.addView(checkbox)
        }
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("⚙️ Días de Entrega")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                // Actualizar configuración
                diasEntrega = ConfiguracionDiasEntrega(
                    lunes = checkboxes["lunes"]?.isChecked ?: true,
                    martes = checkboxes["martes"]?.isChecked ?: true,
                    miercoles = checkboxes["miercoles"]?.isChecked ?: true,
                    jueves = checkboxes["jueves"]?.isChecked ?: true,
                    viernes = checkboxes["viernes"]?.isChecked ?: true,
                    sabado = checkboxes["sabado"]?.isChecked ?: true,
                    domingo = checkboxes["domingo"]?.isChecked ?: true
                )
                
                Log.d("PedidoFabricaActivity", "📅 Configuración de días actualizada: $diasEntrega")
                Log.d("PedidoFabricaActivity", "📅 Domingo tiene entrega: ${diasEntrega.domingo}")
                
                // Guardar en Firebase
                guardarDiasEntregaEnFirebase(diasEntrega)
                
                // 🔥 ELIMINAR NOTIFICACIÓN MOLESTA - Las sugerencias se actualizan automáticamente
                // Toast.makeText(this@PedidoFabricaActivity, "Recalculando sugerencias...", Toast.LENGTH_SHORT).show()
                
                // Actualizar el mensaje de días extra en la interfaz
                actualizarInformacionDiasExtra()
                
                // Recalcular automáticamente cuando cambian los días de entrega
                recalcularTodasLasSugerencias()
                actualizarSugerenciasEnTiempoReal(configuracion.porcentajeExtra)
                
                // 🔥 ELIMINAR NOTIFICACIÓN MOLESTA - Las sugerencias se actualizan automáticamente
                // Toast.makeText(this@PedidoFabricaActivity, "Días de entrega actualizados y sugerencias recalculadas", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null)
            .create()
        
        // Configurar fondo y colores del diálogo para modo oscuro
        val dialogBackgroundColor = if (isDarkMode()) {
            android.graphics.Color.BLACK
        } else {
            android.graphics.Color.WHITE
        }
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
        
        // Configurar color del título del diálogo
        val titleView = dialog.findViewById<TextView>(android.R.id.title)
        titleView?.setTextColor(textColor)
        
        dialog.show()
    }
    
    private fun mostrarConfiguracion() {
        val isDark = isDarkMode()
        val textColor = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        
        // Obtener el porcentaje actual de la UI
        val porcentajeActual = txtPorcentajeExtra.text.toString()
            .replace("% Extra: ", "")
            .replace("%", "")
            .toIntOrNull() ?: configuracion.porcentajeExtra
        
        val txtPorcentaje = TextView(this).apply {
            text = "Porcentaje Extra: $porcentajeActual%"
            textSize = 16f
            setPadding(16, 8, 16, 8)
            setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
        }
        
        val seekBarPorcentaje = android.widget.SeekBar(this).apply {
            max = 100 // 0% a 100%
            progress = porcentajeActual
            setPadding(16, 16, 16, 16)
            
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        txtPorcentaje.text = "Porcentaje Extra: $progress%"
                        // Actualizar el porcentaje en SharedPreferences para que se use en los cálculos
                        actualizarPorcentajeExtra(progress.toFloat())
                        // 🔥 ACTUALIZAR SUGERENCIAS AUTOMÁTICAMENTE AL CAMBIAR PORCENTAJE
                        actualizarSugerenciasEnTiempoReal(progress)
                        
                        // 🔥 ELIMINAR NOTIFICACIÓN MOLESTA - Las sugerencias se actualizan automáticamente
                        // Toast.makeText(this@PedidoFabricaActivity, "Porcentaje actualizado. Modifica un campo de stock para ver las nuevas sugerencias.", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        
        val txtHora = TextView(this).apply {
            text = "Hora de Alerta:"
            textSize = 16f
            setPadding(16, 8, 16, 8)
        }
        
        val editHora = EditText(this).apply {
            // Usar la hora actual mostrada en la UI en lugar de la configuración inicial
            val horaActual = txtHoraAlerta.text.toString().replace("🔔 Alerta: ", "")
            setText(horaActual)
            setPadding(16, 16, 16, 16)
            isFocusable = false
            setOnClickListener {
                mostrarSelectorHoraTemporal(this)
            }
        }
        
        layout.addView(txtPorcentaje)
        layout.addView(seekBarPorcentaje)
        layout.addView(txtHora)
        layout.addView(editHora)
        
        // 📈 Checkbox para ajuste por tendencia
        val layoutTendencia = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }
        
        val checkBoxTendencia = android.widget.CheckBox(this).apply {
            text = "Ajuste por Tendencia"
            isChecked = configuracion.usarAjusteTendencia
            setTextColor(textColor)
        }
        
        val btnAyudaTendencia = TextView(this).apply {
            text = " ❓"
            textSize = 18f
            setPadding(8, 8, 8, 8)
            setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle("📈 Ajuste por Tendencia")
                    .setMessage("El ajuste por tendencia analiza las ventas de las últimas 3 semanas para el mismo día de la semana. Si detecta un aumento o disminución constante en las ventas, ajusta la sugerencia del pedido para adaptarse a esa tendencia (creciente o decreciente).")
                    .setPositiveButton("Entendido", null)
                    .show()
            }
        }
        
        layoutTendencia.addView(checkBoxTendencia)
        layoutTendencia.addView(btnAyudaTendencia)
        layout.addView(layoutTendencia)
        
        // Botón para configurar días de entrega
        val btnDiasEntrega = Button(this).apply {
            text = "📅 Configurar Días de Entrega"
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                mostrarConfiguracionDiasEntrega()
            }
        }
        layout.addView(btnDiasEntrega)
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("⚙️ Configuración")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val nuevoPorcentaje = seekBarPorcentaje.progress
                val nuevaHora = editHora.text.toString()
                val nuevoUsarTendencia = checkBoxTendencia.isChecked
                val nuevaConfig = configuracion.copy(
                    porcentajeExtra = nuevoPorcentaje,
                    horaAlerta = nuevaHora,
                    usarAjusteTendencia = nuevoUsarTendencia
                )
                
                // Actualizar el porcentaje en SharedPreferences para que se use en los cálculos
                actualizarPorcentajeExtra(nuevoPorcentaje.toFloat())
                
                // 🔥 SINCRONIZAR porcentajeSeleccionado con la nueva configuración
                porcentajeSeleccionado = nuevoPorcentaje.toDouble()
                Log.d("CONFIG_SAVE", "🔄 porcentajeSeleccionado actualizado: $porcentajeSeleccionado")
                
                // Marcar como modificada para evitar sobrescritura
                configuracionModificada = true
                
                // Guardar en Firebase
                guardarConfiguracionEnFirebase(nuevaConfig)
                
                // Actualizar localmente
                repository.actualizarConfiguracion(nuevaConfig)
                configuracion = nuevaConfig
                
                // Guardar hora en SharedPreferences para NotificationReceiver
                val prefs = getSharedPreferences("configuracion_pedido_fabrica", MODE_PRIVATE)
                prefs.edit().putString("hora_alerta", nuevaConfig.horaAlerta).apply()
                
                // Reprogramar alarma con nueva hora
                val alarmScheduler = com.tuapp.inventario.notification.AlarmScheduler(this@PedidoFabricaActivity)
                // cancelStockReminder ya se llama dentro de scheduleStockReminder
                alarmScheduler.scheduleStockReminder(nuevaConfig.horaAlerta)
                Log.d("PedidoFabricaActivity", "🔔 Alarma reprogramada para las ${nuevaConfig.horaAlerta}")
                
                txtPorcentajeExtra.text = "% Extra: ${configuracion.porcentajeExtra}%"
                txtHoraAlerta.text = "🔔 Alerta: ${configuracion.horaAlerta}"
                
                // 🔥 ACTUALIZAR SUGERENCIAS AUTOMÁTICAMENTE AL GUARDAR CONFIGURACIÓN
                recalcularTodasLasSugerencias()
                actualizarSugerenciasEnTiempoReal(nuevoPorcentaje)
                actualizarIndicadorTendencia()
                mostrarContenidoPedidoInterno()
                
                // 🔥 ELIMINAR NOTIFICACIÓN MOLESTA - Las sugerencias se actualizan automáticamente
                // Toast.makeText(this@PedidoFabricaActivity, "Configuración guardada. Modifica un campo de stock para ver las nuevas sugerencias.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null)
            .create()
        
        // Configurar fondo y colores del diálogo para modo oscuro
        dialog.setOnShowListener {
            val dialogBackgroundColor = if (isDarkMode()) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
            val titleView = dialog.findViewById<TextView>(android.R.id.title)
            val textColor = if (isDarkMode()) {
                android.graphics.Color.WHITE
            } else {
                android.graphics.Color.BLACK
            }
            titleView?.setTextColor(textColor)
        }
        
        dialog.show()
    }

    private fun mostrarSelectorHoraTemporal(editHora: EditText) {
        
        // Parsear la hora actual del EditText para inicializar el picker
        val horaActual = editHora.text.toString()
        var hourOfDay = 15
        var minute = 30
        
        try {
            val partes = horaActual.split(":")
            if (partes.size == 2) {
                hourOfDay = partes[0].toInt()
                minute = partes[1].toInt()
            }
        } catch (e: Exception) {
            // Usar valores por defecto si hay error
        }
        
        val timePicker = TimePickerDialog(
            this,
            { _, selectedHour, selectedMinute ->
                val horaFormateada = String.format("%02d:%02d", selectedHour, selectedMinute)
                // Solo actualizar el EditText, no guardar todavía
                editHora.setText(horaFormateada)
            },
            hourOfDay,
            minute,
            true
        )
        timePicker.show()
    }

    private fun mostrarSelectorHora() {
        val calendar = DateHelper.getCalendar()
        val timePicker = TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                val horaFormateada = String.format("%02d:%02d", hourOfDay, minute)
                val nuevaConfig = configuracion.copy(horaAlerta = horaFormateada)
                
                // Marcar como modificada para evitar sobrescritura
                configuracionModificada = true
                
                // Guardar en Firebase
                guardarConfiguracionEnFirebase(nuevaConfig)
                
                // Actualizar localmente
                repository.actualizarConfiguracion(nuevaConfig)
                configuracion = nuevaConfig
                
                // Reprogramar alarma con nueva hora
                val alarmScheduler = com.tuapp.inventario.notification.AlarmScheduler(this@PedidoFabricaActivity)
                // cancelStockReminder ya se llama dentro de scheduleStockReminder
                alarmScheduler.scheduleStockReminder(nuevaConfig.horaAlerta)
                Log.d("PedidoFabricaActivity", "🔔 Alarma reprogramada para las ${nuevaConfig.horaAlerta}")
                
                // Actualizar UI
                txtHoraAlerta.text = "🔔 Alerta: ${configuracion.horaAlerta}"
                
                Toast.makeText(this@PedidoFabricaActivity, "Hora guardada y alarma reprogramada para las ${nuevaConfig.horaAlerta}", Toast.LENGTH_SHORT).show()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        )
        timePicker.show()
    }

    private fun mostrarDialogoCargarStock() {
        println("🔍 DEBUG: mostrarDialogoCargarStock() llamado")
        println("🔍 DEBUG: productos.size = ${productos.size}")
        
        // 🔥 INVALIDAR CACHE para forzar recarga de datos frescos (stock anterior e ingreso)
        invalidarCacheRegistros()
        Log.d("SUG", "🗑️ SUG: Cache de registros invalidado al abrir modal")
        
        productos.forEach { producto ->
            println("🔍 DEBUG: Producto en lista: ${producto.nombre} - Promedios: ${producto.promediosVenta}")
        }

        if (productos.isEmpty()) {
            println("🔍 DEBUG: No hay productos, mostrando cuadro con productos básicos...")
            mostrarCuadroConProductosBasicos()
            return
        }
        
        println("🔍 DEBUG: Creando diálogo de carga de stock con sugerencias...")
        
        val scrollView = ScrollView(this)
        val backgroundColor = if (isDarkMode()) {
            resources.getColor(android.R.color.black, null)
        } else {
            resources.getColor(android.R.color.white, null)
        }
        val textColor = if (isDarkMode()) {
            resources.getColor(android.R.color.white, null)
        } else {
            resources.getColor(android.R.color.black, null)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(backgroundColor)
        }
        
        val txtInfo = TextView(this).apply {
            text = "📊 CARGAR STOCK\n\nIngresa el stock actual de cada producto y ve la sugerencia de pedido automáticamente:"
            textSize = 16f
            setPadding(16, 8, 16, 16)
            setTextColor(textColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        layout.addView(txtInfo)
        
        val editTexts = mutableMapOf<String, EditText>()
        // Limpiar txtSugerencias para evitar duplicados
        txtSugerencias.clear()
        
        productos.forEach { producto ->
            val filaLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 8, 8, 8)
                setBackgroundColor(backgroundColor)
            }
            
            // Fila principal: Código + Input + Sugerencia
            val filaPrincipalDrawable = android.graphics.drawable.GradientDrawable().apply {
                val filaBgColor = if (isDarkMode()) {
                    android.graphics.Color.parseColor("#2D2D2D")
                } else {
                    android.graphics.Color.parseColor("#F5F5F5")
                }
                val filaBorderColor = if (isDarkMode()) {
                    android.graphics.Color.parseColor("#404040")
                } else {
                    android.graphics.Color.parseColor("#E0E0E0")
                }
                setColor(filaBgColor)
                cornerRadius = 6f * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt(), filaBorderColor)
            }
            
            val filaPrincipal = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 4, 8, 4)
                background = filaPrincipalDrawable
            }
            
            val txtProducto = TextView(this).apply {
                text = "${producto.codigo} - ${producto.nombre}"
                textSize = 14f
                setPadding(8, 8, 8, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(textColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            
            // Crear drawable con borde para EditText
            val editStockDrawable = android.graphics.drawable.GradientDrawable().apply {
                val editTextBgColor = if (isDarkMode()) {
                    android.graphics.Color.parseColor("#1E1E1E")
                } else {
                    android.graphics.Color.WHITE
                }
                val editTextBorderColor = if (isDarkMode()) {
                    android.graphics.Color.parseColor("#FFD700")
                } else {
                    resources.getColor(R.color.brand_gold, null)
                }
                setColor(editTextBgColor)
                cornerRadius = 8f * resources.displayMetrics.density
                setStroke((2 * resources.displayMetrics.density).toInt(), editTextBorderColor)
            }
            
            val editStock = EditText(this).apply {
                // Restaurar valor guardado si existe, sino usar el valor del producto
                val valorGuardado = estadoEditTexts[producto.codigo]
                val stockValue = valorGuardado ?: producto.stockActual.toString()
                setText(stockValue)
                Log.d("ADMIN_FLOW", "🔧 EditText Stock para ${producto.codigo}: valor asignado = '$stockValue' (guardado: ${valorGuardado != null})")
                inputType = InputType.TYPE_CLASS_NUMBER
                setPadding(12, 12, 12, 12)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                hint = "Stock actual"
                background = editStockDrawable
                setTextColor(textColor)
                setHintTextColor(if (isDarkMode()) android.graphics.Color.parseColor("#CCCCCC") else android.graphics.Color.parseColor("#808080"))
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                tag = producto.codigo // Agregar tag con el código del producto
                
                // Calcular sugerencia cuando cambie el texto - SOLO para este producto
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        // Solo calcular si no estamos inicializando los productos
                        if (!inicializandoProductos) {
                            val stock = s.toString().toIntOrNull() ?: 0
                            Log.d("SUG", "🔍 SUG: Usuario modificó stock de ${producto.codigo} a $stock")
                            // Usar la función optimizada que solo calcula este producto específico
                            val sugerencia = calcularSugerenciaPedido(producto.codigo, stock)
                            txtSugerencias[producto.codigo]?.text = "💡 Pedido sugerido: $sugerencia unidades"
                        }
                    }
                })
            }
            configurarSeleccionTexto(editStock)
            
            val sugerenciaBackgroundColor = if (isDarkMode()) {
                android.graphics.Color.parseColor("#1E1E1E") // Fondo oscuro para sugerencia
            } else {
                resources.getColor(android.R.color.holo_blue_light, null)
            }
            val sugerenciaBorderColor = if (isDarkMode()) {
                android.graphics.Color.parseColor("#404040") // Borde gris en modo oscuro
            } else {
                android.graphics.Color.parseColor("#E0E0E0") // Borde gris claro en modo claro
            }
            val sugerenciaDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(sugerenciaBackgroundColor)
                cornerRadius = 8f * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt(), sugerenciaBorderColor)
            }
            val txtSugerencia = TextView(this).apply {
                val sugerenciaValue = producto.pedidoSugerido
                text = "💡 Pedido sugerido: $sugerenciaValue unidades"
                Log.d("ADMIN_FLOW", "🔧 TextView Sugerencia para ${producto.codigo}: valor asignado = '$sugerenciaValue'")
                textSize = 14f
                setPadding(8, 8, 8, 8)
                setTextColor(textColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = sugerenciaDrawable
            }
            
            editTexts[producto.codigo] = editStock
            txtSugerencias[producto.codigo] = txtSugerencia
            
            filaPrincipal.addView(txtProducto)
            filaPrincipal.addView(editStock)
            
            filaLayout.addView(filaPrincipal)
            filaLayout.addView(txtSugerencia)
            layout.addView(filaLayout)
        }
        
        scrollView.addView(layout)
        scrollView.setBackgroundColor(backgroundColor)
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("📊 CARGAR STOCK")
            .setView(scrollView)
            .setPositiveButton("💾 Guardar Stock") { _, _ ->
                // Guardar los valores ingresados
                editTexts.forEach { (codigo, editText) ->
                    val stock = editText.text.toString().toIntOrNull() ?: 0
                    repository.actualizarStockProducto(codigo, stock)
                }
                Toast.makeText(this@PedidoFabricaActivity, "Stock guardado correctamente", Toast.LENGTH_SHORT).show()
                cargarDatos() // Recargar para mostrar los cambios
            }
            .setNegativeButton("Cancelar", null)
            .create()
        
        dialog.setOnShowListener {
            // Configurar fondo del diálogo con ColorDrawable directamente
            val window = dialog.window
            val dialogBackgroundColor = if (isDarkMode()) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
            
            // Configurar color del título
            val titleView = dialog.findViewById<android.widget.TextView>(android.R.id.title)
            val titleTextColor = if (isDarkMode()) {
                android.graphics.Color.WHITE
            } else {
                android.graphics.Color.BLACK
            }
            titleView?.setTextColor(titleTextColor)
            
            // Asegurar que el ScrollView y Layout también tengan el fondo correcto
            scrollView.setBackgroundColor(dialogBackgroundColor)
            layout.setBackgroundColor(dialogBackgroundColor)
        }
        
        dialog.show()
    }
    
    /**
     * Muestra el cuadro de carga de stock con productos básicos cuando no hay datos de promedios
     */
    private fun mostrarCuadroConProductosBasicos() {
        println("🔍 DEBUG: Creando cuadro con productos básicos...")
        
        val scrollView = ScrollView(this)
        val backgroundColor = if (isDarkMode()) {
            resources.getColor(android.R.color.black, null)
        } else {
            resources.getColor(android.R.color.white, null)
        }
        val textColor = if (isDarkMode()) {
            resources.getColor(android.R.color.white, null)
        } else {
            resources.getColor(android.R.color.black, null)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(backgroundColor)
        }
        
           // Título principal (igual que Ingreso de Mercadería)
           val txtTitulo = TextView(this).apply {
               text = "CARGAR STOCK"
               textSize = 20f
               setPadding(16, 16, 16, 8)
               setTextColor(textColor)
               setTypeface(null, android.graphics.Typeface.BOLD)
               gravity = android.view.Gravity.CENTER
           }
           layout.addView(txtTitulo)

           // Texto de sugerencia arriba
           val txtSugerencia = TextView(this).apply {
               text = "💡 Sugerencia de Pedido"
               textSize = 16f
               setPadding(16, 8, 16, 16)
               setTextColor(textColor)
               setTypeface(null, android.graphics.Typeface.BOLD)
               gravity = android.view.Gravity.CENTER
           }
           layout.addView(txtSugerencia)
        
        // Usar exactamente el mismo formato que CargaActivity (Stock Final)
        // SIN PANIFICADORA
        val ordenCategorias = listOf("Empanadas", "Pizzas", "Pastelitos")
        
        // Orden específico de productos dentro de cada categoría (igual que CargaActivity) - SIN PANIFICADORA
        val ordenProductos = mapOf(
            "Empanadas" to listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH"),
            "Pizzas" to listOf("MUZZARE", "JAMON", "PEPPERO"),
            "Pastelitos" to listOf("BATATA", "MEMBRIL")
            // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity
        )

        val editTexts = mutableMapOf<String, EditText>()

        // Mostrar cada categoría en el orden específico (igual que CargaActivity)
        for (categoria in ordenCategorias) {
            val productosCategoria = ordenProductos[categoria] ?: emptyList()
            if (productosCategoria.isNotEmpty()) {
                mostrarCategoriaConSugerencia(categoria, productosCategoria, editTexts, layout)
            }
        }
        
        scrollView.addView(layout)
        scrollView.setBackgroundColor(backgroundColor)
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("📊 CARGAR STOCK")
            .setView(scrollView)
            .setPositiveButton("💾 PREVISUALIZAR Y GUARDAR") { _, _ ->
                // Guardar los valores ingresados
                editTexts.forEach { (codigo, editText) ->
                    val stock = editText.text.toString().toIntOrNull() ?: 0
                    repository.actualizarStockProducto(codigo, stock)
                }
                Toast.makeText(this@PedidoFabricaActivity, "Stock guardado correctamente", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .create()
        
        dialog.setOnShowListener {
            // Configurar fondo del diálogo con ColorDrawable directamente
            val window = dialog.window
            val dialogBackgroundColor = if (isDarkMode()) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
            
            // Configurar color del título
            val titleView = dialog.findViewById<android.widget.TextView>(android.R.id.title)
            val titleTextColor = if (isDarkMode()) {
                android.graphics.Color.WHITE
            } else {
                android.graphics.Color.BLACK
            }
            titleView?.setTextColor(titleTextColor)
            
            // Asegurar que el ScrollView y Layout también tengan el fondo correcto
            scrollView.setBackgroundColor(dialogBackgroundColor)
            layout.setBackgroundColor(dialogBackgroundColor)
        }
        
        dialog.show()
    }
    
    /**
     * Abre la sección de carga de stock 15:30 integrada en esta pantalla
     */
    private fun abrirSeccionCargarStock() {
        Log.d("PedidoFabricaActivity", "📊 Mostrando sección de carga de stock 15:30 integrada...")
        
        // Mostrar la sección de carga de stock integrada en esta pantalla
        mostrarSeccionCargarStockIntegrada()
    }
    
    /**
     * Muestra la sección de carga de stock 15:30 integrada en la pantalla actual
     */
    private fun mostrarSeccionCargarStockIntegrada() {
        // Obtener el layout de contenido
        val layoutContenido = findViewById<LinearLayout>(R.id.layoutContenido)
        layoutContenido?.removeAllViews()
        
        // Crear la sección de carga de stock (sin padding superior)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 16, 16)  // Top padding = 0
            setBackgroundColor(getThemeColor(android.R.attr.colorBackground))
        }
        
        // Leyenda de íconos ULTRA compacta
        val leyendaTendencias = TextView(this).apply {
            text = "📈≥10% | ⬆️5-10% | 📉≤-10% | ⬇️-10 a -5%"  // En una línea
            textSize = 9f
            setPadding(8, 2, 8, 4)  // Mínimo padding
            setTextColor(getThemeColor(android.R.attr.textColorSecondary))
            gravity = android.view.Gravity.CENTER
        }
        layout.addView(leyendaTendencias)
        
        // Usar el mismo formato que CargaActivity - SIN PANIFICADORA
        val ordenCategorias = listOf("Empanadas", "Pizzas", "Pastelitos")
        val ordenProductos = mapOf(
            "Empanadas" to listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH"),
            "Pizzas" to listOf("MUZZARE", "JAMON", "PEPPERO"),
            "Pastelitos" to listOf("BATATA", "MEMBRIL")
            // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity
        )
        
        val editTexts = mutableMapOf<String, EditText>()
        
        // 📈 Calcular tendencia general de ventas (promedio de productos principales)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val productosParaTendencia = listOf("JQ", "PB", "CS") // Productos principales
                val tendencias = productosParaTendencia.mapNotNull { codigo ->
                    try {
                        val t = detectarTendenciaVentas(codigo)
                        Log.d("TENDENCIA_UI", "  Producto $codigo: ${String.format("%.1f", t)}%")
                        if (t != 0.0) t else null // Solo contar si es significativa
                    } catch (e: Exception) {
                        null
                    }
                }
                
                Log.d("TENDENCIA_UI", "  Tendencias válidas encontradas: ${tendencias.size}")
                tendencias.forEachIndexed { i, t -> 
                    Log.d("TENDENCIA_UI", "    [$i] = ${String.format("%.1f", t)}%")
                }
                
                val tendenciaGeneral = if (tendencias.isNotEmpty()) {
                    tendencias.average()
                } else {
                    0.0
                }
                
                Log.d("TENDENCIA_UI", "📊 Tendencia general calculada: ${String.format("%.1f", tendenciaGeneral)}% (de ${tendencias.size} productos)")
                
                // Actualizar UI en el hilo principal
                withContext(Dispatchers.Main) {
                    if (kotlin.math.abs(tendenciaGeneral) >= 5.0) {
                        val icono = if (tendenciaGeneral > 0) "📈" else "📉"
                        val texto = if (tendenciaGeneral > 0) "AUMENTO" else "DISMINUCIÓN"
                        val color = if (tendenciaGeneral > 0) {
                            resources.getColor(android.R.color.holo_green_dark, null)
                        } else {
                            resources.getColor(android.R.color.holo_orange_dark, null)
                        }
                        
                        txtTendenciaVentas.text = "$icono $texto DE VENTAS: ${String.format("%.0f", kotlin.math.abs(tendenciaGeneral))}%"
                        txtTendenciaVentas.setBackgroundColor(color)
                        txtTendenciaVentas.setTextColor(resources.getColor(android.R.color.white, null))
                        txtTendenciaVentas.visibility = View.VISIBLE
                        
                        Log.d("TENDENCIA_UI", "✅ Indicador de tendencia mostrado: $tendenciaGeneral%")
                    } else {
                        txtTendenciaVentas.visibility = View.GONE
                        Log.d("TENDENCIA_UI", "ℹ️ Tendencia no significativa, indicador oculto")
                    }
                }
            } catch (e: Exception) {
                Log.e("TENDENCIA_UI", "Error calculando tendencia general: ${e.message}")
            }
        }
        
        // Mostrar cada categoría
        for (categoria in ordenCategorias) {
            val productosCategoria = ordenProductos[categoria] ?: emptyList()
            if (productosCategoria.isNotEmpty()) {
                mostrarCategoriaIntegrada(categoria, productosCategoria, editTexts, layout)
            }
        }
        
        // Agregar botón de WhatsApp
        val btnWhatsApp = Button(this).apply {
            text = when {
                modoAdmin -> "📱 Enviar por WhatsApp (Admin)"
                modoSoloLectura -> "📱 Ver Pedido (Solo Lectura)"
                else -> "📱 Enviar por WhatsApp"
            }
            setPadding(16, 16, 16, 16)
            setBackgroundColor(resources.getColor(android.R.color.holo_green_dark, null))
            setTextColor(resources.getColor(android.R.color.white, null))
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setOnClickListener {
                // Si no es modo solo lectura, guardar primero
                if (!modoSoloLectura) {
                    guardarPedidoEnFirestore(editTexts)
                }
                // Generar y enviar pedido por WhatsApp
                generarYEnviarPedidoWhatsApp(editTexts)
            }
        }
        layout.addView(btnWhatsApp)
        
        // Agregar el layout al contenido principal
        layoutContenido?.addView(layout)

        // Misma pantalla que tendencias/stock: acá no pasaba por mostrarContenidoPedidoInterno()
        solicitarContextoDemandaManiana()
    }
    
    /**
     * Muestra una categoría integrada en la pantalla actual
     */
    private fun mostrarCategoriaIntegrada(categoria: String, productos: List<String>, editTexts: MutableMap<String, EditText>, layout: LinearLayout) {
        // Crear contenedor de categoría (igual que CargaActivity)
        val categoriaLayout = LinearLayout(this)
        categoriaLayout.orientation = LinearLayout.VERTICAL
        categoriaLayout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        categoriaLayout.setPadding(0, 16, 0, 8)
        
        // Header de categoría con color (igual que CargaActivity)
        val headerLayout = LinearLayout(this)
        headerLayout.orientation = LinearLayout.HORIZONTAL
        headerLayout.setPadding(12, 12, 12, 12)
        headerLayout.gravity = android.view.Gravity.CENTER_VERTICAL
        
        // Aplicar color según categoría (igual que CargaActivity)
        val headerBackground = when (categoria) {
            "Empanadas" -> R.drawable.category_empanadas_header
            "Pizzas" -> R.drawable.category_pizzas_header
            "Pastelitos" -> R.drawable.category_pastelitos_header
            "Otros" -> R.drawable.category_otros_header
            else -> R.drawable.category_header_background
        }
        headerLayout.setBackgroundResource(headerBackground)
        
        val txtCategoria = TextView(this)
        txtCategoria.text = categoria.uppercase()
        txtCategoria.textSize = 18f
        txtCategoria.setTextColor(resources.getColor(android.R.color.white, null))
        txtCategoria.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        
        val txtCantidad = TextView(this)
        txtCantidad.text = "${productos.size} productos"
        txtCantidad.textSize = 12f
        txtCantidad.setTextColor(resources.getColor(android.R.color.white, null))
        txtCantidad.setBackgroundResource(R.drawable.category_count_background)
        txtCantidad.setPadding(8, 4, 8, 4)
        
        // Agregar icono de categoría
        val iconoCategoria = TextView(this)
        iconoCategoria.text = when (categoria) {
            "Empanadas" -> "🥟"
            "Pizzas" -> "🍕"
            "Pastelitos" -> "🥧"
            "Otros" -> "🥐"
            else -> "📦"
        }
        iconoCategoria.textSize = 24f
        iconoCategoria.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = 12
        }
        
        headerLayout.addView(iconoCategoria)
        headerLayout.addView(txtCategoria)
        headerLayout.addView(txtCantidad)
        
        // Contenedor de productos con fondo dinámico
        val isDark = isDarkMode()
        val contentBackgroundColor = if (isDark) {
            android.graphics.Color.parseColor("#1E1E1E") // Gris oscuro
        } else {
            android.graphics.Color.parseColor("#FFF5F5") // Blanco rosado (original)
        }
        val textColor = if (isDark) {
            android.graphics.Color.WHITE // Texto blanco en modo oscuro
        } else {
            android.graphics.Color.BLACK // Texto negro en modo claro
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
        val editTextBackgroundColor = if (isDark) {
            android.graphics.Color.parseColor("#1E1E1E") // Fondo oscuro para EditText
        } else {
            android.graphics.Color.WHITE
        }
        val editTextBorderColor = if (isDark) {
            android.graphics.Color.parseColor("#FFD700") // Borde dorado en modo oscuro
        } else {
            resources.getColor(R.color.brand_gold, null)
        }
        
        val productosLayout = LinearLayout(this)
        productosLayout.orientation = LinearLayout.VERTICAL
        productosLayout.setBackgroundColor(contentBackgroundColor)
        productosLayout.setPadding(8, 8, 8, 8)
        
        // Agregar productos con el estilo original de Ingreso/Stock Final pero con 3 columnas
        for (codigo in productos) {
            val fila = layoutInflater.inflate(R.layout.item_producto_stock_1530_3columnas, null)
            val txtCodigo = fila.findViewById<TextView>(R.id.txtCodigo)
            val edtCantidad = fila.findViewById<EditText>(R.id.edtCantidad)
            val txtSugerencia = fila.findViewById<TextView>(R.id.txtSugerencia)
            val edtPedido = fila.findViewById<EditText>(R.id.edtPedido)
            
            // Aplicar fondo y borde a la fila
            val filaDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(itemBackgroundColor)
                cornerRadius = 6f * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt(), itemBorderColor)
            }
            fila.background = filaDrawable
            
            // Aplicar colores de texto
            txtCodigo.setTextColor(textColor)
            txtSugerencia.setTextColor(if (isDark) resources.getColor(android.R.color.holo_green_light, null) else resources.getColor(android.R.color.holo_green_dark, null))
            
            // Aplicar fondo y borde al TextView de sugerencia
            val sugerenciaBackgroundColor = if (isDark) {
                android.graphics.Color.parseColor("#1E1E1E") // Fondo oscuro para sugerencia
            } else {
                android.graphics.Color.WHITE
            }
            val sugerenciaBorderColor = if (isDark) {
                android.graphics.Color.parseColor("#404040") // Borde gris en modo oscuro
            } else {
                android.graphics.Color.parseColor("#E0E0E0") // Borde gris claro en modo claro
            }
            val sugerenciaDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(sugerenciaBackgroundColor)
                cornerRadius = 8f * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt(), sugerenciaBorderColor)
            }
            txtSugerencia.background = sugerenciaDrawable
            
            // Aplicar fondo y borde a EditTexts
            val editTextDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(editTextBackgroundColor)
                cornerRadius = 8f * resources.displayMetrics.density
                setStroke((2 * resources.displayMetrics.density).toInt(), editTextBorderColor)
            }
            edtCantidad.background = editTextDrawable
            edtPedido.background = editTextDrawable
            edtCantidad.setTextColor(textColor)
            edtPedido.setTextColor(textColor)
            edtCantidad.setHintTextColor(if (isDark) android.graphics.Color.parseColor("#CCCCCC") else android.graphics.Color.parseColor("#808080"))
            edtPedido.setHintTextColor(if (isDark) android.graphics.Color.parseColor("#CCCCCC") else android.graphics.Color.parseColor("#808080"))
            
            // Configurar código con abreviación para pizzas
            val codigoMostrar = abreviarCodigoProducto(codigo)
            txtCodigo.text = codigoMostrar
            
            val badgeStockBajo = fila.findViewById<TextView>(R.id.badgeStockBajo)
            val badgeTendencia = fila.findViewById<TextView>(R.id.badgeTendencia)
            val badgeVencimiento = fila.findViewById<TextView>(R.id.badgeVencimiento)

            // Variables para almacenar estado de iconos/badges
            var tieneStockBajo = false
            var historialStockBajo: List<Pair<String, Int>> = emptyList()
            var tieneTendencia = false
            var tendenciaValor: Double = 0.0

            fun actualizarTextoConIconos() {
                val isDarkTheme = isDarkMode()
                txtCodigo.text = codigoMostrar

                // 1. Badge Stock Bajo
                if (tieneStockBajo) {
                    badgeStockBajo.visibility = View.VISIBLE
                    badgeStockBajo.text = "⚠️ Bajo"
                    val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (isDarkTheme) android.graphics.Color.parseColor("#3E2E1A") else android.graphics.Color.parseColor("#FFF3CD"))
                        cornerRadius = 4f * resources.displayMetrics.density
                        setStroke((1 * resources.displayMetrics.density).toInt(), android.graphics.Color.parseColor("#F59E0B"))
                    }
                    badgeStockBajo.background = bgDrawable
                    badgeStockBajo.setTextColor(if (isDarkTheme) android.graphics.Color.parseColor("#FBBF24") else android.graphics.Color.parseColor("#D97706"))
                    val histAct = historialStockBajo
                    badgeStockBajo.setOnClickListener { mostrarReporteStockBajo(codigo, histAct) }
                } else {
                    badgeStockBajo.visibility = View.GONE
                }

                // 2. Badge Tendencia
                if (tieneTendencia) {
                    badgeTendencia.visibility = View.VISIBLE
                    val esAumento = tendenciaValor >= 0
                    val icono = if (tendenciaValor >= 10.0) "📈" else if (tendenciaValor >= 5.0) "⬆️" else if (tendenciaValor <= -10.0) "📉" else "⬇️"
                    val porcentajeStr = String.format("%.0f", kotlin.math.abs(tendenciaValor))
                    badgeTendencia.text = "$icono $porcentajeStr%"
                    
                    val colorBg = if (esAumento) {
                        if (isDarkTheme) android.graphics.Color.parseColor("#0F2F1D") else android.graphics.Color.parseColor("#D1E7DD")
                    } else {
                        if (isDarkTheme) android.graphics.Color.parseColor("#3B1319") else android.graphics.Color.parseColor("#F8D7DA")
                    }
                    val colorBorder = if (esAumento) android.graphics.Color.parseColor("#10B981") else android.graphics.Color.parseColor("#EF4444")
                    val colorText = if (esAumento) {
                        if (isDarkTheme) android.graphics.Color.parseColor("#34D399") else android.graphics.Color.parseColor("#0F5132")
                    } else {
                        if (isDarkTheme) android.graphics.Color.parseColor("#F87171") else android.graphics.Color.parseColor("#842029")
                    }
                    val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
                        setColor(colorBg)
                        cornerRadius = 4f * resources.displayMetrics.density
                        setStroke((1 * resources.displayMetrics.density).toInt(), colorBorder)
                    }
                    badgeTendencia.background = bgDrawable
                    badgeTendencia.setTextColor(colorText)
                    val tendAct = tendenciaValor
                    badgeTendencia.setOnClickListener { mostrarGraficoConLoading(codigo, tendAct) }
                } else {
                    badgeTendencia.visibility = View.GONE
                }

                // 3. Badge Merma / Vencimiento
                val vencInfo = vencimientosBatchMap[codigo]
                if (vencInfo != null && vencInfo.cantidadVenceHoy > 0) {
                    badgeVencimiento.visibility = View.VISIBLE
                    val cant = vencInfo.cantidadVenceHoy
                    val todoVence = vencInfo.todoVenceHoy
                    
                    if (todoVence) {
                        badgeVencimiento.text = "⏰ Merma Total ($cant u)"
                        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
                            setColor(if (isDarkTheme) android.graphics.Color.parseColor("#4A1212") else android.graphics.Color.parseColor("#F8D7DA"))
                            cornerRadius = 4f * resources.displayMetrics.density
                            setStroke((1 * resources.displayMetrics.density).toInt(), android.graphics.Color.parseColor("#DC2626"))
                        }
                        badgeVencimiento.background = bgDrawable
                        badgeVencimiento.setTextColor(if (isDarkTheme) android.graphics.Color.parseColor("#FF6B6B") else android.graphics.Color.parseColor("#842029"))
                    } else {
                        badgeVencimiento.text = "⏰ Vence ($cant u)"
                        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
                            setColor(if (isDarkTheme) android.graphics.Color.parseColor("#3E2E1A") else android.graphics.Color.parseColor("#FFF3CD"))
                            cornerRadius = 4f * resources.displayMetrics.density
                            setStroke((1 * resources.displayMetrics.density).toInt(), android.graphics.Color.parseColor("#F59E0B"))
                        }
                        badgeVencimiento.background = bgDrawable
                        badgeVencimiento.setTextColor(if (isDarkTheme) android.graphics.Color.parseColor("#FBBF24") else android.graphics.Color.parseColor("#D97706"))
                    }
                    
                    badgeVencimiento.setOnClickListener {
                        mostrarDetalleVencimientoMerma(codigo, vencInfo)
                    }
                } else {
                    badgeVencimiento.visibility = View.GONE
                }
            }
            
            // 📈 Agregar tendencia del cache si está disponible (instantáneo)
            val fechaHoy = DateHelper.getFechaActual()
            if (configuracion.usarAjusteTendencia &&
                MainActivity.cacheTendencias != null && 
                MainActivity.cacheTendenciasFecha == fechaHoy && 
                MainActivity.cacheTendenciasLocalId == localId) {
                
                tendenciaValor = MainActivity.cacheTendencias!![codigo] ?: 0.0
                tieneTendencia = kotlin.math.abs(tendenciaValor) >= 5.0
                Log.d("TENDENCIA_CACHE", "⚡ Tendencia desde cache: $codigo → ${String.format("%.0f", kotlin.math.abs(tendenciaValor))}% (tieneTendencia=$tieneTendencia)")
            }
            
            // Verificar si hay stock bajo en los últimos 7 días (después de cargar tendencia)
            lifecycleScope.launch {
                try {
                    val historial = obtenerHistorialStockBajo(codigo)
                    historialStockBajo = historial
                    tieneStockBajo = historial.isNotEmpty()
                    Log.d("STOCK_BAJO", "🔍 Stock bajo para $codigo: $tieneStockBajo (${historial.size} registros)")
                    runOnUiThread {
                        actualizarTextoConIconos()
                    }
                } catch (e: Exception) {
                    Log.e("PedidoFabricaActivity", "Error obteniendo historial stock bajo para $codigo: ${e.message}", e)
                    tieneStockBajo = false
                    historialStockBajo = emptyList()
                    runOnUiThread {
                        actualizarTextoConIconos()
                    }
                }
            }
            
            // Si ya tenemos la tendencia, actualizar inmediatamente
            if (tieneTendencia) {
                actualizarTextoConIconos()
            }
            
            // Configurar valores iniciales
            val producto = this.productos.find { it.codigo == codigo }
            edtCantidad.setText((producto?.stockActual ?: 0).toString())
            txtSugerencia.text = (producto?.pedidoSugerido ?: 0).toString()
            edtPedido.setText((producto?.pedidoFinal ?: 0).toString())
            
            // Configurar tags para identificación
            edtCantidad.tag = "${codigo}_stock"
            edtPedido.tag = "${codigo}_pedido"
            txtSugerencia.tag = "${codigo}_sugerencia"
            
            // Configurar modo solo lectura si es necesario (ANTES de configurar selección de texto)
            if (modoSoloLectura && !modoAdmin) {
                // Para usuarios normales en fechas históricas: TODAS las columnas son solo lectura
                edtCantidad.isEnabled = false
                edtPedido.isEnabled = false
                edtCantidad.setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
                edtPedido.setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
            } else {
                // Solo configurar selección automática de texto si NO es modo solo lectura
                configurarSeleccionTexto(edtCantidad)
                configurarSeleccionTexto(edtPedido)
            }
            
            // Configurar sugerencias automáticas
            // El listener se actualizará después para incluir actualización de totales
            edtCantidad.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!inicializandoProductos) {
                        val stock = s.toString().toIntOrNull() ?: 0
                        val sugerencia = calcularSugerenciaPedido(codigo, stock)
                        txtSugerencia.text = sugerencia.toString()
                    }
                }
            })
            
            editTexts[codigo] = edtCantidad
            productosLayout.addView(fila)
        }
        
        // Crear fila de totales usando el mismo layout que los productos para alineación perfecta
        val filaTotal = layoutInflater.inflate(R.layout.item_producto_stock_1530_3columnas, null)
        val txtCodigoTotal = filaTotal.findViewById<TextView>(R.id.txtCodigo)
        val edtCantidadTotal = filaTotal.findViewById<EditText>(R.id.edtCantidad)
        val txtSugerenciaTotal = filaTotal.findViewById<TextView>(R.id.txtSugerencia)
        val edtPedidoTotal = filaTotal.findViewById<EditText>(R.id.edtPedido)
        
        // Configurar estilo de totales
        val totalBackgroundColor = if (isDark) {
            android.graphics.Color.parseColor("#2D2D2D")
        } else {
            android.graphics.Color.parseColor("#FFF9E6") // Amarillo muy claro
        }
        val totalTextColor = if (isDark) {
            android.graphics.Color.parseColor("#FFD700") // Dorado en modo oscuro
        } else {
            resources.getColor(R.color.brand_gold_dark, null)
        }
        val totalDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(totalBackgroundColor)
            cornerRadius = 6f * resources.displayMetrics.density
            setStroke((1 * resources.displayMetrics.density).toInt(), if (isDark) android.graphics.Color.parseColor("#404040") else android.graphics.Color.parseColor("#E0E0E0"))
        }
        filaTotal.background = totalDrawable
        
        // Configurar columna 1: Label "TOTAL"
        txtCodigoTotal.text = "TOTAL"
        txtCodigoTotal.textSize = 13f
        txtCodigoTotal.setTypeface(null, android.graphics.Typeface.BOLD)
        txtCodigoTotal.setTextColor(totalTextColor)
        
        // Configurar columna 2: Total Stock
        // Ocultar el label "Stock" y usar solo el valor
        val layoutStockTotal = edtCantidadTotal.parent as? LinearLayout
        layoutStockTotal?.getChildAt(0)?.visibility = android.view.View.GONE // Ocultar label "Stock"
        edtCantidadTotal.visibility = android.view.View.GONE
        val dp60 = (60 * resources.displayMetrics.density).toInt()
        val dp35 = (35 * resources.displayMetrics.density).toInt()
        val txtTotalStock = TextView(this).apply {
            text = "0"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(totalTextColor)
            gravity = android.view.Gravity.CENTER
            layoutParams = android.view.ViewGroup.LayoutParams(dp60, dp35)
            tag = "total_stock_$categoria"
        }
        layoutStockTotal?.addView(txtTotalStock)
        
        // Configurar columna 3: Total Sugerencia
        val layoutSugerenciaTotal = txtSugerenciaTotal.parent as? LinearLayout
        layoutSugerenciaTotal?.getChildAt(0)?.visibility = android.view.View.GONE // Ocultar label "Sugerencia"
        txtSugerenciaTotal.text = "0"
        txtSugerenciaTotal.textSize = 16f
        txtSugerenciaTotal.setTypeface(null, android.graphics.Typeface.BOLD)
        txtSugerenciaTotal.setTextColor(totalTextColor)
        txtSugerenciaTotal.tag = "total_sugerencia_$categoria"
        
        // Configurar columna 4: Total Pedido
        val layoutPedidoTotal = edtPedidoTotal.parent as? LinearLayout
        layoutPedidoTotal?.getChildAt(0)?.visibility = android.view.View.GONE // Ocultar label "Pedido Final"
        edtPedidoTotal.visibility = android.view.View.GONE
        val txtTotalPedido = TextView(this).apply {
            text = "0"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(totalTextColor)
            gravity = android.view.Gravity.CENTER
            layoutParams = android.view.ViewGroup.LayoutParams(dp60, dp35)
            tag = "total_pedido_$categoria"
        }
        layoutPedidoTotal?.addView(txtTotalPedido)
        
        // Agregar la fila de totales al layout de productos
        productosLayout.addView(filaTotal)
        
        // Función para actualizar los totales de la categoría
        fun actualizarTotalesCategoria() {
            var totalStock = 0
            var totalSugerencia = 0
            var totalPedido = 0
            
            // Iterar sobre todos los hijos EXCEPTO el último (que es la fila de totales)
            val cantidadFilas = productosLayout.childCount - 1
            for (i in 0 until cantidadFilas) {
                val fila = productosLayout.getChildAt(i)
                val edtCantidad = fila?.findViewById<EditText>(R.id.edtCantidad)
                val txtSugerencia = fila?.findViewById<TextView>(R.id.txtSugerencia)
                val edtPedido = fila?.findViewById<EditText>(R.id.edtPedido)
                
                if (edtCantidad != null) {
                    totalStock += edtCantidad.text.toString().toIntOrNull() ?: 0
                }
                if (txtSugerencia != null) {
                    totalSugerencia += txtSugerencia.text.toString().toIntOrNull() ?: 0
                }
                if (edtPedido != null) {
                    totalPedido += edtPedido.text.toString().toIntOrNull() ?: 0
                }
            }
            
            // Actualizar los TextViews de totales usando las referencias guardadas
            txtTotalStock.text = totalStock.toString()
            txtSugerenciaTotal.text = totalSugerencia.toString()
            txtTotalPedido.text = totalPedido.toString()
        }
        
        // Agregar listeners adicionales para actualizar totales DESPUÉS de agregar todos los productos
        // y la fila de totales. Nota: los listeners de sugerencia automática ya existen, estos son adicionales
        for (i in 0 until productos.size) {
            val fila = productosLayout.getChildAt(i)
            val edtCantidad = fila?.findViewById<EditText>(R.id.edtCantidad)
            val txtSugerencia = fila?.findViewById<TextView>(R.id.txtSugerencia)
            val edtPedido = fila?.findViewById<EditText>(R.id.edtPedido)
            
            // Agregar listener adicional para actualizar totales cuando cambia stock
            // (la sugerencia se actualizará automáticamente por el listener existente)
            edtCantidad?.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    // Usar post para asegurar que la sugerencia se haya actualizado primero
                    txtSugerencia?.post {
                        actualizarTotalesCategoria()
                    }
                }
            })
            
            // Listener para actualizar totales cuando cambia pedido
            edtPedido?.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    actualizarTotalesCategoria()
                }
            })
        }
        
        // Actualizar los totales inicialmente
        actualizarTotalesCategoria()
        
        categoriaLayout.addView(headerLayout)
        categoriaLayout.addView(productosLayout)
        layout.addView(categoriaLayout)
    }
    
    /**
     * Configura las sugerencias para el layout integrado
     */
    private fun configurarSugerenciasIntegradas(fila: View, codigo: String) {
        val edtCantidad = fila.findViewById<EditText>(R.id.edtCantidad)
        val txtSugerencia = fila.findViewById<TextView>(R.id.txtSugerencia)
        
        // Seleccionar todo el texto cuando se toque
        edtCantidad.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                edtCantidad.selectAll()
            }
        }
        
        // Agregar listener para calcular sugerencia automáticamente
        edtCantidad.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // Solo calcular si no estamos inicializando los productos
                if (!inicializandoProductos) {
                    val stock = s?.toString()?.toIntOrNull() ?: 0
                    Log.d("SUG", "🔍 SUG: Usuario modificó stock de $codigo a $stock (sección 2)")
                    val sugerencia = calcularSugerenciaPedido(codigo, stock)
                    txtSugerencia.text = sugerencia.toString()
                }
            }
        })
    }
    
    /**
     * Obtiene la fecha actual en formato string
     */
    private fun obtenerFechaActual(): String {
        val sdf = DateHelper.getDateFormat("dd/MM/yyyy")
        return sdf.format(java.util.Date())
    }
    
    /**
     * Muestra una categoría con tres columnas: Stock, Sugerencia, Pedido Final
     */
    private fun mostrarCategoriaConSugerencia(categoria: String, productos: List<String>, editTexts: MutableMap<String, EditText>, layout: LinearLayout) {
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
        val editTextBackgroundColor = if (isDark) {
            android.graphics.Color.parseColor("#1E1E1E") // Fondo oscuro para EditText
        } else {
            android.graphics.Color.WHITE
        }
        val editTextBorderColor = if (isDark) {
            android.graphics.Color.parseColor("#FFD700") // Borde dorado en modo oscuro
        } else {
            resources.getColor(R.color.brand_gold, null)
        }
        
        // Crear contenedor de categoría (igual que CargaActivity)
        val categoriaLayout = LinearLayout(this)
        categoriaLayout.orientation = LinearLayout.VERTICAL
        categoriaLayout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        categoriaLayout.setPadding(0, 16, 0, 8)
        
        // Header de categoría con color (igual que CargaActivity)
        val headerLayout = LinearLayout(this)
        headerLayout.orientation = LinearLayout.HORIZONTAL
        headerLayout.setPadding(12, 12, 12, 12)
        headerLayout.gravity = android.view.Gravity.CENTER_VERTICAL
        
        // Aplicar color según categoría (igual que CargaActivity)
        val headerBackground = when (categoria) {
            "Empanadas" -> R.drawable.category_empanadas_header
            "Pizzas" -> R.drawable.category_pizzas_header
            "Pastelitos" -> R.drawable.category_pastelitos_header
            "Otros" -> R.drawable.category_otros_header
            else -> R.drawable.category_header_background
        }
        headerLayout.setBackgroundResource(headerBackground)
        
        val txtCategoria = TextView(this)
        txtCategoria.text = categoria.uppercase()
        txtCategoria.textSize = 18f
        txtCategoria.setTextColor(resources.getColor(android.R.color.white, null))
        txtCategoria.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        
        val txtCantidad = TextView(this)
        txtCantidad.text = "${productos.size} productos"
        txtCantidad.textSize = 12f
        txtCantidad.setTextColor(resources.getColor(android.R.color.white, null))
        txtCantidad.setBackgroundResource(R.drawable.category_count_background)
        txtCantidad.setPadding(8, 4, 8, 4)
        
        // Agregar icono de categoría
        val iconoCategoria = TextView(this)
        iconoCategoria.text = when (categoria) {
            "Empanadas" -> "🥟"
            "Pizzas" -> "🍕"
            "Pastelitos" -> "🥧"
            "Otros" -> "🥐"
            else -> "📦"
        }
        iconoCategoria.textSize = 24f
        iconoCategoria.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = 12
        }
        
        headerLayout.addView(iconoCategoria)
        headerLayout.addView(txtCategoria)
        headerLayout.addView(txtCantidad)
        
        // Contenedor de productos con fondo dinámico
        val productosLayout = LinearLayout(this)
        productosLayout.orientation = LinearLayout.VERTICAL
        productosLayout.setBackgroundColor(contentBackgroundColor)
        productosLayout.setPadding(8, 8, 8, 8)
        
        // Agregar productos con tres columnas: Stock, Sugerencia, Pedido Final
        for (codigo in productos) {
            // Crear drawable con borde para la fila
            val filaDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(itemBackgroundColor)
                cornerRadius = 6f * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt(), itemBorderColor)
            }
            
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(12, 12, 12, 12)
                background = filaDrawable
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 4, 0, 4)
                }
            }
            
            // Columna 1: Código y Icono de Advertencia
            val columnaInfo = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            }
            
            val txtCodigo = TextView(this).apply {
                text = codigo
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            
            columnaInfo.addView(txtCodigo)
            
            // Verificar si hay stock bajo en los últimos 7 días y agregar icono de advertencia
            lifecycleScope.launch {
                val historialStockBajo = obtenerHistorialStockBajo(codigo)
                if (historialStockBajo.isNotEmpty()) {
                    val iconoAdvertencia = TextView(this@PedidoFabricaActivity).apply {
                        text = "⚠️"
                        textSize = 16f
                        setPadding(8, 0, 0, 0)
                        setOnClickListener {
                            mostrarReporteStockBajo(codigo, historialStockBajo)
                        }
                    }
                    columnaInfo.addView(iconoAdvertencia)
                }
            }
            
            fila.addView(columnaInfo)
            
            // Columna 2: Stock 15:30
            val edtStockDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(editTextBackgroundColor)
                cornerRadius = 8f * resources.displayMetrics.density
                setStroke((2 * resources.displayMetrics.density).toInt(), editTextBorderColor)
            }
            val edtStock = EditText(this).apply {
                hint = "Stock"
                textSize = 14f
                inputType = InputType.TYPE_CLASS_NUMBER
                background = edtStockDrawable
                setTextColor(textColor)
                setHintTextColor(if (isDark) android.graphics.Color.parseColor("#CCCCCC") else android.graphics.Color.parseColor("#808080"))
                setPadding(8, 8, 8, 8)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                tag = "${codigo}_stock"
            }
            configurarSeleccionTexto(edtStock)
            fila.addView(edtStock)
            
            // Columna 3: Sugerencia (solo lectura)
            val sugerenciaBackgroundColor = if (isDark) {
                android.graphics.Color.parseColor("#1E1E1E") // Fondo oscuro para sugerencia
            } else {
                android.graphics.Color.WHITE
            }
            val sugerenciaBorderColor = if (isDark) {
                android.graphics.Color.parseColor("#404040") // Borde gris en modo oscuro
            } else {
                android.graphics.Color.parseColor("#E0E0E0") // Borde gris claro en modo claro
            }
            val sugerenciaDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(sugerenciaBackgroundColor)
                cornerRadius = 8f * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt(), sugerenciaBorderColor)
            }
            val txtSugerencia = TextView(this).apply {
                text = "0"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (isDark) resources.getColor(android.R.color.holo_green_light, null) else resources.getColor(android.R.color.holo_green_dark, null))
                setPadding(8, 8, 8, 8)
                gravity = android.view.Gravity.CENTER
                background = sugerenciaDrawable
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                tag = "${codigo}_sugerencia"
            }
            fila.addView(txtSugerencia)
            
            // Columna 4: Pedido Final
            val edtPedidoDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(editTextBackgroundColor)
                cornerRadius = 8f * resources.displayMetrics.density
                setStroke((2 * resources.displayMetrics.density).toInt(), editTextBorderColor)
            }
            val edtPedido = EditText(this).apply {
                hint = "Pedido"
                textSize = 14f
                inputType = InputType.TYPE_CLASS_NUMBER
                background = edtPedidoDrawable
                setTextColor(textColor)
                setHintTextColor(if (isDark) android.graphics.Color.parseColor("#CCCCCC") else android.graphics.Color.parseColor("#808080"))
                setPadding(8, 8, 8, 8)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                tag = "${codigo}_pedido"
            }
            configurarSeleccionTexto(edtPedido)
            fila.addView(edtPedido)
            
            // Configurar valores iniciales
            val producto = this.productos.find { it.codigo == codigo }
            edtStock.setText((producto?.stockActual ?: 0).toString())
            txtSugerencia.text = (producto?.pedidoSugerido ?: 0).toString()
            edtPedido.setText((producto?.pedidoFinal ?: 0).toString())
            
            // Configurar modo solo lectura si es necesario
            if (modoSoloLectura && !modoAdmin) {
                edtStock.isEnabled = false
                edtPedido.isEnabled = false
                edtStock.setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
                edtPedido.setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
            }
            
            // Configurar sugerencias automáticas
            edtStock.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!inicializandoProductos) {
                        val stock = s.toString().toIntOrNull() ?: 0
                        val sugerencia = calcularSugerenciaPedido(codigo, stock)
                        txtSugerencia.text = sugerencia.toString()
                    }
                }
            })
            
            editTexts[codigo] = edtStock
            productosLayout.addView(fila)
        }
        
        categoriaLayout.addView(headerLayout)
        categoriaLayout.addView(productosLayout)
        layout.addView(categoriaLayout)
    }
    
    /**
     * Obtiene el valor de sugerencia para un producto
     */
    private fun obtenerValorSugerencia(codigo: String): Int {
        try {
            // 1. Buscar en la ventana emergente (AlertDialog) - PRIORIDAD ALTA
            val tagSugerencia = "${codigo}_sugerencia"
            val textViewSugerencia = layoutContenido.findViewWithTag<TextView>(tagSugerencia)
            if (textViewSugerencia != null) {
                val valor = textViewSugerencia.text?.toString()?.toIntOrNull() ?: 0
                Log.d("SUGERENCIA", "Valor encontrado en ventana emergente para $codigo: $valor")
                return valor
            }
            
            // 2. Buscar el producto en la lista actual
            val producto = productos.find { it.codigo == codigo }
            if (producto != null) {
                Log.d("SUGERENCIA", "Valor encontrado en lista productos para $codigo: ${producto.pedidoSugerido}")
                return producto.pedidoSugerido
            }
            
            // 3. Si no se encuentra, calcular la sugerencia
            val nombreProducto = obtenerNombreProducto(codigo)
            val valorCalculado = calcularSugerenciaParaProducto(nombreProducto, fechaSeleccionada, 0)
            Log.d("SUGERENCIA", "Valor calculado para $codigo: $valorCalculado")
            return valorCalculado
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error obteniendo sugerencia para $codigo: ${e.message}")
            return 0
        }
    }
    
    /**
     * Obtiene el nombre del producto basado en el código
     */
    /**
     * Abrevia el código del producto para mostrar en la UI (especialmente para pizzas)
     */
    private fun abreviarCodigoProducto(codigo: String): String {
        return when (codigo) {
            "MUZZARE" -> "M"
            "JAMON" -> "J"
            "PEPPERO" -> "P"
            else -> codigo
        }
    }
    
    private fun obtenerNombreProducto(codigo: String): String {
        return when (codigo) {
            "JQ" -> "Jamón y Queso"
            "PB" -> "Pollo BBQ"
            "CS" -> "Carne Suave"
            "PO" -> "Pollo"
            "CP" -> "Carne Picante"
            "HU" -> "Humita"
            "ES" -> "Espinaca"
            "CQ" -> "Calabaza y Queso"
            "RJ" -> "Roquefort y Jamón"
            "QC" -> "Queso y Cebolla"
            "CB" -> "Cerdo y Barbacoa"
            "CH" -> "Cheeseburger"
            "MUZZARE" -> "Muzzarella"
            "JAMON" -> "Jamón"
            "PEPPERO" -> "Pepperoni"
            "BATATA" -> "Batata"
            "MEMBRIL" -> "Membrillo"
            // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity
            else -> codigo
        }
    }
    
    /**
     * Redondea la sugerencia según el tipo de producto
     * Si la sugerencia es muy baja (menor al porcentaje configurado del múltiplo), devuelve 0
     */
    private fun redondearSugerencia(sugerencia: Double, codigo: String): Int {
        // Si la sugerencia es 0 o negativa, devolver 0
        if (sugerencia <= 0) return 0
        
        // Usar el porcentaje extra configurado dinámicamente
        val porcentajeMultiplo = porcentajeSeleccionado / 100.0 // Convertir porcentaje a decimal
        
        return when {
            // Empanadas: redondear a múltiplo de 50
            codigo in listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH") -> {
                val multiplo = 50
                val umbral = multiplo * porcentajeMultiplo // Porcentaje configurado del múltiplo
                if (sugerencia < umbral) 0 else ((sugerencia / multiplo).toInt() + 1) * multiplo
            }
            // Pizzas: redondear a múltiplo de 5
            codigo in listOf("MUZZARE", "JAMON", "PEPPERO") -> {
                val multiplo = 5
                val umbral = multiplo * porcentajeMultiplo // Porcentaje configurado del múltiplo
                if (sugerencia < umbral) 0 else {
                    val cociente = sugerencia / multiplo
                    val resto = sugerencia % multiplo
                    if (resto > 0) (cociente.toInt() + 1) * multiplo else sugerencia.toInt()
                }
            }
            // Pastelitos: redondear a múltiplo de 35
            codigo in listOf("BATATA", "MEMBRIL") -> {
                val multiplo = 35
                val umbral = multiplo * porcentajeMultiplo // Porcentaje configurado del múltiplo
                if (sugerencia < umbral) 0 else ((sugerencia / multiplo).toInt() + 1) * multiplo
            }
            // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity - ya no se redondean aquí
            // Por defecto: redondear a múltiplo de 10
            else -> {
                val multiplo = 10
                val umbral = multiplo * porcentajeMultiplo // Porcentaje configurado del múltiplo
                if (sugerencia < umbral) 0 else ((sugerencia / multiplo).toInt() + 1) * multiplo
            }
        }
    }
    
    /**
     * Calcula la sugerencia de pedido basada en la lógica real de negocio
     * Basado en la fórmula de Excel proporcionada
     */
    /**
     * Obtiene el próximo día hábil (día con entrega) a partir de una fecha
     * Si hoy es día hábil, devuelve hoy. Si no, busca el próximo día hábil.
     */
    private fun obtenerProximoDiaHabil(fechaActual: Calendar): Calendar {
        // Primero verificar si HOY es día hábil
        if (esDiaConEntrega(fechaActual)) {
            Log.d("PedidoFabricaActivity", "✅ Hoy es día hábil, usando fecha actual")
            return fechaActual
        }
        
        // Si hoy no es día hábil, buscar el próximo día hábil
        val proximoDia = fechaActual.clone() as Calendar
        proximoDia.add(Calendar.DAY_OF_MONTH, 1)
        
        while (!esDiaConEntrega(proximoDia)) {
            proximoDia.add(Calendar.DAY_OF_MONTH, 1)
        }
        
        Log.d("PedidoFabricaActivity", "📅 Hoy no es día hábil, próximo día hábil: ${proximoDia.get(Calendar.DAY_OF_WEEK)}")
        return proximoDia
    }
    
    /**
     * Verifica si un día específico tiene entrega según la configuración
     */
    private fun esDiaConEntrega(fecha: Calendar): Boolean {
        val diaSemana = fecha.get(Calendar.DAY_OF_WEEK)
        return esDiaConEntrega(diaSemana)
    }
    
    private fun esDiaConEntrega(diaSemana: Int): Boolean {
        return when (diaSemana) {
            Calendar.MONDAY -> diasEntrega.lunes
            Calendar.TUESDAY -> diasEntrega.martes
            Calendar.WEDNESDAY -> diasEntrega.miercoles
            Calendar.THURSDAY -> diasEntrega.jueves
            Calendar.FRIDAY -> diasEntrega.viernes
            Calendar.SATURDAY -> diasEntrega.sabado
            Calendar.SUNDAY -> diasEntrega.domingo
            else -> true
        }
    }
    
    /**
     * Recalcula todas las sugerencias cuando cambia la configuración de días de entrega
     */
    private fun recalcularTodasLasSugerencias() {
        Log.d("PedidoFabricaActivity", "🔄 Recalculando todas las sugerencias...")
        
        // Obtener todos los productos y recalcular sus sugerencias
        productos.forEach { producto ->
            val nuevaSugerencia = calcularSugerenciaPedido(producto.codigo, producto.stockActual)
            Log.d("PedidoFabricaActivity", "🔄 Producto ${producto.codigo}: Nueva sugerencia = $nuevaSugerencia")
            
            // Actualizar el producto con la nueva sugerencia
            repository.actualizarPedidoFinal(producto.codigo, nuevaSugerencia)
            
            // CORRECCIÓN: Actualizar directamente el TextView de sugerencias en la interfaz
            txtSugerencias[producto.codigo]?.text = "💡 Pedido sugerido: $nuevaSugerencia unidades"
            Log.d("PedidoFabricaActivity", "🔄 Actualizado TextView para ${producto.codigo}: $nuevaSugerencia unidades")
        }
        
        Log.d("PedidoFabricaActivity", "✅ Todas las sugerencias recalculadas y actualizadas en la interfaz")
    }
    
    /**
     * Calcula la suma de promedios de venta de los días sin entrega
     * Verifica los próximos 2-3 días para ver si hay días desmarcados
     */
    private fun calcularPromedioDiasSinEntrega(codigo: String): Double {
        val fechaActual = DateHelper.getCalendar()
        val diaSemana = fechaActual.get(Calendar.DAY_OF_WEEK)
        var sumaPromedios = 0.0
        
        
        // LÓGICA CORRECTA: Buscar días SIN entrega después del día de entrega normal
        val diaSiguiente = when (diaSemana) {
            Calendar.MONDAY -> Calendar.TUESDAY
            Calendar.TUESDAY -> Calendar.WEDNESDAY
            Calendar.WEDNESDAY -> Calendar.THURSDAY
            Calendar.THURSDAY -> Calendar.FRIDAY
            Calendar.FRIDAY -> Calendar.SATURDAY
            Calendar.SATURDAY -> Calendar.SUNDAY
            Calendar.SUNDAY -> Calendar.MONDAY
            else -> return 0.0
        }
        
        val nombreDiaSiguiente = when (diaSiguiente) {
            Calendar.MONDAY -> "LUNES"
            Calendar.TUESDAY -> "MARTES"
            Calendar.WEDNESDAY -> "MIERCOLES"
            Calendar.THURSDAY -> "JUEVES"
            Calendar.FRIDAY -> "VIERNES"
            Calendar.SATURDAY -> "SABADO"
            Calendar.SUNDAY -> "DOMINGO"
            else -> "DESCONOCIDO"
        }
        
        
        // SIEMPRE buscar días adicionales sin entrega (después del día de entrega normal)
        val diasParaVerificar = when (diaSiguiente) {
            Calendar.MONDAY -> listOf(Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
            Calendar.TUESDAY -> listOf(Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
            Calendar.WEDNESDAY -> listOf(Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
            Calendar.THURSDAY -> listOf(Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
            Calendar.FRIDAY -> listOf(Calendar.SATURDAY, Calendar.SUNDAY)
            Calendar.SATURDAY -> listOf(Calendar.SUNDAY)
            Calendar.SUNDAY -> listOf(Calendar.MONDAY)
            else -> emptyList()
        }
        
        
        // Sumar todos los días consecutivos sin entrega
        for (dia in diasParaVerificar) {
            val tieneEntrega = when (dia) {
                Calendar.MONDAY -> diasEntrega.lunes
                Calendar.TUESDAY -> diasEntrega.martes
                Calendar.WEDNESDAY -> diasEntrega.miercoles
                Calendar.THURSDAY -> diasEntrega.jueves
                Calendar.FRIDAY -> diasEntrega.viernes
                Calendar.SATURDAY -> diasEntrega.sabado
                Calendar.SUNDAY -> diasEntrega.domingo
                else -> true
            }
            
            if (!tieneEntrega) {
                val promedio = obtenerPromedioPorDiaSemana(codigo, dia)
                sumaPromedios += promedio
            } else {
                // NO parar aquí, continuar buscando días sin entrega
            }
        }
        
        return sumaPromedios
    }
    
    /**
     * Obtiene el promedio de venta para un día específico de la semana
     * Usa los datos reales de promedios de la página principal
     */
    private fun obtenerPromedioPorDiaSemana(codigo: String, diaSemana: Int): Double {
        val diaSemanaString = when (diaSemana) {
            Calendar.MONDAY -> "LUNES"
            Calendar.TUESDAY -> "MARTES"
            Calendar.WEDNESDAY -> "MIERCOLES"
            Calendar.THURSDAY -> "JUEVES"
            Calendar.FRIDAY -> "VIERNES"
            Calendar.SATURDAY -> "SABADO"
            Calendar.SUNDAY -> "DOMINGO"
            else -> return 0.0
        }
        
        Log.d("SUG", "🔍 SUG: Obteniendo promedio para $codigo en $diaSemanaString")
        
        // Obtener el nombre del producto desde el código
        val nombreProducto = obtenerNombreProducto(codigo)
        Log.d("SUG", "🔍 SUG: Nombre producto: $nombreProducto")
        
        // 🚀 OPTIMIZACIÓN: Intentar primero desde MainActivity, luego desde cache local
        var promediosReales = MainActivity.promediosReales
        
        // Si MainActivity no tiene promedios, intentar cargar desde cache de PedidoFabricaActivity
        if (promediosReales.isEmpty() && cachePromedios != null) {
            Log.d("SUG", "📦 SUG: MainActivity.promediosReales vacío, usando cache de PedidoFabricaActivity")
            promediosReales = cachePromedios!!
            MainActivity.promediosReales = promediosReales
            MainActivity.promediosCargados = true
        }
        
        Log.d("SUG", "🔍 SUG: Promedios disponibles: ${promediosReales.isNotEmpty()}")
        
        if (promediosReales.isNotEmpty()) {
            val datosDia = promediosReales[diaSemanaString]
            Log.d("SUG", "🔍 SUG: Datos para $diaSemanaString: ${datosDia != null}")
            
            if (datosDia != null) {
                Log.d("SUG", "🔍 SUG: Productos disponibles en $diaSemanaString: ${datosDia.keys}")
                
                // Buscar la clave completa "CÓDIGO - Nombre"
                val claveCompleta = "$codigo - $nombreProducto"
                var promedio = datosDia[claveCompleta] ?: 0.0
                Log.d("SUG", "🔍 SUG: Buscando clave completa '$claveCompleta': $promedio")
                
                // Si no se encuentra, intentar con nombre del producto
                if (promedio == 0.0) {
                    promedio = datosDia[nombreProducto] ?: 0.0
                    Log.d("SUG", "🔍 SUG: Buscando por nombre '$nombreProducto': $promedio")
                }
                
                // Si no se encuentra, intentar con el código directamente
                if (promedio == 0.0) {
                    promedio = datosDia[codigo] ?: 0.0
                    Log.d("SUG", "🔍 SUG: Buscando por código '$codigo': $promedio")
                }
                
                Log.d("SUG", "✅ SUG: Promedio encontrado: $promedio")
                return promedio
            } else {
                Log.w("SUG", "⚠️ SUG: No hay datos para $diaSemanaString")
            }
        } else {
            Log.w("SUG", "⚠️ SUG: No hay promedios reales cargados")
        }
        
        Log.w("SUG", "⚠️ SUG: No se encontró promedio para $codigo en $diaSemanaString")
        return 0.0
    }
    
    
    private fun calcularSugerenciaPedido(codigo: String, stockActual: Int): Int {
        try {
            val promedioHoy = obtenerPromedioDiarioHoy(codigo)
            
            if (promedioHoy <= 0) {
                return 0
            }

            // Obtener el día actual y el de mañana
            val diaActual = obtenerDiaActual()
            val diaSiguiente = obtenerDiaSiguiente(diaActual)
            val diaSiguienteCalendar = obtenerDiaCalendar(diaSiguiente)
            val promedioVentaMañana = obtenerPromedioPorDiaSemana(codigo, diaSiguienteCalendar)

            // Porcentaje extra configurado
            val porcentajeExtra = obtenerPorcentajeExtraConfigurado()

            // ⏰ PASO 1: Calcular el sobrante estimado al cierre de hoy considerando mermas por vencimiento
            val vencInfo = vencimientosBatchMap[codigo]
            val cantidadVenceHoy = vencInfo?.cantidadVenceHoy ?: 0
            val todoVenceHoy = vencInfo?.todoVenceHoy ?: false

            // Si todo el stock se vence hoy (o mermará), el sobrante aprovechable al cierre para mañana es 0
            val stockUsableMax = if (todoVenceHoy) 0.0 else maxOf(0.0, (stockActual - cantidadVenceHoy).toDouble())
            val sobranteHoyCalculado = maxOf(0.0, stockActual.toDouble() - promedioHoy)
            val sobranteHoy = minOf(sobranteHoyCalculado, stockUsableMax)

            if (todoVenceHoy) {
                Log.d("SUG_MERMA", "⏰ SUG [$codigo]: Todo el stock ($stockActual u) o lote vence hoy ($cantidadVenceHoy u) -> Sobrante al cierre forzado a 0.0 para sugerir reemplazo total")
            } else if (cantidadVenceHoy > 0) {
                Log.d("SUG_MERMA", "⏰ SUG [$codigo]: Stock vence parcialmente ($cantidadVenceHoy u) -> Sobrante ajustado de $sobranteHoyCalculado a $sobranteHoy")
            }

            // Detectar tendencia de ventas (aumento/disminución)
            val tendenciaVentas = detectarTendenciaVentas(codigo)
            val factorTendencia = if (tendenciaVentas != 0.0) 1.0 + (tendenciaVentas / 100.0) else 1.0

            // Aplicar tendencia de ventas a los promedios futuros
            val promedioVentaMañanaAdjusted = promedioVentaMañana * factorTendencia

            // PASO 2: Calcular cuánto falta para cubrir mañana
            val faltanteMañana = maxOf(0.0, promedioVentaMañanaAdjusted - sobranteHoy)

            // PASO 3: Calcular el porcentaje extra (colchón) sobre el promedio de mañana (no el faltante)
            val porcentajeExtraDecimal = porcentajeExtra / 100.0
            val colchon = promedioVentaMañanaAdjusted * porcentajeExtraDecimal

            // PASO 4: Mantener la lógica actual de días extra
            val diasExtra = obtenerDiasExtraSegunTexto()
            var necesidadDiasExtra = 0.0
            diasExtra.forEach { nombreDia ->
                val diaCalendar = obtenerDiaCalendar(nombreDia)
                necesidadDiasExtra += obtenerPromedioPorDiaSemana(codigo, diaCalendar)
            }
            val necesidadDiasExtraAdjusted = necesidadDiasExtra * factorTendencia

            // PASO 5: Fórmula final
            val pedido = faltanteMañana + colchon + necesidadDiasExtraAdjusted

            // PASO 6: Redondeo
            val resultado = redondearSugerencia(pedido, codigo)
            
            Log.d("SUG", "🚀 SUG: stockActual=$stockActual, promedioHoy=$promedioHoy, sobranteHoy=$sobranteHoy, promedioVentaMañanaAdjusted=$promedioVentaMañanaAdjusted, faltanteMañana=$faltanteMañana, colchon=$colchon, necesidadDiasExtra=$necesidadDiasExtraAdjusted, pedido=$pedido, redondeado=$resultado")

            return resultado

        } catch (e: Exception) {
            Log.e("SUG", "❌ SUG: Error calculando sugerencia para $codigo: ${e.message}")
            e.printStackTrace()
            return 0
        }
    }
    
    // Cache de registros para evitar múltiples consultas
    private var cacheRegistros: List<RegistroInventario>? = null
    private var cacheFechaRegistros: String? = null
    
    /**
     * Invalida el cache de registros (útil cuando se actualizan datos)
     */
    private fun invalidarCacheRegistros() {
        cacheRegistros = null
        cacheFechaRegistros = null
        Log.d("SUG", "🗑️ SUG: Cache de registros invalidado")
    }

    /**
     * Calcula el stock inicial más preciso para pre-cargar el campo Stock Actual.
     *
     * Prioridad:
     * 1. Stock Final ayer + Ingreso de Mercadería de hoy  → si el registro de ingreso existe
     * 2. Stock Final ayer + Pedido Final de ayer (Pedido a Fábrica) → si no hay ingreso hoy
     * 3. Stock Final ayer solo → si tampoco hay pedido de ayer
     * 4. 0 → si no hay ningún dato disponible
     */
        /**
     * Pre-calcula el stock inicial de TODOS los productos en un solo lote asíncrono
     * Fórmula: Stock Final Ayer + (Ingreso de Hoy si existe, sino Pedido Fábrica de Ayer)
     */
    private suspend fun precalcularStockInicialLote(codigosYNombres: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        try {
            stockInicialBatchMap.clear()
            val localId = obtenerLocalId()
            val fechaHoy = fechaSeleccionada
            val fechaAyer = obtenerFechaAnterior(fechaHoy)

            val firebaseRepo = FirebaseInventarioRepository(localId)
            val fAyer = try { firebaseRepo.getRegistrosByFecha(fechaAyer).first() } catch (e: Exception) { emptyList() }
            val fHoy = try { firebaseRepo.getRegistrosByFecha(fechaHoy).first() } catch (e: Exception) { emptyList() }
            val lAyer = try { localRepository.getRegistrosByFecha(localId, fechaAyer).first() } catch (e: Exception) { emptyList() }
            val lHoy = try { localRepository.getRegistrosByFecha(localId, fechaHoy).first() } catch (e: Exception) { emptyList() }

            val registrosAyer = if (fAyer.isNotEmpty()) fAyer else lAyer
            val registrosHoy = if (fHoy.isNotEmpty()) fHoy else lHoy

            val registroStockAyer = registrosAyer.find { it.tipo == "Stock Final" }
            val registrosIngresoHoy = registrosHoy.filter { it.tipo == "Ingreso de Mercadería" }
            val hayIngresoHoy = registrosIngresoHoy.isNotEmpty()

            var productosPedidoAyer: List<Map<String, Any>>? = null
            if (!hayIngresoHoy) {
                try {
                    val doc = firestore.collection("locales").document(localId)
                        .collection("pedidos_fabrica").document(fechaAyer).get().await()
                    if (doc.exists()) {
                        @Suppress("UNCHECKED_CAST")
                        productosPedidoAyer = doc.get("productos") as? List<Map<String, Any>>
                    }
                } catch (e: Exception) {
                    Log.w("STOCK_BATCH", "No se pudo leer pedido de ayer: ${e.message}")
                }
            }

            // ⏰ Cargar lotes activos de vencimiento para detectar mermas totales o parciales de hoy
            val vencimientoRepo = com.tuapp.inventario.repository.VencimientoRepository()
            val lotesActivos = try {
                vencimientoRepo.obtenerLotesActivos(localId)
            } catch (e: Exception) {
                Log.w("STOCK_BATCH", "No se pudieron obtener lotes activos: ${e.message}")
                emptyList()
            }
            vencimientosBatchMap.clear()

            for ((codigo, nombreProducto) in codigosYNombres) {
                if (registroStockAyer == null) {
                    stockInicialBatchMap[codigo] = 0
                    continue
                }

                val prodStockAyer = registroStockAyer.productos.find { p ->
                    val c = obtenerCodigoProducto(p.nombre)
                    c == codigo || p.nombre == nombreProducto || p.nombre.startsWith("$codigo - ") || p.nombre == codigo
                }
                val stockFinalAyer = prodStockAyer?.cantidad ?: 0

                val adicional = if (hayIngresoHoy) {
                    registrosIngresoHoy.flatMap { it.productos }.find { p ->
                        val c = obtenerCodigoProducto(p.nombre)
                        c == codigo || p.nombre == nombreProducto || p.nombre.startsWith("$codigo - ") || p.nombre == codigo
                    }?.cantidad ?: 0
                } else if (productosPedidoAyer != null) {
                    val pMap = productosPedidoAyer.find { map ->
                        val n = map["nombre"] as? String ?: ""
                        val cleanN = if (n.contains(" - ")) n.split(" - ").lastOrNull() ?: n else n
                        val c = map["codigo"] as? String ?: obtenerCodigoProducto(cleanN)
                        c == codigo || cleanN == nombreProducto
                    }
                    (pMap?.get("pedido") as? Number)?.toInt() ?: 0
                } else {
                    0
                }

                val stockCalculado = stockFinalAyer + adicional
                stockInicialBatchMap[codigo] = stockCalculado

                // ⏰ Evaluar vencimiento/merma del producto para la fecha seleccionada
                val lotesProd = lotesActivos.filter { 
                    it.productoCodigo == codigo || 
                    it.productoNombre == nombreProducto || 
                    it.productoNombre.startsWith("$codigo - ") ||
                    obtenerCodigoProducto(it.productoNombre) == codigo
                }
                val lotesVencenHoy = lotesProd.filter { it.fechaVencimiento <= fechaHoy && it.cantidad > 0 }
                val cantidadVenceHoy = lotesVencenHoy.sumOf { it.cantidad }
                val totalLotesCantidad = lotesProd.filter { it.cantidad > 0 }.sumOf { it.cantidad }
                
                val todoVenceHoy = (cantidadVenceHoy > 0) && 
                        (cantidadVenceHoy >= totalLotesCantidad || (stockCalculado > 0 && cantidadVenceHoy >= stockCalculado))

                vencimientosBatchMap[codigo] = VencimientoMermaInfo(
                    cantidadVenceHoy = cantidadVenceHoy,
                    todoVenceHoy = todoVenceHoy,
                    lotesVencenHoy = lotesVencenHoy
                )
            }
            Log.d("STOCK_BATCH", "✅ Pre-calculado stock inicial para ${stockInicialBatchMap.size} productos en lote")
        } catch (e: Exception) {
            Log.e("STOCK_BATCH", "Error en precalculo de lote: ${e.message}", e)
        }
    }

    private fun calcularStockInicialProducto(codigo: String, nombreProducto: String): Int {
        if (stockInicialBatchMap.containsKey(codigo)) {
            return stockInicialBatchMap[codigo] ?: 0
        }
        return try {
            val localId = obtenerLocalId()

            val fechaHoy = fechaSeleccionada
            val fechaAyer = obtenerFechaAnterior(fechaHoy)

            // Obtener registros de ayer y hoy desde Firebase (con fallback a Room local)
            val firebaseRepo = FirebaseInventarioRepository(localId)
            val (registrosAyer, registrosHoy) = runBlocking(Dispatchers.IO) {
                try {
                    val fAyer = firebaseRepo.getRegistrosByFecha(fechaAyer).first()
                    val fHoy = firebaseRepo.getRegistrosByFecha(fechaHoy).first()
                    val lAyer = localRepository.getRegistrosByFecha(localId, fechaAyer).first()
                    val lHoy = localRepository.getRegistrosByFecha(localId, fechaHoy).first()
                    Pair(if (fAyer.isNotEmpty()) fAyer else lAyer, if (fHoy.isNotEmpty()) fHoy else lHoy)
                } catch (e: Exception) {
                    val lAyer = localRepository.getRegistrosByFecha(localId, fechaAyer).first()
                    val lHoy = localRepository.getRegistrosByFecha(localId, fechaHoy).first()
                    Pair(lAyer, lHoy)
                }
            }

            // ── PASO 1: Stock Final de ayer ──────────────────────────────────────────
            val registroStockAyer = registrosAyer.find { it.tipo == "Stock Final" }
            if (registroStockAyer == null) {
                Log.d("STOCK_INI", "⚠️ STOCK_INI [$codigo]: Stock Final de ayer ($fechaAyer) NO está cargado → 0")
                return 0
            }

            val productoStockAyer = registroStockAyer.productos.find { p ->
                val codigoP = obtenerCodigoProducto(p.nombre)
                codigoP == codigo || p.nombre == nombreProducto || p.nombre.startsWith("$codigo - ") || p.nombre == codigo
            }
            val stockFinalAyer = productoStockAyer?.cantidad ?: 0

            Log.d("STOCK_INI", "📦 STOCK_INI [$codigo]: stockFinalAyer=$stockFinalAyer (fecha: $fechaAyer)")

            // ── PASO 2: Verificar si existe registro de Ingreso de hoy ───────────────
            val registrosIngresoHoy = registrosHoy.filter { it.tipo == "Ingreso de Mercadería" }
            val hayIngresoHoy = registrosIngresoHoy.isNotEmpty()

            if (hayIngresoHoy) {
                // Hay registro de ingreso → sumarlo aunque sea 0 para ese producto
                val ingresoHoy = registrosIngresoHoy
                    .flatMap { it.productos }
                    .find { p ->
                        val codigoP = obtenerCodigoProducto(p.nombre)
                        codigoP == codigo || p.nombre == nombreProducto || p.nombre.startsWith("$codigo - ") || p.nombre == codigo
                    }
                    ?.cantidad ?: 0

                val resultado = stockFinalAyer + ingresoHoy
                Log.d("STOCK_INI", "✅ STOCK_INI [$codigo]: Stock Final Ayer ($stockFinalAyer) + Ingreso Hoy ($ingresoHoy) = $resultado")
                return resultado
            }

            // ── PASO 3: Sin ingreso hoy → buscar Pedido Final de ayer en Firestore ──
            Log.d("STOCK_INI", "🔍 STOCK_INI [$codigo]: Sin ingreso hoy, buscando pedido de ayer en Firebase...")
            val pedidoAyer = runBlocking(Dispatchers.IO) {
                try {
                    val doc = firestore
                        .collection("locales")
                        .document(localId)
                        .collection("pedidos_fabrica")
                        .document(fechaAyer)
                        .get()
                        .await()

                    if (doc.exists()) {
                        @Suppress("UNCHECKED_CAST")
                        val productosData = doc.get("productos") as? List<Map<String, Any>> ?: emptyList()
                        val productoMap = productosData.find { map ->
                            val nombre = map["nombre"] as? String ?: ""
                            val cleanNombre = if (nombre.contains(" - ")) nombre.split(" - ").lastOrNull() ?: nombre else nombre
                            val codigoMap = map["codigo"] as? String ?: obtenerCodigoProducto(cleanNombre)
                            codigoMap == codigo || cleanNombre == nombreProducto
                        }
                        (productoMap?.get("pedido") as? Number)?.toInt() ?: 0
                    } else {
                        Log.d("STOCK_INI", "🔍 STOCK_INI [$codigo]: No hay pedido_fabrica para $fechaAyer")
                        0
                    }
                } catch (e: Exception) {
                    Log.e("STOCK_INI", "❌ STOCK_INI [$codigo]: Error leyendo pedido de ayer: ${e.message}")
                    0
                }
            }

            val resultado = stockFinalAyer + pedidoAyer
            Log.d("STOCK_INI", "✅ STOCK_INI [$codigo]: Sin ingreso → stockFinalAyer=$stockFinalAyer + pedidoAyer=$pedidoAyer = $resultado")
            return resultado

        } catch (e: Exception) {
            Log.e("STOCK_INI", "❌ STOCK_INI: Error calculando stock inicial para $codigo: ${e.message}")
            0
        }
    }

    /**
     * Obtiene el stock actual del sistema directamente desde Firebase
     */
    private fun obtenerStockActualDelSistema(codigo: String, localId: String): Int {
        val nombreProducto = obtenerNombreProducto(codigo)
        return calcularStockInicialProducto(codigo, nombreProducto)
    }

    /**
     * Obtiene los datos necesarios para calcular la venta parcial del día
     * Retorna un par (stockAnterior, ingresoHoy)
     */
    private fun obtenerDatosVentaParcial(codigo: String): Pair<Int, Int> {
        return try {
            val localId = obtenerLocalId()
            val nombreProducto = obtenerNombreProducto(codigo)
            
            // Obtener la fecha actual (usar fechaSeleccionada si estamos en modo admin o fecha histórica)
            val fechaHoy = if (modoAdmin || modoSoloLectura) {
                fechaSeleccionada
            } else {
                val timeZoneHoy = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
                val sdfHoy = DateHelper.getDateFormat("yyyy-MM-dd")
                sdfHoy.timeZone = timeZoneHoy
                sdfHoy.format(Date())
            }
            
            val fechaAnterior = obtenerFechaAnterior(fechaHoy)
            
            Log.d("SUG", "🔍 SUG: Obteniendo datos venta parcial para $codigo ($nombreProducto)")
            Log.d("SUG", "🔍 SUG: Fecha hoy: $fechaHoy, Fecha anterior: $fechaAnterior")
            
            // Intentar usar cache primero
            var registros = cacheRegistros
            if (registros == null || cacheFechaRegistros != fechaHoy) {
                // Obtener registros de forma síncrona (usando runBlocking con Dispatcher.IO para no bloquear UI)
                registros = runBlocking(Dispatchers.IO) {
                    localRepository.getAllRegistros(localId).first()
                }
                cacheRegistros = registros
                cacheFechaRegistros = fechaHoy
            }
            
            // 🔍 DEBUG: Mostrar información de todos los registros disponibles
            Log.d("SUG", "🔍 SUG: Total registros en BD: ${registros.size}")
            Log.d("SUG", "🔍 SUG: Fechas disponibles: ${registros.map { it.fecha }.distinct()}")
            Log.d("SUG", "🔍 SUG: Tipos disponibles: ${registros.map { it.tipo }.distinct()}")
            
            // Obtener stock anterior (stock final del día anterior)
            val registrosStockAnterior = registros.filter { it.fecha == fechaAnterior && it.tipo == "Stock Final" }
            Log.d("SUG", "🔍 SUG: Registros Stock Final para $fechaAnterior: ${registrosStockAnterior.size}")
            registrosStockAnterior.forEach { reg ->
                Log.d("SUG", "🔍 SUG:   Stock Final reg: fecha=${reg.fecha}, tipo=${reg.tipo}, productos: ${reg.productos.map { p -> "${p.nombre}=${p.cantidad}" }}")
            }
            
            val stockAnteriorRegistros = registrosStockAnterior.flatMap { it.productos }
            
            val stockAnterior = stockAnteriorRegistros
                .find { producto -> 
                    producto.nombre == nombreProducto || 
                    producto.nombre.startsWith("$codigo - ") ||
                    producto.nombre == codigo
                }?.cantidad ?: 0
            
            // Obtener ingreso del día actual
            val registrosIngresoHoy = registros.filter { it.fecha == fechaHoy && it.tipo == "Ingreso de Mercadería" }
            Log.d("SUG", "🔍 SUG: Registros Ingreso para $fechaHoy: ${registrosIngresoHoy.size}")
            registrosIngresoHoy.forEach { reg ->
                Log.d("SUG", "🔍 SUG:   Ingreso reg: fecha=${reg.fecha}, tipo=${reg.tipo}, productos: ${reg.productos.map { p -> "${p.nombre}=${p.cantidad}" }}")
            }
            
            val ingresoHoyRegistros = registrosIngresoHoy.flatMap { it.productos }
            
            val ingresoHoy = ingresoHoyRegistros
                .find { producto -> 
                    producto.nombre == nombreProducto || 
                    producto.nombre.startsWith("$codigo - ") ||
                    producto.nombre == codigo
                }?.cantidad ?: 0
            
            Log.d("SUG", "🔍 SUG: Stock anterior encontrado: $stockAnterior (buscando: $nombreProducto, $codigo)")
            Log.d("SUG", "🔍 SUG: Ingreso hoy encontrado: $ingresoHoy (buscando: $nombreProducto, $codigo)")
            
            // 🔥 Si no hay datos locales, intentar leer desde Firebase directamente
            var stockAnteriorFinal = stockAnterior
            var ingresoHoyFinal = ingresoHoy
            
            if (stockAnterior == 0 && ingresoHoy == 0) {
                Log.d("SUG", "🔍 SUG: Sin datos locales, intentando Firebase...")
                val datosFirebase = obtenerDatosVentaParcialDesdeFirebase(codigo, nombreProducto, fechaHoy, fechaAnterior)
                stockAnteriorFinal = datosFirebase.first
                ingresoHoyFinal = datosFirebase.second
            }
            
            Pair(stockAnteriorFinal, ingresoHoyFinal)
            
        } catch (e: Exception) {
            Log.e("SUG", "❌ SUG: Error obteniendo datos venta parcial para $codigo: ${e.message}")
            e.printStackTrace()
            // Si hay error, retornar valores por defecto (0, 0) - esto hará que ventaParcial sea negativa o 0
            // y la lógica se adaptará automáticamente
            Pair(0, 0)
        }
    }
    
    /**
     * 🔥 Obtiene datos de venta parcial directamente desde Firebase
     * Cuando la BD local no tiene los registros sincronizados
     */
    private fun obtenerDatosVentaParcialDesdeFirebase(
        codigo: String,
        nombreProducto: String,
        fechaHoy: String,
        fechaAnterior: String
    ): Pair<Int, Int> {
        return try {
            Log.d("SUG", "🔍 SUG: Leyendo desde Firebase...")
            
            val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
            val localId = obtenerLocalId()
            
            var stockAnterior = 0
            var ingresoHoy = 0
            
            runBlocking {
                // Obtener Stock Final del día anterior desde Firebase
                val docStockAnterior = firestore
                    .collection("locales")
                    .document(localId)
                    .collection("registros")
                    .document("${fechaAnterior}_Stock_Final")
                    .get()
                    .await()
                
                if (docStockAnterior.exists()) {
                    @Suppress("DEPRECATION")
                    val registro = docStockAnterior.toObject(RegistroInventario::class.java)
                    val producto = registro?.productos?.find { p ->
                        p.nombre == nombreProducto ||
                        p.nombre.startsWith("$codigo - ") ||
                        p.nombre == codigo
                    }
                    stockAnterior = producto?.cantidad ?: 0
                    Log.d("SUG", "🔍 SUG: Desde Firebase - Stock anterior: $stockAnterior")
                } else {
                    Log.d("SUG", "🔍 SUG: No existe doc ${fechaAnterior}_Stock_Final en Firebase")
                }
                
                // Obtener Ingreso de Mercadería del día actual desde Firebase
                val docIngreso = firestore
                    .collection("locales")
                    .document(localId)
                    .collection("registros")
                    .document("${fechaHoy}_Ingreso_de_Mercadería")
                    .get()
                    .await()
                
                if (docIngreso.exists()) {
                    @Suppress("DEPRECATION")
                    val registro = docIngreso.toObject(RegistroInventario::class.java)
                    val producto = registro?.productos?.find { p ->
                        p.nombre == nombreProducto ||
                        p.nombre.startsWith("$codigo - ") ||
                        p.nombre == codigo
                    }
                    ingresoHoy = producto?.cantidad ?: 0
                    Log.d("SUG", "🔍 SUG: Desde Firebase - Ingreso hoy: $ingresoHoy")
                } else {
                    Log.d("SUG", "🔍 SUG: No existe doc ${fechaHoy}_Ingreso_de_Mercadería en Firebase")
                }
            }
            
            Log.d("SUG", "✅ SUG: Datos desde Firebase - stockAnterior: $stockAnterior, ingresoHoy: $ingresoHoy")
            Pair(stockAnterior, ingresoHoy)
            
        } catch (e: Exception) {
            Log.e("SUG", "❌ SUG: Error leyendo desde Firebase: ${e.message}")
            Pair(0, 0)
        }
    }
    
    /**
     * Obtiene la suma de promedios desde mañana hasta la próxima entrega
     * Siempre suma mañana, y si no hay entrega, sigue sumando hasta encontrar un día con entrega
     */
    private fun obtenerPromediosHastaProximaEntrega(codigo: String): Double {
        Log.d("DEBUG_JQ", "🔍 DEBUG_JQ: === OBTENIENDO PROMEDIOS DÍAS ===")

        val fechaActual = DateHelper.getCalendar()
        val diaActual = obtenerDiaActual()
        val diaSiguiente = obtenerDiaSiguiente(diaActual)
        
        val promedios = mutableListOf<Double>()
        val diasConsiderados = mutableListOf<String>()

        // 1. Siempre incluir el día siguiente
        val diaSiguienteCalendar = obtenerDiaCalendar(diaSiguiente)
        val promDiaSiguiente = obtenerPromedioPorDiaSemana(codigo, diaSiguienteCalendar)
        promedios.add(promDiaSiguiente)
        diasConsiderados.add(diaSiguiente)
        Log.d("SUG", "🔍 SUG: Día siguiente $diaSiguiente: $promDiaSiguiente")

        // 2. Incluir los días extra (días sin entrega que aparecen en el texto)
        val diasExtra = obtenerDiasExtraSegunTexto()
        Log.d("SUG", "🔍 SUG: Días extra encontrados: $diasExtra")
        
        diasExtra.forEach { nombreDia ->
            val diaCalendar = obtenerDiaCalendar(nombreDia)
            val prom = obtenerPromedioPorDiaSemana(codigo, diaCalendar)
            promedios.add(prom)
            diasConsiderados.add(nombreDia)
            Log.d("SUG", "🔍 SUG: Día extra $nombreDia: $prom")
        }

        val suma = promedios.sum()
        Log.d("SUG", "🔍 SUG: Días sumados: ${diasConsiderados.joinToString(", ")}")
        Log.d("SUG", "🔍 SUG: SUMA TOTAL: $suma")
        return suma
    }
    
    /**
     * Obtiene los días extra que aparecen en el texto de arriba (misma lógica que actualizarInformacionDiasExtra)
     * Lógica: El primer día (mañana) DEBE tener entrega. Luego suma días sin entrega hasta encontrar el siguiente día con entrega
     */
    private fun obtenerDiasExtraSegunTexto(): List<String> {
        val fechaActual = DateHelper.getCalendar()
        val diaSemana = fechaActual.get(Calendar.DAY_OF_WEEK)
        val diasExtra = mutableListOf<String>()
        
        // Obtener la lista de próximos días (mañana + siguientes)
        val proximosDias = when (diaSemana) {
            Calendar.MONDAY -> listOf("MARTES" to diasEntrega.martes, "MIERCOLES" to diasEntrega.miercoles, "JUEVES" to diasEntrega.jueves, "VIERNES" to diasEntrega.viernes)
            Calendar.TUESDAY -> listOf("MIERCOLES" to diasEntrega.miercoles, "JUEVES" to diasEntrega.jueves, "VIERNES" to diasEntrega.viernes, "SABADO" to diasEntrega.sabado)
            Calendar.WEDNESDAY -> listOf("JUEVES" to diasEntrega.jueves, "VIERNES" to diasEntrega.viernes, "SABADO" to diasEntrega.sabado, "DOMINGO" to diasEntrega.domingo)
            Calendar.THURSDAY -> listOf("VIERNES" to diasEntrega.viernes, "SABADO" to diasEntrega.sabado, "DOMINGO" to diasEntrega.domingo, "LUNES" to diasEntrega.lunes)
            Calendar.FRIDAY -> listOf("SABADO" to diasEntrega.sabado, "DOMINGO" to diasEntrega.domingo, "LUNES" to diasEntrega.lunes, "MARTES" to diasEntrega.martes)
            Calendar.SATURDAY -> listOf("DOMINGO" to diasEntrega.domingo, "LUNES" to diasEntrega.lunes, "MARTES" to diasEntrega.martes, "MIERCOLES" to diasEntrega.miercoles)
            Calendar.SUNDAY -> listOf("LUNES" to diasEntrega.lunes, "MARTES" to diasEntrega.martes, "MIERCOLES" to diasEntrega.miercoles, "JUEVES" to diasEntrega.jueves)
            else -> emptyList()
        }
        
        if (proximosDias.isEmpty()) {
            Log.e("SUG", "❌ SUG: No hay días configurados")
            return diasExtra
        }
        
        // 1. Verificar que el PRIMER día (mañana) tenga entrega
        val (primerDia, primerDiaTieneEntrega) = proximosDias.first()
        if (!primerDiaTieneEntrega) {
            Log.e("SUG", "❌ SUG: MAÑANA ($primerDia) NO tiene entrega - No se puede hacer pedido")
            // Aquí podrías mostrar un mensaje de error al usuario
            return diasExtra
        }
        
        Log.d("SUG", "✅ SUG: MAÑANA ($primerDia) tiene entrega - Se puede hacer pedido")
        
        // 2. Revisar los días DESPUÉS de mañana
        for (i in 1 until proximosDias.size) {
            val (nombreDia, tieneEntrega) = proximosDias[i]
            
            if (!tieneEntrega) {
                // Este día NO tiene entrega, agregarlo y continuar
                diasExtra.add(nombreDia)
                Log.d("SUG", "➕ SUG: $nombreDia sin entrega, agregarlo")
            } else {
                // Este día SÍ tiene entrega, PARAR aquí
                Log.d("SUG", "🛑 SUG: $nombreDia tiene entrega, parar")
                break
            }
        }
        
        Log.d("SUG", "📋 SUG: Días extra finales: $diasExtra")
        return diasExtra
    }
    
    /**
     * Convierte nombre de día a Calendar.DAY_OF_WEEK
     */
    private fun obtenerDiaCalendar(nombreDia: String): Int {
        return when (nombreDia.uppercase()) {
            "LUNES" -> Calendar.MONDAY
            "MARTES" -> Calendar.TUESDAY
            "MIERCOLES" -> Calendar.WEDNESDAY
            "JUEVES" -> Calendar.THURSDAY
            "VIERNES" -> Calendar.FRIDAY
            "SABADO" -> Calendar.SATURDAY
            "DOMINGO" -> Calendar.SUNDAY
            else -> Calendar.MONDAY
        }
    }
    
    /**
     * Helper para obtener el nombre del día para logs
     */
    private fun obtenerNombreDia(diaSemana: Int): String {
        return when (diaSemana) {
            Calendar.MONDAY -> "LUNES"
            Calendar.TUESDAY -> "MARTES"
            Calendar.WEDNESDAY -> "MIERCOLES"
            Calendar.THURSDAY -> "JUEVES"
            Calendar.FRIDAY -> "VIERNES"
            Calendar.SATURDAY -> "SABADO"
            Calendar.SUNDAY -> "DOMINGO"
            else -> "DESCONOCIDO"
        }
    }
    
    /**
     * Consulta los promedios reales cuando el usuario escribe
     */
    private fun consultarPromediosReales(codigo: String, stock: Int, callback: (Int) -> Unit) {
        lifecycleScope.launch {
            try {
                // Obtener el día actual y el día siguiente
                val diaActual = obtenerDiaActual()
                val diaSiguiente = obtenerDiaSiguiente(diaActual)
                
                // Obtener el nombre completo del producto
                val nombreProducto = obtenerNombreProducto(codigo)
                
                // Consultar promedios desde el repositorio local
                val localId = obtenerLocalId()
                val localRepository = (application as InventarioApplication).localRepository
                
                var promedioHoy: Double
                var promedioManana: Double
                
                localRepository.getAllRegistros(localId).collect { registros ->
                    if (isFinishing || isDestroyed) return@collect
                    
                    val promediosPorDia = calcularPromediosPorDia(registros)
                    
                    // Obtener promedio del día actual
                    promedioHoy = promediosPorDia[diaActual]?.get(nombreProducto) ?: 0.0
                    
                    // Obtener promedio del día siguiente
                    promedioManana = promediosPorDia[diaSiguiente]?.get(nombreProducto) ?: 0.0
                    
                    Log.d("PedidoFabricaActivity", "Promedios para $codigo ($nombreProducto): Hoy=$promedioHoy, Mañana=$promedioManana")
                    
                    // Calcular sugerencia con datos reales
                    val sugerencia = calcularSugerenciaConPromedios(stock, promedioHoy, promedioManana, codigo)
                    
                    // Actualizar UI en el hilo principal
                    runOnUiThread {
                        callback(sugerencia)
                    }
                }
                
            } catch (e: Exception) {
                Log.e("PedidoFabricaActivity", "Error consultando promedios reales: ${e.message}")
                // En caso de error, usar promedios simulados
                val sugerencia = calcularSugerenciaPedido(codigo, stock)
                runOnUiThread {
                    callback(sugerencia)
                }
            }
        }
    }
    
    /**
     * Calcula la sugerencia con promedios específicos
     */
    private fun calcularSugerenciaConPromedios(stock: Int, promedioHoy: Double, promedioManana: Double, codigo: String): Int {
        try {
            if (promedioHoy <= 0) {
                return 0
            }
            
            // Si no hay datos para mañana, usar solo los datos de hoy con un factor de seguridad
            val promedioMananaUsar = if (promedioManana > 0) {
                promedioManana
            } else {
                promedioHoy * 0.8 // Usar 80% del promedio de hoy como estimación conservadora
            }
            
            // Obtener el porcentaje extra configurado
            val porcentajeExtra = obtenerPorcentajeExtraConfigurado()
            
            // Fórmula correcta basada en tu cálculo:
            // Pedido = ((Promedio_HOY/2 - Stock_15:30) + Promedio_MAÑANA) + (Promedio_MAÑANA × %Extra)
            val mitadPromedioHoy = promedioHoy / 2.0
            val diferenciaStock = mitadPromedioHoy - stock
            val sugerenciaBase = diferenciaStock + promedioMananaUsar
            val porcentajeExtraDecimal = porcentajeExtra / 100.0
            val porcentajeExtraValor = promedioMananaUsar * porcentajeExtraDecimal
            val sugerenciaFinal = sugerenciaBase + porcentajeExtraValor
            
            // Asegurar que no sea negativo
            val sugerenciaPositiva = maxOf(0.0, sugerenciaFinal)
            
            // Redondear según el tipo de producto
            return redondearSugerencia(sugerenciaPositiva, codigo)
            
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error calculando sugerencia con promedios: ${e.message}")
            return 0
        }
    }
    
    /**
     * Obtiene el promedio del día actual para un producto específico
     * Consulta los datos reales de la sección "Promedio de Ventas"
     */
    private fun obtenerPromedioDiarioHoy(codigo: String): Double {
        try {
            val localId = obtenerLocalId()
            val diaActual = obtenerDiaActual()
            val nombreProducto = obtenerNombreProducto(codigo)
            
        Log.d("SUG", "🔍 SUG: Obteniendo promedio hoy para $codigo en $diaActual")
            
            // Verificar si hay datos cargados en el cache
            if (MainActivity.promediosReales.isEmpty()) {
                Log.w("DEBUG_JQ", "⚠️ DEBUG_JQ: Cache de promedios vacío - Intentando cargar desde Firebase...")
                // Intentar cargar desde Firebase de forma síncrona
                kotlinx.coroutines.runBlocking {
                    val promediosFirebase = cargarPromediosGeneralesDesdeFirebase(localId)
                    if (promediosFirebase.isNotEmpty()) {
                        MainActivity.promediosReales = promediosFirebase
                        MainActivity.promediosCargados = true
                        Log.d("DEBUG_JQ", "✅ Promedios cargados desde Firebase: ${promediosFirebase.size} días")
                    } else {
                        Log.w("DEBUG_JQ", "⚠️ No hay promedios en Firebase para local $localId")
                        return@runBlocking
                    }
                }
                
                // Si aún está vacío después de intentar cargar, retornar 0
                if (MainActivity.promediosReales.isEmpty()) {
                    return 0.0
                }
            }
            
            // Verificar si hay datos para el día específico
            val datosDia = MainActivity.promediosReales[diaActual]
            if (datosDia == null) {
                Log.w("SUG", "⚠️ SUG: No hay datos para el día $diaActual en local $localId")
                Log.d("SUG", "🔍 SUG: Días disponibles en cache: ${MainActivity.promediosReales.keys}")
                return 0.0
            }
            
            Log.d("SUG", "🔍 SUG: Buscando producto '$codigo' ($nombreProducto) en $diaActual")
            Log.d("SUG", "🔍 SUG: Productos disponibles en $diaActual: ${datosDia.keys.take(10)}")
            
            // Intentar obtener datos reales del cache global
            // 🔥 FIX: En Firebase los productos están guardados como "CÓDIGO - Nombre"
            // Buscar primero por formato completo "CÓDIGO - Nombre"
            val claveCompleta = "$codigo - $nombreProducto"
            var promedioHoy = datosDia[claveCompleta] ?: 0.0
            Log.d("SUG", "🔍 SUG: Buscando por clave completa '$claveCompleta': $promedioHoy")
            
            // Si no encuentra por clave completa, buscar por código solo
            if (promedioHoy <= 0) {
                promedioHoy = datosDia[codigo] ?: 0.0
                Log.d("SUG", "🔍 SUG: Buscando por código '$codigo': $promedioHoy")
            }
            
            // Si aún no encuentra, buscar por cualquier clave que contenga el código
            if (promedioHoy <= 0) {
                for ((key, value) in datosDia) {
                    if (key.startsWith("$codigo - ") || key == codigo || key.contains(codigo)) {
                        promedioHoy = value
                        Log.d("SUG", "🔍 SUG: Encontrado por clave parcial '$key' = $value")
                        break
                    }
                }
            }
            
            // Si aún no encuentra, buscar por nombre solo (por si acaso)
            if (promedioHoy <= 0) {
                promedioHoy = datosDia[nombreProducto] ?: 0.0
                Log.d("SUG", "🔍 SUG: Buscando por nombre '$nombreProducto': $promedioHoy")
            }
            
            if (promedioHoy > 0) {
            Log.d("SUG", "✅ SUG: Promedio encontrado: $promedioHoy")
            return promedioHoy
            }
            
            // Si no hay datos reales, devolver 0 (no usar datos simulados)
            Log.w("SUG", "❌ SUG: No hay datos reales para local '$localId', producto '$codigo' ($nombreProducto) en $diaActual - devolviendo 0")
            return 0.0
            
        } catch (e: Exception) {
            Log.e("SUG", "❌ SUG: Error obteniendo promedio hoy para $codigo: ${e.message}")
            return 0.0
        }
    }
    
    /**
     * 📈 Obtiene historial de ventas vs promedio para mostrar en gráfico
     * Retorna lista de (fecha, ventaReal, promedioHistorico)
     */
    /**
     * 🚀 OPTIMIZADO: Obtiene el historial de ventas cargando SOLO los datos necesarios
     * Solo carga los últimos 7 días de este producto específico
     */
    private suspend fun obtenerHistorialVentasTendenciaOptimizado(codigo: String): List<Triple<String, Double, Double>> {
        return withContext(Dispatchers.IO) {
            try {
                val nombreProducto = obtenerNombreProducto(codigo)
                val fechaHoy = DateHelper.getFechaActual()
                val calendar = DateHelper.getCalendar()
                val historial = mutableListOf<Triple<String, Double, Double>>()
                val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
                
                Log.d("GRAFICO_OPT", "📊 Cargando últimos 7 días para $codigo ($nombreProducto)")
                
                // Cargar los últimos 7 días
                for (i in 1..7) {
                    try {
                        calendar.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaHoy) ?: Date()
                        calendar.add(Calendar.DAY_OF_YEAR, -i)
                        val fecha = DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
                        val fechaAnterior = obtenerFechaAnteriorA(fecha)
                        
                        Log.d("GRAFICO_OPT", "  📅 Procesando día: $fecha")
                        
                        // Cargar solo los 3 documentos necesarios para este día
                        val stockAnteriorDoc = firestore
                            .collection("locales").document(localId)
                            .collection("registros").document("${fechaAnterior}_Stock_Final")
                            .get().await()
                        
                        val ingresoDoc = firestore
                            .collection("locales").document(localId)
                            .collection("registros").document("${fecha}_Ingreso_de_Mercadería")
                            .get().await()
                        
                        val stockFinalDoc = firestore
                            .collection("locales").document(localId)
                            .collection("registros").document("${fecha}_Stock_Final")
                            .get().await()
                        
                        // Buscar este producto en los documentos
                        val stockAnterior = if (stockAnteriorDoc.exists()) {
                            @Suppress("DEPRECATION")
                            stockAnteriorDoc.toObject(RegistroInventario::class.java)
                                ?.productos?.find { 
                                    it.nombre == nombreProducto || 
                                    it.nombre == "$codigo - $nombreProducto" ||
                                    it.nombre.startsWith("$codigo ")
                                }?.cantidad ?: 0
                        } else 0
                        
                        val ingreso = if (ingresoDoc.exists()) {
                            @Suppress("DEPRECATION")
                            ingresoDoc.toObject(RegistroInventario::class.java)
                                ?.productos?.find { 
                                    it.nombre == nombreProducto || 
                                    it.nombre == "$codigo - $nombreProducto" ||
                                    it.nombre.startsWith("$codigo ")
                                }?.cantidad ?: 0
                        } else 0
                        
                        val stockFinal = if (stockFinalDoc.exists()) {
                            @Suppress("DEPRECATION")
                            stockFinalDoc.toObject(RegistroInventario::class.java)
                                ?.productos?.find { 
                                    it.nombre == nombreProducto || 
                                    it.nombre == "$codigo - $nombreProducto" ||
                                    it.nombre.startsWith("$codigo ")
                                }?.cantidad ?: 0
                        } else 0
                        
                        // Calcular venta real
                        val ventaReal = (stockAnterior + ingreso - stockFinal).toDouble()
                        
                        if (ventaReal > 0 || (stockAnterior > 0 || ingreso > 0 || stockFinal > 0)) {
                            // Calcular promedio histórico solo para este día
                            val cal = DateHelper.getCalendar()
                            cal.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(fecha) ?: continue
                            val diaSemana = cal.get(Calendar.DAY_OF_WEEK)
                            val promedioHistorico = obtenerPromedioPorDiaSemanaOptimizado(codigo, diaSemana)
                            
                            historial.add(Triple(fecha, ventaReal, promedioHistorico))
                            Log.d("GRAFICO_OPT", "    ✅ $fecha: venta=$ventaReal, promedio=$promedioHistorico")
                        } else {
                            Log.d("GRAFICO_OPT", "    ⏭️ $fecha: sin datos de venta")
                        }
                        
                    } catch (e: Exception) {
                        Log.w("GRAFICO_OPT", "    ⚠️ Error en día: ${e.message}")
                    }
                }
                
                Log.d("GRAFICO_OPT", "✅ Historial completo: ${historial.size} días con datos")
                historial.reversed() // Del más antiguo al más reciente
                
            } catch (e: Exception) {
                Log.e("GRAFICO_OPT", "❌ Error obteniendo historial: ${e.message}")
                emptyList()
            }
        }
    }
    
    /**
     * 🚀 OPTIMIZADO: Obtiene el promedio cargando bajo demanda si es necesario
     */
    private suspend fun obtenerPromedioPorDiaSemanaOptimizado(codigo: String, diaSemana: Int): Double {
        return withContext(Dispatchers.IO) {
            try {
                val diaSemanaString = when (diaSemana) {
                    Calendar.MONDAY -> "LUNES"
                    Calendar.TUESDAY -> "MARTES"
                    Calendar.WEDNESDAY -> "MIERCOLES"
                    Calendar.THURSDAY -> "JUEVES"
                    Calendar.FRIDAY -> "VIERNES"
                    Calendar.SATURDAY -> "SABADO"
                    Calendar.SUNDAY -> "DOMINGO"
                    else -> return@withContext 0.0
                }
                
                val nombreProducto = obtenerNombreProducto(codigo)
                
                // Intentar desde cache primero
                var promediosReales = MainActivity.promediosReales
                
                // Si no hay cache, cargar bajo demanda
                if (promediosReales.isEmpty()) {
                    if (cachePromedios != null && cachePromediosLocalId == localId) {
                        promediosReales = cachePromedios!!
                    } else {
                        // Cargar desde Firebase solo si es necesario
                        Log.d("GRAFICO_OPT", "  📥 Cargando promedios bajo demanda...")
                        promediosReales = cargarPromediosGeneralesDesdeFirebase(localId)
                        cachePromedios = promediosReales
                        cachePromediosLocalId = localId
                        cachePromediosTimestamp = System.currentTimeMillis()
                    }
                    MainActivity.promediosReales = promediosReales
                    MainActivity.promediosCargados = true
                }
                
                // Buscar el promedio
                val datosDia = promediosReales[diaSemanaString]
                if (datosDia != null) {
                    val claveCompleta = "$codigo - $nombreProducto"
                    var promedio = datosDia[claveCompleta] ?: 0.0
                    
                    if (promedio == 0.0) {
                        promedio = datosDia[nombreProducto] ?: 0.0
                    }
                    
                    if (promedio == 0.0) {
                        promedio = datosDia[codigo] ?: 0.0
                    }
                    
                    return@withContext promedio
                }
                
                0.0
            } catch (e: Exception) {
                Log.e("GRAFICO_OPT", "❌ Error obteniendo promedio: ${e.message}")
                0.0
            }
        }
    }
    
    private suspend fun obtenerHistorialVentasTendencia(codigo: String): List<Triple<String, Double, Double>> {
        return withContext(Dispatchers.IO) {
            try {
                val nombreProducto = obtenerNombreProducto(codigo)
                val fechaHoy = DateHelper.getFechaActual()
                val calendar = DateHelper.getCalendar()
                val historial = mutableListOf<Triple<String, Double, Double>>()
                
                for (i in 1..7) {
                    calendar.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaHoy) ?: Date()
                    calendar.add(Calendar.DAY_OF_YEAR, -i)
                    val fecha = DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
                    val fechaAnterior = obtenerFechaAnteriorA(fecha)
                    
                    try {
                        // Obtener día de la semana
                        val cal = DateHelper.getCalendar()
                        cal.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(fecha) ?: continue
                        val diaSemana = cal.get(Calendar.DAY_OF_WEEK)
                        
                        // Obtener promedio histórico
                        val promedioHistorico = obtenerPromedioPorDiaSemana(codigo, diaSemana)
                        if (promedioHistorico <= 0) continue
                        
                        // Obtener registros
                        val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
                        val registros = kotlinx.coroutines.runBlocking {
                            try {
                                val registrosList = mutableListOf<RegistroInventario>()
                                
                                val docStockAnterior = firestore
                                    .collection("locales").document(localId)
                                    .collection("registros").document("${fechaAnterior}_Stock_Final")
                                    .get().await()
                                
                                if (docStockAnterior.exists()) {
                                    @Suppress("DEPRECATION")
                                    docStockAnterior.toObject(RegistroInventario::class.java)?.let { registrosList.add(it) }
                                }
                                
                                val docIngreso = firestore
                                    .collection("locales").document(localId)
                                    .collection("registros").document("${fecha}_Ingreso_de_Mercadería")
                                    .get().await()
                                
                                if (docIngreso.exists()) {
                                    @Suppress("DEPRECATION")
                                    docIngreso.toObject(RegistroInventario::class.java)?.let { registrosList.add(it) }
                                }
                                
                                val docStockFinal = firestore
                                    .collection("locales").document(localId)
                                    .collection("registros").document("${fecha}_Stock_Final")
                                    .get().await()
                                
                                if (docStockFinal.exists()) {
                                    @Suppress("DEPRECATION")
                                    docStockFinal.toObject(RegistroInventario::class.java)?.let { registrosList.add(it) }
                                }
                                
                                registrosList
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                        
                        val stockAnterior = registros
                            .filter { it.fecha == fechaAnterior && it.tipo == "Stock Final" }
                            .flatMap { it.productos }
                            .find { 
                                it.nombre == nombreProducto || 
                                it.nombre == "$codigo - $nombreProducto" ||
                                it.nombre.startsWith("$codigo ")
                            }
                            ?.cantidad ?: 0
                        
                        val ingreso = registros
                            .filter { it.fecha == fecha && it.tipo == "Ingreso de Mercadería" }
                            .flatMap { it.productos }
                            .find { 
                                it.nombre == nombreProducto || 
                                it.nombre == "$codigo - $nombreProducto" ||
                                it.nombre.startsWith("$codigo ")
                            }
                            ?.cantidad ?: 0
                        
                        val stockFinal = registros
                            .filter { it.fecha == fecha && it.tipo == "Stock Final" }
                            .flatMap { it.productos }
                            .find { 
                                it.nombre == nombreProducto || 
                                it.nombre == "$codigo - $nombreProducto" ||
                                it.nombre.startsWith("$codigo ")
                            }
                            ?.cantidad ?: 0
                        
                        val ventaReal = (stockAnterior + ingreso - stockFinal).toDouble()
                        
                        if (ventaReal > 0) {
                            historial.add(Triple(fecha, ventaReal, promedioHistorico))
                        }
                        
                    } catch (e: Exception) {
                        // Silenciar errores individuales
                    }
                }
                
                historial.reversed() // Del más antiguo al más reciente
                
            } catch (e: Exception) {
                Log.e("TENDENCIA", "Error obteniendo historial: ${e.message}")
                emptyList()
            }
        }
    }
    
    /**
     * 📥 Carga el cache completo de tendencias desde Firebase (una sola vez al inicio)
     * Usa documento único "tendencias" que se sobrescribe (sin fecha)
     */
    private fun cargarCacheTendenciasDesdeFirebase() {
        // Si ya está cargado para este local, no hacer nada
        if (MainActivity.cacheTendencias != null && 
            MainActivity.cacheTendenciasLocalId == localId) {
            Log.d("TENDENCIA", "✅ Cache ya disponible en memoria (${MainActivity.cacheTendencias!!.size} productos)")
            return
        }
        
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                Log.d("TENDENCIA", "📥 Cargando cache desde Firebase (documento único)...")
                val firestore = FirebaseRegionManager.getFirestore()
                val docTendencias = firestore
                    .collection("locales")
                    .document(localId)
                    .collection("cache_tendencias")
                    .document("tendencias")  // Documento único
                    .get()
                    .await()
                
                if (docTendencias.exists()) {
                    // Filtrar el campo fechaActualizacion y convertir solo los productos
                    val data = docTendencias.data
                    if (data != null && data.isNotEmpty()) {
                        val tendenciasGuardadas = data
                            .filterKeys { it != "fechaActualizacion" }  // Excluir el timestamp
                            .mapValues { (it.value as? Number)?.toDouble() ?: 0.0 }  // Convertir a Double
                        
                        if (tendenciasGuardadas.isNotEmpty()) {
                            MainActivity.cacheTendencias = tendenciasGuardadas
                            MainActivity.cacheTendenciasLocalId = localId
                            MainActivity.cacheTendenciasFecha = DateHelper.getFechaActual()
                            Log.d("TENDENCIA", "✅ Cache cargado desde Firebase: ${tendenciasGuardadas.size} productos")
                            Log.d("TENDENCIA", "   Productos con tendencia: ${tendenciasGuardadas.filter { it.value != 0.0 }.size}")
                        } else {
                            Log.d("TENDENCIA", "⚠️ Documento existe pero no tiene tendencias válidas")
                        }
                    } else {
                        Log.d("TENDENCIA", "⚠️ Documento existe pero está vacío")
                    }
                } else {
                    Log.d("TENDENCIA", "ℹ️ No hay cache en Firebase (aún no se cargó Stock Final)")
                }
            } catch (e: Exception) {
                Log.e("TENDENCIA", "❌ Error cargando cache desde Firebase: ${e.message}")
            }
        }
    }
    
    /**
     * 📊 Muestra el gráfico con un loading mientras carga los datos
     */
    private fun mostrarGraficoConLoading(codigo: String, tendencia: Double) {
        // Crear diálogo de loading
        val loadingDialog = android.app.ProgressDialog(this).apply {
            setMessage("Cargando datos del gráfico...")
            setCancelable(false)
            show()
        }
        
        lifecycleScope.launch {
            try {
                Log.d("GRAFICO", "📥 Cargando historial para $codigo...")
                
                // Cargar solo los datos necesarios para este producto
                val historial = obtenerHistorialVentasTendenciaOptimizado(codigo)
                
                runOnUiThread {
                    loadingDialog.dismiss()
                    
                    if (historial.isEmpty()) {
                        android.widget.Toast.makeText(
                            this@PedidoFabricaActivity,
                            "No hay suficientes datos para mostrar el gráfico de $codigo",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Log.d("GRAFICO", "✅ Historial cargado: ${historial.size} días")
                        mostrarGraficoTendencia(codigo, tendencia, historial)
                    }
                }
            } catch (e: Exception) {
                Log.e("GRAFICO", "❌ Error cargando gráfico: ${e.message}")
                runOnUiThread {
                    loadingDialog.dismiss()
                    android.widget.Toast.makeText(
                        this@PedidoFabricaActivity,
                        "Error cargando gráfico: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    /**
     * 📈 Detecta tendencia de ventas
     * SOLO lee del cache (ya no calcula en tiempo real)
     */
    private fun detectarTendenciaVentas(codigo: String): Double {
        if (!configuracion.usarAjusteTendencia) {
            return 0.0
        }
        return try {
            // Solo leer del cache (documento único, sin verificar fecha)
            if (MainActivity.cacheTendencias != null && 
                MainActivity.cacheTendenciasLocalId == localId) {
                
                val tendenciaCache = MainActivity.cacheTendencias!![codigo] ?: 0.0
                if (tendenciaCache != 0.0) {
                    Log.d("TENDENCIA", "⚡ $codigo: ${String.format("%.1f", tendenciaCache)}% (desde cache)")
                }
                return tendenciaCache
            }
            
            // Si no hay cache, retornar 0
            0.0
            
        } catch (e: Exception) {
            0.0
        }
    }
    
    /**
     * Calcula la venta real de un día específico
     * Venta = Stock Final Anterior + Ingreso - Stock Final
     */
    private fun calcularVentaRealDia(codigo: String, fecha: String): Double {
        return try {
            val nombreProducto = obtenerNombreProducto(codigo)
            val fechaAnterior = obtenerFechaAnteriorA(fecha)
            
            // Obtener registros desde Firebase usando los IDs correctos
            val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
            val registros = kotlinx.coroutines.runBlocking {
                try {
                    val registrosList = mutableListOf<RegistroInventario>()
                    
                    // Obtener Stock Final del día anterior
                    val docStockAnterior = firestore
                        .collection("locales")
                        .document(localId)
                        .collection("registros")
                        .document("${fechaAnterior}_Stock_Final")
                        .get()
                        .await()
                    
                    if (docStockAnterior.exists()) {
                        @Suppress("DEPRECATION")
                        docStockAnterior.toObject(RegistroInventario::class.java)?.let { registrosList.add(it) }
                    }
                    
                    // Obtener Ingreso del día actual
                    val docIngreso = firestore
                        .collection("locales")
                        .document(localId)
                        .collection("registros")
                        .document("${fecha}_Ingreso_de_Mercadería")
                        .get()
                        .await()
                    
                    if (docIngreso.exists()) {
                        @Suppress("DEPRECATION")
                        docIngreso.toObject(RegistroInventario::class.java)?.let { registrosList.add(it) }
                    }
                    
                    // Obtener Stock Final del día actual
                    val docStockFinal = firestore
                        .collection("locales")
                        .document(localId)
                        .collection("registros")
                        .document("${fecha}_Stock_Final")
                        .get()
                        .await()
                    
                    if (docStockFinal.exists()) {
                        @Suppress("DEPRECATION")
                        docStockFinal.toObject(RegistroInventario::class.java)?.let { registrosList.add(it) }
                    }
                    
                    registrosList
                } catch (e: Exception) {
                    Log.w("TENDENCIA", "Error obteniendo registros de Firebase: ${e.message}")
                    emptyList()
                }
            }
            
            val stockAnterior = registros
                .filter { it.fecha == fechaAnterior && it.tipo == "Stock Final" }
                .flatMap { it.productos }
                .find { 
                    it.nombre == nombreProducto || 
                    it.nombre == "$codigo - $nombreProducto" ||
                    it.nombre.startsWith("$codigo ")
                }
                ?.cantidad ?: 0
            
            val ingreso = registros
                .filter { it.fecha == fecha && it.tipo == "Ingreso de Mercadería" }
                .flatMap { it.productos }
                .find { 
                    it.nombre == nombreProducto || 
                    it.nombre == "$codigo - $nombreProducto" ||
                    it.nombre.startsWith("$codigo ")
                }
                ?.cantidad ?: 0
            
            val stockFinal = registros
                .filter { it.fecha == fecha && it.tipo == "Stock Final" }
                .flatMap { it.productos }
                .find { 
                    it.nombre == nombreProducto || 
                    it.nombre == "$codigo - $nombreProducto" ||
                    it.nombre.startsWith("$codigo ")
                }
                ?.cantidad ?: 0
            
            val venta = stockAnterior + ingreso - stockFinal
            venta.toDouble()
            
        } catch (e: Exception) {
            Log.w("TENDENCIA", "Error calculando venta real de $fecha: ${e.message}")
            0.0
        }
    }
    
    /**
     * Obtiene la fecha anterior a una fecha dada
     */
    private fun obtenerFechaAnteriorA(fecha: String): String {
        return try {
            val calendar = DateHelper.getCalendar()
            calendar.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(fecha) ?: Date()
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
        } catch (e: Exception) {
            fecha
        }
    }
    
    /**
     * 📈 Actualiza el indicador visual de tendencia de ventas
     * Calcula la tendencia promedio de TODOS los productos y la muestra
     */
    private fun actualizarIndicadorTendencia() {
        try {
            if (!configuracion.usarAjusteTendencia) {
                txtTendenciaVentas.visibility = View.GONE
                return
            }
            // Solo mostrar para fecha actual (no para históricos)
            if (modoAdmin && fechaSeleccionada != obtenerFechaHoyArgentina()) {
                txtTendenciaVentas.visibility = View.GONE
                return
            }
            
            // Calcular tendencia de TODOS los productos cargados
            val tendencias = mutableListOf<Double>()
            
            productos.forEach { producto ->
                val tendencia = detectarTendenciaVentas(producto.codigo)
                if (tendencia != 0.0) {
                    tendencias.add(tendencia)
                }
            }
            
            if (tendencias.isEmpty()) {
                txtTendenciaVentas.visibility = View.GONE
                return
            }
            
            val tendenciaPromedio = tendencias.average()
            
            // Solo mostrar si es significativa (>= 5% o <= -5%)
            if (kotlin.math.abs(tendenciaPromedio) < 5.0) {
                txtTendenciaVentas.visibility = View.GONE
                return
            }
            
            // Mostrar indicador con color según tendencia
            val simbolo = if (tendenciaPromedio > 0) "📈" else "📉"
            val palabra = if (tendenciaPromedio > 0) "AUMENTO" else "DISMINUCIÓN"
            val mensaje = "$simbolo $palabra DE VENTAS: ${String.format("%.0f", kotlin.math.abs(tendenciaPromedio))}%"
            
            txtTendenciaVentas.text = mensaje
            txtTendenciaVentas.visibility = View.VISIBLE
            
            // Color según tendencia
            val color = if (tendenciaPromedio > 0) {
                android.graphics.Color.parseColor("#4CAF50") // Verde para aumento
            } else {
                android.graphics.Color.parseColor("#FF9800") // Naranja para disminución
            }
            txtTendenciaVentas.setTextColor(color)
            
            Log.d("TENDENCIA", "✅ Indicador actualizado: $mensaje")
            
        } catch (e: Exception) {
            Log.e("TENDENCIA", "Error actualizando indicador: ${e.message}")
            txtTendenciaVentas.visibility = View.GONE
        }
    }
    
    /**
     * Obtiene el promedio del día siguiente para un producto específico
     * Consulta los datos reales de la sección "Promedio de Ventas"
     */
    private fun obtenerPromedioDiarioManana(codigo: String): Double {
        try {
            val localId = obtenerLocalId()
            val diaActual = obtenerDiaActual()
            val diaSiguiente = obtenerDiaSiguiente(diaActual)
            val nombreProducto = obtenerNombreProducto(codigo)
            
            Log.d("PedidoFabricaActivity", "🔍 Buscando promedio MAÑANA para local '$localId', producto '$codigo' ($nombreProducto) en día '$diaSiguiente'")
            
            // Verificar si hay datos cargados en el cache
            if (MainActivity.promediosReales.isEmpty()) {
                Log.w("PedidoFabricaActivity", "⚠️ Cache de promedios vacío para local $localId")
                return 0.0
            }
            
            // Verificar si hay datos para el día específico
            val datosDia = MainActivity.promediosReales[diaSiguiente]
            if (datosDia == null) {
                Log.w("PedidoFabricaActivity", "⚠️ No hay datos para el día $diaSiguiente en local $localId")
                return 0.0
            }
            
            // Intentar obtener datos reales del cache global
            // Buscar tanto por código como por nombre completo
            var promedioManana = datosDia[nombreProducto] ?: 0.0
            
            // Si no encuentra por nombre completo, buscar por código
            if (promedioManana <= 0) {
                promedioManana = datosDia[codigo] ?: 0.0
            }
            
            // Si aún no encuentra, buscar por cualquier clave que contenga el código
            if (promedioManana <= 0) {
                for ((key, value) in datosDia) {
                    if (key.startsWith("$codigo - ") || key == codigo) {
                        promedioManana = value
                        Log.d("PedidoFabricaActivity", "🔍 Encontrado por clave: '$key' = $value")
                        break
                    }
                }
            }
            
            if (promedioManana > 0) {
                Log.d("PedidoFabricaActivity", "✅ Promedio REAL MAÑANA para local '$localId', producto '$codigo' ($nombreProducto) en $diaSiguiente: $promedioManana")
                return promedioManana
            }
            
            // Si no hay datos reales, devolver 0 (no usar datos simulados)
            Log.d("PedidoFabricaActivity", "❌ No hay datos reales para local '$localId', producto '$codigo' ($nombreProducto) en $diaSiguiente - devolviendo 0")
            return 0.0
            
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "❌ Error obteniendo promedio mañana para $codigo: ${e.message}")
            return 0.0
        }
    }
    
    
    /**
     * Consulta el promedio real de forma síncrona desde la base de datos
     * NOTA: Por ahora devuelve 0 para evitar ANR, se implementará consulta asíncrona
     */
    private fun consultarPromedioRealSincrono(dia: String, nombreProducto: String): Double {
        try {
            // Por ahora devolver 0 para evitar ANR
            // TODO: Implementar consulta asíncrona que no bloquee el hilo principal
            Log.d("PedidoFabricaActivity", "Consultando promedio real para $nombreProducto en $dia - usando fallback")
            return 0.0
            
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error consultando promedio real síncrono: ${e.message}")
            return 0.0
        }
    }
    
    /**
     * Obtiene el porcentaje extra configurado desde la configuración actual
     */
    private fun obtenerPorcentajeExtraConfigurado(): Double {
        try {
            // 🔥 USAR LA CONFIGURACIÓN ACTUAL EN LUGAR DE SHAREDPREFERENCES
            val porcentaje = configuracion.porcentajeExtra
            Log.d("PedidoFabricaActivity", "🔍 Porcentaje extra leído desde configuración actual: $porcentaje%")
            return porcentaje.toDouble()
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error obteniendo porcentaje extra: ${e.message}")
            return 30.0 // 30% por defecto en caso de error
        }
    }
    
    /**
     * Actualiza el porcentaje extra en la configuración
     */
    private fun actualizarPorcentajeExtra(nuevoPorcentaje: Float) {
        try {
            val prefs = getSharedPreferences("configuracion_pedido_fabrica", MODE_PRIVATE)
            prefs.edit().putFloat("porcentaje_extra", nuevoPorcentaje).apply()
            Log.d("PedidoFabricaActivity", "Porcentaje extra actualizado a: $nuevoPorcentaje%")
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error actualizando porcentaje extra: ${e.message}")
        }
    }
    
    /**
     * Obtiene el día actual de la semana
     */
    private fun obtenerDiaActual(): String {
        return obtenerDiaSemana(fechaSeleccionada)
    }
    
    /**
     * Obtiene el día siguiente de la semana
     */
    private fun obtenerDiaSiguiente(diaActual: String): String {
        val dias = listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        val indexActual = dias.indexOf(diaActual)
        return if (indexActual == dias.size - 1) "LUNES" else dias[indexActual + 1]
    }
    
    /**
     * Actualiza la información de días extra en la interfaz
     */
    private fun actualizarInformacionDiasExtra() {
        val fechaActual = try {
            val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
            sdf.parse(fechaSeleccionada) ?: Date()
        } catch (e: Exception) {
            Date()
        }
        val calendar = DateHelper.getCalendar()
        calendar.time = fechaActual
        val diaSemana = calendar.get(Calendar.DAY_OF_WEEK)
        
        val diaActual = obtenerDiaActual()
        val diaSiguiente = obtenerDiaSiguiente(diaActual)
        
        Log.d("PedidoFabricaActivity", "🔄 Actualizando información de días extra...")
        Log.d("PedidoFabricaActivity", "📅 Día actual: $diaActual (día semana: $diaSemana)")
        Log.d("PedidoFabricaActivity", "📅 Día siguiente: $diaSiguiente")
        Log.d("PedidoFabricaActivity", "📅 Configuración actual: $diasEntrega")
        
        val diasExtra = mutableListOf<String>()
        
        // Nueva lógica: El primer día (mañana) DEBE tener entrega. Luego suma días sin entrega hasta encontrar el siguiente día con entrega
        val proximosDias = when (diaSemana) {
            Calendar.MONDAY -> listOf("Martes" to diasEntrega.martes, "Miércoles" to diasEntrega.miercoles, "Jueves" to diasEntrega.jueves, "Viernes" to diasEntrega.viernes)
            Calendar.TUESDAY -> listOf("Miércoles" to diasEntrega.miercoles, "Jueves" to diasEntrega.jueves, "Viernes" to diasEntrega.viernes, "Sábado" to diasEntrega.sabado)
            Calendar.WEDNESDAY -> listOf("Jueves" to diasEntrega.jueves, "Viernes" to diasEntrega.viernes, "Sábado" to diasEntrega.sabado, "Domingo" to diasEntrega.domingo)
            Calendar.THURSDAY -> listOf("Viernes" to diasEntrega.viernes, "Sábado" to diasEntrega.sabado, "Domingo" to diasEntrega.domingo, "Lunes" to diasEntrega.lunes)
            Calendar.FRIDAY -> listOf("Sábado" to diasEntrega.sabado, "Domingo" to diasEntrega.domingo, "Lunes" to diasEntrega.lunes, "Martes" to diasEntrega.martes)
            Calendar.SATURDAY -> listOf("Domingo" to diasEntrega.domingo, "Lunes" to diasEntrega.lunes, "Martes" to diasEntrega.martes, "Miércoles" to diasEntrega.miercoles)
            Calendar.SUNDAY -> listOf("Lunes" to diasEntrega.lunes, "Martes" to diasEntrega.martes, "Miércoles" to diasEntrega.miercoles, "Jueves" to diasEntrega.jueves)
            else -> emptyList()
        }
        
        if (proximosDias.isEmpty()) {
            Log.e("PedidoFabricaActivity", "❌ No hay días configurados")
            txtDiasExtra.text = "⚠️ Error: No hay días configurados"
            txtDiasExtra.visibility = View.VISIBLE
            return
        }
        
        // 1. Verificar que el PRIMER día (mañana) tenga entrega
        val (primerDia, primerDiaTieneEntrega) = proximosDias.first()
        if (!primerDiaTieneEntrega) {
            Log.e("PedidoFabricaActivity", "❌ MAÑANA ($primerDia) NO tiene entrega - No se puede hacer pedido")
            txtDiasExtra.text = "⚠️ No se puede hacer pedido: $primerDia no tiene entrega marcada"
            txtDiasExtra.visibility = View.VISIBLE
            
            // Mostrar alerta al usuario
            runOnUiThread {
                val dialog = AlertDialog.Builder(this@PedidoFabricaActivity)
                    .setTitle("⚠️ Error en configuración")
                    .setMessage("No se puede hacer pedido porque MAÑANA ($primerDia) no tiene entrega marcada.\n\nPor favor, marca $primerDia como día con entrega en la configuración de días de entrega.")
                    .setPositiveButton("Configurar Días") { _, _ ->
                        mostrarConfiguracionDiasEntrega()
                    }
                    .setNegativeButton("Cancelar", null)
                    .create()
                
                // Configurar fondo y colores del diálogo para modo oscuro
                dialog.setOnShowListener {
                    val dialogBackgroundColor = if (isDarkMode()) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    }
                    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
                    val titleView = dialog.findViewById<TextView>(android.R.id.title)
                    val messageView = dialog.findViewById<TextView>(android.R.id.message)
                    val textColor = if (isDarkMode()) {
                        android.graphics.Color.WHITE
                    } else {
                        android.graphics.Color.BLACK
                    }
                    titleView?.setTextColor(textColor)
                    messageView?.setTextColor(textColor)
                }
                
                dialog.show()
            }
            return
        }
        
        Log.d("PedidoFabricaActivity", "✅ MAÑANA ($primerDia) tiene entrega - Se puede hacer pedido")
        
        // 2. Revisar los días DESPUÉS de mañana
        for (i in 1 until proximosDias.size) {
            val (nombreDia, tieneEntrega) = proximosDias[i]
            
            if (!tieneEntrega) {
                // Este día NO tiene entrega, agregarlo y continuar
                diasExtra.add(nombreDia)
                Log.d("PedidoFabricaActivity", "➕ $nombreDia sin entrega, agregarlo")
            } else {
                // Este día SÍ tiene entrega, PARAR aquí
                Log.d("PedidoFabricaActivity", "🛑 $nombreDia tiene entrega, parar")
                break
            }
        }
        
        // Construir el texto
        val textoPedido = if (diasExtra.isEmpty()) {
            "📅 Pedido para: $diaSiguiente"
        } else {
            "📅 Pedido para: $diaSiguiente + ${diasExtra.joinToString(" + ")}"
        }
        
        Log.d("PedidoFabricaActivity", "📅 Días extra encontrados: $diasExtra")
        Log.d("PedidoFabricaActivity", "📅 Texto final: $textoPedido")
        
        txtDiasExtra.text = textoPedido
        txtDiasExtra.visibility = View.VISIBLE
        
        Log.d("PedidoFabricaActivity", "✅ Información de días extra actualizada en la interfaz")
    }
    
    /**
     * Calcula los promedios por día basado en los registros
     * (Copiado de PromedioVentasActivity)
     */
    private fun calcularPromediosPorDia(registros: List<RegistroInventario>): Map<String, Map<String, Double>> {
        val promediosPorDia = mutableMapOf<String, MutableMap<String, Double>>()
        val diasSemana = listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        
        // Inicializar estructura
        diasSemana.forEach { dia ->
            promediosPorDia[dia] = mutableMapOf()
        }
        
        // 🚀 OPTIMIZACIÓN: Usar registros de "Venta Diaria" pre-calculados si existen
        val registrosVentaDiaria = registros.filter { it.tipo == "Venta Diaria" }
        
        if (registrosVentaDiaria.isNotEmpty()) {
            Log.d("PROMEDIOS", "✅ Usando ventas diarias pre-calculadas: ${registrosVentaDiaria.size} registros")
            
            // Agrupar por día de la semana usando ventas pre-calculadas
            val ventasPorDia = mutableMapOf<String, MutableList<Map<String, Int>>>()
            
            registrosVentaDiaria.forEach { registro ->
                val diaSemana = obtenerDiaSemana(registro.fecha)
                val ventasDia = registro.productos.associate { it.nombre to it.cantidad }
                if (ventasDia.isNotEmpty()) {
                    ventasPorDia.getOrPut(diaSemana) { mutableListOf() }.add(ventasDia)
                }
            }
            
            // Calcular promedios desde ventas pre-calculadas
            ventasPorDia.forEach { (dia, listaVentas) ->
                if (listaVentas.isNotEmpty()) {
                    val productos = listaVentas.first().keys
                    productos.forEach { producto ->
                        // Filtrar solo los días donde este producto tiene ventas > 0
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
        
        // 🔄 FALLBACK: Si no hay ventas pre-calculadas, calcular desde registros históricos (método antiguo)
        Log.d("PROMEDIOS", "⚠️ No hay ventas pre-calculadas, usando método antiguo (más lento)")
        
        // Obtener todas las fechas ordenadas
        val fechasOrdenadas = registros.map { it.fecha }.distinct().sorted()
        
        if (fechasOrdenadas.isEmpty()) {
            return promediosPorDia
        }
        
        // Agrupar por día de la semana
        val ventasPorDia = mutableMapOf<String, MutableList<Map<String, Int>>>()
        
        fechasOrdenadas.forEach { fecha ->
            val diaSemana = obtenerDiaSemana(fecha)
            val ventasDia = calcularVentasDia(fecha, registros)
            if (ventasDia.isNotEmpty()) {
                ventasPorDia.getOrPut(diaSemana) { mutableListOf() }.add(ventasDia)
            }
        }
        
        // Calcular promedios
        ventasPorDia.forEach { (dia, listaVentas) ->
            if (listaVentas.isNotEmpty()) {
                val productos = listaVentas.first().keys
                productos.forEach { producto ->
                    // Filtrar solo los días donde este producto tiene ventas > 0
                    val ventasConDatos = listaVentas.mapNotNull { it[producto] }.filter { it > 0 }
                    
                    if (ventasConDatos.isNotEmpty()) {
                        val suma = ventasConDatos.sum()
                        val promedio = suma.toDouble() / ventasConDatos.size
                    promediosPorDia[dia]?.set(producto, promedio)
                    } else {
                        // Si no hay días con ventas > 0, el promedio es 0
                        promediosPorDia[dia]?.set(producto, 0.0)
                    }
                }
            }
        }
        
        return promediosPorDia
    }
    
    /**
     * Calcula las ventas de un día específico
     */
    private fun calcularVentasDia(fecha: String, registros: List<RegistroInventario>): Map<String, Int> {
        val registrosFecha = registros.filter { it.fecha == fecha }
        val ingreso = registrosFecha.find { it.tipo == "Ingreso de Mercadería" }?.productos ?: emptyList()
        val stockFinal = registrosFecha.find { it.tipo == "Stock Final" }?.productos ?: emptyList()
        
        // Obtener stock final del día anterior
        val fechaAnterior = obtenerFechaAnterior(fecha)
        val stockAnterior = registros.filter { it.fecha == fechaAnterior }
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
            
            if (venta > 0) {
                ventas[nombreProducto] = venta
            }
        }
        
        return ventas
    }
    
    /**
     * Obtiene la fecha anterior en formato yyyy-MM-dd
     */
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
     * Obtiene los promedios de venta para un producto específico
     */
    private fun obtenerPromediosProducto(codigo: String): List<Double> {
        // Por ahora retornar promedios simulados, después se conectarán con los datos reales
        return when (codigo) {
            "JQ" -> listOf(50.0, 60.0, 45.0, 55.0, 70.0, 80.0, 90.0) // L-D
            "PB" -> listOf(30.0, 35.0, 25.0, 40.0, 45.0, 50.0, 60.0)
            "CS" -> listOf(20.0, 25.0, 15.0, 30.0, 35.0, 40.0, 45.0)
            "PO" -> listOf(40.0, 45.0, 35.0, 50.0, 55.0, 60.0, 70.0)
            else -> listOf(10.0, 12.0, 8.0, 15.0, 18.0, 20.0, 25.0)
        }
    }
    
    /**
     * Obtiene el índice del día (0=Lunes, 6=Domingo)
     */
    private fun obtenerIndiceDia(dia: String): Int {
        return when (dia.uppercase()) {
            "LUNES" -> 0
            "MARTES" -> 1
            "MIERCOLES" -> 2
            "JUEVES" -> 3
            "VIERNES" -> 4
            "SABADO" -> 5
            "DOMINGO" -> 6
            else -> 0
        }
    }
    
    /**
     * Genera y envía el pedido por WhatsApp
     */
    private fun generarYEnviarPedidoWhatsApp(editTexts: MutableMap<String, EditText>) {
        try {
            // Generar el texto del pedido
            val textoPedido = generarTextoPedido(editTexts)
            
            // Crear intent para WhatsApp
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_TEXT, textoPedido)
            }
            
            // Verificar si WhatsApp está instalado
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                // Si no está instalado, usar intent genérico
                val intentGenerico = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, textoPedido)
                }
                startActivity(Intent.createChooser(intentGenerico, "Compartir pedido"))
            }
            
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error enviando pedido por WhatsApp: ${e.message}")
            Toast.makeText(this, "Error enviando pedido: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Genera el texto del pedido en el formato requerido
     */
    private fun generarTextoPedido(_editTexts: MutableMap<String, EditText>): String {
        val pedido = StringBuilder()
        
        // Orden específico de productos (igual que en la pantalla)
        val ordenProductos = listOf(
            // Empanadas
            "JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH",
            // Pizzas
            "MUZZARE", "JAMON", "PEPPERO",
            // Pastelitos
            "BATATA", "MEMBRIL"
            // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity
        )
        
        // Contadores para agregar espacios
        var contadorEmpanadas = 0
        var contadorPizzas = 0
        var contadorPastelitos = 0
        
        pedido.append("📋 PEDIDO FÁBRICA - ${obtenerFechaActual()}\n\n")
        
        // Agregar cada producto en el orden específico
        ordenProductos.forEach { codigo ->
            val stock = obtenerValorStock(codigo)
            // Obtener el valor del campo "Pedido Final" de la tabla
            val pedidoFinal = obtenerValorPedidoFinal(codigo)
            
            // Agregar espacio antes de cada sección
            when {
                // Empanadas
                codigo in listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH") -> {
                    if (contadorEmpanadas == 0) {
                        pedido.append("\n")
                    }
                    contadorEmpanadas++
                }
                // Pizzas
                codigo in listOf("MUZZARE", "JAMON", "PEPPERO") -> {
                    if (contadorPizzas == 0) {
                        pedido.append("\n")
                    }
                    contadorPizzas++
                }
                // Pastelitos
                codigo in listOf("BATATA", "MEMBRIL") -> {
                    if (contadorPastelitos == 0) {
                        pedido.append("\n")
                    }
                    contadorPastelitos++
                }
                // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity - ya no se procesan aquí
            }
            
            // ✅ MOSTRAR LITERAL: Sin multiplicar ni redondear - exactamente lo que escribió el usuario
            val pedidoFormateado = when {
                // Pizzas: mostrar literal (sin multiplicar por 5)
                codigo in listOf("MUZZARE", "PEPPERO", "JAMON") -> {
                    if (pedidoFinal > 0) "${codigo.lowercase()} $stock-$pedidoFinal" else "${codigo.lowercase()} $stock-nopido"
                }
                // Pastelitos: mostrar literal (sin redondear)
                codigo in listOf("MEMBRIL", "BATATA") -> {
                    if (pedidoFinal > 0) "${codigo.lowercase()} $stock-$pedidoFinal" else "${codigo.lowercase()} $stock-0"
                }
                // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity - ya no se formatean aquí
                // Empanadas: usar pedido final directamente
                else -> {
                    if (pedidoFinal > 0) "${codigo.lowercase()} $stock-$pedidoFinal" else "${codigo.lowercase()} $stock-no pido"
                }
            }
            
            pedido.append("$pedidoFormateado\n")
        }
        
        return pedido.toString()
    }
    
    /**
     * Obtiene el valor del campo "Pedido Final" para un producto específico
     */
    private fun obtenerValorPedidoFinal(codigo: String): Int {
        try {
            // Método 1: Buscar por tag
            val tag = "${codigo}_pedido"
            val editText = layoutContenido.findViewWithTag<EditText>(tag)
            if (editText != null) {
                val valor = editText.text?.toString()?.toIntOrNull() ?: 0
                Log.d("PedidoFabricaActivity", "🔍 DEBUG: Obteniendo pedido final para $codigo (tag: $tag) - EditText encontrado: true, valor: $valor")
                return valor
            }
            
            // Método 2: Buscar recursivamente en todos los EditText
            val editTexts = mutableListOf<EditText>()
            buscarEditTextsRecursivamente(layoutContenido, editTexts)
            
            for (editTextItem in editTexts) {
                if (editTextItem.tag == tag) {
                    val valor = editTextItem.text?.toString()?.toIntOrNull() ?: 0
                    Log.d("PedidoFabricaActivity", "🔍 DEBUG: Obteniendo pedido final para $codigo (método recursivo) - valor: $valor")
                    return valor
                }
            }
            
            Log.w("PedidoFabricaActivity", "⚠️ No se encontró EditText para pedido final de $codigo")
            return 0
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error obteniendo valor pedido final para $codigo: ${e.message}")
            return 0
        }
    }
    
    private fun obtenerValorStock(codigo: String): Int {
        try {
            // Método 1: Buscar por tag
            val tag = "${codigo}_stock"
            val editText = layoutContenido.findViewWithTag<EditText>(tag)
            if (editText != null) {
                val valor = editText.text?.toString()?.toIntOrNull() ?: 0
                Log.d("PedidoFabricaActivity", "🔍 DEBUG: Obteniendo stock para $codigo (tag: $tag) - EditText encontrado: true, valor: $valor")
                return valor
            }
            
            // Método 2: Buscar recursivamente en todos los EditText
            val editTexts = mutableListOf<EditText>()
            buscarEditTextsRecursivamente(layoutContenido, editTexts)
            
            for (editTextItem in editTexts) {
                if (editTextItem.tag == tag) {
                    val valor = editTextItem.text?.toString()?.toIntOrNull() ?: 0
                    Log.d("PedidoFabricaActivity", "🔍 DEBUG: Obteniendo stock para $codigo (método recursivo) - valor: $valor")
                    return valor
                }
            }
            
            Log.w("PedidoFabricaActivity", "⚠️ No se encontró EditText para stock de $codigo")
            return 0
        } catch (e: Exception) {
            Log.e("PedidoFabricaActivity", "Error obteniendo valor stock para $codigo: ${e.message}")
            return 0
        }
    }
    
    /**
     * Función auxiliar para buscar EditTexts recursivamente en un ViewGroup
     */
    private fun buscarEditTextsRecursivamente(view: android.view.View, editTexts: MutableList<EditText>) {
        when (view) {
            is EditText -> editTexts.add(view)
            is android.view.ViewGroup -> {
                for (i in 0 until view.childCount) {
                    buscarEditTextsRecursivamente(view.getChildAt(i), editTexts)
                }
            }
        }
    }
    
    /**
     * Configura un EditText para seleccionar todo el texto cuando recibe el foco
     */
    private fun configurarSeleccionTexto(editText: EditText) {
        var fueClickTactil = false
        
        editText.setOnClickListener {
            fueClickTactil = true
            editText.selectAll()
        }
        
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (fueClickTactil) {
                    // Si fue un clic táctil, ya se seleccionó en el onClick
                    fueClickTactil = false
                } else {
                    // Si fue navegación por teclado, seleccionar después de un pequeño delay
                    editText.post {
                        editText.selectAll()
                    }
                }
            }
        }
        
        // Configurar el teclado móvil para mostrar "Siguiente" en lugar de "Enter"
        editText.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        
        // Manejar el botón "Siguiente" del teclado móvil
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                Log.d("NAVEGACION", "Botón Siguiente presionado en: ${editText.tag}")
                // Buscar el siguiente EditText y enfocarlo
                val siguienteEditText = encontrarSiguienteEditTextInteligente(editText)
                if (siguienteEditText != null) {
                    Log.d("NAVEGACION", "Navegando a: ${siguienteEditText.tag}")
                    siguienteEditText.requestFocus()
                    siguienteEditText.selectAll()
                } else {
                    Log.d("NAVEGACION", "No se encontró siguiente EditText")
                }
                true // Consumir el evento
            } else {
                false
            }
        }
        
        // Configurar navegación por teclado para que funcione correctamente
        editText.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // Permitir navegación hacia abajo
                        editText.clearFocus()
                        false // Permitir que el sistema maneje la navegación
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        // Permitir navegación hacia arriba
                        editText.clearFocus()
                        false // Permitir que el sistema maneje la navegación
                    }
                    android.view.KeyEvent.KEYCODE_TAB -> {
                        // Navegación inteligente con Tab
                        editText.clearFocus()
                        encontrarSiguienteEditTextInteligente(editText)?.let { siguienteEditText ->
                            siguienteEditText.requestFocus()
                            siguienteEditText.selectAll()
                        }
                        true // Consumir el evento
                    }
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        // ENTER en móvil - navegar al siguiente campo y seleccionar todo
                        Log.d("NAVEGACION", "ENTER presionado en: ${editText.tag}")
                        editText.clearFocus()
                        // Buscar el siguiente EditText y enfocarlo
                        val siguienteEditText = encontrarSiguienteEditTextInteligente(editText)
                        if (siguienteEditText != null) {
                            Log.d("NAVEGACION", "Navegando a: ${siguienteEditText.tag}")
                            siguienteEditText.requestFocus()
                            siguienteEditText.selectAll()
                        } else {
                            Log.d("NAVEGACION", "No se encontró siguiente EditText")
                        }
                        true // Consumir el evento
                    }
                    else -> false
                }
            } else {
                false
            }
        }
    }
    
    
    /**
     * Navegación inteligente: primero por columna Stock, luego por columna Pedido
     */
    private fun encontrarSiguienteEditTextInteligente(editTextActual: EditText): EditText? {
        // Orden fijo de productos
        val ordenProductos = listOf(
            "JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH",  // EMPANADAS
            "MUZZARE", "JAMON", "PEPPERO",  // PIZZAS
            "BATATA", "MEMBRIL"  // PASTELITOS
            // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity
        )
        
        val tagActual = editTextActual.tag?.toString() ?: ""
        Log.d("NAVEGACION", "Tag actual: $tagActual")
        
        // Extraer código y tipo del tag actual
        val partes = tagActual.split("_")
        if (partes.size != 2) {
            Log.d("NAVEGACION", "Tag no válido, usando navegación normal")
            return encontrarSiguienteEditText(editTextActual)
        }
        
        val codigoActual = partes[0]
        val tipoActual = partes[1] // "stock" o "pedido"
        
        Log.d("NAVEGACION", "Código actual: $codigoActual, Tipo actual: $tipoActual")
        
        val indiceActual = ordenProductos.indexOf(codigoActual)
        if (indiceActual == -1) {
            Log.d("NAVEGACION", "Código no encontrado en orden, usando navegación normal")
            return encontrarSiguienteEditText(editTextActual)
        }
        
        return when (tipoActual) {
            "stock" -> {
                // Si estamos en stock, ir al siguiente producto en stock
                if (indiceActual < ordenProductos.size - 1) {
                    // Siguiente producto en stock
                    val siguienteCodigo = ordenProductos[indiceActual + 1]
                    val siguienteTag = "${siguienteCodigo}_stock"
                    val siguienteEditText = layoutContenido.findViewWithTag<EditText>(siguienteTag)
                    Log.d("NAVEGACION", "Siguiente stock: $siguienteTag")
                    siguienteEditText
                } else {
                    // Terminamos la columna de stock, ir al primer pedido
                    val primerPedido = ordenProductos[0]
                    val primerTag = "${primerPedido}_pedido"
                    val primerEditText = layoutContenido.findViewWithTag<EditText>(primerTag)
                    Log.d("NAVEGACION", "Terminó stock, yendo al primer pedido: $primerTag")
                    primerEditText
                }
            }
            "pedido" -> {
                // Si estamos en pedido, ir al siguiente producto en pedido
                if (indiceActual < ordenProductos.size - 1) {
                    // Siguiente producto en pedido
                    val siguienteCodigo = ordenProductos[indiceActual + 1]
                    val siguienteTag = "${siguienteCodigo}_pedido"
                    val siguienteEditText = layoutContenido.findViewWithTag<EditText>(siguienteTag)
                    Log.d("NAVEGACION", "Siguiente pedido: $siguienteTag")
                    siguienteEditText
                } else {
                    // Terminamos la columna de pedido, ir al primer stock
                    val primerStock = ordenProductos[0]
                    val primerTag = "${primerStock}_stock"
                    val primerEditText = layoutContenido.findViewWithTag<EditText>(primerTag)
                    Log.d("NAVEGACION", "Terminó pedido, yendo al primer stock: $primerTag")
                    primerEditText
                }
            }
            else -> {
                Log.d("NAVEGACION", "Tipo no reconocido: $tipoActual")
                encontrarSiguienteEditText(editTextActual)
            }
        }
    }
    
    /**
     * Encuentra el siguiente EditText en la pantalla para navegación
     */
    private fun encontrarSiguienteEditText(editTextActual: EditText): EditText? {
        val editTexts = mutableListOf<EditText>()
        buscarEditTextsRecursivamente(layoutContenido, editTexts)
        
        // Filtrar solo los EditText habilitados y visibles
        val editTextsVisibles = editTexts.filter { it.isEnabled && it.visibility == android.view.View.VISIBLE }
        
        // Ordenar por posición en pantalla (de arriba hacia abajo, izquierda a derecha)
        val editTextsOrdenados = editTextsVisibles.sortedWith { a, b ->
            val posA = IntArray(2)
            val posB = IntArray(2)
            a.getLocationOnScreen(posA)
            b.getLocationOnScreen(posB)
            
            when {
                posA[1] != posB[1] -> posA[1].compareTo(posB[1]) // Comparar por Y (vertical)
                else -> posA[0].compareTo(posB[0]) // Si están en la misma fila, comparar por X (horizontal)
            }
        }
        
        // Encontrar el índice del EditText actual
        val indiceActual = editTextsOrdenados.indexOf(editTextActual)
        
        // Retornar el siguiente EditText, o el primero si estamos en el último
        return if (indiceActual >= 0 && indiceActual < editTextsOrdenados.size - 1) {
            editTextsOrdenados[indiceActual + 1]
        } else if (editTextsOrdenados.isNotEmpty()) {
            editTextsOrdenados[0] // Volver al primero si estamos en el último
        } else {
            null
        }
    }

    private fun calcularPedidos() {
        if (productos.isEmpty()) {
            Toast.makeText(this@PedidoFabricaActivity, "No hay productos disponibles. Primero carga los promedios.", Toast.LENGTH_LONG).show()
            return
        }
        
        var pedidosCalculados = 0
        
        productos.forEach { producto ->
            val pedidoSugerido = calcularSugerenciaPedido(producto.codigo, producto.stockActual)
            // Actualizar el pedido sugerido en el producto
            val indice = productos.indexOfFirst { it.codigo == producto.codigo }
            if (indice != -1) {
                productos = productos.toMutableList().apply {
                    set(indice, producto.copy(pedidoSugerido = pedidoSugerido))
                }
                pedidosCalculados++
            }
        }
        
        Toast.makeText(this@PedidoFabricaActivity, "Pedidos calculados: $pedidosCalculados productos", Toast.LENGTH_SHORT).show()
        mostrarContenidoPedido() // Actualizar la vista con los cálculos
    }
    
    /**
     * Guarda la configuración en Firebase para el local actual
     */
    private fun guardarDiasEntregaEnFirebase(dias: ConfiguracionDiasEntrega) {
        lifecycleScope.launch {
            try {
                val localId = obtenerLocalId()
                val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
                
                val diasData = mapOf(
                    "lunes" to dias.lunes,
                    "martes" to dias.martes,
                    "miercoles" to dias.miercoles,
                    "jueves" to dias.jueves,
                    "viernes" to dias.viernes,
                    "sabado" to dias.sabado,
                    "domingo" to dias.domingo,
                    "fechaActualizacion" to com.google.firebase.Timestamp.now()
                )
                
                // Guardar dentro del local específico: locales/{localId}/configuracion_dias_entrega
                firestore.collection("locales")
                    .document(localId)
                    .collection("configuracion_dias_entrega")
                    .document("config")
                    .set(diasData)
                    .addOnSuccessListener {
                        Log.d("PedidoFabricaActivity", "✅ Días de entrega guardados en Firebase para local: $localId")
                    }
                    .addOnFailureListener { e ->
                        Log.e("PedidoFabricaActivity", "❌ Error guardando días de entrega en Firebase: ${e.message}")
                    }
                    
            } catch (e: Exception) {
                Log.e("PedidoFabricaActivity", "Error guardando días de entrega: ${e.message}")
            }
        }
    }
    
    private fun guardarConfiguracionEnFirebase(config: ConfiguracionPedidoFabrica) {
        // Guardar también en SharedPreferences para que NotificationReceiver pueda acceder
        val prefs = getSharedPreferences("configuracion_pedido_fabrica", MODE_PRIVATE)
        prefs.edit().putString("hora_alerta", config.horaAlerta).apply()
        Log.d("PedidoFabricaActivity", "💾 Hora de alarma guardada en SharedPreferences: ${config.horaAlerta}")
        
        lifecycleScope.launch {
            try {
                val localId = obtenerLocalId()
                val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
                
                val configData = mapOf(
                    "porcentajeExtra" to config.porcentajeExtra,
                    "horaAlerta" to config.horaAlerta,
                    "usarAjusteTendencia" to config.usarAjusteTendencia,
                    // 🔧 NO guardar días de entrega aquí - se guardan por separado en guardarDiasEntregaEnFirebase()
                    // Los días de entrega se guardan en configuracion_dias_entrega (colección separada)
                    "fechaActualizacion" to com.google.firebase.Timestamp.now()
                )
                
                // Guardar dentro del local específico: locales/{localId}/configuracion_pedido_fabrica
                firestore.collection("locales")
                    .document(localId)
                    .collection("configuracion_pedido_fabrica")
                    .document("config")
                    .set(configData)
                    .addOnSuccessListener {
                        Log.d("PedidoFabricaActivity", "✅ Configuración guardada en Firebase para local: $localId")
                    }
                    .addOnFailureListener { e ->
                        Log.e("PedidoFabricaActivity", "❌ Error guardando configuración en Firebase: ${e.message}")
                    }
                    
            } catch (e: Exception) {
                Log.e("PedidoFabricaActivity", "Error guardando configuración: ${e.message}")
            }
        }
    }
    
    /**
     * Guarda el pedido de fábrica en Firestore con la nueva estructura
     */
    private fun guardarPedidoEnFirestore(_editTexts: MutableMap<String, EditText>) {
        if (modoSoloLectura && !modoAdmin) {
            Log.w("PedidoFabricaActivity", "⚠️ Modo solo lectura - no se puede guardar")
            Toast.makeText(this, "No se puede guardar en fechas anteriores", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (modoAdmin) {
            Log.d("PedidoFabricaActivity", "🔧 Modo administrador - guardando en fecha histórica")
        }
        
        lifecycleScope.launch {
            try {
                Log.d("PedidoFabricaActivity", "🔍 DEBUG: Lista productos antes de mapear: ${productos.size}")
                productos.forEach { producto ->
                    Log.d("PedidoFabricaActivity", "🔍 DEBUG: Producto: ${producto.codigo} - ${producto.nombre}")
                }
                
                // 🔧 FIX: Crear lista de productos desde los valores actuales de la UI - SIN PANIFICADORA
                val ordenProductos = listOf(
                    "JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH",  // EMPANADAS
                    "MUZZARE", "JAMON", "PEPPERO",  // PIZZAS
                    "BATATA", "MEMBRIL"  // PASTELITOS
                    // DULCES, CHIPA, CRIOLLITO movidos a PedidoPanificadoraActivity
                )
                
                val productosParaGuardar = ordenProductos.mapNotNull { codigo ->
                    // Obtener los valores de los campos de la tabla
                    val stockActual = obtenerValorStock(codigo)
                    val cantidadPedido = obtenerValorPedidoFinal(codigo)
                    
                    // Solo incluir productos que tienen valores
                    if (stockActual > 0 || cantidadPedido > 0) {
                        Log.d("PedidoFabricaActivity", "🔍 DEBUG: ${codigo} - Stock: $stockActual, Pedido: $cantidadPedido")
                    
                    mapOf(
                            "codigo" to codigo,
                            "nombre" to obtenerNombreProducto(codigo),
                            "stock" to stockActual,
                            "sugerencia" to obtenerValorSugerencia(codigo),
                        "pedido" to cantidadPedido
                    )
                    } else {
                        null
                    }
                }
                
                // 🔥 CAPTURAR información de "para cuándo es el pedido" desde la UI
                val textoDiasExtra = txtDiasExtra.text.toString()
                
                val configuracionParaGuardar = mapOf(
                    "porcentajeExtra" to porcentajeSeleccionado,
                    "horaAlerta" to configuracion.horaAlerta,
                    "diasEntrega" to mapOf(
                        "lunes" to diasEntrega.lunes,
                        "martes" to diasEntrega.martes,
                        "miercoles" to diasEntrega.miercoles,
                        "jueves" to diasEntrega.jueves,
                        "viernes" to diasEntrega.viernes,
                        "sabado" to diasEntrega.sabado,
                        "domingo" to diasEntrega.domingo
                    ),
                    "fechaCreacion" to com.google.firebase.Timestamp.now(),
                    "paraFecha" to textoDiasExtra  // 🔥 Guardar el texto de "para cuándo es el pedido"
                )
                
                val pedidoData = mapOf(
                    "productos" to productosParaGuardar,
                    "configuracion" to configuracionParaGuardar,
                    "fecha" to fechaSeleccionada,
                    "localId" to localId
                )
                
                // Guardar en locales/{localId}/pedidos_fabrica/{fecha}
                val rutaCompleta = "locales/$localId/pedidos_fabrica/$fechaSeleccionada"
                Log.d("PedidoFabricaActivity", "💾 GUARDANDO en ruta: $rutaCompleta")
                Log.d("PedidoFabricaActivity", "📊 Datos a guardar: ${productosParaGuardar.size} productos")
                
                firestore.collection("locales")
                    .document(localId)
                    .collection("pedidos_fabrica")
                    .document(fechaSeleccionada)
                    .set(pedidoData)
                    .addOnSuccessListener {
                        Log.d("PedidoFabricaActivity", "✅ Pedido guardado exitosamente en Firebase")
                        Log.d("PedidoFabricaActivity", "📍 Ruta: $rutaCompleta")
                        Log.d("PedidoFabricaActivity", "📅 Fecha: $fechaSeleccionada")
                        Log.d("PedidoFabricaActivity", "🏪 Local: $localId")
                        Toast.makeText(this@PedidoFabricaActivity, "Pedido guardado exitosamente", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Log.e("PedidoFabricaActivity", "❌ Error guardando pedido en Firebase: ${e.message}")
                        Toast.makeText(this@PedidoFabricaActivity, "Error guardando pedido: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    
            } catch (e: Exception) {
                Log.e("PedidoFabricaActivity", "Error guardando pedido: ${e.message}")
                Toast.makeText(this@PedidoFabricaActivity, "Error guardando pedido: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * 🔥 NUEVA FUNCIÓN: Guarda automáticamente los datos reconstruidos desde registros
     */
    private fun guardarPedidoReconstruidoEnFirebase(pedidoReconstruido: PedidoFabricaGuardado) {
        lifecycleScope.launch {
            try {
                val productosParaGuardar = pedidoReconstruido.productos.map { producto ->
                    mapOf(
                        "codigo" to producto.codigo,
                        "nombre" to producto.nombre,
                        "stock" to producto.stockActual,
                        "sugerencia" to producto.pedidoSugerido,
                        "pedido" to producto.pedidoFinal
                    )
                }
                
                // 🔥 CAPTURAR información de "para cuándo es el pedido" desde la UI
                val textoDiasExtra = txtDiasExtra.text.toString()
                
                val configuracionParaGuardar = mapOf(
                    "porcentajeExtra" to porcentajeSeleccionado,  // 🔥 USAR EL PORCENTAJE ACTUAL
                    "horaAlerta" to (pedidoReconstruido.configuracion.horaAlerta),
                    "diasEntrega" to mapOf(
                        "lunes" to diasEntrega.lunes,
                        "martes" to diasEntrega.martes,
                        "miercoles" to diasEntrega.miercoles,
                        "jueves" to diasEntrega.jueves,
                        "viernes" to diasEntrega.viernes,
                        "sabado" to diasEntrega.sabado,
                        "domingo" to diasEntrega.domingo
                    ),
                    "fechaCreacion" to com.google.firebase.Timestamp.now(),
                    "paraFecha" to textoDiasExtra,  // 🔥 Guardar el texto de "para cuándo es el pedido"
                    "fecha" to fechaSeleccionada,
                    "localId" to localId
                )
                
                val pedidoData = mapOf(
                    "productos" to productosParaGuardar,
                    "configuracion" to configuracionParaGuardar,
                    "fecha" to fechaSeleccionada,
                    "localId" to localId,
                    "reconstruido" to true  // Marcar como reconstruido
                )
                
                // Guardar en locales/{localId}/pedidos_fabrica/{fecha}
                val rutaCompleta = "locales/$localId/pedidos_fabrica/$fechaSeleccionada"
                Log.d("PedidoFabricaActivity", "💾 GUARDANDO DATOS RECONSTRUIDOS en ruta: $rutaCompleta")
                Log.d("PedidoFabricaActivity", "📊 Productos a guardar: ${productosParaGuardar.size}")
                
                firestore.collection("locales")
                    .document(localId)
                    .collection("pedidos_fabrica")
                    .document(fechaSeleccionada)
                    .set(pedidoData)
                    .addOnSuccessListener {
                        Log.d("PedidoFabricaActivity", "✅ DATOS RECONSTRUIDOS guardados exitosamente en Firebase")
                        Log.d("PedidoFabricaActivity", "📍 Ruta: $rutaCompleta")
                        Log.d("PedidoFabricaActivity", "📅 Fecha: $fechaSeleccionada")
                        Log.d("PedidoFabricaActivity", "🏪 Local: $localId")
                        Log.d("PedidoFabricaActivity", "🔄 Reconstruido: true")
                    }
                    .addOnFailureListener { e ->
                        Log.e("PedidoFabricaActivity", "❌ Error guardando datos reconstruidos: ${e.message}")
                    }
                    
            } catch (e: Exception) {
                Log.e("PedidoFabricaActivity", "Error guardando datos reconstruidos: ${e.message}")
            }
        }
    }
    
    /**
     * Carga la configuración de días de entrega desde Firebase
     */
    private fun mostrarPopupPrimeraVez() {
        val prefs = getSharedPreferences("pedido_fabrica_prefs", MODE_PRIVATE)
        val yaVisto = prefs.getBoolean("popup_primera_vez_visto", false)
        
        if (!yaVisto) {
            // Mostrar popup después de un pequeño delay para que la UI esté lista
            findViewById<ScrollView>(R.id.scrollViewContenido)?.postDelayed({
                mostrarPopupInformativo()
            }, 1000)
        }
    }
    
    private fun mostrarPopupInformativo() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        val titulo = TextView(this).apply {
            text = "🎯 ¡Bienvenido a Pedido Fábrica!"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
            setTextColor(getThemeColor(android.R.attr.textColorPrimary))
        }
        
        val descripcion = TextView(this).apply {
            text = "Para obtener sugerencias precisas de pedido, configura los días en que tu local recibe mercadería de la fábrica.\n\n" +
                   "Por ejemplo, si no te entregan los domingos, desmarca ese día para que el viernes calcule stock para sábado, domingo y lunes."
            textSize = 14f
            setPadding(0, 0, 0, 24)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        
        val btnConfigurar = Button(this).apply {
            text = "⚙️ Configurar Días de Entrega"
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                mostrarConfiguracionDiasEntrega()
            }
        }
        
        layout.addView(titulo)
        layout.addView(descripcion)
        layout.addView(btnConfigurar)
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("ℹ️ Información Importante")
            .setView(layout)
            .setPositiveButton("Entendido") { _, _ ->
                // Marcar como visto
                val prefs = getSharedPreferences("pedido_fabrica_prefs", MODE_PRIVATE)
                prefs.edit().putBoolean("popup_primera_vez_visto", true).apply()
            }
            .setCancelable(false)
            .create()
        
        // Configurar fondo y colores del diálogo para modo oscuro
        dialog.setOnShowListener {
            val dialogBackgroundColor = if (isDarkMode()) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
            val titleView = dialog.findViewById<TextView>(android.R.id.title)
            val textColor = if (isDarkMode()) {
                android.graphics.Color.WHITE
            } else {
                android.graphics.Color.BLACK
            }
            titleView?.setTextColor(textColor)
        }
        
        dialog.show()
    }
    
    private fun cargarDiasEntrega() {
        lifecycleScope.launch {
            try {
                val localId = obtenerLocalId()
                val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
                
                // Cargar desde el local específico: locales/{localId}/configuracion_dias_entrega
                firestore.collection("locales")
                    .document(localId)
                    .collection("configuracion_dias_entrega")
                    .document("config")
                    .get()
                    .addOnSuccessListener { document ->
                        if (document.exists()) {
                            diasEntrega = ConfiguracionDiasEntrega(
                                lunes = document.getBoolean("lunes") ?: true,
                                martes = document.getBoolean("martes") ?: true,
                                miercoles = document.getBoolean("miercoles") ?: true,
                                jueves = document.getBoolean("jueves") ?: true,
                                viernes = document.getBoolean("viernes") ?: true,
                                sabado = document.getBoolean("sabado") ?: true,
                                domingo = document.getBoolean("domingo") ?: true
                            )
                            Log.d("PedidoFabricaActivity", "✅ Días de entrega cargados desde Firebase: $diasEntrega")
                            // Actualizar la información de días extra en la interfaz
                            runOnUiThread {
                            actualizarInformacionDiasExtra()
                            }
                        } else {
                            Log.d("PedidoFabricaActivity", "📝 No hay configuración de días de entrega en Firebase, usando valores por defecto")
                            // Guardar configuración por defecto en Firebase
                            guardarDiasEntregaEnFirebase(diasEntrega)
                            // Actualizar la información de días extra en la interfaz
                            runOnUiThread {
                            actualizarInformacionDiasExtra()
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("PedidoFabricaActivity", "❌ Error cargando días de entrega desde Firebase: ${e.message}")
                        // Actualizar la información de días extra en la interfaz con valores por defecto
                        runOnUiThread {
                        actualizarInformacionDiasExtra()
                        }
                    }
                    
            } catch (e: Exception) {
                Log.e("PedidoFabricaActivity", "Error cargando días de entrega: ${e.message}")
            }
        }
    }
    
    /**
     * Carga la configuración desde Firebase para el local actual
     */
    private fun cargarConfiguracionDesdeFirebase() {
        lifecycleScope.launch {
            try {
                val localId = obtenerLocalId()
                Log.d("CONFIG_LOAD", "🔍 INICIANDO CARGA DE CONFIGURACIÓN")
                Log.d("CONFIG_LOAD", "📍 LocalId: $localId")
                Log.d("CONFIG_LOAD", "📍 Ruta: locales/$localId/configuracion_pedido_fabrica/config")
                
                val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
                
                // Cargar desde el local específico: locales/{localId}/configuracion_pedido_fabrica
                firestore.collection("locales")
                    .document(localId)
                    .collection("configuracion_pedido_fabrica")
                    .document("config")
                    .get()
                    .addOnSuccessListener { document ->
                        Log.d("CONFIG_LOAD", "✅ DOCUMENTO OBTENIDO DE FIREBASE")
                        Log.d("CONFIG_LOAD", "📄 Documento existe: ${document.exists()}")
                        Log.d("CONFIG_LOAD", "📄 Documento ID: ${document.id}")
                        Log.d("CONFIG_LOAD", "📄 Datos completos: ${document.data}")
                            if (document.exists()) {
                            Log.d("CONFIG_LOAD", "📋 EXTRAYENDO DATOS DEL DOCUMENTO")
                            
                                val porcentajeExtra = document.getLong("porcentajeExtra")?.toInt() ?: 30
                                val horaAlerta = document.getString("horaAlerta") ?: "15:30"
                                val usarAjusteTendencia = document.getBoolean("usarAjusteTendencia") ?: true
                                
                                Log.d("CONFIG_LOAD", "📊 PorcentajeExtra extraído: $porcentajeExtra")
                                Log.d("CONFIG_LOAD", "⏰ HoraAlerta extraída: $horaAlerta")
                                Log.d("CONFIG_LOAD", "📈 UsarAjusteTendencia extraído: $usarAjusteTendencia")
                                
                                // 🔧 NO cargar días de entrega aquí - se cargan por separado en cargarDiasEntrega()
                                // Los días de entrega se cargan desde configuracion_dias_entrega (colección separada)
                                    
                                    configuracion = ConfiguracionPedidoFabrica(
                                        porcentajeExtra = porcentajeExtra,
                                        horaAlerta = horaAlerta,
                                        usarAjusteTendencia = usarAjusteTendencia
                                    )
                            
                            // 🔥 SINCRONIZAR porcentajeSeleccionado con la configuración cargada
                            porcentajeSeleccionado = porcentajeExtra.toDouble()
                            Log.d("CONFIG_LOAD", "🔄 porcentajeSeleccionado sincronizado: $porcentajeSeleccionado")
                            
                            Log.d("CONFIG_LOAD", "🔄 ACTUALIZANDO UI")
                            
                            // Actualizar UI en el hilo principal
                            runOnUiThread {
                                Log.d("CONFIG_LOAD", "🎨 Actualizando txtPorcentajeExtra: % Extra: ${configuracion.porcentajeExtra}%")
                                txtPorcentajeExtra.text = "% Extra: ${configuracion.porcentajeExtra}%"

                                Log.d("CONFIG_LOAD", "🎨 Actualizando txtHoraAlerta: 🔔 Alerta: ${configuracion.horaAlerta}")
                                txtHoraAlerta.text = "🔔 Alerta: ${configuracion.horaAlerta}"

                                Log.d("CONFIG_LOAD", "🎨 Llamando actualizarInformacionDiasExtra()")
                                actualizarInformacionDiasExtra()

                                Log.d("CONFIG_LOAD", "✅ UI ACTUALIZADA COMPLETAMENTE")
                                Log.d("CONFIG_LOAD", "🔍 VERIFICACIÓN FINAL - txtPorcentajeExtra.text: ${txtPorcentajeExtra.text}")
                                Log.d("CONFIG_LOAD", "🔍 VERIFICACIÓN FINAL - txtHoraAlerta.text: ${txtHoraAlerta.text}")
                                Log.d("CONFIG_LOAD", "🔍 VERIFICACIÓN FINAL - diasEntrega: $diasEntrega")
                                Log.d("CONFIG_LOAD", "🔍 VERIFICACIÓN FINAL - porcentajeSeleccionado: $porcentajeSeleccionado")
                            }
                                
                                // Actualizar también en el repositorio local
                                repository.actualizarConfiguracion(configuracion)
                                
                            Log.d("CONFIG_LOAD", "💾 Configuración actualizada en repositorio local")
                            
                            // Guardar hora en SharedPreferences para NotificationReceiver
                            val prefs = getSharedPreferences("configuracion_pedido_fabrica", MODE_PRIVATE)
                            prefs.edit().putString("hora_alerta", configuracion.horaAlerta).apply()
                            
                            // 🔔 PROGRAMAR ALARMA cuando se carga la configuración
                            val alarmScheduler = com.tuapp.inventario.notification.AlarmScheduler(this@PedidoFabricaActivity)
                            alarmScheduler.scheduleStockReminder(configuracion.horaAlerta)
                            Log.d("CONFIG_LOAD", "🔔 Alarma programada para las ${configuracion.horaAlerta}")
                            
                            Log.d("CONFIG_LOAD", "🎉 CARGA COMPLETA EXITOSA")
                            
                            // 🔧 EJECUTAR cargarDatosParaFecha DESPUÉS de cargar la configuración
                            Log.d("CONFIG_LOAD", "🔄 Ejecutando cargarDatosParaFecha después de cargar configuración")
                            lifecycleScope.launch {
                                cargarDatosParaFecha(fechaSeleccionada)
                                
                                // 🔍 VERIFICAR VALORES DESPUÉS DE TODO EL PROCESO
                                runOnUiThread {
                                    Log.d("CONFIG_LOAD", "🔍 DESPUÉS DE TODO - txtPorcentajeExtra.text: ${txtPorcentajeExtra.text}")
                                    Log.d("CONFIG_LOAD", "🔍 DESPUÉS DE TODO - txtHoraAlerta.text: ${txtHoraAlerta.text}")
                                    Log.d("CONFIG_LOAD", "🔍 DESPUÉS DE TODO - diasEntrega: $diasEntrega")
                                }
                            }
                            } else {
                            Log.d("CONFIG_LOAD", "❌ DOCUMENTO NO EXISTE EN FIREBASE")
                            Log.d("CONFIG_LOAD", "📝 Usando valores por defecto")
                            Log.d("CONFIG_LOAD", "📊 Porcentaje por defecto: ${configuracion.porcentajeExtra}")
                            Log.d("CONFIG_LOAD", "⏰ Hora por defecto: ${configuracion.horaAlerta}")
                            
                            // 🔥 SINCRONIZAR porcentajeSeleccionado con valor por defecto
                            porcentajeSeleccionado = configuracion.porcentajeExtra.toDouble()
                            Log.d("CONFIG_LOAD", "🔄 porcentajeSeleccionado sincronizado con defecto: $porcentajeSeleccionado")
                            
                                // Actualizar UI con valores por defecto
                            runOnUiThread {
                                Log.d("CONFIG_LOAD", "🎨 Actualizando UI con valores por defecto")
                                txtPorcentajeExtra.text = "% Extra: ${configuracion.porcentajeExtra}%"
                                txtHoraAlerta.text = "🔔 Alerta: ${configuracion.horaAlerta}"
                            }
                                // Guardar configuración por defecto en Firebase
                            Log.d("CONFIG_LOAD", "💾 Guardando configuración por defecto en Firebase")
                                guardarConfiguracionEnFirebase(configuracion)
                            
                            // Guardar hora en SharedPreferences para NotificationReceiver
                            val prefs = getSharedPreferences("configuracion_pedido_fabrica", MODE_PRIVATE)
                            prefs.edit().putString("hora_alerta", configuracion.horaAlerta).apply()
                            
                            // 🔔 PROGRAMAR ALARMA con configuración por defecto
                            val alarmScheduler = com.tuapp.inventario.notification.AlarmScheduler(this@PedidoFabricaActivity)
                            alarmScheduler.scheduleStockReminder(configuracion.horaAlerta)
                            Log.d("CONFIG_LOAD", "🔔 Alarma programada (por defecto) para las ${configuracion.horaAlerta}")
                            
                            // 🔧 EJECUTAR cargarDatosParaFecha DESPUÉS de guardar configuración por defecto
                            Log.d("CONFIG_LOAD", "🔄 Ejecutando cargarDatosParaFecha después de guardar configuración por defecto")
                            lifecycleScope.launch {
                                cargarDatosParaFecha(fechaSeleccionada)
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("CONFIG_LOAD", "❌ ERROR AL CARGAR CONFIGURACIÓN DESDE FIREBASE")
                        Log.e("CONFIG_LOAD", "🚨 Error: ${e.message}")
                        Log.e("CONFIG_LOAD", "🚨 Exception: ${e.toString()}")
                        
                        // 🔧 EJECUTAR cargarDatosParaFecha AÚN EN CASO DE ERROR
                        Log.d("CONFIG_LOAD", "🔄 Ejecutando cargarDatosParaFecha después de error en Firebase")
                        lifecycleScope.launch {
                            cargarDatosParaFecha(fechaSeleccionada)
                        }
                    }
                    
            } catch (e: Exception) {
                Log.e("CONFIG_LOAD", "🚨 EXCEPCIÓN AL CARGAR CONFIGURACIÓN")
                Log.e("CONFIG_LOAD", "🚨 Exception: ${e.toString()}")
                Log.e("CONFIG_LOAD", "🚨 Message: ${e.message}")
                
                // 🔧 EJECUTAR cargarDatosParaFecha AÚN EN CASO DE EXCEPCIÓN
                Log.d("CONFIG_LOAD", "🔄 Ejecutando cargarDatosParaFecha después de excepción")
                lifecycleScope.launch {
                    cargarDatosParaFecha(fechaSeleccionada)
                }
            }
        }
    }
    
    private fun actualizarSugerenciasEnTiempoReal(nuevoPorcentaje: Int) {
        Log.d("PedidoFabricaActivity", "🔄 Actualizando sugerencias en tiempo real con porcentaje: $nuevoPorcentaje%")
        
        // Simular el cambio de texto en todos los EditText para activar los TextWatcher existentes
        val scrollView = findViewById<ScrollView>(R.id.scrollViewContenido)
        if (scrollView != null) {
            simularCambioTextoEnEditTexts(scrollView)
        } else {
            Log.e("PedidoFabricaActivity", "❌ ScrollView no encontrado!")
        }
    }
    
    private fun simularCambioTextoEnEditTexts(scrollView: ScrollView) {
        val layout = scrollView.getChildAt(0) as? LinearLayout ?: return
        simularCambioEnLinearLayout(layout)
    }
    
    private fun simularCambioEnLinearLayout(layout: LinearLayout) {
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            when (child) {
                is EditText -> {
                    // 🔥 SOLO ACTUALIZAR SI YA TIENE DATOS INGRESADOS
                    val textoActual = child.text.toString().trim()
                    if (textoActual.isNotEmpty() && textoActual != "0") {
                        // Simular un cambio de texto para activar el TextWatcher
                        child.setText("") // Limpiar temporalmente
                        child.setText(textoActual) // Restaurar - esto activará el TextWatcher
                        Log.d("PedidoFabricaActivity", "🔄 Actualizando sugerencia para producto con datos: $textoActual")
                    } else {
                        Log.d("PedidoFabricaActivity", "⏭️ Saltando producto sin datos: '$textoActual'")
                    }
                }
                is LinearLayout -> {
                    // Buscar recursivamente en sub-layouts
                    simularCambioEnLinearLayout(child)
                }
            }
        }
    }
    
    private fun recolectarEditTextsYTextView(scrollView: ScrollView, editTexts: MutableMap<String, EditText>, txtSugerencias: MutableMap<String, TextView>) {
        val layout = scrollView.getChildAt(0) as? LinearLayout ?: return
        recolectarEnLinearLayout(layout, editTexts, txtSugerencias)
    }
    
    private fun recolectarEnLinearLayout(layout: LinearLayout, editTexts: MutableMap<String, EditText>, txtSugerencias: MutableMap<String, TextView>) {
        Log.d("PedidoFabricaActivity", "🔍 Recolectando en LinearLayout con ${layout.childCount} hijos")
        
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            when (child) {
                is EditText -> {
                    // Intentar obtener el código del producto desde el tag
                    val codigo = child.tag as? String ?: "GENERICO"
                    editTexts[codigo] = child
                    Log.d("PedidoFabricaActivity", "📝 EditText encontrado para código: $codigo")
                }
                is TextView -> {
                    val texto = child.text.toString()
                    Log.d("PedidoFabricaActivity", "📄 TextView encontrado: '$texto'")
                    if (texto.contains("💡") || texto.contains("Sugerencia") || texto.matches(Regex("\\d+"))) {
                        // Intentar asociar con el EditText más cercano
                        val codigo = encontrarCodigoMasCercano(child, editTexts)
                        if (codigo != null) {
                            txtSugerencias[codigo] = child
                            Log.d("PedidoFabricaActivity", "✅ TextView de sugerencia asociado con código: $codigo")
                        } else {
                            Log.w("PedidoFabricaActivity", "⚠️ No se pudo asociar TextView de sugerencia: '$texto'")
                        }
                    }
                }
                is LinearLayout -> {
                    // Buscar recursivamente en sub-layouts
                    recolectarEnLinearLayout(child, editTexts, txtSugerencias)
                }
            }
        }
    }
    
    private fun encontrarCodigoMasCercano(textView: TextView, editTexts: MutableMap<String, EditText>): String? {
        // Buscar el EditText más cercano en la jerarquía
        var parent = textView.parent
        while (parent != null) {
            if (parent is LinearLayout) {
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (child is EditText) {
                        // Encontrar el código correspondiente a este EditText
                        editTexts.forEach { (codigo, editText) ->
                            if (editText == child) {
                                return codigo
                            }
                        }
                    }
                }
            }
            parent = parent.parent
        }
        return null
    }
    
    // Funciones para skeleton loading
    private fun showSkeletonLoading(message: String = "Cargando datos...") {
        runOnUiThread {
            txtOfflineMsg.text = message
            offlineOverlay.visibility = android.view.View.VISIBLE
            
            // Cancelar timeout anterior si existe
            skeletonTimeout?.cancel()
            
            // Crear timeout de seguridad (5 segundos máximo)
            skeletonTimeout = lifecycleScope.launch {
                kotlinx.coroutines.delay(5000)
                Log.w("PedidoFabricaActivity", "⚠️ Timeout del skeleton loading - forzando ocultación")
                hideSkeletonLoading()
            }
        }
    }
    
    private fun hideSkeletonLoading() {
        runOnUiThread {
            try {
                // Cancelar timeout
                skeletonTimeout?.cancel()
                skeletonTimeout = null
                
                offlineOverlay.visibility = android.view.View.GONE
                Log.d("PedidoFabricaActivity", "✅ Skeleton loading ocultado")
            } catch (e: Exception) {
                Log.e("PedidoFabricaActivity", "❌ Error ocultando skeleton loading", e)
            }
        }
    }
    
    /**
     * Obtiene el historial de stock bajo (≤ 15) de los últimos 7 días para un producto
     */
    private suspend fun obtenerHistorialStockBajo(codigo: String): List<Pair<String, Int>> {
        return try {
            val historial = mutableListOf<Pair<String, Int>>()
            val calendar = DateHelper.getCalendar()
            
            // Buscar en los últimos 7 días
            for (i in 1..7) {
                calendar.time = Date()
                calendar.add(java.util.Calendar.DAY_OF_MONTH, -i)
                val fecha = DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
                
                // Obtener registros de esa fecha usando el repositorio local
                val registros = localRepository.getRegistrosByFecha(localId, fecha).first()
                
                // Buscar el producto específico en registros de "Stock Final"
                val registroStockFinal = registros.find { it.tipo == "Stock Final" }
                if (registroStockFinal != null) {
                    Log.d("HISTORIAL_STOCK", "🔍 Buscando producto '$codigo' en fecha $fecha")
                    Log.d("HISTORIAL_STOCK", "🔍 Productos disponibles: ${registroStockFinal.productos.map { "${it.nombre}:${it.cantidad}" }}")
                    
                    // Buscar por código al inicio del nombre (ej: "HU - Humita" contiene "HU")
                    val producto = registroStockFinal.productos.find { it.nombre.startsWith("$codigo -") }
                    if (producto != null) {
                        Log.d("HISTORIAL_STOCK", "🔍 Producto encontrado: ${producto.nombre} con cantidad ${producto.cantidad}")
                        
                        // Determinar umbral según el tipo de producto
                        val umbral = when {
                            codigo.startsWith("MUZZA") || codigo.startsWith("JAMON") || codigo.startsWith("PEPPER") -> 0 // Solo sin stock (es normal tener 1-2 pizzas)
                            else -> 15 // Umbral normal para otros productos
                        }
                        
                        if (producto.cantidad <= umbral) {
                            historial.add(Pair(fecha, producto.cantidad))
                            Log.d("HISTORIAL_STOCK", "✅ Agregado a historial: $fecha -> ${producto.cantidad} (umbral: $umbral)")
                        } else {
                            Log.d("HISTORIAL_STOCK", "❌ Stock no es bajo: ${producto.cantidad} > $umbral")
                        }
                    } else {
                        Log.d("HISTORIAL_STOCK", "❌ Producto '$codigo' no encontrado en fecha $fecha")
                    }
                } else {
                    Log.d("HISTORIAL_STOCK", "❌ No hay registro 'Stock Final' para fecha $fecha")
                }
            }
            
            Log.d("HISTORIAL_STOCK", "Stock bajo encontrado para $codigo: ${historial.size} días")
            historial
        } catch (e: Exception) {
            Log.e("HISTORIAL_STOCK", "Error obteniendo historial para $codigo: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Muestra un reporte de stock bajo cuando se toca el icono de advertencia
     */
    private fun mostrarReporteStockBajo(codigo: String, historial: List<Pair<String, Int>>) {
        Log.d("REPORTE_STOCK", "Mostrando reporte para $codigo con ${historial.size} registros")
        
        val nombreProducto = try {
            productos.find { it.codigo == codigo }?.nombre ?: codigo
        } catch (e: Exception) {
            codigo
        }
        
        // Colores explícitos para asegurar visibilidad
        val isDark = isDarkMode()
        val textColorPrimary = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val textColorSecondary = if (isDark) android.graphics.Color.parseColor("#CCCCCC") else android.graphics.Color.parseColor("#666666")
        val bgColor = if (isDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        
        // Layout principal
        val layoutPrincipal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(bgColor)
        }
        
        // Título
        val titulo = TextView(this).apply {
            text = "⚠️ Stock Bajo Detectado"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        
        // Producto
        val producto = TextView(this).apply {
            text = "$codigo - $nombreProducto"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textColorPrimary)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        
        // Mensaje
        val mensaje = TextView(this).apply {
            text = "En los últimos 7 días, este producto quedó con stock bajo (≤ 15 unidades):"
            textSize = 16f
            setTextColor(textColorPrimary)
            setPadding(0, 0, 0, 12)
        }
        
        layoutPrincipal.addView(titulo)
        layoutPrincipal.addView(producto)
        layoutPrincipal.addView(mensaje)
        
        // Contenedor para la lista con ScrollView
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        
        val layoutLista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        
        // Lista de fechas y stock
        if (historial.isEmpty()) {
            Log.d("REPORTE_STOCK", "Historial vacío para $codigo")
            val sinDatos = TextView(this).apply {
                text = "No se encontraron registros de stock bajo"
                textSize = 16f
                // Usar color explícito para asegurar visibilidad
                val textColor = if (isDarkMode()) {
                    android.graphics.Color.WHITE
                } else {
                    android.graphics.Color.BLACK
                }
                setTextColor(textColor)
                setPadding(16, 16, 16, 16)
                gravity = android.view.Gravity.CENTER
            }
            layoutLista.addView(sinDatos)
        } else {
            Log.d("REPORTE_STOCK", "Agregando ${historial.size} items al layout")
            for ((fecha, stock) in historial) {
                val fechaFormateada = try {
                    val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
                    val date = sdf.parse(fecha)
                    DateHelper.getDateFormat("dd/MM/yyyy").format(date ?: Date())
                } catch (e: Exception) {
                    Log.e("REPORTE_STOCK", "Error formateando fecha $fecha: ${e.message}")
                    fecha
                }
                
                Log.d("REPORTE_STOCK", "Agregando item: $fechaFormateada -> $stock unidades")
                
                val item = TextView(this).apply {
                    text = "📅 $fechaFormateada → $stock unidades restantes"
                    textSize = 16f
                    // Usar colores explícitos para asegurar visibilidad
                    val textColor = if (isDarkMode()) {
                        android.graphics.Color.WHITE
                    } else {
                        android.graphics.Color.BLACK
                    }
                    val bgColor = if (isDarkMode()) {
                        android.graphics.Color.parseColor("#2D2D2D")
                    } else {
                        android.graphics.Color.parseColor("#F5F5F5")
                    }
                    setTextColor(textColor)
                    setBackgroundColor(bgColor)
                    setPadding(16, 12, 16, 12)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 8)
                    }
                }
                layoutLista.addView(item)
            }
        }
        
        scrollView.addView(layoutLista)
        layoutPrincipal.addView(scrollView)
        
        // Recomendación
        val recomendacion = TextView(this).apply {
            text = "💡 Considera reforzar el pedido para evitar quedarte sin stock."
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
            setPadding(0, 8, 0, 0)
        }
        layoutPrincipal.addView(recomendacion)
        
        val dialog = AlertDialog.Builder(this)
            .setView(layoutPrincipal)
            .setPositiveButton("Entendido", null)
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
        
        dialog.show()
    }
    
    /**
     * Muestra opciones cuando un producto tiene stock bajo Y tendencia
     */
    private fun mostrarOpcionesTendenciaYStock(codigo: String, tendencia: Double, historial: List<Triple<String, Double, Double>>) {
        val codigoMostrar = abreviarCodigoProducto(codigo)
        val opciones = arrayOf(
            "📈 Ver Tendencia de Ventas",
            "⚠️ Ver Historial de Stock Bajo"
        )
        
        AlertDialog.Builder(this)
            .setTitle("$codigoMostrar - Información Disponible")
            .setItems(opciones) { _, which ->
                when (which) {
                    0 -> mostrarGraficoTendencia(codigo, tendencia, historial)
                    1 -> {
                        // Obtener historial de stock bajo
                        lifecycleScope.launch {
                            val historialStock = obtenerHistorialStockBajo(codigo)
                            runOnUiThread {
                                mostrarReporteStockBajo(codigo, historialStock)
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * 📈 Muestra gráfico de tendencia de ventas cuando se toca el ícono
     */
    /**
     * Vista personalizada para dibujar gráfico de líneas
     */
    private inner class GraficoLineas(context: android.content.Context) : android.view.View(context) {
        var historial: List<Triple<String, Double, Double>> = emptyList()
        var codigoProducto: String = ""
        var nombreProducto: String = ""
        var tendencia: Double = 0.0  // Agregar campo para la tendencia
        
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (historial.isEmpty()) return
            
            // 🎨 ESTILO WEB: Colores y diseño como la versión web
            val colorMorado = android.graphics.Color.parseColor("#7b2cbf")  // Venta real
            val colorNaranja = android.graphics.Color.parseColor("#f59e0b") // Promedio
            val colorGrisClaro = android.graphics.Color.parseColor("#e5e7eb") // Ejes
            val colorGrisOscuro = android.graphics.Color.parseColor("#d1d5db") // Líneas guía
            
            // Fondo blanco o negro según tema
            val bgPaint = android.graphics.Paint()
            bgPaint.color = if (isDarkMode()) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
            bgPaint.style = android.graphics.Paint.Style.FILL
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
            }
            
            val textPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 32f
            }
            
            // Márgenes como en web (ajustado para título + subtítulo)
            val marginLeft = 120f
            val marginRight = 60f
            val marginTop = 120f  // Espacio para título + subtítulo
            val marginBottom = 100f
            
            val graphWidth = width - marginLeft - marginRight
            val graphHeight = height - marginTop - marginBottom
            
            // Encontrar valores min y max (desde 0 como en web)
            val ventasReales = historial.map { it.second }
            val promedios = historial.map { it.third }
            val todosValores = ventasReales + promedios
            val maxValue = todosValores.maxOrNull() ?: 100.0
            val minValue = 0.0  // Siempre desde 0 como en web
            val valueRange = maxValue - minValue
            
            // Título del producto (morado como en web)
            textPaint.textAlign = android.graphics.Paint.Align.CENTER
            textPaint.textSize = 42f
            textPaint.color = colorMorado
            textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            val titulo = if (codigoProducto.isNotEmpty()) "$codigoProducto - $nombreProducto" else "Gráfico de Tendencia"
            canvas.drawText(titulo, width / 2f, 50f, textPaint)
            
            // Subtítulo con tendencia (verde/naranja según valor, como en web)
            val iconoTendencia = when {
                tendencia >= 10 -> "📈"
                tendencia >= 5 -> "⬆️"
                tendencia <= -10 -> "📉"
                else -> "⬇️"
            }
            textPaint.textSize = 36f
            textPaint.color = if (tendencia >= 0) {
                android.graphics.Color.parseColor("#22c55e")  // Verde
            } else {
                colorNaranja  // Naranja
            }
            textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText("$iconoTendencia Tendencia: ${String.format("%.1f", tendencia)}%", width / 2f, 92f, textPaint)
            
            // Ejes (gris claro como en web)
            paint.color = colorGrisClaro
            paint.strokeWidth = 2f
            paint.style = android.graphics.Paint.Style.STROKE
            canvas.drawLine(marginLeft, marginTop, marginLeft, height - marginBottom, paint) // Eje Y
            canvas.drawLine(marginLeft, height - marginBottom, width - marginRight, height - marginBottom, paint) // Eje X
            
            // Etiqueta "Unidades" rotada en eje Y (como en web)
            textPaint.textAlign = android.graphics.Paint.Align.CENTER
            textPaint.textSize = 32f
            textPaint.color = colorMorado
            textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.save()
            canvas.translate(35f, marginTop + graphHeight / 2)
            canvas.rotate(-90f)
            canvas.drawText("Unidades", 0f, 0f, textPaint)
            canvas.restore()
            
            // Dibujar líneas guía horizontales y etiquetas Y
            val numYLabels = 5
            for (i in 0..numYLabels) {
                val value = minValue + (valueRange * i / numYLabels)
                val y = height - marginBottom - (graphHeight * i / numYLabels)
                
                // Líneas guía (gris más oscuro como en web)
                paint.color = colorGrisOscuro
                paint.strokeWidth = 1f
                paint.style = android.graphics.Paint.Style.STROKE
                canvas.drawLine(marginLeft, y, width - marginRight, y, paint)
                
                // Etiqueta Y (gris oscuro)
                textPaint.textAlign = android.graphics.Paint.Align.RIGHT
                textPaint.textSize = 28f
                textPaint.typeface = android.graphics.Typeface.DEFAULT
                textPaint.color = if (isDarkMode()) android.graphics.Color.LTGRAY else android.graphics.Color.DKGRAY
                canvas.drawText(String.format("%.0f", value), marginLeft - 15, y + 10, textPaint)
            }
            
            // Función para convertir valor a coordenada Y
            fun valueToY(value: Double): Float {
                val normalized = (value - minValue) / valueRange
                return height - marginBottom - (graphHeight * normalized).toFloat()
            }
            
            // Función para convertir índice a coordenada X
            fun indexToX(index: Int): Float {
                return marginLeft + (graphWidth * index / (historial.size - 1).coerceAtLeast(1))
            }
            
            // Dibujar etiquetas X (fechas) - formato dd/mm como en web
            historial.forEachIndexed { index, (fecha, _, _) ->
                // Mostrar solo primera, última y algunas intermedias para no amontonar
                if (index == 0 || index == historial.size - 1 || index % kotlin.math.max(1, historial.size / 7) == 0) {
                    val x = indexToX(index)
                    val fechaCorta = try {
                        val partes = fecha.split("-")
                        "${partes[2]}/${partes[1]}"  // dd/mm como en web
                    } catch (e: Exception) {
                        fecha.takeLast(5)
                    }
                    
                    textPaint.textAlign = android.graphics.Paint.Align.CENTER
                    textPaint.textSize = 26f
                    textPaint.typeface = android.graphics.Typeface.DEFAULT
                    textPaint.color = if (isDarkMode()) android.graphics.Color.LTGRAY else android.graphics.Color.DKGRAY
                    canvas.drawText(fechaCorta, x, height - marginBottom + 35, textPaint)
                }
            }
            
            // 📊 Dibujar línea de PROMEDIO (naranja punteada como en web)
            paint.color = colorNaranja
            paint.strokeWidth = 4f
            paint.style = android.graphics.Paint.Style.STROKE
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
            
            for (i in 0 until historial.size - 1) {
                val x1 = indexToX(i)
                val y1 = valueToY(historial[i].third)
                val x2 = indexToX(i + 1)
                val y2 = valueToY(historial[i + 1].third)
                canvas.drawLine(x1, y1, x2, y2, paint)
            }
            paint.pathEffect = null
            
            // 📈 Dibujar línea de VENTA REAL (morada sólida como en web)
            paint.color = colorMorado
            paint.strokeWidth = 6f
            paint.style = android.graphics.Paint.Style.STROKE
            
            for (i in 0 until historial.size - 1) {
                val x1 = indexToX(i)
                val y1 = valueToY(historial[i].second)
                val x2 = indexToX(i + 1)
                val y2 = valueToY(historial[i + 1].second)
                canvas.drawLine(x1, y1, x2, y2, paint)
            }
            
            // Dibujar puntos sobre la venta real (morados con borde blanco como en web)
            historial.forEachIndexed { index, (_, ventaReal, _) ->
                val x = indexToX(index)
                val y = valueToY(ventaReal)
                
                // Punto morado
                paint.color = colorMorado
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawCircle(x, y, 10f, paint)
                
                // Borde blanco
                paint.color = android.graphics.Color.WHITE
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 4f
                canvas.drawCircle(x, y, 10f, paint)
            }
            
            // Leyenda en la parte inferior (como en web)
            val legendY = height - 40f
            textPaint.textSize = 28f
            textPaint.typeface = android.graphics.Typeface.DEFAULT
            textPaint.textAlign = android.graphics.Paint.Align.CENTER
            textPaint.color = if (isDarkMode()) android.graphics.Color.LTGRAY else android.graphics.Color.DKGRAY
            
            // Venta Real (morado con línea)
            paint.color = colorMorado
            paint.strokeWidth = 6f
            paint.style = android.graphics.Paint.Style.STROKE
            canvas.drawLine(width / 2f - 200, legendY, width / 2f - 150, legendY, paint)
            canvas.drawText("Venta Real", width / 2f - 90, legendY + 10, textPaint)
            
            // Promedio (naranja punteado con línea)
            paint.color = colorNaranja
            paint.strokeWidth = 4f
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
            canvas.drawLine(width / 2f + 50, legendY, width / 2f + 100, legendY, paint)
            paint.pathEffect = null
            canvas.drawText("Promedio", width / 2f + 160, legendY + 10, textPaint)
        }
    }
    
    private fun mostrarGraficoTendencia(codigo: String, tendencia: Double, historial: List<Triple<String, Double, Double>>) {
        Log.d("GRAFICO_TENDENCIA", "Mostrando gráfico para $codigo con ${historial.size} días")
        
        val nombreProducto = try {
            obtenerNombreProducto(codigo)
        } catch (e: Exception) {
            codigo
        }
        
        // Layout principal (sin padding para que el gráfico ocupe todo)
        val layoutPrincipal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        
        // GRÁFICO DE LÍNEAS VISUAL
        if (historial.isEmpty()) {
            val sinDatos = TextView(this).apply {
                text = "No hay datos suficientes para mostrar el gráfico"
                textSize = 14f
                setTextColor(resources.getColor(android.R.color.darker_gray, null))
                setPadding(16, 16, 16, 16)
                gravity = android.view.Gravity.CENTER
            }
            layoutPrincipal.addView(sinDatos)
        } else {
            val graficoView = GraficoLineas(this).apply {
                this.historial = historial
                this.codigoProducto = codigo
                this.nombreProducto = nombreProducto
                this.tendencia = tendencia  // Pasar la tendencia al gráfico
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (500 * resources.displayMetrics.density).toInt() // 500dp para título + subtítulo + gráfico
                )
                setPadding(0, 0, 0, 0)
            }
            layoutPrincipal.addView(graficoView)
        }
        
        // Mensaje compacto
        val mensajeFinal = TextView(this).apply {
            text = if (tendencia >= 5) {
                "💡 Sugerencias ajustadas automáticamente"
            } else {
                "💡 Considera reducir el pedido"
            }
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (tendencia >= 0) 
                resources.getColor(android.R.color.holo_green_dark, null) 
                else resources.getColor(android.R.color.holo_orange_dark, null))
            setPadding(0, 4, 0, 0)
            gravity = android.view.Gravity.CENTER
        }
        layoutPrincipal.addView(mensajeFinal)
        
        // Crear diálogo SIN título ni padding extra
        val dialog = AlertDialog.Builder(this)
            .setView(layoutPrincipal)
            .create()
        
        // Configurar fondo y eliminar TODOS los paddings
        dialog.setOnShowListener {
            val dialogBackgroundColor = if (isDarkMode()) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
            
            // ELIMINAR padding del contenedor principal del diálogo
            val containerView = layoutPrincipal.parent as? android.view.ViewGroup
            containerView?.setPadding(0, 0, 0, 0)
            
            // ELIMINAR padding del decorView también
            dialog.window?.decorView?.setPadding(0, 0, 0, 0)
            
            Log.d("GRAFICO_DEBUG", "🎨 Diálogo mostrado - todos los paddings eliminados")
        }
        
        // Agregar botón Entendido manualmente ABAJO
        layoutPrincipal.addView(Button(this).apply {
            text = "Entendido"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark, null))
            setTextColor(android.graphics.Color.WHITE)
            setPadding(32, 16, 32, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
                setMargins(0, 16, 0, 16)
            }
            setOnClickListener { dialog.dismiss() }
        })
        
        dialog.show()
    }
    
    /**
     * Guarda el estado de todos los EditText antes de que la actividad se destruya (por rotación)
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        
        // Recolectar todos los EditText del layout
        val editTexts = mutableListOf<EditText>()
        buscarEditTextsRecursivamente(layoutContenido, editTexts)
        
        // Guardar los valores de cada EditText usando su tag como clave
        estadoEditTexts.clear()
        editTexts.forEach { editText ->
            val tag = editText.tag?.toString()
            val valor = editText.text?.toString() ?: ""
            if (tag != null && valor.isNotEmpty()) {
                estadoEditTexts[tag] = valor
                Log.d("PedidoFabricaActivity", "💾 Guardando estado: $tag = $valor")
            }
        }
        
        // Guardar en el Bundle
        outState.putSerializable("estadoEditTexts", HashMap(estadoEditTexts))
        Log.d("PedidoFabricaActivity", "💾 Estado guardado: ${estadoEditTexts.size} EditText")
    }
    
    /**
     * Restaura los valores de los EditText después de que se recrean
     */
    private fun restaurarEstadoEditTexts() {
        if (estadoEditTexts.isEmpty()) {
            Log.d("PedidoFabricaActivity", "📦 No hay estado para restaurar")
            return
        }
        
        // Esperar un poco para que los EditText se creen completamente
        layoutContenido.post {
            val editTexts = mutableListOf<EditText>()
            buscarEditTextsRecursivamente(layoutContenido, editTexts)
            
            var restaurados = 0
            editTexts.forEach { editText ->
                val tag = editText.tag?.toString()
                if (tag != null && estadoEditTexts.containsKey(tag)) {
                    val valorGuardado = estadoEditTexts[tag] ?: ""
                    if (valorGuardado.isNotEmpty()) {
                        editText.setText(valorGuardado)
                        restaurados++
                        Log.d("PedidoFabricaActivity", "📦 Restaurado: $tag = $valorGuardado")
                    }
                }
            }
            
            Log.d("PedidoFabricaActivity", "📦 Estado restaurado: $restaurados EditText de ${estadoEditTexts.size} guardados")
            
            // Limpiar el estado después de restaurar
            estadoEditTexts.clear()
        }
    }
}

data class ConfiguracionDiasEntrega(
    val lunes: Boolean = true,
    val martes: Boolean = true,
    val miercoles: Boolean = true,
    val jueves: Boolean = true,
    val viernes: Boolean = true,
    val sabado: Boolean = true,
    val domingo: Boolean = true
)



data class VencimientoMermaInfo(
    val cantidadVenceHoy: Int = 0,
    val todoVenceHoy: Boolean = false,
    val lotesVencenHoy: List<com.tuapp.inventario.model.VencimientoLote> = emptyList()
)
