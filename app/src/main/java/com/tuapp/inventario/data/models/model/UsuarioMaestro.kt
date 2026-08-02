package com.tuapp.inventario.model

/**
 * Modelo para usuarios maestros que tienen acceso completo al sistema
 */
data class UsuarioMaestro(
    val id: String = "",
    val usuario: String = "",
    val email: String = "", // Email del maestro (obligatorio para Firebase Auth)
    val contraseña: String = "", // Se mantiene temporalmente para migracion
    val rol: String = "maestro",
    val activo: Boolean = true,
    val fechaCreacion: String = "",
    val firebaseAuthUid: String? = null // UID de Firebase Auth cuando se migre
)
