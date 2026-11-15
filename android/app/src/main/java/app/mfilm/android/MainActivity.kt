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
import android.graphics.Matrix
import android.media.MediaActionSound
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
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
import android.hardware.camera2.CaptureRequest
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var card: Card
    private lateinit var screen: ScreenView
    private lateinit var preview: PreviewView
    private val ui = Ui()
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var rawCapture = false

    /**
     * How the phone is held when the shutter goes, from the sensor: a frame
     * shot with the phone upright is a portrait one (its RAW's orientation;
     * the darkroom turns the print).
     */
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
    private val sound = MediaActionSound()
    private val ev = IntArray(3)
    private val roll = IntArray(5)
    private var running = false
    private var uiUp = true
    private var volumeDown = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else Toast.makeText(this, "M-Film needs the camera to shoot", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()

        screen = ScreenView(this)
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        sound.load(MediaActionSound.SHUTTER_CLICK)

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
        Native.frame(screen.bitmap, true)

        // turned round, the activity starts again: the film OS carries on where it was
        uiUp = Native.uiRunning()
        ui.filmOs = uiUp
        ui.leftHanded = getPreferences(MODE_PRIVATE).getBoolean("left_handed", false)
        updateLabel()
        val tap = { k: Int -> Native.key(k); Native.key(k or Native.RELEASE) }
        val actions = Actions(
            half = { if (it) halfPress() },
            fire = ::release,
            exposure = ::exposure,
            focus = { x, y -> focusAt(x, y, lock = false) },
            lockFocus = { x, y -> focusAt(x, y, lock = true) },
            zoom = ::setZoom,
            film = { ui.route = Route.SHELF },
            counter = { ui.route = Route.ROLL },
            prints = { Native.openScreen(Native.OPEN_PRINTS) },
            darkroom = { Native.openScreen(Native.OPEN_DARKROOM) },
            wind = { tap(Native.K_SET) },
            key = tap,
        )
        setContent {
            when (ui.route) {
                Route.CAMERA -> CameraScreen(ui, preview, screen, actions)
                Route.SHELF -> ShelfScreen(
                    ui,
                    onLoad = { film, exp ->
                        Native.loadRoll(film, exp)
                        updateLabel()
                        ui.route = Route.CAMERA
                    },
                    onOpenRoll = { ui.route = Route.ROLL },
                    onBack = { ui.route = Route.CAMERA },
                )
                Route.ROLL -> RollScreen(
                    ui,
                    onRewind = {
                        Native.rewindRoll()
                        updateLabel()
                        ui.route = Route.CAMERA
                    },
                    onShelf = { ui.route = Route.SHELF },
                    onBack = { ui.route = Route.CAMERA },
                )
            }
        }
        // a debug build, started with `am start ... --ez camera true`: straight to the camera (a half-press),
        // for a screenshot of it where nothing can touch the screen
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.getBooleanExtra("camera", false)) {
            screen.postDelayed({ Native.half(true); Native.half(false) }, 2500)
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            when (intent.getStringExtra("route")) {
                "shelf" -> ui.route = Route.SHELF
                "roll" -> ui.route = Route.ROLL
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)

        card.exportPrints()
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

    // ---------------------------------------------------------------- keys

    /** the shutter touched: a half-press (the film OS's screens close); let go: the shutter */
    private fun halfPress() {
        Native.half(true)
    }

    private fun release(fire: Boolean) {
        Native.half(false)
        if (fire && !uiUp) Native.shutter()
    }

    private val hwKeys = mapOf(
        KeyEvent.KEYCODE_DPAD_LEFT to Native.K_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to Native.K_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP to Native.K_UP, KeyEvent.KEYCODE_DPAD_DOWN to Native.K_DOWN,
        KeyEvent.KEYCODE_DPAD_CENTER to Native.K_SET, KeyEvent.KEYCODE_ENTER to Native.K_SET,
        KeyEvent.KEYCODE_M to Native.K_MENU, KeyEvent.KEYCODE_I to Native.K_INFO, KeyEvent.KEYCODE_P to Native.K_PLAY,
        KeyEvent.KEYCODE_LEFT_BRACKET to Native.K_WHEEL_L, KeyEvent.KEYCODE_RIGHT_BRACKET to Native.K_WHEEL_R,
    )

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // the volume keys are the shutter
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (event.repeatCount == 0) { volumeDown = true; halfPress() }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && ui.route != Route.CAMERA) {
            if (event.repeatCount == 0) ui.route = Route.CAMERA
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && Native.uiRunning()) {
            if (event.repeatCount == 0) Native.key(Native.K_MENU)
            return true
        }
        hwKeys[keyCode]?.let { if (event.repeatCount == 0) Native.key(it); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (volumeDown) { volumeDown = false; release(true) }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && ui.route != Route.CAMERA) {
            if (event.repeatCount == 0) ui.route = Route.CAMERA
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && Native.uiRunning()) {
            Native.key(Native.K_MENU or Native.RELEASE)
            return true
        }
        hwKeys[keyCode]?.let { Native.key(it or Native.RELEASE); return true }
        return super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------------- the camera

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val back = CameraSelector.DEFAULT_BACK_CAMERA
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                val pv = Preview.Builder().setResolutionSelector(selector).build()
                pv.setSurfaceProvider(preview.surfaceProvider)
                // the sensor's own RAW where the phone gives it (a DNG the darkroom develops), else a photo made into a CR2
                rawCapture = ImageCapture.OUTPUT_FORMAT_RAW in
                    ImageCapture.getImageCaptureCapabilities(provider.getCameraInfo(back)).supportedOutputFormats
                val icb = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                if (rawCapture) {
                    icb.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
                    // the lens's fall-off goes into the DNG (OpcodeList2), for the darkroom to take out
                    Camera2Interop.Extender(icb).setCaptureRequestOption(
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                } else icb.setResolutionSelector(selector)
                val ic = icb.build()
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, back, pv, ic).also {
                    exposureScale(it)
                    zoomScale(it)
                }
                imageCapture = ic
                Log.i(Card.TAG, if (rawCapture) "camera: RAW (DNG)" else "camera: no RAW, photos made into CR2s")
            } catch (t: Throwable) {
                Log.e(Card.TAG, "camera", t)
                Toast.makeText(this, "No camera: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** a tap on LiveView focuses and meters there; a long-press locks both until the next tap */
    private fun focusAt(x: Float, y: Float, lock: Boolean) {
        val cam = camera ?: return
        val point = preview.meteringPointFactory.createPoint(x, y)
        val b = FocusMeteringAction.Builder(point)
        if (lock) b.disableAutoCancel()
        cam.cameraControl.startFocusAndMetering(b.build())
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

    /** the film OS asked for frame `number`: take it and keep it */
    private fun capture(number: Int) {
        val ic = imageCapture
        if (ic == null) {
            Native.shotDone(false, false)
            Toast.makeText(this, "The camera isn't ready", Toast.LENGTH_SHORT).show()
            return
        }
        ic.targetRotation = held
        sound.play(MediaActionSound.SHUTTER_CLICK)
        screen.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (rawCapture) {
            // straight to the card; the roll counts it once it is all there
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

    // ---------------------------------------------------------------- the film OS's screen and events

    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            while (Native.pollEvent(ev)) event(ev[0], ev[1], ev[2])
            if (Native.frame(screen.bitmap, false)) screen.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun event(what: Int, a: Int, b: Int) {
        when (what) {
            Native.EV_CAPTURE -> capture(a)
            Native.EV_MODE -> { uiUp = a != 0; ui.filmOs = uiUp; updateLabel() }
            Native.EV_SHOT, Native.EV_LOADED, Native.EV_REWOUND, Native.EV_FINISHED, Native.EV_WIND -> updateLabel()
            Native.EV_DEVELOPING -> if (a == 0) {
                updateLabel()
                card.exportPrints { n ->
                    if (n > 0) runOnUiThread { Toast.makeText(this, "$n prints in Pictures/M-Film", Toast.LENGTH_LONG).show() }
                }
            }
            Native.EV_DIM -> window.attributes = window.attributes.apply {
                screenBrightness = if (a != 0) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            Native.EV_CANON_MENU -> about()
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
        ui.pendingRolls = Native.pendingRolls()
    }

    private fun about() {
        val handLabel = if (ui.leftHanded) "Right-handed layout" else "Left-handed layout"
        AlertDialog.Builder(this)
            .setTitle("M-Film for Android")
            .setMessage(
                "The film OS of M-Film (github.com/HNGSeries/mfilm-canon-film-simulation, GPL-2.0), " +
                    "running on the phone.\n\nThe card: ${card.root.path}\nPrints are copied to Pictures/M-Film.\n\n" +
                    "Colour tables of 59 films: Pat David, CC BY-SA 4.0 (see ML/THIRD_PARTY.txt on the card). " +
                    "Not affiliated with Canon, Kodak, Fujifilm or others."
            )
            .setNeutralButton(handLabel) { _, _ ->
                ui.leftHanded = !ui.leftHanded
                getPreferences(MODE_PRIVATE).edit().putBoolean("left_handed", ui.leftHanded).apply()
            }
            .setPositiveButton("OK", null)
            .show()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) Native.battery(level * 100 / scale)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Native.started()) return
        running = true
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        Native.frame(screen.bitmap, true)
        screen.invalidate()
        updateLabel()
        Choreographer.getInstance().postFrameCallback(tick)
        @Suppress("DEPRECATION")
        held = windowManager.defaultDisplay.rotation        // until the sensor says
        if (holding.canDetectOrientation()) holding.enable()
    }

    override fun onPause() {
        super.onPause()
        holding.disable()
        if (running) {
            running = false
            unregisterReceiver(batteryReceiver)
        }
        Native.half(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
    }
}
