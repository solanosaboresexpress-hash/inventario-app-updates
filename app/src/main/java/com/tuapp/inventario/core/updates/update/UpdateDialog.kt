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
     * Reintenta la instalacion despues de otorgar permisos
     * Debe ser llamado desde la Activity cuando se regrese de la configuracion de permisos
     */
    fun retryInstallation() {
        apkInstaller.retryInstallation()
    }
    
    /**
     * Muestra el dialogo de actualizacion disponible
     */
    fun showUpdateDialog(updateInfo: SimpleDriveRelease, activity: Activity) {
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG]  showUpdateDialog() LLAMADO")
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] Version: ${updateInfo.versionName} (${updateInfo.versionCode})")
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] Activity: ${activity.javaClass.simpleName}")
        
        val message = buildString {
            append(" Nueva version disponible!\n\n")
            append("Version actual: ${getCurrentVersionName()}\n")
            append("Nueva version: ${updateInfo.versionName}\n\n")
            if (updateInfo.description.isNotEmpty()) {
                append("Descripcion:\n${updateInfo.description}\n\n")
            }
            append("Deseas actualizar ahora?")
        }
        
        //  Verificar modo oscuro usando la ACTIVITY actual (no el context del constructor)
        val nightModeFlags = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] Modo oscuro detectado: $isDark")
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] nightModeFlags: $nightModeFlags, UI_MODE_NIGHT_YES: ${android.content.res.Configuration.UI_MODE_NIGHT_YES}")
        
        val dialogBackgroundColor = if (isDark) {
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG]  Usando fondo NEGRO (modo oscuro)")
            android.graphics.Color.BLACK
        } else {
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG]  Usando fondo BLANCO (modo claro)")
            android.graphics.Color.WHITE
        }
        val textColor = if (isDark) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.BLACK
        }
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG]  dialogBackgroundColor: $dialogBackgroundColor (hex: #${Integer.toHexString(dialogBackgroundColor)}), textColor: $textColor (hex: #${Integer.toHexString(textColor)})")
        
        // Crear Dialog personalizado (NO AlertDialog) con tema explicito - usar ACTIVITY, no context
        val dialog = android.app.Dialog(activity, if (isDark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        
        // Crear layout principal - usar ACTIVITY, no context
        val mainLayout = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(dialogBackgroundColor)
            setPadding(0, 0, 0, 0)
        }
        
        // Titulo - usar ACTIVITY, no context
        val titleView = android.widget.TextView(activity).apply {
            text = "Actualizacion Disponible"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textColor)
            setBackgroundColor(dialogBackgroundColor)
            setPadding(24, 24, 24, 16)
        }
        mainLayout.addView(titleView)
        
        // ScrollView con mensaje - usar ACTIVITY, no context
        //  FIX: Limitar altura del ScrollView para que los botones siempre sean visibles
        val scrollView = android.widget.ScrollView(activity).apply {
            setBackgroundColor(dialogBackgroundColor)
            setPadding(0, 0, 0, 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                weight = 1f // Permite que el ScrollView use el espacio disponible pero no mas
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
        //  FIX: Asegurar que los botones siempre sean visibles con layoutParams explicitos
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
        
        // Funcion helper para crear botones como TextView - usar ACTIVITY, no context
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
        
        val btnMasTarde = createButton("Mas tarde") {
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
        
        // Configurar el dialogo
        dialog.setContentView(mainLayout)
        
        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackgroundColor))
            window.decorView.setBackgroundColor(dialogBackgroundColor)
            //  FIX: Limitar altura maxima del dialogo para que los botones siempre sean visibles
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
        
        // Si es actualizacion forzosa, no permitir cancelar
        if (updateInfo.forceUpdate) {
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            // Ocultar boton "Mas tarde" si es forzosa
            btnMasTarde.visibility = android.view.View.GONE
        }
        
        // Forzar colores despues de mostrar el dialogo
        dialog.setOnShowListener {
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG]  Dialogo mostrado - forzando colores...")
            
            // Verificar modo oscuro nuevamente (por si cambio) - usar ACTIVITY, no context
            val nightModeFlags2 = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val isDark2 = nightModeFlags2 == android.content.res.Configuration.UI_MODE_NIGHT_YES
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG] Modo oscuro despues de show: $isDark2")
            
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
                
                android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG]  Colores forzados despues de show - fondo: $dialogBgColor, texto: $txtColor")
            }
        }
        
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG]  Llamando a dialog.show()")
        dialog.show()
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG]  dialog.show() COMPLETADO")
    }
    
    /**
     * Descarga e instala la actualizacion
     */
    private fun downloadAndInstall(updateInfo: SimpleDriveRelease, activity: Activity) {
        showProgressDialog("Descargando actualizacion...")
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d(TAG, " Iniciando descarga de actualizacion...")
                
                val apkFile = updateChecker.downloadUpdate(updateInfo) { progress ->
                    activity.runOnUiThread {
                        @Suppress("DEPRECATION")
                        progressDialog?.progress = progress
                        @Suppress("DEPRECATION")
                        progressDialog?.setMessage("Descargando actualizacion... $progress%")
                    }
                }
                
                if (apkFile != null && updateChecker.validateApk(apkFile)) {
                    Log.d(TAG, " APK descargada y validada correctamente")
                    
                    // Marcar version como descargada
                    markVersionAsDownloaded(updateInfo.versionCode)
                    
                    progressDialog?.dismiss()
                    progressDialog = null
                    
                    // Mostrar dialogo de confirmacion de instalacion
                    showInstallDialog(apkFile, activity, updateInfo)
                    
                } else {
                    Log.e(TAG, " Error descargando o validando APK")
                    progressDialog?.dismiss()
                    progressDialog = null
                    
                    Toast.makeText(context, "Error descargando la actualizacion", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, " Error en descarga", e)
                progressDialog?.dismiss()
                progressDialog = null
                
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * Muestra el dialogo de confirmacion de instalacion
     */
    private fun showInstallDialog(apkFile: java.io.File, activity: Activity, updateInfo: SimpleDriveRelease) {
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL]  showInstallDialog() LLAMADO")
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL] Activity: ${activity.javaClass.simpleName}")
        
        val message = buildString {
            append("La descarga se completo correctamente.\n\n")
            append(" Nueva version: ${updateInfo.versionName} (${updateInfo.versionCode})\n")
            append(" Archivo: ${apkFile.name}\n\n")
            append("Deseas instalar la nueva version ahora?\n\n")
            append(" Nota: Se abrira el instalador del sistema. ")
            append("Despues de instalar, regresa a la app para completar el proceso.")
        }
        
        //  Verificar modo oscuro usando la ACTIVITY actual (no el context del constructor)
        val nightModeFlags = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL] Modo oscuro detectado: $isDark")
        
        val dialogBackgroundColor = if (isDark) {
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL]  Usando fondo NEGRO (modo oscuro)")
            android.graphics.Color.BLACK
        } else {
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL]  Usando fondo BLANCO (modo claro)")
            android.graphics.Color.WHITE
        }
        val textColor = if (isDark) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.BLACK
        }
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL]  dialogBackgroundColor: $dialogBackgroundColor, textColor: $textColor")
        
        // Crear Dialog personalizado (NO AlertDialog) con tema explicito - usar ACTIVITY, no context
        val dialog = android.app.Dialog(activity, if (isDark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        
        // Crear layout principal - usar ACTIVITY, no context
        val mainLayout = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(dialogBackgroundColor)
            setPadding(0, 0, 0, 0)
        }
        
        // Titulo - usar ACTIVITY, no context
        val titleView = android.widget.TextView(activity).apply {
            text = "Instalar Actualizacion"
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
        
        // Funcion helper para crear botones como TextView - usar ACTIVITY, no context
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
                "Se abrira el instalador. Despues de instalar, regresa a la app.", 
                android.widget.Toast.LENGTH_LONG
            ).show()
            dialog.dismiss()
        }
        
        buttonsLayout.addView(btnCancelar)
        buttonsLayout.addView(btnInstalar)
        mainLayout.addView(buttonsLayout)
        
        // Configurar el dialogo
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
        
        // Forzar colores despues de mostrar el dialogo
        dialog.setOnShowListener {
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL]  Dialogo mostrado - forzando colores...")
            
            // Verificar modo oscuro nuevamente (por si cambio) - usar ACTIVITY, no context
            val nightModeFlags2 = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val isDark2 = nightModeFlags2 == android.content.res.Configuration.UI_MODE_NIGHT_YES
            android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL] Modo oscuro despues de show: $isDark2")
            
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
                
                android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL]  Colores forzados despues de show - fondo: $dialogBgColor, texto: $txtColor")
            }
        }
        
        dialog.setOnCancelListener {
            apkInstaller.cleanupTempFiles()
            clearDownloadState()
            updateDialogShown = false
        }
        
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL]  Llamando a dialog.show()")
        dialog.show()
        android.util.Log.d("DIALOG_DEBUG", "[UPDATE_DIALOG_INSTALL]  dialog.show() COMPLETADO")
    }
    
    /**
     * Muestra el dialogo de progreso
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
     * Obtiene el nombre de la version actual
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
     * Verifica actualizaciones automaticamente
     */
    fun checkForUpdates(activity: Activity) {
        // Evitar verificaciones duplicadas
        if (isCheckingForUpdates || updateDialogShown) {
            Log.d(TAG, " Verificacion de actualizaciones ya en progreso o dialogo mostrado")
            return
        }
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                isCheckingForUpdates = true
                Log.d(TAG, " Verificando actualizaciones automaticamente...")
                
                // Verificar si hay una instalacion en progreso
                if (isInstallationInProgress()) {
                    Log.d(TAG, " Instalacion en progreso, saltando verificacion de actualizaciones")
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
                    Log.d(TAG, " Actualizacion encontrada: ${updateInfo.versionName} (${updateInfo.versionCode})")
                    
                    // Verificar si es una actualizacion critica que debe forzarse
                    val isCriticalUpdate = isCriticalUpdate(updateInfo)
                    
                    // Verificar si ya tenemos esta version descargada
                    if (!isVersionAlreadyDownloaded(updateInfo.versionCode) || isCriticalUpdate) {
                        if (isCriticalUpdate) {
                            Log.d(TAG, " Actualizacion critica detectada, forzando descarga")
                        }
                        updateDialogShown = true
                        showUpdateDialog(updateInfo, activity)
                    } else {
                        Log.d(TAG, " Version ya descargada anteriormente, saltando")
                    }
                } else {
                    Log.d(TAG, " No hay actualizaciones disponibles")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, " Error verificando actualizaciones", e)
            } finally {
                isCheckingForUpdates = false
            }
        }
    }
    
    /**
     * Verifica si la app se actualizo despues de una instalacion manual
     * Debe ser llamado cuando la app regresa al primer plano
     */
    fun checkForUpdateAfterInstallation(activity: Activity) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d(TAG, " Verificando estado de actualizacion al regresar a la app...")
                
                if (isInstallationInProgress()) {
                    Log.d(TAG, " Verificando si la instalacion se completo...")
                    
                    // Verificar si la version cambio
                    val currentVersion = getCurrentVersionCode()
                    val lastKnownVersion = getLastKnownVersionCode()
                    
                    Log.d(TAG, " Version actual: $currentVersion, ultima conocida: $lastKnownVersion")
                    
                    if (currentVersion > lastKnownVersion) {
                        Log.d(TAG, " Instalacion completada exitosamente! Nueva version: $currentVersion")
                        clearInstallationProgress()
                        
                        // Mostrar mensaje de exito
                        android.widget.Toast.makeText(
                            context, 
                            "Actualizacion instalada exitosamente! Version: ${getCurrentVersionName()}", 
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Log.d(TAG, " Instalacion aun en progreso o no completada")
                        
                        // Verificar si hay una nueva actualizacion disponible
                        checkForUpdates(activity)
                    }
                } else {
                    // No hay instalacion en progreso, verificar si hay actualizaciones disponibles
                    Log.d(TAG, " No hay instalacion en progreso, verificando actualizaciones...")
                    checkForUpdates(activity)
                }
            } catch (e: Exception) {
                Log.e(TAG, " Error verificando instalacion", e)
            }
        }
    }
    
    /**
     * Marca que se esta realizando una instalacion
     */
    private fun markInstallationInProgress() {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("installation_start_time", System.currentTimeMillis())
            .apply()
        Log.d(TAG, " Marcando instalacion en progreso")
    }
    
    /**
     * Verifica si hay una instalacion en progreso
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
     * Limpia el estado de instalacion en progreso
     */
    fun clearInstallationProgress() {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("installation_start_time").apply()
        Log.d(TAG, " Limpiando estado de instalacion en progreso")
    }
    
    /**
     * Inicializa el sistema de actualizaciones
     * Debe ser llamado al iniciar la app para limpiar estados previos
     */
    fun initialize() {
        Log.d(TAG, " Inicializando sistema de actualizaciones...")
        
        // Limpiar cualquier estado de instalacion previo al iniciar
        clearInstallationProgress()
        
        // Guardar la version actual como referencia
        saveCurrentVersionCode()
        
        // Limpiar archivos APK antiguos
        apkInstaller.cleanupTempFiles()
        
        // Verificar y limpiar estados problematicos de versiones anteriores
        checkAndCleanProblematicVersions()
        
        // Verificar si la app se actualizo desde la ultima vez
        checkIfAppWasUpdated()
        
        Log.d(TAG, " Sistema de actualizaciones inicializado")
    }
    
    /**
     * Verifica si la aplicacion se actualizo desde la ultima ejecucion
     */
    private fun checkIfAppWasUpdated() {
        try {
            val currentVersion = getCurrentVersionCode()
            val lastKnownVersion = getLastKnownVersionCode()
            
            Log.d(TAG, " Verificando actualizacion: actual=$currentVersion, anterior=$lastKnownVersion")
            
            if (currentVersion > lastKnownVersion && lastKnownVersion > 0) {
                Log.d(TAG, " Aplicacion actualizada exitosamente!")
                
                // Limpiar el flag de descarga para permitir futuras actualizaciones
                val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .remove("last_downloaded_version")
                    .apply()
                
                // Mostrar mensaje de exito
                android.widget.Toast.makeText(
                    context,
                    "Aplicacion actualizada exitosamente! Version: ${getCurrentVersionName()}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, " Error verificando actualizacion de app", e)
        }
    }
    
    /**
     * Obtiene el codigo de version actual
     */
    private fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.longVersionCode.toInt()
        } catch (e: Exception) {
            Log.e(TAG, " Error obteniendo version actual", e)
            0
        }
    }
    
    /**
     * Guarda el codigo de version actual como referencia
     */
    private fun saveCurrentVersionCode() {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val currentVersion = getCurrentVersionCode()
        prefs.edit()
            .putInt("last_known_version_code", currentVersion)
            .apply()
        Log.d(TAG, " Guardada version actual: $currentVersion")
    }
    
    /**
     * Obtiene la ultima version conocida guardada
     */
    private fun getLastKnownVersionCode(): Int {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("last_known_version_code", 0)
    }
    
    /**
     * Verifica si una version especifica ya fue descargada anteriormente
     * Solo considera descargada si la version actual es mayor o igual a la descargada
     */
    private fun isVersionAlreadyDownloaded(versionCode: Int): Boolean {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val lastDownloadedVersion = prefs.getInt("last_downloaded_version", 0)
        val currentVersion = getCurrentVersionCode()
        
        // Solo considerar descargada si la version actual es mayor o igual a la descargada
        // Esto evita que se marque como descargada si no se instalo realmente
        val isActuallyInstalled = currentVersion >= lastDownloadedVersion && lastDownloadedVersion > 0
        
        Log.d(TAG, " Verificando si version $versionCode ya fue descargada:")
        Log.d(TAG, " Version actual: $currentVersion, ultima descargada: $lastDownloadedVersion")
        Log.d(TAG, " Esta realmente instalada?: $isActuallyInstalled")
        
        return isActuallyInstalled && versionCode <= lastDownloadedVersion
    }
    
    /**
     * Marca una version como descargada
     */
    private fun markVersionAsDownloaded(versionCode: Int) {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("last_downloaded_version", versionCode)
            .apply()
        Log.d(TAG, " Version $versionCode marcada como descargada")
    }
    
    /**
     * Limpia el estado de descarga para permitir volver a descargar la misma version
     * Util cuando la instalacion falla o el usuario cancela
     */
    fun clearDownloadState() {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("last_downloaded_version")
            .apply()
        Log.d(TAG, " Estado de descarga limpiado para permitir nueva descarga")
    }
    
    /**
     * Fuerza la verificacion de actualizaciones ignorando el estado de descarga
     * Util para casos donde la instalacion fallo y se quiere volver a intentar
     */
    fun forceCheckForUpdates(activity: Activity) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d(TAG, " Forzando verificacion de actualizaciones...")
                
                // Limpiar estado de descarga para permitir nueva descarga
                clearDownloadState()
                
                // Limpiar archivos antiguos
                apkInstaller.cleanupTempFiles()
                
                val updateInfo = withContext(Dispatchers.IO) {
                    updateChecker.checkForUpdate()
                }
                
                if (updateInfo != null) {
                    Log.d(TAG, " Actualizacion encontrada (forzada): ${updateInfo.versionName} (${updateInfo.versionCode})")
                    showUpdateDialog(updateInfo, activity)
                } else {
                    Log.d(TAG, " No hay actualizaciones disponibles (verificacion forzada)")
                    android.widget.Toast.makeText(
                        context, 
                        "No hay actualizaciones disponibles", 
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, " Error en verificacion forzada de actualizaciones", e)
                android.widget.Toast.makeText(
                    context, 
                    "Error verificando actualizaciones: ${e.message}", 
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    /**
     * Verifica si una instalacion fallo y limpia el estado si es necesario
     */
    private fun checkAndCleanFailedInstallation() {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val lastDownloadedVersion = prefs.getInt("last_downloaded_version", 0)
        val currentVersion = getCurrentVersionCode()
        
        // Si hay una version marcada como descargada pero la version actual es menor,
        // significa que la instalacion fallo
        if (lastDownloadedVersion > 0 && currentVersion < lastDownloadedVersion) {
            Log.d(TAG, " Instalacion fallida detectada. Version actual: $currentVersion, descargada: $lastDownloadedVersion")
            clearDownloadState()
        }
    }
    
    /**
     * Verifica si una actualizacion es critica y debe forzarse
     * Las actualizaciones criticas incluyen correcciones importantes del sistema
     */
    private fun isCriticalUpdate(updateInfo: SimpleDriveRelease): Boolean {
        // Verificar si es una version critica especifica
        val isCriticalVersion = UpdateConfig.CRITICAL_VERSIONS.contains(updateInfo.versionCode)
        
        // Verificar si la descripcion contiene palabras clave criticas
        val hasCriticalKeywords = UpdateConfig.CRITICAL_KEYWORDS.any { keyword ->
            updateInfo.description.contains(keyword, ignoreCase = true)
        }
        
        Log.d(TAG, " Verificando si es actualizacion critica:")
        Log.d(TAG, " Version: ${updateInfo.versionCode}")
        Log.d(TAG, " Version critica?: $isCriticalVersion")
        Log.d(TAG, " Contiene palabras clave?: $hasCriticalKeywords")
        
        return isCriticalVersion || hasCriticalKeywords
    }
    
    /**
     * Detecta y limpia estados problematicos de versiones anteriores
     * Esto es especialmente util para usuarios que ya tienen versiones con el bug
     */
    private fun checkAndCleanProblematicVersions() {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val currentVersion = getCurrentVersionCode()
        val lastDownloadedVersion = prefs.getInt("last_downloaded_version", 0)
        
        // Versiones problematicas que necesitan limpieza de estado
        val problematicVersions = UpdateConfig.PROBLEMATIC_VERSIONS
        
        // Verificar si la version actual esta en la lista de problematicas
        val isCurrentVersionProblematic = problematicVersions.contains(currentVersion)
        
        // Verificar si hay un estado de descarga que podria ser problematico
        val hasProblematicDownloadState = lastDownloadedVersion > 0 && currentVersion < lastDownloadedVersion
        
        Log.d(TAG, " Verificando versiones problematicas:")
        Log.d(TAG, " Version actual: $currentVersion")
        Log.d(TAG, " Ultima descargada: $lastDownloadedVersion")
        Log.d(TAG, " Version actual problematica?: $isCurrentVersionProblematic")
        Log.d(TAG, " Estado de descarga problematico?: $hasProblematicDownloadState")
        
        if (isCurrentVersionProblematic || hasProblematicDownloadState) {
            Log.d(TAG, " Limpiando estado problematico de versiones anteriores...")
            
            // Limpiar todo el estado de actualizaciones para permitir verificacion limpia
            clearDownloadState()
            clearInstallationProgress()
            
            // Marcar que se hizo la limpieza para evitar hacerla repetidamente
            prefs.edit()
                .putBoolean("cleanup_done_for_version_$currentVersion", true)
                .apply()
            
            Log.d(TAG, " Estado problematico limpiado para version $currentVersion")
            
            // Mostrar mensaje informativo al usuario
            android.widget.Toast.makeText(
                context,
                "Sistema de actualizaciones optimizado. Se verificara automaticamente.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } else {
            Log.d(TAG, " No se detectaron versiones problematicas")
        }
    }
}
