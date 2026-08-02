package com.tuapp.inventario.update

/**
 * Configuracion centralizada para el sistema de actualizaciones
 * Permite actualizar facilmente las versiones problematicas y criticas
 */
object UpdateConfig {
    
    /**
     * Versiones que tenian el bug de no verificar instalaciones reales
     * Estas versiones necesitan limpieza automatica del estado
     */
    val PROBLEMATIC_VERSIONS = listOf(
        // Version 2.1.10 - codigo 36 (tenia el bug)
        36,
        // Version 2.1.11 - codigo 37 (tenia el bug)
        37,
    )
    
    /**
     * Versiones que contienen correcciones criticas del sistema de actualizaciones
     * Estas versiones se forzaran incluso si ya fueron descargadas anteriormente
     */
    val CRITICAL_VERSIONS = listOf(
        // Version 2.1.12 - codigo 38 (contiene la correccion del bug)
        38,
    )
    
    /**
     * Palabras clave que indican que una actualizacion es critica
     * Si la descripcion contiene alguna de estas palabras, se considerara critica
     */
    val CRITICAL_KEYWORDS = listOf(
        "actualizaciones",
        "sistema",
        "correccion",
        "critico",
        "bug",
        "fix",
        "correccion critica",
        "sistema de actualizaciones",
        "instalacion",
        "actualizacion automatica"
    )
    
    /**
     * Version minima que debe tener el usuario para considerar el sistema "limpio"
     * Los usuarios con versiones menores a esta se consideraran problematicos
     */
    const val MIN_CLEAN_VERSION = 38  // Version 2.1.12 que contiene la correccion
    
    /**
     * Tiempo maximo (en minutos) para considerar una instalacion como "en progreso"
     * Despues de este tiempo, se limpiara el estado automaticamente
     */
    const val MAX_INSTALLATION_TIME_MINUTES = 5L
}
