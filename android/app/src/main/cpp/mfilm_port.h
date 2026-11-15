/* M-Film on Android: the film OS's port, as ml-dev/filmos/web/web.c is in a
 * browser. Plain C, no Android headers: jni_bridge.c wraps it for the app,
 * and android/test/smoke.c runs it on a PC.
 *
 * Threads: mf_run() is the film OS's own loop and never returns; run it on
 * a thread of its own. Everything else may be called from any thread. The
 * film OS's state is only ever touched on its own thread: the other calls
 * post to it (keys, commands) or read what it published (the screen,
 * events). */

#ifndef MFILM_PORT_H
#define MFILM_PORT_H

#include <stdint.h>

#define MF_W 720
#define MF_H 480

/* what the app is told (mf_poll_event) */
enum {
    MF_EV_LOADED, MF_EV_SHOT, MF_EV_FINISHED, MF_EV_REWOUND, MF_EV_DEVELOPING,
    MF_EV_WIND, MF_EV_LV, MF_EV_SCREEN,         /* the same as web.c's */
    MF_EV_MODE = 16,        /* a = 1: the film OS is up, 0: LiveView */
    MF_EV_CAPTURE,          /* a = file number: take the picture, then mf_make_cr2 + mf_shot_done */
    MF_EV_DIM,              /* a = 1: dim the backlight while the darkroom works */
    MF_EV_CANON_MENU,       /* "Canon's menu": the app's own settings */
};

/* files_dir: where FOSFONT.BIN and FOSFILMS.BIN are; card_dir: the memory
 * card, ending in '/' (ML/DARKROOM/LOOKnn.LUT already on it) */
int  mf_init(const char *files_dir, const char *card_dir);
void mf_run(void);

void mf_key(int k);                 /* FK_*, | 0x100 on release */
void mf_half(int down);             /* the shutter half-pressed */
void mf_shutter(void);              /* fully pressed: MF_EV_CAPTURE follows if a frame can be shot */
void mf_shot_done(int ok, int dng); /* the frame asked for by MF_EV_CAPTURE was taken (or not): IMG_nnnn.DNG, or .CR2 */
void mf_set_pending(int n);         /* how many CR2s are still being written: the darkroom waits for them */
void mf_open(int screen);           /* FOS_OPEN_*, when on LiveView */
void mf_battery(int percent);

int  mf_poll_event(int *what, int *a, int *b);   /* 1 while there are events */
/* the screen as ARGB (index 0 transparent: LiveView shows through), if it
 * changed since the last call; returns 1 if out was written */
int  mf_frame(uint32_t *out, int force);
int  mf_ui_running(void);
/* out: loaded, shot, exposures, the frame needs winding on, the film's ISO; film: its name */
void mf_roll_info(int out[5], char *film, int film_size);
int  mf_pending_rolls(void);
int  mf_film_count(void);
void mf_film_info(int idx, int out[6], char *name, int name_size);
void mf_draw_canister(int film, int exposures, int leader, uint32_t *argb, int w, int h);
void mf_load_roll(int film, int exposures);
void mf_rewind_roll(void);
int  mf_roll_frames(char *out, int out_size);
int  mf_get_setting(int id);
void mf_set_setting(int id, int val);
void mf_set_shot_fstop(int fstop_x100);
int  mf_lab_rolls(char *out, int out_size);
int  mf_lab_roll_prints(int roll_no, char *out, int out_size);
int  mf_dev_open(int roll_no, int size);
int  mf_dev_step(int budget_ms, int out[3]);
int  mf_dev_preview_bmp(uint32_t *argb, int w, int h);
void mf_dev_close(void);
const char *mf_card(void);

/* a CR2 shaped like the EOS M's (the whole 18 MP sensor) from a photo:
 * rgba as Android's ARGB_8888 ints (0xAARRGGBB), sRGB. 0 on success */
int  mf_make_cr2(const char *path, const uint32_t *argb, int w, int h, int seed);

extern void (*mf_log_fn)(const char *msg);

#endif
