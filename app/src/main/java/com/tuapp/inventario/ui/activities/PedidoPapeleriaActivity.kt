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

class PedidoPapeleriaActivity : AppCompatActivity() {
    
    private lateinit var btnVolver: Button
    private lateinit var txtTitulo: TextView
    private lateinit var txtFecha: TextView
    private lateinit var layoutContenido: LinearLayout
    private lateinit var btnRecalcular: Button
    private lateinit var btnGuardar: Button
    private lateinit var scrollView: ScrollView
    
    private lateinit var repository: PapeleriaRepository
    private lateinit var localId: String
    private lateinit var fechaSeleccionada: String
    private lateinit var localNombre: String
    private lateinit var usuarioId: String
    
    private var productosPedido = mutableListOf<ProductoPedidoPapeleria>()
    private val editTextsPedido = mutableMapOf<String, EditText>()
    private var modoSoloLectura = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pedido_papeleria)
        
        // Obtener datos del intent
        fechaSeleccionada = intent.getStringExtra("fechaSeleccionada") 
            ?: DateHelper.getDateFormat("yyyy-MM-dd").format(Date())
        
        // Verificar si es viernes para permitir edició³n
        val esViernesValido = esViernes(fechaSeleccionada)
        val esFechaFutura = esFechaFutura(fechaSeleccionada)
        
        if (!esViernesValido && !esFechaFutura) {
            // Si no es viernes pero es fecha futura, mostrar error
            mostrarErrorDiaIncorrecto()
            return
        }
        
        // Si no es viernes pero es fecha pasada, permitir solo lectura
        modoSoloLectura = !esViernesValido
        
        // Obtener datos del usuario
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        localId = prefs.getString("localId", "default") ?: "default"
        localNombre = prefs.getString("localNombre", "Local") ?: "Local"
        usuarioId = prefs.getString("usuarioId", "usuario") ?: "usuario"
        
        // Inicializar repositorio
        repository = PapeleriaRepository(localId)
        
        initViews()
        setupListeners()
        cargarDatosYCalcular()
    }
    
    private fun initViews() {
        btnVolver = findViewById(R.id.btnVolver)
        txtTitulo = findViewById(R.id.txtTitulo)
        txtFecha = findViewById(R.id.txtFecha)
        layoutContenido = findViewById(R.id.layoutContenido)
        btnRecalcular = findViewById(R.id.btnRecalcular)
        btnGuardar = findViewById(R.id.btnGuardar)
        scrollView = findViewById(R.id.scrollView)
        
        // Configurar tó­tulo y fecha
        val titulo = if (modoSoloLectura) "ðŸ§¾ Pedido Papeleró­a (Solo Lectura)" else "ðŸ§¾ Pedido Papeleró­a"
        txtTitulo.text = titulo
        
        // El indicador de local se agregaró¡ en crearInterfazProductos() despuó©s de removeAllViews()
        val formatoFecha = DateHelper.getDateFormat("EEEE - dd/MM/yy", Locale("es", "ES"))
        val fecha = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaSeleccionada)
        txtFecha.text = if (fecha != null) formatoFecha.format(fecha) else fechaSeleccionada
        
        // Configurar botones segóºn el modo
        if (modoSoloLectura) {
            btnRecalcular.visibility = View.GONE
            btnGuardar.visibility = View.GONE
        }
    }
    
    private fun setupListeners() {
        btnVolver.setOnClickListener {
            @Suppress("DEPRECATION")
            onBackPressed()
        }
        
        btnRecalcular.setOnClickListener {
            cargarDatosYCalcular()
        }
        
        btnGuardar.setOnClickListener {
            guardarPedido()
        }
    }
    
    private fun cargarDatosYCalcular() {
        lifecycleScope.launch {
            try {
                // Mostrar indicador de carga
                mostrarIndicadorCarga()
                
                // Obtener los dos óºltimos stocks
                val (stockActual, stockAnterior) = repository.obtenerUltimosDosStocks()
                
                // Calcular consumo y sugerencias
                productosPedido = repository.calcularConsumoYSugerencias(stockActual, stockAnterior).toMutableList()
                
                // Crear interfaz
                crearInterfazProductos()
                
                ocultarIndicadorCarga()
                
            } catch (e: Exception) {
                Log.e("PedidoPapeleriaActivity", "Error cargando datos", e)
                ocultarIndicadorCarga()
                Toast.makeText(this@PedidoPapeleriaActivity, "Error cargando datos: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
        
        // Crear encabezados de la tabla
        val encabezados = crearEncabezadosTabla()
        layoutContenido.addView(encabezados)
        
        // Crear fila para cada producto
        productosPedido.forEach { producto ->
            val filaProducto = crearFilaProducto(producto)
            layoutContenido.addView(filaProducto)
        }
        
        // Agregar botones al final
        val layoutBotones = crearLayoutBotones()
        layoutContenido.addView(layoutBotones)
    }
    
    private fun crearEncabezadosTabla(): LinearLayout {
        val encabezados = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark, null))
            elevation = 6f
        }
        
        val encabezadosTexto = listOf("Producto", "Stock Lunes", "Cons. Est. Viernes", "Stock Est. Viernes", "Pedido Sugerido", "Pedido Final")
        val pesos = listOf(0.35f, 0.13f, 0.13f, 0.13f, 0.13f, 0.13f)
        
        encabezadosTexto.forEachIndexed { index, texto ->
            val txtEncabezado = TextView(this).apply {
                text = texto
                textSize = 11f
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
    
    private fun crearFilaProducto(producto: ProductoPedidoPapeleria): LinearLayout {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(resources.getColor(R.color.surface_color, null))
            // Agregar elevació³n para modo oscuro
            elevation = 4f
        }
        
        // Nombre del producto (35% del ancho)
        val txtProducto = TextView(this).apply {
            text = producto.nombre
            textSize = 12f
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.35f)
            setPadding(8, 12, 4, 12)
        }
        fila.addView(txtProducto)
        
        // Stock Lunes (15% del ancho)
        val txtStockLunes = TextView(this).apply {
            text = producto.stockLunes.toString()
            textSize = 12f
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.13f)
            setPadding(4, 12, 4, 12)
        }
        fila.addView(txtStockLunes)
        
        // Consumo Estimado Viernes (15% del ancho)
        val txtConsumo = TextView(this).apply {
            text = producto.consumoEstimadoViernes.toString()
            textSize = 12f
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.13f)
            setPadding(4, 12, 4, 12)
        }
        fila.addView(txtConsumo)
        
        // Stock Estimado Viernes (15% del ancho)
        val txtStockViernes = TextView(this).apply {
            text = producto.stockEstimadoViernes.toString()
            textSize = 12f
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.13f)
            setPadding(4, 12, 4, 12)
        }
        fila.addView(txtStockViernes)
        
        // Pedido Sugerido (15% del ancho)
        val txtPedidoSugerido = TextView(this).apply {
            text = producto.pedidoSugerido.toString()
            textSize = 12f
            setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.13f)
            setPadding(4, 12, 4, 12)
        }
        fila.addView(txtPedidoSugerido)
        
        // Pedido Final (15% del ancho) - Editable o Solo Lectura
        val editPedidoFinal = if (modoSoloLectura) {
            TextView(this).apply {
                text = producto.pedidoFinal.toString()
                textSize = 12f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                gravity = android.view.Gravity.CENTER
                setPadding(8, 8, 8, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.13f)
            }
        } else {
            EditText(this).apply {
                setText(producto.pedidoFinal.toString())
                textSize = 12f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                setHintTextColor(resources.getColor(android.R.color.darker_gray, null))
                gravity = android.view.Gravity.CENTER
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setPadding(8, 8, 8, 8)
                background = resources.getDrawable(android.R.drawable.edit_text, null)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.13f)
            }
        }
        if (editPedidoFinal is EditText) {
            editTextsPedido[producto.nombre] = editPedidoFinal
        }
        fila.addView(editPedidoFinal)
        
        return fila
    }
    
    private fun crearLayoutBotones(): LinearLayout {
        val layoutBotones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 16)
        }
        
        // Botó³n Recalcular
        val btnRecalcular = Button(this).apply {
            text = "ðŸ”„ Recalcular"
            textSize = 14f
            setBackgroundColor(resources.getColor(android.R.color.holo_orange_dark, null))
            setTextColor(resources.getColor(R.color.web_text_light, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f).apply {
                setMargins(16, 0, 8, 0)
            }
            setOnClickListener { cargarDatosYCalcular() }
        }
        layoutBotones.addView(btnRecalcular)
        
        // Botó³n Guardar
        val btnGuardar = Button(this).apply {
            text = "ðŸ’¾ Guardar Pedido"
            textSize = 14f
            setBackgroundColor(resources.getColor(android.R.color.holo_green_dark, null))
            setTextColor(resources.getColor(R.color.web_text_light, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f).apply {
                setMargins(8, 0, 16, 0)
            }
            setOnClickListener { guardarPedido() }
        }
        layoutBotones.addView(btnGuardar)
        
        return layoutBotones
    }
    
    private fun guardarPedido() {
        lifecycleScope.launch {
            try {
                // Actualizar pedidos finales con los valores de los EditText
                productosPedido = productosPedido.map { producto ->
                    val pedidoFinal = editTextsPedido[producto.nombre]?.text?.toString()?.toIntOrNull() ?: producto.pedidoFinal
                    producto.copy(pedidoFinal = pedidoFinal)
                }.toMutableList()
                
                val pedido = PedidoPapeleria(
                    fecha = fechaSeleccionada,
                    local = localNombre,
                    productos = productosPedido,
                    creadoPor = usuarioId,
                    totalProductos = productosPedido.size
                )
                
                repository.guardarPedidoPapeleria(pedido)
                
                Toast.makeText(this@PedidoPapeleriaActivity, "Pedido guardado exitosamente", Toast.LENGTH_SHORT).show()
                
                // Volver a la actividad principal
                finish()
                
            } catch (e: Exception) {
                Log.e("PedidoPapeleriaActivity", "Error guardando pedido", e)
                Toast.makeText(this@PedidoPapeleriaActivity, "Error guardando pedido: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun mostrarIndicadorCarga() {
        // Crear un indicador de carga simple
        val progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
        
        val txtCargando = TextView(this).apply {
            text = "Calculando consumo y sugerencias..."
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
    
    private fun esViernes(fecha: String): Boolean {
        return try {
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val date = formato.parse(fecha) ?: Date()
            val calendar = DateHelper.getCalendar().apply { time = date }
            calendar.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
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
            .setMessage("Los pedidos de papeleró­a solo se pueden generar los VIERNES.\n\nSelecciona un viernes para continuar.")
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
                android.util.Log.e("PedidoPapeleriaActivity", "No se encontró³ localNombre en SharedPreferences")
                "NO SELECCIONADO"
            } else {
                android.util.Log.d("PedidoPapeleriaActivity", "LocalNombre obtenido: $localNombre")
                localNombre
            }
        } catch (e: Exception) {
            android.util.Log.e("PedidoPapeleriaActivity", "Error obteniendo localNombre", e)
            "NO SELECCIONADO"
        }
    }
}
