package app.mfilm.android

import android.graphics.Bitmap

/** The film OS, as built from ml-dev/filmos (libmfilm.so, src/main/cpp). */
object Native {
    init {
        System.loadLibrary("mfilm")
    }

    const val W = 720
    const val H = 480

    // keys (fos.h FK_*), | RELEASE when let go
    const val K_LEFT = 1
    const val K_RIGHT = 2
    const val K_UP = 3
    const val K_DOWN = 4
    const val K_WHEEL_L = 5
    const val K_WHEEL_R = 6
    const val K_SET = 7
    const val K_MENU = 8
    const val K_INFO = 9
    const val K_PLAY = 10
    const val K_HOME = 11
    const val RELEASE = 0x100

    // screens (FOS_OPEN_*)
    const val OPEN_SHELF = 0
    const val OPEN_HOME = 1
    const val OPEN_PRINTS = 4
    const val OPEN_DARKROOM = 6

    // events (mfilm_port.h MF_EV_*)
    const val EV_LOADED = 0
    const val EV_SHOT = 1
    const val EV_FINISHED = 2
    const val EV_REWOUND = 3
    const val EV_DEVELOPING = 4
    const val EV_WIND = 5
    const val EV_MODE = 16
    const val EV_CAPTURE = 17
    const val EV_DIM = 18
    const val EV_CANON_MENU = 19

    /** files: where FOSFONT.BIN and FOSFILMS.BIN are; card: the card's folder. 0 when running. */
    @JvmStatic external fun start(files: String, card: String): Int
    @JvmStatic external fun started(): Boolean
    @JvmStatic external fun key(k: Int)
    @JvmStatic external fun half(down: Boolean)
    @JvmStatic external fun shutter()
    @JvmStatic external fun shotDone(ok: Boolean)
    @JvmStatic external fun setPending(n: Int)
    @JvmStatic external fun openScreen(screen: Int)
    @JvmStatic external fun battery(percent: Int)
    @JvmStatic external fun uiRunning(): Boolean
    /** out: what, a, b */
    @JvmStatic external fun pollEvent(out: IntArray): Boolean
    /** the screen into a 720 x 480 ARGB_8888 bitmap; true if it changed */
    @JvmStatic external fun frame(bitmap: Bitmap, force: Boolean): Boolean
    /** out: loaded, shot, exposures; returns the film's name */
    @JvmStatic external fun rollInfo(out: IntArray): String
    /** argb: sRGB pixels, w x h; 0 on success */
    @JvmStatic external fun makeCr2(path: String, argb: IntArray, w: Int, h: Int, seed: Int): Int
}
