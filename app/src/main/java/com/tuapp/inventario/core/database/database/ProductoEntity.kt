package com.tuapp.inventario.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "productos")
data class ProductoEntity(
    @PrimaryKey
    val nombre: String,
    val categoria: String,
    val localId: String // ID del local para separar datos
)
