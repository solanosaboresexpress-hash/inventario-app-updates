package com.tuapp.inventario

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tuapp.inventario.model.*
import com.tuapp.inventario.repository.BebidasRepository
import kotlinx.coroutines.launch
import com.tuapp.inventario.utils.DateHelper
import java.util.*

class StockBebidasActivity : AppCompatActivity() {
    
    private lateinit var btnVolver: Button
    private lateinit var txtTitulo: TextView
    private lateinit var txtFecha: TextView
    private lateinit var layoutContenido: LinearLayout
    private var btnGuardar: Button? = null
    private lateinit var scrollView: ScrollView
    
    private lateinit var repository: BebidasRepository
    private lateinit var localId: String
    private lateinit var fechaSeleccionada: String
    private lateinit var localNombre: String
    private lateinit var usuarioId: String
    
    private val editTextsStock = mutableMapOf<String, EditText>()
    private val editTextsIngreso = mutableMapOf<String, EditText>()
    private var modoSoloLectura = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_bebidas)
        
        // Obtener datos del usuario primero
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        localId = prefs.getString("localId", "default") ?: "default"
        localNombre = prefs.getString("localNombre", "Local") ?: "Local"
        usuarioId = prefs.getString("usuarioId", "usuario") ?: "usuario"
        
        // Inicializar repositorio
        repository = BebidasRepository(localId)
        
        // Obtener datos del intent
        fechaSeleccionada = intent.getStringExtra("fechaSeleccionada") 
            ?: DateHelper.getDateFormat("yyyy-MM-dd").format(Date())
        
        // Verificar si es dó­a vó¡lido para ingreso
        val esDiaValido = esDiaValidoParaIngreso(fechaSeleccionada)
        val esFechaFutura = esFechaFutura(fechaSeleccionada)
        
        if (!esDiaValido && !esFechaFutura) {
            mostrarErrorDiaIncorrecto()
            return
        }
        
        // Si no es dó­a vó¡lido pero es fecha futura, permitir solo lectura
        modoSoloLectura = !esDiaValido
        
        initViews()
        setupListeners()
        cargarDatos()
    }
    
    private fun initViews() {
        btnVolver = findViewById(R.id.btnVolver)
        txtTitulo = findViewById(R.id.txtTitulo)
        txtFecha = findViewById(R.id.txtFecha)
        layoutContenido = findViewById(R.id.layoutContenido)
        scrollView = findViewById(R.id.scrollView)
        
        // Configurar tó­tulo y fecha
        val titulo = if (modoSoloLectura) "ðŸ¥¤ Stock Bebidas (Solo Lectura)" else "ðŸ¥¤ Stock Bebidas"
        txtTitulo.text = titulo
        
        // El indicador de local se agregaró¡ en crearInterfazProductos() despuó©s de removeAllViews()
        val formatoFecha = DateHelper.getDateFormat("EEEE - dd/MM/yy", Locale("es", "ES"))
        val fecha = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaSeleccionada)
        txtFecha.text = if (fecha != null) formatoFecha.format(fecha) else fechaSeleccionada
        
        // Configurar botó³n segóºn el modo
        if (modoSoloLectura) {
            btnGuardar?.visibility = View.GONE
        }
    }
    
    private fun setupListeners() {
        btnVolver.setOnClickListener {
            @Suppress("DEPRECATION")
            onBackPressed()
        }
        
        btnGuardar?.setOnClickListener {
            guardarStock()
        }
    }
    
    private fun cargarDatos() {
        lifecycleScope.launch {
            try {
                mostrarIndicadorCarga()
                
                val stockExistente = repository.obtenerStockBebidas(fechaSeleccionada)
                
                crearInterfazProductos(stockExistente)
                
                ocultarIndicadorCarga()
                
            } catch (e: Exception) {
                Log.e("StockBebidasActivity", "Error cargando datos", e)
                ocultarIndicadorCarga()
                Toast.makeText(this@StockBebidasActivity, "Error cargando datos: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun crearInterfazProductos(stockExistente: StockBebida?) {
        layoutContenido.removeAllViews()
        
        // Agregar indicador de local ARRIBA del tó­tulo principal
        val localNombre = obtenerLocalNombre()
        val indicadorLocal = TextView(this).apply {
            text = "LOCAL: $localNombre"
            textSize = 16f
            setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 5, 0, 10)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        
        // Agregar el indicador VISUALMENTE ARRIBA de todo (al inicio del layout principal)
        val rootLayout = findViewById<LinearLayout>(R.id.layoutContenido).parent.parent as LinearLayout
        rootLayout.addView(indicadorLocal, 0)
        
        // Crear encabezados de la tabla
        val encabezados = crearEncabezadosTabla()
        layoutContenido.addView(encabezados)
        
        // Crear fila para cada producto
        repository.productosBebidas.forEach { nombreProducto ->
            val stockActual = stockExistente?.productos?.find { it.nombre == nombreProducto }?.stock ?: 0
            val ingresoActual = stockExistente?.productos?.find { it.nombre == nombreProducto }?.ingreso ?: 0
            
            val filaProducto = crearFilaProducto(nombreProducto, stockActual, ingresoActual)
            layoutContenido.addView(filaProducto)
        }
        
        // Agregar botó³n de guardar al final
        if (!modoSoloLectura) {
            val layoutBotones = crearLayoutBotones()
            layoutContenido.addView(layoutBotones)
        }
    }
    
    private fun crearEncabezadosTabla(): LinearLayout {
        val encabezados = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark, null))
            elevation = 6f
        }
        
        val encabezadosTexto = listOf("Producto", "Stock Actual", "Ingreso")
        val pesos = listOf(0.6f, 0.2f, 0.2f)
        
        encabezadosTexto.forEachIndexed { index, texto ->
            val txtEncabezado = TextView(this).apply {
                text = texto
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setTextColor(resources.getColor(R.color.web_text_light, null))
                setPadding(4, 8, 4, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, pesos[index])
            }
            encabezados.addView(txtEncabezado)
        }
        
        return encabezados
    }
    
    private fun crearFilaProducto(nombreProducto: String, stockActual: Int, ingresoActual: Int): LinearLayout {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(resources.getColor(R.color.surface_color, null))
            elevation = 4f
        }
        
        // Nombre del producto (60% del ancho)
        val txtProducto = TextView(this).apply {
            text = nombreProducto
            textSize = 12f
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
            setPadding(8, 12, 4, 12)
        }
        fila.addView(txtProducto)
        
        // Stock Actual (20% del ancho)
        val editStock = if (modoSoloLectura) {
            TextView(this).apply {
                text = stockActual.toString()
                textSize = 12f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                gravity = android.view.Gravity.CENTER
                setPadding(8, 8, 8, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f)
            }
        } else {
            EditText(this).apply {
                setText(stockActual.toString())
                textSize = 12f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                setHintTextColor(resources.getColor(android.R.color.darker_gray, null))
                gravity = android.view.Gravity.CENTER
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setPadding(8, 8, 8, 8)
                background = resources.getDrawable(android.R.drawable.edit_text, null)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f)
            }
        }
        if (editStock is EditText) {
            editTextsStock[nombreProducto] = editStock
        }
        fila.addView(editStock)
        
        // Ingreso (20% del ancho)
        val editIngreso = if (modoSoloLectura) {
            TextView(this).apply {
                text = ingresoActual.toString()
                textSize = 12f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                gravity = android.view.Gravity.CENTER
                setPadding(8, 8, 8, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f)
            }
        } else {
            EditText(this).apply {
                setText(ingresoActual.toString())
                textSize = 12f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                setHintTextColor(resources.getColor(android.R.color.darker_gray, null))
                gravity = android.view.Gravity.CENTER
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setPadding(8, 8, 8, 8)
                background = resources.getDrawable(android.R.drawable.edit_text, null)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f)
            }
        }
        if (editIngreso is EditText) {
            editTextsIngreso[nombreProducto] = editIngreso
        }
        fila.addView(editIngreso)
        
        return fila
    }
    
    private fun crearLayoutBotones(): LinearLayout {
        val layoutBotones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 16)
        }
        
        // Botó³n Guardar (amarillo como prefieres)
        btnGuardar = Button(this).apply {
            text = "ðŸ’¾ Guardar Stock"
            textSize = 16f
            background = resources.getDrawable(R.drawable.button_primary_background, null)
            setTextColor(resources.getColor(R.color.brand_black, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).apply {
                setMargins(16, 0, 16, 0)
            }
            setOnClickListener { guardarStock() }
        }
        layoutBotones.addView(btnGuardar)
        
        return layoutBotones
    }
    
    private fun guardarStock() {
        lifecycleScope.launch {
            try {
                val productos = repository.productosBebidas.map { nombreProducto ->
                    val stock = editTextsStock[nombreProducto]?.text?.toString()?.toIntOrNull() ?: 0
                    val ingreso = editTextsIngreso[nombreProducto]?.text?.toString()?.toIntOrNull() ?: 0
                    
                    ProductoBebida(
                        nombre = nombreProducto,
                        stock = stock,
                        ingreso = ingreso
                    )
                }
                
                val diaSemana = repository.obtenerDiaSemana(fechaSeleccionada)
                val tipoCarga = when (diaSemana) {
                    "Martes" -> "Ingreso Martes (Pedido Lunes)"
                    "Viernes" -> "Ingreso Viernes (Pedido Jueves)"
                    else -> "Ingreso $diaSemana"
                }
                
                val stockBebida = StockBebida(
                    fecha = fechaSeleccionada,
                    diaSemana = diaSemana,
                    tipoCarga = tipoCarga,
                    local = localNombre,
                    productos = productos
                )
                
                repository.guardarStockBebidas(stockBebida)
                
                Toast.makeText(this@StockBebidasActivity, "Stock guardado exitosamente", Toast.LENGTH_SHORT).show()
                
                finish()
                
            } catch (e: Exception) {
                Log.e("StockBebidasActivity", "Error guardando stock", e)
                Toast.makeText(this@StockBebidasActivity, "Error guardando stock: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun mostrarIndicadorCarga() {
        val progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
        
        val txtCargando = TextView(this).apply {
            text = "Cargando datos..."
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }
        
        val layoutCarga = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        
        layoutCarga.addView(progressBar)
        layoutCarga.addView(txtCargando)
        
        layoutContenido.removeAllViews()
        layoutContenido.addView(layoutCarga)
    }
    
    private fun ocultarIndicadorCarga() {
        // El indicador se oculta cuando se llama a crearInterfazProductos()
    }
    
    private fun esDiaValidoParaIngreso(fecha: String): Boolean {
        return repository.esDiaValidoParaIngreso(fecha)
    }
    
    private fun esFechaFutura(fecha: String): Boolean {
        return try {
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val fechaSeleccionada = formato.parse(fecha) ?: Date()
            val hoy = DateHelper.getCalendar()
            hoy.set(Calendar.HOUR_OF_DAY, 0)
            hoy.set(Calendar.MINUTE, 0)
            hoy.set(Calendar.SECOND, 0)
            hoy.set(Calendar.MILLISECOND, 0)
            
            val fechaSeleccionadaCalendar = DateHelper.getCalendar().apply { time = fechaSeleccionada }
            fechaSeleccionadaCalendar.after(hoy)
        } catch (e: Exception) {
            false
        }
    }
    
    private fun mostrarErrorDiaIncorrecto() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("âŒ Dó­a Incorrecto")
            .setMessage("Los ingresos de bebidas solo se pueden cargar los MARTES y VIERNES.\n\nSelecciona un dó­a vó¡lido para continuar.")
            .setPositiveButton("Volver") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .create()
            .show()
    }
    
    private fun obtenerLocalNombre(): String {
        return try {
            val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
            val localNombre = prefs.getString("localNombre", "")
            if (localNombre.isNullOrEmpty()) {
                android.util.Log.e("StockBebidasActivity", "No se encontró³ localNombre en SharedPreferences")
                "NO SELECCIONADO"
            } else {
                android.util.Log.d("StockBebidasActivity", "LocalNombre obtenido: $localNombre")
                localNombre
            }
        } catch (e: Exception) {
            android.util.Log.e("StockBebidasActivity", "Error obteniendo localNombre", e)
            "NO SELECCIONADO"
        }
    }
}
