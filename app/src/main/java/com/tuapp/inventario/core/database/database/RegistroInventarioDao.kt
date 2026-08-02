package com.tuapp.inventario.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RegistroInventarioDao {
    @Query("SELECT * FROM registros_inventario WHERE localId = :localId ORDER BY fecha DESC, tipo, nombreProducto")
    fun getAllRegistros(localId: String): Flow<List<RegistroInventarioEntity>>
    
    // Funcion suspend directa para evitar bloqueos con Flow
    @Query("SELECT * FROM registros_inventario WHERE localId = :localId ORDER BY fecha DESC, tipo, nombreProducto")
    suspend fun getAllRegistrosSync(localId: String): List<RegistroInventarioEntity>
    
    @Query("SELECT * FROM registros_inventario WHERE localId = :localId AND fecha = :fecha ORDER BY tipo, nombreProducto")
    fun getRegistrosByFecha(localId: String, fecha: String): Flow<List<RegistroInventarioEntity>>
    
    @Query("SELECT * FROM registros_inventario WHERE localId = :localId AND fecha = :fecha AND tipo = :tipo ORDER BY nombreProducto")
    fun getRegistrosByFechaYTipo(localId: String, fecha: String, tipo: String): Flow<List<RegistroInventarioEntity>>
    
    @Query("SELECT DISTINCT fecha FROM registros_inventario WHERE localId = :localId ORDER BY fecha DESC")
    fun getAllFechas(localId: String): Flow<List<String>>
    
    @Query("SELECT DISTINCT fecha FROM registros_inventario WHERE localId = :localId AND fecha LIKE :mes ORDER BY fecha DESC")
    fun getFechasByMes(localId: String, mes: String): Flow<List<String>>
    
    //  OPTIMIZACION: Verificar existencia sin cargar datos completos
    @Query("SELECT COUNT(*) > 0 FROM registros_inventario WHERE localId = :localId AND fecha = :fecha AND tipo = :tipo")
    suspend fun existeRegistro(localId: String, fecha: String, tipo: String): Boolean
    
    //  OPTIMIZACION: Obtener tipos de registro por fecha sin cargar datos completos
    @Query("SELECT DISTINCT tipo FROM registros_inventario WHERE localId = :localId AND fecha = :fecha")
    suspend fun getTiposRegistroPorFecha(localId: String, fecha: String): List<String>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegistro(registro: RegistroInventarioEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegistros(registros: List<RegistroInventarioEntity>)
    
    @Delete
    suspend fun deleteRegistro(registro: RegistroInventarioEntity)
    
    @Query("DELETE FROM registros_inventario WHERE localId = :localId AND fecha = :fecha AND tipo = :tipo")
    suspend fun deleteRegistrosByFechaYTipo(localId: String, fecha: String, tipo: String)
    
    @Query("DELETE FROM registros_inventario WHERE localId = :localId AND fecha = :fecha")
    suspend fun deleteRegistrosByFecha(localId: String, fecha: String)
    
    @Query("DELETE FROM registros_inventario WHERE localId = :localId")
    suspend fun deleteAllRegistros(localId: String)
    
    @Query("DELETE FROM registros_inventario")
    suspend fun deleteAllRegistros()
}
