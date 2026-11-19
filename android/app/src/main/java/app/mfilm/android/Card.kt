package app.mfilm.android

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.floor

/**
 * The camera's memory card: a folder of the app's (Android/data/<app>/files/card),
 * laid out as a Canon card with M-Film on it:
 *   DCIM/100CANON/IMG_nnnn.CR2, ROLL_nnn.TXT   what was shot
 *   DCIM/<day> <film>/IMG_nnnn.JPG               the developed prints (copied to the gallery too)
 *   ML/DARKROOM/LOOKnn.LUT                       every film's colour table
 *   ML/PENDING/IMG_nnnn.JPG                      frames shot whose CR2 isn't written yet
 */
class Card(private val context: Context) {
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "card")
    val data: File = context.filesDir
    private val dcim = File(root, "DCIM/100CANON")
    private val pending = File(root, "ML/PENDING")
    private val exportedList = File(root, "ML/EXPORTED.TXT")
    private val memosFile = File(root, "ML/MEMOS.TXT")
    private val gpsFile = File(root, "ML/GPS.TXT")
    val widgetCanisterFile = File(root, "ML/WIDGET_CANISTER.PNG")

    private val writer = Executors.newSingleThreadExecutor()
    private val exporter = Executors.newSingleThreadExecutor()
    private val queued = AtomicInteger(0)

    /** the fonts, films and colour tables from the APK, once per version */
    fun install() {
        dcim.mkdirs()
        pending.mkdirs()
        val darkroom = File(root, "ML/DARKROOM").apply { mkdirs() }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val stamp = File(data, "installed.txt")
        if (stamp.exists() && stamp.readText().trim() == version.toString() &&
            File(data, "FOSFONT.BIN").exists() && (darkroom.list()?.size ?: 0) > 0) return
        val assets = context.assets
        for (name in listOf("FOSFONT.BIN", "FOSFILMS.BIN")) copyAsset(name, File(data, name))
        for (name in assets.list("tables") ?: emptyArray()) copyAsset("tables/$name", File(darkroom, name))
        for (name in listOf("LICENSE.txt", "THIRD_PARTY.txt")) copyAsset(name, File(root, "ML/$name"))
        stamp.writeText(version.toString())
    }

    private fun copyAsset(name: String, to: File) {
        to.parentFile?.mkdirs()
        context.assets.open(name).use { input -> FileOutputStream(to).use { input.copyTo(it) } }
    }

    // ---------------------------------------------------------------- the shutter

    /** a frame just taken (already cropped to 3:2): kept, and its CR2 written in the background */
    fun shot(number: Int, photo: Bitmap) {
        val file = File(pending, "IMG_%04d.JPG".format(number))
        FileOutputStream(file).use { photo.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        photo.recycle()
        enqueue(file)
    }

    /** where the sensor's RAW of frame `number` is written while it is being written; counted as pending until rawDone */
    fun rawPart(number: Int): File {
        Native.setPending(queued.incrementAndGet())
        return File(dcim, "IMG_%04d.DNG.part".format(number))
    }

    /** the RAW written (ok) or not: IMG_nnnn.DNG on the card, or nothing. Whether it is there */
    fun rawDone(part: File, ok: Boolean): Boolean {
        val dng = File(part.path.removeSuffix(".part"))
        val there = ok && part.length() > 0 && part.renameTo(dng)
        if (!there) part.delete()
        Native.setPending(queued.decrementAndGet())
        return there
    }

    /** frames left from before (the app closed while writing them) */
    fun resumePending() {
        pending.listFiles { f -> f.name.endsWith(".JPG") }?.sortedBy { it.name }?.forEach { enqueue(it) }
    }

    private fun enqueue(file: File) {
        Native.setPending(queued.incrementAndGet())
        writer.execute {
            try {
                writeCr2(file)
            } catch (t: Throwable) {
                Log.e(TAG, "CR2 for ${file.name}", t)
            } finally {
                Native.setPending(queued.decrementAndGet())
            }
        }
    }

    private fun writeCr2(file: File) {
        val number = Regex("IMG_(\\d{4})").find(file.name)?.groupValues?.get(1)?.toInt() ?: return
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: run { file.delete(); return }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val cr2 = File(dcim, "IMG_%04d.CR2".format(number))
        val r = Native.makeCr2(cr2.path, px, bmp.width, bmp.height, number)
        bmp.recycle()
        if (r == 0) file.delete() else Log.e(TAG, "mkcr2 failed ($r) for ${file.name}")
    }

    // ---------------------------------------------------------------- storage, memos, GPS & gallery import

    fun rawStorageBytes(): Long {
        val rawFiles = dcim.listFiles { f ->
            val u = f.name.uppercase()
            u.endsWith(".DNG") || u.endsWith(".CR2")
        } ?: emptyArray()
        val pendFiles = pending.listFiles() ?: emptyArray()
        return rawFiles.sumOf { it.length() } + pendFiles.sumOf { it.length() }
    }

    fun cleanRawsForRoll(rollNo: Int): Int {
        val logFile = File(dcim, "ROLL_%03d.TXT".format(rollNo))
        if (!logFile.exists()) return 0
        var deleted = 0
        val re = Regex("(IMG_\\d{4}\\.(?:CR2|DNG))", RegexOption.IGNORE_CASE)
        for (line in logFile.readLines()) {
            val m = re.find(line) ?: continue
            val f = File(dcim, m.groupValues[1])
            if (f.exists() && f.delete()) deleted++
        }
        return deleted
    }

    fun cleanAllDevelopedRaws(): Int {
        var total = 0
        for (r in parseLabRolls(Native.labRolls())) {
            if (r.isDeveloped && !r.inCamera) total += cleanRawsForRoll(r.number)
        }
        return total
    }

    fun loadMemos(): Map<Int, String> {
        if (!memosFile.exists()) return emptyMap()
        val map = mutableMapOf<Int, String>()
        for (line in memosFile.readLines()) {
            val idx = line.indexOf('=')
            if (idx > 0) {
                val no = line.substring(0, idx).trim().toIntOrNull() ?: continue
                val text = line.substring(idx + 1).trim()
                if (text.isNotEmpty()) map[no] = text
            }
        }
        return map
    }

    fun setMemo(rollNo: Int, memo: String) {
        val map = loadMemos().toMutableMap()
        val clean = memo.replace('\n', ' ').replace('\r', ' ').trim().take(48)
        if (clean.isEmpty()) map.remove(rollNo) else map[rollNo] = clean
        memosFile.parentFile?.mkdirs()
        memosFile.writeText(map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" })
    }

    fun recordGps(fileNumber: Int, lat: Double, lon: Double) {
        try {
            gpsFile.parentFile?.mkdirs()
            gpsFile.appendText("IMG_%04d=%.6f,%.6f\n".format(Locale.US, fileNumber, lat, lon))
        } catch (_: Throwable) { }
    }

    private fun lookupGps(stem: String): Pair<Double, Double>? {
        if (!gpsFile.exists()) return null
        val prefix = "$stem="
        val line = gpsFile.readLines().lastOrNull { it.startsWith(prefix) } ?: return null
        val parts = line.removePrefix(prefix).split(',')
        if (parts.size < 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return lat to lon
    }

    fun importImagesAsCr2(uris: List<Uri>, firstFile: Int): Int {
        var okCount = 0
        uris.forEachIndexed { idx, uri ->
            val fileNo = firstFile + idx
            val bmp = decodeImported32(uri) ?: return@forEachIndexed
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val cr2 = File(dcim, "IMG_%04d.CR2".format(fileNo))
            val r = Native.makeCr2(cr2.path, px, bmp.width, bmp.height, fileNo)
            bmp.recycle()
            if (r == 0) okCount++
        }
        return okCount
    }

    private fun decodeImported32(uri: Uri): Bitmap? {
        return try {
            val cr = context.contentResolver
            val rot = cr.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 2800 || bounds.outHeight / sample > 2800) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            val upright = if (rot != 0f) {
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot) }, true)
                    .also { if (it !== raw) raw.recycle() }
            } else raw
            val landscape = upright.width >= upright.height
            var w = upright.width
            var h = if (landscape) w * 2 / 3 else w * 3 / 2
            if (h > upright.height) {
                h = upright.height
                w = if (landscape) h * 3 / 2 else h * 2 / 3
            }
            val maxLong = 2592f
            val scale = minOf(1f, maxLong / maxOf(w, h))
            val m = Matrix().apply { postScale(scale, scale) }
            val out = Bitmap.createBitmap(upright, (upright.width - w) / 2, (upright.height - h) / 2, w, h, m, true)
            if (out !== upright) upright.recycle()
            out
        } catch (t: Throwable) {
            Log.e(TAG, "import decode", t)
            null
        }
    }

    fun saveWidgetCanister(filmIdx: Int, exposures: Int) {
        try {
            if (filmIdx < 0) return
            val bmp = Bitmap.createBitmap(260, 220, Bitmap.Config.ARGB_8888)
            Native.drawCanister(filmIdx, exposures.coerceAtLeast(12), 0, bmp)
            widgetCanisterFile.parentFile?.mkdirs()
            FileOutputStream(widgetCanisterFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
            bmp.recycle()
        } catch (_: Throwable) { }
    }

    // ---------------------------------------------------------------- the gallery

    /** every print the darkroom made, into Pictures/M-Film/<roll folder>, once each */
    fun exportPrints(done: (Int) -> Unit = {}) {
        exporter.execute { done(exportPrintsSync()) }
    }

    fun exportPrintsSync(): Int = synchronized(exportLock) {
        var n = 0
        try {
            val seen = if (exportedList.exists()) exportedList.readLines().toMutableSet() else mutableSetOf()
            val folders = File(root, "DCIM").listFiles { f -> f.isDirectory && f.name != "100CANON" } ?: emptyArray()
            for (folder in folders.sortedBy { it.name }) {
                val prints = folder.listFiles { f -> f.name.uppercase().endsWith(".JPG") } ?: continue
                for (p in prints.sortedBy { it.name }) {
                    val key = "${folder.name}/${p.name}:${p.length()}:${p.lastModified()}"
                    val legacyKey = "${folder.name}/${p.name}:${p.length()}"
                    if (key in seen || legacyKey in seen) continue
                    if (toGallery(folder.name, p)) {
                        seen += key
                        exportedList.appendText(key + "\n")
                        n++
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "export", t)
        }
        n
    }

    private fun toDms(coord: Double): String {
        val a = abs(coord)
        val deg = floor(a).toInt()
        val minF = (a - deg) * 60.0
        val min = floor(minF).toInt()
        val sec = ((minF - min) * 60.0 * 1000.0).toInt()
        return "$deg/1,$min/1,$sec/1000"
    }

    private fun writeExif(folder: File, jpeg: File) {
        try {
            val exif = ExifInterface(jpeg.absolutePath)
            var filmName = folder.name.substringAfter(' ', folder.name)
            var rollNo = -1
            val tagFile = folder.listFiles { f -> f.name.startsWith("ROLL") && f.name.endsWith(".TXT") }?.firstOrNull()
            if (tagFile != null) {
                for (line in tagFile.readLines()) {
                    if (line.startsWith("roll = ")) rollNo = line.removePrefix("roll = ").trim().toIntOrNull() ?: -1
                    if (line.startsWith("film = ")) filmName = line.removePrefix("film = ").trim()
                }
            }
            var dateStr: String? = null
            var fstopStr: String? = null
            val stem = jpeg.nameWithoutExtension
            if (rollNo > 0) {
                val logFile = File(dcim, "ROLL_%03d.TXT".format(rollNo))
                if (logFile.exists()) {
                    val match = logFile.readLines().firstOrNull { it.contains(stem) }
                    if (match != null) {
                        Regex("(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2})").find(match)?.let {
                            dateStr = "${it.groupValues[1].replace('-', ':')} ${it.groupValues[2]}"
                        }
                        Regex("f/([0-9.]+)").find(match)?.let { fstopStr = it.groupValues[1] }
                    }
                }
            }
            if (dateStr == null) {
                dateStr = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(jpeg.lastModified()))
            }
            val memo = if (rollNo > 0) loadMemos()[rollNo] else null
            val desc = if (memo != null) "M-Film · $filmName · $memo" else "M-Film · $filmName"
            exif.setAttribute(ExifInterface.TAG_MAKE, "M-Film")
            exif.setAttribute(ExifInterface.TAG_MODEL, filmName)
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, desc)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, desc)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
            if (fstopStr != null) exif.setAttribute(ExifInterface.TAG_F_NUMBER, fstopStr)
            lookupGps(stem)?.let { (lat, lon) ->
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, toDms(lat))
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (lat >= 0) "N" else "S")
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toDms(lon))
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lon >= 0) "E" else "W")
            }
            exif.saveAttributes()
        } catch (t: Throwable) {
            Log.w(TAG, "exif ${jpeg.name}: ${t.message}")
        }
    }

    private fun toGallery(folder: String, file: File): Boolean {
        file.parentFile?.let { writeExif(it, file) }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${folder.replace(' ', '_')}_${file.name}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/M-Film/$folder")
            put(MediaStore.Images.Media.DATE_TAKEN, file.lastModified())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw Exception("no stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            Log.e(TAG, "gallery ${file.name}", t)
            false
        }
    }

    companion object {
        const val TAG = "mfilm"
        private val exportLock = Any()
    }
}
