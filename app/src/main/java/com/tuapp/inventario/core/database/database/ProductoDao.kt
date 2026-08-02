package com.tuapp.inventario.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductoDao {
    @Query("SELECT * FROM productos WHERE localId = :localId ORDER BY categoria, nombre")
    fun getAllProductos(localId: String): Flow<List<ProductoEntity>>
    
    // Funcion suspend directa para evitar bloqueos con Flow
    @Query("SELECT * FROM productos WHERE localId = :localId ORDER BY categoria, nombre")
    suspend fun getAllProductosSync(localId: String): List<ProductoEntity>
    
    @Query("SELECT * FROM productos WHERE localId = :localId AND categoria = :categoria ORDER BY nombre")
    fun getProductosByCategoria(localId: String, categoria: String): Flow<List<ProductoEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducto(producto: ProductoEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductos(productos: List<ProductoEntity>)
    
    @Delete
    suspend fun deleteProducto(producto: ProductoEntity)
    
    @Query("DELETE FROM productos WHERE localId = :localId")
    suspend fun deleteAllProductos(localId: String)
    
    @Query("DELETE FROM productos")
    suspend fun deleteAllProductos()
}
