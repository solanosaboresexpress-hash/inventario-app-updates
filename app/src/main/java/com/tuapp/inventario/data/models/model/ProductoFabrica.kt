package com.tuapp.inventario.model

data class ProductoFabrica(
    val codigo: String,
    val nombre: String,
    val categoria: String,
    val promediosVenta: Map<String, Int>, // Dia de la semana -> promedio de venta
    val stockActual: Int = 0,
    val ventaHasta1530: Int = 0, // Venta hasta las 15:30
    val pedidoSugerido: Int = 0,
    val pedidoFinal: Int = 0
) {
    companion object {
        fun getDiasSemana(): List<String> {
            return listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        }
        
        fun getCategorias(): List<String> {
            return listOf("EMPANADAS", "PIZZAS", "PASTELITOS", "MEDIALUNAS")
        }
    }
}

data class ConfiguracionPedidoFabrica(
    val porcentajeExtra: Int = 30, // % de mas para que alcance hasta que llegue fabrica
    val horaAlerta: String = "15:30",
    val activarAlerta: Boolean = true,
    val usarAjusteTendencia: Boolean = true
)

data class AlertaStock(
    val producto: String,
    val stockPredicho: Int,
    val semana: String,
    val fecha: String
)

data class AlertaReemplazoStock(
    val productoCodigo: String,
    val productoNombre: String,
    val stockTotal: Int,
    val stockVenceManana: Int,
    val fechaVencimiento: String,
    val pedidoPendiente: Int = 0,
    val sugerenciaPedido: Int = 0,
    val prioridad: String = "ALTA" // ALTA, MEDIA, BAJA
)
