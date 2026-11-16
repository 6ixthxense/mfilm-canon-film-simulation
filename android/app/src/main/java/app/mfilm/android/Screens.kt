package app.mfilm.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

private val SHELF_WOOD = Color(0xFF1E150F)
private val SHELF_EDGE = Color(0xFF3A281B)
private val GROUPS = listOf("Colour", "Slide", "B&W", "Cine")
private val EXPOSURES = listOf(12, 24, 36)

data class FilmEntry(
    val index: Int,
    val name: String,
    val key: String,
    val note: String,
    val iso: Int,
    val group: Int,
    val grain: Int,
    val punch: Int,
    val colour: Int,
    val warmth: Int,
)

data class FrameLog(val no: Int, val whenStr: String, val fstop: String)

fun loadFilmCatalog(): List<FilmEntry> {
    val n = Native.filmCount()
    val info = IntArray(6)
    return (0 until n).map { i ->
        val parts = Native.filmInfo(i, info).split('|')
        val name = parts.getOrElse(0) { "" }
        val key = parts.getOrElse(1) { "" }
        val note = parts.getOrElse(2) { "" }
        FilmEntry(i, name, key, note, info[0], info[1], info[2], info[3], info[4], info[5])
    }
}

@Composable
private fun SamplePhoto(filmKey: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp = remember(filmKey) {
        if (filmKey.isBlank()) null
        else runCatching {
            context.assets.open("samples/$filmKey.jpg").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
    if (bmp != null) {
        Image(
            bmp.asImageBitmap(),
            contentDescription = "Sample for $filmKey",
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier.background(Look.Window), contentAlignment = Alignment.Center) {
            Label("NO SAMPLE", size = 10.sp, color = Look.Dim)
        }
    }
}

fun parseRollFrames(raw: String): List<FrameLog> {
    if (raw.isBlank()) return emptyList()
    return raw.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val p = line.split('|')
            if (p.size >= 3) FrameLog(p[0].toIntOrNull() ?: return@mapNotNull null, p[1], p[2])
            else null
        }.toList()
}

@Composable
private fun CanisterBitmap(filmIndex: Int, exposures: Int, leaderOut: Boolean, modifier: Modifier = Modifier) {
    val bmp = remember(filmIndex, exposures, leaderOut) {
        Bitmap.createBitmap(260, 220, Bitmap.Config.ARGB_8888).also {
            Native.drawCanister(filmIndex, exposures, if (leaderOut) 256 else 0, it)
        }
    }
    Image(bmp.asImageBitmap(), contentDescription = null, modifier = modifier)
}

@Composable
private fun SpecBar(label: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label, size = 10.sp, color = Look.Dim)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 1..5) {
                Box(Modifier.size(8.dp, 4.dp).background(if (i <= value) Look.Brass else Look.Plate))
            }
        }
    }
}

/**
 * Film shelf: canisters on dark wood.
 * Swipe left/right to pick a film, up/down to change shelf, tap canister to pull the leader out,
 * then pick 12 / 24 / 36 exposures and load.
 */
@Composable
fun ShelfScreen(
    ui: Ui,
    onLoad: (Int, Int) -> Unit,
    onSwitchFilm: (Int) -> Unit,
    onOpenRoll: () -> Unit,
    onBack: () -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val catalog = remember { loadFilmCatalog() }
    var group by remember { mutableIntStateOf(0) }
    val shelfFilms = remember(catalog, group) { catalog.filter { it.group == group } }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { shelfFilms.size })
    var leaderOut by remember(group, pagerState.currentPage) { mutableStateOf(false) }
    var exp by remember { mutableIntStateOf(36) }
    var fullSample by remember { mutableStateOf(false) }

    LaunchedEffect(group) {
        if (shelfFilms.isNotEmpty()) pagerState.scrollToPage(0)
    }
    LaunchedEffect(pagerState.settledPage) {
        if (shelfFilms.isNotEmpty()) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    val selected = shelfFilms.getOrNull(pagerState.currentPage)

    if (fullSample && selected != null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(16.dp)
                .pointerInput(shelfFilms.size, pagerState.currentPage) {
                    var dx = 0f
                    detectHorizontalDragGestures(onDragStart = { dx = 0f }) { change, drag ->
                        change.consume()
                        dx += drag
                        if (abs(dx) > 64.dp.toPx()) {
                            val next = (pagerState.currentPage + if (dx > 0) -1 else 1).coerceIn(0, shelfFilms.lastIndex)
                            dx = 0f
                            if (next != pagerState.currentPage) {
                                scope.launch { pagerState.animateScrollToPage(next) }
                            }
                        }
                    }
                },
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Key("← Shelf") { fullSample = false }
                Label("SAMPLE · ${selected.name}", size = 14.sp, color = Look.Amber)
                Spacer(Modifier.width(56.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                SamplePhoto(
                    selected.key,
                    Modifier.fillMaxWidth().aspectRatio(3f / 2).border(1.dp, Look.Brass),
                )
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Label("ISO ${selected.iso}  ·  ${selected.note}", size = 11.sp, color = Look.Ivory)
                Spacer(Modifier.height(8.dp))
                Label("Swipe left/right to compare films", size = 10.sp, color = Look.Dim)
            }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().background(Look.Body).windowInsetsPadding(WindowInsets.displayCutout).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // top bar: back + shelf tabs
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Key("← Back", onClick = onBack)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GROUPS.forEachIndexed { idx, name ->
                    Key(name, color = if (idx == group) Look.Amber else Look.Dim) {
                        group = idx
                        leaderOut = false
                    }
                }
            }
        }

        // shelf area: vertical swipe changes shelf, HorizontalPager glides between canisters
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    var dy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dy = 0f },
                    ) { change, drag ->
                        change.consume()
                        dy += drag
                        val step = 64.dp.toPx()
                        if (abs(dy) >= step) {
                            val next = (group + if (dy > 0) -1 else 1).coerceIn(0, GROUPS.lastIndex)
                            dy = 0f
                            if (next != group) {
                                group = next
                                leaderOut = false
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (selected == null) {
                Label("Empty shelf", color = Look.Dim)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HorizontalPager(
                        state = pagerState,
                        contentPadding = PaddingValues(horizontal = 88.dp),
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) { page ->
                        val film = shelfFilms[page]
                        val isCenter = page == pagerState.currentPage
                        val off = abs((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
                        val scale = 1f - 0.34f * off
                        val inCamera = ui.loaded && film.name == ui.film
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = 1f - 0.45f * off
                                }
                                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                                    if (isCenter) {
                                        if (ui.singleShot) {
                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            onSwitchFilm(film.index)
                                        } else {
                                            leaderOut = !leaderOut
                                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                        }
                                    } else {
                                        scope.launch { pagerState.animateScrollToPage(page) }
                                    }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (inCamera) {
                                Label("IN CAMERA", size = 10.sp, color = Look.Amber)
                            }
                            CanisterBitmap(
                                film.index,
                                exp,
                                isCenter && leaderOut,
                                Modifier.size(196.dp, 160.dp),
                            )
                        }
                    }

                    // dark wood plank
                    Box(Modifier.fillMaxWidth().height(14.dp).background(SHELF_WOOD).border(1.dp, SHELF_EDGE))
                    Spacer(Modifier.height(14.dp))

                    // film title + note + live tone sample + specs
                    Label(selected.name, size = 18.sp, color = Look.Ivory)
                    Spacer(Modifier.height(2.dp))
                    Label("ISO ${selected.iso}  ·  ${pagerState.currentPage + 1} OF ${shelfFilms.size}", size = 11.sp, color = Look.Dim)
                    if (selected.note.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Label(selected.note, size = 11.sp, color = Look.Dim)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .width(156.dp)
                                .aspectRatio(3f / 2)
                                .border(1.dp, Look.Brass, RoundedCornerShape(2.dp))
                                .clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    fullSample = true
                                }
                        ) {
                            SamplePhoto(selected.key, Modifier.fillMaxSize())
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SpecBar("GRN", selected.grain)
                            SpecBar("PCH", selected.punch)
                            SpecBar("CLR", selected.colour)
                            SpecBar("WRM", selected.warmth)
                            Label("TAP PHOTO TO ZOOM", size = 9.sp, color = Look.Dim)
                        }
                    }
                }
            }
        }

        // bottom action bar: in SINGLE mode switch immediately; in ROLL mode pull leader and pick 12/24/36
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (ui.singleShot && selected != null) {
                Box(
                    Modifier
                        .defaultMinSize(136.dp, 48.dp)
                        .background(Look.Plate, RoundedCornerShape(3.dp))
                        .border(1.dp, Look.Amber, RoundedCornerShape(3.dp))
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onSwitchFilm(selected.index)
                        }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Label(
                        if (ui.loaded && selected.name == ui.film) "Active film" else "Use ${selected.name}",
                        size = 13.sp,
                        color = Look.Amber,
                    )
                }
            } else if (ui.loaded) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Look.Brass, RoundedCornerShape(3.dp))
                        .background(Look.Window, RoundedCornerShape(3.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label("${ui.film} (${ui.shot}/${ui.exposures}) loaded", size = 12.sp, color = Look.Dim)
                    Key("Roll →", color = Look.Amber, onClick = onOpenRoll)
                }
            } else if (selected != null) {
                if (!leaderOut) {
                    Label("Tap canister to pull leader", size = 12.sp, color = Look.Dim, modifier = Modifier.padding(bottom = 12.dp))
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier
                                .background(Look.Window, RoundedCornerShape(3.dp))
                                .border(1.dp, Look.Brass, RoundedCornerShape(3.dp))
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EXPOSURES.forEachIndexed { idx, n ->
                                if (idx > 0) Label("·", size = 12.sp, color = Look.Dim)
                                Key("$n EXP", color = if (exp == n) Look.Amber else Look.Ivory) { exp = n }
                            }
                        }
                        Box(
                            Modifier
                                .defaultMinSize(108.dp, 48.dp)
                                .background(Look.Plate, RoundedCornerShape(3.dp))
                                .border(1.dp, Look.Amber, RoundedCornerShape(3.dp))
                                .clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    onLoad(selected.index, exp)
                                }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Label("Load film", size = 13.sp, color = Look.Amber)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Roll screen: large frame counter, text-only log of shot frames (time + f-stop, no thumbnails),
 * and a rewind button.
 */
@Composable
fun RollScreen(ui: Ui, onRewind: () -> Unit, onShelf: () -> Unit, onBack: () -> Unit) {
    val view = LocalView.current
    var frames by remember { mutableStateOf<List<FrameLog>>(emptyList()) }
    var confirmRewind by remember { mutableStateOf(false) }

    LaunchedEffect(ui.loaded, ui.shot) {
        frames = if (ui.loaded) parseRollFrames(Native.rollFrames()) else emptyList()
        confirmRewind = false
    }

    val finished = ui.loaded && ui.shot >= ui.exposures

    Column(
        Modifier.fillMaxSize().background(Look.Body).windowInsetsPadding(WindowInsets.displayCutout).padding(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Key("← Back", onClick = onBack)
            Label(if (ui.loaded) "${ui.film} · ISO ${ui.iso}" else "No roll loaded", size = 14.sp, color = Look.Ivory)
            Spacer(Modifier.width(56.dp))
        }

        Spacer(Modifier.height(16.dp))

        // large frame counter readout
        Row(
            Modifier
                .fillMaxWidth()
                .background(Look.Window, RoundedCornerShape(4.dp))
                .border(1.dp, Look.Brass, RoundedCornerShape(4.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Label(if (ui.loaded) "FRAME" else "EMPTY", size = 11.sp, color = Look.Dim)
                Spacer(Modifier.height(4.dp))
                Label(
                    when {
                        !ui.loaded -> "-- / --"
                        finished -> "END · ${ui.shot} OF ${ui.exposures}"
                        else -> "%02d OF %02d".format(ui.shot, ui.exposures)
                    },
                    size = 24.sp,
                    color = if (finished) Look.Amber else Look.Red,
                )
            }
            if (ui.loaded) {
                val label = when {
                    finished -> "Rewind roll"
                    confirmRewind -> "Tap again to rewind"
                    else -> "Rewind"
                }
                Box(
                    Modifier
                        .defaultMinSize(96.dp, 48.dp)
                        .background(Look.Plate, RoundedCornerShape(3.dp))
                        .border(1.dp, if (finished || confirmRewind) Look.Amber else Look.Brass, RoundedCornerShape(3.dp))
                        .clickable {
                            if (finished || confirmRewind) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                onRewind()
                            } else {
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                confirmRewind = true
                            }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Label(label, size = 12.sp, color = if (finished || confirmRewind) Look.Amber else Look.Ivory)
                }
            } else {
                Key("Open shelf", color = Look.Amber, onClick = onShelf)
            }
        }

        Spacer(Modifier.height(16.dp))
        Label("EXPOSURE LOG", size = 11.sp, color = Look.Dim)
        Spacer(Modifier.height(8.dp))

        if (frames.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Label(if (ui.loaded) "No exposures yet" else "Load a roll from the shelf", size = 13.sp, color = Look.Dim)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(frames, key = { it.no }) { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .border(0.5.dp, Look.Plate)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Label("#%02d".format(f.no), size = 14.sp, color = Look.Amber)
                        Label(f.whenStr.takeLast(5), size = 13.sp, color = Look.Ivory)
                        Label(if (f.fstop.isNotBlank()) f.fstop else "—", size = 13.sp, color = Look.Dim)
                    }
                }
            }
        }
    }
}
