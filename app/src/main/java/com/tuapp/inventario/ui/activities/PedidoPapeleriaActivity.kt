package com.tuapp.inventario

import android.os.Bundle
import android.util.Log
import android.view.Gravity
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
    private val editTextsStock = mutableMapOf<String, EditText>()
    private var modoSoloLectura = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pedido_papeleria)

        fechaSeleccionada = intent.getStringExtra("fechaSeleccionada")
            ?: DateHelper.getDateFormat("yyyy-MM-dd").format(Date())

        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        localId = prefs.getString("localId", "default") ?: "default"
        localNombre = prefs.getString("localNombre", "Local") ?: "Local"
        usuarioId = prefs.getString("usuarioId", "usuario") ?: "usuario"

        repository = PapeleriaRepository(localId)

        // Modo solo lectura desactivado - permitir editar cualquier fecha
        modoSoloLectura = false

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

        val titulo = if (modoSoloLectura) "📋 Pedido Papelería (Solo Lectura)" else "📋 Pedido Papelería"
        txtTitulo.text = titulo

        val formatoFecha = DateHelper.getDateFormat("EEEE - dd/MM/yy", Locale("es", "ES"))
        val fecha = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaSeleccionada)
        txtFecha.text = if (fecha != null) formatoFecha.format(fecha) else fechaSeleccionada

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
                mostrarIndicadorCarga()

                val stockActual = repository.obtenerStockPapeleria(fechaSeleccionada)
                val configuracion = repository.obtenerConfiguracion()
                Log.d("PedidoPapeleria", "Fecha: $fechaSeleccionada | LocalId: $localId | Stock: ${stockActual?.productos?.size ?: 0} productos")

                productosPedido = repository.calcularSugerencias(stockActual, configuracion).toMutableList()

                // Si ya existe un pedido guardado, cargarlo
                val pedidoExistente = repository.obtenerPedidoPapeleria(fechaSeleccionada)
                Log.d("PedidoPapeleria", "Pedido para $fechaSeleccionada: ${if (pedidoExistente != null) "encontrado" else "no encontrado"}")
                if (pedidoExistente != null) {
                    productosPedido = productosPedido.map { pp ->
                        val existente = pedidoExistente.productos.find { it.codigo == pp.codigo }
                        if (existente != null) pp.copy(pedidoFinal = existente.pedidoFinal) else pp
                    }.toMutableList()
                }

                // Si ya existe stock guardado, usar esos valores
                if (stockActual != null) {
                    productosPedido = productosPedido.map { pp ->
                        val stockGuardado = stockActual.productos.find { it.codigo == pp.codigo }
                        if (stockGuardado != null) pp.copy(stockActual = stockGuardado.stock) else pp
                    }.toMutableList()
                }

                crearInterfazProductos()
                ocultarIndicadorCarga()

            } catch (e: Exception) {
                Log.e("PedidoPapeleria", "Error cargando datos", e)
                ocultarIndicadorCarga()
                Toast.makeText(this@PedidoPapeleriaActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun crearInterfazProductos() {
        layoutContenido.removeAllViews()

        val isDark = isDarkMode()
        val textColor = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val headerBgColor = if (isDark) android.graphics.Color.parseColor("#3D3D3D") else resources.getColor(R.color.brand_gold_dark, null)
        val headerTextColor = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.WHITE
        val itemBgColor = if (isDark) android.graphics.Color.parseColor("#2D2D2D") else android.graphics.Color.WHITE
        val itemBorderColor = if (isDark) android.graphics.Color.parseColor("#404040") else android.graphics.Color.parseColor("#E0E0E0")
        val editTextBgColor = if (isDark) android.graphics.Color.parseColor("#1E1E1E") else android.graphics.Color.WHITE
        val editTextBorderColor = if (isDark) android.graphics.Color.parseColor("#FFD700") else resources.getColor(R.color.brand_gold, null)
        val categoryBgColor = if (isDark) android.graphics.Color.parseColor("#3D3D3D") else resources.getColor(R.color.brand_grey_light, null)
        val alertColor = android.graphics.Color.parseColor("#FF5252")
        val sugeridoColor = if (isDark) android.graphics.Color.parseColor("#4CAF50") else resources.getColor(android.R.color.holo_green_dark, null)

        // Indicador de local
        val indicadorLocal = TextView(this).apply {
            text = "LOCAL: $localNombre"
            textSize = 14f
            setTextColor(if (isDark) android.graphics.Color.parseColor("#FFD700") else resources.getColor(R.color.brand_gold_dark, null))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 12)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        layoutContenido.addView(indicadorLocal)

        // Tabla con columnas alineadas
        val tableLayout = TableLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isStretchAllColumns = false
            setColumnStretchable(0, true) // Producto se estira
            setColumnShrinkable(0, true)
        }

        // Encabezados
        val filaHeader = TableRow(this).apply {
            setPadding(8, 12, 8, 12)
            setBackgroundColor(headerBgColor)
            elevation = 4f
        }

        listOf("Producto", "Stock", "Sug.", "Pedido").forEachIndexed { index, texto ->
            val txt = TextView(this).apply {
                this.text = texto
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = if (index == 0) Gravity.START else Gravity.CENTER
                setTextColor(headerTextColor)
                setPadding(4, 8, 4, 8)
            }
            val params = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT)
            params.width = if (index == 0) 0 else TableRow.LayoutParams.WRAP_CONTENT
            params.weight = if (index == 0) 1f else 0f
            txt.layoutParams = params
            filaHeader.addView(txt)
        }
        tableLayout.addView(filaHeader)

        // Agrupar por categoria
        val productosPorCategoria = productosPedido.groupBy { it.categoria }

        productosPorCategoria.entries.forEach { (categoria, productosCategoria) ->
            // Header de categoria (fila que ocupa todas las columnas)
            val emoji = ProductoPapeleria.getCategoriaEmoji(categoria)
            val filaCat = TableRow(this).apply {
                setPadding(12, 16, 12, 8)
                setBackgroundColor(categoryBgColor)
            }
            val txtCategoria = TextView(this).apply {
                text = "$emoji $categoria"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textColor)
            }
            val catParams = TableRow.LayoutParams()
            catParams.span = 4
            txtCategoria.layoutParams = catParams
            filaCat.addView(txtCategoria)
            tableLayout.addView(filaCat)

            // Filas de productos
            productosCategoria.forEach { producto ->
                val fila = crearFilaProducto(
                    producto, textColor, itemBgColor, itemBorderColor,
                    editTextBgColor, editTextBorderColor, alertColor, sugeridoColor
                )
                tableLayout.addView(fila)
            }
        }

        layoutContenido.addView(tableLayout)

        // Enlazar focus vertical: stock -> stock siguiente, pedido -> pedido siguiente
        val codigosOrdenados = productosPedido.map { it.codigo }
        for (i in codigosOrdenados.indices) {
            val codigoActual = codigosOrdenados[i]
            val codigoSiguiente = codigosOrdenados.getOrNull(i + 1)

            editTextsStock[codigoActual]?.let { stockEdit ->
                if (codigoSiguiente != null) {
                    val nextStock = editTextsStock[codigoSiguiente]
                    if (nextStock != null) {
                        stockEdit.nextFocusDownId = nextStock.id
                    }
                }
            }

            editTextsPedido[codigoActual]?.let { pedidoEdit ->
                if (codigoSiguiente != null) {
                    val nextPedido = editTextsPedido[codigoSiguiente]
                    if (nextPedido != null) {
                        pedidoEdit.nextFocusDownId = nextPedido.id
                    }
                }
            }
        }

        // Alertas de anomalias
        val anomalias = productosPedido.filter { it.esAnomalia }
        if (anomalias.isNotEmpty()) {
            val txtAnomalia = TextView(this).apply {
                text = "🚨 ${anomalias.size} producto(s) con consumo anormal"
                textSize = 13f
                setTextColor(alertColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(16, 12, 16, 4)
            }
            layoutContenido.addView(txtAnomalia)

            anomalias.forEach { p ->
                val txtDetalle = TextView(this).apply {
                    text = "   ${p.nombre}: consumió ${p.consumoReal} vs promedio ${String.format("%.1f", p.consumoPromedio)}"
                    textSize = 11f
                    setTextColor(alertColor)
                    setPadding(16, 2, 16, 2)
                }
                layoutContenido.addView(txtDetalle)
            }
        }

        // Nota de stock bajo
        val productosConAlerta = productosPedido.filter { it.stockActual < it.pedidoSugerido }
        if (productosConAlerta.isNotEmpty()) {
            val txtAlerta = TextView(this).apply {
                text = "⚠️ ${productosConAlerta.size} producto(s) con stock bajo para la semana"
                textSize = 13f
                setTextColor(alertColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(16, 12, 16, 8)
            }
            layoutContenido.addView(txtAlerta)
        }
    }

    private fun crearFilaProducto(
        producto: ProductoPedidoPapeleria,
        textColor: Int,
        itemBgColor: Int,
        itemBorderColor: Int,
        editTextBgColor: Int,
        editTextBorderColor: Int,
        alertColor: Int,
        sugeridoColor: Int
    ): TableRow {
        val filaDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(itemBgColor)
            cornerRadius = 6f * resources.displayMetrics.density
            setStroke((1 * resources.displayMetrics.density).toInt(), itemBorderColor)
        }

        val editDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(editTextBgColor)
            cornerRadius = 8f * resources.displayMetrics.density
            setStroke((2 * resources.displayMetrics.density).toInt(), editTextBorderColor)
        }

        val fila = TableRow(this).apply {
            setPadding(8, 10, 8, 10)
            background = filaDrawable
            gravity = Gravity.CENTER_VERTICAL
        }

        // Celda 1: Nombre + unidades (se estira)
        val contenedorNombre = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 4, 4, 4)
        }
        val txtProducto = TextView(this).apply {
            text = producto.nombre
            textSize = 12f
            setTextColor(textColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 0)
        }
        contenedorNombre.addView(txtProducto)

        if (producto.unidadesPorBulto > 1) {
            val txtUnidades = TextView(this).apply {
                text = "${producto.unidadesPorBulto} unid/bulto"
                textSize = 9f
                setTextColor(if (isDarkMode()) android.graphics.Color.parseColor("#AAAAAA") else android.graphics.Color.parseColor("#666666"))
                setPadding(0, 0, 0, 0)
            }
            contenedorNombre.addView(txtUnidades)
        }

        val nombreParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT)
        nombreParams.weight = 1f
        contenedorNombre.layoutParams = nombreParams
        fila.addView(contenedorNombre)

        // Celda 2: Stock editable
        val stockParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
        stockParams.setMargins(8, 0, 8, 0)

        val editStock = if (modoSoloLectura) {
            TextView(this).apply {
                text = producto.stockActual.toString()
                textSize = 14f
                setTextColor(textColor)
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(16, 8, 16, 8)
                background = editDrawable
                layoutParams = stockParams
            }
        } else {
            EditText(this).apply {
                id = View.generateViewId()
                setText(producto.stockActual.toString())
                textSize = 14f
                setTextColor(textColor)
                setHintTextColor(if (isDarkMode()) android.graphics.Color.parseColor("#CCCCCC") else android.graphics.Color.parseColor("#808080"))
                gravity = Gravity.CENTER
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                setPadding(16, 8, 16, 8)
                background = editDrawable
                layoutParams = stockParams
                minWidth = (60 * resources.displayMetrics.density).toInt()
            }
        }
        if (editStock is EditText) {
            editTextsStock[producto.codigo] = editStock
        }
        fila.addView(editStock)

        // Celda 3: Sugerido
        val txtSugerido = TextView(this).apply {
            text = producto.pedidoSugerido.toString()
            textSize = 14f
            setTextColor(sugeridoColor)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(8, 8, 8, 8)
            val p = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
            p.setMargins(8, 0, 8, 0)
            layoutParams = p
            minWidth = (50 * resources.displayMetrics.density).toInt()
        }
        fila.addView(txtSugerido)

        // Celda 4: Pedido final editable
        val pedidoParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
        pedidoParams.setMargins(8, 0, 8, 0)

        val editPedidoFinal = if (modoSoloLectura) {
            TextView(this).apply {
                text = producto.pedidoFinal.toString()
                textSize = 14f
                setTextColor(textColor)
                gravity = Gravity.CENTER
                setPadding(16, 8, 16, 8)
                background = editDrawable
                layoutParams = pedidoParams
                minWidth = (60 * resources.displayMetrics.density).toInt()
            }
        } else {
            EditText(this).apply {
                id = View.generateViewId()
                setText(producto.pedidoFinal.toString())
                textSize = 14f
                setTextColor(textColor)
                setHintTextColor(if (isDarkMode()) android.graphics.Color.parseColor("#CCCCCC") else android.graphics.Color.parseColor("#808080"))
                gravity = Gravity.CENTER
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                setPadding(16, 8, 16, 8)
                background = editDrawable
                layoutParams = pedidoParams
                minWidth = (60 * resources.displayMetrics.density).toInt()
            }
        }

        // Marcar con alerta si stock < sugerido
        if (producto.stockActual < producto.pedidoSugerido && producto.pedidoSugerido > 0) {
            editStock.setTextColor(alertColor)
        }

        if (editPedidoFinal is EditText) {
            editTextsPedido[producto.codigo] = editPedidoFinal
        }
        fila.addView(editPedidoFinal)

        return fila
    }

    private fun guardarPedido() {
        lifecycleScope.launch {
            try {
                // Actualizar stock y pedido desde los inputs
                productosPedido = productosPedido.map { producto ->
                    val pedidoFinal = editTextsPedido[producto.codigo]?.text?.toString()?.toIntOrNull() ?: producto.pedidoFinal
                    val stockActual = editTextsStock[producto.codigo]?.text?.toString()?.toIntOrNull() ?: producto.stockActual
                    producto.copy(pedidoFinal = pedidoFinal, stockActual = stockActual)
                }.toMutableList()

                // Guardar stock
                val productosStock = ProductoPapeleria.getProductosPapeleria().map { producto ->
                    val pp = productosPedido.find { it.codigo == producto.codigo }
                    producto.copy(stock = pp?.stockActual ?: 0)
                }
                val stock = StockPapeleria(
                    fecha = fechaSeleccionada,
                    local = localNombre,
                    productos = productosStock
                )
                repository.guardarStockPapeleria(stock)

                // Guardar pedido
                val pedido = PedidoPapeleria(
                    fecha = fechaSeleccionada,
                    local = localNombre,
                    productos = productosPedido,
                    creadoPor = usuarioId,
                    totalProductos = productosPedido.size
                )
                repository.guardarPedidoPapeleria(pedido)

                // Generar ingreso automatico para el lunes (Opcion C - asumir que llego todo)
                val formato = DateHelper.getDateFormat("yyyy-MM-dd")
                val fechaPedido = formato.parse(fechaSeleccionada) ?: Date()
                val cal = DateHelper.getCalendar().apply { time = fechaPedido }
                cal.add(Calendar.DAY_OF_MONTH, 3) // Viernes -> Lunes
                val fechaIngreso = formato.format(cal.time)
                repository.generarIngresoDesdePedido(fechaSeleccionada, fechaIngreso)

                Toast.makeText(this@PedidoPapeleriaActivity, "Stock y pedido guardados", Toast.LENGTH_SHORT).show()
                finish()

            } catch (e: Exception) {
                Log.e("PedidoPapeleria", "Error guardando", e)
                Toast.makeText(this@PedidoPapeleriaActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun mostrarIndicadorCarga() {
        val progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }

        val txtCargando = TextView(this).apply {
            text = "Calculando sugerencias..."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }

        val layoutCarga = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        layoutCarga.addView(progressBar)
        layoutCarga.addView(txtCargando)
        layoutContenido.removeAllViews()
        layoutContenido.addView(layoutCarga)
    }

    private fun ocultarIndicadorCarga() {
        // Se oculta cuando se llama a crearInterfazProductos()
    }

    private fun isDarkMode(): Boolean {
        val mode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}
