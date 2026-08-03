package com.tuapp.inventario.model

data class ProductoPapeleria(
    val codigo: String = "",
    val nombre: String = "",
    val categoria: String = "",
    val unidadCarga: String = "bultos", // "bultos", "unidades" o "pack"
    val unidadesPorBulto: Int = 1, // ej: 100, 1000, 2000, etc.
    val stock: Int = 0,
    val ingreso: Int = 0
) {
    companion object {
        fun getProductosPapeleria(): List<ProductoPapeleria> {
            return listOf(
                // EMBALAJE
                ProductoPapeleria("CAJA6", "Caja 6 Sabores", "EMBALAJE", "bultos", 100),
                ProductoPapeleria("CAJAPIZZA", "Caja de Pizza", "EMBALAJE", "bultos", 100),
                ProductoPapeleria("CAJA2DOC", "Caja 2 Docenas", "EMBALAJE", "bultos", 100),
                ProductoPapeleria("CAJA1DOC", "Caja 1 Docena", "EMBALAJE", "bultos", 100),
                ProductoPapeleria("KRAFT4", "Bolsa Kraft N4", "EMBALAJE", "bultos", 1000),
                ProductoPapeleria("KRAFT7", "Bolsa Kraft N7", "EMBALAJE", "bultos", 500),
                ProductoPapeleria("CAMISETA", "Bolsa Camiseta", "EMBALAJE", "bultos", 100),

                // INSUMOS
                ProductoPapeleria("SERVILLETA", "Servilletas", "INSUMOS", "bultos", 2000),
                ProductoPapeleria("FONDOCH", "Fondo Chico", "INSUMOS", "bultos", 1000),
                ProductoPapeleria("FONDOEMP", "Fondo Empanadas", "INSUMOS", "bultos", 333),
                ProductoPapeleria("FONDOGR", "Fondo Grande", "INSUMOS", "bultos", 1000),
                ProductoPapeleria("TRIPODES", "Tripodes", "INSUMOS", "bultos", 250),

                // ETIQUETAS Y CINTAS
                ProductoPapeleria("CINTASAB", "Cinta Sabores", "ETIQUETAS", "unidades", 1),
                ProductoPapeleria("FAJINAR", "Rollo Fajinar", "ETIQUETAS", "unidades", 1),
                ProductoPapeleria("TERMICO", "Rollos Termicos", "ETIQUETAS", "unidades", 1),

                // LIMPIEZA
                ProductoPapeleria("PAPELHIG", "Papel Higienico", "LIMPIEZA", "bultos", 30),
                ProductoPapeleria("FIBRA3M", "Fibra 3M", "LIMPIEZA", "unidades", 1),
                ProductoPapeleria("CONSORCIO", "Bolsa Consorcio", "LIMPIEZA", "bultos", 10),
                ProductoPapeleria("DESODORANTE", "Desodorante de Piso", "LIMPIEZA", "pack", 1),
                ProductoPapeleria("JABONMANOS", "Jabon para Manos", "LIMPIEZA", "pack", 1),
                ProductoPapeleria("LIMPIAVIDR", "Limpiavidrios", "LIMPIEZA", "pack", 1),
                ProductoPapeleria("DETERGENTE", "Detergente", "LIMPIEZA", "pack", 1),
                ProductoPapeleria("DESENGRAS", "Desengrasante", "LIMPIEZA", "pack", 1),
                ProductoPapeleria("ALCOHOL", "Alcohol", "LIMPIEZA", "pack", 1),
                ProductoPapeleria("LAVANDINA", "Lavandina", "LIMPIEZA", "pack", 1)
            )
        }

        fun getCategorias(): List<String> {
            return listOf("EMBALAJE", "INSUMOS", "ETIQUETAS", "LIMPIEZA")
        }

        fun getCategoriaEmoji(categoria: String): String {
            return when (categoria) {
                "EMBALAJE" -> "📦"
                "INSUMOS" -> "🍽️"
                "ETIQUETAS" -> "🏷️"
                "LIMPIEZA" -> "🧹"
                else -> "📦"
            }
        }
    }
}

data class StockPapeleria(
    val fecha: String = "",
    val local: String = "",
    val productos: List<ProductoPapeleria> = emptyList()
)

data class ProductoPedidoPapeleria(
    val codigo: String = "",
    val nombre: String = "",
    val categoria: String = "",
    val stockActual: Int = 0,
    val unidadesPorBulto: Int = 1,
    val consumoReal: Int = 0, // consumo calculado de la ultima semana
    val consumoPromedio: Double = 0.0, // promedio ponderado 50/30/20
    val esAnomalia: Boolean = false, // true si consumo desproporcionado vs ventas
    val pedidoSugerido: Int = 0,
    val pedidoFinal: Int = 0
)

data class PedidoPapeleria(
    val fecha: String = "",
    val local: String = "",
    val productos: List<ProductoPedidoPapeleria> = emptyList(),
    val creadoPor: String = "",
    val totalProductos: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

data class ConfiguracionPedidoPapeleria(
    val porcentajeExtra: Int = 20,
    val diaPedido: Int = 6, // Calendar.FRIDAY = 6
    val diaEntrega: Int = 2, // Calendar.MONDAY = 2
    val activarAlerta: Boolean = true,
    val horaAlerta: String = "12:00"
)

data class IngresoPapeleria(
    val fecha: String = "",
    val local: String = "",
    val productos: List<IngresoProductoPapeleria> = emptyList(),
    val confirmado: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class IngresoProductoPapeleria(
    val codigo: String = "",
    val nombre: String = "",
    val cantidadPedida: Int = 0,
    val cantidadRecibida: Int = 0
)
