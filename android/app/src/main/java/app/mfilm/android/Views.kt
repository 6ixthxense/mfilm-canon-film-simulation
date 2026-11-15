package app.mfilm.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs

/** A 3:2 box, as tall as it may be: the camera's screen. */
class AspectFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        var h = maxH
        var w = h * 3 / 2
        if (w > maxW) { w = maxW; h = w * 2 / 3 }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}

/** The film OS's 720 x 480 screen over LiveView; a drag across it turns the wheel. */
class ScreenView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    val bitmap: Bitmap = Bitmap.createBitmap(Native.W, Native.H, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    private var lastX = 0f
    private var moved = 0f

    override fun onDraw(canvas: Canvas) {
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val step = width / 14f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; moved = 0f }
            MotionEvent.ACTION_MOVE -> {
                moved += e.x - lastX
                lastX = e.x
                while (abs(moved) >= step) {
                    val k = if (moved > 0) Native.K_WHEEL_R else Native.K_WHEEL_L
                    Native.key(k)
                    Native.key(k or Native.RELEASE)
                    moved -= if (moved > 0) step else -step
                    performHapticFeedback(HAPTIC_FEEDBACK_ENABLED_TICK)
                }
            }
        }
        return true
    }

    companion object {
        private const val HAPTIC_FEEDBACK_ENABLED_TICK = android.view.HapticFeedbackConstants.CLOCK_TICK
    }
}
