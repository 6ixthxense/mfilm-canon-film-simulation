package app.mfilm.android

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var card: Card
    private lateinit var preview: PreviewView
    private val ui = Ui()
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var rawCapture = false
    private var minFocusDist = 0f

    private var held = Surface.ROTATION_90
    private val holding by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                held = when (deg) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
    }
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val histExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var soundPool: SoundPool? = null
    private var sfxShutter = 0
    private var sfxWind = 0
    private var sfxRewind = 0
    private var sfxTick = 0
    private val ev = IntArray(3)
    private val roll = IntArray(6)
    private var running = false
    private var volumeDown = false
    private var timerRunnable: Runnable? = null
    private var doubleFirst: Bitmap? = null
    private var halfFirst: Bitmap? = null
    private var pendingDoubleBlend: Bitmap? = null
    private var lastMeterUpdateMs = 0L
    private var lastTiltMs = 0L
    private var lastWidgetFilm = ""
    private var expRangeNs: Range<Long>? = null
    private var isoRange: Range<Int>? = null
    private var sensorManager: SensorManager? = null
    private var pendingImportUris by mutableStateOf<List<Uri>>(emptyList())
    private var importing by mutableStateOf(false)

    private val filmCatalog by lazy { loadFilmCatalog() }

    private val tiltListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!ui.spiritLevel || ui.route != Route.CAMERA) return
            val now = System.currentTimeMillis()
            if (now - lastTiltMs < 33) return
            lastTiltMs = now
            val gx = event.values[0].toDouble()
            val gy = event.values[1].toDouble()
            if (gx * gx + gy * gy < 2.0) return
            val deg = (atan2(gx, gy) * 180.0 / PI).toFloat()
            val nearest = (deg / 90f).roundToInt() * 90f
            val err = (deg - nearest).coerceIn(-45f, 45f)
            ui.tiltDeg = ui.tiltDeg * 0.78f + err * 0.22f
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else Toast.makeText(this, "M-Film needs the camera to shoot", Toast.LENGTH_LONG).show()
    }

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val ok = res.values.any { it }
        ui.gpsTag = ok
        getPreferences(MODE_PRIVATE).edit().putBoolean("gps_tag", ok).apply()
        if (!ok) Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
    }

    private val pickGallery = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) pendingImportUris = uris.take(36)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()

        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }

        card = Card(this)
        if (!Native.started()) {
            try {
                card.install()
            } catch (t: Throwable) {
                Log.e(Card.TAG, "install", t)
            }
            val r = Native.start(card.data.path, card.root.path + "/")
            if (r != 0) {
                AlertDialog.Builder(this).setTitle("M-Film").setMessage("The film OS could not start ($r).")
                    .setPositiveButton("OK") { _, _ -> finish() }.show()
                return
            }
            card.resumePending()
        }

        val prefs = getPreferences(MODE_PRIVATE)
        ui.leftHanded = prefs.getBoolean("left_handed", false)
        ui.cleanFinder = prefs.getBoolean("clean_finder", false)
        ui.framelines = prefs.getBoolean("framelines", true)
        ui.spiritLevel = prefs.getBoolean("spirit_level", true)
        ui.filmPreviewMode = prefs.getInt("film_preview_mode", 2).coerceIn(0, 2)
        ui.singleShot = prefs.getBoolean("single_shot", false)
        ui.defaultPrintSize = prefs.getInt("default_print_size", 1).coerceIn(0, 2)
        ui.aspectFormat = prefs.getInt("aspect_format", 0).coerceIn(0, 2)
        ui.filmRebate = prefs.getBoolean("film_rebate", false)
        ui.lightLeakMode = prefs.getInt("light_leak_mode", 0).coerceIn(0, 2)
        ui.dustMode = prefs.getInt("dust_mode", 0).coerceIn(0, 2)
        ui.showHistogram = prefs.getBoolean("show_histogram", true)
        ui.gpsTag = prefs.getBoolean("gps_tag", false)
        ui.autoCleanRaw = prefs.getBoolean("auto_clean_raw", false)
        ui.realisticWind = Native.getSetting(Native.SET_WIND) != 0
        ui.dateStamp = Native.getSetting(Native.SET_STAMP) != 0
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        if (ui.singleShot && !ui.loaded) {
            Native.switchFilm(0, true)
        }
        updateLabel()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build().also { sp ->
                sfxShutter = sp.load(this, R.raw.shutter, 1)
                sfxWind = sp.load(this, R.raw.wind, 1)
                sfxRewind = sp.load(this, R.raw.rewind, 1)
                sfxTick = sp.load(this, R.raw.tick, 1)
            }

        val actions = Actions(
            half = { down ->
                ui.halfPressed = down
                Native.half(down)
            },
            fire = ::release,
            exposure = ::exposure,
            focus = { x, y -> focusAt(x, y, lock = false) },
            lockFocus = { x, y -> focusAt(x, y, lock = true) },
            zoom = ::setZoom,
            film = { ui.route = Route.SHELF },
            counter = { ui.route = Route.ROLL },
            prints = { ui.printsRoll = -1; ui.route = Route.PRINTS },
            darkroom = { ui.route = Route.DARKROOM },
            settings = { ui.route = Route.SETTINGS },
            wind = { playWindSound(); Native.wind() },
            toggleTimer = {
                cancelTimer()
                ui.timerSec = when (ui.timerSec) {
                    0 -> 2
                    2 -> 10
                    else -> 0
                }
            },
            toggleDoubleExp = {
                ui.doubleExposure = !ui.doubleExposure
                if (ui.doubleExposure) {
                    ui.halfFrame = false
                    halfFirst?.recycle(); halfFirst = null
                    ui.halfFirstReady = false
                } else {
                    doubleFirst?.recycle(); doubleFirst = null
                    pendingDoubleBlend?.recycle(); pendingDoubleBlend = null
                    ui.doubleFirstReady = false
                }
                startCamera()
            },
            toggleHalfFrame = {
                ui.halfFrame = !ui.halfFrame
                if (ui.halfFrame) {
                    ui.doubleExposure = false
                    doubleFirst?.recycle(); doubleFirst = null
                    ui.doubleFirstReady = false
                } else {
                    halfFirst?.recycle(); halfFirst = null
                    pendingDoubleBlend?.recycle(); pendingDoubleBlend = null
                    ui.halfFirstReady = false
                }
                startCamera()
            },
            toggleManualExp = {
                if (ui.manualExpSupported) {
                    ui.manualExp = !ui.manualExp
                    applyCamera2Controls()
                }
            },
            stepManualShutter = { delta ->
                ui.manualShutterIdx = (ui.manualShutterIdx + delta).coerceIn(0, MANUAL_SHUTTERS.lastIndex)
                if (ui.manualExp) applyCamera2Controls()
            },
            stepManualIso = { delta ->
                ui.manualIsoIdx = (ui.manualIsoIdx + delta).coerceIn(0, MANUAL_ISOS.lastIndex)
                if (ui.manualExp) applyCamera2Controls()
            },
            toggleMf = {
                if (ui.mfSupported) {
                    ui.mfEnabled = !ui.mfEnabled
                    if (!ui.mfEnabled) ui.mfLoupe = false
                    applyCamera2Controls()
                }
            },
            setMfDistance = { d ->
                ui.mfDistance = d
                if (ui.mfEnabled) applyCamera2Controls()
            },
            setLoupe = { on -> ui.mfLoupe = on },
            toggleFlash = {
                ui.flashMode = (ui.flashMode + 1) % 3
                applyFlashMode()
            },
            toggleCamera = {
                ui.frontCamera = !ui.frontCamera
                if (ui.frontCamera) {
                    ui.mfEnabled = false
                    ui.mfLoupe = false
                    ui.manualExp = false
                }
                startCamera()
            },
            toggleFormat = {
                ui.aspectFormat = (ui.aspectFormat + 1) % 3
                prefs.edit().putInt("aspect_format", ui.aspectFormat).apply()
                if (ui.aspectFormat != 0 && ui.halfFrame) {
                    ui.halfFrame = false
                    halfFirst?.recycle(); halfFirst = null
                    ui.halfFirstReady = false
                    startCamera()
                }
            },
            toggleWb = {
                ui.wbMode = (ui.wbMode + 1) % 4
                applyCamera2Controls()
            },
        )

        setContent {
            when (ui.route) {
                Route.CAMERA -> CameraScreen(ui, preview, actions)
                Route.SHELF -> ShelfScreen(
                    ui,
                    onLoad = { film, exp ->
                        Native.loadRoll(film, exp)
                        updateLabel()
                        ui.route = Route.CAMERA
                    },
                    onSwitchFilm = { film ->
                        Native.switchFilm(film, true)
                        updateLabel()
                        ui.route = Route.CAMERA
                    },
                    onOpenRoll = { ui.route = Route.ROLL },
                    onBack = { ui.route = Route.CAMERA },
                )
                Route.ROLL -> RollScreen(
                    ui,
                    onRewind = {
                        playRewindSound()
                        Native.rewindRoll()
                        updateLabel()
                        ui.route = Route.CAMERA
                    },
                    onShelf = { ui.route = Route.SHELF },
                    onBack = { ui.route = Route.CAMERA },
                )
                Route.DARKROOM -> DarkroomScreen(
                    pendingImportCount = pendingImportUris.size,
                    importing = importing,
                    onRequestImport = { pickGallery.launch("image/*") },
                    onConfirmImport = { filmIdx, size, pushStops, grainPct, halPct ->
                        val uris = pendingImportUris
                        pendingImportUris = emptyList()
                        if (uris.isNotEmpty()) importGalleryRoll(uris, filmIdx, size, pushStops, grainPct, halPct)
                    },
                    onCancelImport = { pendingImportUris = emptyList() },
                    onDevelop = { rollNo, size, pushStops, grainPct, halPct ->
                        DevelopService.start(this, rollNo, size, pushStops, 0, grainPct, halPct)
                    },
                    onCancel = { DevelopService.stop(this) },
                    onOpenPrints = { rollNo -> ui.printsRoll = rollNo; ui.route = Route.PRINTS },
                    onBack = { pendingImportUris = emptyList(); updateLabel(); ui.route = Route.CAMERA },
                )
                Route.PRINTS -> PrintsScreen(
                    initialRoll = ui.printsRoll,
                    onBack = { ui.route = Route.CAMERA },
                )
                Route.SETTINGS -> SettingsScreen(
                    ui = ui,
                    onToggleShooting = {
                        ui.singleShot = !ui.singleShot
                        prefs.edit().putBoolean("single_shot", ui.singleShot).apply()
                        if (ui.singleShot && !ui.loaded) {
                            Native.switchFilm(0, true)
                            updateLabel()
                        }
                    },
                    onTogglePrintSize = {
                        ui.defaultPrintSize = (ui.defaultPrintSize + 1) % 3
                        prefs.edit().putInt("default_print_size", ui.defaultPrintSize).apply()
                    },
                    onToggleFormat = {
                        ui.aspectFormat = (ui.aspectFormat + 1) % 3
                        prefs.edit().putInt("aspect_format", ui.aspectFormat).apply()
                    },
                    onToggleRebate = {
                        ui.filmRebate = !ui.filmRebate
                        prefs.edit().putBoolean("film_rebate", ui.filmRebate).apply()
                    },
                    onToggleLightLeak = {
                        ui.lightLeakMode = (ui.lightLeakMode + 1) % 3
                        prefs.edit().putInt("light_leak_mode", ui.lightLeakMode).apply()
                    },
                    onToggleDust = {
                        ui.dustMode = (ui.dustMode + 1) % 3
                        prefs.edit().putInt("dust_mode", ui.dustMode).apply()
                    },
                    onToggleWind = {
                        ui.realisticWind = !ui.realisticWind
                        Native.setSetting(Native.SET_WIND, if (ui.realisticWind) 1 else 0)
                        updateLabel()
                    },
                    onToggleFinder = {
                        ui.cleanFinder = !ui.cleanFinder
                        prefs.edit().putBoolean("clean_finder", ui.cleanFinder).apply()
                    },
                    onToggleFramelines = {
                        ui.framelines = !ui.framelines
                        prefs.edit().putBoolean("framelines", ui.framelines).apply()
                    },
                    onToggleSpiritLevel = {
                        ui.spiritLevel = !ui.spiritLevel
                        prefs.edit().putBoolean("spirit_level", ui.spiritLevel).apply()
                    },
                    onToggleHistogram = {
                        ui.showHistogram = !ui.showHistogram
                        prefs.edit().putBoolean("show_histogram", ui.showHistogram).apply()
                        if (!ui.showHistogram) {
                            ui.histogram = null
                            ui.highlightClipped = false
                        }
                    },
                    onToggleLiveLook = {
                        ui.filmPreviewMode = (ui.filmPreviewMode + 1) % 3
                        prefs.edit().putInt("film_preview_mode", ui.filmPreviewMode).apply()
                        updateFilmPreviewMatrix()
                    },
                    onToggleDateStamp = {
                        ui.dateStamp = !ui.dateStamp
                        Native.setSetting(Native.SET_STAMP, if (ui.dateStamp) 1 else 0)
                    },
                    onToggleGps = {
                        if (!ui.gpsTag) {
                            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if (fine || coarse) {
                                ui.gpsTag = true
                                prefs.edit().putBoolean("gps_tag", true).apply()
                            } else {
                                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            }
                        } else {
                            ui.gpsTag = false
                            prefs.edit().putBoolean("gps_tag", false).apply()
                        }
                    },
                    onToggleAutoClean = {
                        ui.autoCleanRaw = !ui.autoCleanRaw
                        prefs.edit().putBoolean("auto_clean_raw", ui.autoCleanRaw).apply()
                    },
                    onCleanRaws = {
                        val cleaned = card.cleanAllDevelopedRaws()
                        ui.rawBytes = card.rawStorageBytes()
                        Toast.makeText(
                            this,
                            if (cleaned > 0) "Cleaned $cleaned developed RAW files" else "No developed RAW files to clean",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onToggleHand = {
                        ui.leftHanded = !ui.leftHanded
                        prefs.edit().putBoolean("left_handed", ui.leftHanded).apply()
                    },
                    onBack = { ui.route = Route.CAMERA },
                )
            }
        }
        applyRouteFromIntent(intent)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)

        card.exportPrints()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyRouteFromIntent(intent)
    }

    private fun applyRouteFromIntent(i: Intent?) {
        when (i?.getStringExtra("route")) {
            "shelf" -> ui.route = Route.SHELF
            "roll" -> ui.route = Route.ROLL
            "darkroom" -> ui.route = Route.DARKROOM
            "prints" -> { ui.printsRoll = -1; ui.route = Route.PRINTS }
            "settings" -> ui.route = Route.SETTINGS
        }
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    // ---------------------------------------------------------------- shutter, timer, double-exposure

    private fun halfPress() {
        ui.halfPressed = true
        Native.half(true)
    }

    private fun release(fire: Boolean) {
        ui.halfPressed = false
        Native.half(false)
        if (!fire || ui.route != Route.CAMERA) return
        if (ui.timerRemaining > 0) {
            cancelTimer()
            return
        }
        if (ui.timerSec > 0) {
            startTimer(ui.timerSec)
            return
        }
        fireShutter()
    }

    private fun startTimer(seconds: Int) {
        ui.timerRemaining = seconds
        playTickSound()
        val r = object : Runnable {
            override fun run() {
                val left = ui.timerRemaining - 1
                if (left > 0) {
                    ui.timerRemaining = left
                    playTickSound()
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    ui.timerRemaining = 0
                    timerRunnable = null
                    fireShutter()
                }
            }
        }
        timerRunnable = r
        mainHandler.postDelayed(r, 1000L)
    }

    private fun cancelTimer() {
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        timerRunnable = null
        ui.timerRemaining = 0
    }

    private fun fireShutter() {
        if (!ui.loaded) {
            Toast.makeText(this, "Load film first", Toast.LENGTH_SHORT).show()
            return
        }
        if (ui.needWind) {
            Toast.makeText(this, "Wind on first", Toast.LENGTH_SHORT).show()
            return
        }
        if (ui.halfFrame) {
            val ic = imageCapture
            if (ic == null) {
                Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
                return
            }
            ic.targetRotation = held
            ui.shutterFlash = true
            playShutterSound()
            preview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            ic.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val half = try {
                        frameHalfOf(image)
                    } catch (t: Throwable) {
                        Log.e(Card.TAG, "half-frame capture", t)
                        null
                    } finally {
                        image.close()
                    }
                    if (half == null) return
                    runOnUiThread {
                        if (halfFirst == null) {
                            halfFirst = half
                            ui.halfFirstReady = true
                        } else {
                            val left = halfFirst!!
                            halfFirst = null
                            ui.halfFirstReady = false
                            captureExecutor.execute {
                                val diptych = stitchHalfFrame(left, half)
                                left.recycle()
                                half.recycle()
                                pendingDoubleBlend = diptych
                                runOnUiThread { Native.shutter() }
                            }
                        }
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    Log.e(Card.TAG, "half-frame capture", e)
                }
            })
            return
        }
        if (ui.doubleExposure) {
            val ic = imageCapture
            if (ic == null) {
                Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
                return
            }
            ic.targetRotation = held
            ui.shutterFlash = true
            playShutterSound()
            preview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            ic.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val snap = try {
                        frameOf(image)
                    } catch (t: Throwable) {
                        Log.e(Card.TAG, "double exposure capture", t)
                        null
                    } finally {
                        image.close()
                    }
                    if (snap == null) return
                    runOnUiThread {
                        if (doubleFirst == null) {
                            doubleFirst = snap
                            ui.doubleFirstReady = true
                        } else {
                            val first = doubleFirst!!
                            doubleFirst = null
                            ui.doubleFirstReady = false
                            captureExecutor.execute {
                                val blended = blendBitmaps(first, snap)
                                first.recycle()
                                snap.recycle()
                                pendingDoubleBlend = blended
                                runOnUiThread { Native.shutter() }
                            }
                        }
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    Log.e(Card.TAG, "double exposure capture", e)
                }
            })
            return
        }
        Native.shutter()
    }

    private fun frameHalfOf(image: ImageProxy): Bitmap {
        val src = image.toBitmap()
        val rot = image.imageInfo.rotationDegrees
        val upright = if (rot != 0) {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                .also { if (it !== src) src.recycle() }
        } else src
        // target aspect 1280:1728 (~0.74 portrait half-frame)
        var h = upright.height
        var w = (h * 1280L / 1728L).toInt()
        if (w > upright.width) {
            w = upright.width
            h = (w * 1728L / 1280L).toInt()
        }
        val crop = Bitmap.createBitmap(upright, (upright.width - w) / 2, (upright.height - h) / 2, w, h)
        if (crop !== upright) upright.recycle()
        val scaled = Bitmap.createScaledBitmap(crop, 1280, 1728, true)
        if (scaled !== crop) crop.recycle()
        return scaled
    }

    private fun stitchHalfFrame(left: Bitmap, right: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(2592, 1728, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(0xFF0A0A09.toInt())
        canvas.drawBitmap(left, null, Rect(0, 0, 1280, 1728), null)
        canvas.drawBitmap(right, null, Rect(1312, 0, 2592, 1728), null)
        return out
    }

    private fun blendBitmaps(a: Bitmap, b: Bitmap): Bitmap {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        val sa = if (a.width != w || a.height != h) Bitmap.createScaledBitmap(a, w, h, true) else a
        val sb = if (b.width != w || b.height != h) Bitmap.createScaledBitmap(b, w, h, true) else b
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        sa.getPixels(pa, 0, w, 0, 0, w, h)
        sb.getPixels(pb, 0, w, 0, 0, w, h)
        for (i in pa.indices) {
            val c1 = pa[i]; val c2 = pb[i]
            val r = (((c1 shr 16) and 0xFF) + ((c2 shr 16) and 0xFF)) ushr 1
            val g = (((c1 shr 8) and 0xFF) + ((c2 shr 8) and 0xFF)) ushr 1
            val bl = ((c1 and 0xFF) + (c2 and 0xFF)) ushr 1
            pa[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        if (sa !== a) sa.recycle()
        if (sb !== b) sb.recycle()
        return Bitmap.createBitmap(pa, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (event.repeatCount == 0) { volumeDown = true; halfPress() }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && ui.route != Route.CAMERA) {
            if (event.repeatCount == 0) ui.route = Route.CAMERA
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (volumeDown) { volumeDown = false; release(true) }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && ui.route != Route.CAMERA) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------------- the camera

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val camSel = if (ui.frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                val pvb = Preview.Builder().setResolutionSelector(selector)
                Camera2Interop.Extender(pvb).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastMeterUpdateMs < 150) return
                        lastMeterUpdateMs = now
                        val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        val isoVal = result.get(CaptureResult.SENSOR_SENSITIVITY)
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        val shutterStr = if (expNs != null && expNs > 0L) {
                            val sec = expNs / 1_000_000_000.0
                            if (sec >= 0.4) "%.1f\"".format(sec)
                            else "1/${(1.0 / sec).roundToInt().coerceAtLeast(1)}"
                        } else null
                        val offset = when (aeState) {
                            CaptureResult.CONTROL_AE_STATE_CONVERGED,
                            CaptureResult.CONTROL_AE_STATE_LOCKED -> 0
                            else -> ui.ev.coerceIn(-2, 2).let { if (it == 0) 1 else it }
                        }
                        runOnUiThread {
                            if (!ui.manualExp) {
                                if (shutterStr != null) ui.liveShutter = shutterStr
                                if (isoVal != null) ui.liveIso = isoVal
                            }
                            ui.meterOffset = if (ui.manualExp) 0 else offset
                        }
                    }
                })
                val pv = pvb.build()
                pv.setSurfaceProvider(preview.surfaceProvider)

                val supportsRaw = ImageCapture.OUTPUT_FORMAT_RAW in
                    ImageCapture.getImageCaptureCapabilities(provider.getCameraInfo(camSel)).supportedOutputFormats
                rawCapture = supportsRaw && !ui.doubleExposure && !ui.halfFrame && !ui.frontCamera
                val mode = if (ui.doubleExposure || ui.halfFrame) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                val icb = ImageCapture.Builder().setCaptureMode(mode)
                if (rawCapture) {
                    icb.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
                    Camera2Interop.Extender(icb).setCaptureRequestOption(
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                } else icb.setResolutionSelector(selector)
                val ic = icb.build()
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, camSel, pv, ic).also {
                    exposureScale(it)
                    zoomScale(it)
                    readLensSpecs()
                }
                imageCapture = ic
                applyFlashMode()
                applyCamera2Controls()
                Log.i(Card.TAG, if (rawCapture) "camera: RAW (DNG)" else "camera: JPEG -> CR2")
            } catch (t: Throwable) {
                Log.e(Card.TAG, "camera", t)
                Toast.makeText(this, "No camera: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** a tap on LiveView focuses and meters there; a long-press locks both until the next tap */
    private fun focusAt(x: Float, y: Float, lock: Boolean) {
        val cam = camera ?: return
        if (ui.mfEnabled) {
            ui.mfEnabled = false
            applyCamera2Controls()
        }
        val point = preview.meteringPointFactory.createPoint(x, y)
        val b = FocusMeteringAction.Builder(point)
        if (lock) b.disableAutoCancel()
        cam.cameraControl.startFocusAndMetering(b.build())
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCamera2Controls() {
        val cam = camera ?: return
        val ctrl = Camera2CameraControl.from(cam.cameraControl)
        val b = CaptureRequestOptions.Builder()
        if (ui.mfEnabled && minFocusDist > 0f) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, ui.mfDistance * minFocusDist)
        } else {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (ui.manualExp && ui.manualExpSupported) {
            val (ssLabel, rawNs) = MANUAL_SHUTTERS.getOrElse(ui.manualShutterIdx) { MANUAL_SHUTTERS[3] }
            val rawIso = MANUAL_ISOS.getOrElse(ui.manualIsoIdx) { 400 }
            val ns = expRangeNs?.clamp(rawNs) ?: rawNs
            val iso = isoRange?.clamp(rawIso) ?: rawIso
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            ui.liveShutter = ssLabel
            ui.liveIso = iso
        } else {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        val awb = when (ui.wbMode) {
            1 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            2 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            3 -> CaptureRequest.CONTROL_AWB_MODE_SHADE
            else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        }
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awb)
        ctrl.setCaptureRequestOptions(b.build())
    }

    private fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val z = cam.cameraInfo.zoomState.value
        val clamped = if (z != null) ratio.coerceIn(z.minZoomRatio, z.maxZoomRatio) else ratio
        cam.cameraControl.setZoomRatio(clamped)
        ui.zoom = ratio
    }

    private fun zoomScale(cam: Camera) {
        val z = cam.cameraInfo.zoomState.value
        val list = mutableListOf<Float>()
        if (z != null && z.minZoomRatio <= 0.65f) list.add(0.6f)
        list.add(1f)
        if (z == null || z.maxZoomRatio >= 2f) list.add(2f)
        ui.lenses = list
        ui.zoom = 1f
    }

    /** a drag up or down on LiveView: the exposure, a step at a time */
    private fun exposure(steps: Int) {
        val cam = camera ?: return
        if (!cam.cameraInfo.exposureState.isExposureCompensationSupported) return
        ui.ev = (ui.ev + steps).coerceIn(ui.evMin, ui.evMax)
        cam.cameraControl.setExposureCompensationIndex(ui.ev)
    }

    /** the exposure dial's scale: the camera's steps, as far as 3 EV either way */
    private fun exposureScale(cam: Camera) {
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) { ui.evMin = 0; ui.evMax = 0; return }
        ui.evStep = state.exposureCompensationStep.toFloat()
        val most = (3 / ui.evStep).toInt()
        ui.evMin = maxOf(state.exposureCompensationRange.lower, -most)
        ui.evMax = minOf(state.exposureCompensationRange.upper, most)
        ui.ev = state.exposureCompensationIndex.coerceIn(ui.evMin, ui.evMax)
    }

    private fun applyFlashMode() {
        imageCapture?.flashMode = when (ui.flashMode) {
            1 -> ImageCapture.FLASH_MODE_AUTO
            2 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    private fun recordShotGps(fileNumber: Int) {
        if (!ui.gpsTag) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
            var best: Location? = null
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                val loc = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null }
                if (loc != null && (best == null || loc.time > best.time)) best = loc
            }
            if (best != null) card.recordGps(fileNumber, best.latitude, best.longitude)
        } catch (_: Throwable) { }
    }

    private fun importGalleryRoll(
        uris: List<Uri>,
        filmIdx: Int,
        size: Int,
        pushStops: Int,
        grainPct: Int,
        halPct: Int,
    ) {
        importing = true
        captureExecutor.execute {
            try {
                val firstFile = IntArray(1)
                val rollNo = Native.importRoll(filmIdx, uris.size, firstFile)
                if (rollNo > 0) {
                    val ok = card.importImagesAsCr2(uris, firstFile[0])
                    runOnUiThread {
                        importing = false
                        if (ok > 0) {
                            DevelopService.start(this, rollNo, size, pushStops, 0, grainPct, halPct)
                        } else {
                            Toast.makeText(this, "Could not decode selected photos", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        importing = false
                        Toast.makeText(this, "Could not create import roll", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (t: Throwable) {
                Log.e(Card.TAG, "importGalleryRoll", t)
                runOnUiThread {
                    importing = false
                    Toast.makeText(this, "Import error: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** the film OS asked for frame `number`: take it and keep it */
    private fun capture(number: Int) {
        recordShotGps(number)
        if (!ui.halfFrame && ui.aspectFormat != 0) card.recordFormat(number, ui.aspectFormat)
        val dbl = pendingDoubleBlend
        if (dbl != null) {
            pendingDoubleBlend = null
            captureExecutor.execute {
                val ok = try {
                    card.shot(number, dbl)
                    true
                } catch (t: Throwable) {
                    Log.e(Card.TAG, "double exposure", t)
                    false
                }
                Native.shotDone(ok, false)
            }
            return
        }

        val ic = imageCapture
        if (ic == null) {
            Native.shotDone(false, false)
            Toast.makeText(this, "The camera isn't ready", Toast.LENGTH_SHORT).show()
            return
        }
        ic.targetRotation = held
        ui.shutterFlash = true
        playShutterSound()
        preview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (rawCapture) {
            val part = card.rawPart(number)
            ic.takePicture(ImageCapture.OutputFileOptions.Builder(part).build(), captureExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        Native.shotDone(card.rawDone(part, true), true)
                    }

                    override fun onError(e: ImageCaptureException) {
                        Log.e(Card.TAG, "RAW capture", e)
                        card.rawDone(part, false)
                        Native.shotDone(false, true)
                    }
                })
            return
        }
        ic.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val ok = try {
                    val photo = frameOf(image)
                    card.shot(number, photo)
                    true
                } catch (t: Throwable) {
                    Log.e(Card.TAG, "capture", t)
                    false
                } finally {
                    image.close()
                }
                Native.shotDone(ok, false)
            }

            override fun onError(e: ImageCaptureException) {
                Log.e(Card.TAG, "capture", e)
                Native.shotDone(false, false)
            }
        })
    }

    /** the picture upright, its middle 3:2, at most 2592 x 1728 (what the CR2 is made from) */
    private fun frameOf(image: ImageProxy): Bitmap {
        val src = image.toBitmap()
        val rot = image.imageInfo.rotationDegrees
        val upright = if (rot != 0) {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                .also { if (it !== src) src.recycle() }
        } else src
        var w = upright.width
        var h = w * 2 / 3
        if (h > upright.height) { h = upright.height; w = h * 3 / 2 }
        val scale = minOf(1f, 2592f / w)
        val m = Matrix().apply { postScale(scale, scale) }
        val out = Bitmap.createBitmap(upright, (upright.width - w) / 2, (upright.height - h) / 2, w, h, m, true)
        if (out !== upright) upright.recycle()
        return out
    }

    // ---------------------------------------------------------------- events & telemetry

    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            while (Native.pollEvent(ev)) event(ev[0], ev[1], ev[2])
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun event(what: Int, a: Int, b: Int) {
        when (what) {
            Native.EV_CAPTURE -> capture(a)
            Native.EV_SHOT -> {
                updateLabel()
                if (ui.singleShot && ui.rollNumber > 0 && ui.shot > 0) {
                    DevelopService.start(this, ui.rollNumber, ui.defaultPrintSize, 0, ui.shot)
                }
            }
            Native.EV_LOADED, Native.EV_REWOUND, Native.EV_WIND -> updateLabel()
            Native.EV_FINISHED -> {
                updateLabel()
                if (ui.singleShot) {
                    // auto-roll over cleanly when 64-frame single-shot buffer fills
                    val currentName = ui.film
                    val idx = (0 until Native.filmCount()).firstOrNull {
                        Native.filmInfo(it, IntArray(6)).substringBefore('|') == currentName
                    } ?: 0
                    Native.switchFilm(idx, true)
                    updateLabel()
                } else {
                    ui.route = Route.ROLL
                }
            }
            Native.EV_DEVELOPING -> if (a == 0) {
                updateLabel()
                card.exportPrints { n ->
                    if (n > 0) runOnUiThread { Toast.makeText(this, "$n prints in Pictures/M-Film", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    /** the roll, for the screen (Ui.kt) */
    private fun updateLabel() {
        ui.film = Native.rollInfo(roll)
        ui.loaded = roll[0] != 0
        ui.shot = roll[1]
        ui.exposures = roll[2]
        ui.needWind = roll[3] != 0
        ui.iso = roll[4]
        ui.rollNumber = roll[5]
        val entry = if (ui.loaded) filmCatalog.firstOrNull { it.name == ui.film } else null
        ui.isBwFilm = entry?.group == 2
        ui.pendingRolls = Native.pendingRolls()
        ui.rawBytes = card.rawStorageBytes()
        updateFilmPreviewMatrix()
        if (entry != null && ui.film != lastWidgetFilm) {
            lastWidgetFilm = ui.film
            card.saveWidgetCanister(entry.index, ui.exposures)
        }
        MFilmWidget.updateAll(this, ui)
    }

    private fun updateFilmPreviewMatrix() {
        if (!ui.loaded || ui.filmPreviewMode == 0) {
            ui.filmColorMatrix = null
            return
        }
        val entry = filmCatalog.firstOrNull { it.name == ui.film }
        if (entry == null) {
            ui.filmColorMatrix = null
            return
        }
        if (ui.filmPreviewMode == 1) {
            ui.filmColorMatrix = if (entry.group == 2) ColorMatrix().apply { setSaturation(0f) }.array else null
            return
        }
        // Mode 2: Live Film Look Preview from the film's attributes (group, punch, colour, warmth)
        val cm = ColorMatrix()
        if (entry.group == 2) {
            cm.setSaturation(0f)
            val c = 0.96f + (entry.punch - 2) * 0.09f
            val t = (1f - c) * 128f + 4f
            val w = (entry.warmth - 3) * 0.03f
            cm.postConcat(ColorMatrix(floatArrayOf(
                c * (1f + w), 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c * (1f - w), 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )))
        } else {
            val sat = (0.76f + (entry.colour - 1) * 0.13f).coerceIn(0.70f, 1.35f)
            cm.setSaturation(sat)
            val c = 0.94f + (entry.punch - 2) * 0.075f
            val lift = if (entry.group == 1) 0f else 6f
            val t = (1f - c) * 128f + lift
            val w = (entry.warmth - 3) * 0.055f
            val gTint = if (entry.group == 3 && entry.warmth <= 2) 1.03f else 1f
            cm.postConcat(ColorMatrix(floatArrayOf(
                c * (1f + w), 0f, 0f, 0f, t,
                0f, c * gTint, 0f, 0f, t,
                0f, 0f, c * (1f - w), 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        ui.filmColorMatrix = cm.array
    }

    private fun readLensSpecs() {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val wantFacing = if (ui.frontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) == wantFacing) {
                    val ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
                    if (ap != null && ap > 0f) {
                        Native.setShotFstop((ap * 100f).toInt())
                        ui.liveFstop = "f/%.1f".format(ap)
                    }
                    minFocusDist = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    ui.mfSupported = !ui.frontCamera && minFocusDist > 0f
                    expRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    val aeModes = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                    ui.manualExpSupported = !ui.frontCamera &&
                        expRangeNs != null && isoRange != null &&
                        aeModes != null && CaptureRequest.CONTROL_AE_MODE_OFF in aeModes
                    break
                }
            }
        } catch (_: Throwable) { }
    }

    private fun playSound(id: Int) {
        if (id != 0) soundPool?.play(id, 1f, 1f, 1, 0, 1f)
    }

    private fun playShutterSound() = playSound(sfxShutter)
    private fun playWindSound() = playSound(sfxWind)
    private fun playRewindSound() = playSound(sfxRewind)
    private fun playTickSound() = playSound(sfxTick)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) Native.battery(level * 100 / scale)
        }
    }

    private val histSampler: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (ui.showHistogram && !ui.cleanFinder && ui.route == Route.CAMERA) {
                val bmp = preview.bitmap
                if (bmp != null) {
                    histExecutor.execute {
                        try {
                            val sw = 64
                            val sh = 48
                            val small = Bitmap.createScaledBitmap(bmp, sw, sh, false)
                            bmp.recycle()
                            val px = IntArray(sw * sh)
                            small.getPixels(px, 0, sw, 0, 0, sw, sh)
                            small.recycle()
                            val bins = IntArray(32)
                            var clipped = 0
                            for (c in px) {
                                val r = (c shr 16) and 0xFF
                                val g = (c shr 8) and 0xFF
                                val bl = c and 0xFF
                                val l = (r * 54 + g * 183 + bl * 19) shr 8
                                bins[(l shr 3).coerceIn(0, 31)]++
                                if (l >= 248) clipped++
                            }
                            val maxBin = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
                            val norm = FloatArray(32) { i -> bins[i].toFloat() / maxBin }
                            val isClip = clipped > (px.size * 3 / 100)
                            runOnUiThread {
                                ui.histogram = norm
                                ui.highlightClipped = isClip
                            }
                        } catch (_: Throwable) {
                            runCatching { bmp.recycle() }
                        }
                    }
                }
            }
            mainHandler.postDelayed(this, 250L)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Native.started()) return
        running = true
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(tiltListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        updateLabel()
        Choreographer.getInstance().postFrameCallback(tick)
        mainHandler.postDelayed(histSampler, 400L)
        @Suppress("DEPRECATION")
        held = windowManager.defaultDisplay.rotation
        if (holding.canDetectOrientation()) holding.enable()
    }

    override fun onPause() {
        super.onPause()
        cancelTimer()
        mainHandler.removeCallbacks(histSampler)
        holding.disable()
        sensorManager?.unregisterListener(tiltListener)
        if (running) {
            running = false
            unregisterReceiver(batteryReceiver)
        }
        Native.half(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        doubleFirst?.recycle()
        halfFirst?.recycle()
        pendingDoubleBlend?.recycle()
        histExecutor.shutdownNow()
        soundPool?.release()
        soundPool = null
    }
}
