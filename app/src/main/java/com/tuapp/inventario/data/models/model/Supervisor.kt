package com.tuapp.inventario.model

/**
 * Modelo para supervisores que gestionan locales especificos asignados por el maestro
 */
data class Supervisor(
    val id: String = "",
    val usuario: String = "",
    val email: String = "", // Email del supervisor (obligatorio para Firebase Auth)
    val contraseña: String = "", // Se mantiene temporalmente para migracion
    val rol: String = "supervisor",
    val activo: Boolean = true,
    val localesAsignados: List<String> = emptyList(), // IDs de locales asignados
    val creadoPor: String = "", // ID del usuario maestro que lo creo
    val fechaCreacion: String = "",
    val firebaseAuthUid: String? = null, // UID de Firebase Auth cuando se migre
    val esAsistente: Boolean = false // Indica si el supervisor es asistente del maestro (tiene acceso completo)
)
