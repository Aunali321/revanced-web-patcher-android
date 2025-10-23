package app.revanced.webpatcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.revanced.library.logging.Logger
import app.revanced.webpatcher.routing.configurePatchRoutes
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.event.Level

class BackendService : Service() {
    private var server: NettyApplicationEngine? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "revanced_patcher_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            try {
                // Skip Logger.setDefault() on Android - use Android's built-in logging
                server = embeddedServer(Netty, port = 3000) {
                    install(DefaultHeaders)
                    install(CallLogging) { level = Level.INFO }

                    install(CORS) {
                        anyHost()
                        allowHeader(HttpHeaders.AccessControlAllowOrigin)
                        allowMethod(HttpMethod.Options)
                        allowMethod(HttpMethod.Get)
                        allowMethod(HttpMethod.Post)
                        allowHeaders { true }
                        allowSameOrigin
                        allowNonSimpleContentTypes = true
                        exposeHeader("X-Patch-Job-Id")
                        exposeHeader(HttpHeaders.ContentDisposition)
                    }

                    install(ContentNegotiation) {
                        jackson {
                            registerModule(JavaTimeModule())
                            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        }
                    }

                    install(StatusPages) {
                        exception<PatchProcessingException> { call, cause ->
                            call.respondPatchError(cause.message ?: "Failed to patch APK", cause.status)
                        }

                        exception<IllegalArgumentException> { call, cause ->
                            call.respondPatchError(
                                cause.message ?: "Invalid request",
                                PatchErrorStatus.BAD_REQUEST
                            )
                        }

                        exception<Throwable> { call, cause ->
                            call.respondPatchError(
                                "Unexpected server error",
                                PatchErrorStatus.SERVER_ERROR,
                                logCause = cause,
                            )
                        }
                    }

                    configurePatchRoutes()
                }
                server?.start(wait = false)
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop(1000, 2000)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ReVanced Patcher Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running backend server"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
