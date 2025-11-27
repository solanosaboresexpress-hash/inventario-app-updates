package com.tuapp.inventario

import android.app.Activity
import android.app.DatePickerDialog
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import com.tuapp.inventario.util.DateHelper
import androidx.core.content.ContextCompat
import android.util.TypedValue
import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
import java.util.*

/**
 * Pantalla para que los locales carguen vencimientos de productos
 */
class VencimientosActivity : AppCompatActivity() {
    
    private lateinit var spinnerProducto: Spinner
    private lateinit var edtCantidad: EditText
    private lateinit var btnFechaVencimiento: Button
    private lateinit var btnFechaAnterior: Button
    private lateinit var btnFechaSiguiente: Button
    private lateinit var btnProductoAnterior: Button
    private lateinit var btnProductoSiguiente: Button
    private lateinit var btnAgregarLote: Button
    private lateinit var btnWhatsApp: Button
    private lateinit var layoutLotesCargados: LinearLayout
    private lateinit var scrollViewLotes: ScrollView
    private lateinit var layoutLoading: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var txtLoading: TextView
    
    private val repository = VencimientoRepository()
    private var fechaVencimientoSeleccionada: String = ""
    private lateinit var localId: String
    private var lotesActivos = mutableListOf<VencimientoLote>()
    private var stockActualPorProducto = mutableMapOf<String, Int>()
    private lateinit var localRepository: com.tuapp.inventario.repository.LocalInventarioRepository
    private var isLoading = false // Flag para evitar recargas múltiples
    private var pollingActivo = false // Flag para controlar el polling de stock
    
    // Lista de productos disponibles (mismos nombres que en PedidoFabricaActivity)
    private val productos = mapOf(
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
        setContentView(R.layout.activity_vencimientos)
        
        // Obtener localId de SharedPreferences
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        localId = prefs.getString("localId", "") ?: ""
        val localNombre = prefs.getString("localNombre", "Local") ?: "Local"
        
        if (localId.isEmpty()) {
            Toast.makeText(this, "Error: No se encontró el local", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        inicializarVistas()
        configurarSpinnerProductos()
        configurarBotones()
        
        // Configurar título
        val txtTitulo = findViewById<TextView>(R.id.txtTitulo)
        txtTitulo?.text = "📅 Vencimientos - $localNombre"
        
        // Cargar datos solo una vez al crear la actividad
        cargarLotesActivos()
    }
    
    override fun onResume() {
        super.onResume()
        // NO recargar automáticamente - solo se recarga manualmente o al agregar lotes
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Detener polling si está activo
        pollingActivo = false
    }
    
    private fun inicializarVistas() {
        spinnerProducto = findViewById(R.id.spinnerProducto)
        edtCantidad = findViewById(R.id.edtCantidad)
        btnFechaVencimiento = findViewById(R.id.btnFechaVencimiento)
        btnFechaAnterior = findViewById(R.id.btnFechaAnterior)
        btnFechaSiguiente = findViewById(R.id.btnFechaSiguiente)
        btnProductoAnterior = findViewById(R.id.btnProductoAnterior)
        btnProductoSiguiente = findViewById(R.id.btnProductoSiguiente)
        btnAgregarLote = findViewById(R.id.btnAgregarLote)
        btnWhatsApp = findViewById(R.id.btnWhatsApp)
        layoutLotesCargados = findViewById(R.id.layoutLotesCargados)
        scrollViewLotes = findViewById(R.id.scrollViewLotes)
        layoutLoading = findViewById(R.id.layoutLoading)
        progressBar = findViewById(R.id.progressBar)
        txtLoading = findViewById(R.id.txtLoading)
        
        // Configurar botón WhatsApp
        btnWhatsApp.setOnClickListener {
            generarYEnviarInformeWhatsApp()
        }
        
        // ✅ Agregar opción de sincronización manual (mantener presionado el botón WhatsApp)
        btnWhatsApp.setOnLongClickListener {
            mostrarDialogoSincronizacion()
            true
        }
        
        // Mostrar loading al inicio
        layoutLoading.visibility = View.VISIBLE
        scrollViewLotes.visibility = View.GONE
        
        // Inicializar fecha con mañana por defecto
        val calendar = DateHelper.getCalendar()
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        fechaVencimientoSeleccionada = DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
        actualizarTextoFecha()
        
        // Configurar colores dinámicos para EditText de cantidad
        configurarColoresEditText()
        
        // Inicializar repositorio local
        localRepository = (application as InventarioApplication).localRepository
    }
    
    /**
     * Configura los colores del EditText de cantidad según el modo oscuro
     */
    private fun configurarColoresEditText() {
        val isDark = isDarkMode()
        val textColor = if (isDark) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.BLACK
        }
        val backgroundColor = if (isDark) {
            android.graphics.Color.parseColor("#1E1E1E")
        } else {
            android.graphics.Color.WHITE
        }
        val borderColor = if (isDark) {
            android.graphics.Color.parseColor("#FFD700")
        } else {
            resources.getColor(R.color.brand_gold, null)
        }
        val hintColor = if (isDark) {
            android.graphics.Color.parseColor("#CCCCCC")
        } else {
            android.graphics.Color.parseColor("#808080")
        }
        
        // Crear drawable con fondo y borde para el EditText
        val editTextDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = 8f * resources.displayMetrics.density
            setStroke((2 * resources.displayMetrics.density).toInt(), borderColor)
        }
        edtCantidad.background = editTextDrawable
        edtCantidad.setTextColor(textColor)
        edtCantidad.setHintTextColor(hintColor)
    }
    
    private fun configurarSpinnerProductos() {
        val nombresProductos = productos.values.toList()
        
        // Crear adapter personalizado con colores dinámicos
        val isDark = isDarkMode()
        val textColor = if (isDark) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.BLACK
        }
        val backgroundColor = if (isDark) {
            android.graphics.Color.parseColor("#1E1E1E")
        } else {
            android.graphics.Color.WHITE
        }
        val borderColor = if (isDark) {
            android.graphics.Color.parseColor("#404040")
        } else {
            android.graphics.Color.parseColor("#E0E0E0")
        }
        
        // Crear drawable con fondo y borde para el Spinner
        val spinnerDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = 8f * resources.displayMetrics.density
            setStroke((2 * resources.displayMetrics.density).toInt(), borderColor)
        }
        spinnerProducto.background = spinnerDrawable
        
        // Crear adapter personalizado con colores dinámicos
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, nombresProductos) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView?.setTextColor(textColor)
                textView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                return view
            }
            
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView?.setTextColor(textColor)
                textView?.setBackgroundColor(backgroundColor)
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerProducto.adapter = adapter
        
        // Listener para cuando se selecciona un producto
        spinnerProducto.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                completarCantidadFaltante()
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // No hacer nada
            }
        }
    }
    
    /**
     * Encuentra el siguiente producto que tenga stock sin lotes cargados
     */
    private fun encontrarSiguienteProductoConStockSinLotes(productoActualCodigo: String): String? {
        val nombresProductos = productos.values.toList()
        val posicionActual = nombresProductos.indexOf(productos[productoActualCodigo] ?: "")
        
        // Buscar desde la posición actual + 1 hasta el final, luego desde el inicio
        val productosABuscar = nombresProductos.subList(posicionActual + 1, nombresProductos.size) + 
                              nombresProductos.subList(0, posicionActual + 1)
        
        for (productoNombre in productosABuscar) {
            val codigo = productos.entries.find { it.value == productoNombre }?.key ?: ""
            if (codigo.isEmpty()) continue
            
            val codigoNormalizado = normalizarCodigoProducto(codigo)
            val stockActual = stockActualPorProducto.entries.find { 
                normalizarCodigoProducto(it.key) == codigoNormalizado 
            }?.value ?: 0
            
            if (stockActual > 0) {
                val sumaLotesCargados = lotesActivos
                    .filter { normalizarCodigoProducto(it.productoCodigo) == codigoNormalizado }
                    .sumOf { it.cantidad }
                
                val cantidadFaltante = stockActual - sumaLotesCargados
                
                if (cantidadFaltante > 0) {
                    Log.d("VencimientosActivity", "✅ Encontrado siguiente producto con stock sin lotes: $productoNombre (Faltan: $cantidadFaltante)")
                    return productoNombre
                }
            }
        }
        
        Log.d("VencimientosActivity", "ℹ️ No hay más productos con stock sin lotes")
        return null
    }
    
    /**
     * Busca y selecciona automáticamente el primer producto que tenga stock sin lotes
     */
    private fun buscarPrimerProductoConStockSinLotes() {
        val nombresProductos = productos.values.toList()
        
        // Buscar el primer producto con stock sin lotes
        for (productoNombre in nombresProductos) {
            val codigo = productos.entries.find { it.value == productoNombre }?.key ?: ""
            if (codigo.isEmpty()) continue
            
            val codigoNormalizado = normalizarCodigoProducto(codigo)
            val stockActual = stockActualPorProducto.entries.find { 
                normalizarCodigoProducto(it.key) == codigoNormalizado 
            }?.value ?: 0
            
            if (stockActual <= 0) continue
            
            val sumaLotesCargados = lotesActivos
                .filter { normalizarCodigoProducto(it.productoCodigo) == codigoNormalizado }
                .sumOf { it.cantidad }
            
            val cantidadFaltante = stockActual - sumaLotesCargados
            
            if (cantidadFaltante > 0) {
                val posicion = nombresProductos.indexOf(productoNombre)
                if (posicion >= 0 && posicion != spinnerProducto.selectedItemPosition) {
                    Log.d("VencimientosActivity", "🎯 Seleccionando automáticamente primer producto con stock sin lotes: $productoNombre (Faltan: $cantidadFaltante)")
                    spinnerProducto.setSelection(posicion, true)
                    return
                }
            }
        }
        
        Log.d("VencimientosActivity", "ℹ️ No se encontró ningún producto con stock sin lotes para seleccionar automáticamente")
    }
    
    /**
     * Pasa automáticamente al siguiente producto que tenga stock sin lotes
     */
    private fun pasarAlSiguienteProductoConStockSinLotes() {
        val productoActualNombre = spinnerProducto.selectedItem.toString()
        val productoActualCodigo = productos.entries.find { it.value == productoActualNombre }?.key ?: ""
        
        if (productoActualCodigo.isEmpty()) {
            completarCantidadFaltante()
            return
        }
        
        val siguienteProducto = encontrarSiguienteProductoConStockSinLotes(productoActualCodigo)
        
        if (siguienteProducto != null) {
            val nombresProductos = productos.values.toList()
            val nuevaPosicion = nombresProductos.indexOf(siguienteProducto)
            
            if (nuevaPosicion >= 0) {
                Log.d("VencimientosActivity", "🔄 Cambiando automáticamente a: $siguienteProducto")
                spinnerProducto.setSelection(nuevaPosicion, true)
                // El listener del spinner se activará automáticamente y completará la cantidad
            } else {
                completarCantidadFaltante()
            }
        } else {
            // No hay más productos con stock sin lotes, mostrar mensaje
            runOnUiThread {
                edtCantidad.setText("")
                edtCantidad.hint = "✅ Todos los productos con stock están loteados"
                Toast.makeText(this, "✅ ¡Todos los productos con stock están loteados!", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun completarCantidadFaltante() {
        lifecycleScope.launch {
            try {
                val productoNombre = spinnerProducto.selectedItem.toString()
                val productoCodigo = productos.entries.find { it.value == productoNombre }?.key ?: ""
                
                if (productoCodigo.isEmpty()) {
                    edtCantidad.setText("")
                    return@launch
                }
                
                // Normalizar código
                val codigoNormalizado = normalizarCodigoProducto(productoCodigo)
                
                // Obtener stock actual del producto
                val stockActual = stockActualPorProducto.entries.find { 
                    normalizarCodigoProducto(it.key) == codigoNormalizado 
                }?.value ?: 0
                
                // Calcular suma de lotes ya cargados para este producto
                val sumaLotesCargados = lotesActivos
                    .filter { normalizarCodigoProducto(it.productoCodigo) == codigoNormalizado }
                    .sumOf { it.cantidad }
                
                // Calcular cantidad faltante
                val cantidadFaltante = stockActual - sumaLotesCargados
                
                // Actualizar campo cantidad solo si hay stock y falta lotear
                runOnUiThread {
                    if (cantidadFaltante > 0) {
                        edtCantidad.setText(cantidadFaltante.toString())
                        // Seleccionar el texto para que el usuario pueda editarlo fácilmente
                        edtCantidad.selectAll()
                    } else if (stockActual > 0 && sumaLotesCargados >= stockActual) {
                        // Ya está todo lotado
                        edtCantidad.setText("")
                        edtCantidad.hint = "Todo lotado (Stock: $stockActual)"
                    } else {
                        // No hay stock o no hay datos
                        edtCantidad.setText("")
                        edtCantidad.hint = if (stockActual > 0) "Stock: $stockActual" else "Ej: 200"
                    }
                }
            } catch (e: Exception) {
                Log.e("VencimientosActivity", "Error completando cantidad: ${e.message}")
            }
        }
    }
    
    private fun configurarBotones() {
        // Botón para seleccionar fecha de vencimiento (abre calendario)
        btnFechaVencimiento.setOnClickListener {
            mostrarDatePicker()
        }
        
        // Botón para retroceder fecha
        btnFechaAnterior.setOnClickListener {
            cambiarFecha(-1)
        }
        
        // Botón para avanzar fecha
        btnFechaSiguiente.setOnClickListener {
            cambiarFecha(1)
        }
        
        // Botón para producto anterior (arriba)
        btnProductoAnterior.setOnClickListener {
            cambiarProducto(-1)
        }
        
        // Botón para producto siguiente (abajo)
        btnProductoSiguiente.setOnClickListener {
            cambiarProducto(1)
        }
        
        // Botón para agregar lote
        btnAgregarLote.setOnClickListener {
            agregarNuevoLote()
        }
    }
    
    private fun cambiarProducto(direccion: Int) {
        val nombresProductos = productos.values.toList()
        val posicionActual = spinnerProducto.selectedItemPosition
        val nuevaPosicion = posicionActual + direccion
        
        // Validar límites (circular: si llega al final, vuelve al inicio y viceversa)
        val posicionFinal = when {
            nuevaPosicion < 0 -> nombresProductos.size - 1
            nuevaPosicion >= nombresProductos.size -> 0
            else -> nuevaPosicion
        }
        
        spinnerProducto.setSelection(posicionFinal, true)
        // El listener del spinner se activará automáticamente y completará la cantidad
    }
    
    private fun cambiarFecha(dias: Int) {
        try {
            val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
            val calendar = DateHelper.getCalendar()
            calendar.time = sdf.parse(fechaVencimientoSeleccionada) ?: Date()
            calendar.add(Calendar.DAY_OF_YEAR, dias)
            fechaVencimientoSeleccionada = sdf.format(calendar.time)
            actualizarTextoFecha()
        } catch (e: Exception) {
            Log.e("VencimientosActivity", "Error cambiando fecha: ${e.message}")
        }
    }
    
    private fun mostrarDatePicker() {
        val calendar = DateHelper.getCalendar()
        
        // Parsear la fecha actual
        try {
            val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
            calendar.time = sdf.parse(fechaVencimientoSeleccionada) ?: Date()
        } catch (e: Exception) {
            // Usar fecha actual si hay error
        }
        
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val fechaSeleccionada = DateHelper.getCalendar()
                fechaSeleccionada.set(year, month, dayOfMonth)
                fechaVencimientoSeleccionada = DateHelper.getDateFormat("yyyy-MM-dd")
                    .format(fechaSeleccionada.time)
                actualizarTextoFecha()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
    
    private fun actualizarTextoFecha() {
        try {
            val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
            val sdfDisplay = DateHelper.getDateFormat("dd/MM/yyyy")
            val fecha = sdf.parse(fechaVencimientoSeleccionada)
            btnFechaVencimiento.text = "📅 Vence: ${sdfDisplay.format(fecha!!)}"
        } catch (e: Exception) {
            btnFechaVencimiento.text = "📅 Seleccionar Fecha"
        }
    }
    
    private fun agregarNuevoLote() {
        // Validar cantidad
        val cantidad = edtCantidad.text.toString().toIntOrNull()
        if (cantidad == null || cantidad <= 0) {
            Toast.makeText(this, "Ingresá una cantidad válida", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Validar fecha
        if (fechaVencimientoSeleccionada.isEmpty()) {
            Toast.makeText(this, "Seleccioná una fecha de vencimiento", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Obtener producto seleccionado
        val productoNombre = spinnerProducto.selectedItem.toString()
        val productoCodigo = productos.entries.find { it.value == productoNombre }?.key ?: ""
        
        if (productoCodigo.isEmpty()) {
            Toast.makeText(this, "Error al obtener el producto", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Validar que el stock final de ayer esté cargado antes de permitir cargar lotes
        lifecycleScope.launch {
            try {
                val fechaHoy = DateHelper.getFechaActual()
                val fechaAyer = DateHelper.obtenerFechaAnterior(fechaHoy)
                
                val stockFinalAyerExiste = localRepository.existeRegistro(localId, fechaAyer, "Stock Final")
                
                if (!stockFinalAyerExiste) {
                    runOnUiThread {
                        val fechaAyerFormateada = DateHelper.formatearFecha(fechaAyer, "dd/MM/yyyy")
                        AlertDialog.Builder(this@VencimientosActivity)
                            .setTitle("⚠️ Stock Final Pendiente")
                            .setMessage("No podés cargar lotes porque falta cargar el Stock Final de ayer ($fechaAyerFormateada).\n\nPor favor, cargá el Stock Final de ayer primero.")
                            .setPositiveButton("Entendido", null)
                            .setNeutralButton("📊 Ir a Stock Final") { _, _ ->
                                // Abrir CargaActivity con Stock Final de ayer
                                val intent = Intent(this@VencimientosActivity, CargaActivity::class.java)
                                intent.putExtra("tipo", "Stock Final")
                                intent.putExtra("fecha", fechaAyer)
                                @Suppress("DEPRECATION")
                                startActivityForResult(intent, 1002) // Código de request para Stock Final (cargar lotes)
                            }
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .show()
                    }
                    return@launch
                }
                
                // Si el stock final de ayer existe, continuar con la carga del lote
                runOnUiThread {
                    // Crear nuevo lote
                    val fechaActual = DateHelper.getFechaActual()
                    val nuevoLote = VencimientoLote(
                        localId = localId,
                        productoCodigo = productoCodigo,
                        productoNombre = productoNombre,
                        cantidad = cantidad,
                        cantidadInicial = cantidad,
                        fechaVencimiento = fechaVencimientoSeleccionada,
                        fechaCarga = fechaActual
                    )
                    
                    // Guardar en Firebase
                    lifecycleScope.launch {
                        try {
                            val exito = repository.agregarLote(nuevoLote)
                            
                            if (exito) {
                                runOnUiThread {
                                    Toast.makeText(this@VencimientosActivity, "Lote agregado exitosamente", Toast.LENGTH_SHORT).show()
                                }
                                
                                // Mostrar loading mientras se recarga
                                runOnUiThread {
                                    layoutLoading.visibility = View.VISIBLE
                                    scrollViewLotes.visibility = View.GONE
                                }
                                
                                // Recargar lista y ESPERAR a que termine completamente
                                cargarLotesActivosCompleto()
                                
                                // Esperar un momento adicional para asegurar que la UI se actualizó
                                delay(300)
                                
                                // Verificar si el producto actual ya no tiene stock sin lotes
                                val productoActualNombre = spinnerProducto.selectedItem.toString()
                                val productoActualCodigo = productos.entries.find { it.value == productoActualNombre }?.key ?: ""
                                
                                if (productoActualCodigo.isNotEmpty()) {
                                    val codigoNormalizadoVerif = normalizarCodigoProducto(productoActualCodigo)
                                    val stockActualVerif = stockActualPorProducto.entries.find { 
                                        normalizarCodigoProducto(it.key) == codigoNormalizadoVerif 
                                    }?.value ?: 0
                                    
                                    val sumaLotesCargadosVerif = lotesActivos
                                        .filter { normalizarCodigoProducto(it.productoCodigo) == codigoNormalizadoVerif }
                                        .sumOf { it.cantidad }
                                    
                                    val cantidadFaltante = stockActualVerif - sumaLotesCargadosVerif
                                    
                                    Log.d("VencimientosActivity", "🔍 Verificando producto $productoActualCodigo:")
                                    Log.d("VencimientosActivity", "   Stock: $stockActualVerif, Lotes: $sumaLotesCargadosVerif, Faltante: $cantidadFaltante")
                                    
                                    runOnUiThread {
                                        // Si ya no hay stock sin lotes, pasar al siguiente producto con stock sin lotes
                                        if (cantidadFaltante <= 0 && stockActualVerif > 0) {
                                            Log.d("VencimientosActivity", "   ✅ Producto completado, buscando siguiente...")
                                            pasarAlSiguienteProductoConStockSinLotes()
                                        } else {
                                            // Actualizar cantidad faltante después de recargar
                                            completarCantidadFaltante()
                                        }
                                    }
                                } else {
                                    runOnUiThread {
                                        // Actualizar cantidad faltante después de recargar
                                        completarCantidadFaltante()
                                    }
                                }
                            } else {
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed) {
                                        Toast.makeText(this@VencimientosActivity, "Error al agregar lote", Toast.LENGTH_SHORT).show()
                                        // Asegurar que el loading se oculte incluso si hay error
                                        layoutLoading.visibility = View.GONE
                                        scrollViewLotes.visibility = View.VISIBLE
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            // Ignorar cancelaciones si la actividad está terminando
                            if (!isFinishing && !isDestroyed) {
                                Log.w("VencimientosActivity", "Operación cancelada: ${e.message}")
                                // Asegurar que el loading se oculte
                                runOnUiThread {
                                    layoutLoading.visibility = View.GONE
                                    scrollViewLotes.visibility = View.VISIBLE
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("VencimientosActivity", "Error agregando lote: ${e.message}", e)
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    Toast.makeText(this@VencimientosActivity, "Error al agregar lote", Toast.LENGTH_SHORT).show()
                                    // Asegurar que el loading se oculte incluso si hay error
                                    layoutLoading.visibility = View.GONE
                                    scrollViewLotes.visibility = View.VISIBLE
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VencimientosActivity", "Error verificando stock final: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@VencimientosActivity, "Error verificando stock final: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Carga los lotes activos y espera a que termine completamente (versión suspend)
     */
    private suspend fun cargarLotesActivosCompleto() {
        // Evitar recargas múltiples esperando a que termine la carga actual
        while (isLoading) {
            delay(100)
        }
        
        isLoading = true
        try {
            // MANTENER loading visible inicialmente
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    layoutLoading.visibility = View.VISIBLE
                    scrollViewLotes.visibility = View.GONE
                }
            }
            
            // PRIMERO: Cargar lotes activos (esto es rápido)
            lotesActivos.clear()
            lotesActivos.addAll(repository.obtenerLotesActivos(localId))
            Log.d("VencimientosActivity", "✅ Lotes cargados: ${lotesActivos.size}")
            
            // Calcular stock actual desde local (asegurar que esté completo)
            val stockCalculado = calcularStockActual()
            
            // ACTUALIZAR stock con datos locales disponibles
            stockActualPorProducto.clear()
            stockActualPorProducto.putAll(stockCalculado)
            
            Log.d("VencimientosActivity", "📊 Stock desde local: ${stockCalculado.size} productos, ${stockCalculado.values.count { it > 0 }} con stock > 0")
            
            // Pequeño delay para asegurar que el mapa esté completamente actualizado
            delay(200)
            
            // LIMPIAR Y MOSTRAR UI con datos locales
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    // Limpiar layout completamente ANTES de mostrar
                    layoutLotesCargados.removeAllViews()
                    
                    // Ocultar loading y mostrar contenido
                    layoutLoading.visibility = View.GONE
                    scrollViewLotes.visibility = View.VISIBLE
                    
                    // Mostrar UI con datos disponibles (stock ya está calculado)
                    mostrarLotesCargados()
                    
                    // Forzar invalidate y requestLayout para asegurar renderizado
                    layoutLotesCargados.invalidate()
                    layoutLotesCargados.requestLayout()
                    scrollViewLotes.invalidate()
                    scrollViewLotes.requestLayout()
                    
                    Log.d("VencimientosActivity", "✅ UI mostrada con datos locales (${stockActualPorProducto.size} productos en stock)")
                }
            }
            
            // Resetear flag al finalizar carga exitosa
            isLoading = false
        } catch (e: CancellationException) {
            // Ignorar cancelaciones si la actividad está terminando
            if (!isFinishing && !isDestroyed) {
                Log.w("VencimientosActivity", "Carga cancelada: ${e.message}")
            }
            // Asegurar que la UI se muestre incluso si se cancela
            runOnUiThread {
                layoutLoading.visibility = View.GONE
                scrollViewLotes.visibility = View.VISIBLE
                if (lotesActivos.isNotEmpty()) {
                    mostrarLotesCargados()
                }
            }
            // Resetear flag
            isLoading = false
            throw e
        } catch (e: Exception) {
            Log.e("VencimientosActivity", "Error cargando lotes: ${e.message}", e)
            runOnUiThread {
                // Ocultar loading incluso si hay error
                layoutLoading.visibility = View.GONE
                scrollViewLotes.visibility = View.VISIBLE
                
                // Mostrar lotes si se cargaron antes del error
                if (lotesActivos.isNotEmpty()) {
                    mostrarLotesCargados()
                }
                
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this@VencimientosActivity, "Error cargando datos", Toast.LENGTH_SHORT).show()
                }
            }
            // Resetear flag
            isLoading = false
            throw e
        }
    }
    
    private fun cargarLotesActivos() {
        // Evitar recargas múltiples
        if (isLoading) {
            Log.d("VencimientosActivity", "⏭️ Ya está cargando, omitiendo recarga")
            return
        }
        
        isLoading = true
        lifecycleScope.launch {
            try {
                // MANTENER loading visible inicialmente
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        layoutLoading.visibility = View.VISIBLE
                        scrollViewLotes.visibility = View.GONE
                    }
                }
                
                // PRIMERO: Cargar lotes activos (esto es rápido)
                lotesActivos.clear()
                lotesActivos.addAll(repository.obtenerLotesActivos(localId))
                Log.d("VencimientosActivity", "✅ Lotes cargados: ${lotesActivos.size}")
                
                // Calcular stock actual desde local (asegurar que esté completo)
                var stockCalculado = calcularStockActual()
                
                // ACTUALIZAR stock con datos locales disponibles
                stockActualPorProducto.clear()
                stockActualPorProducto.putAll(stockCalculado)
                
                Log.d("VencimientosActivity", "📊 Stock desde local: ${stockCalculado.size} productos, ${stockCalculado.values.count { it > 0 }} con stock > 0")
                stockActualPorProducto.forEach { (codigo, stock) ->
                    Log.d("VencimientosActivity", "   - $codigo: $stock")
                }
                
                // Pequeño delay para asegurar que el mapa esté completamente actualizado
                delay(200)
                
                // LIMPIAR Y MOSTRAR UI con datos locales (no esperar sincronización)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        // Limpiar layout completamente ANTES de mostrar
                        layoutLotesCargados.removeAllViews()
                        
                        // Ocultar loading y mostrar contenido
                        layoutLoading.visibility = View.GONE
                        scrollViewLotes.visibility = View.VISIBLE
                        
                        // Mostrar UI con datos disponibles (stock ya está calculado)
                        mostrarLotesCargados()
                        completarCantidadFaltante()
                        
                        // Buscar automáticamente el primer producto con stock sin lotes si no hay uno seleccionado
                        buscarPrimerProductoConStockSinLotes()
                        
                        // Forzar invalidate y requestLayout para asegurar renderizado
                        layoutLotesCargados.invalidate()
                        layoutLotesCargados.requestLayout()
                        scrollViewLotes.invalidate()
                        scrollViewLotes.requestLayout()
                        
                        // Forzar otro invalidate después de un pequeño delay
                        layoutLotesCargados.post {
                            layoutLotesCargados.invalidate()
                            layoutLotesCargados.requestLayout()
                        }
                        
                        Log.d("VencimientosActivity", "✅ UI mostrada con datos locales (${stockActualPorProducto.size} productos en stock)")
                    }
                }
                
                // SINCRONIZAR EN SEGUNDO PLANO Y ACTUALIZAR UI CUANDO LOS DATOS ESTÉN LISTOS
                val usuarioActual = (application as InventarioApplication).obtenerUsuarioActual()
                if (usuarioActual != null) {
                    // Sincronizar en segundo plano con timeout
                    try {
                        withTimeout(10000) { // 10 segundos máximo
                            val firebaseRepo = com.tuapp.inventario.repository.FirebaseInventarioRepository(usuarioActual.localId)
                            firebaseRepo.sincronizarConLocal(localRepository)
                            Log.d("VencimientosActivity", "   ✅ Sincronización completada, recalculando stock...")
                            
                            // Después de sincronizar, esperar un momento para que Room procese los datos
                            delay(500)
                            
                            // Recalcular stock con los nuevos datos sincronizados
                            val stockActualizado = calcularStockActual()
                            if (stockActualizado.isNotEmpty() && stockActualizado.values.any { it > 0 }) {
                                Log.d("VencimientosActivity", "   ✅ Stock actualizado después de sincronización: ${stockActualizado.size} productos")
                                
                                // Actualizar el mapa de stock
                                stockActualPorProducto.clear()
                                stockActualPorProducto.putAll(stockActualizado)
                                
                                // Actualizar UI automáticamente
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed) {
                                        mostrarLotesCargados()
                                        Log.d("VencimientosActivity", "   ✅ UI actualizada automáticamente después de sincronización")
                                    }
                                }
                            } else {
                                Log.w("VencimientosActivity", "   ⚠️ Stock sigue vacío después de sincronización, iniciando polling...")
                                
                                // Si aún no hay datos, iniciar polling para verificar periódicamente
                                iniciarPollingStock()
                            }
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        Log.w("VencimientosActivity", "   ⏱️ Sincronización timeout (10s), iniciando polling...")
                        // Iniciar polling para verificar si los datos se cargan después del timeout
                        iniciarPollingStock()
                    } catch (e: Exception) {
                        Log.e("VencimientosActivity", "   ❌ Error sincronizando en segundo plano: ${e.message}", e)
                        // Iniciar polling incluso si hay error, por si acaso los datos se cargan después
                        iniciarPollingStock()
                    }
                } else {
                    Log.w("VencimientosActivity", "   ⚠️ No hay usuario actual, no se puede sincronizar")
                    // Iniciar polling para verificar si los datos se cargan desde otra fuente
                    iniciarPollingStock()
                }
                
                // Resetear flag al finalizar carga exitosa
                isLoading = false
            } catch (e: CancellationException) {
                // Ignorar cancelaciones si la actividad está terminando
                if (!isFinishing && !isDestroyed) {
                    Log.w("VencimientosActivity", "Carga cancelada: ${e.message}")
                }
                // Asegurar que la UI se muestre incluso si se cancela
                runOnUiThread {
                    layoutLoading.visibility = View.GONE
                    scrollViewLotes.visibility = View.VISIBLE
                    if (lotesActivos.isNotEmpty()) {
                        mostrarLotesCargados()
                    }
                }
                // Resetear flag
                isLoading = false
            } catch (e: Exception) {
                Log.e("VencimientosActivity", "Error cargando lotes: ${e.message}", e)
                runOnUiThread {
                    // Ocultar loading incluso si hay error
                    layoutLoading.visibility = View.GONE
                    scrollViewLotes.visibility = View.VISIBLE
                    
                    // Mostrar lotes si se cargaron antes del error
                    if (lotesActivos.isNotEmpty()) {
                        mostrarLotesCargados()
                    }
                    
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this@VencimientosActivity, "Error cargando datos", Toast.LENGTH_SHORT).show()
                    }
                }
                // Resetear flag
                isLoading = false
            }
        }
    }
    
    private suspend fun calcularStockActualDesdeFirebase(): Map<String, Int> {
        return try {
            val usuarioActual = (application as InventarioApplication).obtenerUsuarioActual()
            if (usuarioActual == null) {
                Log.w("VencimientosActivity", "⚠️ No hay usuario actual para obtener datos de Firebase")
                return emptyMap()
            }
            
            val firebaseRepo = com.tuapp.inventario.repository.FirebaseInventarioRepository(usuarioActual.localId)
            val fechaHoy = DateHelper.getFechaActual()
            val fechaAyer = obtenerFechaAnterior(fechaHoy)
            
            Log.d("VencimientosActivity", "🔍 Obteniendo stock desde Firebase:")
            Log.d("VencimientosActivity", "   Fecha hoy: $fechaHoy")
            Log.d("VencimientosActivity", "   Fecha ayer: $fechaAyer")
            
            // Obtener registros de Firebase directamente
            val registrosAyer = try {
                withTimeoutOrNull(10000) {
                    firebaseRepo.getRegistrosByFecha(fechaAyer).first()
                } ?: emptyList()
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("VencimientosActivity", "   ℹ️ Flow cancelado (normal)")
                emptyList()
            } catch (e: Exception) {
                Log.w("VencimientosActivity", "   ⚠️ Error obteniendo registros de ayer desde Firebase: ${e.message}")
                emptyList()
            }
            
            val registrosHoy = try {
                withTimeoutOrNull(10000) {
                    firebaseRepo.getRegistrosByFecha(fechaHoy).first()
                } ?: emptyList()
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("VencimientosActivity", "   ℹ️ Flow cancelado (normal)")
                emptyList()
            } catch (e: Exception) {
                Log.w("VencimientosActivity", "   ⚠️ Error obteniendo registros de hoy desde Firebase: ${e.message}")
                emptyList()
            }
            
            val registrosFirebase = registrosAyer + registrosHoy
            
            Log.d("VencimientosActivity", "   ✅ Registros obtenidos de Firebase: ${registrosFirebase.size}")
            
            // PRIORIDAD 1: Stock Final de HOY (si existe)
            val stockFinalHoy = registrosFirebase
                .filter { registro -> registro.fecha == fechaHoy && registro.tipo == "Stock Final" }
                .flatMap { registro -> registro.productos }
            
            if (stockFinalHoy.isNotEmpty()) {
                val stockMap = mutableMapOf<String, Int>()
                stockFinalHoy.forEach { producto ->
                    val codigo = obtenerCodigoProducto(producto.nombre)
                    stockMap[codigo] = producto.cantidad
                }
                Log.d("VencimientosActivity", "   ✅ Usando Stock Final de HOY desde Firebase: ${stockMap.size} productos")
                return stockMap
            }
            
            // PRIORIDAD 2: Stock Final Ayer + Ingreso Hoy
            val stockAyer = registrosFirebase
                .filter { registro -> registro.fecha == fechaAyer && registro.tipo == "Stock Final" }
                .flatMap { registro -> registro.productos }
            
            val stockMap = mutableMapOf<String, Int>()
            stockAyer.forEach { producto ->
                val codigo = obtenerCodigoProducto(producto.nombre)
                stockMap[codigo] = producto.cantidad
            }
            
            val ingresoHoy = registrosFirebase
                .filter { registro -> registro.fecha == fechaHoy && registro.tipo == "Ingreso de Mercadería" }
                .flatMap { registro -> registro.productos }
            
            ingresoHoy.forEach { producto ->
                val codigo = obtenerCodigoProducto(producto.nombre)
                val cantidadAnterior = stockMap[codigo] ?: 0
                stockMap[codigo] = cantidadAnterior + producto.cantidad
            }
            
            Log.d("VencimientosActivity", "   ✅ Stock calculado desde Firebase (Ayer + Ingreso Hoy): ${stockMap.size} productos")
            stockMap
        } catch (e: Exception) {
            Log.e("VencimientosActivity", "❌ Error obteniendo stock de Firebase: ${e.message}")
            emptyMap()
        }
    }
    
    /**
     * Calcula el stock actual desde Firebase directamente (siempre consulta Firebase primero)
     */
    private suspend fun calcularStockActual(): Map<String, Int> {
        return calcularStockActualDesdeFirebase()
    }
    
    private fun obtenerFechaAnterior(fecha: String): String {
        return DateHelper.obtenerFechaAnterior(fecha)
    }
    
    private fun obtenerCodigoProducto(nombre: String): String {
        // Extraer código del nombre (formato: "JQ - Jamón y Queso" -> "JQ")
        val codigo = when {
            nombre.contains(" - ") -> nombre.split(" - ")[0].trim()
            nombre.startsWith("Pizza ") -> {
                when {
                    nombre.contains("Muzzarella") -> "MUZZA"
                    nombre.contains("Jamón") -> "JAMON"
                    nombre.contains("Pepperoni") -> "PEPPER"
                    else -> nombre
                }
            }
            nombre.startsWith("Pastelito ") -> {
                when {
                    nombre.contains("Batata") -> "BATATA"
                    nombre.contains("Membrillo") -> "MEMBRIL"
                    else -> nombre
                }
            }
            else -> nombre
        }
        return normalizarCodigoProducto(codigo)
    }
    
    private fun normalizarCodigoProducto(codigo: String): String {
        return when (codigo) {
            "PEPPERO" -> "PEPPER"
            "MUZZARE" -> "MUZZA"
            else -> codigo
        }
    }
    
    /**
     * Inicia un polling que verifica periódicamente si los datos de stock están listos
     * y actualiza la UI automáticamente cuando los datos se cargan
     */
    private fun iniciarPollingStock() {
        // Evitar múltiples instancias de polling
        if (pollingActivo) {
            Log.d("VencimientosActivity", "⏭️ Polling ya está activo, omitiendo")
            return
        }
        
        pollingActivo = true
        var intentos = 0
        val maxIntentos = 20 // 20 intentos * 1 segundo = 20 segundos máximo
        val intervaloMs = 1000L // Verificar cada 1 segundo
        
        lifecycleScope.launch {
            try {
                while (intentos < maxIntentos && !isFinishing && !isDestroyed) {
                    delay(intervaloMs)
                    intentos++
                    
                    // Recalcular stock para verificar si los datos están listos
                    val stockVerificado = calcularStockActual()
                    
                    if (stockVerificado.isNotEmpty() && stockVerificado.values.any { it > 0 }) {
                        Log.d("VencimientosActivity", "   ✅ Stock detectado en intento $intentos: ${stockVerificado.size} productos")
                        
                        // Actualizar el mapa de stock
                        stockActualPorProducto.clear()
                        stockActualPorProducto.putAll(stockVerificado)
                        
                        // Actualizar UI automáticamente
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                mostrarLotesCargados()
                                Log.d("VencimientosActivity", "   ✅ UI actualizada automáticamente por polling (intento $intentos)")
                            }
                        }
                        
                        // Detener polling ya que los datos están listos
                        pollingActivo = false
                        return@launch
                    } else {
                        if (intentos % 5 == 0) { // Log cada 5 intentos para no saturar
                            Log.d("VencimientosActivity", "   ⏳ Polling intento $intentos/$maxIntentos: aún sin datos de stock")
                        }
                    }
                }
                
                // Si llegamos aquí, el polling terminó sin encontrar datos
                if (intentos >= maxIntentos) {
                    Log.w("VencimientosActivity", "   ⏱️ Polling terminado después de $maxIntentos intentos sin encontrar datos")
                }
                
                pollingActivo = false
            } catch (e: CancellationException) {
                // Ignorar cancelaciones si la actividad está terminando
                if (!isFinishing && !isDestroyed) {
                    Log.w("VencimientosActivity", "Polling cancelado: ${e.message}")
                }
                pollingActivo = false
            } catch (e: Exception) {
                Log.e("VencimientosActivity", "Error en polling: ${e.message}", e)
                pollingActivo = false
            }
        }
    }
    
    private fun mostrarLotesCargados() {
        layoutLotesCargados.removeAllViews()
        
        // Ordenar por categorías (mismo orden que CargaActivity)
        val categorias = mapOf(
            "EMPANADAS" to listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH"),
            "PIZZAS" to listOf("MUZZA", "JAMON", "PEPPER"),
            "PASTELITOS" to listOf("BATATA", "MEMBRIL")
        )
        
        // Agrupar lotes por producto (normalizar códigos)
        val lotesPorProducto = lotesActivos.groupBy { normalizarCodigoProducto(it.productoCodigo) }
        
        // Normalizar códigos de stockActualPorProducto para comparar correctamente
        val stockActualNormalizado = stockActualPorProducto.mapKeys { (codigo, _) ->
            normalizarCodigoProducto(codigo)
        }
        
        // Obtener todos los códigos de productos que SÍ están en el control de vencimientos
        val codigosConControlVencimientos = categorias.values.flatten().map { normalizarCodigoProducto(it) }.toSet()
        
        // Obtener productos con stock pero sin lotes (solo los que están en el control de vencimientos)
        val productosConStockSinLotes = stockActualNormalizado.filter { (codigoNormalizado, stock) ->
            stock > 0 
            && lotesPorProducto[codigoNormalizado].isNullOrEmpty()
            && codigoNormalizado in codigosConControlVencimientos
        }
        
        // Log para diagnóstico
        Log.d("VencimientosActivity", "🔍 Diagnóstico de validación:")
        Log.d("VencimientosActivity", "   Stock actual: ${stockActualNormalizado.size} productos")
        Log.d("VencimientosActivity", "   Lotes cargados: ${lotesPorProducto.size} productos")
        Log.d("VencimientosActivity", "   Productos con stock sin lotes: ${productosConStockSinLotes.size}")
        productosConStockSinLotes.forEach { (codigo, stock) ->
            Log.d("VencimientosActivity", "      - $codigo: $stock uds (sin lotes)")
        }
        
        // Verificar si hay productos con lotes
        var hayProductosConLotes = false
        categorias.forEach { (_, codigosCategoria) ->
            val lotesCategoria = lotesActivos.filter { it.productoCodigo in codigosCategoria }
            if (lotesCategoria.isNotEmpty()) {
                hayProductosConLotes = true
            }
        }
        
        // PRIMERO: Mostrar mensaje positivo si todo está en orden
        Log.d("VencimientosActivity", "🔍 Verificando condiciones para 'TODO EN ORDEN':")
        Log.d("VencimientosActivity", "   productosConStockSinLotes.isEmpty(): ${productosConStockSinLotes.isEmpty()}")
        Log.d("VencimientosActivity", "   hayProductosConLotes: $hayProductosConLotes")
        Log.d("VencimientosActivity", "   stockActualNormalizado.isNotEmpty(): ${stockActualNormalizado.isNotEmpty()}")
        Log.d("VencimientosActivity", "   stockActualNormalizado.size: ${stockActualNormalizado.size}")
        Log.d("VencimientosActivity", "   lotesActivos.size: ${lotesActivos.size}")
        
        // Verificar también productos con diferencias (stock != suma de lotes)
        val productosConDiferencias = stockActualNormalizado.filter { (codigoNormalizado, stock) ->
            stock > 0 
            && codigoNormalizado in codigosConControlVencimientos
            && lotesPorProducto[codigoNormalizado]?.isNotEmpty() == true
        }.filter { (codigoNormalizado, stock) ->
            val sumaLotes = (lotesPorProducto[codigoNormalizado] ?: emptyList()).sumOf { it.cantidad }
            sumaLotes != stock
        }
        
        Log.d("VencimientosActivity", "   Productos con diferencias: ${productosConDiferencias.size}")
        productosConDiferencias.forEach { (codigo, stock) ->
            val sumaLotes = (lotesPorProducto[codigo] ?: emptyList()).sumOf { it.cantidad }
            Log.d("VencimientosActivity", "      - $codigo: stock=$stock, lotes=$sumaLotes, diferencia=${stock - sumaLotes}")
        }
        
        // CRÍTICO: Solo mostrar "TODO EN ORDEN" si:
        // 1. Los datos de stock están cargados (tieneDatosStock == true)
        // 2. No hay productos con stock sin lotes
        // 3. No hay productos con diferencias entre stock y lotes
        // 4. Hay lotes cargados
        // 5. Todos los productos con stock tienen lotes Y coinciden exactamente
        val tieneDatosStock = stockActualNormalizado.isNotEmpty()
        
        // NO mostrar "TODO EN ORDEN" si no hay datos de stock cargados
        // Solo mostrar cuando los datos están cargados Y no hay problemas
        if (tieneDatosStock && productosConStockSinLotes.isEmpty() && productosConDiferencias.isEmpty() && hayProductosConLotes) {
            // Verificar que todos los productos con stock tengan lotes Y que la suma coincida exactamente (solo los que están en control de vencimientos)
            val todosTienenLotes = stockActualNormalizado.filter { (codigoNormalizado, _) ->
                codigoNormalizado in codigosConControlVencimientos
            }.all { (codigoNormalizado, stock) ->
                if (stock == 0) {
                    true // Si no hay stock, no importa
                } else {
                    val lotesProducto = lotesPorProducto[codigoNormalizado] ?: emptyList()
                    val tieneLotes = lotesProducto.isNotEmpty()
                    val sumaLotes = lotesProducto.sumOf { it.cantidad }
                    val sumaCoincide = sumaLotes == stock
                    
                    if (!tieneLotes) {
                        Log.d("VencimientosActivity", "   ⚠️ Producto $codigoNormalizado tiene stock ($stock) pero no tiene lotes")
                    } else if (!sumaCoincide) {
                        Log.d("VencimientosActivity", "   ⚠️ Producto $codigoNormalizado tiene stock ($stock) pero lotes ($sumaLotes) no coinciden (diferencia: ${stock - sumaLotes})")
                    }
                    
                    tieneLotes && sumaCoincide
                }
            }
            
            Log.d("VencimientosActivity", "   ✅ Todos tienen lotes y coinciden: $todosTienenLotes")
            
            if (todosTienenLotes) {
                Log.d("VencimientosActivity", "   ✅ Mostrando mensaje 'TODO EN ORDEN'")
                val txtTodoEnOrden = TextView(this).apply {
                    text = "✅ TODO EN ORDEN - Todos los productos con stock tienen lotes cargados"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(16, 12, 16, 12)
                    setTextColor(ContextCompat.getColor(this@VencimientosActivity, android.R.color.white))
                    // Usar un color de fondo que se vea bien en ambos modos
                    val backgroundColor = if (isDarkMode()) {
                        ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_green_light)
                    } else {
                        ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_green_dark)
                    }
                    setBackgroundColor(backgroundColor)
                }
                layoutLotesCargados.addView(txtTodoEnOrden)
                
                // Separador compacto
                val txtSeparador = TextView(this).apply {
                    text = ""
                    textSize = 4f
                    setPadding(0, 2, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        2
                    )
                }
                layoutLotesCargados.addView(txtSeparador)
            } else {
                Log.d("VencimientosActivity", "   ❌ No se muestra 'TODO EN ORDEN' porque todosTienenLotes = false")
            }
        } else {
            Log.d("VencimientosActivity", "   ❌ No se cumple condición inicial para 'TODO EN ORDEN':")
            if (!tieneDatosStock) {
                Log.d("VencimientosActivity", "      - No hay datos de stock cargados (stockActualNormalizado está vacío)")
                
                // Si hay lotes cargados pero no hay datos de stock, mostrar mensaje informativo
                if (hayProductosConLotes && lotesActivos.isNotEmpty()) {
                    Log.d("VencimientosActivity", "   ⏳ Mostrando mensaje de carga (hay lotes pero no hay datos de stock aún)")
                    val txtInfo = TextView(this).apply {
                        text = "⏳ Cargando datos de stock... Por favor, espere unos segundos."
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(16, 12, 16, 12)
                        setTextColor(ContextCompat.getColor(this@VencimientosActivity, android.R.color.white))
                        // Usar un color de fondo que se vea bien en ambos modos
                        val backgroundColor = if (isDarkMode()) {
                            ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_orange_light)
                        } else {
                            ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_orange_dark)
                        }
                        setBackgroundColor(backgroundColor)
                    }
                    layoutLotesCargados.addView(txtInfo)
                    
                    // Iniciar polling para verificar cuando los datos estén listos
                    iniciarPollingStock()
                }
            } else {
                if (productosConStockSinLotes.isNotEmpty()) {
                    Log.d("VencimientosActivity", "      - Hay productos con stock sin lotes: ${productosConStockSinLotes.size}")
                }
                if (productosConDiferencias.isNotEmpty()) {
                    Log.d("VencimientosActivity", "      - Hay productos con diferencias: ${productosConDiferencias.size}")
                }
                if (!hayProductosConLotes) {
                    Log.d("VencimientosActivity", "      - No hay productos con lotes cargados")
                }
            }
        }
        
        // SEGUNDO: Mostrar advertencia de productos con stock pero sin lotes (solo si hay problemas)
        if (productosConStockSinLotes.isNotEmpty()) {
            // Título de advertencia
            val txtAdvertencia = TextView(this).apply {
                text = "⚠️ PRODUCTOS CON STOCK SIN LOTES CARGADOS"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(16, 8, 16, 8)
                setTextColor(ContextCompat.getColor(this@VencimientosActivity, android.R.color.white))
                // Usar un color de fondo que se vea bien en ambos modos
                val backgroundColor = if (isDarkMode()) {
                    ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_red_light)
                } else {
                    ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_red_dark)
                }
                setBackgroundColor(backgroundColor)
            }
            layoutLotesCargados.addView(txtAdvertencia)
            
            // Agrupar por categorías para mostrar ordenados
            categorias.forEach { (categoria, codigosCategoria) ->
                // Verificar si hay productos de esta categoría con stock sin lotes
                var hayProductosCategoria = false
                codigosCategoria.forEach { codigo ->
                    val codigoNormalizado = normalizarCodigoProducto(codigo)
                    val stock = productosConStockSinLotes[codigoNormalizado]
                    if (stock != null && stock > 0) {
                        hayProductosCategoria = true
                    }
                }
                
                if (hayProductosCategoria) {
                    // Subtítulo de categoría
                    val txtSubCategoria = TextView(this).apply {
                        text = "📊 $categoria"
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(8, 6, 8, 4)
                        // Usar color con buen contraste en ambos modos
                        val textColor = if (isDarkMode()) {
                            ContextCompat.getColor(this@VencimientosActivity, android.R.color.white)
                        } else {
                            ContextCompat.getColor(this@VencimientosActivity, android.R.color.black)
                        }
                        setTextColor(textColor)
                        // Fondo sutil para mejor visibilidad
                        val bgColor = if (isDarkMode()) {
                            ContextCompat.getColor(this@VencimientosActivity, android.R.color.darker_gray)
                        } else {
                            ContextCompat.getColor(this@VencimientosActivity, android.R.color.darker_gray)
                        }
                        setBackgroundColor(bgColor)
                        visibility = View.VISIBLE
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 4, 0, 2)
                        }
                    }
                    layoutLotesCargados.addView(txtSubCategoria)
                    
                    // Mostrar productos en orden
                    codigosCategoria.forEach { codigo ->
                        val codigoNormalizado = normalizarCodigoProducto(codigo)
                        val stock = productosConStockSinLotes[codigoNormalizado]
                        if (stock != null && stock > 0) {
                            mostrarProductoSinLotes(codigoNormalizado, stock)
                        }
                    }
                }
            }
            
            // Separador compacto
            val txtSeparador = TextView(this).apply {
                text = ""
                textSize = 4f
                setPadding(0, 2, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    2
                )
            }
            layoutLotesCargados.addView(txtSeparador)
        }
        
        // TERCERO: Mostrar advertencia de productos con diferencias entre stock y lotes
        if (productosConDiferencias.isNotEmpty()) {
            // Título de advertencia
            val txtAdvertencia = TextView(this).apply {
                text = "🔴 PRODUCTOS CON DIFERENCIAS ENTRE STOCK Y LOTES"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(16, 12, 16, 12)
                setTextColor(ContextCompat.getColor(this@VencimientosActivity, android.R.color.white))
                val backgroundColor = if (isDarkMode()) {
                    ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_red_light)
                } else {
                    ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_red_dark)
                }
                setBackgroundColor(backgroundColor)
            }
            layoutLotesCargados.addView(txtAdvertencia)
            
            // Agrupar por categoría
            categorias.forEach { (categoria, codigosCategoria) ->
                var hayProductosCategoria = false
                
                // Verificar si hay productos de esta categoría con diferencias
                codigosCategoria.forEach { codigo ->
                    val codigoNormalizado = normalizarCodigoProducto(codigo)
                    if (productosConDiferencias.containsKey(codigoNormalizado)) {
                        hayProductosCategoria = true
                    }
                }
                
                if (hayProductosCategoria) {
                    // Subtítulo de categoría
                    val txtSubCategoria = TextView(this).apply {
                        text = "📊 $categoria"
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(8, 6, 8, 4)
                        val textColor = if (isDarkMode()) {
                            ContextCompat.getColor(this@VencimientosActivity, android.R.color.white)
                        } else {
                            ContextCompat.getColor(this@VencimientosActivity, android.R.color.black)
                        }
                        setTextColor(textColor)
                        val bgColor = if (isDarkMode()) {
                            ContextCompat.getColor(this@VencimientosActivity, android.R.color.darker_gray)
                        } else {
                            ContextCompat.getColor(this@VencimientosActivity, android.R.color.darker_gray)
                        }
                        setBackgroundColor(bgColor)
                        visibility = View.VISIBLE
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 4, 0, 2)
                        }
                    }
                    layoutLotesCargados.addView(txtSubCategoria)
                    
                    // Mostrar productos con diferencias
                    codigosCategoria.forEach { codigo ->
                        val codigoNormalizado = normalizarCodigoProducto(codigo)
                        val stock = productosConDiferencias[codigoNormalizado]
                        if (stock != null && stock > 0) {
                            val sumaLotes = (lotesPorProducto[codigoNormalizado] ?: emptyList()).sumOf { it.cantidad }
                            val diferencia = stock - sumaLotes
                            mostrarProductoConDiferencias(codigoNormalizado, stock, sumaLotes, diferencia)
                        }
                    }
                }
            }
            
            // Separador compacto
            val txtSeparador = TextView(this).apply {
                text = ""
                textSize = 4f
                setPadding(0, 2, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    2
                )
            }
            layoutLotesCargados.addView(txtSeparador)
        }
        
        // CUARTO: Mostrar productos con lotes
        categorias.forEach { (categoria, codigosCategoria) ->
            val lotesCategoria = lotesActivos.filter { it.productoCodigo in codigosCategoria }
            
            if (lotesCategoria.isNotEmpty()) {
                // Título de categoría (compacto y visible)
                val txtCategoria = TextView(this).apply {
                    text = "📊 $categoria"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(8, 6, 8, 4)
                    // Usar color con buen contraste en ambos modos
                    val textColor = if (isDarkMode()) {
                        ContextCompat.getColor(this@VencimientosActivity, android.R.color.white)
                    } else {
                        ContextCompat.getColor(this@VencimientosActivity, android.R.color.black)
                    }
                    setTextColor(textColor)
                    // Fondo sutil para mejor visibilidad
                    val bgColor = if (isDarkMode()) {
                        ContextCompat.getColor(this@VencimientosActivity, android.R.color.darker_gray)
                    } else {
                        ContextCompat.getColor(this@VencimientosActivity, android.R.color.darker_gray)
                    }
                    setBackgroundColor(bgColor)
                    visibility = View.VISIBLE
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 4, 0, 2)
                    }
                }
                layoutLotesCargados.addView(txtCategoria)
                
                // Mostrar productos en orden
                codigosCategoria.forEach { codigo ->
                    val codigoNormalizado = normalizarCodigoProducto(codigo)
                    val lotesProducto = lotesPorProducto[codigoNormalizado] ?: emptyList()
                    
                    if (lotesProducto.isNotEmpty()) {
                        // Buscar stock normalizado (igual que StockVencimientosActivity)
                        val stockRegistrado = stockActualNormalizado[codigoNormalizado]
                        val sumaLotes = lotesProducto.sumOf { it.cantidad }
                        
                        // Si el código está en el mapa, usar ese valor (incluso si es 0)
                        // Si no está en el mapa, significa que no hay registros, usar suma de lotes
                        val stockReal = if (stockRegistrado != null) {
                            // Hay registros, usar el valor (puede ser 0 si realmente no hay stock)
                            stockRegistrado
                        } else {
                            // No hay registros, usar suma de lotes como fallback
                            sumaLotes
                        }
                        
                        mostrarProductoConValidacion(codigoNormalizado, lotesProducto, stockReal)
                    }
                }
            }
        }
        
        // Si no hay lotes cargados y no hay productos con stock sin lotes
        if (!hayProductosConLotes && productosConStockSinLotes.isEmpty()) {
            val txtSinLotes = TextView(this).apply {
                text = "No hay lotes cargados"
                textSize = 13f
                setPadding(16, 8, 16, 8)
                setTextColor(getThemeColor(android.R.attr.textColorSecondary))
            }
            layoutLotesCargados.addView(txtSinLotes)
        }
    }
    
    private fun mostrarProductoConValidacion(codigo: String, lotes: List<VencimientoLote>, stockActual: Int) {
        val productoNombre = lotes.first().productoNombre
        val sumaLotes = lotes.sumOf { it.cantidad }
        
        // Log para debug
        Log.d("VencimientosActivity", "🔍 mostrarProductoConValidacion: $codigo")
        Log.d("VencimientosActivity", "   stockActual recibido: $stockActual")
        Log.d("VencimientosActivity", "   sumaLotes: $sumaLotes")
        
        // Calcular validación (igual que StockVencimientosActivity)
        val coincide = sumaLotes == stockActual
        val diferencia = stockActual - sumaLotes
        
        Log.d("VencimientosActivity", "   sumaLotes: $sumaLotes, coincide: $coincide, diferencia: $diferencia")
        
        // Nombre del producto con fondo (ultra compacto)
        val txtProducto = TextView(this).apply {
            text = productoNombre
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(8, 4, 8, 4)
            setTextColor(ContextCompat.getColor(this@VencimientosActivity, android.R.color.white))
            setBackgroundColor(ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_blue_dark))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 4, 0, 1)
            }
        }
        layoutLotesCargados.addView(txtProducto)
        
        // Stock y validación en una sola línea compacta (igual que StockVencimientosActivity)
        val txtValidacion = TextView(this).apply {
            when {
                coincide -> {
                    text = "   ✅ Stock: $stockActual | Lotes: $sumaLotes ✓"
                    setTextColor(ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_green_dark))
                }
                diferencia > 0 -> {
                    text = "   ⚠️ Stock: $stockActual | Lotes: $sumaLotes | Faltan: +$diferencia"
                    setTextColor(ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_orange_dark))
                }
                else -> {
                    text = "   ⚠️ Stock: $stockActual | Lotes: $sumaLotes | Sobran: ${-diferencia}"
                    setTextColor(ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_red_dark))
                }
            }
            textSize = 11f
            setPadding(8, 1, 8, 1)
            setTypeface(null, android.graphics.Typeface.BOLD)
            // Asegurar que el texto sea visible y forzar renderizado
            visibility = View.VISIBLE
            alpha = 1.0f
            // Forzar que se muestre inmediatamente
            post {
                visibility = View.VISIBLE
                invalidate()
            }
        }
        layoutLotesCargados.addView(txtValidacion)
        
        // Log para debug
        Log.d("VencimientosActivity", "📝 txtValidacion creado: ${txtValidacion.text}, visibility=${txtValidacion.visibility}, alpha=${txtValidacion.alpha}")
        
        // Ordenar lotes por fecha de vencimiento
        val lotesOrdenados = lotes.sortedBy { it.fechaVencimiento }
        
        // Mostrar cada lote (igual formato que StockVencimientosActivity pero con botón eliminar)
        lotesOrdenados.forEach { lote ->
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
            
            // Fila con lote y botón eliminar
            val filaLote = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            
            val txtLote = TextView(this).apply {
                text = "   $icono ${lote.cantidad}ud → $fechaDisplay$urgencia"
                textSize = 11f
                setPadding(8, 1, 4, 1)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                
                // Color del texto según urgencia (adaptado para tema oscuro)
                val color = when {
                    diasHasta <= 0 -> android.R.color.holo_red_dark
                    diasHasta == 1 -> android.R.color.holo_orange_dark
                    else -> {
                        // Usar color que se adapta al tema (blanco en oscuro, negro en claro)
                        if (isDarkMode()) {
                            android.R.color.white
                        } else {
                            android.R.color.black
                        }
                    }
                }
                setTextColor(ContextCompat.getColor(this@VencimientosActivity, color))
            }
            filaLote.addView(txtLote)
            
            // Botón eliminar
            val btnEliminar = Button(this).apply {
                text = "❌"
                textSize = 14f
                setPadding(12, 8, 12, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    confirmarEliminarLote(lote)
                }
            }
            filaLote.addView(btnEliminar)
            
            layoutLotesCargados.addView(filaLote)
        }
    }
    
    private fun mostrarProductoSinLotes(codigoNormalizado: String, stock: Int) {
        // Buscar el nombre del producto usando el código normalizado o el original
        val productoNombre = productos[codigoNormalizado] 
            ?: productos.entries.find { normalizarCodigoProducto(it.key) == codigoNormalizado }?.value
            ?: codigoNormalizado
        
        val nombreLimpio = if (productoNombre.contains(" - ")) {
            productoNombre.split(" - ")[1]
        } else {
            productoNombre
        }
        
        // Layout del producto
        val layoutProducto = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 8)
        }
        
        // Título del producto con fondo de advertencia
        val txtProducto = TextView(this).apply {
            text = nombreLimpio
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(12, 8, 12, 8)
            setTextColor(ContextCompat.getColor(this@VencimientosActivity, android.R.color.white))
            // Usar un color de fondo que se vea bien en ambos modos
            val backgroundColor = if (isDarkMode()) {
                ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_red_light)
            } else {
                ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_red_dark)
            }
            setBackgroundColor(backgroundColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 4)
            }
        }
        layoutProducto.addView(txtProducto)
        
        // Mensaje de advertencia
        val txtAdvertencia = TextView(this).apply {
            text = "   ⚠️ Stock: $stock uds | Lotes: 0 uds | FALTAN CARGAR LOTES"
            textSize = 12f
            setPadding(16, 8, 16, 8)
            setTextColor(ContextCompat.getColor(this@VencimientosActivity, android.R.color.white))
            setTypeface(null, android.graphics.Typeface.BOLD)
            // Usar un color de fondo que se vea bien en ambos modos
            val backgroundColor = if (isDarkMode()) {
                ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_orange_dark)
            } else {
                ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_orange_light)
            }
            setBackgroundColor(backgroundColor)
        }
        layoutProducto.addView(txtAdvertencia)
        
        layoutLotesCargados.addView(layoutProducto)
    }
    
    private fun mostrarProductoConDiferencias(codigoNormalizado: String, stock: Int, sumaLotes: Int, diferencia: Int) {
        // Buscar el nombre del producto usando el código normalizado o el original
        val productoNombre = productos[codigoNormalizado] 
            ?: productos.entries.find { normalizarCodigoProducto(it.key) == codigoNormalizado }?.value
            ?: codigoNormalizado
        
        val nombreLimpio = if (productoNombre.contains(" - ")) {
            productoNombre.split(" - ")[1]
        } else {
            productoNombre
        }
        
        // Layout del producto
        val layoutProducto = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 8)
        }
        
        // Título del producto con fondo de advertencia
        val txtProducto = TextView(this).apply {
            text = nombreLimpio
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(12, 8, 12, 8)
            setTextColor(ContextCompat.getColor(this@VencimientosActivity, android.R.color.white))
            val backgroundColor = if (isDarkMode()) {
                ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_red_light)
            } else {
                ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_red_dark)
            }
            setBackgroundColor(backgroundColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 4)
            }
        }
        layoutProducto.addView(txtProducto)
        
        // Mensaje de diferencia
        val txtDiferencia = TextView(this).apply {
            val mensaje = if (diferencia > 0) {
                "   🔴 Stock: $stock uds | Lotes: $sumaLotes uds | FALTAN: +$diferencia uds"
            } else {
                "   🔴 Stock: $stock uds | Lotes: $sumaLotes uds | SOBRAN: ${-diferencia} uds"
            }
            text = mensaje
            textSize = 13f
            setPadding(16, 8, 16, 8)
            setTextColor(ContextCompat.getColor(this@VencimientosActivity, android.R.color.white))
            setTypeface(null, android.graphics.Typeface.BOLD)
            val backgroundColor = if (diferencia > 0) {
                // Faltan unidades - naranja
                if (isDarkMode()) {
                    ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_orange_dark)
                } else {
                    ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_orange_light)
                }
            } else {
                // Sobran unidades - rojo
                if (isDarkMode()) {
                    ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_red_light)
                } else {
                    ContextCompat.getColor(this@VencimientosActivity, android.R.color.holo_red_dark)
                }
            }
            setBackgroundColor(backgroundColor)
        }
        layoutProducto.addView(txtDiferencia)
        
        layoutLotesCargados.addView(layoutProducto)
    }
    
    private fun crearVistaLote(lote: VencimientoLote): LinearLayout {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 8, 8, 8)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        
        // Agregar margin bottom para separar los lotes
        fila.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 4)
        }
        
        // Icono de urgencia
        val diasHasta = lote.diasHastaVencimiento()
        val icono = when {
            diasHasta <= 0 -> "🔴"
            diasHasta == 1 -> "🟡"
            else -> "🟢"
        }
        
        // Formatear fecha para mostrar
        val fechaDisplay = try {
            val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
            val sdfDisplay = DateHelper.getDateFormat("dd/MM/yyyy")
            val fecha = sdf.parse(lote.fechaVencimiento)
            sdfDisplay.format(fecha!!)
        } catch (e: Exception) {
            lote.fechaVencimiento
        }
        
        val textoUrgencia = when {
            diasHasta <= 0 -> " (¡HOY!)"
            diasHasta == 1 -> " (Mañana)"
            else -> ""
        }
        
        // Texto del lote
        val txtLote = TextView(this).apply {
            text = "$icono ${lote.cantidad} uds → $fechaDisplay$textoUrgencia"
            textSize = 14f
            setTextColor(getThemeColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setPadding(0, 4, 16, 4)
        }
        
        // Botón eliminar
        val btnEliminar = Button(this).apply {
            text = "❌"
            textSize = 14f
            setPadding(12, 8, 12, 8)
            setOnClickListener {
                confirmarEliminarLote(lote)
            }
        }
        
        fila.addView(txtLote)
        fila.addView(btnEliminar)
        
        return fila
    }
    
    private fun confirmarEliminarLote(lote: VencimientoLote) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar Lote")
            .setMessage("¿Eliminar este lote?\n\n${lote.productoNombre}\n${lote.cantidad} uds → ${lote.fechaVencimiento}")
            .setPositiveButton("Eliminar") { _, _ ->
                eliminarLote(lote)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun eliminarLote(lote: VencimientoLote) {
        lifecycleScope.launch {
            val exito = repository.eliminarLote(localId, lote.id)
            
            runOnUiThread {
                if (exito) {
                    Toast.makeText(this@VencimientosActivity, "Lote eliminado", Toast.LENGTH_SHORT).show()
                    cargarLotesActivos()
                } else {
                    Toast.makeText(this@VencimientosActivity, "Error al eliminar lote", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    override fun onBackPressed() {
        finish()
    }
    
    /**
     * Genera y envía directamente el informe de vencimientos por WhatsApp
     */
    private fun generarYEnviarInformeWhatsApp() {
        if (lotesActivos.isEmpty()) {
            Toast.makeText(this, "No hay vencimientos para enviar", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Validar que el stock final de ayer esté cargado
        lifecycleScope.launch {
            try {
                val fechaHoy = DateHelper.getFechaActual()
                val fechaAyer = DateHelper.obtenerFechaAnterior(fechaHoy)
                
                val stockFinalAyerExiste = localRepository.existeRegistro(localId, fechaAyer, "Stock Final")
                
                if (!stockFinalAyerExiste) {
                    runOnUiThread {
                        val fechaAyerFormateada = DateHelper.formatearFecha(fechaAyer, "dd/MM/yyyy")
                        AlertDialog.Builder(this@VencimientosActivity)
                            .setTitle("⚠️ Stock Final Pendiente")
                            .setMessage("No podés enviar el informe de vencimientos porque falta cargar el Stock Final de ayer ($fechaAyerFormateada).\n\nPor favor, cargá el Stock Final de ayer primero.")
                            .setPositiveButton("Entendido", null)
                            .setNeutralButton("📊 Ir a Stock Final") { _, _ ->
                                // Abrir CargaActivity con Stock Final de ayer
                                val intent = Intent(this@VencimientosActivity, CargaActivity::class.java)
                                intent.putExtra("tipo", "Stock Final")
                                intent.putExtra("fecha", fechaAyer)
                                @Suppress("DEPRECATION")
                                startActivityForResult(intent, 1001) // Código de request para Stock Final
                            }
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .show()
                    }
                    return@launch
                }
                
                // Si el stock final de ayer existe, continuar con el envío
                runOnUiThread {
                    try {
                        val mensaje = generarInformeWhatsApp(lotesActivos)
                        copiarYEnviarWhatsApp(mensaje)
                    } catch (e: Exception) {
                        Log.e("VencimientosActivity", "Error generando informe WhatsApp: ${e.message}", e)
                        Toast.makeText(this@VencimientosActivity, "Error generando informe: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("VencimientosActivity", "Error verificando stock final: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@VencimientosActivity, "Error verificando stock final: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Genera el texto del informe de vencimientos agrupado por urgencia
     */
    private fun generarInformeWhatsApp(lotes: List<VencimientoLote>): String {
        Log.d("VencimientosActivity", "🔍 Generando informe WhatsApp. Lotes recibidos: ${lotes.size}")
        
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        val localNombre = prefs.getString("localNombre", "Local") ?: "Local"
        
        val hoy = DateHelper.getCalendar().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        val manana = DateHelper.getCalendar().apply {
            timeInMillis = hoy.timeInMillis
            add(Calendar.DAY_OF_YEAR, 1)
        }
        
        val sdf = DateHelper.getDateFormat("yyyy-MM-dd")
        val sdfDisplay = DateHelper.getDateFormat("dd/MM/yyyy")
        val hoyStr = sdf.format(hoy.time)
        val mananaStr = sdf.format(manana.time)
        
        Log.d("VencimientosActivity", "🔍 Fecha hoy: $hoyStr, Fecha mañana: $mananaStr")
        
        val vencimientosHoy = mutableListOf<Triple<String, Int, String>>()
        val vencimientosManana = mutableListOf<Triple<String, Int, String>>()
        val vencimientosProximos = mutableListOf<Triple<String, Int, String>>()
        
        var lotesConFecha = 0
        var lotesSinFecha = 0
        
        lotes.forEach { lote ->
            if (lote.fechaVencimiento.isEmpty()) {
                lotesSinFecha++
                Log.d("VencimientosActivity", "⚠️ Lote sin fecha: ${lote.productoNombre} (${lote.productoCodigo})")
                return@forEach
            }
            
            lotesConFecha++
            
            val fechaVenc = try {
                sdf.parse(lote.fechaVencimiento) ?: run {
                    Log.d("VencimientosActivity", "⚠️ Error parseando fecha: ${lote.fechaVencimiento}")
                    return@forEach
                }
            } catch (e: Exception) {
                Log.d("VencimientosActivity", "⚠️ Excepción parseando fecha: ${lote.fechaVencimiento} - ${e.message}")
                return@forEach
            }
            
            val fechaVencStr = sdf.format(fechaVenc)
            val diasHasta = ((fechaVenc.time - hoy.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
            
            val codigo = lote.productoCodigo
            val fechaDisplay = sdfDisplay.format(fechaVenc)
            
            Log.d("VencimientosActivity", "🔍 Lote: $codigo, Fecha: $fechaVencStr, Días hasta: $diasHasta")
            
            when {
                fechaVencStr == hoyStr -> {
                    vencimientosHoy.add(Triple(codigo, lote.cantidad, fechaDisplay))
                    Log.d("VencimientosActivity", "✅ Agregado a VENCEN HOY: $codigo")
                }
                fechaVencStr == mananaStr -> {
                    vencimientosManana.add(Triple(codigo, lote.cantidad, fechaDisplay))
                    Log.d("VencimientosActivity", "✅ Agregado a VENCEN MAÑANA: $codigo")
                }
                diasHasta > 1 && diasHasta <= 7 -> {
                    vencimientosProximos.add(Triple(codigo, lote.cantidad, fechaDisplay))
                    Log.d("VencimientosActivity", "✅ Agregado a PRÓXIMOS: $codigo")
                }
                else -> {
                    Log.d("VencimientosActivity", "⏭️ Lote fuera de rango (${diasHasta} días): $codigo")
                }
            }
        }
        
        Log.d("VencimientosActivity", "📊 Resumen: Lotes con fecha=$lotesConFecha, sin fecha=$lotesSinFecha")
        Log.d("VencimientosActivity", "📊 Vencen hoy=${vencimientosHoy.size}, mañana=${vencimientosManana.size}, próximos=${vencimientosProximos.size}")
        
        // Ordenar próximos por fecha
        vencimientosProximos.sortBy { it.third }
        
        // Calcular totales
        val totalHoy = vencimientosHoy.sumOf { it.second }
        val totalManana = vencimientosManana.sumOf { it.second }
        
        // Construir mensaje
        val mensaje = StringBuilder()
        mensaje.append("⌛*VENCIMIENTOS*⌛\n")
        mensaje.append("Local: $localNombre\n")
        mensaje.append("Fecha: ${sdfDisplay.format(hoy.time)}\n\n")
        
        if (vencimientosHoy.isNotEmpty()) {
            mensaje.append("> *VENCEN HOY* 🚨:\n")
            vencimientosHoy.forEach { (codigo, cantidad, _) ->
                mensaje.append("  • $codigo: $cantidad uds\n")
            }
            mensaje.append("\n")
        }
        
        if (vencimientosManana.isNotEmpty()) {
            mensaje.append("> *VENCEN MAÑANA* ⚠️:\n")
            vencimientosManana.forEach { (codigo, cantidad, _) ->
                mensaje.append("  • $codigo: $cantidad uds\n")
            }
            mensaje.append("\n")
        }
                
        if (vencimientosHoy.isEmpty() && vencimientosManana.isEmpty() && vencimientosProximos.isEmpty()) {
            mensaje.append("✅ OK - No hay vencimientos proximos en los proximos 7 dias.\n\n")
        }
        
        mensaje.append("================================\n")
        
        val mensajeFinal = mensaje.toString()
        Log.d("VencimientosActivity", "📝 Mensaje generado (${mensajeFinal.length} caracteres):\n$mensajeFinal")
        
        return mensajeFinal
    }
    
    /**
     * Muestra una vista previa del mensaje antes de enviarlo por WhatsApp
     */
    private fun mostrarVistaPreviaWhatsApp(mensaje: String) {
        Log.d("VencimientosActivity", "🔍 Mostrando vista previa. Mensaje length: ${mensaje.length}")
        Log.d("VencimientosActivity", "🔍 Mensaje contenido: $mensaje")
        
        // Verificar si el mensaje está vacío
        if (mensaje.isBlank()) {
            Toast.makeText(this, "No hay contenido para mostrar en la vista previa", Toast.LENGTH_SHORT).show()
            return
        }
        
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // Asegurar altura mínima
            minimumHeight = 200
        }
        
        val textView = TextView(this).apply {
            text = mensaje
            textSize = 14f
            setPadding(24, 24, 24, 24)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getThemeColor(android.R.attr.textColorPrimary))
            setBackgroundColor(getThemeColor(android.R.attr.colorBackground))
            // Asegurar que el texto sea visible
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // Permitir scroll si el texto es largo
            isScrollContainer = false
        }
        
        scrollView.addView(textView)
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("📱 Vista Previa del Mensaje")
            .setView(scrollView)
            .setPositiveButton("📱 Copiar y Abrir WhatsApp") { _, _ ->
                copiarYEnviarWhatsApp(mensaje)
            }
            .setNegativeButton("Cancelar", null)
            .create()
        
        dialog.show()
        
        // Forzar actualización del diálogo
        dialog.window?.setLayout(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    
    /**
     * Copia el mensaje al portapapeles y abre WhatsApp
     */
    private fun copiarYEnviarWhatsApp(mensaje: String) {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Informe Vencimientos", mensaje)
            clipboard.setPrimaryClip(clip)
            
            // Intentar abrir WhatsApp
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_TEXT, mensaje)
            }
            
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Toast.makeText(this, "Mensaje copiado. Abriendo WhatsApp...", Toast.LENGTH_SHORT).show()
            } else {
                // Si no está instalado, usar intent genérico
                val intentGenerico = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, mensaje)
                }
                startActivity(Intent.createChooser(intentGenerico, "Compartir informe"))
                Toast.makeText(this, "Mensaje copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("VencimientosActivity", "Error enviando por WhatsApp: ${e.message}", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Maneja el resultado cuando regresa de CargaActivity
     * Si se cargó el Stock Final de ayer, permite continuar con la acción pendiente
     */
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        // Si regresó de cargar Stock Final (códigos 1001 o 1002)
        if (requestCode == 1001 || requestCode == 1002) {
            if (resultCode == Activity.RESULT_OK) {
                // Verificar nuevamente si el Stock Final de ayer ya está cargado
                lifecycleScope.launch {
                    try {
                        val fechaHoy = DateHelper.getFechaActual()
                        val fechaAyer = DateHelper.obtenerFechaAnterior(fechaHoy)
                        
                        val stockFinalAyerExiste = localRepository.existeRegistro(localId, fechaAyer, "Stock Final")
                        
                        if (stockFinalAyerExiste) {
                            runOnUiThread {
                                Toast.makeText(this@VencimientosActivity, "✅ Stock Final de ayer cargado correctamente", Toast.LENGTH_SHORT).show()
                                
                                // Si era para WhatsApp (1001), intentar enviar nuevamente
                                if (requestCode == 1001) {
                                    generarYEnviarInformeWhatsApp()
                                }
                                // Si era para cargar lote (1002), el usuario puede intentar nuevamente
                                // No hacemos nada automático aquí, solo mostramos el mensaje
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(this@VencimientosActivity, "⚠️ Aún falta cargar el Stock Final de ayer", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("VencimientosActivity", "Error verificando stock final después de cargar: ${e.message}", e)
                    }
                }
            }
        }
    }
    
    /**
     * Muestra un diálogo para sincronizar manualmente los lotes con el stock final
     */
    private fun mostrarDialogoSincronizacion() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("🔄 Sincronizar Lotes con Stock Final")
        builder.setMessage("Esta función ajustará los lotes para que coincidan exactamente con el Stock Final de hoy.\n\n" +
                "• Si hay más lotes que stock: se descontarán los lotes más viejos\n" +
                "• Si hay menos lotes que stock: se mostrará un aviso\n" +
                "• Si el stock es 0: se eliminarán todos los lotes\n\n" +
                "¿Deseas continuar?")
        
        builder.setPositiveButton("✅ Sincronizar") { _, _ ->
            sincronizarLotesConStockFinal()
        }
        
        builder.setNegativeButton("❌ Cancelar", null)
        
        val dialog = builder.create()
        dialog.show()
    }
    
    /**
     * Sincroniza manualmente los lotes con el stock final de hoy
     */
    private fun sincronizarLotesConStockFinal() {
        lifecycleScope.launch {
            try {
                runOnUiThread {
                    Toast.makeText(this@VencimientosActivity, "🔄 Sincronizando lotes con stock final...", Toast.LENGTH_SHORT).show()
                }
                
                val fechaHoy = DateHelper.getFechaActual()
                
                // Obtener Stock Final de hoy
                val stockFinalRegistro = localRepository
                    .getRegistrosByFechaYTipo(localId, fechaHoy, "Stock Final")
                    .first()
                    .firstOrNull()
                
                if (stockFinalRegistro == null) {
                    runOnUiThread {
                        AlertDialog.Builder(this@VencimientosActivity)
                            .setTitle("⚠️ Stock Final no encontrado")
                            .setMessage("No se encontró el Stock Final de hoy ($fechaHoy).\n\n" +
                                    "Debes cargar el Stock Final primero antes de sincronizar los lotes.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    return@launch
                }
                
                // Preparar mapa de stock final normalizado
                val stockFinalPorProducto = stockFinalRegistro.productos.associate { producto ->
                    val codigo = producto.nombre.split(" - ").firstOrNull() ?: producto.nombre
                    val codigoNormalizado = normalizarCodigoProducto(codigo)
                    codigoNormalizado to producto.cantidad
                }
                
                Log.d("VENCIMIENTOS", "🔄 SINCRONIZACIÓN MANUAL - Stock Final por producto: $stockFinalPorProducto")
                
                // Ejecutar sincronización
                val vencimientoManager = com.tuapp.inventario.manager.VencimientoManager(repository)
                vencimientoManager.sincronizarLotesConStockFinal(localId, fechaHoy, stockFinalPorProducto)
                
                // Limpiar cache de lotes para forzar recarga
                repository.clearLotesCache(localId)
                
                // Recargar lotes
                delay(500)
                cargarLotesActivos()
                
                runOnUiThread {
                    Toast.makeText(this@VencimientosActivity, "✅ Sincronización completada", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e("VENCIMIENTOS", "❌ Error sincronizando lotes: ${e.message}", e)
                runOnUiThread {
                    AlertDialog.Builder(this@VencimientosActivity)
                        .setTitle("❌ Error")
                        .setMessage("Error al sincronizar lotes:\n${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }
    
}

