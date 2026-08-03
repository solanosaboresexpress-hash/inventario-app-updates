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

class ConfirmarIngresoPapeleriaActivity : AppCompatActivity() {

    private lateinit var btnVolver: Button
    private lateinit var txtTitulo: TextView
    private lateinit var txtFecha: TextView
    private lateinit var layoutContenido: LinearLayout
    private lateinit var btnTodoOK: Button
    private lateinit var btnGuardar: Button

    private lateinit var repository: PapeleriaRepository
    private lateinit var localId: String
    private lateinit var fechaSeleccionada: String
    private lateinit var localNombre: String

    private var ingreso: IngresoPapeleria? = null
    private val editTextsRecibido = mutableMapOf<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirmar_ingreso_papeleria)

        fechaSeleccionada = intent.getStringExtra("fechaSeleccionada")
            ?: DateHelper.getDateFormat("yyyy-MM-dd").format(Date())

        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        localId = prefs.getString("localId", "default") ?: "default"
        localNombre = prefs.getString("localNombre", "Local") ?: "Local"

        repository = PapeleriaRepository(localId)

        initViews()
        setupListeners()
        cargarIngreso()
    }

    private fun initViews() {
        btnVolver = findViewById(R.id.btnVolver)
        txtTitulo = findViewById(R.id.txtTitulo)
        txtFecha = findViewById(R.id.txtFecha)
        layoutContenido = findViewById(R.id.layoutContenido)
        btnTodoOK = findViewById(R.id.btnTodoOK)
        btnGuardar = findViewById(R.id.btnGuardar)

        txtTitulo.text = "📥 Confirmar Ingreso Papelería"

        val formatoFecha = DateHelper.getDateFormat("EEEE - dd/MM/yy", Locale("es", "ES"))
        val fecha = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaSeleccionada)
        txtFecha.text = if (fecha != null) formatoFecha.format(fecha) else fechaSeleccionada
    }

    private fun setupListeners() {
        btnVolver.setOnClickListener {
            @Suppress("DEPRECATION")
            onBackPressed()
        }

        btnTodoOK.setOnClickListener {
            confirmarTodoOK()
        }

        btnGuardar.setOnClickListener {
            guardarCorrecciones()
        }
    }

    private fun cargarIngreso() {
        lifecycleScope.launch {
            try {
                ingreso = repository.obtenerIngresoPapeleria(fechaSeleccionada)

                if (ingreso == null) {
                    // No hay ingreso generado - buscar pedido del viernes anterior
                    val formato = DateHelper.getDateFormat("yyyy-MM-dd")
                    val fechaLunes = formato.parse(fechaSeleccionada) ?: Date()
                    val cal = DateHelper.getCalendar().apply { time = fechaLunes }
                    cal.add(Calendar.DAY_OF_MONTH, -3) // Lunes -> Viernes
                    val fechaViernes = formato.format(cal.time)

                    val pedido = repository.obtenerPedidoPapeleria(fechaViernes)
                    if (pedido != null) {
                        ingreso = repository.generarIngresoDesdePedido(fechaViernes, fechaSeleccionada)
                    }
                }

                if (ingreso == null) {
                    mostrarSinIngreso()
                    return@launch
                }

                crearInterfazProductos()
            } catch (e: Exception) {
                Log.e("ConfirmarIngreso", "Error", e)
                Toast.makeText(this@ConfirmarIngresoPapeleriaActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun crearInterfazProductos() {
        layoutContenido.removeAllViews()
        val ing = ingreso ?: return

        val isDark = isDarkMode()
        val textColor = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val headerBgColor = if (isDark) android.graphics.Color.parseColor("#3D3D3D") else resources.getColor(R.color.brand_gold_dark, null)
        val headerTextColor = android.graphics.Color.WHITE
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

        // Encabezados
        val encabezados = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 12, 8, 12)
            setBackgroundColor(headerBgColor)
            elevation = 4f
        }

        listOf(
            Triple("Producto", 0.40f, false),
            Triple("Pedido", 0.25f, true),
            Triple("Recibido", 0.35f, true)
        ).forEach { (texto, peso, _) ->
            val txt = TextView(this).apply {
                this.text = texto
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(headerTextColor)
                setPadding(4, 8, 4, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, peso)
            }
            encabezados.addView(txt)
        }
        layoutContenido.addView(encabezados)

        // Agrupar por categoria del catalogo
        val catalogo = ProductoPapeleria.getProductosPapeleria()
        val productosPorCategoria = ing.productos.mapNotNull { ip ->
            val cat = catalogo.find { it.codigo == ip.codigo }?.categoria ?: ""
            Triple(ip, cat, catalogo.find { it.codigo == ip.codigo }?.nombre ?: ip.nombre)
        }.groupBy { it.second }

        productosPorCategoria.entries.forEach { (categoria, items) ->
            if (categoria.isNotEmpty()) {
                val emoji = ProductoPapeleria.getCategoriaEmoji(categoria)
                val txtCategoria = TextView(this).apply {
                    text = "$emoji $categoria"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(12, 16, 12, 8)
                    setTextColor(textColor)
                    setBackgroundColor(categoryBgColor)
                }
                layoutContenido.addView(txtCategoria)
            }

            items.forEach { (ip, _, nombre) ->
                val filaDrawable = android.graphics.drawable.GradientDrawable().apply {
                    setColor(itemBgColor)
                    cornerRadius = 6f * resources.displayMetrics.density
                    setStroke((1 * resources.displayMetrics.density).toInt(), itemBorderColor)
                }

                val fila = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(8, 10, 8, 10)
                    gravity = Gravity.CENTER_VERTICAL
                    background = filaDrawable
                }

                // Nombre
                val txtProducto = TextView(this).apply {
                    text = nombre
                    textSize = 12f
                    setTextColor(textColor)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.40f)
                    setPadding(4, 8, 4, 8)
                }
                fila.addView(txtProducto)

                // Cantidad pedida
                val txtPedido = TextView(this).apply {
                    text = ip.cantidadPedida.toString()
                    textSize = 14f
                    setTextColor(textColor)
                    gravity = Gravity.CENTER
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.25f)
                    setPadding(4, 8, 4, 8)
                }
                fila.addView(txtPedido)

                // Cantidad recibida (editable)
                val editDrawable = android.graphics.drawable.GradientDrawable().apply {
                    setColor(editTextBgColor)
                    cornerRadius = 8f * resources.displayMetrics.density
                    setStroke((2 * resources.displayMetrics.density).toInt(), editTextBorderColor)
                }

                val editRecibido = EditText(this).apply {
                    setText(ip.cantidadRecibida.toString())
                    textSize = 14f
                    setTextColor(textColor)
                    gravity = Gravity.CENTER
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setPadding(8, 8, 8, 8)
                    background = editDrawable
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.35f)
                }
                editTextsRecibido[ip.codigo] = editRecibido
                fila.addView(editRecibido)

                layoutContenido.addView(fila)
            }
        }
    }

    private fun mostrarSinIngreso() {
        layoutContenido.removeAllViews()
        val txt = TextView(this).apply {
            text = "No hay pedido de papelería para esta fecha.\n\nEl pedido se genera los viernes y llega los lunes."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(32, 64, 32, 64)
        }
        layoutContenido.addView(txt)
        btnTodoOK.visibility = View.GONE
        btnGuardar.visibility = View.GONE
    }

    private fun confirmarTodoOK() {
        lifecycleScope.launch {
            try {
                val correcciones = editTextsRecibido.mapValues { it.value.text.toString().toIntOrNull() ?: 0 }
                repository.confirmarIngreso(fechaSeleccionada, correcciones)
                Toast.makeText(this@ConfirmarIngresoPapeleriaActivity, "Ingreso confirmado", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@ConfirmarIngresoPapeleriaActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun guardarCorrecciones() {
        lifecycleScope.launch {
            try {
                val correcciones = editTextsRecibido.mapValues { it.value.text.toString().toIntOrNull() ?: 0 }
                repository.confirmarIngreso(fechaSeleccionada, correcciones)
                Toast.makeText(this@ConfirmarIngresoPapeleriaActivity, "Ingreso confirmado y corregido", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@ConfirmarIngresoPapeleriaActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isDarkMode(): Boolean {
        val mode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}
