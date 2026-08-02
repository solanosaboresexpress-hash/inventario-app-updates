package com.tuapp.inventario.ui.activities

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.tuapp.inventario.R
import com.tuapp.inventario.utils.DateHelper
import com.tuapp.inventario.utils.AnimationUtils
import com.tuapp.inventario.data.models.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.MediaStore
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Activity principal para administración del local
 * Gestiona documentos del local y personal con sus certificaciones
 */
class AdministracionLocalActivity : AppCompatActivity() {
    
    // Variables para selección de imágenes
    private var libretaSanitariaBase64: String? = null
    private var cursoManipulacionBase64: String? = null
    private var isSeleccionandoLibreta = false
    
    // Contrato para seleccionar imagen
    private val pickMultipleImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        Log.d("SeleccionarImagen", "pickMultipleImages callback - uris size: ${uris.size}, isSeleccionandoLibreta: $isSeleccionandoLibreta")
        if (!uris.isNullOrEmpty()) {
            val base64 = convertMultipleImagesToBase64(uris)
            Log.d("SeleccionarImagen", "Base64 generado: ${base64.take(100)}... length: ${base64.length}")
            if (isSeleccionandoLibreta) {
                libretaSanitariaBase64 = base64
                actualizarPreviewImage(R.id.imgPreviewLibreta, base64)
                Log.d("SeleccionarImagen", "Asignado a libretaSanitariaBase64")
            } else {
                cursoManipulacionBase64 = base64
                actualizarPreviewImage(R.id.imgPreviewCurso, base64)
                Log.d("SeleccionarImagen", "Asignado a cursoManipulacionBase64")
            }
        } else {
            Log.w("SeleccionarImagen", "No se seleccionaron imágenes")
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val base64 = convertImageToBase64(it)
            if (isSeleccionandoLibreta) {
                libretaSanitariaBase64 = base64
                actualizarPreviewImage(R.id.imgPreviewLibreta, base64)
            } else {
                cursoManipulacionBase64 = base64
                actualizarPreviewImage(R.id.imgPreviewCurso, base64)
            }
        }
    }

    private fun actualizarPreviewImage(viewId: Int, base64: String) {
        try {
            val base64Data = if (base64.startsWith("data:image")) {
                base64.substringAfter(",")
            } else {
                base64
            }
            val imageData = Base64.decode(base64Data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            dialogView?.findViewById<ImageView>(viewId)?.setImageBitmap(bitmap)
            dialogView?.findViewById<ImageView>(viewId)?.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e("SeleccionarImagen", "Error cargando preview", e)
        }
    }
    
    private var dialogView: View? = null
    
    private fun isDarkMode(): Boolean {
        return when (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) {
            android.content.res.Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }
    
    val tabLayout: TabLayout? = null
    private lateinit var txtLocalActual: TextView
    private lateinit var txtTotalDocumentos: TextView
    private lateinit var txtTotalPersonal: TextView
    private lateinit var txtAlertasActivas: TextView
    
    private lateinit var documentosContainer: LinearLayout
    private lateinit var personalContainer: LinearLayout
    private lateinit var notificacionesContainer: LinearLayout
    
    private var localId: String = ""
    private var localNombre: String = ""
    
    // Datos de ejemplo (después se conectarán con Firebase/Room)
    private var documentosLocales = mutableListOf<DocumentoLocal>()
    private var personalLocal = mutableListOf<PersonalLocal>()
    private var notificaciones = mutableListOf<NotificacionVencimiento>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_administracion_local)
        
        // Obtener datos del local
        localId = intent.getStringExtra("localId") ?: ""
        localNombre = intent.getStringExtra("localNombre") ?: "Local Seleccionado"
        
        setupUI()
        cargarDatosDesdeFirebase()
        actualizarUI()
    }
    
    private fun setupUI() {
        txtLocalActual = findViewById(R.id.txtLocalActual)
        txtTotalDocumentos = findViewById(R.id.txtTotalDocumentos)
        txtTotalPersonal = findViewById(R.id.txtTotalPersonal)
        txtAlertasActivas = findViewById(R.id.txtAlertasActivas)
        
        // Configurar clics en tarjetas de estadísticas
        findViewById<LinearLayout>(R.id.cardDocumentos).setOnClickListener {
            mostrarDocumentos()
        }
        
        findViewById<LinearLayout>(R.id.cardPersonal).setOnClickListener {
            mostrarPersonal()
        }
        
        findViewById<LinearLayout>(R.id.cardAlertas).setOnClickListener {
            mostrarNotificaciones()
        }
        
        // Inicializar contenedores
        documentosContainer = findViewById(R.id.documentosContainer)
        personalContainer = findViewById(R.id.personalContainer)
        notificacionesContainer = findViewById(R.id.notificacionesContainer)
        
        // Configurar FABs
        findViewById<ExtendedFloatingActionButton>(R.id.fabAgregarDocumento).setOnClickListener {
            mostrarDialogoAgregarDocumento()
        }
        
        findViewById<ExtendedFloatingActionButton>(R.id.fabAgregarPersonal).setOnClickListener {
            mostrarDialogoAgregarPersonal()
        }
        
        // Mostrar documentos por defecto
        mostrarDocumentos()
    }
    
    private fun cargarDatosDesdeFirebase() {
        val db = FirebaseFirestore.getInstance()
        
        // Cargar documentos desde Firebase
        db.collection("locales")
            .document(localId)
            .collection("documentos")
            .get()
            .addOnSuccessListener { result ->
                documentosLocales.clear()
                for (document in result) {
                    val documento = document.toObject(DocumentoLocal::class.java)
                    
                    // Recalcular diasParaVencer con fecha actual
                    val documentoActualizado = documento.copy(
                        diasParaVencer = if (documento.fechaVencimiento != null) {
                            DateHelper.getDaysUntilDate(documento.fechaVencimiento)
                        } else 0,
                        estado = if (documento.fechaVencimiento != null) {
                            when {
                                DateHelper.isDateExpired(documento.fechaVencimiento) -> EstadoDocumento.VENCIDO
                                DateHelper.getDaysUntilDate(documento.fechaVencimiento) <= 30 -> EstadoDocumento.POR_VENCER
                                else -> EstadoDocumento.VIGENTE
                            }
                        } else documento.estado
                    )
                    
                    documentosLocales.add(documentoActualizado)
                }

                cargarDocumentosUI()
                generarNotificaciones()
                actualizarUI()
            }
            .addOnFailureListener { e ->
                Log.e("AdministracionLocal", "Error cargando documentos desde Firebase", e)
                cargarDocumentosUI()
                generarNotificaciones()
                actualizarUI()
            }
        
        // Cargar personal desde Firebase
        db.collection("locales")
            .document(localId)
            .collection("personal")
            .get()
            .addOnSuccessListener { result ->
                personalLocal.clear()
                for (document in result) {
                    val personal = document.toObject(PersonalLocal::class.java)
                    
                    // Log para depuración - verificar campos del documento
                    Log.d("CargarPersonal", "Documento ID: ${document.id}")
                    Log.d("CargarPersonal", "Campos del documento: ${document.data.keys}")
                    Log.d("CargarPersonal", "Personal: ${personal.nombreCompleto}, Libreta Base64: ${personal.libretaSanitariaBase64 != null}, Curso Base64: ${personal.cursoManipulacionBase64 != null}")
                    Log.d("CargarPersonal", "Libreta Base64 length: ${personal.libretaSanitariaBase64?.length ?: 0}")
                    Log.d("CargarPersonal", "Curso Base64 length: ${personal.cursoManipulacionBase64?.length ?: 0}")
                    
                    // Recalcular diasParaVencer para libreta sanitaria
                    val libretaActualizada = personal.libretaSanitaria?.let { libreta ->
                        val diasParaVencer = if (libreta.fechaVencimiento != null) {
                            DateHelper.getDaysUntilDate(libreta.fechaVencimiento)
                        } else 0
                        
                        libreta.copy(
                            diasParaVencer = diasParaVencer,
                            estado = when {
                                libreta.fechaVencimiento == null -> EstadoCertificado.NO_POSEE
                                DateHelper.isDateExpired(libreta.fechaVencimiento) -> EstadoCertificado.VENCIDO
                                DateHelper.getDaysUntilDate(libreta.fechaVencimiento) <= 30 -> EstadoCertificado.POR_VENCER
                                else -> EstadoCertificado.VIGENTE
                            }
                        )
                    }
                    
                    // Recalcular diasParaVencer para curso de manipulación
                    val cursoActualizado = personal.cursoManipulacion?.let { curso ->
                        val diasParaVencer = if (curso.fechaVencimiento != null) {
                            DateHelper.getDaysUntilDate(curso.fechaVencimiento)
                        } else 0
                        
                        curso.copy(
                            diasParaVencer = diasParaVencer,
                            estado = when {
                                curso.fechaVencimiento == null -> EstadoCertificado.NO_POSEE
                                DateHelper.isDateExpired(curso.fechaVencimiento) -> EstadoCertificado.VENCIDO
                                DateHelper.getDaysUntilDate(curso.fechaVencimiento) <= 30 -> EstadoCertificado.POR_VENCER
                                else -> EstadoCertificado.VIGENTE
                            }
                        )
                    }
                    
                    // Actualizar personal con los valores recalculados
                    val personalActualizado = personal.copy(
                        libretaSanitaria = libretaActualizada,
                        cursoManipulacion = cursoActualizado,
                        libretaSanitariaBase64 = personal.libretaSanitariaBase64,
                        cursoManipulacionBase64 = personal.cursoManipulacionBase64
                    )
                    
                    personalLocal.add(personalActualizado)
                }
                cargarPersonalUI()
                generarNotificaciones()
                actualizarUI()
            }
            .addOnFailureListener { e ->
                Log.e("AdministracionLocal", "Error cargando personal desde Firebase", e)
                cargarPersonalUI()
                generarNotificaciones()
                actualizarUI()
            }
    }
    
    private fun mostrarDocumentos() {
        Log.d("AdministracionLocal", "Mostrando sección de documentos")
        documentosContainer.visibility = View.VISIBLE
        personalContainer.visibility = View.GONE
        notificacionesContainer.visibility = View.GONE
        
        // Cargar documentos en el contenedor
        documentosContainer.removeAllViews()
        documentosLocales.forEach { documento ->
            val cardView = crearDocumentoCard(documento)
            documentosContainer.addView(cardView)
            AnimationUtils.animateViewIn(cardView)
        }
        
        // Ocultar FAB de personal, mostrar FAB de documentos
        findViewById<ExtendedFloatingActionButton>(R.id.fabAgregarDocumento).visibility = View.VISIBLE
        findViewById<ExtendedFloatingActionButton>(R.id.fabAgregarPersonal).visibility = View.GONE
        
        actualizarUI()
    }
    
    private fun mostrarPersonal() {
        Log.d("AdministracionLocal", "Mostrando sección de personal")
        documentosContainer.visibility = View.GONE
        personalContainer.visibility = View.VISIBLE
        notificacionesContainer.visibility = View.GONE
        
        // Cargar personal en el contenedor
        personalContainer.removeAllViews()
        personalLocal.forEach { personal ->
            val cardView = crearPersonalCard(personal)
            personalContainer.addView(cardView)
            AnimationUtils.animateViewIn(cardView)
        }
        
        // Ocultar FAB de documentos, mostrar FAB de personal
        findViewById<ExtendedFloatingActionButton>(R.id.fabAgregarDocumento).visibility = View.GONE
        findViewById<ExtendedFloatingActionButton>(R.id.fabAgregarPersonal).visibility = View.VISIBLE
        
        actualizarUI()
    }
    
    private fun mostrarNotificaciones() {
        Log.d("AdministracionLocal", "Mostrando sección de notificaciones")
        documentosContainer.visibility = View.GONE
        personalContainer.visibility = View.GONE
        notificacionesContainer.visibility = View.VISIBLE
        
        // Cargar notificaciones en el contenedor
        notificacionesContainer.removeAllViews()
        if (notificaciones.isEmpty()) {
            val txtSinNotificaciones = TextView(this).apply {
                text = "¡No hay alertas activas! \ud83c\udf89"
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 100, 0, 0)
                setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            }
            notificacionesContainer.addView(txtSinNotificaciones)
        } else {
            notificaciones.forEach { notificacion ->
                val cardView = crearNotificacionCard(notificacion)
                notificacionesContainer.addView(cardView)
                AnimationUtils.animateViewIn(cardView)
            }
        }
        
        // Ocultar ambos FABs en notificaciones
        findViewById<ExtendedFloatingActionButton>(R.id.fabAgregarDocumento).visibility = View.GONE
        findViewById<ExtendedFloatingActionButton>(R.id.fabAgregarPersonal).visibility = View.GONE
        
        actualizarUI()
    }

    private fun generarNotificaciones() {
        notificaciones.clear()
        
        // Notificaciones de documentos
        documentosLocales.forEach { doc ->
            when {
                doc.diasParaVencer <= 0 -> {
                    notificaciones.add(NotificacionVencimiento(
                        id = "doc_${doc.id}",
                        localId = localId,
                        tipo = TipoNotificacion.DOCUMENTO_LOCAL,
                        elementoId = doc.id,
                        elementoNombre = doc.nombreDocumento,
                        tipoElemento = doc.tipoDocumento.displayName,
                        fechaVencimiento = doc.fechaVencimiento,
                        diasParaVencer = doc.diasParaVencer,
                        mensaje = "¡${doc.nombreDocumento} ${if (doc.diasParaVencer == 0) "VENCE HOY" else "VENCIDO"}!",
                        prioridad = if (doc.diasParaVencer <= -30) PrioridadNotificacion.CRITICA else PrioridadNotificacion.ALTA
                    ))
                }
                doc.diasParaVencer <= 30 -> {
                    notificaciones.add(NotificacionVencimiento(
                        id = "doc_${doc.id}",
                        localId = localId,
                        tipo = TipoNotificacion.DOCUMENTO_LOCAL,
                        elementoId = doc.id,
                        elementoNombre = doc.nombreDocumento,
                        tipoElemento = doc.tipoDocumento.displayName,
                        fechaVencimiento = doc.fechaVencimiento,
                        diasParaVencer = doc.diasParaVencer,
                        mensaje = "${doc.nombreDocumento} - ${formatTimeRemaining(doc.diasParaVencer)}",
                        prioridad = if (doc.diasParaVencer <= 7) PrioridadNotificacion.ALTA else PrioridadNotificacion.MEDIA
                    ))
                }
            }
        }
        
        // Notificaciones de personal
        personalLocal.forEach { personal ->
            // Libreta sanitaria
            personal.libretaSanitaria?.let { libreta ->
                when {
                    libreta.diasParaVencer <= 0 -> {
                        notificaciones.add(NotificacionVencimiento(
                            id = "lib_${personal.id}",
                            localId = localId,
                            tipo = TipoNotificacion.LIBRETA_SANITARIA,
                            elementoId = personal.id,
                            elementoNombre = personal.nombreCompleto,
                            tipoElemento = "Libreta Sanitaria",
                            fechaVencimiento = libreta.fechaVencimiento,
                            diasParaVencer = libreta.diasParaVencer,
                            mensaje = "Libreta sanitaria de ${personal.nombreCompleto} ${if (libreta.diasParaVencer == 0) "VENCE HOY" else "VENCIDA"}!",
                            prioridad = PrioridadNotificacion.ALTA
                        ))
                    }
                    libreta.diasParaVencer <= 30 -> {
                        notificaciones.add(NotificacionVencimiento(
                            id = "lib_${personal.id}",
                            localId = localId,
                            tipo = TipoNotificacion.LIBRETA_SANITARIA,
                            elementoId = personal.id,
                            elementoNombre = personal.nombreCompleto,
                            tipoElemento = "Libreta Sanitaria",
                            fechaVencimiento = libreta.fechaVencimiento,
                            diasParaVencer = libreta.diasParaVencer,
                            mensaje = "Libreta sanitaria de ${personal.nombreCompleto} - ${formatTimeRemaining(libreta.diasParaVencer)}",
                            prioridad = PrioridadNotificacion.MEDIA
                        ))
                    }
                    else -> {
                        // No hay notificación para libreta vigente (más de 30 días)
                    }
                }
            }
            
            // Curso de manipulación
            personal.cursoManipulacion?.let { curso ->
                when {
                    curso.diasParaVencer <= 0 -> {
                        notificaciones.add(NotificacionVencimiento(
                            id = "cur_${personal.id}",
                            localId = localId,
                            tipo = TipoNotificacion.CURSO_MANIPULACION,
                            elementoId = personal.id,
                            elementoNombre = personal.nombreCompleto,
                            tipoElemento = "Curso Manipulación",
                            fechaVencimiento = curso.fechaVencimiento,
                            diasParaVencer = curso.diasParaVencer,
                            mensaje = "Curso de ${personal.nombreCompleto} ${if (curso.diasParaVencer == 0) "VENCE HOY" else "VENCIDO"}!",
                            prioridad = PrioridadNotificacion.ALTA
                        ))
                    }
                    curso.diasParaVencer <= 30 -> {
                        notificaciones.add(NotificacionVencimiento(
                            id = "cur_${personal.id}",
                            localId = localId,
                            tipo = TipoNotificacion.CURSO_MANIPULACION,
                            elementoId = personal.id,
                            elementoNombre = personal.nombreCompleto,
                            tipoElemento = "Curso Manipulación",
                            fechaVencimiento = curso.fechaVencimiento,
                            diasParaVencer = curso.diasParaVencer,
                            mensaje = "Curso de ${personal.nombreCompleto} - ${formatTimeRemaining(curso.diasParaVencer)}",
                            prioridad = PrioridadNotificacion.MEDIA
                        ))
                    }
                    else -> {
                        // No hay notificación para curso vigente
                    }
                }
            }
        }
        
        // Ordenar por prioridad y días para vencer
        notificaciones.sortWith(compareByDescending<NotificacionVencimiento> { 
            when (it.prioridad) {
                PrioridadNotificacion.CRITICA -> 4
                PrioridadNotificacion.ALTA -> 3
                PrioridadNotificacion.MEDIA -> 2
                PrioridadNotificacion.BAJA -> 1
            }
        }.thenBy { it.diasParaVencer })
    }
    
    private fun cargarDocumentosUI() {
        documentosContainer.removeAllViews()
        
        documentosLocales.forEach { documento ->
            val cardView = crearDocumentoCard(documento)
            documentosContainer.addView(cardView)
            AnimationUtils.animateViewIn(cardView)
        }
    }
    
    private fun cargarPersonalUI() {
        personalContainer.removeAllViews()
        
        personalLocal.forEach { personal ->
            val cardView = crearPersonalCard(personal)
            personalContainer.addView(cardView)
            AnimationUtils.animateViewIn(cardView)
        }
    }
    
    private fun updateDocumentCount() {
        notificacionesContainer.removeAllViews()
        
        if (notificaciones.isEmpty()) {
            val txtSinNotificaciones = TextView(this).apply {
                text = "¡No hay alertas activas! \ud83c\udf89"
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 100, 0, 0)
                setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            }
            notificacionesContainer.addView(txtSinNotificaciones)
        } else {
            notificaciones.forEach { notificacion ->
                val cardView = crearNotificacionCard(notificacion)
                notificacionesContainer.addView(cardView)
                AnimationUtils.animateViewIn(cardView)
            }
        }
    }
    
    private fun crearDocumentoCard(documento: DocumentoLocal): View {
        // Inflar el nuevo layout de tarjeta
        val cardView = layoutInflater.inflate(R.layout.item_documento_card, null)
        
        // Configurar los datos del documento
        cardView.findViewById<TextView>(R.id.txtIcono).text = when (documento.tipoDocumento) {
            TipoDocumento.FUMIGACION -> "🌿"
            TipoDocumento.OBLEA_MATAFUEGOS -> "🔥"
            TipoDocumento.ANALISIS_AGUA -> "💧"
            TipoDocumento.REBA -> "⚠️"
            TipoDocumento.POLIZA_SEGURO -> "🛡️"
            TipoDocumento.HABILITACION_MUNICIPAL -> "🏢"
            TipoDocumento.CERTIFICADO_HIGIENE -> "🏥"
        }
        
        cardView.findViewById<TextView>(R.id.txtNombreDocumento).text = documento.nombreDocumento
        
        // Empresa y teléfono
        cardView.findViewById<TextView>(R.id.txtEmpresa).text = documento.empresaResponsable
        cardView.findViewById<TextView>(R.id.txtTelefono).text = documento.telefono.ifEmpty { "No especificado" }
        
        // Fecha de vencimiento
        val sdf = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        cardView.findViewById<TextView>(R.id.txtFechaVencimiento).text = 
            documento.fechaVencimiento?.let { sdf.format(it) } ?: "No especificada"
        
        // Observaciones
        val txtObservaciones = cardView.findViewById<TextView>(R.id.txtObservaciones)
        if (documento.observaciones.isNotBlank()) {
            txtObservaciones.text = documento.observaciones
            txtObservaciones.visibility = View.VISIBLE
        } else {
            txtObservaciones.visibility = View.GONE
        }
        
        // Estado
        val txtEstado = cardView.findViewById<TextView>(R.id.txtEstado)
        txtEstado.text = documento.estado.displayName
        txtEstado.setBackgroundColor(ContextCompat.getColor(this, 
            when (documento.estado) {
                EstadoDocumento.VIGENTE -> android.R.color.holo_green_dark
                EstadoDocumento.POR_VENCER -> android.R.color.holo_orange_dark
                EstadoDocumento.VENCIDO -> android.R.color.holo_red_dark
                EstadoDocumento.EN_TRAMITE -> android.R.color.holo_blue_dark
            }
        ))
        
        // Días para vencer
        val txtDiasParaVencer = cardView.findViewById<TextView>(R.id.txtDiasParaVencer)
        if (documento.diasParaVencer > 0) {
            txtDiasParaVencer.text = "${documento.diasParaVencer} días"
        } else if (documento.diasParaVencer == 0) {
            txtDiasParaVencer.text = "Vence hoy"
        } else {
            txtDiasParaVencer.text = "Vencido"
        }
        
        // Botón de acciones
        cardView.findViewById<ImageButton>(R.id.btnAcciones).setOnClickListener {
            mostrarOpcionesDocumento(documento)
        }
        
        return cardView
    }
    
    private fun mostrarOpcionesDocumento(documento: DocumentoLocal) {
        val opciones = arrayOf("Ver Detalles", "Editar", "Eliminar")
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Opciones - ${documento.nombreDocumento}")
            .setItems(opciones) { _, which ->
                when (which) {
                    0 -> mostrarDialogoDetalleDocumento(documento)
                    1 -> mostrarDialogoEditarDocumento(documento)
                    2 -> mostrarDialogoEliminarDocumento(documento)
                }
            }
            .show()
    }
    
    private fun crearPersonalCard(personal: PersonalLocal): CardView {
        val cardView = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 8, 16, 8)
            }
            radius = 16f
            cardElevation = 8f
            setCardBackgroundColor(resources.getColor(R.color.surface_color, null))
        }
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        
        // Nombre y cargo
        val nombreView = TextView(this).apply {
            text = personal.nombreCompleto
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.text_primary, null))
        }
        
        val cargoView = TextView(this).apply {
            text = personal.cargo
            textSize = 14f
            setTextColor(resources.getColor(R.color.text_secondary, null))
            setPadding(0, 4, 0, 8)
        }
        
        // Libreta sanitaria
        val libretaLayout = crearCertificadoView(
            "Libreta Sanitaria",
            personal.libretaSanitaria,
            personal.libretaSanitariaBase64 != null,
            personal.libretaSanitariaBase64
        )
        
        // Log para depuración
        Log.d("PersonalCard", "Libreta Base64: ${personal.libretaSanitariaBase64 != null}")
        
        // Curso manipulación
        val cursoLayout = crearCertificadoView(
            "Curso Manipulación",
            personal.cursoManipulacion,
            personal.cursoManipulacionBase64 != null,
            personal.cursoManipulacionBase64
        )
        
        // Log para depuración
        Log.d("PersonalCard", "Curso Base64: ${personal.cursoManipulacionBase64 != null}")
        
        layout.addView(nombreView)
        layout.addView(cargoView)
        layout.addView(libretaLayout)
        layout.addView(cursoLayout)
        
        cardView.addView(layout)
        cardView.setOnClickListener {
            mostrarDialogoDetallePersonal(personal)
        }
        
        return cardView
    }
    
    private fun crearCertificadoView(titulo: String, certificado: Any?, tieneImagen: Boolean = false, imagenBase64: String? = null): LinearLayout {
        // Log para depuración
        Log.d("CrearCertificado", "Título: $titulo, tieneImagen: $tieneImagen, imagenBase64: ${imagenBase64 != null}")
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        
        val iconView = TextView(this).apply {
            text = when (titulo) {
                "Libreta Sanitaria" -> "\ud83d\udcdd"
                "Curso Manipulación" -> "\ud83c\udf93"
                else -> "\ud83d\udccb"
            }
            textSize = 20f
            setPadding(0, 0, 12, 0)
        }
        
        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        
        val tituloView = TextView(this).apply {
            text = titulo
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(R.color.text_primary, null))
        }
        
        val estadoView = TextView(this).apply {
            text = when (certificado) {
                is LibretaSanitaria -> {
                    when (certificado.estado) {
                        EstadoCertificado.VIGENTE -> "Vigente - ${formatTimeRemaining(certificado.diasParaVencer)}"
                        EstadoCertificado.POR_VENCER -> "Por vencer - ${formatTimeRemaining(certificado.diasParaVencer)}"
                        EstadoCertificado.VENCIDO -> "Vencido - ${formatTimeRemaining(certificado.diasParaVencer)}"
                        EstadoCertificado.NO_POSEE -> "No posee"
                        EstadoCertificado.EN_TRAMITE -> "En trámite"
                    }
                }
                is CursoManipulacion -> {
                    when (certificado.estado) {
                        EstadoCertificado.VIGENTE -> "Vigente - ${formatTimeRemaining(certificado.diasParaVencer)}"
                        EstadoCertificado.POR_VENCER -> "Por vencer - ${formatTimeRemaining(certificado.diasParaVencer)}"
                        EstadoCertificado.VENCIDO -> "Vencido - ${formatTimeRemaining(certificado.diasParaVencer)}"
                        EstadoCertificado.NO_POSEE -> "No posee"
                        EstadoCertificado.EN_TRAMITE -> "En trámite"
                    }
                }
                else -> "No disponible"
            }
            textSize = 12f
            setTextColor(resources.getColor(R.color.text_secondary, null))
        }
        
        infoLayout.addView(tituloView)
        infoLayout.addView(estadoView)
        
        layout.addView(iconView)
        layout.addView(infoLayout)
        
        // Indicador de imagen cargada
        if (tieneImagen && imagenBase64 != null) {
            val imagenIndicator = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_view)
                setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
                setColorFilter(android.graphics.Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(8, 0, 0, 0)
                }
                setPadding(6, 6, 6, 6)
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                contentDescription = "Ver imagen"
                setOnClickListener {
                    mostrarVisorImagen(titulo, imagenBase64)
                }
            }
            layout.addView(imagenIndicator)
            Log.d("CrearCertificado", "Indicador de imagen agregado para $titulo")
        }
        
        return layout
    }
    
    private fun crearNotificacionCard(notificacion: NotificacionVencimiento): CardView {
        val cardView = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 8, 16, 8)
            }
            radius = 12f
            cardElevation = 6f
            setCardBackgroundColor(resources.getColor(
                when (notificacion.prioridad) {
                    PrioridadNotificacion.CRITICA -> R.color.notif_critica_bg
                    PrioridadNotificacion.ALTA -> R.color.notif_alta_bg
                    PrioridadNotificacion.MEDIA -> R.color.notif_media_bg
                    PrioridadNotificacion.BAJA -> R.color.notif_baja_bg
                }
            , null))
        }
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        
        // Icono y prioridad
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        
        val iconView = TextView(this).apply {
            text = when (notificacion.prioridad) {
                PrioridadNotificacion.CRITICA -> "\ud83d\udea8"
                PrioridadNotificacion.ALTA -> "\u26a0\ufe0f"
                PrioridadNotificacion.MEDIA -> "\u2139\ufe0f"
                PrioridadNotificacion.BAJA -> "\u2705"
            }
            textSize = 24f
            setPadding(0, 0, 12, 0)
        }
        
        val prioridadView = TextView(this).apply {
            text = "${notificacion.prioridad.displayName}"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor(
                when (notificacion.prioridad) {
                    PrioridadNotificacion.CRITICA -> "#D32F2F"
                    PrioridadNotificacion.ALTA -> "#F57C00"
                    PrioridadNotificacion.MEDIA -> "#F9A825"
                    PrioridadNotificacion.BAJA -> "#388E3C"
                }
            ))
        }
        
        headerLayout.addView(iconView)
        headerLayout.addView(prioridadView)
        
        // Mensaje
        val mensajeView = TextView(this).apply {
            text = notificacion.mensaje
            textSize = 14f
            setTextColor(resources.getColor(R.color.text_primary, null))
            setPadding(0, 8, 0, 0)
        }
        
        layout.addView(headerLayout)
        layout.addView(mensajeView)
        
        cardView.addView(layout)
        cardView.setOnClickListener {
            marcarNotificacionComoLeida(notificacion)
        }
        
        return cardView
    }
    
    private fun mostrarDialogoAgregarDocumento() {
        Log.d("AdministracionLocal", "mostrarDialogoAgregarDocumento llamado")
        val dialogView = layoutInflater.inflate(R.layout.dialog_agregar_documento, null)
        
        val spinnerTipo = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.spinnerTipoDocumento)
        val editEmpresa = dialogView.findViewById<TextInputEditText>(R.id.edtEmpresa)
        val editTelefono = dialogView.findViewById<TextInputEditText>(R.id.edtTelefono)
        val editVencimiento = dialogView.findViewById<TextInputEditText>(R.id.edtFechaVencimiento)
        val editObservaciones = dialogView.findViewById<TextInputEditText>(R.id.edtObservaciones)
        
        // Configurar dropdown de tipos
        val tipos = TipoDocumento.values().map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, tipos)
        spinnerTipo.setAdapter(adapter)
        
        spinnerTipo.setOnClickListener { spinnerTipo.showDropDown() }
        spinnerTipo.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) spinnerTipo.showDropDown() }
        
        // Configurar date picker
        editVencimiento.setOnClickListener { showDatePicker(editVencimiento) }
        
        // Crear diálogo
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Agregar Documento")
            .setView(dialogView)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        
        dialog.show()
        
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selectedTipo = spinnerTipo.text.toString()
            val tipoIndex = tipos.indexOf(selectedTipo)
            
            if (tipoIndex == -1) {
                Toast.makeText(this, "Seleccione un tipo de documento", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val vencimiento = parseDate(editVencimiento.text.toString())
            if (vencimiento == null) {
                Toast.makeText(this, "Complete la fecha de vencimiento", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            guardarDocumento(
                tipo = TipoDocumento.values()[tipoIndex],
                empresa = editEmpresa.text.toString(),
                telefono = editTelefono.text.toString(),
                vencimiento = vencimiento,
                observaciones = editObservaciones.text.toString(),
                dialog = dialog
            )
        }
    }

    private fun mostrarDialogoAgregarPersonal() {
        Log.d("AdministracionLocal", "mostrarDialogoAgregarPersonal llamado")
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_agregar_personal, null)
        
        // Establecer dialogView para uso en pickImage
        this.dialogView = dialogView
        
        val editNombre = dialogView.findViewById<EditText>(R.id.edtNombre)
        val editApellido = dialogView.findViewById<EditText>(R.id.edtApellido)
        val editDni = dialogView.findViewById<EditText>(R.id.edtDni)
        val editCargo = dialogView.findViewById<EditText>(R.id.edtCargo)
        val editTelefono = dialogView.findViewById<TextInputEditText>(R.id.edtTelefono)
        val editEmail = dialogView.findViewById<TextInputEditText>(R.id.edtEmail)
        val editFechaIngreso = dialogView.findViewById<TextInputEditText>(R.id.edtFechaIngreso)
        val editLibretaVencimiento = dialogView.findViewById<TextInputEditText>(R.id.edtVencimientoLibreta)
        val editCursoVencimiento = dialogView.findViewById<TextInputEditText>(R.id.edtVencimientoCurso)
        val chkPoseeLibreta = dialogView.findViewById<CheckBox>(R.id.chkPoseeLibreta)
        val chkPoseeCurso = dialogView.findViewById<CheckBox>(R.id.chkPoseeCurso)
        val layoutLibretaSanitaria = dialogView.findViewById<View>(R.id.layoutLibretaSanitaria)
        val layoutCursoManipulacion = dialogView.findViewById<View>(R.id.layoutCursoManipulacion)
        val btnSeleccionarLibreta = dialogView.findViewById<Button>(R.id.btnSeleccionarLibreta)
        val btnSeleccionarCurso = dialogView.findViewById<Button>(R.id.btnSeleccionarCurso)
        val imgPreviewLibreta = dialogView.findViewById<ImageView>(R.id.imgPreviewLibreta)
        val imgPreviewCurso = dialogView.findViewById<ImageView>(R.id.imgPreviewCurso)
        
        // Reset variables
        libretaSanitariaBase64 = null
        cursoManipulacionBase64 = null
        
        chkPoseeLibreta.setOnCheckedChangeListener { _, isChecked ->
            layoutLibretaSanitaria.visibility = if (isChecked) View.VISIBLE else View.GONE
            btnSeleccionarLibreta.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                libretaSanitariaBase64 = null
                imgPreviewLibreta.visibility = View.GONE
            }
        }
        
        chkPoseeCurso.setOnCheckedChangeListener { _, isChecked ->
            layoutCursoManipulacion.visibility = if (isChecked) View.VISIBLE else View.GONE
            btnSeleccionarCurso.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                cursoManipulacionBase64 = null
                imgPreviewCurso.visibility = View.GONE
            }
        }
        
        btnSeleccionarLibreta.setOnClickListener {
            isSeleccionandoLibreta = true
            pickMultipleImages.launch("image/*")
        }
        
        btnSeleccionarCurso.setOnClickListener {
            isSeleccionandoLibreta = false
            pickMultipleImages.launch("image/*")
        }
        
        editFechaIngreso.setOnClickListener { showDatePicker(editFechaIngreso) }
        editLibretaVencimiento.setOnClickListener { showDatePicker(editLibretaVencimiento) }
        editCursoVencimiento.setOnClickListener { showDatePicker(editCursoVencimiento) }
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Agregar Personal")
            .setView(dialogView)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()
            
        dialog.show()
        
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nombre = editNombre.text.toString()
            val dni = editDni.text.toString()
            val cargo = editCargo.text.toString()
            
            Log.d("DialogoPersonal", "Botón guardar presionado - nombre: $nombre, dni: $dni, cargo: $cargo")
            Log.d("DialogoPersonal", "chkPoseeLibreta: ${chkPoseeLibreta.isChecked}, chkPoseeCurso: ${chkPoseeCurso.isChecked}")
            Log.d("DialogoPersonal", "libretaSanitariaBase64 length: ${libretaSanitariaBase64?.length ?: 0}")
            Log.d("DialogoPersonal", "cursoManipulacionBase64 length: ${cursoManipulacionBase64?.length ?: 0}")
            
            if (nombre.isBlank() || dni.isBlank() || cargo.isBlank()) {
                Toast.makeText(this, "Complete los campos obligatorios (Nombre, DNI, Cargo)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            guardarPersonal(
                nombre = nombre,
                apellido = editApellido.text.toString(),
                dni = dni,
                cargo = cargo,
                telefono = editTelefono.text.toString(),
                email = editEmail.text.toString(),
                fechaIngreso = parseDate(editFechaIngreso.text.toString()),
                libretaVencimiento = if (chkPoseeLibreta.isChecked) parseDate(editLibretaVencimiento.text.toString()) else null,
                cursoVencimiento = if (chkPoseeCurso.isChecked) parseDate(editCursoVencimiento.text.toString()) else null,
                libretaBase64 = libretaSanitariaBase64,
                cursoBase64 = cursoManipulacionBase64,
                dialog = dialog
            )
        }
    }

    private fun mostrarDialogoEditarDocumento(documento: DocumentoLocal) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_agregar_documento, null)
        val spinnerTipo = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.spinnerTipoDocumento)
        val editEmpresa = dialogView.findViewById<TextInputEditText>(R.id.edtEmpresa)
        val editTelefono = dialogView.findViewById<TextInputEditText>(R.id.edtTelefono)
        val editVencimiento = dialogView.findViewById<TextInputEditText>(R.id.edtFechaVencimiento)
        val editObservaciones = dialogView.findViewById<TextInputEditText>(R.id.edtObservaciones)
        
        val tipos = TipoDocumento.values().map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, tipos)
        spinnerTipo.setAdapter(adapter)
        spinnerTipo.setOnClickListener { spinnerTipo.showDropDown() }
        
        spinnerTipo.setText(documento.tipoDocumento.displayName, false)
        editEmpresa.setText(documento.empresaResponsable)
        editTelefono.setText(documento.telefono)
        editVencimiento.setText(documento.fechaVencimiento?.let { SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(it) } ?: "")
        editObservaciones.setText(documento.observaciones)
        
        editVencimiento.setOnClickListener { showDatePicker(editVencimiento) }
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Editar Documento")
            .setView(dialogView)
            .setPositiveButton("Actualizar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        
        dialog.show()
        
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selectedTipo = spinnerTipo.text.toString()
            val tipoIndex = tipos.indexOf(selectedTipo)
            val vencimiento = parseDate(editVencimiento.text.toString())
            
            if (tipoIndex == -1 || vencimiento == null) {
                Toast.makeText(this, "Complete los campos obligatorios", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            actualizarDocumento(
                documento = documento.copy(
                    tipoDocumento = TipoDocumento.values()[tipoIndex],
                    empresaResponsable = editEmpresa.text.toString(),
                    telefono = editTelefono.text.toString(),
                    fechaVencimiento = vencimiento,
                    observaciones = editObservaciones.text.toString(),
                    estado = when {
                        DateHelper.isDateExpired(vencimiento) -> EstadoDocumento.VENCIDO
                        DateHelper.getDaysUntilDate(vencimiento) <= 30 -> EstadoDocumento.POR_VENCER
                        else -> EstadoDocumento.VIGENTE
                    }
                ),
                dialog = dialog
            )
        }
    }

    private fun mostrarDialogoEditarPersonal(personal: PersonalLocal) {
        dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_agregar_personal, null)
        val view = dialogView!!
        val editNombre = view.findViewById<EditText>(R.id.edtNombre)
        val editApellido = view.findViewById<EditText>(R.id.edtApellido)
        val editDni = view.findViewById<EditText>(R.id.edtDni)
        val editCargo = view.findViewById<EditText>(R.id.edtCargo)
        val editTelefono = view.findViewById<TextInputEditText>(R.id.edtTelefono)
        val editEmail = view.findViewById<TextInputEditText>(R.id.edtEmail)
        val editFechaIngreso = view.findViewById<TextInputEditText>(R.id.edtFechaIngreso)
        val editLibretaVencimiento = view.findViewById<TextInputEditText>(R.id.edtVencimientoLibreta)
        val editCursoVencimiento = view.findViewById<TextInputEditText>(R.id.edtVencimientoCurso)
        val chkPoseeLibreta = view.findViewById<CheckBox>(R.id.chkPoseeLibreta)
        val chkPoseeCurso = view.findViewById<CheckBox>(R.id.chkPoseeCurso)
        val layoutLibretaSanitaria = view.findViewById<View>(R.id.layoutLibretaSanitaria)
        val layoutCursoManipulacion = view.findViewById<View>(R.id.layoutCursoManipulacion)
        val btnSeleccionarLibreta = view.findViewById<Button>(R.id.btnSeleccionarLibreta)
        val btnSeleccionarCurso = view.findViewById<Button>(R.id.btnSeleccionarCurso)
        val imgPreviewLibreta = view.findViewById<ImageView>(R.id.imgPreviewLibreta)
        val imgPreviewCurso = view.findViewById<ImageView>(R.id.imgPreviewCurso)
        
        // Reset variables
        libretaSanitariaBase64 = personal.libretaSanitariaBase64
        cursoManipulacionBase64 = personal.cursoManipulacionBase64
        
        chkPoseeLibreta.setOnCheckedChangeListener { _, isChecked ->
            layoutLibretaSanitaria.visibility = if (isChecked) View.VISIBLE else View.GONE
            btnSeleccionarLibreta.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                libretaSanitariaBase64 = null
                imgPreviewLibreta.visibility = View.GONE
            }
        }
        
        chkPoseeCurso.setOnCheckedChangeListener { _, isChecked ->
            layoutCursoManipulacion.visibility = if (isChecked) View.VISIBLE else View.GONE
            btnSeleccionarCurso.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                cursoManipulacionBase64 = null
                imgPreviewCurso.visibility = View.GONE
            }
        }
        
        btnSeleccionarLibreta.setOnClickListener {
            isSeleccionandoLibreta = true
            pickMultipleImages.launch("image/*")
        }
        
        btnSeleccionarCurso.setOnClickListener {
            isSeleccionandoLibreta = false
            pickMultipleImages.launch("image/*")
        }
        
        editNombre.setText(personal.nombre)
        editApellido.setText(personal.apellido)
        editDni.setText(personal.dni)
        editCargo.setText(personal.cargo)
        editTelefono.setText(personal.telefono)
        editEmail.setText(personal.email)
        editFechaIngreso.setText(personal.fechaIngreso?.let { SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(it) } ?: "")
        
        personal.libretaSanitaria?.let { libreta ->
            chkPoseeLibreta.isChecked = true
            layoutLibretaSanitaria.visibility = View.VISIBLE
            btnSeleccionarLibreta.visibility = View.VISIBLE
            editLibretaVencimiento.setText(libreta.fechaVencimiento?.let { SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(it) } ?: "")
        }
        personal.cursoManipulacion?.let { curso ->
            chkPoseeCurso.isChecked = true
            layoutCursoManipulacion.visibility = View.VISIBLE
            btnSeleccionarCurso.visibility = View.VISIBLE
            editCursoVencimiento.setText(curso.fechaVencimiento?.let { SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(it) } ?: "")
        }
        
        // Mostrar imágenes existentes
        personal.libretaSanitariaBase64?.let { base64 ->
            try {
                val base64Data = if (base64.startsWith("data:image")) {
                    base64.substringAfter(",")
                } else {
                    base64
                }
                val imageData = Base64.decode(base64Data, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                if (bitmap != null) {
                    imgPreviewLibreta.setImageBitmap(bitmap)
                    imgPreviewLibreta.visibility = View.VISIBLE
                } else {
                    // Bitmap es null, no hacer nada
                }
            } catch (e: Exception) {
                Log.e("EditarPersonal", "Error cargando imagen libreta", e)
            }
        }
        personal.cursoManipulacionBase64?.let { base64 ->
            try {
                val base64Data = if (base64.startsWith("data:image")) {
                    base64.substringAfter(",")
                } else {
                    base64
                }
                val imageData = Base64.decode(base64Data, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                if (bitmap != null) {
                    imgPreviewCurso.setImageBitmap(bitmap)
                    imgPreviewCurso.visibility = View.VISIBLE
                } else {
                    // Bitmap es null, no hacer nada
                }
            } catch (e: Exception) {
                Log.e("EditarPersonal", "Error cargando imagen curso", e)
            }
        }
        
        editFechaIngreso.setOnClickListener { showDatePicker(editFechaIngreso) }
        editLibretaVencimiento.setOnClickListener { showDatePicker(editLibretaVencimiento) }
        editCursoVencimiento.setOnClickListener { showDatePicker(editCursoVencimiento) }
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Editar Personal")
            .setView(view)
            .setPositiveButton("Actualizar", null)
            .setNegativeButton("Cancelar", null)
            .create()
            
        dialog.show()
        
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nombre = editNombre.text.toString()
            val dni = editDni.text.toString()
            val cargo = editCargo.text.toString()
            
            if (nombre.isBlank() || dni.isBlank() || cargo.isBlank()) {
                Toast.makeText(this, "Complete los campos obligatorios", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            actualizarPersonal(
                personal = personal.copy(
                    nombre = nombre,
                    apellido = editApellido.text.toString(),
                    dni = dni,
                    cargo = cargo,
                    telefono = editTelefono.text.toString(),
                    email = editEmail.text.toString(),
                    fechaIngreso = parseDate(editFechaIngreso.text.toString()),
                    libretaSanitaria = if (chkPoseeLibreta.isChecked) {
                        val venc = parseDate(editLibretaVencimiento.text.toString())
                        if (venc != null) {
                            LibretaSanitaria(
                                numeroLibreta = "Libreta Sanitaria",
                                fechaVencimiento = venc,
                                estado = when {
                                    DateHelper.isDateExpired(venc) -> EstadoCertificado.VENCIDO
                                    DateHelper.getDaysUntilDate(venc) <= 30 -> EstadoCertificado.POR_VENCER
                                    else -> EstadoCertificado.VIGENTE
                                },
                                diasParaVencer = DateHelper.getDaysUntilDate(venc)
                            )
                        } else null
                    } else null,
                    cursoManipulacion = if (chkPoseeCurso.isChecked) {
                        val venc = parseDate(editCursoVencimiento.text.toString())
                        if (venc != null) {
                            CursoManipulacion(
                                institucion = "Curso de Manipulación",
                                tipoCurso = "Manipulación de Alimentos",
                                fechaVencimiento = venc,
                                estado = when {
                                    DateHelper.isDateExpired(venc) -> EstadoCertificado.VENCIDO
                                    DateHelper.getDaysUntilDate(venc) <= 30 -> EstadoCertificado.POR_VENCER
                                    else -> EstadoCertificado.VIGENTE
                                },
                                diasParaVencer = DateHelper.getDaysUntilDate(venc)
                            )
                        } else null
                    } else null,
                    libretaSanitariaBase64 = libretaSanitariaBase64,
                    cursoManipulacionBase64 = cursoManipulacionBase64
                ),
                dialog = dialog
            )
        }
    }
    
    private fun mostrarDialogoDetalleDocumento(documento: DocumentoLocal) {
        val sdf = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        val mensaje = """
            Documento: ${documento.nombreDocumento}
            Tipo: ${documento.tipoDocumento.displayName}
            Estado: ${documento.estado.displayName}
            Empresa: ${documento.empresaResponsable}
            Vencimiento: ${documento.fechaVencimiento?.let { sdf.format(it) } ?: "No especificada"}
            Observaciones: ${documento.observaciones.ifEmpty { "Sin observaciones" }}
        """.trimIndent()
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Detalle del Documento")
            .setMessage(mensaje)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Editar") { _, _ -> mostrarDialogoEditarDocumento(documento) }
            .setNegativeButton("Eliminar") { _, _ -> mostrarDialogoEliminarDocumento(documento) }
            .show()
    }

    private fun mostrarDialogoDetallePersonal(personal: PersonalLocal) {
        val sdf = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        val mensaje = """
            Nombre: ${personal.nombreCompleto}
            DNI: ${personal.dni}
            Cargo: ${personal.cargo}
            Teléfono: ${personal.telefono}
            Ingreso: ${personal.fechaIngreso?.let { sdf.format(it) } ?: "No especificada"}
            Libreta: ${personal.libretaSanitaria?.fechaVencimiento?.let { sdf.format(it) } ?: "No posee"}
            Curso: ${personal.cursoManipulacion?.fechaVencimiento?.let { sdf.format(it) } ?: "No posee"}
        """.trimIndent()
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Detalles del Personal")
            .setMessage(mensaje)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Editar") { _, _ -> mostrarDialogoEditarPersonal(personal) }
            .setNegativeButton("Eliminar") { _, _ -> mostrarDialogoEliminarPersonal(personal) }
            .show()
    }

    private fun mostrarDialogoEliminarDocumento(documento: DocumentoLocal) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar Documento")
            .setMessage("¿Está seguro que desea eliminar \"${documento.nombreDocumento}\"?")
            .setPositiveButton("Eliminar") { _, _ ->
                documentosLocales.removeIf { it.id == documento.id }
                eliminarDocumentoDeFirebase(documento.id)
                actualizarUI()
                cargarDocumentosUI()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarDialogoEliminarPersonal(personal: PersonalLocal) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar Personal")
            .setMessage("¿Está seguro que desea eliminar a ${personal.nombreCompleto}?")
            .setPositiveButton("Eliminar") { _, _ ->
                personalLocal.removeIf { it.id == personal.id }
                FirebaseFirestore.getInstance().collection("locales").document(localId)
                    .collection("personal").document(personal.id).delete()
                actualizarUI()
                cargarPersonalUI()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun guardarDocumento(
        tipo: TipoDocumento,
        empresa: String,
        telefono: String,
        vencimiento: Date?,
        observaciones: String,
        dialog: AlertDialog? = null
    ) {
        if (vencimiento == null) {
            Toast.makeText(this, "Complete la fecha de vencimiento", Toast.LENGTH_SHORT).show()
            return
        }
        
        val documento = DocumentoLocal(
            id = "doc_${System.currentTimeMillis()}",
            localId = localId,
            tipoDocumento = tipo,
            nombreDocumento = tipo.displayName,
            empresaResponsable = empresa,
            telefono = telefono,
            numeroCertificado = "",
            fechaEmision = null,
            fechaVencimiento = vencimiento,
            observaciones = observaciones,
            diasParaVencer = DateHelper.getDaysUntilDate(vencimiento),
            estado = when {
                DateHelper.isDateExpired(vencimiento) -> EstadoDocumento.VENCIDO
                DateHelper.getDaysUntilDate(vencimiento) <= 30 -> EstadoDocumento.POR_VENCER
                else -> EstadoDocumento.VIGENTE
            }
        )
        
        documentosLocales.add(documento)
        guardarDocumentoEnFirebase(documento) { exito ->
            if (exito) {
                generarNotificaciones()
                actualizarUI()
                cargarDocumentosUI()
                Toast.makeText(this, "Documento guardado exitosamente", Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
            } else {
                Toast.makeText(this, "Error al guardar documento", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun guardarPersonal(
        nombre: String,
        apellido: String,
        dni: String,
        cargo: String,
        telefono: String,
        email: String,
        fechaIngreso: Date?,
        libretaVencimiento: Date?,
        cursoVencimiento: Date?,
        libretaBase64: String? = null,
        cursoBase64: String? = null,
        dialog: AlertDialog? = null
    ) {
        Log.d("GuardarPersonal", "Iniciando guardado - nombre: $nombre, libretaBase64: ${libretaBase64?.take(50)}..., cursoBase64: ${cursoBase64?.take(50)}...")
        Log.d("GuardarPersonal", "cursoVencimiento: $cursoVencimiento, cursoBase64 length: ${cursoBase64?.length ?: 0}")
        
        val personal = PersonalLocal(
            id = "per_${System.currentTimeMillis()}",
            localId = localId,
            nombre = nombre,
            apellido = apellido,
            dni = dni,
            cargo = cargo,
            telefono = telefono,
            email = email,
            fechaIngreso = fechaIngreso,
            estado = EstadoPersonal.ACTIVO,
            libretaSanitaria = if (libretaVencimiento != null) {
                LibretaSanitaria(
                    numeroLibreta = "Libreta Sanitaria",
                    fechaVencimiento = libretaVencimiento,
                    estado = when {
                        DateHelper.isDateExpired(libretaVencimiento) -> EstadoCertificado.VENCIDO
                        DateHelper.getDaysUntilDate(libretaVencimiento) <= 30 -> EstadoCertificado.POR_VENCER
                        else -> EstadoCertificado.VIGENTE
                    },
                    diasParaVencer = DateHelper.getDaysUntilDate(libretaVencimiento)
                )
            } else null,
            cursoManipulacion = if (cursoVencimiento != null) {
                CursoManipulacion(
                    institucion = "Curso de Manipulación",
                    tipoCurso = "Manipulación de Alimentos",
                    fechaVencimiento = cursoVencimiento,
                    estado = when {
                        DateHelper.isDateExpired(cursoVencimiento) -> EstadoCertificado.VENCIDO
                        DateHelper.getDaysUntilDate(cursoVencimiento) <= 30 -> EstadoCertificado.POR_VENCER
                        else -> EstadoCertificado.VIGENTE
                    },
                    diasParaVencer = DateHelper.getDaysUntilDate(cursoVencimiento)
                )
            } else null,
            libretaSanitariaBase64 = libretaBase64,
            cursoManipulacionBase64 = cursoBase64
        )
        
        Log.d("GuardarPersonal", "Personal creado - cursoManipulacionBase64 length: ${personal.cursoManipulacionBase64?.length ?: 0}")
        
        personalLocal.add(personal)
        guardarPersonalEnFirebase(personal) { exito, mensajeError ->
            if (exito) {
                generarNotificaciones()
                actualizarUI()
                cargarPersonalUI()
                Toast.makeText(this, "Personal guardado exitosamente", Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
                this.dialogView = null
            } else {
                Toast.makeText(this, mensajeError ?: "Error al guardar personal", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun actualizarDocumento(documento: DocumentoLocal, dialog: AlertDialog? = null) {
        val index = documentosLocales.indexOfFirst { it.id == documento.id }
        if (index != -1) {
            documentosLocales[index] = documento
            guardarDocumentoEnFirebase(documento) { exito ->
                if (exito) {
                    generarNotificaciones()
                    actualizarUI()
                    cargarDocumentosUI()
                    Toast.makeText(this, "Documento actualizado exitosamente", Toast.LENGTH_SHORT).show()
                    dialog?.dismiss()
                } else {
                    Toast.makeText(this, "Error al actualizar documento", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun actualizarPersonal(personal: PersonalLocal, dialog: AlertDialog? = null) {
        val index = personalLocal.indexOfFirst { it.id == personal.id }
        if (index != -1) {
            personalLocal[index] = personal
            guardarPersonalEnFirebase(personal) { exito, mensajeError ->
                if (exito) {
                    generarNotificaciones()
                    actualizarUI()
                    cargarPersonalUI()
                    Toast.makeText(this, "Personal actualizado exitosamente", Toast.LENGTH_SHORT).show()
                    dialog?.dismiss()
                    this.dialogView = null
                } else {
                    Toast.makeText(this, mensajeError ?: "Error al actualizar personal", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun eliminarDocumentoDeFirebase(id: String) {
        FirebaseFirestore.getInstance().collection("locales").document(localId)
            .collection("documentos").document(id).delete()
    }

    private fun guardarDocumentoEnFirebase(documento: DocumentoLocal, onComplete: (Boolean) -> Unit) {
        FirebaseFirestore.getInstance().collection("locales").document(localId)
            .collection("documentos").document(documento.id).set(documento)
            .addOnSuccessListener {
                Log.d("AdministracionLocal", "Documento guardado exitosamente en Firebase: ${documento.id}")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e("AdministracionLocal", "Error guardando documento en Firebase", e)
                onComplete(false)
            }
    }

    private fun guardarPersonalEnFirebase(personal: PersonalLocal, onComplete: (Boolean, String?) -> Unit) {
        // Validar que las imágenes adjuntas no excedan el límite de Firestore (~1 MB)
        val totalBase64Len = (personal.libretaSanitariaBase64?.length ?: 0) + (personal.cursoManipulacionBase64?.length ?: 0)
        if (totalBase64Len > 900000) {
            Log.w("AdministracionLocal", "Imágenes adjuntas muy grandes: $totalBase64Len caracteres base64 — comprimiendo automáticamente")
            val libretaComprimida = personal.libretaSanitariaBase64?.let { comprimirBase64SiNecesario(it) }
            val cursoComprimido = personal.cursoManipulacionBase64?.let { comprimirBase64SiNecesario(it) }
            val nuevoTotal = (libretaComprimida?.length ?: 0) + (cursoComprimido?.length ?: 0)
            Log.d("AdministracionLocal", "Compresión automática: $totalBase64Len → $nuevoTotal caracteres base64")
            if (nuevoTotal > 900000) {
                onComplete(false, "La imagen o imágenes adjuntas son muy grandes incluso después de comprimir. Probá con una foto de menor tamaño.")
            } else {
                guardarPersonalEnFirebase(
                    personal.copy(
                        libretaSanitariaBase64 = libretaComprimida,
                        cursoManipulacionBase64 = cursoComprimido
                    ),
                    onComplete
                )
            }
            return
        }

        FirebaseFirestore.getInstance().collection("locales").document(localId)
            .collection("personal").document(personal.id).set(personal)
            .addOnSuccessListener {
                Log.d("AdministracionLocal", "Personal guardado exitosamente en Firebase: ${personal.id}")
                onComplete(true, null)
            }
            .addOnFailureListener { e ->
                Log.e("AdministracionLocal", "Error guardando personal en Firebase", e)
                val msg = e.message ?: ""
                val esMuyGrande = msg.contains("larger than", ignoreCase = true)
                        || msg.contains("exceeds the maximum", ignoreCase = true)
                        || msg.contains("cannot write", ignoreCase = true)
                        || msg.contains("too large", ignoreCase = true)
                        || msg.contains("size", ignoreCase = true)
                val mensaje = if (esMuyGrande) {
                    "La imagen o imágenes adjuntas son muy grandes. Reducí la calidad o el tamaño de la foto e intentá de nuevo."
                } else {
                    "Error al guardar personal: $msg"
                }
                onComplete(false, mensaje)
            }
    }

    private fun showDatePicker(editText: EditText) {
        val calendar = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            calendar.set(y, m, d)
            editText.setText(SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(calendar.time))
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun convertImageToBase64(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return "data:image/jpeg;base64," + Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /**
     * Comprime una imagen base64 reduciendo calidad y dimensiones hasta quedar por debajo de maxLen.
     * Si ya está por debajo del límite, la devuelve sin cambios.
     */
    private fun comprimirBase64SiNecesario(base64: String, maxLen: Int = 700000): String {
        if (base64.length <= maxLen) return base64

        return try {
            val datos = base64.substringAfter("base64,", base64)
            val bytes = Base64.decode(datos, Base64.NO_WRAP)
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return base64

            var calidad = 70
            var resultado = base64

            for (intento in 1..8) {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, calidad, outputStream)
                resultado = "data:image/jpeg;base64," + Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                Log.d("ComprimirImagen", "Intento $intento: ${bitmap.width}x${bitmap.height} q=$calidad -> ${resultado.length} chars")
                if (resultado.length <= maxLen) break

                // Reducir calidad primero, luego dimensiones
                if (calidad > 40) {
                    calidad -= 15
                } else {
                    val nuevoAncho = (bitmap.width * 0.75).toInt().coerceAtLeast(200)
                    val nuevoAlto = (bitmap.height * 0.75).toInt().coerceAtLeast(200)
                    bitmap = Bitmap.createScaledBitmap(bitmap, nuevoAncho, nuevoAlto, true)
                }
            }

            resultado
        } catch (e: Exception) {
            Log.e("ComprimirImagen", "Error comprimiendo imagen", e)
            base64
        }
    }

    private fun convertMultipleImagesToBase64(uris: List<Uri>): String {
        if (uris.isEmpty()) return ""
        if (uris.size == 1) return convertImageToBase64(uris[0])

        val bitmaps = uris.mapNotNull { uri ->
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            } catch (e: Exception) {
                Log.e("Collage", "Error leyendo uri $uri", e)
                null
            }
        }

        if (bitmaps.isEmpty()) return ""
        if (bitmaps.size == 1) {
            val outputStream = ByteArrayOutputStream()
            bitmaps[0].compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            return "data:image/jpeg;base64," + Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        }

        val collageBitmap = crearCollageBitmaps(bitmaps)
        val outputStream = ByteArrayOutputStream()
        collageBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return "data:image/jpeg;base64," + Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun crearCollageBitmaps(bitmaps: List<Bitmap>): Bitmap {
        val targetWidth = 1000
        val scaledBitmaps = bitmaps.map { bmp ->
            val scale = targetWidth.toFloat() / bmp.width.toFloat()
            val targetHeight = (bmp.height * scale).toInt()
            Bitmap.createScaledBitmap(bmp, targetWidth, targetHeight, true)
        }

        val headerMargin = 40
        val dividerSpace = 15
        var totalHeight = 0
        scaledBitmaps.forEach { totalHeight += it.height + headerMargin + dividerSpace }

        val combinedBitmap = Bitmap.createBitmap(targetWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(combinedBitmap)
        
        canvas.drawColor(android.graphics.Color.parseColor("#0F172A"))

        val paintText = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FBBF24")
            textSize = 28f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val paintLine = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#E6C874")
            strokeWidth = 4f
            isAntiAlias = true
        }

        var currentY = 0f
        scaledBitmaps.forEachIndexed { index, bmp ->
            val tag = if (index == 0) "📷 FRENTE" else if (index == 1) "📷 DORSO / REVERSO" else "📷 PARTE ${index + 1}"
            canvas.drawText(tag, 20f, currentY + 30f, paintText)
            currentY += headerMargin

            canvas.drawBitmap(bmp, 0f, currentY, null)
            currentY += bmp.height

            canvas.drawLine(0f, currentY + 5f, targetWidth.toFloat(), currentY + 5f, paintLine)
            currentY += dividerSpace
        }

        return combinedBitmap
    }

    private fun parseDate(dateString: String): Date? {
        if (dateString.isBlank()) return null
        return try {
            val sdf = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
            sdf.timeZone = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
            sdf.parse(dateString)
        } catch (e: Exception) { null }
    }
    
    private fun mostrarVisorImagen(titulo: String, imagenBase64: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_visor_imagen, null)
        val imageView = dialogView.findViewById<ImageView>(R.id.imgVisor)
        val btnCerrar = dialogView.findViewById<Button>(R.id.btnCerrar)
        val btnDescargar = dialogView.findViewById<Button>(R.id.btnDescargar)
        val txtTitulo = dialogView.findViewById<TextView>(R.id.txtTitulo)
        
        txtTitulo.text = titulo
        
        // Decodificar y mostrar imagen
        try {
            Log.d("VisorImagen", "Decodificando imagen, longitud: ${imagenBase64.length}")
            
            // Remover el prefijo data:image/jpeg;base64, si existe
            val base64Data = if (imagenBase64.startsWith("data:image")) {
                imagenBase64.substringAfter(",")
            } else {
                imagenBase64
            }
            
            Log.d("VisorImagen", "Base64 data length: ${base64Data.length}")
            
            val imageData = Base64.decode(base64Data, Base64.DEFAULT)
            Log.d("VisorImagen", "Image data decoded, length: ${imageData.size}")
            
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            
            if (bitmap != null) {
                Log.d("VisorImagen", "Bitmap decoded successfully: ${bitmap.width}x${bitmap.height}")
                imageView.setImageBitmap(bitmap)
                imageView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            } else {
                Log.e("VisorImagen", "Bitmap decode returned null")
                Toast.makeText(this, "Error al decodificar la imagen", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("VisorImagen", "Error cargando imagen", e)
            Toast.makeText(this, "Error al cargar la imagen: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()
        
        btnCerrar.setOnClickListener {
            dialog.dismiss()
        }
        
        btnDescargar.setOnClickListener {
            descargarImagen(imagenBase64, titulo)
        }
        
        dialog.show()
    }
    
    private fun descargarImagen(imagenBase64: String, nombre: String) {
        try {
            // Remover el prefijo data:image/jpeg;base64, si existe
            val base64Data = if (imagenBase64.startsWith("data:image")) {
                imagenBase64.substringAfter(",")
            } else {
                imagenBase64
            }
            
            val imageData = Base64.decode(base64Data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            
            if (bitmap == null) {
                Toast.makeText(this, "Error al decodificar la imagen", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Guardar en almacenamiento externo
            val filename = "${nombre.replace(" ", "_")}_${System.currentTimeMillis()}.jpg"
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/InventarioApp")
            }
            
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                val outputStream = contentResolver.openOutputStream(it)
                outputStream?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                }
                Toast.makeText(this, "Imagen guardada en Galería", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("DescargarImagen", "Error descargando imagen", e)
            Toast.makeText(this, "Error al descargar la imagen: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun marcarNotificacionComoLeida(notificacion: NotificacionVencimiento) {
        val index = notificaciones.indexOfFirst { it.id == notificacion.id }
        if (index != -1) {
            notificaciones[index] = notificacion.copy(leida = true)
            actualizarUI()
        }
    }

    private fun formatTimeRemaining(dias: Int): String {
        return when {
            dias < 0 -> "Vencido hace ${-dias} días"
            dias == 0 -> "Vence hoy"
            dias == 1 -> "Vence mañana"
            dias < 30 -> "Vence en $dias días"
            else -> {
                val meses = dias / 30
                if (meses == 1) "Vence en 1 mes"
                else "Vence en $meses meses"
            }
        }
    }

    private fun actualizarUI() {
        txtLocalActual.text = "Administración: $localNombre"
        txtTotalDocumentos.text = documentosLocales.size.toString()
        txtTotalPersonal.text = personalLocal.size.toString()
        txtAlertasActivas.text = notificaciones.count { !it.leida }.toString()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}
