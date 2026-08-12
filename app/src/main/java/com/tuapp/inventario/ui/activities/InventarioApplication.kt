package com.tuapp.inventario

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.tuapp.inventario.database.InventarioDatabase
import com.tuapp.inventario.repository.InventarioRepository
import com.tuapp.inventario.repository.FirebaseInventarioRepository
import com.tuapp.inventario.repository.LocalInventarioRepository
import com.tuapp.inventario.model.UsuarioActual
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class InventarioApplication : Application() {
    
    val database by lazy { InventarioDatabase.getDatabase(this) }
    val localRepository by lazy { LocalInventarioRepository(database) }
    
    // Repository que se adapta según el usuario logueado
    val repository: InventarioRepository by lazy {
        try {
            val usuarioActual = obtenerUsuarioActual()
            if (usuarioActual != null) {
                Log.d("InventarioApp", "Usuario logueado: ${usuarioActual.localNombre}, usando Firebase")
                // Usar Firebase para sincronización (FirebaseRegionManager maneja el Firestore correcto)
                val firebaseRepo = FirebaseInventarioRepository(usuarioActual.localId)
                // Sincronizar datos locales con Firebase en background
                applicationScope.launch {
                    try {
                        Log.d("InventarioApp", "Iniciando sincronización con Firebase...")
                        firebaseRepo.sincronizarConLocal(localRepository)
                        Log.d("InventarioApp", "Sincronización con Firebase completada")
                    } catch (e: Exception) {
                        Log.e("InventarioApp", "Error sincronizando con Firebase", e)
                    }
                }
                firebaseRepo
            } else {
                Log.d("InventarioApp", "No hay usuario logueado, usando repositorio local")
                // Usar base de datos local como fallback
                localRepository
            }
        } catch (e: Exception) {
            Log.e("InventarioApp", "Error creating repository", e)
            // Fallback a repository local en caso de error
            localRepository
        }
    }
    
    // Scope para operaciones de base de datos
    val applicationScope = CoroutineScope(SupervisorJob())
    
    override fun onCreate() {
        super.onCreate()
        Log.d("InventarioApp", "Application started")
        
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        
        // IMPORTANTE: Firebase NO se inicializa automáticamente aquí
        // Se inicializa dinámicamente en LoginActivity cuando el usuario selecciona una región
        // Esto permite cambiar entre diferentes proyectos Firebase según la región
        
        // ⚠️ NOTA SOBRE LOGS DE FIRESTORE:
        // Los warnings "[CustomClassMapper]: No setter/field for codigo/usuario/localId/timestamp found"
        // son normales del SDK de Firestore y NO afectan la funcionalidad.
        // Aparecen porque Firestore encuentra campos en la BD que no tienen setters en las clases Kotlin.
        // Estos campos (codigo, usuario, localId, timestamp) se usan para metadata pero no se mapean a las clases.
        // En builds Release, estos logs se reducen automáticamente.
        // NO se pueden eliminar sin cambiar la estructura de datos en Firebase.
        
        // Inicialización simplificada para evitar crashes
        try {
            Log.d("InventarioApp", "Initializing application...")
            // Inicializar la base de datos local primero
            applicationScope.launch {
                try {
                    Log.d("InventarioApp", "Starting database initialization...")
                    initializeDefaultProducts(localRepository)
                    Log.d("InventarioApp", "Local database initialized successfully")
                    
                    // También inicializar en Firebase si hay usuario logueado
                    val usuarioActual = obtenerUsuarioActual()
                    if (usuarioActual != null) {
                        Log.d("InventarioApp", "Usuario logueado, sincronizando con Firebase...")
                        val firebaseRepo = FirebaseInventarioRepository(usuarioActual.localId)
                        // Solo inicializar si no hay productos existentes
                        initializeDefaultProductsIfNeeded(firebaseRepo)
                    }
                    
                } catch (e: Exception) {
                    Log.e("InventarioApp", "Error initializing database", e)
                }
            }
        } catch (e: Exception) {
            Log.e("InventarioApp", "Error in onCreate", e)
        }
    }
    
    private fun initializeDefaultProductsIfNeeded(repository: InventarioRepository) {
        // Esto se ejecutará en background
        applicationScope.launch {
            try {
                val usuarioActual = obtenerUsuarioActual()
                if (usuarioActual == null) {
                    Log.w("InventarioApp", "No hay usuario logueado, no se pueden inicializar productos")
                    return@launch
                }
                
                // Verificar si ya existen productos para este local
                val productosExistentes = repository.getAllProductos()
                var hasProducts = false
                
                productosExistentes.collect { productos ->
                    hasProducts = productos.isNotEmpty()
                }
                
                if (hasProducts) {
                    Log.d("InventarioApp", "Ya existen productos para local: ${usuarioActual.localNombre}, saltando inicialización")
                    return@launch
                }
                
                Log.d("InventarioApp", "Iniciando inserción de productos por defecto para local: ${usuarioActual.localNombre}")
                
                val productos = listOf(
                // EMPANADAS (en el orden exacto de tu tabla)
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
                
                // PIZZAS (en el orden exacto de tu tabla)
                Producto("MUZZARE - Muzzarella", "Pizzas"),
                Producto("JAMON - Jamón", "Pizzas"),
                Producto("PEPPERO - Pepperoni", "Pizzas"),
                
                // PASTELITOS (en el orden exacto de tu tabla)
                Producto("BATATA - Batata", "Pastelitos"),
                Producto("MEMBRIL - Membrillo", "Pastelitos"),
                
                // OTROS (en el orden exacto de tu tabla)
                Producto("DULCES - Dulces", "Otros"),
                Producto("SALADO - Salado", "Otros"),
                Producto("CHIPA - Chipa", "Otros"),
                Producto("CRIOLLITO - Criollito", "Otros")
            )
            
            Log.d("InventarioApp", "Insertando ${productos.size} productos para local: ${usuarioActual.localId}")
            
            // Usar el método específico con localId
            if (repository is LocalInventarioRepository) {
                repository.insertProductos(usuarioActual.localId, productos)
            } else {
                // Para Firebase, usar el método normal (ya maneja localId internamente)
                repository.insertProductos(productos)
            }
            
            Log.d("InventarioApp", "Productos insertados exitosamente para local: ${usuarioActual.localNombre}")
            
            } catch (e: Exception) {
                Log.e("InventarioApp", "Error inserting default products", e)
            }
        }
    }
    
    private fun initializeDefaultProducts(repository: InventarioRepository) {
        // Esto se ejecutará en background
        applicationScope.launch {
            try {
                val usuarioActual = obtenerUsuarioActual()
                if (usuarioActual == null) {
                    Log.w("InventarioApp", "No hay usuario logueado, no se pueden inicializar productos")
                    return@launch
                }
                
                Log.d("InventarioApp", "Iniciando inserción de productos por defecto para local: ${usuarioActual.localNombre}")
                
                val productos = listOf(
                // EMPANADAS (en el orden exacto de tu tabla)
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
                
                // PIZZAS (en el orden exacto de tu tabla)
                Producto("MUZZARE - Muzzarella", "Pizzas"),
                Producto("JAMON - Jamón", "Pizzas"),
                Producto("PEPPERO - Pepperoni", "Pizzas"),
                
                // PASTELITOS (en el orden exacto de tu tabla)
                Producto("BATATA - Batata", "Pastelitos"),
                Producto("MEMBRIL - Membrillo", "Pastelitos"),
                
                // OTROS (en el orden exacto de tu tabla)
                Producto("DULCES - Dulces", "Otros"),
                Producto("SALADO - Salado", "Otros"),
                Producto("CHIPA - Chipa", "Otros"),
                Producto("CRIOLLITO - Criollito", "Otros")
            )
            
            Log.d("InventarioApp", "Insertando ${productos.size} productos para local: ${usuarioActual.localId}")
            
            // Usar el método específico con localId
            if (repository is LocalInventarioRepository) {
                repository.insertProductos(usuarioActual.localId, productos)
            } else {
                // Para Firebase, usar el método normal (ya maneja localId internamente)
                repository.insertProductos(productos)
            }
            
            Log.d("InventarioApp", "Productos insertados exitosamente para local: ${usuarioActual.localNombre}")
            
            } catch (e: Exception) {
                Log.e("InventarioApp", "Error inserting default products", e)
            }
        }
    }
    
    fun obtenerUsuarioActual(): UsuarioActual? {
        return try {
            val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
            val localId = prefs.getString("localId", "")
            val localNombre = prefs.getString("localNombre", "")
            val usuarioId = prefs.getString("usuarioId", "")
            val usuario = prefs.getString("usuario", "")
            val rol = prefs.getString("rol", "")
            
            if (localId.isNullOrEmpty() || localNombre.isNullOrEmpty() || usuarioId.isNullOrEmpty() || usuario.isNullOrEmpty()) {
                null
            } else {
                UsuarioActual(
                    localId = localId,
                    localNombre = localNombre,
                    usuarioId = usuarioId,
                    usuario = usuario,
                    rol = rol ?: ""
                )
            }
        } catch (e: Exception) {
            Log.e("InventarioApp", "Error obteniendo usuario actual", e)
            null
        }
    }
    
}
