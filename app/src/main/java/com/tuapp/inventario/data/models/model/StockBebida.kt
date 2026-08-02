package com.tuapp.inventario.model

data class StockBebida(
    val fecha: String = "",
    val diaSemana: String = "",
    val tipoCarga: String = "", // "Ingreso Martes (Pedido Lunes)" o "Ingreso Viernes (Pedido Jueves)"
    val local: String = "",
    val productos: List<ProductoBebida> = emptyList()
)

data class ProductoBebida(
    val nombre: String = "",
    val stock: Int = 0,
    val ingreso: Int = 0
)

data class PedidoBebida(
    val fecha: String = "",
    val diaSemana: String = "",
    val tipoPedido: String = "", // "Pedido Lunes (Ingreso Martes)" o "Pedido Jueves (Ingreso Viernes)"
    val local: String = "",
    val productos: List<ProductoPedidoBebida> = emptyList(),
    val creadoPor: String = "",
    val totalProductos: Int = 0
)

data class ProductoPedidoBebida(
    val nombre: String = "",
    val stockUltimoIngreso: Int = 0,
    val consumoEstimado: Int = 0,
    val stockEstimadoActual: Int = 0,
    val pedidoSugerido: Int = 0,
    val pedidoFinal: Int = 0
)
