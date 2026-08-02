package com.tuapp.inventario

import java.util.Date

data class Producto(
    val nombre: String = "",
    val categoria: String = "",
    var cantidad: Int = 0
) {
    // Constructor sin argumentos para Firebase
    constructor() : this("", "", 0)
}

data class RegistroInventario(
    val fecha: String = "",
    val tipo: String = "", // "Ingreso" o "Stock Final"
    val productos: List<Producto> = emptyList(),
    val noRecibioMercaderia: Boolean = false // Indica si el local no recibió mercadería ese día
) {
    // Constructor sin argumentos para Firebase
    constructor() : this("", "", emptyList(), false)
}
