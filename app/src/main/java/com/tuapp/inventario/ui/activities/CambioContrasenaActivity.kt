package com.tuapp.inventario

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.FirebaseFirestore
import com.tuapp.inventario.model.Local
import com.tuapp.inventario.model.UsuarioActual
import com.tuapp.inventario.model.UsuarioLocal
import com.tuapp.inventario.utils.FooterHelper
import com.tuapp.inventario.utils.FirebaseRegionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CambioContrasenaActivity : AppCompatActivity() {

    private lateinit var edtNuevaPassword: EditText
    private lateinit var edtConfirmarPassword: EditText
    private lateinit var btnCambiarPassword: Button
    private lateinit var txtEstado: TextView
    private lateinit var imgWatermark: ImageView

    // Overlay views
    private lateinit var offlineOverlay: FrameLayout
    private lateinit var progressSkeleton: ProgressBar
    private lateinit var txtOfflineMsg: TextView

    private var firestore: FirebaseFirestore? = null
    private var localId: String = ""
    private var email: String = ""
    private var regionId: String = ""

    // NetworkCallback
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isCurrentlyOnline = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cambio_contrasena)

        // Recuperar parámetros
        localId = intent.getStringExtra("localId") ?: ""
        email = intent.getStringExtra("email") ?: ""
        regionId = intent.getStringExtra("regionId") ?: ""

        if (localId.isEmpty() || email.isEmpty()) {
            Toast.makeText(this, "Datos de inicio de sesión inválidos", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupListeners()
        configurarMarcaDeAgua()
        
        // Configurar footer del desarrollador
        FooterHelper.configurarFooter(this)

        // Inicializar ConnectivityManager y callback
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallback()

        // Inicializar Firestore para la región
        inicializarFirestore()

        // Verificar conexión inicial
        if (!isOnline()) {
            showOfflineOverlay("Sin conexión. Verifique su red")
        } else {
            hideOfflineOverlay()
        }
    }

    override fun onResume() {
        super.onResume()
        configurarMarcaDeAgua()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCallback()
    }

    private fun initViews() {
        edtNuevaPassword = findViewById(R.id.edtNuevaPassword)
        edtConfirmarPassword = findViewById(R.id.edtConfirmarPassword)
        btnCambiarPassword = findViewById(R.id.btnCambiarPassword)
        txtEstado = findViewById(R.id.txtEstado)
        imgWatermark = findViewById(R.id.imgWatermark)

        offlineOverlay = findViewById(R.id.offlineOverlay)
        progressSkeleton = findViewById(R.id.progressSkeleton)
        txtOfflineMsg = findViewById(R.id.txtOfflineMsg)
    }

    private fun setupListeners() {
        btnCambiarPassword.setOnClickListener {
            if (!isOnline()) {
                txtEstado.text = "No hay internet. Intenta reconectar."
                showOfflineOverlay("Sin conexión. Reconectando...")
                return@setOnClickListener
            }
            procesarCambioContrasena()
        }
    }

    private fun configurarMarcaDeAgua() {
        // Adaptar según modo oscuro / claro
        val isDarkMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (isDarkMode) {
            imgWatermark.alpha = 0.04f
        } else {
            imgWatermark.alpha = 0.08f
        }
    }

    private fun inicializarFirestore() {
        try {
            // Si ya está inicializado en FirebaseRegionManager, usar ese
            firestore = FirebaseRegionManager.getFirestore()
        } catch (e: Exception) {
            // Intentar inicializar con la región guardada
            lifecycleScope.launch {
                try {
                    val regiones = FirebaseRegionManager.cargarRegiones(this@CambioContrasenaActivity)
                    val region = regiones.find { it.id == regionId }
                    if (region != null) {
                        val exito = FirebaseRegionManager.initializeFirebase(this@CambioContrasenaActivity, region)
                        if (exito) {
                            firestore = FirebaseRegionManager.getFirestore()
                            Log.d("CambioContrasenaActivity", "✅ Firebase inicializado para región: ${region.nombre}")
                        }
                    }
                } catch (ex: Exception) {
                    Log.e("CambioContrasenaActivity", "Error al inicializar Firestore", ex)
                }
            }
        }
    }

    private fun procesarCambioContrasena() {
        val nuevaPass = edtNuevaPassword.text.toString().trim()
        val confirmarPass = edtConfirmarPassword.text.toString().trim()

        if (nuevaPass.isEmpty()) {
            edtNuevaPassword.error = "Contraseña requerida"
            return
        }

        if (nuevaPass.length < 6) {
            edtNuevaPassword.error = "La contraseña debe tener al menos 6 caracteres"
            return
        }

        val añoActual = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        if (nuevaPass == "Inicial$añoActual") {
            edtNuevaPassword.error = "No puede utilizar la contraseña temporal por defecto"
            return
        }

        if (confirmarPass.isEmpty()) {
            edtConfirmarPassword.error = "Debe confirmar la contraseña"
            return
        }

        if (nuevaPass != confirmarPass) {
            edtConfirmarPassword.error = "Las contraseñas no coinciden"
            return
        }

        lifecycleScope.launch {
            try {
                txtEstado.text = "Actualizando contraseña en base de datos..."
                val db = firestore ?: FirebaseRegionManager.getFirestore() ?: throw IllegalStateException("Firestore no inicializado")

                // Buscar el local en Firestore
                val docRef = db.collection("locales").document(localId)
                val snapshot = docRef.get().await()

                if (!snapshot.exists()) {
                    txtEstado.text = "Error: Local no encontrado en Firestore"
                    return@launch
                }

                val local = snapshot.toObject(Local::class.java)
                if (local == null) {
                    txtEstado.text = "Error: No se pudo parsear el Local"
                    return@launch
                }

                // Buscar el usuario administrador del local
                val usuarioAdmin = local.usuarios.values.find { it.rol == "admin_local" }
                if (usuarioAdmin == null) {
                    txtEstado.text = "Error: No se encontró el usuario administrador del local"
                    return@launch
                }

                // Crear usuario actualizado con nueva contraseña y apagar bandera
                val usuarioAdminActualizado = usuarioAdmin.copy(
                    contraseña = nuevaPass,
                    debeCambiarContraseña = false
                )

                // Actualizar el local
                val usuariosActualizados = local.usuarios.toMutableMap()
                usuariosActualizados[usuarioAdmin.id] = usuarioAdminActualizado

                val localActualizado = local.copy(
                    usuarios = usuariosActualizados,
                    debeCambiarContraseña = false
                )

                // Subir documento actualizado
                docRef.set(localActualizado).await()
                Log.d("CambioContrasenaActivity", "✅ Local actualizado exitosamente en Firestore")

                txtEstado.text = "Sesión iniciada. Abriendo aplicación..."

                // Iniciar sesión y guardar en SharedPreferences
                val usuarioActual = UsuarioActual(
                    localId = local.id,
                    localNombre = local.nombre,
                    usuarioId = "admin_${local.id.lowercase()}",
                    usuario = "admin_${local.id.lowercase()}",
                    rol = "admin_local"
                )
                guardarUsuarioActual(usuarioActual)

                // Programar alarma de vencimientos
                val alarmScheduler = com.tuapp.inventario.notification.AlarmScheduler(this@CambioContrasenaActivity)
                alarmScheduler.scheduleVencimientosReminder()

                // Ir a MainActivity
                val intent = Intent(this@CambioContrasenaActivity, MainActivity::class.java)
                startActivity(intent)
                finish()

            } catch (e: Exception) {
                Log.e("CambioContrasenaActivity", "Error al actualizar contraseña", e)
                txtEstado.text = "Error: ${e.message}"
            }
        }
    }

    private fun guardarUsuarioActual(usuario: UsuarioActual) {
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString("localId", usuario.localId)
        editor.putString("localNombre", usuario.localNombre)
        editor.putString("usuarioId", usuario.usuarioId)
        editor.putString("usuario", usuario.usuario)
        editor.putString("rol", usuario.rol)
        editor.putString("localesAsignados", "")
        editor.putString("metodoLogin", "local_seleccionado")
        editor.apply()
    }

    // --- Network Connection Management ---

    private fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                isCurrentlyOnline = true
                runOnUiThread {
                    hideOfflineOverlay()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                isCurrentlyOnline = false
                runOnUiThread {
                    showOfflineOverlay("Sin conexión. Verifique su red")
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } catch (e: Exception) {
            Log.w("CambioContrasenaActivity", "No se pudo registrar network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            // Ignorar
        }
    }

    private fun showOfflineOverlay(mensaje: String) {
        txtOfflineMsg.text = mensaje
        offlineOverlay.visibility = View.VISIBLE
    }

    private fun hideOfflineOverlay() {
        offlineOverlay.visibility = View.GONE
    }
}
