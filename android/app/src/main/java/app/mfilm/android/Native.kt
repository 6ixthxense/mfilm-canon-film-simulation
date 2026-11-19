package app.mfilm.android

import android.graphics.Bitmap

/** The film OS, as built from ml-dev/filmos (libmfilm.so, src/main/cpp). */
object Native {
    init {
        System.loadLibrary("mfilm")
    }

    // events (mfilm_port.h MF_EV_*)
    const val EV_LOADED = 0
    const val EV_SHOT = 1
    const val EV_FINISHED = 2
    const val EV_REWOUND = 3
    const val EV_DEVELOPING = 4
    const val EV_WIND = 5
    const val EV_CAPTURE = 17

    /** files: where FOSFONT.BIN and FOSFILMS.BIN are; card: the card's folder. 0 when running. */
    @JvmStatic external fun start(files: String, card: String): Int
    @JvmStatic external fun started(): Boolean
    @JvmStatic external fun half(down: Boolean)
    @JvmStatic external fun shutter()
    @JvmStatic external fun wind()
    /** the frame asked for was taken (or not); dng: it is the phone's RAW, IMG_nnnn.DNG, not a CR2 */
    @JvmStatic external fun shotDone(ok: Boolean, dng: Boolean)
    @JvmStatic external fun setPending(n: Int)
    @JvmStatic external fun battery(percent: Int)
    /** out: what, a, b */
    @JvmStatic external fun pollEvent(out: IntArray): Boolean

    /** out (6): loaded, shot, exposures, needs winding on, the film's ISO, roll number; returns the film's name */
    @JvmStatic external fun rollInfo(out: IntArray): String
    @JvmStatic external fun pendingRolls(): Int
    @JvmStatic external fun filmCount(): Int
    /** out (6): iso, group, grain, punch, colour, warmth; returns film name */
    @JvmStatic external fun filmInfo(idx: Int, out: IntArray): String
    @JvmStatic external fun drawCanister(film: Int, exposures: Int, leader: Int, bitmap: Bitmap)
    @JvmStatic external fun loadRoll(film: Int, exposures: Int)
    @JvmStatic external fun switchFilm(film: Int, singleMode: Boolean)
    @JvmStatic external fun rewindRoll()
    @JvmStatic external fun rollFrames(): String

    const val SET_WIND = 3
    const val SET_STAMP = 23
    @JvmStatic external fun getSetting(id: Int): Int
    @JvmStatic external fun setSetting(id: Int, value: Int)
    @JvmStatic external fun setShotFstop(fstopX100: Int)
    @JvmStatic external fun labRolls(): String
    @JvmStatic external fun labRollPrints(rollNo: Int): String
    @JvmStatic external fun labDelete(rollNo: Int, whole: Boolean): Int
    @JvmStatic external fun labHasRaws(rollNo: Int): Boolean
    @JvmStatic external fun importRoll(film: Int, count: Int, outFirstFile: IntArray): Int
    @JvmStatic external fun devOpen(rollNo: Int, size: Int, pushStops: Int, targetFrame: Int): Int
    /** out (3): currentFrameNo, doneFrames, progress0to1000; returns 1 developing, 0 done, -1 error */
    @JvmStatic external fun devStep(budgetMs: Int, out: IntArray): Int
    @JvmStatic external fun devPreview(bitmap: Bitmap): Boolean
    @JvmStatic external fun devError(): String
    @JvmStatic external fun devClose()
    @JvmStatic external fun makeCr2(path: String, argb: IntArray, w: Int, h: Int, seed: Int): Int
}
