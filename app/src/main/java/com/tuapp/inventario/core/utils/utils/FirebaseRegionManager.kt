package com.tuapp.inventario.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.tuapp.inventario.model.Region
import org.json.JSONObject
import java.io.InputStream

/**
 * Gestor para inicializacion dinamica de Firebase segun la region seleccionada
 */
object FirebaseRegionManager {
    
    private const val TAG = "FirebaseRegionManager"
    private const val PREFS_NAME = "firebase_region_prefs"
    private const val KEY_CURRENT_REGION_ID = "current_region_id"
    private const val KEY_CURRENT_REGION_JSON = "current_region_json"
    
    private var currentRegion: Region? = null
    private var firebaseApp: FirebaseApp? = null
    
    /**
     * Carga las regiones disponibles desde assets/regiones.json
     */
    fun cargarRegiones(context: Context): List<Region> {
        return try {
            val inputStream: InputStream = context.assets.open("regiones.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonArray = org.json.JSONArray(jsonString)
            
            val regiones = mutableListOf<Region>()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                
                // Parsear firebaseConfig si existe
                val firebaseConfig = if (jsonObject.has("firebaseConfig")) {
                    val configObj = jsonObject.getJSONObject("firebaseConfig")
                    com.tuapp.inventario.model.FirebaseConfig(
                        apiKey = configObj.optString("apiKey", ""),
                        authDomain = configObj.optString("authDomain", ""),
                        projectId = configObj.optString("projectId", ""),
                        storageBucket = configObj.optString("storageBucket", ""),
                        messagingSenderId = configObj.optString("messagingSenderId", ""),
                        appId = configObj.optString("appId", "")
                    )
                } else null
                
                regiones.add(
                    Region(
                        id = jsonObject.getString("id"),
                        nombre = jsonObject.getString("nombre"),
                        proyectoFirebaseId = jsonObject.optString("proyectoFirebaseId", ""),
                        googleServicesJson = jsonObject.optString("googleServicesJson", ""),
                        maestroNombre = jsonObject.optString("maestroNombre", ""),
                        activo = jsonObject.optBoolean("activo", true),
                        firebaseConfig = firebaseConfig
                    )
                )
            }
            
            Log.d(TAG, " ${regiones.size} regiones cargadas")
            regiones
        } catch (e: Exception) {
            Log.e(TAG, " Error cargando regiones", e)
            emptyList()
        }
    }
    
    /**
     * Inicializa Firebase con la region seleccionada
     */
    fun initializeFirebase(context: Context, region: Region): Boolean {
        return try {
            Log.d(TAG, " Inicializando Firebase para region: ${region.nombre}")
            
            // Si es la region 1 (google-services por defecto), usar FirebaseApp por defecto
            if (region.googleServicesJson == "google-services") {
                try {
                    // Firebase ya esta inicializado por el plugin de Google Services
                    firebaseApp = FirebaseApp.getInstance()
                    currentRegion = region
                    guardarRegionActual(context, region)
                    Log.d(TAG, " Usando Firebase por defecto para region: ${region.nombre}")
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, " Firebase por defecto no inicializado, intentando inicializar...")
                    // Continuar con inicializacion manual
                }
            }
            
            // Cerrar Firebase actual si existe (solo si no es la region por defecto)
            firebaseApp?.let { app ->
                if (app.name != FirebaseApp.DEFAULT_APP_NAME) {
                    try {
                        FirebaseApp.getInstance(app.name).delete()
                        Log.d(TAG, " Firebase anterior cerrado")
                    } catch (e: Exception) {
                        Log.w(TAG, " No se pudo cerrar Firebase anterior: ${e.message}")
                    }
                }
            }
            
            // Cargar google-services.json segun region
            Log.d(TAG, " Cargando configuracion desde: ${region.googleServicesJson}")
            val options = cargarFirebaseOptions(context, region.googleServicesJson)
            Log.d(TAG, " Configuracion cargada - Proyecto: ${options.projectId}")
            
            // Inicializar Firebase con el nombre de la region
            val appName = "firebase_${region.id}"
            Log.d(TAG, " Inicializando FirebaseApp con nombre: $appName")
            
            firebaseApp = try {
                val app = FirebaseApp.initializeApp(context, options, appName)
                Log.d(TAG, " FirebaseApp inicializado: ${app.name}")
                app
            } catch (e: Exception) {
                Log.w(TAG, " FirebaseApp ya existe, obteniendo instancia: ${e.message}")
                // Si ya existe, obtener la instancia existente
                FirebaseApp.getInstance(appName)
            }
            
            currentRegion = region
            
            // Guardar region actual
            guardarRegionActual(context, region)
            
            Log.d(TAG, " Firebase inicializado para region: ${region.nombre} (Proyecto: ${options.projectId})")
            true
        } catch (e: Exception) {
            Log.e(TAG, " Error inicializando Firebase", e)
            false
        }
    }
    
    /**
     * Carga FirebaseOptions desde un archivo JSON en res/raw/ o desde app/src/main/ (por defecto)
     */
    fun cargarFirebaseOptions(context: Context, jsonFileName: String): FirebaseOptions {
        val jsonString: String
        
        // Si el nombre es "google-services" (sin extension), usar el archivo por defecto
        if (jsonFileName == "google-services") {
            // Intentar leer desde assets o usar FirebaseApp por defecto
            // Para la region 1, Firebase ya esta inicializado por el plugin de Google Services
            // Solo necesitamos obtener las opciones del FirebaseApp por defecto
            try {
                val defaultApp = FirebaseApp.getInstance()
                return defaultApp.options
            } catch (e: Exception) {
                // Si no existe, intentar leer desde assets
                try {
                    val inputStream = context.assets.open("google-services.json")
                    jsonString = inputStream.bufferedReader().use { it.readText() }
                } catch (e2: Exception) {
                    throw IllegalArgumentException("No se encontro google-services.json por defecto ni en assets/")
                }
            }
        } else {
            // Para otras regiones, leer desde res/raw/
            val resourceId = context.resources.getIdentifier(
                jsonFileName,
                "raw",
                context.packageName
            )
            
            if (resourceId == 0) {
                throw IllegalArgumentException("No se encontro el archivo $jsonFileName en res/raw/")
            }
            
            val inputStream = context.resources.openRawResource(resourceId)
            jsonString = inputStream.bufferedReader().use { it.readText() }
        }
        
        val jsonObject = JSONObject(jsonString)
        
        val projectInfo = jsonObject.getJSONObject("project_info")
        val client = jsonObject.getJSONArray("client").getJSONObject(0)
        val clientInfo = client.getJSONObject("client_info")
        val apiKey = client.getJSONArray("api_key").getJSONObject(0)
        
        return FirebaseOptions.Builder()
            .setProjectId(projectInfo.getString("project_id"))
            .setApplicationId(clientInfo.getString("mobilesdk_app_id"))
            .setApiKey(apiKey.getString("current_key"))
            .setDatabaseUrl("https://${projectInfo.getString("project_id")}.firebaseio.com")
            .setStorageBucket(projectInfo.getString("storage_bucket"))
            .build()
    }
    
    /**
     * Obtiene la instancia de Firestore para la region actual
     */
    fun getFirestore(): FirebaseFirestore {
        val app = firebaseApp
            ?: try {
                // Si no hay app especifica, intentar usar FirebaseApp por defecto
                FirebaseApp.getInstance()
            } catch (e: Exception) {
                throw IllegalStateException("Firebase no esta inicializado. Selecciona una region en LoginActivity primero.")
            }
        
        return FirebaseFirestore.getInstance(app)
    }
    
    /**
     * Obtiene la instancia de FirebaseAuth para la region actual
     */
    fun getFirebaseAuth(): FirebaseAuth {
        val app = firebaseApp
            ?: try {
                // Si no hay app especifica, intentar usar FirebaseApp por defecto
                FirebaseApp.getInstance()
            } catch (e: Exception) {
                throw IllegalStateException("Firebase no esta inicializado. Selecciona una region en LoginActivity primero.")
            }
        
        return FirebaseAuth.getInstance(app)
    }
    
    /**
     * Obtiene la instancia de FirebaseAuth para una region especifica
     * Util para intentar operaciones en multiples regiones (ej: reset de contrasena)
     */
    fun getFirebaseAuthForRegion(context: Context, region: Region): FirebaseAuth? {
        return try {
            val app = if (region.googleServicesJson == "google-services") {
                // Region por defecto
                FirebaseApp.getInstance()
            } else {
                // Otras regiones
                val appName = "firebase_${region.id}"
                try {
                    FirebaseApp.getInstance(appName)
                } catch (e: Exception) {
                    // Si no existe, inicializarla temporalmente
                    val options = cargarFirebaseOptions(context, region.googleServicesJson)
                    FirebaseApp.initializeApp(context, options, appName)
                }
            }
            FirebaseAuth.getInstance(app)
        } catch (e: Exception) {
            Log.w(TAG, " No se pudo obtener FirebaseAuth para region ${region.nombre}: ${e.message}")
            null
        }
    }
    
    /**
     * Obtiene la region actual
     */
    fun getCurrentRegion(): Region? = currentRegion
    
    /**
     * Guarda la region actual en SharedPreferences
     */
    private fun guardarRegionActual(context: Context, region: Region) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_CURRENT_REGION_ID, region.id)
            putString(KEY_CURRENT_REGION_JSON, region.googleServicesJson)
            apply()
        }
    }
    
    /**
     * Carga la region actual desde SharedPreferences
     */
    fun cargarRegionActual(context: Context): Region? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val regionId = prefs.getString(KEY_CURRENT_REGION_ID, null)
        val jsonFileName = prefs.getString(KEY_CURRENT_REGION_JSON, null)
        
        if (regionId == null || jsonFileName == null) {
            return null
        }
        
        // Buscar la region en la lista
        val regiones = cargarRegiones(context)
        return regiones.find { it.id == regionId && it.googleServicesJson == jsonFileName }
    }
    
    /**
     * Limpia la region actual (para cambiar de region)
     */
    fun limpiarRegionActual(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        currentRegion = null
        firebaseApp = null
    }
}

