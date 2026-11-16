package app.mfilm.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.remember
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
import java.io.File
import kotlin.math.abs

private val SAFELIGHT = Color(0xFFD92B1C)
private val SAFELIGHT_DIM = Color(0xFF5C150E)
private val PRINT_SIZES = listOf("S" to 0, "M" to 1, "L" to 2)

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

/**
 * Darkroom: pure black with safelight-red accents.
 * Lists rolls as film envelopes, lets the user pick S / M / L print size,
 * and shows live paper-develop preview via fos_dev_preview while DevelopService runs.
 */
@Composable
fun DarkroomScreen(
    onDevelop: (Int, Int) -> Unit,
    onCancel: () -> Unit,
    onOpenPrints: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val view = LocalView.current
    var rolls by remember { mutableStateOf<List<LabRoll>>(emptyList()) }
    var selectedRoll by remember { mutableIntStateOf(-1) }
    var printSize by remember { mutableIntStateOf(0) }
    var deleteAsk by remember { mutableStateOf(false) }

    LaunchedEffect(DevelopState.running) {
        rolls = parseLabRolls(Native.labRolls())
        if (selectedRoll < 0) {
            selectedRoll = rolls.firstOrNull { it.isWaiting }?.number ?: rolls.firstOrNull()?.number ?: -1
        }
        deleteAsk = false
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout).padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Key("← Back", color = SAFELIGHT, onClick = onBack)
            Label("DARKROOM", size = 15.sp, color = SAFELIGHT)
            Spacer(Modifier.width(56.dp))
        }

        Spacer(Modifier.height(12.dp))

        if (DevelopState.running) {
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
                Label("DEVELOPING ROLL %03d".format(DevelopState.rollNo), size = 14.sp, color = SAFELIGHT)

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
            }
            if (rolls.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Label("No rolls on card", size = 13.sp, color = SAFELIGHT_DIM)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rolls, key = { it.number }) { r ->
                        val active = r.number == selectedRoll
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
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Label("ROLL %03d · %s".format(r.number, r.filmName), size = 14.sp, color = SAFELIGHT)
                                Spacer(Modifier.height(3.dp))
                                Label("${r.shot}/${r.exposures} EXP  ·  ${r.loaded.take(10)}", size = 11.sp, color = SAFELIGHT_DIM)
                            }
                            Label(status, size = 11.sp, color = if (r.isWaiting) Look.Amber else SAFELIGHT_DIM)
                        }
                    }
                }

                val sel = rolls.find { it.number == selectedRoll }
                if (sel != null) {
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
                                    rolls = parseLabRolls(Native.labRolls())
                                }
                            }
                            Key("Del all", color = Look.Amber) {
                                Native.labDelete(sel.number, true)
                                deleteAsk = false
                                rolls = parseLabRolls(Native.labRolls())
                                selectedRoll = rolls.firstOrNull()?.number ?: -1
                            }
                            Key("Cancel", color = SAFELIGHT_DIM) { deleteAsk = false }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (sel.isWaiting) {
                                Row(
                                    Modifier.border(1.dp, SAFELIGHT_DIM, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PRINT_SIZES.forEachIndexed { idx, (label, sz) ->
                                        if (idx > 0) Label("·", size = 12.sp, color = SAFELIGHT_DIM)
                                        Key(label, color = if (printSize == sz) SAFELIGHT else SAFELIGHT_DIM) { printSize = sz }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (!sel.inCamera) Key("Delete", color = SAFELIGHT_DIM) { deleteAsk = true }
                                    Box(
                                        Modifier
                                            .defaultMinSize(108.dp, 48.dp)
                                            .border(1.dp, SAFELIGHT, RoundedCornerShape(3.dp))
                                            .clickable {
                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                onDevelop(sel.number, printSize)
                                            }
                                            .padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Label("Develop", size = 13.sp, color = SAFELIGHT)
                                    }
                                }
                            } else {
                                if (!sel.inCamera) Key("Delete", color = SAFELIGHT_DIM) { deleteAsk = true }
                                else Spacer(Modifier.width(1.dp))
                                if (sel.jpegs > 0 || sel.isDeveloped) {
                                    Key("View prints →", color = SAFELIGHT) { onOpenPrints(sel.number) }
                                }
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

    if (lightboxIdx in prints.indices) {
        Lightbox(
            prints = prints,
            index = lightboxIdx,
            onIndex = { lightboxIdx = it },
            onShare = { item -> shareJpeg(context, item.jpegPath) },
            onClose = { lightboxIdx = -1 },
        )
        return
    }

    Column(
        Modifier.fillMaxSize().background(Look.Body).windowInsetsPadding(WindowInsets.displayCutout).padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Key("← Back", onClick = onBack)
            Label(if (rollNo > 0) "PRINTS · ROLL %03d".format(rollNo) else "PRINTS", size = 14.sp, color = Look.Ivory)
            Spacer(Modifier.width(56.dp))
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
                itemsIndexed(prints, key = { _, p -> p.no }) { idx, item ->
                    val thumb = remember(item.jpegPath) { decodeThumb(item.jpegPath, 360) }
                    Column(
                        Modifier.clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            lightboxIdx = idx
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (thumb != null) {
                            Image(
                                thumb.asImageBitmap(),
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
    prints: List<PrintItem>,
    index: Int,
    onIndex: (Int) -> Unit,
    onShare: (PrintItem) -> Unit,
    onClose: () -> Unit,
) {
    val item = prints[index]
    val bmp = remember(item.jpegPath) { decodeThumb(item.jpegPath, 1920) }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var pan by remember(index) { mutableStateOf(Offset.Zero) }

    Column(
        Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout).padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Key("← Sheet", onClick = onClose)
            Label("#%02d  ·  %s  ·  %s".format(item.no, item.whenStr.takeLast(5), item.fstop.ifBlank { "—" }), size = 12.sp, color = Look.Ivory)
            Key("Share", color = Look.Amber) { onShare(item) }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
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
                    bmp.asImageBitmap(),
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
        }
    }
}

private fun decodeThumb(path: String, maxDim: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    var sample = 1
    while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun shareJpeg(context: Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share print"))
}

/**
 * Settings: single-page engraved camera manual list.
 */
@Composable
fun SettingsScreen(
    ui: Ui,
    onToggleWind: () -> Unit,
    onToggleFinder: () -> Unit,
    onToggleFramelines: () -> Unit,
    onToggleBwPreview: () -> Unit,
    onToggleDateStamp: () -> Unit,
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

        Spacer(Modifier.height(18.dp))

        SettingRow("FILM ADVANCE", if (ui.realisticWind) "REALISTIC (WIND ON)" else "AUTO", onToggleWind)
        SettingRow("VIEWFINDER", if (ui.cleanFinder) "CLEAN" else "INFO", onToggleFinder)
        SettingRow("FRAMELINES", if (ui.framelines) "ON" else "OFF", onToggleFramelines)
        SettingRow("B&W FILM PREVIEW", if (ui.bwPreview) "MONOCHROME" else "COLOUR", onToggleBwPreview)
        SettingRow("DATE STAMP", if (ui.dateStamp) "ON" else "OFF", onToggleDateStamp)
        SettingRow("LANDSCAPE GRIP", if (ui.leftHanded) "LEFT-HANDED" else "RIGHT-HANDED", onToggleHand)

        Spacer(Modifier.height(24.dp))
        Label("M-FILM FOR ANDROID  ·  GPL-2.0", size = 11.sp, color = Look.Dim)
        Spacer(Modifier.height(4.dp))
        Label("59 FILM TABLES BY PAT DAVID (CC BY-SA 4.0)", size = 11.sp, color = Look.Dim)
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
