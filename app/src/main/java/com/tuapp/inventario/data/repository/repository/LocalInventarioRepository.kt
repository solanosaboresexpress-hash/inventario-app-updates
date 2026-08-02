package com.tuapp.inventario.repository

import com.tuapp.inventario.Producto
import com.tuapp.inventario.RegistroInventario
import com.tuapp.inventario.database.InventarioDatabase
import com.tuapp.inventario.database.ProductoEntity
import com.tuapp.inventario.database.RegistroInventarioEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import com.tuapp.inventario.utils.Logger

class LocalInventarioRepository(private val database: InventarioDatabase) : InventarioRepository() {
    
    private val productoDao = database.productoDao()
    private val registroDao = database.registroInventarioDao()
    
    // Productos
    @Deprecated("Use getAllProductos(localId: String) instead", ReplaceWith("getAllProductos(localId: String)"))
    override fun getAllProductos(): Flow<List<Producto>> {
        Logger.w("LocalInventarioRepository", "getAllProductos() llamado sin localId - usar getAllProductos(localId: String)")
        return emptyFlow()
    }
    
    fun getAllProductos(localId: String): Flow<List<Producto>> {
        return productoDao.getAllProductos(localId).map { entities ->
            entities.map { it.toProducto() }
        }
    }
    
    // Funcion suspend directa para sincronizacion (evita bloqueos con Flow)
    suspend fun getAllProductosSync(localId: String): List<Producto> {
        return productoDao.getAllProductosSync(localId).map { it.toProducto() }
    }
    
    @Deprecated("Use getProductosByCategoria(localId: String, categoria: String) instead", ReplaceWith("getProductosByCategoria(localId: String, categoria: String)"))
    override fun getProductosByCategoria(categoria: String): Flow<List<Producto>> {
        Logger.w("LocalInventarioRepository", "getProductosByCategoria() llamado sin localId - usar getProductosByCategoria(localId: String, categoria: String)")
        return emptyFlow()
    }
    
    fun getProductosByCategoria(localId: String, categoria: String): Flow<List<Producto>> {
        return productoDao.getProductosByCategoria(localId, categoria).map { entities ->
            entities.map { it.toProducto() }
        }
    }
    
    @Deprecated("Use insertProductos(localId: String, productos: List<Producto>) instead", ReplaceWith("insertProductos(localId: String, productos: List<Producto>)"))
    override suspend fun insertProductos(productos: List<Producto>) {
        Logger.w("LocalInventarioRepository", "insertProductos() llamado sin localId - usar insertProductos(localId: String, productos: List<Producto>)")
        // No hacer nada para evitar errores
    }
    
    suspend fun insertProductos(localId: String, productos: List<Producto>) {
        val entities = productos.map { it.toEntity(localId) }
        productoDao.insertProductos(entities)
    }
    
    @Deprecated("Use deleteAllProductos(localId: String) instead", ReplaceWith("deleteAllProductos(localId: String)"))
    override suspend fun deleteAllProductos() {
        Logger.w("LocalInventarioRepository", "deleteAllProductos() llamado sin localId - usar deleteAllProductos(localId: String)")
        // No hacer nada para evitar errores
    }
    
    suspend fun deleteAllProductos(localId: String) {
        productoDao.deleteAllProductos(localId)
    }
    
    // Registros de Inventario
    @Deprecated("Use getAllRegistros(localId: String) instead", ReplaceWith("getAllRegistros(localId: String)"))
    override fun getAllRegistros(): Flow<List<RegistroInventario>> {
        Logger.w("LocalInventarioRepository", "getAllRegistros() llamado sin localId - usar getAllRegistros(localId: String)")
        return emptyFlow()
    }
    
    fun getAllRegistros(localId: String): Flow<List<RegistroInventario>> {
        return registroDao.getAllRegistros(localId).map { entities ->
            groupRegistrosByFechaYTipo(entities)
        }
    }
    
    // Funcion suspend directa para sincronizacion (evita bloqueos con Flow)
    suspend fun getAllRegistrosSync(localId: String): List<RegistroInventario> {
        return groupRegistrosByFechaYTipo(registroDao.getAllRegistrosSync(localId))
    }
    
    @Deprecated("Use getRegistrosByFecha(localId: String, fecha: String) instead", ReplaceWith("getRegistrosByFecha(localId: String, fecha: String)"))
    override fun getRegistrosByFecha(fecha: String): Flow<List<RegistroInventario>> {
        Logger.w("LocalInventarioRepository", "getRegistrosByFecha() llamado sin localId - usar getRegistrosByFecha(localId: String, fecha: String)")
        return emptyFlow()
    }
    
    fun getRegistrosByFecha(localId: String, fecha: String): Flow<List<RegistroInventario>> {
        return registroDao.getRegistrosByFecha(localId, fecha).map { entities ->
            groupRegistrosByFechaYTipo(entities)
        }
    }
    
    @Deprecated("Use getAllFechas(localId: String) instead", ReplaceWith("getAllFechas(localId: String)"))
    override fun getAllFechas(): Flow<List<String>> {
        Logger.w("LocalInventarioRepository", "getAllFechas() llamado sin localId - usar getAllFechas(localId: String)")
        return emptyFlow()
    }
    
    fun getAllFechas(localId: String): Flow<List<String>> {
        return registroDao.getAllFechas(localId)
    }
    
    //  OPTIMIZACION: Verificar existencia de registro sin cargar datos completos
    suspend fun existeRegistro(localId: String, fecha: String, tipo: String): Boolean {
        return registroDao.existeRegistro(localId, fecha, tipo)
    }
    
    //  OPTIMIZACION: Obtener tipos de registro por fecha sin cargar datos completos
    suspend fun getTiposRegistroPorFecha(localId: String, fecha: String): List<String> {
        return registroDao.getTiposRegistroPorFecha(localId, fecha)
    }
    
    //  OPTIMIZACION: Obtener registros por fecha y tipo (ya existe, pero agregamos funcion helper)
    fun getRegistrosByFechaYTipo(localId: String, fecha: String, tipo: String): Flow<List<RegistroInventario>> {
        return registroDao.getRegistrosByFechaYTipo(localId, fecha, tipo).map { entities ->
            groupRegistrosByFechaYTipo(entities)
        }
    }
    
    @Deprecated("Use saveRegistroInventario(localId: String, registro: RegistroInventario) instead", ReplaceWith("saveRegistroInventario(localId: String, registro: RegistroInventario)"))
    override suspend fun saveRegistroInventario(registro: RegistroInventario) {
        Logger.w("LocalInventarioRepository", "saveRegistroInventario() llamado sin localId - usar saveRegistroInventario(localId: String, registro: RegistroInventario)")
        // No hacer nada para evitar errores
    }
    
    suspend fun saveRegistroInventario(localId: String, registro: RegistroInventario) {
        // Eliminar registros existentes del mismo tipo y fecha
        registroDao.deleteRegistrosByFechaYTipo(localId, registro.fecha, registro.tipo)
        
        // Insertar nuevos registros
        val entities = registro.productos.map { producto ->
            RegistroInventarioEntity(
                localId = localId,
                fecha = registro.fecha,
                tipo = registro.tipo,
                nombreProducto = producto.nombre,
                cantidad = producto.cantidad
            )
        }
        registroDao.insertRegistros(entities)
    }
    
    @Deprecated("Use deleteRegistrosByFecha(localId: String, fecha: String) instead", ReplaceWith("deleteRegistrosByFecha(localId: String, fecha: String)"))
    override suspend fun deleteRegistrosByFecha(fecha: String) {
        Logger.w("LocalInventarioRepository", "deleteRegistrosByFecha() llamado sin localId - usar deleteRegistrosByFecha(localId: String, fecha: String)")
        // No hacer nada para evitar errores
    }
    
    suspend fun deleteRegistrosByFecha(localId: String, fecha: String) {
        registroDao.deleteRegistrosByFecha(localId, fecha)
    }
    
    suspend fun deleteAllRegistros(localId: String) {
        registroDao.deleteAllRegistros(localId)
    }
    
    /**
     * Limpia TODOS los datos locales (productos y registros)
     * Util cuando se cambia de region para evitar mostrar datos de otra region
     */
    suspend fun limpiarTodosLosDatos() {
        Logger.d("LocalInventarioRepository", " Limpiando TODOS los datos locales (cambio de region)")
        productoDao.deleteAllProductos()
        registroDao.deleteAllRegistros()
        Logger.d("LocalInventarioRepository", " Todos los datos locales limpiados")
    }
    
    private fun groupRegistrosByFechaYTipo(entities: List<RegistroInventarioEntity>): List<RegistroInventario> {
        val grouped = entities.groupBy { "${it.fecha}_${it.tipo}" }
        
        return grouped.map { (_, registros) ->
            val first = registros.first()
            val productos = registros.map { 
                Producto(
                    nombre = it.nombreProducto,
                    categoria = "", // Se puede obtener de la tabla de productos si es necesario
                    cantidad = it.cantidad
                )
            }
            RegistroInventario(
                fecha = first.fecha,
                tipo = first.tipo,
                productos = productos
            )
        }
    }
}

// Extension functions para convertir entre entidades y modelos
private fun ProductoEntity.toProducto(): Producto {
    return Producto(
        nombre = this.nombre,
        categoria = this.categoria,
        cantidad = 0
    )
}

private fun Producto.toEntity(localId: String): ProductoEntity {
    return ProductoEntity(
        nombre = this.nombre,
        categoria = this.categoria,
        localId = localId
    )
}
