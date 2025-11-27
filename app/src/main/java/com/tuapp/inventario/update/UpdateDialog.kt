package com.tuapp.inventario.update

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import com.tuapp.inventario.update.SimpleDriveRelease

class UpdateDialog(private val context: Context) {
    
    companion object {
        private const val TAG = "UpdateDialog"
    }
    
    @Suppress("DEPRECATION")
    private var progressDialog: ProgressDialog? = null
    private val updateChecker = SimpleDriveUpdateChecker(context)
    private val apkInstaller = ApkInstaller(context)
    private var isCheckingForUpdates = false
    private var updateDialogShown = false
    
    /**
     * Reintenta la instalación después de otorgar permisos
     * Debe ser llamado desde la Activity cuando se regrese de la configuración de permisos
     */
    fun retryInstallation() {
        apkInstaller.retryInstallation()
    }
    
    /**
     * Muestra el diálogo de actualización disponible
     */
    fun showUpdateDialog(updateInfo: SimpleDriveRelease, activity: Activity) {
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] 🚀 showUpdateDialog() LLAMADO")
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] Versión: ${updateInfo.versionName} (${updateInfo.versionCode})")
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] Activity: ${activity.javaClass.simpleName}")
        
        val message = buildString {
            append("🆕 Nueva versión disponible!\n\n")
            append("Versión actual: ${getCurrentVersionName()}\n")
            append("Nueva versión: ${updateInfo.versionName}\n\n")
            if (updateInfo.description.isNotEmpty()) {
                append("Descripción:\n${updateInfo.description}\n\n")
            }
            append("¿Deseas actualizar ahora?")
        }
        
        // 🔍 Verificar modo oscuro usando la ACTIVITY actual (no el context del constructor)
        val nightModeFlags = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] Modo oscuro detectado: $isDark")
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] nightModeFlags: $nightModeFlags, UI_MODE_NIGHT_YES: ${android.content.res.Configuration.UI_MODE_NIGHT_YES}")
        
        val dialogBackgroundColor = if (isDark) {
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] ✅ Usando fondo NEGRO (modo oscuro)")
            android.graphics.Color.BLACK
        } else {
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] ✅ Usando fondo BLANCO (modo claro)")
            android.graphics.Color.WHITE
        }
        val textColor = if (isDark) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.BLACK
        }
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] 🎨 dialogBackgroundColor: $dialogBackgroundColor (hex: #${Integer.toHexString(dialogBackgroundColor)}), textColor: $textColor (hex: #${Integer.toHexString(textColor)})")
        
        // Crear Dialog personalizado (NO AlertDialog) con tema explícito - usar ACTIVITY, no context
        val dialog = android.app.Dialog(activity, if (isDark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        
        // Crear layout principal - usar ACTIVITY, no context
        val mainLayout = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(dialogBackgroundColor)
            setPadding(0, 0, 0, 0)
        }
        
        // Título - usar ACTIVITY, no context
        val titleView = android.widget.TextView(activity).apply {
            text = "Actualización Disponible"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textColor)
            setBackgroundColor(dialogBackgroundColor)
            setPadding(24, 24, 24, 16)
        }
        mainLayout.addView(titleView)
        
        // ScrollView con mensaje - usar ACTIVITY, no context
        // ✅ FIX: Limitar altura del ScrollView para que los botones siempre sean visibles
        val scrollView = android.widget.ScrollView(activity).apply {
            setBackgroundColor(dialogBackgroundColor)
            setPadding(0, 0, 0, 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                weight = 1f // Permite que el ScrollView use el espacio disponible pero no más
            }
        }
        
        val messageView = android.widget.TextView(activity).apply {
            text = message
            textSize = 14f
            setTextColor(textColor)
            setBackgroundColor(dialogBackgroundColor)
            setPadding(24, 0, 24, 16)
        }
        
        scrollView.addView(messageView)
        mainLayout.addView(scrollView)
        
        // Botones - usar ACTIVITY, no context
        // ✅ FIX: Asegurar que los botones siempre sean visibles con layoutParams explícitos
        val buttonsLayout = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setBackgroundColor(dialogBackgroundColor)
            setPadding(16, 8, 16, 16)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Función helper para crear botones como TextView - usar ACTIVITY, no context
        fun createButton(text: String, onClick: () -> Unit): android.widget.TextView {
            return android.widget.TextView(activity).apply {
                this.text = text
                textSize = 14f
                setTextColor(textColor)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(24, 16, 24, 16)
                gravity = android.view.Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                foreground = android.content.res.Resources.getSystem().getDrawable(
                    android.R.drawable.list_selector_background,
                    null
                )
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(8, 0, 8, 0)
                }
            }
        }
        
        val btnMasTarde = createButton("Más tarde") {
            updateDialogShown = false
            dialog.dismiss()
        }
        
        val btnActualizar = createButton("Actualizar") {
            dialog.dismiss()
            downloadAndInstall(updateInfo, activity)
        }
        
        buttonsLayout.addView(btnMasTarde)
        buttonsLayout.addView(btnActualizar)
        mainLayout.addView(buttonsLayout)
        
        // Configurar el diálogo
        dialog.setContentView(mainLayout)
        
        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
            window.decorView.setBackgroundColor(dialogBackgroundColor)
            // ✅ FIX: Limitar altura máxima del diálogo para que los botones siempre sean visibles
            val maxDialogHeight = (activity.resources.displayMetrics.heightPixels * 0.85).toInt()
            val layoutParams = mainLayout.layoutParams
            if (layoutParams != null) {
                layoutParams.height = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                mainLayout.layoutParams = layoutParams
            }
            window.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.9).toInt(),
                maxDialogHeight
            )
        }
        
        // Si es actualización forzosa, no permitir cancelar
        if (updateInfo.forceUpdate) {
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            // Ocultar botón "Más tarde" si es forzosa
            btnMasTarde.visibility = android.view.View.GONE
        }
        
        // Forzar colores después de mostrar el diálogo
        dialog.setOnShowListener {
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] 👁️ Diálogo mostrado - forzando colores...")
            
            // Verificar modo oscuro nuevamente (por si cambió) - usar ACTIVITY, no context
            val nightModeFlags2 = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val isDark2 = nightModeFlags2 == android.content.res.Configuration.UI_MODE_NIGHT_YES
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] Modo oscuro después de show: $isDark2")
            
            val dialogBgColor = if (isDark2) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            val txtColor = if (isDark2) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            
            // Forzar colores en todos los elementos
            mainLayout.setBackgroundColor(dialogBgColor)
            titleView.setTextColor(txtColor)
            titleView.setBackgroundColor(dialogBgColor)
            messageView.setTextColor(txtColor)
            messageView.setBackgroundColor(dialogBgColor)
            scrollView.setBackgroundColor(dialogBgColor)
            buttonsLayout.setBackgroundColor(dialogBgColor)
            btnMasTarde.setTextColor(txtColor)
            btnActualizar.setTextColor(txtColor)
            
            // Forzar colores en la ventana
            val window2 = dialog.window
            if (window2 != null) {
                window2.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBgColor))
                window2.decorView.setBackgroundColor(dialogBgColor)
                
                // Forzar colores en todos los hijos del decorView
                (window2.decorView as? android.view.ViewGroup)?.let { decorView ->
                    for (i in 0 until decorView.childCount) {
                        val child = decorView.getChildAt(i)
                        if (child is android.view.ViewGroup) {
                            child.setBackgroundColor(dialogBgColor)
                        }
                    }
                }
                
                android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] ✅ Colores forzados después de show - fondo: $dialogBgColor, texto: $txtColor")
            }
        }
        
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] 📱 Llamando a dialog.show()")
        dialog.show()
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] ✅ dialog.show() COMPLETADO")
    }
    
    /**
     * Descarga e instala la actualización
     */
    private fun downloadAndInstall(updateInfo: SimpleDriveRelease, activity: Activity) {
        showProgressDialog("Descargando actualización...")
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d(TAG, "📥 Iniciando descarga de actualización...")
                
                val apkFile = updateChecker.downloadUpdate(updateInfo) { progress ->
                    activity.runOnUiThread {
                        @Suppress("DEPRECATION")
                        progressDialog?.progress = progress
                        @Suppress("DEPRECATION")
                        progressDialog?.setMessage("Descargando actualización... $progress%")
                    }
                }
                
                if (apkFile != null && updateChecker.validateApk(apkFile)) {
                    Log.d(TAG, "✅ APK descargada y validada correctamente")
                    
                    // Marcar versión como descargada
                    markVersionAsDownloaded(updateInfo.versionCode)
                    
                    progressDialog?.dismiss()
                    progressDialog = null
                    
                    // Mostrar diálogo de confirmación de instalación
                    showInstallDialog(apkFile, activity, updateInfo)
                    
                } else {
                    Log.e(TAG, "❌ Error descargando o validando APK")
                    progressDialog?.dismiss()
                    progressDialog = null
                    
                    Toast.makeText(context, "Error descargando la actualización", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en descarga", e)
                progressDialog?.dismiss()
                progressDialog = null
                
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * Muestra el diálogo de confirmación de instalación
     */
    private fun showInstallDialog(apkFile: java.io.File, activity: Activity, updateInfo: SimpleDriveRelease) {
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL] 🚀 showInstallDialog() LLAMADO")
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL] Activity: ${activity.javaClass.simpleName}")
        
        val message = buildString {
            append("La descarga se completó correctamente.\n\n")
            append("📱 Nueva versión: ${updateInfo.versionName} (${updateInfo.versionCode})\n")
            append("🔧 Archivo: ${apkFile.name}\n\n")
            append("¿Deseas instalar la nueva versión ahora?\n\n")
            append("ℹ️ Nota: Se abrirá el instalador del sistema. ")
            append("Después de instalar, regresa a la app para completar el proceso.")
        }
        
        // 🔍 Verificar modo oscuro usando la ACTIVITY actual (no el context del constructor)
        val nightModeFlags = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL] Modo oscuro detectado: $isDark")
        
        val dialogBackgroundColor = if (isDark) {
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL] ✅ Usando fondo NEGRO (modo oscuro)")
            android.graphics.Color.BLACK
        } else {
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL] ✅ Usando fondo BLANCO (modo claro)")
            android.graphics.Color.WHITE
        }
        val textColor = if (isDark) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.BLACK
        }
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL] 🎨 dialogBackgroundColor: $dialogBackgroundColor, textColor: $textColor")
        
        // Crear Dialog personalizado (NO AlertDialog) con tema explícito - usar ACTIVITY, no context
        val dialog = android.app.Dialog(activity, if (isDark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        
        // Crear layout principal - usar ACTIVITY, no context
        val mainLayout = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(dialogBackgroundColor)
            setPadding(0, 0, 0, 0)
        }
        
        // Título - usar ACTIVITY, no context
        val titleView = android.widget.TextView(activity).apply {
            text = "Instalar Actualización"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textColor)
            setBackgroundColor(dialogBackgroundColor)
            setPadding(24, 24, 24, 16)
        }
        mainLayout.addView(titleView)
        
        // ScrollView con mensaje - usar ACTIVITY, no context
        val scrollView = android.widget.ScrollView(activity).apply {
            setBackgroundColor(dialogBackgroundColor)
            setPadding(0, 0, 0, 0)
        }
        
        val messageView = android.widget.TextView(activity).apply {
            text = message
            textSize = 14f
            setTextColor(textColor)
            setBackgroundColor(dialogBackgroundColor)
            setPadding(24, 0, 24, 16)
        }
        
        scrollView.addView(messageView)
        mainLayout.addView(scrollView)
        
        // Botones - usar ACTIVITY, no context
        val buttonsLayout = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setBackgroundColor(dialogBackgroundColor)
            setPadding(16, 8, 16, 16)
        }
        
        // Función helper para crear botones como TextView - usar ACTIVITY, no context
        fun createButton(text: String, onClick: () -> Unit): android.widget.TextView {
            return android.widget.TextView(activity).apply {
                this.text = text
                textSize = 14f
                setTextColor(textColor)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(24, 16, 24, 16)
                gravity = android.view.Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                foreground = android.content.res.Resources.getSystem().getDrawable(
                    android.R.drawable.list_selector_background,
                    null
                )
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(8, 0, 8, 0)
                }
            }
        }
        
        val btnCancelar = createButton("Cancelar") {
            apkInstaller.cleanupTempFiles()
            clearDownloadState()
            updateDialogShown = false
            dialog.dismiss()
        }
        
        val btnInstalar = createButton("Instalar") {
            markInstallationInProgress()
            apkInstaller.installApk(apkFile, activity)
            android.widget.Toast.makeText(
                context, 
                "Se abrirá el instalador. Después de instalar, regresa a la app.", 
                android.widget.Toast.LENGTH_LONG
            ).show()
            dialog.dismiss()
        }
        
        buttonsLayout.addView(btnCancelar)
        buttonsLayout.addView(btnInstalar)
        mainLayout.addView(buttonsLayout)
        
        // Configurar el diálogo
        dialog.setContentView(mainLayout)
        
        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
            window.decorView.setBackgroundColor(dialogBackgroundColor)
            window.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.9).toInt(),
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Forzar colores después de mostrar el diálogo
        dialog.setOnShowListener {
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL] 👁️ Diálogo mostrado - forzando colores...")
            
            // Verificar modo oscuro nuevamente (por si cambió) - usar ACTIVITY, no context
            val nightModeFlags2 = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val isDark2 = nightModeFlags2 == android.content.res.Configuration.UI_MODE_NIGHT_YES
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL] Modo oscuro después de show: $isDark2")
            
            val dialogBgColor = if (isDark2) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            val txtColor = if (isDark2) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            
            // Forzar colores en todos los elementos
            mainLayout.setBackgroundColor(dialogBgColor)
            titleView.setTextColor(txtColor)
            titleView.setBackgroundColor(dialogBgColor)
            messageView.setTextColor(txtColor)
            messageView.setBackgroundColor(dialogBgColor)
            scrollView.setBackgroundColor(dialogBgColor)
            buttonsLayout.setBackgroundColor(dialogBgColor)
            btnCancelar.setTextColor(txtColor)
            btnInstalar.setTextColor(txtColor)
            
            // Forzar colores en la ventana
            val window2 = dialog.window
            if (window2 != null) {
                window2.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBgColor))
                window2.decorView.setBackgroundColor(dialogBgColor)
                
                // Forzar colores en todos los hijos del decorView
                (window2.decorView as? android.view.ViewGroup)?.let { decorView ->
                    for (i in 0 until decorView.childCount) {
                        val child = decorView.getChildAt(i)
                        if (child is android.view.ViewGroup) {
                            child.setBackgroundColor(dialogBgColor)
                        }
                    }
                }
                
                android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL] ✅ Colores forzados después de show - fondo: $dialogBgColor, texto: $txtColor")
            }
        }
        
        dialog.setOnCancelListener {
            apkInstaller.cleanupTempFiles()
            clearDownloadState()
            updateDialogShown = false
        }
        
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL] 📱 Llamando a dialog.show()")
        dialog.show()
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL] ✅ dialog.show() COMPLETADO")
    }
    
    /**
     * Muestra el diálogo de progreso
     */
    @Suppress("DEPRECATION")
    private fun showProgressDialog(message: String) {
        progressDialog = ProgressDialog(context).apply {
            setTitle("Actualizando...")
            setMessage(message)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setMax(100)
            setProgress(0)
            setCancelable(false)
            show()
        }
    }
    
    /**
     * Obtiene el nombre de la versión actual
     */
    private fun getCurrentVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName
        } catch (e: Exception) {
            "Desconocida"
        }
    }
    
    /**
     * Verifica actualizaciones automáticamente
     */
    fun checkForUpdates(activity: Activity) {
        // Evitar verificaciones duplicadas
        if (isCheckingForUpdates || updateDialogShown) {
            Log.d(TAG, "⏭️ Verificación de actualizaciones ya en progreso o diálogo mostrado")
            return
        }
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                isCheckingForUpdates = true
                Log.d(TAG, "🔍 Verificando actualizaciones automáticamente...")
                
                // Verificar si hay una instalación en progreso
                if (isInstallationInProgress()) {
                    Log.d(TAG, "⏳ Instalación en progreso, saltando verificación de actualizaciones")
                    isCheckingForUpdates = false
                    return@launch
                }
                
                // Verificar si hay instalaciones fallidas y limpiar estado
                checkAndCleanFailedInstallation()
                
                // Limpiar archivos antiguos antes de verificar
                apkInstaller.cleanupTempFiles()
                
                val updateInfo = withContext(Dispatchers.IO) {
                    updateChecker.checkForUpdate()
                }
                
                if (updateInfo != null) {
                    Log.d(TAG, "🆕 Actualización encontrada: ${updateInfo.versionName} (${updateInfo.versionCode})")
                    
                    // Verificar si es una actualización crítica que debe forzarse
                    val isCriticalUpdate = isCriticalUpdate(updateInfo)
                    
                    // Verificar si ya tenemos esta versión descargada
                    if (!isVersionAlreadyDownloaded(updateInfo.versionCode) || isCriticalUpdate) {
                        if (isCriticalUpdate) {
                            Log.d(TAG, "🚨 Actualización crítica detectada, forzando descarga")
                        }
                        updateDialogShown = true
                        showUpdateDialog(updateInfo, activity)
                    } else {
                        Log.d(TAG, "⏭️ Versión ya descargada anteriormente, saltando")
                    }
                } else {
                    Log.d(TAG, "✅ No hay actualizaciones disponibles")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error verificando actualizaciones", e)
            } finally {
                isCheckingForUpdates = false
            }
        }
    }
    
    /**
     * Verifica si la app se actualizó después de una instalación manual
     * Debe ser llamado cuando la app regresa al primer plano
     */
    fun checkForUpdateAfterInstallation(activity: Activity) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d(TAG, "🔍 Verificando estado de actualización al regresar a la app...")
                
                if (isInstallationInProgress()) {
                    Log.d(TAG, "🔍 Verificando si la instalación se completó...")
                    
                    // Verificar si la versión cambió
                    val currentVersion = getCurrentVersionCode()
                    val lastKnownVersion = getLastKnownVersionCode()
                    
                    Log.d(TAG, "📊 Versión actual: $currentVersion, última conocida: $lastKnownVersion")
                    
                    if (currentVersion > lastKnownVersion) {
                        Log.d(TAG, "✅ Instalación completada exitosamente! Nueva versión: $currentVersion")
                        clearInstallationProgress()
                        
                        // Mostrar mensaje de éxito
                        android.widget.Toast.makeText(
                            context, 
                            "¡Actualización instalada exitosamente! Versión: ${getCurrentVersionName()}", 
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Log.d(TAG, "⏳ Instalación aún en progreso o no completada")
                        
                        // Verificar si hay una nueva actualización disponible
                        checkForUpdates(activity)
                    }
                } else {
                    // No hay instalación en progreso, verificar si hay actualizaciones disponibles
                    Log.d(TAG, "🔍 No hay instalación en progreso, verificando actualizaciones...")
                    checkForUpdates(activity)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error verificando instalación", e)
            }
        }
    }
    
    /**
     * Marca que se está realizando una instalación
     */
    private fun markInstallationInProgress() {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("installation_start_time", System.currentTimeMillis())
            .apply()
        Log.d(TAG, "📝 Marcando instalación en progreso")
    }
    
    /**
     * Verifica si hay una instalación en progreso
     */
    private fun isInstallationInProgress(): Boolean {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val installationStartTime = prefs.getLong("installation_start_time", 0)
        
        if (installationStartTime == 0L) {
            return false
        }
        
        val timeSinceInstallation = System.currentTimeMillis() - installationStartTime
        val maxInstallationTime = UpdateConfig.MAX_INSTALLATION_TIME_MINUTES * 60 * 1000L
        
        if (timeSinceInstallation > maxInstallationTime) {
            // Limpiar el flag si ha pasado mucho tiempo
            prefs.edit().remove("installation_start_time").apply()
            return false
        }
        
        return true
    }
    
    /**
     * Limpia el estado de instalación en progreso
     */
    fun clearInstallationProgress() {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("installation_start_time").apply()
        Log.d(TAG, "🧹 Limpiando estado de instalación en progreso")
    }
    
    /**
     * Inicializa el sistema de actualizaciones
     * Debe ser llamado al iniciar la app para limpiar estados previos
     */
    fun initialize() {
        Log.d(TAG, "🚀 Inicializando sistema de actualizaciones...")
        
        // Limpiar cualquier estado de instalación previo al iniciar
        clearInstallationProgress()
        
        // Guardar la versión actual como referencia
        saveCurrentVersionCode()
        
        // Limpiar archivos APK antiguos
        apkInstaller.cleanupTempFiles()
        
        // Verificar y limpiar estados problemáticos de versiones anteriores
        checkAndCleanProblematicVersions()
        
        // Verificar si la app se actualizó desde la última vez
        checkIfAppWasUpdated()
        
        Log.d(TAG, "✅ Sistema de actualizaciones inicializado")
    }
    
    /**
     * Verifica si la aplicación se actualizó desde la última ejecución
     */
    private fun checkIfAppWasUpdated() {
        try {
            val currentVersion = getCurrentVersionCode()
            val lastKnownVersion = getLastKnownVersionCode()
            
            Log.d(TAG, "🔍 Verificando actualización: actual=$currentVersion, anterior=$lastKnownVersion")
            
            if (currentVersion > lastKnownVersion && lastKnownVersion > 0) {
                Log.d(TAG, "🎉 Aplicación actualizada exitosamente!")
                
                // Limpiar el flag de descarga para permitir futuras actualizaciones
                val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .remove("last_downloaded_version")
                    .apply()
                
                // Mostrar mensaje de éxito
                android.widget.Toast.makeText(
                    context,
                    "¡Aplicación actualizada exitosamente! Versión: ${getCurrentVersionName()}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error verificando actualización de app", e)
        }
    }
    
    /**
     * Obtiene el código de versión actual
     */
    private fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.longVersionCode.toInt()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error obteniendo versión actual", e)
            0
        }
    }
    
    /**
     * Guarda el código de versión actual como referencia
     */
    private fun saveCurrentVersionCode() {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val currentVersion = getCurrentVersionCode()
        prefs.edit()
            .putInt("last_known_version_code", currentVersion)
            .apply()
        Log.d(TAG, "💾 Guardada versión actual: $currentVersion")
    }
    
    /**
     * Obtiene la última versión conocida guardada
     */
    private fun getLastKnownVersionCode(): Int {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("last_known_version_code", 0)
    }
    
    /**
     * Verifica si una versión específica ya fue descargada anteriormente
     * Solo considera descargada si la versión actual es mayor o igual a la descargada
     */
    private fun isVersionAlreadyDownloaded(versionCode: Int): Boolean {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val lastDownloadedVersion = prefs.getInt("last_downloaded_version", 0)
        val currentVersion = getCurrentVersionCode()
        
        // Solo considerar descargada si la versión actual es mayor o igual a la descargada
        // Esto evita que se marque como descargada si no se instaló realmente
        val isActuallyInstalled = currentVersion >= lastDownloadedVersion && lastDownloadedVersion > 0
        
        Log.d(TAG, "🔍 Verificando si versión $versionCode ya fue descargada:")
        Log.d(TAG, "📊 Versión actual: $currentVersion, última descargada: $lastDownloadedVersion")
        Log.d(TAG, "✅ ¿Está realmente instalada?: $isActuallyInstalled")
        
        return isActuallyInstalled && versionCode <= lastDownloadedVersion
    }
    
    /**
     * Marca una versión como descargada
     */
    private fun markVersionAsDownloaded(versionCode: Int) {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("last_downloaded_version", versionCode)
            .apply()
        Log.d(TAG, "📝 Versión $versionCode marcada como descargada")
    }
    
    /**
     * Limpia el estado de descarga para permitir volver a descargar la misma versión
     * Útil cuando la instalación falla o el usuario cancela
     */
    fun clearDownloadState() {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("last_downloaded_version")
            .apply()
        Log.d(TAG, "🧹 Estado de descarga limpiado para permitir nueva descarga")
    }
    
    /**
     * Fuerza la verificación de actualizaciones ignorando el estado de descarga
     * Útil para casos donde la instalación falló y se quiere volver a intentar
     */
    fun forceCheckForUpdates(activity: Activity) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d(TAG, "🔄 Forzando verificación de actualizaciones...")
                
                // Limpiar estado de descarga para permitir nueva descarga
                clearDownloadState()
                
                // Limpiar archivos antiguos
                apkInstaller.cleanupTempFiles()
                
                val updateInfo = withContext(Dispatchers.IO) {
                    updateChecker.checkForUpdate()
                }
                
                if (updateInfo != null) {
                    Log.d(TAG, "🆕 Actualización encontrada (forzada): ${updateInfo.versionName} (${updateInfo.versionCode})")
                    showUpdateDialog(updateInfo, activity)
                } else {
                    Log.d(TAG, "✅ No hay actualizaciones disponibles (verificación forzada)")
                    android.widget.Toast.makeText(
                        context, 
                        "No hay actualizaciones disponibles", 
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en verificación forzada de actualizaciones", e)
                android.widget.Toast.makeText(
                    context, 
                    "Error verificando actualizaciones: ${e.message}", 
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    /**
     * Verifica si una instalación falló y limpia el estado si es necesario
     */
    private fun checkAndCleanFailedInstallation() {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val lastDownloadedVersion = prefs.getInt("last_downloaded_version", 0)
        val currentVersion = getCurrentVersionCode()
        
        // Si hay una versión marcada como descargada pero la versión actual es menor,
        // significa que la instalación falló
        if (lastDownloadedVersion > 0 && currentVersion < lastDownloadedVersion) {
            Log.d(TAG, "⚠️ Instalación fallida detectada. Versión actual: $currentVersion, descargada: $lastDownloadedVersion")
            clearDownloadState()
        }
    }
    
    /**
     * Verifica si una actualización es crítica y debe forzarse
     * Las actualizaciones críticas incluyen correcciones importantes del sistema
     */
    private fun isCriticalUpdate(updateInfo: SimpleDriveRelease): Boolean {
        // Verificar si es una versión crítica específica
        val isCriticalVersion = UpdateConfig.CRITICAL_VERSIONS.contains(updateInfo.versionCode)
        
        // Verificar si la descripción contiene palabras clave críticas
        val hasCriticalKeywords = UpdateConfig.CRITICAL_KEYWORDS.any { keyword ->
            updateInfo.description.contains(keyword, ignoreCase = true)
        }
        
        Log.d(TAG, "🔍 Verificando si es actualización crítica:")
        Log.d(TAG, "📊 Versión: ${updateInfo.versionCode}")
        Log.d(TAG, "📊 ¿Versión crítica?: $isCriticalVersion")
        Log.d(TAG, "📊 ¿Contiene palabras clave?: $hasCriticalKeywords")
        
        return isCriticalVersion || hasCriticalKeywords
    }
    
    /**
     * Detecta y limpia estados problemáticos de versiones anteriores
     * Esto es especialmente útil para usuarios que ya tienen versiones con el bug
     */
    private fun checkAndCleanProblematicVersions() {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val currentVersion = getCurrentVersionCode()
        val lastDownloadedVersion = prefs.getInt("last_downloaded_version", 0)
        
        // Versiones problemáticas que necesitan limpieza de estado
        val problematicVersions = UpdateConfig.PROBLEMATIC_VERSIONS
        
        // Verificar si la versión actual está en la lista de problemáticas
        val isCurrentVersionProblematic = problematicVersions.contains(currentVersion)
        
        // Verificar si hay un estado de descarga que podría ser problemático
        val hasProblematicDownloadState = lastDownloadedVersion > 0 && currentVersion < lastDownloadedVersion
        
        Log.d(TAG, "🔍 Verificando versiones problemáticas:")
        Log.d(TAG, "📊 Versión actual: $currentVersion")
        Log.d(TAG, "📊 Última descargada: $lastDownloadedVersion")
        Log.d(TAG, "⚠️ ¿Versión actual problemática?: $isCurrentVersionProblematic")
        Log.d(TAG, "⚠️ ¿Estado de descarga problemático?: $hasProblematicDownloadState")
        
        if (isCurrentVersionProblematic || hasProblematicDownloadState) {
            Log.d(TAG, "🧹 Limpiando estado problemático de versiones anteriores...")
            
            // Limpiar todo el estado de actualizaciones para permitir verificación limpia
            clearDownloadState()
            clearInstallationProgress()
            
            // Marcar que se hizo la limpieza para evitar hacerla repetidamente
            prefs.edit()
                .putBoolean("cleanup_done_for_version_$currentVersion", true)
                .apply()
            
            Log.d(TAG, "✅ Estado problemático limpiado para versión $currentVersion")
            
            // Mostrar mensaje informativo al usuario
            android.widget.Toast.makeText(
                context,
                "Sistema de actualizaciones optimizado. Se verificará automáticamente.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } else {
            Log.d(TAG, "✅ No se detectaron versiones problemáticas")
        }
    }
}
