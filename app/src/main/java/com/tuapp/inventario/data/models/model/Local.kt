package com.tuapp.inventario.model

data class Local(
    val id: String = "",
    val nombre: String = "",
    val email: String = "", // Email del local (obligatorio para Firebase Auth)
    val fechaCreacion: String = "",
    val activo: Boolean = true,
    val usuarios: Map<String, UsuarioLocal> = emptyMap(),
    val firebaseAuthUid: String? = null, // UID de Firebase Auth cuando se migre
    val debeCambiarContraseña: Boolean = false, // Indica si debe cambiar la contraseña por defecto
    val razonSocial: String = "",
    val cuit: String = "",
    val direccion: String = "",
    val telefono: String = ""
)

data class UsuarioLocal(
    val id: String = "",
    val usuario: String = "",
    val email: String = "", // Email del empleado (obligatorio para Firebase Auth)
    val contraseña: String = "", // Se mantiene temporalmente para migracion
    val rol: String = "empleado", // "admin_local", "empleado"
    val activo: Boolean = true,
    val firebaseAuthUid: String? = null, // UID de Firebase Auth cuando se migre
    val debeCambiarContraseña: Boolean = false // Indica si el admin del local debe cambiar la contraseña por defecto
)

data class UsuarioActual(
    val localId: String = "",
    val localNombre: String = "",
    val usuarioId: String = "",
    val usuario: String = "",
    val rol: String = "", // "maestro", "supervisor", "admin_local", "empleado"
    val localesAsignados: List<String> = emptyList() // Solo para supervisores
)
