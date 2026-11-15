/* The shutter: a phone photo into a CR2 shaped like the EOS M's, for the
 * darkroom (fos_dev.c) to develop, as the browser does with mkcr2.wasm.
 *
 * ml-dev/filmos/sim/tools/mkcr2.c is a command-line tool; it is included
 * here as it is, its main renamed, and its buffers given back after each
 * frame (the tool leaves that to the end of its process). The photo goes
 * straight into its `photo` (linear sRGB, 0.18 mid grey), not through a
 * PFM file. */

#define main mkcr2_main
#include "../../../../../ml-dev/filmos/sim/tools/mkcr2.c"
#undef main

#include <pthread.h>
#include "mfilm_port.h"

static pthread_mutex_t mk_lock = PTHREAD_MUTEX_INITIALIZER;

static void reset(void)
{
    free(photo); photo = 0; photo_w = photo_h = 0;
    free(raw); raw = 0;
    free(out); out = 0; out_len = out_cap = 0;
    free(tif); tif = 0; tif_len = 0;
    bitacc = 0; bitn = 0;
    fold = 0; scene_kind = 0; wb_r = wb_b = 0; clip_at = WHITE + 300;
    RAW_W = 5280; RAW_H = 3528; CROP_L = 84; CROP_T = 64; CROP_W = 5184; CROP_H = 3456;
}

int mf_make_cr2(const char *path, const uint32_t *argb, int w, int h, int seed)
{
    if (!argb || w < 2 || h < 2) return -1;
    pthread_mutex_lock(&mk_lock);
    reset();
    float lin[256];
    for (int i = 0; i < 256; i++)
    {
        double v = i / 255.0;
        lin[i] = (float) (v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4));
    }
    photo = malloc((size_t) w * h * 3 * sizeof(float));
    if (!photo) { pthread_mutex_unlock(&mk_lock); return -1; }
    photo_w = w; photo_h = h;
    for (size_t i = 0; i < (size_t) w * h; i++)
    {
        uint32_t c = argb[i];
        photo[i * 3 + 0] = lin[(c >> 16) & 0xFF];
        photo[i * 3 + 1] = lin[(c >> 8) & 0xFF];
        photo[i * 3 + 2] = lin[c & 0xFF];
    }
    char seed_s[16];
    snprintf(seed_s, sizeof(seed_s), "%d", seed);
    char *argv[] = { "mkcr2", (char *) path, "--seed", seed_s, 0 };
    int r = mkcr2_main(4, argv);
    reset();
    pthread_mutex_unlock(&mk_lock);
    return r;
}
