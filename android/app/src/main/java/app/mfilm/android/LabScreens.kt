package app.mfilm.android

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.widget.EditText
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import java.io.FileOutputStream
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

private val SAFELIGHT = Color(0xFFD92B1C)
private val SAFELIGHT_DIM = Color(0xFF5C150E)
private val PRINT_SIZES = listOf("S" to 0, "M" to 1, "L" to 2)
private val PUSH_STOPS = listOf("-1" to -1, "0" to 0, "+1" to 1)
private val RECIPE_STEPS = listOf(0, 50, 100, 150, 200)

data class LabRoll(
    val number: Int,
    val filmIdx: Int,
    val filmName: String,
    val exposures: Int,
    val shot: Int,
    val developed: Int,
    val sheet: Boolean,
    val jpegs: Int,
    val inCamera: Boolean,
    val loaded: String,
    val last: String,
) {
    val isDeveloped: Boolean get() = sheet && developed >= shot && jpegs >= developed && shot > 0
    val isWaiting: Boolean get() = !isDeveloped && !inCamera && shot > 0
}

data class PrintItem(
    val no: Int,
    val whenStr: String,
    val fstop: String,
    val jpegPath: String,
)

fun parseLabRolls(raw: String): List<LabRoll> {
    if (raw.isBlank()) return emptyList()
    return raw.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
        val p = line.split('|')
        if (p.size < 11) return@mapNotNull null
        LabRoll(
            number = p[0].toIntOrNull() ?: return@mapNotNull null,
            filmIdx = p[1].toIntOrNull() ?: -1,
            filmName = p[2],
            exposures = p[3].toIntOrNull() ?: 0,
            shot = p[4].toIntOrNull() ?: 0,
            developed = p[5].toIntOrNull() ?: 0,
            sheet = (p[6].toIntOrNull() ?: 0) != 0,
            jpegs = p[7].toIntOrNull() ?: 0,
            inCamera = (p[8].toIntOrNull() ?: 0) != 0,
            loaded = p[9],
            last = p[10],
        )
    }.toList()
}

fun parseRollPrints(raw: String): List<PrintItem> {
    if (raw.isBlank()) return emptyList()
    return raw.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
        val p = line.split('|')
        if (p.size < 4) return@mapNotNull null
        PrintItem(p[0].toIntOrNull() ?: return@mapNotNull null, p[1], p[2], p[3])
    }.toList()
}

private fun formatMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 100) "%.0f MB".format(mb) else "%.1f MB".format(mb)
}

/**
 * Darkroom: pure black with safelight-red accents.
 * Lists rolls as film envelopes, supports gallery import, roll labels, re-developing,
 * S / M / L print size, push/pull, and live paper-develop preview while DevelopService runs.
 */
@Composable
fun DarkroomScreen(
    pendingImportCount: Int,
    importing: Boolean,
    onRequestImport: () -> Unit,
    onConfirmImport: (Int, Int, Int, Int, Int) -> Unit,
    onCancelImport: () -> Unit,
    onDevelop: (Int, Int, Int, Int, Int) -> Unit,
    onCancel: () -> Unit,
    onOpenPrints: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val card = remember { Card(context) }
    var rolls by remember { mutableStateOf<List<LabRoll>>(emptyList()) }
    var memos by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var rawBytes by remember { mutableStateOf(0L) }
    var selectedRoll by remember { mutableIntStateOf(-1) }
    var printSize by remember { mutableIntStateOf(1) }
    var pushStops by remember { mutableIntStateOf(0) }
    var grainPct by remember { mutableIntStateOf(100) }
    var halPct by remember { mutableIntStateOf(100) }
    var deleteAsk by remember { mutableStateOf(false) }
    var redevMode by remember { mutableStateOf(false) }
    var pickSwapFilm by remember { mutableStateOf(false) }
    var swapFilmIdx by remember { mutableIntStateOf(-1) }
    val catalog = remember { loadFilmCatalog() }
    var importFilmIdx by remember { mutableIntStateOf(0) }

    fun nextRecipe(cur: Int): Int {
        val idx = RECIPE_STEPS.indexOf(cur)
        return RECIPE_STEPS[(idx + 1) % RECIPE_STEPS.size]
    }

    fun refreshLab() {
        rolls = parseLabRolls(Native.labRolls())
        memos = card.loadMemos()
        rawBytes = card.rawStorageBytes()
        if (selectedRoll < 0 || rolls.none { it.number == selectedRoll }) {
            selectedRoll = rolls.firstOrNull { it.isWaiting }?.number ?: rolls.firstOrNull()?.number ?: -1
        }
    }

    fun editRollMemo(rollNo: Int) {
        val input = EditText(context).apply {
            setText(memos[rollNo] ?: "")
            hint = "e.g. KYOTO 2026"
            setSingleLine(true)
        }
        AlertDialog.Builder(context)
            .setTitle("Roll %03d Label".format(rollNo))
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                card.setMemo(rollNo, input.text.toString())
                memos = card.loadMemos()
            }
            .setNeutralButton("Clear") { _, _ ->
                card.setMemo(rollNo, "")
                memos = card.loadMemos()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    LaunchedEffect(DevelopState.running, importing) {
        refreshLab()
        deleteAsk = false
        redevMode = false
        pickSwapFilm = false
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout).padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Key("← Back", color = SAFELIGHT, onClick = onBack)
            Label("DARKROOM · ${formatMb(rawBytes)}", size = 14.sp, color = SAFELIGHT)
            if (!DevelopState.running && !importing && pendingImportCount == 0) {
                Key("Import", color = Look.Amber, onClick = onRequestImport)
            } else {
                Spacer(Modifier.width(56.dp))
            }
        }

        Spacer(Modifier.height(12.dp))

        if (importing) {
            Box(
                Modifier.weight(1f).fillMaxWidth().border(1.dp, SAFELIGHT_DIM, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Label("PREPARING IMPORTED ROLL…", size = 13.sp, color = SAFELIGHT)
            }
        } else if (pendingImportCount > 0) {
            // Inline film picker for imported gallery photos
            Label("IMPORT $pendingImportCount PHOTO${if (pendingImportCount == 1) "" else "S"}  ·  PICK FILM", size = 12.sp, color = Look.Amber)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(catalog, key = { it.index }) { f ->
                    val picked = f.index == importFilmIdx
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (picked) Color(0xFF1A0705) else Color(0xFF0A0303), RoundedCornerShape(3.dp))
                            .border(1.dp, if (picked) Look.Amber else SAFELIGHT_DIM, RoundedCornerShape(3.dp))
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                importFilmIdx = f.index
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Label(f.name, size = 13.sp, color = if (picked) Look.Amber else SAFELIGHT)
                            if (f.note.isNotBlank()) Label(f.note, size = 10.sp, color = SAFELIGHT_DIM)
                        }
                        Label("ISO ${f.iso}", size = 11.sp, color = SAFELIGHT_DIM)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Key("GRN $grainPct%", modifier = Modifier.defaultMinSize(72.dp, 36.dp), color = if (grainPct != 100) Look.Amber else SAFELIGHT_DIM) {
                    grainPct = nextRecipe(grainPct)
                }
                Key("HAL $halPct%", modifier = Modifier.defaultMinSize(72.dp, 36.dp), color = if (halPct != 100) Look.Amber else SAFELIGHT_DIM) {
                    halPct = nextRecipe(halPct)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.border(1.dp, SAFELIGHT_DIM, RoundedCornerShape(3.dp)).padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PRINT_SIZES.forEachIndexed { idx, (label, sz) ->
                            if (idx > 0) Label("·", size = 11.sp, color = SAFELIGHT_DIM)
                            Key(label, modifier = Modifier.defaultMinSize(30.dp, 40.dp), color = if (printSize == sz) SAFELIGHT else SAFELIGHT_DIM) { printSize = sz }
                        }
                    }
                    Row(
                        Modifier.border(1.dp, SAFELIGHT_DIM, RoundedCornerShape(3.dp)).padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PUSH_STOPS.forEachIndexed { idx, (label, ev) ->
                            if (idx > 0) Label("·", size = 11.sp, color = SAFELIGHT_DIM)
                            Key(label, modifier = Modifier.defaultMinSize(30.dp, 40.dp), color = if (pushStops == ev) SAFELIGHT else SAFELIGHT_DIM) { pushStops = ev }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Key("Cancel", modifier = Modifier.defaultMinSize(44.dp, 42.dp), color = SAFELIGHT_DIM, onClick = onCancelImport)
                    Box(
                        Modifier
                            .defaultMinSize(84.dp, 44.dp)
                            .border(1.dp, Look.Amber, RoundedCornerShape(3.dp))
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                onConfirmImport(importFilmIdx, printSize, pushStops, grainPct, halPct)
                            }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Label("Develop", size = 13.sp, color = Look.Amber)
                    }
                }
            }
        } else if (pickSwapFilm) {
            // Inline film-swap picker for Re-develop with different film stock
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Label("SWAP FILM STOCK FOR RE-DEV", size = 12.sp, color = Look.Amber)
                Key("Done", color = SAFELIGHT) { pickSwapFilm = false }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(catalog, key = { it.index }) { f ->
                    val picked = f.index == swapFilmIdx
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (picked) Color(0xFF1A0705) else Color(0xFF0A0303), RoundedCornerShape(3.dp))
                            .border(1.dp, if (picked) Look.Amber else SAFELIGHT_DIM, RoundedCornerShape(3.dp))
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                swapFilmIdx = f.index
                                pickSwapFilm = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Label(f.name, size = 13.sp, color = if (picked) Look.Amber else SAFELIGHT)
                            if (f.note.isNotBlank()) Label(f.note, size = 10.sp, color = SAFELIGHT_DIM)
                        }
                        Label("ISO ${f.iso}", size = 11.sp, color = SAFELIGHT_DIM)
                    }
                }
            }
        } else if (DevelopState.running) {
            // live developing tray
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, SAFELIGHT_DIM, RoundedCornerShape(4.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                val q = DevelopState.queuedCount
                val heading = if (q > 0) "DEVELOPING ROLL %03d  ·  +%d QUEUED".format(DevelopState.rollNo, q)
                else "DEVELOPING ROLL %03d".format(DevelopState.rollNo)
                Label(heading, size = 14.sp, color = SAFELIGHT)

                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = DevelopState.preview
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(),
                            contentDescription = "Developing preview",
                            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 2).border(1.dp, SAFELIGHT_DIM),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Label("MIXING CHEMICALS…", size = 12.sp, color = SAFELIGHT_DIM)
                    }
                }

                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val frameText = if (DevelopState.currentFrame > 0) {
                        "FRAME %02d  ·  %d OF %d  ·  %d%%".format(
                            DevelopState.currentFrame,
                            DevelopState.doneFrames,
                            DevelopState.totalFrames.coerceAtLeast(1),
                            DevelopState.progress / 10,
                        )
                    } else "PREPARING…"
                    Label(frameText, size = 12.sp, color = SAFELIGHT)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).background(SAFELIGHT_DIM)) {
                        val frac = (DevelopState.progress / 1000f).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth(frac).height(6.dp).background(SAFELIGHT))
                    }
                    Spacer(Modifier.height(14.dp))
                    Key("Cancel", color = SAFELIGHT, onClick = onCancel)
                }
            }
        } else {
            if (DevelopState.error != null) {
                Label(DevelopState.error!!, size = 12.sp, color = Look.Amber, modifier = Modifier.padding(bottom = 8.dp))
            } else if (DevelopState.status != null) {
                Label(DevelopState.status!!, size = 12.sp, color = SAFELIGHT, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (rolls.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Label("No rolls on card · tap Import to develop gallery photos", size = 12.sp, color = SAFELIGHT_DIM)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rolls, key = { it.number }) { r ->
                        val active = r.number == selectedRoll
                        val memo = memos[r.number]
                        val status = when {
                            r.inCamera -> "IN CAMERA"
                            r.isDeveloped -> "DEVELOPED"
                            r.isWaiting -> "WAITING"
                            else -> "EMPTY"
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (active) Color(0xFF140504) else Color(0xFF0A0303), RoundedCornerShape(3.dp))
                                .border(1.dp, if (active) SAFELIGHT else SAFELIGHT_DIM, RoundedCornerShape(3.dp))
                                .clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    selectedRoll = r.number
                                    deleteAsk = false
                                    redevMode = false
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Label("ROLL %03d · %s".format(r.number, r.filmName), size = 14.sp, color = SAFELIGHT)
                                if (!memo.isNullOrBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Label(memo, size = 11.sp, color = Look.Amber)
                                }
                                Spacer(Modifier.height(3.dp))
                                Label("${r.shot}/${r.exposures} EXP  ·  ${r.loaded.take(10)}", size = 11.sp, color = SAFELIGHT_DIM)
                            }
                            Label(status, size = 11.sp, color = if (r.isWaiting) Look.Amber else SAFELIGHT_DIM)
                        }
                    }
                }

                val sel = rolls.find { it.number == selectedRoll }
                if (sel != null) {
                    val hasRaws = remember(sel.number, rolls) { Native.labHasRaws(sel.number) }
                    Spacer(Modifier.height(10.dp))
                    if (deleteAsk && !sel.inCamera) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .border(1.dp, SAFELIGHT, RoundedCornerShape(3.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (sel.isDeveloped || sel.jpegs > 0) {
                                Key("Del prints", color = SAFELIGHT) {
                                    Native.labDelete(sel.number, false)
                                    deleteAsk = false
                                    refreshLab()
                                }
                            }
                            if (hasRaws && (sel.isDeveloped || sel.jpegs > 0)) {
                                Key("Del RAW", color = SAFELIGHT) {
                                    card.cleanRawsForRoll(sel.number)
                                    deleteAsk = false
                                    refreshLab()
                                }
                            }
                            Key("Del all", color = Look.Amber) {
                                Native.labDelete(sel.number, true)
                                deleteAsk = false
                                refreshLab()
                            }
                            Key("Cancel", color = SAFELIGHT_DIM) { deleteAsk = false }
                        }
                    } else if (sel.isWaiting || redevMode) {
                        val chosenFilm = catalog.find { it.index == swapFilmIdx }
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Grain & Halation custom recipe row + Swap Film button in Re-dev mode
                            Row(
                                Modifier.fillMaxWidth().border(0.5.dp, SAFELIGHT_DIM, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Key("GRN $grainPct%", modifier = Modifier.defaultMinSize(66.dp, 36.dp), color = if (grainPct != 100) Look.Amber else SAFELIGHT_DIM) {
                                    grainPct = nextRecipe(grainPct)
                                }
                                Key("HAL $halPct%", modifier = Modifier.defaultMinSize(66.dp, 36.dp), color = if (halPct != 100) Look.Amber else SAFELIGHT_DIM) {
                                    halPct = nextRecipe(halPct)
                                }
                                if (redevMode) {
                                    val fname = chosenFilm?.name ?: sel.filmName
                                    Key("FILM: $fname ▾", modifier = Modifier.defaultMinSize(84.dp, 36.dp), color = Look.Amber) {
                                        if (swapFilmIdx < 0) swapFilmIdx = sel.filmIdx.coerceAtLeast(0)
                                        pickSwapFilm = true
                                    }
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Row(
                                        Modifier.border(1.dp, SAFELIGHT_DIM, RoundedCornerShape(3.dp)).padding(horizontal = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        PRINT_SIZES.forEachIndexed { idx, (label, sz) ->
                                            if (idx > 0) Label("·", size = 11.sp, color = SAFELIGHT_DIM)
                                            Key(label, modifier = Modifier.defaultMinSize(28.dp, 40.dp), color = if (printSize == sz) SAFELIGHT else SAFELIGHT_DIM) { printSize = sz }
                                        }
                                    }
                                    Row(
                                        Modifier.border(1.dp, SAFELIGHT_DIM, RoundedCornerShape(3.dp)).padding(horizontal = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        PUSH_STOPS.forEachIndexed { idx, (label, ev) ->
                                            if (idx > 0) Label("·", size = 11.sp, color = SAFELIGHT_DIM)
                                            Key(label, modifier = Modifier.defaultMinSize(28.dp, 40.dp), color = if (pushStops == ev) SAFELIGHT else SAFELIGHT_DIM) { pushStops = ev }
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (redevMode) {
                                        Key("Back", modifier = Modifier.defaultMinSize(38.dp, 40.dp), color = SAFELIGHT_DIM) {
                                            redevMode = false
                                            swapFilmIdx = -1
                                        }
                                    } else {
                                        Key("Lbl", modifier = Modifier.defaultMinSize(34.dp, 40.dp), color = SAFELIGHT_DIM) { editRollMemo(sel.number) }
                                        if (!sel.inCamera) Key("Del", modifier = Modifier.defaultMinSize(34.dp, 40.dp), color = SAFELIGHT_DIM) { deleteAsk = true }
                                    }
                                    Box(
                                        Modifier
                                            .defaultMinSize(78.dp, 44.dp)
                                            .border(1.dp, SAFELIGHT, RoundedCornerShape(3.dp))
                                            .clickable {
                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                if (redevMode) {
                                                    Native.labDelete(sel.number, false)
                                                    if (swapFilmIdx >= 0 && swapFilmIdx != sel.filmIdx) {
                                                        Native.labSetFilm(sel.number, swapFilmIdx)
                                                    }
                                                    redevMode = false
                                                    swapFilmIdx = -1
                                                }
                                                onDevelop(sel.number, printSize, pushStops, grainPct, halPct)
                                            }
                                            .padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Label(if (redevMode) "Re-dev" else "Develop", size = 12.sp, color = SAFELIGHT)
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Key("Label", modifier = Modifier.defaultMinSize(44.dp, 42.dp), color = SAFELIGHT_DIM) { editRollMemo(sel.number) }
                                if (!sel.inCamera) Key("Del", modifier = Modifier.defaultMinSize(38.dp, 42.dp), color = SAFELIGHT_DIM) { deleteAsk = true }
                                if (!sel.inCamera && hasRaws && (sel.isDeveloped || sel.jpegs > 0)) {
                                    Key("Re-dev", modifier = Modifier.defaultMinSize(54.dp, 42.dp), color = Look.Amber) {
                                        swapFilmIdx = sel.filmIdx.coerceAtLeast(0)
                                        redevMode = true
                                    }
                                }
                            }
                            if (sel.jpegs > 0 || sel.isDeveloped) {
                                Key("Prints →", color = SAFELIGHT) { onOpenPrints(sel.number) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Prints: contact sheet on a dark light table.
 * Tap a frame for full-screen lightbox with pinch-to-zoom, swipe navigation, and share.
 */
@Composable
fun PrintsScreen(initialRoll: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val cfg = LocalConfiguration.current
    val cols = if (cfg.screenWidthDp > cfg.screenHeightDp) 6 else 3

    var rolls by remember { mutableStateOf<List<LabRoll>>(emptyList()) }
    var rollNo by remember { mutableIntStateOf(initialRoll) }
    var prints by remember { mutableStateOf<List<PrintItem>>(emptyList()) }
    var lightboxIdx by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        rolls = parseLabRolls(Native.labRolls()).filter { it.jpegs > 0 || it.isDeveloped }
        if (rollNo <= 0) rollNo = rolls.firstOrNull()?.number ?: -1
    }

    LaunchedEffect(rollNo) {
        prints = if (rollNo > 0) parseRollPrints(Native.labRollPrints(rollNo)).filter { it.jpegPath.isNotBlank() } else emptyList()
    }

    val scope = rememberCoroutineScope()
    val memos = remember { Card(context).loadMemos() }
    val currentRoll = rolls.find { it.number == rollNo }
    val currentMemo = memos[rollNo]

    if (lightboxIdx in prints.indices) {
        Lightbox(
            roll = currentRoll,
            prints = prints,
            index = lightboxIdx,
            onIndex = { lightboxIdx = it },
            onShare = { item, withBorder ->
                scope.launch {
                    val path = if (!withBorder) item.jpegPath else withContext(Dispatchers.IO) {
                        val src = BitmapFactory.decodeFile(item.jpegPath) ?: return@withContext item.jpegPath
                        val framed = Card.renderFilmRebate(src, currentRoll?.filmName ?: "M-FILM", item.no)
                        src.recycle()
                        val outFile = File(context.cacheDir, "rebate_${rollNo}_${item.no}.jpg")
                        FileOutputStream(outFile).use { framed.compress(Bitmap.CompressFormat.JPEG, 93, it) }
                        framed.recycle()
                        outFile.absolutePath
                    }
                    shareFile(context, path, "image/jpeg", "Share print")
                }
            },
            onClose = { lightboxIdx = -1 },
        )
        return
    }

    Column(
        Modifier.fillMaxSize().background(Look.Body).windowInsetsPadding(WindowInsets.displayCutout).padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Key("← Back", onClick = onBack)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Label(if (rollNo > 0) "PRINTS · ROLL %03d".format(rollNo) else "PRINTS", size = 14.sp, color = Look.Ivory)
                if (!currentMemo.isNullOrBlank()) {
                    Label(currentMemo, size = 10.sp, color = Look.Amber)
                }
            }
            if (prints.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Key("Sheet", modifier = Modifier.defaultMinSize(46.dp, 44.dp), color = Look.Amber) {
                        scope.launch {
                            val sheet = withContext(Dispatchers.IO) { renderContactSheetFile(context, currentRoll, currentMemo, prints) }
                            if (sheet != null) shareFile(context, sheet.absolutePath, "image/jpeg", "Share contact sheet")
                        }
                    }
                    Key("ZIP", modifier = Modifier.defaultMinSize(38.dp, 44.dp), color = Look.Ivory) {
                        scope.launch {
                            val zip = withContext(Dispatchers.IO) { buildRollZip(context, currentRoll, currentMemo, prints) }
                            if (zip != null) shareFile(context, zip.absolutePath, "application/zip", "Share roll archive")
                        }
                    }
                }
            } else {
                Spacer(Modifier.width(56.dp))
            }
        }

        if (rolls.size > 1) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rolls.take(4).forEach { r ->
                    Key("R%03d".format(r.number), color = if (r.number == rollNo) Look.Amber else Look.Dim) {
                        rollNo = r.number
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (prints.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Label("No developed prints yet", size = 13.sp, color = Look.Dim)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                modifier = Modifier.weight(1f).fillMaxWidth().background(Look.Window).border(1.dp, Look.Brass).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(prints, key = { _, p -> "${p.no}:${p.jpegPath}" }) { idx, item ->
                    val cacheKey = "thumb:${item.jpegPath}:${File(item.jpegPath).lastModified()}"
                    val thumb by produceState<Bitmap?>(initialValue = BitmapCache.get(cacheKey), key1 = cacheKey) {
                        val cached = BitmapCache.get(cacheKey)
                        if (cached != null) {
                            value = cached
                            return@produceState
                        }
                        value = withContext(Dispatchers.IO) {
                            decodeThumb(item.jpegPath, 360)?.also { BitmapCache.put(cacheKey, it) }
                        }
                    }
                    Column(
                        Modifier.clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            lightboxIdx = idx
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (thumb != null) {
                            Image(
                                thumb!!.asImageBitmap(),
                                contentDescription = "Frame ${item.no}",
                                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 2).border(0.5.dp, Look.Plate),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(Modifier.fillMaxWidth().aspectRatio(3f / 2).background(Look.Plate))
                        }
                        Spacer(Modifier.height(3.dp))
                        Label("#%02d".format(item.no), size = 10.sp, color = Look.Amber)
                    }
                }
            }
        }
    }
}

@Composable
private fun Lightbox(
    roll: LabRoll?,
    prints: List<PrintItem>,
    index: Int,
    onIndex: (Int) -> Unit,
    onShare: (PrintItem, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val item = prints[index]
    var withBorder by remember { mutableStateOf(false) }
    val dims by produceState(initialValue = "", key1 = item.jpegPath) {
        value = withContext(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(item.jpegPath, o)
            if (o.outWidth > 0 && o.outHeight > 0) "${o.outWidth}×${o.outHeight}" else ""
        }
    }
    val fullKey = "full:${item.jpegPath}:${File(item.jpegPath).lastModified()}"
    val rawBmp by produceState<Bitmap?>(initialValue = BitmapCache.get(fullKey), key1 = fullKey) {
        val cached = BitmapCache.get(fullKey)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            decodeThumb(item.jpegPath, 1920)?.also { BitmapCache.put(fullKey, it) }
        }
    }
    val bmp by produceState<Bitmap?>(initialValue = rawBmp, rawBmp, withBorder) {
        val base = rawBmp
        if (base == null || !withBorder) {
            value = base
        } else {
            value = withContext(Dispatchers.IO) {
                Card.renderFilmRebate(base, roll?.filmName ?: "M-FILM", item.no)
            }
        }
    }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var pan by remember(index) { mutableStateOf(Offset.Zero) }
    var showInfo by remember { mutableStateOf(true) }

    Column(
        Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout).padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Key("← Sheet", onClick = onClose)
            Label("#%02d  ·  %s  ·  %s".format(item.no, item.whenStr.takeLast(5), item.fstop.ifBlank { "—" }), size = 12.sp, color = Look.Ivory)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                Key("35mm", modifier = Modifier.defaultMinSize(44.dp, 44.dp), color = if (withBorder) Look.Amber else Look.Dim) {
                    withBorder = !withBorder
                }
                Key("Share", modifier = Modifier.defaultMinSize(48.dp, 44.dp), color = Look.Amber) { onShare(item, withBorder) }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(index) {
                    detectTapGestures(onTap = { showInfo = !showInfo })
                }
                .pointerInput(index, prints.size) {
                    detectTransformGestures { _, drag, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = nextScale
                        pan = if (nextScale > 1.05f) pan + drag else Offset.Zero
                    }
                }
                .pointerInput(index, prints.size) {
                    var dx = 0f
                    detectHorizontalDragGestures(onDragStart = { dx = 0f }) { change, drag ->
                        if (scale > 1.05f) return@detectHorizontalDragGestures
                        change.consume()
                        dx += drag
                        if (abs(dx) > 72.dp.toPx()) {
                            val next = (index + if (dx > 0) -1 else 1).coerceIn(0, prints.lastIndex)
                            dx = 0f
                            if (next != index) onIndex(next)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(
                    bmp!!.asImageBitmap(),
                    contentDescription = "Frame ${item.no}",
                    modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = pan.x,
                        translationY = pan.y,
                    ),
                    contentScale = ContentScale.Fit,
                )
            }
            if (showInfo) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xCC0A0A09))
                        .border(0.5.dp, Look.Plate)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label(roll?.filmName ?: "M-FILM", size = 11.sp, color = Look.Amber)
                    Label("${item.whenStr}  ·  ${item.fstop.ifBlank { "—" }}  ·  $dims", size = 11.sp, color = Look.Ivory)
                }
            }
        }
    }
}

private fun renderContactSheetFile(context: Context, roll: LabRoll?, memo: String?, prints: List<PrintItem>): File? {
    if (prints.isEmpty()) return null
    val cols = 6
    val rows = (prints.size + cols - 1) / cols
    val pad = 44
    val gap = 24
    val cellW = 360
    val cellH = 240
    val headerH = 96
    val stripH = cellH + 56
    val w = pad * 2 + cols * cellW + (cols - 1) * gap
    val h = headerH + pad + rows * stripH

    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    c.drawColor(0xFF0A0A09.toInt())

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFECA834.toInt()
        textSize = 34f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD8CFC0.toInt()
        textSize = 22f
        typeface = Typeface.MONOSPACE
    }
    val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFECA834.toInt()
        textSize = 20f
        typeface = Typeface.MONOSPACE
    }
    val holePaint = Paint().apply { color = 0xFF1A1816.toInt() }

    val baseTitle = "ROLL %03d · %s".format(roll?.number ?: 0, (roll?.filmName ?: "M-FILM").uppercase())
    val title = if (!memo.isNullOrBlank()) "$baseTitle — ${memo.uppercase()}" else baseTitle
    c.drawText(title, pad.toFloat(), 58f, titlePaint)
    val sub = "${prints.size} FRAMES · ${roll?.loaded?.take(10) ?: ""}"
    c.drawText(sub, w - pad - subPaint.measureText(sub), 58f, subPaint)

    prints.forEachIndexed { idx, item ->
        val col = idx % cols
        val row = idx / cols
        val x = pad + col * (cellW + gap)
        val y = headerH + row * stripH
        if (col == 0) {
            // sprocket holes along strip top
            var sx = pad
            while (sx < w - pad) {
                c.drawRect(sx.toFloat(), (y - 16).toFloat(), (sx + 14).toFloat(), (y - 6).toFloat(), holePaint)
                sx += 32
            }
        }
        val thumb = decodeThumb(item.jpegPath, cellW)
        if (thumb != null) {
            c.drawBitmap(thumb, null, Rect(x, y, x + cellW, y + cellH), null)
            thumb.recycle()
        }
        c.drawText("#%02d  %s".format(item.no, item.fstop), x.toFloat(), (y + cellH + 26).toFloat(), numPaint)
    }

    val outFile = File(context.cacheDir, "contact_sheet_${roll?.number ?: 0}.jpg")
    FileOutputStream(outFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    bmp.recycle()
    return outFile
}

private fun buildRollZip(context: Context, roll: LabRoll?, memo: String?, prints: List<PrintItem>): File? {
    if (prints.isEmpty()) return null
    val rollNo = roll?.number ?: 0
    val safeFilm = (roll?.filmName ?: "MFilm").replace(Regex("[^A-Za-z0-9_-]"), "_")
    val zipFile = File(context.cacheDir, "MFilm_Roll_%03d_%s.zip".format(rollNo, safeFilm))
    val sheet = renderContactSheetFile(context, roll, memo, prints)
    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
        if (sheet != null && sheet.exists()) {
            zos.putNextEntry(ZipEntry("CONTACT_SHEET.JPG"))
            sheet.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
        if (rollNo > 0) {
            val logFile = Card(context).rollLogFile(rollNo)
            if (logFile.exists()) {
                zos.putNextEntry(ZipEntry("ROLL_%03d.TXT".format(rollNo)))
                logFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        if (!memo.isNullOrBlank()) {
            zos.putNextEntry(ZipEntry("MEMO.TXT"))
            zos.write("$memo\n".toByteArray())
            zos.closeEntry()
        }
        for (item in prints) {
            val f = File(item.jpegPath)
            if (!f.exists()) continue
            zos.putNextEntry(ZipEntry("FRAME_%02d_%s".format(item.no, f.name)))
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
    return zipFile
}

private fun decodeThumb(path: String, maxDim: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    var sample = 1
    while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun shareFile(context: Context, path: String, mime: String, title: String) {
    val file = File(path)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, title))
}

/**
 * Settings: single-page engraved camera manual list.
 */
@Composable
fun SettingsScreen(
    ui: Ui,
    onToggleShooting: () -> Unit,
    onTogglePrintSize: () -> Unit,
    onToggleFormat: () -> Unit,
    onToggleRebate: () -> Unit,
    onToggleLightLeak: () -> Unit,
    onToggleDust: () -> Unit,
    onToggleWind: () -> Unit,
    onToggleFinder: () -> Unit,
    onToggleFramelines: () -> Unit,
    onToggleSpiritLevel: () -> Unit,
    onToggleHistogram: () -> Unit,
    onToggleLiveLook: () -> Unit,
    onToggleDateStamp: () -> Unit,
    onToggleGps: () -> Unit,
    onToggleAutoClean: () -> Unit,
    onCleanRaws: () -> Unit,
    onToggleHand: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Look.Body).windowInsetsPadding(WindowInsets.displayCutout).padding(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Key("← Back", onClick = onBack)
            Label("CAMERA MANUAL / SETTINGS", size = 14.sp, color = Look.Ivory)
            Spacer(Modifier.width(56.dp))
        }

        Spacer(Modifier.height(14.dp))

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            SettingRow("SHOOTING", if (ui.singleShot) "SINGLE (AUTO-DEV)" else "ROLL (12/24/36)", onToggleShooting)
            SettingRow(
                "PRINT SIZE (SINGLE)",
                when (ui.defaultPrintSize) {
                    0 -> "S (720×480)"
                    1 -> "M (1440×960)"
                    else -> "L (FULL)"
                },
                onTogglePrintSize,
            )
            SettingRow(
                "FRAME FORMAT",
                when (ui.aspectFormat) {
                    1 -> "6×6 SQUARE (1:1)"
                    2 -> "XPAN PANORAMA (65:24)"
                    else -> "35MM FULL (3:2)"
                },
                onToggleFormat,
            )
            SettingRow("35MM FILM REBATE", if (ui.filmRebate) "ON (SPROCKETS)" else "OFF", onToggleRebate)
            SettingRow(
                "LIGHT LEAK (EXPIRED)",
                when (ui.lightLeakMode) {
                    1 -> "SUBTLE"
                    2 -> "HEAVY"
                    else -> "OFF"
                },
                onToggleLightLeak,
            )
            SettingRow(
                "DUST & SCRATCHES",
                when (ui.dustMode) {
                    1 -> "SUBTLE"
                    2 -> "VINTAGE"
                    else -> "OFF"
                },
                onToggleDust,
            )
            SettingRow("FILM ADVANCE", if (ui.realisticWind) "REALISTIC (WIND ON)" else "AUTO", onToggleWind)
            SettingRow("VIEWFINDER", if (ui.cleanFinder) "CLEAN" else "INFO", onToggleFinder)
            SettingRow("FRAMELINES", if (ui.framelines) "ON" else "OFF", onToggleFramelines)
            SettingRow("HORIZON LEVEL", if (ui.spiritLevel) "ON" else "OFF", onToggleSpiritLevel)
            SettingRow("LIVE HISTOGRAM", if (ui.showHistogram) "ON" else "OFF", onToggleHistogram)
            SettingRow(
                "LIVE LOOK PREVIEW",
                when (ui.filmPreviewMode) {
                    2 -> "FILM LOOK"
                    1 -> "B&W ONLY"
                    else -> "RAW VIEW"
                },
                onToggleLiveLook,
            )
            SettingRow("DATE STAMP", if (ui.dateStamp) "ON" else "OFF", onToggleDateStamp)
            SettingRow("GPS GEOTAG", if (ui.gpsTag) "ON" else "OFF", onToggleGps)
            SettingRow("AUTO-CLEAN RAW", if (ui.autoCleanRaw) "ON (AFTER DEV)" else "OFF", onToggleAutoClean)
            SettingRow("RAW STORAGE", "${formatMb(ui.rawBytes)} · TAP TO CLEAN", onCleanRaws)
            SettingRow("LANDSCAPE GRIP", if (ui.leftHanded) "LEFT-HANDED" else "RIGHT-HANDED", onToggleHand)

            Spacer(Modifier.height(20.dp))
            Label("M-FILM FOR ANDROID  ·  GPL-2.0", size = 11.sp, color = Look.Dim)
            Spacer(Modifier.height(4.dp))
            Label("59 FILM TABLES BY PAT DAVID (CC BY-SA 4.0)", size = 11.sp, color = Look.Dim)
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    val view = LocalView.current
    Row(
        Modifier
            .fillMaxWidth()
            .border(0.5.dp, Look.Plate)
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(title, size = 13.sp, color = Look.Ivory)
        Label(value, size = 13.sp, color = Look.Amber)
    }
}
