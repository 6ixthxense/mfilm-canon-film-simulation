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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.abs
import kotlin.math.roundToInt

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

/** What the screens show: the activity keeps it up to date. */
class Ui {
    var route by mutableStateOf(Route.CAMERA)
    var loaded by mutableStateOf(false)
    var film by mutableStateOf("")
    var iso by mutableIntStateOf(0)
    var shot by mutableIntStateOf(0)
    var exposures by mutableIntStateOf(0)
    var needWind by mutableStateOf(false)
    var ev by mutableIntStateOf(0)              // exposure compensation, in the camera's steps
    var evStep by mutableFloatStateOf(1f / 3)   // EV a step
    var evMin by mutableIntStateOf(-6)
    var evMax by mutableIntStateOf(6)
    var focus by mutableStateOf<Offset?>(null)  // where the last tap focused, on the finder
    var focusLocked by mutableStateOf(false)
    var zoom by mutableFloatStateOf(1f)
    var lenses by mutableStateOf(listOf(1f))
    var pendingRolls by mutableIntStateOf(0)
    var leftHanded by mutableStateOf(false)
    var realisticWind by mutableStateOf(false)
    var cleanFinder by mutableStateOf(false)
    var bwPreview by mutableStateOf(true)
    var isBwFilm by mutableStateOf(false)
    var dateStamp by mutableStateOf(false)
    var framelines by mutableStateOf(true)
    var printsRoll by mutableIntStateOf(-1)
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
    val finished = ui.loaded && ui.shot >= ui.exposures
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
            when { !ui.loaded -> "No film"; finished -> "End of roll"; else -> "of ${ui.exposures}" },
            Modifier.padding(top = 6.dp), size = 11.sp, color = if (finished) Look.Amber else Look.Dim,
        )
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

// ---------------------------------------------------------------- the finder

/** a view made by the activity, put where the layout has it (it may have been somewhere before) */
@Composable
private fun <T : View> Placed(view: T, bw: Boolean = false, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { (view.parent as? ViewGroup)?.removeView(view); view },
        modifier = modifier,
        update = { v ->
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                v.setRenderEffect(
                    if (bw) android.graphics.RenderEffect.createColorFilterEffect(
                        android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
                    ) else null
                )
            }
        },
    )
}

/**
 * LiveView in the frame the picture will have.
 * A tap focuses and meters there; a long-press locks both.
 */
@Composable
private fun Finder(ui: Ui, preview: View, on: Actions, ratio: Float, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(ratio).background(Color.Black)) {
        Placed(preview, bw = ui.bwPreview && ui.isBwFilm, modifier = Modifier.fillMaxSize())
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
                onLongPress = { at ->
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    ui.focusLocked = true
                    ui.focus = at
                    on.lockFocus(at.x, at.y)
                },
            )
        }) {
            if (!ui.cleanFinder && ui.framelines) {
                val inset = 14.dp.toPx()
                val arm = 16.dp.toPx()
                val lw = 1.dp.toPx()
                val frameColor = Look.Ivory.copy(alpha = 0.35f)
                val x0 = inset; val y0 = inset
                val x1 = size.width - inset; val y1 = size.height - inset
                // Rangefinder frame corners
                drawLine(frameColor, Offset(x0, y0), Offset(x0 + arm, y0), lw)
                drawLine(frameColor, Offset(x0, y0), Offset(x0, y0 + arm), lw)
                drawLine(frameColor, Offset(x1, y0), Offset(x1 - arm, y0), lw)
                drawLine(frameColor, Offset(x1, y0), Offset(x1, y0 + arm), lw)
                drawLine(frameColor, Offset(x0, y1), Offset(x0 + arm, y1), lw)
                drawLine(frameColor, Offset(x0, y1), Offset(x0, y1 - arm), lw)
                drawLine(frameColor, Offset(x1, y1), Offset(x1 - arm, y1), lw)
                drawLine(frameColor, Offset(x1, y1), Offset(x1, y1 - arm), lw)
                // Center rangefinder patch ticks
                val mx = size.width / 2f; val my = size.height / 2f
                val cr = 8.dp.toPx()
                drawLine(frameColor, Offset(mx - cr, my), Offset(mx + cr, my), lw)
                drawLine(frameColor, Offset(mx, my - cr), Offset(mx, my + cr), lw)
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
            Modifier.width(104.dp).fillMaxHeight().padding(horizontal = 8.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!ui.cleanFinder) {
                FilmWindow(ui, Modifier.fillMaxWidth(), on.film)
                if (ui.lenses.size > 1) LensRing(ui, onZoom = on.zoom)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Key("Prints", onClick = on.prints)
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
            if (ui.leftHanded && !ui.cleanFinder) {
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
            if (!ui.leftHanded && !ui.cleanFinder) {
                ExposureDial(ui, true, Modifier.padding(end = 10.dp).width(46.dp).fillMaxHeight(), on.exposure)
            }
        }
    }
    Row(Modifier.fillMaxSize()) {
        if (ui.leftHanded) grip() else support()
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Finder(ui, preview, on, 3f / 2, Modifier.fillMaxHeight())
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
        if (!ui.cleanFinder && ui.lenses.size > 1) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
                LensRing(ui, onZoom = on.zoom)
            }
        }
        if (!ui.cleanFinder) {
            ExposureDial(ui, false, Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(46.dp), on.exposure)
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
                    Key("Prints", onClick = on.prints)
                    DarkroomKey(ui.pendingRolls, onClick = on.darkroom)
                }
            }
        }
    }
}
