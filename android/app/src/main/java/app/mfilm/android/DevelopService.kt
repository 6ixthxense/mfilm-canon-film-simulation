package app.mfilm.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

object DevelopState {
    var running by mutableStateOf(false)
    var rollNo by mutableIntStateOf(0)
    var currentFrame by mutableIntStateOf(0)
    var doneFrames by mutableIntStateOf(0)
    var totalFrames by mutableIntStateOf(0)
    var progress by mutableIntStateOf(0)       // 0..1000
    var preview by mutableStateOf<Bitmap?>(null)
    var error by mutableStateOf<String?>(null)
}

class DevelopService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelled = true
            stopSelf()
            return START_NOT_STICKY
        }
        val rollNo = intent?.getIntExtra(EXTRA_ROLL, -1) ?: -1
        val size = intent?.getIntExtra(EXTRA_SIZE, 0) ?: 0
        val push = intent?.getIntExtra(EXTRA_PUSH, 0) ?: 0
        val targetFrame = intent?.getIntExtra(EXTRA_FRAME, 0) ?: 0
        if (rollNo <= 0 || DevelopState.running) return START_NOT_STICKY

        val chId = "mfilm_darkroom"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(chId, "Darkroom", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, chId)
            .setContentTitle("M-Film Darkroom")
            .setContentText(
                if (targetFrame > 0) "Developing frame #%02d…".format(targetFrame)
                else "Developing roll %03d…".format(rollNo)
            )
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        cancelled = false
        DevelopState.running = true
        DevelopState.rollNo = rollNo
        DevelopState.doneFrames = 0
        DevelopState.totalFrames = 0
        DevelopState.progress = 0
        DevelopState.error = null

        worker.execute {
            try {
                val total = Native.devOpen(rollNo, size, push, targetFrame)
                if (total <= 0) {
                    DevelopState.error = if (total < 0) "Cannot open roll" else "Nothing to develop"
                } else {
                    DevelopState.totalFrames = total
                    val out = IntArray(3)
                    val bmp = Bitmap.createBitmap(180, 120, Bitmap.Config.ARGB_8888)
                    var lastPreviewMs = 0L
                    while (!cancelled) {
                        val r = Native.devStep(120, out)
                        DevelopState.currentFrame = out[0]
                        DevelopState.doneFrames = out[1]
                        DevelopState.progress = out[2]
                        val now = System.currentTimeMillis()
                        if (r > 0 && now - lastPreviewMs > 250) {
                            lastPreviewMs = now
                            if (Native.devPreview(bmp)) {
                                DevelopState.preview = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            }
                        }
                        if (r <= 0) {
                            if (r < 0) DevelopState.error = "Development failed"
                            break
                        }
                    }
                    bmp.recycle()
                    if (!cancelled) {
                        Card(this).exportPrints()
                    }
                }
            } catch (t: Throwable) {
                DevelopState.error = t.message ?: "Error"
            } finally {
                Native.devClose()
                DevelopState.running = false
                DevelopState.preview = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "app.mfilm.android.STOP_DEV"
        const val EXTRA_ROLL = "roll"
        const val EXTRA_SIZE = "size"
        const val EXTRA_PUSH = "push"
        const val EXTRA_FRAME = "frame"

        fun start(context: Context, rollNo: Int, size: Int, pushStops: Int = 0, targetFrame: Int = 0) {
            val i = Intent(context, DevelopService::class.java)
                .putExtra(EXTRA_ROLL, rollNo)
                .putExtra(EXTRA_SIZE, size)
                .putExtra(EXTRA_PUSH, pushStops)
                .putExtra(EXTRA_FRAME, targetFrame)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DevelopService::class.java).setAction(ACTION_STOP))
        }
    }
}
