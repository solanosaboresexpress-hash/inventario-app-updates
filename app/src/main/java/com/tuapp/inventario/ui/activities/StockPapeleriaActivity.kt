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
    private var modoSoloLectura = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_papeleria)

        fechaSeleccionada = intent.getStringExtra("fechaSeleccionada")
            ?: DateHelper.getDateFormat("yyyy-MM-dd").format(Date())

        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        localId = prefs.getString("localId", "default") ?: "default"
        localNombre = prefs.getString("localNombre", "Local") ?: "Local"

        repository = PapeleriaRepository(localId)

        // Modo solo lectura si la fecha es pasada
        val hoy = DateHelper.getCalendar()
        hoy.set(Calendar.HOUR_OF_DAY, 0)
        hoy.set(Calendar.MINUTE, 0)
        hoy.set(Calendar.SECOND, 0)
        hoy.set(Calendar.MILLISECOND, 0)
        val fechaCal = DateHelper.getCalendar().apply {
            time = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaSeleccionada) ?: Date()
        }
        modoSoloLectura = fechaCal.before(hoy)

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

        val titulo = if (modoSoloLectura) "📦 Stock Papelería (Solo Lectura)" else "📦 Stock Papelería"
        txtTitulo.text = titulo

        val formatoFecha = DateHelper.getDateFormat("EEEE - dd/MM/yy", Locale("es", "ES"))
        val fecha = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaSeleccionada)
        txtFecha.text = if (fecha != null) formatoFecha.format(fecha) else fechaSeleccionada

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

        val isDark = isDarkMode()
        val textColor = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val itemBgColor = if (isDark) android.graphics.Color.parseColor("#2D2D2D") else android.graphics.Color.WHITE
        val itemBorderColor = if (isDark) android.graphics.Color.parseColor("#404040") else android.graphics.Color.parseColor("#E0E0E0")
        val editTextBgColor = if (isDark) android.graphics.Color.parseColor("#1E1E1E") else android.graphics.Color.WHITE
        val editTextBorderColor = if (isDark) android.graphics.Color.parseColor("#FFD700") else resources.getColor(R.color.brand_gold, null)
        val categoryBgColor = if (isDark) android.graphics.Color.parseColor("#3D3D3D") else resources.getColor(R.color.brand_grey_light, null)

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

        // Agrupar por categoria
        val productosPorCategoria = productos.groupBy { it.categoria }

        productosPorCategoria.entries.forEachIndexed { index, (categoria, productosCategoria) ->
            // Header de categoria
            val emoji = ProductoPapeleria.getCategoriaEmoji(categoria)
            val txtCategoria = TextView(this).apply {
                text = "$emoji $categoria"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(16, 20, 16, 10)
                setTextColor(textColor)
                setBackgroundColor(categoryBgColor)
            }
            layoutContenido.addView(txtCategoria)

            // Productos de la categoria
            productosCategoria.forEach { producto ->
                val fila = crearFilaProducto(producto, textColor, itemBgColor, itemBorderColor, editTextBgColor, editTextBorderColor)
                layoutContenido.addView(fila)
            }

            // Separador
            if (index < productosPorCategoria.size - 1) {
                val separador = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 12)
                }
                layoutContenido.addView(separador)
            }
        }
    }

    private fun crearFilaProducto(
        producto: ProductoPapeleria,
        textColor: Int,
        itemBgColor: Int,
        itemBorderColor: Int,
        editTextBgColor: Int,
        editTextBorderColor: Int
    ): LinearLayout {
        val filaDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(itemBgColor)
            cornerRadius = 6f * resources.displayMetrics.density
            setStroke((1 * resources.displayMetrics.density).toInt(), itemBorderColor)
        }

        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            background = filaDrawable
        }

        // Nombre del producto + unidad
        val txtProducto = TextView(this).apply {
            text = producto.nombre + if (producto.unidadCarga == "bultos") " (x${producto.unidadesPorBulto})" else ""
            textSize = 13f
            setTextColor(textColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(4, 4, 4, 8)
        }
        fila.addView(txtProducto)

        // Fila horizontal: Stock label + input
        val filaHorizontal = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val txtStockLabel = TextView(this).apply {
            text = "Bultos/Unid:"
            textSize = 12f
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
            setPadding(4, 4, 8, 4)
        }
        filaHorizontal.addView(txtStockLabel)

        val editDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(editTextBgColor)
            cornerRadius = 8f * resources.displayMetrics.density
            setStroke((2 * resources.displayMetrics.density).toInt(), editTextBorderColor)
        }

        val editStock = if (modoSoloLectura) {
            TextView(this).apply {
                text = "0"
                textSize = 16f
                setTextColor(textColor)
                gravity = Gravity.CENTER
                setPadding(12, 10, 12, 10)
                background = editDrawable
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
            }
        } else {
            EditText(this).apply {
                hint = "0"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                textSize = 16f
                setTextColor(textColor)
                setHintTextColor(if (isDarkMode()) android.graphics.Color.parseColor("#CCCCCC") else android.graphics.Color.parseColor("#808080"))
                gravity = Gravity.CENTER
                setPadding(12, 10, 12, 10)
                background = editDrawable
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
            }
        }
        if (editStock is EditText) {
            editTextsStock[producto.codigo] = editStock
        }
        filaHorizontal.addView(editStock)

        fila.addView(filaHorizontal)
        return fila
    }

    private fun cargarDatosExistentes() {
        lifecycleScope.launch {
            try {
                val stockExistente = repository.obtenerStockPapeleria(fechaSeleccionada)
                if (stockExistente != null) {
                    stockExistente.productos.forEach { producto ->
                        editTextsStock[producto.codigo]?.setText(producto.stock.toString())
                    }
                }
            } catch (e: Exception) {
                Log.e("StockPapeleria", "Error cargando datos", e)
            }
        }
    }

    private fun guardarStock() {
        lifecycleScope.launch {
            try {
                val productosStock = productos.map { producto ->
                    val stock = editTextsStock[producto.codigo]?.text?.toString()?.toIntOrNull() ?: 0
                    producto.copy(stock = stock)
                }

                val stock = StockPapeleria(
                    fecha = fechaSeleccionada,
                    local = localNombre,
                    productos = productosStock
                )

                repository.guardarStockPapeleria(stock)
                Toast.makeText(this@StockPapeleriaActivity, "Stock guardado", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Log.e("StockPapeleria", "Error guardando", e)
                Toast.makeText(this@StockPapeleriaActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun limpiarCampos() {
        editTextsStock.values.forEach { it.setText("") }
    }

    private fun isDarkMode(): Boolean {
        val mode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}
