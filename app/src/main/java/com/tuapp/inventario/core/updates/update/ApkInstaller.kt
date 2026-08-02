package com.tuapp.inventario.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class ApkInstaller(private val context: Context) {
    
    companion object {
        private const val TAG = "ApkInstaller"
        const val REQUEST_INSTALL_PACKAGES = 1001
    }
    
    // Callback para manejar la instalacion despues de otorgar permisos
    private var pendingApkFile: File? = null
    private var pendingActivity: Activity? = null
    
    /**
     * Instala la APK descargada
     */
    fun installApk(apkFile: File, activity: Activity? = null) {
        try {
            Log.d(TAG, " Iniciando instalacion de APK: ${apkFile.name}")
            Log.d(TAG, " Ruta del archivo: ${apkFile.absolutePath}")
            Log.d(TAG, " Tamano del archivo: ${apkFile.length()} bytes")
            Log.d(TAG, " Archivo existe: ${apkFile.exists()}")
            
            if (!apkFile.exists()) {
                Log.e(TAG, " El archivo APK no existe: ${apkFile.absolutePath}")
                return
            }
            
            if (apkFile.length() == 0L) {
                Log.e(TAG, " El archivo APK esta vacio")
                return
            }
            
            // Verificar permisos de instalacion
            if (!canInstallApk()) {
                Log.d(TAG, " Permisos de instalacion no disponibles, solicitando...")
                // Guardar archivo y actividad para reintentar despues
                pendingApkFile = apkFile
                pendingActivity = activity
                requestInstallPermission(activity)
                return
            }
            
            Log.d(TAG, " Permisos de instalacion confirmados")
            
            // Usar metodo tradicional que es mas confiable en la mayoria de dispositivos
            val intent = createInstallIntent(apkFile)
            
            // Verificar que el intent puede ser manejado antes de enviarlo
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            if (resolveInfo == null) {
                Log.e(TAG, " No hay aplicacion que pueda manejar la instalacion")
                return
            }
            
            Log.d(TAG, " Enviando intent de instalacion...")
            context.startActivity(intent)
            Log.d(TAG, " Intent de instalacion enviado exitosamente")
            
            // Nota: PackageInstaller puede fallar en algunos dispositivos, 
            // por lo que usamos el metodo tradicional que es mas compatible
            
        } catch (e: Exception) {
            Log.e(TAG, " Error instalando APK", e)
            e.printStackTrace()
        }
    }
    
    /**
     * Reintenta la instalacion despues de otorgar permisos
     * Debe ser llamado desde la Activity cuando se regrese de la configuracion de permisos
     */
    fun retryInstallation() {
        val apkFile = pendingApkFile
        val activity = pendingActivity
        
        if (apkFile != null && activity != null) {
            Log.d(TAG, " Reintentando instalacion despues de otorgar permisos...")
            
            // Limpiar referencias pendientes
            pendingApkFile = null
            pendingActivity = null
            
            // Verificar si el archivo aun existe
            if (!apkFile.exists()) {
                Log.e(TAG, " El archivo APK ya no existe: ${apkFile.absolutePath}")
                return
            }
            
            // Verificar si ahora tiene permisos
            if (canInstallApk()) {
                Log.d(TAG, " Permisos confirmados, procediendo con la instalacion...")
                installApk(apkFile, activity)
            } else {
                Log.e(TAG, " Permisos aun no otorgados correctamente")
                // Limpiar archivos temporales si no se pueden usar
                cleanupTempFiles()
            }
        } else {
            Log.w(TAG, " No hay instalacion pendiente para reintentar")
        }
    }
    
    /**
     * Verifica si la aplicacion puede instalar APKs
     */
    private fun canInstallApk(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }
    
    /**
     * Solicita permisos para instalar APKs (Android 8.0+)
     */
    private fun requestInstallPermission(activity: Activity?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            activity.startActivityForResult(intent, REQUEST_INSTALL_PACKAGES)
        }
    }
    
    /**
     * Crea el Intent para instalar la APK
     */
    private fun createInstallIntent(apkFile: File): Intent {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        
        // Siempre copiar a almacenamiento interno para evitar problemas con FileProvider
        val internalFile = copyToInternalStorage(apkFile)
        
        val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android 7.0+ requiere FileProvider
            try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    internalFile
                )
            } catch (e: Exception) {
                Log.e(TAG, " Error generando URI con FileProvider", e)
                // Fallback: usar URI directa del archivo interno
                Uri.fromFile(internalFile)
            }
        } else {
            // Android 6.0 y anteriores
            Uri.fromFile(internalFile)
        }
        
        Log.d(TAG, " URI generada: $apkUri")
        Log.d(TAG, " Archivo interno: ${internalFile.absolutePath}")
        Log.d(TAG, " Tamano archivo: ${internalFile.length()} bytes")
        Log.d(TAG, " Archivo existe: ${internalFile.exists()}")
        
        // Configurar el Intent con mas opciones para mayor compatibilidad
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        
        // Agregar flags adicionales para mejor compatibilidad
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        
        // Verificar que el Intent puede ser manejado
        val packageManager = context.packageManager
        val resolveInfo = packageManager.resolveActivity(intent, 0)
        if (resolveInfo != null) {
            Log.d(TAG, " Intent puede ser manejado por: ${resolveInfo.activityInfo.packageName}")
        } else {
            Log.e(TAG, " No hay aplicacion que pueda manejar este Intent")
        }
        
        return intent
    }
    
    /**
     * Copia el archivo APK al almacenamiento interno para evitar problemas de URI
     */
    private fun copyToInternalStorage(sourceFile: File): File {
        try {
            val internalDir = File(context.filesDir, "apk_updates")
            if (!internalDir.exists()) {
                internalDir.mkdirs()
            }
            
            val internalFile = File(internalDir, sourceFile.name)
            
            if (internalFile.exists()) {
                internalFile.delete()
            }
            
            // Verificar que el archivo fuente existe y es valido
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                throw Exception("Archivo fuente no valido: ${sourceFile.absolutePath}")
            }
            
            sourceFile.copyTo(internalFile, overwrite = true)
            
            // Verificar que la copia fue exitosa
            if (!internalFile.exists() || internalFile.length() != sourceFile.length()) {
                throw Exception("Error copiando archivo APK")
            }
            
            Log.d(TAG, " APK copiada a almacenamiento interno: ${internalFile.absolutePath}")
            Log.d(TAG, " Tamano original: ${sourceFile.length()}, copia: ${internalFile.length()}")
            
            return internalFile
        } catch (e: Exception) {
            Log.e(TAG, " Error copiando APK a almacenamiento interno", e)
            // Fallback: devolver el archivo original
            return sourceFile
        }
    }
    
    /**
     * Verifica si la instalacion fue exitosa
     */
    fun isInstallationSuccessful(): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * Metodo alternativo de instalacion usando PackageInstaller (mas confiable)
     */
    private fun installWithPackageInstaller(apkFile: File) {
        try {
            Log.d(TAG, " Intentando instalacion con PackageInstaller...")
            
            val packageInstaller = context.packageManager.packageInstaller
            val sessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            sessionParams.setAppPackageName(context.packageName)
            
            val sessionId = packageInstaller.createSession(sessionParams)
            val session = packageInstaller.openSession(sessionId)
            
            val inputStream = FileInputStream(apkFile)
            val outputStream = session.openWrite("install", 0, apkFile.length())
            
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            
            session.fsync(outputStream)
            inputStream.close()
            outputStream.close()
            
            // Crear un Intent para recibir el callback de instalacion
            val intent = Intent(context, ApkInstallReceiver::class.java)
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, 
                0, 
                intent, 
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            session.commit(pendingIntent.intentSender)
            Log.d(TAG, " Instalacion con PackageInstaller iniciada")
            
        } catch (e: Exception) {
            Log.e(TAG, " Error con PackageInstaller, usando metodo tradicional", e)
            // Fallback al metodo tradicional
            val intent = createInstallIntent(apkFile)
            context.startActivity(intent)
        }
    }
    
    /**
     * Limpia archivos APK temporales
     */
    fun cleanupTempFiles() {
        try {
            val externalDir = context.getExternalFilesDir(null)
            externalDir?.listFiles()?.forEach { file ->
                if (file.extension == "apk" && file.name.startsWith("update_")) {
                    if (file.delete()) {
                        Log.d(TAG, " Archivo temporal eliminado: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, " Error limpiando archivos temporales", e)
        }
    }
}
