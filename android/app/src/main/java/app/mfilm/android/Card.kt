package app.mfilm.android

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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

    // ---------------------------------------------------------------- the gallery

    /** every print the darkroom made, into Pictures/M-Film/<roll folder>, once each */
    fun exportPrints(done: (Int) -> Unit = {}) {
        exporter.execute {
            var n = 0
            try {
                val seen = if (exportedList.exists()) exportedList.readLines().toMutableSet() else mutableSetOf()
                val folders = File(root, "DCIM").listFiles { f -> f.isDirectory && f.name != "100CANON" } ?: emptyArray()
                for (folder in folders.sortedBy { it.name }) {
                    val prints = folder.listFiles { f -> f.name.uppercase().endsWith(".JPG") } ?: continue
                    for (p in prints.sortedBy { it.name }) {
                        val key = "${folder.name}/${p.name}:${p.length()}"
                        if (key in seen) continue
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
            done(n)
        }
    }

    private fun toGallery(folder: String, file: File): Boolean {
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
    }
}
