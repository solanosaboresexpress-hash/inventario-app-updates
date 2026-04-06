package com.tuapp.inventario

import android.app.Activity
import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tuapp.inventario.update.UpdateDialog
import com.tuapp.inventario.update.ApkInstaller
import com.tuapp.inventario.util.DateHelper
import java.util.*
import com.tuapp.inventario.repository.InventarioRepository
import com.tuapp.inventario.repository.FirebaseInventarioRepository
import com.tuapp.inventario.RegistroInventario
import com.tuapp.inventario.model.UsuarioActual
import com.tuapp.inventario.notification.AlarmScheduler
import com.tuapp.inventario.notification.NotificationHelper
import com.tuapp.inventario.utils.FooterHelper
import com.tuapp.inventario.utils.FirebaseRegionManager
import com.tuapp.inventario.repository.VencimientoRepository
import com.google.firebase.Timestamp
import com.tuapp.inventario.model.VencimientoLote
import com.tuapp.inventario.InventarioApplication
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {
    
    private lateinit var txtFechaActual: TextView
    private lateinit var layoutDateSelector: com.google.android.material.card.MaterialCardView
    private lateinit var btnDiaAnterior: ImageButton
    private lateinit var btnDiaSiguiente: ImageButton
    private lateinit var txtLocalActual: TextView
    private lateinit var btnAlertaLocal: ImageView
    private lateinit var btnIngreso: Button
    private lateinit var btnStock: Button
    private lateinit var btnVencimientos: Button
    private var fechaSeleccionada: String = ""
    
    private lateinit var repository: InventarioRepository
    private var usuarioActual: UsuarioActual? = null
    private lateinit var updateDialog: UpdateDialog
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var notificationHelper: NotificationHelper
    
    
    // Cache global de promedios reales para toda la app
    companion object {
        private const val REQUEST_CODE_CARGA = 1001
        var promediosReales: Map<String, Map<String, Double>> = emptyMap()
        var promediosCargados = false
        
        // Cache para reducir lecturas de Firebase
        private var cacheRegistrosCalendario: Map<String, Set<String>>? = null // fecha -> tipos
        private var cacheRegistrosCalendarioTimestamp: Long = 0
        private var cacheRegistrosAlertas: List<RegistroInventario>? = null
        private var cacheRegistrosAlertasTimestamp: Long = 0
        private var cacheRegistrosAlertasLocalId: String = "" // ID del local al que pertenece el cache
        private var cacheProductos: List<com.tuapp.inventario.Producto>? = null
        private var cacheProductosTimestamp: Long = 0
        private var cacheRegistrosPromedios: List<RegistroInventario>? = null
        private var cacheRegistrosPromediosTimestamp: Long = 0
        
        // Cache para stock calculado (evitar consultas duplicadas)
        private var cacheStockCalculado: Map<String, Int>? = null
        private var cacheStockCalculadoTimestamp: Long = 0
        private var cacheStockCalculadoLocalId: String = ""
        
        // 📈 Cache para tendencias de ventas (calculadas una vez al día)
        var cacheTendencias: Map<String, Double>? = null
        var cacheTendenciasFecha: String = ""
        var cacheTendenciasLocalId: String = ""
        
        // Flag para evitar verificaciones simultáneas
        @Volatile
        private var verificacionVencimientosEnProgreso = false
        
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L // 5 minutos
        private const val CACHE_PRODUCTOS_DURATION_MS = 15 * 60 * 1000L // 15 minutos (productos cambian raramente)
        private const val CACHE_PROMEDIOS_DURATION_MS = 30 * 60 * 1000L // 30 minutos (promedios cambian lentamente)
        private const val CACHE_STOCK_DURATION_MS = 2 * 60 * 1000L // 2 minutos (stock cambia frecuentemente)
        
        fun isCacheValid(timestamp: Long): Boolean {
            return (System.currentTimeMillis() - timestamp) < CACHE_DURATION_MS
        }
        
        fun isProductosCacheValid(timestamp: Long): Boolean {
            return (System.currentTimeMillis() - timestamp) < CACHE_PRODUCTOS_DURATION_MS
        }
        
        fun isPromediosCacheValid(timestamp: Long): Boolean {
            return (System.currentTimeMillis() - timestamp) < CACHE_PROMEDIOS_DURATION_MS
        }
        
        fun clearCache() {
            cacheRegistrosCalendario = null
            cacheRegistrosCalendarioTimestamp = 0
            cacheRegistrosAlertas = null
            cacheRegistrosAlertasTimestamp = 0
            cacheRegistrosAlertasLocalId = ""
            cacheProductos = null
            cacheProductosTimestamp = 0
            cacheRegistrosPromedios = null
            cacheRegistrosPromediosTimestamp = 0
            cacheStockCalculado = null
            cacheStockCalculadoTimestamp = 0
            cacheStockCalculadoLocalId = ""
        }
        
        fun isAlertasCacheValid(timestamp: Long, localId: String): Boolean {
            return (System.currentTimeMillis() - timestamp) < CACHE_DURATION_MS &&
                   cacheRegistrosAlertasLocalId == localId
        }
        
        fun isStockCacheValid(timestamp: Long, localId: String): Boolean {
            return (System.currentTimeMillis() - timestamp) < CACHE_STOCK_DURATION_MS &&
                   cacheStockCalculadoLocalId == localId
        }
    }
    
    // Variables para controlar actualizaciones de botones
    private var ultimoEstadoIngreso: Boolean? = null
    private var ultimoEstadoStock: Boolean? = null
    private var ultimoEstadoVencimientos: Boolean? = null
    private var actualizandoBotones = false
    private var ultimaActualizacion = 0L
    private var sincronizacionInicialCompletada = false // Flag para saber si la sincronización inicial terminó
    
    // Variable para el diálogo del calendario
    private var calendarioDialog: AlertDialog? = null
    
    // Variable para prevenir múltiples toques
    private var cargandoCalendario = false
    
    // Variable para el timeout del indicador de carga
    private var indicadorCargaToast: Toast? = null
    
    // BroadcastReceiver para actualizar botones cuando se guardan datos
    private val actualizarBotonesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.tuapp.inventario.ACTUALIZAR_BOTONES") {
                val tipo = intent.getStringExtra("tipo")
                val localIdBroadcast = intent.getStringExtra("localId")
                
                Log.d("MainActivity", "📡 Broadcast recibido: Actualizar botones - tipo: $tipo")
                
                // ✅ Limpiar cache cuando se guardan nuevos datos
                clearCache()
                Log.d("MainActivity", "🗑️ Cache limpiado debido a nuevo registro guardado")
                
                // Esperar un poco para asegurar que la transacción de Room se complete
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(500) // Delay para asegurar que Room complete la transacción
                    verificarRegistrosExistentes()
                    
                    // 📈 Si es Stock Final, calcular tendencias
                    Log.d("TENDENCIAS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Log.d("TENDENCIAS", "🔍 Verificando si calcular tendencias...")
                    Log.d("TENDENCIAS", "   Tipo: $tipo")
                    Log.d("TENDENCIAS", "   Local broadcast: $localIdBroadcast")
                    Log.d("TENDENCIAS", "   Local actual: ${usuarioActual?.localId}")
                    Log.d("TENDENCIAS", "   Condición cumplida: ${tipo == "Stock Final" && localIdBroadcast == usuarioActual?.localId}")
                    
                    if (tipo == "Stock Final" && localIdBroadcast == usuarioActual?.localId) {
                        Log.d("TENDENCIAS", "✅ Condición cumplida → Calculando tendencias...")
                        Log.d("TENDENCIAS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        calcularYGuardarTendencias()
                    } else {
                        Log.d("TENDENCIAS", "⏭️ Condición NO cumplida → No se calculan tendencias")
                        Log.d("TENDENCIAS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    }
                    
                    // Verificar nuevamente después de un delay adicional para capturar cualquier cambio
                    kotlinx.coroutines.delay(1000)
                    verificarRegistrosExistentes()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar repositorio (se configurará después)
        // repository se inicializará en configurarUsuario()
        
        // Inicializar sistema de actualizaciones
        updateDialog = UpdateDialog(this)
        updateDialog.initialize()
        
        // Inicializar sistema de notificaciones
        alarmScheduler = AlarmScheduler(this)
        notificationHelper = NotificationHelper(this)
        

        txtFechaActual = findViewById(R.id.txtFechaActual)
        layoutDateSelector = findViewById(R.id.layoutDateSelector)
        btnDiaAnterior = findViewById(R.id.btnDiaAnterior)
        btnDiaSiguiente = findViewById(R.id.btnDiaSiguiente)
        txtLocalActual = findViewById(R.id.txtLocalActual)
        btnAlertaLocal = findViewById(R.id.btnAlertaLocal)
        
        btnIngreso = findViewById(R.id.btnIngreso)
        btnStock = findViewById(R.id.btnStock)
        btnVencimientos = findViewById(R.id.btnVencimientos)

        val btnPromedioVentas: Button = findViewById(R.id.btnPromedioVentas)
        
        // Configurar fecha actual
        configurarFechaActual()
        
        // Configurar selector de fecha - ahora en el TextView central
        txtFechaActual.setOnClickListener {
            mostrarSelectorFecha()
        }
        
        // Configurar flechas de navegación de fecha
        btnDiaAnterior.setOnClickListener {
            cambiarDia(-1)
        }
        
        btnDiaSiguiente.setOnClickListener {
            cambiarDia(1)
        }

        btnIngreso.setOnClickListener {
            val intent = Intent(this, CargaActivity::class.java)
            intent.putExtra("tipo", "Ingreso de Mercadería")
            intent.putExtra("fecha", fechaSeleccionada)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_CARGA)
        }

        btnStock.setOnClickListener {
            val intent = Intent(this, CargaActivity::class.java)
            intent.putExtra("tipo", "Stock Final")
            intent.putExtra("fecha", fechaSeleccionada)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_CARGA)
        }

        btnPromedioVentas.setOnClickListener {
            val intent = Intent(this, PromedioVentasActivity::class.java)
            startActivity(intent)
        }

        val btnVentaVolumen: Button = findViewById(R.id.btnVentaVolumen)
        btnVentaVolumen.setOnClickListener {
            val intent = Intent(this, VentaVolumenActivity::class.java)
            startActivity(intent)
        }
        
        // Botón Vencimientos
        btnVencimientos.setOnClickListener {
            val intent = Intent(this, VencimientosActivity::class.java)
            startActivity(intent)
        }
        
        // Configurar usuario
        configurarUsuario()
        
        // 📈 Calcular y guardar tendencias en background (no bloquea la UI)
        // MOVIDO A CargaActivity: Se calcula al guardar Stock Final (tiene más sentido)
        // calcularYGuardarTendencias()
        
        // Configurar marca de agua según modo oscuro
        configurarMarcaDeAgua()
        
        // NOTA: La verificación inmediata se ejecutará DESPUÉS de la sincronización inicial
        // en el bloque de sincronización más abajo, para asegurar que los datos estén en Room
        
        // Registrar BroadcastReceiver para actualizar botones cuando se guardan datos
        // RECEIVER_NOT_EXPORTED porque es un broadcast interno de la app
        val filter = IntentFilter("com.tuapp.inventario.ACTUALIZAR_BOTONES")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actualizarBotonesReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(actualizarBotonesReceiver, filter)
        }
        
        // Verificar alertas del local
        // Configurar footer del desarrollador
        FooterHelper.configurarFooter(this)
        
        // Forzar sincronización con Firebase al abrir la app y LUEGO verificar alertas
        // Esto asegura que los datos estén sincronizados antes de verificar los registros
        lifecycleScope.launch {
            try {
                Log.d("MainActivity", "🔄 Iniciando sincronización inicial...")
                
                // ✅ VERIFICAR INMEDIATAMENTE con datos locales (si hay datos en Room)
                // Esto permite que los botones se actualicen de inmediato si hay datos locales
                if (usuarioActual != null && fechaSeleccionada.isNotEmpty()) {
                    Log.d("MainActivity", "🔍 Verificación inmediata (datos locales existentes)...")
                    verificarRegistrosExistentes()
                }
                
                // Esperar a que la sincronización termine
                Log.d("MainActivity", "🔄 Iniciando forzarSincronizacionFirebase()...")
                forzarSincronizacionFirebase()
                Log.d("MainActivity", "✅ Sincronización inicial completada")
                
                // Marcar que la sincronización inicial terminó
                sincronizacionInicialCompletada = true
                
                // ✅ VERIFICAR DESPUÉS DE SINCRONIZACIÓN: Ahora que los datos están en Room
                // Esperar más tiempo para que Room procese las escrituras (la sincronización puede tomar varios segundos)
                kotlinx.coroutines.delay(2000) // Aumentado a 2 segundos para dar tiempo a Room
                Log.d("MainActivity", "🔍 Verificación después de sincronización (datos descargados)...")
                verificarRegistrosExistentes()
                
                // Verificar nuevamente después de un delay adicional por si Room aún está procesando
                kotlinx.coroutines.delay(1000) // Aumentado a 1 segundo adicional
                Log.d("MainActivity", "🔍 Verificación final después de sincronización...")
                verificarRegistrosExistentes()
                
                // ✅ VERIFICAR VENCIMIENTOS DESPUÉS DEL LOGIN Y SINCRONIZACIÓN
                // Esperar un poco más antes de verificar vencimientos para asegurar que los datos estén listos
                kotlinx.coroutines.delay(1000) // Delay adicional antes de verificar vencimientos
                Log.d("MainActivity", "🔍 Verificando vencimientos después del login...")
                verificarVencimientosCoinciden()
                
                // Ahora sí verificar alertas (pero NO incluir verificarRegistrosExistentes aquí)
                verificarAlertasLocal()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error en sincronización inicial", e)
                // Marcar como completada incluso si falla, para no bloquear verificaciones futuras
                sincronizacionInicialCompletada = true
                // Si falla la sincronización, aún así verificar con datos locales
                kotlinx.coroutines.delay(500)
                verificarRegistrosExistentes()
                // ✅ VERIFICAR VENCIMIENTOS incluso si falla la sincronización
                verificarVencimientosCoinciden()
                verificarAlertasLocal()
            }
        }
        
        // Verificar actualizaciones
        updateDialog.checkForUpdates(this)
        
        // Programar notificaciones
        programarNotificaciones()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(actualizarBotonesReceiver)
        } catch (e: Exception) {
            // El receiver puede no estar registrado, ignorar
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Configurar marca de agua según modo oscuro (por si cambió mientras la app estaba en segundo plano)
        configurarMarcaDeAgua()
        
        // ✅ VERIFICAR INMEDIATAMENTE con datos locales (como en la web)
        // Esto asegura que los botones se actualicen de inmediato, sin esperar sincronización
        Log.d("MainActivity", "🔍 Verificación inmediata en onResume (datos locales)...")
        verificarRegistrosExistentes()
        verificarVencimientosCoinciden()
        
        // Si la sincronización inicial ya terminó, sincronizar en segundo plano
        if (sincronizacionInicialCompletada) {
            // Forzar sincronización con Firebase al reanudar la app (en segundo plano)
            lifecycleScope.launch {
                try {
                    forzarSincronizacionFirebase()
                    // Después de sincronizar, verificar registros nuevamente
                    kotlinx.coroutines.delay(500)
                    Log.d("MainActivity", "🔍 Verificación después de sincronización en onResume...")
                    verificarRegistrosExistentes()
                    verificarVencimientosCoinciden()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error en sincronización en onResume", e)
                }
            }
        } else {
            // Si la sincronización inicial aún no terminó, esperar a que termine y luego verificar nuevamente
            lifecycleScope.launch {
                // Esperar hasta que la sincronización inicial termine (máximo 10 segundos)
                var intentos = 0
                while (!sincronizacionInicialCompletada && intentos < 20) {
                    kotlinx.coroutines.delay(500)
                    intentos++
                }
                
                // Verificar nuevamente después de que termine la sincronización
                Log.d("MainActivity", "🔍 Verificación después de sincronización inicial en onResume...")
                verificarRegistrosExistentes()
                verificarVencimientosCoinciden()
            }
        }
        
        // Verificar si hay una actualización pendiente después de regresar de permisos
        updateDialog.checkForUpdateAfterInstallation(this)
        
        // ✅ Verificar alertas del local después de un delay para asegurar que los datos estén listos
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000) // Esperar 2 segundos para que los datos se carguen
            Log.d("MainActivity", "🔍 Verificando alertas del local en onResume...")
            verificarAlertasLocal()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_CODE_CARGA, 2000 -> {
                // Cuando regresa de CargaActivity (normal o desde completar error), forzar verificación inmediata y luego con delays
                if (resultCode == Activity.RESULT_OK) {
                    Log.d("MainActivity", "🔄 Regresó de CargaActivity, verificando registros...")
                    // Verificar inmediatamente (puede que los datos ya estén guardados)
                    verificarRegistrosExistentes()
                    
                    // Verificar después de múltiples delays para asegurar que los datos se guardaron
                    // El BroadcastReceiver también se activará cuando CargaActivity envíe el broadcast
                    lifecycleScope.launch {
                        // Múltiples verificaciones con delays incrementales
                        kotlinx.coroutines.delay(500) // Primer intento después de 500ms
                        verificarRegistrosExistentes()
                        
                        kotlinx.coroutines.delay(500) // Segundo intento (total 1000ms)
                        verificarRegistrosExistentes()
                        
                        kotlinx.coroutines.delay(1000) // Tercer intento (total 2000ms)
                        verificarRegistrosExistentes()
                        
                        // También verificar vencimientos después de guardar stock/ingreso
                        kotlinx.coroutines.delay(500)
                        verificarVencimientosCoinciden()
                        
                        // Si fue desde completar error, re-verificar alertas para actualizar el diálogo
                        if (requestCode == 2000) {
                            kotlinx.coroutines.delay(1000)
                            verificarAlertasLocal()
                        }
                    }
                }
            }
            com.tuapp.inventario.update.ApkInstaller.REQUEST_INSTALL_PACKAGES -> {
                Log.d("MainActivity", "🔧 Resultado de solicitud de permisos de instalación: $resultCode")
                if (resultCode == Activity.RESULT_OK) {
                    Log.d("MainActivity", "✅ Permisos de instalación otorgados, reintentando instalación...")
                    updateDialog.retryInstallation()
                } else {
                    Log.d("MainActivity", "❌ Permisos de instalación denegados")
                    Toast.makeText(this, "Se requieren permisos de instalación para actualizar la aplicación", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    override fun onBackPressed() {
        // Verificar si el usuario ingresó desde gestión de locales
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        val metodoLogin = prefs.getString("metodoLogin", "")
        
        if (metodoLogin == "admin_gestion") {
            // Si ingresó desde gestión de locales, volver a esa pantalla
            val intent = Intent(this, GestionLocalesActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        } else {
            // Si ingresó normalmente, volver al login
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }
    
    private fun configurarFechaActual() {
        val calendar = DateHelper.getCalendar()
        val formato = DateHelper.getDateFormat("EEEE - dd/MM/yy", Locale("es", "ES"))
        // 🔧 FORZAR ZONA HORARIA DE ARGENTINA
        val timeZone = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
        formato.timeZone = timeZone
        val sdfFecha = DateHelper.getDateFormat("yyyy-MM-dd")
        sdfFecha.timeZone = timeZone
        fechaSeleccionada = sdfFecha.format(calendar.time)
        txtFechaActual.text = formato.format(calendar.time)
    }
    
    private fun mostrarSelectorFecha() {
        // Prevenir múltiples toques simultáneos
        if (cargandoCalendario) {
            return
        }
        
        // Verificar si ya hay un diálogo abierto
        if (calendarioDialog != null && calendarioDialog!!.isShowing) {
            return
        }
        
        // Crear un CalendarView personalizado en lugar del DatePickerDialog
        mostrarCalendarioPersonalizado()
    }
    
    /**
     * Muestra un calendario personalizado con colores para fechas con datos
     */
    private fun mostrarCalendarioPersonalizado() {
        // Marcar como cargando para prevenir múltiples toques
        cargandoCalendario = true
        
        // Mostrar indicador de carga inmediatamente
        runOnUiThread {
            mostrarIndicadorCarga()
        }
        
        lifecycleScope.launch {
            try {
                val localId = obtenerLocalId()
                
                // ✅ OPTIMIZACIÓN: Usar cache para reducir lecturas de Firebase
                val fechasCompletas = mutableSetOf<String>()
                val fechasParciales = mutableSetOf<String>()
                val registrosPorFecha: Map<String, Set<String>>
                
                // Verificar si el cache es válido
                if (isCacheValid(cacheRegistrosCalendarioTimestamp) && cacheRegistrosCalendario != null) {
                    Log.d("MainActivity", "📅 Calendario - Usando cache (ahorra lecturas de Firebase)")
                    registrosPorFecha = cacheRegistrosCalendario!!
                } else {
                    Log.d("MainActivity", "📅 Calendario - Cache expirado o no existe, consultando Firebase...")
                    val firestore = FirebaseRegionManager.getFirestore()
                    val registrosCollection = firestore.collection("locales").document(localId).collection("registros")
                    
                    // Obtener todos los registros de Firebase (solo si el cache expiró)
                    val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        registrosCollection.get().await()
                    }
                    
                    // Procesar registros para determinar fechas completas y parciales
                    val registrosPorFechaMutable = mutableMapOf<String, MutableSet<String>>()
                    
                    snapshot.documents.forEach { doc ->
                        val fecha = doc.getString("fecha") ?: return@forEach
                        val tipo = doc.getString("tipo") ?: return@forEach
                        
                        if (!registrosPorFechaMutable.containsKey(fecha)) {
                            registrosPorFechaMutable[fecha] = mutableSetOf()
                        }
                        registrosPorFechaMutable[fecha]!!.add(tipo)
                    }
                    
                    // Guardar en cache
                    cacheRegistrosCalendario = registrosPorFechaMutable.mapValues { it.value.toSet() }
                    cacheRegistrosCalendarioTimestamp = System.currentTimeMillis()
                    registrosPorFecha = cacheRegistrosCalendario!!
                    Log.d("MainActivity", "📅 Calendario - Cache actualizado con ${registrosPorFecha.size} fechas")
                }
                
                // Clasificar fechas
                registrosPorFecha.forEach { (fecha, tipos) ->
                    val tieneIngreso = tipos.contains("Ingreso de Mercadería")
                    val tieneStock = tipos.contains("Stock Final")
                    
                    when {
                        tieneIngreso && tieneStock -> fechasCompletas.add(fecha)
                        tieneIngreso || tieneStock -> fechasParciales.add(fecha)
                        // else: fecha sin datos (no se agrega a ningún set)
                    }
                }
                
                Log.d("MainActivity", "📅 Calendario - Fechas completas: ${fechasCompletas.size}, Parciales: ${fechasParciales.size}")
                
                // Mostrar calendario personalizado en UI thread
                runOnUiThread {
                    ocultarIndicadorCarga()
                    crearDialogoCalendarioConColores(fechasCompletas, fechasParciales)
                    cargandoCalendario = false // Resetear flag de carga
                }
                
            } catch (e: Exception) {
                Log.e("MainActivity", "Error cargando datos para calendario desde Firebase", e)
                
                // Fallback: intentar con Room si Firebase falla
                try {
                    val localId = obtenerLocalId()
                    val localRepository = (application as InventarioApplication).localRepository
                    
                    localRepository.getAllFechas(localId).collect { fechas ->
                        if (isFinishing || isDestroyed) return@collect
                        
                        val fechasCompletas = mutableSetOf<String>()
                        val fechasParciales = mutableSetOf<String>()
                        
                        fechas.forEach { fecha ->
                            val tipos = localRepository.getTiposRegistroPorFecha(localId, fecha)
                            val tieneIngreso = tipos.contains("Ingreso de Mercadería")
                            val tieneStock = tipos.contains("Stock Final")
                            
                            when {
                                tieneIngreso && tieneStock -> fechasCompletas.add(fecha)
                                tieneIngreso || tieneStock -> fechasParciales.add(fecha)
                            }
                        }
                        
                        runOnUiThread {
                            ocultarIndicadorCarga()
                            crearDialogoCalendarioConColores(fechasCompletas, fechasParciales)
                            cargandoCalendario = false
                        }
                        
                        return@collect
                    }
                } catch (e2: Exception) {
                    Log.e("MainActivity", "Error en fallback a Room para calendario", e2)
                    // Fallback final al DatePickerDialog normal
                    runOnUiThread {
                        ocultarIndicadorCarga()
                        mostrarDatePickerNormal()
                        cargandoCalendario = false
                    }
                }
            }
        }
    }
    
    /**
     * Crea un diálogo con calendario personalizado y colores
     */
    private fun crearDialogoCalendarioConColores(
        fechasCompletas: Set<String>, 
        fechasParciales: Set<String>
    ) {
        // Variable para controlar el mes actual - inicializar con la fecha seleccionada
        var mesActual = DateHelper.getCalendar()
        
        // Si hay una fecha seleccionada, navegar a ese mes
        if (fechaSeleccionada.isNotEmpty()) {
            try {
                // 🔧 FORZAR ZONA HORARIA DE ARGENTINA
                val timeZone = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
                val formato = DateHelper.getDateFormat("yyyy-MM-dd")
                formato.timeZone = timeZone
                val fecha = formato.parse(fechaSeleccionada)
                if (fecha != null) {
                    mesActual = DateHelper.getCalendar().apply {
                        time = fecha
                        set(Calendar.DAY_OF_MONTH, 1) // Primer día del mes
                    }
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Error parseando fecha seleccionada: ${e.message}")
                // Usar fecha actual como fallback
                mesActual = DateHelper.getCalendar()
            }
        }
        
        // Crear layout principal
        val layoutPrincipal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        
        // Título
        val txtTitulo = TextView(this).apply {
            text = "📅 Seleccionar Fecha"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        layoutPrincipal.addView(txtTitulo)
        
        // Leyenda de colores (más compacta)
        val leyenda = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        
        // Verde - Completos
        val circuloVerde = TextView(this).apply {
            text = "● Completo"
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
            textSize = 12f
            setPadding(0, 0, 16, 0)
        }
        leyenda.addView(circuloVerde)
        
        // Naranja - Parciales
        val circuloNaranja = TextView(this).apply {
            text = "● Parcial"
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_dark))
                    textSize = 12f
                }
        leyenda.addView(circuloNaranja)
                
        layoutPrincipal.addView(leyenda)
                
        // Navegación de mes
        val navegacionMes = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        
        // Flecha izquierda (mes anterior) - simplificado
        val btnMesAnterior = Button(this).apply {
            text = "◀"
            textSize = 16f
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_blue_dark))
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
            
            val dp = resources.displayMetrics.density
            val widthPx = (56 * dp).toInt()  // 56dp es tamaño estándar para botones de acción
            val heightPx = (48 * dp).toInt()
            val marginPx = (8 * dp).toInt()
            
            layoutParams = LinearLayout.LayoutParams(widthPx, heightPx).apply {
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
            setPadding(0, 0, 0, 0)
        }
        
        // Título del mes - simplificado
        val txtMesAño = TextView(this).apply {
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            
            val dp = resources.displayMetrics.density
            val paddingPx = (12 * dp).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
        }
        
        // Flecha derecha (mes siguiente) - simplificado
        val btnMesSiguiente = Button(this).apply {
            text = "▶"
            textSize = 16f
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_blue_dark))
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
            
            val dp = resources.displayMetrics.density
            val widthPx = (56 * dp).toInt()  // 56dp es tamaño estándar para botones de acción
            val heightPx = (48 * dp).toInt()
            val marginPx = (8 * dp).toInt()
            
            layoutParams = LinearLayout.LayoutParams(widthPx, heightPx).apply {
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
            setPadding(0, 0, 0, 0)
        }
        
        navegacionMes.addView(btnMesAnterior)
        navegacionMes.addView(txtMesAño)
        navegacionMes.addView(btnMesSiguiente)
        layoutPrincipal.addView(navegacionMes)
        
        // Container para el calendario que se actualizará
        val calendarioContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        layoutPrincipal.addView(calendarioContainer)
        
        // Variable para guardar referencia del diálogo
        var dialogRef: AlertDialog? = null
        
        // Función para actualizar el calendario
        fun actualizarCalendario() {
            calendarioContainer.removeAllViews()
            
            // Actualizar título del mes
            txtMesAño.text = DateHelper.getDateFormat("MMMM yyyy", Locale("es", "ES")).format(mesActual.time)
            
            // Crear encabezados de días
            val encabezados = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, 8)
            }
            
            val diasSemana = arrayOf("Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb")
            diasSemana.forEach { dia ->
                val txtDia = TextView(this).apply {
                    text = dia
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(4, 4, 4, 4)
                }
                encabezados.addView(txtDia)
            }
            calendarioContainer.addView(encabezados)
            
            // Crear grid del mes
            crearGridMesUnico(calendarioContainer, mesActual, fechasCompletas, fechasParciales, dialogRef)
        }
        
        // Configurar listeners de navegación
        btnMesAnterior.setOnClickListener {
            mesActual = DateHelper.getCalendar().apply {
                time = mesActual.time
                add(Calendar.MONTH, -1)
            }
            actualizarCalendario()
        }
        
        btnMesSiguiente.setOnClickListener {
            mesActual = DateHelper.getCalendar().apply {
                time = mesActual.time
                add(Calendar.MONTH, 1)
            }
            actualizarCalendario()
        }
        
        // Mostrar calendario inicial
        actualizarCalendario()
        
        // Crear y mostrar diálogo
        val dialog = AlertDialog.Builder(this)
            .setView(layoutPrincipal)
            .setOnDismissListener {
                dialogRef = null
                calendarioDialog = null
                cargandoCalendario = false
            }
            .create()
        
        // Asignar referencia del diálogo ANTES de mostrarlo
        dialogRef = dialog
        calendarioDialog = dialog  // También guardar en variable de clase
        
        // Log información del dispositivo para debugging
        // Variables removidas - no se usaban
        
        dialog.show()
        
        
    }
    
    /**
     * Crea un grid para un mes específico con navegación
     */
    private fun crearGridMesUnico(
        container: LinearLayout,
        mesCalendario: Calendar,
        fechasCompletas: Set<String>,
        fechasParciales: Set<String>,
        dialogRef: AlertDialog?
    ) {
        val año = mesCalendario.get(Calendar.YEAR)
        val mes = mesCalendario.get(Calendar.MONTH)
        
        // Configurar calendario para el primer día del mes
        val primerDia = DateHelper.getCalendar().apply {
            set(año, mes, 1)
        }
        
        val diasEnMes = primerDia.getActualMaximum(Calendar.DAY_OF_MONTH)
        val primerDiaSemana = primerDia.get(Calendar.DAY_OF_WEEK) - 1 // 0=Domingo, 1=Lunes, etc.
        
        // Crear filas de 7 días
        var filaActual: LinearLayout? = null
        var diaEnFila = 0
        
        // Agregar espacios vacíos para los días anteriores al primer día del mes
        for (i in 0 until primerDiaSemana) {
            if (diaEnFila == 0) {
                filaActual = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER
                    setPadding(0, 4, 0, 4)
                }
                container.addView(filaActual)
            }
            
            // Espacio vacío con mismo tamaño que botones
            val espacioVacio = TextView(this).apply {
                text = ""
                val dp = resources.displayMetrics.density
                val heightDp = 48
                val marginDp = 2
                
                val heightPx = (heightDp * dp).toInt()
                val marginPx = (marginDp * dp).toInt()
                
                layoutParams = LinearLayout.LayoutParams(0, heightPx, 1f).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
            }
            filaActual?.addView(espacioVacio)
            diaEnFila++
        }
        
        // Agregar los días del mes
        for (dia in 1..diasEnMes) {
            if (diaEnFila == 0) {
                filaActual = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER
                                setPadding(0, 4, 0, 4)
                            }
                container.addView(filaActual)
            }
            
            val fechaString = String.format("%04d-%02d-%02d", año, mes + 1, dia)
            
            // Verificar si es el día actual
            val hoy = DateHelper.getCalendar()
            val esHoy = hoy.get(Calendar.YEAR) == año && 
                       hoy.get(Calendar.MONTH) == mes && 
                       hoy.get(Calendar.DAY_OF_MONTH) == dia
            
            // Verificar si es la fecha seleccionada
            val esFechaSeleccionada = fechaString == fechaSeleccionada
            
            val btnFecha = Button(this).apply {
                text = dia.toString()
                
                // Usar tamaño fijo más grande y simple
                textSize = 18f // Tamaño uniforme para todos los días
                setTypeface(null, android.graphics.Typeface.BOLD)
                
                // Determinar color según datos
                when {
                    fechasCompletas.contains(fechaString) -> {
                        setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
                        setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                    }
                    fechasParciales.contains(fechaString) -> {
                        setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_dark))
                        setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                    }
                    else -> {
                        setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                        setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                    }
                }
                
                // Agregar reborde rojo: prioridad a fecha seleccionada, sino día actual
                val debeTenerReborde = if (fechaSeleccionada.isNotEmpty() && esFechaSeleccionada) {
                    true // Si hay fecha seleccionada válida, mostrar esa
                } else if (fechaSeleccionada.isEmpty()) {
                    esHoy // Si no hay fecha seleccionada, mostrar día actual
                } else {
                    false // Si hay fecha seleccionada pero no es esta, no mostrar reborde
                }
                
                if (debeTenerReborde) {
                    val dp = resources.displayMetrics.density
                    val strokeWidthPx = (3 * dp).toInt() // 3dp de grosor
                    val strokeColor = ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark)
                    
                    // Obtener el color de fondo según el estado de los datos
                    val backgroundColor = when {
                        fechasCompletas.contains(fechaString) -> ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark)
                        fechasParciales.contains(fechaString) -> ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_dark)
                        else -> ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray)
                    }
                    
                    // Crear un drawable con reborde
                    val drawable = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(backgroundColor)
                        setStroke(strokeWidthPx, strokeColor)
                        cornerRadius = 8 * dp // Esquinas redondeadas
                    }
                    background = drawable
                }
                
                // Convertir dp a px para tamaños consistentes
                val dp = resources.displayMetrics.density
                val heightDp = 48 // 48dp es un tamaño estándar de botón en Android
                val marginDp = 2
                val paddingDp = 4
                
                val heightPx = (heightDp * dp).toInt()
                val marginPx = (marginDp * dp).toInt()
                val paddingPx = (paddingDp * dp).toInt()
                
                layoutParams = LinearLayout.LayoutParams(0, heightPx, 1f).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                
                // Padding mínimo para que el texto no se corte
                setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                
                // Centrar texto y asegurar que no se corte
                gravity = android.view.Gravity.CENTER
                includeFontPadding = false
                
                // Asegurar que el texto siempre quepa
                minHeight = heightPx
                minWidth = heightPx
                
                setOnClickListener {
                    val calendarioSeleccionado = DateHelper.getCalendar().apply {
                        set(año, mes, dia)
                    }
                    
                    Log.d("MainActivity", "🔘 Click en fecha: $fechaString")
                    
                    // Actualizar fecha seleccionada
                    actualizarFechaSeleccionada(calendarioSeleccionado)
                    
                    // Cerrar el diálogo después de seleccionar la fecha
                    Log.d("MainActivity", "✅ Fecha seleccionada, cerrando diálogo")
                    
                    try {
                        var dialogoCerrado = false
                        
                        // Intentar con dialogRef primero
                        if (dialogRef != null) {
                            Log.d("MainActivity", "🔄 Cerrando diálogo con dialogRef...")
                            dialogRef.dismiss()
                            dialogoCerrado = true
                        }
                        
                        // Intentar con calendarioDialog si el primero falló
                        if (!dialogoCerrado && calendarioDialog != null) {
                            Log.d("MainActivity", "🔄 Cerrando diálogo con calendarioDialog...")
                            calendarioDialog?.dismiss()
                            dialogoCerrado = true
                        }
                        
                        // Si ninguno funcionó, buscar en la jerarquía
                        if (!dialogoCerrado) {
                            Log.w("MainActivity", "⚠️ Ambas referencias son null, buscando diálogo alternativo")
                            var parent: Any? = (this as View).parent
                            while (parent != null) {
                                if (parent is AlertDialog) {
                                    Log.d("MainActivity", "✅ Encontrado diálogo en jerarquía, cerrando...")
                                    parent.dismiss()
                                    dialogoCerrado = true
                                    break
                                }
                                parent = if (parent is View) parent.parent else null
                            }
                        }
                        
                        if (dialogoCerrado) {
                            Log.d("MainActivity", "✅ Diálogo cerrado exitosamente")
                        } else {
                            Log.e("MainActivity", "❌ No se pudo cerrar el diálogo")
                        }
                        
                    } catch (e: Exception) {
                        Log.e("MainActivity", "❌ Error cerrando diálogo: ${e.message}")
                    }
                }
            }
            
            filaActual?.addView(btnFecha)
            diaEnFila++
            
            if (diaEnFila == 7) {
                diaEnFila = 0
            }
        }
        
        // Completar la última fila con espacios vacíos si es necesario
        while (diaEnFila != 0 && diaEnFila < 7) {
            val espacioVacio = TextView(this).apply {
                text = ""
                val dp = resources.displayMetrics.density
                val heightDp = 48
                val marginDp = 2
                
                val heightPx = (heightDp * dp).toInt()
                val marginPx = (marginDp * dp).toInt()
                
                layoutParams = LinearLayout.LayoutParams(0, heightPx, 1f).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
            }
            filaActual?.addView(espacioVacio)
            diaEnFila++
        }
    }
    
    
    /**
     * Fallback al DatePickerDialog normal
     */
    private fun mostrarDatePickerNormal() {
        val calendar = DateHelper.getCalendar()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        
        val datePickerDialog = DatePickerDialog(
            this,
            { _, selectedYear, selectedMonth, selectedDay ->
                val fechaSeleccionada = DateHelper.getCalendar().apply {
                    set(selectedYear, selectedMonth, selectedDay)
                }
                actualizarFechaSeleccionada(fechaSeleccionada)
            },
            year, month, day
        )
        
        datePickerDialog.show()
    }
    
    /**
     * Muestra un indicador de carga simple
     */
    private fun mostrarIndicadorCarga() {
        // Cancelar toast anterior si existe
        indicadorCargaToast?.cancel()
        
        indicadorCargaToast = Toast.makeText(this, "Cargando calendario...", Toast.LENGTH_SHORT)
        indicadorCargaToast?.show()
        
        // Timeout de seguridad para resetear el flag si algo falla
        layoutDateSelector.postDelayed({
            if (cargandoCalendario) {
                Log.w("MainActivity", "⚠️ Timeout del indicador de carga - reseteando flag")
                cargandoCalendario = false
                indicadorCargaToast?.cancel()
            }
        }, 10000) // 10 segundos de timeout
    }
    
    /**
     * Oculta el indicador de carga
     */
    private fun ocultarIndicadorCarga() {
        indicadorCargaToast?.cancel()
        indicadorCargaToast = null
    }
    
    /**
     * Cambia la fecha seleccionada sumando `offset` días (-1 retrocede, +1 avanza)
     */
    private fun cambiarDia(offset: Int) {
        try {
            // 🔧 FORZAR ZONA HORARIA DE ARGENTINA
            val timeZone = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            formato.timeZone = timeZone

            // Si no hay fechaSeleccionada (string vacío), usar hoy
            val calendar = if (fechaSeleccionada.isNullOrEmpty()) {
                DateHelper.getCalendar()
            } else {
                val fecha = formato.parse(fechaSeleccionada)
                if (fecha != null) DateHelper.getCalendar().apply { time = fecha } else DateHelper.getCalendar()
            }

            calendar.add(Calendar.DAY_OF_MONTH, offset)
            actualizarFechaSeleccionada(calendar)

        } catch (e: Exception) {
            Log.e("MainActivity", "Error cambiando de día: ${e.message}")
            // Fallback: usar hoy
            actualizarFechaSeleccionada(DateHelper.getCalendar())
        }
    }
    
    private fun actualizarFechaSeleccionada(calendar: Calendar) {
        // 🔧 FORZAR ZONA HORARIA DE ARGENTINA
        val timeZone = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
        val formato = DateHelper.getDateFormat("EEEE - dd/MM/yy", Locale("es", "ES"))
        formato.timeZone = timeZone
        val sdfFecha = DateHelper.getDateFormat("yyyy-MM-dd")
        sdfFecha.timeZone = timeZone
        fechaSeleccionada = sdfFecha.format(calendar.time)
        txtFechaActual.text = formato.format(calendar.time)
        
        Log.d("MainActivity", "📅 Fecha seleccionada: $fechaSeleccionada")
        
        // Resetear estados de botones al cambiar fecha para forzar actualización
        ultimoEstadoIngreso = null
        ultimoEstadoStock = null
        ultimoEstadoVencimientos = null
        ultimaActualizacion = 0L  // Resetear tiempo para permitir actualización inmediata
        
        // Forzar actualización inmediata de botones al cambiar fecha
        verificarRegistrosExistentes()
        verificarVencimientosCoinciden()
    }
    
    private fun configurarUsuario() {
        // Obtener usuario actual desde SharedPreferences
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        val localId = prefs.getString("localId", "")
        val localNombre = prefs.getString("localNombre", "")
        val usuarioId = prefs.getString("usuarioId", "")
        val usuario = prefs.getString("usuario", "")
        val rol = prefs.getString("rol", "")
        
        // Verificar si cambió el local (para limpiar cache de alertas)
        val localIdAnterior = usuarioActual?.localId
        val cambioLocal = localIdAnterior != null && localIdAnterior != localId
        
        if (!localId.isNullOrEmpty() && !localNombre.isNullOrEmpty() && !usuarioId.isNullOrEmpty() && !usuario.isNullOrEmpty()) {
            usuarioActual = UsuarioActual(localId, localNombre, usuarioId, usuario, rol ?: "")
            txtLocalActual.text = "🏪 ${usuarioActual!!.localNombre}"
            
            // Si cambió el local, limpiar cache de alertas específicamente
            if (cambioLocal) {
                Log.d("MainActivity", "🔄 Cambio de local detectado: $localIdAnterior → $localId, limpiando cache de alertas")
                cacheRegistrosAlertas = null
                cacheRegistrosAlertasTimestamp = 0
                cacheRegistrosAlertasLocalId = ""
            }
            
            // Inicializar repositorio con el localId
            repository = FirebaseInventarioRepository(usuarioActual!!.localId)
            // ✅ OPTIMIZACIÓN: NO cargar promedios aquí - se cargarán solo cuando se necesiten
            // Los promedios se cargan automáticamente cuando entras a:
            // - PedidoFabricaActivity (en cargarPromediosRealesDelLocal())
            // - VentaVolumenActivity (si los necesita)
            // Esto mejora el tiempo de inicio de la app
            // cargarPromediosReales() // Movido a carga diferida
            // NO verificar registros aquí - se hará después de verificarAlertasLocal() que se ejecuta en onCreate()
            // Esto evita verificaciones duplicadas y asegura que se ejecute después de la sincronización
            } else {
            usuarioActual = null
            txtLocalActual.text = "🏪 No seleccionado"
            // Usar un localId por defecto
            repository = FirebaseInventarioRepository("default")
        }
    }
    
    /**
     * Verifica si el dispositivo está en modo oscuro
     */
    private fun isDarkMode(): Boolean {
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    
    /**
     * Configura la marca de agua según el modo oscuro
     * En modo oscuro: se muestra difuminada pero visible (alpha = 0.3)
     * En modo claro: se muestra con transparencia (alpha = 0.08)
     */
    private fun configurarMarcaDeAgua() {
        val imgWatermark = findViewById<ImageView>(R.id.imgWatermark)
        imgWatermark?.let {
            if (isDarkMode()) {
                // Modo oscuro: mostrar difuminada pero visible
                it.alpha = 0.3f
                Log.d("MainActivity", "🌙 Modo oscuro: marca de agua difuminada (alpha=0.3)")
            } else {
                // Modo claro: mostrar con transparencia
                it.alpha = 0.08f
                Log.d("MainActivity", "☀️ Modo claro: marca de agua con transparencia (alpha=0.08)")
            }
        }
    }
    
    /**
     * Carga los promedios reales desde Firebase para toda la aplicación
     */
    /**
     * 📈 Calcula tendencias de ventas y las guarda en Firebase
     * Se ejecuta en background al iniciar la app
     */
    private fun calcularYGuardarTendencias() {
        Log.d("TENDENCIAS", "🚀 calcularYGuardarTendencias() INICIADA")
        
        if (usuarioActual == null) {
            Log.e("TENDENCIAS", "❌ usuarioActual es NULL → ABORTANDO")
            return
        }
        
        val localId = usuarioActual!!.localId
        val fechaHoy = DateHelper.getFechaActual()
        
        Log.d("TENDENCIAS", "✅ Usuario válido: $localId")
        Log.d("TENDENCIAS", "📅 Fecha hoy: $fechaHoy")
        Log.d("TENDENCIAS", "🔄 Lanzando coroutine en Dispatchers.IO...")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("TENDENCIAS", "🏁 Coroutine INICIADA en background")
                Log.d("TENDENCIAS", "📈 Iniciando cálculo de tendencias para $localId...")
                
                // 1. Intentar cargar desde Firebase primero (documento único)
                val firestore = FirebaseRegionManager.getFirestore()
                val docTendencias = firestore
                    .collection("locales")
                    .document(localId)
                    .collection("cache_tendencias")
                    .document("tendencias")  // Documento único que se sobrescribe
                    .get()
                    .await()
                
                if (docTendencias.exists()) {
                    // Verificar si ya están calculadas para HOY
                    val fechaActualizacion = docTendencias.getTimestamp("fechaActualizacion")
                    val sdfFecha = DateHelper.getDateFormat("yyyy-MM-dd")
                    val fechaDoc = if (fechaActualizacion != null) {
                        sdfFecha.format(fechaActualizacion.toDate())
                    } else {
                        ""
                    }
                    
                    if (fechaDoc == fechaHoy) {
                        // Ya están calculadas para hoy
                        @Suppress("UNCHECKED_CAST")
                        val tendenciasGuardadas = docTendencias.data?.filterKeys { it != "fechaActualizacion" } as? Map<String, Double>
                        if (tendenciasGuardadas != null && tendenciasGuardadas.isNotEmpty()) {
                            cacheTendencias = tendenciasGuardadas
                            cacheTendenciasFecha = fechaHoy
                            cacheTendenciasLocalId = localId
                            Log.d("TENDENCIAS", "✅ Tendencias cargadas desde Firebase: ${tendenciasGuardadas.size} productos (actualizado hoy)")
                            return@launch
                        }
                    } else {
                        Log.d("TENDENCIAS", "⚠️ Tendencias desactualizadas (última: $fechaDoc), recalculando para hoy ($fechaHoy)...")
                    }
                }
                
                // 2. Si no existen, calcularlas
                Log.d("TENDENCIAS", "🔄 Calculando tendencias nuevas...")
                
                // 2.1 Cargar promedios si no están disponibles
                if (!promediosCargados) {
                    Log.d("TENDENCIAS", "📊 Cargando promedios primero...")
                    try {
                        val registros: List<RegistroInventario>
                        
                        if (isPromediosCacheValid(cacheRegistrosPromediosTimestamp) && cacheRegistrosPromedios != null) {
                            registros = cacheRegistrosPromedios!!
                        } else {
                            val calendar = Calendar.getInstance()
                            calendar.add(Calendar.DAY_OF_YEAR, -60)
                            val fechaLimite = DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
                            
                            val snapshot = firestore
                                .collection("locales")
                                .document(localId)
                                .collection("registros")
                                .whereGreaterThanOrEqualTo("fecha", fechaLimite)
                                .get()
                                .await()
                            
                            registros = snapshot.documents.mapNotNull { document ->
                                @Suppress("DEPRECATION")
                                document.toObject(RegistroInventario::class.java)
                            }
                            
                            cacheRegistrosPromedios = registros
                            cacheRegistrosPromediosTimestamp = System.currentTimeMillis()
                        }
                        
                        if (registros.isNotEmpty()) {
                            val promediosPorDia = calcularPromediosPorDia(registros)
                            promediosReales = promediosPorDia
                            promediosCargados = true
                            Log.d("TENDENCIAS", "✅ Promedios cargados: ${promediosPorDia.keys.size} días")
                        }
                    } catch (e: Exception) {
                        Log.e("TENDENCIAS", "❌ Error cargando promedios: ${e.message}")
                    }
                }
                
                if (!promediosCargados) {
                    Log.w("TENDENCIAS", "⚠️ No se pudieron cargar promedios, cancelando cálculo de tendencias")
                    return@launch
                }
                
                // 2.2 Usar lista hardcodeada de productos (más rápido y confiable)
                val productos = listOf(
                    // Empanadas
                    "JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH",
                    // Pizzas
                    "MUZZARE", "JAMON", "PEPPERO",
                    // Pastelitos
                    "BATATA", "MEMBRIL",
                    // Panificadora
                    "MEDIALUNA_MANTECA", "MEDIALUNA_GRASA", "CHIPA", "CRIOLLITO"
                )
                
                Log.d("TENDENCIAS", "📦 Productos a calcular: ${productos.size}")
                
                val tendencias = mutableMapOf<String, Double>()
                
                productos.forEach { codigo ->
                    try {
                        Log.d("TENDENCIAS", "  🔄 Calculando $codigo...")
                        val tendencia = calcularTendenciaProducto(codigo, localId)
                        tendencias[codigo] = tendencia
                        if (tendencia != 0.0) {
                            Log.d("TENDENCIAS", "  ✅ $codigo: ${String.format("%.1f", tendencia)}%")
                        } else {
                            Log.d("TENDENCIAS", "  ℹ️ $codigo: 0.0% (sin tendencia)")
                        }
                    } catch (e: Exception) {
                        Log.w("TENDENCIAS", "  ⚠️ Error en $codigo: ${e.message}")
                        e.printStackTrace()
                        tendencias[codigo] = 0.0
                    }
                }
                
                Log.d("TENDENCIAS", "📊 Resumen: ${tendencias.filter { it.value != 0.0 }.size} productos con tendencia de ${tendencias.size} totales")
                
                // 3. Guardar en Firebase (documento único que se sobrescribe)
                Log.d("TENDENCIAS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.d("TENDENCIAS", "💾 GUARDANDO EN FIREBASE")
                Log.d("TENDENCIAS", "   📍 Ruta: locales/$localId/cache_tendencias/tendencias")
                Log.d("TENDENCIAS", "   📦 Total items: ${tendencias.size}")
                Log.d("TENDENCIAS", "   📈 Con tendencia: ${tendencias.filter { it.value != 0.0 }.size}")
                Log.d("TENDENCIAS", "   📊 Datos: ${tendencias.filter { it.value != 0.0 }}")
                
                // Crear mapa con tipos mixtos (Double para tendencias, Timestamp para fecha)
                val timestamp = com.google.firebase.Timestamp.now()
                val datosParaGuardar = hashMapOf<String, Any>()
                datosParaGuardar.putAll(tendencias)  // Agregar todas las tendencias
                datosParaGuardar["fechaActualizacion"] = timestamp  // Agregar timestamp real
                
                Log.d("TENDENCIAS", "   🕒 Timestamp: ${timestamp.toDate()}")
                Log.d("TENDENCIAS", "   📝 Preparando escritura a Firebase...")
                
                try {
                    Log.d("TENDENCIAS", "   ⏳ Ejecutando firestore.set()...")
                    
                    firestore
                        .collection("locales")
                        .document(localId)
                        .collection("cache_tendencias")
                        .document("tendencias")  // Documento único que se sobrescribe
                        .set(datosParaGuardar)
                        .await()
                    
                    Log.d("TENDENCIAS", "   ✅ Firebase.set() completado exitosamente!")
                    Log.d("TENDENCIAS", "✅ GUARDADO EN FIREBASE EXITOSO")
                    Log.d("TENDENCIAS", "   📅 Fecha: $fechaHoy")
                    Log.d("TENDENCIAS", "   📦 Productos: ${tendencias.size}")
                    Log.d("TENDENCIAS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                } catch (e: Exception) {
                    Log.e("TENDENCIAS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Log.e("TENDENCIAS", "❌ ERROR AL GUARDAR EN FIREBASE")
                    Log.e("TENDENCIAS", "   Mensaje: ${e.message}")
                    Log.e("TENDENCIAS", "   Tipo: ${e.javaClass.simpleName}")
                    Log.e("TENDENCIAS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    e.printStackTrace()
                }
                
                // 4. Guardar en caché local
                Log.d("TENDENCIAS", "💾 Guardando en caché local...")
                cacheTendencias = tendencias
                cacheTendenciasFecha = fechaHoy
                cacheTendenciasLocalId = localId
                Log.d("TENDENCIAS", "✅ Caché local actualizado")
                
                Log.d("TENDENCIAS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.d("TENDENCIAS", "🏁 PROCESO COMPLETADO")
                Log.d("TENDENCIAS", "   📦 Total productos: ${tendencias.size}")
                Log.d("TENDENCIAS", "   📈 Con tendencia: ${tendencias.filter { it.value != 0.0 }.size}")
                Log.d("TENDENCIAS", "   📅 Fecha: $fechaHoy")
                Log.d("TENDENCIAS", "   🏪 Local: $localId")
                Log.d("TENDENCIAS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                
            } catch (e: Exception) {
                Log.e("TENDENCIAS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.e("TENDENCIAS", "❌ ERROR GENERAL EN calcularYGuardarTendencias()")
                Log.e("TENDENCIAS", "   Mensaje: ${e.message}")
                Log.e("TENDENCIAS", "   Tipo: ${e.javaClass.simpleName}")
                Log.e("TENDENCIAS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                e.printStackTrace()
            }
        }
        
        Log.d("TENDENCIAS", "🔚 calcularYGuardarTendencias() finalizó el lanzamiento de coroutine")
    }
    
    /**
     * Calcula la tendencia de un producto específico
     */
    private suspend fun calcularTendenciaProducto(codigo: String, localId: String): Double {
        return withContext(Dispatchers.IO) {
            try {
                // Verificar que los promedios estén cargados
                if (!promediosCargados || promediosReales.isEmpty()) {
                    Log.w("TENDENCIAS", "  ⚠️ $codigo: Promedios no disponibles")
                    return@withContext 0.0
                }
                
                val firestore = FirebaseRegionManager.getFirestore()
                val fechaHoy = DateHelper.getFechaActual()
                val calendar = DateHelper.getCalendar()
                val fechas = mutableListOf<String>()
                
                for (i in 1..7) {
                    calendar.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(fechaHoy) ?: java.util.Date()
                    calendar.add(Calendar.DAY_OF_YEAR, -i)
                    fechas.add(DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time))
                }
                
                val diferencias = mutableListOf<Double>()
                
                var diasConDatos = 0
                fechas.forEach { fecha ->
                    try {
                        val cal = DateHelper.getCalendar()
                        cal.time = DateHelper.getDateFormat("yyyy-MM-dd").parse(fecha) ?: return@forEach
                        val diaSemana = cal.get(Calendar.DAY_OF_WEEK)
                        
                        // Obtener promedio histórico (buscar por código o nombre completo)
                        val nombreDia = obtenerNombreDia(diaSemana)
                        val promediosDelDia = promediosReales[nombreDia] ?: emptyMap()
                        
                        // Buscar por código o por nombre que contenga el código
                        val promedioHistorico = promediosDelDia[codigo] 
                            ?: promediosDelDia.entries.find { it.key.startsWith("$codigo ") || it.key.startsWith("$codigo -") }?.value 
                            ?: 0.0
                        
                        if (promedioHistorico <= 0) {
                            return@forEach
                        }
                        
                        // Calcular venta real
                        val fechaAnterior = obtenerFechaAnterior(fecha)
                        
                        val stockAnterior = firestore.collection("locales").document(localId)
                            .collection("registros").document("${fechaAnterior}_Stock_Final")
                            .get().await()
                            .toObject(RegistroInventario::class.java)
                            ?.productos?.find { it.nombre.startsWith("$codigo ") || it.nombre.startsWith("$codigo -") }
                            ?.cantidad ?: 0
                        
                        val ingreso = firestore.collection("locales").document(localId)
                            .collection("registros").document("${fecha}_Ingreso_de_Mercadería")
                            .get().await()
                            .toObject(RegistroInventario::class.java)
                            ?.productos?.find { it.nombre.startsWith("$codigo ") || it.nombre.startsWith("$codigo -") }
                            ?.cantidad ?: 0
                        
                        val stockFinal = firestore.collection("locales").document(localId)
                            .collection("registros").document("${fecha}_Stock_Final")
                            .get().await()
                            .toObject(RegistroInventario::class.java)
                            ?.productos?.find { it.nombre.startsWith("$codigo ") || it.nombre.startsWith("$codigo -") }
                            ?.cantidad ?: 0
                        
                        val ventaReal = stockAnterior + ingreso - stockFinal
                        if (ventaReal <= 0) return@forEach
                        
                        val diferenciaPorcentual = ((ventaReal - promedioHistorico) / promedioHistorico) * 100
                        diferencias.add(diferenciaPorcentual)
                        diasConDatos++
                        
                    } catch (e: Exception) {
                        // Silenciar errores individuales
                    }
                }
                
                Log.d("TENDENCIAS", "    📊 $codigo: $diasConDatos días con datos de ${fechas.size}")
                
                if (diferencias.isEmpty()) return@withContext 0.0
                
                val tendenciaPromedio = diferencias.average()
                if (kotlin.math.abs(tendenciaPromedio) < 5.0) return@withContext 0.0
                
                tendenciaPromedio
                
            } catch (e: Exception) {
                0.0
            }
        }
    }
    
    private fun obtenerNombreDia(diaSemana: Int): String {
        return when (diaSemana) {
            Calendar.SUNDAY -> "DOMINGO"
            Calendar.MONDAY -> "LUNES"
            Calendar.TUESDAY -> "MARTES"
            Calendar.WEDNESDAY -> "MIERCOLES"
            Calendar.THURSDAY -> "JUEVES"
            Calendar.FRIDAY -> "VIERNES"
            Calendar.SATURDAY -> "SABADO"
            else -> ""
        }
    }
    
    private fun cargarPromediosReales() {
        if (usuarioActual == null) return
        
        lifecycleScope.launch {
            try {
                Log.d("MainActivity", "🔄 Cargando promedios reales para local: ${usuarioActual!!.localId}")
                
                // ✅ OPTIMIZACIÓN: Usar cache para reducir lecturas de Firebase
                val registros: List<RegistroInventario>
                
                if (isPromediosCacheValid(cacheRegistrosPromediosTimestamp) && cacheRegistrosPromedios != null) {
                    Log.d("MainActivity", "📊 Promedios - Usando cache (ahorra lecturas de Firebase)")
                    registros = cacheRegistrosPromedios!!
                } else {
                    Log.d("MainActivity", "📊 Promedios - Cache expirado, consultando Firebase...")
                    val localId = usuarioActual!!.localId
                    val firestore = FirebaseRegionManager.getFirestore()
                    
                    // ✅ OPTIMIZACIÓN: Limitar a últimos 60 días (suficiente para promedios)
                    val calendar = Calendar.getInstance()
                    calendar.add(Calendar.DAY_OF_YEAR, -60)
                    val fechaLimite = DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
                    
                    val snapshot = firestore
                        .collection("locales")
                        .document(localId)
                        .collection("registros")
                        .whereGreaterThanOrEqualTo("fecha", fechaLimite)
                        .get()
                        .await()
                    
                    registros = snapshot.documents.mapNotNull { document ->
                        @Suppress("DEPRECATION")
                        document.toObject(RegistroInventario::class.java)
                    }
                    
                    // Guardar en cache
                    cacheRegistrosPromedios = registros
                    cacheRegistrosPromediosTimestamp = System.currentTimeMillis()
                    Log.d("MainActivity", "📊 Cache de promedios actualizado con ${registros.size} registros (últimos 60 días)")
                }
                
                if (registros.isNotEmpty()) {
                    val promediosPorDia = calcularPromediosPorDia(registros)
                    promediosReales = promediosPorDia
                    promediosCargados = true
                    Log.d("MainActivity", "✅ Promedios reales cargados: ${promediosPorDia.keys}")
                } else {
                    Log.w("MainActivity", "⚠️ No hay registros para calcular promedios")
                }
                
                // NO actualizar botones aquí - se hará después de verificarAlertasLocal()
                // Esto evita verificaciones duplicadas y asegura que se ejecute después de la sincronización
            } catch (e: Exception) {
                Log.e("MainActivity", "❌ Error cargando promedios reales: ${e.message}")
            }
        }
    }
    
    /**
     * Calcula promedios por día de la semana (copiado de PromedioVentasActivity)
     */
    private fun calcularPromediosPorDia(registros: List<RegistroInventario>): Map<String, Map<String, Double>> {
        val promediosPorDia = mutableMapOf<String, MutableMap<String, Double>>()
        val diasSemana = listOf("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO")
        
        // Inicializar estructura
        diasSemana.forEach { dia ->
            promediosPorDia[dia] = mutableMapOf()
        }
        
        // Agrupar registros por fecha
        val registrosPorFecha = registros.groupBy { it.fecha }
        
        // Calcular ventas por día
        val ventasPorDia = mutableMapOf<String, Map<String, Int>>()
        registrosPorFecha.forEach { (fecha, _) ->
            val ventas = calcularVentasDelDia(fecha, registros)
            if (ventas.isNotEmpty()) {
                val diaSemana = obtenerDiaSemana(fecha)
                ventasPorDia[diaSemana] = ventas
            }
        }
        
        // Calcular promedios por día de la semana
        diasSemana.forEach { dia ->
            // Recopilar todas las ventas de este día de la semana
            val ventasDelDia = ventasPorDia[dia] ?: emptyMap()
            if (ventasDelDia.isNotEmpty()) {
                // Para cada producto, calcular el promedio de todas las ventas de ese día
                ventasDelDia.forEach { (producto, _) ->
                    // Buscar todas las fechas que corresponden a este día de la semana
                    val fechasDelDia = registrosPorFecha.keys.filter { obtenerDiaSemana(it) == dia }
                    val ventasProducto = mutableListOf<Int>()
                    
                    // Recopilar todas las ventas de este producto en este día de la semana
                    fechasDelDia.forEach { fecha ->
                        val ventasFecha = calcularVentasDelDia(fecha, registros)
                        ventasFecha[producto]?.let { ventasProducto.add(it) }
                    }
                    
                    // Calcular promedio real de todas las ventas (solo días con ventas > 0)
                    val ventasConDatos = ventasProducto.filter { it > 0 }
                    if (ventasConDatos.isNotEmpty()) {
                        val promedioReal = ventasConDatos.sum().toDouble() / ventasConDatos.size
                        promediosPorDia[dia]?.set(producto, promedioReal)
                    } else {
                        promediosPorDia[dia]?.set(producto, 0.0)
                    }
                }
            }
        }
        
        return promediosPorDia
    }
    
    /**
     * Calcula las ventas de un día específico (copiado de PromedioVentasActivity)
     */
    private fun calcularVentasDelDia(fecha: String, todosRegistros: List<RegistroInventario>): Map<String, Int> {
        val ventas = mutableMapOf<String, Int>()
        
        // Obtener registros del día
        val registrosDelDia = todosRegistros.filter { it.fecha == fecha }
        val ingreso = registrosDelDia.filter { it.tipo == "Ingreso de Mercadería" }.flatMap { it.productos }
        val stockFinal = registrosDelDia.filter { it.tipo == "Stock Final" }.flatMap { it.productos }
        
        // Obtener stock anterior (día anterior)
        val fechaAnterior = obtenerFechaAnterior(fecha)
        val stockAnterior = todosRegistros.filter { it.fecha == fechaAnterior && it.tipo == "Stock Final" }.flatMap { it.productos }
        
        // Si no hay stock final del día actual, no podemos calcular ventas
        if (stockFinal.isEmpty()) {
            return ventas
        }
        
        // Calcular ventas para cada producto del stock final
        stockFinal.forEach { productoStock ->
            val nombreProducto = productoStock.nombre
            val ingresoCantidad = ingreso.find { it.nombre == nombreProducto }?.cantidad ?: 0
            val stockFinalCantidad = productoStock.cantidad
            val stockAnteriorCantidad = stockAnterior.find { it.nombre == nombreProducto }?.cantidad ?: 0
            
            val venta = stockAnteriorCantidad + ingresoCantidad - stockFinalCantidad
            
            if (venta > 0) {
                ventas[nombreProducto] = venta
            }
        }
        
        return ventas
    }
    
    /**
     * Obtiene el día de la semana de una fecha (copiado de PromedioVentasActivity)
     */
    private fun obtenerDiaSemana(fecha: String): String {
        return try {
            // 🔧 FORZAR ZONA HORARIA DE ARGENTINA
            val timeZone = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            formato.timeZone = timeZone
            val date = formato.parse(fecha) ?: Date()
            val calendar = DateHelper.getCalendar().apply { 
                time = date
                this.timeZone = timeZone
            }
            
            when (calendar.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SUNDAY -> "DOMINGO"
                Calendar.MONDAY -> "LUNES"
                Calendar.TUESDAY -> "MARTES"
                Calendar.WEDNESDAY -> "MIERCOLES"
                Calendar.THURSDAY -> "JUEVES"
                Calendar.FRIDAY -> "VIERNES"
                Calendar.SATURDAY -> "SABADO"
                else -> "LUNES"
            }
        } catch (e: Exception) {
            "LUNES"
        }
    }
    
    /**
     * Obtiene la fecha anterior en formato yyyy-MM-dd
     */
    private fun obtenerFechaAnterior(fecha: String): String {
        return try {
            // 🔧 FORZAR ZONA HORARIA DE ARGENTINA
            val timeZone = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
            val formato = DateHelper.getDateFormat("yyyy-MM-dd")
            formato.timeZone = timeZone
            val date = formato.parse(fecha) ?: Date()
            val calendar = DateHelper.getCalendar().apply { 
                time = date
                this.timeZone = timeZone
            }
            calendar.add(Calendar.DAY_OF_MONTH, -1)
            formato.format(calendar.time)
        } catch (e: Exception) {
            fecha
        }
    }
    
    private fun verificarRegistrosExistentes() {
        lifecycleScope.launch {
            try {
                val localId = obtenerLocalId()
                
                // ✅ CONSULTAR FIREBASE DIRECTAMENTE PRIMERO (como en la web)
                // Esto es mucho más rápido y confiable que esperar a que Room se sincronice
                val firestore = FirebaseRegionManager.getFirestore()
                val registrosCollection = firestore.collection("locales").document(localId).collection("registros")
                
                // Consultar Firebase directamente para la fecha seleccionada
                val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    registrosCollection
                        .whereEqualTo("fecha", fechaSeleccionada)
                        .get()
                        .await()
                }
                
                val registrosFirebase = snapshot.documents.mapNotNull { doc ->
                    doc.getString("tipo")
                }
                
                val tieneIngreso = registrosFirebase.contains("Ingreso de Mercadería")
                val tieneStock = registrosFirebase.contains("Stock Final")
                
                // Log para debugging
                Log.d("MainActivity", "🔍 Verificando registros en Firebase - Ingreso: $tieneIngreso, Stock: $tieneStock (Fecha: $fechaSeleccionada)")
                
                // Crear lista mínima solo para actualizar botones (compatible con actualizarIndicadoresBotones)
                val registrosFecha = mutableListOf<RegistroInventario>()
                if (tieneIngreso) {
                    registrosFecha.add(RegistroInventario(fechaSeleccionada, "Ingreso de Mercadería", emptyList()))
                }
                if (tieneStock) {
                    registrosFecha.add(RegistroInventario(fechaSeleccionada, "Stock Final", emptyList()))
                }
                
                actualizarIndicadoresBotones(registrosFecha)
                
                // También verificar en Room como fallback (en segundo plano)
                // Esto asegura que si Firebase falla, al menos tenemos los datos locales
                try {
                    val localRepository = (application as InventarioApplication).localRepository
                    val tieneIngresoLocal = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        localRepository.existeRegistro(localId, fechaSeleccionada, "Ingreso de Mercadería")
                    }
                    val tieneStockLocal = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        localRepository.existeRegistro(localId, fechaSeleccionada, "Stock Final")
                    }
                    
                    // Si Room tiene datos que Firebase no tiene, actualizar botones con Room
                    if ((tieneIngresoLocal && !tieneIngreso) || (tieneStockLocal && !tieneStock)) {
                        Log.d("MainActivity", "🔍 Actualizando desde Room (fallback) - Ingreso: $tieneIngresoLocal, Stock: $tieneStockLocal")
                        val registrosFechaLocal = mutableListOf<RegistroInventario>()
                        if (tieneIngresoLocal) {
                            registrosFechaLocal.add(RegistroInventario(fechaSeleccionada, "Ingreso de Mercadería", emptyList()))
                        }
                        if (tieneStockLocal) {
                            registrosFechaLocal.add(RegistroInventario(fechaSeleccionada, "Stock Final", emptyList()))
                        }
                        actualizarIndicadoresBotones(registrosFechaLocal)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error verificando en Room (fallback)", e)
                }
                
            } catch (e: Exception) {
                Log.e("MainActivity", "Error verificando registros existentes en Firebase", e)
                
                // Fallback: intentar con Room si Firebase falla
                try {
                    val localId = obtenerLocalId()
                    val localRepository = (application as InventarioApplication).localRepository
                    
                    val tieneIngreso = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        localRepository.existeRegistro(localId, fechaSeleccionada, "Ingreso de Mercadería")
                    }
                    val tieneStock = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        localRepository.existeRegistro(localId, fechaSeleccionada, "Stock Final")
                    }
                    
                    Log.d("MainActivity", "🔍 Fallback a Room - Ingreso: $tieneIngreso, Stock: $tieneStock")
                    
                    val registrosFecha = mutableListOf<RegistroInventario>()
                    if (tieneIngreso) {
                        registrosFecha.add(RegistroInventario(fechaSeleccionada, "Ingreso de Mercadería", emptyList()))
                    }
                    if (tieneStock) {
                        registrosFecha.add(RegistroInventario(fechaSeleccionada, "Stock Final", emptyList()))
                    }
                    
                    actualizarIndicadoresBotones(registrosFecha)
                } catch (e2: Exception) {
                    Log.e("MainActivity", "Error en fallback a Room", e2)
                }
            }
        }
    }
    
    /**
     * Fuerza la sincronización con Firebase para obtener los datos más actualizados
     */
    private suspend fun forzarSincronizacionFirebase() {
        try {
            // Log reducido - solo en caso de error
            // Log.d("MainActivity", "🔄 Forzando sincronización con Firebase...")
            
            // Verificar si hay usuario logueado
            if (usuarioActual != null) {
                val firebaseRepo = FirebaseInventarioRepository(usuarioActual!!.localId)
                val localRepo = (application as InventarioApplication).localRepository
                
                // Forzar sincronización completa desde Firebase
                firebaseRepo.forzarSincronizacionDesdeFirebase(localRepo)
                
                // Log reducido
                // Log.d("MainActivity", "✅ Sincronización forzada completada")
            }
            
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Error en sincronización forzada", e)
            throw e // Re-lanzar para que el llamador pueda manejar el error
        }
    }
    
    private fun obtenerLocalId(): String {
        return usuarioActual?.localId ?: "default"
    }
    
    private fun programarNotificaciones() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
                    } else {
            cargarConfiguracionYProgramarAlarmas()
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            cargarConfiguracionYProgramarAlarmas()
        }
    }
    
    private fun cargarConfiguracionYProgramarAlarmas() {
        usuarioActual?.let { usuario ->
            lifecycleScope.launch {
                try {
                    Log.d("MainActivity", "🔄 Cargando configuración desde Firebase para programar alarmas...")
                    
                    val firestore = com.tuapp.inventario.utils.FirebaseRegionManager.getFirestore()
                    firestore.collection("locales")
                        .document(usuario.localId)
                        .collection("configuracion_pedido_fabrica")
                        .document("config")
                        .get()
                        .addOnSuccessListener { document ->
                            val horaAlerta = if (document.exists()) {
                                document.getString("horaAlerta") ?: "15:30"
                            } else {
                                "15:30" // Valor por defecto
                            }
                            
                            Log.d("MainActivity", "✅ Configuración cargada. Hora de alerta: $horaAlerta")
                            
                            // Programar las notificaciones con la hora configurada
                            alarmScheduler.scheduleAllReminders(horaAlerta)
                        }
                        .addOnFailureListener { e ->
                            Log.e("MainActivity", "❌ Error cargando configuración, usando hora por defecto", e)
                            // Si falla, usar hora por defecto
                            alarmScheduler.scheduleAllReminders("15:30")
            }
            
        } catch (e: Exception) {
                    Log.e("MainActivity", "Error cargando configuración para alarmas", e)
                    // Si hay error, usar hora por defecto
                    alarmScheduler.scheduleAllReminders("15:30")
                }
            }
        } ?: run {
            Log.w("MainActivity", "⚠️ No hay usuario actual, usando configuración por defecto")
            alarmScheduler.scheduleAllReminders("15:30")
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_pedidos, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_pedidos -> {
                mostrarMenuPedidos()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    
    private fun mostrarMenuPedidos() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_menu_pedidos, null)
        
        val btnPedidoFabrica = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnPedidoFabrica)
        val btnPedidoBebidas = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnPedidoBebidas)
        val btnPedidoPapeleria = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnPedidoPapeleria)
        val btnPedidoPanificadora = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnPedidoPanificadora)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        // Personalizar el diálogo para que se vea mejor
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        btnPedidoFabrica.setOnClickListener {
            dialog.dismiss()
            abrirPedidoFabrica()
        }
        
        btnPedidoBebidas.setOnClickListener {
            dialog.dismiss()
            abrirPedidoBebidas()
        }
        
        btnPedidoPapeleria.setOnClickListener {
            dialog.dismiss()
            abrirPedidoPapelera()
        }
        
        btnPedidoPanificadora.setOnClickListener {
            dialog.dismiss()
            abrirPedidoPanificadora()
        }
        
        dialog.show()
    }
    
    private fun abrirPedidoFabrica() {
        val intent = Intent(this, PedidoFabricaActivity::class.java)
        intent.putExtra("fechaSeleccionada", fechaSeleccionada) // yyyy-MM-dd
        
        // 🔧 MODO ADMINISTRADOR SOLO SI SE USÓ CONTRASEÑA ADMIN123
        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        val metodoLogin = prefs.getString("metodoLogin", "")
        val esModoAdmin = metodoLogin == "admin123"
        intent.putExtra("modoAdmin", esModoAdmin)
        
        Log.d("ADMIN_FLOW", "🔧 DIAGNÓSTICO MODO ADMIN:")
        Log.d("ADMIN_FLOW", "🔧 métodoLogin guardado: '$metodoLogin'")
        Log.d("ADMIN_FLOW", "🔧 Comparación con 'admin123': ${metodoLogin == "admin123"}")
        Log.d("ADMIN_FLOW", "🔧 Modo admin resultante: $esModoAdmin")
        startActivity(intent)
    }
    
    private fun abrirPedidoPanificadora() {
        val intent = Intent(this, PedidoPanificadoraActivity::class.java)
        intent.putExtra("fechaSeleccionada", fechaSeleccionada) // yyyy-MM-dd

        val prefs = getSharedPreferences("usuario_actual", MODE_PRIVATE)
        val metodoLogin = prefs.getString("metodoLogin", "")
        val esModoAdmin = metodoLogin == "admin123"
        intent.putExtra("modoAdmin", esModoAdmin)

        Log.d("ADMIN_FLOW", "🔧 DIAGNÓSTICO MODO ADMIN (Panificadora):")
        Log.d("ADMIN_FLOW", "🔧 métodoLogin guardado: '$metodoLogin'")
        Log.d("ADMIN_FLOW", "🔧 Comparación con 'admin123': ${metodoLogin == "admin123"}")
        Log.d("ADMIN_FLOW", "🔧 Modo admin resultante: $esModoAdmin")
        startActivity(intent)
    }
    
    private fun abrirPedidoBebidas() {
        mostrarMenuBebidas()
    }
    
    private fun mostrarMenuBebidas() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_menu_bebidas, null)
        
        val btnStockBebidas = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnStockBebidas)
        val btnPedidoBebidas = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnPedidoBebidas)
        val btnResumenBebidas = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnResumenBebidas)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        // Personalizar el diálogo para que se vea mejor
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        btnStockBebidas.setOnClickListener {
            dialog.dismiss()
            abrirStockBebidas()
        }
        
        btnPedidoBebidas.setOnClickListener {
            dialog.dismiss()
            abrirPedidoBebidasDirecto()
        }
        
        btnResumenBebidas.setOnClickListener {
            dialog.dismiss()
            abrirResumenBebidas()
        }
        
        dialog.show()
    }
    
    private fun abrirStockBebidas() {
        val intent = Intent(this, StockBebidasActivity::class.java)
        intent.putExtra("fechaSeleccionada", fechaSeleccionada)
        startActivity(intent)
    }
    
    private fun abrirPedidoBebidasDirecto() {
        val intent = Intent(this, PedidoBebidasActivity::class.java)
        intent.putExtra("fechaSeleccionada", fechaSeleccionada)
        startActivity(intent)
    }
    
    private fun abrirResumenBebidas() {
        val intent = Intent(this, ResumenBebidasActivity::class.java)
        intent.putExtra("fechaSeleccionada", fechaSeleccionada)
        startActivity(intent)
    }
    
    private fun abrirPedidoPapelera() {
        mostrarMenuPapeleria()
    }
    
    private fun mostrarMenuPapeleria() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_menu_papeleria, null)
        
        val btnStockPapeleria = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnStockPapeleria)
        val btnPedidoPapeleria = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnPedidoPapeleria)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        // Personalizar el diálogo para que se vea mejor
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        btnStockPapeleria.setOnClickListener {
            dialog.dismiss()
            abrirStockPapeleria()
        }
        
        btnPedidoPapeleria.setOnClickListener {
            dialog.dismiss()
            abrirPedidoPapeleria()
        }
        
        dialog.show()
    }
    
    private fun abrirStockPapeleria() {
        val intent = Intent(this, StockPapeleriaActivity::class.java)
        intent.putExtra("fechaSeleccionada", fechaSeleccionada)
        startActivity(intent)
    }
    
    private fun abrirPedidoPapeleria() {
        val intent = Intent(this, PedidoPapeleriaActivity::class.java)
        intent.putExtra("fechaSeleccionada", fechaSeleccionada)
        startActivity(intent)
    }
    
    private fun esFechaActual(fecha: String): Boolean {
        if (fecha.isEmpty()) return true // Si no hay fecha seleccionada, asumir día actual
        
        // 🔧 FORZAR ZONA HORARIA DE ARGENTINA
        val timeZone = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
        val formato = DateHelper.getDateFormat("yyyy-MM-dd")
        formato.timeZone = timeZone
        val fechaActual = DateHelper.getCalendar()
        fechaActual.timeZone = timeZone
        val fechaFormateada = formato.format(fechaActual.time)
        
        return fecha == fechaFormateada
    }
    
    private fun actualizarIndicadoresBotones(registros: List<RegistroInventario>) {
        // Log reducido - solo información esencial
        // Log.d("MainActivity", "🚀 INICIANDO actualizarIndicadoresBotones - Registros recibidos: ${registros.size}")
        runOnUiThread {
            val currentTime = System.currentTimeMillis()
            
            // Evitar actualizaciones simultáneas o muy frecuentes
            if (actualizandoBotones) {
                Log.d("MainActivity", "Actualización de botones ya en progreso, omitiendo...")
                return@runOnUiThread
            }
            
            // Evitar actualizaciones muy frecuentes (menos de 50ms)
            // Reducido a 50ms para permitir actualizaciones más frecuentes cuando sea necesario
            // pero aún así evitar actualizaciones duplicadas casi simultáneas
            if (currentTime - ultimaActualizacion < 50) {
                Log.d("MainActivity", "Actualización muy frecuente, omitiendo...")
                return@runOnUiThread
            }
            
            ultimaActualizacion = currentTime
            
            actualizandoBotones = true
            
            try {
                // Log reducido - solo cambios importantes
                // Log.d("MainActivity", "🔄 INICIANDO actualización de botones - Registros: ${registros.size}")
                
                // Verificar si hay registros de Ingreso de Mercadería
                val tieneIngreso = registros.any { it.tipo == "Ingreso de Mercadería" }
                
                // Verificar si hay registros de Stock Final
                val tieneStock = registros.any { it.tipo == "Stock Final" }
                
                // Verificar si estamos en el día actual
                val esDiaActual = esFechaActual(fechaSeleccionada)
                
                // Actualizar botón de Ingreso si el estado ha cambiado
                if (ultimoEstadoIngreso != tieneIngreso) {
                    ultimoEstadoIngreso = tieneIngreso
                    // Log solo cuando hay cambio
                    Log.d("MainActivity", "🔄 Botón Ingreso actualizado: ${if (tieneIngreso) "✅" else "⏳"}")
                    if (tieneIngreso) {
                        btnIngreso.text = "✅ Ingreso de Mercadería"
                        // Actualizar inmediatamente en el hilo UI
                        val colorStateList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.status_success))
                        btnIngreso.backgroundTintList = colorStateList
                        btnIngreso.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.brand_black))
                    } else {
                        btnIngreso.text = "Ingreso de Mercadería"
                        // Actualizar inmediatamente en el hilo UI
                        val colorRes = if (esDiaActual) R.color.brand_gold else R.color.brand_gold
                        val textColorRes = if (esDiaActual) R.color.brand_black else R.color.brand_black
                        
                        val colorStateList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, colorRes))
                        btnIngreso.backgroundTintList = colorStateList
                        btnIngreso.setTextColor(ContextCompat.getColor(this@MainActivity, textColorRes))
                    }
                    // Forzar actualización visual
                    btnIngreso.invalidate()
                    btnIngreso.requestLayout()
                }
                
                // Actualizar botón de Stock si el estado ha cambiado
                if (ultimoEstadoStock != tieneStock) {
                    ultimoEstadoStock = tieneStock
                    // Log solo cuando hay cambio
                    Log.d("MainActivity", "🔄 Botón Stock actualizado: ${if (tieneStock) "✅" else "⏳"}")
                    if (tieneStock) {
                        btnStock.text = "✅ Stock Final"
                        // Actualizar inmediatamente en el hilo UI
                        val colorStateList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.status_success))
                        btnStock.backgroundTintList = colorStateList
                        btnStock.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.brand_black))
                    } else {
                        btnStock.text = "Stock Final"
                        // Actualizar inmediatamente en el hilo UI
                        val colorRes = if (esDiaActual) R.color.brand_gold else R.color.brand_gold
                        val textColorRes = if (esDiaActual) R.color.brand_black else R.color.brand_black
                        
                        val colorStateList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, colorRes))
                        btnStock.backgroundTintList = colorStateList
                        btnStock.setTextColor(ContextCompat.getColor(this@MainActivity, textColorRes))
                    }
                    // Forzar actualización visual
                    btnStock.invalidate()
                    btnStock.requestLayout()
                }
                
                // Verificar vencimientos y actualizar botón
                verificarVencimientosCoinciden()
                
            } finally {
                actualizandoBotones = false
            }
        }
    }
    
    /**
     * Verifica si el stock total coincide con los lotes cargados
     * y actualiza el botón de Vencimientos en consecuencia
     */
    private fun verificarVencimientosCoinciden() {
        // Evitar verificaciones simultáneas
        if (verificacionVencimientosEnProgreso) {
            Log.d("MainActivity", "⏭️ Verificación de vencimientos ya en progreso, omitiendo...")
            return
        }
        
        verificacionVencimientosEnProgreso = true
        
        lifecycleScope.launch {
            try {
                val localId = obtenerLocalId()
                if (localId.isEmpty()) {
                    verificacionVencimientosEnProgreso = false
                    return@launch
                }
                
                // Obtener stock actual (usando la misma lógica que VencimientosActivity)
                val stockActual = calcularStockActualParaVencimientos(localId)
                
                Log.d("MainActivity", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.d("MainActivity", "🔍 VERIFICACIÓN VENCIMIENTOS - Stock cargado:")
                stockActual.forEach { (codigo, cantidad) ->
                    Log.d("MainActivity", "  - $codigo: $cantidad uds")
                }
                Log.d("MainActivity", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                
                // Obtener lotes activos
                val vencimientoRepository = VencimientoRepository()
                val lotesActivos = vencimientoRepository.obtenerLotesActivos(localId)
                
                Log.d("MainActivity", "🔍 VERIFICACIÓN VENCIMIENTOS - Lotes cargados:")
                lotesActivos.forEach { lote ->
                    Log.d("MainActivity", "  - ${lote.productoCodigo} (${lote.productoNombre}): ${lote.cantidad} uds → ${lote.fechaVencimiento}")
                }
                Log.d("MainActivity", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                
                // Categorías de productos con control de vencimientos (mismo que VencimientosActivity)
                val categorias = mapOf(
                    "EMPANADAS" to listOf("JQ", "PB", "CS", "PO", "CP", "HU", "ES", "CQ", "RJ", "QC", "CB", "CH"),
                    "PIZZAS" to listOf("MUZZA", "JAMON", "PEPPER"),
                    "PASTELITOS" to listOf("BATATA", "MEMBRIL")
                )
                
                // Normalizar códigos de stock
                val stockActualNormalizado = stockActual.mapKeys { (codigo, _) ->
                    normalizarCodigoProducto(codigo)
                }
                
                // Agrupar lotes por producto (normalizar códigos)
                val lotesPorProducto = lotesActivos.groupBy { normalizarCodigoProducto(it.productoCodigo) }
                
                // Obtener todos los códigos de productos que SÍ están en el control de vencimientos
                val codigosConControlVencimientos = categorias.values.flatten().map { normalizarCodigoProducto(it) }.toSet()
                
                // Verificar que todos los productos con stock tengan lotes y que la suma coincida
                val productosConProblemas = mutableListOf<String>()
                
                // Filtrar solo productos que están en el control de vencimientos
                val productosConControl = stockActualNormalizado.filter { (codigoNormalizado, _) ->
                    codigoNormalizado in codigosConControlVencimientos
                }
                
                Log.d("MainActivity", "🔍 Productos con control de vencimientos: ${productosConControl.size}")
                productosConControl.forEach { (codigo, stock) ->
                    Log.d("MainActivity", "  - $codigo: stock=$stock")
                }
                
                // Verificar que todos los productos con stock tengan lotes y que la suma coincida
                val todosTienenLotes = if (productosConControl.isEmpty()) {
                    // Si no hay productos con stock, verificar si hay lotes cargados (esto sería un problema)
                    val hayLotesSinStock = lotesActivos.any { lote ->
                        val codigoNormalizado = normalizarCodigoProducto(lote.productoCodigo)
                        codigoNormalizado in codigosConControlVencimientos && 
                        (stockActualNormalizado[codigoNormalizado] ?: 0) == 0
                    }
                    
                    if (hayLotesSinStock) {
                        Log.d("MainActivity", "⚠️ Hay lotes cargados pero NO hay stock para esos productos")
                        productosConProblemas.add("Hay lotes cargados pero no hay stock")
                        false // Hay problema: lotes sin stock
                    } else {
                        Log.d("MainActivity", "✅ No hay productos con stock en control de vencimientos y no hay lotes sin stock")
                        true // No hay problema: no hay stock ni lotes
                    }
                } else {
                    productosConControl.all { (codigoNormalizado, stock) ->
                        if (stock == 0) {
                            // Si no hay stock pero hay lotes, es un problema
                            val lotesProducto = lotesPorProducto[codigoNormalizado] ?: emptyList()
                            if (lotesProducto.isNotEmpty()) {
                                productosConProblemas.add("$codigoNormalizado: tiene lotes (${lotesProducto.sumOf { it.cantidad }}) pero NO tiene stock")
                                Log.d("MainActivity", "❌ $codigoNormalizado: stock=0, lotes=${lotesProducto.sumOf { it.cantidad }} (lotes sin stock)")
                                false
                            } else {
                                true // No hay stock ni lotes, no es problema
                            }
                        } else {
                            val lotesProducto = lotesPorProducto[codigoNormalizado] ?: emptyList()
                            val sumaLotes = lotesProducto.sumOf { it.cantidad }
                            // Verificar que tenga lotes y que la suma coincida exactamente con el stock
                            val tieneLotes = lotesProducto.isNotEmpty()
                            val sumaCoincide = sumaLotes == stock
                            
                            if (!tieneLotes) {
                                productosConProblemas.add("$codigoNormalizado: tiene stock ($stock) pero NO tiene lotes")
                                Log.d("MainActivity", "❌ $codigoNormalizado: stock=$stock, lotes=0 (sin lotes)")
                            } else if (!sumaCoincide) {
                                productosConProblemas.add("$codigoNormalizado: stock=$stock, lotes=$sumaLotes, diferencia=${stock - sumaLotes}")
                                Log.d("MainActivity", "❌ $codigoNormalizado: stock=$stock, lotes=$sumaLotes, diferencia=${stock - sumaLotes}")
                            } else {
                                Log.d("MainActivity", "✅ $codigoNormalizado: stock=$stock, lotes=$sumaLotes (coincide)")
                            }
                            
                            tieneLotes && sumaCoincide
                        }
                    }
                }
                
                // Verificar si hay productos con lotes (para mostrar verde aunque no haya stock)
                val hayProductosConLotes = categorias.values.any { codigosCategoria ->
                    lotesActivos.any { normalizarCodigoProducto(it.productoCodigo) in codigosCategoria.map { normalizarCodigoProducto(it) } }
                }
                
                // CRÍTICO: Verificar que los datos de stock estén cargados
                // Si stockActual está vacío, significa que los datos aún no se cargaron desde Room
                val datosStockCargados = stockActual.isNotEmpty()
                
                // El botón está verde SOLO si:
                // 1. Los datos de stock están cargados (datosStockCargados == true)
                // 2. Todos los productos con stock tienen lotes Y la suma coincide exactamente, Y
                // 3. NO hay productos con problemas (sin lotes o diferencias)
                // 4. Hay productos con stock en control (no puede estar verde si no hay stock para verificar)
                val hayProblemas = !datosStockCargados || productosConProblemas.isNotEmpty() || !todosTienenLotes
                // CRÍTICO: Solo puede estar verde si:
                // - Los datos están cargados (datosStockCargados == true)
                // - Hay productos con stock Y todos tienen lotes correctos
                // Si no hay productos con stock, el botón NO puede estar verde (no hay nada que verificar)
                val coincide = datosStockCargados && !hayProblemas && productosConControl.isNotEmpty() && todosTienenLotes
                
                Log.d("MainActivity", "🔍 Verificación Vencimientos:")
                Log.d("MainActivity", "  - Datos stock cargados: $datosStockCargados")
                Log.d("MainActivity", "  - Productos con stock: ${stockActualNormalizado.size}")
                Log.d("MainActivity", "  - Lotes activos: ${lotesActivos.size}")
                Log.d("MainActivity", "  - Todos tienen lotes y coinciden: $todosTienenLotes")
                Log.d("MainActivity", "  - Hay productos con lotes: $hayProductosConLotes")
                Log.d("MainActivity", "  - Hay problemas: $hayProblemas")
                Log.d("MainActivity", "  - Coincide (botón verde): $coincide")
                if (productosConProblemas.isNotEmpty()) {
                    Log.d("MainActivity", "  - Productos con problemas:")
                    productosConProblemas.forEach { Log.d("MainActivity", "    * $it") }
                }
                
                // Actualizar botón en UI
                runOnUiThread {
                    // Siempre actualizar el botón (no solo si cambió) para asegurar que se actualice al volver de otras actividades
                    ultimoEstadoVencimientos = coincide
                    Log.d("MainActivity", "🔄 Botón Vencimientos actualizado: ${if (coincide) "✅ VERDE" else "⏳ DORADO"} (datosStockCargados=$datosStockCargados, todosTienenLotes=$todosTienenLotes, hayStock=${stockActualNormalizado.isNotEmpty()}, hayLotes=$hayProductosConLotes, hayProblemas=$hayProblemas)")
                    
                    if (coincide) {
                        btnVencimientos.text = "✅ Vencimientos"
                        val colorStateList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.status_success))
                        btnVencimientos.backgroundTintList = colorStateList
                        btnVencimientos.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.brand_black))
                    } else {
                        btnVencimientos.text = "Vencimientos"
                        val esDiaActual = esFechaActual(fechaSeleccionada)
                        val colorRes = if (esDiaActual) R.color.brand_gold else R.color.brand_gold
                        val textColorRes = if (esDiaActual) R.color.brand_black else R.color.brand_black
                        
                        val colorStateList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, colorRes))
                        btnVencimientos.backgroundTintList = colorStateList
                        btnVencimientos.setTextColor(ContextCompat.getColor(this@MainActivity, textColorRes))
                    }
                    
                    // Forzar actualización visual
                    btnVencimientos.invalidate()
                    btnVencimientos.requestLayout()
                }
                
            } catch (e: Exception) {
                Log.e("MainActivity", "Error verificando vencimientos: ${e.message}", e)
            } finally {
                verificacionVencimientosEnProgreso = false
            }
        }
    }
    
    /**
     * Calcula el stock actual para verificación de vencimientos (misma lógica que VencimientosActivity)
     */
    /**
     * Calcula el stock actual desde Firebase directamente (siempre consulta Firebase primero)
     */
    private suspend fun calcularStockActualParaVencimientos(localId: String): Map<String, Int> {
        return calcularStockActualDesdeFirebase(localId)
    }
    
    /**
     * Calcula el stock actual desde Firebase directamente (usa cache para evitar consultas duplicadas)
     */
    private suspend fun calcularStockActualDesdeFirebase(localId: String): Map<String, Int> {
        // Verificar cache primero
        if (cacheStockCalculado != null && 
            isStockCacheValid(cacheStockCalculadoTimestamp, localId)) {
            Log.d("MainActivity", "📦 Usando cache de stock calculado (ahorra lecturas Firebase)")
            return cacheStockCalculado!!
        }
        
        return try {
            val firebaseRepo = FirebaseInventarioRepository(localId)
            val fechaHoy = DateHelper.getFechaActual()
            val fechaAyer = obtenerFechaAnterior(fechaHoy)
            
            Log.d("MainActivity", "🔍 Obteniendo stock desde Firebase (cache expirado o no existe):")
            Log.d("MainActivity", "   Fecha hoy: $fechaHoy")
            Log.d("MainActivity", "   Fecha ayer: $fechaAyer")
            Log.d("MainActivity", "   LocalId: $localId")
            
            // Obtener registros de Firebase directamente (sin depender de Room)
            val registrosAyer = try {
                kotlinx.coroutines.withTimeoutOrNull(10000) { // 10 segundos timeout
                    firebaseRepo.getRegistrosByFecha(fechaAyer).first()
                } ?: run {
                    Log.w("MainActivity", "   ⚠️ Timeout obteniendo registros de ayer")
                    emptyList()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("MainActivity", "   ℹ️ Flow cancelado (normal)")
                emptyList()
            } catch (e: Exception) {
                Log.w("MainActivity", "   ⚠️ Error obteniendo registros de ayer desde Firebase: ${e.message}")
                emptyList()
            }
            
            val registrosHoy = try {
                kotlinx.coroutines.withTimeoutOrNull(10000) { // 10 segundos timeout
                    firebaseRepo.getRegistrosByFecha(fechaHoy).first()
                } ?: run {
                    Log.w("MainActivity", "   ⚠️ Timeout obteniendo registros de hoy")
                    emptyList()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("MainActivity", "   ℹ️ Flow cancelado (normal)")
                emptyList()
            } catch (e: Exception) {
                Log.w("MainActivity", "   ⚠️ Error obteniendo registros de hoy desde Firebase: ${e.message}")
                emptyList()
            }
            
            val registrosFirebase = registrosAyer + registrosHoy
            
            Log.d("MainActivity", "   ✅ Registros obtenidos de Firebase: ${registrosFirebase.size}")
            
            // PRIORIDAD 1: Stock Final de HOY (si existe)
            val stockFinalHoy = registrosFirebase
                .filter { registro -> registro.fecha == fechaHoy && registro.tipo == "Stock Final" }
                .flatMap { registro -> registro.productos }
            
            if (stockFinalHoy.isNotEmpty()) {
                val stockMap = mutableMapOf<String, Int>()
                stockFinalHoy.forEach { producto ->
                    val codigo = obtenerCodigoProducto(producto.nombre)
                    stockMap[codigo] = producto.cantidad
                }
                Log.d("MainActivity", "   ✅ Usando Stock Final de HOY desde Firebase: ${stockMap.size} productos")
                
                // Guardar en cache
                cacheStockCalculado = stockMap
                cacheStockCalculadoTimestamp = System.currentTimeMillis()
                cacheStockCalculadoLocalId = localId
                
                return stockMap
            }
            
            // PRIORIDAD 2: Stock Final Ayer + Ingreso Hoy
            val stockAyer = registrosFirebase
                .filter { registro -> registro.fecha == fechaAyer && registro.tipo == "Stock Final" }
                .flatMap { registro -> registro.productos }
            
            val stockMap = mutableMapOf<String, Int>()
            stockAyer.forEach { producto ->
                val codigo = obtenerCodigoProducto(producto.nombre)
                stockMap[codigo] = producto.cantidad
            }
            
            val ingresoHoy = registrosFirebase
                .filter { registro -> registro.fecha == fechaHoy && registro.tipo == "Ingreso de Mercadería" }
                .flatMap { registro -> registro.productos }
            
            ingresoHoy.forEach { producto ->
                val codigo = obtenerCodigoProducto(producto.nombre)
                val cantidadAnterior = stockMap[codigo] ?: 0
                stockMap[codigo] = cantidadAnterior + producto.cantidad
            }
            
            Log.d("MainActivity", "   ✅ Stock calculado desde Firebase (Ayer + Ingreso Hoy): ${stockMap.size} productos")
            
            // Guardar en cache
            cacheStockCalculado = stockMap
            cacheStockCalculadoTimestamp = System.currentTimeMillis()
            cacheStockCalculadoLocalId = localId
            
            stockMap
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Error obteniendo stock de Firebase: ${e.message}", e)
            emptyMap()
        }
    }
    
    /**
     * Obtiene el código del producto desde su nombre (misma lógica que VencimientosActivity)
     */
    private fun obtenerCodigoProducto(nombre: String): String {
        val codigo = when {
            nombre.contains(" - ") -> nombre.split(" - ")[0].trim()
            nombre.startsWith("Pizza ") -> {
                when {
                    nombre.contains("Muzzarella") -> "MUZZA"
                    nombre.contains("Jamón") -> "JAMON"
                    nombre.contains("Pepperoni") -> "PEPPER"
                    else -> nombre
                }
            }
            nombre.startsWith("Pastelito ") -> {
                when {
                    nombre.contains("Batata") -> "BATATA"
                    nombre.contains("Membrillo") -> "MEMBRIL"
                    else -> nombre
                }
            }
            else -> nombre
        }
        return normalizarCodigoProducto(codigo)
    }
    
    /**
     * Normaliza el código del producto (misma lógica que VencimientosActivity)
     */
    private fun normalizarCodigoProducto(codigo: String): String {
        return when (codigo) {
            "PEPPERO" -> "PEPPER"
            "MUZZARE" -> "MUZZA"
            else -> codigo
        }
    }
    
    /**
     * Verifica si hay alertas para el local actual
     */
    private fun verificarAlertasLocal() {
        if (usuarioActual == null) {
            Log.d("MainActivity", "⚠️ verificarAlertasLocal: usuarioActual es null, saliendo")
            return
        }
        
        Log.d("MainActivity", "🚀 verificarAlertasLocal: Iniciando verificación para local ${usuarioActual!!.localId}")
        
        lifecycleScope.launch {
            try {
                val problemas = mutableListOf<String>()
                
                // Obtener la fecha de hoy
                val formatoFecha = DateHelper.getDateFormat("yyyy-MM-dd")
                val fechaHoy = formatoFecha.format(Date())
                
                Log.d("MainActivity", "🔍 verificarAlertasLocal: Verificando registros para fecha: $fechaHoy")
                
                // Verificar si es un local nuevo (sin registros históricos)
                val firestore = FirebaseRegionManager.getFirestore()
                val snapshotTotal = firestore
                    .collection("locales")
                    .document(usuarioActual!!.localId)
                    .collection("registros")
                    .get()
                    .await()
                
                Log.d("MainActivity", "📊 verificarAlertasLocal: Total de registros encontrados: ${snapshotTotal.size()}")
                
                // Actualizar UI en el hilo principal
                runOnUiThread {
                    if (snapshotTotal.isEmpty) {
                        Log.d("MainActivity", "🏪 verificarAlertasLocal: Local nuevo sin registros, mostrando bienvenida")
                        // Local nuevo - mostrar mensaje de bienvenida automáticamente
                        mostrarMensajeBienvenida(usuarioActual!!.localNombre)
                        btnAlertaLocal.visibility = View.GONE
                    } else {
                        Log.d("MainActivity", "🔍 verificarAlertasLocal: Local existente, verificando alertas...")
                        // Local existente - verificar alertas normales
                        lifecycleScope.launch {
                            // 1. Verificar datos en 0 de hoy
                            Log.d("MainActivity", "🔍 verificarAlertasLocal: Llamando verificarDatosEnCeroHoy...")
                            verificarDatosEnCeroHoy(usuarioActual!!.localId, fechaHoy, problemas)
                            
                            // 2. Verificar días faltantes en los últimos 7 días
                            Log.d("MainActivity", "🔍 verificarAlertasLocal: Llamando verificarDiasFaltantes...")
                            verificarDiasFaltantes(usuarioActual!!.localId, fechaHoy, problemas)
                            
                            Log.d("MainActivity", "🔍 Total de problemas encontrados: ${problemas.size}")
                            problemas.forEach { problema ->
                                Log.d("MainActivity", "  - $problema")
                            }
                            
                            // Actualizar UI con los resultados
                            runOnUiThread {
                                if (problemas.isNotEmpty()) {
                                    Log.d("MainActivity", "✅ Mostrando círculo rojo de alerta - ${problemas.size} problemas encontrados")
                                    btnAlertaLocal.visibility = View.VISIBLE
                                    btnAlertaLocal.setOnClickListener {
                                        mostrarDetallesProblemas(usuarioActual!!.localNombre, problemas)
                                    }
                                } else {
                                    Log.d("MainActivity", "ℹ️ No hay problemas - ocultando círculo rojo")
                                    btnAlertaLocal.visibility = View.GONE
                                }
                            }
                            
                            // Esperar un momento para asegurar que la UI esté lista antes de mostrar el diálogo
                            kotlinx.coroutines.delay(800)
                            
                            // Mostrar automáticamente el diálogo de alertas al ingresar al local
                            runOnUiThread {
                                if (problemas.isNotEmpty() && !isFinishing && !isDestroyed) {
                                    Log.d("MainActivity", "📋 Intentando mostrar diálogo automático de alertas...")
                                    mostrarDetallesProblemas(usuarioActual!!.localNombre, problemas)
                                } else {
                                    Log.d("MainActivity", "⚠️ No se muestra diálogo: problemas.isEmpty=${problemas.isEmpty()}, isFinishing=$isFinishing, isDestroyed=$isDestroyed")
                                }
                            }
                            
                            // NO llamar verificarRegistrosExistentes() aquí porque ya se llama
                            // después de que termine la sincronización inicial en onCreate()
                            // Esto evita verificaciones duplicadas
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e("MainActivity", "❌ Error verificando alertas para local ${usuarioActual?.localId}: ${e.message}", e)
            }
        }
    }
    
    /**
     * Verifica si hay datos en 0 (todos los productos) en Stock Final e Ingreso de Mercadería de hoy
     * Usa la misma lógica que GestionLocalesActivity
     */
    private suspend fun verificarDatosEnCeroHoy(localId: String, fechaHoy: String, problemas: MutableList<String>) {
        Log.d("MainActivity", "🔍 Verificando datos en 0 en registros históricos para local: $localId")
        
        try {
            // ✅ OPTIMIZACIÓN: Usar cache para reducir lecturas de Firebase
            val registros: List<RegistroInventario>
            
            if (isAlertasCacheValid(cacheRegistrosAlertasTimestamp, localId) && cacheRegistrosAlertas != null) {
                Log.d("MainActivity", "🔍 Alertas - Usando cache (ahorra lecturas de Firebase) para local: $localId")
                registros = cacheRegistrosAlertas!!
            } else {
                Log.d("MainActivity", "🔍 Alertas - Cache expirado, consultando Firebase...")
                val firestore = FirebaseRegionManager.getFirestore()
                
                // ✅ OPTIMIZACIÓN: Limitar a últimos 30 días en lugar de todos los registros
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.DAY_OF_YEAR, -30)
                val fechaLimite = DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
                
                // Obtener registros de los últimos 30 días (reduce significativamente las lecturas)
                val snapshot = firestore
                    .collection("locales")
                    .document(localId)
                    .collection("registros")
                    .whereGreaterThanOrEqualTo("fecha", fechaLimite)
                    .get()
                    .await()
                
                Log.d("MainActivity", "📊 Registros obtenidos (últimos 30 días): ${snapshot.documents.size}")
                
                registros = snapshot.documents.mapNotNull { document ->
                    @Suppress("DEPRECATION")
                    document.toObject(RegistroInventario::class.java)
                }
                
                // Guardar en cache con el localId
                cacheRegistrosAlertas = registros
                cacheRegistrosAlertasTimestamp = System.currentTimeMillis()
                cacheRegistrosAlertasLocalId = localId
                Log.d("MainActivity", "🔍 Cache de alertas actualizado con ${registros.size} registros para local: $localId")
            }
            
            registros.forEach { registro ->
                // Verificar si todos los productos están en 0 (igual que GestionLocalesActivity)
                // Verificar que haya productos antes de verificar si están en 0
                val todosEnCero = registro.productos.isNotEmpty() && registro.productos.all { it.cantidad == 0 }
                
                Log.d("MainActivity", "📅 Verificando ${registro.tipo} - ${registro.fecha}: todosEnCero=$todosEnCero, productos.size=${registro.productos.size}, noRecibioMercaderia=${registro.noRecibioMercaderia}")
                
                when (registro.tipo) {
                    "Ingreso de Mercadería" -> {
                        // Si todos están en 0 y NO tiene el flag noRecibioMercaderia, mostrar alerta
                        if (todosEnCero && !registro.noRecibioMercaderia) {
                            val fechaFormateada = formatearFecha(registro.fecha)
                            val problema = "📦 $fechaFormateada:\n Ingreso de Mercadería\n   ⚠️ Todos los productos en 0"
                            problemas.add(problema)
                            Log.d("MainActivity", "⚠️ Problema encontrado: ${registro.tipo} - ${registro.fecha}")
                        }
                    }
                    "Stock Final" -> {
                        // Para Stock Final solo alertar si todos están en 0 (no hay flag aquí)
                        if (todosEnCero) {
                            val fechaFormateada = formatearFecha(registro.fecha)
                            val problema = "📊 $fechaFormateada - Stock Final\n   ⚠️ Todos los productos en 0"
                            problemas.add(problema)
                            Log.d("MainActivity", "⚠️ Problema encontrado: ${registro.tipo} - ${registro.fecha}")
                        }
                    }
                }
            }
            
            Log.d("MainActivity", "✅ Verificación de datos en 0 completada. Problemas encontrados: ${problemas.size}")
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Error verificando datos en 0: ${e.message}", e)
        }
    }
    
    /**
     * Verifica días faltantes en los últimos 7 días
     */
    private suspend fun verificarDiasFaltantes(localId: String, fechaHoy: String, problemas: MutableList<String>) {
        Log.d("MainActivity", "🔍 Verificando días faltantes en los últimos 7 días para local: $localId")
        
        try {
            // ✅ OPTIMIZACIÓN: Usar cache para evitar consultas costosas
            val registros: List<RegistroInventario>
            
            if (isAlertasCacheValid(cacheRegistrosAlertasTimestamp, localId) && cacheRegistrosAlertas != null) {
                Log.d("MainActivity", "🔍 Días faltantes - Usando cache (ahorra lecturas de Firebase) para local: $localId")
                registros = cacheRegistrosAlertas!!
            } else {
                Log.d("MainActivity", "🔍 Días faltantes - Cache expirado, consultando Firebase...")
                val firestore = FirebaseRegionManager.getFirestore()
                
                // ✅ OPTIMIZACIÓN: Limitar a últimos 7 días en lugar de todos los registros
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.DAY_OF_YEAR, -7)
                val fechaLimite = DateHelper.getDateFormat("yyyy-MM-dd").format(calendar.time)
                
                val snapshot = firestore
                    .collection("locales")
                    .document(localId)
                    .collection("registros")
                    .whereGreaterThanOrEqualTo("fecha", fechaLimite)
                    .get()
                    .await()
                
                registros = snapshot.documents.mapNotNull { document ->
                    @Suppress("DEPRECATION")
                    document.toObject(RegistroInventario::class.java)
                }
                
                // Guardar en cache si no estaba guardado o si es de otro local
                if (cacheRegistrosAlertas == null || cacheRegistrosAlertasLocalId != localId) {
                    cacheRegistrosAlertas = registros
                    cacheRegistrosAlertasTimestamp = System.currentTimeMillis()
                    cacheRegistrosAlertasLocalId = localId
                    Log.d("MainActivity", "🔍 Cache de días faltantes actualizado para local: $localId")
                }
            }
            
            // Si el local no tiene registros históricos (local nuevo), no generar alertas
            if (registros.isEmpty()) {
                Log.d("MainActivity", "🏪 Local nuevo sin registros históricos, no generando alertas de días faltantes")
                return
            }
            
            Log.d("MainActivity", "📊 Total de registros para verificar días faltantes: ${registros.size}")
            
            // Crear un mapa de fecha -> tipos para búsqueda rápida
            val registrosPorFecha = registros.groupBy { it.fecha }
                .mapValues { (_, registrosFecha) -> registrosFecha.map { it.tipo }.toSet() }
            
            val formatoFecha = DateHelper.getDateFormat("yyyy-MM-dd")
            val fechaHoyDate = formatoFecha.parse(fechaHoy) ?: Date()
            
            // Verificar los últimos 7 días (excluyendo hoy)
            for (i in 1..7) {
                val calendar = DateHelper.getCalendar()
                calendar.time = fechaHoyDate
                calendar.add(Calendar.DAY_OF_MONTH, -i) // Restar i días desde hoy
                val fechaVerificar = formatoFecha.format(calendar.time)
                
                Log.d("MainActivity", "🔍 Verificando fecha: $fechaVerificar")
                
                // ✅ OPTIMIZACIÓN: Usar el mapa en memoria en lugar de consultas individuales
                val tiposExistentes = registrosPorFecha[fechaVerificar] ?: emptySet()
                
                val faltantes = mutableListOf<String>()
                if (!tiposExistentes.contains("Ingreso de Mercadería")) {
                    faltantes.add("Ingreso de Mercadería")
                    Log.d("MainActivity", "⚠️ Falta Ingreso de Mercadería para $fechaVerificar")
                }
                if (!tiposExistentes.contains("Stock Final")) {
                    faltantes.add("Stock Final")
                    Log.d("MainActivity", "⚠️ Falta Stock Final para $fechaVerificar")
                }
                
                if (faltantes.isNotEmpty()) {
                    val problemaTexto = "📅 ${formatearFecha(fechaVerificar)}: Faltan: ${faltantes.joinToString(", ")}"
                    problemas.add(problemaTexto)
                    Log.d("MainActivity", "📅 Agregado problema para $fechaVerificar: ${faltantes.joinToString(", ")}")
                    Log.d("MainActivity", "📝 Lista de problemas ahora tiene ${problemas.size} elementos")
                } else {
                    Log.d("MainActivity", "✅ Fecha $fechaVerificar tiene todos los datos necesarios")
                }
            }
            
            Log.d("MainActivity", "✅ Verificación completada. Problemas encontrados: ${problemas.size}")
            if (problemas.isNotEmpty()) {
                Log.d("MainActivity", "📋 Lista completa de problemas:")
                problemas.forEachIndexed { index, problema ->
                    Log.d("MainActivity", "  ${index + 1}. $problema")
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Error verificando días faltantes: ${e.message}", e)
        }
        
        // NO llamar verificarRegistrosExistentes() aquí porque ya se llama en verificarAlertasLocal()
        // después de que se complete esta función
    }
    
    /**
     * Formatea una fecha de yyyy-MM-dd a formato legible
     */
    private fun formatearFecha(fecha: String): String {
        return try {
            val formatoEntrada = DateHelper.getDateFormat("yyyy-MM-dd")
            val formatoSalida = DateHelper.getDateFormat("EEE dd-MM-yy", Locale("es", "ES"))
            val date = formatoEntrada.parse(fecha)
            formatoSalida.format(date ?: Date())
        } catch (e: Exception) {
            fecha
        }
    }
    
    /**
     * Estructura para almacenar información de un problema
     */
    data class ProblemaInfo(
        val fecha: String, // yyyy-MM-dd
        val tipo: String, // "Stock Final" o "Ingreso de Mercadería"
        val texto: String // Texto completo del problema
    )
    
    /**
     * Muestra un diálogo con los detalles de los problemas encontrados
     * Estilo igual a la web: header rojo, mensaje introductorio amarillo, cards clickeables
     */
    private fun mostrarDetallesProblemas(nombreLocal: String, problemas: List<String>) {
        // Parsear problemas para extraer fecha y tipo
        val problemasParseados = mutableListOf<ProblemaInfo>()
        
        problemas.forEach { problemaTexto ->
            try {
                // Formato 1: "📅 JUE 06-11-25: Faltan: Stock Final"
                // Formato 2: "📅 JUE 06-11-25: Faltan: Ingreso de Mercadería"
                // Formato 3: "📊 JUE 06-11-25 - Stock Final\n   ⚠️ Todos los productos en 0"
                // Formato 4: "📦 JUE 06-11-25:\n Ingreso de Mercadería\n   ⚠️ Todos los productos en 0"
                
                val regexFecha = Regex("""(\d{2})-(\d{2})-(\d{2})""")
                val matchFecha = regexFecha.find(problemaTexto)
                
                if (matchFecha != null) {
                    val dia = matchFecha.groupValues[1]
                    val mes = matchFecha.groupValues[2]
                    val año = matchFecha.groupValues[3]
                    // Convertir a yyyy-MM-dd (año completo)
                    val añoCompleto = if (año.toInt() < 50) "20$año" else "19$año"
                    val fecha = "$añoCompleto-$mes-$dia"
                    
                    // Detectar tipo
                    val tipo = when {
                        problemaTexto.contains("Stock Final", ignoreCase = true) -> "Stock Final"
                        problemaTexto.contains("Ingreso de Mercadería", ignoreCase = true) -> "Ingreso de Mercadería"
                        else -> null
                    }
                    
                    if (tipo != null) {
                        problemasParseados.add(ProblemaInfo(fecha, tipo, problemaTexto))
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error parseando problema: $problemaTexto", e)
            }
        }
        
        // Verificar modo oscuro
        val darkMode = isDarkMode()
        val colorFondo = if (darkMode) 0xFF1E1E1E.toInt() else android.graphics.Color.WHITE
        val colorTexto = if (darkMode) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val colorCardFondo = if (darkMode) 0xFF2D2D2D.toInt() else 0xFFF9FAFB.toInt()
        val colorCardBorde = if (darkMode) 0xFF404040.toInt() else 0xFFE5E7EB.toInt()
        val colorIntroFondo = if (darkMode) 0xFF3D2E00.toInt() else 0xFFFFF3CD.toInt()
        val colorIntroTexto = if (darkMode) 0xFFFFD54F.toInt() else 0xFF856404.toInt()
        val colorFooterBorde = if (darkMode) 0xFF404040.toInt() else 0xFFE5E7EB.toInt()
        
        // Crear layout principal con fondo dinámico
        val layoutPrincipal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(colorFondo)
            // Bordes redondeados para el diálogo completo
            val drawable = GradientDrawable().apply {
                setColor(colorFondo)
                cornerRadius = 12f * resources.displayMetrics.density
            }
            background = drawable
        }
        
        // HEADER ROJO (igual que web)
        val header = TextView(this).apply {
            text = "⚠️ Alertas - $nombreLocal"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xFFF44336.toInt()) // #F44336
            setPadding(
                (16 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt()
            )
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        layoutPrincipal.addView(header)
        
        // BODY CON SCROLL
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // Limitar altura máxima (80vh de la web = ~80% de la pantalla)
                val maxHeightPx = (resources.displayMetrics.heightPixels * 0.8).toInt()
                height = maxHeightPx
            }
        }
        
        val layoutProblemas = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt()
            )
        }
        
        // MENSAJE INTRODUCTORIO AMARILLO (adaptado al tema)
        val txtIntro = TextView(this).apply {
            text = "⚠️ Se encontraron los siguientes problemas:\n\nHacé clic en cada problema para ir directamente a completarlo."
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(colorIntroTexto)
            setPadding(
                (12 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt()
            )
            // Borde izquierdo amarillo (usando drawable) - adaptado al tema
            val drawable = GradientDrawable().apply {
                setColor(colorIntroFondo)
                cornerRadius = 4f * resources.displayMetrics.density
                setStroke(
                    (4 * resources.displayMetrics.density).toInt(),
                    0xFFFFC107.toInt() // #ffc107 (amarillo se mantiene)
                )
            }
            background = drawable
        }
        layoutProblemas.addView(txtIntro)
        
        // Crear diálogo sin título (ya lo tenemos en el header) - ANTES para poder usarlo en los clicks
        val dialog = AlertDialog.Builder(this)
            .setView(layoutPrincipal)
            .create()
        
        // Agregar cada problema como card clickeable (igual que web)
        problemasParseados.forEach { problema ->
            val cardProblema = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
                }
                setPadding(
                    (12 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt()
                )
                // Fondo dinámico con borde y bordes redondeados
                val drawable = GradientDrawable().apply {
                    setColor(colorCardFondo)
                    cornerRadius = 8f * resources.displayMetrics.density
                    setStroke(
                        (2 * resources.displayMetrics.density).toInt(),
                        colorCardBorde
                    )
                }
                background = drawable
                isClickable = true
                isFocusable = true
                
                // Click para navegar
                setOnClickListener {
                    dialog.dismiss()
                    val intent = Intent(this@MainActivity, CargaActivity::class.java)
                    intent.putExtra("tipo", problema.tipo)
                    intent.putExtra("fecha", problema.fecha)
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, 2000)
                }
                
                // Efecto hover (ripple) - usar ripple drawable
                val ripple = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.list_selector_background)
                foreground = ripple
            }
            
            val txtProblema = TextView(this).apply {
                text = problema.texto.replace("\n", " ")
                textSize = 14f
                setTextColor(colorTexto) // Color dinámico según tema
                setLineSpacing(0f, 1.2f)
            }
            
            cardProblema.addView(txtProblema)
            layoutProblemas.addView(cardProblema)
        }
        
        scrollView.addView(layoutProblemas)
        layoutPrincipal.addView(scrollView)
        
        // FOOTER CON BOTÓN CERRAR (igual que web)
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(
                (12 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt()
            )
            gravity = android.view.Gravity.END
            // Borde superior dinámico
            val drawable = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(
                    (1 * resources.displayMetrics.density).toInt(),
                    colorFooterBorde
                )
            }
            background = drawable
        }
        
        val btnCerrar = Button(this).apply {
            text = "Cerrar"
            setTextColor(android.graphics.Color.WHITE)
            setPadding(
                (8 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt()
            )
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            // Bordes redondeados
            val drawable = GradientDrawable().apply {
                setColor(0xFF6B7280.toInt()) // #6b7280
                cornerRadius = 6f * resources.displayMetrics.density
            }
            background = drawable
        }
        
        // Configurar botón cerrar
        btnCerrar.setOnClickListener {
            dialog.dismiss()
        }
        
        footer.addView(btnCerrar)
        layoutPrincipal.addView(footer)
        
        // Estilo del diálogo (fondo dinámico, bordes redondeados)
        dialog.window?.apply {
            // Fondo dinámico según tema
            if (darkMode) {
                setBackgroundDrawableResource(android.R.color.black)
                decorView.setBackgroundColor(0xFF1E1E1E.toInt())
            } else {
                setBackgroundDrawableResource(android.R.color.white)
                decorView.setBackgroundColor(android.graphics.Color.WHITE)
            }
            setLayout(
                (resources.displayMetrics.widthPixels * 0.9).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        
        dialog.show()
    }
    
    /**
     * Muestra el mensaje de bienvenida para locales nuevos
     */
    private fun mostrarMensajeBienvenida(nombreLocal: String) {
        val mensaje = """
            🎉 ¡BIENVENIDO $nombreLocal!
            
            Para comenzar a usar el Product Mix V2, necesitas cargar los datos iniciales:
            
            📦 PASO 1: STOCK INICIAL + INGRESO DE HOY(si ya ingreso)
            • Ve a "Ingreso de Mercadería" 
            • Carga las cantidades actuales de todos los productos
            • Mercaderia Inicial + Ingreso de Fabrica <-- Solo por esta vez
            • Esto será tu punto de partida

            📦 PASO 2: Luego todos los dias se carga:
            • Ingreso de Mercaderia + Stock Final:
            • Ingreso de Mercaderia(Ingreso de Fabrica)
            • Stock Final(El Stock al Cierre del Local)
            
            ✅ Una vez cargados ambos datos, el sistema comenzará a funcionar normalmente y podrás:
            • Ver promedios de ventas
            • Generar pedidos automáticos
            • Recibir alertas cuando sea necesario
            
            ¡Que tengas un excelente día! 🚀
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("🎉 ¡Bienvenido!")
            .setMessage(mensaje)
            .setPositiveButton("¡Entendido!", null)
            .show()
    }
    
}