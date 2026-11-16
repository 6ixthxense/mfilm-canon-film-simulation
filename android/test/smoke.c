/* Runs the Android port on a PC, as the app would: a script of
 * shots.
 *   smoke FILES_DIR CARD_DIR OUT_DIR  cmd...
 *   cmds: w<ms> (wait)  s (shoot)  h1/h0 (half)  l<film> (load 36)  n (wind) */

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "../app/src/main/cpp/mfilm_port.h"

static const char *outdir, *card;
static int shots;

static void *run(void *x) { mf_run(); return 0; }

static void events(void)
{
    int w, a, b;
    while (mf_poll_event(&w, &a, &b))
    {
        printf("event %d %d %d\n", w, a, b);
        if (w == MF_EV_CAPTURE)
        {
            int W = 2592, H = 1728;
            uint32_t *img = malloc((size_t) W * H * 4);
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++)
                {
                    int r = x * 255 / W, g = y * 255 / H, bl = (x / 200 + y / 200) % 2 ? 200 : 60;
                    img[y * W + x] = 0xFF000000u | r << 16 | g << 8 | bl;
                }
            char path[600];
            snprintf(path, sizeof(path), "%sDCIM/100CANON/IMG_%04d.CR2", mf_card(), a);
            mf_set_pending(1);
            mf_shot_done(1, 0);
            int r = mf_make_cr2(path, img, W, H, a);
            mf_set_pending(0);
            free(img);
            printf("cr2 %s -> %d\n", path, r);
            shots++;
        }
    }
}

static void wait_ms(int ms)
{
    for (int t = 0; t < ms; t += 20) { events(); usleep(20000); }
    events();
}

int main(int argc, char **argv)
{
    if (argc < 4) return 2;
    card = argv[2]; outdir = argv[3];
    if (mf_init(argv[1], card)) return 1;
    pthread_t t;
    pthread_create(&t, 0, run, 0);
    for (int i = 4; i < argc; i++)
    {
        const char *c = argv[i];
        if (c[0] == 'w') wait_ms(atoi(c + 1));
        else if (c[0] == 'l') { mf_load_roll(atoi(c + 1), 36); wait_ms(100); }
        else if (c[0] == 'n') { mf_wind(); wait_ms(100); }
        else if (c[0] == 's') { mf_half(1); wait_ms(100); mf_shutter(); mf_half(0); wait_ms(600); }
        else if (c[0] == 'h') mf_half(c[1] == '1');
    }
    int r[6];
    char film[64];
    mf_roll_info(r, film, sizeof(film));
    printf("roll: loaded %d, %d/%d, %s (#%d); shots %d\n", r[0], r[1], r[2], film, r[5], shots);
    return 0;
}
