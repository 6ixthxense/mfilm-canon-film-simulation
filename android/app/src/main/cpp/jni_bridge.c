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
JNIEXPORT void JNICALL FN(shotDone)(JNIEnv *env, jclass cls, jboolean ok) { mf_shot_done(ok); }
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
    int r[3];
    char film[64];
    mf_roll_info(&r[0], &r[1], &r[2], film, sizeof(film));
    (*env)->SetIntArrayRegion(env, out, 0, 3, r);
    return (*env)->NewStringUTF(env, film);
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
