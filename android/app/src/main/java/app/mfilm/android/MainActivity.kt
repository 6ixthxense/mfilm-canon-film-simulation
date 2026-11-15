package app.mfilm.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
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
    private lateinit var rollLabel: TextView
    private var imageCapture: ImageCapture? = null
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val sound = MediaActionSound()
    private val ev = IntArray(3)
    private val roll = IntArray(3)
    private var running = false
    private var uiUp = true
    private var volumeDown = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else Toast.makeText(this, "M-Film needs the camera to shoot", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()

        screen = findViewById(R.id.screen)
        preview = findViewById(R.id.preview)
        rollLabel = findViewById(R.id.rollLabel)
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

        bindKey(R.id.kMenu, Native.K_MENU)
        bindKey(R.id.kInfo, Native.K_INFO)
        bindKey(R.id.kPlay, Native.K_PLAY)
        bindKey(R.id.kUp, Native.K_UP)
        bindKey(R.id.kDown, Native.K_DOWN)
        bindKey(R.id.kLeft, Native.K_LEFT)
        bindKey(R.id.kRight, Native.K_RIGHT)
        bindKey(R.id.kSet, Native.K_SET)
        bindKey(R.id.kWheelL, Native.K_WHEEL_L)
        bindKey(R.id.kWheelR, Native.K_WHEEL_R)
        bindShutter(findViewById(R.id.shutter))

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

    @SuppressLint("ClickableViewAccessibility")
    private fun bindKey(id: Int, k: Int) {
        findViewById<View>(id).setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    Native.key(k)
                    // the dial only clicks: no release
                    if (k == Native.K_WHEEL_L || k == Native.K_WHEEL_R) Native.key(k or Native.RELEASE)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (k != Native.K_WHEEL_L && k != Native.K_WHEEL_R) Native.key(k or Native.RELEASE)
                }
            }
            true
        }
    }

    /** down: a half-press (the HUD lights, the film OS closes); up: the shutter */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindShutter(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.isPressed = true; halfPress() }
                MotionEvent.ACTION_UP -> { view.isPressed = false; release(true) }
                MotionEvent.ACTION_CANCEL -> { view.isPressed = false; release(false) }
            }
            true
        }
    }

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
        if (keyCode == KeyEvent.KEYCODE_BACK && Native.uiRunning()) {
            Native.key(Native.K_MENU or Native.RELEASE)
            return true
        }
        hwKeys[keyCode]?.let { Native.key(it or Native.RELEASE); return true }
        return super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------------- the camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                val pv = Preview.Builder().setResolutionSelector(selector).build()
                pv.setSurfaceProvider(preview.surfaceProvider)
                val ic = ImageCapture.Builder()
                    .setResolutionSelector(selector)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, ic)
                imageCapture = ic
            } catch (t: Throwable) {
                Log.e(Card.TAG, "camera", t)
                Toast.makeText(this, "No camera: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** the film OS asked for frame `number`: take it, crop it to 3:2, keep it */
    private fun capture(number: Int) {
        val ic = imageCapture
        if (ic == null) {
            Native.shotDone(false)
            Toast.makeText(this, "The camera isn't ready", Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("DEPRECATION")
        ic.targetRotation = windowManager.defaultDisplay.rotation
        sound.play(MediaActionSound.SHUTTER_CLICK)
        screen.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
                Native.shotDone(ok)
            }

            override fun onError(e: ImageCaptureException) {
                Log.e(Card.TAG, "capture", e)
                Native.shotDone(false)
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
            Native.EV_MODE -> { uiUp = a != 0; updateLabel() }
            Native.EV_SHOT, Native.EV_LOADED, Native.EV_REWOUND, Native.EV_FINISHED -> updateLabel()
            Native.EV_DEVELOPING -> if (a == 0) card.exportPrints { n ->
                if (n > 0) runOnUiThread { Toast.makeText(this, "$n prints in Pictures/M-Film", Toast.LENGTH_LONG).show() }
            }
            Native.EV_DIM -> window.attributes = window.attributes.apply {
                screenBrightness = if (a != 0) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            Native.EV_CANON_MENU -> about()
        }
    }

    private fun updateLabel() {
        val film = Native.rollInfo(roll)
        rollLabel.text = if (roll[0] != 0) "$film\n${roll[1]} / ${roll[2]}" else "no film"
    }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle("M-Film for Android")
            .setMessage(
                "The film OS of M-Film (github.com/HNGSeries/mfilm-canon-film-simulation, GPL-2.0), " +
                    "running on the phone.\n\nThe card: ${card.root.path}\nPrints are copied to Pictures/M-Film.\n\n" +
                    "Colour tables of 59 films: Pat David, CC BY-SA 4.0 (see ML/THIRD_PARTY.txt on the card). " +
                    "Not affiliated with Canon, Kodak, Fujifilm or others."
            )
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
    }

    override fun onPause() {
        super.onPause()
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
