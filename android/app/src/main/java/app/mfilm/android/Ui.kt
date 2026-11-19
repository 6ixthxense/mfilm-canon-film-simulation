package app.mfilm.android

import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The app's own screens: a film camera, not the Canon's buttons on glass.
 *
 * Two materials and two accents, and nothing drawn that isn't a control or a
 * reading: the body's black, brass where a camera's paint wears through;
 * amber (M-Film's) for what is set, the counter's red. Labels are engraved
 * capitals, not icons.
 */
object Look {
    val Body = Color(0xFF0E0D0C)
    val Plate = Color(0xFF1A1816)
    val Window = Color(0xFF050505)
    val Brass = Color(0xFFB08D57)
    val BrassWorn = Color(0xFFD9B77A)
    val Amber = Color(0xFFECA834)
    val Red = Color(0xFFFF3A28)
    val RedUnlit = Color(0xFF2B0D09)
    val Ivory = Color(0xFFD8CFC0)
    val Dim = Color(0xFF7D756A)
}

private val Engraved = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
)

private fun engraved(size: TextUnit, color: Color) = TextStyle(
    color = color, fontSize = size, fontFamily = Engraved, fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.14.em, textAlign = TextAlign.Center,
)

@Composable
fun Label(text: String, modifier: Modifier = Modifier, size: TextUnit = 13.sp, color: Color = Look.Ivory) {
    BasicText(text.uppercase(), modifier, style = engraved(size, color), maxLines = 1)
}

enum class Route { CAMERA, SHELF, ROLL, DARKROOM, PRINTS, SETTINGS }

val MANUAL_SHUTTERS = listOf(
    "1/1000" to 1_000_000L,
    "1/500" to 2_000_000L,
    "1/250" to 4_000_000L,
    "1/125" to 8_000_000L,
    "1/60" to 16_666_667L,
    "1/30" to 33_333_333L,
    "1/15" to 66_666_667L,
    "1/8" to 125_000_000L,
    "1/4" to 250_000_000L,
    "1/2" to 500_000_000L,
    "1\"" to 1_000_000_000L,
)

val MANUAL_ISOS = listOf(100, 200, 400, 800, 1600, 3200)

/** What the screens show: the activity keeps it up to date. */
class Ui {
    var route by mutableStateOf(Route.CAMERA)
    var loaded by mutableStateOf(false)
    var film by mutableStateOf("")
    var iso by mutableIntStateOf(0)
    var shot by mutableIntStateOf(0)
    var exposures by mutableIntStateOf(0)
    var rollNumber by mutableIntStateOf(0)
    var needWind by mutableStateOf(false)
    var singleShot by mutableStateOf(false)
    var ev by mutableIntStateOf(0)              // exposure compensation, in the camera's steps
    var evStep by mutableFloatStateOf(1f / 3)   // EV a step
    var evMin by mutableIntStateOf(-6)
    var evMax by mutableIntStateOf(6)
    var focus by mutableStateOf<Offset?>(null)  // where the last tap focused, on the finder
    var focusLocked by mutableStateOf(false)
    var halfPressed by mutableStateOf(false)
    var liveShutter by mutableStateOf("1/125")
    var liveFstop by mutableStateOf("f/1.8")
    var liveIso by mutableIntStateOf(400)
    var meterOffset by mutableIntStateOf(0)     // -2..2
    var timerSec by mutableIntStateOf(0)        // 0, 2, 10
    var timerRemaining by mutableIntStateOf(0)  // active countdown > 0
    var mfSupported by mutableStateOf(false)
    var mfEnabled by mutableStateOf(false)
    var mfDistance by mutableFloatStateOf(0f)   // 0f = inf .. 1f = near
    var mfLoupe by mutableStateOf(false)
    var doubleExposure by mutableStateOf(false)
    var doubleFirstReady by mutableStateOf(false)
    var halfFrame by mutableStateOf(false)
    var halfFirstReady by mutableStateOf(false)
    var manualExpSupported by mutableStateOf(false)
    var manualExp by mutableStateOf(false)
    var manualShutterIdx by mutableIntStateOf(3) // 1/125
    var manualIsoIdx by mutableIntStateOf(2)     // 400
    var zoom by mutableFloatStateOf(1f)
    var lenses by mutableStateOf(listOf(1f))
    var pendingRolls by mutableIntStateOf(0)
    var leftHanded by mutableStateOf(false)
    var realisticWind by mutableStateOf(false)
    var cleanFinder by mutableStateOf(false)
    var filmPreviewMode by mutableIntStateOf(2)  // 0=RAW, 1=B&W only, 2=FILM look
    var filmColorMatrix by mutableStateOf<FloatArray?>(null)
    var isBwFilm by mutableStateOf(false)
    var spiritLevel by mutableStateOf(true)
    var tiltDeg by mutableFloatStateOf(0f)
    var dateStamp by mutableStateOf(false)
    var gpsTag by mutableStateOf(false)
    var autoCleanRaw by mutableStateOf(false)
    var rawBytes by mutableStateOf(0L)
    var framelines by mutableStateOf(true)
    var printsRoll by mutableIntStateOf(-1)
    var defaultPrintSize by mutableIntStateOf(1)    // 0=S, 1=M, 2=L (for SINGLE mode)
    var flashMode by mutableIntStateOf(0)           // 0=OFF, 1=AUTO, 2=ON
    var frontCamera by mutableStateOf(false)
    var shutterFlash by mutableStateOf(false)
}

/** What the controls do. */
class Actions(
    val half: (Boolean) -> Unit,                // the shutter touched (half-pressed), or let go
    val fire: (Boolean) -> Unit,                // let go: true to shoot, false if the finger slid off
    val exposure: (Int) -> Unit,                // steps up or down
    val focus: (Float, Float) -> Unit,          // on the finder, its pixels
    val lockFocus: (Float, Float) -> Unit,      // long-press: lock focus and exposure
    val zoom: (Float) -> Unit,                  // lens ring: switch focal length
    val film: () -> Unit,
    val counter: () -> Unit,
    val prints: () -> Unit,
    val darkroom: () -> Unit,
    val settings: () -> Unit,
    val wind: () -> Unit,
    val toggleTimer: () -> Unit,
    val toggleDoubleExp: () -> Unit,
    val toggleHalfFrame: () -> Unit,
    val toggleManualExp: () -> Unit,
    val stepManualShutter: (Int) -> Unit,
    val stepManualIso: (Int) -> Unit,
    val toggleMf: () -> Unit,
    val setMfDistance: (Float) -> Unit,
    val setLoupe: (Boolean) -> Unit,
    val toggleFlash: () -> Unit,
    val toggleCamera: () -> Unit,
)

// ---------------------------------------------------------------- the controls

/** An engraved word that is a button: amber while pressed. */
@Composable
fun Key(text: String, modifier: Modifier = Modifier, color: Color = Look.Ivory, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val view = LocalView.current
    Box(
        modifier.defaultMinSize(56.dp, 48.dp).clickable(source, indication = null, role = Role.Button) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        },
        contentAlignment = Alignment.Center,
    ) { Label(text, color = if (pressed) Look.Amber else color) }
}

/** Darkroom door: shows an amber dot when a roll is waiting to be developed. */
@Composable
fun DarkroomKey(pending: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val view = LocalView.current
    Row(
        modifier.defaultMinSize(56.dp, 48.dp).clickable(source, indication = null, role = Role.Button) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (pending > 0) {
            Canvas(Modifier.padding(end = 6.dp).size(6.dp)) { drawCircle(Look.Amber) }
        }
        Label("Darkroom", color = if (pressed) Look.Amber else Look.Ivory)
    }
}

/** Prints door: shows a blinking amber indicator while DevelopService is actively developing. */
@Composable
fun PrintsKey(developing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val view = LocalView.current
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(developing) {
        if (!developing) {
            blink = false
            return@LaunchedEffect
        }
        while (true) {
            blink = !blink
            delay(450)
        }
    }
    Row(
        modifier.defaultMinSize(56.dp, 48.dp).clickable(source, indication = null, role = Role.Button) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (developing) {
            Canvas(Modifier.padding(end = 6.dp).size(6.dp)) {
                drawCircle(if (blink) Look.Amber else Look.Amber.copy(alpha = 0.25f))
            }
        }
        Label("Prints", color = if (pressed) Look.Amber else Look.Ivory)
    }
}

/** Lens ring: 0.6 · 1 · 2 — tap a number to switch focal length. */
@Composable
fun LensRing(ui: Ui, modifier: Modifier = Modifier, onZoom: (Float) -> Unit) {
    val view = LocalView.current
    Row(
        modifier
            .background(Look.Window, RoundedCornerShape(3.dp))
            .border(1.dp, Look.Brass, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        ui.lenses.forEachIndexed { idx, ratio ->
            if (idx > 0) Label("·", size = 12.sp, color = Look.Dim)
            val active = abs(ui.zoom - ratio) < 0.05f
            val label = if (ratio < 1f) "%.1f".format(ratio) else "%.0f".format(ratio)
            Box(
                Modifier
                    .defaultMinSize(28.dp, 36.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onZoom(ratio)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Label(label, size = 12.sp, color = if (active) Look.Amber else Look.Ivory)
            }
        }
    }
}

/** Film advance lever (Realistic mode): swipe across or tap to wind on. */
@Composable
fun WindLever(modifier: Modifier = Modifier, onWind: () -> Unit) {
    val view = LocalView.current
    val wind by rememberUpdatedState(onWind)
    var dragX by remember { mutableFloatStateOf(0f) }
    var fired by remember { mutableStateOf(false) }
    Box(
        modifier
            .defaultMinSize(76.dp, 48.dp)
            .background(Look.Plate, RoundedCornerShape(3.dp))
            .border(1.dp, Look.Amber, RoundedCornerShape(3.dp))
            .semantics { contentDescription = "Wind on"; role = Role.Button }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragX = 0f; fired = false },
                    onDragEnd = { dragX = 0f; fired = false },
                    onDragCancel = { dragX = 0f; fired = false },
                ) { change, drag ->
                    change.consume()
                    if (fired) return@detectDragGestures
                    dragX += drag.x
                    if (abs(dragX) >= 28.dp.toPx()) {
                        fired = true
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        wind()
                    }
                }
            }
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                wind()
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label("Wind →", size = 12.sp, color = Look.Amber)
    }
}

/** The shutter release: touching it is the half-press, letting go shoots. */
@Composable
fun Shutter(size: Dp, on: Actions) {
    var down by remember { mutableStateOf(false) }
    Canvas(
        Modifier.size(size)
            .semantics { contentDescription = "Shutter"; role = Role.Button }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    down = true
                    on.half(true)
                    val up = waitForUpOrCancellation()
                    down = false
                    on.fire(up != null)
                }
            }
    ) {
        val r = this.size.minDimension / 2
        val hair = 1.dp.toPx()
        drawCircle(Look.Plate, r)                                           // the collar
        drawCircle(Look.Brass, r - hair, style = Stroke(hair * 1.5f))
        val button = r * if (down) 0.70f else 0.76f                         // it goes in
        drawCircle(if (down) Color(0xFF8C6E40) else Look.Brass, button)
        drawCircle(Look.BrassWorn, button - hair, style = Stroke(hair), alpha = 0.7f)
        drawCircle(Look.Body, button * 0.20f)                               // the cable release's thread
    }
}

private val SEGMENTS = mapOf(
    '0' to 0b1111110, '1' to 0b0110000, '2' to 0b1101101, '3' to 0b1111001, '4' to 0b0110011,
    '5' to 0b1011011, '6' to 0b1011111, '7' to 0b1110000, '8' to 0b1111111, '9' to 0b1111011, '-' to 0b0000001,
)

/** one seven-segment digit in the box at o: lit segments red, the others just seen */
private fun DrawScope.digit(c: Char, o: Offset, w: Float, h: Float) {
    val bits = SEGMENTS[c] ?: 0
    val t = h * 0.10f
    val g = t * 0.3f                          // the gap between segments
    val r = CornerRadius(t / 2)
    val half = (h - t) / 2
    fun seg(bit: Int, x: Float, y: Float, sw: Float, sh: Float) =
        drawRoundRect(if (bits shr bit and 1 == 1) Look.Red else Look.RedUnlit, Offset(o.x + x, o.y + y), Size(sw, sh), r)
    // the bars lie between the uprights' middles, the uprights between the bars'
    val bx = t / 2 + g
    val bw = w - 2 * bx
    val uy = t / 2 + g
    val uh = half - 2 * g
    seg(6, bx, 0f, bw, t)                   // a
    seg(5, w - t, uy, t, uh)                // b
    seg(4, w - t, uy + half, t, uh)         // c
    seg(3, bx, h - t, bw, t)                // d
    seg(2, 0f, uy + half, t, uh)            // e
    seg(1, 0f, uy, t, uh)                   // f
    seg(0, bx, half, bw, t)                 // g
}

/** The frame counter's window: the frame the next shot will be. */
@Composable
fun Counter(ui: Ui, onClick: () -> Unit) {
    val finished = !ui.singleShot && ui.loaded && ui.shot >= ui.exposures
    val digits = when {
        !ui.loaded -> "  "
        finished -> "--"
        else -> "%02d".format((ui.shot + 1) % 100)
    }
    Column(
        Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (ui.loaded) "Frame ${ui.shot + 1} of ${ui.exposures}" else "No film" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(84.dp, 58.dp)) {
            val corner = CornerRadius(4.dp.toPx())
            drawRoundRect(Look.Window, cornerRadius = corner)
            drawRoundRect(Look.Brass, cornerRadius = corner, style = Stroke(1.dp.toPx()))
            val h = size.height * 0.62f
            val w = h * 0.52f
            val gap = w * 0.34f
            val x0 = (size.width - 2 * w - gap) / 2
            val y0 = (size.height - h) / 2
            digit(digits[0], Offset(x0, y0), w, h)
            digit(digits[1], Offset(x0 + w + gap, y0), w, h)
        }
        Label(
            when {
                !ui.loaded -> "No film"
                ui.singleShot -> "Single"
                finished -> "End of roll"
                else -> "of ${ui.exposures}"
            },
            Modifier.padding(top = 6.dp), size = 11.sp, color = if (finished) Look.Amber else Look.Dim,
        )
    }
}

/** Mechanical manual focus distance slider: infinity to near, engages focus loupe while dragging. */
@Composable
fun FocusDial(ui: Ui, modifier: Modifier = Modifier, onDrag: (Float) -> Unit, onDragState: (Boolean) -> Unit) {
    val view = LocalView.current
    val dragCb by rememberUpdatedState(onDrag)
    val stateCb by rememberUpdatedState(onDragState)
    val text = rememberTextMeasurer()
    Canvas(
        modifier
            .semantics { contentDescription = "Manual focus" }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { stateCb(true) },
                    onDragEnd = { stateCb(false) },
                    onDragCancel = { stateCb(false) },
                ) { change, drag ->
                    change.consume()
                    val delta = drag.x / size.width
                    val next = (ui.mfDistance + delta).coerceIn(0f, 1f)
                    if (abs(next - ui.mfDistance) > 0.02f) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    dragCb(next)
                }
            }
    ) {
        val corner = CornerRadius(3.dp.toPx())
        val hair = 1.dp.toPx()
        drawRoundRect(Look.Window, cornerRadius = corner)
        drawRoundRect(Look.Brass, cornerRadius = corner, style = Stroke(hair))
        val marks = listOf(0f to "∞", 0.25f to "2M", 0.5f to "1M", 0.75f to ".5", 1f to ".2")
        val pad = 16.dp.toPx()
        val span = (size.width - 2 * pad).coerceAtLeast(1f)
        for ((pos, lbl) in marks) {
            val x = pad + pos * span
            drawLine(Look.Dim, Offset(x, size.height - 10.dp.toPx()), Offset(x, size.height - 3.dp.toPx()), hair)
            val m = text.measure(lbl, engraved(9.sp, Look.Dim))
            drawText(m, topLeft = Offset(x - m.size.width / 2f, 3.dp.toPx()))
        }
        val ix = pad + ui.mfDistance * span
        drawLine(Look.Amber, Offset(ix, 2.dp.toPx()), Offset(ix, size.height - 2.dp.toPx()), 2.dp.toPx())
    }
}

/** The film reminder window on a camera's back: what is loaded. Touch it for the shelf. */
@Composable
fun FilmWindow(ui: Ui, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .background(Look.Window, RoundedCornerShape(4.dp))
            .border(1.dp, Look.Brass, RoundedCornerShape(4.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!ui.loaded) {
            Label("Load", color = Look.Amber)
            Label("film", color = Look.Amber)
        } else {
            // "Kodak Portra 400" breaks before its last word, as a canister's label does
            val cut = ui.film.trim().lastIndexOf(' ')
            if (cut > 0) Label(ui.film.substring(0, cut), size = 12.sp)
            Label(ui.film.substring(cut + 1), size = 15.sp)
            Label("ISO ${ui.iso}", Modifier.padding(top = 6.dp), size = 11.sp, color = Look.Dim)
        }
    }
}

/**
 * Exposure compensation: the edge of a dial seen through a slot. The scale
 * goes with the thumb, a click a step, under an index that stays.
 */
@Composable
fun ExposureDial(ui: Ui, vertical: Boolean, modifier: Modifier = Modifier, onStep: (Int) -> Unit) {
    val view = LocalView.current
    val step by rememberUpdatedState(onStep)
    val text = rememberTextMeasurer()
    val pitch = 15.dp
    Canvas(
        modifier
            .semantics { contentDescription = "Exposure compensation" }
            .pointerInput(vertical) {
                var moved = 0f
                detectDragGestures(onDragStart = { moved = 0f }) { change, drag ->
                    change.consume()
                    // the scale follows the thumb: + is up (or to the right), so a drag down (or left) brings + to the index
                    moved += if (vertical) drag.y else -drag.x
                    val p = pitch.toPx()
                    while (abs(moved) >= p) {
                        val d = if (moved > 0) 1 else -1
                        moved -= d * p
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        step(d)
                    }
                }
            }
    ) {
        val corner = CornerRadius(3.dp.toPx())
        val hair = 1.dp.toPx()
        drawRoundRect(Look.Window, cornerRadius = corner)
        drawRoundRect(Look.Brass, cornerRadius = corner, style = Stroke(hair))
        val p = pitch.toPx()
        val along = if (vertical) size.height else size.width
        val across = if (vertical) size.width else size.height
        val mid = along / 2
        for (i in ui.evMin..ui.evMax) {
            // + up on a dial that stands, + to the right on one that lies
            val at = if (vertical) mid - (i - ui.ev) * p else mid + (i - ui.ev) * p
            if (at < p / 2 || at > along - p / 2) continue
            val value = i * ui.evStep
            val whole = abs(value - value.roundToInt()) < 0.01f
            val len = across * if (whole) 0.34f else 0.18f
            val colour = if (i == ui.ev) Look.Ivory else Look.Dim
            if (vertical) drawLine(colour, Offset(across - len, at), Offset(across - 3 * hair, at), hair * 1.5f)
            else drawLine(colour, Offset(at, across - len), Offset(at, across - 3 * hair), hair * 1.5f)
            if (whole) {
                val n = value.roundToInt()
                val m = text.measure(if (n > 0) "+$n" else if (n < 0) "−${-n}" else "0", engraved(11.sp, colour))
                val along0 = at - (if (vertical) m.size.height else m.size.width) / 2f
                val across0 = (across - len - (if (vertical) m.size.width else m.size.height)) / 2f
                drawText(m, topLeft = if (vertical) Offset(across0, along0) else Offset(along0, across0))
            }
        }
        // the index: on the scale's edge, amber when the exposure is off zero
        val mark = if (ui.ev == 0) Look.Ivory else Look.Amber
        val tip = 5.dp.toPx()
        drawPath(Path().apply {
            if (vertical) { moveTo(across, mid - tip); lineTo(across - tip * 1.4f, mid); lineTo(across, mid + tip) }
            else { moveTo(mid - tip, across); lineTo(mid, across - tip * 1.4f); lineTo(mid + tip, across) }
            close()
        }, mark)
    }
}

/** Manual Shutter Speed & ISO mechanical dial bar when Manual exposure (M) is active. */
@Composable
fun ManualDial(ui: Ui, modifier: Modifier = Modifier, onStepShutter: (Int) -> Unit, onStepIso: (Int) -> Unit) {
    val ssLabel = MANUAL_SHUTTERS.getOrElse(ui.manualShutterIdx) { MANUAL_SHUTTERS[3] }.first
    val isoVal = MANUAL_ISOS.getOrElse(ui.manualIsoIdx) { 400 }
    Row(
        modifier
            .background(Look.Window, RoundedCornerShape(3.dp))
            .border(1.dp, Look.Brass, RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Label("SS", size = 10.sp, color = Look.Dim)
            Key("‹", modifier = Modifier.defaultMinSize(30.dp, 38.dp), color = Look.Ivory) { onStepShutter(-1) }
            Label(ssLabel, size = 12.sp, color = Look.Amber, modifier = Modifier.width(46.dp))
            Key("›", modifier = Modifier.defaultMinSize(30.dp, 38.dp), color = Look.Ivory) { onStepShutter(1) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Label("ISO", size = 10.sp, color = Look.Dim)
            Key("‹", modifier = Modifier.defaultMinSize(30.dp, 38.dp), color = Look.Ivory) { onStepIso(-1) }
            Label("$isoVal", size = 12.sp, color = Look.Amber, modifier = Modifier.width(38.dp))
            Key("›", modifier = Modifier.defaultMinSize(30.dp, 38.dp), color = Look.Ivory) { onStepIso(1) }
        }
    }
}

// ---------------------------------------------------------------- the finder

/** a view made by the activity, put where the layout has it (it may have been somewhere before) */
@Composable
private fun <T : View> Placed(view: T, colorMatrix: FloatArray? = null, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { (view.parent as? ViewGroup)?.removeView(view); view },
        modifier = modifier,
        update = { v ->
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                v.setRenderEffect(
                    if (colorMatrix != null) android.graphics.RenderEffect.createColorFilterEffect(
                        android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(colorMatrix))
                    ) else null
                )
            }
        },
    )
}

/**
 * LiveView in the frame the picture will have.
 * Tap focuses and meters; long-press locks both; double-tap toggles 2.5x focus loupe.
 */
@Composable
private fun Finder(ui: Ui, preview: View, on: Actions, ratio: Float, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(ratio).background(Color.Black).clipToBounds()) {
        val zoomScale = if (ui.mfLoupe) 2.5f else 1f
        Placed(
            preview,
            colorMatrix = ui.filmColorMatrix,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoomScale, scaleY = zoomScale),
        )
        val view = LocalView.current
        val text = rememberTextMeasurer()
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onTap = { at ->
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    ui.focusLocked = false
                    ui.focus = at
                    on.focus(at.x, at.y)
                },
                onDoubleTap = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    on.setLoupe(!ui.mfLoupe)
                },
                onLongPress = { at ->
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    ui.focusLocked = true
                    ui.focus = at
                    on.lockFocus(at.x, at.y)
                },
            )
        }) {
            val mx = size.width / 2f; val my = size.height / 2f

            // Half-frame split mask: highlights active half (left first, then right)
            if (ui.halfFrame) {
                val maskX = if (ui.halfFirstReady) 0f else mx
                drawRect(Color(0x99000000), Offset(maskX, 0f), Size(mx, size.height))
                drawLine(Look.Brass.copy(alpha = 0.8f), Offset(mx, 0f), Offset(mx, size.height), 1.5.dp.toPx())
            }

            if (!ui.cleanFinder && ui.framelines) {
                val inset = 14.dp.toPx()
                val arm = 16.dp.toPx()
                val lw = 1.dp.toPx()
                val frameColor = Look.Ivory.copy(alpha = 0.35f)
                val x0 = inset; val y0 = inset
                val x1 = size.width - inset; val y1 = size.height - inset
                drawLine(frameColor, Offset(x0, y0), Offset(x0 + arm, y0), lw)
                drawLine(frameColor, Offset(x0, y0), Offset(x0, y0 + arm), lw)
                drawLine(frameColor, Offset(x1, y0), Offset(x1 - arm, y0), lw)
                drawLine(frameColor, Offset(x1, y0), Offset(x1, y0 + arm), lw)
                drawLine(frameColor, Offset(x0, y1), Offset(x0 + arm, y1), lw)
                drawLine(frameColor, Offset(x0, y1), Offset(x0, y1 - arm), lw)
                drawLine(frameColor, Offset(x1, y1), Offset(x1 - arm, y1), lw)
                drawLine(frameColor, Offset(x1, y1), Offset(x1, y1 - arm), lw)
                if (!ui.spiritLevel) {
                    val cr = 8.dp.toPx()
                    drawLine(frameColor, Offset(mx - cr, my), Offset(mx + cr, my), lw)
                    drawLine(frameColor, Offset(mx, my - cr), Offset(mx, my + cr), lw)
                }
            }

            // Electronic Horizon Spirit Level at finder center
            if (!ui.cleanFinder && ui.spiritLevel) {
                val isLevel = abs(ui.tiltDeg) <= 1.5f
                val refGap = 32.dp.toPx()
                val refLen = 8.dp.toPx()
                val refCol = if (isLevel) Look.Amber else Look.Ivory.copy(alpha = 0.35f)
                val refW = 1.dp.toPx()
                drawLine(refCol, Offset(mx - refGap - refLen, my), Offset(mx - refGap, my), refW)
                drawLine(refCol, Offset(mx + refGap, my), Offset(mx + refGap + refLen, my), refW)

                val rad = (-ui.tiltDeg.coerceIn(-35f, 35f)) * (PI / 180.0)
                val halfBar = 22.dp.toPx()
                val dx = (cos(rad) * halfBar).toFloat()
                val dy = (sin(rad) * halfBar).toFloat()
                drawLine(
                    if (isLevel) Look.Amber else Look.Ivory.copy(alpha = 0.6f),
                    Offset(mx - dx, my - dy),
                    Offset(mx + dx, my + dy),
                    if (isLevel) 2.dp.toPx() else 1.2.dp.toPx(),
                )
            }

            // Rangefinder live meter & exposure readout along bottom edge
            if (!ui.cleanFinder) {
                val pad = 12.dp.toPx()
                val lit = ui.halfPressed || ui.focusLocked || ui.manualExp
                val modePrefix = if (ui.manualExp) "M  " else ""
                val readout = "$modePrefix${ui.liveShutter}  ${ui.liveFstop}  ISO ${ui.liveIso}"
                val m = text.measure(readout, engraved(10.sp, if (lit) Look.Amber else Look.Ivory.copy(alpha = 0.72f)))
                drawText(m, topLeft = Offset(pad, size.height - m.size.height - 8.dp.toPx()))

                // - · ● · + rangefinder meter LEDs at bottom-right
                val dotR = 2.5.dp.toPx()
                val step = 10.dp.toPx()
                val cx = size.width - pad - 2 * step
                val cy = size.height - m.size.height / 2f - 8.dp.toPx()
                for (i in -2..2) {
                    val active = i == ui.meterOffset.coerceIn(-2, 2)
                    val col = when {
                        !active -> Look.Dim.copy(alpha = 0.45f)
                        i == 0 -> Look.Amber
                        else -> Look.Red
                    }
                    drawCircle(col, if (active) dotR * 1.4f else dotR, Offset(cx + i * step, cy))
                }
            }

            // Top-left status badges: 2.5X loupe, 2X double exposure, 1/2 half-frame
            var topY = 8.dp.toPx()
            if (ui.mfLoupe) {
                val m = text.measure("2.5X FOCUS", engraved(10.sp, Look.Amber))
                drawText(m, topLeft = Offset(10.dp.toPx(), topY))
                topY += m.size.height + 4.dp.toPx()
            }
            if (ui.doubleExposure) {
                val tag = if (ui.doubleFirstReady) "2X · 1/2 READY" else "2X"
                val m = text.measure(tag, engraved(10.sp, Look.Amber))
                drawText(m, topLeft = Offset(10.dp.toPx(), topY))
                topY += m.size.height + 4.dp.toPx()
            }
            if (ui.halfFrame) {
                val tag = if (ui.halfFirstReady) "HALF · R READY" else "HALF · L"
                val m = text.measure(tag, engraved(10.sp, Look.Amber))
                drawText(m, topLeft = Offset(10.dp.toPx(), topY))
            }

            // Self-timer blinking red LED & countdown
            if (ui.timerRemaining > 0) {
                val r = 6.dp.toPx()
                val ledCenter = Offset(size.width - 18.dp.toPx(), 18.dp.toPx())
                drawCircle(Look.Red, r, ledCenter)
                val m = text.measure("${ui.timerRemaining}", engraved(22.sp, Look.Red))
                drawText(m, topLeft = Offset(size.width - m.size.width - 30.dp.toPx(), 10.dp.toPx()))
            }

            // where it focused: the corners of a frame, as a finder's are
            val f = ui.focus ?: return@Canvas
            val r = 26.dp.toPx()
            val arm = 9.dp.toPx()
            val w = if (ui.focusLocked) 2.2.dp.toPx() else 1.5.dp.toPx()
            for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
                val c = Offset(f.x + sx * r, f.y + sy * r)
                drawLine(Look.Amber, c, Offset(c.x - sx * arm, c.y), w)
                drawLine(Look.Amber, c, Offset(c.x, c.y - sy * arm), w)
            }
            if (ui.focusLocked) {
                val m = text.measure("AE/AF", engraved(10.sp, Look.Amber))
                val tx = (f.x - m.size.width / 2f).coerceIn(4.dp.toPx(), size.width - m.size.width - 4.dp.toPx())
                val ty = (f.y + r + 4.dp.toPx()).coerceAtMost(size.height - m.size.height - 4.dp.toPx())
                drawText(m, topLeft = Offset(tx, ty))
            }
        }
        LaunchedEffect(ui.focus, ui.focusLocked) {
            if (ui.focus != null && !ui.focusLocked) { delay(1200); ui.focus = null }
        }
        if (ui.shutterFlash) {
            Box(Modifier.fillMaxSize().background(Color.Black))
            LaunchedEffect(Unit) {
                delay(110)
                ui.shutterFlash = false
            }
        }
    }
}

// ---------------------------------------------------------------- the camera, both ways up

@Composable
fun CameraScreen(ui: Ui, preview: View, on: Actions) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Look.Body).windowInsetsPadding(WindowInsets.displayCutout)) {
        if (maxHeight > maxWidth) Upright(ui, preview, on) else Level(ui, preview, on)
    }
}

/** Held as a camera: the thumb on the shutter and the exposure dial, the film at the other hand. */
@Composable
private fun Level(ui: Ui, preview: View, on: Actions) {
    val support: @Composable () -> Unit = {
        Column(
            Modifier.width(108.dp).fillMaxHeight().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!ui.cleanFinder) {
                FilmWindow(ui, Modifier.fillMaxWidth(), on.film)
                if (ui.lenses.size > 1) LensRing(ui, onZoom = on.zoom)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (ui.mfSupported) {
                        Key(if (ui.mfEnabled) "MF" else "AF", color = if (ui.mfEnabled) Look.Amber else Look.Dim, modifier = Modifier.defaultMinSize(30.dp, 36.dp), onClick = on.toggleMf)
                    }
                    if (ui.manualExpSupported) {
                        Key(if (ui.manualExp) "M" else "AE", color = if (ui.manualExp) Look.Amber else Look.Dim, modifier = Modifier.defaultMinSize(28.dp, 36.dp), onClick = on.toggleManualExp)
                    }
                    Key(if (ui.timerSec > 0) "${ui.timerSec}S" else "TMR", color = if (ui.timerSec > 0) Look.Amber else Look.Dim, modifier = Modifier.defaultMinSize(30.dp, 36.dp), onClick = on.toggleTimer)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Key("2X", color = if (ui.doubleExposure) Look.Amber else Look.Dim, modifier = Modifier.defaultMinSize(26.dp, 36.dp), onClick = on.toggleDoubleExp)
                    Key("½", color = if (ui.halfFrame) Look.Amber else Look.Dim, modifier = Modifier.defaultMinSize(24.dp, 36.dp), onClick = on.toggleHalfFrame)
                    val flsLabel = when (ui.flashMode) { 1 -> "FA"; 2 -> "F1"; else -> "FL" }
                    Key(flsLabel, color = if (ui.flashMode != 0) Look.Amber else Look.Dim, modifier = Modifier.defaultMinSize(24.dp, 36.dp), onClick = on.toggleFlash)
                    Key(if (ui.frontCamera) "FR" else "CM", color = if (ui.frontCamera) Look.Amber else Look.Dim, modifier = Modifier.defaultMinSize(24.dp, 36.dp), onClick = on.toggleCamera)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PrintsKey(DevelopState.running, onClick = on.prints)
                    DarkroomKey(ui.pendingRolls, onClick = on.darkroom)
                    Key("Manual", color = Look.Dim, onClick = on.settings)
                }
            } else {
                Spacer(Modifier.height(1.dp))
                Key("Manual", color = Look.Dim, onClick = on.settings)
            }
        }
    }
    val grip: @Composable () -> Unit = {
        Row(Modifier.width(196.dp).fillMaxHeight().padding(vertical = 14.dp)) {
            if (ui.leftHanded && !ui.cleanFinder && !ui.manualExp) {
                ExposureDial(ui, true, Modifier.padding(start = 10.dp).width(46.dp).fillMaxHeight(), on.exposure)
            }
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Counter(ui, on.counter)
                Shutter(96.dp, on)
                if (ui.needWind) WindLever(onWind = on.wind)
                else Spacer(Modifier.height(48.dp))
            }
            if (!ui.leftHanded && !ui.cleanFinder && !ui.manualExp) {
                ExposureDial(ui, true, Modifier.padding(end = 10.dp).width(46.dp).fillMaxHeight(), on.exposure)
            }
        }
    }
    Row(Modifier.fillMaxSize()) {
        if (ui.leftHanded) grip() else support()
        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Finder(ui, preview, on, 3f / 2, Modifier.fillMaxHeight())
            }
            if (!ui.cleanFinder && ui.manualExp) {
                ManualDial(ui, Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth().height(38.dp), on.stepManualShutter, on.stepManualIso)
            }
            if (!ui.cleanFinder && ui.mfEnabled) {
                FocusDial(ui, Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth().height(34.dp), on.setMfDistance, on.setLoupe)
            }
        }
        if (ui.leftHanded) support() else grip()
    }
}

/** Held upright, in one hand: everything touched is in the thumb's half, the top is only read. */
@Composable
private fun Upright(ui: Ui, preview: View, on: Actions) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!ui.cleanFinder) {
                Label(if (ui.loaded) ui.film else "No film", size = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (ui.loaded) Label("ISO ${ui.iso}", size = 14.sp, color = Look.Dim)
                    Key("Manual", color = Look.Dim, onClick = on.settings)
                }
            } else {
                Spacer(Modifier.width(1.dp))
                Key("Manual", color = Look.Dim, onClick = on.settings)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Finder(ui, preview, on, 2f / 3)
        }
        if (!ui.cleanFinder) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ui.lenses.size > 1) LensRing(ui, onZoom = on.zoom)
                    val flsLabel = when (ui.flashMode) { 1 -> "FA"; 2 -> "F1"; else -> "FL" }
                    Key(flsLabel, color = if (ui.flashMode != 0) Look.Amber else Look.Dim, modifier = Modifier.defaultMinSize(32.dp, 38.dp), onClick = on.toggleFlash)
                    Key(if (ui.frontCamera) "FRT" else "CAM", color = if (ui.frontCamera) Look.Amber else Look.Dim, modifier = Modifier.defaultMinSize(34.dp, 38.dp), onClick = on.toggleCamera)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ui.manualExpSupported) {
                        Key(if (ui.manualExp) "M" else "AE", color = if (ui.manualExp) Look.Amber else Look.Dim, modifier = Modifier.defaultMinSize(30.dp, 38.dp), onClick = on.toggleManualExp)
                    }
                    if (ui.mfSupported) {
                        Key(if (ui.mfEnabled) "MF" else "AF", color = if (ui.mfEnabled) Look.Amber else Look.Dim, modifier = Modifier.defaultMinSize(30.dp, 38.dp), onClick = on.toggleMf)
                    }
                    Key(if (ui.timerSec > 0) "${ui.timerSec}S" else "TMR", color = if (ui.timerSec > 0) Look.Amber else Look.Dim, modifier = Modifier.defaultMinSize(32.dp, 38.dp), onClick = on.toggleTimer)
                    Key("2X", color = if (ui.doubleExposure) Look.Amber else Look.Dim, modifier = Modifier.defaultMinSize(28.dp, 38.dp), onClick = on.toggleDoubleExp)
                    Key("½", color = if (ui.halfFrame) Look.Amber else Look.Dim, modifier = Modifier.defaultMinSize(26.dp, 38.dp), onClick = on.toggleHalfFrame)
                }
            }
            if (ui.mfEnabled) {
                FocusDial(ui, Modifier.padding(horizontal = 22.dp, vertical = 4.dp).fillMaxWidth().height(36.dp), on.setMfDistance, on.setLoupe)
            }
            if (ui.manualExp) {
                ManualDial(ui, Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(46.dp), on.stepManualShutter, on.stepManualIso)
            } else {
                ExposureDial(ui, false, Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(46.dp), on.exposure)
            }
        }
        Row(
            Modifier.fillMaxWidth().height(168.dp).padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Counter(ui, on.counter)
                if (!ui.cleanFinder) Key(if (ui.loaded) "Film" else "Load film", color = if (ui.loaded) Look.Ivory else Look.Amber, onClick = on.film)
            }
            Shutter(104.dp, on)
            Column(Modifier.width(100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (ui.needWind) WindLever(onWind = on.wind)
                if (!ui.cleanFinder) {
                    PrintsKey(DevelopState.running, onClick = on.prints)
                    DarkroomKey(ui.pendingRolls, onClick = on.darkroom)
                }
            }
        }
    }
}
