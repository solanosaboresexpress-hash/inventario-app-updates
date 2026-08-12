package com.tuapp.inventario

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.tuapp.inventario.model.Local
import com.tuapp.inventario.model.UsuarioLocal
import com.tuapp.inventario.model.Supervisor
import com.tuapp.inventario.model.UsuarioActual
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.tasks.await
import com.tuapp.inventario.utils.DateHelper
import com.tuapp.inventario.utils.FirebaseRegionManager
import java.util.*

class GestionLocalesActivity : AppCompatActivity() {

    // Views principales
    private lateinit var btnTabLocales: Button
    private lateinit var btnTabSupervisores: Button
    private lateinit var layoutContenidoLocales: LinearLayout
    private lateinit var layoutContenidoSupervisores: LinearLayout
    
    // Views de Locales
    private lateinit var btnAgregarLocal: Button
    private lateinit var layoutLocales: LinearLayout
    private lateinit var editTextBuscar: EditText
    
    // Views de Supervisores
    private lateinit var btnAgregarSupervisor: Button
    private lateinit var layoutSupervisores: LinearLayout
    private lateinit var editTextBuscarSupervisores: EditText
    
    // Estado
    private lateinit var txtEstado: TextView
    
    // Firebase (usar FirebaseRegionManager para obtener el Firestore de la región actual)
    private val firestore: FirebaseFirestore
        get() = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
    
    // Datos
    private val locales = mutableListOf<Local>()
    private val localesFiltrados = mutableListOf<Local>()
    private val supervisores = mutableListOf<Supervisor>()
    private val supervisoresFiltrados = mutableListOf<Supervisor>()
    private val localesPorSupervisor = mutableMapOf<String, MutableList<Local>>()
    
    // Estado de la aplicación
    private var usuarioActual: UsuarioActual? = null
    private var esUsuarioMaestro = false
    private var esUsuarioAsistente = false // Indica si el usuario actual es asistente (no maestro real)
    private var uidUsuarioActual: String? = null // UID de Firebase Auth del usuario actual
    
    // TextWatchers para poder removerlos en onDestroy
    private var textWatcherLocales: TextWatcher? = null
    private var textWatcherSupervisores: TextWatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gestion_locales)

        // Cargar usuario actual y determinar tipo
        cargarUsuarioActual()
        
        initViews()
        setupListeners()
        
        // Configurar marca de agua según modo oscuro
        configurarMarcaDeAgua()
        
        findViewById<TextView>(R.id.btnGuiaAyuda)?.setOnClickListener {
            val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
            val rol = prefs.getString("rol", "") ?: ""
            com.tuapp.inventario.ui.dialogs.InteractiveGuideHelper.show(this, sectionName = "gestion-locales", rolUsuario = rol)
        }
        
        // Cargar datos según el tipo de usuario
        lifecycleScope.launch {
            // Si es supervisor, verificar si es asistente antes de cargar datos
            if (!esUsuarioMaestro && usuarioActual?.rol == "supervisor") {
                val esAsistente = verificarSiEsAsistente()
                if (esAsistente) {
                    // Si es asistente, actualizar la interfaz para mostrar opciones de maestro
                    // Ejecutar en el hilo principal para actualizar la UI
                    runOnUiThread {
                        configurarInterfazSegunUsuario()
                    }
                    // Cargar datos como maestro
                    cargarSupervisoresSuspend()
                    // Mostrar vista jerárquica después de cargar supervisores
                    runOnUiThread {
                        mostrarLocales()
                    }
                } else {
                    // Supervisor normal - solo cargar sus locales asignados
                    cargarLocalesAsignados()
                }
            } else if (esUsuarioMaestro) {
                // Usuario maestro - cargar datos
                cargarSupervisoresSuspend()
                // Mostrar vista jerárquica después de cargar supervisores
                runOnUiThread {
                    mostrarLocales()
                }
            } else {
                // Usuario supervisor - solo cargar sus locales asignados
                cargarLocalesAsignados()
            }
        }
    }

    private fun initViews() {
        // Views principales
        btnTabLocales = findViewById(R.id.btnTabLocales)
        btnTabSupervisores = findViewById(R.id.btnTabSupervisores)
        layoutContenidoLocales = findViewById(R.id.layoutContenidoLocales)
        layoutContenidoSupervisores = findViewById(R.id.layoutContenidoSupervisores)
        
        // Views de Locales
        btnAgregarLocal = findViewById(R.id.btnAgregarLocal)
        layoutLocales = findViewById(R.id.layoutLocales)
        editTextBuscar = findViewById(R.id.editTextBuscar)
        
        // Views de Supervisores
        btnAgregarSupervisor = findViewById(R.id.btnAgregarSupervisor)
        layoutSupervisores = findViewById(R.id.layoutSupervisores)
        editTextBuscarSupervisores = findViewById(R.id.editTextBuscarSupervisores)
        
        // Estado
        txtEstado = findViewById(R.id.txtEstado)
        
        // Configurar interfaz según el tipo de usuario
        configurarInterfazSegunUsuario()
    }

    private fun setupListeners() {
        // Listeners de pestañas
        btnTabLocales.setOnClickListener {
            mostrarPestanaLocales()
        }
        
        btnTabSupervisores.setOnClickListener {
            mostrarPestanaSupervisores()
        }
        
        // Listeners de Locales
        btnAgregarLocal.setOnClickListener {
            mostrarDialogoAgregarLocal()
        }
        
        textWatcherLocales = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isFinishing && !isDestroyed) {
                    filtrarLocales(s.toString())
                }
            }
        }
        editTextBuscar.addTextChangedListener(textWatcherLocales)
        
        // Listeners de Supervisores
        btnAgregarSupervisor.setOnClickListener {
            mostrarDialogoAgregarSupervisor()
        }
        
        textWatcherSupervisores = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isFinishing && !isDestroyed) {
                    filtrarSupervisores(s.toString())
                }
            }
        }
        editTextBuscarSupervisores.addTextChangedListener(textWatcherSupervisores)
    }

    /**
     * Carga el usuario actual y determina si es maestro o supervisor
     */
    private fun cargarUsuarioActual() {
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        val metodoLogin = prefs.getString("metodoLogin", "")
        
        // 🔧 Si viene desde admin_gestion (acceso desde gestión de locales), restaurar rol original
        if (metodoLogin == "admin_gestion") {
            val rolOriginal = prefs.getString("rolOriginal", "")
            val usuarioOriginal = prefs.getString("usuarioOriginal", "")
            
            Log.d("GestionLocalesActivity", "🔧 Usuario viene desde admin_gestion, restaurando rol original: $rolOriginal")
            
            if (!rolOriginal.isNullOrEmpty()) {
                // Restaurar el rol original
                val editor = prefs.edit()
                editor.putString("rol", rolOriginal)
                
                // Si hay usuario original, restaurarlo también
                if (!usuarioOriginal.isNullOrEmpty()) {
                    editor.putString("usuario", usuarioOriginal)
                }
                
                // Limpiar el flag de método de login para que no se restaure repetidamente
                editor.putString("metodoLogin", "")
                editor.remove("rolOriginal")
                editor.remove("usuarioOriginal")
                editor.apply()
                
                Log.d("GestionLocalesActivity", "✅ Rol restaurado: $rolOriginal")
            }
        }
        
        val rol = prefs.getString("rol", "")
        val localesAsignadosStr = prefs.getString("localesAsignados", "")
        
        val localesAsignados = if (localesAsignadosStr.isNullOrEmpty()) {
            emptyList()
        } else {
            localesAsignadosStr.split(",").filter { it.isNotEmpty() }
        }
        
        usuarioActual = UsuarioActual(
            localId = prefs.getString("localId", "") ?: "",
            localNombre = prefs.getString("localNombre", "") ?: "",
            usuarioId = prefs.getString("usuarioId", "") ?: "",
            usuario = prefs.getString("usuario", "") ?: "",
            rol = rol ?: "",
            localesAsignados = localesAsignados
        )
        
        esUsuarioMaestro = rol == "maestro"
        
        Log.d("GestionLocalesActivity", "Usuario cargado: ${usuarioActual?.usuario}, Rol: ${usuarioActual?.rol}, EsMaestro: $esUsuarioMaestro, LocalesAsignados: ${usuarioActual?.localesAsignados}")
    }
    
    /**
     * Verifica si el supervisor actual es asistente del maestro
     * Retorna true si es asistente, false en caso contrario
     */
    private suspend fun verificarSiEsAsistente(): Boolean {
        return try {
            val uid = usuarioActual?.usuarioId
            
            if (uid.isNullOrEmpty()) {
                Log.e("GestionLocalesActivity", "❌ No hay usuarioId guardado")
                return false
            }
            
            // Buscar el supervisor en Firebase usando su ID
            val supervisorDoc = firestore.collection("supervisores").document(uid).get().await()
            
            if (supervisorDoc.exists()) {
                val supervisor = supervisorDoc.toObject(Supervisor::class.java)
                if (supervisor != null && supervisor.esAsistente) {
                    // Si es asistente, otorgar permisos de maestro
                    esUsuarioMaestro = true
                    esUsuarioAsistente = true
                    uidUsuarioActual = uid
                    Log.d("GestionLocalesActivity", "✅ Supervisor es asistente - otorgando permisos de maestro")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e("GestionLocalesActivity", "Error verificando si es asistente", e)
            false
        }
    }
    
    /**
     * Configura la interfaz según el tipo de usuario
     */
    private fun configurarInterfazSegunUsuario() {
        if (esUsuarioMaestro) {
            // Usuario maestro - mostrar ambas pestañas y botón agregar local
            btnTabSupervisores.visibility = android.view.View.VISIBLE
            btnAgregarSupervisor.visibility = android.view.View.VISIBLE
            btnAgregarLocal.visibility = android.view.View.VISIBLE
        } else {
            // Usuario supervisor - solo mostrar pestaña de locales, sin botón agregar local
            btnTabSupervisores.visibility = android.view.View.GONE
            btnAgregarSupervisor.visibility = android.view.View.GONE
            btnAgregarLocal.visibility = android.view.View.GONE
            layoutContenidoSupervisores.visibility = android.view.View.GONE
        }
    }
    
    /**
     * Muestra la pestaña de locales
     */
    private fun mostrarPestanaLocales() {
        layoutContenidoLocales.visibility = android.view.View.VISIBLE
        layoutContenidoSupervisores.visibility = android.view.View.GONE
        
        // Actualizar estilos de botones
        btnTabLocales.setBackgroundResource(R.drawable.tab_selected_background)
        btnTabLocales.setTextColor(resources.getColor(R.color.web_text_light, null))
        btnTabSupervisores.setBackgroundResource(R.drawable.tab_unselected_background)
        btnTabSupervisores.setTextColor(resources.getColor(R.color.brand_white, null))
    }
    
    /**
     * Muestra la pestaña de supervisores
     */
    private fun mostrarPestanaSupervisores() {
        layoutContenidoLocales.visibility = android.view.View.GONE
        layoutContenidoSupervisores.visibility = android.view.View.VISIBLE
        
        // Actualizar estilos de botones
        btnTabSupervisores.setBackgroundResource(R.drawable.tab_selected_background)
        btnTabSupervisores.setTextColor(resources.getColor(R.color.web_text_light, null))
        btnTabLocales.setBackgroundResource(R.drawable.tab_unselected_background)
        btnTabLocales.setTextColor(resources.getColor(R.color.brand_white, null))
    }
    
    /**
     * Función suspend para cargar locales de forma síncrona
     */
    private suspend fun cargarLocalesSuspend() {
        try {
            txtEstado.text = "Cargando información de locales..."
            
            // Solo cargar información básica de locales (sin datos de inventario)
            val snapshot = firestore.collection("locales")
                .whereEqualTo("activo", true)
                .get()
                .await()
            
            locales.clear()
            snapshot.documents.forEach { document ->
                val local = document.toObject(Local::class.java)?.copy(id = document.id)
                if (local != null) {
                    // Solo mantener información básica del local para gestión
                    val localBasico = local.copy(
                        usuarios = local.usuarios, // Mantener usuarios para gestión
                        // No cargar datos de inventario aquí
                    )
                    locales.add(localBasico)
                }
            }
            
            Log.d("GestionLocalesActivity", "Información básica de locales cargada: ${locales.size}")
            Log.d("GestionLocalesActivity", "Locales encontrados: ${locales.map { it.nombre }}")
            
            // Mostrar los locales en la interfaz
            mostrarLocales()
            
        } catch (e: Exception) {
            Log.e("GestionLocalesActivity", "Error cargando locales", e)
            txtEstado.text = "Error cargando locales: ${e.message}"
        }
    }

    private fun cargarLocales() {
        lifecycleScope.launch {
            cargarLocalesSuspend()
            
            // Solo mostrar locales si estamos en la pestaña de locales
            if (layoutContenidoLocales.visibility == android.view.View.VISIBLE) {
                mostrarLocales()
            }
            txtEstado.text = ""
        }
    }
    
    /**
     * Carga todos los supervisores (solo para usuarios maestros)
     */
    private fun cargarSupervisores() {
        lifecycleScope.launch {
            try {
                txtEstado.text = "Cargando supervisores..."
                
                val snapshot = firestore.collection("supervisores")
                    .whereEqualTo("activo", true)
                    .get()
                    .await()
                
                supervisores.clear()
                snapshot.documents.forEach { document ->
                    val supervisor = document.toObject(Supervisor::class.java)?.copy(id = document.id)
                    if (supervisor != null) {
                        supervisores.add(supervisor)
                    }
                }
                
                Log.d("GestionLocalesActivity", "Supervisores cargados: ${supervisores.size}")
                mostrarSupervisores()
                txtEstado.text = ""
                
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error cargando supervisores", e)
                txtEstado.text = "Error cargando supervisores: ${e.message}"
            }
        }
    }
    
    /**
     * Versión suspend de cargarSupervisores para uso en corrutinas
     */
    private suspend fun cargarSupervisoresSuspend() {
        try {
            txtEstado.text = "Cargando supervisores..."
            
            val snapshot = firestore.collection("supervisores")
                .whereEqualTo("activo", true)
                .get()
                .await()
            
            supervisores.clear()
            snapshot.documents.forEach { document ->
                val supervisor = document.toObject(Supervisor::class.java)?.copy(id = document.id)
                if (supervisor != null) {
                    supervisores.add(supervisor)
                }
            }
            
            Log.d("GestionLocalesActivity", "Supervisores cargados: ${supervisores.size}")
            mostrarSupervisores()
            txtEstado.text = ""
            
        } catch (e: Exception) {
            Log.e("GestionLocalesActivity", "Error cargando supervisores", e)
            txtEstado.text = "Error cargando supervisores: ${e.message}"
        }
    }
    
    /**
     * Carga solo los locales asignados al supervisor actual
     */
    private fun cargarLocalesAsignados() {
        lifecycleScope.launch {
            try {
                txtEstado.text = "Cargando locales asignados..."
                
                // 🔄 SIEMPRE consultar Firebase para obtener los locales asignados actualizados
                // El maestro puede cambiar los locales asignados en cualquier momento
                val uid = usuarioActual?.usuarioId
                
                if (uid.isNullOrEmpty()) {
                    Log.e("GestionLocalesActivity", "❌ No hay usuarioId guardado")
                    txtEstado.text = "Error: No hay usuario autenticado"
                    mostrarLocales()
                    return@launch
                }
                
                Log.d("GestionLocalesActivity", "🔍 Buscando supervisor con ID: $uid")
                
                // Buscar el supervisor en Firebase usando su ID
                val supervisorDoc = firestore.collection("supervisores").document(uid).get().await()
                
                if (!supervisorDoc.exists()) {
                    Log.w("GestionLocalesActivity", "⚠️ Supervisor no encontrado en Firebase con ID: $uid")
                    txtEstado.text = "Supervisor no encontrado"
                    mostrarLocales()
                    return@launch
                }
                
                val supervisor = supervisorDoc.toObject(Supervisor::class.java)
                    ?.copy(id = supervisorDoc.id)
                
                if (supervisor == null) {
                    Log.e("GestionLocalesActivity", "❌ Error parseando supervisor desde Firebase")
                    txtEstado.text = "Error cargando datos del supervisor"
                    mostrarLocales()
                    return@launch
                }
                
                val localesAsignados = supervisor.localesAsignados
                Log.d("GestionLocalesActivity", "✅ Supervisor encontrado: ${supervisor.usuario}")
                Log.d("GestionLocalesActivity", "📋 Locales asignados desde Firebase: $localesAsignados")
                
                // Actualizar usuarioActual con los datos actualizados de Firebase
                usuarioActual = usuarioActual?.copy(localesAsignados = localesAsignados)
                
                if (localesAsignados.isEmpty()) {
                    Log.w("GestionLocalesActivity", "⚠️ El supervisor no tiene locales asignados")
                    txtEstado.text = "No tienes locales asignados"
                    mostrarLocales()
                    return@launch
                }
                
                // Cargar solo los locales asignados desde Firebase
                val snapshot = firestore.collection("locales")
                    .whereEqualTo("activo", true)
                    .get()
                    .await()
                
                Log.d("GestionLocalesActivity", "📦 Total de locales activos en Firebase: ${snapshot.documents.size}")
                
                locales.clear()
                snapshot.documents.forEach { document ->
                    val local = document.toObject(Local::class.java)?.copy(id = document.id)
                    if (local != null) {
                        Log.d("GestionLocalesActivity", "🔍 Verificando local: ${local.id} (nombre: ${local.nombre})")
                        // Comparar tanto por ID como por nombre (por si acaso)
                        if (localesAsignados.contains(local.id) || localesAsignados.contains(local.nombre)) {
                            Log.d("GestionLocalesActivity", "✅ Local ${local.nombre} (${local.id}) agregado a la lista")
                            locales.add(local)
                        }
                    }
                }
                
                Log.d("GestionLocalesActivity", "✅ Locales asignados cargados: ${locales.size} de ${localesAsignados.size} esperados")
                mostrarLocales()
                txtEstado.text = if (locales.isEmpty()) {
                    "No se encontraron los locales asignados en Firebase"
                } else {
                    ""
                }
                
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error cargando locales asignados", e)
                txtEstado.text = "Error cargando locales asignados: ${e.message}"
            }
        }
    }

    private fun mostrarLocales() {
        layoutLocales.removeAllViews()
        
        if (esUsuarioMaestro) {
            // VISTA JERÁRQUICA PARA MAESTROS
            if (supervisores.isEmpty()) {
                val mensajeVacio = TextView(this).apply {
                    text = "👥 No hay supervisores registrados aún"
                    textSize = 16f
                    setTextColor(resources.getColor(android.R.color.darker_gray, null))
                    gravity = android.view.Gravity.CENTER
                    setPadding(32, 32, 32, 32)
                }
                layoutLocales.addView(mensajeVacio)
            } else {
                // Iterar supervisores para crear tarjetas plegables (filtrar propia cuenta si es asistente)
                val supervisoresAMostrar = if (esUsuarioAsistente && uidUsuarioActual != null) {
                    supervisores.filter { it.id != uidUsuarioActual && it.firebaseAuthUid != uidUsuarioActual }
                } else {
                    supervisores
                }
                supervisoresAMostrar.forEach { supervisor ->
                    val layoutSupervisor = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(16, 16, 16, 16)
                        setBackgroundResource(R.drawable.card_local_background) // reusamos el fondo
                        isClickable = true
                        isFocusable = true
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(16, 8, 16, 16)
                        }
                    }
                    
                    val headerLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    
                    val nombreSupervisor = TextView(this).apply {
                        val textoNombre = if (supervisor.esAsistente) {
                            "👨‍💼 ${supervisor.usuario} ⭐ ASISTENTE"
                        } else {
                            "👨‍💼 ${supervisor.usuario}"
                        }
                        text = textoNombre
                        textSize = 18f
                        setTextColor(resources.getColor(R.color.web_text_primary, null))
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    
                    val chevronSup = TextView(this).apply {
                        text = "›"
                        textSize = 22f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(resources.getColor(R.color.web_text_secondary, null))
                        setPadding(8, 0, 4, 0)
                    }
                    
                    headerLayout.addView(nombreSupervisor)
                    headerLayout.addView(chevronSup)
                    layoutSupervisor.addView(headerLayout)
                    
                    val containerLocales = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        visibility = View.GONE
                        setPadding(0, 16, 0, 0)
                    }
                    layoutSupervisor.addView(containerLocales)
                    
                    layoutSupervisor.setOnClickListener {
                        if (containerLocales.visibility == View.VISIBLE) {
                            containerLocales.visibility = View.GONE
                            chevronSup.text = "›"
                        } else {
                            containerLocales.visibility = View.VISIBLE
                            chevronSup.text = "⌄"
                            if (containerLocales.childCount == 0) {
                                cargarLocalesDeSupervisor(supervisor, containerLocales)
                            }
                        }
                    }
                    
                    layoutLocales.addView(layoutSupervisor)
                }
                
                // Botón para cargar locales sin asignar
                val btnCargarHuerfanos = Button(this).apply {
                    text = "🔍 BUSCAR LOCALES SIN ASIGNAR"
                    textSize = 14f
                    setTextColor(resources.getColor(R.color.web_text_light, null))
                    setBackgroundResource(R.drawable.button_gestion_warning)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(16, 16, 16, 32)
                    }
                    setOnClickListener {
                        cargarLocalesHuerfanos(this@apply) // Pasa el botón para deshabilitarlo o mostrarlo cargando
                    }
                }
                layoutLocales.addView(btnCargarHuerfanos)
            }
        } else {
            // VISTA PLANA PARA SUPERVISORES
            val listaBaseLocales = if (localesFiltrados.isNotEmpty()) localesFiltrados else locales
            val listaAMostrar = if (esUsuarioAsistente && uidUsuarioActual != null) {
                listaBaseLocales.filter { it.firebaseAuthUid != uidUsuarioActual }
            } else {
                listaBaseLocales
            }
            
            if (listaAMostrar.isEmpty()) {
                val mensajeVacio = TextView(this).apply {
                    text = if (localesFiltrados.isNotEmpty()) "🔍 No se encontraron locales" else "📝 No tienes locales asignados"
                    textSize = 16f
                    setTextColor(resources.getColor(android.R.color.darker_gray, null))
                    gravity = android.view.Gravity.CENTER
                    setPadding(32, 32, 32, 32)
                }
                layoutLocales.addView(mensajeVacio)
            } else {
                val badgesMap = mutableMapOf<String, TextView>()
                listaAMostrar.forEach { local ->
                    val (localLayout, badgeView) = crearLayoutLocalConBadge(local)
                    layoutLocales.addView(localLayout)
                    badgesMap[local.id] = badgeView
                }
                
                if (badgesMap.isNotEmpty()) {
                    val localesAsignados = usuarioActual?.localesAsignados ?: emptyList()
                    val localesParaCargar = badgesMap.filterKeys { localId -> localId in localesAsignados }
                    if (localesParaCargar.isNotEmpty()) {
                        cargarBadgesVencimientosParalelo(localesParaCargar)
                    }
                }
            }
        }
    }

    private fun cargarLocalesDeSupervisor(supervisor: Supervisor, container: LinearLayout) {
        lifecycleScope.launch {
            try {
                val loadingText = TextView(this@GestionLocalesActivity).apply {
                    text = "Cargando locales..."
                    setPadding(16, 16, 16, 16)
                }
                container.addView(loadingText)
                
                val localesAsignadosIds = supervisor.localesAsignados
                if (localesAsignadosIds.isEmpty()) {
                    loadingText.text = "No hay locales asignados a este supervisor"
                    return@launch
                }
                
                var localesDelSupervisor = localesPorSupervisor[supervisor.id]
                
                if (localesDelSupervisor == null) {
                    localesDelSupervisor = mutableListOf()
                    val chunks = localesAsignadosIds.chunked(10)
                    
                    for (chunk in chunks) {
                        val snapshot = firestore.collection("locales")
                            .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                            .get()
                            .await()
                        
                        snapshot.documents.forEach { doc ->
                            val local = doc.toObject(Local::class.java)?.copy(id = doc.id)
                            if (local != null) {
                                localesDelSupervisor.add(local)
                                if (locales.none { it.id == local.id }) locales.add(local)
                            }
                        }
                    }
                    localesPorSupervisor[supervisor.id] = localesDelSupervisor
                }
                
                container.removeAllViews()
                
                val badgesMap = mutableMapOf<String, TextView>()
                localesDelSupervisor.forEach { local ->
                    val (localLayout, badgeView) = crearLayoutLocalConBadge(local)
                    // Dar un poco de margen indentado a los locales
                    localLayout.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(32, 8, 16, 8) }
                    
                    container.addView(localLayout)
                    badgesMap[local.id] = badgeView
                }
                
                if (badgesMap.isNotEmpty()) {
                    cargarBadgesVencimientosParalelo(badgesMap)
                }
            } catch (e: Exception) {
                container.removeAllViews()
                val errorText = TextView(this@GestionLocalesActivity).apply {
                    text = "Error: ${e.message}"
                    setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                }
                container.addView(errorText)
            }
        }
    }

    private fun cargarLocalesHuerfanos(btnCargar: Button) {
        lifecycleScope.launch {
            try {
                btnCargar.isEnabled = false
                btnCargar.text = "⏳ CARGANDO..."
                
                // Obtener todos los IDs asignados a algún supervisor
                val todosLosIdsAsignados = supervisores.flatMap { it.localesAsignados }.toSet()
                
                // Descargar TODOS los locales activos (la única lectura masiva)
                val snapshot = firestore.collection("locales")
                    .whereEqualTo("activo", true)
                    .get()
                    .await()
                
                val huerfanos = mutableListOf<Local>()
                snapshot.documents.forEach { doc ->
                    val local = doc.toObject(Local::class.java)?.copy(id = doc.id)
                    if (local != null && !todosLosIdsAsignados.contains(local.id)) {
                        huerfanos.add(local)
                        if (locales.none { it.id == local.id }) locales.add(local)
                    }
                }
                
                // Ocultar botón y mostrar contenedor
                btnCargar.visibility = View.GONE
                
                val layoutHuerfanos = LinearLayout(this@GestionLocalesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 16, 16, 16)
                    setBackgroundResource(R.drawable.card_local_background)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(16, 8, 16, 32)
                    }
                }
                
                val titulo = TextView(this@GestionLocalesActivity).apply {
                    text = "⚠️ LOCALES SIN ASIGNAR (${huerfanos.size})"
                    textSize = 18f
                    setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, 16)
                }
                layoutHuerfanos.addView(titulo)
                
                if (huerfanos.isEmpty()) {
                    val msj = TextView(this@GestionLocalesActivity).apply {
                        text = "Todos los locales están asignados a algún supervisor."
                        setTextColor(resources.getColor(android.R.color.darker_gray, null))
                    }
                    layoutHuerfanos.addView(msj)
                } else {
                    val badgesMap = mutableMapOf<String, TextView>()
                    huerfanos.forEach { local ->
                        val (localLayout, badgeView) = crearLayoutLocalConBadge(local)
                        layoutHuerfanos.addView(localLayout)
                        badgesMap[local.id] = badgeView
                    }
                    if (badgesMap.isNotEmpty()) {
                        cargarBadgesVencimientosParalelo(badgesMap)
                    }
                }
                
                layoutLocales.addView(layoutHuerfanos)
                
            } catch (e: Exception) {
                btnCargar.isEnabled = true
                btnCargar.text = "❌ ERROR: INTENTAR DE NUEVO"
                Log.e("GestionLocalesActivity", "Error buscando huerfanos", e)
            }
        }
    }
    
    /**
     * Muestra la lista de supervisores
     */
    private fun mostrarSupervisores() {
        layoutSupervisores.removeAllViews()
        
        // Usar la lista filtrada si hay filtro, sino usar la lista completa
        val listaBase = if (supervisoresFiltrados.isNotEmpty()) supervisoresFiltrados else supervisores
        
        // Si el usuario actual es asistente (no maestro real), filtrar su propia cuenta
        val listaAMostrar = if (esUsuarioAsistente && uidUsuarioActual != null) {
            listaBase.filter { supervisor ->
                supervisor.id != uidUsuarioActual && supervisor.firebaseAuthUid != uidUsuarioActual
            }
        } else {
            listaBase
        }
        
        if (listaAMostrar.isEmpty()) {
            val mensajeVacio = TextView(this).apply {
                text = if (supervisoresFiltrados.isNotEmpty()) "🔍 No se encontraron supervisores que coincidan con la búsqueda" else "👥 No hay supervisores registrados"
                textSize = 16f
                setTextColor(resources.getColor(android.R.color.darker_gray, null))
                gravity = android.view.Gravity.CENTER
                setPadding(32, 32, 32, 32)
            }
            layoutSupervisores.addView(mensajeVacio)
        } else {
            listaAMostrar.forEach { supervisor ->
                val supervisorLayout = crearLayoutSupervisor(supervisor)
                layoutSupervisores.addView(supervisorLayout)
            }
        }
    }
    
    /**
     * Filtra supervisores por texto de búsqueda
     */
    private fun filtrarSupervisores(texto: String) {
        supervisoresFiltrados.clear()
        
        if (texto.isBlank()) {
            // Si no hay texto de búsqueda, mostrar todos los supervisores
            mostrarSupervisores()
            return
        }
        
        // Filtrar supervisores que contengan el texto de búsqueda
        supervisoresFiltrados.addAll(
            supervisores.filter { supervisor ->
                supervisor.usuario.contains(texto, ignoreCase = true) ||
                supervisor.fechaCreacion.contains(texto, ignoreCase = true) ||
                supervisor.localesAsignados.any { localId ->
                    locales.find { it.id == localId }?.nombre?.contains(texto, ignoreCase = true) == true
                }
            }
        )
        
        mostrarSupervisores()
    }

    /**
     * Crea el layout de un local y retorna también el badge de vencimientos
     */
    private fun crearLayoutLocalConBadge(local: Local): Pair<LinearLayout, TextView> {
        var badgeVencimientosRef: TextView? = null
        
        val layout = crearLayoutLocal(local) { badgeView ->
            badgeVencimientosRef = badgeView
        }
        
        return layout to (badgeVencimientosRef ?: TextView(this))
    }
    
    private fun crearLayoutLocal(local: Local, onBadgeCreated: ((TextView) -> Unit)? = null): LinearLayout {

        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundResource(R.drawable.card_local_background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 6, 16, 6)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                mostrarInformacionYDocumentacionLocal(local)
            }
        }

        // Icono de tienda 🏬
        val iconView = TextView(this).apply {
            text = "🏬"
            textSize = 22f
            setPadding(8, 8, 8, 8)
        }
        layout.addView(iconView)

        // Columna de título y subtítulo (Nombre del local y Razón Social / Email)
        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(12, 0, 8, 0)
            }
        }

        val nombreText = TextView(this).apply {
            text = local.nombre
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
        }

        val subText = TextView(this).apply {
            text = if (local.razonSocial.isNotEmpty()) local.razonSocial else (if (local.email.isNotEmpty()) local.email else "Local")
            textSize = 12.5f
            setTextColor(if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.GRAY)
        }

        infoLayout.addView(nombreText)
        infoLayout.addView(subText)
        layout.addView(infoLayout)

        // Columna de Badges y Alertas
        val rightLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val btnNotifBell = TextView(this).apply {
            text = "🔔"
            textSize = 18f
            setPadding(8, 8, 8, 8)
            isClickable = true
            isFocusable = true
            tag = "badge_vencimientos"
            setOnClickListener {
                mostrarPopupVencimientosHoy(local)
            }
        }

        val badgeContador = TextView(this).apply {
            text = ""
            textSize = 9f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.button_gestion_danger)
            setPadding(4, 1, 4, 1)
            visibility = View.GONE
            tag = "badge_contador"
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
            }
        }

        val bellContainer = android.widget.FrameLayout(this).apply {
            addView(btnNotifBell)
            addView(badgeContador)
        }
        rightLayout.addView(bellContainer)
        onBadgeCreated?.invoke(badgeContador)

        val chevronView = TextView(this).apply {
            text = "›"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.GRAY)
            setPadding(8, 0, 4, 0)
        }
        rightLayout.addView(chevronView)

        layout.addView(rightLayout)

        verificarDatosEnCero(local.id, local.nombre, nombreText, btnNotifBell, badgeContador)

        return layout
    }

    /**
     * Muestra el modal de Información Legal, Acciones y Documentación del Local (idéntico a la web)
     */
    private fun mostrarInformacionYDocumentacionLocal(local: Local) {
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#1E293B") else android.graphics.Color.WHITE)
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val txtTitle = TextView(this).apply {
            text = "🏬 Detalle: ${local.nombre}"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.GRAY)
            setPadding(16, 0, 16, 0)
            setOnClickListener { dialog.dismiss() }
        }

        headerLayout.addView(txtTitle)
        headerLayout.addView(btnClose)
        rootLayout.addView(headerLayout)

        val scrollView = android.widget.ScrollView(this).apply {
            isFillViewport = true
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // BOTÓN PRINCIPAL: Ingresar al Local
        val btnIngresarAcceso = Button(this).apply {
            text = "🚀 INGRESAR AL LOCAL"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.button_primary_background)
            setTextColor(resources.getColor(R.color.web_text_light, null))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 12)
            }
            setOnClickListener {
                dialog.dismiss()
                ingresarAlLocal(local)
            }
        }
        contentLayout.addView(btnIngresarAcceso)

        // FILA DE ACCIONES DE GESTIÓN
        val gridAcciones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 16)
            }
        }

        val btnCreds = Button(this).apply {
            text = "👥 CREDENCIALES"
            textSize = 10.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.button_gestion_info)
            setTextColor(resources.getColor(R.color.web_text_light, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 4, 0) }
            setOnClickListener { mostrarUsuariosYCredenciales(local) }
        }

        val btnPass = Button(this).apply {
            text = "🔑 BLANQUEO"
            textSize = 10.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.button_gestion_success)
            setTextColor(resources.getColor(R.color.web_text_light, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) }
            setOnClickListener { mostrarDialogoBlanquearClave(local) }
        }

        gridAcciones.addView(btnCreds)
        gridAcciones.addView(btnPass)

        if (esUsuarioMaestro) {
            val btnDel = Button(this).apply {
                text = "🗑️ ELIMINAR"
                textSize = 10.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.button_gestion_danger)
                setTextColor(resources.getColor(R.color.web_text_light, null))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 0, 0) }
                setOnClickListener { mostrarDialogoEliminarLocal(local) }
            }
            gridAcciones.addView(btnDel)
        }
        contentLayout.addView(gridAcciones)

        // SECCIÓN 1: DATOS LEGALES Y UBICACIÓN
        val lblInfoHead = TextView(this).apply {
            text = "📋 Datos Legales y Ubicacion"
            textSize = 14.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#C9A24A"))
            setPadding(0, 8, 0, 8)
        }
        contentLayout.addView(lblInfoHead)

        val inputRazon = EditText(this).apply {
            hint = "Razón Social"
            setText(local.razonSocial)
            setPadding(16, 16, 16, 16)
            setBackgroundResource(R.drawable.edit_text_background)
            setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999"))
        }
        val inputCuit = EditText(this).apply {
            hint = "CUIT"
            setText(local.cuit)
            setPadding(16, 16, 16, 16)
            setBackgroundResource(R.drawable.edit_text_background)
            setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999"))
        }
        val inputDireccion = EditText(this).apply {
            hint = "Dirección del Local"
            setText(local.direccion)
            setPadding(16, 16, 16, 16)
            setBackgroundResource(R.drawable.edit_text_background)
            setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999"))
        }
        val inputTel = EditText(this).apply {
            hint = "Teléfono de Contacto"
            setText(local.telefono)
            setPadding(16, 16, 16, 16)
            setBackgroundResource(R.drawable.edit_text_background)
            setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999"))
        }

        contentLayout.addView(TextView(this).apply { text = "Razón Social:"; textSize = 11.5f; setTextColor(if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.DKGRAY) })
        contentLayout.addView(inputRazon)
        contentLayout.addView(TextView(this).apply { text = "CUIT:"; textSize = 11.5f; setTextColor(if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.DKGRAY) })
        contentLayout.addView(inputCuit)
        contentLayout.addView(TextView(this).apply { text = "Dirección:"; textSize = 11.5f; setTextColor(if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.DKGRAY) })
        contentLayout.addView(inputDireccion)
        contentLayout.addView(TextView(this).apply { text = "Teléfono:"; textSize = 11.5f; setTextColor(if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.DKGRAY) })
        contentLayout.addView(inputTel)

        val btnGuardarInfo = Button(this).apply {
            text = "💾 Guardar Informacion del Local"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.button_primary_background)
            setTextColor(resources.getColor(R.color.web_text_light, null))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 12, 0, 16)
            }
            setOnClickListener {
                val rSocial = inputRazon.text.toString().trim()
                val cuitVal = inputCuit.text.toString().trim()
                val dirVal = inputDireccion.text.toString().trim()
                val telVal = inputTel.text.toString().trim()

                firestore.collection("locales").document(local.id)
                    .update(
                        mapOf(
                            "razonSocial" to rSocial,
                            "cuit" to cuitVal,
                            "direccion" to dirVal,
                            "telefono" to telVal
                        )
                    ).addOnSuccessListener {
                        Toast.makeText(this@GestionLocalesActivity, "Información guardada correctamente", Toast.LENGTH_SHORT).show()
                    }.addOnFailureListener { e ->
                        Toast.makeText(this@GestionLocalesActivity, "Error al guardar: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        }
        contentLayout.addView(btnGuardarInfo)

        // SECCIÓN 2: SOLAPAS (DOCUMENTACIÓN, PERSONAL, SERVICIOS)
        val tabsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 12)
        }

        val btnTabDocs = Button(this)
        val btnTabPersonal = Button(this)
        val btnTabServicios = Button(this)

        val tabContentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun updateTabsUI(selectedTab: String) {
            val activeBg = android.graphics.Color.parseColor("#C9A24A")
            val inactiveBg = if (isDark) android.graphics.Color.parseColor("#334155") else android.graphics.Color.parseColor("#E2E8F0")
            val activeText = android.graphics.Color.WHITE
            val inactiveText = if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.DKGRAY

            btnTabDocs.apply {
                setBackgroundColor(if (selectedTab == "docs") activeBg else inactiveBg)
                setTextColor(if (selectedTab == "docs") activeText else inactiveText)
            }
            btnTabPersonal.apply {
                setBackgroundColor(if (selectedTab == "personal") activeBg else inactiveBg)
                setTextColor(if (selectedTab == "personal") activeText else inactiveText)
            }
            btnTabServicios.apply {
                setBackgroundColor(if (selectedTab == "servicios") activeBg else inactiveBg)
                setTextColor(if (selectedTab == "servicios") activeText else inactiveText)
            }
        }

        fun cargarTabDocs() {
            updateTabsUI("docs")
            tabContentContainer.removeAllViews()

            val btnAddDoc = Button(this@GestionLocalesActivity).apply {
                text = "➕ NUEVO DOCUMENTO"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.button_primary_background)
                setTextColor(resources.getColor(R.color.web_text_light, null))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 4, 0, 12)
                }
                setOnClickListener {
                    mostrarDialogoAgregarDocumento(local) { cargarTabDocs() }
                }
            }
            tabContentContainer.addView(btnAddDoc)

            firestore.collection("locales").document(local.id).collection("documentos")
                .get()
                .addOnSuccessListener { docs ->
                    if (tabContentContainer.childCount > 1) {
                        tabContentContainer.removeViews(1, tabContentContainer.childCount - 1)
                    }
                    if (docs.isEmpty) {
                        tabContentContainer.addView(TextView(this@GestionLocalesActivity).apply {
                            text = "📑 No hay documentos registrados"
                            textSize = 13f
                            setTextColor(if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.GRAY)
                            setPadding(0, 8, 0, 8)
                        })
                    } else {
                        for (doc in docs) {
                            val nombreDoc = doc.get("nombreDocumento")?.toString()
                                ?: doc.get("nombre")?.toString()
                                ?: doc.get("tipoDocumento")?.toString()
                                ?: doc.get("tipo")?.toString()
                                ?: "Documento"
                            val fechaVencObj = doc.get("fechaVencimiento")
                            val fechaVenc = when (fechaVencObj) {
                                is String -> fechaVencObj
                                is com.google.firebase.Timestamp -> {
                                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                    sdf.format(fechaVencObj.toDate())
                                }
                                is java.util.Date -> {
                                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                    sdf.format(fechaVencObj)
                                }
                                else -> fechaVencObj?.toString() ?: ""
                            }
                            val estadoDoc = doc.get("estado")?.toString() ?: "VIGENTE"

                            val itemRow = LinearLayout(this@GestionLocalesActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                setPadding(12, 12, 12, 12)
                                setBackgroundResource(R.drawable.card_local_background)
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                    setMargins(0, 4, 0, 4)
                                }
                            }

                            val txtDocName = TextView(this@GestionLocalesActivity).apply {
                                text = "📄 $nombreDoc\nVencimiento: ${fechaVenc.ifEmpty { "Sin fecha" }}"
                                textSize = 12.5f
                                setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }

                            val badgeState = TextView(this@GestionLocalesActivity).apply {
                                text = estadoDoc
                                textSize = 10.5f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                setPadding(12, 6, 12, 6)
                                setTextColor(resources.getColor(R.color.web_text_light, null))
                                setBackgroundColor(
                                    when (estadoDoc.uppercase()) {
                                        "VENCIDO" -> android.graphics.Color.parseColor("#EF4444")
                                        "POR VENCER" -> android.graphics.Color.parseColor("#EAB308")
                                        else -> android.graphics.Color.parseColor("#22C55E")
                                    }
                                )
                            }

                            val btnDelete = TextView(this@GestionLocalesActivity).apply {
                                text = "🗑️"
                                textSize = 16f
                                setPadding(12, 0, 4, 0)
                                setOnClickListener {
                                    firestore.collection("locales").document(local.id).collection("documentos").document(doc.id).delete()
                                        .addOnSuccessListener {
                                            Toast.makeText(this@GestionLocalesActivity, "Documento eliminado", Toast.LENGTH_SHORT).show()
                                            cargarTabDocs()
                                        }
                                }
                            }

                            itemRow.addView(txtDocName)
                            itemRow.addView(badgeState)
                            itemRow.addView(btnDelete)
                            tabContentContainer.addView(itemRow)
                        }
                    }
                }
        }

        fun cargarTabPersonal() {
            updateTabsUI("personal")
            tabContentContainer.removeAllViews()

            val btnAddPersonal = Button(this@GestionLocalesActivity).apply {
                text = "👤 NUEVO PERSONAL"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.button_primary_background)
                setTextColor(resources.getColor(R.color.web_text_light, null))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 4, 0, 12)
                }
                setOnClickListener {
                    mostrarDialogoAgregarPersonal(local) { cargarTabPersonal() }
                }
            }
            tabContentContainer.addView(btnAddPersonal)

            firestore.collection("locales").document(local.id).collection("personal")
                .get()
                .addOnSuccessListener { query ->
                    if (tabContentContainer.childCount > 1) {
                        tabContentContainer.removeViews(1, tabContentContainer.childCount - 1)
                    }
                    if (query.isEmpty) {
                        tabContentContainer.addView(TextView(this@GestionLocalesActivity).apply {
                            text = "👥 No hay personal registrado"
                            textSize = 13f
                            setTextColor(if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.GRAY)
                            setPadding(0, 8, 0, 8)
                        })
                    } else {
                        for (doc in query) {
                            val nombre = doc.getString("nombre") ?: "Sin Nombre"
                            val cargo = doc.getString("cargo") ?: ""
                            val dni = doc.getString("dni") ?: ""

                            val libretaMap = doc.get("libretaSanitaria") as? Map<*, *>
                            val libretaVenc = formatearFechaVencimiento(libretaMap?.get("fechaVencimiento"))
                            val cursoMap = doc.get("cursoManipulacion") as? Map<*, *>
                            val cursoVenc = formatearFechaVencimiento(cursoMap?.get("fechaVencimiento"))

                            val itemRow = LinearLayout(this@GestionLocalesActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(12, 12, 12, 12)
                                setBackgroundResource(R.drawable.card_local_background)
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                    setMargins(0, 4, 0, 4)
                                }
                            }

                            val topRow = LinearLayout(this@GestionLocalesActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                            }

                            val txtInfo = TextView(this@GestionLocalesActivity).apply {
                                text = "👤 $nombre\n${cargo.ifEmpty { "Empleado" }}${if (dni.isNotEmpty()) " - DNI: $dni" else ""}"
                                textSize = 13f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }

                            val btnDelete = TextView(this@GestionLocalesActivity).apply {
                                text = "🗑️"
                                textSize = 16f
                                setPadding(8, 0, 4, 0)
                                setOnClickListener {
                                    firestore.collection("locales").document(local.id).collection("personal").document(doc.id).delete()
                                        .addOnSuccessListener {
                                            Toast.makeText(this@GestionLocalesActivity, "Personal eliminado", Toast.LENGTH_SHORT).show()
                                            cargarTabPersonal()
                                        }
                                }
                            }
                            topRow.addView(txtInfo)
                            topRow.addView(btnDelete)
                            itemRow.addView(topRow)

                            val badgesRow = LinearLayout(this@GestionLocalesActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(0, 6, 0, 0)
                            }

                            if (libretaVenc.isNotEmpty()) {
                                val diasLibreta = diasHastaVencimiento(libretaMap?.get("fechaVencimiento"))
                                val colorLibreta = when {
                                    diasLibreta < 0 -> "#DC2626"
                                    diasLibreta <= 7 -> "#F59E0B"
                                    diasLibreta <= 30 -> "#3B82F6"
                                    else -> "#10B981"
                                }
                                val textoLibreta = formatearDiasVencimiento(diasLibreta)
                                badgesRow.addView(TextView(this@GestionLocalesActivity).apply {
                                    text = "📋 Libreta: $textoLibreta"
                                    textSize = 10.5f
                                    setPadding(8, 4, 8, 4)
                                    setTextColor(resources.getColor(R.color.web_text_light, null))
                                    setBackgroundColor(android.graphics.Color.parseColor(colorLibreta))
                                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 6, 0) }
                                })
                            }
                            if (cursoVenc.isNotEmpty()) {
                                val diasCurso = diasHastaVencimiento(cursoMap?.get("fechaVencimiento"))
                                val colorCurso = when {
                                    diasCurso < 0 -> "#DC2626"
                                    diasCurso <= 7 -> "#F59E0B"
                                    diasCurso <= 30 -> "#3B82F6"
                                    else -> "#10B981"
                                }
                                val textoCurso = formatearDiasVencimiento(diasCurso)
                                badgesRow.addView(TextView(this@GestionLocalesActivity).apply {
                                    text = "🎓 Curso: $textoCurso"
                                    textSize = 10.5f
                                    setPadding(8, 4, 8, 4)
                                    setTextColor(resources.getColor(R.color.web_text_light, null))
                                    setBackgroundColor(android.graphics.Color.parseColor(colorCurso))
                                })
                            }
                            if (badgesRow.childCount > 0) itemRow.addView(badgesRow)

                            tabContentContainer.addView(itemRow)
                        }
                    }
                }
        }

        fun cargarTabServicios() {
            updateTabsUI("servicios")
            tabContentContainer.removeAllViews()

            val btnAddServ = Button(this@GestionLocalesActivity).apply {
                text = "🔧 NUEVO SERVICIO"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.button_primary_background)
                setTextColor(resources.getColor(R.color.web_text_light, null))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 4, 0, 12)
                }
                setOnClickListener {
                    mostrarDialogoAgregarServicio(local) { cargarTabServicios() }
                }
            }
            tabContentContainer.addView(btnAddServ)

            firestore.collection("locales").document(local.id).collection("servicios")
                .get()
                .addOnSuccessListener { query ->
                    if (tabContentContainer.childCount > 1) {
                        tabContentContainer.removeViews(1, tabContentContainer.childCount - 1)
                    }
                    if (query.isEmpty) {
                        tabContentContainer.addView(TextView(this@GestionLocalesActivity).apply {
                            text = "🔧 No hay servicios registrados"
                            textSize = 13f
                            setTextColor(if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.GRAY)
                            setPadding(0, 8, 0, 8)
                        })
                    } else {
                        for (doc in query) {
                            val tipo = doc.getString("tipo") ?: "Servicio"
                            val proveedor = doc.getString("proveedor") ?: ""
                            val numCliente = doc.getString("numeroCliente") ?: ""
                            val tel = doc.getString("telefono") ?: ""

                            val iconoServicio = when (tipo) {
                                "Internet" -> "📶"
                                "Luz" -> "⚡"
                                "Fumigacion" -> "🐛"
                                "Extintor" -> "🔥"
                                "Dispenser" -> "💧"
                                "Coca-Cola" -> "🥤"
                                "Cervezas" -> "🍺"
                                "Agua" -> "💧"
                                "Alarma" -> "⏰"
                                "Gas" -> "🔥"
                                "Municipalidad" -> "🏢"
                                else -> "🔧"
                            }

                            val itemRow = LinearLayout(this@GestionLocalesActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                setPadding(12, 12, 12, 12)
                                setBackgroundResource(R.drawable.card_local_background)
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                    setMargins(0, 4, 0, 4)
                                }
                            }

                            val txtInfo = TextView(this@GestionLocalesActivity).apply {
                                val det = mutableListOf<String>()
                                if (proveedor.isNotEmpty()) det.add("Proveedor: $proveedor")
                                if (numCliente.isNotEmpty()) det.add("N° Cliente: $numCliente")
                                if (tel.isNotEmpty()) det.add("Tel: $tel")

                                text = "$iconoServicio $tipo\n${det.joinToString(" | ")}"
                                textSize = 12.5f
                                setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }

                            val btnDelete = TextView(this@GestionLocalesActivity).apply {
                                text = "🗑️"
                                textSize = 16f
                                setPadding(8, 0, 4, 0)
                                setOnClickListener {
                                    firestore.collection("locales").document(local.id).collection("servicios").document(doc.id).delete()
                                        .addOnSuccessListener {
                                            Toast.makeText(this@GestionLocalesActivity, "Servicio eliminado", Toast.LENGTH_SHORT).show()
                                            cargarTabServicios()
                                        }
                                }
                            }

                            itemRow.addView(txtInfo)
                            itemRow.addView(btnDelete)
                            tabContentContainer.addView(itemRow)
                        }
                    }
                }
        }

        btnTabDocs.apply {
            text = "📑 Documentos"
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 2, 0) }
            setOnClickListener { cargarTabDocs() }
        }
        btnTabPersonal.apply {
            text = "👤 Personal"
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 2, 0) }
            setOnClickListener { cargarTabPersonal() }
        }
        btnTabServicios.apply {
            text = "🔧 Servicios"
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(2, 0, 0, 0) }
            setOnClickListener { cargarTabServicios() }
        }

        tabsLayout.addView(btnTabDocs)
        tabsLayout.addView(btnTabPersonal)
        tabsLayout.addView(btnTabServicios)

        contentLayout.addView(tabsLayout)
        contentLayout.addView(tabContentContainer)

        cargarTabDocs()

        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)
        dialog.setContentView(rootLayout)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun mostrarDialogoAgregarDocumento(local: Local, onSave: () -> Unit) {
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val inputNombre = EditText(this).apply {
            hint = "Nombre del Documento (Ej: Habilitación Comercial)"
            setPadding(16, 16, 16, 16)
            setBackgroundResource(R.drawable.edit_text_background)
            setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999"))
        }
        val inputFecha = EditText(this).apply {
            hint = "Fecha Vencimiento (AAAA-MM-DD)"
            setPadding(16, 16, 16, 16)
            setBackgroundResource(R.drawable.edit_text_background)
            setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999"))
        }

        layout.addView(TextView(this).apply { text = "Nombre del Documento:"; textSize = 12f; setTextColor(if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.DKGRAY) })
        layout.addView(inputNombre)
        layout.addView(TextView(this).apply { text = "Fecha de Vencimiento:"; textSize = 12f; setTextColor(if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.DKGRAY); setPadding(0, 12, 0, 0) })
        layout.addView(inputFecha)

        AlertDialog.Builder(this)
            .setTitle("📑 Agregar Documento - ${local.nombre}")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = inputNombre.text.toString().trim()
                val fecha = inputFecha.text.toString().trim()
                if (nombre.isEmpty()) {
                    Toast.makeText(this, "Ingrese el nombre del documento", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val data = hashMapOf(
                    "nombreDocumento" to nombre,
                    "nombre" to nombre,
                    "fechaVencimiento" to fecha,
                    "estado" to "VIGENTE"
                )
                firestore.collection("locales").document(local.id).collection("documentos").add(data)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Documento guardado", Toast.LENGTH_SHORT).show()
                        onSave()
                    }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarDialogoAgregarPersonal(local: Local, onSave: () -> Unit) {
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val inputNombre = EditText(this).apply { hint = "Nombre y Apellido"; setPadding(16, 16, 16, 16); setBackgroundResource(R.drawable.edit_text_background); setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK); setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999")) }
        val inputCargo = EditText(this).apply { hint = "Cargo (Ej: Cajero, Encargado)"; setPadding(16, 16, 16, 16); setBackgroundResource(R.drawable.edit_text_background); setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK); setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999")) }
        val inputDni = EditText(this).apply { hint = "DNI"; setPadding(16, 16, 16, 16); setBackgroundResource(R.drawable.edit_text_background); setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK); setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999")) }
        val inputLibreta = EditText(this).apply { hint = "Vencimiento Libreta (AAAA-MM-DD)"; setPadding(16, 16, 16, 16); setBackgroundResource(R.drawable.edit_text_background); setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK); setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999")) }
        val inputCurso = EditText(this).apply { hint = "Vencimiento Curso (AAAA-MM-DD)"; setPadding(16, 16, 16, 16); setBackgroundResource(R.drawable.edit_text_background); setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK); setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999")) }

        layout.addView(inputNombre)
        layout.addView(inputCargo)
        layout.addView(inputDni)
        layout.addView(inputLibreta)
        layout.addView(inputCurso)

        AlertDialog.Builder(this)
            .setTitle("👤 Agregar Personal - ${local.nombre}")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = inputNombre.text.toString().trim()
                if (nombre.isEmpty()) {
                    Toast.makeText(this, "Ingrese el nombre del personal", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val data = hashMapOf(
                    "nombre" to nombre,
                    "cargo" to inputCargo.text.toString().trim(),
                    "dni" to inputDni.text.toString().trim(),
                    "activo" to true,
                    "libretaSanitaria" to hashMapOf("fechaVencimiento" to inputLibreta.text.toString().trim()),
                    "cursoManipulacion" to hashMapOf("fechaVencimiento" to inputCurso.text.toString().trim())
                )
                firestore.collection("locales").document(local.id).collection("personal").add(data)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Personal guardado", Toast.LENGTH_SHORT).show()
                        onSave()
                    }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarDialogoAgregarServicio(local: Local, onSave: () -> Unit) {
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val spinnerTipo = android.widget.Spinner(this)
        val tipos = arrayOf("Internet", "Luz", "Fumigacion", "Extintor", "Dispenser", "Coca-Cola", "Cervezas", "Agua", "Alarma", "Gas", "Municipalidad", "Otro")
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tipos)
        spinnerTipo.adapter = adapter

        val inputProv = EditText(this).apply { hint = "Proveedor (Ej: Telecentro)"; setPadding(16, 16, 16, 16); setBackgroundResource(R.drawable.edit_text_background); setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK); setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999")) }
        val inputCliente = EditText(this).apply { hint = "N° de Cliente"; setPadding(16, 16, 16, 16); setBackgroundResource(R.drawable.edit_text_background); setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK); setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999")) }
        val inputTel = EditText(this).apply { hint = "Teléfono"; setPadding(16, 16, 16, 16); setBackgroundResource(R.drawable.edit_text_background); setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK); setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999")) }

        layout.addView(TextView(this).apply { text = "Tipo de Servicio:"; textSize = 12f; setTextColor(if (isDark) android.graphics.Color.LTGRAY else android.graphics.Color.DKGRAY) })
        layout.addView(spinnerTipo)
        layout.addView(inputProv)
        layout.addView(inputCliente)
        layout.addView(inputTel)

        AlertDialog.Builder(this)
            .setTitle("🔧 Agregar Servicio - ${local.nombre}")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val tipo = spinnerTipo.selectedItem.toString()
                val prov = inputProv.text.toString().trim()
                if (prov.isEmpty()) {
                    Toast.makeText(this, "Ingrese el nombre del proveedor", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val data = hashMapOf(
                    "tipo" to tipo,
                    "proveedor" to prov,
                    "numeroCliente" to inputCliente.text.toString().trim(),
                    "telefono" to inputTel.text.toString().trim()
                )
                firestore.collection("locales").document(local.id).collection("servicios").add(data)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Servicio guardado", Toast.LENGTH_SHORT).show()
                        onSave()
                    }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }


    
    /**
     * Crea el layout para mostrar un supervisor
     */
    private fun crearLayoutSupervisor(supervisor: Supervisor): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundResource(R.drawable.card_local_background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 8, 16, 16)
            }
        }
        
        // Nombre del supervisor (con indicador de asistente si aplica)
        val nombreText = TextView(this).apply {
            val textoNombre = if (supervisor.esAsistente) {
                "👨‍💼 ${supervisor.usuario} ⭐ ASISTENTE"
            } else {
                "👨‍💼 ${supervisor.usuario}"
            }
            text = textoNombre
            textSize = 20f
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        layout.addView(nombreText)
        
        // Fecha de creación
        val fechaText = TextView(this).apply {
            text = "Creado: ${supervisor.fechaCreacion}"
            textSize = 12f
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            setPadding(0, 4, 0, 4)
        }
        layout.addView(fechaText)
        
        // Locales asignados
        val localesAsignadosText = TextView(this).apply {
            val nombresLocales = supervisor.localesAsignados.mapNotNull { localId ->
                locales.find { it.id == localId }?.nombre
            }
            text = "Locales asignados: ${nombresLocales.size} (${nombresLocales.joinToString(", ")})"
            textSize = 12f
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            setPadding(0, 4, 0, 8)
        }
        layout.addView(localesAsignadosText)
        
        // Layout para botones de administración
        val botonesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Botón ver locales asignados
        val btnVerLocales = Button(this).apply {
            text = "🏪 LOCALES"
            textSize = 10f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.button_gestion_info)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 4, 0)
            }
            setPadding(4, 8, 4, 8)
            setOnClickListener {
                mostrarLocalesAsignados(supervisor)
            }
        }
        
        // Botón asignar locales
        val btnAsignarLocales = Button(this).apply {
            text = "➕ ASIGNAR"
            textSize = 10f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.button_gestion_success)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            }
            setPadding(4, 8, 4, 8)
            setOnClickListener {
                mostrarDialogoAsignarLocales(supervisor)
            }
        }
        
        // Botón ver contraseña
        val btnVercontraseña = Button(this).apply {
            text = "🔑 CLAVE"
            textSize = 10f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.button_gestion_info)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            }
            setPadding(4, 8, 4, 8)
            setOnClickListener {
                mostrarcontraseñaSupervisor(supervisor)
            }
        }
        
        // Botón designar/remover como asistente (solo visible para maestros reales, no asistentes)
        val btnAsistente = Button(this).apply {
            text = if (supervisor.esAsistente) "⭐ NO ASIST" else "⭐ ASISTENTE"
            textSize = 10f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundResource(if (supervisor.esAsistente) R.drawable.button_gestion_warning else R.drawable.button_gestion_success)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            }
            setPadding(4, 8, 4, 8)
            setOnClickListener {
                toggleAsistenteSupervisor(supervisor)
            }
        }
        
        // Botón eliminar supervisor
        val btnEliminarSupervisor = Button(this).apply {
            text = "🗑️ ELIMINAR"
            textSize = 10f
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.button_gestion_danger)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 0, 0)
            }
            setPadding(4, 8, 4, 8)
            setOnClickListener {
                mostrarDialogoEliminarSupervisor(supervisor)
            }
        }
        
        botonesLayout.addView(btnVerLocales)
        botonesLayout.addView(btnAsignarLocales)
        botonesLayout.addView(btnVercontraseña)
        
        // Solo mostrar botón de asistente si es maestro real (no asistente)
        if (!esUsuarioAsistente) {
            botonesLayout.addView(btnAsistente)
        }
        
        botonesLayout.addView(btnEliminarSupervisor)
        layout.addView(botonesLayout)
        
        return layout
    }
    
    /**
     * Muestra los locales asignados a un supervisor
     */
    private fun mostrarLocalesAsignados(supervisor: Supervisor) {
        val nombresLocales = supervisor.localesAsignados.mapNotNull { localId ->
            locales.find { it.id == localId }?.nombre
        }
        
        val mensaje = if (nombresLocales.isEmpty()) {
            "Este supervisor no tiene locales asignados"
        } else {
            "Locales asignados:\n\n" + nombresLocales.joinToString("\n") { "🏪 $it" }
        }
        
        mostrarDialogoPersonalizado(
            icono = "🏪",
            titulo = "Locales de ${supervisor.usuario}",
            contenido = mensaje,
            textoBotonConfirmar = "OK",
            soloConfirmar = true
        )
    }
    
    /**
     * Muestra y permite editar las credenciales del supervisor
     */
    private fun mostrarcontraseñaSupervisor(supervisor: Supervisor) {
        // Si el email tiene el dominio, mostrar solo la parte antes del "@"
        val emailSinDominio = if (supervisor.email.contains("@")) {
            supervisor.email.substringBefore("@")
        } else {
            supervisor.email
        }
        
        val contenido = "Ingresa el email y la nueva contraseña para el supervisor '${supervisor.usuario}'.\n\n⚠️ Si el usuario no existe en Firebase Auth, se creará automáticamente."
        
        val campos = listOf(
            CampoEntrada("email", "Email del supervisor", 
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 
                emailSinDominio, esEmail = true),
            CampoEntrada("nueva", "Nueva contraseña", 
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD),
            CampoEntrada("confirmar", "Confirmar nueva contraseña", 
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        )
        
        mostrarDialogoPersonalizado(
            icono = "🔑",
            titulo = "Cambiar Credenciales: ${supervisor.usuario}",
            contenido = contenido,
            textoBotonConfirmar = "CAMBIAR",
            soloConfirmar = false,
            camposEntrada = campos
        ) { valores ->
            val email = valores["email"]?.trim() ?: ""
            val contraseñaNueva = valores["nueva"]?.trim() ?: ""
            val confirmarcontraseña = valores["confirmar"]?.trim() ?: ""
            
            if (email.isNotEmpty() && contraseñaNueva.isNotEmpty() && confirmarcontraseña.isNotEmpty()) {
                if (contraseñaNueva == confirmarcontraseña) {
                    cambiarcontraseñaSupervisor(supervisor, email, "", contraseñaNueva)
                } else {
                    txtEstado.text = "Las contraseñas nuevas no coinciden"
                }
            } else {
                txtEstado.text = "Email y nueva contraseña son obligatorios"
            }
        }
    }
    
    /**
     * Muestra el diálogo para crear un nuevo supervisor
     */
    private fun mostrarDialogoAgregarSupervisor() {
        val contenido = "Ingresa los datos del nuevo supervisor:"
        
        val campos = listOf(
            CampoEntrada("usuario", "Nombre de usuario del supervisor"),
            CampoEntrada("email", "Email del supervisor (ej: supervisor)", 
                android.text.InputType.TYPE_CLASS_TEXT,
                esEmail = true),
            CampoEntrada("contraseña", "contraseña del supervisor", 
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        )
        
        mostrarDialogoPersonalizado(
            icono = "➕",
            titulo = "Agregar Nuevo Supervisor",
            contenido = contenido,
            textoBotonConfirmar = "CREAR",
            soloConfirmar = false,
            camposEntrada = campos
        ) { valores ->
            val usuarioSupervisor = valores["usuario"]?.trim() ?: ""
            val emailUsuario = valores["email"]?.trim() ?: ""
            val contraseña = valores["contraseña"]?.trim() ?: ""
            
            if (usuarioSupervisor.isEmpty()) {
                txtEstado.text = "El nombre de usuario es obligatorio"
                return@mostrarDialogoPersonalizado
            }
            
            if (emailUsuario.isEmpty()) {
                txtEstado.text = "El email del supervisor es obligatorio"
                return@mostrarDialogoPersonalizado
            }
            
            if (contraseña.isEmpty()) {
                txtEstado.text = "La contraseña es obligatoria"
                return@mostrarDialogoPersonalizado
            }
            
            // Agregar el dominio automáticamente si no lo tiene
            val email = if (emailUsuario.contains("@")) {
                emailUsuario
            } else {
                "$emailUsuario@saboresexpress.com.ar"
            }
            
            if (!email.endsWith("@saboresexpress.com.ar")) {
                txtEstado.text = "El email debe terminar en @saboresexpress.com.ar"
                return@mostrarDialogoPersonalizado
            }
            
            crearNuevoSupervisor(usuarioSupervisor, email, contraseña)
        }
    }
    
    /**
     * Crea un nuevo supervisor
     */
    private fun crearNuevoSupervisor(usuarioSupervisor: String, email: String, contraseña: String) {
        lifecycleScope.launch {
            try {
                txtEstado.text = "Creando supervisor $usuarioSupervisor..."
                
                // Verificar si el supervisor ya existe
                val snapshot = firestore.collection("supervisores")
                    .whereEqualTo("usuario", usuarioSupervisor)
                    .get()
                    .await()
                
                if (!snapshot.isEmpty) {
                    txtEstado.text = "El supervisor $usuarioSupervisor ya existe"
                    return@launch
                }
                
                // Verificar si el email ya está en uso
                val snapshotEmail = firestore.collection("supervisores")
                    .whereEqualTo("email", email)
                    .get()
                    .await()
                
                if (!snapshotEmail.isEmpty) {
                    txtEstado.text = "El email $email ya está en uso por otro supervisor"
                    return@launch
                }
                
                // Crear el supervisor sin Firebase Auth
                val nuevoSupervisor = Supervisor(
                    usuario = usuarioSupervisor,
                    email = email,
                    contraseña = contraseña,
                    localesAsignados = emptyList(),
                    creadoPor = usuarioActual?.usuarioId ?: "",
                    fechaCreacion = DateHelper.getDateFormat("yyyy-MM-dd").format(Date()),
                    firebaseAuthUid = null // UID de Firebase Auth nulo
                )
                
                // Guardar en Firebase
                val docRef = firestore.collection("supervisores").document()
                docRef.set(nuevoSupervisor.copy(id = docRef.id)).await()
                
                // Agregar a la lista local
                supervisores.add(nuevoSupervisor.copy(id = docRef.id))
                mostrarSupervisores()
                
                txtEstado.text = "Supervisor $usuarioSupervisor creado exitosamente"
                
                // Mostrar credenciales
                mostrarCredencialesSupervisor(usuarioSupervisor, email, contraseña)
                
                Log.d("GestionLocalesActivity", "Supervisor $usuarioSupervisor creado exitosamente con email: $email")
                
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error creando supervisor", e)
                txtEstado.text = "Error creando supervisor: ${e.message}"
            }
        }
    }
    
    /**
     * Muestra las credenciales del supervisor creado
     */
    private fun mostrarCredencialesSupervisor(usuarioSupervisor: String, email: String, contraseña: String) {
        val mensaje = """
            🎉 Supervisor creado exitosamente!
            
            👤 Usuario: $usuarioSupervisor
            📧 Email: $email
            🔑 contraseña: $contraseña
            
            ⚠️ Guarda estas credenciales para que el supervisor pueda acceder
            Usa el email y contraseña para iniciar sesión
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("Supervisor Creado")
            .setMessage(mensaje)
            .setPositiveButton("OK") { _, _ ->
                // Recargar la lista de supervisores
                cargarSupervisores()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * Cambia las credenciales del supervisor (email y contraseña) en Firestore y Firebase Auth
     */
    private fun cambiarcontraseñaSupervisor(supervisor: Supervisor, email: String, contraseñaActual: String, contraseñaNueva: String) {
        lifecycleScope.launch {
            try {
                txtEstado.text = "Verificando datos..."
                
                // Verificar contraseña actual solo si se proporcionó
                if (contraseñaActual.isNotEmpty() && supervisor.contraseña != contraseñaActual) {
                    txtEstado.text = "❌ contraseña actual incorrecta"
                    return@launch
                }
                
                // Validar nueva contraseña
                if (contraseñaNueva.length < 4) {
                    txtEstado.text = "La nueva contraseña debe tener al menos 4 caracteres"
                    return@launch
                }
                
                // Validar email
                if (email.isEmpty() || !email.contains("@")) {
                    txtEstado.text = "El email debe ser válido"
                    return@launch
                }
                
                txtEstado.text = "Actualizando credenciales en Firestore..."
                
                // Crear supervisor actualizado con nuevo email y contraseña (sin FirebaseAuth)
                val supervisorActualizado = supervisor.copy(
                    email = email,
                    contraseña = contraseñaNueva,
                    firebaseAuthUid = null
                )
                
                // Guardar en Firestore
                firestore.collection("supervisores").document(supervisor.id).set(supervisorActualizado).await()
                
                // Actualizar en la lista local
                val indice = supervisores.indexOfFirst { it.id == supervisor.id }
                if (indice != -1) {
                    supervisores[indice] = supervisorActualizado
                    mostrarSupervisores()
                }
                
                txtEstado.text = "✅ Credenciales actualizadas exitosamente"
                
                val mensajeConfirmacion = "Las credenciales del supervisor '${supervisor.usuario}' han sido actualizadas exitosamente.\n\nEmail: $email"
                
                AlertDialog.Builder(this@GestionLocalesActivity)
                    .setTitle("✅ Credenciales Actualizadas")
                    .setMessage(mensajeConfirmacion)
                    .setPositiveButton("OK", null)
                    .show()
                
                Log.d("GestionLocalesActivity", "Credenciales del supervisor ${supervisor.usuario} actualizadas exitosamente")
                
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error cambiando credenciales del supervisor", e)
                txtEstado.text = "Error cambiando credenciales: ${e.message}"
            }
        }
    }
    
    /**
     * Muestra el diálogo para asignar locales a un supervisor
     */
    private fun mostrarDialogoAsignarLocales(supervisor: Supervisor) {
        lifecycleScope.launch {
            try {
                txtEstado.text = "Cargando locales para asignación..."
                
                // 1. Descargar TODOS los locales activos
                val snapshot = firestore.collection("locales")
                    .whereEqualTo("activo", true)
                    .get()
                    .await()
                
                val todosLosLocales = mutableListOf<Local>()
                snapshot.documents.forEach { doc ->
                    val local = doc.toObject(Local::class.java)?.copy(id = doc.id)
                    if (local != null) {
                        todosLosLocales.add(local)
                        if (locales.none { it.id == local.id }) locales.add(local)
                    }
                }
                
                // 2. Identificar qué locales ya pertenecen a OTROS supervisores
                val idsAsignadosAOtros = supervisores
                    .filter { it.id != supervisor.id }
                    .flatMap { it.localesAsignados }
                    .toSet()
                
                // 3. Separar en asignados a este supervisor y disponibles (huérfanos)
                val localesAsignados = todosLosLocales.filter { supervisor.localesAsignados.contains(it.id) }
                val localesDisponibles = todosLosLocales.filter { !supervisor.localesAsignados.contains(it.id) && !idsAsignadosAOtros.contains(it.id) }
                
                val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                val isDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
                
                val layoutPrincipal = LinearLayout(this@GestionLocalesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 16, 16, 16)
                }
                
                val titulo = TextView(this@GestionLocalesActivity).apply {
                    text = "Asignar Locales a ${supervisor.usuario}"
                    textSize = 18f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, 16)
                }
                layoutPrincipal.addView(titulo)
                
                // Buscador
                val searchBox = EditText(this@GestionLocalesActivity).apply {
                    hint = "🔍 Buscar local..."
                    setPadding(16, 16, 16, 16)
                    setBackgroundResource(R.drawable.edit_text_background)
                    setTextColor(if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                    setHintTextColor(if (isDark) android.graphics.Color.parseColor("#8A8A8A") else android.graphics.Color.parseColor("#999999"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 16) }
                }
                layoutPrincipal.addView(searchBox)
                
                val scroll = android.widget.ScrollView(this@GestionLocalesActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                }
                
                val containerListas = LinearLayout(this@GestionLocalesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                
                val checkboxes = mutableMapOf<String, android.widget.CheckBox>()
                val allCheckboxViews = mutableListOf<View>()
                
                // Título Asignados
                val tituloAsignados = TextView(this@GestionLocalesActivity).apply {
                    text = "✅ Asignados Actualmente (${localesAsignados.size})"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                    setPadding(0, 8, 0, 8)
                }
                containerListas.addView(tituloAsignados)
                
                if (localesAsignados.isEmpty()) {
                    containerListas.addView(TextView(this@GestionLocalesActivity).apply { text = "Ninguno" })
                } else {
                    localesAsignados.forEach { local ->
                        val cb = android.widget.CheckBox(this@GestionLocalesActivity).apply {
                            text = "🏪 ${local.nombre}"
                            isChecked = true
                            tag = local.nombre.lowercase()
                        }
                        checkboxes[local.id] = cb
                        allCheckboxViews.add(cb)
                        containerListas.addView(cb)
                    }
                }
                
                // Título Disponibles
                val tituloDisp = TextView(this@GestionLocalesActivity).apply {
                    text = "➕ Disponibles (Sin Asignar) (${localesDisponibles.size})"
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
                    setPadding(0, 16, 0, 8)
                }
                containerListas.addView(tituloDisp)
                
                if (localesDisponibles.isEmpty()) {
                    containerListas.addView(TextView(this@GestionLocalesActivity).apply { text = "No hay locales huérfanos" })
                } else {
                    localesDisponibles.forEach { local ->
                        val cb = android.widget.CheckBox(this@GestionLocalesActivity).apply {
                            text = "🏪 ${local.nombre}"
                            isChecked = false
                            tag = local.nombre.lowercase()
                        }
                        checkboxes[local.id] = cb
                        allCheckboxViews.add(cb)
                        containerListas.addView(cb)
                    }
                }
                
                searchBox.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val query = s.toString().lowercase()
                        allCheckboxViews.forEach { cb ->
                            if ((cb.tag as String).contains(query)) {
                                cb.visibility = View.VISIBLE
                            } else {
                                cb.visibility = View.GONE
                            }
                        }
                    }
                })
                
                scroll.addView(containerListas)
                layoutPrincipal.addView(scroll)
                
                val dialog = AlertDialog.Builder(this@GestionLocalesActivity)
                    .setTitle("Gestión de Locales")
                    .setView(layoutPrincipal)
                    .setCancelable(false)
                    .setPositiveButton("ASIGNAR", null)
                    .setNegativeButton("Cancelar", null)
                    .create()
                
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val localesSeleccionados = checkboxes.filter { it.value.isChecked }.keys.toList()
                        asignarLocalesASupervisor(supervisor, localesSeleccionados)
                        dialog.dismiss()
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        dialog.dismiss()
                    }
                }
                
                dialog.show()
                val window = dialog.window
                window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.9).toInt(),
                    (resources.displayMetrics.heightPixels * 0.8).toInt()
                )
                
                txtEstado.text = ""
                
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error mostrando diálogo de asignación", e)
                txtEstado.text = "Error: ${e.message}"
            }
        }
    }
    
    /**
     * Asigna locales a un supervisor
     */
    private fun asignarLocalesASupervisor(supervisor: Supervisor, localesAsignados: List<String>) {
        lifecycleScope.launch {
            try {
                txtEstado.text = "Asignando locales a ${supervisor.usuario}..."
                
                val supervisorActualizado = supervisor.copy(localesAsignados = localesAsignados)
                
                // Actualizar en Firebase
                firestore.collection("supervisores").document(supervisor.id).set(supervisorActualizado).await()
                
                // Actualizar en la lista local
                val indice = supervisores.indexOfFirst { it.id == supervisor.id }
                if (indice != -1) {
                    supervisores[indice] = supervisorActualizado
                    mostrarSupervisores()
                }
                
                txtEstado.text = "Locales asignados exitosamente"
                
                Log.d("GestionLocalesActivity", "Locales asignados a ${supervisor.usuario}: ${localesAsignados.joinToString(", ")}")
                
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error asignando locales", e)
                txtEstado.text = "Error asignando locales: ${e.message}"
            }
        }
    }
    
    /**
     * Cambia el estado de asistente de un supervisor
     */
    private fun toggleAsistenteSupervisor(supervisor: Supervisor) {
        val nuevoEstado = !supervisor.esAsistente
        val mensaje = if (nuevoEstado) {
            "¿Deseas designar a ${supervisor.usuario} como asistente?\n\n" +
            "⚠️ Como asistente, tendrá acceso completo como el maestro:\n" +
            "• Ver todos los locales\n" +
            "• Ver todos los supervisores\n" +
            "• Gestionar locales y supervisores"
        } else {
            "¿Deseas quitar a ${supervisor.usuario} como asistente?\n\n" +
            "⚠️ Perderá el acceso completo y solo verá sus locales asignados."
        }
        
        AlertDialog.Builder(this)
            .setTitle(if (nuevoEstado) "Designar como Asistente" else "Quitar como Asistente")
            .setMessage(mensaje)
            .setPositiveButton("SÍ, ${if (nuevoEstado) "DESIGNAR" else "QUITAR"}") { _, _ ->
                actualizarEstadoAsistente(supervisor, nuevoEstado)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Actualiza el estado de asistente de un supervisor en Firebase
     */
    private fun actualizarEstadoAsistente(supervisor: Supervisor, esAsistente: Boolean) {
        lifecycleScope.launch {
            try {
                txtEstado.text = if (esAsistente) {
                    "Designando ${supervisor.usuario} como asistente..."
                } else {
                    "Quitando ${supervisor.usuario} como asistente..."
                }
                
                val supervisorActualizado = supervisor.copy(esAsistente = esAsistente)
                
                // Actualizar en Firebase
                firestore.collection("supervisores").document(supervisor.id).set(supervisorActualizado).await()
                
                // Actualizar en la lista local
                val indice = supervisores.indexOfFirst { it.id == supervisor.id }
                if (indice != -1) {
                    supervisores[indice] = supervisorActualizado
                    mostrarSupervisores()
                }
                
                txtEstado.text = if (esAsistente) {
                    "✅ ${supervisor.usuario} ahora es asistente del maestro"
                } else {
                    "✅ Se quitó a ${supervisor.usuario} como asistente"
                }
                
                Log.d("GestionLocalesActivity", "Estado de asistente actualizado para ${supervisor.usuario}: $esAsistente")
                
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error actualizando estado de asistente", e)
                txtEstado.text = "Error actualizando estado: ${e.message}"
            }
        }
    }
    
    /**
     * Muestra el diálogo para eliminar un supervisor
     */
    private fun mostrarDialogoEliminarSupervisor(supervisor: Supervisor) {
        val contenido = "⚠️ ADVERTENCIA: Esta acción eliminará permanentemente el supervisor.\n\n¿Estás seguro de que quieres eliminar a ${supervisor.usuario}?"
        
        AlertDialog.Builder(this)
            .setTitle("Eliminar Supervisor")
            .setMessage(contenido)
            .setPositiveButton("SÍ, ELIMINAR") { _, _ ->
                eliminarSupervisor(supervisor)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Elimina un supervisor
     */
    private fun eliminarSupervisor(supervisor: Supervisor) {
        lifecycleScope.launch {
            try {
                txtEstado.text = "Eliminando supervisor ${supervisor.usuario}..."
                
                // Si tiene firebaseAuthUid, DEBE eliminarse de Firebase Auth primero
                if (!supervisor.firebaseAuthUid.isNullOrEmpty()) {
                    try {
                        // Intentar eliminar usando Firebase Functions (HTTP callable)
                        val functions = Firebase.functions
                        val deleteUser = functions.getHttpsCallable("deleteUser")
                        val data = hashMapOf("uid" to supervisor.firebaseAuthUid)
                        deleteUser.call(data).await()
                        
                        Log.d("GestionLocalesActivity", "✅ Usuario de Firebase Auth eliminado: ${supervisor.firebaseAuthUid}")
                        
                    } catch (e: Exception) {
                        // Si no se puede eliminar de Firebase Auth, NO eliminar de Firestore
                        Log.e("GestionLocalesActivity", "❌ No se pudo eliminar usuario de Firebase Auth: ${e.message}")
                        txtEstado.text = "Error: No se pudo eliminar el usuario de Firebase Auth. El supervisor no fue eliminado."
                        return@launch
                    }
                }
                
                // Solo eliminar de Firestore si se pudo eliminar de Firebase Auth (o si no tenía firebaseAuthUid)
                firestore.collection("supervisores").document(supervisor.id).delete().await()
                
                // Remover de la lista local
                supervisores.removeAll { it.id == supervisor.id }
                mostrarSupervisores()
                
                txtEstado.text = "✅ Supervisor '${supervisor.usuario}' eliminado exitosamente"
                Log.d("GestionLocalesActivity", "Supervisor ${supervisor.usuario} eliminado exitosamente")
                
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error eliminando supervisor", e)
                txtEstado.text = "Error eliminando supervisor: ${e.message}"
            }
        }
    }

    /**
     * Muestra las credenciales de Firebase Auth del local
     * Si no tiene firebaseAuthUid, muestra mensaje indicando que necesita generarlos
     */
    private fun mostrarUsuariosYCredenciales(local: Local) {
        val usuarioAdmin = local.usuarios.values.find { it.rol == "admin_local" }
        val email = local.email.ifEmpty { usuarioAdmin?.email ?: "" }.ifEmpty { "—" }
        val password = usuarioAdmin?.contraseña ?: ""
        val passwordDisplay = if (password.isNotEmpty()) password else "—"
        val fechaCreacion = local.fechaCreacion.ifEmpty { "—" }
        val debeCambiar = if (local.debeCambiarContraseña) "Sí (pendiente)" else "No"
        val enActividad = if (local.activo) "Sí" else "No"
        val usuariosCount = local.usuarios.size

        val mensaje = buildString {
            append("🏪 ${local.nombre}\n\n")
            append("📧 Email: $email\n")
            append("🔑 Clave: $passwordDisplay\n")
            append("📅 Fecha de Creación: $fechaCreacion\n")
            append("🔄 En Actividad: $enActividad\n")
            append("⚠️ Debe Cambiar Clave: $debeCambiar\n")
            append("👥 Usuarios: $usuariosCount\n")
        }

        mostrarDialogoPersonalizado(
            icono = "👥",
            titulo = "Credenciales: ${local.nombre}",
            contenido = mensaje,
            textoBotonConfirmar = "OK",
            soloConfirmar = true
        )
    }

    @Suppress("UNNECESSARY_SAFE_CALL")
    private fun mostrarDialogoPersonalizado(
        icono: String,
        titulo: String,
        contenido: String,
        textoBotonConfirmar: String = "CONFIRMAR",
        soloConfirmar: Boolean = false,
        camposEntrada: List<CampoEntrada> = emptyList(),
        onConfirmar: ((Map<String, String>) -> Unit)? = null
    ) {
        // Usar layout con EditText predefinidos para evitar problemas de timing
        val dialogView = layoutInflater.inflate(R.layout.dialog_gestion_locales_input, null)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Configurar elementos del diálogo
        dialogView.findViewById<TextView>(R.id.iconHeader)?.text = icono
        dialogView.findViewById<TextView>(R.id.titleHeader)?.text = titulo
        dialogView.findViewById<TextView>(R.id.textDescription)?.text = contenido

        // Obtener los EditText del layout
        val editText1 = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editText1)
        val inputLayout1 = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.inputLayout1)
        val editText2 = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editText2)
        val inputLayout2 = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.inputLayout2)
        val editText3 = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editText3)
        val inputLayout3 = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.inputLayout3)
        
        val camposMap = mutableMapOf<String, EditText>()
        val camposEmailMap = mutableMapOf<String, Boolean>() // Mapa para saber qué campos son de email
        var primerEditText: EditText? = null

        // Configurar campos según la cantidad de camposEntrada
        when (camposEntrada.size) {
            0 -> {
                // Sin campos
                inputLayout1?.visibility = android.view.View.GONE
                inputLayout2?.visibility = android.view.View.GONE
            }
            1 -> {
                // Un solo campo
                val campo = camposEntrada[0]
                inputLayout1?.hint = campo.hint
                editText1?.inputType = campo.tipoInput
                if (campo.textoInicial.isNotEmpty()) {
                    editText1?.setText(campo.textoInicial)
                }
                
                // Configurar autocompletado de email si es necesario
                if (campo.esEmail && editText1 != null) {
                    configurarAutocompletadoEmail(editText1, inputLayout1)
                    camposEmailMap[campo.id] = true
                }
                
                camposMap[campo.id] = editText1!!
                primerEditText = editText1
                inputLayout2?.visibility = android.view.View.GONE
                inputLayout3?.visibility = android.view.View.GONE
            }
            2 -> {
                // Dos campos
                val campo1 = camposEntrada[0]
                inputLayout1?.hint = campo1.hint
                editText1?.inputType = campo1.tipoInput
                if (campo1.textoInicial.isNotEmpty()) {
                    editText1?.setText(campo1.textoInicial)
                }
                
                // Configurar autocompletado de email si es necesario
                if (campo1.esEmail && editText1 != null) {
                    configurarAutocompletadoEmail(editText1, inputLayout1)
                }
                
                camposMap[campo1.id] = editText1!!
                primerEditText = editText1
                
                val campo2 = camposEntrada[1]
                inputLayout2?.hint = campo2.hint
                inputLayout2?.visibility = android.view.View.VISIBLE
                editText2?.inputType = campo2.tipoInput
                if (campo2.textoInicial.isNotEmpty()) {
                    editText2?.setText(campo2.textoInicial)
                }
                
                // Configurar autocompletado de email si es necesario
                if (campo2.esEmail && editText2 != null) {
                    configurarAutocompletadoEmail(editText2, inputLayout2)
                }
                
                camposMap[campo2.id] = editText2!!
                inputLayout3?.visibility = android.view.View.GONE
                
                // Configurar navegación entre campos
                editText1?.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                editText1?.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                        editText2?.requestFocus()
                        true
                    } else {
                        false
                    }
                }
            }
            else -> {
                // Dos campos
                val campo1 = camposEntrada[0]
                inputLayout1?.hint = campo1.hint
                editText1?.inputType = campo1.tipoInput
                if (campo1.textoInicial.isNotEmpty()) {
                    editText1?.setText(campo1.textoInicial)
                }
                
                // Configurar autocompletado de email si es necesario
                if (campo1.esEmail && editText1 != null) {
                    configurarAutocompletadoEmail(editText1, inputLayout1)
                    camposEmailMap[campo1.id] = true
                }
                
                camposMap[campo1.id] = editText1!!
                primerEditText = editText1
                
                val campo2 = camposEntrada[1]
                inputLayout2?.hint = campo2.hint
                inputLayout2?.visibility = android.view.View.VISIBLE
                editText2?.inputType = campo2.tipoInput
                if (campo2.textoInicial.isNotEmpty()) {
                    editText2?.setText(campo2.textoInicial)
                }
                
                // Configurar autocompletado de email si es necesario
                if (campo2.esEmail && editText2 != null) {
                    configurarAutocompletadoEmail(editText2, inputLayout2)
                    camposEmailMap[campo2.id] = true
                }
                
                camposMap[campo2.id] = editText2!!
                
                // Tercer campo (si existe)
                if (camposEntrada.size >= 3) {
                    val campo3 = camposEntrada[2]
                    inputLayout3?.hint = campo3.hint
                    inputLayout3?.visibility = android.view.View.VISIBLE
                    editText3?.inputType = campo3.tipoInput
                    if (campo3.textoInicial.isNotEmpty()) {
                        editText3?.setText(campo3.textoInicial)
                    }
                    
                    // Configurar autocompletado de email si es necesario
                    if (campo3.esEmail && editText3 != null) {
                        configurarAutocompletadoEmail(editText3, inputLayout3)
                        camposEmailMap[campo3.id] = true
                    }
                    
                    camposMap[campo3.id] = editText3!!
                    
                    // Configurar navegación entre campos
                    editText1?.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                    editText1?.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                            editText2?.requestFocus()
                            true
                        } else {
                            false
                        }
                    }
                    
                    editText2?.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                    editText2?.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                            editText3?.requestFocus()
                            true
                        } else {
                            false
                        }
                    }
                } else {
                    inputLayout3?.visibility = android.view.View.GONE
                    
                    // Configurar navegación entre campos (solo 2 campos)
                    editText1?.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                    editText1?.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                            editText2?.requestFocus()
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        }

        // Configurar botones
        dialogView.findViewById<Button>(R.id.btnCancel)?.apply {
            if (soloConfirmar) {
                visibility = android.view.View.GONE
            } else {
                setOnClickListener { dialog.dismiss() }
            }
        }

        dialogView.findViewById<Button>(R.id.btnConfirm)?.apply {
            text = textoBotonConfirmar
            setOnClickListener {
                // Obtener valores y agregar dominio automáticamente para campos de email
                val valores = camposMap.mapValues { (campoId, editText) ->
                    var valor = editText.text.toString().trim()
                    // Si es un campo de email y no contiene "@", agregar el dominio automáticamente
                    if (camposEmailMap[campoId] == true && valor.isNotEmpty() && !valor.contains("@")) {
                        valor = "$valor@saboresexpress.com.ar"
                    }
                    valor
                }
                onConfirmar?.invoke(valores)
                dialog.dismiss()
            }
        }

        // Configurar window ANTES de mostrar
        dialog.window?.let { window ->
            @Suppress("DEPRECATION")
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        dialog.show()
        
        // Configurar window DESPUÉS de mostrar (importante para que funcione)
        dialog.window?.let { window ->
            @Suppress("DEPRECATION")
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or 
                                  android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        
        // Dar foco al primer EditText y mostrar teclado
        primerEditText?.let { editText ->
            editText.post {
                editText.requestFocus()
                editText.isCursorVisible = true
                
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                
                // Múltiples intentos para asegurar que el teclado aparezca
                editText.postDelayed({
                    if (!editText.hasFocus()) {
                        editText.requestFocus()
                    }
                    imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }, 100)
                
                editText.postDelayed({
                    if (!imm.isActive(editText)) {
                        editText.requestFocus()
                        imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }, 300)
            }
        }
    }
    
    /**
     * Configura el autocompletado de email para un EditText
     * Similar a la implementación en LoginActivity
     */
    private fun configurarAutocompletadoEmail(
        editText: com.google.android.material.textfield.TextInputEditText,
        inputLayout: com.google.android.material.textfield.TextInputLayout?
    ) {
        // Configurar suffix text en el TextInputLayout
        inputLayout?.setSuffixText("@saboresexpress.com.ar")
        inputLayout?.setSuffixTextColor(android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#757575") // Color gris para el suffix
        ))
        
        // Agregar TextWatcher para prevenir que el usuario escriba "@" o el dominio
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Si el usuario intenta escribir "@", prevenir que lo escriba
                val texto = s.toString()
                if (texto.contains("@")) {
                    // Remover el "@" y todo lo que venga después
                    val textoLimpio = texto.substringBefore("@")
                    if (editText.text.toString() != textoLimpio) {
                        editText.setText(textoLimpio)
                        editText.setSelection(textoLimpio.length)
                    }
                }
            }
            
            override fun afterTextChanged(s: Editable?) {}
        })
        
        // Agregar filtro para prevenir que se escriba "@" o el dominio
        editText.filters = arrayOf(android.text.InputFilter { source, _, _, _, _, _ ->
            // Si el texto contiene "@" o el dominio, no permitirlo
            val texto = source.toString()
            if (texto.contains("@") || texto.contains("saboresexpress.com.ar")) {
                return@InputFilter ""
            }
            null
        })
    }

    data class CampoEntrada(
        val id: String,
        val hint: String,
        val tipoInput: Int = android.text.InputType.TYPE_CLASS_TEXT,
        val textoInicial: String = "",
        val esEmail: Boolean = false // Flag para indicar si es un campo de email que necesita autocompletado
    )

    private fun mostrarDialogoAgregarLocal() {
        val contenido = "Ingresa los datos del nuevo local:"
        
        val campos = listOf(
            CampoEntrada("nombre", "Nombre del local (ej: WILDE)"),
            CampoEntrada("email", "Email del local (ej: wilde)", 
                android.text.InputType.TYPE_CLASS_TEXT,
                esEmail = true)
        )
        
        mostrarDialogoPersonalizado(
            icono = "➕",
            titulo = "Agregar Nuevo Local",
            contenido = contenido,
            textoBotonConfirmar = "CREAR",
            soloConfirmar = false,
            camposEntrada = campos
        ) { valores ->
            val nombreLocal = valores["nombre"]?.trim() ?: ""
            val emailUsuario = valores["email"]?.trim() ?: ""
            
            if (nombreLocal.isEmpty()) {
                txtEstado.text = "El nombre del local no puede estar vacío"
                return@mostrarDialogoPersonalizado
            }
            
            if (emailUsuario.isEmpty()) {
                txtEstado.text = "El email del local es obligatorio"
                return@mostrarDialogoPersonalizado
            }
            
            // Agregar el dominio automáticamente si no lo tiene
            val email = if (emailUsuario.contains("@")) {
                emailUsuario
            } else {
                "$emailUsuario@saboresexpress.com.ar"
            }
            
            if (!email.endsWith("@saboresexpress.com.ar")) {
                txtEstado.text = "El email debe terminar en @saboresexpress.com.ar"
                return@mostrarDialogoPersonalizado
            }
            
            crearNuevoLocal(nombreLocal, email)
        }
    }

    private fun crearNuevoLocal(nombreLocal: String, email: String) {
        val añoActual = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val contraseñaDefault = "Inicial$añoActual"
        
        lifecycleScope.launch {
            try {
                txtEstado.text = "Creando local $nombreLocal..."
                
                // Verificar si el local ya existe
                val snapshot = firestore.collection("locales")
                    .whereEqualTo("nombre", nombreLocal)
                    .get()
                    .await()
                
                if (!snapshot.isEmpty) {
                    txtEstado.text = "El local $nombreLocal ya existe"
                    return@launch
                }
                
                // Verificar si el email ya está en uso
                val snapshotEmail = firestore.collection("locales")
                    .whereEqualTo("email", email)
                    .get()
                    .await()
                
                if (!snapshotEmail.isEmpty) {
                    txtEstado.text = "El email $email ya está en uso por otro local"
                    return@launch
                }
                
                // Crear usuario admin para el nuevo local (sin Firebase Auth)
                val usuarioAdmin = UsuarioLocal(
                    id = "admin_${nombreLocal.lowercase()}",
                    usuario = "admin_${nombreLocal.lowercase()}",
                    email = email,
                    contraseña = contraseñaDefault,
                    rol = "admin_local",
                    firebaseAuthUid = null,
                    debeCambiarContraseña = true
                )
                
                // Crear el local
                val nuevoLocal = Local(
                    nombre = nombreLocal,
                    email = email,
                    fechaCreacion = DateHelper.getDateFormat("yyyy-MM-dd").format(Date()),
                    activo = true,
                    usuarios = mapOf(usuarioAdmin.id to usuarioAdmin),
                    firebaseAuthUid = null,
                    debeCambiarContraseña = true
                )
                
                // Guardar en Firebase
                val docRef = firestore.collection("locales").document(nombreLocal)
                docRef.set(nuevoLocal).await()
                
                // Agregar a la lista local
                locales.add(nuevoLocal.copy(id = nombreLocal))
                mostrarLocales()
                
                txtEstado.text = "Local $nombreLocal creado exitosamente"
                
                // Mostrar credenciales
                mostrarCredenciales(nombreLocal, usuarioAdmin, email)
                
                Log.d("GestionLocalesActivity", "Local $nombreLocal creado exitosamente con email: $email")
                
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error creando local", e)
                txtEstado.text = "Error creando local: ${e.message}"
            }
        }
    }

    private fun generarcontraseña(): String {
        val caracteres = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8)
            .map { caracteres.random() }
            .joinToString("")
    }

    private fun mostrarCredenciales(nombreLocal: String, usuario: UsuarioLocal, email: String) {
        val mensaje = """
            🎉 Local creado exitosamente!
            
            📍 Local: $nombreLocal
            📧 Email: $email
            🔑 contraseña: ${usuario.contraseña}
            
            ⚠️ Guarda estas credenciales para acceder al local
            Usa el email y contraseña para iniciar sesión
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("Local Creado")
            .setMessage(mensaje)
            .setPositiveButton("OK") { _, _ ->
                // Recargar la lista de locales
                cargarLocales()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun mostrarDialogoEliminarLocal(local: Local) {
        val contenido = "⚠️ ADVERTENCIA: Esta acción eliminará permanentemente el local y todos sus datos.\n\nPara confirmar, ingresa la contraseña del local:"
        
        val contraseñaGuardada = obtenercontraseñaGuardada(local.id)
        
        val campos = listOf(
            CampoEntrada("contraseña", "contraseña del local", 
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                contraseñaGuardada)
        )
        
        mostrarDialogoPersonalizado(
            icono = "🗑️",
            titulo = "Eliminar Local: ${local.nombre}",
            contenido = contenido,
            textoBotonConfirmar = "ELIMINAR",
            soloConfirmar = false,
            camposEntrada = campos
        ) { valores ->
            val contraseñaIngresada = valores["contraseña"]?.trim() ?: ""
            
            if (contraseñaIngresada.isNotEmpty()) {
                eliminarLocal(local, contraseñaIngresada)
            } else {
                txtEstado.text = "Debes ingresar la contraseña para eliminar el local"
            }
        }
    }
    
    private fun mostrarDialogoBlanquearClave(local: Local) {
        val contenido = "¿Blanquear la clave del local '${local.nombre}'?\n\nSe restablecerá a \"Inicial2026\" y el usuario deberá cambiarla al ingresar."

        mostrarDialogoPersonalizado(
            icono = "🔑",
            titulo = "Blanqueo de Clave: ${local.nombre}",
            contenido = contenido,
            textoBotonConfirmar = "BLANQUEAR",
            soloConfirmar = false
        ) { _ ->
            blanquearClave(local)
        }
    }

    private fun blanquearClave(local: Local) {
        lifecycleScope.launch {
            try {
                txtEstado.text = "Blanqueando clave..."

                val usuarioAdmin = local.usuarios.values.find { it.rol == "admin_local" }
                if (usuarioAdmin == null) {
                    txtEstado.text = "No se encontró el usuario administrador del local"
                    return@launch
                }

                val nuevaClave = "Inicial2026"

                val usuarioActualizado = usuarioAdmin.copy(
                    contraseña = nuevaClave,
                    firebaseAuthUid = null,
                    debeCambiarContraseña = true
                )

                val usuariosActualizados = local.usuarios.toMutableMap()
                usuariosActualizados[usuarioAdmin.id] = usuarioActualizado

                val localActualizado = local.copy(
                    usuarios = usuariosActualizados,
                    firebaseAuthUid = null,
                    debeCambiarContraseña = true
                )

                firestore.collection("locales").document(local.nombre).set(localActualizado).await()

                val indice = locales.indexOfFirst { it.id == local.id }
                if (indice != -1) {
                    locales[indice] = localActualizado
                    mostrarLocales()
                }

                txtEstado.text = "✅ Clave blanqueada exitosamente"

                AlertDialog.Builder(this@GestionLocalesActivity)
                    .setTitle("✅ Blanqueo de Clave")
                    .setMessage("La clave del local '${local.nombre}' ha sido blanqueada a \"$nuevaClave\".\n\nEl usuario deberá cambiarla al ingresar.")
                    .setPositiveButton("OK", null)
                    .show()

                Log.d("GestionLocalesActivity", "Clave blanqueada para local ${local.nombre}")

            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error blanqueando clave", e)
                txtEstado.text = "Error blanqueando clave: ${e.message}"
            }
        }
    }
    
    private fun eliminarLocal(local: Local, contraseñaIngresada: String) {
        lifecycleScope.launch {
            try {
                txtEstado.text = "Verificando contraseña y eliminando local..."
                
                // Buscar el usuario administrador del local
                val usuarioAdmin = local.usuarios.values.find { it.rol == "admin_local" }
                if (usuarioAdmin == null) {
                    txtEstado.text = "No se encontró el usuario administrador del local"
                    return@launch
                }
                
                // Verificar contraseña
                if (usuarioAdmin.contraseña != contraseñaIngresada) {
                    txtEstado.text = "❌ contraseña incorrecta. No se puede eliminar el local."
                    return@launch
                }
                
                // Guardar contraseña para uso futuro
                guardarcontraseñaLocal(local.id, contraseñaIngresada)
                
                // Mostrar diálogo de confirmación final
                AlertDialog.Builder(this@GestionLocalesActivity)
                    .setTitle("⚠️ Confirmación Final")
                    .setMessage("¿Estás seguro de que quieres eliminar permanentemente el local '${local.nombre}'?\n\nEsta acción NO se puede deshacer.")
                    .setPositiveButton("SÍ, ELIMINAR") { _, _ ->
                        ejecutarEliminacionLocal(local)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error verificando contraseña", e)
                txtEstado.text = "Error verificando contraseña: ${e.message}"
            }
        }
    }
    
    private fun ejecutarEliminacionLocal(local: Local) {
        lifecycleScope.launch {
            try {
                txtEstado.text = "Eliminando local ${local.nombre} y todos sus datos..."
                
                // Si tiene firebaseAuthUid, DEBE eliminarse de Firebase Auth primero
                if (!local.firebaseAuthUid.isNullOrEmpty()) {
                    try {
                        // Intentar eliminar usando Firebase Functions (HTTP callable)
                        val functions = Firebase.functions
                        val deleteUser = functions.getHttpsCallable("deleteUser")
                        val data = hashMapOf("uid" to local.firebaseAuthUid)
                        deleteUser.call(data).await()
                        
                        Log.d("GestionLocalesActivity", "✅ Usuario de Firebase Auth del local eliminado: ${local.firebaseAuthUid}")
                        
                    } catch (e: Exception) {
                        // Si no se puede eliminar de Firebase Auth, NO eliminar de Firestore
                        Log.e("GestionLocalesActivity", "❌ No se pudo eliminar usuario de Firebase Auth: ${e.message}")
                        txtEstado.text = "Error: No se pudo eliminar el usuario de Firebase Auth. El local no fue eliminado."
                        return@launch
                    }
                }
                
                // Solo eliminar de Firestore si se pudo eliminar de Firebase Auth (o si no tenía firebaseAuthUid)
                // Eliminar subcolecciones primero
                eliminarSubcolecciones(local.nombre)
                
                // Eliminar el local de Firebase
                firestore.collection("locales").document(local.nombre).delete().await()
                
                // Verificar que todo se eliminó correctamente
                verificarEliminacionCompleta(local.nombre)
                
                // Remover de la lista local
                locales.removeAll { it.id == local.id }
                mostrarLocales()
                
                txtEstado.text = "✅ Local '${local.nombre}' eliminado exitosamente"
                Log.d("GestionLocalesActivity", "Local ${local.nombre} eliminado exitosamente")
                
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error eliminando local", e)
                txtEstado.text = "Error eliminando local: ${e.message}"
            }
        }
    }
    
    /**
     * Eliminar todas las subcolecciones de un local
     */
    private suspend fun eliminarSubcolecciones(nombreLocal: String) {
        try {
            Log.d("GestionLocalesActivity", "Eliminando subcolecciones para local: $nombreLocal")
            
            // Eliminar subcolección de productos
            eliminarSubcoleccion(nombreLocal, "productos")
            
            // Eliminar subcolección de registros
            eliminarSubcoleccion(nombreLocal, "registros")
            
            Log.d("GestionLocalesActivity", "Subcolecciones eliminadas exitosamente para: $nombreLocal")
            
        } catch (e: Exception) {
            Log.e("GestionLocalesActivity", "Error eliminando subcolecciones para $nombreLocal", e)
            throw e
        }
    }
    
    /**
     * Eliminar una subcolección específica
     */
    private suspend fun eliminarSubcoleccion(nombreLocal: String, subcoleccion: String) {
        try {
            Log.d("GestionLocalesActivity", "Iniciando eliminación de subcolección: $subcoleccion")
            
            // Obtener todos los documentos de la subcolección
            val snapshot = firestore.collection("locales")
                .document(nombreLocal)
                .collection(subcoleccion)
                .get()
                .await()
            
            Log.d("GestionLocalesActivity", "Encontrados ${snapshot.size()} documentos en $subcoleccion")
            
            if (snapshot.isEmpty) {
                Log.d("GestionLocalesActivity", "Subcolección $subcoleccion ya está vacía")
                return
            }
            
            // Eliminar documentos en lotes (Firebase tiene límite de 500 operaciones por batch)
            val documentos = snapshot.documents.toList()
            val lotes = documentos.chunked(500) // Dividir en lotes de máximo 500
            
            for ((indice, lote) in lotes.withIndex()) {
                Log.d("GestionLocalesActivity", "Procesando lote ${indice + 1}/${lotes.size} de $subcoleccion")
                
                val batch = firestore.batch()
                
                // Agregar todos los documentos del lote al batch
                for (document in lote) {
                    batch.delete(document.reference)
                }
                
                // Ejecutar el batch
                batch.commit().await()
                Log.d("GestionLocalesActivity", "Lote ${indice + 1} eliminado: ${lote.size} documentos")
            }
            
            Log.d("GestionLocalesActivity", "✅ Subcolección $subcoleccion eliminada completamente")
            
        } catch (e: Exception) {
            Log.e("GestionLocalesActivity", "Error eliminando subcolección $subcoleccion", e)
            throw e
        }
    }
    
    /**
     * Verificar que la eliminación fue completa
     */
    private suspend fun verificarEliminacionCompleta(nombreLocal: String) {
        try {
            Log.d("GestionLocalesActivity", "Verificando eliminación completa para: $nombreLocal")
            
            // Verificar que el documento principal no existe
            val documentoExiste = firestore.collection("locales")
                .document(nombreLocal)
                .get()
                .await()
                .exists()
            
            if (documentoExiste) {
                Log.w("GestionLocalesActivity", "⚠️ El documento principal aún existe")
            } else {
                Log.d("GestionLocalesActivity", "✅ Documento principal eliminado")
            }
            
            // Verificar subcolecciones
            val productosSnapshot = firestore.collection("locales")
                .document(nombreLocal)
                .collection("productos")
                .get()
                .await()
            
            val registrosSnapshot = firestore.collection("locales")
                .document(nombreLocal)
                .collection("registros")
                .get()
                .await()
            
            Log.d("GestionLocalesActivity", "Verificación final:")
            Log.d("GestionLocalesActivity", "- Productos restantes: ${productosSnapshot.size()}")
            Log.d("GestionLocalesActivity", "- Registros restantes: ${registrosSnapshot.size()}")
            
            if (productosSnapshot.isEmpty() && registrosSnapshot.isEmpty()) {
                Log.d("GestionLocalesActivity", "✅ Eliminación completa verificada")
            } else {
                Log.w("GestionLocalesActivity", "⚠️ Aún hay datos residuales")
            }
            
        } catch (e: Exception) {
            Log.e("GestionLocalesActivity", "Error verificando eliminación", e)
        }
    }
    
    private fun cambiarcontraseña(local: Local, email: String, contraseñaActual: String, contraseñaNueva: String) {
        lifecycleScope.launch {
            try {
                txtEstado.text = "Verificando datos..."
                
                // Buscar el usuario administrador del local
                val usuarioAdmin = local.usuarios.values.find { it.rol == "admin_local" }
                if (usuarioAdmin == null) {
                    txtEstado.text = "No se encontró el usuario administrador del local"
                    return@launch
                }
                
                // Verificar contraseña actual solo si se proporcionó
                if (contraseñaActual.isNotEmpty() && usuarioAdmin.contraseña != contraseñaActual) {
                    txtEstado.text = "❌ contraseña actual incorrecta"
                    return@launch
                }
                
                // Guardar contraseña actual para uso futuro (si se proporcionó)
                if (contraseñaActual.isNotEmpty()) {
                    guardarcontraseñaLocal(local.id, contraseñaActual)
                }
                
                // Validar nueva contraseña
                if (contraseñaNueva.length < 4) {
                    txtEstado.text = "La nueva contraseña debe tener al menos 4 caracteres"
                    return@launch
                }
                
                // Validar email
                if (email.isEmpty() || !email.contains("@")) {
                    txtEstado.text = "El email debe ser válido"
                    return@launch
                }
                
                txtEstado.text = "Actualizando credenciales en Firestore..."
                
                // Crear usuario actualizado con nuevo email y contraseña (sin FirebaseAuth y apagando la bandera de cambiar contraseña)
                val usuarioActualizado = usuarioAdmin.copy(
                    email = email,
                    contraseña = contraseñaNueva,
                    firebaseAuthUid = null,
                    debeCambiarContraseña = false
                )
                
                // Actualizar el local con el usuario modificado y apagar la bandera del local
                val usuariosActualizados = local.usuarios.toMutableMap()
                usuariosActualizados[usuarioAdmin.id] = usuarioActualizado
                
                val localActualizado = local.copy(
                    email = email,
                    usuarios = usuariosActualizados,
                    firebaseAuthUid = null,
                    debeCambiarContraseña = false
                )
                
                // Guardar en Firebase
                firestore.collection("locales").document(local.nombre).set(localActualizado).await()
                
                // Actualizar en la lista local
                val indice = locales.indexOfFirst { it.id == local.id }
                if (indice != -1) {
                    locales[indice] = localActualizado
                    mostrarLocales()
                }
                
                txtEstado.text = "✅ Credenciales actualizadas exitosamente"
                
                val mensajeConfirmacion = "Las credenciales del local '${local.nombre}' han sido actualizadas exitosamente.\n\nEmail: $email"
                
                AlertDialog.Builder(this@GestionLocalesActivity)
                    .setTitle("✅ Credenciales Actualizadas")
                    .setMessage(mensajeConfirmacion)
                    .setPositiveButton("OK", null)
                    .show()
                
                Log.d("GestionLocalesActivity", "Credenciales del local ${local.nombre} actualizadas exitosamente")
                
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error cambiando credenciales", e)
                txtEstado.text = "Error cambiando credenciales: ${e.message}"
            }
        }
    }
    
    private fun filtrarLocales(texto: String) {
        localesFiltrados.clear()
        
        if (texto.isBlank()) {
            // Si no hay texto de búsqueda, mostrar todos los locales
            mostrarLocales()
            return
        }
        
        // Filtrar locales que contengan el texto de búsqueda
        localesFiltrados.addAll(
            locales.filter { local ->
                local.nombre.contains(texto, ignoreCase = true) ||
                local.fechaCreacion.contains(texto, ignoreCase = true) ||
                local.usuarios.values.any { usuario ->
                    usuario.usuario.contains(texto, ignoreCase = true) ||
                    usuario.rol.contains(texto, ignoreCase = true)
                }
            }
        )
        
        mostrarLocales()
    }
    
    private fun ingresarAlLocal(local: Local) {
        lifecycleScope.launch {
            try {
                // Buscar el usuario administrador del local para obtener credenciales completas
                val usuarioAdmin = local.usuarios.values.find { it.rol == "admin_local" }
                
                // Guardar datos completos del usuario en SharedPreferences
                val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
                
                // 🔧 IMPORTANTE: Guardar el rol original antes de sobrescribirlo
                val rolOriginal = prefs.getString("rol", "") ?: ""
                val esMaestroOriginal = rolOriginal == "maestro"
                
                val editor = prefs.edit()
                editor.putString("localId", local.id)
                editor.putString("localNombre", local.nombre)
                
                // 🔧 Guardar el rol original del usuario maestro/supervisor para restaurarlo después
                if (esMaestroOriginal) {
                    editor.putString("rolOriginal", "maestro")
                    editor.putString("usuarioOriginal", prefs.getString("usuario", "") ?: "")
                    Log.d("GestionLocalesActivity", "🔧 Guardando rol original: maestro")
                } else if (rolOriginal == "supervisor") {
                    editor.putString("rolOriginal", "supervisor")
                    editor.putString("usuarioOriginal", prefs.getString("usuario", "") ?: "")
                    Log.d("GestionLocalesActivity", "🔧 Guardando rol original: supervisor")
                }
                
                // Si encontramos el usuario admin, guardar sus datos
                if (usuarioAdmin != null) {
                    editor.putString("usuarioId", usuarioAdmin.id)
                    editor.putString("usuario", usuarioAdmin.usuario)
                    editor.putString("rol", usuarioAdmin.rol)
                    editor.putString("metodoLogin", "admin_gestion") // 🔧 Marcar que vino desde gestión de locales
                    
                    Log.d("ADMIN_FLOW", "🔧 Marcando admin_gestion en GestionLocalesActivity (usuario admin encontrado)")
                    Log.d("GestionLocalesActivity", "Usuario admin ingresó al local: ${local.nombre} (Usuario: ${usuarioAdmin.usuario})")
                } else {
                    // Si no hay usuario admin, crear datos por defecto para el administrador
                    editor.putString("usuarioId", "admin_${local.id}")
                    editor.putString("usuario", "admin_${local.id}")
                    editor.putString("rol", "admin_local")
                    editor.putString("metodoLogin", "admin_gestion") // 🔧 Marcar que vino desde gestión de locales
                    
                    Log.d("ADMIN_FLOW", "🔧 Marcando admin_gestion en GestionLocalesActivity (usuario admin por defecto)")
                    Log.d("GestionLocalesActivity", "Usuario ingresó al local como admin por defecto: ${local.nombre}")
                }
                
                editor.apply()
                
                // Ir directamente al MainActivity
                val intent = Intent(this@GestionLocalesActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                finish()
                
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error ingresando al local", e)
                txtEstado.text = "Error ingresando al local: ${e.message}"
            }
        }
    }
    
    private fun obtenercontraseñaGuardada(localId: String): String {
        val prefs = getSharedPreferences("contraseñas_locales", MODE_PRIVATE)
        return prefs.getString("local_$localId", "") ?: ""
    }
    
    private fun guardarcontraseñaLocal(localId: String, contraseña: String) {
        val prefs = getSharedPreferences("contraseñas_locales", MODE_PRIVATE)
        prefs.edit()
            .putString("local_$localId", contraseña)
            .apply()
        
        Log.d("GestionLocalesActivity", "contraseña guardada para local: $localId")
    }
    
    private fun limpiarcontraseñaLocal(localId: String) {
        val prefs = getSharedPreferences("contraseñas_locales", MODE_PRIVATE)
        prefs.edit()
            .remove("local_$localId")
            .apply()
        
        Log.d("GestionLocalesActivity", "contraseña eliminada para local: $localId")
    }
    
    /**
     * Verifica si un local tiene datos en 0 sin el flag noRecibioMercaderia
     * y también verifica si faltan datos en días anteriores
     */
    private fun verificarDatosEnCero(localId: String, nombreLocal: String, @Suppress("UNUSED_PARAMETER") nombreTextView: TextView, btnNotifBell: TextView, badgeContador: TextView) {
        lifecycleScope.launch {
            try {
                val formatoFecha = DateHelper.getDateFormat("yyyy-MM-dd")
                val fechaHoy = formatoFecha.format(Date())
                val problemas = mutableListOf<String>()
                
                // 1. Verificar datos en 0 de hoy
                verificarDatosEnCeroHoy(localId, fechaHoy, problemas)
                
                // 2. Verificar días faltantes en los últimos 7 días
                verificarDiasFaltantes(localId, fechaHoy, problemas)

                // 3. Verificar documentos vencidos / por vencer (<= 30 días)
                try {
                    val docsSnapshot = firestore.collection("locales").document(localId).collection("documentos").get().await()
                    for (doc in docsSnapshot) {
                        val nombreDoc = doc.get("nombreDocumento")?.toString() ?: doc.get("nombre")?.toString() ?: doc.get("tipoDocumento")?.toString() ?: "Documento"
                        val fechaVencObj = doc.get("fechaVencimiento")
                        val fechaVencStr = when (fechaVencObj) {
                            is String -> fechaVencObj
                            is com.google.firebase.Timestamp -> {
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                sdf.format(fechaVencObj.toDate())
                            }
                            is java.util.Date -> {
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                sdf.format(fechaVencObj)
                            }
                            else -> fechaVencObj?.toString() ?: ""
                        }
                        val estadoDoc = doc.get("estado")?.toString() ?: ""
                        val diasVenc = calcularDiasParaVencer(fechaVencStr)

                        if (diasVenc != null && diasVenc <= 30) {
                            if (diasVenc < 0) {
                                problemas.add("📄 $nombreDoc (vencido hace ${-diasVenc}d)")
                            } else if (diasVenc == 0) {
                                problemas.add("📄 $nombreDoc (vence hoy)")
                            } else {
                                problemas.add("📄 $nombreDoc (vence en ${diasVenc}d)")
                            }
                        } else if (estadoDoc.equals("VENCIDO", ignoreCase = true) || estadoDoc.equals("POR VENCER", ignoreCase = true)) {
                            problemas.add("📄 $nombreDoc ($estadoDoc)")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GestionLocalesActivity", "Error verificando docs local $localId: ${e.message}")
                }

                // 4. Verificar personal con certificados vencidos / por vencer (<= 30 días)
                try {
                    val personalSnapshot = firestore.collection("locales").document(localId).collection("personal").get().await()
                    for (doc in personalSnapshot) {
                        val nombrePer = doc.getString("nombre") ?: "Personal"
                        
                        val libretaVenc = extraerFechaVencimiento(doc.get("libretaSanitaria"))
                        val diasLib = calcularDiasParaVencer(libretaVenc)
                        if (diasLib != null && diasLib <= 30) {
                            if (diasLib < 0) {
                                problemas.add("📋 $nombrePer (libreta vencida hace ${-diasLib}d)")
                            } else if (diasLib == 0) {
                                problemas.add("📋 $nombrePer (libreta vence hoy)")
                            } else {
                                problemas.add("📋 $nombrePer (libreta vence en ${diasLib}d)")
                            }
                        }

                        val cursoVenc = extraerFechaVencimiento(doc.get("cursoManipulacion"))
                        val diasCur = calcularDiasParaVencer(cursoVenc)
                        if (diasCur != null && diasCur <= 30) {
                            if (diasCur < 0) {
                                problemas.add("🎓 $nombrePer (curso vencido hace ${-diasCur}d)")
                            } else if (diasCur == 0) {
                                problemas.add("🎓 $nombrePer (curso vence hoy)")
                            } else {
                                problemas.add("🎓 $nombrePer (curso vence en ${diasCur}d)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GestionLocalesActivity", "Error verificando personal local $localId: ${e.message}")
                }
                
                // Actualizar el TextView y badges en el hilo principal
                runOnUiThread {
                    if (problemas.isNotEmpty()) {
                        badgeContador.text = "${problemas.size}"
                        badgeContador.visibility = View.VISIBLE
                        btnNotifBell.setOnClickListener {
                            mostrarDetallesProblemas(nombreLocal, problemas)
                        }
                    } else {
                        badgeContador.visibility = View.GONE
                        btnNotifBell.setOnClickListener {
                            Toast.makeText(this@GestionLocalesActivity, "Sin alertas registradas en $nombreLocal", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error verificando datos para local $localId", e)
            }
        }
    }
    
    private fun extraerFechaVencimiento(obj: Any?): String {
        if (obj == null) return ""
        return when (obj) {
            is String -> obj
            is com.google.firebase.Timestamp -> {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                sdf.format(obj.toDate())
            }
            is java.util.Date -> {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                sdf.format(obj)
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = obj as Map<String, Any?>
                val fecha = map["fechaVencimiento"]
                extraerFechaVencimiento(fecha)
            }
            else -> obj.toString()
        }
    }

    private fun formatearFechaVencimiento(obj: Any?): String {
        val fechaStr = extraerFechaVencimiento(obj)
        if (fechaStr.isBlank()) return ""
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val fecha = sdf.parse(fechaStr) ?: return fechaStr
            val sdfOut = java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.getDefault())
            sdfOut.format(fecha)
        } catch (e: Exception) {
            fechaStr
        }
    }

    private fun diasHastaVencimiento(obj: Any?): Int {
        val fechaStr = extraerFechaVencimiento(obj)
        if (fechaStr.isBlank()) return 999
        return calcularDiasParaVencer(fechaStr) ?: 999
    }

    private fun formatearDiasVencimiento(dias: Int): String {
        if (dias < 0) return "Vencido"
        if (dias == 0) return "Vence hoy"
        val años = dias / 365
        val meses = (dias % 365) / 30
        val diasRestantes = dias % 30
        return when {
            años > 0 && meses > 0 -> "Vence en $años año${if (años > 1) "s" else ""} y $meses mes${if (meses > 1) "es" else ""}"
            años > 0 -> "Vence en $años año${if (años > 1) "s" else ""}"
            meses > 0 && diasRestantes > 0 -> "Vence en $meses mes${if (meses > 1) "es" else ""} y $diasRestantes día${if (diasRestantes > 1) "s" else ""}"
            meses > 0 -> "Vence en $meses mes${if (meses > 1) "es" else ""}"
            else -> "Vence en $diasRestantes día${if (diasRestantes > 1) "s" else ""}"
        }
    }
    
    private fun calcularDiasParaVencer(fechaStr: String): Int? {
        if (fechaStr.isBlank()) return null
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val fecha = sdf.parse(fechaStr) ?: return null
            val hoy = sdf.parse(sdf.format(Date())) ?: return null
            val diffMs = fecha.time - hoy.time
            (diffMs / (1000 * 60 * 60 * 24)).toInt()
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Verifica si hay datos en 0 sin el flag correspondiente en TODOS los registros históricos
     * Verifica desde el primer registro cargado
     */
    private suspend fun verificarDatosEnCeroHoy(localId: String, @Suppress("UNUSED_PARAMETER") _fechaHoy: String, problemas: MutableList<String>) {
        Log.d("GestionLocalesActivity", "🔍 Verificando datos en 0 en TODOS los registros históricos para local: $localId")
        
        try {
            // Obtener TODOS los registros históricos del local
            val snapshot = firestore.collection("locales")
                .document(localId)
                .collection("registros")
                .get()
                .await()
            
            Log.d("GestionLocalesActivity", "📊 Total de registros para verificar datos en 0: ${snapshot.documents.size}")
            
            snapshot.documents.forEach { document ->
                val registro = document.toObject(RegistroInventario::class.java)
                if (registro != null) {
                    // Verificar si todos los productos están en 0 (verificar que haya productos antes)
                    val todosEnCero = registro.productos.isNotEmpty() && registro.productos.all { it.cantidad == 0 }
                    
                    Log.d("GestionLocalesActivity", "📅 Verificando ${registro.tipo} - ${registro.fecha}: todosEnCero=$todosEnCero, productos.size=${registro.productos.size}, noRecibioMercaderia=${registro.noRecibioMercaderia}")
                    
                    when (registro.tipo) {
                        "Ingreso de Mercadería" -> {
                            // Si todos están en 0 y NO tiene el flag noRecibioMercaderia, mostrar alerta
                            if (todosEnCero && !registro.noRecibioMercaderia) {
                                val calendarFecha = DateHelper.getCalendar()
                                calendarFecha.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(registro.fecha)!!
                                val diaSemana = obtenerDiaSemana(calendarFecha)
                                val fechaFormateada = formatearFechaParaMostrar(calendarFecha)
                                
                                Log.d("GestionLocalesActivity", "⚠️ Problema encontrado: ${registro.tipo} - ${registro.fecha}")
                                problemas.add("📦 $diaSemana $fechaFormateada - Ingreso en 0")
                            }
                        }
                        "Stock Final" -> {
                            // Para Stock Final solo alertar si todos están en 0 (no hay flag aquí)
                            if (todosEnCero) {
                                val calendarFecha = DateHelper.getCalendar()
                                calendarFecha.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(registro.fecha)!!
                                val diaSemana = obtenerDiaSemana(calendarFecha)
                                val fechaFormateada = formatearFechaParaMostrar(calendarFecha)
                                
                                Log.d("GestionLocalesActivity", "⚠️ Problema encontrado: ${registro.tipo} - ${registro.fecha}")
                                problemas.add("📊 $diaSemana $fechaFormateada - Stock en 0")
                            }
                        }
                    }
                }
            }
            
            Log.d("GestionLocalesActivity", "✅ Verificación de datos en 0 completada. Problemas encontrados: ${problemas.size}")
        } catch (e: Exception) {
            Log.e("GestionLocalesActivity", "❌ Error verificando datos en 0: ${e.message}", e)
        }
    }
    
    /**
     * Verifica días faltantes en TODOS los registros históricos (excluyendo hoy)
     * Verifica desde el primer registro cargado
     */
    private suspend fun verificarDiasFaltantes(localId: String, fechaHoy: String, problemas: MutableList<String>) {
        val formatoFecha = DateHelper.getDateFormat("yyyy-MM-dd")
        
        Log.d("GestionLocalesActivity", "🔍 Verificando días faltantes en TODOS los registros históricos para local: $localId, fecha hoy: $fechaHoy")
        
        try {
            // Obtener TODOS los registros históricos del local
            val snapshot = firestore.collection("locales")
                .document(localId)
                .collection("registros")
                .get()
                .await()
            
            Log.d("GestionLocalesActivity", "📊 Total de registros históricos encontrados: ${snapshot.documents.size}")
            
            // Si el local no tiene registros históricos (local nuevo), no generar alertas
            if (snapshot.isEmpty) {
                Log.d("GestionLocalesActivity", "🏪 Local nuevo sin registros históricos, no generando alertas de días faltantes")
                return
            }
            
            // Agrupar registros por fecha
            val registrosPorFecha = mutableMapOf<String, MutableSet<String>>()
            
            snapshot.documents.forEach { document ->
                val registro = document.toObject(RegistroInventario::class.java)
                if (registro != null && registro.fecha != fechaHoy) { // Excluir hoy
                    val fecha = registro.fecha
                    val tipo = registro.tipo
                    
                    if (!registrosPorFecha.containsKey(fecha)) {
                        registrosPorFecha[fecha] = mutableSetOf()
                    }
                    registrosPorFecha[fecha]!!.add(tipo)
                    
                    Log.d("GestionLocalesActivity", "📅 Fecha: $fecha, Tipo: $tipo")
                }
            }
            
            Log.d("GestionLocalesActivity", "📊 Fechas con datos: ${registrosPorFecha.keys.sorted()}")
            
            // Verificar cada fecha histórica
            registrosPorFecha.forEach { (fecha, tiposExistentes) ->
                val calendarFecha = DateHelper.getCalendar()
                calendarFecha.time = formatoFecha.parse(fecha)!!
                val diaSemana = obtenerDiaSemana(calendarFecha)
                
                val faltantes = mutableListOf<String>()
                
                if (!tiposExistentes.contains("Ingreso de Mercadería")) {
                    faltantes.add("Ingreso de Mercadería")
                    Log.d("GestionLocalesActivity", "⚠️ Falta Ingreso de Mercadería para $fecha")
                }
                if (!tiposExistentes.contains("Stock Final")) {
                    faltantes.add("Stock Final")
                    Log.d("GestionLocalesActivity", "⚠️ Falta Stock Final para $fecha")
                }
                
                if (faltantes.isNotEmpty()) {
                    val fechaFormateada = formatearFechaParaMostrar(calendarFecha)
                    Log.d("GestionLocalesActivity", "📝 Agregando problema para $fecha: ${faltantes.joinToString(", ")}")
                    problemas.add("📅 $diaSemana $fechaFormateada - Falta: ${faltantes.joinToString(", ")}")
                }
            }
            
            Log.d("GestionLocalesActivity", "✅ Verificación completada. Problemas encontrados: ${problemas.size}")
        } catch (e: Exception) {
            Log.e("GestionLocalesActivity", "❌ Error verificando días faltantes: ${e.message}", e)
        }
    }
    
    /**
     * Obtiene el nombre del día de la semana en español
     */
    private fun obtenerDiaSemana(calendar: Calendar): String {
        val dias = arrayOf("DOM", "LUN", "MAR", "MIÉ", "JUE", "VIE", "SÁB")
        return dias[calendar.get(Calendar.DAY_OF_WEEK) - 1]
    }
    
    /**
     * Formatea una fecha para mostrar en formato "DD-MM-YY"
     */
    private fun formatearFechaParaMostrar(calendar: Calendar): String {
        val dia = String.format("%02d", calendar.get(Calendar.DAY_OF_MONTH))
        val mes = String.format("%02d", calendar.get(Calendar.MONTH) + 1)
        val año = String.format("%02d", calendar.get(Calendar.YEAR) % 100)
        return "$dia-$mes-$año"
    }
    
    /**
     * Abre la pantalla de Stock y Vencimientos para un local
     */
    private fun abrirStockVencimientos(local: Local) {
        Log.d("GestionLocalesActivity", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d("GestionLocalesActivity", "🚀 Abriendo StockVencimientosActivity")
        Log.d("GestionLocalesActivity", "   local.id: '${local.id}'")
        Log.d("GestionLocalesActivity", "   local.nombre: '${local.nombre}'")
        
        // Validar que el local tenga un ID válido
        val localIdValido = if (local.id.isEmpty()) {
            // Si el ID está vacío, usar el nombre como fallback (ya que en Firebase el document ID es el nombre)
            Log.w("GestionLocalesActivity", "⚠️ local.id está vacío, usando local.nombre como fallback")
            local.nombre
        } else {
            local.id
        }
        
        if (localIdValido.isEmpty()) {
            Log.e("GestionLocalesActivity", "❌ ERROR: No se puede abrir vencimientos - localId y localNombre están vacíos")
            Toast.makeText(this, "Error: No se pudo identificar el local. Por favor, intente nuevamente.", Toast.LENGTH_LONG).show()
            return
        }
        
        Log.d("GestionLocalesActivity", "   ✅ localId válido: '$localIdValido'")
        
        try {
            val intent = Intent(this, StockVencimientosActivity::class.java)
            intent.putExtra("localId", localIdValido)
            intent.putExtra("localNombre", local.nombre)
            startActivity(intent)
            Log.d("GestionLocalesActivity", "   ✅ Intent creado y actividad iniciada")
        } catch (e: Exception) {
            Log.e("GestionLocalesActivity", "❌ ERROR abriendo StockVencimientosActivity: ${e.message}", e)
            Toast.makeText(this, "Error abriendo vencimientos: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Carga el badge de vencimientos para mostrar alertas en la lista de locales
     */
    private fun cargarBadgeVencimientos(localId: String, badgeView: TextView) {
        lifecycleScope.launch {
            try {
                val vencimientoRepo = com.tuapp.inventario.repository.VencimientoRepository()
                val resumen = vencimientoRepo.obtenerResumenVencimientos(localId)
                
                val hoy = resumen["hoy"] ?: 0
                val manana = resumen["manana"] ?: 0
                
                runOnUiThread {
                    if (hoy > 0) {
                        badgeView.text = "$hoy"
                        badgeView.visibility = View.VISIBLE
                    } else if (manana > 0) {
                        badgeView.text = "$manana"
                        badgeView.visibility = View.VISIBLE
                    } else {
                        badgeView.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error cargando badge de vencimientos: ${e.message}")
            }
        }
    }
    
    /**
     * Carga todos los badges de vencimientos en paralelo para mejorar el rendimiento
     */
    private fun cargarBadgesVencimientosParalelo(badgesMap: Map<String, TextView>) {
        lifecycleScope.launch {
            try {
                val vencimientoRepo = com.tuapp.inventario.repository.VencimientoRepository()
                
                // Cargar todos los resúmenes en paralelo usando async
                val deferredResumenes = badgesMap.keys.map { localId ->
                    async {
                        localId to vencimientoRepo.obtenerResumenVencimientos(localId)
                    }
                }
                
                // Esperar a que todas las corrutinas terminen en paralelo
                val resumenes = deferredResumenes.awaitAll()
                
                // Actualizar todos los badges en el hilo principal
                runOnUiThread {
                    resumenes.forEach { (localId, resumen) ->
                        val badgeView = badgesMap[localId] ?: return@forEach
                        val hoy = resumen["hoy"] ?: 0
                        val manana = resumen["manana"] ?: 0
                        
                        if (hoy > 0) {
                            badgeView.text = "$hoy"
                            badgeView.visibility = View.VISIBLE
                        } else if (manana > 0) {
                            badgeView.text = "$manana"
                            badgeView.visibility = View.VISIBLE
                        } else {
                            badgeView.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error cargando badges de vencimientos en paralelo: ${e.message}")
            }
        }
    }
    
    /**
     * Muestra un popup con los productos que vencen hoy
     */
    private fun mostrarPopupVencimientosHoy(local: Local) {
        lifecycleScope.launch {
            try {
                val vencimientoRepo = com.tuapp.inventario.repository.VencimientoRepository()
                val lotes = vencimientoRepo.obtenerLotesActivos(local.id)
                
                // Filtrar solo los que vencen hoy
                val lotesHoy = lotes.filter { it.diasHastaVencimiento() == 0 }
                
                runOnUiThread {
                    if (lotesHoy.isEmpty()) {
                        Toast.makeText(this@GestionLocalesActivity, "No hay productos que venzan hoy", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    
                    // Agrupar por producto
                    val lotesPorProducto = lotesHoy.groupBy { it.productoCodigo }
                    
                    // Construir mensaje
                    val mensaje = StringBuilder()
                    mensaje.append("🔴 Productos que vencen HOY:\n\n")
                    
                    lotesPorProducto.forEach { (_, lotesProducto) ->
                        val totalCantidad = lotesProducto.sumOf { it.cantidad }
                        val nombreProducto = lotesProducto.first().productoNombre
                        mensaje.append("• $nombreProducto: $totalCantidad uds\n")
                    }
                    
                    // Mostrar diálogo
                    AlertDialog.Builder(this@GestionLocalesActivity)
                        .setTitle("⚠️ ${local.nombre} - Vencimientos HOY")
                        .setMessage(mensaje.toString())
                        .setPositiveButton("Ver Detalles") { _, _ ->
                            abrirStockVencimientos(local)
                        }
                        .setNegativeButton("Cerrar", null)
                        .show()
                }
            } catch (e: Exception) {
                Log.e("GestionLocalesActivity", "Error mostrando popup vencimientos: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this@GestionLocalesActivity, "Error cargando vencimientos", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * Muestra un diálogo con los detalles de los problemas encontrados
     * Incluye scroll para poder ver todas las alertas cuando hay muchas
     */
    private fun mostrarDetallesProblemas(nombreLocal: String, problemas: List<String>) {
        // Crear layout principal
        val layoutPrincipal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Título
        val txtTitulo = TextView(this).apply {
            text = "⚠️ Alertas - $nombreLocal"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.web_text_light, null))
            setBackgroundColor(0xFFF44336.toInt()) // Rojo
            setPadding(32, 24, 32, 24)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        layoutPrincipal.addView(txtTitulo)
        
        // ScrollView para el contenido
        val scrollView = android.widget.ScrollView(this)
        scrollView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.6).toInt() // Limitar altura máxima a 60% de la pantalla
        )
        
        // Layout interno para el contenido
        val layoutContenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        
        // Mensaje introductorio
        val txtIntro = TextView(this).apply {
            text = "Se encontraron los siguientes problemas:\n"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.web_text_primary, null))
            setPadding(0, 0, 0, 16)
        }
        layoutContenido.addView(txtIntro)
        
        // Agregar cada problema
        problemas.forEach { problema ->
            val txtProblema = TextView(this).apply {
                text = problema
                textSize = 14f
                setTextColor(resources.getColor(R.color.web_text_primary, null))
                setPadding(0, 8, 0, 16)
            }
            layoutContenido.addView(txtProblema)
        }
        
        scrollView.addView(layoutContenido)
        layoutPrincipal.addView(scrollView)
        
        // Mostrar diálogo
        AlertDialog.Builder(this)
            .setView(layoutPrincipal)
            .setPositiveButton("Entendido", null)
            .show()
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
                Log.d("GestionLocalesActivity", "🌙 Modo oscuro: marca de agua difuminada (alpha=0.3)")
            } else {
                // Modo claro: mostrar con transparencia
                it.alpha = 0.08f
                Log.d("GestionLocalesActivity", "☀️ Modo claro: marca de agua con transparencia (alpha=0.08)")
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Configurar marca de agua según modo oscuro (por si cambió mientras la app estaba en segundo plano)
        configurarMarcaDeAgua()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Remover listeners para prevenir memory leaks
        try {
            textWatcherLocales?.let {
                editTextBuscar.removeTextChangedListener(it)
            }
            textWatcherSupervisores?.let {
                editTextBuscarSupervisores.removeTextChangedListener(it)
            }
        } catch (e: Exception) {
            // Ignorar si los views no están inicializados
            Log.d("GestionLocalesActivity", "Error removiendo listeners: ${e.message}")
        }
    }
    
}
