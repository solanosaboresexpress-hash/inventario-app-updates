package com.tuapp.inventario

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.tuapp.inventario.model.Local
import com.tuapp.inventario.model.UsuarioActual
import com.tuapp.inventario.model.UsuarioLocal
import com.tuapp.inventario.model.Supervisor
import com.tuapp.inventario.model.Region
import com.tuapp.inventario.update.UpdateDialog
import com.tuapp.inventario.util.DateHelper
import com.tuapp.inventario.utils.FooterHelper
import com.tuapp.inventario.utils.FirebaseRegionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class LoginActivity : AppCompatActivity() {

    private lateinit var spinnerRegion: AutoCompleteTextView
    private lateinit var spinnerLocales: AutoCompleteTextView
    private lateinit var edtContraseña: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnGestionLocales: Button
    private lateinit var txtEstado: TextView

    // Overlay views
    private lateinit var offlineOverlay: FrameLayout
    private lateinit var progressSkeleton: ProgressBar
    private lateinit var txtOfflineMsg: TextView

    private var firestore: FirebaseFirestore? = null
    private val locales = mutableListOf<Local>()
    private lateinit var updateDialog: UpdateDialog
    private var localSeleccionado: Local? = null
    private var regionSeleccionada: Region? = null
    private val regiones = mutableListOf<Region>()

    // NetworkCallback
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isCurrentlyOnline = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        initViews()
        setupListeners()
        
        // Configurar marca de agua según modo oscuro
        configurarMarcaDeAgua()

        // Configurar footer del desarrollador
        FooterHelper.configurarFooter(this)

        // Inicializar ConnectivityManager y callback
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallback()

        // Cargar regiones disponibles
        cargarRegiones()
        
        // Intentar cargar región guardada
        val regionGuardada = FirebaseRegionManager.cargarRegionActual(this)
        val regionIdGuardada = getSharedPreferences("firebase_region_prefs", MODE_PRIVATE)
            .getString("current_region_id", null)
        
        // Verificar si hay un usuario logueado y si la región cambió
        val prefsUsuario = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        val regionIdUsuario = prefsUsuario.getString("region_id", null)
        
        // Si la región cambió desde la última sesión, limpiar datos locales
        if (regionIdUsuario != null && regionIdGuardada != null && regionIdUsuario != regionIdGuardada) {
            Log.d("LoginActivity", "🔄 Región cambió de $regionIdUsuario a $regionIdGuardada, limpiando datos locales...")
            lifecycleScope.launch {
                try {
                    val localRepo = (application as InventarioApplication).localRepository
                    localRepo.limpiarTodosLosDatos()
                    // Limpiar también el region_id guardado del usuario
                    prefsUsuario.edit().remove("region_id").apply()
                    Log.d("LoginActivity", "✅ Datos locales limpiados por cambio de región")
                } catch (e: Exception) {
                    Log.w("LoginActivity", "⚠️ Error limpiando datos: ${e.message}")
                }
            }
        }
        
        if (regionGuardada != null) {
            seleccionarRegion(regionGuardada)
        }

        // Primero mostrar overlay si no hay internet
        if (!isOnline()) {
            showOfflineOverlay("Sin conexión. Verifique su red")
        } else {
            hideOfflineOverlay()
        }

        // Inicializar sistema de actualizaciones
        updateDialog = UpdateDialog(this)
        updateDialog.initialize()

        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000)
            updateDialog.checkForUpdates(this@LoginActivity)
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Configurar marca de agua según modo oscuro (por si cambió mientras la app estaba en segundo plano)
        configurarMarcaDeAgua()
        
        // Recargar locales si estamos online
        if (isOnline()) {
            cargarLocales()
        } else {
            txtEstado.text = "Sin conexión"
        }

        updateDialog.checkForUpdateAfterInstallation(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCallback()
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            com.tuapp.inventario.update.ApkInstaller.REQUEST_INSTALL_PACKAGES -> {
                Log.d("LoginActivity", "🔧 Resultado de solicitud de permisos de instalación: $resultCode")
                if (resultCode == Activity.RESULT_OK) {
                    Log.d("LoginActivity", "✅ Permisos de instalación otorgados, reintentando instalación...")
                    updateDialog.retryInstallation()
                } else {
                    Log.d("LoginActivity", "❌ Permisos de instalación denegados")
                    Toast.makeText(this, "Se requieren permisos de instalación para actualizar la aplicación", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun initViews() {
        spinnerRegion = findViewById(R.id.spinnerRegion)
        spinnerLocales = findViewById(R.id.spinnerLocales)
        edtContraseña = findViewById(R.id.edtContraseña)
        btnLogin = findViewById(R.id.btnLogin)
        btnGestionLocales = findViewById(R.id.btnGestionLocales)
        txtEstado = findViewById(R.id.txtEstado)

        // overlay views (asegurate de haberlas agregado en el XML)
        offlineOverlay = findViewById(R.id.offlineOverlay)
        progressSkeleton = findViewById(R.id.progressSkeleton)
        txtOfflineMsg = findViewById(R.id.txtOfflineMsg)

        // Configurar AutoCompleteTextView para que muestren el dropdown correctamente
        configurarAutoCompleteTextView(spinnerRegion)
        configurarAutoCompleteTextView(spinnerLocales)

        cargarCredencialesGuardadas()
    }
    
    /**
     * Configura un AutoCompleteTextView para que muestre el dropdown correctamente
     */
    private fun configurarAutoCompleteTextView(autoCompleteTextView: AutoCompleteTextView) {
        // Threshold 0 = mostrar dropdown inmediatamente al hacer click
        autoCompleteTextView.threshold = 0
        
        // Habilitar el campo
        autoCompleteTextView.isEnabled = true
        autoCompleteTextView.isClickable = true
        autoCompleteTextView.isFocusable = true
        autoCompleteTextView.isFocusableInTouchMode = true
        
        // Verificar si el dropdown está abierto usando reflexión (no hay método público directo)
        fun isDropDownShowing(): Boolean {
            return try {
                val method = AutoCompleteTextView::class.java.getDeclaredMethod("isPopupShowing")
                method.isAccessible = true
                method.invoke(autoCompleteTextView) as Boolean
            } catch (e: Exception) {
                false // Si no se puede verificar, asumir que está cerrado
            }
        }
        
        // Mostrar/ocultar dropdown cuando se hace click
        autoCompleteTextView.setOnClickListener {
            if (isDropDownShowing()) {
                // Si está abierto, cerrarlo
                autoCompleteTextView.dismissDropDown()
            } else {
                // Si está cerrado, abrirlo
                autoCompleteTextView.showDropDown()
            }
        }
        
        // Mostrar dropdown cuando se enfoca (solo si está cerrado)
        autoCompleteTextView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !isDropDownShowing()) {
                autoCompleteTextView.showDropDown()
            }
        }
    }

    private fun setupListeners() {
        btnLogin.setOnClickListener {
            // Si estamos offline no permitir intentar login que haga llamadas a la red
            if (!isOnline()) {
                txtEstado.text = "No hay internet. Intenta reconectar."
                showOfflineOverlay("Sin conexión. Reconectando...")
                return@setOnClickListener
            }
            realizarLogin()
        }

        btnGestionLocales.setOnClickListener {
            // bloquear gestión si no hay internet (puedes permitir gestión local si tenés cachés)
            if (!isOnline()) {
                txtEstado.text = "No hay internet. No se puede gestionar locales."
                showOfflineOverlay("Sin conexión. Reconectando...")
                return@setOnClickListener
            }
            mostrarDialogoContraseñaGestion()
        }

        spinnerLocales.setOnItemClickListener { _, _, position, _ ->
            localSeleccionado = locales[position]
            txtEstado.text = "Local seleccionado: ${localSeleccionado?.nombre}"
        }
        
        spinnerRegion.setOnItemClickListener { _, _, position, _ ->
            val region = regiones[position]
            seleccionarRegion(region)
        }
    }
    
    /**
     * Carga las regiones disponibles desde assets/regiones.json
     * y carga los nombres de los maestros desde Firebase para cada región
     */
    private fun cargarRegiones() {
        lifecycleScope.launch {
            try {
                regiones.clear()
                regiones.addAll(FirebaseRegionManager.cargarRegiones(this@LoginActivity))
                
                // Primero mostrar nombres de fallback (maestroNombre del JSON o "Maestro")
                val nombresMaestrosIniciales = regiones.map { it.maestroNombre }
                val adapter = ArrayAdapter(this@LoginActivity, R.layout.dropdown_item_custom, nombresMaestrosIniciales)
                spinnerRegion.setAdapter(adapter)
                
                // ✅ FIX: Usar siempre el nombre del JSON como fuente de verdad
                // El nombre del maestro debe venir del JSON, no de Firebase
                // Firebase puede tener nombres diferentes (por ejemplo, si se cambió el maestro)
                val nombresMaestros = regiones.map { region ->
                    // Si el JSON tiene un nombre de maestro definido, usarlo
                    // Si está vacío, intentar cargarlo desde Firebase como fallback
                    if (region.maestroNombre.isNotEmpty()) {
                        Log.d("LoginActivity", "📋 Región ${region.nombre} - Usando nombre del JSON: ${region.maestroNombre}")
                        region.maestroNombre
                    } else {
                        // Solo si el JSON no tiene nombre, intentar cargarlo desde Firebase
                        try {
                            val tempFirestore = if (region.googleServicesJson == "google-services") {
                                try {
                                    FirebaseFirestore.getInstance()
                                } catch (e: Exception) {
                                    null
                                }
                            } else {
                                val options = cargarFirebaseOptionsTemporal(region.googleServicesJson)
                                val appName = "temp_${region.id}"
                                try {
                                    FirebaseApp.getInstance(appName).delete()
                                } catch (e: Exception) {
                                    // Ignorar si no existe
                                }
                                val tempApp = FirebaseApp.initializeApp(this@LoginActivity, options, appName)
                                FirebaseFirestore.getInstance(tempApp)
                            }
                            
                            if (tempFirestore != null) {
                                val configDoc = tempFirestore.collection("configuracion")
                                    .document("admin")
                                    .get()
                                    .await()
                                val nombre = configDoc.getString("maestro_nombre") ?: "Maestro"
                                
                                // Limpiar instancia temporal de Firebase
                                if (region.googleServicesJson != "google-services") {
                                    try {
                                        FirebaseApp.getInstance("temp_${region.id}").delete()
                                    } catch (e: Exception) {
                                        // Ignorar
                                    }
                                }
                                
                                Log.d("LoginActivity", "📦 Región ${region.nombre} - Cargado desde Firebase: $nombre")
                                nombre
                            } else {
                                "Maestro"
                            }
                        } catch (e: Exception) {
                            Log.w("LoginActivity", "⚠️ No se pudo cargar nombre del maestro para ${region.nombre}, usando fallback: ${e.message}")
                            "Maestro"
                        }
                    }
                }
                
                // ✅ FIX: Limpiar cache de nombres de maestros para evitar datos incorrectos
                val prefsRegiones = getSharedPreferences("regiones_cache", MODE_PRIVATE)
                prefsRegiones.edit().clear().apply()
                Log.d("LoginActivity", "🗑️ Cache de nombres de maestros limpiado (usando nombres del JSON)")
                
                // Actualizar adapter con nombres reales de maestros
                val adapterActualizado = ArrayAdapter(this@LoginActivity, R.layout.dropdown_item_custom, nombresMaestros)
                spinnerRegion.setAdapter(adapterActualizado)
                
                // Asegurar que el dropdown se muestre correctamente después de actualizar el adapter
                runOnUiThread {
                    configurarAutoCompleteTextView(spinnerRegion)
                }
                
                // Si hay una región guardada, seleccionarla
                val regionGuardada = FirebaseRegionManager.cargarRegionActual(this@LoginActivity)
                if (regionGuardada != null) {
                    val index = regiones.indexOfFirst { it.id == regionGuardada.id }
                    if (index >= 0 && index < nombresMaestros.size) {
                        spinnerRegion.setText(nombresMaestros[index], false)
                        seleccionarRegion(regiones[index])
                    }
                } else if (regiones.isNotEmpty()) {
                    // Por defecto, seleccionar la primera región
                    if (nombresMaestros.isNotEmpty()) {
                        spinnerRegion.setText(nombresMaestros[0], false)
                        seleccionarRegion(regiones[0])
                    }
                }
                
                Log.d("LoginActivity", "✅ ${regiones.size} regiones cargadas con nombres de maestros")
            } catch (e: Exception) {
                Log.e("LoginActivity", "❌ Error cargando regiones", e)
                txtEstado.text = "Error cargando regiones: ${e.message}"
            }
        }
    }
    
    /**
     * Carga temporalmente FirebaseOptions para una región específica
     * (función helper para cargar nombres de maestros)
     */
    private suspend fun cargarFirebaseOptionsTemporal(jsonFileName: String): com.google.firebase.FirebaseOptions {
        val resourceId = resources.getIdentifier(
            jsonFileName,
            "raw",
            packageName
        )

        if (resourceId == 0) {
            throw IllegalArgumentException("No se encontró el archivo $jsonFileName en res/raw/")
        }

        val inputStream = resources.openRawResource(resourceId)
        val jsonString = inputStream.bufferedReader().use { it.readText() }

        val jsonObject = org.json.JSONObject(jsonString)

        val projectInfo = jsonObject.getJSONObject("project_info")
        val client = jsonObject.getJSONArray("client").getJSONObject(0)
        val clientInfo = client.getJSONObject("client_info")
        val apiKey = client.getJSONArray("api_key").getJSONObject(0)

        return com.google.firebase.FirebaseOptions.Builder()
            .setProjectId(projectInfo.getString("project_id"))
            .setApplicationId(clientInfo.getString("mobilesdk_app_id"))
            .setApiKey(apiKey.getString("current_key"))
            .setDatabaseUrl("https://${projectInfo.getString("project_id")}.firebaseio.com")
            .setStorageBucket(projectInfo.getString("storage_bucket"))
            .build()
    }
    
    /**
     * Selecciona una región e inicializa Firebase
     */
    private fun seleccionarRegion(region: Region) {
        lifecycleScope.launch {
            try {
                txtEstado.text = "Inicializando región: ${region.nombre}..."
                
                // Limpiar locales y datos de la región anterior
                locales.clear()
                localSeleccionado = null
                mostrarLocales() // Actualizar UI para mostrar lista vacía
                
                // ✅ FIX: Limpiar cache de locales de SharedPreferences al cambiar de región
                val prefsCache = getSharedPreferences("locales_cache", MODE_PRIVATE)
                prefsCache.edit().clear().apply()
                Log.d("LoginActivity", "🗑️ Cache de locales limpiado al cambiar de región")
                
                // Limpiar TODOS los datos locales para evitar mostrar datos de otra región
                try {
                    val localRepo = (application as InventarioApplication).localRepository
                    localRepo.limpiarTodosLosDatos()
                    Log.d("LoginActivity", "✅ Datos locales limpiados al cambiar de región")
                } catch (e: Exception) {
                    Log.w("LoginActivity", "⚠️ Error limpiando datos locales: ${e.message}")
                }
                
                // Inicializar Firebase con la región seleccionada
                val exito = FirebaseRegionManager.initializeFirebase(this@LoginActivity, region)
                
                if (exito) {
                    regionSeleccionada = region
                    firestore = FirebaseRegionManager.getFirestore()
                    
                    // Guardar region_id en usuario_actual para verificar cambios futuros
                    getSharedPreferences("usuario_actual", MODE_PRIVATE)
                        .edit()
                        .putString("region_id", region.id)
                        .apply()
                    
                    Log.d("LoginActivity", "✅ Región ${region.nombre} inicializada correctamente")
                    Log.d("LoginActivity", "📊 Proyecto Firebase: ${region.proyectoFirebaseId}")
                    txtEstado.text = "Región: ${region.nombre} - Cargando locales..."
                    
                    // Cargar locales de la nueva región
                    if (isOnline()) {
                        cargarLocales()
                    } else {
                        txtEstado.text = "Región: ${region.nombre} - Sin conexión"
                    }
                } else {
                    txtEstado.text = "Error inicializando región: ${region.nombre}"
                    Toast.makeText(this@LoginActivity, "Error inicializando región: ${region.nombre}. Verifica la configuración.", Toast.LENGTH_LONG).show()
                    Log.e("LoginActivity", "❌ No se pudo inicializar Firebase para región: ${region.nombre}")
                }
            } catch (e: Exception) {
                // Ignorar JobCancellationException si la Activity ya no está activa
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d("LoginActivity", "ℹ️ Selección de región cancelada (Activity destruida)")
                    return@launch
                }
                
                // Verificar si la Activity todavía está activa antes de mostrar errores
                if (isFinishing || isDestroyed) {
                    Log.d("LoginActivity", "ℹ️ Activity destruida, ignorando error: ${e.message}")
                    return@launch
                }
                
                Log.e("LoginActivity", "❌ Error seleccionando región: ${region.nombre}", e)
                txtEstado.text = "Error: ${e.message}"
                Toast.makeText(this@LoginActivity, "Error seleccionando región: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cargarLocales() {
        lifecycleScope.launch {
            try {
                if (!isOnline()) {
                    txtEstado.text = "Sin conexión - no se pueden cargar locales"
                    showOfflineOverlay("Sin conexión. Esperando reconexión...")
                    return@launch
                }

                val regionActual = regionSeleccionada
                if (regionActual == null) {
                    txtEstado.text = "Selecciona una región primero"
                    return@launch
                }

                val firestoreInstance = firestore 
                    ?: throw IllegalStateException("Firebase no está inicializado. Selecciona una región primero.")
                
                // ✅ OPTIMIZACIÓN: Cache inteligente de locales
                val prefs = getSharedPreferences("locales_cache", MODE_PRIVATE)
                val cacheKey = "locales_${regionActual.id}"
                val cacheCountKey = "${cacheKey}_count"
                val cacheIdsKey = "${cacheKey}_ids"
                val cacheTimestampKey = "${cacheKey}_timestamp"
                
                val cacheCount = prefs.getInt(cacheCountKey, -1)
                val cacheIds = prefs.getStringSet(cacheIdsKey, null) ?: emptySet()
                val cacheTimestamp = prefs.getLong(cacheTimestampKey, 0)
                val cacheDuration = 30 * 60 * 1000L // 30 minutos
                val isCacheValid = (System.currentTimeMillis() - cacheTimestamp) < cacheDuration
                
                Log.d("LoginActivity", "🔍 Verificando cache de locales para región: ${regionActual.nombre}")
                Log.d("LoginActivity", "   Cache count: $cacheCount, Cache válido: $isCacheValid, Cache IDs: ${cacheIds.size}")
                
                // Primero, obtener solo los IDs de locales activos (más eficiente que leer todos los datos)
                txtEstado.text = "Verificando locales de ${regionActual.nombre}..."
                
                // ✅ OPTIMIZACIÓN: Consultar solo IDs primero (más rápido que leer todos los datos)
                val snapshotIds = firestoreInstance.collection("locales")
                    .whereEqualTo("activo", true)
                    .get()
                    .await()
                
                val idsActuales = snapshotIds.documents.map { it.id }.toSet()
                val countActual = idsActuales.size
                
                Log.d("LoginActivity", "📊 Locales en Firebase para región ${regionActual.nombre} (ID: ${regionActual.id}): $countActual (Cache: $cacheCount)")
                Log.d("LoginActivity", "   IDs actuales: ${idsActuales.joinToString(", ")}")
                Log.d("LoginActivity", "   IDs en cache: ${cacheIds.joinToString(", ")}")
                Log.d("LoginActivity", "   Proyecto Firebase: ${regionActual.proyectoFirebaseId}")
                
                // ✅ FIX: NO usar cache si la lista de locales está vacía (cambió de región)
                // Si el conteo es igual, los IDs son iguales y el cache es válido, usar datos en memoria
                // SOLO si la lista de locales NO está vacía (no cambió de región recientemente)
                if (countActual == cacheCount && isCacheValid && cacheIds == idsActuales && locales.isNotEmpty()) {
                    Log.d("LoginActivity", "✅ Usando locales en memoria (misma cantidad e IDs, cache válido)")
                    mostrarLocales()
                    cargarCredencialesGuardadas()
                    txtEstado.text = "${locales.size} locales cargados desde cache"
                    hideOfflineOverlay()
                    return@launch
                } else if (locales.isEmpty() && isCacheValid) {
                    // Si la lista está vacía pero hay cache, limpiar cache y forzar descarga
                    Log.d("LoginActivity", "⚠️ Lista de locales vacía pero hay cache - limpiando cache y forzando descarga")
                    prefs.edit().remove(cacheCountKey).remove(cacheIdsKey).remove(cacheTimestampKey).apply()
                }
                
                // Si hay más locales o el cache expiró, descargar solo los nuevos
                val idsNuevos = if (cacheIds.isNotEmpty() && isCacheValid && countActual >= cacheCount) {
                    idsActuales - cacheIds // Solo los que no están en cache
                } else {
                    idsActuales // Si no hay cache o hay menos, descargar todos
                }
                
                val necesitaDescargarTodos = idsNuevos.size == countActual || cacheIds.isEmpty() || !isCacheValid
                
                Log.d("LoginActivity", "📦 ${if (necesitaDescargarTodos) "Descargando todos" else "Descargando solo nuevos"} locales: ${idsNuevos.size} de $countActual")
                txtEstado.text = "Cargando locales de ${regionActual.nombre}..."
                showOfflineOverlay("Cargando...")
                
                val localesNuevos = mutableListOf<Local>()
                
                if (necesitaDescargarTodos) {
                    // Descargar todos los locales (primera vez, cache expirado, o cantidad cambió)
                    snapshotIds.documents.forEach { document ->
                        val local = document.toObject(Local::class.java)?.copy(id = document.id)
                        if (local != null) {
                            localesNuevos.add(local.copy(usuarios = local.usuarios))
                        }
                    }
                } else {
                    // Solo descargar los locales nuevos
                    idsNuevos.forEach { localId ->
                        val document = firestoreInstance.collection("locales")
                            .document(localId)
                            .get()
                            .await()
                        
                        if (document.exists()) {
                            val local = document.toObject(Local::class.java)?.copy(id = document.id)
                            if (local != null) {
                                localesNuevos.add(local.copy(usuarios = local.usuarios))
                            }
                        }
                    }
                    
                    // Agregar locales existentes que ya están en memoria (si los hay)
                    if (locales.isNotEmpty() && cacheIds == idsActuales.minus(idsNuevos)) {
                        localesNuevos.addAll(locales.filter { it.id in cacheIds && it.id !in idsNuevos })
                    }
                }

                // Actualizar cache con conteo e IDs
                prefs.edit()
                    .putInt(cacheCountKey, countActual)
                    .putStringSet(cacheIdsKey, idsActuales)
                    .putLong(cacheTimestampKey, System.currentTimeMillis())
                    .apply()
                
                Log.d("LoginActivity", "💾 Cache de locales actualizado: $countActual locales, ${idsNuevos.size} nuevos descargados")

                // Limpiar locales anteriores y agregar los nuevos
                locales.clear()
                locales.addAll(localesNuevos)
                Log.d("LoginActivity", "✅ Locales cargados: ${locales.size} para región: ${regionSeleccionada?.nombre}")

                if (locales.isEmpty()) {
                    Log.d("LoginActivity", "⚠️ No hay locales en esta región, creando local por defecto...")
                    crearLocalPorDefecto()
                } else {
                    mostrarLocales()
                    cargarCredencialesGuardadas()
                    txtEstado.text = "${locales.size} locales cargados de ${regionSeleccionada?.nombre}"
                }
            } catch (e: Exception) {
                // Ignorar JobCancellationException si la Activity ya no está activa (usuario navegó a otra pantalla)
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d("LoginActivity", "ℹ️ Carga de locales cancelada (Activity destruida o navegación)")
                    return@launch
                }
                
                // Verificar si la Activity todavía está activa antes de mostrar errores
                if (isFinishing || isDestroyed) {
                    Log.d("LoginActivity", "ℹ️ Activity destruida, ignorando error: ${e.message}")
                    return@launch
                }
                
                Log.e("LoginActivity", "❌ Error cargando locales para región: ${regionSeleccionada?.nombre}", e)
                val errorMsg = when {
                    e.message?.contains("PERMISSION_DENIED") == true -> 
                        "Error de permisos en Firestore. Verifica las reglas de seguridad en Firebase Console para el proyecto ${regionSeleccionada?.proyectoFirebaseId}"
                    e.message?.contains("Missing or insufficient permissions") == true ->
                        "Permisos insuficientes. Configura las reglas de Firestore en Firebase Console."
                    else -> "Error cargando locales: ${e.message}"
                }
                txtEstado.text = errorMsg
                Toast.makeText(this@LoginActivity, errorMsg, Toast.LENGTH_LONG).show()
                // En caso de error de red/firestore
                if (!isOnline()) {
                    showOfflineOverlay("Sin conexión. Esperando reconexión...")
                } else {
                    hideOfflineOverlay()
                    crearLocalPorDefecto()
                }
            } finally {
                // Solo actualizar UI si la Activity todavía está activa
                if (!isFinishing && !isDestroyed && isOnline()) {
                    hideOfflineOverlay()
                }
            }
        }
    }

    private fun crearLocalPorDefecto() {
        lifecycleScope.launch {
            try {
                val solano = Local(
                    id = "SOLANO",
                    nombre = "SOLANO",
                    fechaCreacion = DateHelper.getFechaActual(),
                    activo = true,
                    usuarios = mapOf(
                        "admin" to UsuarioLocal(
                            id = "admin",
                            usuario = "admin",
                            contraseña = "admin123",
                            rol = "admin_local"
                        )
                    )
                )

                val firestoreInstance = firestore 
                    ?: throw IllegalStateException("Firebase no está inicializado. Selecciona una región primero.")
                
                val docRef = firestoreInstance.collection("locales").document("SOLANO")
                docRef.set(solano).await()

                locales.add(solano)
                mostrarLocales()
                Log.d("LoginActivity", "Local SOLANO creado por defecto")
            } catch (e: Exception) {
                Log.e("LoginActivity", "Error creando local por defecto", e)
                txtEstado.text = "Error creando local por defecto: ${e.message}"
            } finally {
                if (isOnline()) hideOfflineOverlay()
            }
        }
    }

    private fun mostrarLocales() {
        val nombresLocales = locales.map { it.nombre }
        val adapter = ArrayAdapter(this, R.layout.dropdown_item_custom, nombresLocales)
        spinnerLocales.setAdapter(adapter)

        // Asegurar que el dropdown se muestre correctamente
        configurarAutoCompleteTextView(spinnerLocales)

        if (locales.isNotEmpty()) {
            btnGestionLocales.visibility = android.view.View.VISIBLE
        }

        txtEstado.text = "Selecciona un local (${locales.size} disponibles)"

        if (locales.size == 1) {
            localSeleccionado = locales[0]
            spinnerLocales.setText(locales[0].nombre, false)
            txtEstado.text = "Local seleccionado: ${locales[0].nombre}"
        } else if (locales.size > 1) {
            localSeleccionado = null
            spinnerLocales.setText("", false)
            txtEstado.text = "Selecciona un local (${locales.size} disponibles)"
        }

        Log.d("LoginActivity", "Mostrando ${locales.size} locales: ${nombresLocales.joinToString(", ")}")
    }

    private fun realizarLogin() {
        val contraseña = edtContraseña.text.toString().trim()

        if (localSeleccionado == null) {
            txtEstado.text = "Selecciona un local"
            return
        }

        if (contraseña.isEmpty()) {
            txtEstado.text = "Ingrese la contraseña"
            return
        }

        lifecycleScope.launch {
            try {
                if (!isOnline()) {
                    txtEstado.text = "Sin conexión. No se puede verificar credenciales."
                    showOfflineOverlay("Sin conexión. Reconectando...")
                    return@launch
                }

                txtEstado.text = "Verificando credenciales..."
                val usuarioEncontrado = localSeleccionado?.usuarios?.values?.find {
                    it.contraseña == contraseña && it.activo
                }

                if (usuarioEncontrado != null) {
                    // 🔧 LIMPIAR métodoLogin admin123 antes de hacer login normal
                    limpiarMetodoLoginAdmin()
                    
                    val usuarioActual = UsuarioActual(
                        localId = localSeleccionado!!.id,
                        localNombre = localSeleccionado!!.nombre,
                        usuarioId = usuarioEncontrado.id,
                        usuario = usuarioEncontrado.usuario,
                        rol = usuarioEncontrado.rol
                    )

                    guardarUsuarioActual(usuarioActual)
                    guardarCredenciales(localSeleccionado!!.id, contraseña)
                    Log.d("LoginActivity", "Login exitoso: ${usuarioActual.localNombre} - ${usuarioActual.usuario}")

                    // 🔔 Programar alarma de vencimientos a las 7 AM
                    val alarmScheduler = com.tuapp.inventario.notification.AlarmScheduler(this@LoginActivity)
                    alarmScheduler.scheduleVencimientosReminder()
                    
                    // Ir a la actividad principal
                    val intent = Intent(this@LoginActivity, MainActivity::class.java)
                    startActivity(intent)
                    finish()
                } else {
                    txtEstado.text = "Credenciales incorrectas"
                    edtContraseña.error = "Contraseña incorrecta"
                }
            } catch (e: Exception) {
                Log.e("LoginActivity", "Error en login", e)
                txtEstado.text = "Error: ${e.message}"
            }
        }
    }

    private fun guardarUsuarioActual(usuario: UsuarioActual) {
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString("localId", usuario.localId)
        editor.putString("localNombre", usuario.localNombre)
        editor.putString("usuarioId", usuario.usuarioId)
        editor.putString("usuario", usuario.usuario)
        editor.putString("rol", usuario.rol)
        
        // 🔧 PRESERVAR métodoLogin si ya es admin123 (desde gestión de locales)
        val metodoLoginActual = prefs.getString("metodoLogin", "")
        Log.d("ADMIN_FLOW", "🔍 métodoLogin ANTES de guardar: '$metodoLoginActual'")
        
        if (metodoLoginActual == "admin123") {
            editor.putString("metodoLogin", "admin123") // 🔧 MANTENER admin123
            Log.d("ADMIN_FLOW", "✅ PRESERVANDO métodoLogin admin123 al seleccionar local")
        } else {
            editor.putString("metodoLogin", "local_seleccionado")
            Log.d("ADMIN_FLOW", "📝 métodoLogin normal: local_seleccionado")
        }
        
        Log.d("ADMIN_FLOW", "🏪 Guardando datos del local: ${usuario.localNombre} (ID: ${usuario.localId})")
        
        editor.apply()
    }
    
    /**
     * 🔧 NUEVA FUNCIÓN: Limpiar métodoLogin admin123 cuando se hace login normal
     */
    private fun limpiarMetodoLoginAdmin() {
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        val metodoLoginActual = prefs.getString("metodoLogin", "")
        
        if (metodoLoginActual == "admin123") {
            Log.d("ADMIN_FLOW", "🧹 LIMPIANDO métodoLogin admin123 (login normal detectado)")
            val editor = prefs.edit()
            editor.putString("metodoLogin", "local_seleccionado")
            editor.apply()
        }
    }

    private fun mostrarDialogoContraseñaGestion() {
        // Inflar el layout personalizado
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_gestion_locales_access, null)
        
        val spinnerUsuario = dialogView.findViewById<AutoCompleteTextView>(R.id.spinnerUsuario)
        val editTextPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTextPassword)
        val checkboxRecordarPassword = dialogView.findViewById<CheckBox>(R.id.checkboxRecordarPassword)
        val btnAcceder = dialogView.findViewById<Button>(R.id.btnAcceder)
        val btnCancelar = dialogView.findViewById<Button>(R.id.btnCancelar)
        
        // Crear el diálogo primero (antes de configurar el spinner)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        // Cargar usuarios disponibles en el dropdown (asíncrono)
        // Después de cargar, configurar el AutoCompleteTextView
        cargarUsuariosDisponibles(spinnerUsuario) {
            // Callback cuando los usuarios están cargados
            configurarAutoCompleteTextView(spinnerUsuario)
            // Cargar credenciales guardadas después de que el adapter esté listo
            cargarCredencialesGestionGuardadas(spinnerUsuario, editTextPassword, checkboxRecordarPassword)
        }
        
        // Configurar listeners de botones
        btnAcceder.setOnClickListener {
            val usuarioSeleccionado = spinnerUsuario.text.toString().trim()
            val contraseña = editTextPassword.text.toString().trim()
            val recordarPassword = checkboxRecordarPassword.isChecked
            
            if (usuarioSeleccionado.isEmpty()) {
                spinnerUsuario.error = "Seleccione un usuario"
                return@setOnClickListener
            }
            
            if (contraseña.isEmpty()) {
                editTextPassword.error = "Ingrese la contraseña"
                return@setOnClickListener
            }
            
            // Verificar credenciales de administrador desde Firebase
            verificarCredencialesAdmin(usuarioSeleccionado, contraseña, recordarPassword, dialog, spinnerUsuario, editTextPassword)
        }
        
        btnCancelar.setOnClickListener {
            dialog.dismiss()
        }
        
        // Mostrar el diálogo
        dialog.show()
        
        // Enfocar el campo de usuario
        spinnerUsuario.requestFocus()
    }

    private fun cargarCredencialesGuardadas() {
        val prefs = getSharedPreferences("credenciales_guardadas", MODE_PRIVATE)
        val localIdGuardado = prefs.getString("localId", "")
        val contraseñaGuardada = prefs.getString("contraseña", "")

        if (!localIdGuardado.isNullOrEmpty() && !contraseñaGuardada.isNullOrEmpty()) {
            localSeleccionado = locales.find { it.id == localIdGuardado }
            if (localSeleccionado != null) {
                spinnerLocales.setText(localSeleccionado!!.nombre, false)
                edtContraseña.setText(contraseñaGuardada)
                txtEstado.text = "✅ Credenciales cargadas automáticamente"
            }
        }
    }

    private fun guardarCredenciales(localId: String, contraseña: String) {
        val prefs = getSharedPreferences("credenciales_guardadas", MODE_PRIVATE)
        prefs.edit()
            .putString("localId", localId)
            .putString("contraseña", contraseña)
            .apply()

        Log.d("LoginActivity", "Credenciales guardadas para localId: $localId")
    }

    private fun limpiarCredencialesGuardadas() {
        val prefs = getSharedPreferences("credenciales_guardadas", MODE_PRIVATE)
        prefs.edit().clear().apply()
        Log.d("LoginActivity", "Credenciales guardadas eliminadas")
    }

    private fun guardarCredencialesGestion(usuario: String, contraseña: String) {
        val prefs = getSharedPreferences("credenciales_gestion", MODE_PRIVATE)
        prefs.edit()
            .putString("usuario", usuario)
            .putString("password", contraseña)
            .apply()
        Log.d("LoginActivity", "Credenciales de gestión guardadas")
    }

    private fun limpiarCredencialesGestion() {
        val prefs = getSharedPreferences("credenciales_gestion", MODE_PRIVATE)
        prefs.edit().clear().apply()
        Log.d("LoginActivity", "Credenciales de gestión eliminadas")
    }

    private fun cargarUsuariosDisponibles(spinnerUsuario: AutoCompleteTextView, onComplete: (() -> Unit)? = null) {
        lifecycleScope.launch {
            try {
                val usuarios = mutableListOf<String>()
                
                val firestoreInstance = firestore 
                    ?: throw IllegalStateException("Firebase no está inicializado. Selecciona una región primero.")
                
                // Agregar usuario maestro (leer desde Firebase o usar el de la región)
                val regionActual = regionSeleccionada
                val nombreMaestro = try {
                    val configDoc = firestoreInstance.collection("configuracion")
                        .document("admin")
                        .get()
                        .await()
                    configDoc.getString("maestro_nombre") 
                        ?: regionActual?.maestroNombre 
                        ?: "Maestro"
                } catch (e: Exception) {
                    Log.w("LoginActivity", "⚠️ No se pudo leer nombre del maestro, usando de región: ${e.message}")
                    regionActual?.maestroNombre ?: "Maestro"
                }
                usuarios.add(nombreMaestro)
                Log.d("LoginActivity", "👤 Usuario maestro agregado: $nombreMaestro")
                
                // Cargar supervisores desde Firebase
                
                val supervisoresSnapshot = firestoreInstance.collection("supervisores")
                    .whereEqualTo("activo", true)
                    .get()
                    .await()
                
                supervisoresSnapshot.documents.forEach { doc ->
                    val supervisor = doc.toObject(Supervisor::class.java)
                    supervisor?.let {
                        usuarios.add(it.usuario)
                    }
                }
                
                // Configurar el adapter del spinner en el hilo principal
                runOnUiThread {
                    val adapter = ArrayAdapter(this@LoginActivity, R.layout.dropdown_item_custom, usuarios)
                    spinnerUsuario.setAdapter(adapter)
                    
                    // Configurar AutoCompleteTextView después de que el adapter esté listo
                    configurarAutoCompleteTextView(spinnerUsuario)
                    
                    Log.d("LoginActivity", "✅ Usuarios cargados y adapter configurado: $usuarios")
                    
                    // Ejecutar callback si existe
                    onComplete?.invoke()
                }
                
            } catch (e: Exception) {
                Log.e("LoginActivity", "Error cargando usuarios", e)
                // En caso de error, al menos mostrar el maestro
                runOnUiThread {
                    val usuarios = listOf("Lucas PIARRISTEGUY")
                    val adapter = ArrayAdapter(this@LoginActivity, R.layout.dropdown_item_custom, usuarios)
                    spinnerUsuario.setAdapter(adapter)
                    configurarAutoCompleteTextView(spinnerUsuario)
                    onComplete?.invoke()
                }
            }
        }
    }

    private fun cargarCredencialesGestionGuardadas(spinnerUsuario: AutoCompleteTextView, editTextPassword: EditText, checkboxRecordarPassword: CheckBox) {
        val prefs = getSharedPreferences("credenciales_gestion", MODE_PRIVATE)
        val usuarioGuardado = prefs.getString("usuario", "")
        val contraseñaGuardada = prefs.getString("password", "")

        if (!usuarioGuardado.isNullOrEmpty() && !contraseñaGuardada.isNullOrEmpty()) {
            spinnerUsuario.setText(usuarioGuardado, false)
            editTextPassword.setText(contraseñaGuardada)
            checkboxRecordarPassword.isChecked = true
            Log.d("LoginActivity", "Credenciales de gestión cargadas")
        }
    }

    // ------------------------
    // NETWORK / OFFLINE HANDLING
    // ------------------------

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showOfflineOverlay(message: String) {
        runOnUiThread {
            txtOfflineMsg.text = message
            offlineOverlay.visibility = android.view.View.VISIBLE

            // deshabilitar inputs principales
            btnLogin.isEnabled = false
            btnGestionLocales.isEnabled = false
            spinnerLocales.isEnabled = false
            edtContraseña.isEnabled = false
        }
    }

    private fun hideOfflineOverlay() {
        runOnUiThread {
            offlineOverlay.visibility = android.view.View.GONE
            btnLogin.isEnabled = true
            btnGestionLocales.isEnabled = true
            spinnerLocales.isEnabled = true
            edtContraseña.isEnabled = true
        }
    }

    private fun registerNetworkCallback() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d("LoginActivity", "Network available")
                isCurrentlyOnline = true
                runOnUiThread {
                    txtEstado.text = "Conectado"
                    hideOfflineOverlay()
                    // si no cargaron locales antes, intentar
                    if (locales.isEmpty()) {
                        cargarLocales()
                    }
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d("LoginActivity", "Network lost")
                isCurrentlyOnline = false
                runOnUiThread {
                    txtEstado.text = "Sin conexión"
                    showOfflineOverlay("Sin conexión. Esperando reconexión...")
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            Log.w("LoginActivity", "Error al dar unregister del network callback: ${e.message}")
        }
    }
    
    /**
     * Verifica la contraseña de administrador o supervisor desde Firebase
     */
    private fun verificarCredencialesAdmin(usuario: String, contraseña: String, recordarPassword: Boolean, dialog: androidx.appcompat.app.AlertDialog, spinnerUsuario: AutoCompleteTextView, editTextPassword: EditText) {
        lifecycleScope.launch {
            try {
                txtEstado.text = "Verificando credenciales..."
                
                val firestoreInstance = firestore 
                    ?: throw IllegalStateException("Firebase no está inicializado. Selecciona una región primero.")
                
                // Primero verificar si es usuario maestro
                val configDoc = firestoreInstance.collection("configuracion")
                    .document("admin")
                    .get()
                    .await()
                
                val passwordMaestro = configDoc.getString("password") ?: "admin123"
                val nombreMaestro = configDoc.getString("maestro_nombre") 
                    ?: regionSeleccionada?.maestroNombre 
                    ?: "Maestro" // Fallback si no está configurado
                
                Log.d("ADMIN_FLOW", "🔍 Verificando maestro: nombre='$nombreMaestro', usuario ingresado='$usuario'")
                
                if (usuario == nombreMaestro && contraseña == passwordMaestro) {
                    // Es usuario maestro
                    Log.d("ADMIN_FLOW", "🔑 CREDENCIALES DE MAESTRO CORRECTAS: $nombreMaestro")
                    
                    // Guardar credenciales si está marcado el checkbox
                    if (recordarPassword) {
                        guardarCredencialesGestion(usuario, contraseña)
                    } else {
                        limpiarCredencialesGestion()
                    }
                    
                    // Guardar datos del usuario maestro
                    val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
                    val editor = prefs.edit()
                    editor.putString("metodoLogin", "admin123")
                    editor.putString("rol", "maestro")
                    editor.putString("usuarioId", "maestro")
                    editor.putString("usuario", "maestro")
                    editor.apply()
                    
                    Log.d("ADMIN_FLOW", "💾 Usuario maestro autenticado")

                    dialog.dismiss()
                    
                    // Ir a gestión de locales
                    val intent = Intent(this@LoginActivity, GestionLocalesActivity::class.java)
                    startActivity(intent)
                    
                    txtEstado.text = "Accediendo a gestión de locales..."
                    return@launch
                }
                
                // Si no es maestro, verificar si es supervisor
                val supervisoresSnapshot = firestoreInstance.collection("supervisores")
                    .whereEqualTo("usuario", usuario)
                    .whereEqualTo("contraseña", contraseña)
                    .whereEqualTo("activo", true)
                    .get()
                    .await()
                
                if (!supervisoresSnapshot.isEmpty) {
                    val supervisorDoc = supervisoresSnapshot.documents[0]
                    val supervisor = supervisorDoc.toObject(Supervisor::class.java)?.copy(id = supervisorDoc.id)
                    
                    if (supervisor != null) {
                        Log.d("SUPERVISOR_FLOW", "🔑 SUPERVISOR AUTENTICADO: ${supervisor.usuario}")
                        
                        // Guardar credenciales si está marcado el checkbox
                        if (recordarPassword) {
                            guardarCredencialesGestion(usuario, contraseña)
                        } else {
                            limpiarCredencialesGestion()
                        }
                        
                        // Guardar datos del supervisor
                        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
                        val editor = prefs.edit()
                        editor.putString("metodoLogin", "supervisor")
                        editor.putString("rol", "supervisor")
                        editor.putString("usuarioId", supervisor.id)
                        editor.putString("usuario", supervisor.usuario)
                        editor.putString("localesAsignados", supervisor.localesAsignados.joinToString(","))
                        editor.apply()
                        
                        Log.d("SUPERVISOR_FLOW", "💾 Supervisor autenticado: ${supervisor.usuario}")

                        dialog.dismiss()
                        
                        // Ir a gestión de locales
                        val intent = Intent(this@LoginActivity, GestionLocalesActivity::class.java)
                        startActivity(intent)
                        
                        txtEstado.text = "Accediendo a gestión de locales..."
                        return@launch
                    }
                }
                
                // Si llegamos aquí, las credenciales son incorrectas
                spinnerUsuario.error = "Usuario o contraseña incorrectos"
                editTextPassword.error = "Usuario o contraseña incorrectos"
                txtEstado.text = "Credenciales incorrectas"
                
            } catch (e: Exception) {
                Log.e("LoginActivity", "Error verificando credenciales", e)
                txtEstado.text = "Error verificando credenciales: ${e.message}"
            }
        }
    }
    
    /**
     * Verifica si el dispositivo está en modo oscuro
     */
    private fun isDarkMode(): Boolean {
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    
    /**
     * Configura la marca de agua según el modo oscuro
     * En modo oscuro: se muestra difuminada pero visible (alpha = 0.3)
     * En modo claro: se muestra con transparencia (alpha = 0.08)
     */
    private fun configurarMarcaDeAgua() {
        val imgWatermark = findViewById<ImageView>(R.id.imgWatermark)
        imgWatermark?.let {
            if (isDarkMode()) {
                // Modo oscuro: mostrar difuminada pero visible
                it.alpha = 0.3f
                Log.d("LoginActivity", "🌙 Modo oscuro: marca de agua difuminada (alpha=0.3)")
            } else {
                // Modo claro: mostrar con transparencia
                it.alpha = 0.08f
                Log.d("LoginActivity", "☀️ Modo claro: marca de agua con transparencia (alpha=0.08)")
            }
        }
    }
}
