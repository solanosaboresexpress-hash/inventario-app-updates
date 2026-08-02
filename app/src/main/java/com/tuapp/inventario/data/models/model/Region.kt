package com.tuapp.inventario.model

/**
 * Modelo para regiones que agrupan locales, supervisores y maestros
 * Cada region tiene su propio proyecto Firebase separado
 */
data class Region(
    val id: String = "",              // Ej: "region_1", "region_2"
    val nombre: String = "",           // Ej: "Region 1", "Region 2"
    val proyectoFirebaseId: String = "", // ID del proyecto Firebase (ej: "region2-99f04")
    val googleServicesJson: String = "",  // Nombre del archivo en res/raw/ (ej: "google-services-region2")
    val maestroNombre: String = "",    // Ej: "Lucas PIARRISTEGUY"
    val activo: Boolean = true,
    val firebaseConfig: FirebaseConfig? = null  // Configuración embebida para cuando no hay archivo
)

data class FirebaseConfig(
    val apiKey: String = "",
    val authDomain: String = "",
    val projectId: String = "",
    val storageBucket: String = "",
    val messagingSenderId: String = "",
    val appId: String = ""
)

