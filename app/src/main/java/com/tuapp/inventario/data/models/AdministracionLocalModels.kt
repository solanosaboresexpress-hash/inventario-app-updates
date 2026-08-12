package com.tuapp.inventario.data.models

import com.google.firebase.firestore.PropertyName
import java.util.Date

/**
 * Modelo para documentos del local con vencimientos
 */
data class DocumentoLocal(
    val id: String = "",
    val localId: String = "",
    @PropertyName("tipo")
    val tipoDocumento: TipoDocumento = TipoDocumento.FUMIGACION,
    val nombreDocumento: String = "",
    val fechaVencimiento: Date? = null,
    val fechaEmision: Date? = null,
    val numeroCertificado: String = "",
    val empresaResponsable: String = "",
    val telefono: String = "",
    val observaciones: String = "",
    val archivoUrl: String = "",
    val estado: EstadoDocumento = EstadoDocumento.VIGENTE,
    val diasParaVencer: Int = 0,
    val creadoEn: Date = Date(),
    val actualizadoEn: Date = Date()
)

enum class TipoDocumento(val displayName: String, val icon: String, val color: String) {
    FUMIGACION("Fumigación", "pesticide", "#4CAF50"),
    OBLEA_MATAFUEGOS("Oblea Matafuegos", "fire_extinguisher", "#FF5722"),
    ANALISIS_AGUA("Análisis de Agua", "water_drop", "#2196F3"),
    REBA("REBA", "health_and_safety", "#FF9800"),
    POLIZA_SEGURO("Póliza de Seguro", "security", "#9C27B0"),
    HABILITACION_MUNICIPAL("Habilitación Municipal", "location_city", "#795548"),
    CERTIFICADO_HIGIENE("Certificado de Higiene", "sanitary", "#607D8B")
}

enum class EstadoDocumento(val displayName: String, val color: String) {
    VIGENTE("Vigente", "#4CAF50"),
    POR_VENCER("Por Vencer", "#FF9800"),
    VENCIDO("Vencido", "#F44336"),
    EN_TRAMITE("En Trámite", "#2196F3")
}

/**
 * Modelo para personal del local
 */
data class PersonalLocal(
    val id: String = "",
    val localId: String = "",
    val nombre: String = "",
    val apellido: String = "",
    val dni: String = "",
    val cargo: String = "",
    val categoria: String = "",
    val telefono: String = "",
    val email: String = "",
    val fechaIngreso: Date? = null,
    val estado: EstadoPersonal = EstadoPersonal.ACTIVO,
    val libretaSanitaria: LibretaSanitaria? = null,
    val cursoManipulacion: CursoManipulacion? = null,
    val libretaSanitariaBase64: String? = null,
    val cursoManipulacionBase64: String? = null,
    val creadoEn: Date = Date(),
    val actualizadoEn: Date = Date()
) {
    val nombreCompleto: String
        get() = "$nombre $apellido"
}

enum class EstadoPersonal(val displayName: String, val color: String) {
    ACTIVO("Activo", "#4CAF50"),
    VACACIONES("Vacaciones", "#2196F3"),
    SUSPENDIDO("Suspendido", "#FF9800"),
    INACTIVO("Inactivo", "#F44336")
}

val CATEGORIAS_PERSONAL = listOf("GERENTE", "ENCARGADO", "ENTRENADOR", "FULL-TIME", "PART-TIME")

/**
 * Modelo para libreta sanitaria del personal
 */
data class LibretaSanitaria(
    val numeroLibreta: String = "",
    val fechaEmision: Date? = null,
    val fechaVencimiento: Date? = null,
    val categoria: String = "",
    val estado: EstadoCertificado = EstadoCertificado.VIGENTE,
    val diasParaVencer: Int = 0,
    val archivoUrl: String = ""
)

/**
 * Modelo para curso de manipulación de alimentos
 */
data class CursoManipulacion(
    val institucion: String = "",
    val tipoCurso: String = "",
    val fechaEmision: Date? = null,
    val fechaVencimiento: Date? = null,
    val numeroCertificado: String = "",
    val estado: EstadoCertificado = EstadoCertificado.VIGENTE,
    val diasParaVencer: Int = 0,
    val archivoUrl: String = ""
)

enum class EstadoCertificado(val displayName: String, val color: String) {
    VIGENTE("Vigente", "#4CAF50"),
    POR_VENCER("Por Vencer", "#FF9800"),
    VENCIDO("Vencido", "#F44336"),
    NO_POSEE("No Posee", "#9E9E9E"),
    EN_TRAMITE("En Trámite", "#2196F3")
}

/**
 * Modelo para notificaciones de vencimientos
 */
data class NotificacionVencimiento(
    val id: String = "",
    val localId: String = "",
    val tipo: TipoNotificacion = TipoNotificacion.DOCUMENTO_LOCAL,
    val elementoId: String = "",
    val elementoNombre: String = "",
    val tipoElemento: String = "",
    val fechaVencimiento: Date? = null,
    val diasParaVencer: Int = 0,
    val mensaje: String = "",
    val prioridad: PrioridadNotificacion = PrioridadNotificacion.MEDIA,
    val leida: Boolean = false,
    val creadoEn: Date = Date()
)

enum class TipoNotificacion(val displayName: String) {
    DOCUMENTO_LOCAL("Documento del Local"),
    LIBRETA_SANITARIA("Libreta Sanitaria"),
    CURSO_MANIPULACION("Curso Manipulación")
}

enum class PrioridadNotificacion(val displayName: String, val color: String) {
    BAJA("Baja", "#4CAF50"),
    MEDIA("Media", "#FF9800"),
    ALTA("Alta", "#F44336"),
    CRITICA("Crítica", "#B71C1C")
}
