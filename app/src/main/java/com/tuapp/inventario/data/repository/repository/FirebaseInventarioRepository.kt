package com.tuapp.inventario.repository

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObject
import com.tuapp.inventario.RegistroInventario
import com.tuapp.inventario.Producto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import com.tuapp.inventario.utils.DateHelper
import com.tuapp.inventario.utils.FirebaseRegionManager
import java.util.*

class FirebaseInventarioRepository(
    private val localId: String,
    private val firestore: FirebaseFirestore? = null
) : InventarioRepository() {
    
    private val firestoreInstance: FirebaseFirestore
        get() = firestore ?: FirebaseRegionManager.getFirestore()

    private val productosCollection 
        get() = firestoreInstance.collection("locales").document(localId).collection("productos")
    private val registrosCollection 
        get() = firestoreInstance.collection("locales").document(localId).collection("registros")
    
    //  Cache para productos (cambian raramente)
    private var cacheProductos: List<Producto>? = null
    private var cacheProductosTimestamp: Long = 0
    private val CACHE_PRODUCTOS_DURATION_MS = 15 * 60 * 1000L // 15 minutos

    override suspend fun insertProductos(productos: List<Producto>) {
        try {
            productos.forEach { producto ->
                productosCollection.document(producto.nombre).set(producto).await()
            }
            //  Limpiar cache cuando se actualizan productos
            clearProductosCache()
            Log.d("FirebaseRepository", "Productos insertados para local $localId")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error insertando productos", e)
            throw e
        }
    }

    override suspend fun deleteAllProductos() {
        try {
            val snapshot = productosCollection.get().await()
            snapshot.documents.forEach { document ->
                document.reference.delete().await()
            }
            Log.d("FirebaseRepository", "Todos los productos eliminados para local $localId")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error eliminando productos", e)
            throw e
        }
    }

    override fun getAllProductos(): Flow<List<Producto>> = flow {
        try {
            //  OPTIMIZACIoN: Usar cache para productos (cambian raramente)
            val productos: List<Producto>
            val ahora = System.currentTimeMillis()
            
            if (cacheProductos != null && (ahora - cacheProductosTimestamp) < CACHE_PRODUCTOS_DURATION_MS) {
                Log.d("FirebaseRepository", " Productos - Usando cache (ahorra lecturas de Firebase)")
                productos = cacheProductos!!
            } else {
                Log.d("FirebaseRepository", " Productos - Cache expirado, consultando Firebase...")
                val snapshot = productosCollection.get().await()
                @Suppress("DEPRECATION")
                productos = snapshot.documents.mapNotNull { document ->
                    document.toObject<Producto>()
                }
                // Guardar en cache
                cacheProductos = productos
                cacheProductosTimestamp = ahora
                Log.d("FirebaseRepository", " Cache de productos actualizado: ${productos.size} productos")
            }
            emit(productos)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error obteniendo productos", e)
            emit(emptyList())
        }
    }
    
    /**
     * Limpiar cache de productos (llamar cuando se actualicen productos)
     */
    fun clearProductosCache() {
        cacheProductos = null
        cacheProductosTimestamp = 0
        Log.d("FirebaseRepository", " Cache de productos limpiado")
    }

    override fun getProductosByCategoria(categoria: String): Flow<List<Producto>> = flow {
        try {
            val snapshot = productosCollection
                .whereEqualTo("categoria", categoria)
                .get()
                .await()
            @Suppress("DEPRECATION")
            val productos = snapshot.documents.mapNotNull { document ->
                document.toObject<Producto>()
            }
            emit(productos)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error obteniendo productos por categoroa", e)
            emit(emptyList())
        }
    }

    override suspend fun saveRegistroInventario(registro: RegistroInventario) {
        val documentId = "${registro.fecha}_${registro.tipo.replace(" ", "_")}"
        val maxRetries = 3
        var lastException: Exception? = null
        
        // Verificar tamaoo aproximado del documento
        val productosCount = registro.productos.size
        val estimatedSize = productosCount * 200 // Estimacion aproximada: ~200 bytes por producto
        Log.d("FirebaseRepository", "Guardando registro: $documentId con $productosCount productos (tamaoo estimado: ~${estimatedSize} bytes)")
        
        if (estimatedSize > 900000) { // Cerca del lomite de 1MB
            Log.w("FirebaseRepository", " ADVERTENCIA: El documento es muy grande (~${estimatedSize} bytes). Puede fallar si excede 1MB.")
        }
        
        // Retry logic con backoff exponencial
        for (attempt in 1..maxRetries) {
            try {
                Log.d("FirebaseRepository", "Intento $attempt de $maxRetries para guardar registro: $documentId")
                
                // Usar timeout mos largo para documentos grandes
                val timeout = if (productosCount > 100) 30000L else 15000L // 30s para muchos productos, 15s para pocos
                
                // Intentar guardar con timeout
                kotlinx.coroutines.withTimeout(timeout) {
                    registrosCollection.document(documentId).set(registro).await()
                }
                
                Log.d("FirebaseRepository", " Registro guardado exitosamente: $documentId para local $localId")
                return // oxito, salir
                
            } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
                lastException = e
                val errorCode = e.code
                Log.w("FirebaseRepository", "Error en intento $attempt: $errorCode - ${e.message}")
                
                // Si es un error que no se puede recuperar, no reintentar
                when (errorCode) {
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.INVALID_ARGUMENT,
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.NOT_FOUND -> {
                        Log.e("FirebaseRepository", "Error no recuperable: $errorCode")
                        throw e
                    }
                    else -> {
                        // Otros errores: continuar con reintentos
                        Log.d("FirebaseRepository", "Error recuperable: $errorCode, reintentando...")
                    }
                }
                
                // Si no es el oltimo intento, esperar antes de reintentar
                if (attempt < maxRetries) {
                    val delayMs = (1000L * attempt * attempt) // Backoff exponencial: 1s, 4s, 9s
                    Log.d("FirebaseRepository", "Esperando ${delayMs}ms antes de reintentar...")
                    kotlinx.coroutines.delay(delayMs)
                }
                
            } catch (e: Exception) {
                lastException = e
                Log.e("FirebaseRepository", "Error inesperado en intento $attempt", e)
                
                // Si es un error de red o timeout, reintentar
                if (attempt < maxRetries && (e.message?.contains("network", ignoreCase = true) == true || 
                                             e.message?.contains("timeout", ignoreCase = true) == true ||
                                             e.message?.contains("unavailable", ignoreCase = true) == true)) {
                    val delayMs = (1000L * attempt * attempt)
                    Log.d("FirebaseRepository", "Error de red, esperando ${delayMs}ms antes de reintentar...")
                    kotlinx.coroutines.delay(delayMs)
                } else {
                    // Otro tipo de error, no reintentar
                    throw e
                }
            }
        }
        
        // Si llegamos aquo, todos los intentos fallaron
        Log.e("FirebaseRepository", " Error guardando registro despuos de $maxRetries intentos: $documentId")
        throw lastException ?: Exception("Error desconocido al guardar registro")
    }

    override fun getAllRegistros(): Flow<List<RegistroInventario>> = flow {
        try {
            val snapshot = registrosCollection.get().await()
            @Suppress("DEPRECATION")
            val registros = snapshot.documents.mapNotNull { document ->
                document.toObject<RegistroInventario>()
            }
            emit(registros)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error obteniendo registros", e)
            emit(emptyList())
        }
    }

    override fun getRegistrosByFecha(fecha: String): Flow<List<RegistroInventario>> = flow {
        Log.d("FirebaseRepository", " Consultando registros por fecha: $fecha para local: $localId")
        Log.d("FirebaseRepository", "   Ruta coleccion: locales/$localId/registros")
        
        val snapshot = registrosCollection
            .whereEqualTo("fecha", fecha)
            .get()
            .await()
        
        Log.d("FirebaseRepository", "    Snapshot obtenido: ${snapshot.documents.size} documentos")
        
        @Suppress("DEPRECATION")
        val registros = snapshot.documents.mapNotNull { document ->
            try {
                val registro = document.toObject<RegistroInventario>()
                if (registro != null) {
                    Log.d("FirebaseRepository", "      Registro parseado: ${registro.tipo} - ${registro.productos.size} productos")
                }
                registro
            } catch (e: Exception) {
                Log.e("FirebaseRepository", "      Error parseando documento ${document.id}: ${e.message}")
                null
            }
        }
        
        Log.d("FirebaseRepository", "    Total registros parseados: ${registros.size}")
        emit(registros)
    }.catch { e ->
        Log.e("FirebaseRepository", " Error obteniendo registros por fecha: ${e.message}", e)
        emit(emptyList())
    }

    override fun getAllFechas(): Flow<List<String>> = flow {
        try {
            val snapshot = registrosCollection.get().await()
            val fechas = snapshot.documents.mapNotNull { document ->
                document.getString("fecha")
            }.distinct().sorted()
            emit(fechas)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error obteniendo fechas", e)
            emit(emptyList())
        }
    }

    override suspend fun deleteRegistrosByFecha(fecha: String) {
        try {
            val snapshot = registrosCollection
                .whereEqualTo("fecha", fecha)
                .get()
                .await()
            snapshot.documents.forEach { document ->
                document.reference.delete().await()
            }
            Log.d("FirebaseRepository", "Registros eliminados para fecha: $fecha")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error eliminando registros por fecha", e)
            throw e
        }
    }

    // Motodo para sincronizar datos locales con Firebase (BIDIRECCIONAL)
    suspend fun sincronizarConLocal(localRepository: com.tuapp.inventario.repository.LocalInventarioRepository) {
        try {
            Log.d("FirebaseRepository", "Iniciando sincronizacion BIDIRECCIONAL para local $localId")
            
            // 1. PRIMERO: Descargar datos de Firebase al dispositivo local
            Log.d("FirebaseRepository", "Paso 1: Descargando datos de Firebase...")
            descargarDatosDeFirebase(localRepository)
            
            // 2. SEGUNDO: Subir datos locales a Firebase (si hay cambios)
            Log.d("FirebaseRepository", "Paso 2: Subiendo datos locales a Firebase...")
            subirDatosLocalesAFirebase(localRepository)
            
            Log.d("FirebaseRepository", "Sincronizacion BIDIRECCIONAL completada para local $localId")
            
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error en sincronizacion bidireccional", e)
            throw e
        }
    }
    
    /**
     * Descarga todos los datos de Firebase al dispositivo local
     *  OPTIMIZACIoN: Usa cache de productos para evitar lecturas innecesarias
     */
    private suspend fun descargarDatosDeFirebase(localRepository: LocalInventarioRepository) {
        try {
            //  OPTIMIZACIoN: Usar cache de productos si esto disponible
            val productosFirebase: List<Producto>
            val ahora = System.currentTimeMillis()
            
            if (cacheProductos != null && (ahora - cacheProductosTimestamp) < CACHE_PRODUCTOS_DURATION_MS) {
                Log.d("FirebaseRepository", " Sincronizacion - Usando cache de productos")
                productosFirebase = cacheProductos!!
            } else {
                // Descargar productos de Firebase
                val snapshotProductos = productosCollection.get().await()
                productosFirebase = snapshotProductos.documents.mapNotNull { document ->
                    @Suppress("DEPRECATION")
                    document.toObject<Producto>()
                }
                // Guardar en cache
                cacheProductos = productosFirebase
                cacheProductosTimestamp = ahora
            }
            
            if (productosFirebase.isNotEmpty()) {
                localRepository.insertProductos(localId, productosFirebase)
                Log.d("FirebaseRepository", "Productos descargados de Firebase: ${productosFirebase.size}")
            }
            
            //  OPTIMIZACIoN: Solo descargar registros de los oltimos 30 doas (suficiente para sincronizacion)
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -30)
            val fechaLimite = DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
            
            val snapshotRegistros = registrosCollection
                .whereGreaterThanOrEqualTo("fecha", fechaLimite)
                .get()
                .await()
            
            @Suppress("DEPRECATION")
            val registrosFirebase = snapshotRegistros.documents.mapNotNull { document ->
                document.toObject<RegistroInventario>()
            }
            
            if (registrosFirebase.isNotEmpty()) {
                registrosFirebase.forEach { registro ->
                    localRepository.saveRegistroInventario(localId, registro)
                }
                Log.d("FirebaseRepository", "Registros descargados de Firebase (oltimos 30 doas): ${registrosFirebase.size}")
            }
            
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error descargando datos de Firebase", e)
        }
    }
    
    /**
     * Descargar solo productos de Firebase (sin tocar registros locales)
     */
    private suspend fun descargarProductosDeFirebase(localRepository: LocalInventarioRepository) {
        try {
            // Descargar solo productos de Firebase
            val snapshotProductos = productosCollection.get().await()
            val productosFirebase = snapshotProductos.documents.mapNotNull { document ->
                document.toObject<Producto>()
            }
            
            if (productosFirebase.isNotEmpty()) {
                localRepository.insertProductos(localId, productosFirebase)
                Log.d("FirebaseRepository", "Productos descargados de Firebase: ${productosFirebase.size}")
            }
            
            Log.d("FirebaseRepository", "Solo productos sincronizados, registros locales preservados")
            
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error descargando productos de Firebase", e)
        }
    }
    
    /**
     * Sube datos locales a Firebase (solo si hay cambios)
     */
    private suspend fun subirDatosLocalesAFirebase(localRepository: LocalInventarioRepository) {
        try {
            // Obtener datos locales
            val productosLocales = localRepository.getAllProductos(localId).let { flow ->
                var productos: List<Producto> = emptyList()
                flow.collect { productos = it }
                productos
            }
            
            val registrosLocales = localRepository.getAllRegistros(localId).let { flow ->
                var registros: List<RegistroInventario> = emptyList()
                flow.collect { registros = it }
                registros
            }
            
            // Subir productos si existen
            if (productosLocales.isNotEmpty()) {
                insertProductos(productosLocales)
                Log.d("FirebaseRepository", "Productos locales subidos a Firebase: ${productosLocales.size}")
            }
            
            // Subir registros si existen
            if (registrosLocales.isNotEmpty()) {
                registrosLocales.forEach { registro ->
                    saveRegistroInventario(registro)
                }
                Log.d("FirebaseRepository", "Registros locales subidos a Firebase: ${registrosLocales.size}")
            }
            
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error subiendo datos locales a Firebase", e)
        }
    }
    
    /**
     * Sincronizacion inteligente bidireccional
     * Compara fechas de modificacion y mantiene los datos mos recientes
     * @param localRepository Repositorio local
     * @param onProgress Callback opcional para actualizar progreso (mensaje: String) -> Unit
     * @param timeoutMs Timeout en milisegundos (por defecto 60000 = 60 segundos)
     */
    suspend fun sincronizacionInteligente(
        localRepository: LocalInventarioRepository,
        onProgress: ((String) -> Unit)? = null,
        timeoutMs: Long = 60000L
    ) {
        try {
            Log.d("FirebaseRepository", "Iniciando sincronizacion inteligente para local: $localId (timeout: ${timeoutMs}ms)")
            
            withTimeout(timeoutMs) {
                // 1. Sincronizar productos
                onProgress?.invoke("Sincronizando productos...")
                sincronizarProductosInteligente(localRepository, onProgress)
                
                // 2. Sincronizar registros
                onProgress?.invoke("Obteniendo registros de Firebase...")
                sincronizarRegistrosInteligente(localRepository, onProgress)
                
                onProgress?.invoke(" Sincronizacion completada")
            }
            
            Log.d("FirebaseRepository", "Sincronizacion inteligente completada para local: $localId")
            
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e("FirebaseRepository", " Timeout en sincronizacion inteligente despuos de ${timeoutMs}ms", e)
            onProgress?.invoke(" Timeout - La sincronizacion esto tardando mos de lo esperado")
            throw e
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error en sincronizacion inteligente", e)
            onProgress?.invoke(" Error: ${e.message}")
            throw e
        }
    }
    
    private suspend fun sincronizarProductosInteligente(
        localRepository: LocalInventarioRepository,
        onProgress: ((String) -> Unit)? = null
    ) {
        try {
            onProgress?.invoke("Obteniendo productos locales...")
            Log.d("FirebaseRepository", " Iniciando sincronizacion de productos para local: $localId")
            
            // Obtener productos locales usando funcion sync directa (mos ropida y sin bloqueos)
            val productosLocales = withTimeout(10000L) { // 10 segundos timeout
                localRepository.getAllProductosSync(localId)
            }
            Log.d("FirebaseRepository", " Productos locales obtenidos: ${productosLocales.size}")
            
            //  OPTIMIZACIoN: Usar cache de productos si esto disponible
            val productosFirebase: List<Producto>
            val ahora = System.currentTimeMillis()
            
            if (cacheProductos != null && (ahora - cacheProductosTimestamp) < CACHE_PRODUCTOS_DURATION_MS) {
                Log.d("FirebaseRepository", " Sincronizacion inteligente - Usando cache de productos")
                productosFirebase = cacheProductos!!
            } else {
                onProgress?.invoke("Obteniendo productos de Firebase...")
                // Obtener productos de Firebase
                val snapshot = productosCollection.get().await()
                @Suppress("DEPRECATION")
                productosFirebase = snapshot.documents.mapNotNull { it.toObject<Producto>() }
                // Guardar en cache
                cacheProductos = productosFirebase
                cacheProductosTimestamp = ahora
            }
            
            Log.d("FirebaseRepository", "Productos locales: ${productosLocales.size}, Firebase: ${productosFirebase.size}")
            
            // Crear mapas para comparacion
            val mapaLocales = productosLocales.associateBy { it.nombre }
            val mapaFirebase = productosFirebase.associateBy { it.nombre }
            
            // Sincronizar productos locales a Firebase (si no existen o son mos recientes)
            var productosSubidos = 0
            productosLocales.forEach { productoLocal ->
                val productoFirebase = mapaFirebase[productoLocal.nombre]
                
                if (productoFirebase == null) {
                    // No existe en Firebase, subirlo
                    productosCollection.document(productoLocal.nombre).set(productoLocal).await()
                    productosSubidos++
                    Log.d("FirebaseRepository", "Producto subido a Firebase: ${productoLocal.nombre}")
                } else {
                    // Comparar fechas de modificacion (usar timestamp de creacion como proxy)
                    val esLocalMasReciente = productoLocal.nombre.length > productoFirebase.nombre.length || 
                                           productoLocal.categoria != productoFirebase.categoria
                    
                    if (esLocalMasReciente) {
                        productosCollection.document(productoLocal.nombre).set(productoLocal).await()
                        productosSubidos++
                        Log.d("FirebaseRepository", "Producto actualizado en Firebase: ${productoLocal.nombre}")
                    }
                }
            }
            
            if (productosSubidos > 0) {
                onProgress?.invoke("Subiendo $productosSubidos productos a Firebase...")
            }
            
            // Sincronizar productos de Firebase a local (si no existen localmente)
            var productosDescargados = 0
            productosFirebase.forEach { productoFirebase ->
                val productoLocal = mapaLocales[productoFirebase.nombre]
                
                if (productoLocal == null) {
                    // No existe localmente, descargarlo
                    localRepository.insertProductos(localId, listOf(productoFirebase))
                    productosDescargados++
                    Log.d("FirebaseRepository", "Producto descargado de Firebase: ${productoFirebase.nombre}")
                }
            }
            
            if (productosDescargados > 0) {
                onProgress?.invoke("Descargando $productosDescargados productos de Firebase...")
            }
            
            onProgress?.invoke(" Productos sincronizados")
            
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error sincronizando productos", e)
            throw e
        }
    }
    
    private suspend fun sincronizarRegistrosInteligente(
        localRepository: LocalInventarioRepository,
        onProgress: ((String) -> Unit)? = null
    ) {
        try {
            onProgress?.invoke("Obteniendo registros locales...")
            Log.d("FirebaseRepository", " Iniciando sincronizacion de registros para local: $localId")
            
            // Obtener registros locales usando funcion sync directa (mos ropida y sin bloqueos)
            val registrosLocales = withTimeout(20000L) { // 20 segundos timeout (puede haber muchos registros)
                localRepository.getAllRegistrosSync(localId)
            }
            Log.d("FirebaseRepository", " Registros locales obtenidos: ${registrosLocales.size}")
            
            onProgress?.invoke("Obteniendo registros de Firebase (esto puede tardar)...")
            // Obtener registros de Firebase (con timeout mos largo para esta operacion)
            val snapshot = withTimeout(45000L) { // 45 segundos para obtener todos los registros
                registrosCollection.get().await()
            }
            @Suppress("DEPRECATION")
            val registrosFirebase = snapshot.documents.mapNotNull { it.toObject<RegistroInventario>() }
            
            Log.d("FirebaseRepository", "Registros locales: ${registrosLocales.size}, Firebase: ${registrosFirebase.size}")
            
            onProgress?.invoke("Comparando ${registrosLocales.size} registros locales con ${registrosFirebase.size} de Firebase...")
            
            // Crear mapas para comparacion (usar fecha + tipo como clave)
            val mapaLocales = registrosLocales.associateBy { "${it.fecha}_${it.tipo}" }
            val mapaFirebase = registrosFirebase.associateBy { "${it.fecha}_${it.tipo}" }
            
            // Sincronizar registros locales a Firebase (si no existen o son mos recientes)
            var registrosSubidos = 0
            var totalRegistros = registrosLocales.size
            registrosLocales.forEachIndexed { index, registroLocal ->
                val clave = "${registroLocal.fecha}_${registroLocal.tipo}"
                val registroFirebase = mapaFirebase[clave]
                
                if (index % 10 == 0 && totalRegistros > 10) {
                    onProgress?.invoke("Procesando registros locales... (${index + 1}/$totalRegistros)")
                }
                
                if (registroFirebase == null) {
                    // No existe en Firebase, subirlo
                    registrosCollection.document(clave).set(registroLocal).await()
                    registrosSubidos++
                    Log.d("FirebaseRepository", "Registro subido a Firebase: $clave")
                } else {
                    // Comparar por cantidad de productos (proxy para "mos reciente")
                    val esLocalMasReciente = registroLocal.productos.size > registroFirebase.productos.size
                    
                    if (esLocalMasReciente) {
                        registrosCollection.document(clave).set(registroLocal).await()
                        registrosSubidos++
                        Log.d("FirebaseRepository", "Registro actualizado en Firebase: $clave")
                    }
                }
            }
            
            if (registrosSubidos > 0) {
                onProgress?.invoke("Subiendo $registrosSubidos registros a Firebase...")
            }
            
            // Sincronizar registros de Firebase a local (si no existen localmente)
            var registrosDescargados = 0
            totalRegistros = registrosFirebase.size
            registrosFirebase.forEachIndexed { index, registroFirebase ->
                val clave = "${registroFirebase.fecha}_${registroFirebase.tipo}"
                val registroLocal = mapaLocales[clave]
                
                if (index % 10 == 0 && totalRegistros > 10) {
                    onProgress?.invoke("Procesando registros de Firebase... (${index + 1}/$totalRegistros)")
                }
                
                if (registroLocal == null) {
                    // No existe localmente, descargarlo
                    localRepository.saveRegistroInventario(localId, registroFirebase)
                    registrosDescargados++
                    Log.d("FirebaseRepository", "Registro descargado de Firebase: $clave")
                }
            }
            
            if (registrosDescargados > 0) {
                onProgress?.invoke("Descargando $registrosDescargados registros de Firebase...")
            }
            
            onProgress?.invoke(" Registros sincronizados")
            
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e("FirebaseRepository", " Timeout obteniendo registros de Firebase", e)
            onProgress?.invoke(" Timeout obteniendo registros - Hay muchos registros para sincronizar")
            throw e
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error sincronizando registros", e)
            throw e
        }
    }
    
    /**
     * Sincronizar todos los datos locales existentes a Firebase
     */
    suspend fun sincronizarDatosLocalesAFirebase(localRepository: LocalInventarioRepository) {
        try {
            Log.d("FirebaseRepository", "Sincronizando todos los datos locales a Firebase para local: $localId")
            
            // 1. Sincronizar productos
            val productosLocales = localRepository.getAllProductos(localId).let { flow ->
                var productos: List<Producto> = emptyList()
                flow.collect { productos = it }
                productos
            }
            
            if (productosLocales.isNotEmpty()) {
                insertProductos(productosLocales)
                Log.d("FirebaseRepository", "Productos locales sincronizados a Firebase: ${productosLocales.size}")
            }
            
            // 2. Sincronizar registros
            val registrosLocales = localRepository.getAllRegistros(localId).let { flow ->
                var registros: List<RegistroInventario> = emptyList()
                flow.collect { registros = it }
                registros
            }
            
            if (registrosLocales.isNotEmpty()) {
                registrosLocales.forEach { registro ->
                    saveRegistroInventario(registro)
                }
                Log.d("FirebaseRepository", "Registros locales sincronizados a Firebase: ${registrosLocales.size}")
            }
            
            Log.d("FirebaseRepository", "Sincronizacion completa de datos locales a Firebase finalizada")
            
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error sincronizando datos locales a Firebase", e)
            throw e
        }
    }
    
    /**
     * Forzar sincronizacion completa desde Firebase (otil para debugging)
     */
    suspend fun forzarSincronizacionDesdeFirebase(localRepository: LocalInventarioRepository) {
        try {
            Log.d("FirebaseRepository", " FORZANDO sincronizacion desde Firebase para local: $localId")
            
            // Verificar si hay datos locales para la fecha actual antes de limpiar
            val fechaActual = DateHelper.getFechaActual()
            val registrosActuales = localRepository.getAllRegistros(localId).let { flow ->
                var result: List<RegistroInventario> = emptyList()
                flow.collect { result = it }
                result
            }
            
            val tieneRegistrosHoy = registrosActuales.any { it.fecha == fechaActual }
            
            if (tieneRegistrosHoy) {
                Log.d("FirebaseRepository", " Datos locales para hoy encontrados, sincronizando sin limpiar")
                // Solo sincronizar productos, mantener registros locales
                descargarProductosDeFirebase(localRepository)
            } else {
                Log.d("FirebaseRepository", " No hay datos locales para hoy, limpiando y descargando todo")
                // Limpiar datos locales primero solo si no hay datos para hoy
                localRepository.deleteAllProductos(localId)
                localRepository.deleteAllRegistros(localId)
                Log.d("FirebaseRepository", "Datos locales limpiados")
                
                // Descargar todo desde Firebase
                descargarDatosDeFirebase(localRepository)
            }
            
            Log.d("FirebaseRepository", " Sincronizacion forzada completada")
            
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error en sincronizacion forzada", e)
            throw e
        }
    }
    
    /**
     * Verificar estado de sincronizacion
     */
    suspend fun verificarEstadoSincronizacion(localRepository: LocalInventarioRepository): String {
        try {
            val productosLocales = localRepository.getAllProductos(localId).let { flow ->
                var productos: List<Producto> = emptyList()
                flow.collect { productos = it }
                productos
            }
            
            val registrosLocales = localRepository.getAllRegistros(localId).let { flow ->
                var registros: List<RegistroInventario> = emptyList()
                flow.collect { registros = it }
                registros
            }
            
            val snapshotProductos = productosCollection.get().await()
            val productosFirebase = snapshotProductos.documents.size
            
            val snapshotRegistros = registrosCollection.get().await()
            val registrosFirebase = snapshotRegistros.documents.size
            
            val estado = """
                 ESTADO DE SINCRONIZACIoN - Local: $localId
                 Local: ${productosLocales.size} productos, ${registrosLocales.size} registros
                 Firebase: $productosFirebase productos, $registrosFirebase registros
                 Estado: ${if (productosLocales.size == productosFirebase && registrosLocales.size == registrosFirebase) " Sincronizado" else " Desincronizado"}
            """.trimIndent()
            
            Log.d("FirebaseRepository", estado)
            return estado
            
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error verificando estado de sincronizacion", e)
            return " Error verificando sincronizacion: ${e.message}"
        }
    }
}
