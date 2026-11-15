package app.mfilm.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The film OS's 720 x 480 screen (the shelf, the darkroom, the prints ...),
 * shown while it is up, and its touch screen: a tap is SET, a drag steps the
 * arrows the way it goes, two fingers are MENU (back). The camera itself is
 * the app's own screen (Ui.kt).
 */
class ScreenView(context: Context) : View(context) {
    val bitmap: Bitmap = Bitmap.createBitmap(Native.W, Native.H, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = 0f
    private var axis = 0                 // 0 not yet, 1 across, 2 up and down
    private var twoFingers = false

    override fun onDraw(canvas: Canvas) {
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    private fun key(k: Int) {
        Native.key(k)
        Native.key(k or Native.RELEASE)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = 0f; axis = 0; twoFingers = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> twoFingers = true
            MotionEvent.ACTION_MOVE -> if (!twoFingers) drag(e)
            MotionEvent.ACTION_UP -> if (twoFingers || axis == 0) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                key(if (twoFingers) Native.K_MENU else Native.K_SET)
            }
        }
        return true
    }

    private fun drag(e: MotionEvent) {
        if (axis == 0) {
            if (hypot(e.x - downX, e.y - downY) < slop) return
            axis = if (abs(e.x - downX) >= abs(e.y - downY)) 1 else 2
            lastX = e.x; lastY = e.y
            return
        }
        // a step: a 14th of the width across, an 8th of the height up and down
        val step = if (axis == 1) width / 14f else height / 8f
        moved += if (axis == 1) e.x - lastX else lastY - e.y      // up is +
        lastX = e.x; lastY = e.y
        while (abs(moved) >= step) {
            val plus = moved > 0
            moved -= if (plus) step else -step
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            key(if (axis == 1) (if (plus) Native.K_RIGHT else Native.K_LEFT) else (if (plus) Native.K_UP else Native.K_DOWN))
        }
    }
}
