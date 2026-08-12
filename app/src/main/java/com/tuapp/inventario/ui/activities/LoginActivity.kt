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
import android.widget.LinearLayout
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import com.tuapp.inventario.model.Local
import com.tuapp.inventario.model.UsuarioActual
import com.tuapp.inventario.model.UsuarioLocal
import com.tuapp.inventario.model.Supervisor
import com.tuapp.inventario.model.Region
import com.tuapp.inventario.update.UpdateDialog
import com.tuapp.inventario.utils.DateHelper
import com.tuapp.inventario.utils.FooterHelper
import com.tuapp.inventario.utils.FirebaseRegionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class LoginActivity : AppCompatActivity() {

    private lateinit var spinnerRegion: AutoCompleteTextView
    private lateinit var edtEmail: EditText
    private lateinit var edtcontraseña: EditText
    private lateinit var btnLogin: Button
    private lateinit var txtOlvidecontraseña: TextView
    private lateinit var checkboxRecordarCredenciales: CheckBox
    private lateinit var txtEstado: TextView

    // Overlay views
    private lateinit var offlineOverlay: FrameLayout
    private lateinit var progressSkeleton: ProgressBar
    private lateinit var txtOfflineMsg: TextView

    private var firestore: FirebaseFirestore? = null
    private lateinit var updateDialog: UpdateDialog
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
        
        // Aplicar modo claro/oscuro guardado
        aplicarModoGuardado()
        val btnToggleTheme = findViewById<ImageView>(R.id.btnToggleTheme)
        btnToggleTheme.setOnClickListener {
            val prefs = getSharedPreferences("inventario_prefs", MODE_PRIVATE)
            val isDark = isDarkMode()
            val newMode = if (isDark) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
            prefs.edit().putInt("night_mode", newMode).apply()
            AppCompatDelegate.setDefaultNightMode(newMode)
        }

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
        
        // Estado inicial
        txtEstado.text = "Seleccione una región e ingrese su email y contraseña"

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
        edtEmail = findViewById(R.id.edtEmail)
        edtcontraseña = findViewById(R.id.edtPassword)
        btnLogin = findViewById(R.id.btnLogin)
        txtOlvidecontraseña = findViewById(R.id.txtForgotPassword)
        checkboxRecordarCredenciales = findViewById(R.id.checkboxRecordarCredenciales)
        txtEstado = findViewById(R.id.txtEstado)

        // overlay views (asegurate de haberlas agregado en el XML)
        offlineOverlay = findViewById(R.id.offlineOverlay)
        progressSkeleton = findViewById(R.id.progressSkeleton)
        txtOfflineMsg = findViewById(R.id.txtOfflineMsg)

        // Configurar AutoCompleteTextView para que muestren el dropdown correctamente
        configurarAutoCompleteTextView(spinnerRegion)
        
        // Configurar el campo de email para que solo permita escribir antes del "@"
        configurarCampoEmail()
        
        // Cargar credenciales guardadas
        cargarCredencialesGuardadas()
    }
    
    /**
     * Configura el campo de email para que solo permita escribir antes del "@"
     * y agregue automáticamente "@saboresexpress.com.ar"
     */
    private fun configurarCampoEmail() {
        edtEmail.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Si el usuario intenta escribir "@", prevenir que lo escriba
                val texto = s.toString()
                if (texto.contains("@")) {
                    // Remover el "@" y todo lo que venga después
                    val textoLimpio = texto.substringBefore("@")
                    if (edtEmail.text.toString() != textoLimpio) {
                        edtEmail.setText(textoLimpio)
                        edtEmail.setSelection(textoLimpio.length)
                    }
                }
            }
            
            override fun afterTextChanged(s: Editable?) {}
        })
        
        // Agregar filtro para prevenir que se escriba "@" o el dominio
        edtEmail.filters = arrayOf(android.text.InputFilter { source, start, end, dest, dstart, dend ->
            // Si el texto contiene "@" o el dominio, no permitirlo
            val texto = source.toString()
            if (texto.contains("@") || texto.contains("saboresexpress.com.ar")) {
                return@InputFilter ""
            }
            null
        })
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

        txtOlvidecontraseña.setOnClickListener {
            mostrarDialogoResetcontraseña()
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
                                // Otras regiones: usar firebaseConfig embebido cuando no existe archivo
                                val options = if (region.googleServicesJson.isNotEmpty() && resources.getIdentifier(region.googleServicesJson, "raw", packageName) != 0) {
                                    cargarFirebaseOptionsTemporal(region.googleServicesJson)
                                } else if (region.firebaseConfig != null && region.firebaseConfig.apiKey.isNotEmpty()) {
                                    com.google.firebase.FirebaseOptions.Builder()
                                        .setProjectId(region.firebaseConfig.projectId)
                                        .setApplicationId(region.firebaseConfig.appId)
                                        .setApiKey(region.firebaseConfig.apiKey)
                                        .setDatabaseUrl("https://${region.firebaseConfig.projectId}.firebaseio.com")
                                        .setStorageBucket(region.firebaseConfig.storageBucket)
                                        .build()
                                } else null
                                
                                if (options != null) {
                                    val appName = "temp_${region.id}"
                                    try {
                                        FirebaseApp.getInstance(appName).delete()
                                    } catch (e: Exception) {
                                        // Ignorar si no existe
                                    }
                                    val tempApp = FirebaseApp.initializeApp(this@LoginActivity, options, appName)
                                    FirebaseFirestore.getInstance(tempApp)
                                } else null
                            }
                            
                            if (tempFirestore != null) {
                                val configDoc = tempFirestore.collection("configuracion")
                                    .document("admin")
                                    .get()
                                    .await()
                                val nombre = configDoc.getString("maestro_nombre")
                                    ?: region.maestroNombre  // Fallback a JSON primero
                                    ?: "Maestro"             // Fallback final
                                
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
                                region.maestroNombre ?: "Maestro"
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
                
                // Limpiar datos de la región anterior (ya no se cargan locales aquí)
                
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
                    txtEstado.text = "Región: ${region.nombre} - Ingrese su email y contraseña"
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

    // Función removida - ya no se cargan locales en el login
    // Los locales se detectan por email en realizarLogin() usando Firebase Auth
    // Esto optimiza las lecturas de Firebase: solo consulta el usuario específico que intenta loguearse

    // Funciones removidas - ya no se crean/muestran locales en el login
    // Los locales se detectan por email en realizarLogin() usando Firebase Auth

    private fun realizarLogin() {
        // Obtener el email y agregar el dominio automáticamente si no lo tiene
        var email = edtEmail.text.toString().trim()
        
        // Si el email no contiene "@", agregar el dominio automáticamente
        if (email.isNotEmpty() && !email.contains("@")) {
            email = "$email@saboresexpress.com.ar"
        }
        
        val contraseña = edtcontraseña.text.toString().trim()

        if (email.isEmpty() || email == "@saboresexpress.com.ar") {
            txtEstado.text = "Ingrese el email"
            edtEmail.error = "Email requerido"
            return
        }
        
        // Validar que el email termine con el dominio correcto
        if (!email.endsWith("@saboresexpress.com.ar")) {
            txtEstado.text = "El email debe ser @saboresexpress.com.ar"
            edtEmail.error = "Email inválido"
            return
        }

        if (contraseña.isEmpty()) {
            txtEstado.text = "Ingrese la contraseña"
            edtcontraseña.error = "contraseña requerida"
            return
        }

        if (regionSeleccionada == null) {
            txtEstado.text = "Seleccione una región primero"
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

                val firestoreInstance = firestore ?: throw IllegalStateException("Firebase no está inicializado")
                
                // Buscar el usuario en Firestore por email de forma manual
                val local = buscarLocalPorEmail(email)
                val supervisor = buscarSupervisorPorEmail(email)
                val maestro = buscarMaestroPorEmail(email)

                when {
                    maestro != null -> {
                        if (maestro.contraseña != contraseña) {
                            txtEstado.text = "contraseña incorrecta"
                            edtcontraseña.error = "contraseña incorrecta"
                            return@launch
                        }
                        
                        Log.d("LoginActivity", "🔑 Login manual como MAESTRO: ${maestro.usuario}")
                        
                        // Guardar credenciales si el checkbox está marcado
                        if (checkboxRecordarCredenciales.isChecked) {
                            guardarCredencialesLogin(email, contraseña)
                        } else {
                            limpiarCredencialesLogin()
                        }
                        
                        val usuarioActual = UsuarioActual(
                            localId = "",
                            localNombre = "",
                            usuarioId = maestro.id,
                            usuario = maestro.usuario,
                            rol = "maestro"
                        )
                        guardarUsuarioActual(usuarioActual)
                        
                        val intent = Intent(this@LoginActivity, GestionLocalesActivity::class.java)
                        startActivity(intent)
                        finish()
                    }
                    supervisor != null -> {
                        if (supervisor.contraseña != contraseña) {
                            txtEstado.text = "contraseña incorrecta"
                            edtcontraseña.error = "contraseña incorrecta"
                            return@launch
                        }
                        
                        Log.d("LoginActivity", "👤 Login manual como SUPERVISOR: ${supervisor.usuario}")
                        
                        // Guardar credenciales si el checkbox está marcado
                        if (checkboxRecordarCredenciales.isChecked) {
                            guardarCredencialesLogin(email, contraseña)
                        } else {
                            limpiarCredencialesLogin()
                        }
                        
                        val usuarioActual = UsuarioActual(
                            localId = "",
                            localNombre = "",
                            usuarioId = supervisor.id,
                            usuario = supervisor.usuario,
                            rol = "supervisor",
                            localesAsignados = supervisor.localesAsignados
                        )
                        guardarUsuarioActual(usuarioActual)
                        
                        val intent = Intent(this@LoginActivity, GestionLocalesActivity::class.java)
                        startActivity(intent)
                        finish()
                    }
                    local != null -> {
                        val usuarioAdmin = local.usuarios.values.find { it.rol == "admin_local" }
                        if (usuarioAdmin == null || usuarioAdmin.contraseña != contraseña) {
                            txtEstado.text = "contraseña incorrecta"
                            edtcontraseña.error = "contraseña incorrecta"
                            return@launch
                        }
                        
                        Log.d("LoginActivity", "🏪 Login manual como LOCAL: ${local.nombre}")
                        
                        // Guardar credenciales si el checkbox está marcado
                        if (checkboxRecordarCredenciales.isChecked) {
                            guardarCredencialesLogin(email, contraseña)
                        } else {
                            limpiarCredencialesLogin()
                        }

                        // Verificar si debe cambiar la contraseña (primer ingreso)
                        val debeCambiar = local.debeCambiarContraseña || usuarioAdmin.debeCambiarContraseña
                        
                        if (debeCambiar) {
                            Log.d("LoginActivity", "🔄 Local debe cambiar contraseña. Abriendo CambioContrasenaActivity...")
                            val intent = Intent(this@LoginActivity, CambioContrasenaActivity::class.java).apply {
                                putExtra("localId", local.id)
                                putExtra("email", email)
                                putExtra("regionId", regionSeleccionada?.id)
                            }
                            startActivity(intent)
                            finish()
                        } else {
                            val usuarioActual = UsuarioActual(
                                localId = local.id,
                                localNombre = local.nombre,
                                usuarioId = "admin_${local.id.lowercase()}",
                                usuario = "admin_${local.id.lowercase()}",
                                rol = "admin_local"
                            )
                            guardarUsuarioActual(usuarioActual)
                            
                            // Programar alarma de vencimientos
                            val alarmScheduler = com.tuapp.inventario.notification.AlarmScheduler(this@LoginActivity)
                            alarmScheduler.scheduleVencimientosReminder()
                            
                            val intent = Intent(this@LoginActivity, MainActivity::class.java)
                            startActivity(intent)
                            finish()
                        }
                    }
                    else -> {
                        txtEstado.text = "Email no registrado"
                        edtEmail.error = "Email no registrado"
                    }
                }
            } catch (e: Exception) {
                Log.e("LoginActivity", "Error en login", e)
                txtEstado.text = "Error: ${e.message}"
            }
        }
    }

    /**
     * Busca un local en Firestore por su email
     */
    private suspend fun buscarLocalPorEmail(email: String): Local? {
        return try {
            val firestoreInstance = firestore ?: return null
            val snapshot = firestoreInstance.collection("locales")
                .whereEqualTo("email", email)
                .limit(1)
                .get()
                .await()
            
            if (!snapshot.isEmpty) {
                snapshot.documents[0].toObject(Local::class.java)
                    ?.copy(id = snapshot.documents[0].id)
            } else null
        } catch (e: Exception) {
            Log.e("LoginActivity", "Error buscando local por email", e)
            null
        }
    }

    /**
     * Busca un supervisor en Firestore por su email
     */
    private suspend fun buscarSupervisorPorEmail(email: String): Supervisor? {
        return try {
            val firestoreInstance = firestore ?: return null
            val snapshot = firestoreInstance.collection("supervisores")
                .whereEqualTo("email", email)
                .limit(1)
                .get()
                .await()
            
            if (!snapshot.isEmpty) {
                snapshot.documents[0].toObject(Supervisor::class.java)
                    ?.copy(id = snapshot.documents[0].id)
            } else null
        } catch (e: Exception) {
            Log.e("LoginActivity", "Error buscando supervisor por email", e)
            null
        }
    }

    /**
     * Busca un maestro en Firestore por su email
     * El maestro se guarda en configuracion/admin
     */
    private suspend fun buscarMaestroPorEmail(email: String): com.tuapp.inventario.model.UsuarioMaestro? {
        return try {
            val firestoreInstance = firestore ?: return null
            val configDoc = firestoreInstance.collection("configuracion")
                .document("admin")
                .get()
                .await()
            
            val configEmail = configDoc.getString("email") ?: ""
            if (configEmail.lowercase() == email.lowercase()) {
                com.tuapp.inventario.model.UsuarioMaestro(
                    id = "maestro",
                    usuario = configDoc.getString("maestro_nombre") ?: "Maestro",
                    email = configEmail,
                    contraseña = configDoc.getString("password") ?: "",
                    rol = "maestro",
                    activo = true,
                    fechaCreacion = configDoc.getString("fechaCreacion") ?: "",
                    firebaseAuthUid = null
                )
            } else null
        } catch (e: Exception) {
            Log.e("LoginActivity", "Error buscando maestro por email", e)
            null
        }
    }

    /**
     * Muestra el diálogo para resetear contraseña
     */
    private fun mostrarDialogoResetcontraseña() {
        // Crear layout con TextInputLayout para mostrar el sufijo
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        
        val textInputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = "Email"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setSuffixText("@saboresexpress.com.ar")
            setSuffixTextColor(resources.getColorStateList(android.R.color.darker_gray, null))
        }
        
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "usuario"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Configurar filtro para prevenir "@" y dominio
        input.filters = arrayOf(android.text.InputFilter { source, start, end, dest, dstart, dend ->
            val texto = source.toString()
            if (texto.contains("@") || texto.contains("saboresexpress.com.ar")) {
                return@InputFilter ""
            }
            null
        })
        
        textInputLayout.addView(input)
        layout.addView(textInputLayout)
        
        AlertDialog.Builder(this)
            .setTitle("Recuperar contraseña")
            .setMessage("Se enviará un enlace a su email para restablecer la contraseña")
            .setView(layout as View)
            .setPositiveButton("Enviar") { _, _ ->
                var email = input.text.toString().trim()
                
                // Agregar dominio automáticamente si no lo tiene
                if (email.isNotEmpty() && !email.contains("@")) {
                    email = "$email@saboresexpress.com.ar"
                }
                
                if (email.isNotEmpty() && email != "@saboresexpress.com.ar") {
                    if (email.endsWith("@saboresexpress.com.ar")) {
                        enviarEmailResetcontraseña(email)
                    } else {
                        txtEstado.text = "El email debe ser @saboresexpress.com.ar"
                    }
                } else {
                    txtEstado.text = "Ingrese un email válido"
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Envía el email de reset de contraseña usando Firebase Auth
     * Intenta con todas las regiones hasta encontrar la correcta
     */
    private fun enviarEmailResetcontraseña(email: String) {
        lifecycleScope.launch {
            try {
                txtEstado.text = "Buscando cuenta en todas las regiones..."
                
                // Cargar todas las regiones disponibles
                val todasLasRegiones = FirebaseRegionManager.cargarRegiones(this@LoginActivity)
                
                // Ordenar regiones: primero la seleccionada, luego la por defecto, luego las demás
                val regionActual = this@LoginActivity.regionSeleccionada
                val regionesOrdenadas = mutableListOf<Region>()
                
                // 1. Agregar región seleccionada primero (si existe)
                if (regionActual != null) {
                    regionesOrdenadas.add(regionActual)
                }
                
                // 2. Agregar región por defecto (google-services)
                val regionPorDefecto = todasLasRegiones.find { it.googleServicesJson == "google-services" }
                if (regionPorDefecto != null && regionPorDefecto != regionActual) {
                    regionesOrdenadas.add(regionPorDefecto)
                }
                
                // 3. Agregar las demás regiones
                todasLasRegiones.forEach { region ->
                    if (region != regionActual && region != regionPorDefecto) {
                        regionesOrdenadas.add(region)
                    }
                }
                
                Log.d("LoginActivity", "🔍 Intentando reset de contraseña en ${regionesOrdenadas.size} regiones")
                
                // Intentar con cada región hasta encontrar una donde el usuario exista
                var exito = false
                var ultimoError: Exception? = null
                
                for (region in regionesOrdenadas) {
                    try {
                        Log.d("LoginActivity", "🔄 Intentando reset en región: ${region.nombre}")
                        val auth = FirebaseRegionManager.getFirebaseAuthForRegion(this@LoginActivity, region)
                        
                        if (auth != null) {
                            auth.sendPasswordResetEmail(email).await()
                            
                            // Éxito: el usuario existe en esta región
                            Log.d("LoginActivity", "✅ Email de reset enviado desde región: ${region.nombre}")
                            exito = true
                            
                            txtEstado.text = "✅ Email enviado. Revise su bandeja de entrada"
                            
                            AlertDialog.Builder(this@LoginActivity)
                                .setTitle("Email Enviado")
                                .setMessage("Se ha enviado un enlace a $email para restablecer su contraseña. Revise su bandeja de entrada.")
                                .setPositiveButton("OK", null)
                                .show()
                            
                            break
                        }
                    } catch (e: FirebaseAuthInvalidUserException) {
                        // Usuario no existe en esta región, continuar con la siguiente
                        Log.d("LoginActivity", "⚠️ Usuario no encontrado en región ${region.nombre}, intentando siguiente...")
                        ultimoError = e
                        continue
                    } catch (e: Exception) {
                        // Otro error, guardar pero continuar
                        Log.w("LoginActivity", "⚠️ Error en región ${region.nombre}: ${e.message}")
                        if (ultimoError == null) {
                            ultimoError = e
                        }
                        continue
                    }
                }
                
                // Si no se encontró el usuario en ninguna región
                if (!exito) {
                    Log.e("LoginActivity", "❌ Usuario no encontrado en ninguna región")
                    txtEstado.text = "Email no registrado"
                    AlertDialog.Builder(this@LoginActivity)
                        .setTitle("Error")
                        .setMessage("El email $email no está registrado en ninguna región del sistema.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                    
            } catch (e: Exception) {
                Log.e("LoginActivity", "Error enviando email reset", e)
                txtEstado.text = "Error: ${e.message}"
                AlertDialog.Builder(this@LoginActivity)
                    .setTitle("Error")
                    .setMessage("No se pudo enviar el email. Verifique su conexión e intente nuevamente.")
                    .setPositiveButton("OK", null)
                    .show()
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
        
        // Guardar locales asignados (importante para supervisores)
        val localesAsignadosStr = usuario.localesAsignados.joinToString(",")
        editor.putString("localesAsignados", localesAsignadosStr)
        Log.d("LoginActivity", "💾 Guardando locales asignados: $localesAsignadosStr")
        
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

    private fun mostrarDialogocontraseñaGestion() {
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

    // Función removida - ya no se cargan credenciales guardadas de locales
    // El login ahora usa email + contraseña directamente

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
            edtEmail.isEnabled = false
            edtcontraseña.isEnabled = false
        }
    }

    private fun hideOfflineOverlay() {
        runOnUiThread {
            offlineOverlay.visibility = android.view.View.GONE
            btnLogin.isEnabled = true
            edtEmail.isEnabled = true
            edtcontraseña.isEnabled = true
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
                    // Estado actualizado - ya no se cargan locales aquí
                    txtEstado.text = "Conectado - Ingrese su email y contraseña"
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
    
    private fun aplicarModoGuardado() {
        val prefs = getSharedPreferences("inventario_prefs", MODE_PRIVATE)
        val savedMode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedMode)
        actualizarIconoToggleTheme()
    }
    
    private fun actualizarIconoToggleTheme() {
        val btnToggle = findViewById<ImageView>(R.id.btnToggleTheme) ?: return
        if (isDarkMode()) {
            btnToggle.setImageResource(R.drawable.ic_sun)
        } else {
            btnToggle.setImageResource(R.drawable.ic_moon)
        }
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
    
    /**
     * Guarda las credenciales de login (email y contraseña) en SharedPreferences
     */
    private fun guardarCredencialesLogin(email: String, contraseña: String) {
        val prefs = getSharedPreferences("credenciales_login", MODE_PRIVATE)
        val editor = prefs.edit()
        
        // Guardar solo la parte antes del "@" del email
        val emailSinDominio = email.substringBefore("@")
        editor.putString("email", emailSinDominio)
        editor.putString("password", contraseña)
        editor.putBoolean("recordar", true)
        editor.apply()
        
        Log.d("LoginActivity", "✅ Credenciales guardadas")
    }
    
    /**
     * Limpia las credenciales guardadas
     */
    private fun limpiarCredencialesLogin() {
        val prefs = getSharedPreferences("credenciales_login", MODE_PRIVATE)
        val editor = prefs.edit()
        editor.remove("email")
        editor.remove("password")
        editor.putBoolean("recordar", false)
        editor.apply()
        
        Log.d("LoginActivity", "🧹 Credenciales limpiadas")
    }
    
    /**
     * Carga las credenciales guardadas y las muestra en los campos
     */
    private fun cargarCredencialesGuardadas() {
        val prefs = getSharedPreferences("credenciales_login", MODE_PRIVATE)
        val emailGuardado = prefs.getString("email", "")
        val passwordGuardado = prefs.getString("password", "")
        val recordar = prefs.getBoolean("recordar", false)
        
        if (recordar && emailGuardado?.isNotEmpty() == true) {
            // Cargar email (solo la parte antes del "@")
            edtEmail.setText(emailGuardado)
            
            // Cargar contraseña
            if (passwordGuardado?.isNotEmpty() == true) {
                edtcontraseña.setText(passwordGuardado)
            }
            
            // Marcar checkbox
            checkboxRecordarCredenciales.isChecked = true
            
            Log.d("LoginActivity", "✅ Credenciales cargadas")
        } else {
            checkboxRecordarCredenciales.isChecked = false
        }
    }
    
}
