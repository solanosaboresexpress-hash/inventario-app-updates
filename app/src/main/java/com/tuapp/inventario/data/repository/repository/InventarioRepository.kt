package com.tuapp.inventario.repository

import com.tuapp.inventario.Producto
import com.tuapp.inventario.RegistroInventario
import com.tuapp.inventario.database.InventarioDatabase
import com.tuapp.inventario.database.ProductoEntity
import com.tuapp.inventario.database.RegistroInventarioEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

abstract class InventarioRepository {
    
    // Productos
    abstract fun getAllProductos(): Flow<List<Producto>>
    
    abstract fun getProductosByCategoria(categoria: String): Flow<List<Producto>>
    
    abstract suspend fun insertProductos(productos: List<Producto>)
    
    abstract suspend fun deleteAllProductos()
    
    // Registros de Inventario
    abstract fun getAllRegistros(): Flow<List<RegistroInventario>>
    
    abstract fun getRegistrosByFecha(fecha: String): Flow<List<RegistroInventario>>
    
    abstract fun getAllFechas(): Flow<List<String>>
    
    abstract suspend fun saveRegistroInventario(registro: RegistroInventario)
    
    abstract suspend fun deleteRegistrosByFecha(fecha: String)
}

// Extension functions para convertir entre entidades y modelos
private fun ProductoEntity.toProducto(): Producto {
    return Producto(
        nombre = this.nombre,
        categoria = this.categoria,
        cantidad = 0
    )
}

private fun Producto.toEntity(): ProductoEntity {
    return ProductoEntity(
        nombre = this.nombre,
        categoria = this.categoria,
        localId = "default" // Fallback para compatibilidad
    )
}
