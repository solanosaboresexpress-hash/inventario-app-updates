package com.tuapp.inventario

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tuapp.inventario.model.*
import com.tuapp.inventario.repository.PapeleriaRepository
import kotlinx.coroutines.launch
import com.tuapp.inventario.utils.DateHelper
import java.util.*

class StockPapeleriaActivity : AppCompatActivity() {
    
    private lateinit var btnVolver: Button
    private lateinit var txtTitulo: TextView
    private lateinit var txtFecha: TextView
    private lateinit var layoutContenido: LinearLayout
    private lateinit var btnGuardar: Button
    private lateinit var btnLimpiar: Button
    
    private lateinit var repository: PapeleriaRepository
    private lateinit var localId: String
    private lateinit var fechaSeleccionada: String
    private lateinit var localNombre: String
    
    private val productos = ProductoPapeleria.getProductosPapeleria()
    private val editTextsStock = mutableMapOf<String, EditText>()
    private val editTextsIngreso = mutableMapOf<String, EditText>()
    private var modoSoloLectura = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_papeleria)
        
        // Obtener datos del intent
        fechaSeleccionada = intent.getStringExtra("fechaSeleccionada") 
            ?: DateHelper.getDateFormat("yyyy-MM-dd").format(Date())
        
        // Verificar si es lunes para permitir edició³n
        val esLunesValido = esLunes(fechaSeleccionada)
        val esFechaFutura = esFechaFutura(fechaSeleccionada)
        
        if (!esLunesValido && !esFechaFutura) {
            // Si no es lunes pero es fecha futura, mostrar error
            mostrarErrorDiaIncorrecto()
            return
        }
        
        // Si no es lunes pero es fecha pasada, permitir solo lectura
        modoSoloLectura = !esLunesValido
        
        // Obtener datos del usuario
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        localId = prefs.getString("localId", "default") ?: "default"
        localNombre = prefs.getString("localNombre", "Local") ?: "Local"
        
        // Inicializar repositorio
        repository = PapeleriaRepository(localId)
        
        initViews()
        setupListeners()
        cargarDatosExistentes()
    }
    
    private fun initViews() {
        btnVolver = findViewById(R.id.btnVolver)
        txtTitulo = findViewById(R.id.txtTitulo)
        txtFecha = findViewById(R.id.txtFecha)
        layoutContenido = findViewById(R.id.layoutContenido)
        btnGuardar = findViewById(R.id.btnGuardar)
        btnLimpiar = findViewById(R.id.btnLimpiar)
        
        // Configurar tó­tulo y fecha
        val titulo = if (modoSoloLectura) "ðŸ“¦ Stock Papeleró­a (Solo Lectura)" else "ðŸ“¦ Stock Papeleró­a"
        txtTitulo.text = titulo
        
        // El indicador de local se agregaró¡ en crearInterfazProductos() despuó©s de removeAllViews()
        val formatoFecha = DateHelper.getDateFormat("EEEE - dd/MM/yy", Locale("es", "ES"))
        val fecha = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaSeleccionada)
        txtFecha.text = if (fecha != null) formatoFecha.format(fecha) else fechaSeleccionada
        
        // Configurar botones segóºn el modo
        if (modoSoloLectura) {
            btnGuardar.visibility = View.GONE
            btnLimpiar.visibility = View.GONE
        }
        
        crearInterfazProductos()
    }
    
    private fun setupListeners() {
        btnVolver.setOnClickListener {
            @Suppress("DEPRECATION")
            onBackPressed()
        }
        
        btnGuardar.setOnClickListener {
            guardarStock()
        }
        
        btnLimpiar.setOnClickListener {
            limpiarCampos()
        }
    }
    
    private fun crearInterfazProductos() {
        layoutContenido.removeAllViews()
        
        // Agregar indicador de local VISUALMENTE ARRIBA de todo (al inicio del layout principal)
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
        
        // Agrupar productos por categoró­a
        val productosPorCategoria = productos.groupBy { it.categoria }
        
        productosPorCategoria.entries.forEachIndexed { index, entry ->
            val categoria = entry.key
            val productosCategoria = entry.value
            // Tó­tulo de categoró­a
            val txtCategoria = TextView(this).apply {
                text = "ðŸ“ $categoria"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(16, 24, 16, 12)
                setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
                setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
            }
            layoutContenido.addView(txtCategoria)
            
            // Crear fila para cada producto
            productosCategoria.forEach { producto ->
                val filaProducto = crearFilaProducto(producto)
                layoutContenido.addView(filaProducto)
            }
            
            // Agregar separador entre categoró­as (excepto la óºltima)
            if (index < productosPorCategoria.size - 1) {
                val separador = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        16
                    )
                }
                layoutContenido.addView(separador)
            }
        }
    }
    
    private fun crearFilaProducto(producto: ProductoPapeleria): LinearLayout {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(resources.getColor(R.color.surface_color, null))
            // Agregar elevació³n para modo oscuro
            elevation = 4f
        }
        
        // Nombre del producto (45% del ancho)
        val txtProducto = TextView(this).apply {
            text = producto.nombre
            textSize = 14f
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.45f)
            setPadding(8, 12, 8, 12)
        }
        fila.addView(txtProducto)
        
        // Campo Stock (25% del ancho)
        val txtStock = TextView(this).apply {
            text = "Stock:"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.15f)
            setPadding(8, 12, 4, 12)
        }
        fila.addView(txtStock)
        
        val editStock = if (modoSoloLectura) {
            TextView(this).apply {
                text = "0"
                textSize = 14f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                setPadding(12, 12, 12, 12)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f)
            }
        } else {
            EditText(this).apply {
                hint = "0"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                textSize = 14f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                setHintTextColor(resources.getColor(android.R.color.darker_gray, null))
                setPadding(12, 12, 12, 12)
                background = resources.getDrawable(android.R.drawable.edit_text, null)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f)
            }
        }
        if (editStock is EditText) {
            editTextsStock[producto.nombre] = editStock
        }
        fila.addView(editStock)
        
        // Campo Ingreso (20% del ancho)
        val txtIngreso = TextView(this).apply {
            text = "Ingreso:"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.15f)
            setPadding(8, 12, 4, 12)
        }
        fila.addView(txtIngreso)
        
        val editIngreso = if (modoSoloLectura) {
            TextView(this).apply {
                text = "0"
                textSize = 14f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                setPadding(12, 12, 12, 12)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f)
            }
        } else {
            EditText(this).apply {
                hint = "0"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                textSize = 14f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                setHintTextColor(resources.getColor(android.R.color.darker_gray, null))
                setPadding(12, 12, 12, 12)
                background = resources.getDrawable(android.R.drawable.edit_text, null)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f)
            }
        }
        if (editIngreso is EditText) {
            editTextsIngreso[producto.nombre] = editIngreso
        }
        fila.addView(editIngreso)
        
        return fila
    }
    
    private fun cargarDatosExistentes() {
        lifecycleScope.launch {
            try {
                val stockExistente = repository.obtenerStockPapeleria(fechaSeleccionada)
                if (stockExistente != null) {
                    // Cargar datos existentes
                    stockExistente.productos.forEach { producto ->
                        editTextsStock[producto.nombre]?.setText(producto.stock.toString())
                        editTextsIngreso[producto.nombre]?.setText(producto.ingreso.toString())
                    }
                    val mensaje = if (modoSoloLectura) "Datos cargados (Solo Lectura)" else "Datos cargados"
                    Toast.makeText(this@StockPapeleriaActivity, mensaje, Toast.LENGTH_SHORT).show()
                } else {
                    // Si no hay datos y es modo solo lectura, mostrar mensaje
                    if (modoSoloLectura) {
                        Toast.makeText(this@StockPapeleriaActivity, "No hay datos para esta fecha", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("StockPapeleriaActivity", "Error cargando datos existentes", e)
                Toast.makeText(this@StockPapeleriaActivity, "Error cargando datos: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun guardarStock() {
        lifecycleScope.launch {
            try {
                val productosConDatos = productos.map { producto ->
                    val stock = editTextsStock[producto.nombre]?.text?.toString()?.toIntOrNull() ?: 0
                    val ingreso = editTextsIngreso[producto.nombre]?.text?.toString()?.toIntOrNull() ?: 0
                    
                    producto.copy(
                        stock = stock,
                        ingreso = ingreso
                    )
                }
                
                val stockPapeleria = StockPapeleria(
                    fecha = fechaSeleccionada,
                    local = localNombre,
                    productos = productosConDatos
                )
                
                repository.guardarStockPapeleria(stockPapeleria)
                
                Toast.makeText(this@StockPapeleriaActivity, "Stock guardado exitosamente", Toast.LENGTH_SHORT).show()
                
                // Volver a la actividad principal
                finish()
                
            } catch (e: Exception) {
                Log.e("StockPapeleriaActivity", "Error guardando stock", e)
                Toast.makeText(this@StockPapeleriaActivity, "Error guardando: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun limpiarCampos() {
        editTextsStock.values.forEach { it.setText("0") }
        editTextsIngreso.values.forEach { it.setText("0") }
    }
    
    private fun esLunes(fecha: String): Boolean {
        return try {
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val date = formato.parse(fecha) ?: Date()
            val calendar = DateHelper.getCalendar().apply { time = date }
            calendar.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY
        } catch (e: Exception) {
            false
        }
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
        val dialog = AlertDialog.Builder(this)
            .setTitle("âŒ Dó­a Incorrecto")
            .setMessage("El stock de papeleró­a solo se puede cargar los LUNES.\n\nSelecciona un lunes para continuar.")
            .setPositiveButton("Volver") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .create()
        
        dialog.show()
    }
    
    private fun obtenerLocalNombre(): String {
        return try {
            val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
            val localNombre = prefs.getString("localNombre", "")
            if (localNombre.isNullOrEmpty()) {
                android.util.Log.e("StockPapeleriaActivity", "No se encontró³ localNombre en SharedPreferences")
                "NO SELECCIONADO"
            } else {
                android.util.Log.d("StockPapeleriaActivity", "LocalNombre obtenido: $localNombre")
                localNombre
            }
        } catch (e: Exception) {
            android.util.Log.e("StockPapeleriaActivity", "Error obteniendo localNombre", e)
            "NO SELECCIONADO"
        }
    }
}
