/* The port (mfilm_port.c) for Kotlin: app.mfilm.android.Native */

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <android/bitmap.h>
#include <android/log.h>
#include "mfilm_port.h"

#define FN(name) Java_app_mfilm_android_Native_##name

static void alog(const char *msg) { __android_log_print(ANDROID_LOG_INFO, "mfilm", "%s", msg); }

static pthread_t fos_thread;
static int started;
static uint32_t frame_buf[MF_W * MF_H];

static void *run(void *arg) { mf_run(); return 0; }

JNIEXPORT jint JNICALL FN(start)(JNIEnv *env, jclass cls, jstring files, jstring card)
{
    if (started) return 0;
    mf_log_fn = alog;
    const char *f = (*env)->GetStringUTFChars(env, files, 0);
    const char *c = (*env)->GetStringUTFChars(env, card, 0);
    int r = mf_init(f, c);
    (*env)->ReleaseStringUTFChars(env, files, f);
    (*env)->ReleaseStringUTFChars(env, card, c);
    if (r) return r;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 4 << 20);      /* the film OS keeps big things on its stack */
    if (pthread_create(&fos_thread, &attr, run, 0)) return -2;
    started = 1;
    return 0;
}

JNIEXPORT jboolean JNICALL FN(started)(JNIEnv *env, jclass cls) { return started; }
JNIEXPORT void JNICALL FN(key)(JNIEnv *env, jclass cls, jint k) { mf_key(k); }
JNIEXPORT void JNICALL FN(half)(JNIEnv *env, jclass cls, jboolean down) { mf_half(down); }
JNIEXPORT void JNICALL FN(shutter)(JNIEnv *env, jclass cls) { mf_shutter(); }
JNIEXPORT void JNICALL FN(shotDone)(JNIEnv *env, jclass cls, jboolean ok, jboolean dng) { mf_shot_done(ok, dng); }
JNIEXPORT void JNICALL FN(setPending)(JNIEnv *env, jclass cls, jint n) { mf_set_pending(n); }
JNIEXPORT void JNICALL FN(openScreen)(JNIEnv *env, jclass cls, jint screen) { mf_open(screen); }
JNIEXPORT void JNICALL FN(battery)(JNIEnv *env, jclass cls, jint pct) { mf_battery(pct); }
JNIEXPORT jboolean JNICALL FN(uiRunning)(JNIEnv *env, jclass cls) { return mf_ui_running() != 0; }

/* out: what, a, b */
JNIEXPORT jboolean JNICALL FN(pollEvent)(JNIEnv *env, jclass cls, jintArray out)
{
    int e[3];
    if (!mf_poll_event(&e[0], &e[1], &e[2])) return JNI_FALSE;
    (*env)->SetIntArrayRegion(env, out, 0, 3, e);
    return JNI_TRUE;
}

/* the screen into a 720 x 480 ARGB_8888 bitmap, if it changed */
JNIEXPORT jboolean JNICALL FN(frame)(JNIEnv *env, jclass cls, jobject bitmap, jboolean force)
{
    if (!started || !mf_frame(frame_buf, force)) return JNI_FALSE;
    AndroidBitmapInfo info;
    void *px;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.width != MF_W || info.height != MF_H ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || AndroidBitmap_lockPixels(env, bitmap, &px) < 0)
        return JNI_FALSE;
    for (int y = 0; y < MF_H; y++)
    {
        uint32_t *row = (uint32_t *) ((uint8_t *) px + (size_t) y * info.stride);
        const uint32_t *src = frame_buf + y * MF_W;
        for (int x = 0; x < MF_W; x++)
        {
            uint32_t c = src[x];        /* 0xAARRGGBB -> bytes R, G, B, A */
            row[x] = (c & 0xFF00FF00u) | (c >> 16 & 0xFF) | (c & 0xFF) << 16;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

/* out: loaded, shot, exposures; returns the film's name */
JNIEXPORT jstring JNICALL FN(rollInfo)(JNIEnv *env, jclass cls, jintArray out)
{
    int r[5];
    char film[64];
    mf_roll_info(r, film, sizeof(film));
    (*env)->SetIntArrayRegion(env, out, 0, 5, r);
    return (*env)->NewStringUTF(env, film);
}

JNIEXPORT jint JNICALL FN(pendingRolls)(JNIEnv *env, jclass cls)
{
    return mf_pending_rolls();
}

JNIEXPORT jint JNICALL FN(filmCount)(JNIEnv *env, jclass cls)
{
    return mf_film_count();
}

JNIEXPORT jstring JNICALL FN(filmInfo)(JNIEnv *env, jclass cls, jint idx, jintArray out)
{
    int r[6];
    char name[64];
    mf_film_info(idx, r, name, sizeof(name));
    (*env)->SetIntArrayRegion(env, out, 0, 6, r);
    return (*env)->NewStringUTF(env, name);
}

JNIEXPORT void JNICALL FN(drawCanister)(JNIEnv *env, jclass cls, jint film, jint exposures, jint leader, jobject bitmap)
{
    AndroidBitmapInfo info;
    void *px;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || AndroidBitmap_lockPixels(env, bitmap, &px) < 0)
        return;
    int w = (int) info.width, h = (int) info.height;
    uint32_t *buf = malloc((size_t) w * h * 4);
    if (buf)
    {
        mf_draw_canister(film, exposures, leader, buf, w, h);
        for (int y = 0; y < h; y++)
        {
            uint32_t *row = (uint32_t *) ((uint8_t *) px + (size_t) y * info.stride);
            const uint32_t *src = buf + y * w;
            for (int x = 0; x < w; x++)
            {
                uint32_t c = src[x];
                row[x] = (c & 0xFF00FF00u) | (c >> 16 & 0xFF) | (c & 0xFF) << 16;
            }
        }
        free(buf);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL FN(loadRoll)(JNIEnv *env, jclass cls, jint film, jint exposures)
{
    mf_load_roll(film, exposures);
}

JNIEXPORT void JNICALL FN(rewindRoll)(JNIEnv *env, jclass cls)
{
    mf_rewind_roll();
}

JNIEXPORT jstring JNICALL FN(rollFrames)(JNIEnv *env, jclass cls)
{
    char buf[4096];
    mf_roll_frames(buf, sizeof(buf));
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jint JNICALL FN(getSetting)(JNIEnv *env, jclass cls, jint id)
{
    return mf_get_setting(id);
}

JNIEXPORT void JNICALL FN(setSetting)(JNIEnv *env, jclass cls, jint id, jint val)
{
    mf_set_setting(id, val);
}

JNIEXPORT void JNICALL FN(setShotFstop)(JNIEnv *env, jclass cls, jint fstopX100)
{
    mf_set_shot_fstop(fstopX100);
}

JNIEXPORT jstring JNICALL FN(labRolls)(JNIEnv *env, jclass cls)
{
    char buf[4096];
    mf_lab_rolls(buf, sizeof(buf));
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL FN(labRollPrints)(JNIEnv *env, jclass cls, jint rollNo)
{
    char *buf = malloc(16384);
    if (!buf) return (*env)->NewStringUTF(env, "");
    mf_lab_roll_prints(rollNo, buf, 16384);
    jstring res = (*env)->NewStringUTF(env, buf);
    free(buf);
    return res;
}

JNIEXPORT jint JNICALL FN(labDelete)(JNIEnv *env, jclass cls, jint rollNo, jboolean whole)
{
    return mf_lab_delete(rollNo, whole ? 1 : 0);
}

JNIEXPORT jint JNICALL FN(devOpen)(JNIEnv *env, jclass cls, jint rollNo, jint size)
{
    return mf_dev_open(rollNo, size);
}

JNIEXPORT jint JNICALL FN(devStep)(JNIEnv *env, jclass cls, jint budgetMs, jintArray out)
{
    int r[3] = {0, 0, 0};
    int res = mf_dev_step(budgetMs, r);
    (*env)->SetIntArrayRegion(env, out, 0, 3, r);
    return res;
}

JNIEXPORT jboolean JNICALL FN(devPreview)(JNIEnv *env, jclass cls, jobject bitmap)
{
    AndroidBitmapInfo info;
    void *px;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || AndroidBitmap_lockPixels(env, bitmap, &px) < 0)
        return JNI_FALSE;
    int w = (int) info.width, h = (int) info.height;
    uint32_t *buf = malloc((size_t) w * h * 4);
    int ok = 0;
    if (buf)
    {
        ok = mf_dev_preview_bmp(buf, w, h);
        if (ok)
        {
            for (int y = 0; y < h; y++)
            {
                uint32_t *row = (uint32_t *) ((uint8_t *) px + (size_t) y * info.stride);
                const uint32_t *src = buf + y * w;
                for (int x = 0; x < w; x++)
                {
                    uint32_t c = src[x];
                    row[x] = (c & 0xFF00FF00u) | (c >> 16 & 0xFF) | (c & 0xFF) << 16;
                }
            }
        }
        free(buf);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL FN(devClose)(JNIEnv *env, jclass cls)
{
    mf_dev_close();
}

JNIEXPORT jint JNICALL FN(makeCr2)(JNIEnv *env, jclass cls, jstring path, jintArray argb, jint w, jint h, jint seed)
{
    if ((*env)->GetArrayLength(env, argb) < w * h) return -1;
    const char *p = (*env)->GetStringUTFChars(env, path, 0);
    jint *px = (*env)->GetIntArrayElements(env, argb, 0);
    int r = mf_make_cr2(p, (const uint32_t *) px, w, h, seed);
    (*env)->ReleaseIntArrayElements(env, argb, px, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return r;
}
