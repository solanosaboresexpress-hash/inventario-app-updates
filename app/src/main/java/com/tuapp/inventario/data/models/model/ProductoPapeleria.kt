package com.tuapp.inventario.model

data class ProductoPapeleria(
    val nombre: String,
    val categoria: String, // "CAJAS", "BOLSAS", "SERVILLETAS"
    val stock: Int = 0,
    val ingreso: Int = 0
) {
    companion object {
        fun getProductosPapeleria(): List<ProductoPapeleria> {
            return listOf(
                // Cajas (DIARIO)
                ProductoPapeleria("Caja 6 Sabores", "CAJAS"),
                ProductoPapeleria("Caja Pizza Sabores", "CAJAS"),
                ProductoPapeleria("Caja 2 Docenas Sabores", "CAJAS"),
                ProductoPapeleria("Caja 1 Docena Sabores", "CAJAS"),
                
                // Bolsas (DIARIO)
                ProductoPapeleria("Bolsa Kraft N4 Sabores", "BOLSAS"),
                ProductoPapeleria("Bolsa Kraft N7 Sabores", "BOLSAS"),
                
                // Servilletas (DIARIO)
                ProductoPapeleria("Servilleta Sabores", "SERVILLETAS")
            )
        }
        
        fun getCategorias(): List<String> {
            return listOf("CAJAS", "BOLSAS", "SERVILLETAS")
        }
    }
}

data class StockPapeleria(
    val fecha: String,
    val local: String,
    val productos: List<ProductoPapeleria>
)

data class ProductoPedidoPapeleria(
    val nombre: String,
    val stockLunes: Int,
    val consumoEstimadoViernes: Int,
    val stockEstimadoViernes: Int,
    val pedidoSugerido: Int,
    val pedidoFinal: Int
)

data class PedidoPapeleria(
    val fecha: String,
    val local: String,
    val productos: List<ProductoPedidoPapeleria>,
    val creadoPor: String,
    val totalProductos: Int
)
