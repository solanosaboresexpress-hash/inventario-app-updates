package com.tuapp.inventario.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SimpleDriveRelease(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val fileName: String,
    val description: String,
    val forceUpdate: Boolean = false
)

class SimpleDriveUpdateChecker(private val context: Context) {
    
    companion object {
        private const val TAG = "SimpleDriveUpdateChecker"
        // URL del archivo JSON con informacion de version (ahora en GitHub)
        private const val VERSION_INFO_URL = "https://raw.githubusercontent.com/solanosaboresexpress-hash/inventario-app-updates/refs/heads/main/version_info.json"
    }
    
    /**
     * Verifica si hay una actualizacion disponible desde GitHub
     */
    suspend fun checkForUpdate(): SimpleDriveRelease? {
        return try {
            Log.d(TAG, " Verificando actualizaciones desde GitHub...")
            
            val currentVersionCode = getCurrentVersionCode()
            val currentVersionName = getCurrentVersionName()
            Log.d(TAG, " Version actual: $currentVersionCode ($currentVersionName)")
            
            val release = getLatestRelease()
            
            if (release != null) {
                Log.d(TAG, " Version remota: ${release.versionCode} (${release.versionName})")
                
                // Verificar si realmente hay una actualizacion
                if (release.versionCode > currentVersionCode) {
                    Log.d(TAG, " Actualizacion disponible: ${release.versionName} (${release.versionCode})")
                    
                    // Verificacion adicional: comparar tambien el versionName
                    if (release.versionName != currentVersionName) {
                        Log.d(TAG, " Confirmado: version diferente detectada")
                        release
                    } else {
                        Log.w(TAG, " Mismo versionName pero versionCode diferente - posible problema")
                        null
                    }
                } else if (release.versionCode == currentVersionCode) {
                    Log.d(TAG, " Aplicacion actualizada (misma version)")
                    null
                } else {
                    Log.w(TAG, " Version remota es menor que la actual - posible problema de configuracion")
                    null
                }
            } else {
                Log.e(TAG, " No se pudo obtener informacion de la version remota")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, " Error verificando actualizaciones", e)
            null
        }
    }
    
    /**
     * Obtiene la informacion de la ultima version desde GitHub
     */
    private suspend fun getLatestRelease(): SimpleDriveRelease? {
        return withContext(Dispatchers.IO) {
            try {
                // Agregar timestamp para evitar cache
                val timestamp = System.currentTimeMillis()
                val urlWithTimestamp = "$VERSION_INFO_URL?t=$timestamp"
                val url = URL(urlWithTimestamp)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                // Headers para evitar cache
                connection.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                connection.setRequestProperty("Pragma", "no-cache")
                connection.setRequestProperty("Expires", "0")
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    
                    SimpleDriveRelease(
                        versionCode = json.getInt("versionCode"),
                        versionName = json.getString("versionName"),
                        downloadUrl = json.getString("downloadUrl"),
                        fileName = json.getString("fileName"),
                        description = json.getString("description"),
                        forceUpdate = json.optBoolean("forceUpdate", false)
                    )
                } else {
                    Log.e(TAG, " Error HTTP: ${connection.responseCode}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, " Error obteniendo informacion de version", e)
                null
            }
        }
    }
    
    /**
     * Obtiene el codigo de version actual de la aplicacion
     */
    private fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.longVersionCode.toInt()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, " Error obteniendo version actual", e)
            0
        }
    }
    
    /**
     * Obtiene el nombre de version actual de la aplicacion
     */
    private fun getCurrentVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "Desconocida"
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, " Error obteniendo nombre de version actual", e)
            "Desconocida"
        }
    }
    
    /**
     * Descarga la nueva APK desde GitHub
     */
    suspend fun downloadUpdate(release: SimpleDriveRelease, onProgress: (Int) -> Unit = {}): java.io.File? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, " Iniciando descarga desde GitHub...")
                Log.d(TAG, " URL: ${release.downloadUrl}")
                Log.d(TAG, " Archivo: ${release.fileName}")
                
                // Limpiar archivos anteriores
                cleanupOldApkFiles()
                
                // Crear nombre unico para evitar conflictos
                val timestamp = System.currentTimeMillis()
                val uniqueFileName = "update_${release.versionCode}_${timestamp}.apk"
                val localFile = java.io.File(context.filesDir, uniqueFileName)
                
                // Crear directorio si no existe
                localFile.parentFile?.mkdirs()
                
                val url = URL(release.downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                // Headers para evitar cache
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("User-Agent", "InventarioApp/1.0")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val totalSize = connection.contentLength
                Log.d(TAG, " Tamano total: $totalSize bytes")
                
                val inputStream = connection.inputStream
                val outputStream = java.io.FileOutputStream(localFile)
                
                val buffer = ByteArray(8192)
                var downloaded = 0
                var bytesRead: Int
                var lastProgress = -1
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    
                    if (totalSize > 0) {
                        val progress = (downloaded * 100 / totalSize)
                        if (progress != lastProgress) {
                            onProgress(progress)
                            lastProgress = progress
                        }
                    }
                }
                
                inputStream.close()
                outputStream.close()
                connection.disconnect()
                
                Log.d(TAG, " Descarga completada: ${localFile.absolutePath}")
                Log.d(TAG, " Tamano descargado: ${localFile.length()} bytes")
                Log.d(TAG, " Archivo existe: ${localFile.exists()}")
                
                // Verificar que el archivo se descargo correctamente
                if (localFile.exists() && localFile.length() > 0) {
                    if (totalSize > 0 && localFile.length() != totalSize.toLong()) {
                        Log.w(TAG, " Tamano no coincide: esperado $totalSize, obtenido ${localFile.length()}")
                    }
                    localFile
                } else {
                    Log.e(TAG, " Archivo descargado no valido")
                    null
                }
            } else {
                Log.e(TAG, " Error HTTP descargando: ${connection.responseCode} - ${connection.responseMessage}")
                connection.disconnect()
                null
            }
            
            } catch (e: Exception) {
                Log.e(TAG, " Error descargando actualizacion", e)
                null
            }
        }
    }
    
    /**
     * Verifica si el archivo APK es valido
     */
    fun validateApk(file: java.io.File): Boolean {
        return try {
            Log.d(TAG, " Validando APK: ${file.absolutePath}")
            
            if (!file.exists()) {
                Log.e(TAG, " Archivo no existe")
                return false
            }
            
            if (file.length() == 0L) {
                Log.e(TAG, " Archivo vacio")
                return false
            }
            
            if (file.extension != "apk") {
                Log.e(TAG, " Extension incorrecta: ${file.extension}")
                return false
            }
            
            // Verificacion adicional: intentar leer el archivo como APK
            try {
                val packageManager = context.packageManager
                val packageInfo = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                if (packageInfo != null) {
                    Log.d(TAG, " APK valida - Paquete: ${packageInfo.packageName}")
                    Log.d(TAG, " Version APK: ${packageInfo.versionName} (${packageInfo.longVersionCode})")
                    
                    // Verificar que sea el paquete correcto
                    if (packageInfo.packageName == context.packageName) {
                        Log.d(TAG, " Paquete correcto confirmado")
                        true
                    } else {
                        Log.e(TAG, " Paquete incorrecto: esperado ${context.packageName}, obtenido ${packageInfo.packageName}")
                        false
                    }
                } else {
                    Log.e(TAG, " No se pudo leer informacion del paquete APK")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, " Error leyendo APK como paquete", e)
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, " Error validando APK", e)
            false
        }
    }
    
    /**
     * Limpia archivos APK antiguos para liberar espacio
     */
    private fun cleanupOldApkFiles() {
        try {
            Log.d(TAG, " Limpiando archivos APK antiguos...")
            
            val filesDir = context.filesDir
            val apkFiles = filesDir.listFiles { file ->
                file.extension == "apk" && (file.name.startsWith("update_") || file.name.startsWith("inventario_"))
            }
            
            apkFiles?.forEach { file ->
                if (file.delete()) {
                    Log.d(TAG, " Archivo eliminado: ${file.name}")
                } else {
                    Log.w(TAG, " No se pudo eliminar: ${file.name}")
                }
            }
            
            // Tambien limpiar directorio interno de APK
            val internalApkDir = java.io.File(context.filesDir, "apk_updates")
            if (internalApkDir.exists()) {
                internalApkDir.listFiles()?.forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, " Archivo interno eliminado: ${file.name}")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, " Error limpiando archivos antiguos", e)
        }
    }
}
