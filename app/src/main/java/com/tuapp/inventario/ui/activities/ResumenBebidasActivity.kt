package com.tuapp.inventario

import android.app.DatePickerDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tuapp.inventario.model.*
import com.tuapp.inventario.repository.BebidasRepository
import kotlinx.coroutines.launch
import com.tuapp.inventario.utils.DateHelper
import java.util.*

class ResumenBebidasActivity : AppCompatActivity() {
    
    private lateinit var btnVolver: Button
    private lateinit var txtTitulo: TextView
    private lateinit var txtFecha: TextView
    private lateinit var layoutContenido: LinearLayout
    private lateinit var scrollView: ScrollView
    
    private lateinit var repository: BebidasRepository
    private lateinit var localId: String
    private lateinit var fechaSeleccionada: String
    private lateinit var localNombre: String
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_resumen_bebidas)
        
        // Obtener datos del intent
        fechaSeleccionada = intent.getStringExtra("fechaSeleccionada") 
            ?: DateHelper.getDateFormat("yyyy-MM-dd").format(Date())
        
        // Obtener datos del usuario
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        localId = prefs.getString("localId", "default") ?: "default"
        
        localNombre = prefs.getString("localNombre", "Local") ?: "Local"
        
        // Inicializar repositorio
        repository = BebidasRepository(localId)
        
        initViews()
        setupListeners()
        cargarResumen()
    }
    
    private fun initViews() {
        btnVolver = findViewById(R.id.btnVolver)
        txtTitulo = findViewById(R.id.txtTitulo)
        txtFecha = findViewById(R.id.txtFecha)
        layoutContenido = findViewById(R.id.layoutContenido)
        scrollView = findViewById(R.id.scrollView)
        
        txtTitulo.text = "ü•§ Resumen Bebidas"
        
        // El indicador de local se agregarÛ° en crearInterfazResumen() despuÛ©s de removeAllViews()
        
        // Configurar fecha
        actualizarFecha()
    }
    
    private fun setupListeners() {
        btnVolver.setOnClickListener {
            @Suppress("DEPRECATION")
            onBackPressed()
        }
        
        txtFecha.setOnClickListener {
            mostrarSelectorFecha()
        }
    }
    
    private fun actualizarFecha() {
        val formatoFecha = DateHelper.getDateFormat("EEEE - dd/MM/yy", Locale("es", "ES"))
        val fecha = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaSeleccionada)
        txtFecha.text = if (fecha != null) formatoFecha.format(fecha) else fechaSeleccionada
    }
    
    private fun mostrarSelectorFecha() {
        val calendar = DateHelper.getCalendar()
        try {
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val fecha = formato.parse(fechaSeleccionada)
            if (fecha != null) {
                calendar.time = fecha
            }
        } catch (e: Exception) {
            Log.e("ResumenBebidasActivity", "Error parseando fecha", e)
        }
        
        val datePickerDialog = DatePickerDialog(
            this,
            { _, selectedYear, selectedMonth, selectedDay ->
                val fechaSeleccionada = DateHelper.getCalendar().apply {
                    set(selectedYear, selectedMonth, selectedDay)
                }
                actualizarFechaSeleccionada(fechaSeleccionada)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        
        datePickerDialog.show()
    }
    
    private fun actualizarFechaSeleccionada(calendar: Calendar) {
        fechaSeleccionada = DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
        actualizarFecha()
        cargarResumen()
    }
    
    private fun cargarResumen() {
        lifecycleScope.launch {
            try {
                mostrarIndicadorCarga()
                
                // Cargar datos del dÛ≠a seleccionado
                val stockBebidas = repository.obtenerStockBebidas(fechaSeleccionada)
                val pedidoBebidas = repository.obtenerPedidoBebidas(fechaSeleccionada)
                
                crearInterfazResumen(stockBebidas, pedidoBebidas)
                
                ocultarIndicadorCarga()
                
            } catch (e: Exception) {
                Log.e("ResumenBebidasActivity", "Error cargando resumen", e)
                ocultarIndicadorCarga()
                Toast.makeText(this@ResumenBebidasActivity, "Error cargando resumen: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun crearInterfazResumen(stockBebidas: StockBebida?, pedidoBebidas: PedidoBebida?) {
        layoutContenido.removeAllViews()
        
        // Agregar indicador de local ARRIBA del tÛ≠tulo principal
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
        
        val diaSemana = repository.obtenerDiaSemana(fechaSeleccionada)
        
        // TÛ≠tulo del resumen
        val tituloResumen = TextView(this).apply {
            text = "üìä Resumen del $diaSemana ${fechaSeleccionada}"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(16, 16, 16, 24)
            setTextColor(resources.getColor(R.color.web_text_primary, null))
        }
        layoutContenido.addView(tituloResumen)
        
        // Mostrar informaciÛ≥n segÛ∫n el tipo de dÛ≠a
        when (diaSemana) {
            "Martes", "Viernes" -> {
                // DÛ≠as de ingreso de stock
                if (stockBebidas != null) {
                    mostrarResumenStock(stockBebidas)
                } else {
                    mostrarSinDatos("No hay datos de stock para este dÛ≠a")
                }
            }
            "Lunes", "Jueves" -> {
                // DÛ≠as de pedidos
                if (pedidoBebidas != null) {
                    mostrarResumenPedido(pedidoBebidas)
                } else {
                    mostrarSinDatos("No hay pedido registrado para este dÛ≠a")
                }
            }
            else -> {
                mostrarSinDatos("Este dÛ≠a no corresponde al ciclo de bebidas")
            }
        }
    }
    
    private fun mostrarResumenStock(stockBebidas: StockBebida) {
        // InformaciÛ≥n general
        val infoGeneral = crearCardInfo("üì¶ InformaciÛ≥n de Stock", listOf(
            "Fecha: ${stockBebidas.fecha}",
            "DÛ≠a: ${stockBebidas.diaSemana}",
            "Tipo: ${stockBebidas.tipoCarga}",
            "Local: ${stockBebidas.local}"
        ))
        layoutContenido.addView(infoGeneral)
        
        // Totales
        val totalStock = stockBebidas.productos.sumOf { it.stock }
        val totalIngreso = stockBebidas.productos.sumOf { it.ingreso }
        
        val totales = crearCardInfo("üìä Totales", listOf(
            "Stock Total: $totalStock unidades",
            "Ingreso Total: $totalIngreso unidades"
        ))
        layoutContenido.addView(totales)
        
        // Tabla de productos
        val tablaProductos = crearTablaStock(stockBebidas.productos)
        layoutContenido.addView(tablaProductos)
    }
    
    private fun mostrarResumenPedido(pedidoBebidas: PedidoBebida) {
        // InformaciÛ≥n general
        val infoGeneral = crearCardInfo("üìã InformaciÛ≥n de Pedido", listOf(
            "Fecha: ${pedidoBebidas.fecha}",
            "DÛ≠a: ${pedidoBebidas.diaSemana}",
            "Tipo: ${pedidoBebidas.tipoPedido}",
            "Local: ${pedidoBebidas.local}",
            "Creado por: ${pedidoBebidas.creadoPor}"
        ))
        layoutContenido.addView(infoGeneral)
        
        // Totales
        val totalPedido = pedidoBebidas.productos.sumOf { it.pedidoFinal }
        val totalSugerido = pedidoBebidas.productos.sumOf { it.pedidoSugerido }
        
        val totales = crearCardInfo("üìä Totales", listOf(
            "Pedido Final: $totalPedido unidades",
            "Pedido Sugerido: $totalSugerido unidades",
            "Productos: ${pedidoBebidas.totalProductos}"
        ))
        layoutContenido.addView(totales)
        
        // Tabla de productos
        val tablaProductos = crearTablaPedido(pedidoBebidas.productos)
        layoutContenido.addView(tablaProductos)
    }
    
    private fun mostrarSinDatos(mensaje: String) {
        val sinDatos = TextView(this).apply {
            text = "‚ÑπÔ∏è $mensaje"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(32, 64, 32, 64)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layoutContenido.addView(sinDatos)
    }
    
    private fun crearCardInfo(titulo: String, items: List<String>): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(resources.getColor(R.color.surface_color, null))
            elevation = 4f
        }
        
        // TÛ≠tulo de la card
        val tituloCard = TextView(this).apply {
            text = titulo
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
            setPadding(0, 0, 0, 8)
        }
        card.addView(tituloCard)
        
        // Items
        items.forEach { item ->
            val itemView = TextView(this).apply {
                text = "‚Ä¢ $item"
                textSize = 14f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                setPadding(16, 4, 0, 4)
            }
            card.addView(itemView)
        }
        
        // Margen inferior
        val margin = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 16)
        }
        card.layoutParams = margin
        
        return card
    }
    
    private fun crearTablaStock(productos: List<ProductoBebida>): LinearLayout {
        val tabla = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resources.getColor(R.color.surface_color, null))
            elevation = 4f
        }
        
        // Encabezados
        val encabezados = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark, null))
        }
        
        val encabezadosTexto = listOf("Producto", "Stock", "Ingreso")
        val pesos = listOf(0.6f, 0.2f, 0.2f)
        
        encabezadosTexto.forEachIndexed { index, texto ->
            val txtEncabezado = TextView(this).apply {
                text = texto
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setTextColor(resources.getColor(R.color.web_text_light, null))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, pesos[index])
            }
            encabezados.addView(txtEncabezado)
        }
        tabla.addView(encabezados)
        
        // Filas de productos
        productos.forEach { producto ->
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 12, 16, 12)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            
            // Nombre del producto
            val txtProducto = TextView(this).apply {
                text = producto.nombre
                textSize = 12f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
            }
            fila.addView(txtProducto)
            
            // Stock
            val txtStock = TextView(this).apply {
                text = producto.stock.toString()
                textSize = 12f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f)
            }
            fila.addView(txtStock)
            
            // Ingreso
            val txtIngreso = TextView(this).apply {
                text = producto.ingreso.toString()
                textSize = 12f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f)
            }
            fila.addView(txtIngreso)
            
            tabla.addView(fila)
        }
        
        return tabla
    }
    
    private fun crearTablaPedido(productos: List<ProductoPedidoBebida>): LinearLayout {
        val tabla = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resources.getColor(R.color.surface_color, null))
            elevation = 4f
        }
        
        // Encabezados
        val encabezados = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark, null))
        }
        
        val encabezadosTexto = listOf("Producto", "Stock Ûölt.", "Pedido Sug.", "Pedido Final")
        val pesos = listOf(0.5f, 0.15f, 0.15f, 0.2f)
        
        encabezadosTexto.forEachIndexed { index, texto ->
            val txtEncabezado = TextView(this).apply {
                text = texto
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setTextColor(resources.getColor(R.color.web_text_light, null))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, pesos[index])
            }
            encabezados.addView(txtEncabezado)
        }
        tabla.addView(encabezados)
        
        // Filas de productos
        productos.forEach { producto ->
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 12, 16, 12)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            
            // Nombre del producto
            val txtProducto = TextView(this).apply {
                text = producto.nombre
                textSize = 12f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f)
            }
            fila.addView(txtProducto)
            
            // Stock Û∫ltimo ingreso
            val txtStock = TextView(this).apply {
                text = producto.stockUltimoIngreso.toString()
                textSize = 12f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.15f)
            }
            fila.addView(txtStock)
            
            // Pedido sugerido
            val txtSugerido = TextView(this).apply {
                text = producto.pedidoSugerido.toString()
                textSize = 12f
                setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.15f)
            }
            fila.addView(txtSugerido)
            
            // Pedido final
            val txtFinal = TextView(this).apply {
                text = producto.pedidoFinal.toString()
                textSize = 12f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                gravity = android.view.Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f)
            }
            fila.addView(txtFinal)
            
            tabla.addView(fila)
        }
        
        return tabla
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
            text = "Cargando resumen..."
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
        // El indicador se oculta cuando se llama a crearInterfazResumen()
    }
    
    private fun obtenerLocalNombre(): String {
        return try {
            val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
            val localNombre = prefs.getString("localNombre", "")
            if (localNombre.isNullOrEmpty()) {
                android.util.Log.e("ResumenBebidasActivity", "No se encontrÛ≥ localNombre en SharedPreferences")
                "NO SELECCIONADO"
            } else {
                android.util.Log.d("ResumenBebidasActivity", "LocalNombre obtenido: $localNombre")
                localNombre
            }
        } catch (e: Exception) {
            android.util.Log.e("ResumenBebidasActivity", "Error obteniendo localNombre", e)
            "NO SELECCIONADO"
        }
    }
}
