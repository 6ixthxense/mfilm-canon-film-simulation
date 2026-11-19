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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object DevelopState {
    var running by mutableStateOf(false)
    var queuedCount by mutableIntStateOf(0)
    var rollNo by mutableIntStateOf(0)
    var currentFrame by mutableIntStateOf(0)
    var doneFrames by mutableIntStateOf(0)
    var totalFrames by mutableIntStateOf(0)
    var progress by mutableIntStateOf(0)       // 0..1000
    var preview by mutableStateOf<Bitmap?>(null)
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
}

private data class DevJob(val rollNo: Int, val size: Int, val push: Int, val targetFrame: Int)

class DevelopService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val queue = ConcurrentLinkedQueue<DevJob>()
    private val draining = AtomicBoolean(false)
    @Volatile private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelled = true
            queue.clear()
            DevelopState.queuedCount = 0
            stopSelf()
            return START_NOT_STICKY
        }
        val rollNo = intent?.getIntExtra(EXTRA_ROLL, -1) ?: -1
        val size = intent?.getIntExtra(EXTRA_SIZE, 0) ?: 0
        val push = intent?.getIntExtra(EXTRA_PUSH, 0) ?: 0
        val targetFrame = intent?.getIntExtra(EXTRA_FRAME, 0) ?: 0
        if (rollNo <= 0) return START_NOT_STICKY

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
        queue.add(DevJob(rollNo, size, push, targetFrame))
        DevelopState.queuedCount = queue.size

        if (draining.compareAndSet(false, true)) {
            DevelopState.running = true
            DevelopState.error = null
            DevelopState.status = null

            worker.execute {
                var totalSaved = 0
                try {
                    val bmp = Bitmap.createBitmap(180, 120, Bitmap.Config.ARGB_8888)
                    val out = IntArray(3)
                    while (!cancelled) {
                        val job = queue.poll() ?: break
                        DevelopState.queuedCount = queue.size
                        DevelopState.rollNo = job.rollNo
                        DevelopState.doneFrames = 0
                        DevelopState.totalFrames = 0
                        DevelopState.progress = 0

                        val total = Native.devOpen(job.rollNo, job.size, job.push, job.targetFrame)
                        if (total <= 0) {
                            DevelopState.error = if (total < 0) "Cannot open roll" else "Nothing to develop"
                            Native.devClose()
                            continue
                        }
                        DevelopState.totalFrames = total
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
                                if (r < 0) {
                                    val err = Native.devError()
                                    DevelopState.error = err.ifBlank { "Development failed" }
                                }
                                break
                            }
                        }
                        Native.devClose()
                        if (!cancelled) {
                            totalSaved += Card(this).exportPrintsSync()
                        }
                    }
                    bmp.recycle()
                    if (!cancelled && totalSaved > 0) {
                        DevelopState.status = "Saved $totalSaved photo${if (totalSaved == 1) "" else "s"} to Pictures/M-Film"
                    }
                } catch (t: Throwable) {
                    DevelopState.error = t.message ?: "Error"
                } finally {
                    Native.devClose()
                    draining.set(false)
                    DevelopState.queuedCount = queue.size
                    DevelopState.running = false
                    DevelopState.preview = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        queue.clear()
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
