package com.tuapp.inventario

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tuapp.inventario.utils.DateHelper
import com.tuapp.inventario.utils.AnimationUtils
import java.util.*
import com.tuapp.inventario.repository.InventarioRepository
import com.tuapp.inventario.repository.LocalInventarioRepository
import com.tuapp.inventario.repository.FirebaseInventarioRepository
import com.tuapp.inventario.RegistroInventario
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import com.tuapp.inventario.utils.FirebaseRegionManager
import com.google.firebase.Timestamp

class CargaActivity : AppCompatActivity() {

    private lateinit var layoutProductos: LinearLayout
    private lateinit var btnGuardar: Button
    private lateinit var txtTipoOperacion: TextView
    private lateinit var txtFechaOperacion: TextView
    private lateinit var layoutNoRecibioMercaderia: LinearLayout
    private lateinit var switchNoRecibioMercaderia: androidx.appcompat.widget.SwitchCompat
    private lateinit var tipo: String
    private lateinit var fecha: String
    private lateinit var repository: InventarioRepository
    private lateinit var localRepository: LocalInventarioRepository
    private val listaProductos = mutableListOf<Producto>()
    private var localId: String = ""
    
    // Skeleton loading
    private lateinit var offlineOverlay: LinearLayout
    private lateinit var progressSkeleton: android.widget.ProgressBar
    private lateinit var txtOfflineMsg: TextView
    private var skeletonTimeout: kotlinx.coroutines.Job? = null

    override fun onStart() {
        super.onStart()
        android.util.Log.d("DIALOG_DEBUG", "[LOG-000] onStart() llamado")
    }
    
    override fun onResume() {
        super.onResume()
        android.util.Log.d("DIALOG_DEBUG", "[LOG-000A] onResume() llamado")
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("DIALOG_DEBUG", "[LOG-001] 🎬 onCreate() INICIADO")
        android.util.Log.d("DIALOG_DEBUG", "[LOG-001A] Verificando si hay diálogos activos...")
        // Verificar si hay algún diálogo activo (no debería haber ninguno)
        val activeDialogs = try {
            // Intentar obtener diálogos activos (si es posible)
            "No hay forma directa de verificar diálogos activos en Android"
        } catch (e: Exception) {
            "Error verificando diálogos: ${e.message}"
        }
        android.util.Log.d("DIALOG_DEBUG", "[LOG-001A] $activeDialogs")
        setContentView(R.layout.activity_carga)
        android.util.Log.d("DIALOG_DEBUG", "[LOG-002] setContentView() completado")
        
        // 🔍 DEBUG: Verificar modo oscuro inmediatamente después de setContentView
        val isDark = isDarkMode()
        android.util.Log.d("DIALOG_DEBUG", "[LOG-002A] Modo oscuro detectado: $isDark")
        android.util.Log.d("DIALOG_DEBUG", "[LOG-002B] UI Mode: ${resources.configuration.uiMode}")
        android.util.Log.d("DIALOG_DEBUG", "[LOG-002C] UI_MODE_NIGHT_MASK: ${android.content.res.Configuration.UI_MODE_NIGHT_MASK}")
        android.util.Log.d("DIALOG_DEBUG", "[LOG-002D] UI_MODE_NIGHT_YES: ${android.content.res.Configuration.UI_MODE_NIGHT_YES}")

        // Inicializar repositorios
        repository = (application as InventarioApplication).repository
        localRepository = (application as InventarioApplication).localRepository
        
        // Obtener localId del usuario actual
        localId = obtenerLocalId()


        layoutProductos = findViewById(R.id.layoutProductos)
        btnGuardar = findViewById(R.id.btnGuardar)
        btnGuardar.text = "👁️ Previsualizar y Guardar"
        txtTipoOperacion = findViewById(R.id.txtTipoOperacion)
        txtFechaOperacion = findViewById(R.id.txtFechaOperacion)
        layoutNoRecibioMercaderia = findViewById(R.id.layoutNoRecibioMercaderia)
        switchNoRecibioMercaderia = findViewById(R.id.switchNoRecibioMercaderia)
        tipo = intent.getStringExtra("tipo") ?: ""
        fecha = intent.getStringExtra("fecha") ?: ""
        android.util.Log.d("DIALOG_DEBUG", "📋 Tipo de operación recibido: '$tipo'")
        android.util.Log.d("DIALOG_DEBUG", "📅 Fecha recibida: '$fecha'")
        
        // Inicializar skeleton loading
        offlineOverlay = findViewById(R.id.offlineOverlay)
        progressSkeleton = findViewById(R.id.progressSkeleton)
        txtOfflineMsg = findViewById(R.id.txtOfflineMsg)
        
        // Configurar información de fecha y tipo
        configurarInformacionOperacion()

        // Mostrar switch solo para "Ingreso de Mercadería"
        if (tipo == "Ingreso de Mercadería") {
            layoutNoRecibioMercaderia.visibility = View.VISIBLE
        }

        // Cargar productos desde la base de datos
        android.util.Log.d("DIALOG_DEBUG", "[LOG-003] Llamando a cargarProductos()")
        cargarProductos()

        btnGuardar.setOnClickListener {
            android.util.Log.d("DIALOG_DEBUG", "[LOG-009] 🔘 BOTÓN GUARDAR PRESIONADO")
            mostrarPrevisualizacion()
        }
    }

    private fun cargarProductos() {
        android.util.Log.d("DIALOG_DEBUG", "[LOG-004] cargarProductos() INICIADO")
        lifecycleScope.launch {
            try {
                Log.d("CargaActivity", "Iniciando carga de productos...")
                android.util.Log.d("DIALOG_DEBUG", "[LOG-005] Mostrando skeleton loading")
                showSkeletonLoading("Cargando datos de Firebase...")
                
                // Forzar sincronización con Firebase antes de cargar productos
                forzarSincronizacionFirebase()
                
                // Usar directamente la base de datos local como fuente principal
                cargarProductosDesdeLocal()
                
            } catch (e: Exception) {
                Log.e("CargaActivity", "Error cargando productos", e)
                // Intentar con el repositorio local como fallback
                cargarProductosDesdeLocal()
                // Ocultar skeleton loading en caso de error
                hideSkeletonLoading()
            }
        }
    }
    
    private fun cargarProductosDesdeLocal() {
        lifecycleScope.launch {
            try {
                Log.d("CargaActivity", "Cargando productos específicos para local: $localId")
                
                // Cargar productos locales primero (más rápido) - usar first() para cargar siempre
                val productosLocales = localRepository.getAllProductos(localId).first()
                
                if (isFinishing || isDestroyed) return@launch
                
                Log.d("CargaActivity", "Productos locales encontrados: ${productosLocales.size}")
                
                if (productosLocales.isNotEmpty()) {
                    // Usar productos locales si existen
                    listaProductos.clear()
                    listaProductos.addAll(productosLocales)
                    Log.d("CargaActivity", "Usando productos locales para local: $localId")
                    // 🔥 Asegurar que productos esenciales siempre estén presentes
                    asegurarProductosEsenciales()
                    cargarDatosExistentes()
                } else {
                    // Si no hay productos locales, intentar Firebase
                    cargarProductosDesdeFirebase()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelación esperada cuando la actividad se destruye
                Log.d("CargaActivity", "Operación cancelada (actividad cerrada)")
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    Log.e("CargaActivity", "Error cargando productos locales", e)
                    cargarProductosDesdeFirebase()
                }
            }
        }
    }
    
    private fun cargarProductosDesdeFirebase() {
        lifecycleScope.launch {
            try {
                Log.d("CargaActivity", "Cargando productos desde Firebase para local: $localId")
                
                val firebaseRepo = FirebaseInventarioRepository(localId)
                val productos = firebaseRepo.getAllProductos().first()
                
                if (isFinishing || isDestroyed) return@launch
                
                Log.d("CargaActivity", "Productos recibidos de Firebase: ${productos.size}")
                
                if (productos.isEmpty()) {
                    Log.w("CargaActivity", "No hay productos en Firebase, inicializando...")
                    forzarInicializacionProductos()
                } else if (productos.size < 20) {
                    Log.w("CargaActivity", "Productos incompletos en Firebase (${productos.size}/20), inicializando...")
                    forzarInicializacionProductos()
                } else {
                    // Verificar si CRIOLLITO está presente
                    val criollitoPresente = productos.any { it.nombre.contains("CRIOLLITO") }
                    if (!criollitoPresente) {
                        Log.w("CargaActivity", "CRIOLLITO no encontrado en Firebase, inicializando...")
                        forzarInicializacionProductos()
                    } else {
                        listaProductos.clear()
                        listaProductos.addAll(productos)
                        Log.d("CargaActivity", "Usando productos de Firebase para local: $localId")
                        // 🔥 Asegurar que productos esenciales siempre estén presentes
                        asegurarProductosEsenciales()
                        cargarDatosExistentes()
                    }
                }
            } catch (e: Exception) {
                Log.e("CargaActivity", "Error cargando productos desde Firebase", e)
                forzarInicializacionProductos()
            }
        }
    }
    
    private fun forzarInicializacionProductos() {
        lifecycleScope.launch {
            try {
                Log.d("CargaActivity", "Forzando inicialización de productos para localId: $localId")
                
                // Primero limpiar productos existentes para este local
                localRepository.deleteAllProductos(localId)
                Log.d("CargaActivity", "Productos existentes eliminados para local: $localId")
                
                val productos = listOf(
                    // EMPANADAS
                    Producto("JQ - Jamón y Queso", "Empanadas"),
                    Producto("PB - Pollo BBQ", "Empanadas"),
                    Producto("CS - Carne Suave", "Empanadas"),
                    Producto("PO - Pollo", "Empanadas"),
                    Producto("CP - Carne Picante", "Empanadas"),
                    Producto("HU - Humita", "Empanadas"),
                    Producto("ES - Espinaca", "Empanadas"),
                    Producto("CQ - Calabaza y Queso", "Empanadas"),
                    Producto("RJ - Roquefort y Jamón", "Empanadas"),
                    Producto("QC - Queso y Cebolla", "Empanadas"),
                    Producto("CB - Cerdo y Barbacoa", "Empanadas"),
                    Producto("CH - Cheeseburger", "Empanadas"),
                    
                    // PIZZAS
                    Producto("MUZZARE - Muzzarella", "Pizzas"),
                    Producto("JAMON - Jamón", "Pizzas"),
                    Producto("PEPPERO - Pepperoni", "Pizzas"),
                    
                    // PASTELITOS
                    Producto("BATATA - Batata", "Pastelitos"),
                    Producto("MEMBRIL - Membrillo", "Pastelitos"),
                    
                    // OTROS
                    Producto("DULCES - Dulces", "Otros"),
                    Producto("SALADO - Salado", "Otros"),
                    Producto("CHIPA - Chipa", "Otros"),
                    Producto("CRIOLLITO - Criollito", "Otros")
                )
                
                localRepository.insertProductos(localId, productos)
                Log.d("CargaActivity", "Productos insertados forzadamente: ${productos.size} para local: $localId")
                
                // Log específico para CRIOLLITO
                val criollitoProducto = productos.find { it.nombre.contains("CRIOLLITO") }
                Log.d("CargaActivity", "CRIOLLITO en lista de inicialización: ${criollitoProducto != null}")
                if (criollitoProducto != null) {
                    Log.d("CargaActivity", "CRIOLLITO: ${criollitoProducto.nombre}, Categoría: ${criollitoProducto.categoria}")
                }
                
                // Recargar productos directamente
                listaProductos.clear()
                listaProductos.addAll(productos)
                Log.d("CargaActivity", "Mostrando productos inicializados: ${productos.map { it.nombre }}")
                
                // 🔥 Asegurar que productos esenciales siempre estén presentes
                asegurarProductosEsenciales()
                
                // Verificar CRIOLLITO en listaProductos
                val criollitoEnLista = listaProductos.find { it.nombre.contains("CRIOLLITO") }
                Log.d("CargaActivity", "CRIOLLITO en listaProductos: ${criollitoEnLista != null}")
                if (criollitoEnLista != null) {
                    Log.d("CargaActivity", "CRIOLLITO en listaProductos: ${criollitoEnLista.nombre}")
                }
                
                // Cargar datos existentes para esta fecha y tipo
                cargarDatosExistentes()
                
            } catch (e: Exception) {
                Log.e("CargaActivity", "Error forzando inicialización", e)
                mostrarMensajeError("Error inicializando productos: ${e.message}")
            }
        }
    }
    
    /**
     * Asegura que los productos esenciales (CRIOLLITO y SALADO) siempre estén en listaProductos
     * Estos productos deben aparecer SIEMPRE, incluso si no están en la base de datos
     */
    private fun asegurarProductosEsenciales() {
        val productosEsenciales = listOf(
            Producto("SALADO - Salado", "Otros"),
            Producto("CRIOLLITO - Criollito", "Otros")
        )
        
        val productosFaltantes = productosEsenciales.filter { productoEsencial ->
            !listaProductos.any { it.nombre == productoEsencial.nombre }
        }
        
        if (productosFaltantes.isNotEmpty()) {
            Log.w("CargaActivity", "⚠️ Agregando productos esenciales faltantes: ${productosFaltantes.map { it.nombre }}")
            listaProductos.addAll(productosFaltantes)
            
            // Guardar productos esenciales en la base de datos local y Firebase
            lifecycleScope.launch {
                try {
                    localRepository.insertProductos(localId, productosFaltantes)
                    Log.d("CargaActivity", "✅ Productos esenciales guardados en base local")
                    
                    // También guardar en Firebase
                    val firebaseRepo = FirebaseInventarioRepository(localId)
                    firebaseRepo.insertProductos(productosFaltantes)
                    Log.d("CargaActivity", "✅ Productos esenciales guardados en Firebase")
                } catch (e: Exception) {
                    Log.e("CargaActivity", "❌ Error guardando productos esenciales: ${e.message}")
                }
            }
        }
    }
    
    private fun cargarDatosExistentes() {
        android.util.Log.d("DIALOG_DEBUG", "[LOG-006] cargarDatosExistentes() INICIADO")
        lifecycleScope.launch {
            try {
                Log.d("CargaActivity", "🔍 Buscando datos existentes para fecha: $fecha, tipo: $tipo, localId: $localId")
                android.util.Log.d("DIALOG_DEBUG", "[LOG-007] Buscando datos en Firebase...")
                
                // Leer directamente de Firebase para obtener el valor más actualizado - usar first() para cargar siempre
                val usuarioActual = (application as InventarioApplication).obtenerUsuarioActual()
                if (usuarioActual != null) {
                    val firebaseRepo = FirebaseInventarioRepository(usuarioActual.localId)
                    val registrosFirebase = firebaseRepo.getRegistrosByFecha(fecha).first()
                    
                    if (isFinishing || isDestroyed) return@launch
                    
                    Log.d("CargaActivity", "📊 Registros encontrados en Firebase para $fecha: ${registrosFirebase.size}")
                    registrosFirebase.forEach { registro ->
                        Log.d("CargaActivity", "  - Tipo: ${registro.tipo}, Productos: ${registro.productos.size}, noRecibioMercaderia: ${registro.noRecibioMercaderia}")
                    }
                    
                    val registroExistente = registrosFirebase.find { it.tipo == tipo }
                    if (registroExistente != null) {
                        Log.d("CargaActivity", "✅ Datos existentes encontrados en Firebase: ${registroExistente.productos.size} productos, noRecibioMercaderia=${registroExistente.noRecibioMercaderia}")
                        Log.d("CargaActivity", "🔍 Tipo de registro: ${registroExistente.tipo}")
                        Log.d("CargaActivity", "🔍 Fecha del registro: ${registroExistente.fecha}")
                        Log.d("CargaActivity", "🔍 Valor noRecibioMercaderia desde Firebase: ${registroExistente.noRecibioMercaderia}")
                        
                        // 🔥 FIX: Asegurar que todos los productos del registro estén en listaProductos
                        // Si hay productos en el registro que no están en listaProductos, agregarlos
                        val productosFaltantes = registroExistente.productos.filter { productoRegistro ->
                            !listaProductos.any { it.nombre == productoRegistro.nombre }
                        }
                        
                        if (productosFaltantes.isNotEmpty()) {
                            Log.w("CargaActivity", "⚠️ Encontrados ${productosFaltantes.size} productos en el registro que no están en listaProductos")
                            productosFaltantes.forEach { producto ->
                                Log.w("CargaActivity", "   - Agregando producto faltante: ${producto.nombre}")
                            }
                            
                            // Agregar productos faltantes a listaProductos
                            listaProductos.addAll(productosFaltantes)
                            
                            // Guardar productos faltantes en la base de datos local y Firebase
                            lifecycleScope.launch {
                                try {
                                    localRepository.insertProductos(localId, productosFaltantes)
                                    Log.d("CargaActivity", "✅ Productos faltantes guardados en base local")
                                    
                                    // También guardar en Firebase
                                    val firebaseRepo = FirebaseInventarioRepository(localId)
                                    firebaseRepo.insertProductos(productosFaltantes)
                                    Log.d("CargaActivity", "✅ Productos faltantes guardados en Firebase")
                                } catch (e: Exception) {
                                    Log.e("CargaActivity", "❌ Error guardando productos faltantes: ${e.message}")
                                }
                                
                                // 🔥 FIX: Volver a mostrar productos y cargar datos después de agregar los faltantes
                                runOnUiThread {
                                    mostrarProductos()
                                    cargarDatosEnInterfaz(registroExistente.productos, registroExistente.noRecibioMercaderia)
                                }
                            }
                        } else {
                            // No hay productos faltantes, cargar datos normalmente
                            cargarDatosEnInterfaz(registroExistente.productos, registroExistente.noRecibioMercaderia)
                        }
                    } else {
                        Log.d("CargaActivity", "❌ No se encontraron datos existentes en Firebase para $tipo en $fecha")
                        // No hay datos existentes, mostrar productos vacíos
                        mostrarProductos()

                        // 🔧 AUTOCOMPLETAR con pedido de fábrica del día anterior si es Ingreso de Mercadería
                        if (tipo == "Ingreso de Mercadería") {
                            autocompletarConPedidoFabricaAnterior()
                        }
                    }
                    
                    // Ocultar skeleton loading después de cargar datos
                    hideSkeletonLoading()
                } else {
                    Log.d("CargaActivity", "⚠️ No hay usuario logueado, usando datos locales")
                    // Fallback a datos locales si no hay usuario - usar first() para cargar siempre
                    val registros = localRepository.getRegistrosByFecha(localId, fecha).first()
                    
                    if (isFinishing || isDestroyed) return@launch
                    
                    val registroExistente = registros.find { it.tipo == tipo }
                    if (registroExistente != null) {
                        // 🔥 FIX: Asegurar que todos los productos del registro estén en listaProductos
                        val productosFaltantes = registroExistente.productos.filter { productoRegistro ->
                            !listaProductos.any { it.nombre == productoRegistro.nombre }
                        }
                        
                        if (productosFaltantes.isNotEmpty()) {
                            Log.w("CargaActivity", "⚠️ Encontrados ${productosFaltantes.size} productos en el registro local que no están en listaProductos")
                            productosFaltantes.forEach { producto ->
                                Log.w("CargaActivity", "   - Agregando producto faltante: ${producto.nombre}")
                            }
                            
                            // Agregar productos faltantes a listaProductos
                            listaProductos.addAll(productosFaltantes)
                            
                            // Guardar productos faltantes en la base de datos local
                            lifecycleScope.launch {
                                try {
                                    localRepository.insertProductos(localId, productosFaltantes)
                                    Log.d("CargaActivity", "✅ Productos faltantes guardados en base local")
                                } catch (e: Exception) {
                                    Log.e("CargaActivity", "❌ Error guardando productos faltantes: ${e.message}")
                                }
                                
                                // 🔥 FIX: Volver a mostrar productos y cargar datos después de agregar los faltantes
                                runOnUiThread {
                                    mostrarProductos()
                                    cargarDatosEnInterfaz(registroExistente.productos, registroExistente.noRecibioMercaderia)
                                }
                            }
                        } else {
                            // No hay productos faltantes, cargar datos normalmente
                            cargarDatosEnInterfaz(registroExistente.productos, registroExistente.noRecibioMercaderia)
                        }
                    } else {
                        mostrarProductos()

                        // 🔧 AUTOCOMPLETAR con pedido de fábrica del día anterior si es Ingreso de Mercadería
                        if (tipo == "Ingreso de Mercadería") {
                            autocompletarConPedidoFabricaAnterior()
                        }
                    }
                    hideSkeletonLoading()
                }
            } catch (e: Exception) {
                Log.e("CargaActivity", "Error cargando datos existentes", e)
                // Mostrar productos vacíos en caso de error
                mostrarProductos()
                // Ocultar skeleton loading
                hideSkeletonLoading()
            }
        }
    }
    
    private fun cargarDatosEnInterfaz(productosExistentes: List<Producto>, noRecibioMercaderia: Boolean = false) {
        Log.d("CargaActivity", "🔧 cargarDatosEnInterfaz - tipo: $tipo, noRecibioMercaderia: $noRecibioMercaderia")
        mostrarProductos()

        // Configurar el switch si es "Ingreso de Mercadería"
        if (tipo == "Ingreso de Mercadería") {
            Log.d("CargaActivity", "🔧 Configurando switch para Ingreso de Mercadería: $noRecibioMercaderia")
            switchNoRecibioMercaderia.isChecked = noRecibioMercaderia
            Log.d("CargaActivity", "🔧 Switch configurado: noRecibioMercaderia=$noRecibioMercaderia, estado actual: ${switchNoRecibioMercaderia.isChecked}")
        } else {
            Log.d("CargaActivity", "🔧 No es Ingreso de Mercadería, no configurando switch. Tipo: $tipo")
        }

        // Usar post para asegurar que la UI esté completamente renderizada antes de llenar los campos
        layoutProductos.post {
            Log.d("CargaActivity", "🔧 Llenando campos después de que la UI esté renderizada")
            Log.d("CargaActivity", "🔧 Productos existentes: ${productosExistentes.map { "${it.nombre}=${it.cantidad}" }}")
            // Llenar los campos con los datos existentes
            for (i in 0 until layoutProductos.childCount) {
                val categoriaLayout = layoutProductos.getChildAt(i) as LinearLayout
                if (categoriaLayout.childCount > 1) {
                    val productosLayout = categoriaLayout.getChildAt(1) as LinearLayout

                    for (j in 0 until productosLayout.childCount) {
                        val fila = productosLayout.getChildAt(j)
                        val edtCantidad = fila.findViewById<EditText?>(R.id.edtCantidad)
                        if (edtCantidad != null) {
                            val producto = edtCantidad.tag as Producto
                            // Extraer el código del nombre del producto de la UI (formato "CÓDIGO - Nombre")
                            val codigoUI = if (producto.nombre.contains(" - ")) {
                                producto.nombre.split(" - ")[0].trim()
                            } else {
                                producto.nombre
                            }
                            // Buscar por código extraído - también extraer el código de los productos existentes
                            val productoExistente = productosExistentes.find { prod ->
                                val codigoExistente = if (prod.nombre.contains(" - ")) {
                                    prod.nombre.split(" - ")[0].trim()
                                } else {
                                    prod.nombre
                                }
                                codigoExistente == codigoUI
                            }
                            if (productoExistente != null) {
                                Log.d("CargaActivity", "🔧 Llenando campo: codigoUI=$codigoUI, cantidad=${productoExistente.cantidad}")
                                edtCantidad.setText(productoExistente.cantidad.toString())
                            } else {
                                Log.d("CargaActivity", "⚠️ No se encontró producto para codigoUI=$codigoUI")
                            }
                        }
                    }
                }
            }
            Log.d("CargaActivity", "🔧 Campos llenados completados")
        }
    }

    /**
     * Autocompleta los campos de ingreso con el pedido de fábrica del día anterior
     */
    private fun autocompletarConPedidoFabricaAnterior() {
        lifecycleScope.launch {
            try {
                Log.d("CargaActivity", "🔍 Intentando autocompletar con pedido de fábrica del día anterior...")

                // Calcular fecha anterior
                val fechaAnterior = obtenerFechaAnterior(fecha)
                Log.d("CargaActivity", "📅 Fecha anterior: $fechaAnterior")

                // Buscar pedido de fábrica del día anterior en Firebase
                val firestore = FirebaseRegionManager.getFirestore()
                val doc = firestore
                    .collection("locales")
                    .document(localId)
                    .collection("pedidos_fabrica")
                    .document(fechaAnterior)
                    .get()
                    .await()

                if (doc.exists()) {
                    val pedidoData = doc.data
                    Log.d("CargaActivity", "📦 Pedido de fábrica encontrado: ${pedidoData?.keys}")

                    // Extraer productos del pedido final
                    val productosAutocompletar = mutableListOf<Producto>()
                    if (pedidoData != null) {
                        @Suppress("UNCHECKED_CAST")
                        val productosData = pedidoData["productos"] as? List<Map<String, Any>> ?: emptyList()

                        productosData.forEach { productoMap ->
                            val codigo = productoMap["codigo"] as? String ?: ""
                            val nombreMap = productoMap["nombre"] as? String ?: ""
                            val pedidoFinal = (productoMap["pedido"] as? Number)?.toInt() ?: 0

                            Log.d("CargaActivity", "  🔍 Procesando: codigo='$codigo', nombre='$nombreMap', pedido=$pedidoFinal")

                            // Buscar el producto correspondiente en listaProductos usando el código o nombre
                            val prodExistente = listaProductos.find { p ->
                                val codP = if (p.nombre.contains(" - ")) p.nombre.split(" - ")[0].trim() else p.nombre
                                codP == codigo || p.nombre.startsWith("$codigo -") || p.nombre == nombreMap
                            }

                            val nombreCompleto = prodExistente?.nombre ?: if (nombreMap.isNotEmpty()) nombreMap else codigo
                            val categoria = prodExistente?.categoria ?: ""

                            productosAutocompletar.add(Producto(nombreCompleto, categoria, pedidoFinal))
                        }
                    }

                    Log.d("CargaActivity", "📦 Productos autocompletados: ${productosAutocompletar.size}")
                    productosAutocompletar.forEach { producto ->
                        Log.d("CargaActivity", "  - ${producto.nombre}: ${producto.cantidad}")
                    }

                    // Cargar datos en la interfaz
                    runOnUiThread {
                        mostrarProductos()
                        cargarDatosEnInterfaz(productosAutocompletar, false)
                        Log.d("CargaActivity", "✅ Datos autocompletados desde pedido de fábrica")
                    }
                } else {
                    Log.d("CargaActivity", "📦 No hay pedido de fábrica del día anterior")
                }
            } catch (e: Exception) {
                Log.e("CargaActivity", "❌ Error autocompletando con pedido de fábrica: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun mostrarProductos() {
        android.util.Log.d("DIALOG_DEBUG", "[LOG-008] mostrarProductos() INICIADO - Total: ${listaProductos.size}")
        Log.d("CargaActivity", "Mostrando productos. Total: ${listaProductos.size}")
        
        // 🔥 Asegurar que productos esenciales siempre estén presentes antes de mostrar
        asegurarProductosEsenciales()
        
        layoutProductos.removeAllViews()
        
        // Orden específico de categorías
        val ordenCategorias = listOf("Empanadas", "Pizzas", "Pastelitos", "Otros")
        
        // Orden específico de productos dentro de cada categoría
        val ordenProductos = mapOf(
            "Empanadas" to listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH"),
            "Pizzas" to listOf("MUZZARE", "JAMON", "PEPPERO"),
            "Pastelitos" to listOf("BATATA", "MEMBRIL"),
            "Otros" to listOf("DULCES", "SALADO", "CHIPA", "CRIOLLITO")
        )
        
        // Mostrar cada categoría en el orden específico
        for (categoria in ordenCategorias) {
            val productosCategoria = listaProductos.filter { it.categoria == categoria }
            Log.d("CargaActivity", "Categoría $categoria: ${productosCategoria.size} productos")
            
            if (productosCategoria.isNotEmpty()) {
                val productosOrdenados = ordenarProductosPorCategoria(productosCategoria, ordenProductos[categoria] ?: emptyList())
                mostrarCategoria(categoria, productosOrdenados)
            }
        }
        
        // Si no hay productos en ninguna categoría, mostrar mensaje
        if (listaProductos.isEmpty()) {
            Log.w("CargaActivity", "No hay productos para mostrar")
            mostrarMensajeSinProductos()
        }
    }
    
    private fun ordenarProductosPorCategoria(productos: List<Producto>, ordenEspecifico: List<String>): List<Producto> {
        val productosOrdenados = mutableListOf<Producto>()
        
        // Agregar productos en el orden específico
        for (codigo in ordenEspecifico) {
            val producto = productos.find { it.nombre.startsWith(codigo) }
            if (producto != null) {
                productosOrdenados.add(producto)
            }
        }
        
        // Agregar cualquier producto que no esté en el orden específico al final
        for (producto in productos) {
            if (!productosOrdenados.contains(producto)) {
                productosOrdenados.add(producto)
            }
        }
        
        return productosOrdenados
    }
    
    private fun mostrarCategoria(categoria: String, productos: List<Producto>) {
        Log.d("CargaActivity", "Mostrando categoría: $categoria con ${productos.size} productos")
        
        // Log específico para CRIOLLITO en categoría Otros
        if (categoria == "Otros") {
            val criollitoEnOtros = productos.find { it.nombre.contains("CRIOLLITO") }
            Log.d("CargaActivity", "CRIOLLITO en categoría Otros: ${criollitoEnOtros != null}")
            if (criollitoEnOtros != null) {
                Log.d("CargaActivity", "CRIOLLITO encontrado en Otros: ${criollitoEnOtros.nombre}")
            }
            Log.d("CargaActivity", "Productos en Otros: ${productos.map { it.nombre }}")
        }
        
        // Crear contenedor de categoría
        val categoriaLayout = LinearLayout(this)
        categoriaLayout.orientation = LinearLayout.VERTICAL
        categoriaLayout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        categoriaLayout.setPadding(0, 16, 0, 8)
        
        // Header de categoría con color e icono
        val headerLayout = LinearLayout(this)
        headerLayout.orientation = LinearLayout.HORIZONTAL
        headerLayout.setPadding(16, 16, 16, 16)
        headerLayout.gravity = android.view.Gravity.CENTER_VERTICAL
        
        // Aplicar color según categoría (como en la web)
        val headerBackground = when (categoria) {
            "Empanadas" -> R.drawable.category_empanadas_header
            "Pizzas" -> R.drawable.category_pizzas_header
            "Pastelitos" -> R.drawable.category_pastelitos_header
            "Otros" -> R.drawable.category_otros_header
            else -> R.drawable.category_header_background
        }
        headerLayout.setBackgroundResource(headerBackground)
        
        // Icono de categoría (como en la web)
        val iconoCategoria = TextView(this)
        iconoCategoria.text = when (categoria) {
            "Empanadas" -> "🥟"
            "Pizzas" -> "🍕"
            "Pastelitos" -> "🥧"
            "Otros" -> "🥐"
            else -> "📦"
        }
        iconoCategoria.textSize = 24f
        iconoCategoria.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = 12
        }
        
        val txtCategoria = TextView(this)
        txtCategoria.text = categoria.uppercase()
        txtCategoria.textSize = 18f
        txtCategoria.setTextColor(resources.getColor(R.color.web_text_light, null))
        txtCategoria.typeface = android.graphics.Typeface.DEFAULT_BOLD
        txtCategoria.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        
        val txtCantidad = TextView(this)
        txtCantidad.text = "${productos.size} productos"
        txtCantidad.textSize = 12f
        txtCantidad.setTextColor(resources.getColor(R.color.web_text_light, null))
        txtCantidad.setBackgroundResource(R.drawable.category_count_background)
        txtCantidad.setPadding(8, 4, 8, 4)
        
        headerLayout.addView(iconoCategoria)
        headerLayout.addView(txtCategoria)
        headerLayout.addView(txtCantidad)
        
        // Contenedor de productos
        val productosLayout = LinearLayout(this)
        productosLayout.orientation = LinearLayout.VERTICAL
        // Aplicar fondo dinámico según modo oscuro
        val isDark = isDarkMode()
        val contentBackgroundColor = if (isDark) {
            android.graphics.Color.parseColor("#1E1E1E") // Gris oscuro
        } else {
            android.graphics.Color.parseColor("#FFF5F5") // Blanco rosado (original)
        }
        productosLayout.setBackgroundColor(contentBackgroundColor)
        productosLayout.setPadding(8, 8, 8, 8)
        
        // Crear TextView para el total de la categoría (se actualizará dinámicamente)
        val txtTotalCategoria = TextView(this)
        txtTotalCategoria.text = "Total: 0 uds"
        txtTotalCategoria.textSize = 16f
        txtTotalCategoria.typeface = android.graphics.Typeface.DEFAULT_BOLD
        txtTotalCategoria.setPadding(16, 12, 16, 12)
        txtTotalCategoria.gravity = android.view.Gravity.END
        
        // Aplicar colores según modo oscuro (usar isDark ya declarado arriba)
        val totalTextColor = if (isDark) {
            android.graphics.Color.parseColor("#FFD700") // Dorado en modo oscuro
        } else {
            resources.getColor(R.color.brand_gold_dark, null)
        }
        val totalBackgroundColor = if (isDark) {
            android.graphics.Color.parseColor("#2D2D2D")
        } else {
            android.graphics.Color.parseColor("#FFF9E6") // Amarillo muy claro
        }
        txtTotalCategoria.setTextColor(totalTextColor)
        txtTotalCategoria.setBackgroundColor(totalBackgroundColor)
        
        // Agregar productos
        for (producto in productos) {
            // Usar layout especial para "Cargar Stock 15:30"
            val layoutRes = if (tipo == "Cargar Stock 15:30") {
                R.layout.item_producto_stock_1530
            } else {
                R.layout.item_producto
            }
            
            val fila = layoutInflater.inflate(layoutRes, null)
            val txtCodigo = fila.findViewById<TextView>(R.id.txtCodigo)
            val txtNombre = fila.findViewById<TextView>(R.id.txtNombre)
            val edtCantidad = fila.findViewById<EditText>(R.id.edtCantidad)
            
            // Aplicar colores dinámicos a los elementos del item
            val itemBackgroundColor = if (isDark) {
                android.graphics.Color.parseColor("#2D2D2D") // Gris más oscuro para items
            } else {
                android.graphics.Color.WHITE
            }
            val itemBorderColor = if (isDark) {
                android.graphics.Color.parseColor("#404040") // Borde gris en modo oscuro
            } else {
                android.graphics.Color.parseColor("#E0E0E0") // Borde gris claro en modo claro
            }
            
            // Aplicar fondo al item (fila)
            fila.setBackgroundColor(itemBackgroundColor)
            // Crear un drawable con borde para el item
            val itemDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(itemBackgroundColor)
                cornerRadius = 6f * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt(), itemBorderColor)
            }
            fila.background = itemDrawable
            
            // Aplicar fondo al EditText
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
            val editTextDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(editTextBackgroundColor)
                cornerRadius = 8f * resources.displayMetrics.density
                setStroke((2 * resources.displayMetrics.density).toInt(), editTextBorderColor)
            }
            edtCantidad.background = editTextDrawable
            
            // Asegurar que los textos sean visibles - usar colores explícitos según modo oscuro
            val textColor = if (isDark) {
                android.graphics.Color.WHITE // Texto blanco en modo oscuro
            } else {
                android.graphics.Color.BLACK // Texto negro en modo claro
            }
            val hintColor = if (isDark) {
                android.graphics.Color.parseColor("#CCCCCC") // Hint gris claro en modo oscuro
            } else {
                android.graphics.Color.parseColor("#808080") // Hint gris en modo claro
            }
            txtCodigo.setTextColor(textColor)
            txtNombre.setTextColor(textColor)
            edtCantidad.setTextColor(textColor)
            edtCantidad.setHintTextColor(hintColor)
            
            // Separar código y nombre del producto
            val partes = producto.nombre.split(" - ", limit = 2)
            txtCodigo.text = partes[0]
            txtNombre.text = if (partes.size > 1) partes[1] else producto.nombre
            edtCantidad.tag = producto
            
            // Configurar funcionalidad especial para "Cargar Stock 15:30"
            if (tipo == "Cargar Stock 15:30") {
                configurarSugerencias(fila, partes[0])
            }
            
            // Animar la entrada de la nueva fila
            AnimationUtils.animateViewIn(fila)
            productosLayout.addView(fila)
        }
        
        // Función para actualizar el total de la categoría
        fun actualizarTotalCategoria() {
            var total = 0
            // Iterar sobre todos los hijos EXCEPTO el último (que es el txtTotalCategoria)
            val cantidadFilas = productosLayout.childCount - 1
            for (i in 0 until cantidadFilas) {
                val fila = productosLayout.getChildAt(i)
                val edtCantidad = fila?.findViewById<EditText>(R.id.edtCantidad)
                if (edtCantidad != null) {
                    val cantidad = edtCantidad.text.toString().toIntOrNull() ?: 0
                    total += cantidad
                }
            }
            txtTotalCategoria.text = "Total: $total uds"
        }
        
        // Agregar el TextView del total después de los productos
        productosLayout.addView(txtTotalCategoria)
        
        // Agregar listeners DESPUÉS de agregar todos los productos
        for (i in 0 until productos.size) {
            val fila = productosLayout.getChildAt(i)
            val edtCantidad = fila?.findViewById<EditText>(R.id.edtCantidad)
            edtCantidad?.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    actualizarTotalCategoria()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        
        // Actualizar el total inicialmente
        actualizarTotalCategoria()
        
        categoriaLayout.addView(headerLayout)
        categoriaLayout.addView(productosLayout)
        layoutProductos.addView(categoriaLayout)
    }

    private fun mostrarPrevisualizacion() {
        android.util.Log.d("DIALOG_DEBUG", "[LOG-010] 🚀🚀🚀 mostrarPrevisualizacion() LLAMADO 🚀🚀🚀")
        android.util.Log.d("DIALOG_DEBUG", "[LOG-011] Tipo: $tipo, Fecha: $fecha")
        android.util.Log.d("DIALOG_DEBUG", "[LOG-011A] Stack trace:")
        Thread.currentThread().stackTrace.take(10).forEachIndexed { index, element ->
            android.util.Log.d("DIALOG_DEBUG", "[LOG-011A]   $index: ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }
        
        val productosConCantidad = mutableListOf<Producto>()
        
        // Recorrer todas las categorías para obtener los datos ingresados
        for (i in 0 until layoutProductos.childCount) {
            val categoriaLayout = layoutProductos.getChildAt(i) as LinearLayout
            if (categoriaLayout.childCount > 1) {
                val productosLayout = categoriaLayout.getChildAt(1) as LinearLayout
                
                // Recorrer todos los productos de esta categoría
                for (j in 0 until productosLayout.childCount) {
                    val fila = productosLayout.getChildAt(j)
                    val edtCantidad = fila.findViewById<EditText?>(R.id.edtCantidad)
                    if (edtCantidad != null) {
                        val producto = edtCantidad.tag as Producto
                        val cantidadStr = edtCantidad.text.toString()
                        val cantidad = if (cantidadStr.isNotEmpty()) cantidadStr.toInt() else 0
                        val copia = producto.copy(cantidad = cantidad)
                        productosConCantidad.add(copia)
                    }
                }
            }
        }
        
        // Mostrar todos los productos, incluyendo los que tienen cantidad 0
        val productosConStock = productosConCantidad.filter { it.cantidad >= 0 }
        
        if (productosConStock.isEmpty()) {
            android.widget.Toast.makeText(
                this@CargaActivity, 
                "⚠️ No se pudieron cargar los productos", 
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        // Separar productos con cantidad > 0 y con cantidad = 0
        val productosConStockPositivo = productosConStock.filter { it.cantidad > 0 }
        val productosSinCantidad = productosConStock.filter { it.cantidad == 0 }
        
        // Crear mensaje de previsualización
        val mensaje = StringBuilder()
        mensaje.append("📋 PREVISUALIZACIÓN DE DATOS\n\n")
        mensaje.append("📅 Fecha: $fecha\n")
        mensaje.append("📦 Tipo: $tipo\n")
        mensaje.append("📊 Total de productos: ${productosConStock.size}\n")
        mensaje.append("✅ Con cantidad: ${productosConStockPositivo.size}\n")
        mensaje.append("❌ Sin cantidad (0): ${productosSinCantidad.size}\n\n")
        
        // Agrupar por categoría
        val productosPorCategoria = productosConStock.groupBy { it.categoria }
        
        for ((categoria, productos) in productosPorCategoria) {
            val productosConCantidadCategoria = productos.filter { it.cantidad > 0 }
            val productosSinCantidadCategoria = productos.filter { it.cantidad == 0 }
            
            mensaje.append("🔸 $categoria:\n")
            
            // Mostrar productos con cantidad primero
            if (productosConCantidadCategoria.isNotEmpty()) {
                for (producto in productosConCantidadCategoria.sortedBy { it.nombre }) {
                    val codigo = producto.nombre.split(" - ")[0]
                    val nombre = if (producto.nombre.contains(" - ")) {
                        producto.nombre.split(" - ")[1]
                    } else {
                        producto.nombre
                    }
                    mensaje.append("   ✅ $codigo: $nombre = ${producto.cantidad}\n")
                }
            }
            
            // Mostrar productos sin cantidad
            if (productosSinCantidadCategoria.isNotEmpty()) {
                for (producto in productosSinCantidadCategoria.sortedBy { it.nombre }) {
                    val codigo = producto.nombre.split(" - ")[0]
                    val nombre = if (producto.nombre.contains(" - ")) {
                        producto.nombre.split(" - ")[1]
                    } else {
                        producto.nombre
                    }
                    mensaje.append("   ❌ $codigo: $nombre = 0\n")
                }
            }
            mensaje.append("\n")
        }
        
        // Determinar el título y mensaje según el contenido
        val todosEnCero = productosConStockPositivo.isEmpty()
        val switchActivado = if (tipo == "Ingreso de Mercadería") switchNoRecibioMercaderia.isChecked else false
        
        val titulo = if (todosEnCero && !switchActivado && tipo == "Ingreso de Mercadería") {
            "🚨 ERROR: Todos los productos en 0"
        } else if (todosEnCero) {
            "⚠️ Confirmar: Todos los productos en 0"
        } else {
            "🔍 Previsualización"
        }
        
        val mensajeAdicional = if (todosEnCero && !switchActivado && tipo == "Ingreso de Mercadería") {
            "\n\n🚨 ATENCIÓN: Todos los productos tienen cantidad 0 pero NO activaste el switch 'Hoy no recibí mercadería'.\n\n" +
            "Si no recibiste mercadería hoy, por favor:\n" +
            "1. Cancela esta operación\n" +
            "2. Activa el switch '📦 Hoy no recibí mercadería'\n" +
            "3. Guarda nuevamente\n\n" +
            "Esto evitará que tu local aparezca con alerta ⚠️ en Gestión de Locales."
        } else if (todosEnCero) {
            "\n\n⚠️ ATENCIÓN: Todos los productos tienen cantidad 0.\n¿Estás seguro de que quieres guardar estos datos?"
        } else {
            ""
        }
        
        // Crear layout personalizado para el diálogo con control total del fondo
        android.util.Log.d("DIALOG_DEBUG", "[LOG-012] Verificando modo oscuro...")
        val isDark = isDarkMode()
        android.util.Log.d("DIALOG_DEBUG", "[LOG-013] isDarkMode() = $isDark")
        android.util.Log.d("DIALOG_DEBUG", "🔍 Configuration UI Mode: ${resources.configuration.uiMode}")
        android.util.Log.d("DIALOG_DEBUG", "🔍 UI_MODE_NIGHT_MASK: ${android.content.res.Configuration.UI_MODE_NIGHT_MASK}")
        android.util.Log.d("DIALOG_DEBUG", "🔍 UI_MODE_NIGHT_YES: ${android.content.res.Configuration.UI_MODE_NIGHT_YES}")
        
        val dialogBackgroundColor = if (isDark) {
            android.util.Log.d("DIALOG_DEBUG", "✅ Usando fondo NEGRO (modo oscuro)")
            android.graphics.Color.BLACK
        } else {
            android.util.Log.d("DIALOG_DEBUG", "✅ Usando fondo BLANCO (modo claro)")
            android.graphics.Color.WHITE
        }
        val textColor = if (isDark) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.BLACK
        }
        
        android.util.Log.d("DIALOG_DEBUG", "🎨 dialogBackgroundColor = $dialogBackgroundColor (BLACK=${android.graphics.Color.BLACK}, WHITE=${android.graphics.Color.WHITE})")
        android.util.Log.d("DIALOG_DEBUG", "🎨 textColor = $textColor")
        android.util.Log.d("DIALOG_DEBUG", "🎨 dialogBackgroundColor hex = #${Integer.toHexString(dialogBackgroundColor)}")
        android.util.Log.d("DIALOG_DEBUG", "🎨 textColor hex = #${Integer.toHexString(textColor)}")
        
        // Crear Dialog personalizado (NO AlertDialog) con tema explícito
        val dialog = android.app.Dialog(this, if (isDark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        android.util.Log.d("DIALOG_DEBUG", "📱 Dialog creado con tema: ${if (isDark) "DARK" else "LIGHT"}")
        
        // Crear layout principal
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(dialogBackgroundColor)
            setPadding(0, 0, 0, 0)
        }
        android.util.Log.d("DIALOG_DEBUG", "📦 mainLayout creado con fondo: $dialogBackgroundColor")
        
        // Título
        val titleView = TextView(this).apply {
            text = titulo
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textColor)
            setBackgroundColor(dialogBackgroundColor)
            setPadding(24, 24, 24, 16)
        }
        android.util.Log.d("DIALOG_DEBUG", "📝 titleView creado - textColor: $textColor, background: $dialogBackgroundColor")
        mainLayout.addView(titleView)
        
        // ScrollView con mensaje - Configurar con peso para que no ocupe todo el espacio
        val scrollView = android.widget.ScrollView(this).apply {
            setBackgroundColor(dialogBackgroundColor)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, // Altura 0 para usar weight
                1f // Peso 1 para ocupar espacio disponible
            )
        }
        android.util.Log.d("DIALOG_DEBUG", "📜 scrollView creado con fondo: $dialogBackgroundColor")
        
        val messageView = TextView(this).apply {
            text = mensaje.toString() + mensajeAdicional
            textSize = 14f
            setTextColor(textColor)
            setBackgroundColor(dialogBackgroundColor)
            setPadding(24, 0, 24, 16)
        }
        android.util.Log.d("DIALOG_DEBUG", "💬 messageView creado - textColor: $textColor, background: $dialogBackgroundColor")
        
        scrollView.addView(messageView)
        mainLayout.addView(scrollView)
        
        // Botones - Usar TextView estilizado en lugar de Button para evitar estilos por defecto
        // Configurar sin peso para que siempre sean visibles
        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setBackgroundColor(dialogBackgroundColor)
            setPadding(16, 8, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT // Altura fija basada en contenido
            )
        }
        android.util.Log.d("DIALOG_DEBUG", "🔘 buttonsLayout creado con fondo: $dialogBackgroundColor")
        
        // Función helper para crear botones como TextView
        fun createButton(text: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = 14f
                setTextColor(textColor)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(24, 16, 24, 16)
                gravity = android.view.Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                // Agregar efecto de ripple
                foreground = android.content.res.Resources.getSystem().getDrawable(
                    android.R.drawable.list_selector_background,
                    null
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(8, 0, 8, 0)
                }
            }
        }
        
        val btnCancelar = createButton("❌ Cancelar") { 
            android.util.Log.d("DIALOG_DEBUG", "🔘 Botón Cancelar presionado")
            dialog.dismiss() 
        }
        android.util.Log.d("DIALOG_DEBUG", "🔘 btnCancelar creado - textColor: $textColor")
        
        val btnEditar = createButton("✏️ Editar") { 
            android.util.Log.d("DIALOG_DEBUG", "🔘 Botón Editar presionado")
            dialog.dismiss() 
        }
        android.util.Log.d("DIALOG_DEBUG", "🔘 btnEditar creado - textColor: $textColor")
        
        val btnGuardar = createButton("✅ Guardar") {
            android.util.Log.d("DIALOG_DEBUG", "🔘 Botón Guardar presionado")
            dialog.dismiss()
            guardarDatos()
        }
        android.util.Log.d("DIALOG_DEBUG", "🔘 btnGuardar creado - textColor: $textColor")
        
        buttonsLayout.addView(btnCancelar)
        buttonsLayout.addView(btnEditar)
        buttonsLayout.addView(btnGuardar)
        mainLayout.addView(buttonsLayout)
        
        // Configurar el diálogo
        dialog.setContentView(mainLayout)
        android.util.Log.d("DIALOG_DEBUG", "📱 setContentView aplicado")
        
        val window = dialog.window
        if (window != null) {
            // Forzar el fondo del diálogo
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
            android.util.Log.d("DIALOG_DEBUG", "🪟 Window background configurado: $dialogBackgroundColor")
            
            // También configurar el decorView directamente
            window.decorView.setBackgroundColor(dialogBackgroundColor)
            android.util.Log.d("DIALOG_DEBUG", "🪟 DecorView background configurado directamente: $dialogBackgroundColor")
            
            // Verificar el fondo después de configurarlo
            val drawable = window.decorView.background
            android.util.Log.d("DIALOG_DEBUG", "🪟 DecorView background drawable: $drawable")
            if (drawable is android.graphics.drawable.ColorDrawable) {
                val actualColor = drawable.color
                android.util.Log.d("DIALOG_DEBUG", "🪟 DecorView background color actual: $actualColor (esperado: $dialogBackgroundColor)")
                if (actualColor != dialogBackgroundColor) {
                    android.util.Log.e("DIALOG_DEBUG", "❌ COLOR NO COINCIDE! Esperado: $dialogBackgroundColor, Actual: $actualColor")
                }
            }
            
            // Configurar altura máxima para que los botones siempre sean visibles
            val maxHeight = (resources.displayMetrics.heightPixels * 0.8).toInt()
            window.setLayout(
                (resources.displayMetrics.widthPixels * 0.9).toInt(),
                maxHeight
            )
        } else {
            android.util.Log.e("DIALOG_DEBUG", "❌ Window es NULL!")
        }
        
        // Verificar después de mostrar
        dialog.setOnShowListener {
            android.util.Log.d("DIALOG_DEBUG", "👁️ Diálogo mostrado - verificando fondos...")
            val decorView = dialog.window?.decorView
            if (decorView != null) {
                val bg = decorView.background
                android.util.Log.d("DIALOG_DEBUG", "🪟 DecorView background después de show: $bg")
                if (bg is android.graphics.drawable.ColorDrawable) {
                    val bgColor = bg.color
                    android.util.Log.d("DIALOG_DEBUG", "🪟 DecorView background color después de show: $bgColor (esperado: $dialogBackgroundColor)")
                    if (bgColor != dialogBackgroundColor) {
                        android.util.Log.e("DIALOG_DEBUG", "❌ COLOR NO COINCIDE DESPUÉS DE SHOW! Esperado: $dialogBackgroundColor, Actual: $bgColor")
                        // Intentar forzar nuevamente
                        decorView.setBackgroundColor(dialogBackgroundColor)
                        android.util.Log.d("DIALOG_DEBUG", "🔄 Intentando forzar color nuevamente...")
                    }
                }
                
                // Verificar todos los hijos del decorView
                val childCount = (decorView as? android.view.ViewGroup)?.childCount ?: 0
                android.util.Log.d("DIALOG_DEBUG", "🪟 DecorView tiene $childCount hijos")
                (decorView as? android.view.ViewGroup)?.let { group ->
                    for (i in 0 until group.childCount) {
                        val child = group.getChildAt(i)
                        android.util.Log.d("DIALOG_DEBUG", "🪟 Hijo $i: ${child.javaClass.simpleName}, background: ${child.background}")
                        if (child is android.view.ViewGroup) {
                            child.setBackgroundColor(dialogBackgroundColor)
                        }
                    }
                }
            }
            
            // Verificar mainLayout
            val mainLayoutBg = mainLayout.background
            android.util.Log.d("DIALOG_DEBUG", "📦 mainLayout background: $mainLayoutBg")
            if (mainLayoutBg is android.graphics.drawable.ColorDrawable) {
                val mainLayoutBgColor = mainLayoutBg.color
                android.util.Log.d("DIALOG_DEBUG", "📦 mainLayout background color: $mainLayoutBgColor (esperado: $dialogBackgroundColor)")
                if (mainLayoutBgColor != dialogBackgroundColor) {
                    android.util.Log.e("DIALOG_DEBUG", "❌ MAINLAYOUT COLOR NO COINCIDE! Esperado: $dialogBackgroundColor, Actual: $mainLayoutBgColor")
                }
            }
            
            // Verificar titleView
            val titleColor = titleView.currentTextColor
            android.util.Log.d("DIALOG_DEBUG", "📝 titleView textColor actual: $titleColor (esperado: $textColor)")
            if (titleColor != textColor) {
                android.util.Log.e("DIALOG_DEBUG", "❌ TITLE TEXT COLOR NO COINCIDE! Esperado: $textColor, Actual: $titleColor")
                titleView.setTextColor(textColor)
            }
            
            // Verificar messageView
            val messageColor = messageView.currentTextColor
            android.util.Log.d("DIALOG_DEBUG", "💬 messageView textColor actual: $messageColor (esperado: $textColor)")
            if (messageColor != textColor) {
                android.util.Log.e("DIALOG_DEBUG", "❌ MESSAGE TEXT COLOR NO COINCIDE! Esperado: $textColor, Actual: $messageColor")
                messageView.setTextColor(textColor)
            }
        }
        
        android.util.Log.d("DIALOG_DEBUG", "[LOG-014] Llamando a dialog.show()")
        dialog.show()
        android.util.Log.d("DIALOG_DEBUG", "[LOG-015] ✅ dialog.show() COMPLETADO")
    }
    
    private fun guardarDatos() {
        // Deshabilitar el botón y mostrar indicador de carga
        btnGuardar.isEnabled = false
        btnGuardar.text = "💾 Guardando..."
        
        // Timeout de seguridad - restaurar botón después de 5 segundos
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!btnGuardar.isEnabled) {
                Log.w("CargaActivity", "Timeout: Restaurando botón después de 5 segundos")
                btnGuardar.isEnabled = true
                btnGuardar.text = "👁️ Previsualizar y Guardar"
                android.widget.Toast.makeText(this@CargaActivity, "⚠️ La operación está tomando más tiempo del esperado. Intenta nuevamente.", android.widget.Toast.LENGTH_LONG).show()
            }
        }, 5000)
        
        val productosConCantidad = mutableListOf<Producto>()
        
        // Recorrer todas las categorías
        for (i in 0 until layoutProductos.childCount) {
            val categoriaLayout = layoutProductos.getChildAt(i) as LinearLayout
            if (categoriaLayout.childCount > 1) {
                val productosLayout = categoriaLayout.getChildAt(1) as LinearLayout
                
                // Recorrer todos los productos de esta categoría
                for (j in 0 until productosLayout.childCount) {
                    val fila = productosLayout.getChildAt(j)
                    val edtCantidad = fila.findViewById<EditText?>(R.id.edtCantidad)
                    if (edtCantidad != null) {
                        val producto = edtCantidad.tag as Producto
                        val cantidadStr = edtCantidad.text.toString()
                        val cantidad = if (cantidadStr.isNotEmpty()) cantidadStr.toInt() else 0
                        val copia = producto.copy(cantidad = cantidad)
                        productosConCantidad.add(copia)
                    }
                }
            }
        }
        
        // Obtener el estado del switch
        val noRecibioMercaderia = if (tipo == "Ingreso de Mercadería") {
            switchNoRecibioMercaderia.isChecked
        } else {
            false
        }
        
        val registro = RegistroInventario(fecha, tipo, productosConCantidad, noRecibioMercaderia)
        
        // Crear diálogo de progreso ANTES de empezar cualquier operación
        var dialogoProgreso: android.app.AlertDialog? = null
        var txtProgreso: android.widget.TextView? = null
        var progressBar: android.widget.ProgressBar? = null
        
        runOnUiThread {
            val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
            val layoutProgreso = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
            }
            
            val titulo = TextView(this).apply {
                text = "⏳ Guardando datos..."
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, 24)
            }
            
            progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                max = 100
                progress = 0
            }
            
            txtProgreso = TextView(this).apply {
                text = "Iniciando..."
                textSize = 14f
                setTextColor(resources.getColor(android.R.color.darker_gray, null))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 16, 0, 0)
            }
            
            val advertencia = TextView(this).apply {
                text = "🚨 NO CIERRES LA APP"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 24, 0, 0)
            }
            
            layoutProgreso.addView(titulo)
            layoutProgreso.addView(progressBar)
            layoutProgreso.addView(txtProgreso)
            layoutProgreso.addView(advertencia)
            
            dialogoProgreso = android.app.AlertDialog.Builder(this)
                .setView(layoutProgreso)
                .setCancelable(false)
                .create()
            dialogoProgreso?.show()
        }
        
        // Función helper para actualizar progreso
        fun actualizarProgreso(paso: Int, total: Int, mensaje: String) {
            runOnUiThread {
                val porcentaje = (paso * 100 / total).coerceAtMost(100)
                progressBar?.progress = porcentaje
                txtProgreso?.text = mensaje
                Log.d("CargaActivity", "📊 Progreso: $paso/$total ($porcentaje%) - $mensaje")
            }
        }
        
        lifecycleScope.launch {
            try {
                Log.d("CargaActivity", "Guardando datos para $tipo en $fecha con ${productosConCantidad.size} productos (noRecibioMercaderia=$noRecibioMercaderia)")
                
                val pasosTotales = if (tipo == "Stock Final") 8 else 4
                var pasoActual = 0
                
                // Paso 1: Verificar si debe procesar descuentos
                actualizarProgreso(++pasoActual, pasosTotales, "Verificando datos existentes...")
                
                // 🔥 Si es Stock Final, verificar ANTES de guardar si ya existe y si los datos cambiaron
                // para procesar descuentos SOLO si es PRIMERA VEZ (NO si es modificación)
                var resultadoVerificacion: ResultadoVerificacion? = null
                if (tipo == "Stock Final") {
                    resultadoVerificacion = verificarSiDebeProcesarDescuentos(fecha, productosConCantidad)
                    if (resultadoVerificacion.debeProcesar) {
                        if (resultadoVerificacion.esPrimeraVez) {
                            Log.d("VENCIMIENTOS", "📝 Primera vez guardando Stock Final para $fecha - Procesará descuentos FIFO")
                        } else {
                            Log.d("VENCIMIENTOS", "📝 Stock Final modificado para $fecha - NO procesará descuentos (ya se aplicaron la primera vez)")
                        }
                    } else {
                        Log.d("VENCIMIENTOS", "⏭️ Stock Final ya existía para $fecha con los mismos datos - NO procesará descuentos (recarga)")
                    }
                }
                
                // Paso 2: Guardar en base de datos local
                actualizarProgreso(++pasoActual, pasosTotales, "Guardando en base de datos local...")
                localRepository.saveRegistroInventario(localId, registro)
                Log.d("CargaActivity", "Datos guardados exitosamente en base de datos local")
                
                // Paso 3: Actualizar promedios generales (solo Stock Final)
                if (tipo == "Stock Final") {
                    actualizarProgreso(++pasoActual, pasosTotales, "Actualizando promedios generales...")
                    actualizarPromedioGeneral(fecha, productosConCantidad)
                }
                
                // 🔥 REGLAS PARA DESCUENTO FIFO:
                // 1. Solo procesar si es HOY o AYER
                // 2. Si es modificación y el stock bajó → descontar más FIFO
                // 3. Si es modificación y el stock subió → advertir que faltan lotes
                // 4. NO procesar si es fecha pasada (más de 2 días atrás)
                if (tipo == "Stock Final" && resultadoVerificacion != null && resultadoVerificacion.debeProcesar) {
                    Log.d("VENCIMIENTOS", "📅 Procesando Stock Final para la fecha seleccionada: $fecha")
                    if (resultadoVerificacion.esPrimeraVez) {
                        Log.d("VENCIMIENTOS", "✅ Primera vez - Ajustará lotes al Stock Final")
                        actualizarProgreso(++pasoActual, pasosTotales, "Guardando estado inicial de vencimientos...")
                        guardarEstadoInicialVencimientos(fecha)
                    } else {
                        Log.d("VENCIMIENTOS", "✅ Modificación - Reajustará lotes al nuevo Stock Final")
                    }
                    
                    // Paso 5: Procesar descuentos FIFO
                    actualizarProgreso(++pasoActual, pasosTotales, "Procesando descuentos FIFO...")
                    procesarDescuentosVencimientos(fecha, productosConCantidad)
                    
                    // Paso 6: Guardar estado final
                    actualizarProgreso(++pasoActual, pasosTotales, "Guardando estado final de vencimientos...")
                    guardarEstadoFinalVencimientos(fecha)
                } else if (tipo == "Ingreso de Mercadería") {
                    // Paso 4: Guardar estado inicial cuando se carga el ingreso
                    actualizarProgreso(++pasoActual, pasosTotales, "Guardando estado inicial de vencimientos...")
                    guardarEstadoInicialVencimientos(fecha)
                }
                
                // Paso final local: Esperar a que Room complete la transacción
                actualizarProgreso(++pasoActual, pasosTotales, "Finalizando guardado local...")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // Forzar una lectura para asegurar que la transacción se complete
                    localRepository.getRegistrosByFechaYTipo(localId, fecha, tipo).first()
                    kotlinx.coroutines.delay(300) // Delay adicional para asegurar que todo esté guardado
                }
                
                // Restaurar el botón inmediatamente después de guardar localmente
                runOnUiThread {
                    btnGuardar.isEnabled = true
                    btnGuardar.text = "👁️ Previsualizar y Guardar"
                    
                    // Marcar que se guardaron datos para que MainActivity actualice los botones
                    try {
                        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
                        prefs.edit().putBoolean("datos_guardados", true).apply()
                        Log.d("CargaActivity", "✅ Marcando datos como guardados para actualización de botones")
                        
                        // Enviar broadcast para actualizar botones inmediatamente
                        // Ya esperamos a que Room complete la transacción antes
                        Log.d("CargaActivity", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        Log.d("CargaActivity", "📡 ENVIANDO BROADCAST EXPLÍCITO")
                        Log.d("CargaActivity", "   Acción: com.tuapp.inventario.ACTUALIZAR_BOTONES")
                        Log.d("CargaActivity", "   Tipo: $tipo")
                        Log.d("CargaActivity", "   LocalId: $localId")
                        Log.d("CargaActivity", "   Paquete: ${packageName}")
                        
                        val intent = Intent("com.tuapp.inventario.ACTUALIZAR_BOTONES")
                        intent.setPackage(packageName)  // Hacer el broadcast explícito
                        intent.putExtra("tipo", tipo)  // Agregar el tipo de registro
                        intent.putExtra("localId", localId)
                        sendBroadcast(intent)
                        
                        Log.d("CargaActivity", "✅ Broadcast enviado exitosamente (explícito)")
                        Log.d("CargaActivity", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    } catch (e: Exception) {
                        Log.w("CargaActivity", "Error marcando datos guardados: ${e.message}")
                    }
                    
                }
                
                // Sincronización con Firebase - Actualizar progreso
                actualizarProgreso(++pasoActual, pasosTotales, "Sincronizando con Firebase...")
                
                val firebaseRepo = FirebaseInventarioRepository(localId)
                Log.d("CargaActivity", "🔄 Iniciando sincronización con Firebase... (${productosConCantidad.size} productos)")
                
                firebaseRepo.saveRegistroInventario(registro)
                Log.d("CargaActivity", "✅ Registro guardado en Firebase exitosamente")
                
                // Ejecutar sincronización inteligente completa
                actualizarProgreso(++pasoActual, pasosTotales, "Sincronizando datos completos...")
                
                // Usar callback de progreso para actualizar el diálogo en tiempo real
                firebaseRepo.sincronizacionInteligente(
                    localRepository,
                    onProgress = { mensaje ->
                        actualizarProgreso(pasoActual, pasosTotales, mensaje)
                    },
                    timeoutMs = 120000L // 120 segundos (2 minutos) para sincronización completa
                )
                Log.d("CargaActivity", "✅ Datos sincronizados exitosamente con Firebase")
                
                // 📈 Las tendencias se calcularán en MainActivity al recibir el broadcast
                // (evita que se cancele al cerrar CargaActivity)
                
                // Completado - cerrar diálogo y mostrar éxito
                actualizarProgreso(pasosTotales, pasosTotales, "✅ Completado")
                runOnUiThread {
                    dialogoProgreso?.dismiss()
                    android.widget.Toast.makeText(
                        this@CargaActivity, 
                        "✅ Datos guardados y sincronizados correctamente\nRegresando al menú principal...", 
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    
                    // Cerrar la actividad
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        setResult(Activity.RESULT_OK)
                        finish()
                    }, 1500)
                }
                
            } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
                val errorCode = e.code
                val errorMessage = when (errorCode) {
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED -> 
                        "No tienes permisos para guardar en Firebase"
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> 
                        "El documento es demasiado grande (más de 1MB). Intenta con menos productos o contacta al administrador"
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> 
                        "Timeout al guardar (la conexión es lenta o hay muchos productos). Los datos se guardaron localmente y se sincronizarán más tarde"
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE -> 
                        "Firebase no está disponible. Los datos se guardaron localmente y se sincronizarán cuando haya conexión"
                    else -> "Error de Firebase: ${e.message}"
                }
                Log.w("CargaActivity", "⚠️ No se pudo sincronizar con Firebase: $errorMessage", e)
                
                // Cerrar diálogo y mostrar advertencia
                runOnUiThread {
                    dialogoProgreso?.dismiss()
                    btnGuardar.isEnabled = true
                    btnGuardar.text = "👁️ Previsualizar y Guardar"
                    android.widget.Toast.makeText(
                        this@CargaActivity,
                        "⚠️ Los datos se guardaron localmente, pero hubo un problema al sincronizar con Firebase.\n$errorMessage",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w("CargaActivity", "⏱️ Timeout al sincronizar con Firebase (muchos registros o conexión lenta)", e)
                runOnUiThread {
                    dialogoProgreso?.dismiss()
                    btnGuardar.isEnabled = true
                    btnGuardar.text = "👁️ Previsualizar y Guardar"
                    android.widget.Toast.makeText(
                        this@CargaActivity,
                        "⏱️ Los datos se guardaron localmente.\nLa sincronización completa está tardando más de lo esperado (hay muchos registros históricos).\nLos datos se sincronizarán automáticamente en segundo plano.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    
                    // Cerrar la actividad después de un delay
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        setResult(Activity.RESULT_OK)
                        finish()
                    }, 3000)
                }
            } catch (e: Exception) {
                Log.e("CargaActivity", "❌ Error guardando datos", e)
                
                // Determinar mensaje de error más específico
                val errorMessage = when {
                    e.message?.contains("timeout", ignoreCase = true) == true -> 
                        "⏱️ La operación está tomando demasiado tiempo. Verifica tu conexión a internet e intenta nuevamente."
                    e.message?.contains("network", ignoreCase = true) == true -> 
                        "📡 Error de conexión. Verifica tu internet e intenta nuevamente."
                    e.message?.contains("permission", ignoreCase = true) == true -> 
                        "🔒 No tienes permisos para guardar. Contacta al administrador."
                    e.message?.contains("size", ignoreCase = true) == true || 
                    e.message?.contains("too large", ignoreCase = true) == true -> 
                        "📦 Los datos son demasiado grandes. Intenta guardar menos productos a la vez o contacta al administrador."
                    else -> "❌ Error al guardar los datos: ${e.message ?: "Error desconocido"}"
                }
                
                // Cerrar diálogo y mostrar mensaje de error al usuario
                runOnUiThread {
                    dialogoProgreso?.dismiss()
                    btnGuardar.isEnabled = true
                    btnGuardar.text = "👁️ Previsualizar y Guardar"
                    android.widget.Toast.makeText(
                        this@CargaActivity, 
                        errorMessage, 
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    private fun configurarInformacionOperacion() {
        // Obtener información del local actual
        val localNombre = obtenerLocalNombre()
        
        // Mostrar indicador de local ARRIBA del cuadro de información
        val indicadorLocal = TextView(this).apply {
            text = "LOCAL: $localNombre"
            textSize = 16f
            setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 10, 0, 10)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        
        // Agregar el indicador VISUALMENTE ARRIBA de todo (al inicio del layout principal)
        val rootLayout = findViewById<LinearLayout>(R.id.layoutProductos).parent.parent as LinearLayout
        rootLayout.addView(indicadorLocal, 0)
        
        txtTipoOperacion.text = tipo
        
        // Formatear fecha para mostrar
        try {
            val formatoEntrada = DateHelper.getDateFormat("yyyy-MM-dd")
            val formatoSalida = DateHelper.getDateFormat("EEEE - dd/MM/yy", Locale("es", "ES"))
            val fechaFormateada = formatoSalida.format(formatoEntrada.parse(fecha) ?: Date())
            txtFechaOperacion.text = fechaFormateada
        } catch (e: Exception) {
            txtFechaOperacion.text = fecha
        }
    }
    
    private fun mostrarMensajeSinProductos() {
        runOnUiThread {
            layoutProductos.removeAllViews()
            
            val mensajeLayout = LinearLayout(this)
            mensajeLayout.orientation = LinearLayout.VERTICAL
            mensajeLayout.gravity = android.view.Gravity.CENTER
            mensajeLayout.setPadding(32, 64, 32, 64)
            
            val txtMensaje = TextView(this)
            txtMensaje.text = "No hay productos disponibles.\n\nPor favor, contacta al administrador para configurar los productos."
            txtMensaje.textSize = 16f
            txtMensaje.setTextColor(resources.getColor(android.R.color.darker_gray, null))
            txtMensaje.gravity = android.view.Gravity.CENTER
            txtMensaje.setPadding(16, 16, 16, 16)
            
            mensajeLayout.addView(txtMensaje)
            layoutProductos.addView(mensajeLayout)
            
            // Deshabilitar botón de guardar
            btnGuardar.isEnabled = false
            btnGuardar.text = "No hay productos para guardar"
        }
    }
    
    private fun mostrarMensajeError(mensaje: String) {
        runOnUiThread {
            layoutProductos.removeAllViews()
            
            val mensajeLayout = LinearLayout(this)
            mensajeLayout.orientation = LinearLayout.VERTICAL
            mensajeLayout.gravity = android.view.Gravity.CENTER
            mensajeLayout.setPadding(32, 64, 32, 64)
            
            val txtMensaje = TextView(this)
            txtMensaje.text = "Error: $mensaje\n\nPor favor, intenta nuevamente."
            txtMensaje.textSize = 16f
            txtMensaje.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
            txtMensaje.gravity = android.view.Gravity.CENTER
            txtMensaje.setPadding(16, 16, 16, 16)
            
            mensajeLayout.addView(txtMensaje)
            layoutProductos.addView(mensajeLayout)
            
            // Deshabilitar botón de guardar
            btnGuardar.isEnabled = false
            btnGuardar.text = "Error - No se puede guardar"
        }
    }
    
    private fun obtenerLocalId(): String {
        return try {
            val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
            val localId = prefs.getString("localId", "")
            if (localId.isNullOrEmpty()) {
                Log.e("CargaActivity", "No se encontró localId en SharedPreferences")
                "default" // Fallback
            } else {
                Log.d("CargaActivity", "LocalId obtenido: $localId")
                localId
            }
        } catch (e: Exception) {
            Log.e("CargaActivity", "Error obteniendo localId", e)
            "default" // Fallback
        }
    }
    
    private fun obtenerLocalNombre(): String {
        return try {
            val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
            val localNombre = prefs.getString("localNombre", "")
            if (localNombre.isNullOrEmpty()) {
                Log.e("CargaActivity", "No se encontró localNombre en SharedPreferences")
                "NO SELECCIONADO"
            } else {
                Log.d("CargaActivity", "LocalNombre obtenido: $localNombre")
                localNombre
            }
        } catch (e: Exception) {
            Log.e("CargaActivity", "Error obteniendo localNombre", e)
            "NO SELECCIONADO"
        }
    }
    
    /**
     * Configura las sugerencias para el layout de Stock 15:30
     */
    private fun configurarSugerencias(fila: View, codigo: String) {
        val edtCantidad = fila.findViewById<EditText>(R.id.edtCantidad)
        val txtSugerencia = fila.findViewById<TextView>(R.id.txtSugerencia)
        
        // Seleccionar todo el texto cuando se toque
        edtCantidad.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                edtCantidad.selectAll()
            }
        }
        
        // Agregar listener para calcular sugerencia automáticamente
        edtCantidad.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val stock = s?.toString()?.toIntOrNull() ?: 0
                val sugerencia = calcularSugerenciaPedido(codigo, stock)
                txtSugerencia.text = sugerencia.toString()
            }
        })
    }
    
    /**
     * Calcula la sugerencia de pedido basada en la fórmula de Excel
     */
    private fun calcularSugerenciaPedido(codigo: String, stockActual: Int): Int {
        try {
            // Obtener el día actual
            val diaActual = obtenerDiaActual()
            val esViernes = diaActual == "VIERNES"
            
            // Obtener promedios de venta para este producto
            val promedios = obtenerPromediosProducto(codigo)
            if (promedios.isEmpty()) {
                return 0
            }
            
            // Calcular sugerencia basada en la fórmula de Excel
            val porcentajeExtra = 0.3 // 30% extra por defecto
            
            val sugerencia = if (esViernes) {
                // Para viernes: SÁBADO + DOMINGO*(1+30%) - (stock - promedio)/2
                val promedioSabado = promedios.getOrElse(5) { 0.0 } // Sábado
                val promedioDomingo = promedios.getOrElse(6) { 0.0 } // Domingo
                val promedioGeneral = (promedioSabado + promedioDomingo) / 2
                
                val ventaEsperada = promedioSabado + (promedioDomingo * (1 + porcentajeExtra))
                val ajusteStock = (stockActual - promedioGeneral) / 2
                
                maxOf(0.0, ventaEsperada - ajusteStock)
            } else {
                // Para otros días: siguiente día + 30% - (stock - promedio)/2
                val indiceDia = obtenerIndiceDia(diaActual)
                val promedioDia = promedios.getOrElse(indiceDia) { 0.0 }
                val promedioGeneral = promedios.average()
                
                val ventaEsperada = promedioDia * (1 + porcentajeExtra)
                val ajusteStock = (stockActual - promedioGeneral) / 2
                
                maxOf(0.0, ventaEsperada - ajusteStock)
            }
            
            // Redondear a múltiplo de 50
            return ((sugerencia / 50).toInt() + 1) * 50
            
        } catch (e: Exception) {
            Log.e("CargaActivity", "Error calculando sugerencia para $codigo: ${e.message}")
            return 0
        }
    }
    
    /**
     * Obtiene el día actual
     */
    private fun isDarkMode(): Boolean {
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
        android.util.Log.d("DIALOG_DEBUG", "🌙 isDarkMode() - nightModeFlags: $nightModeFlags, UI_MODE_NIGHT_YES: ${android.content.res.Configuration.UI_MODE_NIGHT_YES}, resultado: $isDark")
        return isDark
    }
    
    private fun getThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
    
    private fun obtenerDiaActual(): String {
        val calendar = DateHelper.getCalendar()
        val dias = arrayOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        return dias[calendar.get(Calendar.DAY_OF_WEEK) - 2] // Ajustar para que lunes sea 0
    }
    
    /**
     * Obtiene los promedios de venta para un producto específico
     */
    private fun obtenerPromediosProducto(codigo: String): List<Double> {
        // Por ahora retornar promedios simulados, después se conectarán con los datos reales
        return when (codigo) {
            "JQ" -> listOf(50.0, 60.0, 45.0, 55.0, 70.0, 80.0, 90.0) // L-D
            "PB" -> listOf(30.0, 35.0, 25.0, 40.0, 45.0, 50.0, 60.0)
            "CS" -> listOf(20.0, 25.0, 15.0, 30.0, 35.0, 40.0, 45.0)
            "PO" -> listOf(40.0, 45.0, 35.0, 50.0, 55.0, 60.0, 70.0)
            else -> listOf(10.0, 12.0, 8.0, 15.0, 18.0, 20.0, 25.0)
        }
    }
    
    /**
     * Obtiene el índice del día (0=Lunes, 6=Domingo)
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
     * Fuerza la sincronización con Firebase para obtener los datos más actualizados
     */
    private fun forzarSincronizacionFirebase() {
        lifecycleScope.launch {
            try {
                Log.d("CargaActivity", "🔄 Forzando sincronización con Firebase...")
                
                // Verificar si hay usuario logueado
                val usuarioActual = (application as InventarioApplication).obtenerUsuarioActual()
                if (usuarioActual != null) {
                    val firebaseRepo = FirebaseInventarioRepository(usuarioActual.localId)
                    val localRepo = (application as InventarioApplication).localRepository
                    
                    // Forzar sincronización completa desde Firebase
                    firebaseRepo.forzarSincronizacionDesdeFirebase(localRepo)
                    
                    // Pequeña pausa para asegurar que la sincronización se complete
                    kotlinx.coroutines.delay(1000)
                    
                    Log.d("CargaActivity", "✅ Sincronización forzada completada")
                } else {
                    Log.d("CargaActivity", "⚠️ No hay usuario logueado, saltando sincronización")
                }
                
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelación esperada cuando la actividad se destruye
                Log.d("CargaActivity", "Sincronización cancelada (actividad cerrada)")
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    Log.e("CargaActivity", "❌ Error en sincronización forzada", e)
                }
            }
        }
    }
    
    // Funciones para skeleton loading
    private fun showSkeletonLoading(message: String = "Cargando datos...") {
        android.util.Log.d("DIALOG_DEBUG", "[LOG-016] showSkeletonLoading() - Mensaje: $message")
        runOnUiThread {
            txtOfflineMsg.text = message
            
            // 🔍 DEBUG: Verificar colores del skeleton loading
            val isDark = isDarkMode()
            android.util.Log.d("DIALOG_DEBUG", "[LOG-016A] Skeleton loading - isDarkMode: $isDark")
            
            // Asegurar que el skeleton loading tenga fondo correcto en modo oscuro
            if (isDark) {
                offlineOverlay.setBackgroundColor(android.graphics.Color.parseColor("#80000000")) // Semi-transparente negro
                txtOfflineMsg.setTextColor(resources.getColor(R.color.web_text_light, null))
                android.util.Log.d("DIALOG_DEBUG", "[LOG-016B] Skeleton loading - Fondo NEGRO aplicado")
            } else {
                offlineOverlay.setBackgroundColor(android.graphics.Color.parseColor("#80000000")) // Semi-transparente negro (igual en ambos)
                txtOfflineMsg.setTextColor(resources.getColor(R.color.web_text_light, null))
                android.util.Log.d("DIALOG_DEBUG", "[LOG-016B] Skeleton loading - Fondo semi-transparente aplicado")
            }
            
            offlineOverlay.visibility = android.view.View.VISIBLE
            android.util.Log.d("DIALOG_DEBUG", "[LOG-017] Skeleton loading VISIBLE")
            
            // Cancelar timeout anterior si existe
            skeletonTimeout?.cancel()
            
            // Crear timeout de seguridad (5 segundos máximo)
            skeletonTimeout = lifecycleScope.launch {
                kotlinx.coroutines.delay(5000)
                Log.w("CargaActivity", "⚠️ Timeout del skeleton loading - forzando ocultación")
                hideSkeletonLoading()
            }
        }
    }
    
    private fun hideSkeletonLoading() {
        android.util.Log.d("DIALOG_DEBUG", "[LOG-018] hideSkeletonLoading() llamado")
        runOnUiThread {
            try {
                // Cancelar timeout
                skeletonTimeout?.cancel()
                skeletonTimeout = null
                
                offlineOverlay.visibility = android.view.View.GONE
                android.util.Log.d("DIALOG_DEBUG", "[LOG-019] Skeleton loading OCULTO")
                Log.d("CargaActivity", "✅ Skeleton loading ocultado")
            } catch (e: Exception) {
                Log.e("CargaActivity", "❌ Error ocultando skeleton loading", e)
            }
        }
    }
    
    /**
     * Resultado de la verificación de si debe procesar descuentos
     */
    private data class ResultadoVerificacion(
        val debeProcesar: Boolean,
        val esPrimeraVez: Boolean
    )
    
    /**
     * Verifica si debe procesar descuentos comparando el Stock Final existente con los nuevos datos
     * - Si la fecha es anterior a hoy: NO procesar (día pasado, descuentos ya se aplicaron)
     * - Si no existe: procesar (primera vez)
     * - Si existe y los datos son diferentes: procesar (modificación)
     * - Si existe y los datos son iguales: NO procesar (recarga)
     */
    private suspend fun verificarSiDebeProcesarDescuentos(
        fecha: String, 
        nuevosProductos: List<Producto>
    ): ResultadoVerificacion {
        return try {
            
            // ✅ OPTIMIZADO: Solo cargar el registro específico que necesitamos
            // En lugar de cargar todos los registros (10,000+), solo cargamos 1 registro específico
            val stockFinalExistente = localRepository
                .getRegistrosByFechaYTipo(localId, fecha, "Stock Final")
                .first()
                .firstOrNull()
            
            if (stockFinalExistente == null) {
                // No existe, es primera vez
                Log.d("VENCIMIENTOS", "🔍 Stock Final para $fecha: NO EXISTE (primera vez)")
                ResultadoVerificacion(debeProcesar = true, esPrimeraVez = true)
            } else {
                // Existe, comparar datos
                val datosSonDiferentes = compararDatosStockFinal(stockFinalExistente.productos, nuevosProductos)
                if (datosSonDiferentes) {
                    Log.d("VENCIMIENTOS", "🔍 Stock Final para $fecha: EXISTE pero DATOS CAMBIARON (modificación)")
                    ResultadoVerificacion(debeProcesar = true, esPrimeraVez = false)
                } else {
                    // 🔥 FIX: Aunque los datos sean iguales, verificar si los lotes coinciden con el stock final
                    // Si no coinciden, forzar el ajuste (puede pasar si se borró y recargó el stock final)
                    val lotesCoinciden = verificarSiLotesCoincidenConStockFinal(nuevosProductos)
                    if (!lotesCoinciden) {
                        Log.d("VENCIMIENTOS", "🔍 Stock Final para $fecha: EXISTE con MISMOS DATOS pero LOTES NO COINCIDEN - Forzando ajuste")
                        ResultadoVerificacion(debeProcesar = true, esPrimeraVez = false)
                    } else {
                        Log.d("VENCIMIENTOS", "🔍 Stock Final para $fecha: EXISTE con MISMOS DATOS y LOTES COINCIDEN (recarga)")
                    ResultadoVerificacion(debeProcesar = false, esPrimeraVez = false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VENCIMIENTOS", "❌ Error verificando Stock Final: ${e.message}")
            // Si hay error, verificar si es fecha pasada antes de asumir que debe procesar
            try {
                val fechaHoy = DateHelper.getDateFormat("yyyy-MM-dd").format(Date())
                val fechaAyer = obtenerFechaAnterior(fechaHoy)
                if (fecha != fechaHoy && fecha != fechaAyer) {
                    Log.d("VENCIMIENTOS", "⏭️ Error pero fecha es anterior (no es HOY ni AYER) - NO procesará descuentos")
                    return ResultadoVerificacion(debeProcesar = false, esPrimeraVez = false)
                }
            } catch (e2: Exception) {
                // Si no se puede verificar fecha, asumir que debe procesar (por seguridad)
            }
            ResultadoVerificacion(debeProcesar = true, esPrimeraVez = true)
        }
    }
    
    /**
     * Compara dos listas de productos para verificar si los datos son diferentes
     * Compara por nombre y cantidad de cada producto
     */
    private fun compararDatosStockFinal(
        productosExistentes: List<Producto>,
        productosNuevos: List<Producto>
    ): Boolean {
        // Si tienen diferente cantidad de productos, son diferentes
        if (productosExistentes.size != productosNuevos.size) {
            Log.d("VENCIMIENTOS", "   📊 Diferente cantidad de productos: ${productosExistentes.size} vs ${productosNuevos.size}")
            return true
        }
        
        // Crear mapas por nombre para comparar
        val mapaExistente = productosExistentes.associateBy { it.nombre }
        val mapaNuevo = productosNuevos.associateBy { it.nombre }
        
        // Verificar si todos los productos coinciden
        for ((nombre, productoNuevo) in mapaNuevo) {
            val productoExistente = mapaExistente[nombre]
            
            if (productoExistente == null) {
                // Producto nuevo que no estaba antes
                Log.d("VENCIMIENTOS", "   📊 Producto nuevo encontrado: $nombre")
                return true
            }
            
            if (productoExistente.cantidad != productoNuevo.cantidad) {
                // Cantidad diferente
                Log.d("VENCIMIENTOS", "   📊 Cantidad diferente para $nombre: ${productoExistente.cantidad} vs ${productoNuevo.cantidad}")
                return true
            }
        }
        
        // Verificar si hay productos que se eliminaron
        for (nombre in mapaExistente.keys) {
            if (!mapaNuevo.containsKey(nombre)) {
                Log.d("VENCIMIENTOS", "   📊 Producto eliminado: $nombre")
                return true
            }
        }
        
        // Todos los datos son iguales
        Log.d("VENCIMIENTOS", "   ✅ Todos los datos son iguales")
        return false
    }
    
    /**
     * Verifica si los lotes activos coinciden con el stock final
     * Retorna true solo si todos los productos tienen lotes que suman exactamente el stock final
     */
    private suspend fun verificarSiLotesCoincidenConStockFinal(stockFinalProductos: List<Producto>): Boolean {
        return try {
            val vencimientoRepo = com.tuapp.inventario.repository.VencimientoRepository()
            val todosLosLotes = vencimientoRepo.obtenerLotesActivos(localId)
            
            // Preparar mapa de stock final normalizado
            val stockFinalPorProducto = stockFinalProductos.associate { producto ->
                val codigo = producto.nombre.split(" - ").firstOrNull() ?: producto.nombre
                val codigoNormalizado = normalizarCodigoProducto(codigo)
                codigoNormalizado to producto.cantidad
            }
            
            // Agrupar lotes por producto
            val lotesPorProducto = todosLosLotes
                .groupBy { it.productoCodigo }
            
            // Verificar cada producto del stock final
            var todosCoinciden = true
            stockFinalPorProducto.forEach { (productoCodigo, stockFinal) ->
                val lotes = lotesPorProducto[productoCodigo] ?: emptyList()
                val sumaLotes = lotes.sumOf { it.cantidad }
                
                if (sumaLotes != stockFinal) {
                    Log.d("VENCIMIENTOS", "   ⚠️ $productoCodigo: Lotes ($sumaLotes) ≠ Stock Final ($stockFinal)")
                    todosCoinciden = false
                }
            }
            
            // También verificar si hay lotes de productos que no están en el stock final (con cantidad > 0)
            lotesPorProducto.forEach { (productoCodigo, lotes) ->
                val sumaLotes = lotes.sumOf { it.cantidad }
                if (sumaLotes > 0 && !stockFinalPorProducto.containsKey(productoCodigo)) {
                    Log.d("VENCIMIENTOS", "   ⚠️ $productoCodigo: Tiene lotes ($sumaLotes) pero no está en Stock Final")
                    todosCoinciden = false
                }
            }
            
            if (todosCoinciden) {
                Log.d("VENCIMIENTOS", "   ✅ Todos los lotes coinciden con el Stock Final")
            }
            
            todosCoinciden
        } catch (e: Exception) {
            Log.e("VENCIMIENTOS", "❌ Error verificando coincidencia de lotes: ${e.message}")
            // Si hay error, asumir que no coinciden para forzar el ajuste
            false
        }
    }
    
    /**
     * Guarda el estado inicial de vencimientos (al inicio del día)
     */
    private suspend fun guardarEstadoInicialVencimientos(fecha: String) {
        try {
            val vencimientoRepo = com.tuapp.inventario.repository.VencimientoRepository()
            val lotesActivos = vencimientoRepo.obtenerLotesActivos(localId)
            vencimientoRepo.guardarEstadoInicialVencimientos(localId, fecha, lotesActivos)
            Log.d("VENCIMIENTOS", "📝 Estado inicial guardado: ${lotesActivos.size} lotes")
        } catch (e: Exception) {
            Log.e("VENCIMIENTOS", "❌ Error guardando estado inicial: ${e.message}")
        }
    }
    
    /**
     * Guarda el estado final de vencimientos (después del stock final)
     */
    private suspend fun guardarEstadoFinalVencimientos(fecha: String) {
        try {
            val vencimientoRepo = com.tuapp.inventario.repository.VencimientoRepository()
            val lotesActivos = vencimientoRepo.obtenerLotesActivos(localId)
            vencimientoRepo.guardarEstadoFinalVencimientos(localId, fecha, lotesActivos)
            Log.d("VENCIMIENTOS", "📝 Estado final guardado: ${lotesActivos.size} lotes")
        } catch (e: Exception) {
            Log.e("VENCIMIENTOS", "❌ Error guardando estado final: ${e.message}")
        }
    }
    
    /**
     * Procesa los descuentos FIFO de vencimientos al guardar Stock Final
     */
    private suspend fun procesarDescuentosVencimientos(fecha: String, stockFinalProductos: List<Producto>) {
        try {
            Log.d("VENCIMIENTOS", "🔥 Iniciando proceso de descuento FIFO")
            Log.d("VENCIMIENTOS", "📅 Fecha: $fecha")
            
            val vencimientoRepo = com.tuapp.inventario.repository.VencimientoRepository()
            val vencimientoManager = com.tuapp.inventario.manager.VencimientoManager(vencimientoRepo)
            
            // Preparar mapa de stock final normalizado
            val stockFinalPorProducto = stockFinalProductos
                .groupBy { normalizarCodigoProducto(it.nombre.split(" - ").firstOrNull() ?: it.nombre) }
                .mapValues { (_, prods) -> prods.first().cantidad }
            
            Log.d("VENCIMIENTOS", "📊 Stock Final por producto (normalizado): $stockFinalPorProducto")
            
            // ✅ LÓGICA SIMPLE Y DIRECTA:
            // Ajustar lotes para que sumen exactamente el Stock Final (descuento FIFO)
            // NO calcular "venta" - solo ajustar lotes al stock final
            Log.d("VENCIMIENTOS", "🔥 Ajustando lotes al Stock Final (FIFO)")
            vencimientoManager.sincronizarLotesConStockFinal(localId, fecha, stockFinalPorProducto)
            
            // Validar resultado
            Log.d("VENCIMIENTOS", "🔍 Validando resultado...")
            stockFinalPorProducto.forEach { (productoCodigo, stockFinal) ->
                val lotes = vencimientoRepo.obtenerLotesActivosProducto(localId, productoCodigo)
                val sumaLotes = lotes.sumOf { it.cantidad }
                
                if (sumaLotes == stockFinal) {
                    Log.d("VENCIMIENTOS", "   ✅ $productoCodigo: Lotes = Stock Final ($stockFinal)")
                } else {
                    Log.e("VENCIMIENTOS", "   ❌ $productoCodigo: Lotes ($sumaLotes) ≠ Stock Final ($stockFinal)")
                }
            }
            
            Log.d("VENCIMIENTOS", "✅ Descuentos FIFO procesados exitosamente")
        } catch (e: Exception) {
            Log.e("VENCIMIENTOS", "❌ Error procesando descuentos FIFO: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Normaliza el código de producto para que coincida con los lotes de vencimientos
     * Esta función asegura que los códigos extraídos de los nombres de productos
     * coincidan con los códigos usados en VencimientosActivity para crear lotes
     * 
     * Mapeo de normalización:
     * - PIZZAS: MUZZARE -> MUZZA, PEPPERO -> PEPPER
     * - EMPANADAS: Ya coinciden (JQ, PB, CS, PO, CP, HU, ES, CQ, RJ, QC, CB, CH)
     * - PASTELITOS: Ya coinciden (BATATA, MEMBRIL)
     * - OTROS: No se usan en vencimientos (DULCES, CHIPA, CRIOLLITO)
     */
    private fun normalizarCodigoProducto(codigo: String): String {
        return when (codigo) {
            // PIZZAS - Normalizar para que coincida con VencimientosActivity
            "PEPPERO" -> "PEPPER"
            "MUZZARE" -> "MUZZA"
            // EMPANADAS - Ya coinciden, no necesitan normalización
            // PASTELITOS - Ya coinciden, no necesitan normalización
            // OTROS - No se usan en vencimientos
            else -> codigo
        }
    }
    
    /**
     * Calcula la venta por producto: Stock Anterior + Ingreso - Stock Final
     */
    private suspend fun calcularVentasPorProducto(fecha: String, stockFinalProductos: List<Producto>): Map<String, Int> {
        return try {
            val fechaAnterior = obtenerFechaAnterior(fecha)
            
            // ✅ OPTIMIZADO: Solo cargar los registros específicos que necesitamos
            // En lugar de cargar todos los registros (10,000+), solo cargamos 2 registros específicos
            
            // Stock Final de ayer
            val stockAnteriorRegistro = localRepository
                .getRegistrosByFechaYTipo(localId, fechaAnterior, "Stock Final")
                .first()
                .firstOrNull()
            val stockAnterior = stockAnteriorRegistro?.productos ?: emptyList()
            
            // Ingreso de hoy
            val ingresoHoyRegistro = localRepository
                .getRegistrosByFechaYTipo(localId, fecha, "Ingreso de Mercadería")
                .first()
                .firstOrNull()
            val ingresoHoy = ingresoHoyRegistro?.productos ?: emptyList()
            
            val ventasMap = mutableMapOf<String, Int>()
            
            // Calcular venta para cada producto
            stockFinalProductos.forEach { productoFinal ->
                val codigo = productoFinal.nombre.split(" - ").firstOrNull() ?: productoFinal.nombre
                // Normalizar código para que coincida con los lotes
                val codigoNormalizado = normalizarCodigoProducto(codigo)
                
                val stockAnt = stockAnterior.find { it.nombre == productoFinal.nombre }?.cantidad ?: 0
                val ingreso = ingresoHoy.find { it.nombre == productoFinal.nombre }?.cantidad ?: 0
                val stockFin = productoFinal.cantidad
                
                // ✅ FÓRMULA CORRECTA: Venta = Stock Ayer + Ingreso Hoy - Stock Final Hoy
                val venta = stockAnt + ingreso - stockFin
                
                // Log detallado para debugging
                Log.d("VENCIMIENTOS", "   📊 $codigoNormalizado (original: $codigo):")
                Log.d("VENCIMIENTOS", "      Stock Ayer: $stockAnt")
                Log.d("VENCIMIENTOS", "      + Ingreso Hoy: $ingreso")
                Log.d("VENCIMIENTOS", "      - Stock Final: $stockFin")
                Log.d("VENCIMIENTOS", "      = Venta: $venta")
                Log.d("VENCIMIENTOS", "      Fórmula: $stockAnt + $ingreso - $stockFin = $venta")
                
                // Solo agregar al mapa si hay venta > 0 (para FIFO)
                // Pero la sincronización procesará TODOS los productos
                if (venta > 0) {
                    ventasMap[codigoNormalizado] = venta
                    Log.d("VENCIMIENTOS", "      ✅ Se procesará descuento FIFO de $venta uds")
                } else if (venta < 0) {
                    Log.w("VENCIMIENTOS", "      ⚠️ Venta negativa: $venta (posible error en datos o ajuste manual)")
                    Log.w("VENCIMIENTOS", "      💡 Esto significa que el stock aumentó más de lo esperado")
                } else {
                    Log.d("VENCIMIENTOS", "      ℹ️ Sin venta (stock se mantuvo igual)")
                }
            }
            
            ventasMap
        } catch (e: Exception) {
            Log.e("VENCIMIENTOS", "Error calculando ventas: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 🚀 OPTIMIZACIÓN: Calcula y guarda TODOS los promedios de TODOS los días históricos
     * Igual que "Venta por Volumen" pero guarda los resultados en Firebase
     * Esto evita tener que recalcular todos los promedios cada vez que se necesitan
     */
    private suspend fun actualizarPromedioGeneral(_fecha: String, _stockFinalProductos: List<Producto>) {
        try {
            Log.d("PROMEDIO_GENERAL", "🔄 Calculando TODOS los promedios históricos (como Venta por Volumen)")
            
            // 🚀 Cargar TODOS los registros históricos
            val todosLosRegistros = localRepository.getAllRegistros(localId).first()
            Log.d("PROMEDIO_GENERAL", "📊 Total registros históricos: ${todosLosRegistros.size}")
            
            if (todosLosRegistros.isEmpty()) {
                Log.w("PROMEDIO_GENERAL", "⚠️ No hay registros históricos para calcular promedios")
                return
            }
            
            // 🚀 Calcular TODOS los promedios de TODOS los días (igual que Venta por Volumen)
            val promediosPorDia = calcularPromediosPorDiaCompleto(todosLosRegistros)
            Log.d("PROMEDIO_GENERAL", "✅ Promedios calculados para ${promediosPorDia.size} días")
            
            // 🚀 Convertir a formato PromedioData (suma, cantidad, promedio) para Firebase
            val promediosGenerales = convertirPromediosAPromedioData(promediosPorDia, todosLosRegistros)
            
            // 🚀 Guardar TODOS los promedios en Firebase
            guardarPromediosGeneralesEnFirebase(localId, promediosGenerales)
            
            Log.d("PROMEDIO_GENERAL", "✅ TODOS los promedios guardados en Firebase: ${promediosGenerales.size} días")
            
            // 📈 CALCULAR TENDENCIAS usando los mismos datos que ya tenemos cargados
            Log.d("TENDENCIAS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.d("TENDENCIAS", "📈 Calculando tendencias con los datos ya cargados...")
            calcularYGuardarTendenciasConDatos(todosLosRegistros, promediosPorDia)
            
        } catch (e: Exception) {
            Log.e("PROMEDIO_GENERAL", "❌ Error calculando promedios generales: ${e.message}")
            e.printStackTrace()
            // No lanzar excepción para no interrumpir el guardado de Stock Final
        }
    }
    
    /**
     * Calcula promedios por día usando la misma lógica que Venta por Volumen
     * Retorna Map<DiaSemana, Map<Producto, Promedio>>
     */
    private fun calcularPromediosPorDiaCompleto(registros: List<RegistroInventario>): Map<String, Map<String, Double>> {
        val promediosPorDia = mutableMapOf<String, MutableMap<String, Double>>()
        val diasSemana = listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        
        // Inicializar estructura
        diasSemana.forEach { dia ->
            promediosPorDia[dia] = mutableMapOf()
        }
        
        // Obtener todas las fechas ordenadas
        val fechasOrdenadas = registros.map { it.fecha }.distinct().sorted()
        
        if (fechasOrdenadas.isEmpty()) {
            return promediosPorDia
        }
        
        // Agrupar por día de la semana
        val ventasPorDia = mutableMapOf<String, MutableList<Map<String, Int>>>()
        
        fechasOrdenadas.forEach { fecha ->
            val diaSemana = obtenerDiaSemana(fecha)
            val ventasDia = calcularVentasDiaCompleto(fecha, registros)
            if (ventasDia.isNotEmpty()) {
                ventasPorDia.getOrPut(diaSemana) { mutableListOf() }.add(ventasDia)
            }
        }
        
        // Calcular promedios
        ventasPorDia.forEach { (dia, listaVentas) ->
            if (listaVentas.isNotEmpty()) {
                // Obtener TODOS los productos únicos de TODOS los registros de este día
                val productos = listaVentas.flatMap { it.keys }.distinct()
                productos.forEach { producto ->
                    // Filtrar solo los días donde este producto tiene ventas > 0
                    val ventasConDatos = listaVentas.mapNotNull { it[producto] }.filter { it > 0 }
                    
                    if (ventasConDatos.isNotEmpty()) {
                        val suma = ventasConDatos.sum()
                        val promedio = suma.toDouble() / ventasConDatos.size
                        promediosPorDia[dia]?.set(producto, promedio)
                    } else {
                        // Si no hay días con ventas > 0, el promedio es 0
                        promediosPorDia[dia]?.set(producto, 0.0)
                    }
                }
            }
        }
        
        return promediosPorDia
    }
    
    /**
     * Calcula las ventas de un día específico (igual que Venta por Volumen)
     */
    private fun calcularVentasDiaCompleto(fecha: String, registros: List<RegistroInventario>): Map<String, Int> {
        val registrosFecha = registros.filter { it.fecha == fecha }
        val ingreso = registrosFecha.find { it.tipo == "Ingreso de Mercadería" }?.productos ?: emptyList()
        val stockFinal = registrosFecha.find { it.tipo == "Stock Final" }?.productos ?: emptyList()
        
        // Obtener stock final del día anterior
        val fechaAnterior = obtenerFechaAnterior(fecha)
        val stockAnterior = registros.filter { it.fecha == fechaAnterior }
            .find { it.tipo == "Stock Final" }?.productos ?: emptyList()
        
        val ventas = mutableMapOf<String, Int>()
        
        // Si no hay stock final del día actual, no podemos calcular ventas
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
            
            // Agregar TODOS los productos al map, incluso si la venta es 0
            ventas[nombreProducto] = if (venta > 0) venta else 0
        }
        
        return ventas
    }
    
    /**
     * Convierte los promedios calculados al formato PromedioData (suma, cantidad, promedio)
     * Necesita los registros para calcular la cantidad de días y la suma total
     */
    private fun convertirPromediosAPromedioData(
        promediosPorDia: Map<String, Map<String, Double>>,
        registros: List<RegistroInventario>
    ): MutableMap<String, MutableMap<String, PromedioData>> {
        val promediosGenerales = mutableMapOf<String, MutableMap<String, PromedioData>>()
        
        // Obtener todas las fechas ordenadas
        val fechasOrdenadas = registros.map { it.fecha }.distinct().sorted()
        
        // Agrupar ventas por día de la semana para calcular suma y cantidad
        val ventasPorDiaSemana = mutableMapOf<String, MutableList<Map<String, Int>>>()
        
        fechasOrdenadas.forEach { fecha ->
            val diaSemana = obtenerDiaSemana(fecha)
            val ventasDia = calcularVentasDiaCompleto(fecha, registros)
            if (ventasDia.isNotEmpty()) {
                ventasPorDiaSemana.getOrPut(diaSemana) { mutableListOf() }.add(ventasDia)
            }
        }
        
        // Convertir promedios a PromedioData
        promediosPorDia.forEach { (dia, productos) ->
            promediosGenerales[dia] = mutableMapOf()
            
            productos.forEach { (producto, _) ->
                // Obtener todas las ventas de este producto en este día de la semana
                val ventasDelProducto = ventasPorDiaSemana[dia]
                    ?.mapNotNull { it[producto] }
                    ?.filter { it > 0 } ?: emptyList()
                
                val suma = ventasDelProducto.sum().toDouble()
                val cantidad = ventasDelProducto.size
                val promedioCalculado = if (cantidad > 0) suma / cantidad else 0.0
                
                promediosGenerales[dia]?.set(producto, PromedioData(
                    suma = suma,
                    cantidad = cantidad,
                    promedio = promedioCalculado
                ))
            }
        }
        
        return promediosGenerales
    }
    
    /**
     * Guarda los promedios generales en Firebase para sincronización entre dispositivos
     */
    private fun guardarPromediosGeneralesEnFirebase(localId: String, promedios: Map<String, Map<String, PromedioData>>) {
        lifecycleScope.launch {
            try {
                val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
                
                // Convertir estructura a formato Firebase
                val promediosFirebase = mutableMapOf<String, Any>()
                promedios.forEach { (dia, productos) ->
                    val productosFirebase = mutableMapOf<String, Map<String, Any>>()
                    productos.forEach { (producto, data) ->
                        productosFirebase[producto] = mapOf(
                            "suma" to data.suma,
                            "cantidad" to data.cantidad,
                            "promedio" to data.promedio
                        )
                    }
                    promediosFirebase[dia] = productosFirebase
                }
                
                val promedioGeneralData = mapOf(
                    "promedios" to promediosFirebase,
                    "fechaActualizacion" to Timestamp.now(),
                    "localId" to localId
                )
                
                // Guardar en: locales/{localId}/promedios_generales/promedios
                firestore.collection("locales")
                    .document(localId)
                    .collection("promedios_generales")
                    .document("promedios")
                    .set(promedioGeneralData)
                    .addOnSuccessListener {
                        Log.d("PROMEDIO_GENERAL", "✅ Promedios generales guardados en Firebase")
                    }
                    .addOnFailureListener { e ->
                        Log.w("PROMEDIO_GENERAL", "⚠️ Error guardando promedios en Firebase: ${e.message}")
                    }
                    
            } catch (e: Exception) {
                Log.w("PROMEDIO_GENERAL", "⚠️ Error sincronizando promedios con Firebase: ${e.message}")
            }
        }
    }
    
    /**
     * Data class para almacenar datos de promedio acumulado
     */
    private data class PromedioData(
        val suma: Double,
        val cantidad: Int,
        val promedio: Double
    )
    
    /**
     * 🚀 Carga los promedios generales SOLO desde Firebase
     */
    private suspend fun cargarPromediosGeneralesDesdeFirebase(localId: String): MutableMap<String, MutableMap<String, PromedioData>> {
        return try {
            val firestore = FirebaseRegionManager.getFirestore()
            val docRef = firestore.collection("locales")
                .document(localId)
                .collection("promedios_generales")
                .document("promedios")
            
            val document = docRef.get().await()
            
            if (!document.exists()) {
                Log.d("PROMEDIO_GENERAL", "⚠️ No hay promedios en Firebase para local $localId - iniciando desde cero")
                return mutableMapOf()
            }
            
            val data = document.data ?: return mutableMapOf()
            val promediosFirebase = data["promedios"] as? Map<*, *> ?: return mutableMapOf()
            
            val promedios = mutableMapOf<String, MutableMap<String, PromedioData>>()
            promediosFirebase.forEach { (dia, productosData) ->
                val diaStr = dia.toString()
                val productos = mutableMapOf<String, PromedioData>()
                
                (productosData as? Map<*, *>)?.forEach { (producto, dataMap) ->
                    val productoStr = producto.toString()
                    val dataObj = dataMap as? Map<*, *>
                    val suma = (dataObj?.get("suma") as? Number)?.toDouble() ?: 0.0
                    val cantidad = (dataObj?.get("cantidad") as? Number)?.toInt() ?: 0
                    val promedio = (dataObj?.get("promedio") as? Number)?.toDouble() ?: 0.0
                    
                    productos[productoStr] = PromedioData(suma = suma, cantidad = cantidad, promedio = promedio)
                }
                
                if (productos.isNotEmpty()) {
                    promedios[diaStr] = productos
                }
            }
            
            Log.d("PROMEDIO_GENERAL", "✅ Promedios cargados desde Firebase: ${promedios.size} días")
            promedios
            
        } catch (e: Exception) {
            Log.w("PROMEDIO_GENERAL", "⚠️ Error cargando promedios desde Firebase: ${e.message}")
            mutableMapOf()
        }
    }

    /**
     * 📈 Calcula tendencias de ventas y las guarda en Firebase
     * Se ejecuta en background después de guardar Stock Final
     */
    private fun calcularYGuardarTendencias(fechaRef: String = fecha) {
        val fechaHoy = fechaRef
        
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                Log.d("TENDENCIAS", "📈 Iniciando cálculo de tendencias para $localId (fecha ref: $fechaRef)...")
                
                // 1. Intentar cargar desde Firebase primero (documento único)
                val firestore = FirebaseRegionManager.getFirestore()
                val docTendencias = firestore
                    .collection("locales")
                    .document(localId)
                    .collection("cache_tendencias")
                    .document("tendencias")  // Documento único que se sobrescribe
                    .get()
                    .await()
                
                if (docTendencias.exists()) {
                    // Verificar si ya están calculadas para HOY
                    val fechaActualizacion = docTendencias.getTimestamp("fechaActualizacion")
                    val sdfFecha = DateHelper.getDateFormat("yyyy-MM-dd")
                    val fechaDoc = if (fechaActualizacion != null) {
                        sdfFecha.format(fechaActualizacion.toDate())
                    } else {
                        ""
                    }
                    
                    if (fechaDoc == fechaHoy) {
                        // Ya están calculadas para hoy
                        @Suppress("UNCHECKED_CAST")
                        val tendenciasGuardadas = docTendencias.data?.filterKeys { it != "fechaActualizacion" } as? Map<String, Double>
                        if (tendenciasGuardadas != null && tendenciasGuardadas.isNotEmpty()) {
                            MainActivity.cacheTendencias = tendenciasGuardadas
                            MainActivity.cacheTendenciasFecha = fechaHoy
                            MainActivity.cacheTendenciasLocalId = localId
                            Log.d("TENDENCIAS", "✅ Tendencias ya actualizadas hoy, no recalcular")
                            return@launch
                        }
                    } else {
                        Log.d("TENDENCIAS", "⚠️ Tendencias desactualizadas (última: $fechaDoc), recalculando...")
                    }
                }
                
                // 2. Si no existen, calcularlas
                Log.d("TENDENCIAS", "🔄 Calculando tendencias nuevas...")
                
                // 2.1 Cargar promedios si no están disponibles
                if (!MainActivity.promediosCargados) {
                    Log.d("TENDENCIAS", "📊 Cargando promedios primero...")
                    try {
                        val calendar = Calendar.getInstance()
                        calendar.add(Calendar.DAY_OF_YEAR, -60)
                        val fechaLimite = DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
                        
                        val snapshot = firestore
                            .collection("locales")
                            .document(localId)
                            .collection("registros")
                            .whereGreaterThanOrEqualTo("fecha", fechaLimite)
                            .get()
                            .await()
                        
                        val registros = snapshot.documents.mapNotNull { document ->
                            @Suppress("DEPRECATION")
                            document.toObject(RegistroInventario::class.java)
                        }
                        
                        if (registros.isNotEmpty()) {
                            MainActivity.promediosReales = calcularPromediosPorDiaParaTendencias(registros)
                            MainActivity.promediosCargados = true
                            Log.d("TENDENCIAS", "✅ Promedios cargados: ${MainActivity.promediosReales.keys.size} días")
                        }
                    } catch (e: Exception) {
                        Log.e("TENDENCIAS", "❌ Error cargando promedios: ${e.message}")
                    }
                }
                
                if (!MainActivity.promediosCargados) {
                    Log.w("TENDENCIAS", "⚠️ No se pudieron cargar promedios, cancelando cálculo de tendencias")
                    return@launch
                }
                
                // 2.2 Usar lista hardcodeada de productos
                val productos = listOf(
                    "JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH",
                    "MUZZARE", "JAMON", "PEPPERO",
                    "BATATA", "MEMBRIL",
                    "MEDIALUNA_MANTECA", "MEDIALUNA_GRASA", "CHIPA", "CRIOLLITO"
                )
                
                Log.d("TENDENCIAS", "📦 Productos a calcular: ${productos.size}")
                
                val tendencias = mutableMapOf<String, Double>()
                
                productos.forEach { codigo ->
                    try {
                        Log.d("TENDENCIAS", "  🔄 Calculando $codigo...")
                        val tendencia = calcularTendenciaProducto(codigo)
                        tendencias[codigo] = tendencia
                        if (tendencia != 0.0) {
                            Log.d("TENDENCIAS", "  ✅ $codigo: ${String.format("%.1f", tendencia)}%")
                        } else {
                            Log.d("TENDENCIAS", "  ℹ️ $codigo: 0.0% (sin tendencia)")
                        }
                    } catch (e: Exception) {
                        Log.w("TENDENCIAS", "  ⚠️ Error en $codigo: ${e.message}")
                        e.printStackTrace()
                        tendencias[codigo] = 0.0
                    }
                }
                
                Log.d("TENDENCIAS", "📊 Resumen: ${tendencias.filter { it.value != 0.0 }.size} productos con tendencia de ${tendencias.size} totales")
                
                // 3. Guardar en Firebase (documento único que se sobrescribe)
                Log.d("TENDENCIAS", "💾 Guardando en Firebase...")
                Log.d("TENDENCIAS", "   Ruta: locales/$localId/cache_tendencias/tendencias (único)")
                Log.d("TENDENCIAS", "   Datos: ${tendencias.filter { it.value != 0.0 }}")
                
                // Agregar timestamp de actualización
                val datosConTimestamp = tendencias.toMutableMap()
                datosConTimestamp["fechaActualizacion"] = com.google.firebase.Timestamp.now().seconds.toDouble()
                
                firestore
                    .collection("locales")
                    .document(localId)
                    .collection("cache_tendencias")
                    .document("tendencias")  // Documento único que se sobrescribe
                    .set(datosConTimestamp)
                    .await()
                
                Log.d("TENDENCIAS", "✅ Guardado en Firebase exitoso (actualizado: $fechaHoy)")
                
                // 4. Guardar en caché local
                MainActivity.cacheTendencias = tendencias
                MainActivity.cacheTendenciasFecha = fechaHoy
                MainActivity.cacheTendenciasLocalId = localId
                
                Log.d("TENDENCIAS", "✅ Tendencias calculadas y guardadas: ${tendencias.size} productos (${tendencias.filter { it.value != 0.0 }.size} con cambios)")
                
            } catch (e: Exception) {
                Log.e("TENDENCIAS", "❌ Error calculando tendencias: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Calcula la tendencia de un producto específico
     */
    private suspend fun calcularTendenciaProducto(codigo: String): Double {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (!MainActivity.promediosCargados || MainActivity.promediosReales.isEmpty()) {
                    Log.w("TENDENCIAS", "  ⚠️ $codigo: Promedios no disponibles")
                    return@withContext 0.0
                }
                
                val firestore = FirebaseRegionManager.getFirestore()
                val fechaHoy = DateHelper.getFechaActual()
                val calendar = DateHelper.getCalendar()
                val fechas = mutableListOf<String>()
                
                for (i in 1..7) {
                    calendar.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaHoy) ?: java.util.Date()
                    calendar.add(Calendar.DAY_OF_YEAR, -i)
                    fechas.add(DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time))
                }
                
                val diferencias = mutableListOf<Double>()
                var diasConDatos = 0
                
                fechas.forEach { fecha ->
                    try {
                        val cal = DateHelper.getCalendar()
                        cal.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(fecha) ?: return@forEach
                        val diaSemana = cal.get(Calendar.DAY_OF_WEEK)
                        
                        val nombreDia = when (diaSemana) {
                            Calendar.SUNDAY -> "DOMINGO"
                            Calendar.MONDAY -> "LUNES"
                            Calendar.TUESDAY -> "MARTES"
                            Calendar.WEDNESDAY -> "MIERCOLES"
                            Calendar.THURSDAY -> "JUEVES"
                            Calendar.FRIDAY -> "VIERNES"
                            Calendar.SATURDAY -> "SABADO"
                            else -> ""
                        }
                        
                        val promediosDelDia = MainActivity.promediosReales[nombreDia] ?: emptyMap()
                        val promedioHistorico = promediosDelDia[codigo] 
                            ?: promediosDelDia.entries.find { it.key.startsWith("$codigo ") || it.key.startsWith("$codigo -") }?.value 
                            ?: 0.0
                        
                        if (promedioHistorico <= 0) {
                            return@forEach
                        }
                        
                        val fechaAnterior = obtenerFechaAnterior(fecha)
                        
                        val stockAnterior = firestore.collection("locales").document(localId)
                            .collection("registros").document("${fechaAnterior}_Stock_Final")
                            .get().await()
                            .toObject(RegistroInventario::class.java)
                            ?.productos?.find { it.nombre.startsWith("$codigo ") || it.nombre.startsWith("$codigo -") }
                            ?.cantidad ?: 0
                        
                        val ingreso = firestore.collection("locales").document(localId)
                            .collection("registros").document("${fecha}_Ingreso_de_Mercadería")
                            .get().await()
                            .toObject(RegistroInventario::class.java)
                            ?.productos?.find { it.nombre.startsWith("$codigo ") || it.nombre.startsWith("$codigo -") }
                            ?.cantidad ?: 0
                        
                        val stockFinal = firestore.collection("locales").document(localId)
                            .collection("registros").document("${fecha}_Stock_Final")
                            .get().await()
                            .toObject(RegistroInventario::class.java)
                            ?.productos?.find { it.nombre.startsWith("$codigo ") || it.nombre.startsWith("$codigo -") }
                            ?.cantidad ?: 0
                        
                        val ventaReal = stockAnterior + ingreso - stockFinal
                        if (ventaReal <= 0) return@forEach
                        
                        val diferenciaPorcentual = ((ventaReal - promedioHistorico) / promedioHistorico) * 100
                        diferencias.add(diferenciaPorcentual)
                        diasConDatos++
                        
                    } catch (e: Exception) {
                        // Silenciar errores individuales
                    }
                }
                
                Log.d("TENDENCIAS", "    📊 $codigo: $diasConDatos días con datos de ${fechas.size}")
                
                if (diferencias.isEmpty()) return@withContext 0.0
                
                val tendenciaPromedio = diferencias.average()
                if (kotlin.math.abs(tendenciaPromedio) < 5.0) return@withContext 0.0
                
                tendenciaPromedio
                
            } catch (e: Exception) {
                0.0
            }
        }
    }
    
    /**
     * Calcula promedios por día de la semana (simplificado para tendencias)
     */
    private fun calcularPromediosPorDiaParaTendencias(registros: List<RegistroInventario>): Map<String, Map<String, Double>> {
        val promediosPorDia = mutableMapOf<String, MutableMap<String, Double>>()
        val diasSemana = listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        
        diasSemana.forEach { dia ->
            promediosPorDia[dia] = mutableMapOf()
        }
        
        val registrosPorFecha = registros.groupBy { it.fecha }
        val ventasPorDia = mutableMapOf<String, Map<String, Int>>()
        
        registrosPorFecha.forEach { (fecha, _) ->
            val ventas = calcularVentasDelDiaParaTendencias(fecha, registros)
            if (ventas.isNotEmpty()) {
                val diaSemana = obtenerDiaSemana(fecha)
                ventasPorDia[diaSemana] = ventas
            }
        }
        
        diasSemana.forEach { dia ->
            val ventasDelDia = ventasPorDia[dia] ?: emptyMap()
            if (ventasDelDia.isNotEmpty()) {
                ventasDelDia.forEach { (producto, _) ->
                    val fechasDelDia = registrosPorFecha.keys.filter { obtenerDiaSemana(it) == dia }
                    val ventasProducto = mutableListOf<Int>()
                    
                    fechasDelDia.forEach { fecha ->
                        val ventasFecha = calcularVentasDelDiaParaTendencias(fecha, registros)
                        ventasFecha[producto]?.let { ventasProducto.add(it) }
                    }
                    
                    val ventasConDatos = ventasProducto.filter { it > 0 }
                    if (ventasConDatos.isNotEmpty()) {
                        val promedioReal = ventasConDatos.sum().toDouble() / ventasConDatos.size
                        promediosPorDia[dia]?.set(producto, promedioReal)
                    } else {
                        promediosPorDia[dia]?.set(producto, 0.0)
                    }
                }
            }
        }
        
        return promediosPorDia
    }
    
    /**
     * Calcula las ventas de un día específico (simplificado para tendencias)
     */
    private fun calcularVentasDelDiaParaTendencias(fecha: String, todosRegistros: List<RegistroInventario>): Map<String, Int> {
        val ventas = mutableMapOf<String, Int>()
        
        val registrosDelDia = todosRegistros.filter { it.fecha == fecha }
        val ingreso = registrosDelDia.filter { it.tipo == "Ingreso de Mercadería" }.flatMap { it.productos }
        val stockFinal = registrosDelDia.filter { it.tipo == "Stock Final" }.flatMap { it.productos }
        
        val fechaAnterior = obtenerFechaAnterior(fecha)
        val stockAnterior = todosRegistros.filter { it.fecha == fechaAnterior && it.tipo == "Stock Final" }.flatMap { it.productos }
        
        if (stockFinal.isEmpty()) {
            return ventas
        }
        
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
     * Calcula y guarda las tendencias usando los datos históricos que ya están cargados
     * Esto es mucho más eficiente que cargar los datos nuevamente
     */
    private suspend fun calcularYGuardarTendenciasConDatos(
        registros: List<RegistroInventario>,
        promediosPorDia: Map<String, Map<String, Double>>
    ) = withContext(Dispatchers.IO) {
        try {
            val fechaHoy = DateHelper.getFechaActual()
            Log.d("TENDENCIAS", "📅 Fecha hoy: $fechaHoy")
            Log.d("TENDENCIAS", "📊 Registros disponibles: ${registros.size}")
            Log.d("TENDENCIAS", "📊 Días con promedios: ${promediosPorDia.size}")
            
            // Lista de productos para calcular tendencias
            val productos = listOf(
                "JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH",
                "MUZZARE", "JAMON", "PEPPERO",
                "BATATA", "MEMBRIL",
                "MEDIALUNA_MANTECA", "MEDIALUNA_GRASA", "CHIPA", "CRIOLLITO"
            )
            
            val tendencias = mutableMapOf<String, Double>()
            
            productos.forEach { codigo ->
                try {
                    val tendencia = calcularTendenciaProductoConDatos(codigo, registros, promediosPorDia)
                    tendencias[codigo] = tendencia
                    if (tendencia != 0.0) {
                        Log.d("TENDENCIAS", "  ✅ $codigo: ${String.format("%.1f", tendencia)}%")
                    }
                } catch (e: Exception) {
                    Log.w("TENDENCIAS", "  ⚠️ Error en $codigo: ${e.message}")
                    tendencias[codigo] = 0.0
                }
            }
            
            Log.d("TENDENCIAS", "📊 Resumen: ${tendencias.filter { it.value != 0.0 }.size} productos con tendencia de ${tendencias.size} totales")
            
            // Guardar en Firebase
            try {
                val firestore = FirebaseRegionManager.getFirestore()
                val timestamp = Timestamp.now()
                val datosParaGuardar = hashMapOf<String, Any>()
                datosParaGuardar.putAll(tendencias)
                datosParaGuardar["fechaActualizacion"] = timestamp
                
                Log.d("TENDENCIAS", "💾 Guardando tendencias en Firebase...")
                firestore
                    .collection("locales")
                    .document(localId)
                    .collection("cache_tendencias")
                    .document("tendencias")
                    .set(datosParaGuardar)
                    .await()
                
                Log.d("TENDENCIAS", "✅ Tendencias guardadas exitosamente en Firebase")
                Log.d("TENDENCIAS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            } catch (e: Exception) {
                Log.e("TENDENCIAS", "❌ Error guardando tendencias en Firebase: ${e.message}")
                e.printStackTrace()
            }
            
        } catch (e: Exception) {
            Log.e("TENDENCIAS", "❌ Error calculando tendencias: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Calcula la tendencia de un producto usando los datos que ya están en memoria
     */
    private fun calcularTendenciaProductoConDatos(
        codigo: String,
        registros: List<RegistroInventario>,
        promediosPorDia: Map<String, Map<String, Double>>
    ): Double {
        try {
            if (promediosPorDia.isEmpty()) {
                return 0.0
            }
            
            val fechaHoy = DateHelper.getFechaActual()
            val calendar = Calendar.getInstance()
            val diferencias = mutableListOf<Double>()
            var diasConDatos = 0
            
            // Analizar los últimos 7 días
            for (i in 1..7) {
                try {
                    calendar.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaHoy) ?: continue
                    calendar.add(Calendar.DAY_OF_YEAR, -i)
                    val fecha = DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
                    
                    val diaSemana = calendar.get(Calendar.DAY_OF_WEEK)
                    val nombreDia = obtenerNombreDiaParaTendencia(diaSemana)
                    
                    // Obtener promedio histórico
                    val promediosDelDia = promediosPorDia[nombreDia] ?: continue
                    val promedioHistorico = promediosDelDia.entries.find { 
                        it.key.startsWith("$codigo ") || it.key.startsWith("$codigo-") || it.key == codigo 
                    }?.value ?: continue
                    
                    if (promedioHistorico <= 0) continue
                    
                    // Calcular venta real del día
                    val fechaAnterior = obtenerFechaAnterior(fecha)
                    
                    val stockAnterior = registros
                        .find { it.fecha == fechaAnterior && it.tipo == "Stock Final" }
                        ?.productos?.find { it.nombre.startsWith("$codigo ") || it.nombre.startsWith("$codigo-") }
                        ?.cantidad ?: 0
                    
                    val ingreso = registros
                        .find { it.fecha == fecha && it.tipo == "Ingreso de Mercadería" }
                        ?.productos?.find { it.nombre.startsWith("$codigo ") || it.nombre.startsWith("$codigo-") }
                        ?.cantidad ?: 0
                    
                    val stockFinal = registros
                        .find { it.fecha == fecha && it.tipo == "Stock Final" }
                        ?.productos?.find { it.nombre.startsWith("$codigo ") || it.nombre.startsWith("$codigo-") }
                        ?.cantidad ?: 0
                    
                    val ventaReal = stockAnterior + ingreso - stockFinal
                    if (ventaReal <= 0) continue
                    
                    // Calcular diferencia porcentual
                    val diferenciaPorcentual = ((ventaReal - promedioHistorico) / promedioHistorico) * 100
                    diferencias.add(diferenciaPorcentual)
                    diasConDatos++
                    
                } catch (e: Exception) {
                    // Continuar con el siguiente día
                }
            }
            
            if (diferencias.isEmpty()) return 0.0
            
            val tendenciaPromedio = diferencias.average()
            
            // Solo retornar si la tendencia es significativa (>5% o <-5%)
            return if (kotlin.math.abs(tendenciaPromedio) < 5.0) 0.0 else tendenciaPromedio
            
        } catch (e: Exception) {
            return 0.0
        }
    }
    
    /**
     * Obtiene el nombre del día de la semana para las tendencias
     */
    private fun obtenerNombreDiaParaTendencia(diaSemana: Int): String {
        return when (diaSemana) {
            Calendar.SUNDAY -> "DOMINGO"
            Calendar.MONDAY -> "LUNES"
            Calendar.TUESDAY -> "MARTES"
            Calendar.WEDNESDAY -> "MIERCOLES"
            Calendar.THURSDAY -> "JUEVES"
            Calendar.FRIDAY -> "VIERNES"
            Calendar.SATURDAY -> "SABADO"
            else -> "LUNES"
        }
    }

    /**
     * Obtiene la fecha anterior (día antes) en formato yyyy-MM-dd
     */
    private fun obtenerFechaAnterior(fecha: String): String {
        return try {
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val date = formato.parse(fecha) ?: Date()
            val calendar = Calendar.getInstance()
            calendar.time = date
            calendar.add(Calendar.DAY_OF_MONTH, -1)
            formato.format(calendar.time)
        } catch (e: Exception) {
            Log.e("CargaActivity", "Error obteniendo fecha anterior: ${e.message}")
            fecha
        }
    }

    /**
     * Obtiene el día de la semana de una fecha en formato yyyy-MM-dd
     */
    private fun obtenerDiaSemana(fecha: String): String {
        return try {
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            val date = formato.parse(fecha) ?: Date()
            val calendar = Calendar.getInstance()
            calendar.time = date
            val diaSemana = calendar.get(Calendar.DAY_OF_WEEK)
            when (diaSemana) {
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
            Log.e("CargaActivity", "Error obteniendo día de la semana: ${e.message}")
            "LUNES"
        }
    }

}
