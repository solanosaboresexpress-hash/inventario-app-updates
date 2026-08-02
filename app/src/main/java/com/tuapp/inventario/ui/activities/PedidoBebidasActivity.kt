package com.tuapp.inventario

import android.app.AlertDialog
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

class PedidoBebidasActivity : AppCompatActivity() {
    
    private lateinit var btnVolver: Button
    private lateinit var txtTitulo: TextView
    private lateinit var txtFecha: TextView
    private lateinit var layoutContenido: LinearLayout
    private var btnRecalcular: Button? = null
    private var btnGuardar: Button? = null
    private lateinit var scrollView: ScrollView
    
    private lateinit var repository: BebidasRepository
    private lateinit var localId: String
    private lateinit var fechaSeleccionada: String
    private lateinit var localNombre: String
    private lateinit var usuarioId: String
    
    private var productosPedido = mutableListOf<ProductoPedidoBebida>()
    private val editTextsPedido = mutableMapOf<String, EditText>()
    private var modoSoloLectura = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pedido_bebidas)
        
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
        
        // Verificar si es dó­a vó¡lido para pedidos
        val esDiaValido = esDiaValidoParaPedido(fechaSeleccionada)
        val esFechaFutura = esFechaFutura(fechaSeleccionada)
        
        if (!esDiaValido && !esFechaFutura) {
            mostrarErrorDiaIncorrecto()
            return
        }
        
        // Si no es dó­a vó¡lido pero es fecha futura, permitir solo lectura
        modoSoloLectura = !esDiaValido
        
        initViews()
        setupListeners()
        cargarDatosYCalcular()
    }
    
    private fun initViews() {
        btnVolver = findViewById(R.id.btnVolver)
        txtTitulo = findViewById(R.id.txtTitulo)
        txtFecha = findViewById(R.id.txtFecha)
        layoutContenido = findViewById(R.id.layoutContenido)
        scrollView = findViewById(R.id.scrollView)
        
        // Configurar tó­tulo y fecha
        val titulo = if (modoSoloLectura) "ðŸ¥¤ Pedido Bebidas (Solo Lectura)" else "ðŸ¥¤ Pedido Bebidas"
        txtTitulo.text = titulo
        
        // El indicador de local se agregaró¡ en crearInterfazProductos() despuó©s de removeAllViews()
        val formatoFecha = DateHelper.getDateFormat("EEEE - dd/MM/yy", Locale("es", "ES"))
        val fecha = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaSeleccionada)
        txtFecha.text = if (fecha != null) formatoFecha.format(fecha) else fechaSeleccionada
        
        // Configurar botones segóºn el modo
        if (modoSoloLectura) {
            btnRecalcular?.visibility = View.GONE
            btnGuardar?.visibility = View.GONE
        }
    }
    
    private fun setupListeners() {
        btnVolver.setOnClickListener {
            onBackPressed()
        }
        
        btnRecalcular?.setOnClickListener {
            cargarDatosYCalcular()
        }
        
        btnGuardar?.setOnClickListener {
            guardarPedido()
        }
    }
    
    private fun cargarDatosYCalcular() {
        lifecycleScope.launch {
            try {
                mostrarIndicadorCarga()
                
                // Obtener los dos óºltimos stocks
                val (stockActual, stockAnterior) = repository.obtenerUltimosDosStocks()
                
                // Calcular consumo y sugerencias
                productosPedido = repository.calcularConsumoYSugerencias(stockActual, stockAnterior).toMutableList()
                
                // Crear interfaz
                crearInterfazProductos()
                
                ocultarIndicadorCarga()
                
            } catch (e: Exception) {
                Log.e("PedidoBebidasActivity", "Error cargando datos", e)
                ocultarIndicadorCarga()
                Toast.makeText(this@PedidoBebidasActivity, "Error cargando datos: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun crearInterfazProductos() {
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
        productosPedido.forEach { producto ->
            val filaProducto = crearFilaProducto(producto)
            layoutContenido.addView(filaProducto)
        }
        
        // Agregar botones al final
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
        
        val encabezadosTexto = listOf("Producto", "Stock óšlt. Ing.", "Cons. Est.", "Stock Est. Actual", "Pedido Sugerido", "Pedido Final")
        val pesos = listOf(0.3f, 0.12f, 0.12f, 0.15f, 0.15f, 0.16f)
        
        encabezadosTexto.forEachIndexed { index, texto ->
            val txtEncabezado = TextView(this).apply {
                text = texto
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setTextColor(resources.getColor(R.color.web_text_light, null))
                setPadding(2, 8, 2, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, pesos[index])
            }
            encabezados.addView(txtEncabezado)
        }
        
        return encabezados
    }
    
    private fun crearFilaProducto(producto: ProductoPedidoBebida): LinearLayout {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 12, 8, 12)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(resources.getColor(R.color.surface_color, null))
            elevation = 4f
        }
        
        // Nombre del producto (30% del ancho)
        val txtProducto = TextView(this).apply {
            text = producto.nombre
            textSize = 10f
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f)
            setPadding(4, 8, 2, 8)
        }
        fila.addView(txtProducto)
        
        // Stock óšltimo Ingreso (12% del ancho)
        val txtStockUltimo = TextView(this).apply {
            text = producto.stockUltimoIngreso.toString()
            textSize = 10f
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.12f)
            setPadding(2, 8, 2, 8)
        }
        fila.addView(txtStockUltimo)
        
        // Consumo Estimado (12% del ancho)
        val txtConsumo = TextView(this).apply {
            text = producto.consumoEstimado.toString()
            textSize = 10f
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.12f)
            setPadding(2, 8, 2, 8)
        }
        fila.addView(txtConsumo)
        
        // Stock Estimado Actual (15% del ancho)
        val txtStockEstimado = TextView(this).apply {
            text = producto.stockEstimadoActual.toString()
            textSize = 10f
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.15f)
            setPadding(2, 8, 2, 8)
        }
        fila.addView(txtStockEstimado)
        
        // Pedido Sugerido (15% del ancho)
        val txtPedidoSugerido = TextView(this).apply {
            text = producto.pedidoSugerido.toString()
            textSize = 10f
            setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.15f)
            setPadding(2, 8, 2, 8)
        }
        fila.addView(txtPedidoSugerido)
        
        // Pedido Final (16% del ancho) - Editable o Solo Lectura
        val editPedidoFinal = if (modoSoloLectura) {
            TextView(this).apply {
                text = producto.pedidoFinal.toString()
                textSize = 10f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                gravity = android.view.Gravity.CENTER
                setPadding(4, 8, 4, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.16f)
            }
        } else {
            EditText(this).apply {
                setText(producto.pedidoFinal.toString())
                textSize = 10f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                setHintTextColor(resources.getColor(android.R.color.darker_gray, null))
                gravity = android.view.Gravity.CENTER
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setPadding(4, 8, 4, 8)
                background = resources.getDrawable(android.R.drawable.edit_text, null)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.16f)
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
        btnRecalcular = Button(this).apply {
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
        btnGuardar = Button(this).apply {
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
                
                val diaSemana = repository.obtenerDiaSemana(fechaSeleccionada)
                val tipoPedido = when (diaSemana) {
                    "Lunes" -> "Pedido Lunes (Ingreso Martes)"
                    "Jueves" -> "Pedido Jueves (Ingreso Viernes)"
                    else -> "Pedido $diaSemana"
                }
                
                val pedido = PedidoBebida(
                    fecha = fechaSeleccionada,
                    diaSemana = diaSemana,
                    tipoPedido = tipoPedido,
                    local = localNombre,
                    productos = productosPedido,
                    creadoPor = usuarioId,
                    totalProductos = productosPedido.size
                )
                
                repository.guardarPedidoBebidas(pedido)
                
                Toast.makeText(this@PedidoBebidasActivity, "Pedido guardado exitosamente", Toast.LENGTH_SHORT).show()
                
                finish()
                
            } catch (e: Exception) {
                Log.e("PedidoBebidasActivity", "Error guardando pedido", e)
                Toast.makeText(this@PedidoBebidasActivity, "Error guardando pedido: ${e.message}", Toast.LENGTH_SHORT).show()
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
    
    private fun esDiaValidoParaPedido(fecha: String): Boolean {
        return repository.esDiaValidoParaPedido(fecha)
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
        AlertDialog.Builder(this)
            .setTitle("âŒ Dó­a Incorrecto")
            .setMessage("Los pedidos de bebidas solo se pueden generar los LUNES y JUEVES.\n\nSelecciona un dó­a vó¡lido para continuar.")
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
                android.util.Log.e("PedidoBebidasActivity", "No se encontró³ localNombre en SharedPreferences")
                "NO SELECCIONADO"
            } else {
                android.util.Log.d("PedidoBebidasActivity", "LocalNombre obtenido: $localNombre")
                localNombre
            }
        } catch (e: Exception) {
            android.util.Log.e("PedidoBebidasActivity", "Error obteniendo localNombre", e)
            "NO SELECCIONADO"
        }
    }
}

