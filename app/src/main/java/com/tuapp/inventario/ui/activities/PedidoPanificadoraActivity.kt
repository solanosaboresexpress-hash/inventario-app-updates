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
import com.tuapp.inventario.repository.PedidoPanificadoraRepository
import com.tuapp.inventario.repository.LocalInventarioRepository
import com.tuapp.inventario.utils.FirebaseRegionManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tuapp.inventario.utils.DateHelper
import java.util.*

/**
 * Data class para almacenar pedidos de fó¡brica guardados
 */
data class PedidoPanificadoraGuardado(
    val productos: List<ProductoFabrica>,
    val configuracion: ConfiguracionPedidoFabrica
)

class PedidoPanificadoraActivity : AppCompatActivity() {

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
    
    private lateinit var repository: PedidoPanificadoraRepository
    private lateinit var localRepository: LocalInventarioRepository
    private var productos: List<ProductoFabrica> = emptyList()
    private var configuracion = ConfiguracionPedidoFabrica()
    private var configuracionModificada = false
    private var diasEntrega = ConfiguracionDiasEntrega()
    private var primeraVezPedidoFabrica = true
    private var inicializandoProductos = false
    private val txtSugerencias = mutableMapOf<String, TextView>()
    
    // Variables para la integració³n de fecha
    private lateinit var fechaSeleccionada: String
    // Firebase (usar FirebaseRegionManager para obtener el Firestore de la regió³n actual)
    private val firestore: FirebaseFirestore
        get() = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
    private lateinit var localId: String
    
    // Skeleton loading
    private lateinit var offlineOverlay: LinearLayout
    private lateinit var progressSkeleton: android.widget.ProgressBar
    private lateinit var txtOfflineMsg: TextView
    private var skeletonTimeout: kotlinx.coroutines.Job? = null
    private var porcentajeSeleccionado: Double = 1.0
    private var diasSeleccionados: List<String> = emptyList()
    private var modoSoloLectura: Boolean = false
    private var modoAdmin: Boolean = false
    
    // Usar el cache global de promedios reales desde MainActivity
    
    /**
     * Obtiene un color del tema actual (se adapta a modo oscuro/claro)
     */
    private fun getThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
    
    /**
     * Verifica si el dispositivo estó¡ en modo oscuro
     */
    private fun isDarkMode(): Boolean {
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pedido_panificadora)

        // Inicializar variables para la integració³n de fecha
        // Firebase se inicializa dinó¡micamente segóºn la regió³n seleccionada en LoginActivity
        // ðŸ”§ FORZAR ZONA HORARIA DE ARGENTINA PARA FECHA INICIAL
        val timeZoneInicial = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
        val sdfInicial = DateHelper.getDateFormat("yyyy-MM-dd")
        sdfInicial.timeZone = timeZoneInicial
        fechaSeleccionada = intent.getStringExtra("fechaSeleccionada")
            ?: sdfInicial.format(Date())

        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        localId = prefs.getString("localId", "default") ?: "default"

        Log.d("PedidoPanificadoraActivity", "ðŸ“… Fecha recibida: $fechaSeleccionada")

        // Verificar si es modo administrador (desde gestió³n de locales)
        modoAdmin = intent.getBooleanExtra("modoAdmin", false)
        
        // Verificar si es modo solo lectura (fecha distinta a hoy Y no es modo admin)
        // ðŸ”§ FORZAR ZONA HORARIA DE ARGENTINA (UTC-3)
        val timeZoneHoy = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
        val sdfHoy = DateHelper.getDateFormat("yyyy-MM-dd")
        sdfHoy.timeZone = timeZoneHoy
        val fechaHoy = sdfHoy.format(Date())
        modoSoloLectura = (fechaSeleccionada != fechaHoy) && !modoAdmin
        
        // ðŸ” DEBUG: Verificar zona horaria
        val calendar = DateHelper.getCalendar()
        val zonaHoraria = calendar.timeZone.id
        val offset = calendar.timeZone.getOffset(Date().time) / (1000 * 60 * 60) // en horas
        
        Log.d("CONFIG_LOAD", "ðŸ• DEBUG ZONA HORARIA:")
        Log.d("CONFIG_LOAD", "ðŸ• Zona horaria del sistema: $zonaHoraria")
        Log.d("CONFIG_LOAD", "ðŸ• Offset UTC: $offset horas")
        Log.d("CONFIG_LOAD", "ðŸ• Hora actual: ${DateHelper.getDateFormat("HH:mm:ss").format(Date())}")
        Log.d("CONFIG_LOAD", "ðŸ• Fecha seleccionada: $fechaSeleccionada")
        Log.d("CONFIG_LOAD", "ðŸ• Fecha hoy calculada: $fechaHoy")
        Log.d("ADMIN_FLOW", "ðŸ”§ DIAGNó“STICO RECIBIDO:")
        Log.d("ADMIN_FLOW", "ðŸ”§ modoAdmin recibido: $modoAdmin")
        Log.d("ADMIN_FLOW", "ðŸ”§ fechaSeleccionada != fechaHoy: ${fechaSeleccionada != fechaHoy}")
        Log.d("ADMIN_FLOW", "ðŸ”§ Modo solo lectura calculado: $modoSoloLectura")
        Log.d("PedidoPanificadoraActivity", "ðŸª Local ID: $localId")

        initViews()
        setupListeners()
        inicializarRepositorio()
        
        // ðŸ“ˆ Cargar cache de tendencias desde Firebase al inicio (una sola vez)
        cargarCacheTendenciasDesdeFirebase()
        
        // Mostrar la fecha actual en la barra de navegació³n
        txtFechaActual.text = fechaSeleccionada
        
        // ðŸ”§ NO cargar datos aquó­ - se cargaró¡n despuó©s de obtener la configuració³n de Firebase
        Log.d("CONFIG_LOAD", "â³ Saltando carga inicial de datos - se ejecutaró¡ despuó©s de Firebase")
        
        // ðŸ”§ NO mostrar contenido inmediatamente - se mostraró¡ despuó©s de cargar los datos
        Log.d("ADMIN_FLOW", "â³ Esperando a que se carguen los datos antes de mostrar contenido")
        
        // Solo abrir automó¡ticamente si no es modo solo lectura Y no es modo admin en fecha histó³rica
        if (!modoSoloLectura && !(modoAdmin && fechaSeleccionada != DateHelper.getDateFormat("yyyy-MM-dd").format(Date()))) {
            Log.d("PedidoPanificadoraActivity", "ðŸ“Š Abriendo automó¡ticamente secció³n de carga de stock 15:30")
            // Abrir la secció³n despuó©s de un pequeó±o delay para que se carguen los datos
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1000) // Esperar 1 segundo para que se carguen los datos
                abrirSeccionCargarStock()
            }
        } else {
            Log.d("ADMIN_FLOW", "â­ï¸ NO abriendo secció³n de carga automó¡ticamente - Modo admin en fecha histó³rica")
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
    }

    private fun setupListeners() {
        btnVolver.setOnClickListener {
            @Suppress("DEPRECATION")
            onBackPressed()
        }
        
        btnConfiguracion.setOnClickListener {
            mostrarConfiguracion()
        }
        
        // ðŸ”§ AGREGAR listeners para navegació³n de fechas
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
        repository = PedidoPanificadoraRepository(localRepository, localId)
        configuracion = repository.getConfiguracion()
        
        // Cargar configuració³n desde Firebase al inicio
        Log.d("CONFIG_LOAD", "ðŸš€ ONCREATE - INICIANDO CARGA DE CONFIGURACIó“N")
        Log.d("CONFIG_LOAD", "ðŸ“ LocalId desde onCreate: ${obtenerLocalId()}")
        cargarConfiguracionDesdeFirebase()
        cargarDiasEntrega()
        
        // ðŸ” DEBUG: Verificar valores despuó©s de cargar
        Log.d("CONFIG_LOAD", "ðŸ” DESPUó‰S DE CARGAR - Configuració³n actual:")
        Log.d("CONFIG_LOAD", "ðŸ“Š configuracion.porcentajeExtra: ${configuracion.porcentajeExtra}")
        Log.d("CONFIG_LOAD", "â° configuracion.horaAlerta: ${configuracion.horaAlerta}")
        Log.d("CONFIG_LOAD", "ðŸ“… diasEntrega: $diasEntrega")
        
        // ðŸ”§ NO LLAMAR cargarDatosParaFecha aquó­ - se llamaró¡ despuó©s de cargar Firebase
        Log.d("CONFIG_LOAD", "â³ Esperando a que termine la carga de Firebase antes de cargar datos")
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
            Log.d("ADMIN_FLOW", "ðŸ”™ Navegando a fecha anterior: $fechaSeleccionada -> $nuevaFecha")
            
            fechaSeleccionada = nuevaFecha
            cargarDatosParaNuevaFecha()
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "Error navegando a fecha anterior", e)
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
            Log.d("ADMIN_FLOW", "ðŸ”œ Navegando a fecha siguiente: $fechaSeleccionada -> $nuevaFecha")
            
            fechaSeleccionada = nuevaFecha
            cargarDatosParaNuevaFecha()
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "Error navegando a fecha siguiente", e)
        }
    }
    
    /**
     * Carga datos para la nueva fecha despuó©s de navegar
     */
    private fun cargarDatosParaNuevaFecha() {
        Log.d("ADMIN_FLOW", "ðŸ”„ Cargando datos para nueva fecha: $fechaSeleccionada")
        
        // Actualizar la fecha en la barra de navegació³n
        txtFechaActual.text = fechaSeleccionada
        
        // Actualizar modo solo lectura segóºn la nueva fecha
        // ðŸ”§ FORZAR ZONA HORARIA DE ARGENTINA (UTC-3)
        val timeZoneNavegacion = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
        val sdfNavegacion = DateHelper.getDateFormat("yyyy-MM-dd")
        sdfNavegacion.timeZone = timeZoneNavegacion
        val fechaHoy = sdfNavegacion.format(Date())
        modoSoloLectura = (fechaSeleccionada != fechaHoy) && !modoAdmin
        
        // ðŸ”§ RESTAURAR CONFIGURACIó“N ACTUAL SI VUELVE AL DóA DE HOY
        if (fechaSeleccionada == fechaHoy && !modoAdmin) {
            Log.d("CONFIG_LOAD", "ðŸ”„ Volviendo al dó­a actual - Restaurando configuració³n desde Firebase")
            cargarConfiguracionDesdeFirebase()
        }
        
        Log.d("ADMIN_FLOW", "ðŸ”§ Modo solo lectura actualizado: $modoSoloLectura")
        
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
                Log.e("PedidoPanificadoraActivity", "No se encontró³ localNombre en SharedPreferences")
                "NO SELECCIONADO"
            } else {
                Log.d("PedidoPanificadoraActivity", "LocalNombre obtenido: $localNombre")
                localNombre
            }
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "Error obteniendo localNombre", e)
            "NO SELECCIONADO"
        }
    }

    private fun cargarDatos() {
        lifecycleScope.launch {
            try {
                println("ðŸ” DEBUG: Cargando datos automó¡ticamente...")
                showSkeletonLoading("Cargando productos...")
                
                // Primero, asegurar que los promedios reales estó©n cargados para este local
                cargarPromediosRealesDelLocal()
                
                // ðŸ”§ En modo admin O modo solo lectura con fecha histó³rica, NO cargar productos desde promedios
                if (!((modoAdmin || modoSoloLectura) && fechaSeleccionada != DateHelper.getDateFormat("yyyy-MM-dd").format(Date()))) {
                    // Cargar los promedios desde los datos reales (solo para fechas actuales)
                    repository.cargarProductosDesdePromedios()
                } else {
                    println("ðŸ” DEBUG: Modo admin/solo lectura en fecha histó³rica - NO cargando productos desde promedios en cargarDatos()")
                }
                
                // Esperar un poco para que se completen los promedios
                kotlinx.coroutines.delay(2000)
                
                // ðŸ”§ En modo admin O modo solo lectura con fecha histó³rica, NO cargar productos ficticios
                if ((modoAdmin || modoSoloLectura) && fechaSeleccionada != DateHelper.getDateFormat("yyyy-MM-dd").format(Date())) {
                    println("ðŸ” DEBUG: Modo admin/solo lectura en fecha histó³rica - NO cargando productos ficticios")
                    return@launch
                }
                
                // ðŸ”§ FIX: Solo cargar productos si no hay productos ya cargados desde Firebase
                if (productos.isEmpty()) {
                // Obtener los productos directamente (solo para fechas actuales)
                productos = repository.getProductosDirectos()
                println("ðŸ” DEBUG: Productos cargados automó¡ticamente: ${productos.size}")
                
                // Si no hay productos, crear productos bó¡sicos como fallback
                if (productos.isEmpty()) {
                    println("ðŸ” DEBUG: No hay productos cargados, creando productos bó¡sicos como fallback")
                    productos = crearProductosBasicos()
                    }
                } else {
                    println("ðŸ” DEBUG: Productos ya cargados desde Firebase, no sobrescribiendo: ${productos.size}")
                }
                
                productos.forEach { producto ->
                    println("ðŸ” DEBUG: Producto cargado: ${producto.codigo} - ${producto.nombre}")
                }
                mostrarContenidoPedido()
            } catch (e: Exception) {
                println("ðŸ” DEBUG: Error cargando datos automó¡ticamente: ${e.message}")
                e.printStackTrace()
            } finally {
                hideSkeletonLoading()
            }
        }
    }
    
    /**
     * Funció³n principal para cargar datos para una fecha especó­fica
     * Intenta leer desde pedidos_fabrica/{fecha}, si no existe reconstruye desde registros
     */
    private suspend fun cargarDatosParaFecha(fecha: String) {
        try {
            Log.d("ADMIN_FLOW", "ðŸ“… Cargando datos para fecha: $fecha")
            
            // Intentar cargar desde pedidos_panificadora/{fecha}
            Log.d("ADMIN_FLOW", "ðŸ” BUSCANDO pedido en Firebase para fecha: $fecha")
            Log.d("ADMIN_FLOW", "ðŸ“ Ruta buscada: locales/$localId/pedidos_panificadora/$fecha")
            
            val pedidoExistente = cargarPedidoExistente(fecha)
            if (pedidoExistente != null) {
                Log.d("ADMIN_FLOW", "âœ… Pedido existente encontrado para $fecha")
                Log.d("ADMIN_FLOW", "ðŸ“Š Productos cargados: ${pedidoExistente.productos.size}")
                
                // ðŸ” DEBUG: Mostrar detalles de los primeros productos
                pedidoExistente.productos.take(3).forEach { producto ->
                    Log.d("ADMIN_FLOW", "ðŸ” Producto: ${producto.nombre} - Stock: ${producto.stockActual} - Sugerencia: ${producto.pedidoSugerido} - Pedido: ${producto.pedidoFinal}")
                }
                
                // ðŸ”§ ORDENAR productos correctamente - SOLO PANIFICADORA
                val ordenProductos = listOf(
                    "MEDIALUNA_MANTECA", "MEDIALUNA_GRASA", "CHIPA", "CRIOLLITO"  // PANIFICADORA
                )
                
                val productosOrdenados = ordenProductos.mapNotNull { codigoOrden ->
                    pedidoExistente.productos.find { it.codigo == codigoOrden }
                }.plus(
                    pedidoExistente.productos.filter { !ordenProductos.contains(it.codigo) }
                )
                
                productos = productosOrdenados
                Log.d("ADMIN_FLOW", "ðŸ”§ Productos ordenados correctamente: ${productos.size}")
                
                porcentajeSeleccionado = pedidoExistente.configuracion.porcentajeExtra.toDouble()
                // diasSeleccionados se mantiene con el valor por defecto
                
                // ðŸ”§ ACTUALIZAR UI con datos histó³ricos despuó©s de cargar
                runOnUiThread {
                    // Actualizar informació³n de dó­as extra con datos histó³ricos
                    actualizarInformacionDiasExtra()
                    
                    Log.d("CONFIG_LOAD", "ðŸ“… Pedido existente encontrado")
                    Log.d("CONFIG_LOAD", "ðŸ“… Fecha del pedido: $fecha")
                    Log.d("CONFIG_LOAD", "ðŸ“… Fecha de hoy: $fechaSeleccionada")
                    Log.d("CONFIG_LOAD", "ðŸ“Š configuracion.porcentajeExtra: ${configuracion.porcentajeExtra}")
                    
                    // ðŸ”§ SOLO actualizar UI si NO es el dó­a de hoy (es histó³rico)
                    if (fecha != fechaSeleccionada) {
                        Log.d("CONFIG_LOAD", "ðŸ“œ Es fecha histó³rica - Actualizando UI con configuració³n del pedido")
                        // Actualizar porcentaje extra con datos histó³ricos
                        txtPorcentajeExtra.text = "% Extra: ${configuracion.porcentajeExtra}%"
                        
                        // Actualizar hora de alerta con datos histó³ricos
                        txtHoraAlerta.text = "ðŸ”” Alerta: ${configuracion.horaAlerta}"
                        
                        Log.d("CONFIG_LOAD", "ðŸ“… UI actualizada con datos histó³ricos:")
                        Log.d("CONFIG_LOAD", "ðŸ“Š Porcentaje: ${configuracion.porcentajeExtra}%")
                        Log.d("CONFIG_LOAD", "â° Hora: ${configuracion.horaAlerta}")
                        Log.d("CONFIG_LOAD", "ðŸ“… Dó­as: $diasEntrega")
                    } else {
                        Log.d("CONFIG_LOAD", "ðŸ“… Es fecha actual - NO sobrescribiendo UI (mantiene valores de Firebase)")
                        Log.d("CONFIG_LOAD", "âœ… txtPorcentajeExtra mantiene: ${txtPorcentajeExtra.text}")
                        Log.d("CONFIG_LOAD", "âœ… txtHoraAlerta mantiene: ${txtHoraAlerta.text}")
                    }
                }
                
                mostrarContenidoPedido()
                return
            } else {
                // ðŸ”§ NO se encontró³ pedido - NO reconstruir nada, solo mostrar mensaje
                Log.d("ADMIN_FLOW", "âŒ No se encontró³ pedido existente para $fecha")
                Log.d("ADMIN_FLOW", "ðŸš« NO reconstruyendo datos - Mostrando mensaje de sin datos")
                mostrarMensajeSinDatosParaFecha(fecha)
                Log.d("ADMIN_FLOW", "âœ… mostrarMensajeSinDatosParaFecha() ejecutado")
            }
            
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "âŒ Error cargando datos para fecha $fecha", e)
            // ðŸ”§ SIEMPRE mostrar mensaje de sin datos si hay error - NO reconstruir nada
            mostrarMensajeSinDatosParaFecha(fecha)
        }
    }

    /**
     * Muestra un mensaje cuando no se encuentran datos para una fecha especó­fica
     */
    private fun mostrarMensajeSinDatosParaFecha(fecha: String) {
        Log.d("ADMIN_FLOW", "ðŸ” EJECUTANDO mostrarMensajeSinDatosParaFecha($fecha)")
        
        // Obtener el dó­a de la semana de la fecha
        val diaSemana = obtenerDiaSemana(fecha)
        val fechaFormateada = try {
            val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
            val fechaParseada = sdf.parse(fecha)
            val sdfMostrar = DateHelper.getDateFormat("dd/MM/yyyy")
            sdfMostrar.format(fechaParseada ?: Date())
        } catch (e: Exception) {
            fecha
        }
        
        // Configurar tó­tulo
        val tituloBase = when {
            modoAdmin -> "ðŸ”§ Pedido Panificadora - $fechaSeleccionada (MODO ADMINISTRADOR)"
            modoSoloLectura -> "ðŸ­ Pedido Panificadora - $fechaSeleccionada (Solo Lectura)"
            else -> "ðŸ­ Pedido Panificadora"
        }
        txtTitulo.text = tituloBase
        
        // Mostrar informació³n bó¡sica
        txtDiaActual.text = "Dó­a: $diaSemana"
        Log.d("CONFIG_LOAD", "ðŸš¨ EN mostrarMensajeSinDatosParaFecha()")
        Log.d("CONFIG_LOAD", "ðŸš¨ configuracion.porcentajeExtra ACTUAL: ${configuracion.porcentajeExtra}")
        Log.d("CONFIG_LOAD", "ðŸš¨ txtPorcentajeExtra.text ACTUAL: ${txtPorcentajeExtra.text}")
        
        // ðŸ”§ NO SOBRESCRIBIR - La UI ya se actualizó³ en cargarConfiguracionDesdeFirebase
        // txtPorcentajeExtra.text = "% Extra: ${configuracion.porcentajeExtra}%"
        Log.d("CONFIG_LOAD", "âœ… NO sobrescribiendo txtPorcentajeExtra - mantiene valor de Firebase")
        
        // Limpiar contenido anterior
        layoutContenido.removeAllViews()
        
        // Crear mensaje principal
        val mensajeView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(if (isDarkMode()) resources.getColor(android.R.color.black, null) else resources.getColor(android.R.color.white, null))
        }
        
        // Icono
        val iconoView = TextView(this).apply {
            text = "ðŸ“‹"
            textSize = 64f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        mensajeView.addView(iconoView)
        
        // Tó­tulo del mensaje
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
                "â€¢ No se realizó³ pedido ese dó­a\n" +
                "â€¢ Los datos no se guardaron correctamente\n" +
                "â€¢ No hay registros de inventario para esa fecha\n\n" +
                "En modo administrador puedes crear un pedido nuevo para esta fecha."
            } else {
                "No se encontraron datos de pedido para esta fecha.\n\n" +
                "Esto puede ocurrir si:\n" +
                "â€¢ No se realizó³ pedido ese dó­a\n" +
                "â€¢ Los datos no se guardaron correctamente\n" +
                "â€¢ No hay registros de inventario para esa fecha"
            }
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
            setPadding(0, 0, 0, 32)
        }
        mensajeView.addView(mensajeExplicativo)
        
        // Botó³n para crear pedido (solo en modo admin, NO en modo solo lectura)
        if (modoAdmin && !modoSoloLectura) {
            val btnCrearPedido = Button(this).apply {
                text = "âž• Crear Pedido para esta Fecha"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark, null))
                setTextColor(resources.getColor(android.R.color.white, null))
                setPadding(32, 16, 32, 16)
                
                setOnClickListener {
                    // ðŸ”§ En modo admin, permitir cargar datos para crear nuevo pedido
                    Log.d("ADMIN_FLOW", "ðŸ”§ Admin creando nuevo pedido para $fecha")
                    layoutContenido.removeAllViews()
                    cargarDatos() // Cargar productos para crear nuevo pedido
                }
            }
            mensajeView.addView(btnCrearPedido)
        }
        
        layoutContenido.addView(mensajeView)
        
        Log.d("PedidoPanificadoraActivity", "ðŸ“‹ Mostrando mensaje de sin datos para fecha $fecha")
    }

    /**
     * Muestra un mensaje explicando que no se pueden crear pedidos ficticios para fechas histó³ricas
     */
    private fun mostrarMensajeCrearPedidoNoDisponible() {
        // Limpiar contenido anterior
        layoutContenido.removeAllViews()
        
        // Crear mensaje principal
        val mensajeView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(if (isDarkMode()) resources.getColor(android.R.color.black, null) else resources.getColor(android.R.color.white, null))
        }
        
        // Icono
        val iconoView = TextView(this).apply {
            text = "âš ï¸"
            textSize = 64f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        mensajeView.addView(iconoView)
        
        // Tó­tulo del mensaje
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
            text = "Para fechas histó³ricas, los pedidos deben crearse con datos reales de inventario.\n\n" +
                   "Si necesitas crear un pedido para esta fecha:\n" +
                   "â€¢ Debe haber registros de inventario reales\n" +
                   "â€¢ Debe haber datos de stock final del dó­a\n" +
                   "â€¢ Los datos deben estar guardados en Firebase\n\n" +
                   "No se pueden generar pedidos ficticios sin datos histó³ricos reales."
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
            setPadding(0, 0, 0, 32)
        }
        mensajeView.addView(mensajeExplicativo)
        
        // Botó³n para volver
        val btnVolver = Button(this).apply {
            text = "ðŸ”™ Volver"
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
        
        Log.d("PedidoPanificadoraActivity", "âš ï¸ Mostrando mensaje de que no se pueden crear pedidos ficticios")
        Log.d("ADMIN_FLOW", "âœ… mostrarMensajeSinDatosParaFecha() COMPLETADO - Mensaje mostrado en pantalla")
    }
    
    /**
     * Carga un pedido existente desde Firestore
     */
    private suspend fun cargarPedidoExistente(fecha: String): PedidoPanificadoraGuardado? {
        return try {
            Log.d("PedidoPanificadoraActivity", "ðŸ” CONSULTANDO Firebase...")
            Log.d("PedidoPanificadoraActivity", "ðŸ“ Colecció³n: locales/$localId/pedidos_panificadora")
            Log.d("PedidoPanificadoraActivity", "ðŸ“„ Documento: $fecha")
            
            val document = firestore
                .collection("locales")
                .document(localId)
                .collection("pedidos_panificadora")
                .document(fecha)
                .get()
                .await()
            
            Log.d("PedidoPanificadoraActivity", "ðŸ“‹ Documento existe: ${document.exists()}")
            
            if (document.exists()) {
                Log.d("PedidoPanificadoraActivity", "ðŸ“„ Documento encontrado para fecha $fecha")
                @Suppress("UNCHECKED_CAST")
                val productosData = document.get("productos") as? List<Map<String, Any>> ?: emptyList()
                @Suppress("UNCHECKED_CAST")
                val configuracionData = document.get("configuracion") as? Map<String, Any> ?: emptyMap()
                Log.d("PedidoPanificadoraActivity", "ðŸ“Š Datos de productos: ${productosData.size} elementos")
                Log.d("PedidoPanificadoraActivity", "âš™ï¸ Configuració³n encontrada: ${configuracionData.isNotEmpty()}")
                
                val productos = productosData.map { productoMap ->
                    val loadedNombre = productoMap["nombre"] as? String ?: ""
                    
                    // ðŸ”§ LIMPIAR los valores cargados para asegurar el formato correcto
                    val cleanedNombre = if (loadedNombre.contains(" - ")) {
                        loadedNombre.split(" - ").lastOrNull() ?: loadedNombre
                    } else {
                        loadedNombre
                    }
                    val cleanedCodigo = obtenerCodigoProducto(cleanedNombre) // Obtener el có³digo correcto del nombre limpio
                    
                    ProductoFabrica(
                        codigo = cleanedCodigo, // Usar el có³digo limpio
                        nombre = cleanedNombre, // Usar el nombre limpio
                        categoria = "EMPANADAS", // Default category
                        promediosVenta = emptyMap(), // Will be populated later if needed
                        stockActual = (productoMap["stock"] as? Number)?.toInt() ?: 0,
                        pedidoSugerido = (productoMap["sugerencia"] as? Number)?.toInt() ?: 0,
                        pedidoFinal = (productoMap["pedido"] as? Number)?.toInt() ?: 0
                    )
                }
                
                // Cargar dó­as de entrega histó³ricos
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
                
                // ðŸ”¥ CARGAR informació³n de "para cuó¡ndo es el pedido" y fecha de creació³n
                val paraFecha = configuracionData["paraFecha"] as? String ?: ""
                val fechaCreacion = configuracionData["fechaCreacion"] as? com.google.firebase.Timestamp
                
                // Guardar la configuració³n histó³rica para usar en la UI
                this.configuracion = configuracion
                this.diasEntrega = diasEntregaHistoricos
                
                // ðŸ”¥ SINCRONIZAR porcentajeSeleccionado con la configuració³n histó³rica
                this.porcentajeSeleccionado = configuracion.porcentajeExtra.toDouble()
                
                // ðŸ”¥ MOSTRAR informació³n de fecha en la UI si estamos viendo un pedido histó³rico
                runOnUiThread {
                    if (paraFecha.isNotEmpty()) {
                        txtDiasExtra.text = paraFecha
                        txtDiasExtra.visibility = View.VISIBLE
                    }
                    
                    // Actualizar el tó­tulo con informació³n de fecha de creació³n si existe
                    if (fechaCreacion != null) {
                        val sdf = DateHelper.getDateFormat("dd/MM/yyyy HH:mm")
                        val fechaCreacionStr = sdf.format(fechaCreacion.toDate())
                        Log.d("PedidoPanificadoraActivity", "ðŸ“… Pedido creado el: $fechaCreacionStr")
                    }
                }
                
                Log.d("PedidoPanificadoraActivity", "ðŸ“… Configuració³n histó³rica cargada:")
                Log.d("PedidoPanificadoraActivity", "ðŸ“Š Porcentaje extra: ${configuracion.porcentajeExtra}")
                Log.d("PedidoPanificadoraActivity", "â° Hora alerta: ${configuracion.horaAlerta}")
                Log.d("PedidoPanificadoraActivity", "ðŸ“… Dó­as de entrega: $diasEntregaHistoricos")
                Log.d("PedidoPanificadoraActivity", "ðŸ“… Para fecha: $paraFecha")
                Log.d("PedidoPanificadoraActivity", "ðŸ”„ porcentajeSeleccionado sincronizado: ${this.porcentajeSeleccionado}")
                
                PedidoPanificadoraGuardado(productos, configuracion)
            } else {
                Log.d("PedidoPanificadoraActivity", "âŒ Documento NO existe para fecha $fecha")
                null
            }
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "Error cargando pedido existente", e)
            null
        }
    }
    
    /**
     * Reconstruye un pedido desde los registros de inventario
     */
    private suspend fun reconstruirPedidoDesdeRegistros(fecha: String): PedidoPanificadoraGuardado? {
        return try {
            val localRepository = (application as InventarioApplication).localRepository
            
            // Obtener registros del dó­a especó­fico
            val registros = localRepository.getAllRegistros(localId).first()
            val registrosDelDia = registros.filter { it.fecha == fecha }
            
            if (registrosDelDia.isEmpty()) {
                Log.w("PedidoPanificadoraActivity", "âš ï¸ No hay registros para la fecha $fecha")
                Log.d("ADMIN_FLOW", "âŒ No se encontraron registros para $fecha - mostrando mensaje de sin datos")
                return null
            }
            
            Log.d("ADMIN_FLOW", "ðŸ“Š Registros encontrados para $fecha: ${registrosDelDia.size}")
            
            // Buscar Stock Final del dó­a
            val stockFinal = registrosDelDia.filter { it.tipo == "Stock Final" }.flatMap { it.productos }
            if (stockFinal.isEmpty()) {
                Log.w("PedidoPanificadoraActivity", "âš ï¸ No hay Stock Final para la fecha $fecha")
                Log.d("ADMIN_FLOW", "âŒ No se encontró³ Stock Final para $fecha - mostrando mensaje de sin datos")
                return null
            }
            
            Log.d("ADMIN_FLOW", "ðŸ“¦ Stock Final encontrado para $fecha: ${stockFinal.size} productos")
            
            // Orden especó­fico de productos (igual que en mostrarContenidoPedido)
            // SOLO PANIFICADORA
            val ordenProductos = listOf(
                "MEDIALUNA_MANTECA", "MEDIALUNA_GRASA", "CHIPA", "CRIOLLITO"  // PANIFICADORA
            )
            
            // Calcular sugerencias basadas en promedios reales y ordenar correctamente
            val productosCalculados = stockFinal.map { productoStock ->
                // ðŸ”§ LIMPIAR nombre del producto PRIMERO (quitar duplicació³n)
                val nombreLimpio = if (productoStock.nombre.contains(" - ")) {
                    productoStock.nombre.split(" - ")[1] // Tomar solo la parte despuó©s del có³digo
                } else {
                    productoStock.nombre
                }
                
                // Buscar el có³digo real del producto usando el nombre LIMPIO
                val codigoReal = obtenerCodigoProducto(nombreLimpio)
                val sugerencia = calcularSugerenciaParaProducto(nombreLimpio, fecha, productoStock.cantidad)
                
                ProductoFabrica(
                    codigo = codigoReal,
                    nombre = nombreLimpio,
                    categoria = productoStock.categoria,
                    promediosVenta = emptyMap(), // Will be populated later if needed
                    stockActual = productoStock.cantidad,
                    pedidoSugerido = sugerencia,
                    pedidoFinal = sugerencia
                )
            }
            
            // Ordenar productos segóºn el orden especó­fico
            val productos = ordenProductos.mapNotNull { codigoOrden ->
                productosCalculados.find { it.codigo == codigoOrden }
            }.plus(
                // Agregar productos que no estó¡n en el orden especó­fico al final
                productosCalculados.filter { !ordenProductos.contains(it.codigo) }
            )
            
            PedidoPanificadoraGuardado(productos, ConfiguracionPedidoFabrica())
            
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "Error reconstruyendo pedido desde registros", e)
            null
        }
    }
    
    /**
     * Obtiene el có³digo real de un producto basado en su nombre
     */
    private fun obtenerCodigoProducto(nombreProducto: String): String {
        // Mapeo de nombres a có³digos (esto deberó­a venir de la base de datos)
        val mapeoNombres = mapOf(
            "Jamó³n y Queso" to "JQ",
            "Pollo BBQ" to "PB",
            "Carne Suave" to "CS",
            "Pollo" to "PO",
            "Carne Picante" to "CP",
            "Humita" to "HU",
            "Espinaca" to "ES",
            "Calabaza y Queso" to "CQ",
            "Roquefort y Jamó³n" to "RJ",
            "Queso y Cebolla" to "QC",
            "Cerdo y Barbacoa" to "CB",
            "Cheeseburger" to "CH",
            "Muzzarella" to "MUZZARE",
            "Jamó³n" to "JAMON",
            "Pepperoni" to "PEPPERO",
            "Batata" to "BATATA",
            "Membrillo" to "MEMBRIL",
            "Medialunas de Manteca" to "MEDIALUNA_MANTECA",
            "Medialunas de Grasa" to "MEDIALUNA_GRASA",
            "Medialuna de Manteca" to "MEDIALUNA_MANTECA",
            "Medialuna de Grasa" to "MEDIALUNA_GRASA",
            "Dulces" to "MEDIALUNA_MANTECA", // Fallback si estó¡ como "Dulces"
            "Salado" to "MEDIALUNA_GRASA", // Fallback si estó¡ como "Salado"
            "Chipa" to "CHIPA",
            "Criollito" to "CRIOLLITO",
            "Criollitos" to "CRIOLLITO"
        )
        return mapeoNombres[nombreProducto] ?: nombreProducto
    }
    
    /**
     * Calcula sugerencia para un producto especó­fico basada en promedios reales
     */
    private fun calcularSugerenciaParaProducto(nombreProducto: String, fecha: String, stockActual: Int): Int {
        try {
            val codigo = obtenerCodigoProducto(nombreProducto)
            Log.d("PedidoPanificadoraActivity", "ðŸ” Calculando sugerencia para $nombreProducto (có³digo: $codigo) en fecha $fecha con stock $stockActual")
            
            // Usar la funció³n completa de có¡lculo de sugerencias con el stock real del dó­a
            return calcularSugerenciaPedido(codigo, stockActual)
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "Error calculando sugerencia para $nombreProducto", e)
            return 0
        }
    }
    
    /**
     * Obtiene el dó­a de la semana de una fecha
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
     * Carga los promedios reales especó­ficos del local actual en el cache global
     */
    private suspend fun cargarPromediosRealesDelLocal() {
        try {
            val localId = obtenerLocalId()
            val localRepository = (application as InventarioApplication).localRepository
            
            Log.d("PedidoPanificadoraActivity", "ðŸ”„ Cargando promedios reales para local: $localId")
            
            localRepository.getAllRegistros(localId).collect { registros ->
                if (registros.isNotEmpty()) {
                    val promediosCalculados = calcularPromediosPorDia(registros)
                    
                    // Actualizar el cache global con los datos de este local especó­fico
                    MainActivity.promediosReales = promediosCalculados
                    MainActivity.promediosCargados = true
                    
                    Log.d("PedidoPanificadoraActivity", "âœ… Promedios reales cargados para local $localId: ${promediosCalculados.size} dó­as")
                    
                    // Log detallado de los datos cargados
                    promediosCalculados.forEach { (dia, productos) ->
                        Log.d("PedidoPanificadoraActivity", "ðŸ“Š $dia: ${productos.size} productos con datos")
                        productos.forEach { (producto, promedio) ->
                            if (promedio > 0) {
                                Log.d("PedidoPanificadoraActivity", "  - $producto: $promedio")
                            }
                        }
                    }
                } else {
                    Log.w("PedidoPanificadoraActivity", "âš ï¸ No hay registros para el local $localId")
                }
                return@collect // Solo tomar la primera emisió³n
            }
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "âŒ Error cargando promedios reales del local: ${e.message}")
        }
    }
    
    /**
     * Carga los promedios generales desde Firebase
     * Igual que en PedidoFabricaActivity
     */
    private suspend fun cargarPromediosGeneralesDesdeFirebase(localId: String): Map<String, Map<String, Double>> {
        return try {
            Log.d("PROMEDIO_GENERAL", "ðŸ“¦ Promedios - Consultando Firebase...")
            val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
            val docRef = firestore.collection("locales")
                .document(localId)
                .collection("promedios_generales")
                .document("promedios")
            
            val document = docRef.get().await()
            
            if (!document.exists()) {
                Log.d("PROMEDIO_GENERAL", "âš ï¸ No hay promedios en Firebase para local $localId")
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
            
            Log.d("PROMEDIO_GENERAL", "âœ… Promedios parseados desde Firebase: ${promedios.size} dó­as")
            
            // Log detallado de los productos cargados
            promedios.forEach { (dia, productos) ->
                Log.d("PROMEDIO_GENERAL", "  ðŸ“Š $dia: ${productos.size} productos")
                productos.entries.take(5).forEach { (producto, promedio) ->
                    Log.d("PROMEDIO_GENERAL", "    - $producto: $promedio")
                }
            }
            
            promedios
            
        } catch (e: Exception) {
            Log.w("PROMEDIO_GENERAL", "âš ï¸ Error cargando promedios desde Firebase: ${e.message}")
            e.printStackTrace()
            emptyMap()
        }
    }
    
    /**
     * Crea productos bó¡sicos como fallback cuando no hay datos histó³ricos
     * SOLO PANIFICADORA: Medialunas de Manteca, Medialunas de Grasa, Chipa, Criollitos
     */
    private fun crearProductosBasicos(): List<ProductoFabrica> {
        val productosBasicos = mutableListOf<ProductoFabrica>()
        
        // SOLO PANIFICADORA
        val panificadora = listOf(
            "MEDIALUNA_MANTECA" to "Medialunas de Manteca",
            "MEDIALUNA_GRASA" to "Medialunas de Grasa",
            "CHIPA" to "Chipa",
            "CRIOLLITO" to "Criollitos"
        )
        
        panificadora.forEach { (codigo, nombre) ->
            productosBasicos.add(ProductoFabrica(
                codigo = codigo,
                nombre = nombre,
                categoria = "PANIFICADORA",
                promediosVenta = emptyMap() // Sin promedios histó³ricos
            ))
        }
        
        Log.d("PedidoPanificadoraActivity", "âœ… Creados ${productosBasicos.size} productos bó¡sicos de panificadora como fallback")
        return productosBasicos
    }
    
    private fun cargarPromedios() {
        println("ðŸ” DEBUG: cargarPromedios() llamado")
        
        // ðŸ”§ En modo admin O modo solo lectura con fecha histó³rica, NO cargar productos ficticios
        if ((modoAdmin || modoSoloLectura) && fechaSeleccionada != DateHelper.getDateFormat("yyyy-MM-dd").format(Date())) {
            println("ðŸ” DEBUG: Modo admin/solo lectura en fecha histó³rica - NO cargando productos en cargarPromedios()")
            return
        }
        
        lifecycleScope.launch {
            try {
                println("ðŸ” DEBUG: Mostrando toast de carga...")
                Toast.makeText(this@PedidoPanificadoraActivity, "Cargando promedios de ventas...", Toast.LENGTH_SHORT).show()
                
                println("ðŸ” DEBUG: Llamando a repository.cargarProductosDesdePromedios()...")
                // ðŸ”§ En modo admin O modo solo lectura con fecha histó³rica, NO cargar productos desde promedios
                if (!((modoAdmin || modoSoloLectura) && fechaSeleccionada != DateHelper.getDateFormat("yyyy-MM-dd").format(Date()))) {
                    // Cargar los promedios desde los datos reales (solo para fechas actuales)
                    repository.cargarProductosDesdePromedios()
                } else {
                    println("ðŸ” DEBUG: Modo admin/solo lectura en fecha histó³rica - NO cargando productos desde promedios")
                }
                
                // Esperar un poco para que se completen los promedios
                kotlinx.coroutines.delay(3000)
                
                println("ðŸ” DEBUG: Obteniendo productos del repositorio...")
                // ðŸ”§ En modo admin O modo solo lectura con fecha histó³rica, NO cargar productos ficticios
                if ((modoAdmin || modoSoloLectura) && fechaSeleccionada != DateHelper.getDateFormat("yyyy-MM-dd").format(Date())) {
                    println("ðŸ” DEBUG: Modo admin/solo lectura en fecha histó³rica - NO cargando productos en cargarPromedios()")
                    return@launch // Salir del launch
                }
                
                // Obtener los productos directamente (solo para fechas actuales)
                productos = repository.getProductosDirectos()
                println("ðŸ” DEBUG: Productos recibidos del repositorio: ${productos.size}")
                productos.forEach { producto ->
                    println("ðŸ” DEBUG: Producto: ${producto.nombre} - Promedios: ${producto.promediosVenta}")
                }
                
                if (productos.isNotEmpty()) {
                    println("ðŸ” DEBUG: Productos cargados exitosamente: ${productos.size}")
                    Toast.makeText(this@PedidoPanificadoraActivity, "Promedios cargados: ${productos.size} productos", Toast.LENGTH_LONG).show()
                } else {
                    println("ðŸ” DEBUG: No se encontraron productos")
                    Toast.makeText(this@PedidoPanificadoraActivity, "No se encontraron datos de ventas. Asegóºrate de tener registros de inventario.", Toast.LENGTH_LONG).show()
                }
                println("ðŸ” DEBUG: Llamando a mostrarContenidoPedido()...")
                mostrarContenidoPedido()
            } catch (e: Exception) {
                println("ðŸ” DEBUG: Error en cargarPromedios: ${e.message}")
                e.printStackTrace()
                Toast.makeText(this@PedidoPanificadoraActivity, "Error cargando promedios: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun mostrarContenidoPedido() {
        Log.d("ADMIN_FLOW", "ðŸ” EJECUTANDO mostrarContenidoPedido()")
        Log.d("ADMIN_FLOW", "ðŸ“Š Productos disponibles: ${productos.size}")
        
        // ðŸ” DEBUG: Mostrar los primeros 3 productos que se van a mostrar
        productos.take(3).forEach { producto ->
            Log.d("ADMIN_FLOW", "ðŸ” Mostrando producto: ${producto.codigo} - ${producto.nombre} - Stock: ${producto.stockActual} - Sugerencia: ${producto.pedidoSugerido}")
        }
        
        // Activar bandera de inicializació³n para evitar có¡lculos automó¡ticos
        inicializandoProductos = true
        
        // Obtener informació³n del local actual
        val localNombre = obtenerLocalNombre()
        
        // Configurar tó­tulo con indicador de local y modo
        val tituloBase = when {
            modoAdmin -> "ðŸ”§ Pedido Panificadora - $fechaSeleccionada (MODO ADMINISTRADOR)"
            modoSoloLectura -> "ðŸ­ Pedido Panificadora - $fechaSeleccionada (Solo Lectura)"
            else -> "ðŸ­ Pedido Panificadora"
        }
        txtTitulo.text = tituloBase
        
        // Mostrar dó­a actual - FORZAR ACTUALIZACIó“N
        val diaActual = if (modoAdmin && fechaSeleccionada != DateHelper.getDateFormat("yyyy-MM-dd").format(Date())) {
            // En modo admin con fecha histó³rica, mostrar el dó­a de esa fecha
            obtenerDiaSemana(fechaSeleccionada)
        } else {
            obtenerDiaActual()
        }
        Log.d("PedidoPanificadoraActivity", "ðŸ” FORZANDO ACTUALIZACIó“N: Dó­a calculado = $diaActual")
        
        // Mostrar dó­a actual
        val textoCompleto = "Dó­a: $diaActual"
        Log.d("PedidoPanificadoraActivity", "ðŸ” ASIGNANDO TEXTO: $textoCompleto")
        txtDiaActual.text = textoCompleto
        
        // Forzar actualizació³n de la UI
        txtDiaActual.invalidate()
        txtDiaActual.requestLayout()
        
        // Mostrar informació³n de dó­as extra
        actualizarInformacionDiasExtra()
        
        // ðŸ“ˆ Actualizar indicador de tendencia de ventas
        actualizarIndicadorTendencia()
        
        // Mostrar porcentaje extra
        val porcentajeMostrar = if (modoAdmin && fechaSeleccionada != DateHelper.getDateFormat("yyyy-MM-dd").format(Date())) {
            // En modo admin con fecha histó³rica, usar el porcentaje guardado en los datos histó³ricos
            // Si no hay configuració³n histó³rica, usar el porcentaje actual
            configuracion.porcentajeExtra
        } else {
            configuracion.porcentajeExtra
        }
        txtPorcentajeExtra.text = "% Extra: ${porcentajeMostrar}%"
        
        // Limpiar contenido anterior
        layoutContenido.removeAllViews()
        
        // Limpiar indicadores de local anteriores para evitar duplicados
        val rootLayout = findViewById<LinearLayout>(R.id.layoutContenido).parent.parent as LinearLayout
        // Buscar y eliminar indicadores de local anteriores (que empiecen con "LOCAL:")
        for (i in rootLayout.childCount - 1 downTo 0) {
            val child = rootLayout.getChildAt(i)
            if (child is TextView && child.text.toString().startsWith("LOCAL:")) {
                rootLayout.removeViewAt(i)
            }
        }
        
        // Crear y agregar indicador de local ARRIBA del tó­tulo principal
        val indicadorLocal = TextView(this).apply {
            text = "LOCAL: $localNombre"
            textSize = 16f
            setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 5, 0, 15)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        
        // Mostrar banner de modo administrador si estó¡ activo
        if (modoAdmin) {
            val bannerAdmin = TextView(this).apply {
                text = "ðŸ”§ MODO ADMINISTRADOR - EDICIó“N HABILITADA"
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
        
        // Mostrar alertas de stock si es la hora correcta
        if (esHoraDeAlerta()) {
            mostrarAlertasStock()
        }
        
        // Mostrar resumen de productos por categoró­a o mensaje de ayuda
        Log.d("PedidoPanificadoraActivity", "ðŸ” Estado de productos: ${productos.size} productos, modoSoloLectura: $modoSoloLectura")
        if (productos.isEmpty()) {
            if (modoSoloLectura) {
                // En modo solo lectura, mostrar mensaje especó­fico si no hay datos
                mostrarMensajeSinDatosLectura()
            } else {
                mostrarMensajeSinProductos()
            }
        } else {
            mostrarResumenProductos()
        }
        
        // Desactivar bandera de inicializació³n - ahora el usuario puede modificar campos
        inicializandoProductos = false
        Log.d("PedidoPanificadoraActivity", "âœ… Inicializació³n completada - campos listos para edició³n")
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
            
            val mensajeAlerta = "âš ï¸ ALERTAS DE STOCK\n\n" + 
                alertas.joinToString("\n") { 
                    "${it.producto} â†’ ${it.stockPredicho} (${it.semana})" 
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
            text = "ðŸ“‹"
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
            text = "No se encontró³ ningóºn pedido guardado para el $fechaSeleccionada.\n\nEsto puede deberse a:\n\nâ€¢ No se realizó³ ningóºn pedido ese dó­a\nâ€¢ Los datos fueron eliminados\nâ€¢ Error al cargar desde la base de datos"
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
            text = "ðŸ“Š"
            textSize = 48f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        
        val tituloView = TextView(this).apply {
            text = "Sin datos histó³ricos de ventas"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 8)
            setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
        }
        
        val descripcionView = TextView(this).apply {
            text = "Para generar sugerencias de pedido necesitas:\n\n1. âœ… Tener registros de inventario histó³ricos\n2. âœ… Ir a 'Promedio de Ventas' y cargar los datos\n3. âœ… Volver aquó­ para ver las sugerencias reales\n\nâš ï¸ Sin datos histó³ricos no se pueden calcular sugerencias precisas"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        
        val botonPromedios = Button(this).apply {
            text = "ðŸ“ˆ Ir a Promedio de Ventas"
            textSize = 14f
            setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark, null))
            setTextColor(resources.getColor(android.R.color.white, null))
            setPadding(16, 12, 16, 12)
            setOnClickListener {
                // Ir a la actividad de Promedio de Ventas
                val intent = Intent(this@PedidoPanificadoraActivity, PromedioVentasActivity::class.java)
                startActivity(intent)
            }
        }
        
        val botonRecargar = Button(this).apply {
            text = "ðŸ”„ Recargar Datos del Local"
            textSize = 12f
            setBackgroundColor(resources.getColor(android.R.color.holo_orange_dark, null))
            setTextColor(resources.getColor(android.R.color.white, null))
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                // Recargar los datos especó­ficos del local actual
                lifecycleScope.launch {
                    cargarPromediosRealesDelLocal()
                    Toast.makeText(this@PedidoPanificadoraActivity, "Recargando datos del local...", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        val botonCargarStock = Button(this).apply {
            text = "ðŸ“¦ Cargar Stock Actual (Sin Sugerencias)"
            textSize = 12f
            setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
            setTextColor(resources.getColor(android.R.color.white, null))
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                // Mostrar la secció³n de carga sin sugerencias
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
        // ðŸ”§ ORDENAR productos por categoró­as - SOLO PANIFICADORA
        val ordenProductos = listOf(
            "MEDIALUNA_MANTECA", "MEDIALUNA_GRASA", "CHIPA", "CRIOLLITO"  // PANIFICADORA
        )
        
        val productosOrdenados = ordenProductos.mapNotNull { codigoOrden ->
            productos.find { it.codigo == codigoOrden }
        }.plus(
            productos.filter { !ordenProductos.contains(it.codigo) }
        )
        
        // ðŸ”§ AGRUPAR productos por categoró­as correctamente
        Log.d("ADMIN_FLOW", "ðŸ” Productos disponibles para categorizar: ${productosOrdenados.map { "${it.codigo}-${it.nombre}" }}")
        
        // ðŸ”§ SOLO PANIFICADORA - Filtrar solo los 4 productos
        val panificadora = productosOrdenados.filter { 
            it.codigo in listOf("MEDIALUNA_MANTECA", "MEDIALUNA_GRASA", "CHIPA", "CRIOLLITO") ||
            it.nombre.contains("Medialuna", ignoreCase = true) ||
            it.nombre.contains("Chipa", ignoreCase = true) ||
            it.nombre.contains("Criollito", ignoreCase = true)
        }
        
        Log.d("ADMIN_FLOW", "ðŸ” PANIFICADORA encontrados: ${panificadora.size} - ${panificadora.map { "${it.codigo}-${it.nombre}" }}")
        
        // ðŸ”§ MOSTRAR solo productos de panificadora
        if (panificadora.isNotEmpty()) mostrarCategoria("PANIFICADORA", panificadora)
        
        Log.d("ADMIN_FLOW", "ðŸ”§ Productos mostrados por categoró­as en orden correcto")
    }

    private fun mostrarCategoria(categoria: String, productos: List<ProductoFabrica>) {
        // ðŸ”§ Tó­tulo de categoró­a con iconos especó­ficos
        val iconoCategoria = when (categoria) {
            "EMPANADAS" -> "ðŸ¥Ÿ"
            "PIZZAS" -> "ðŸ•"
            "PASTELITOS" -> "ðŸ¥§"
            "OTROS" -> "ðŸ“¦"
            else -> "ðŸ“¦"
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
        
        Log.d("ADMIN_FLOW", "ðŸ”§ Mostrando categoró­a: $categoria con ${productos.size} productos")
        
        // ðŸ”§ ORDENAR productos dentro de la categoró­a segóºn el orden especó­fico
        val ordenEspecifico = when (categoria) {
            "PANIFICADORA" -> listOf("MEDIALUNA_MANTECA", "MEDIALUNA_GRASA", "CHIPA", "CRIOLLITO")
            else -> productos.map { it.codigo }
        }
        
        val productosOrdenadosCategoria = ordenEspecifico.mapNotNull { codigo ->
            productos.find { it.codigo == codigo }
        }.plus(
            productos.filter { !ordenEspecifico.contains(it.codigo) }
        )
        
        Log.d("ADMIN_FLOW", "ðŸ”§ Productos en $categoria ordenados: ${productosOrdenadosCategoria.map { it.codigo }}")
        
        // Tabla de productos
        val tablaLayout = TableLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 0, 16, 16)
        }
        
        // Definir colores de texto segóºn modo oscuro
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
                setBackgroundColor(if (isDarkMode()) resources.getColor(android.R.color.black, null) else resources.getColor(android.R.color.holo_blue_light, null))
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
            
            // Nombre del producto (corto)
            val nombreCorto = obtenerNombreCortoProducto(producto.nombre, producto.codigo)
            
            val codigoView = TextView(this).apply {
                text = nombreCorto
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
                    setText(producto.stockActual.toString())
                    textSize = 12f
                    setPadding(8, 8, 8, 8)
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setTextColor(textColor)
                    setBackgroundColor(if (isDarkMode()) resources.getColor(android.R.color.black, null) else resources.getColor(android.R.color.white, null))
                    gravity = android.view.Gravity.CENTER
                    Log.d("ADMIN_FLOW", "ðŸ”§ EditText Stock para ${producto.codigo}: valor asignado = '${producto.stockActual}'")
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
                    setText(producto.pedidoSugerido.toString())
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(8, 8, 8, 8)
                    setTextColor(if (isDarkMode()) resources.getColor(android.R.color.holo_green_light, null) else resources.getColor(android.R.color.holo_green_dark, null))
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setBackgroundColor(if (isDarkMode()) resources.getColor(android.R.color.black, null) else resources.getColor(android.R.color.white, null))
                    gravity = android.view.Gravity.CENTER
                    Log.d("ADMIN_FLOW", "ðŸ”§ EditText Sugerencia para ${producto.codigo}: valor asignado = '${producto.pedidoSugerido}'")
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
                    Log.d("PEDIDO_FINAL", "ðŸ”§ TextView Pedido Final (solo lectura) para ${producto.codigo}: valor = '${producto.pedidoFinal}'")
                }
            } else {
                // En modo normal o admin: EditText editable
                EditText(this).apply {
                    setText(producto.pedidoFinal.toString())
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(8, 8, 8, 8)
                    setTextColor(textColor)
                    gravity = android.view.Gravity.CENTER
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setBackgroundColor(if (isDarkMode()) resources.getColor(android.R.color.black, null) else resources.getColor(android.R.color.white, null))
                    tag = "${producto.codigo}_pedido" // Tag para identificar el campo
                    Log.d("PEDIDO_FINAL", "ðŸ”§ EditText Pedido Final (editable) para ${producto.codigo}: valor = '${producto.pedidoFinal}'")
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
            text = "ðŸ“… Configurar Dó­as de Entrega"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 24)
            setTextColor(textColor)
        }
        
        val descripcion = TextView(this).apply {
            text = "Marca los dó­as en que la fó¡brica entrega mercaderó­a a tu local. Esto afectaró¡ el có¡lculo de sugerencias de pedido."
            textSize = 14f
            setPadding(0, 0, 0, 24)
            setTextColor(textColorSecondary)
        }
        
        val checkboxes = mutableMapOf<String, CheckBox>()
        val dias = listOf(
            "lunes" to "Lunes",
            "martes" to "Martes", 
            "miercoles" to "Mió©rcoles",
            "jueves" to "Jueves",
            "viernes" to "Viernes",
            "sabado" to "Só¡bado",
            "domingo" to "Domingo"
        )
        
        // Agregar tó­tulo y descripció³n primero
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
            .setTitle("âš™ï¸ Dó­as de Entrega")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                // Actualizar configuració³n
                diasEntrega = ConfiguracionDiasEntrega(
                    lunes = checkboxes["lunes"]?.isChecked ?: true,
                    martes = checkboxes["martes"]?.isChecked ?: true,
                    miercoles = checkboxes["miercoles"]?.isChecked ?: true,
                    jueves = checkboxes["jueves"]?.isChecked ?: true,
                    viernes = checkboxes["viernes"]?.isChecked ?: true,
                    sabado = checkboxes["sabado"]?.isChecked ?: true,
                    domingo = checkboxes["domingo"]?.isChecked ?: true
                )
                
                Log.d("PedidoPanificadoraActivity", "ðŸ“… Configuració³n de dó­as actualizada: $diasEntrega")
                Log.d("PedidoPanificadoraActivity", "ðŸ“… Domingo tiene entrega: ${diasEntrega.domingo}")
                
                // Guardar en Firebase
                guardarDiasEntregaEnFirebase(diasEntrega)
                
                // ðŸ”¥ ELIMINAR NOTIFICACIó“N MOLESTA - Las sugerencias se actualizan automó¡ticamente
                // Toast.makeText(this@PedidoPanificadoraActivity, "Recalculando sugerencias...", Toast.LENGTH_SHORT).show()
                
                // Actualizar el mensaje de dó­as extra en la interfaz
                actualizarInformacionDiasExtra()
                
                // Recalcular automó¡ticamente cuando cambian los dó­as de entrega
                recalcularTodasLasSugerencias()
                actualizarSugerenciasEnTiempoReal(configuracion.porcentajeExtra)
                
                // ðŸ”¥ ELIMINAR NOTIFICACIó“N MOLESTA - Las sugerencias se actualizan automó¡ticamente
                // Toast.makeText(this@PedidoPanificadoraActivity, "Dó­as de entrega actualizados y sugerencias recalculadas", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null)
            .create()
        
        // Configurar fondo y colores del dió¡logo para modo oscuro
        val dialogBackgroundColor = if (isDarkMode()) {
            android.graphics.Color.BLACK
        } else {
            android.graphics.Color.WHITE
        }
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
        
        // Configurar color del tó­tulo del dió¡logo
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
                
                // ⚡ SINCRONIZAR porcentajeSeleccionado con la nueva configuración
                porcentajeSeleccionado = nuevoPorcentaje.toDouble()
                Log.d("CONFIG_SAVE", "🔄 porcentajeSeleccionado actualizado: $porcentajeSeleccionado")
                
                // Marcar como modificada para evitar sobrescritura
                configuracionModificada = true
                
                // Guardar en Firebase
                guardarConfiguracionEnFirebase(nuevaConfig)
                
                // Actualizar localmente
                repository.actualizarConfiguracion(nuevaConfig)
                configuracion = nuevaConfig
                
                // Reprogramar alarma con nueva hora
                val alarmScheduler = com.tuapp.inventario.notification.AlarmScheduler(this@PedidoPanificadoraActivity)
                alarmScheduler.cancelStockReminder()
                alarmScheduler.scheduleStockReminder(nuevaConfig.horaAlerta)
                
                txtPorcentajeExtra.text = "% Extra: ${configuracion.porcentajeExtra}%"
                txtHoraAlerta.text = "🔔 Alerta: ${configuracion.horaAlerta}"
                
                // ⚡ ACTUALIZAR SUGERENCIAS AUTOMÁTICAMENTE AL GUARDAR CONFIGURACIÓN
                recalcularTodasLasSugerencias()
                actualizarSugerenciasEnTiempoReal(nuevoPorcentaje)
                actualizarIndicadorTendencia()
                mostrarContenidoPedido()
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
            val titleColor = if (isDarkMode()) {
                android.graphics.Color.WHITE
            } else {
                android.graphics.Color.BLACK
            }
            titleView?.setTextColor(titleColor)
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
                // Solo actualizar el EditText, no guardar todavó­a
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
                val alarmScheduler = com.tuapp.inventario.notification.AlarmScheduler(this@PedidoPanificadoraActivity)
                alarmScheduler.cancelStockReminder()
                alarmScheduler.scheduleStockReminder(nuevaConfig.horaAlerta)
                
                // Actualizar UI
                txtHoraAlerta.text = "ðŸ”” Alerta: ${configuracion.horaAlerta}"
                
                Toast.makeText(this@PedidoPanificadoraActivity, "Hora guardada y alarma reprogramada", Toast.LENGTH_SHORT).show()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        )
        timePicker.show()
    }

    private fun mostrarDialogoCargarStock() {
        println("ðŸ” DEBUG: mostrarDialogoCargarStock() llamado")
        println("ðŸ” DEBUG: productos.size = ${productos.size}")
        productos.forEach { producto ->
            println("ðŸ” DEBUG: Producto en lista: ${producto.nombre} - Promedios: ${producto.promediosVenta}")
        }

        if (productos.isEmpty()) {
            println("ðŸ” DEBUG: No hay productos, mostrando cuadro con productos bó¡sicos...")
            mostrarCuadroConProductosBasicos()
            return
        }
        
        println("ðŸ” DEBUG: Creando dió¡logo de carga de stock con sugerencias...")
        
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
            text = "ðŸ“Š CARGAR STOCK\n\nIngresa el stock actual de cada producto y ve la sugerencia de pedido automó¡ticamente:"
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
            
            // Fila principal: Có³digo + Input + Sugerencia
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
                text = obtenerNombreCortoProducto(producto.nombre, producto.codigo)
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
                val stockValue = producto.stockActual.toString()
                setText(stockValue)
                Log.d("ADMIN_FLOW", "ðŸ”§ EditText Stock para ${producto.codigo}: valor asignado = '$stockValue'")
                inputType = InputType.TYPE_CLASS_NUMBER
                setPadding(12, 12, 12, 12)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                hint = "Stock actual"
                background = editStockDrawable
                setTextColor(textColor)
                setHintTextColor(if (isDarkMode()) android.graphics.Color.parseColor("#CCCCCC") else android.graphics.Color.parseColor("#808080"))
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                tag = producto.codigo // Agregar tag con el có³digo del producto
                
                // Calcular sugerencia cuando cambie el texto - SOLO para este producto
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        // Solo calcular si no estamos inicializando los productos
                        if (!inicializandoProductos) {
                            val stock = s.toString().toIntOrNull() ?: 0
                            Log.d("SUG", "ðŸ” SUG: Usuario modificó³ stock de ${producto.codigo} a $stock")
                            // Usar la funció³n optimizada que solo calcula este producto especó­fico
                            val sugerencia = calcularSugerenciaPedido(producto.codigo, stock)
                            txtSugerencias[producto.codigo]?.text = "ðŸ’¡ Pedido sugerido: $sugerencia unidades"
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
                text = "ðŸ’¡ Pedido sugerido: $sugerenciaValue unidades"
                Log.d("ADMIN_FLOW", "ðŸ”§ TextView Sugerencia para ${producto.codigo}: valor asignado = '$sugerenciaValue'")
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
            .setTitle("ðŸ“Š CARGAR STOCK")
            .setView(scrollView)
            .setPositiveButton("ðŸ’¾ Guardar Stock") { _, _ ->
                // Guardar los valores ingresados
                editTexts.forEach { (codigo, editText) ->
                    val stock = editText.text.toString().toIntOrNull() ?: 0
                    repository.actualizarStockProducto(codigo, stock)
                }
                Toast.makeText(this@PedidoPanificadoraActivity, "Stock guardado correctamente", Toast.LENGTH_SHORT).show()
                cargarDatos() // Recargar para mostrar los cambios
            }
            .setNegativeButton("Cancelar", null)
            .create()
        
        dialog.setOnShowListener {
            // Configurar fondo del dió¡logo con ColorDrawable directamente
            val window = dialog.window
            val dialogBackgroundColor = if (isDarkMode()) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
            
            // Configurar color del tó­tulo
            val titleView = dialog.findViewById<android.widget.TextView>(android.R.id.title)
            val titleTextColor = if (isDarkMode()) {
                android.graphics.Color.WHITE
            } else {
                android.graphics.Color.BLACK
            }
            titleView?.setTextColor(titleTextColor)
            
            // Asegurar que el ScrollView y Layout tambió©n tengan el fondo correcto
            scrollView.setBackgroundColor(dialogBackgroundColor)
            layout.setBackgroundColor(dialogBackgroundColor)
        }
        
        dialog.show()
    }
    
    /**
     * Muestra el cuadro de carga de stock con productos bó¡sicos cuando no hay datos de promedios
     */
    private fun mostrarCuadroConProductosBasicos() {
        println("ðŸ” DEBUG: Creando cuadro con productos bó¡sicos...")
        
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
        
           // Tó­tulo principal (igual que Ingreso de Mercaderó­a)
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
               text = "ðŸ’¡ Sugerencia de Pedido"
               textSize = 16f
               setPadding(16, 8, 16, 16)
               setTextColor(textColor)
               setTypeface(null, android.graphics.Typeface.BOLD)
               gravity = android.view.Gravity.CENTER
           }
           layout.addView(txtSugerencia)
        
        // SOLO PANIFICADORA
        val ordenCategorias = listOf("Panificadora")
        
        // Orden especó­fico de productos dentro de cada categoró­a - SOLO PANIFICADORA
        val ordenProductos = mapOf(
            "Panificadora" to listOf("MEDIALUNA_MANTECA", "MEDIALUNA_GRASA", "CHIPA", "CRIOLLITO")
        )

        val editTexts = mutableMapOf<String, EditText>()

        // Mostrar cada categoró­a en el orden especó­fico (igual que CargaActivity)
        for (categoria in ordenCategorias) {
            val productosCategoria = ordenProductos[categoria] ?: emptyList()
            if (productosCategoria.isNotEmpty()) {
                mostrarCategoriaConSugerencia(categoria, productosCategoria, editTexts, layout)
            }
        }
        
        scrollView.addView(layout)
        scrollView.setBackgroundColor(backgroundColor)
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("ðŸ“Š CARGAR STOCK")
            .setView(scrollView)
            .setPositiveButton("ðŸ’¾ PREVISUALIZAR Y GUARDAR") { _, _ ->
                // Guardar los valores ingresados
                editTexts.forEach { (codigo, editText) ->
                    val stock = editText.text.toString().toIntOrNull() ?: 0
                    repository.actualizarStockProducto(codigo, stock)
                }
                Toast.makeText(this@PedidoPanificadoraActivity, "Stock guardado correctamente", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .create()
        
        dialog.setOnShowListener {
            // Configurar fondo del dió¡logo con ColorDrawable directamente
            val window = dialog.window
            val dialogBackgroundColor = if (isDarkMode()) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
            
            // Configurar color del tó­tulo
            val titleView = dialog.findViewById<android.widget.TextView>(android.R.id.title)
            val titleTextColor = if (isDarkMode()) {
                android.graphics.Color.WHITE
            } else {
                android.graphics.Color.BLACK
            }
            titleView?.setTextColor(titleTextColor)
            
            // Asegurar que el ScrollView y Layout tambió©n tengan el fondo correcto
            scrollView.setBackgroundColor(dialogBackgroundColor)
            layout.setBackgroundColor(dialogBackgroundColor)
        }
        
        dialog.show()
    }
    
    /**
     * Abre la secció³n de carga de stock 15:30 integrada en esta pantalla
     */
    private fun abrirSeccionCargarStock() {
        Log.d("PedidoPanificadoraActivity", "ðŸ“Š Mostrando secció³n de carga de stock 15:30 integrada...")
        
        // Mostrar la secció³n de carga de stock integrada en esta pantalla
        mostrarSeccionCargarStockIntegrada()
    }
    
    /**
     * Muestra la secció³n de carga de stock 15:30 integrada en la pantalla actual
     */
    private fun mostrarSeccionCargarStockIntegrada() {
        // Obtener el layout de contenido
        val layoutContenido = findViewById<LinearLayout>(R.id.layoutContenido)
        layoutContenido?.removeAllViews()
        
        // Crear la secció³n de carga de stock (sin padding superior)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 16, 16)  // Top padding = 0
            setBackgroundColor(if (isDarkMode()) resources.getColor(android.R.color.black, null) else resources.getColor(android.R.color.white, null))
        }
        
        // Leyenda de ó­conos ULTRA compacta
        val leyendaTendencias = TextView(this).apply {
            text = "ðŸ“ˆâ‰¥10% | â¬†ï¸5-10% | ðŸ“‰â‰¤-10% | â¬‡ï¸-10 a -5%"  // En una ló­nea
            textSize = 9f
            setPadding(8, 2, 8, 4)  // Mó­nimo padding
            setTextColor(getThemeColor(android.R.attr.textColorSecondary))
            gravity = android.view.Gravity.CENTER
        }
        layout.addView(leyendaTendencias)
        
        // Usar el mismo formato que CargaActivity
        // SOLO PANIFICADORA
        val ordenCategorias = listOf("Panificadora")
        val ordenProductos = mapOf(
            "Panificadora" to listOf("MEDIALUNA_MANTECA", "MEDIALUNA_GRASA", "CHIPA", "CRIOLLITO")
        )
        
        val editTexts = mutableMapOf<String, EditText>()
        
        // ðŸ“ˆ Calcular tendencia general de ventas (promedio de productos principales)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val productosParaTendencia = listOf("MEDIALUNA_MANTECA", "MEDIALUNA_GRASA", "CHIPA") // Productos principales
                val tendencias = productosParaTendencia.mapNotNull { codigo ->
                    try {
                        val t = detectarTendenciaVentas(codigo)
                        Log.d("TENDENCIA_UI", "  Producto $codigo: ${String.format("%.1f", t)}%")
                        if (t != 0.0) t else null // Solo contar si es significativa
                    } catch (e: Exception) {
                        null
                    }
                }
                
                Log.d("TENDENCIA_UI", "  Tendencias vó¡lidas encontradas: ${tendencias.size}")
                tendencias.forEachIndexed { i, t -> 
                    Log.d("TENDENCIA_UI", "    [$i] = ${String.format("%.1f", t)}%")
                }
                
                val tendenciaGeneral = if (tendencias.isNotEmpty()) {
                    tendencias.average()
                } else {
                    0.0
                }
                
                Log.d("TENDENCIA_UI", "ðŸ“Š Tendencia general calculada: ${String.format("%.1f", tendenciaGeneral)}% (de ${tendencias.size} productos)")
                
                // Actualizar UI en el hilo principal
                withContext(Dispatchers.Main) {
                    if (kotlin.math.abs(tendenciaGeneral) >= 5.0) {
                        val icono = if (tendenciaGeneral > 0) "ðŸ“ˆ" else "ðŸ“‰"
                        val texto = if (tendenciaGeneral > 0) "AUMENTO" else "DISMINUCIó“N"
                        val color = if (tendenciaGeneral > 0) {
                            resources.getColor(android.R.color.holo_green_dark, null)
                        } else {
                            resources.getColor(android.R.color.holo_orange_dark, null)
                        }
                        
                        txtTendenciaVentas.text = "$icono $texto DE VENTAS: ${String.format("%.0f", kotlin.math.abs(tendenciaGeneral))}%"
                        txtTendenciaVentas.setBackgroundColor(color)
                        txtTendenciaVentas.setTextColor(resources.getColor(android.R.color.white, null))
                        txtTendenciaVentas.visibility = View.VISIBLE
                        
                        Log.d("TENDENCIA_UI", "âœ… Indicador de tendencia mostrado: $tendenciaGeneral%")
                    } else {
                        txtTendenciaVentas.visibility = View.GONE
                        Log.d("TENDENCIA_UI", "â„¹ï¸ Tendencia no significativa, indicador oculto")
                    }
                }
            } catch (e: Exception) {
                Log.e("TENDENCIA_UI", "Error calculando tendencia general: ${e.message}")
            }
        }
        
        // Mostrar cada categoró­a
        for (categoria in ordenCategorias) {
            val productosCategoria = ordenProductos[categoria] ?: emptyList()
            if (productosCategoria.isNotEmpty()) {
                mostrarCategoriaIntegrada(categoria, productosCategoria, editTexts, layout)
            }
        }
        
        // Agregar botó³n de WhatsApp
        val btnWhatsApp = Button(this).apply {
            text = when {
                modoAdmin -> "ðŸ“± Enviar por WhatsApp (Admin)"
                modoSoloLectura -> "ðŸ“± Ver Pedido (Solo Lectura)"
                else -> "ðŸ“± Enviar por WhatsApp"
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
    }
    
    /**
     * Muestra una categoró­a integrada en la pantalla actual
     */
    private fun mostrarCategoriaIntegrada(categoria: String, productos: List<String>, editTexts: MutableMap<String, EditText>, layout: LinearLayout) {
        // Crear contenedor de categoró­a (igual que CargaActivity)
        val categoriaLayout = LinearLayout(this)
        categoriaLayout.orientation = LinearLayout.VERTICAL
        categoriaLayout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        categoriaLayout.setPadding(0, 16, 0, 8)
        
        // Header de categoró­a con color (igual que CargaActivity)
        val headerLayout = LinearLayout(this)
        headerLayout.orientation = LinearLayout.HORIZONTAL
        headerLayout.setPadding(12, 12, 12, 12)
        headerLayout.gravity = android.view.Gravity.CENTER_VERTICAL
        
        // Aplicar color segóºn categoró­a (igual que CargaActivity)
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
        
        // Agregar icono de categoró­a
        val iconoCategoria = TextView(this)
        iconoCategoria.text = when (categoria) {
            "Empanadas" -> "ðŸ¥Ÿ"
            "Pizzas" -> "ðŸ•"
            "Pastelitos" -> "ðŸ¥§"
            "Otros" -> "ðŸ¥"
            else -> "ðŸ“¦"
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
        
        // Contenedor de productos con fondo dinó¡mico
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
            android.graphics.Color.parseColor("#2D2D2D") // Gris mó¡s oscuro para items
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
            
            // Obtener el nombre del producto para mostrar
            val nombreProducto = obtenerNombreProducto(codigo)
            val nombreCorto = obtenerNombreCortoProducto(nombreProducto, codigo)
            
            // Configurar có³digo con nombre corto
            txtCodigo.text = nombreCorto
            
            // Variables para almacenar estado de iconos
            var tieneStockBajo = false
            var historialStockBajo: List<Pair<String, Int>> = emptyList()
            var tieneTendencia = false
            var tendenciaValor: Double = 0.0
            
            // Funció³n helper para actualizar el texto del có³digo con ambos iconos
            fun actualizarTextoConIconos() {
                var texto = nombreCorto
                
                // Agregar icono de stock bajo si existe
                if (tieneStockBajo) {
                    texto += " âš ï¸"
                }
                
                // Agregar icono de tendencia si existe
                if (tieneTendencia) {
                    val icono = when {
                        tendenciaValor >= 10.0 -> "ðŸ“ˆ"
                        tendenciaValor >= 5.0 -> "â¬†ï¸"
                        tendenciaValor <= -10.0 -> "ðŸ“‰"
                        else -> "â¬‡ï¸"
                    }
                    val porcentaje = String.format("%.0f", kotlin.math.abs(tendenciaValor))
                    texto += " $icono$porcentaje%"
                }
                
                txtCodigo.text = texto
                
                // Configurar click listener - cada icono es independiente
                // Si hay ambos iconos, el click en el có³digo muestra stock bajo (mó¡s cró­tico)
                // La tendencia se puede ver haciendo click directamente en el icono de tendencia
                if (tieneStockBajo) {
                    txtCodigo.setOnClickListener {
                        mostrarReporteStockBajo(codigo, historialStockBajo)
                    }
                } else if (tieneTendencia) {
                    txtCodigo.setOnClickListener {
                        mostrarGraficoConLoading(codigo, tendenciaValor)
                    }
                }
            }
            
            // Verificar si hay stock bajo en los óºltimos 7 dó­as
            lifecycleScope.launch {
                historialStockBajo = obtenerHistorialStockBajo(codigo)
                tieneStockBajo = historialStockBajo.isNotEmpty()
                runOnUiThread {
                    actualizarTextoConIconos()
                }
            }
            
            // ðŸ“ˆ Agregar tendencia del cache si estó¡ disponible (instantó¡neo)
            val fechaHoy = DateHelper.getFechaActual()
            if (configuracion.usarAjusteTendencia &&
                MainActivity.cacheTendencias != null && 
                MainActivity.cacheTendenciasFecha == fechaHoy && 
                MainActivity.cacheTendenciasLocalId == localId) {
                
                tendenciaValor = MainActivity.cacheTendencias!![codigo] ?: 0.0
                tieneTendencia = kotlin.math.abs(tendenciaValor) >= 5.0
                
                if (tieneTendencia) {
                    actualizarTextoConIconos()
                    Log.d("TENDENCIA_CACHE", "âš¡ Tendencia desde cache: $codigo â†’ ${String.format("%.0f", kotlin.math.abs(tendenciaValor))}%")
                }
            }
            
            // Configurar valores iniciales
            val producto = this.productos.find { it.codigo == codigo }
            val stockActual = producto?.stockActual ?: 0
            
            // 💡 Recalcular la sugerencia dinámicamente si figura en 0
            val sugerenciaCalculada = calcularSugerenciaPedido(codigo, stockActual)
            val sugerenciaFinal = if (sugerenciaCalculada > 0) sugerenciaCalculada else (producto?.pedidoSugerido ?: 0)
            
            if (producto != null && sugerenciaCalculada > 0 && producto.pedidoSugerido != sugerenciaCalculada) {
                this.productos = this.productos.map { p -> if (p.codigo == codigo) p.copy(pedidoSugerido = sugerenciaCalculada) else p }
            }
            
            edtCantidad.setText(stockActual.toString())
            txtSugerencia.text = sugerenciaFinal.toString()
            edtPedido.setText((producto?.pedidoFinal ?: 0).toString())
            
            // Configurar tags para identificació³n
            edtCantidad.tag = "${codigo}_stock"
            edtPedido.tag = "${codigo}_pedido"
            txtSugerencia.tag = "${codigo}_sugerencia"
            
            // Configurar modo solo lectura si es necesario (ANTES de configurar selecció³n de texto)
            if (modoSoloLectura && !modoAdmin) {
                // Para usuarios normales en fechas histó³ricas: TODAS las columnas son solo lectura
                edtCantidad.isEnabled = false
                edtPedido.isEnabled = false
                edtCantidad.setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
                edtPedido.setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
            } else {
                // Solo configurar selecció³n automó¡tica de texto si NO es modo solo lectura
                configurarSeleccionTexto(edtCantidad)
                configurarSeleccionTexto(edtPedido)
            }
            
            // Configurar sugerencias automó¡ticas
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
        
        // Agregar listener para calcular sugerencia automó¡ticamente
        edtCantidad.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // Solo calcular si no estamos inicializando los productos
                if (!inicializandoProductos) {
                    val stock = s?.toString()?.toIntOrNull() ?: 0
                    Log.d("SUG", "ðŸ” SUG: Usuario modificó³ stock de $codigo a $stock (secció³n 2)")
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
     * Muestra una categoró­a con tres columnas: Stock, Sugerencia, Pedido Final
     */
    private fun mostrarCategoriaConSugerencia(categoria: String, productos: List<String>, editTexts: MutableMap<String, EditText>, layout: LinearLayout) {
        // Definir colores segóºn modo oscuro
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
            android.graphics.Color.parseColor("#2D2D2D") // Gris mó¡s oscuro para items
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
        
        // Crear contenedor de categoró­a (igual que CargaActivity)
        val categoriaLayout = LinearLayout(this)
        categoriaLayout.orientation = LinearLayout.VERTICAL
        categoriaLayout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        categoriaLayout.setPadding(0, 16, 0, 8)
        
        // Header de categoró­a con color (igual que CargaActivity)
        val headerLayout = LinearLayout(this)
        headerLayout.orientation = LinearLayout.HORIZONTAL
        headerLayout.setPadding(12, 12, 12, 12)
        headerLayout.gravity = android.view.Gravity.CENTER_VERTICAL
        
        // Aplicar color segóºn categoró­a (igual que CargaActivity)
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
        
        // Agregar icono de categoró­a
        val iconoCategoria = TextView(this)
        iconoCategoria.text = when (categoria) {
            "Empanadas" -> "ðŸ¥Ÿ"
            "Pizzas" -> "ðŸ•"
            "Pastelitos" -> "ðŸ¥§"
            "Otros" -> "ðŸ¥"
            else -> "ðŸ“¦"
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
        
        // Contenedor de productos con fondo dinó¡mico
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
            
            // Columna 1: Có³digo y Icono de Advertencia
            val columnaInfo = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            }
            
            // Obtener el nombre del producto para mostrar
            val nombreProducto = obtenerNombreProducto(codigo)
            val nombreCorto = obtenerNombreCortoProducto(nombreProducto, codigo)
            
            val txtCodigo = TextView(this).apply {
                text = nombreCorto
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            
            columnaInfo.addView(txtCodigo)
            
            // Verificar si hay stock bajo en los óºltimos 7 dó­as y agregar icono de advertencia
            lifecycleScope.launch {
                val historialStockBajo = obtenerHistorialStockBajo(codigo)
                if (historialStockBajo.isNotEmpty()) {
                    val iconoAdvertencia = TextView(this@PedidoPanificadoraActivity).apply {
                        text = "âš ï¸"
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
            
            // Configurar sugerencias automó¡ticas
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
            Log.e("PedidoPanificadoraActivity", "Error obteniendo sugerencia para $codigo: ${e.message}")
            return 0
        }
    }
    
    /**
     * Obtiene el nombre del producto basado en el có³digo
     */
    private fun obtenerNombreProducto(codigo: String): String {
        return when (codigo) {
            "JQ" -> "Jamó³n y Queso"
            "PB" -> "Pollo BBQ"
            "CS" -> "Carne Suave"
            "PO" -> "Pollo"
            "CP" -> "Carne Picante"
            "HU" -> "Humita"
            "ES" -> "Espinaca"
            "CQ" -> "Calabaza y Queso"
            "RJ" -> "Roquefort y Jamó³n"
            "QC" -> "Queso y Cebolla"
            "CB" -> "Cerdo y Barbacoa"
            "CH" -> "Cheeseburger"
            "MUZZARE" -> "Muzzarella"
            "JAMON" -> "Jamó³n"
            "PEPPERO" -> "Pepperoni"
            "BATATA" -> "Batata"
            "MEMBRIL" -> "Membrillo"
            "DULCES" -> "Dulces"
            "SALADO" -> "Salado"
            "MEDIALUNA_MANTECA" -> "Medialunas de Manteca"
            "MEDIALUNA_GRASA" -> "Medialunas de Grasa"
            "CHIPA" -> "Chipa"
            "CRIOLLITO" -> "Criollitos"
            else -> codigo
        }
    }
    
    /**
     * Obtiene el nombre corto del producto para mostrar en la UI
     * SOLO para visualizació³n: muestra "DULCE" o "SALADA" para medialunas
     * La bóºsqueda de promedios usa los nombres completos de Firebase ("DULCES - Dulces", "SALADO - Salado")
     */
    private fun obtenerNombreCortoProducto(nombreCompleto: String, codigo: String): String {
        // SOLO verificar por có³digo (mó¡s confiable y directo)
        val resultado = when (codigo) {
            "MEDIALUNA_MANTECA" -> "DULCE"
            "MEDIALUNA_GRASA" -> "SALADA"
            else -> nombreCompleto
        }
        Log.d("NOMBRE_CORTO", "ðŸ” obtenerNombreCortoProducto: codigo='$codigo', nombreCompleto='$nombreCompleto' -> resultado='$resultado'")
        return resultado
    }
    
    /**
     * Redondea la sugerencia segóºn el tipo de producto
     * Si la sugerencia es muy baja (menor al porcentaje configurado del móºltiplo), devuelve 0
     */
    private fun redondearSugerencia(sugerencia: Double, codigo: String): Int {
        // Si la sugerencia es 0 o negativa, devolver 0
        if (sugerencia <= 0) return 0
        
        // Usar el porcentaje extra configurado dinó¡micamente
        val porcentajeMultiplo = porcentajeSeleccionado / 100.0 // Convertir porcentaje a decimal
        
        return when {
            // Empanadas: redondear a móºltiplo de 50
            codigo in listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH") -> {
                val multiplo = 50
                val umbral = multiplo * porcentajeMultiplo // Porcentaje configurado del móºltiplo
                if (sugerencia < umbral) 0 else ((sugerencia / multiplo).toInt() + 1) * multiplo
            }
            // Pizzas: redondear a móºltiplo de 5
            codigo in listOf("MUZZARE", "JAMON", "PEPPERO") -> {
                val multiplo = 5
                val umbral = multiplo * porcentajeMultiplo // Porcentaje configurado del móºltiplo
                if (sugerencia < umbral) 0 else {
                    val cociente = sugerencia / multiplo
                    val resto = sugerencia % multiplo
                    if (resto > 0) (cociente.toInt() + 1) * multiplo else sugerencia.toInt()
                }
            }
            // Pastelitos: redondear a móºltiplo de 35
            codigo in listOf("BATATA", "MEMBRIL") -> {
                val multiplo = 35
                val umbral = multiplo * porcentajeMultiplo // Porcentaje configurado del móºltiplo
                if (sugerencia < umbral) 0 else ((sugerencia / multiplo).toInt() + 1) * multiplo
            }
            // Medialunas de Manteca: redondear a móºltiplo de 180
            codigo == "MEDIALUNA_MANTECA" -> {
                val multiplo = 180
                val umbral = multiplo * porcentajeMultiplo // Porcentaje configurado del móºltiplo
                if (sugerencia < umbral) 0 else ((sugerencia / multiplo).toInt() + 1) * multiplo
            }
            // Medialunas de Grasa: redondear a móºltiplo de 180
            codigo == "MEDIALUNA_GRASA" -> {
                val multiplo = 180
                val umbral = multiplo * porcentajeMultiplo // Porcentaje configurado del móºltiplo
                if (sugerencia < umbral) 0 else ((sugerencia / multiplo).toInt() + 1) * multiplo
            }
            // Chipa: redondear a móºltiplo de 120
            codigo == "CHIPA" -> {
                val multiplo = 120
                val umbral = multiplo * porcentajeMultiplo // Porcentaje configurado del móºltiplo
                if (sugerencia < umbral) 0 else ((sugerencia / multiplo).toInt() + 1) * multiplo
            }
            // Criollito: redondear a móºltiplo de 162 (bolsa de 162 unidades)
            codigo == "CRIOLLITO" -> {
                val multiplo = 162
                val umbral = multiplo * porcentajeMultiplo // Porcentaje configurado del móºltiplo
                if (sugerencia < umbral) 0 else ((sugerencia / multiplo).toInt() + 1) * multiplo
            }
            // Por defecto: redondear a móºltiplo de 10
            else -> {
                val multiplo = 10
                val umbral = multiplo * porcentajeMultiplo // Porcentaje configurado del móºltiplo
                if (sugerencia < umbral) 0 else ((sugerencia / multiplo).toInt() + 1) * multiplo
            }
        }
    }
    
    /**
     * Calcula la sugerencia de pedido basada en la ló³gica real de negocio
     * Basado en la fó³rmula de Excel proporcionada
     */
    /**
     * Obtiene el pró³ximo dó­a hó¡bil (dó­a con entrega) a partir de una fecha
     * Si hoy es dó­a hó¡bil, devuelve hoy. Si no, busca el pró³ximo dó­a hó¡bil.
     */
    private fun obtenerProximoDiaHabil(fechaActual: Calendar): Calendar {
        // Primero verificar si HOY es dó­a hó¡bil
        if (esDiaConEntrega(fechaActual)) {
            Log.d("PedidoPanificadoraActivity", "âœ… Hoy es dó­a hó¡bil, usando fecha actual")
            return fechaActual
        }
        
        // Si hoy no es dó­a hó¡bil, buscar el pró³ximo dó­a hó¡bil
        val proximoDia = fechaActual.clone() as Calendar
        proximoDia.add(Calendar.DAY_OF_MONTH, 1)
        
        while (!esDiaConEntrega(proximoDia)) {
            proximoDia.add(Calendar.DAY_OF_MONTH, 1)
        }
        
        Log.d("PedidoPanificadoraActivity", "ðŸ“… Hoy no es dó­a hó¡bil, pró³ximo dó­a hó¡bil: ${proximoDia.get(Calendar.DAY_OF_WEEK)}")
        return proximoDia
    }
    
    /**
     * Verifica si un dó­a especó­fico tiene entrega segóºn la configuració³n
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
     * Recalcula todas las sugerencias cuando cambia la configuració³n de dó­as de entrega
     */
    private fun recalcularTodasLasSugerencias() {
        Log.d("PedidoPanificadoraActivity", "ðŸ”„ Recalculando todas las sugerencias...")
        
        // Obtener todos los productos y recalcular sus sugerencias
        productos.forEach { producto ->
            val nuevaSugerencia = calcularSugerenciaPedido(producto.codigo, producto.stockActual)
            Log.d("PedidoPanificadoraActivity", "ðŸ”„ Producto ${producto.codigo}: Nueva sugerencia = $nuevaSugerencia")
            
            // Actualizar el producto con la nueva sugerencia
            repository.actualizarPedidoFinal(producto.codigo, nuevaSugerencia)
            
            // CORRECCIó“N: Actualizar directamente el TextView de sugerencias en la interfaz
            txtSugerencias[producto.codigo]?.text = "ðŸ’¡ Pedido sugerido: $nuevaSugerencia unidades"
            Log.d("PedidoPanificadoraActivity", "ðŸ”„ Actualizado TextView para ${producto.codigo}: $nuevaSugerencia unidades")
        }
        
        Log.d("PedidoPanificadoraActivity", "âœ… Todas las sugerencias recalculadas y actualizadas en la interfaz")
    }
    
    /**
     * Calcula la suma de promedios de venta de los dó­as sin entrega
     * Verifica los pró³ximos 2-3 dó­as para ver si hay dó­as desmarcados
     */
    private fun calcularPromedioDiasSinEntrega(codigo: String): Double {
        val fechaActual = DateHelper.getCalendar()
        val diaSemana = fechaActual.get(Calendar.DAY_OF_WEEK)
        var sumaPromedios = 0.0
        
        
        // Ló“GICA CORRECTA: Buscar dó­as SIN entrega despuó©s del dó­a de entrega normal
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
        
        
        // SIEMPRE buscar dó­as adicionales sin entrega (despuó©s del dó­a de entrega normal)
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
        
        
        // Sumar todos los dó­as consecutivos sin entrega
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
            
            val nombreDia = when (dia) {
                Calendar.MONDAY -> "LUNES"
                Calendar.TUESDAY -> "MARTES"
                Calendar.WEDNESDAY -> "MIERCOLES"
                Calendar.THURSDAY -> "JUEVES"
                Calendar.FRIDAY -> "VIERNES"
                Calendar.SATURDAY -> "SABADO"
                Calendar.SUNDAY -> "DOMINGO"
                else -> "DESCONOCIDO"
            }
            
            
            if (!tieneEntrega) {
                val promedio = obtenerPromedioPorDiaSemana(codigo, dia)
                sumaPromedios += promedio
            } else {
                // NO parar aquó­, continuar buscando dó­as sin entrega
            }
        }
        
        return sumaPromedios
    }
    
    /**
     * Obtiene el promedio de venta para un dó­a especó­fico de la semana
     * Usa los datos reales de promedios de la pó¡gina principal
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
        
        Log.d("SUG", "ðŸ” SUG: Obteniendo promedio para $codigo en $diaSemanaString")
        
        // Obtener el nombre del producto desde el có³digo
        val nombreProducto = obtenerNombreProducto(codigo)
        Log.d("SUG", "ðŸ” SUG: Nombre producto: $nombreProducto")
        
        // Buscar en los promedios reales
        val promediosReales = MainActivity.promediosReales
        Log.d("SUG", "ðŸ” SUG: Promedios reales disponibles: ${promediosReales.isNotEmpty()}")
        
        if (promediosReales.isNotEmpty()) {
            val datosDia = promediosReales[diaSemanaString]
            Log.d("SUG", "ðŸ” SUG: Datos para $diaSemanaString: ${datosDia != null}")
            
            if (datosDia != null) {
                Log.d("SUG", "ðŸ” SUG: Productos disponibles en $diaSemanaString: ${datosDia.keys}")
                
                // Buscar la clave completa "Có“DIGO - Nombre"
                val claveCompleta = "$codigo - $nombreProducto"
                var promedio = datosDia[claveCompleta] ?: 0.0
                Log.d("SUG", "ðŸ” SUG: Buscando clave completa '$claveCompleta': $promedio")
                
                // Si no se encuentra, intentar con nombre del producto
                if (promedio == 0.0) {
                    promedio = datosDia[nombreProducto] ?: 0.0
                    Log.d("SUG", "ðŸ” SUG: Buscando por nombre '$nombreProducto': $promedio")
                }
                
                // Si no se encuentra, intentar con el có³digo directamente
                if (promedio == 0.0) {
                    promedio = datosDia[codigo] ?: 0.0
                    Log.d("SUG", "ðŸ” SUG: Buscando por có³digo '$codigo': $promedio")
                }
                
                // ðŸ”§ ESPECIAL: MEDIALUNA_MANTECA debe buscar "DULCES - Dulces" en Firebase
                if (promedio == 0.0 && codigo == "MEDIALUNA_MANTECA") {
                    // Buscar variantes de "DULCES"
                    for ((key, value) in datosDia) {
                        if (key.startsWith("DULCES", ignoreCase = true) || 
                            (key.contains("Dulce", ignoreCase = true) && key.contains("DULCES", ignoreCase = true)) ||
                            key.contains("Dulce", ignoreCase = true)) {
                            promedio = value
                            Log.d("SUG", "ðŸ” SUG: MEDIALUNA_MANTECA encontrado por clave DULCES: '$key' = $value")
                            break
                        }
                    }
                }
                
                // ðŸ”§ ESPECIAL: MEDIALUNA_GRASA debe buscar "SALADO - Salado" en Firebase
                if (promedio == 0.0 && codigo == "MEDIALUNA_GRASA") {
                    // Buscar variantes de "SALADO"
                    for ((key, value) in datosDia) {
                        if (key.startsWith("SALADO", ignoreCase = true) || 
                            (key.contains("Salado", ignoreCase = true) && key.contains("SALADO", ignoreCase = true)) ||
                            key.contains("Salado", ignoreCase = true)) {
                            promedio = value
                            Log.d("SUG", "ðŸ” SUG: MEDIALUNA_GRASA encontrado por clave SALADO: '$key' = $value")
                            break
                        }
                    }
                }
                
                Log.d("SUG", "âœ… SUG: Promedio encontrado: $promedio")
                return promedio
            } else {
                Log.w("SUG", "âš ï¸ SUG: No hay datos para $diaSemanaString")
            }
        } else {
            Log.w("SUG", "âš ï¸ SUG: No hay promedios reales cargados")
        }
        
        Log.w("SUG", "âš ï¸ SUG: No se encontró³ promedio para $codigo en $diaSemanaString")
        return 0.0
    }
    
    
    /**
     * ðŸ“ˆ Obtiene historial de ventas vs promedio para mostrar en gró¡fico
     * Retorna lista de (fecha, ventaReal, promedioHistorico)
     */
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
                        // Obtener dó­a de la semana
                        val cal = DateHelper.getCalendar()
                        cal.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(fecha) ?: continue
                        val diaSemana = cal.get(Calendar.DAY_OF_WEEK)
                        
                        // Obtener promedio histó³rico
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
                                    .collection("registros").document("${fecha}_Ingreso_de_Mercaderó­a")
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
                            .filter { it.fecha == fecha && it.tipo == "Ingreso de Mercaderó­a" }
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
                
                historial.reversed() // Del mó¡s antiguo al mó¡s reciente
                
            } catch (e: Exception) {
                Log.e("TENDENCIA", "Error obteniendo historial: ${e.message}")
                emptyList()
            }
        }
    }
    
    /**
     * ðŸ“¥ Carga el cache completo de tendencias desde Firebase (una sola vez al inicio)
     * Usa documento óºnico "tendencias" que se sobrescribe (sin fecha)
     */
    private fun cargarCacheTendenciasDesdeFirebase() {
        // Si ya estó¡ cargado para este local, no hacer nada
        if (MainActivity.cacheTendencias != null && 
            MainActivity.cacheTendenciasLocalId == localId) {
            Log.d("TENDENCIA", "âœ… Cache ya disponible en memoria (${MainActivity.cacheTendencias!!.size} productos)")
            return
        }
        
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                Log.d("TENDENCIA", "ðŸ“¥ Cargando cache desde Firebase (documento óºnico)...")
                val firestore = FirebaseRegionManager.getFirestore()
                val docTendencias = firestore
                    .collection("locales")
                    .document(localId)
                    .collection("cache_tendencias")
                    .document("tendencias")  // Documento óºnico
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
                            Log.d("TENDENCIA", "âœ… Cache cargado desde Firebase: ${tendenciasGuardadas.size} productos")
                            Log.d("TENDENCIA", "   Productos con tendencia: ${tendenciasGuardadas.filter { it.value != 0.0 }.size}")
                        } else {
                            Log.d("TENDENCIA", "âš ï¸ Documento existe pero no tiene tendencias vó¡lidas")
                        }
                    } else {
                        Log.d("TENDENCIA", "âš ï¸ Documento existe pero estó¡ vacó­o")
                    }
                } else {
                    Log.d("TENDENCIA", "â„¹ï¸ No hay cache en Firebase (aóºn no se cargó³ Stock Final)")
                }
            } catch (e: Exception) {
                Log.e("TENDENCIA", "âŒ Error cargando cache desde Firebase: ${e.message}")
            }
        }
    }
    
    /**
     * ðŸ“Š Muestra el gró¡fico con un loading mientras carga los datos
     */
    private fun mostrarGraficoConLoading(codigo: String, tendencia: Double) {
        // Crear dió¡logo de loading
        val loadingDialog = android.app.ProgressDialog(this).apply {
            setMessage("Cargando datos del gró¡fico...")
            setCancelable(false)
            show()
        }
        
        lifecycleScope.launch {
            try {
                Log.d("GRAFICO", "ðŸ“¥ Cargando historial para $codigo...")
                
                // Cargar solo los datos necesarios para este producto
                val historial = obtenerHistorialVentasTendenciaOptimizado(codigo)
                
                runOnUiThread {
                    loadingDialog.dismiss()
                    
                    if (historial.isEmpty()) {
                        android.widget.Toast.makeText(
                            this@PedidoPanificadoraActivity,
                            "No hay suficientes datos para mostrar el gró¡fico de $codigo",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Log.d("GRAFICO", "âœ… Historial cargado: ${historial.size} dó­as")
                        mostrarGraficoTendencia(codigo, tendencia, historial)
                    }
                }
            } catch (e: Exception) {
                Log.e("GRAFICO", "âŒ Error cargando gró¡fico: ${e.message}")
                runOnUiThread {
                    loadingDialog.dismiss()
                    android.widget.Toast.makeText(
                        this@PedidoPanificadoraActivity,
                        "Error cargando gró¡fico: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    /**
     * ðŸš€ OPTIMIZADO: Obtiene el historial de ventas cargando SOLO los datos necesarios
     * Solo carga los óºltimos 7 dó­as de este producto especó­fico
     */
    private suspend fun obtenerHistorialVentasTendenciaOptimizado(codigo: String): List<Triple<String, Double, Double>> {
        return withContext(Dispatchers.IO) {
            try {
                val nombreProducto = obtenerNombreProducto(codigo)
                val fechaHoy = DateHelper.getFechaActual()
                val calendar = DateHelper.getCalendar()
                val historial = mutableListOf<Triple<String, Double, Double>>()
                val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
                
                Log.d("GRAFICO_OPT", "ðŸ“Š Cargando óºltimos 7 dó­as para $codigo ($nombreProducto)")
                
                // Cargar los óºltimos 7 dó­as
                for (i in 1..7) {
                    try {
                        calendar.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaHoy) ?: Date()
                        calendar.add(Calendar.DAY_OF_YEAR, -i)
                        val fecha = DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
                        val fechaAnterior = obtenerFechaAnterior(fecha)
                        
                        Log.d("GRAFICO_OPT", "  ðŸ“… Procesando dó­a: $fecha")
                        
                        // Cargar solo los 3 documentos necesarios para este dó­a
                        val stockAnteriorDoc = firestore
                            .collection("locales").document(localId)
                            .collection("registros").document("${fechaAnterior}_Stock_Final")
                            .get().await()
                        
                        val ingresoDoc = firestore
                            .collection("locales").document(localId)
                            .collection("registros").document("${fecha}_Ingreso_de_Mercaderó­a")
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
                            // Calcular promedio histó³rico solo para este dó­a
                            val cal = DateHelper.getCalendar()
                            cal.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(fecha) ?: continue
                            val diaSemana = cal.get(Calendar.DAY_OF_WEEK)
                            val promedioHistorico = obtenerPromedioPorDiaSemana(codigo, diaSemana)
                            
                            historial.add(Triple(fecha, ventaReal, promedioHistorico))
                            Log.d("GRAFICO_OPT", "    âœ… $fecha: venta=$ventaReal, promedio=$promedioHistorico")
                        } else {
                            Log.d("GRAFICO_OPT", "    â­ï¸ $fecha: sin datos de venta")
                        }
                        
                    } catch (e: Exception) {
                        Log.w("GRAFICO_OPT", "    âš ï¸ Error en dó­a: ${e.message}")
                    }
                }
                
                Log.d("GRAFICO_OPT", "âœ… Historial completo: ${historial.size} dó­as con datos")
                historial.reversed() // Del mó¡s antiguo al mó¡s reciente
                
            } catch (e: Exception) {
                Log.e("GRAFICO_OPT", "âŒ Error obteniendo historial: ${e.message}")
                emptyList()
            }
        }
    }
    
    /**
     * ðŸ“ˆ Detecta tendencia de ventas
     * SOLO lee del cache (ya no calcula en tiempo real)
     */
    private fun detectarTendenciaVentas(codigo: String): Double {
        return try {
            // Solo leer del cache (documento óºnico, sin verificar fecha)
            if (MainActivity.cacheTendencias != null && 
                MainActivity.cacheTendenciasLocalId == localId) {
                
                val tendenciaCache = MainActivity.cacheTendencias!![codigo]
                if (tendenciaCache != null) {
                    Log.d("TENDENCIA", "âš¡ $codigo: ${String.format("%.1f", tendenciaCache)}% (desde cache)")
                    return tendenciaCache
                }
            }
            
            // Si no hay cache, retornar 0
            0.0
            
        } catch (e: Exception) {
            0.0
        }
    }
    
    /**
     * Calcula la venta real de un dó­a especó­fico
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
                    
                    // Obtener Stock Final del dó­a anterior
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
                    
                    // Obtener Ingreso del dó­a actual
                    val docIngreso = firestore
                        .collection("locales")
                        .document(localId)
                        .collection("registros")
                        .document("${fecha}_Ingreso_de_Mercaderó­a")
                        .get()
                        .await()
                    
                    if (docIngreso.exists()) {
                        @Suppress("DEPRECATION")
                        docIngreso.toObject(RegistroInventario::class.java)?.let { registrosList.add(it) }
                    }
                    
                    // Obtener Stock Final del dó­a actual
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
                .filter { it.fecha == fecha && it.tipo == "Ingreso de Mercaderó­a" }
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
     * ðŸ“ˆ Actualiza el indicador visual de tendencia de ventas
     * Calcula la tendencia promedio de TODOS los productos y la muestra
     */
    private fun actualizarIndicadorTendencia() {
        try {
            if (!configuracion.usarAjusteTendencia) {
                txtTendenciaVentas.visibility = View.GONE
                return
            }
            // Solo mostrar para fecha actual (no para histó³ricos)
            if (modoAdmin && fechaSeleccionada != DateHelper.getDateFormat("yyyy-MM-dd").format(Date())) {
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
            
            // Mostrar indicador con color segóºn tendencia
            val simbolo = if (tendenciaPromedio > 0) "ðŸ“ˆ" else "ðŸ“‰"
            val palabra = if (tendenciaPromedio > 0) "AUMENTO" else "DISMINUCIó“N"
            val mensaje = "$simbolo $palabra DE VENTAS: ${String.format("%.0f", kotlin.math.abs(tendenciaPromedio))}%"
            
            txtTendenciaVentas.text = mensaje
            txtTendenciaVentas.visibility = View.VISIBLE
            
            // Color segóºn tendencia
            val color = if (tendenciaPromedio > 0) {
                android.graphics.Color.parseColor("#4CAF50") // Verde para aumento
            } else {
                android.graphics.Color.parseColor("#FF9800") // Naranja para disminució³n
            }
            txtTendenciaVentas.setTextColor(color)
            
            Log.d("TENDENCIA", "âœ… Indicador actualizado: $mensaje")
            
        } catch (e: Exception) {
            Log.e("TENDENCIA", "Error actualizando indicador: ${e.message}")
            txtTendenciaVentas.visibility = View.GONE
        }
    }
    
    private fun calcularSugerenciaPedido(codigo: String, stockActual: Int): Int {
        try {
            val promedioHoy = obtenerPromedioDiarioHoy(codigo)
            
            if (promedioHoy <= 0) {
                return 0
            }

            // ðŸ”¥ NUEVA Ló“GICA: Calcular ventas reales del dó­a hasta ahora
            val (stockAnterior, ingresoHoy) = obtenerDatosVentaParcial(codigo)
            
            // ðŸ”¥ FIX: Calcular venta restante segóºn si hay datos histó³ricos o no
            val ventaRestanteHoy: Double
            val ventaParcial: Double
            
            if (stockAnterior > 0 || ingresoHoy > 0) {
                // HAY datos histó³ricos - usar ló³gica completa
                ventaParcial = (stockAnterior + ingresoHoy - stockActual).toDouble()
                
                val porcentajeAvance = if (promedioHoy > 0) {
                    minOf(1.0, maxOf(0.0, ventaParcial / promedioHoy))
                } else {
                    0.0
                }
                
                ventaRestanteHoy = promedioHoy * (1 - porcentajeAvance)
            } else {
                // NO hay datos histó³ricos - usar stock actual como referencia
                ventaParcial = maxOf(0.0, (promedioHoy - stockActual))
                ventaRestanteHoy = maxOf(0.0, (promedioHoy - stockActual))
            }

            // ðŸ“ˆ NUEVO: Detectar tendencia de ventas (aumento/disminució³n)
            val tendenciaVentas = if (configuracion.usarAjusteTendencia) detectarTendenciaVentas(codigo) else 0.0
            
            // Porcentaje extra configurado
            val porcentajeExtra = obtenerPorcentajeExtraConfigurado()

            // ðŸ”¹ Nueva ló³gica: sumar todos los promedios hasta la pró³xima entrega (maó±ana incluido)
            var sumaPromedios = obtenerPromediosHastaProximaEntrega(codigo)
            
            // ðŸ”¥ NUEVO: Aplicar ajuste de tendencia a los promedios
            if (tendenciaVentas != 0.0) {
                val factorTendencia = 1.0 + (tendenciaVentas / 100.0)
                sumaPromedios = sumaPromedios * factorTendencia
            }

            // ðŸ”¥ Ló“GICA CORRECTA: Calcular stock al final de hoy y restar de lo que necesito
            val stockFinalHoy = stockActual - ventaRestanteHoy

            // Fó³rmula correcta: necesito cubrir los pró³ximos dó­as MENOS lo que me va a quedar hoy
            val porcentajeExtraDecimal = porcentajeExtra / 100.0
            val porcentajeExtraValor = sumaPromedios * porcentajeExtraDecimal
            val sugerenciaBase = sumaPromedios - stockFinalHoy  // âœ… CORRECTO: Resto el stock que me va a quedar
            val sugerenciaFinal = sugerenciaBase + porcentajeExtraValor

            // Evitar negativos
            val sugerenciaPositiva = maxOf(0.0, sugerenciaFinal)

            // Redondear segóºn tipo de producto
            val resultado = redondearSugerencia(sugerenciaPositiva, codigo)

            return resultado

        } catch (e: Exception) {
            Log.e("SUG", "âŒ SUG: Error calculando sugerencia para $codigo: ${e.message}")
            e.printStackTrace()
            return 0
        }
    }
    
    // Cache de registros para evitar móºltiples consultas
    private var cacheRegistros: List<RegistroInventario>? = null
    private var cacheFechaRegistros: String? = null
    
    /**
     * Invalida el cache de registros (óºtil cuando se actualizan datos)
     */
    private fun invalidarCacheRegistros() {
        cacheRegistros = null
        cacheFechaRegistros = null
        Log.d("SUG", "ðŸ—‘ï¸ SUG: Cache de registros invalidado")
    }
    
    /**
     * Obtiene los datos necesarios para calcular la venta parcial del dó­a
     * Retorna un par (stockAnterior, ingresoHoy)
     */
    private fun obtenerDatosVentaParcial(codigo: String): Pair<Int, Int> {
        return try {
            val localId = obtenerLocalId()
            val nombreProducto = obtenerNombreProducto(codigo)
            
            // Obtener la fecha actual (usar fechaSeleccionada si estamos en modo admin o fecha histó³rica)
            val fechaHoy = if (modoAdmin || modoSoloLectura) {
                fechaSeleccionada
            } else {
                val timeZoneHoy = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
                val sdfHoy = DateHelper.getDateFormat("yyyy-MM-dd")
                sdfHoy.timeZone = timeZoneHoy
                sdfHoy.format(Date())
            }
            
            val fechaAnterior = obtenerFechaAnterior(fechaHoy)
            
            Log.d("SUG", "ðŸ” SUG: Obteniendo datos venta parcial para $codigo ($nombreProducto)")
            Log.d("SUG", "ðŸ” SUG: Fecha hoy: $fechaHoy, Fecha anterior: $fechaAnterior")
            
            // Intentar usar cache primero
            var registros = cacheRegistros
            if (registros == null || cacheFechaRegistros != fechaHoy) {
                // Obtener registros de forma só­ncrona (usando runBlocking con Dispatcher.IO para no bloquear UI)
                registros = runBlocking(Dispatchers.IO) {
                    localRepository.getAllRegistros(localId).first()
                }
                cacheRegistros = registros
                cacheFechaRegistros = fechaHoy
            }
            
            // Obtener stock anterior (stock final del dó­a anterior)
            val stockAnteriorRegistros = registros
                .filter { it.fecha == fechaAnterior && it.tipo == "Stock Final" }
                .flatMap { it.productos }
            
            val stockAnterior = stockAnteriorRegistros
                .find { producto -> 
                    producto.nombre == nombreProducto || 
                    producto.nombre.startsWith("$codigo - ") ||
                    producto.nombre == codigo
                }?.cantidad ?: 0
            
            // Obtener ingreso del dó­a actual
            val ingresoHoyRegistros = registros
                .filter { it.fecha == fechaHoy && it.tipo == "Ingreso de Mercaderó­a" }
                .flatMap { it.productos }
            
            val ingresoHoy = ingresoHoyRegistros
                .find { producto -> 
                    producto.nombre == nombreProducto || 
                    producto.nombre.startsWith("$codigo - ") ||
                    producto.nombre == codigo
                }?.cantidad ?: 0
            
            Log.d("SUG", "ðŸ” SUG: Stock anterior encontrado: $stockAnterior")
            Log.d("SUG", "ðŸ” SUG: Ingreso hoy encontrado: $ingresoHoy")
            
            Pair(stockAnterior, ingresoHoy)
            
        } catch (e: Exception) {
            Log.e("SUG", "âŒ SUG: Error obteniendo datos venta parcial para $codigo: ${e.message}")
            e.printStackTrace()
            // Si hay error, retornar valores por defecto (0, 0) - esto haró¡ que ventaParcial sea negativa o 0
            // y la ló³gica se adaptaró¡ automó¡ticamente
            Pair(0, 0)
        }
    }
    
    /**
     * Obtiene la suma de promedios desde maó±ana hasta la pró³xima entrega
     * Siempre suma maó±ana, y si no hay entrega, sigue sumando hasta encontrar un dó­a con entrega
     */
    private fun obtenerPromediosHastaProximaEntrega(codigo: String): Double {
        Log.d("DEBUG_JQ", "ðŸ” DEBUG_JQ: === OBTENIENDO PROMEDIOS DóAS ===")

        val fechaActual = DateHelper.getCalendar()
        val diaActual = obtenerDiaActual()
        val diaSiguiente = obtenerDiaSiguiente(diaActual)
        
        val promedios = mutableListOf<Double>()
        val diasConsiderados = mutableListOf<String>()

        // 1. Siempre incluir el dó­a siguiente
        val diaSiguienteCalendar = obtenerDiaCalendar(diaSiguiente)
        val promDiaSiguiente = obtenerPromedioPorDiaSemana(codigo, diaSiguienteCalendar)
        promedios.add(promDiaSiguiente)
        diasConsiderados.add(diaSiguiente)
        Log.d("SUG", "ðŸ” SUG: Dó­a siguiente $diaSiguiente: $promDiaSiguiente")

        // 2. Incluir los dó­as extra (dó­as sin entrega que aparecen en el texto)
        val diasExtra = obtenerDiasExtraSegunTexto()
        Log.d("SUG", "ðŸ” SUG: Dó­as extra encontrados: $diasExtra")
        
        diasExtra.forEach { nombreDia ->
            val diaCalendar = obtenerDiaCalendar(nombreDia)
            val prom = obtenerPromedioPorDiaSemana(codigo, diaCalendar)
            promedios.add(prom)
            diasConsiderados.add(nombreDia)
            Log.d("SUG", "ðŸ” SUG: Dó­a extra $nombreDia: $prom")
        }

        val suma = promedios.sum()
        Log.d("SUG", "ðŸ” SUG: Dó­as sumados: ${diasConsiderados.joinToString(", ")}")
        Log.d("SUG", "ðŸ” SUG: SUMA TOTAL: $suma")
        return suma
    }
    
    /**
     * Obtiene los dó­as extra que aparecen en el texto de arriba (misma ló³gica que actualizarInformacionDiasExtra)
     * Ló³gica: El primer dó­a (maó±ana) DEBE tener entrega. Luego suma dó­as sin entrega hasta encontrar el siguiente dó­a con entrega
     */
    private fun obtenerDiasExtraSegunTexto(): List<String> {
        val fechaActual = DateHelper.getCalendar()
        val diaSemana = fechaActual.get(Calendar.DAY_OF_WEEK)
        val diasExtra = mutableListOf<String>()
        
        // Obtener la lista de pró³ximos dó­as (maó±ana + siguientes)
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
            Log.e("SUG", "âŒ SUG: No hay dó­as configurados")
            return diasExtra
        }
        
        // 1. Verificar que el PRIMER dó­a (maó±ana) tenga entrega
        val (primerDia, primerDiaTieneEntrega) = proximosDias.first()
        if (!primerDiaTieneEntrega) {
            Log.e("SUG", "âŒ SUG: MAó‘ANA ($primerDia) NO tiene entrega - No se puede hacer pedido")
            // Aquó­ podró­as mostrar un mensaje de error al usuario
            return diasExtra
        }
        
        Log.d("SUG", "âœ… SUG: MAó‘ANA ($primerDia) tiene entrega - Se puede hacer pedido")
        
        // 2. Revisar los dó­as DESPUó‰S de maó±ana
        for (i in 1 until proximosDias.size) {
            val (nombreDia, tieneEntrega) = proximosDias[i]
            
            if (!tieneEntrega) {
                // Este dó­a NO tiene entrega, agregarlo y continuar
                diasExtra.add(nombreDia)
                Log.d("SUG", "âž• SUG: $nombreDia sin entrega, agregarlo")
            } else {
                // Este dó­a Só tiene entrega, PARAR aquó­
                Log.d("SUG", "ðŸ›‘ SUG: $nombreDia tiene entrega, parar")
                break
            }
        }
        
        Log.d("SUG", "ðŸ“‹ SUG: Dó­as extra finales: $diasExtra")
        return diasExtra
    }
    
    /**
     * Convierte nombre de dó­a a Calendar.DAY_OF_WEEK
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
     * Helper para obtener el nombre del dó­a para logs
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
                // Obtener el dó­a actual y el dó­a siguiente
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
                    val promediosPorDia = calcularPromediosPorDia(registros)
                    
                    // Obtener promedio del dó­a actual
                    promedioHoy = promediosPorDia[diaActual]?.get(nombreProducto) ?: 0.0
                    
                    // Obtener promedio del dó­a siguiente
                    promedioManana = promediosPorDia[diaSiguiente]?.get(nombreProducto) ?: 0.0
                    
                    Log.d("PedidoPanificadoraActivity", "Promedios para $codigo ($nombreProducto): Hoy=$promedioHoy, Maó±ana=$promedioManana")
                    
                    // Calcular sugerencia con datos reales
                    val sugerencia = calcularSugerenciaConPromedios(stock, promedioHoy, promedioManana, codigo)
                    
                    // Actualizar UI en el hilo principal
                    runOnUiThread {
                        callback(sugerencia)
                    }
                }
                
            } catch (e: Exception) {
                Log.e("PedidoPanificadoraActivity", "Error consultando promedios reales: ${e.message}")
                // En caso de error, usar promedios simulados
                val sugerencia = calcularSugerenciaPedido(codigo, stock)
                runOnUiThread {
                    callback(sugerencia)
                }
            }
        }
    }
    
    /**
     * Calcula la sugerencia con promedios especó­ficos
     */
    private fun calcularSugerenciaConPromedios(stock: Int, promedioHoy: Double, promedioManana: Double, codigo: String): Int {
        try {
            if (promedioHoy <= 0) {
                return 0
            }
            
            // Si no hay datos para maó±ana, usar solo los datos de hoy con un factor de seguridad
            val promedioMananaUsar = if (promedioManana > 0) {
                promedioManana
            } else {
                promedioHoy * 0.8 // Usar 80% del promedio de hoy como estimació³n conservadora
            }
            
            // Obtener el porcentaje extra configurado
            val porcentajeExtra = obtenerPorcentajeExtraConfigurado()
            
            // Fó³rmula correcta basada en tu có¡lculo:
            // Pedido = ((Promedio_HOY/2 - Stock_15:30) + Promedio_MAó‘ANA) + (Promedio_MAó‘ANA ó— %Extra)
            val mitadPromedioHoy = promedioHoy / 2.0
            val diferenciaStock = mitadPromedioHoy - stock
            val sugerenciaBase = diferenciaStock + promedioMananaUsar
            val porcentajeExtraDecimal = porcentajeExtra / 100.0
            val porcentajeExtraValor = promedioMananaUsar * porcentajeExtraDecimal
            val sugerenciaFinal = sugerenciaBase + porcentajeExtraValor
            
            // Asegurar que no sea negativo
            val sugerenciaPositiva = maxOf(0.0, sugerenciaFinal)
            
            // Redondear segóºn el tipo de producto
            return redondearSugerencia(sugerenciaPositiva, codigo)
            
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "Error calculando sugerencia con promedios: ${e.message}")
            return 0
        }
    }
    
    /**
     * Obtiene el promedio del dó­a actual para un producto especó­fico
     * Consulta los datos reales de la secció³n "Promedio de Ventas"
     */
    private fun obtenerPromedioDiarioHoy(codigo: String): Double {
        try {
            val localId = obtenerLocalId()
            val diaActual = obtenerDiaActual()
            val nombreProducto = obtenerNombreProducto(codigo)
            
        Log.d("SUG", "ðŸ” SUG: Obteniendo promedio hoy para $codigo en $diaActual")
            
            // Verificar si hay datos cargados en el cache
            if (MainActivity.promediosReales.isEmpty()) {
                Log.w("DEBUG_JQ", "âš ï¸ DEBUG_JQ: Cache de promedios vacó­o - Intentando cargar desde Firebase...")
                // Intentar cargar desde Firebase de forma só­ncrona
                kotlinx.coroutines.runBlocking {
                    val promediosFirebase = cargarPromediosGeneralesDesdeFirebase(localId)
                    if (promediosFirebase.isNotEmpty()) {
                        MainActivity.promediosReales = promediosFirebase
                        MainActivity.promediosCargados = true
                        Log.d("DEBUG_JQ", "âœ… Promedios cargados desde Firebase: ${promediosFirebase.size} dó­as")
                    } else {
                        Log.w("DEBUG_JQ", "âš ï¸ No hay promedios en Firebase para local $localId")
                        return@runBlocking
                    }
                }
                
                // Si aóºn estó¡ vacó­o despuó©s de intentar cargar, retornar 0
                if (MainActivity.promediosReales.isEmpty()) {
                    return 0.0
                }
            }
            
            // Verificar si hay datos para el dó­a especó­fico
            val datosDia = MainActivity.promediosReales[diaActual]
            if (datosDia == null) {
                Log.w("SUG", "âš ï¸ SUG: No hay datos para el dó­a $diaActual en local $localId")
                Log.d("SUG", "ðŸ” SUG: Dó­as disponibles en cache: ${MainActivity.promediosReales.keys}")
                return 0.0
            }
            
            Log.d("SUG", "ðŸ” SUG: Buscando producto '$codigo' ($nombreProducto) en $diaActual")
            Log.d("SUG", "ðŸ” SUG: Productos disponibles en $diaActual: ${datosDia.keys.take(10)}")
            
            // Intentar obtener datos reales del cache global
            // Buscar tanto por có³digo como por nombre completo
            var promedioHoy = datosDia[nombreProducto] ?: 0.0
            
            // Si no encuentra por nombre completo, buscar por có³digo
            if (promedioHoy <= 0) {
                promedioHoy = datosDia[codigo] ?: 0.0
            }
            
            // ðŸ”§ ESPECIAL: MEDIALUNA_MANTECA debe buscar "DULCES - Dulces" en Firebase
            if (promedioHoy <= 0 && codigo == "MEDIALUNA_MANTECA") {
                // Buscar variantes de "DULCES"
                for ((key, value) in datosDia) {
                    if (key.startsWith("DULCES", ignoreCase = true) || 
                        (key.contains("Dulce", ignoreCase = true) && key.contains("DULCES", ignoreCase = true)) ||
                        key.contains("Dulce", ignoreCase = true)) {
                        promedioHoy = value
                        Log.d("SUG", "ðŸ” SUG: MEDIALUNA_MANTECA encontrado por clave DULCES: '$key' = $value")
                        break
                    }
                }
            }
            
            // ðŸ”§ ESPECIAL: MEDIALUNA_GRASA debe buscar "SALADO - Salado" en Firebase
            if (promedioHoy <= 0 && codigo == "MEDIALUNA_GRASA") {
                // Buscar variantes de "SALADO"
                for ((key, value) in datosDia) {
                    if (key.startsWith("SALADO", ignoreCase = true) || 
                        (key.contains("Salado", ignoreCase = true) && key.contains("SALADO", ignoreCase = true)) ||
                        key.contains("Salado", ignoreCase = true)) {
                        promedioHoy = value
                        Log.d("SUG", "ðŸ” SUG: MEDIALUNA_GRASA encontrado por clave SALADO: '$key' = $value")
                        break
                    }
                }
            }
            
            // Si aóºn no encuentra, buscar por cualquier clave que contenga el có³digo
            if (promedioHoy <= 0) {
                for ((key, value) in datosDia) {
                    if (key.startsWith("$codigo - ") || key == codigo) {
                        promedioHoy = value
                        Log.d("SUG", "ðŸ” SUG: Encontrado por clave: '$key' = $value")
                        break
                    }
                }
            }
            
            if (promedioHoy > 0) {
            Log.d("SUG", "âœ… SUG: Promedio encontrado: $promedioHoy")
            return promedioHoy
            }
            
            // Si no hay datos reales, devolver 0 (no usar datos simulados)
            Log.w("SUG", "âŒ SUG: No hay datos reales para local '$localId', producto '$codigo' ($nombreProducto) en $diaActual - devolviendo 0")
            return 0.0
            
        } catch (e: Exception) {
            Log.e("SUG", "âŒ SUG: Error obteniendo promedio hoy para $codigo: ${e.message}")
            return 0.0
        }
    }
    
    /**
     * Obtiene el promedio del dó­a siguiente para un producto especó­fico
     * Consulta los datos reales de la secció³n "Promedio de Ventas"
     */
    private fun obtenerPromedioDiarioManana(codigo: String): Double {
        try {
            val localId = obtenerLocalId()
            val diaActual = obtenerDiaActual()
            val diaSiguiente = obtenerDiaSiguiente(diaActual)
            val nombreProducto = obtenerNombreProducto(codigo)
            
            Log.d("PedidoPanificadoraActivity", "ðŸ” Buscando promedio MAó‘ANA para local '$localId', producto '$codigo' ($nombreProducto) en dó­a '$diaSiguiente'")
            
            // Verificar si hay datos cargados en el cache
            if (MainActivity.promediosReales.isEmpty()) {
                Log.w("PedidoPanificadoraActivity", "âš ï¸ Cache de promedios vacó­o para local $localId")
                return 0.0
            }
            
            // Verificar si hay datos para el dó­a especó­fico
            val datosDia = MainActivity.promediosReales[diaSiguiente]
            if (datosDia == null) {
                Log.w("PedidoPanificadoraActivity", "âš ï¸ No hay datos para el dó­a $diaSiguiente en local $localId")
                return 0.0
            }
            
            // Intentar obtener datos reales del cache global
            // Buscar tanto por có³digo como por nombre completo
            var promedioManana = datosDia[nombreProducto] ?: 0.0
            
            // Si no encuentra por nombre completo, buscar por có³digo
            if (promedioManana <= 0) {
                promedioManana = datosDia[codigo] ?: 0.0
            }
            
            // ðŸ”§ ESPECIAL: MEDIALUNA_MANTECA debe buscar "DULCES - Dulces" en Firebase
            if (promedioManana <= 0 && codigo == "MEDIALUNA_MANTECA") {
                // Buscar variantes de "DULCES"
                for ((key, value) in datosDia) {
                    if (key.startsWith("DULCES", ignoreCase = true) || 
                        (key.contains("Dulce", ignoreCase = true) && key.contains("DULCES", ignoreCase = true)) ||
                        key.contains("Dulce", ignoreCase = true)) {
                        promedioManana = value
                        Log.d("PedidoPanificadoraActivity", "ðŸ” MEDIALUNA_MANTECA encontrado por clave DULCES: '$key' = $value")
                        break
                    }
                }
            }
            
            // ðŸ”§ ESPECIAL: MEDIALUNA_GRASA debe buscar "SALADO - Salado" en Firebase
            if (promedioManana <= 0 && codigo == "MEDIALUNA_GRASA") {
                // Buscar variantes de "SALADO"
                for ((key, value) in datosDia) {
                    if (key.startsWith("SALADO", ignoreCase = true) || 
                        (key.contains("Salado", ignoreCase = true) && key.contains("SALADO", ignoreCase = true)) ||
                        key.contains("Salado", ignoreCase = true)) {
                        promedioManana = value
                        Log.d("PedidoPanificadoraActivity", "ðŸ” MEDIALUNA_GRASA encontrado por clave SALADO: '$key' = $value")
                        break
                    }
                }
            }
            
            // Si aóºn no encuentra, buscar por cualquier clave que contenga el có³digo
            if (promedioManana <= 0) {
                for ((key, value) in datosDia) {
                    if (key.startsWith("$codigo - ") || key == codigo) {
                        promedioManana = value
                        Log.d("PedidoPanificadoraActivity", "ðŸ” Encontrado por clave: '$key' = $value")
                        break
                    }
                }
            }
            
            if (promedioManana > 0) {
                Log.d("PedidoPanificadoraActivity", "âœ… Promedio REAL MAó‘ANA para local '$localId', producto '$codigo' ($nombreProducto) en $diaSiguiente: $promedioManana")
                return promedioManana
            }
            
            // Si no hay datos reales, devolver 0 (no usar datos simulados)
            Log.d("PedidoPanificadoraActivity", "âŒ No hay datos reales para local '$localId', producto '$codigo' ($nombreProducto) en $diaSiguiente - devolviendo 0")
            return 0.0
            
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "âŒ Error obteniendo promedio maó±ana para $codigo: ${e.message}")
            return 0.0
        }
    }
    
    
    /**
     * Consulta el promedio real de forma só­ncrona desde la base de datos
     * NOTA: Por ahora devuelve 0 para evitar ANR, se implementaró¡ consulta asó­ncrona
     */
    private fun consultarPromedioRealSincrono(dia: String, nombreProducto: String): Double {
        try {
            // Por ahora devolver 0 para evitar ANR
            // TODO: Implementar consulta asó­ncrona que no bloquee el hilo principal
            Log.d("PedidoPanificadoraActivity", "Consultando promedio real para $nombreProducto en $dia - usando fallback")
            return 0.0
            
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "Error consultando promedio real só­ncrono: ${e.message}")
            return 0.0
        }
    }
    
    /**
     * Obtiene el porcentaje extra configurado desde la configuració³n actual
     */
    private fun obtenerPorcentajeExtraConfigurado(): Double {
        try {
            // ðŸ”¥ USAR LA CONFIGURACIó“N ACTUAL EN LUGAR DE SHAREDPREFERENCES
            val porcentaje = configuracion.porcentajeExtra
            Log.d("PedidoPanificadoraActivity", "ðŸ” Porcentaje extra leó­do desde configuració³n actual: $porcentaje%")
            return porcentaje.toDouble()
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "Error obteniendo porcentaje extra: ${e.message}")
            return 30.0 // 30% por defecto en caso de error
        }
    }
    
    /**
     * Actualiza el porcentaje extra en la configuració³n
     */
    private fun actualizarPorcentajeExtra(nuevoPorcentaje: Float) {
        try {
            val prefs = getSharedPreferences("configuracion_pedido_panificadora", MODE_PRIVATE)
            prefs.edit().putFloat("porcentaje_extra", nuevoPorcentaje).apply()
            Log.d("PedidoPanificadoraActivity", "Porcentaje extra actualizado a: $nuevoPorcentaje%")
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "Error actualizando porcentaje extra: ${e.message}")
        }
    }
    
    /**
     * Obtiene el dó­a actual de la semana
     */
    private fun obtenerDiaActual(): String {
        val calendar = DateHelper.getCalendar()
        val diaSemana = calendar.get(Calendar.DAY_OF_WEEK)
        
        // Mapear directamente a los nombres usados en los datos (sin tildes)
        return when (diaSemana) {
            Calendar.MONDAY -> "LUNES"
            Calendar.TUESDAY -> "MARTES"
            Calendar.WEDNESDAY -> "MIERCOLES"
            Calendar.THURSDAY -> "JUEVES"
            Calendar.FRIDAY -> "VIERNES"
            Calendar.SATURDAY -> "SABADO"
            Calendar.SUNDAY -> "DOMINGO"
            else -> "LUNES"
        }
    }
    
    /**
     * Obtiene el dó­a siguiente de la semana
     */
    private fun obtenerDiaSiguiente(diaActual: String): String {
        val dias = listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        val indexActual = dias.indexOf(diaActual)
        return if (indexActual == dias.size - 1) "LUNES" else dias[indexActual + 1]
    }
    
    /**
     * Actualiza la informació³n de dó­as extra en la interfaz
     */
    private fun actualizarInformacionDiasExtra() {
        // ðŸ”§ En modo admin con fecha histó³rica, usar esa fecha en lugar de la fecha actual
        val fechaActual = if (modoAdmin && fechaSeleccionada != DateHelper.getDateFormat("yyyy-MM-dd").format(Date())) {
            // Parsear la fecha histó³rica
            val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
            sdf.parse(fechaSeleccionada) ?: Date()
        } else {
            Date()
        }
        val calendar = DateHelper.getCalendar()
        calendar.time = fechaActual
        val diaSemana = calendar.get(Calendar.DAY_OF_WEEK)
        
        val diaActual = if (modoAdmin && fechaSeleccionada != DateHelper.getDateFormat("yyyy-MM-dd").format(Date())) {
            obtenerDiaSemana(fechaSeleccionada)
        } else {
            obtenerDiaActual()
        }
        val diaSiguiente = obtenerDiaSiguiente(diaActual)
        
        Log.d("PedidoPanificadoraActivity", "ðŸ”„ Actualizando informació³n de dó­as extra...")
        Log.d("PedidoPanificadoraActivity", "ðŸ“… Dó­a actual: $diaActual (dó­a semana: $diaSemana)")
        Log.d("PedidoPanificadoraActivity", "ðŸ“… Dó­a siguiente: $diaSiguiente")
        Log.d("PedidoPanificadoraActivity", "ðŸ“… Configuració³n actual: $diasEntrega")
        
        val diasExtra = mutableListOf<String>()
        
        // Nueva ló³gica: El primer dó­a (maó±ana) DEBE tener entrega. Luego suma dó­as sin entrega hasta encontrar el siguiente dó­a con entrega
        val proximosDias = when (diaSemana) {
            Calendar.MONDAY -> listOf("Martes" to diasEntrega.martes, "Mió©rcoles" to diasEntrega.miercoles, "Jueves" to diasEntrega.jueves, "Viernes" to diasEntrega.viernes)
            Calendar.TUESDAY -> listOf("Mió©rcoles" to diasEntrega.miercoles, "Jueves" to diasEntrega.jueves, "Viernes" to diasEntrega.viernes, "Só¡bado" to diasEntrega.sabado)
            Calendar.WEDNESDAY -> listOf("Jueves" to diasEntrega.jueves, "Viernes" to diasEntrega.viernes, "Só¡bado" to diasEntrega.sabado, "Domingo" to diasEntrega.domingo)
            Calendar.THURSDAY -> listOf("Viernes" to diasEntrega.viernes, "Só¡bado" to diasEntrega.sabado, "Domingo" to diasEntrega.domingo, "Lunes" to diasEntrega.lunes)
            Calendar.FRIDAY -> listOf("Só¡bado" to diasEntrega.sabado, "Domingo" to diasEntrega.domingo, "Lunes" to diasEntrega.lunes, "Martes" to diasEntrega.martes)
            Calendar.SATURDAY -> listOf("Domingo" to diasEntrega.domingo, "Lunes" to diasEntrega.lunes, "Martes" to diasEntrega.martes, "Mió©rcoles" to diasEntrega.miercoles)
            Calendar.SUNDAY -> listOf("Lunes" to diasEntrega.lunes, "Martes" to diasEntrega.martes, "Mió©rcoles" to diasEntrega.miercoles, "Jueves" to diasEntrega.jueves)
            else -> emptyList()
        }
        
        if (proximosDias.isEmpty()) {
            Log.e("PedidoPanificadoraActivity", "âŒ No hay dó­as configurados")
            txtDiasExtra.text = "âš ï¸ Error: No hay dó­as configurados"
            txtDiasExtra.visibility = View.VISIBLE
            return
        }
        
        // 1. Verificar que el PRIMER dó­a (maó±ana) tenga entrega
        val (primerDia, primerDiaTieneEntrega) = proximosDias.first()
        if (!primerDiaTieneEntrega) {
            Log.e("PedidoPanificadoraActivity", "âŒ MAó‘ANA ($primerDia) NO tiene entrega - No se puede hacer pedido")
            txtDiasExtra.text = "âš ï¸ No se puede hacer pedido: $primerDia no tiene entrega marcada"
            txtDiasExtra.visibility = View.VISIBLE
            
            // Mostrar alerta al usuario
            runOnUiThread {
                val dialog = AlertDialog.Builder(this@PedidoPanificadoraActivity)
                    .setTitle("âš ï¸ Error en configuració³n")
                    .setMessage("No se puede hacer pedido porque MAó‘ANA ($primerDia) no tiene entrega marcada.\n\nPor favor, marca $primerDia como dó­a con entrega en la configuració³n de dó­as de entrega.")
                    .setPositiveButton("Configurar Dó­as") { _, _ ->
                        mostrarConfiguracionDiasEntrega()
                    }
                    .setNegativeButton("Cancelar", null)
                    .create()
                
                // Configurar fondo y colores del dió¡logo para modo oscuro
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
        
        Log.d("PedidoPanificadoraActivity", "âœ… MAó‘ANA ($primerDia) tiene entrega - Se puede hacer pedido")
        
        // 2. Revisar los dó­as DESPUó‰S de maó±ana
        for (i in 1 until proximosDias.size) {
            val (nombreDia, tieneEntrega) = proximosDias[i]
            
            if (!tieneEntrega) {
                // Este dó­a NO tiene entrega, agregarlo y continuar
                diasExtra.add(nombreDia)
                Log.d("PedidoPanificadoraActivity", "âž• $nombreDia sin entrega, agregarlo")
            } else {
                // Este dó­a Só tiene entrega, PARAR aquó­
                Log.d("PedidoPanificadoraActivity", "ðŸ›‘ $nombreDia tiene entrega, parar")
                break
            }
        }
        
        // Construir el texto
        val textoPedido = if (diasExtra.isEmpty()) {
            "ðŸ“… Pedido para: $diaSiguiente"
        } else {
            "ðŸ“… Pedido para: $diaSiguiente + ${diasExtra.joinToString(" + ")}"
        }
        
        Log.d("PedidoPanificadoraActivity", "ðŸ“… Dó­as extra encontrados: $diasExtra")
        Log.d("PedidoPanificadoraActivity", "ðŸ“… Texto final: $textoPedido")
        
        txtDiasExtra.text = textoPedido
        txtDiasExtra.visibility = View.VISIBLE
        
        Log.d("PedidoPanificadoraActivity", "âœ… Informació³n de dó­as extra actualizada en la interfaz")
    }
    
    /**
     * Calcula los promedios por dó­a basado en los registros
     * (Copiado de PromedioVentasActivity)
     */
    private fun calcularPromediosPorDia(registros: List<RegistroInventario>): Map<String, Map<String, Double>> {
        val promediosPorDia = mutableMapOf<String, MutableMap<String, Double>>()
        val diasSemana = listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        
        // Inicializar estructura
        diasSemana.forEach { dia ->
            promediosPorDia[dia] = mutableMapOf()
        }
        
        // Obtener todas las fechas ordenadas (igual que PromedioVentasActivity)
        val fechasOrdenadas = registros.map { it.fecha }.distinct().sorted()
        
        if (fechasOrdenadas.isEmpty()) {
            return promediosPorDia
        }
        
        // Agrupar por dó­a de la semana
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
                    // Filtrar solo los dó­as donde este producto tiene ventas > 0
                    val ventasConDatos = listaVentas.mapNotNull { it[producto] }.filter { it > 0 }
                    
                    if (ventasConDatos.isNotEmpty()) {
                        val suma = ventasConDatos.sum()
                        val promedio = suma.toDouble() / ventasConDatos.size
                    promediosPorDia[dia]?.set(producto, promedio)
                    } else {
                        // Si no hay dó­as con ventas > 0, el promedio es 0
                        promediosPorDia[dia]?.set(producto, 0.0)
                    }
                }
            }
        }
        
        return promediosPorDia
    }
    
    /**
     * Calcula las ventas de un dó­a especó­fico
     */
    private fun calcularVentasDia(fecha: String, registros: List<RegistroInventario>): Map<String, Int> {
        val registrosFecha = registros.filter { it.fecha == fecha }
        val ingreso = registrosFecha.find { it.tipo == "Ingreso de Mercaderó­a" }?.productos ?: emptyList()
        val stockFinal = registrosFecha.find { it.tipo == "Stock Final" }?.productos ?: emptyList()
        
        // Obtener stock final del dó­a anterior
        val fechaAnterior = obtenerFechaAnterior(fecha)
        val stockAnterior = registros.filter { it.fecha == fechaAnterior }
            .find { it.tipo == "Stock Final" }?.productos ?: emptyList()
        
        val ventas = mutableMapOf<String, Int>()
        
        // Si no hay stock final del dó­a actual, no podemos calcular ventas
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
     * Obtiene los promedios de venta para un producto especó­fico
     */
    private fun obtenerPromediosProducto(codigo: String): List<Double> {
        // Por ahora retornar promedios simulados, despuó©s se conectaró¡n con los datos reales
        return when (codigo) {
            "JQ" -> listOf(50.0, 60.0, 45.0, 55.0, 70.0, 80.0, 90.0) // L-D
            "PB" -> listOf(30.0, 35.0, 25.0, 40.0, 45.0, 50.0, 60.0)
            "CS" -> listOf(20.0, 25.0, 15.0, 30.0, 35.0, 40.0, 45.0)
            "PO" -> listOf(40.0, 45.0, 35.0, 50.0, 55.0, 60.0, 70.0)
            else -> listOf(10.0, 12.0, 8.0, 15.0, 18.0, 20.0, 25.0)
        }
    }
    
    /**
     * Obtiene el ó­ndice del dó­a (0=Lunes, 6=Domingo)
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
     * Genera y envó­a el pedido por WhatsApp
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
            
            // Verificar si WhatsApp estó¡ instalado
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                // Si no estó¡ instalado, usar intent genó©rico
                val intentGenerico = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, textoPedido)
                }
                startActivity(Intent.createChooser(intentGenerico, "Compartir pedido"))
            }
            
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "Error enviando pedido por WhatsApp: ${e.message}")
            Toast.makeText(this, "Error enviando pedido: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Genera el texto del pedido en el formato requerido
     */
    private fun generarTextoPedido(_editTexts: MutableMap<String, EditText>): String {
        val pedido = StringBuilder()
        
        // Orden especó­fico de productos (igual que en la pantalla)
        val ordenProductos = listOf(
            // Empanadas
            // PANIFICADORA
            "MEDIALUNA_MANTECA", "MEDIALUNA_GRASA", "CHIPA", "CRIOLLITO"
        )
        
        pedido.append("ðŸ“‹ PEDIDO PANIFICADORA - ${obtenerFechaActual()}\n\n")
        
        // Agregar cada producto en el orden especó­fico
        ordenProductos.forEach { codigo ->
            val stock = obtenerValorStock(codigo)
            // Obtener el valor del campo "Pedido Final" de la tabla
            val pedidoFinal = obtenerValorPedidoFinal(codigo)
            
            // SOLO PANIFICADORA - No hay secciones separadas
            
            // âœ… MOSTRAR LITERAL: Sin multiplicar ni redondear - exactamente lo que escribió³ el usuario
            val pedidoFormateado = when {
                // Pizzas: mostrar literal (sin multiplicar por 5)
                codigo in listOf("MUZZARE", "PEPPERO", "JAMON") -> {
                    if (pedidoFinal > 0) "${codigo.lowercase()} $stock-$pedidoFinal" else "${codigo.lowercase()} $stock-nopido"
                }
                // Pastelitos: mostrar literal (sin redondear)
                codigo in listOf("MEMBRIL", "BATATA") -> {
                    if (pedidoFinal > 0) "${codigo.lowercase()} $stock-$pedidoFinal" else "${codigo.lowercase()} $stock-0"
                }
                // Medialunas de Manteca: mostrar literal (sin convertir a bolsas)
                codigo == "MEDIALUNA_MANTECA" -> {
                    if (pedidoFinal > 0) "mdl manteca $stock-$pedidoFinal" else "mdl manteca $stock-no pido"
                }
                // Medialunas de Grasa: mostrar literal (sin convertir a bolsas)
                codigo == "MEDIALUNA_GRASA" -> {
                    if (pedidoFinal > 0) "mdl grasa $stock-$pedidoFinal" else "mdl grasa $stock-no pido"
                }
                // Fallback para DULCES (si existe)
                codigo == "DULCES" -> {
                    if (pedidoFinal > 0) "mdl dulce $stock-$pedidoFinal" else "mdl dulce $stock-no pido"
                }
                // Fallback para SALADO (si existe)
                codigo == "SALADO" -> {
                    if (pedidoFinal > 0) "mdl salado $stock-$pedidoFinal" else "mdl salado $stock-no pido"
                }
                // Chipa: mostrar literal (sin convertir a bolsas)
                codigo == "CHIPA" -> {
                    if (pedidoFinal > 0) "chipa $stock-$pedidoFinal" else "chipa $stock-0"
                }
                // Criollito: mostrar literal (sin convertir a bolsas)
                codigo == "CRIOLLITO" -> {
                    if (pedidoFinal > 0) "criollito $stock-$pedidoFinal" else "criollito $stock-0"
                }
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
     * Obtiene el valor del campo "Pedido Final" para un producto especó­fico
     */
    private fun obtenerValorPedidoFinal(codigo: String): Int {
        try {
            // Mó©todo 1: Buscar por tag
            val tag = "${codigo}_pedido"
            val editText = layoutContenido.findViewWithTag<EditText>(tag)
            if (editText != null) {
                val valor = editText.text?.toString()?.toIntOrNull() ?: 0
                Log.d("PedidoPanificadoraActivity", "ðŸ” DEBUG: Obteniendo pedido final para $codigo (tag: $tag) - EditText encontrado: true, valor: $valor")
                return valor
            }
            
            // Mó©todo 2: Buscar recursivamente en todos los EditText
            val editTexts = mutableListOf<EditText>()
            buscarEditTextsRecursivamente(layoutContenido, editTexts)
            
            for (editTextItem in editTexts) {
                if (editTextItem.tag == tag) {
                    val valor = editTextItem.text?.toString()?.toIntOrNull() ?: 0
                    Log.d("PedidoPanificadoraActivity", "ðŸ” DEBUG: Obteniendo pedido final para $codigo (mó©todo recursivo) - valor: $valor")
                    return valor
                }
            }
            
            Log.w("PedidoPanificadoraActivity", "âš ï¸ No se encontró³ EditText para pedido final de $codigo")
            return 0
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "Error obteniendo valor pedido final para $codigo: ${e.message}")
            return 0
        }
    }
    
    private fun obtenerValorStock(codigo: String): Int {
        try {
            // Mó©todo 1: Buscar por tag
            val tag = "${codigo}_stock"
            val editText = layoutContenido.findViewWithTag<EditText>(tag)
            if (editText != null) {
                val valor = editText.text?.toString()?.toIntOrNull() ?: 0
                Log.d("PedidoPanificadoraActivity", "ðŸ” DEBUG: Obteniendo stock para $codigo (tag: $tag) - EditText encontrado: true, valor: $valor")
                return valor
            }
            
            // Mó©todo 2: Buscar recursivamente en todos los EditText
            val editTexts = mutableListOf<EditText>()
            buscarEditTextsRecursivamente(layoutContenido, editTexts)
            
            for (editTextItem in editTexts) {
                if (editTextItem.tag == tag) {
                    val valor = editTextItem.text?.toString()?.toIntOrNull() ?: 0
                    Log.d("PedidoPanificadoraActivity", "ðŸ” DEBUG: Obteniendo stock para $codigo (mó©todo recursivo) - valor: $valor")
                    return valor
                }
            }
            
            Log.w("PedidoPanificadoraActivity", "âš ï¸ No se encontró³ EditText para stock de $codigo")
            return 0
        } catch (e: Exception) {
            Log.e("PedidoPanificadoraActivity", "Error obteniendo valor stock para $codigo: ${e.message}")
            return 0
        }
    }
    
    /**
     * Funció³n auxiliar para buscar EditTexts recursivamente en un ViewGroup
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
                    // Si fue un clic tó¡ctil, ya se seleccionó³ en el onClick
                    fueClickTactil = false
                } else {
                    // Si fue navegació³n por teclado, seleccionar despuó©s de un pequeó±o delay
                    editText.post {
                        editText.selectAll()
                    }
                }
            }
        }
        
        // Configurar el teclado mó³vil para mostrar "Siguiente" en lugar de "Enter"
        editText.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        
        // Manejar el botó³n "Siguiente" del teclado mó³vil
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                Log.d("NAVEGACION", "Botó³n Siguiente presionado en: ${editText.tag}")
                // Buscar el siguiente EditText y enfocarlo
                val siguienteEditText = encontrarSiguienteEditTextInteligente(editText)
                if (siguienteEditText != null) {
                    Log.d("NAVEGACION", "Navegando a: ${siguienteEditText.tag}")
                    siguienteEditText.requestFocus()
                    siguienteEditText.selectAll()
                } else {
                    Log.d("NAVEGACION", "No se encontró³ siguiente EditText")
                }
                true // Consumir el evento
            } else {
                false
            }
        }
        
        // Configurar navegació³n por teclado para que funcione correctamente
        editText.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // Permitir navegació³n hacia abajo
                        editText.clearFocus()
                        false // Permitir que el sistema maneje la navegació³n
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        // Permitir navegació³n hacia arriba
                        editText.clearFocus()
                        false // Permitir que el sistema maneje la navegació³n
                    }
                    android.view.KeyEvent.KEYCODE_TAB -> {
                        // Navegació³n inteligente con Tab
                        editText.clearFocus()
                        encontrarSiguienteEditTextInteligente(editText)?.let { siguienteEditText ->
                            siguienteEditText.requestFocus()
                            siguienteEditText.selectAll()
                        }
                        true // Consumir el evento
                    }
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        // ENTER en mó³vil - navegar al siguiente campo y seleccionar todo
                        Log.d("NAVEGACION", "ENTER presionado en: ${editText.tag}")
                        editText.clearFocus()
                        // Buscar el siguiente EditText y enfocarlo
                        val siguienteEditText = encontrarSiguienteEditTextInteligente(editText)
                        if (siguienteEditText != null) {
                            Log.d("NAVEGACION", "Navegando a: ${siguienteEditText.tag}")
                            siguienteEditText.requestFocus()
                            siguienteEditText.selectAll()
                        } else {
                            Log.d("NAVEGACION", "No se encontró³ siguiente EditText")
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
     * Navegació³n inteligente: primero por columna Stock, luego por columna Pedido
     */
    private fun encontrarSiguienteEditTextInteligente(editTextActual: EditText): EditText? {
        // Orden fijo de productos
        val ordenProductos = listOf(
            "JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH",  // EMPANADAS
            "MUZZARE", "JAMON", "PEPPERO",  // PIZZAS
            "BATATA", "MEMBRIL",  // PASTELITOS
            "DULCES", "CHIPA"  // OTROS
        )
        
        val tagActual = editTextActual.tag?.toString() ?: ""
        Log.d("NAVEGACION", "Tag actual: $tagActual")
        
        // Extraer có³digo y tipo del tag actual
        val partes = tagActual.split("_")
        if (partes.size != 2) {
            Log.d("NAVEGACION", "Tag no vó¡lido, usando navegació³n normal")
            return encontrarSiguienteEditText(editTextActual)
        }
        
        val codigoActual = partes[0]
        val tipoActual = partes[1] // "stock" o "pedido"
        
        Log.d("NAVEGACION", "Có³digo actual: $codigoActual, Tipo actual: $tipoActual")
        
        val indiceActual = ordenProductos.indexOf(codigoActual)
        if (indiceActual == -1) {
            Log.d("NAVEGACION", "Có³digo no encontrado en orden, usando navegació³n normal")
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
                    Log.d("NAVEGACION", "Terminó³ stock, yendo al primer pedido: $primerTag")
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
                    Log.d("NAVEGACION", "Terminó³ pedido, yendo al primer stock: $primerTag")
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
     * Encuentra el siguiente EditText en la pantalla para navegació³n
     */
    private fun encontrarSiguienteEditText(editTextActual: EditText): EditText? {
        val editTexts = mutableListOf<EditText>()
        buscarEditTextsRecursivamente(layoutContenido, editTexts)
        
        // Filtrar solo los EditText habilitados y visibles
        val editTextsVisibles = editTexts.filter { it.isEnabled && it.visibility == android.view.View.VISIBLE }
        
        // Ordenar por posició³n en pantalla (de arriba hacia abajo, izquierda a derecha)
        val editTextsOrdenados = editTextsVisibles.sortedWith { a, b ->
            val posA = IntArray(2)
            val posB = IntArray(2)
            a.getLocationOnScreen(posA)
            b.getLocationOnScreen(posB)
            
            when {
                posA[1] != posB[1] -> posA[1].compareTo(posB[1]) // Comparar por Y (vertical)
                else -> posA[0].compareTo(posB[0]) // Si estó¡n en la misma fila, comparar por X (horizontal)
            }
        }
        
        // Encontrar el ó­ndice del EditText actual
        val indiceActual = editTextsOrdenados.indexOf(editTextActual)
        
        // Retornar el siguiente EditText, o el primero si estamos en el óºltimo
        return if (indiceActual >= 0 && indiceActual < editTextsOrdenados.size - 1) {
            editTextsOrdenados[indiceActual + 1]
        } else if (editTextsOrdenados.isNotEmpty()) {
            editTextsOrdenados[0] // Volver al primero si estamos en el óºltimo
        } else {
            null
        }
    }

    private fun calcularPedidos() {
        if (productos.isEmpty()) {
            Toast.makeText(this@PedidoPanificadoraActivity, "No hay productos disponibles. Primero carga los promedios.", Toast.LENGTH_LONG).show()
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
        
        Toast.makeText(this@PedidoPanificadoraActivity, "Pedidos calculados: $pedidosCalculados productos", Toast.LENGTH_SHORT).show()
        mostrarContenidoPedido() // Actualizar la vista con los có¡lculos
    }
    
    /**
     * Guarda la configuració³n en Firebase para el local actual
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
                
                // Guardar dentro del local especó­fico: locales/{localId}/configuracion_dias_entrega_panificadora
                firestore.collection("locales")
                    .document(localId)
                    .collection("configuracion_dias_entrega_panificadora")
                    .document("config")
                    .set(diasData)
                    .addOnSuccessListener {
                        Log.d("PedidoPanificadoraActivity", "âœ… Dó­as de entrega guardados en Firebase para local: $localId")
                    }
                    .addOnFailureListener { e ->
                        Log.e("PedidoPanificadoraActivity", "âŒ Error guardando dó­as de entrega en Firebase: ${e.message}")
                    }
                    
            } catch (e: Exception) {
                Log.e("PedidoPanificadoraActivity", "Error guardando dó­as de entrega: ${e.message}")
            }
        }
    }
    
    private fun guardarConfiguracionEnFirebase(config: ConfiguracionPedidoFabrica) {
        lifecycleScope.launch {
            try {
                val localId = obtenerLocalId()
                val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
                
                val configData = mapOf(
                    "porcentajeExtra" to config.porcentajeExtra,
                    "horaAlerta" to config.horaAlerta,
                    "usarAjusteTendencia" to config.usarAjusteTendencia,
                    // ðŸ”§ NO guardar dó­as de entrega aquó­ - se guardan por separado en guardarDiasEntregaEnFirebase()
                    // Los dó­as de entrega se guardan en configuracion_dias_entrega_panificadora (colecció³n separada)
                    "fechaActualizacion" to com.google.firebase.Timestamp.now()
                )
                
                // Guardar dentro del local especó­fico: locales/{localId}/configuracion_pedido_panificadora
                firestore.collection("locales")
                    .document(localId)
                    .collection("configuracion_pedido_panificadora")
                    .document("config")
                    .set(configData)
                    .addOnSuccessListener {
                        Log.d("PedidoPanificadoraActivity", "âœ… Configuració³n guardada en Firebase para local: $localId")
                    }
                    .addOnFailureListener { e ->
                        Log.e("PedidoPanificadoraActivity", "âŒ Error guardando configuració³n en Firebase: ${e.message}")
                    }
                    
            } catch (e: Exception) {
                Log.e("PedidoPanificadoraActivity", "Error guardando configuració³n: ${e.message}")
            }
        }
    }
    
    /**
     * Guarda el pedido de fó¡brica en Firestore con la nueva estructura
     */
    private fun guardarPedidoEnFirestore(_editTexts: MutableMap<String, EditText>) {
        if (modoSoloLectura && !modoAdmin) {
            Log.w("PedidoPanificadoraActivity", "âš ï¸ Modo solo lectura - no se puede guardar")
            Toast.makeText(this, "No se puede guardar en fechas anteriores", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (modoAdmin) {
            Log.d("PedidoPanificadoraActivity", "ðŸ”§ Modo administrador - guardando en fecha histó³rica")
        }
        
        lifecycleScope.launch {
            try {
                Log.d("PedidoPanificadoraActivity", "ðŸ” DEBUG: Lista productos antes de mapear: ${productos.size}")
                productos.forEach { producto ->
                    Log.d("PedidoPanificadoraActivity", "ðŸ” DEBUG: Producto: ${producto.codigo} - ${producto.nombre}")
                }
                
                // ðŸ”§ FIX: Crear lista de productos desde los valores actuales de la UI - SOLO PANIFICADORA
                val ordenProductos = listOf(
                    "MEDIALUNA_MANTECA", "MEDIALUNA_GRASA", "CHIPA", "CRIOLLITO"  // PANIFICADORA
                )
                
                val productosParaGuardar = ordenProductos.mapNotNull { codigo ->
                    // Obtener los valores de los campos de la tabla
                    val stockActual = obtenerValorStock(codigo)
                    val cantidadPedido = obtenerValorPedidoFinal(codigo)
                    
                    // Solo incluir productos que tienen valores
                    if (stockActual > 0 || cantidadPedido > 0) {
                        Log.d("PedidoPanificadoraActivity", "ðŸ” DEBUG: ${codigo} - Stock: $stockActual, Pedido: $cantidadPedido")
                    
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
                
                // ðŸ”¥ CAPTURAR informació³n de "para cuó¡ndo es el pedido" desde la UI
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
                    "paraFecha" to textoDiasExtra  // ðŸ”¥ Guardar el texto de "para cuó¡ndo es el pedido"
                )
                
                val pedidoData = mapOf(
                    "productos" to productosParaGuardar,
                    "configuracion" to configuracionParaGuardar,
                    "fecha" to fechaSeleccionada,
                    "localId" to localId
                )
                
                // Guardar en locales/{localId}/pedidos_panificadora/{fecha}
                val rutaCompleta = "locales/$localId/pedidos_panificadora/$fechaSeleccionada"
                Log.d("PedidoPanificadoraActivity", "ðŸ’¾ GUARDANDO en ruta: $rutaCompleta")
                Log.d("PedidoPanificadoraActivity", "ðŸ“Š Datos a guardar: ${productosParaGuardar.size} productos")
                
                firestore.collection("locales")
                    .document(localId)
                    .collection("pedidos_panificadora")
                    .document(fechaSeleccionada)
                    .set(pedidoData)
                    .addOnSuccessListener {
                        Log.d("PedidoPanificadoraActivity", "âœ… Pedido guardado exitosamente en Firebase")
                        Log.d("PedidoPanificadoraActivity", "ðŸ“ Ruta: $rutaCompleta")
                        Log.d("PedidoPanificadoraActivity", "ðŸ“… Fecha: $fechaSeleccionada")
                        Log.d("PedidoPanificadoraActivity", "ðŸª Local: $localId")
                        Toast.makeText(this@PedidoPanificadoraActivity, "Pedido guardado exitosamente", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Log.e("PedidoPanificadoraActivity", "âŒ Error guardando pedido en Firebase: ${e.message}")
                        Toast.makeText(this@PedidoPanificadoraActivity, "Error guardando pedido: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    
            } catch (e: Exception) {
                Log.e("PedidoPanificadoraActivity", "Error guardando pedido: ${e.message}")
                Toast.makeText(this@PedidoPanificadoraActivity, "Error guardando pedido: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * ðŸ”¥ NUEVA FUNCIó“N: Guarda automó¡ticamente los datos reconstruidos desde registros
     */
    private fun guardarPedidoReconstruidoEnFirebase(pedidoReconstruido: PedidoPanificadoraGuardado) {
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
                
                // ðŸ”¥ CAPTURAR informació³n de "para cuó¡ndo es el pedido" desde la UI
                val textoDiasExtra = txtDiasExtra.text.toString()
                
                val configuracionParaGuardar = mapOf(
                    "porcentajeExtra" to porcentajeSeleccionado,  // ðŸ”¥ USAR EL PORCENTAJE ACTUAL
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
                    "paraFecha" to textoDiasExtra,  // ðŸ”¥ Guardar el texto de "para cuó¡ndo es el pedido"
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
                
                // Guardar en locales/{localId}/pedidos_panificadora/{fecha}
                val rutaCompleta = "locales/$localId/pedidos_panificadora/$fechaSeleccionada"
                Log.d("PedidoPanificadoraActivity", "ðŸ’¾ GUARDANDO DATOS RECONSTRUIDOS en ruta: $rutaCompleta")
                Log.d("PedidoPanificadoraActivity", "ðŸ“Š Productos a guardar: ${productosParaGuardar.size}")
                
                firestore.collection("locales")
                    .document(localId)
                    .collection("pedidos_panificadora")
                    .document(fechaSeleccionada)
                    .set(pedidoData)
                    .addOnSuccessListener {
                        Log.d("PedidoPanificadoraActivity", "âœ… DATOS RECONSTRUIDOS guardados exitosamente en Firebase")
                        Log.d("PedidoPanificadoraActivity", "ðŸ“ Ruta: $rutaCompleta")
                        Log.d("PedidoPanificadoraActivity", "ðŸ“… Fecha: $fechaSeleccionada")
                        Log.d("PedidoPanificadoraActivity", "ðŸª Local: $localId")
                        Log.d("PedidoPanificadoraActivity", "ðŸ”„ Reconstruido: true")
                    }
                    .addOnFailureListener { e ->
                        Log.e("PedidoPanificadoraActivity", "âŒ Error guardando datos reconstruidos: ${e.message}")
                    }
                    
            } catch (e: Exception) {
                Log.e("PedidoPanificadoraActivity", "Error guardando datos reconstruidos: ${e.message}")
            }
        }
    }
    
    /**
     * Carga la configuració³n de dó­as de entrega desde Firebase
     */
    private fun mostrarPopupPrimeraVez() {
        val prefs = getSharedPreferences("pedido_fabrica_prefs", MODE_PRIVATE)
        val yaVisto = prefs.getBoolean("popup_primera_vez_visto", false)
        
        if (!yaVisto) {
            // Mostrar popup despuó©s de un pequeó±o delay para que la UI estó© lista
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
            text = "ðŸŽ¯ Â¡Bienvenido a Pedido Panificadora!"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
            setTextColor(getThemeColor(android.R.attr.textColorPrimary))
        }
        
        val descripcion = TextView(this).apply {
            text = "Para obtener sugerencias precisas de pedido, configura los dó­as en que tu local recibe mercaderó­a de la fó¡brica.\n\n" +
                   "Por ejemplo, si no te entregan los domingos, desmarca ese dó­a para que el viernes calcule stock para só¡bado, domingo y lunes."
            textSize = 14f
            setPadding(0, 0, 0, 24)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        
        val btnConfigurar = Button(this).apply {
            text = "âš™ï¸ Configurar Dó­as de Entrega"
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                mostrarConfiguracionDiasEntrega()
            }
        }
        
        layout.addView(titulo)
        layout.addView(descripcion)
        layout.addView(btnConfigurar)
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("â„¹ï¸ Informació³n Importante")
            .setView(layout)
            .setPositiveButton("Entendido") { _, _ ->
                // Marcar como visto
                val prefs = getSharedPreferences("pedido_fabrica_prefs", MODE_PRIVATE)
                prefs.edit().putBoolean("popup_primera_vez_visto", true).apply()
            }
            .setCancelable(false)
            .create()
        
        // Configurar fondo y colores del dió¡logo para modo oscuro
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
                
                // Cargar desde el local especó­fico: locales/{localId}/configuracion_dias_entrega_panificadora
                firestore.collection("locales")
                    .document(localId)
                    .collection("configuracion_dias_entrega_panificadora")
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
                            Log.d("PedidoPanificadoraActivity", "âœ… Dó­as de entrega cargados desde Firebase: $diasEntrega")
                            // Actualizar la informació³n de dó­as extra en la interfaz
                            runOnUiThread {
                            actualizarInformacionDiasExtra()
                            }
                        } else {
                            Log.d("PedidoPanificadoraActivity", "ðŸ“ No hay configuració³n de dó­as de entrega en Firebase, usando valores por defecto")
                            // Guardar configuració³n por defecto en Firebase
                            guardarDiasEntregaEnFirebase(diasEntrega)
                            // Actualizar la informació³n de dó­as extra en la interfaz
                            runOnUiThread {
                            actualizarInformacionDiasExtra()
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("PedidoPanificadoraActivity", "âŒ Error cargando dó­as de entrega desde Firebase: ${e.message}")
                        // Actualizar la informació³n de dó­as extra en la interfaz con valores por defecto
                        runOnUiThread {
                        actualizarInformacionDiasExtra()
                        }
                    }
                    
            } catch (e: Exception) {
                Log.e("PedidoPanificadoraActivity", "Error cargando dó­as de entrega: ${e.message}")
            }
        }
    }
    
    /**
     * Carga la configuració³n desde Firebase para el local actual
     */
    private fun cargarConfiguracionDesdeFirebase() {
        lifecycleScope.launch {
            try {
                val localId = obtenerLocalId()
                Log.d("CONFIG_LOAD", "ðŸ” INICIANDO CARGA DE CONFIGURACIó“N")
                Log.d("CONFIG_LOAD", "ðŸ“ LocalId: $localId")
                Log.d("CONFIG_LOAD", "ðŸ“ Ruta: locales/$localId/configuracion_pedido_panificadora/config")
                
                val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
                
                // Cargar desde el local especó­fico: locales/{localId}/configuracion_pedido_panificadora
                firestore.collection("locales")
                    .document(localId)
                    .collection("configuracion_pedido_panificadora")
                    .document("config")
                    .get()
                    .addOnSuccessListener { document ->
                        Log.d("CONFIG_LOAD", "âœ… DOCUMENTO OBTENIDO DE FIREBASE")
                        Log.d("CONFIG_LOAD", "ðŸ“„ Documento existe: ${document.exists()}")
                        Log.d("CONFIG_LOAD", "ðŸ“„ Documento ID: ${document.id}")
                        Log.d("CONFIG_LOAD", "ðŸ“„ Datos completos: ${document.data}")
                            if (document.exists()) {
                            Log.d("CONFIG_LOAD", "ðŸ“‹ EXTRAYENDO DATOS DEL DOCUMENTO")
                            
                                val porcentajeExtra = document.getLong("porcentajeExtra")?.toInt() ?: 30
                                val horaAlerta = document.getString("horaAlerta") ?: "15:30"
                                val usarAjusteTendencia = document.getBoolean("usarAjusteTendencia") ?: true
                            
                            Log.d("CONFIG_LOAD", "ðŸ“Š PorcentajeExtra extraó­do: $porcentajeExtra")
                            Log.d("CONFIG_LOAD", "â° HoraAlerta extraó­da: $horaAlerta")
                            
                            // ðŸ”§ NO cargar dó­as de entrega aquó­ - se cargan por separado en cargarDiasEntrega()
                            // Los dó­as de entrega se cargan desde configuracion_dias_entrega_panificadora (colecció³n separada)
                                
                                configuracion = ConfiguracionPedidoFabrica(
                                    porcentajeExtra = porcentajeExtra,
                                    horaAlerta = horaAlerta,
                                    usarAjusteTendencia = usarAjusteTendencia
                                )
                            
                            // ðŸ”¥ SINCRONIZAR porcentajeSeleccionado con la configuració³n cargada
                            porcentajeSeleccionado = porcentajeExtra.toDouble()
                            Log.d("CONFIG_LOAD", "ðŸ”„ porcentajeSeleccionado sincronizado: $porcentajeSeleccionado")
                            
                            Log.d("CONFIG_LOAD", "ðŸ”„ ACTUALIZANDO UI")
                            
                            // Actualizar UI en el hilo principal
                            runOnUiThread {
                                Log.d("CONFIG_LOAD", "ðŸŽ¨ Actualizando txtPorcentajeExtra: % Extra: ${configuracion.porcentajeExtra}%")
                                txtPorcentajeExtra.text = "% Extra: ${configuracion.porcentajeExtra}%"

                                Log.d("CONFIG_LOAD", "ðŸŽ¨ Actualizando txtHoraAlerta: ðŸ”” Alerta: ${configuracion.horaAlerta}")
                                txtHoraAlerta.text = "ðŸ”” Alerta: ${configuracion.horaAlerta}"

                                Log.d("CONFIG_LOAD", "ðŸŽ¨ Llamando actualizarInformacionDiasExtra()")
                                actualizarInformacionDiasExtra()

                                Log.d("CONFIG_LOAD", "âœ… UI ACTUALIZADA COMPLETAMENTE")
                                Log.d("CONFIG_LOAD", "ðŸ” VERIFICACIó“N FINAL - txtPorcentajeExtra.text: ${txtPorcentajeExtra.text}")
                                Log.d("CONFIG_LOAD", "ðŸ” VERIFICACIó“N FINAL - txtHoraAlerta.text: ${txtHoraAlerta.text}")
                                Log.d("CONFIG_LOAD", "ðŸ” VERIFICACIó“N FINAL - diasEntrega: $diasEntrega")
                                Log.d("CONFIG_LOAD", "ðŸ” VERIFICACIó“N FINAL - porcentajeSeleccionado: $porcentajeSeleccionado")
                            }
                                
                                // Actualizar tambió©n en el repositorio local
                                repository.actualizarConfiguracion(configuracion)
                                
                            Log.d("CONFIG_LOAD", "ðŸ’¾ Configuració³n actualizada en repositorio local")
                            Log.d("CONFIG_LOAD", "ðŸŽ‰ CARGA COMPLETA EXITOSA")
                            
                            // ðŸ”§ EJECUTAR cargarDatosParaFecha DESPUó‰S de cargar la configuració³n
                            Log.d("CONFIG_LOAD", "ðŸ”„ Ejecutando cargarDatosParaFecha despuó©s de cargar configuració³n")
                            lifecycleScope.launch {
                                cargarDatosParaFecha(fechaSeleccionada)
                                
                                // ðŸ” VERIFICAR VALORES DESPUó‰S DE TODO EL PROCESO
                                runOnUiThread {
                                    Log.d("CONFIG_LOAD", "ðŸ” DESPUó‰S DE TODO - txtPorcentajeExtra.text: ${txtPorcentajeExtra.text}")
                                    Log.d("CONFIG_LOAD", "ðŸ” DESPUó‰S DE TODO - txtHoraAlerta.text: ${txtHoraAlerta.text}")
                                    Log.d("CONFIG_LOAD", "ðŸ” DESPUó‰S DE TODO - diasEntrega: $diasEntrega")
                                }
                            }
                            } else {
                            Log.d("CONFIG_LOAD", "âŒ DOCUMENTO NO EXISTE EN FIREBASE")
                            Log.d("CONFIG_LOAD", "ðŸ“ Usando valores por defecto")
                            Log.d("CONFIG_LOAD", "ðŸ“Š Porcentaje por defecto: ${configuracion.porcentajeExtra}")
                            Log.d("CONFIG_LOAD", "â° Hora por defecto: ${configuracion.horaAlerta}")
                            
                            // ðŸ”¥ SINCRONIZAR porcentajeSeleccionado con valor por defecto
                            porcentajeSeleccionado = configuracion.porcentajeExtra.toDouble()
                            Log.d("CONFIG_LOAD", "ðŸ”„ porcentajeSeleccionado sincronizado con defecto: $porcentajeSeleccionado")
                            
                                // Actualizar UI con valores por defecto
                            runOnUiThread {
                                Log.d("CONFIG_LOAD", "ðŸŽ¨ Actualizando UI con valores por defecto")
                                txtPorcentajeExtra.text = "% Extra: ${configuracion.porcentajeExtra}%"
                                txtHoraAlerta.text = "ðŸ”” Alerta: ${configuracion.horaAlerta}"
                            }
                                // Guardar configuració³n por defecto en Firebase
                            Log.d("CONFIG_LOAD", "ðŸ’¾ Guardando configuració³n por defecto en Firebase")
                                guardarConfiguracionEnFirebase(configuracion)
                            
                            // ðŸ”§ EJECUTAR cargarDatosParaFecha DESPUó‰S de guardar configuració³n por defecto
                            Log.d("CONFIG_LOAD", "ðŸ”„ Ejecutando cargarDatosParaFecha despuó©s de guardar configuració³n por defecto")
                            lifecycleScope.launch {
                                cargarDatosParaFecha(fechaSeleccionada)
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("CONFIG_LOAD", "âŒ ERROR AL CARGAR CONFIGURACIó“N DESDE FIREBASE")
                        Log.e("CONFIG_LOAD", "ðŸš¨ Error: ${e.message}")
                        Log.e("CONFIG_LOAD", "ðŸš¨ Exception: ${e.toString()}")
                        
                        // ðŸ”§ EJECUTAR cargarDatosParaFecha AóšN EN CASO DE ERROR
                        Log.d("CONFIG_LOAD", "ðŸ”„ Ejecutando cargarDatosParaFecha despuó©s de error en Firebase")
                        lifecycleScope.launch {
                            cargarDatosParaFecha(fechaSeleccionada)
                        }
                    }
                    
            } catch (e: Exception) {
                Log.e("CONFIG_LOAD", "ðŸš¨ EXCEPCIó“N AL CARGAR CONFIGURACIó“N")
                Log.e("CONFIG_LOAD", "ðŸš¨ Exception: ${e.toString()}")
                Log.e("CONFIG_LOAD", "ðŸš¨ Message: ${e.message}")
                
                // ðŸ”§ EJECUTAR cargarDatosParaFecha AóšN EN CASO DE EXCEPCIó“N
                Log.d("CONFIG_LOAD", "ðŸ”„ Ejecutando cargarDatosParaFecha despuó©s de excepció³n")
                lifecycleScope.launch {
                    cargarDatosParaFecha(fechaSeleccionada)
                }
            }
        }
    }
    
    private fun actualizarSugerenciasEnTiempoReal(nuevoPorcentaje: Int) {
        Log.d("PedidoPanificadoraActivity", "ðŸ”„ Actualizando sugerencias en tiempo real con porcentaje: $nuevoPorcentaje%")
        
        // Simular el cambio de texto en todos los EditText para activar los TextWatcher existentes
        val scrollView = findViewById<ScrollView>(R.id.scrollViewContenido)
        if (scrollView != null) {
            simularCambioTextoEnEditTexts(scrollView)
        } else {
            Log.e("PedidoPanificadoraActivity", "âŒ ScrollView no encontrado!")
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
                    // ðŸ”¥ SOLO ACTUALIZAR SI YA TIENE DATOS INGRESADOS
                    val textoActual = child.text.toString().trim()
                    if (textoActual.isNotEmpty() && textoActual != "0") {
                        // Simular un cambio de texto para activar el TextWatcher
                        child.setText("") // Limpiar temporalmente
                        child.setText(textoActual) // Restaurar - esto activaró¡ el TextWatcher
                        Log.d("PedidoPanificadoraActivity", "ðŸ”„ Actualizando sugerencia para producto con datos: $textoActual")
                    } else {
                        Log.d("PedidoPanificadoraActivity", "â­ï¸ Saltando producto sin datos: '$textoActual'")
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
        Log.d("PedidoPanificadoraActivity", "ðŸ” Recolectando en LinearLayout con ${layout.childCount} hijos")
        
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            when (child) {
                is EditText -> {
                    // Intentar obtener el có³digo del producto desde el tag
                    val codigo = child.tag as? String ?: "GENERICO"
                    editTexts[codigo] = child
                    Log.d("PedidoPanificadoraActivity", "ðŸ“ EditText encontrado para có³digo: $codigo")
                }
                is TextView -> {
                    val texto = child.text.toString()
                    Log.d("PedidoPanificadoraActivity", "ðŸ“„ TextView encontrado: '$texto'")
                    if (texto.contains("ðŸ’¡") || texto.contains("Sugerencia") || texto.matches(Regex("\\d+"))) {
                        // Intentar asociar con el EditText mó¡s cercano
                        val codigo = encontrarCodigoMasCercano(child, editTexts)
                        if (codigo != null) {
                            txtSugerencias[codigo] = child
                            Log.d("PedidoPanificadoraActivity", "âœ… TextView de sugerencia asociado con có³digo: $codigo")
                        } else {
                            Log.w("PedidoPanificadoraActivity", "âš ï¸ No se pudo asociar TextView de sugerencia: '$texto'")
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
        // Buscar el EditText mó¡s cercano en la jerarquó­a
        var parent = textView.parent
        while (parent != null) {
            if (parent is LinearLayout) {
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (child is EditText) {
                        // Encontrar el có³digo correspondiente a este EditText
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
            
            // Crear timeout de seguridad (5 segundos mó¡ximo)
            skeletonTimeout = lifecycleScope.launch {
                kotlinx.coroutines.delay(5000)
                Log.w("PedidoPanificadoraActivity", "âš ï¸ Timeout del skeleton loading - forzando ocultació³n")
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
                Log.d("PedidoPanificadoraActivity", "âœ… Skeleton loading ocultado")
            } catch (e: Exception) {
                Log.e("PedidoPanificadoraActivity", "âŒ Error ocultando skeleton loading", e)
            }
        }
    }
    
    /**
     * Obtiene el historial de stock bajo (â‰¤ 15) de los óºltimos 7 dó­as para un producto
     */
    private suspend fun obtenerHistorialStockBajo(codigo: String): List<Pair<String, Int>> {
        return try {
            val historial = mutableListOf<Pair<String, Int>>()
            val calendar = DateHelper.getCalendar()
            
            // Buscar en los óºltimos 7 dó­as
            for (i in 1..7) {
                calendar.time = Date()
                calendar.add(java.util.Calendar.DAY_OF_MONTH, -i)
                val fecha = DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
                
                // Obtener registros de esa fecha usando el repositorio local
                val registros = localRepository.getRegistrosByFecha(localId, fecha).first()
                
                // Buscar el producto especó­fico en registros de "Stock Final"
                val registroStockFinal = registros.find { it.tipo == "Stock Final" }
                if (registroStockFinal != null) {
                    Log.d("HISTORIAL_STOCK", "ðŸ” Buscando producto '$codigo' en fecha $fecha")
                    Log.d("HISTORIAL_STOCK", "ðŸ” Productos disponibles: ${registroStockFinal.productos.map { "${it.nombre}:${it.cantidad}" }}")
                    
                    // Buscar por có³digo al inicio del nombre (ej: "HU - Humita" contiene "HU")
                    val producto = registroStockFinal.productos.find { it.nombre.startsWith("$codigo -") }
                    if (producto != null) {
                        Log.d("HISTORIAL_STOCK", "ðŸ” Producto encontrado: ${producto.nombre} con cantidad ${producto.cantidad}")
                        
                        // Determinar umbral segóºn el tipo de producto
                        val umbral = when {
                            codigo.startsWith("MUZZA") || codigo.startsWith("JAMON") || codigo.startsWith("PEPPER") -> 0 // Solo sin stock (es normal tener 1-2 pizzas)
                            else -> 15 // Umbral normal para otros productos
                        }
                        
                        if (producto.cantidad <= umbral) {
                            historial.add(Pair(fecha, producto.cantidad))
                            Log.d("HISTORIAL_STOCK", "âœ… Agregado a historial: $fecha -> ${producto.cantidad} (umbral: $umbral)")
                        } else {
                            Log.d("HISTORIAL_STOCK", "âŒ Stock no es bajo: ${producto.cantidad} > $umbral")
                        }
                    } else {
                        Log.d("HISTORIAL_STOCK", "âŒ Producto '$codigo' no encontrado en fecha $fecha")
                    }
                } else {
                    Log.d("HISTORIAL_STOCK", "âŒ No hay registro 'Stock Final' para fecha $fecha")
                }
            }
            
            Log.d("HISTORIAL_STOCK", "Stock bajo encontrado para $codigo: ${historial.size} dó­as")
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
        
        // Colores expló­citos para asegurar visibilidad
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
        
        // Tó­tulo
        val titulo = TextView(this).apply {
            text = "âš ï¸ Stock Bajo Detectado"
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
            text = "En los óºltimos 7 dó­as, este producto quedó³ con stock bajo (â‰¤ 15 unidades):"
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
            Log.d("REPORTE_STOCK", "Historial vacó­o para $codigo")
            val sinDatos = TextView(this).apply {
                text = "No se encontraron registros de stock bajo"
                textSize = 16f
                // Usar color expló­cito para asegurar visibilidad
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
                    text = "ðŸ“… $fechaFormateada â†’ $stock unidades restantes"
                    textSize = 16f
                    // Usar colores expló­citos para asegurar visibilidad
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
        
        // Recomendació³n
        val recomendacion = TextView(this).apply {
            text = "ðŸ’¡ Considera reforzar el pedido para evitar quedarte sin stock."
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
        
        // Configurar fondo del dió¡logo para modo oscuro
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
        val opciones = arrayOf(
            "ðŸ“ˆ Ver Tendencia de Ventas",
            "âš ï¸ Ver Historial de Stock Bajo"
        )
        
        AlertDialog.Builder(this)
            .setTitle("$codigo - Informació³n Disponible")
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
     * Vista personalizada para dibujar gró¡fico de ló­neas
     */
    private inner class GraficoLineas(context: android.content.Context) : android.view.View(context) {
        var historial: List<Triple<String, Double, Double>> = emptyList()
        var codigoProducto: String = ""
        var nombreProducto: String = ""
        var tendencia: Double = 0.0  // Agregar campo para la tendencia
        
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (historial.isEmpty()) return
            
            // ðŸŽ¨ ESTILO WEB: Colores y diseó±o como la versió³n web
            val colorMorado = android.graphics.Color.parseColor("#7b2cbf")  // Venta real
            val colorNaranja = android.graphics.Color.parseColor("#f59e0b") // Promedio
            val colorGrisClaro = android.graphics.Color.parseColor("#e5e7eb") // Ejes
            val colorGrisOscuro = android.graphics.Color.parseColor("#d1d5db") // Ló­neas guó­a
            
            // Fondo blanco o negro segóºn tema
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
            
            // Mó¡rgenes como en web (ajustado para tó­tulo + subtó­tulo)
            val marginLeft = 120f
            val marginRight = 60f
            val marginTop = 120f  // Espacio para tó­tulo + subtó­tulo
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
            
            // Tó­tulo del producto (morado como en web)
            textPaint.textAlign = android.graphics.Paint.Align.CENTER
            textPaint.textSize = 42f
            textPaint.color = colorMorado
            textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            val titulo = if (codigoProducto.isNotEmpty()) "$codigoProducto - $nombreProducto" else "Gró¡fico de Tendencia"
            canvas.drawText(titulo, width / 2f, 50f, textPaint)
            
            // Subtó­tulo con tendencia (verde/naranja segóºn valor, como en web)
            val iconoTendencia = when {
                tendencia >= 10 -> "ðŸ“ˆ"
                tendencia >= 5 -> "â¬†ï¸"
                tendencia <= -10 -> "ðŸ“‰"
                else -> "â¬‡ï¸"
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
            
            // Dibujar ló­neas guó­a horizontales y etiquetas Y
            val numYLabels = 5
            for (i in 0..numYLabels) {
                val value = minValue + (valueRange * i / numYLabels)
                val y = height - marginBottom - (graphHeight * i / numYLabels)
                
                // Ló­neas guó­a (gris mó¡s oscuro como en web)
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
            
            // Funció³n para convertir valor a coordenada Y
            fun valueToY(value: Double): Float {
                val normalized = (value - minValue) / valueRange
                return height - marginBottom - (graphHeight * normalized).toFloat()
            }
            
            // Funció³n para convertir ó­ndice a coordenada X
            fun indexToX(index: Int): Float {
                return marginLeft + (graphWidth * index / (historial.size - 1).coerceAtLeast(1))
            }
            
            // Dibujar etiquetas X (fechas) - formato dd/mm como en web
            historial.forEachIndexed { index, (fecha, _, _) ->
                // Mostrar solo primera, óºltima y algunas intermedias para no amontonar
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
            
            // ðŸ“Š Dibujar ló­nea de PROMEDIO (naranja punteada como en web)
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
            
            // ðŸ“ˆ Dibujar ló­nea de VENTA REAL (morada só³lida como en web)
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
            
            // Venta Real (morado con ló­nea)
            paint.color = colorMorado
            paint.strokeWidth = 6f
            paint.style = android.graphics.Paint.Style.STROKE
            canvas.drawLine(width / 2f - 200, legendY, width / 2f - 150, legendY, paint)
            canvas.drawText("Venta Real", width / 2f - 90, legendY + 10, textPaint)
            
            // Promedio (naranja punteado con ló­nea)
            paint.color = colorNaranja
            paint.strokeWidth = 4f
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
            canvas.drawLine(width / 2f + 50, legendY, width / 2f + 100, legendY, paint)
            paint.pathEffect = null
            canvas.drawText("Promedio", width / 2f + 160, legendY + 10, textPaint)
        }
    }
    
    /**
     * ðŸ“ˆ Muestra gró¡fico de tendencia de ventas cuando se toca el ó­cono
     */
    private fun mostrarGraficoTendencia(codigo: String, tendencia: Double, historial: List<Triple<String, Double, Double>>) {
        Log.d("GRAFICO_TENDENCIA", "Mostrando gró¡fico para $codigo con ${historial.size} dó­as")
        
        val nombreProducto = try {
            obtenerNombreProducto(codigo)
        } catch (e: Exception) {
            codigo
        }
        
        // Layout principal (sin padding para que el gró¡fico ocupe todo)
        val layoutPrincipal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        
        // GRóFICO DE LóNEAS VISUAL
        if (historial.isEmpty()) {
            val sinDatos = TextView(this).apply {
                text = "No hay datos suficientes para mostrar el gró¡fico"
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
                this.tendencia = tendencia  // Pasar la tendencia al gró¡fico
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (500 * resources.displayMetrics.density).toInt() // 500dp para tó­tulo + subtó­tulo + gró¡fico
                )
                setPadding(0, 0, 0, 0)
            }
            layoutPrincipal.addView(graficoView)
        }
        
        // Mensaje compacto
        val mensajeFinal = TextView(this).apply {
            text = if (tendencia >= 5) {
                "ðŸ’¡ Sugerencias ajustadas automó¡ticamente"
            } else {
                "ðŸ’¡ Considera reducir el pedido"
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
        
        // Crear dió¡logo SIN tó­tulo ni padding extra
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
            
            // ELIMINAR padding del contenedor principal del dió¡logo
            val containerView = layoutPrincipal.parent as? android.view.ViewGroup
            containerView?.setPadding(0, 0, 0, 0)
            
            // ELIMINAR padding del decorView tambió©n
            dialog.window?.decorView?.setPadding(0, 0, 0, 0)
            
            Log.d("GRAFICO_DEBUG", "ðŸŽ¨ Dió¡logo mostrado - todos los paddings eliminados")
        }
        
        // Agregar botó³n Entendido manualmente ABAJO
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
}

