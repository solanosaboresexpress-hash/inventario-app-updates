package com.tuapp.inventario.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tuapp.inventario.MainActivity
import com.tuapp.inventario.PedidoFabricaActivity
import com.tuapp.inventario.R

class NotificationHelper(private val context: Context) {
    
    companion object {
        const val CHANNEL_ID = "inventario_channel"
        const val NOTIFICATION_ID_STOCK = 1001
        const val NOTIFICATION_ID_INGRESO = 1002
        const val NOTIFICATION_ID_VENCIMIENTOS = 1003
        const val NOTIFICATION_ID_DEMANDA_MANIANA = 1005
    }
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recordatorios de Inventario",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones para recordar cargar stock e ingreso de mercaderia"
                enableVibration(true)
                enableLights(true)
            }
            
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    fun showStockReminderNotification() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_STOCK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu) // Usar el icono existente
            .setContentTitle(" Hora de cargar Stock 15:30!")
            .setContentText("Es momento de cargar el stock actual para el pedido de fabrica")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Es momento de cargar el stock actual a las 15:30 para calcular el pedido de fabrica. Ve a la seccion de Pedidos > Pedido Fabrica para cargar los datos."))
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_STOCK, notification)
    }
    
    fun showIngresoReminderNotification() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_INGRESO,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu) // Usar el icono existente
            .setContentTitle(" Recordatorio de Ingreso!")
            .setContentText("No olvides cargar el ingreso de mercaderia del dia")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Recuerda cargar el ingreso de mercaderia del dia para mantener actualizado el inventario."))
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_INGRESO, notification)
    }
    
    fun cancelStockNotification() {
        notificationManager.cancel(NOTIFICATION_ID_STOCK)
    }
    
    fun cancelIngresoNotification() {
        notificationManager.cancel(NOTIFICATION_ID_INGRESO)
    }
    
    /**
     * Muestra una notificacion con los vencimientos del dia
     * Carga los vencimientos de forma asincrona
     */
    fun showVencimientosNotificationAsync(context: Context) {
        // Ejecutar en un hilo separado para no bloquear el BroadcastReceiver
        Thread {
            try {
                val prefs = context.getSharedPreferences("usuario_actual", Context.MODE_PRIVATE)
                val localId = prefs.getString("localId", null)
                val localNombre = prefs.getString("localNombre", "Local")
                
                if (localId == null || localId.isEmpty()) {
                    Log.w("NotificationHelper", " No hay localId guardado, no se puede mostrar notificacion de vencimientos")
                    return@Thread
                }
                
                Log.d("NotificationHelper", " Obteniendo vencimientos del dia para local: $localId ($localNombre)")
                
                // Obtener vencimientos del dia usando coroutines
                val vencimientos = kotlinx.coroutines.runBlocking {
                    val repository = com.tuapp.inventario.repository.VencimientoRepository()
                    val todosLosLotes = repository.obtenerLotesActivos(localId)
                    
                    // Filtrar solo los que vencen hoy (dias hasta vencimiento == 0)
                    todosLosLotes.filter { lote ->
                        lote.diasHastaVencimiento() == 0 && lote.cantidad > 0
                    }
                }
                
                Log.d("NotificationHelper", " Vencimientos del dia encontrados: ${vencimientos.size}")
                
                // Mostrar notificacion con los resultados
                showVencimientosNotification(context, vencimientos, localNombre ?: "Local")
                
            } catch (e: Exception) {
                Log.e("NotificationHelper", " Error obteniendo vencimientos: ${e.message}", e)
                // Mostrar notificacion generica si hay error
                showVencimientosNotificationError(context)
            }
        }.start()
    }
    
    /**
     * Muestra la notificacion con los vencimientos del dia
     */
    private fun showVencimientosNotification(
        context: Context,
        vencimientos: List<com.tuapp.inventario.model.VencimientoLote>,
        localNombre: String
    ) {
        val intent = Intent(context, com.tuapp.inventario.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_VENCIMIENTOS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        
        if (vencimientos.isEmpty()) {
            // No hay vencimientos hoy
            builder.setContentTitle(" Vencimientos del dia - $localNombre")
                .setContentText("Excelente! No hay productos que vencen hoy")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("No hay productos que vencen hoy en $localNombre. Todo esta en orden."))
        } else {
            // Hay vencimientos
            val totalCantidad = vencimientos.sumOf { it.cantidad }
            
            builder.setContentTitle(" Vencimientos del dia - $localNombre")
                .setContentText("${vencimientos.size} lote(s) vencen hoy (${totalCantidad} unidades)")
            
            // Construir texto detallado
            val textoDetallado = buildString {
                append("Productos que vencen hoy en $localNombre:\n\n")
                vencimientos.take(10).forEach { lote ->
                    append(" ${lote.productoCodigo}: ${lote.cantidad} uds\n")
                }
                if (vencimientos.size > 10) {
                    append("\n... y ${vencimientos.size - 10} lote(s) mas")
                }
                append("\n\nToca para ver detalles completos")
            }
            
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(textoDetallado))
        }
        
        notificationManager.notify(NOTIFICATION_ID_VENCIMIENTOS, builder.build())
        Log.d("NotificationHelper", " Notificacion de vencimientos mostrada")
    }
    
    /**
     * Muestra una notificacion de error si no se pueden cargar los vencimientos
     */
    private fun showVencimientosNotificationError(context: Context) {
        val intent = Intent(context, com.tuapp.inventario.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_VENCIMIENTOS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu)
            .setContentTitle(" Recordatorio de Vencimientos")
            .setContentText("Revisa los vencimientos del dia en la app")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Es momento de revisar los vencimientos del dia. Abre la app para ver los detalles."))
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_VENCIMIENTOS, notification)
    }
    
    fun cancelVencimientosNotification() {
        notificationManager.cancel(NOTIFICATION_ID_VENCIMIENTOS)
    }

    /**
     * Aviso llamativo: clima / partidos que pueden subir la demanda del dia siguiente (pedido fabrica).
     */
    fun showPedidoDemandaManianaNotification(titulo: String, bigText: String) {
        val intent = Intent(context, PedidoFabricaActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_DEMANDA_MANIANA,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu)
            .setContentTitle(titulo)
            .setContentText(bigText.take(120).let { if (bigText.length > 120) "$it" else it })
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager.notify(NOTIFICATION_ID_DEMANDA_MANIANA, notification)
    }
}
