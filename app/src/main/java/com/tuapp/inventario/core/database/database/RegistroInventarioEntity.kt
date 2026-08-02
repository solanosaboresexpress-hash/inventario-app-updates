package com.tuapp.inventario.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "registros_inventario")
data class RegistroInventarioEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val localId: String, // ID del local para separar datos
    val fecha: String,
    val tipo: String, // "Ingreso de Mercaderia" o "Stock Final"
    val nombreProducto: String,
    val cantidad: Int
)
