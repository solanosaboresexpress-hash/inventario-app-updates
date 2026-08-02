package com.tuapp.inventario.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProductoEntity::class, RegistroInventarioEntity::class],
    version = 2,
    exportSchema = false
)
abstract class InventarioDatabase : RoomDatabase() {
    
    abstract fun productoDao(): ProductoDao
    abstract fun registroInventarioDao(): RegistroInventarioDao
    
    companion object {
        @Volatile
        private var INSTANCE: InventarioDatabase? = null
        
        // Migracion de la version 1 a la 2
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Agregar columna localId a la tabla productos
                db.execSQL("ALTER TABLE productos ADD COLUMN localId TEXT NOT NULL DEFAULT 'default'")
                
                // Agregar columna localId a la tabla registros_inventario
                db.execSQL("ALTER TABLE registros_inventario ADD COLUMN localId TEXT NOT NULL DEFAULT 'default'")
            }
        }
        
        fun getDatabase(context: Context): InventarioDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    InventarioDatabase::class.java,
                    "inventario_database"
                )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration() // Solo para desarrollo
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
