package com.tuapp.inventario.ui.dialogs

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.Window
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

data class GuideStep(
    val key: String,
    val section: String,
    val title: String,
    val text: String,
    val iconEmoji: String,
    val visualType: String
)

object InteractiveGuideHelper {

    private const val TAG = "InteractiveGuideHelper"
    private const val PREFS_NAME = "guide_prefs"
    private const val KEY_GUIDE_SEEN = "guide_seen_v5"

    /**
     * Devuelve la guía adaptativa según el rol de la sesión
     */
    fun getGestionLocalesSteps(rolUsuario: String, usuarioNombre: String = ""): List<GuideStep> {
        val rolLower = rolUsuario.lowercase()
        val isMaestro = rolLower.contains("maestro") || rolLower.contains("admin_region")
        val isAsistente = rolLower.contains("asistente")
        val isSupervisor = rolLower.contains("supervisor") && !isAsistente

        val roleLabel = if (isMaestro) "Maestro" else if (isAsistente) "Asistente" else "Supervisor"
        val roleDesc = if (isMaestro) {
            "Tienes control total sobre todos los locales, supervisores y configuraciones de la región."
        } else if (isAsistente) {
            "Tienes acceso completo para gestionar locales y supervisores, igual que el maestro."
        } else {
            "Gestionas los locales asignados a tu cuenta y puedes ingresar a cada uno para controlar su inventario."
        }

        val steps = mutableListOf<GuideStep>()

        steps.add(
            GuideStep(
                key = "gl_welcome",
                section = "gestion-locales",
                title = "Gestión de Locales — $roleLabel",
                text = "Bienvenido $roleLabel. $roleDesc Esta guía te mostrará las funciones disponibles para tu rol.",
                iconEmoji = "🏢",
                visualType = "gl_welcome"
            )
        )

        steps.add(
            GuideStep(
                key = "gl_header",
                section = "gestion-locales",
                title = "Tu Identidad y Rol",
                text = "Se muestra tu rol y nombre (\"$roleLabel: ${usuarioNombre.ifEmpty { "Usuario" }}\"). Esto confirma con qué permisos estás operando.",
                iconEmoji = "👤",
                visualType = "gl_header"
            )
        )

        steps.add(
            GuideStep(
                key = "gl_tabs",
                section = "gestion-locales",
                title = "Pestañas Principales",
                text = if (isMaestro || isAsistente) {
                    "Tienes dos pestañas: 'Locales' para gestionar los locales de la región, y 'Supervisores' para crear y administrar cuentas de supervisores y asistentes."
                } else {
                    "Aquí ves el listado de los locales asignados a tu cuenta."
                },
                iconEmoji = "🗂️",
                visualType = "gl_tabs"
            )
        )

        steps.add(
            GuideStep(
                key = "gl_search",
                section = "gestion-locales",
                title = "Buscador de Locales",
                text = "Usa este campo para filtrar locales o supervisores por nombre o email. Es útil cuando tienes muchos locales.",
                iconEmoji = "🔍",
                visualType = "gl_search"
            )
        )

        if (isMaestro || isAsistente) {
            steps.add(
                GuideStep(
                    key = "gl_new_local",
                    section = "gestion-locales",
                    title = "Crear Nuevo Local",
                    text = "Con el botón '+ Nuevo Local' das de alta una nueva sucursal. Se genera automáticamente con contraseña inicial 'Inicial2026'.",
                    iconEmoji = "🏪",
                    visualType = "gl_new_local"
                )
            )
            steps.add(
                GuideStep(
                    key = "gl_new_sup",
                    section = "gestion-locales",
                    title = "Crear Nuevo Supervisor",
                    text = "Con '+ Nuevo Supervisor' creas cuentas de equipo. Puedes marcar 'Es asistente del maestro' para darle acceso completo. Contraseña inicial: 'Inicial2026'.",
                    iconEmoji = "➕",
                    visualType = "gl_new_sup"
                )
            )
        }

        steps.add(
            GuideStep(
                key = "gl_lista",
                section = "gestion-locales",
                title = "Listado de Locales",
                text = "Cada tarjeta muestra el nombre del local, su razón social y su estado. El badge 'En Actividad' (verde) indica cargas recientes en los últimos 7 días.",
                iconEmoji = "📑",
                visualType = "gl_lista"
            )
        )

        steps.add(
            GuideStep(
                key = "gl_bell",
                section = "gestion-locales",
                title = "Notificaciones por Local",
                text = "La campana al lado de cada local muestra las notificaciones pendientes (vencimientos de documentos, libretas sanitarias). El número rojo indica cuántas alertas activas hay.",
                iconEmoji = "🔔",
                visualType = "gl_bell"
            )
        )

        steps.add(
            GuideStep(
                key = "gl_enter",
                section = "gestion-locales",
                title = "Ingresar al Detalle del Local",
                text = "Haz clic en cualquier local de la lista para ver su detalle: estado de cuenta, actividad, credenciales de acceso y documentos.",
                iconEmoji = "➡️",
                visualType = "gl_enter"
            )
        )

        steps.add(
            GuideStep(
                key = "gl_detail_actions",
                section = "gestion-locales",
                title = "Acciones sobre el Local",
                text = "Puedes: 1) Ingresar al Local (operar como admin), 2) Ver Credenciales (usuario y clave), 3) Blanquear Clave (resetear a 'Inicial2026'), 4) Editar información." + (if (isMaestro || isAsistente) " 5) Eliminar Cuenta." else ""),
                iconEmoji = "🛠️",
                visualType = "gl_detail_actions"
            )
        )

        if (isMaestro || isAsistente) {
            steps.add(
                GuideStep(
                    key = "gl_sup_tab",
                    section = "gestion-locales",
                    title = "Pestaña Supervisores",
                    text = "Cambia a la pestaña 'Supervisores' para ver todos los supervisores y asistentes de la región.",
                    iconEmoji = "👥",
                    visualType = "gl_sup_tab"
                )
            )
            steps.add(
                GuideStep(
                    key = "gl_sup_detail",
                    section = "gestion-locales",
                    title = "Detalle de Supervisor",
                    text = "Desde el detalle puedes: 1) Blanquear clave, 2) Eliminar la cuenta, 3) Asignar o desasignar locales marcando los checkboxes.",
                    iconEmoji = "✏️",
                    visualType = "gl_sup_detail"
                )
            )
        }

        steps.add(
            GuideStep(
                key = "gl_end",
                section = "gestion-locales",
                title = "¡Guía de Gestión Completada! 🎉",
                text = "Ya conoces todas las funciones de Gestión de Locales para tu rol de $roleLabel.",
                iconEmoji = "✨",
                visualType = "gl_end"
            )
        )

        return steps
    }

    fun getFullTourSteps(rolUsuario: String = "", usuarioNombre: String = ""): List<GuideStep> {
        val list = mutableListOf<GuideStep>()

        // 1. DASHBOARD
        list.add(
            GuideStep(
                key = "welcome",
                section = "dashboard",
                title = "¡Bienvenido al Sistema de Inventario!",
                text = "Esta guía te llevará por todas las secciones del sistema. Verás cómo cargar mercadería, gestionar vencimientos, hacer pedidos a fábrica, registrar el stock final y administrar tu local.",
                iconEmoji = "📦",
                visualType = "welcome"
            )
        )
        list.add(
            GuideStep(
                key = "dash_date",
                section = "dashboard",
                title = "Selector de Fecha",
                text = "Navega entre días con las flechas o abre el calendario. Los puntos de color indican el estado: verde (completo), amarillo (parcial) y sin color (sin datos).",
                iconEmoji = "📅",
                visualType = "date_bar"
            )
        )
        list.add(
            GuideStep(
                key = "dash_status",
                section = "dashboard",
                title = "Estado del Día",
                text = "Estas tarjetas te muestran rápidamente qué tareas ya completaste y cuáles están pendientes para la fecha seleccionada.",
                iconEmoji = "📊",
                visualType = "status_cards"
            )
        )
        list.add(
            GuideStep(
                key = "dash_actions",
                section = "dashboard",
                title = "Panel de Acciones",
                text = "Aquí accedes a todas las funciones. Vamos a recorrer cada una empezando por Ingreso de Mercadería.",
                iconEmoji = "🎛️",
                visualType = "actions"
            )
        )

        // 2. INGRESO DE MERCADERÍA
        list.add(
            GuideStep(
                key = "carga_intro",
                section = "carga",
                title = "1) Ingreso de Mercadería",
                text = "Aquí cargas las cantidades que recibiste de fábrica. Guíate con el remito que trae el transporte. Ingresa la cantidad exacta de cada producto.",
                iconEmoji = "🚚",
                visualType = "carga_mercaderia"
            )
        )
        list.add(
            GuideStep(
                key = "carga_form",
                section = "carga",
                title = "Cantidades por Producto",
                text = "Para cada producto, ingresa la cantidad que recibiste según el remito. Los campos se agrupan por categoría: Empanadas, Pizzas, Medialunas y Otros.",
                iconEmoji = "📝",
                visualType = "carga_form_anim"
            )
        )
        list.add(
            GuideStep(
                key = "carga_save",
                section = "carga",
                title = "Guardar Ingreso",
                text = "Una vez completado, presiona Previsualizar y Guardar. Los datos quedan registrados y el sistema los usa para calcular el stock y las sugerencias de pedido.",
                iconEmoji = "💾",
                visualType = "carga_save_anim"
            )
        )

        // 3. VENCIMIENTOS
        list.add(
            GuideStep(
                key = "venc_intro",
                section = "vencimientos",
                title = "2) Control de Vencimientos",
                text = "Ahora vamos a cargar los lotes con sus fechas de vencimiento. Es importante hacerlo ANTES de cargar el stock final, para que el sistema tenga todo en orden.",
                iconEmoji = "⏰",
                visualType = "venc_intro_anim"
            )
        )
        list.add(
            GuideStep(
                key = "venc_producto",
                section = "vencimientos",
                title = "Seleccionar Producto y Lote",
                text = "Selecciona el producto al que le vas a cargar un lote con su fecha de vencimiento. Ingresa fecha de vencimiento y cantidad.",
                iconEmoji = "🏷️",
                visualType = "venc_producto_anim"
            )
        )
        list.add(
            GuideStep(
                key = "venc_lista",
                section = "vencimientos",
                title = "Lotes Cargados y Alertas",
                text = "Una vez cargados todos los lotes, aparecerán ordenados. Los vencidos se marcan en rojo y los por vencer en amarillo. ¡Todo controlado!",
                iconEmoji = "🚦",
                visualType = "venc_lista_anim"
            )
        )

        // 4. PEDIDO A FÁBRICA
        list.add(
            GuideStep(
                key = "pf_intro",
                section = "pedido-fabrica",
                title = "3) Pedido a Fábrica",
                text = "El sistema toma automáticamente tu Stock Final de ayer + el Ingreso de Mercadería de hoy para calcular el stock actual y generar una sugerencia de pedido.",
                iconEmoji = "🏭",
                visualType = "pf_intro_anim"
            )
        )
        list.add(
            GuideStep(
                key = "pf_stock",
                section = "pedido-fabrica",
                title = "Stock Actual y Sugerencia",
                text = "La columna 'Stock' muestra tu stock real automático. La columna 'Sugerido' calcula cuánto pedir basándose en la demanda histórica + paisaje de clima animado + partidos de fútbol.",
                iconEmoji = "📈",
                visualType = "pf_stock_anim"
            )
        )
        list.add(
            GuideStep(
                key = "pf_save",
                section = "pedido-fabrica",
                title = "Enviar Pedido a Fábrica",
                text = "Ingresa las cantidades finales en la columna Pedido y presiona Guardar Pedido. El sistema registra el pedido y la tarea queda marcada como completada.",
                iconEmoji = "📤",
                visualType = "pf_save_anim"
            )
        )

        // 5. STOCK FINAL
        list.add(
            GuideStep(
                key = "stock_intro",
                section = "stock-final",
                title = "4) Stock Final",
                text = "Al cierre del día, contabiliza el sobrante físico real del local e ingresa las cantidades finales. El sistema calcula ventas reales y desvíos de stock.",
                iconEmoji = "📋",
                visualType = "stock_intro_anim"
            )
        )

        // 6. ADMINISTRACIÓN DEL LOCAL
        list.add(
            GuideStep(
                key = "admin_intro",
                section = "admin-local",
                title = "5) Administración del Local",
                text = "Aquí gestionas la documentación del local y el personal. Pestañas: Documentos (Habilitaciones y Seguros) y Personal (Libretas Sanitarias y Cursos).",
                iconEmoji = "🏢",
                visualType = "admin_intro_anim"
            )
        )
        list.add(
            GuideStep(
                key = "admin_docs",
                section = "admin-local",
                title = "Cargar Documentos y Libretas",
                text = "Sube las habilitaciones y registra cada empleado con su libreta sanitaria y curso. El sistema alerta antes de que expiren.",
                iconEmoji = "📑",
                visualType = "admin_docs_anim"
            )
        )

        // 7. VENTA POR VOLUMEN
        list.add(
            GuideStep(
                key = "vv_intro",
                section = "venta-volumen",
                title = "6) Venta por Volumen",
                text = "Aquí visualizas el volumen de ventas por categoría y producto. Puedes ver tendencias y comparar el rendimiento entre días.",
                iconEmoji = "📉",
                visualType = "vv_welcome"
            )
        )

        // 8. CIERRE Y RESUMEN
        list.add(
            GuideStep(
                key = "tour_end",
                section = "dashboard",
                title = "¡Tour Completado! 🎉",
                text = "Ya conoces todas las secciones del sistema.\n\nRecuerda el flujo diario:\n1) Ingresar Mercadería ➔ 2) Cargar Vencimientos ➔ 3) Hacer Pedido a Fábrica ➔ 4) Cargar Stock Final.\n\n¡Puedes volver a ver esta guía cuando quieras con el botón de ayuda ❓!",
                iconEmoji = "✨",
                visualType = "tour_end"
            )
        )

        return list
    }

    fun getStepsForSection(sectionName: String, rolUsuario: String = "", usuarioNombre: String = ""): List<GuideStep> {
        if (sectionName == "gestion-locales") {
            return getGestionLocalesSteps(rolUsuario, usuarioNombre)
        }
        val full = getFullTourSteps(rolUsuario, usuarioNombre)
        val filtered = full.filter { it.section == sectionName }
        return if (filtered.isNotEmpty()) filtered else full
    }

    fun maybeAutoStart(activity: Activity) {
        try {
            val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val seen = prefs.getBoolean(KEY_GUIDE_SEEN, false)
            if (!seen) {
                val userPrefs = activity.getSharedPreferences("usuario_actual", Context.MODE_PRIVATE)
                val rol = userPrefs.getString("rol", "") ?: ""
                val usr = userPrefs.getString("usuario", "") ?: ""
                show(activity, fullTour = true, rolUsuario = rol, usuarioNombre = usr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en auto-start de guía", e)
        }
    }

    fun markGuideAsSeen(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_GUIDE_SEEN, true).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando visto de guía", e)
        }
    }

    fun show(activity: Activity, sectionName: String? = null, fullTour: Boolean = false, rolUsuario: String = "", usuarioNombre: String = "") {
        if (activity.isFinishing || activity.isDestroyed) return

        val userPrefs = activity.getSharedPreferences("usuario_actual", Context.MODE_PRIVATE)
        val effectiveRol = if (rolUsuario.isNotEmpty()) rolUsuario else (userPrefs.getString("rol", "") ?: "")
        val effectiveUsr = if (usuarioNombre.isNotEmpty()) usuarioNombre else (userPrefs.getString("usuario", "") ?: "")

        val steps = if (sectionName == "gestion-locales") {
            getGestionLocalesSteps(effectiveRol, effectiveUsr)
        } else if (fullTour || sectionName == null) {
            getFullTourSteps(effectiveRol, effectiveUsr)
        } else {
            getStepsForSection(sectionName, effectiveRol, effectiveUsr)
        }

        if (steps.isEmpty()) return

        var currentIndex = 0
        markGuideAsSeen(activity)

        val nightModeFlags = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val dialogBgColor = if (isDark) "#1E293B" else "#FFFFFF"
        val textColorPrimary = if (isDark) "#F8FAFC" else "#0F172A"
        val textColorSecondary = if (isDark) "#94A3B8" else "#64748B"
        val accentGold = "#C9A24A"

        val dialog = Dialog(activity, if (isDark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val rootContainer = FrameLayout(activity).apply {
            setBackgroundColor(Color.parseColor(if (isDark) "#CC0F172A" else "#80000000"))
            setPadding(20, 20, 20, 20)
        }

        val cardView = com.google.android.material.card.MaterialCardView(activity).apply {
            setCardBackgroundColor(Color.parseColor(dialogBgColor))
            radius = 20f * activity.resources.displayMetrics.density
            cardElevation = 12f * activity.resources.displayMetrics.density
            strokeWidth = (1.5f * activity.resources.displayMetrics.density).toInt()
            strokeColor = Color.parseColor(accentGold)
        }

        val mainLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        val headerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }

        val lblGuia = TextView(activity).apply {
            text = "❓ GUÍA INTERACTIVA DE USUARIO"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(accentGold))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = TextView(activity).apply {
            text = "✕"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(textColorSecondary))
            setPadding(12, 0, 12, 0)
            setOnClickListener { dialog.dismiss() }
        }

        headerLayout.addView(lblGuia)
        headerLayout.addView(btnClose)

        val txtTitle = TextView(activity).apply {
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(textColorPrimary))
            setPadding(0, 0, 0, 6)
        }

        val txtStepCounter = TextView(activity).apply {
            textSize = 11f
            setTextColor(Color.parseColor(accentGold))
            setPadding(0, 0, 0, 10)
        }

        val txtText = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.parseColor(textColorSecondary))
            setLineSpacing(3f, 1.15f)
            setPadding(0, 0, 0, 12)
        }

        val webVisual = WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (135 * activity.resources.displayMetrics.density).toInt())
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
        }

        val footerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 14, 0, 0)
        }

        val dotsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnPrev = Button(activity).apply {
            text = "◀ Atrás"
            textSize = 12f
            setTextColor(Color.parseColor(textColorSecondary))
            setBackgroundColor(Color.TRANSPARENT)
        }

        val btnNext = Button(activity).apply {
            text = "Siguiente ▶"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1C1C1C"))
            setBackgroundColor(Color.parseColor(accentGold))
        }

        footerLayout.addView(dotsContainer)
        footerLayout.addView(btnPrev)
        footerLayout.addView(btnNext)

        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
        }
        val scrollContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollContent.addView(txtStepCounter)
        scrollContent.addView(txtTitle)
        scrollContent.addView(txtText)
        scrollContent.addView(webVisual)

        scrollView.addView(scrollContent)

        mainLayout.addView(headerLayout)
        mainLayout.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        mainLayout.addView(footerLayout)

        cardView.addView(mainLayout)

        val cardLayoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (450 * activity.resources.displayMetrics.density).toInt()
        ).apply {
            gravity = Gravity.CENTER
        }
        rootContainer.addView(cardView, cardLayoutParams)

        dialog.setContentView(rootContainer)

        fun updateStepUI() {
            val step = steps[currentIndex]
            txtStepCounter.text = "PASO ${currentIndex + 1} DE ${steps.size} · ${step.section.uppercase()}"
            txtTitle.text = "${step.iconEmoji} ${step.title}"
            txtText.text = step.text

            dotsContainer.removeAllViews()
            for (i in steps.indices) {
                val dot = View(activity).apply {
                    val size = (if (i == currentIndex) 9 else 5) * activity.resources.displayMetrics.density
                    val params = LinearLayout.LayoutParams(size.toInt(), size.toInt()).apply {
                        setMargins(3, 0, 3, 0)
                    }
                    layoutParams = params
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor(if (i == currentIndex) accentGold else textColorSecondary))
                    }
                }
                dotsContainer.addView(dot)
            }

            btnPrev.visibility = if (currentIndex > 0) View.VISIBLE else View.INVISIBLE
            btnNext.text = if (currentIndex == steps.size - 1) "¡Finalizar! 🎉" else "Siguiente ▶"

            val htmlVisual = generateVisualHtml(step.visualType, isDark)
            webVisual.loadDataWithBaseURL(null, htmlVisual, "text/html", "UTF-8", null)
        }

        btnPrev.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex--
                updateStepUI()
            }
        }

        btnNext.setOnClickListener {
            if (currentIndex < steps.size - 1) {
                currentIndex++
                updateStepUI()
            } else {
                dialog.dismiss()
            }
        }

        updateStepUI()
        dialog.show()
    }

    private fun generateVisualHtml(type: String, isDark: Boolean): String {
        val bg = if (isDark) "#0F172A" else "#F8FAFC"
        val cardBg = if (isDark) "#1E293B" else "#FFFFFF"
        val textCol = if (isDark) "#F8FAFC" else "#0F172A"
        val gold = "#C9A24A"

        val bodyContent = when (type) {
            "welcome" -> """
                <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%;gap:8px;">
                    <div style="font-size:36px;animation:bounce 2s infinite;">📦</div>
                    <div style="font-size:14px;font-weight:bold;color:$gold;">Sabores Express Inventario</div>
                </div>
            """.trimIndent()

            "gl_welcome" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;border:1px solid $gold;font-size:11px;">
                    <div style="font-weight:bold;color:$gold;margin-bottom:4px;">🏢 Gestión de Locales Regional</div>
                    <div>Panel de administración según los permisos de tu cuenta.</div>
                </div>
            """.trimIndent()

            "gl_header" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;font-size:11px;">
                    <div style="color:$gold;font-weight:bold;">👤 Rol y Permisos Confirmados</div>
                    <div>Confirma qué operaciones puedes realizar en la región.</div>
                </div>
            """.trimIndent()

            "gl_tabs" -> """
                <div style="display:flex;gap:6px;font-size:11px;">
                    <div style="flex:1;background:$gold;color:#1C1C1C;padding:8px;border-radius:6px;font-weight:bold;text-align:center;">Locales</div>
                    <div style="flex:1;background:$cardBg;color:$textCol;padding:8px;border-radius:6px;font-weight:bold;text-align:center;border:1px solid $gold;">Supervisores</div>
                </div>
            """.trimIndent()

            "gl_search" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;font-size:11px;color:$gold;">
                    🔍 Buscador rápido por nombre o correo...
                </div>
            """.trimIndent()

            "gl_new_local" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;border:1px solid $gold;font-size:11px;">
                    <div style="font-weight:bold;color:$gold;">+ Nuevo Local</div>
                    <div>Alta de sucursal con contraseña inicial 'Inicial2026'.</div>
                </div>
            """.trimIndent()

            "gl_new_sup" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;border:1px solid $gold;font-size:11px;">
                    <div style="font-weight:bold;color:$gold;">+ Nuevo Supervisor</div>
                    <div>☑ Opción de marcar como Asistente del Maestro.</div>
                </div>
            """.trimIndent()

            "gl_lista" -> """
                <div style="display:flex;flex-direction:column;gap:4px;font-size:11px;">
                    <div style="background:$cardBg;padding:6px;border-radius:6px;display:flex;justify-content:space-between;"><span>Local Solano</span><span style="color:#22C55E;font-weight:bold;">En Actividad</span></div>
                    <div style="background:$cardBg;padding:6px;border-radius:6px;display:flex;justify-content:space-between;"><span>Local Palermo</span><span style="color:#EAB308;font-weight:bold;">Sin Actividad</span></div>
                </div>
            """.trimIndent()

            "gl_bell" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;display:flex;align-items:center;justify-content:space-between;font-size:11px;">
                    <span>🔔 Notificaciones de Local</span>
                    <span style="background:#EF4444;color:white;padding:2px 8px;border-radius:10px;font-weight:bold;">3 Alertas</span>
                </div>
            """.trimIndent()

            "gl_enter" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;font-size:11px;color:$gold;font-weight:bold;text-align:center;">
                    ➔ Ingresar a la administración del local
                </div>
            """.trimIndent()

            "gl_detail_actions" -> """
                <div style="display:flex;flex-direction:column;gap:4px;font-size:11px;">
                    <div style="background:$cardBg;padding:6px;border-radius:6px;color:$gold;">1) Ingresar al Local (Modo Admin)</div>
                    <div style="background:$cardBg;padding:6px;border-radius:6px;">2) Ver Credenciales (Usuario / Clave)</div>
                    <div style="background:$cardBg;padding:6px;border-radius:6px;">3) Blanquear Clave ('Inicial2026')</div>
                </div>
            """.trimIndent()

            "gl_sup_tab" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;font-size:11px;">
                    <div style="font-weight:bold;color:$gold;">👥 Gestión de Supervisores y Asistentes</div>
                    <div>Alta, edición, blanqueo de claves y asignación de locales.</div>
                </div>
            """.trimIndent()

            "gl_sup_detail" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;font-size:11px;">
                    <div style="font-weight:bold;color:$gold;">✏️ Asignar Locales a Supervisor</div>
                    <div>☑ Solano &nbsp; ☐ Palermo &nbsp; ☑ Varela</div>
                </div>
            """.trimIndent()

            "gl_end" -> """
                <div style="display:flex;align-items:center;justify-content:center;height:100%;font-size:14px;color:$gold;font-weight:bold;">
                    ✨ ¡Guía de Gestión Completada! 🎉
                </div>
            """.trimIndent()

            "date_bar" -> """
                <div style="display:flex;align-items:center;justify-content:space-between;background:$cardBg;padding:10px;border-radius:10px;border:1px solid $gold;">
                    <span style="font-size:14px;font-weight:bold;color:$gold;">◀ Lunes 15 de Julio ▶</span>
                    <span style="font-size:11px;background:#22C55E;color:white;padding:2px 6px;border-radius:4px;">Verde (OK)</span>
                </div>
            """.trimIndent()

            "status_cards" -> """
                <div style="display:grid;grid-template-columns:1fr 1fr;gap:6px;">
                    <div style="background:$cardBg;padding:8px;border-radius:8px;border-left:4px solid #22C55E;font-size:11px;">✓ Mercadería</div>
                    <div style="background:$cardBg;padding:8px;border-radius:8px;border-left:4px solid #EAB308;font-size:11px;">⚠ Stock Final</div>
                    <div style="background:$cardBg;padding:8px;border-radius:8px;border-left:4px solid #94A3B8;font-size:11px;">○ Vencimientos</div>
                    <div style="background:$cardBg;padding:8px;border-radius:8px;border-left:4px solid #94A3B8;font-size:11px;">○ Pedido Fábrica</div>
                </div>
            """.trimIndent()

            "actions" -> """
                <div style="display:grid;grid-template-columns:1fr 1fr;gap:6px;font-size:11px;">
                    <div style="background:$cardBg;padding:8px;border-radius:8px;border:1px solid $gold;color:$gold;font-weight:bold;">📦 Mercadería</div>
                    <div style="background:$cardBg;padding:8px;border-radius:8px;">⏰ Vencimientos</div>
                    <div style="background:$cardBg;padding:8px;border-radius:8px;">🏭 Pedido Fábrica</div>
                    <div style="background:$cardBg;padding:8px;border-radius:8px;">📋 Stock Final</div>
                </div>
            """.trimIndent()

            "carga_mercaderia" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;border:1px solid #334155;font-size:11px;">
                    <div style="font-weight:bold;color:$gold;margin-bottom:4px;">📄 Remito de Transporte #4829</div>
                    <div style="display:flex;justify-content:space-between;margin-bottom:2px;"><span>Jamón y Queso (JQ)</span><span style="font-weight:bold;color:#22C55E;">120 u</span></div>
                    <div style="display:flex;justify-content:space-between;"><span>Carne Especial (CE)</span><span style="font-weight:bold;color:#22C55E;">90 u</span></div>
                </div>
            """.trimIndent()

            "carga_form_anim" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;font-size:11px;">
                    <div style="font-weight:bold;color:$gold;margin-bottom:4px;">🥟 Empanadas</div>
                    <div style="display:flex;justify-content:space-between;align-items:center;"><span>JQ - Jamón y Queso</span><span style="background:$bg;padding:2px 8px;border-radius:4px;border:1px solid $gold;color:#22C55E;font-weight:bold;">120</span></div>
                </div>
            """.trimIndent()

            "carga_save_anim" -> """
                <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;height:100%;">
                    <div style="background:$gold;color:#1C1C1C;padding:8px 16px;border-radius:6px;font-weight:bold;font-size:12px;">💾 Previsualizar y Guardar</div>
                    <div style="color:#22C55E;font-size:11px;font-weight:bold;">✓ Registrado en la base de datos</div>
                </div>
            """.trimIndent()

            "venc_intro_anim" -> """
                <div style="display:flex;align-items:center;justify-content:space-around;font-size:11px;background:$cardBg;padding:10px;border-radius:10px;">
                    <span style="color:#22C55E;">1. Mercadería ✓</span>
                    <span>➔</span>
                    <span style="color:$gold;font-weight:bold;">2. Vencimientos ⏰</span>
                    <span>➔</span>
                    <span style="color:#94A3B8;">3. Pedido</span>
                </div>
            """.trimIndent()

            "venc_producto_anim" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;font-size:11px;">
                    <div style="display:flex;justify-content:space-between;margin-bottom:4px;"><span>Producto: <b>JQ - Jamón y Queso</b></span></div>
                    <div style="display:flex;justify-content:space-between;"><span>Vencimiento: <b>15/08/2026</b></span><span style="color:#22C55E;font-weight:bold;">18 días OK</span></div>
                </div>
            """.trimIndent()

            "venc_lista_anim" -> """
                <div style="display:flex;flex-direction:column;gap:4px;font-size:11px;">
                    <div style="background:$cardBg;padding:6px 10px;border-radius:6px;display:flex;justify-content:space-between;"><span>JQ (Jamón y Queso)</span><span style="color:#22C55E;font-weight:bold;">VIGENTE (18d)</span></div>
                    <div style="background:$cardBg;padding:6px 10px;border-radius:6px;display:flex;justify-content:space-between;"><span>CE (Carne Especial)</span><span style="color:#EAB308;font-weight:bold;">POR VENCER (3d)</span></div>
                    <div style="background:$cardBg;padding:6px 10px;border-radius:6px;display:flex;justify-content:space-between;"><span>PB (Pollo Barbacoa)</span><span style="color:#EF4444;font-weight:bold;">VENCIDO</span></div>
                </div>
            """.trimIndent()

            "pf_intro_anim" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;border:1px solid $gold;font-size:11px;">
                    <div style="font-size:12px;font-weight:bold;color:$gold;margin-bottom:4px;">🧮 Fórmula Stock Actual</div>
                    <div>Stock Final Ayer (30) + Ingreso Hoy (120) = <b style="color:#22C55E;">150 Unidades</b></div>
                    <div style="margin-top:6px;color:#94A3B8;font-size:10px;">🌤️ Sugerencia incluye clima + partidos de fútbol</div>
                </div>
            """.trimIndent()

            "pf_stock_anim" -> """
                <div style="background:$cardBg;padding:8px;border-radius:8px;font-size:11px;">
                    <div style="display:flex;justify-content:space-between;font-weight:bold;color:$gold;margin-bottom:4px;"><span>Producto</span><span>Stock</span><span>Sugerido</span><span>Pedido</span></div>
                    <div style="display:flex;justify-content:space-between;"><span>JQ</span><span style="color:#22C55E;">150</span><span style="color:$gold;">180</span><span style="font-weight:bold;">180</span></div>
                </div>
            """.trimIndent()

            "pf_save_anim" -> """
                <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;height:100%;">
                    <div style="background:$gold;color:#1C1C1C;padding:8px 16px;border-radius:6px;font-weight:bold;font-size:12px;">📤 Guardar Pedido a Fábrica</div>
                    <div style="color:#22C55E;font-size:11px;font-weight:bold;">✓ Tarea marcada como completada</div>
                </div>
            """.trimIndent()

            "stock_intro_anim" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;font-size:11px;">
                    <div style="font-weight:bold;color:$gold;margin-bottom:4px;">📋 Registro de Sobrante Físico</div>
                    <div>Ingresá las unidades contadas al cierre del día para calcular desvíos de stock.</div>
                </div>
            """.trimIndent()

            "admin_intro_anim" -> """
                <div style="display:grid;grid-template-columns:1fr 1fr;gap:6px;font-size:11px;">
                    <div style="background:$cardBg;padding:8px;border-radius:8px;text-align:center;">📑 Habilitaciones (6)</div>
                    <div style="background:$cardBg;padding:8px;border-radius:8px;text-align:center;">👥 Personal (17)</div>
                </div>
            """.trimIndent()

            "admin_docs_anim" -> """
                <div style="display:flex;flex-direction:column;gap:4px;font-size:11px;">
                    <div style="background:$cardBg;padding:6px;border-radius:6px;display:flex;justify-content:space-between;"><span>Habilitación Municipal</span><span style="color:#22C55E;">OK</span></div>
                    <div style="background:$cardBg;padding:6px;border-radius:6px;display:flex;justify-content:space-between;"><span>Libreta Sanitaria - Juan P.</span><span style="color:#EAB308;">Expira en 5d</span></div>
                </div>
            """.trimIndent()

            "vv_welcome" -> """
                <div style="background:$cardBg;padding:10px;border-radius:10px;font-size:11px;text-align:center;">
                    <div style="font-size:12px;font-weight:bold;color:$gold;margin-bottom:4px;">📊 Gráficos de Ventas por Volumen</div>
                    <div>Análisis comparativo de unidades vendidas por categoría y producto.</div>
                </div>
            """.trimIndent()

            else -> """
                <div style="display:flex;align-items:center;justify-content:center;height:100%;font-size:14px;color:$gold;font-weight:bold;">
                    ✨ ¡Flujo diario completo y controlado!
                </div>
            """.trimIndent()
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                * { box-sizing: border-box; margin:0; padding:0; user-select:none; }
                body { background:$bg; color:$textCol; font-family:system-ui, -apple-system, sans-serif; padding:10px; height:100vh; display:flex; flex-direction:column; justify-content:center; }
                @keyframes bounce { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-6px); } }
            </style>
            </head>
            <body>
                $bodyContent
            </body>
            </html>
        """.trimIndent()
    }
}
