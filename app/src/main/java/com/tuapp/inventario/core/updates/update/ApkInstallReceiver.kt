package com.tuapp.inventario.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import java.io.File

class ApkInstallReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ApkInstallReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, " Instalacion exitosa")
                Toast.makeText(context, "Actualizacion instalada exitosamente. La app se reiniciara.", Toast.LENGTH_LONG).show()
                
                // Limpiar el estado de instalacion en progreso
                clearInstallationProgress(context)
                
                // Reiniciar la app despues de un breve delay
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    restartApp(context)
                }, 2000)
            }
            PackageInstaller.STATUS_FAILURE -> {
                Log.e(TAG, " Instalacion fallo: $statusMessage")
                Toast.makeText(context, "Error instalando la actualizacion: $statusMessage", Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.e(TAG, " Instalacion abortada: $statusMessage")
                Toast.makeText(context, "Instalacion cancelada: $statusMessage", Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Log.e(TAG, " Instalacion bloqueada: $statusMessage")
                Toast.makeText(context, "Instalacion bloqueada: $statusMessage", Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Log.e(TAG, " Conflicto en instalacion: $statusMessage")
                Toast.makeText(context, "Conflicto en instalacion: $statusMessage", Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Log.e(TAG, " APK incompatible: $statusMessage")
                Toast.makeText(context, "APK incompatible: $statusMessage", Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_FAILURE_INVALID -> {
                Log.e(TAG, " APK invalida: $statusMessage")
                Toast.makeText(context, "APK invalida: $statusMessage", Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.e(TAG, " Error de almacenamiento: $statusMessage")
                Toast.makeText(context, "Error de almacenamiento: $statusMessage", Toast.LENGTH_LONG).show()
            }
            else -> {
                Log.e(TAG, " Estado de instalacion desconocido: $status - $statusMessage")
                Toast.makeText(context, "Error desconocido en instalacion: $statusMessage", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * Reinicia la aplicacion
     */
    private fun restartApp(context: Context) {
        try {
            Log.d(TAG, " Reiniciando aplicacion...")
            
            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(context.packageName)
            
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                
                context.startActivity(intent)
                
                // Terminar la actividad actual despues de un breve delay
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (context is android.app.Activity) {
                        context.finishAffinity()
                    }
                }, 1000)
            } else {
                Log.e(TAG, " No se pudo obtener el intent de lanzamiento")
            }
        } catch (e: Exception) {
            Log.e(TAG, " Error reiniciando la aplicacion", e)
        }
    }
    
    /**
     * Limpia el estado de instalacion en progreso
     */
    private fun clearInstallationProgress(context: Context) {
        try {
            val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
            prefs.edit().remove("installation_start_time").apply()
            Log.d(TAG, " Limpiando estado de instalacion en progreso")
        } catch (e: Exception) {
            Log.e(TAG, " Error limpiando estado de instalacion", e)
        }
    }
}
