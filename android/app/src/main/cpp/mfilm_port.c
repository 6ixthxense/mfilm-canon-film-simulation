/* M-Film on Android: the port (see mfilm_port.h).
 *
 * Made from ml-dev/filmos/web/web.c, which plays the camera around the film
 * OS in a browser: LiveView with the HUD over it, the roll and its log, the
 * keys that open the film OS. Here the film OS's loop runs on a thread of
 * its own, fos_port_sleep really sleeps, the card is a folder of the app's,
 * and the shutter writes a CR2 from the phone's camera (mkcr2_lib.c). The
 * roll, the settings and the file numbers are kept on the card, so a roll
 * survives the app being closed (the browser's is lost on reload). */

#include <errno.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <dirent.h>

#include "fos.h"
#include "fos_ui.h"
#include "fos_dev.h"
#include "mfilm_port.h"

void sim_fonts();
void sim_films();

static void default_log(const char *msg) { fprintf(stderr, "[mfilm] %s\n", msg); }
void (*mf_log_fn)(const char *msg) = default_log;

static void logf_(const char *fmt, ...)
{
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    mf_log_fn(buf);
}

/* ---------------------------------------------------------------- what the app sees */

static pthread_mutex_t scr_lock = PTHREAD_MUTEX_INITIALIZER;
static uint8_t screen[FOS_W * FOS_H];
static uint32_t pal[256];                   /* 0xRRGGBB */
static int scr_dirty = 1;

/* events for the app */
#define NEV 128
static pthread_mutex_t ev_lock = PTHREAD_MUTEX_INITIALIZER;
static int evq[NEV][3], ev_head, ev_tail;

static void post_event(int what, int a, int b)
{
    pthread_mutex_lock(&ev_lock);
    int next = (ev_tail + 1) % NEV;
    if (next != ev_head) { evq[ev_tail][0] = what; evq[ev_tail][1] = a; evq[ev_tail][2] = b; ev_tail = next; }
    pthread_mutex_unlock(&ev_lock);
}

int mf_poll_event(int *what, int *a, int *b)
{
    pthread_mutex_lock(&ev_lock);
    int have = ev_head != ev_tail;
    if (have)
    {
        *what = evq[ev_head][0]; *a = evq[ev_head][1]; *b = evq[ev_head][2];
        ev_head = (ev_head + 1) % NEV;
    }
    pthread_mutex_unlock(&ev_lock);
    return have;
}

int mf_frame(uint32_t *out, int force)
{
    static uint32_t lut[256];
    pthread_mutex_lock(&scr_lock);
    if (!scr_dirty && !force) { pthread_mutex_unlock(&scr_lock); return 0; }
    scr_dirty = 0;
    for (int i = 0; i < 256; i++) lut[i] = i == 0 ? 0 : 0xFF000000u | (pal[i] & 0xFFFFFF);
    for (int i = 0; i < FOS_W * FOS_H; i++) out[i] = lut[screen[i]];
    pthread_mutex_unlock(&scr_lock);
    return 1;
}

static void present_rows(const uint8_t *rows, int pitch, int y0, int y1)
{
    pthread_mutex_lock(&scr_lock);
    for (int y = y0; y < y1; y++) memcpy(&screen[y * FOS_W], &rows[(y - y0) * pitch], FOS_W);
    scr_dirty = 1;
    pthread_mutex_unlock(&scr_lock);
}

static void palette_changed(void)
{
    pthread_mutex_lock(&scr_lock);
    scr_dirty = 1;
    pthread_mutex_unlock(&scr_lock);
}

/* ---------------------------------------------------------------- the camera's state */

static int settings[FS_COUNT] = {
    [FS_HUD] = 1, [FS_LEDS] = 1, [FS_FOCUS] = 1, [FS_WIND] = 0,
    [FS_APKEYS] = 1, [FS_LENS_WIDE] = 6, [FS_FSTOP] = 9,
    [FS_STARTUP] = 2, [FS_PLAYLOCK] = 1, [FS_BOOTANIM] = 1, [FS_DEBUG] = 0, [FS_MODE] = 0,
    [FS_FILM] = 0, [FS_EXP] = 2, [FS_SCR_FLIP] = -1, [FS_EXPSIM] = -1,
    [FS_HUD_STYLE] = 0, [FS_HUD_BATT] = 1, [FS_SIZE] = FOS_SIZE_L, [FS_STAMP] = 0, [FS_RECENT] = 0,
    [FS_FADE] = 1, [FS_CANON_OFF] = -1,
};
static struct fos_roll roll;
static int file_no = 100;                   /* the card's last IMG_nnnn */
static int need_wind;

static char card[512];                      /* ends in '/' */
static char dcim[600];                      /* card DCIM/100CANON */
static char state_path[600];

const char *mf_card(void) { return card; }

/* the roll and the settings, kept on the card */
#define STATE_MAGIC 0x4D46534Bu             /* MFSK */
#define STATE_VERSION 1
struct state_file
{
    uint32_t magic, version, nsettings, roll_size;
    int settings[FS_COUNT];
    struct fos_roll roll;
    int file_no, need_wind;
};

static void save_state(void)
{
    struct state_file s;
    memset(&s, 0, sizeof(s));
    s.magic = STATE_MAGIC; s.version = STATE_VERSION; s.nsettings = FS_COUNT; s.roll_size = sizeof(roll);
    memcpy(s.settings, settings, sizeof(settings));
    s.roll = roll; s.file_no = file_no; s.need_wind = need_wind;
    char tmp[620];
    snprintf(tmp, sizeof(tmp), "%s.new", state_path);
    FILE *f = fopen(tmp, "wb");
    if (!f) { logf_("can't save %s: %s", tmp, strerror(errno)); return; }
    int ok = fwrite(&s, sizeof(s), 1, f) == 1;
    ok = !fclose(f) && ok;
    if (ok) rename(tmp, state_path);
}

static int load_state(void)
{
    struct state_file s;
    FILE *f = fopen(state_path, "rb");
    if (!f) return 0;
    int ok = fread(&s, sizeof(s), 1, f) == 1;
    fclose(f);
    if (!ok || s.magic != STATE_MAGIC || s.version != STATE_VERSION || s.nsettings != FS_COUNT || s.roll_size != sizeof(roll))
        return 0;
    memcpy(settings, s.settings, sizeof(settings));
    roll = s.roll; file_no = s.file_no; need_wind = s.need_wind;
    return 1;
}

/* without a saved state: the numbers the card's files have got to */
static void scan_card(void)
{
    DIR *d = opendir(dcim);
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d)))
    {
        int n;
        if (sscanf(e->d_name, "IMG_%4d.", &n) == 1 && n > file_no) file_no = n;     /* .CR2 or .DNG */
        if (sscanf(e->d_name, "ROLL_%3d.TXT", &n) == 1 && n > roll.number) roll.number = n;
    }
    closedir(d);
}

/* ---------------------------------------------------------------- keys, commands */

static pthread_mutex_t in_lock = PTHREAD_MUTEX_INITIALIZER;
static int keyq[64], kq_head, kq_tail;
static volatile int ui_running, abort_req, half_press;
static int open_req = -1, shutter_req, shot_done_req, shot_pending;
static volatile int battery = 100;
static volatile int cr2_pending;            /* frames shot whose CR2 the app is still writing */

void mf_set_pending(int n) { cr2_pending = n < 0 ? 0 : n; }

void mf_key(int k)
{
    pthread_mutex_lock(&in_lock);
    int next = (kq_tail + 1) % 64;
    if (next != kq_head) { keyq[kq_tail] = k; kq_tail = next; }
    pthread_mutex_unlock(&in_lock);
}

static int key_pop(void)
{
    pthread_mutex_lock(&in_lock);
    int k = FK_NONE;
    if (kq_head != kq_tail) { k = keyq[kq_head]; kq_head = (kq_head + 1) % 64; }
    pthread_mutex_unlock(&in_lock);
    return k;
}

void mf_half(int down)
{
    half_press = down;
    if (down && ui_running) abort_req = 1;
}

void mf_shutter(void) { pthread_mutex_lock(&in_lock); shutter_req = 1; pthread_mutex_unlock(&in_lock); }
void mf_shot_done(int ok, int dng)
{
    pthread_mutex_lock(&in_lock);
    shot_done_req = !ok ? -1 : dng ? 2 : 1;
    pthread_mutex_unlock(&in_lock);
}
void mf_open(int screen_) { pthread_mutex_lock(&in_lock); if (!ui_running) open_req = screen_; pthread_mutex_unlock(&in_lock); }
void mf_battery(int percent) { battery = percent < 0 ? 0 : percent > 100 ? 100 : percent; }
int  mf_ui_running(void) { return ui_running; }

/* ---------------------------------------------------------------- the port */

static int now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int) ((long long) ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

static void sleep_ms(int ms)
{
    if (ms < 1) ms = 1;
    struct timespec ts = { ms / 1000, (long) (ms % 1000) * 1000000L };
    nanosleep(&ts, 0);
}

static void clock_str(char *buf, int size, int with_date)
{
    time_t t = time(0);
    struct tm tm;
    localtime_r(&t, &tm);
    strftime(buf, (size_t) size, with_date ? "%Y-%m-%d %H:%M" : "%H:%M", &tm);
}

int  fos_port_ms() { return now_ms(); }
int  fos_port_key() { return key_pop(); }
int  fos_port_abort() { return abort_req; }
void fos_port_sleep(int ms) { sleep_ms(ms); }

void fos_port_present(const uint8_t *rows, int pitch, int y0, int y1) { present_rows(rows, pitch, y0, y1); }

void fos_port_palette()
{
    pthread_mutex_lock(&scr_lock);
    for (int i = 0; i < 256; i++)
        pal[i] = i == 0 || i == 255 ? fos_pal[i] :
            settings[FS_SCR_STD] == 1 ? fos_pal[i] :
            fos_screen_rgb(fos_screen_yuv(i, 235, 0, settings[FS_SCR_SAT], settings[FS_SCR_WARM], settings[FS_SCR_BRIGHT]), 235);
    pthread_mutex_unlock(&scr_lock);
    palette_changed();
}

void fos_port_photo(const uint8_t (*p)[3], int n, int k)
{
    pthread_mutex_lock(&scr_lock);
    for (int i = 1; i < 255; i++)
    {
        const uint8_t *c = i <= n ? p[i - 1] : i >= FOS_FIXED_BASE && i < FOS_FIXED_BASE + FOS_FIXED_N ?
                           fos_fixed_rgb[i - FOS_FIXED_BASE] : (const uint8_t *) "\0\0\0";
        uint32_t rgb = (uint32_t) (c[0] * k >> 8) << 16 | (uint32_t) (c[1] * k >> 8) << 8 | (uint32_t) (c[2] * k >> 8);
        pal[i] = settings[FS_SCR_STD] == 1 ? rgb :
            fos_screen_rgb(fos_screen_yuv_rgb(rgb, 235, 0, settings[FS_SCR_SAT], settings[FS_SCR_WARM], settings[FS_SCR_BRIGHT]), 235);
    }
    pthread_mutex_unlock(&scr_lock);
    palette_changed();
}

void fos_port_log(const char *msg) { mf_log_fn(msg); }
int  fos_port_dev_cap() { return 0; }       /* a real clock: the darkroom keeps to its time budget */
void fos_port_stat(const struct fos_stat *s) { }

void *fos_port_alloc(int size) { return malloc((size_t) size); }
void fos_port_free(void *p) { free(p); }

void fos_port_roll(struct fos_roll *r) { *r = roll; r->need_wind = need_wind; }

/* the phone plays an EOS M: its CR2s are the EOS M's (mkcr2), and the
 * darkroom knows that camera best. sim/port_files.c's is renamed away */
const char *fos_port_model() { return "Canon EOS M"; }

int fos_port_get(int s)
{
    if (s == FS_SCR_FLIP || s == FS_EXPSIM || s == FS_CANON_OFF) return -1;    /* not on a phone */
    return s >= 0 && s < FS_COUNT ? settings[s] : 0;
}

void fos_port_set(int s, int v)
{
    if (s == FS_SCR_FLIP || s == FS_EXPSIM || s == FS_CANON_OFF) return;
    if (s >= 0 && s < FS_COUNT) settings[s] = v;
    if (s == FS_SCR_STD || s == FS_SCR_SAT || s == FS_SCR_WARM || s == FS_SCR_BRIGHT) fos_port_palette();
    save_state();
}

static void log_append(const char *line)
{
    char path[700];
    snprintf(path, sizeof(path), "%s/ROLL_%03d.TXT", dcim, roll.number);
    FILE *f = fopen(path, "ab");
    if (f) { fputs(line, f); fclose(f); }
}

static void load_roll(int film, int exposures)
{
    roll.loaded = 1;
    roll.number++;
    roll.film = film;
    roll.exposures = exposures;
    roll.shot = 0;
    roll.nrecent = 0;
    need_wind = 0;
    clock_str(roll.loaded_at, sizeof(roll.loaded_at), 1);
    char head[200];
    snprintf(head, sizeof(head), "# ML film mode roll log (filmroll module)\nroll = %03d\nfilm = %s\nexposures = %d\nloaded = %s:00\n\n",
             roll.number, fos_films[film].key, exposures, roll.loaded_at);
    log_append(head);
    save_state();
    post_event(MF_EV_LOADED, film, exposures);
}

static void rewind_roll(void)
{
    if (!roll.loaded) return;
    if (roll.shot < roll.exposures)
    {
        char line[96], when[20];
        clock_str(when, sizeof(when), 1);
        snprintf(line, sizeof(line), "\n# rewound %s after %d of %d frames\n", when, roll.shot, roll.exposures);
        log_append(line);
    }
    roll.loaded = 0;
    save_state();
    post_event(MF_EV_REWOUND, 0, 0);
}

void fos_port_action(int a, int x, int y)
{
    if (a == FA_LOAD) load_roll(x, fos_exposure_choices[y]);
    if (a == FA_REWIND) rewind_roll();
    if (a == FA_BUSY)
    {
        post_event(MF_EV_DEVELOPING, x, 0);
        /* developing starts: not before every frame shot is on the card as a CR2
         * (the app writes them in the background), as the page waits for its tables */
        for (int waited = 0; x && cr2_pending > 0 && waited < 180000; waited += 100) sleep_ms(100);
    }
    if (a == FA_DIM) post_event(MF_EV_DIM, x, 0);
    if (a == FA_CANON_MENU) post_event(MF_EV_CANON_MENU, 0, 0);
}

void fos_port_clock(char *buf, int size) { clock_str(buf, size, 0); }
int  fos_port_battery(int *exact) { if (exact) *exact = 1; return battery; }
int  fos_port_lens_manual() { return 1; }
const char *fos_port_lens_name() { return "Phone camera"; }
#ifndef MFILM_VERSION
#define MFILM_VERSION "android"
#endif
const char *fos_port_version() { return MFILM_VERSION; }

/* ---------------------------------------------------------------- the shutter */

#define FINISHED (roll.loaded && roll.shot >= roll.exposures)

static int leds_until, end_due;
static int next_fstop_x100 = 0;

void mf_set_shot_fstop(int fstop_x100) { next_fstop_x100 = fstop_x100; }

/* dng: the phone's own RAW (IMG_nnnn.DNG), or a CR2 made from its photo */
static void shot_end(int dng)
{
    file_no++;
    roll.shot++;
    char when[20], aperture[8], line[112];
    clock_str(when, sizeof(when), 1);
    if (next_fstop_x100 > 0)
    {
        if (next_fstop_x100 % 100 == 0)
            snprintf(aperture, sizeof(aperture), "%d", next_fstop_x100 / 100);
        else
            snprintf(aperture, sizeof(aperture), "%d.%d", next_fstop_x100 / 100, (next_fstop_x100 / 10) % 10);
    }
    else
    {
        fos_format_fstop(aperture, sizeof(aperture), settings[FS_FSTOP]);
    }
    snprintf(line, sizeof(line), "%d  IMG_%04d.%s  %s:00  f/%s\n", roll.shot, file_no, dng ? "DNG" : "CR2", when, aperture);
    log_append(line);

    memmove(&roll.recent[1], &roll.recent[0], sizeof(roll.recent[0]) * (FOS_RECENT - 1));
    roll.recent[0].no = roll.shot;
    clock_str(roll.recent[0].time, sizeof(roll.recent[0].time), 0);
    fos_copy(roll.recent[0].fstop, sizeof(roll.recent[0].fstop), aperture, 5);
    if (roll.nrecent < FOS_RECENT) roll.nrecent++;

    if (settings[FS_WIND]) need_wind = 1;
    leds_until = now_ms() + 1500;
    save_state();
    post_event(MF_EV_SHOT, roll.shot, roll.exposures);
    if (FINISHED)
    {
        end_due = now_ms() + 1200;
        post_event(MF_EV_FINISHED, 0, 0);
    }
}

void mf_roll_info(int out[5], char *film, int film_size)
{
    /* read from another thread: a snapshot good enough for a label */
    struct fos_roll r = roll;
    int known = r.loaded && r.film >= 0 && r.film < fos_nfilms;
    out[0] = r.loaded; out[1] = r.shot; out[2] = r.exposures;
    out[3] = need_wind;
    out[4] = known ? fos_films[r.film].box_iso : 0;
    if (film && film_size > 0)
        snprintf(film, (size_t) film_size, "%s", r.loaded && r.film >= 0 && r.film < fos_nfilms ? fos_films[r.film].name : "");
}

int mf_pending_rolls(void)
{
    struct fos_lab_roll rolls[32];
    int n = fos_lab_scan(rolls, 32);
    int count = 0;
    for (int i = 0; i < n; i++)
    {
        const struct fos_lab_roll *r = &rolls[i];
        int developed = r->sheet && r->developed >= r->shot && r->jpegs >= r->developed;
        int finished_in_cam = r->in_camera && !settings[FS_WIND] && r->shot >= r->exposures;
        if (!developed && (!r->in_camera || finished_in_cam) && r->shot > 0)
            count++;
    }
    return count;
}

int mf_film_count(void) { return fos_nfilms; }

void mf_film_info(int idx, int out[6], char *name, int name_size)
{
    if (idx < 0 || idx >= fos_nfilms)
    {
        memset(out, 0, sizeof(int) * 6);
        if (name && name_size > 0) name[0] = 0;
        return;
    }
    const struct fos_film *f = &fos_films[idx];
    out[0] = f->box_iso;
    out[1] = f->group;
    out[2] = f->grain;
    out[3] = f->punch;
    out[4] = f->colour;
    out[5] = f->warmth;
    if (name && name_size > 0) snprintf(name, (size_t) name_size, "%s", f->name);
}

void mf_draw_canister(int film, int exposures, int leader, uint32_t *argb, int w, int h)
{
    if (w <= 0 || h <= 0) return;
    uint8_t *px = calloc((size_t) w * h, 1);
    if (!px) return;
    struct fos_canvas cv;
    fos_canvas_init(&cv, px, w, w, h);
    struct fos_can *c = fos_can_new(film, exposures);
    if (c)
    {
        struct fos_can_pose p = {
            .cx8 = (leader > 0 ? w * 38 / 100 : w / 2) << 8,
            .floor_y = h - 14,
            .scale = 256,
            .rot = 0,
            .light = 256,
            .leader = leader,
            .reflect = 0,
            .fast = 0,
        };
        fos_can_draw(&cv, c, &p);
        fos_can_free(c);
    }
    for (int i = 0; i < w * h; i++)
    {
        uint8_t ix = px[i];
        argb[i] = ix == 0 ? 0 : (fos_pal[ix] | 0xFF000000u);
    }
    free(px);
}

void mf_load_roll(int film, int exposures)
{
    if (film >= 0 && film < fos_nfilms && !roll.loaded) load_roll(film, exposures);
}

void mf_rewind_roll(void)
{
    rewind_roll();
}

int mf_roll_frames(char *out, int out_size)
{
    if (!out || out_size <= 0) return 0;
    out[0] = 0;
    if (!roll.loaded || roll.shot <= 0) return 0;
    struct fos_lab_frame frames[64];
    int n = fos_lab_frames(roll.number, frames, 64);
    if (n > roll.shot) n = roll.shot;
    int pos = 0;
    for (int i = 0; i < n && pos < out_size - 1; i++)
    {
        int wrote = snprintf(out + pos, (size_t) (out_size - pos), "%d|%s|%s\n",
                             frames[i].no, frames[i].when, frames[i].fstop);
        if (wrote < 0 || pos + wrote >= out_size) break;
        pos += wrote;
    }
    return n;
}

int mf_get_setting(int id)
{
    return (id >= 0 && id < FS_COUNT) ? settings[id] : 0;
}

void mf_set_setting(int id, int val)
{
    if (id >= 0 && id < FS_COUNT)
    {
        settings[id] = val;
        if (id == FS_WIND && !val) need_wind = 0;
        save_state();
    }
}

int mf_lab_rolls(char *out, int out_size)
{
    if (!out || out_size <= 0) return 0;
    out[0] = 0;
    struct fos_lab_roll rolls[32];
    int n = fos_lab_scan(rolls, 32);
    int pos = 0;
    for (int i = 0; i < n && pos < out_size - 1; i++)
    {
        const struct fos_lab_roll *r = &rolls[i];
        const char *fname = (r->film >= 0 && r->film < fos_nfilms) ? fos_films[r->film].name : r->gone;
        int wrote = snprintf(out + pos, (size_t) (out_size - pos), "%d|%d|%s|%d|%d|%d|%d|%d|%d|%s|%s\n",
                             r->number, r->film, fname ? fname : "", r->exposures, r->shot,
                             r->developed, r->sheet, r->jpegs, r->in_camera, r->loaded, r->last);
        if (wrote < 0 || pos + wrote >= out_size) break;
        pos += wrote;
    }
    return n;
}

int mf_lab_roll_prints(int roll_no, char *out, int out_size)
{
    if (!out || out_size <= 0) return 0;
    out[0] = 0;
    struct fos_lab_roll rolls[32];
    int nrolls = fos_lab_scan(rolls, 32);
    int film_idx = -1;
    for (int i = 0; i < nrolls; i++)
        if (rolls[i].number == roll_no) { film_idx = rolls[i].film; break; }

    struct fos_lab_frame frames[64];
    int n = fos_lab_frames(roll_no, frames, 64);
    const char *first = 0;
    for (int i = 0; i < n; i++)
        if (frames[i].when[0] && (!first || strcmp(frames[i].when, first) < 0)) first = frames[i].when;

    char dcim_dir[160] = "";
    if (film_idx >= 0) fos_lab_dcim_dir(dcim_dir, sizeof(dcim_dir), roll_no, film_idx, first);

    int pos = 0;
    for (int i = 0; i < n && pos < out_size - 1; i++)
    {
        const struct fos_lab_frame *f = &frames[i];
        char jpeg[256] = "";
        if (dcim_dir[0] && f->cr2[0])
        {
            const char *base = strrchr(f->cr2, '/');
            char name[9];
            fos_copy(name, sizeof(name), base ? base + 1 : f->cr2, 8);
            snprintf(jpeg, sizeof(jpeg), "%s%s/%s.JPG", card, dcim_dir, name);
            struct stat st;
            if (stat(jpeg, &st) != 0) jpeg[0] = 0;
        }
        int wrote = snprintf(out + pos, (size_t) (out_size - pos), "%d|%s|%s|%s\n",
                             f->no, f->when, f->fstop, jpeg);
        if (wrote < 0 || pos + wrote >= out_size) break;
        pos += wrote;
    }
    return n;
}

int mf_lab_delete(int roll_no, int whole)
{
    struct fos_lab_frame frames[FOS_MAX_FRAMES];
    return fos_lab_delete(roll_no, whole, frames);
}

/* ---------------------------------------------------------------- darkroom session */

static struct fos_dev *dk_dev = 0;
static struct fos_lab_frame dk_frames[64];
static int dk_nframes = 0;
static int dk_fi = 0;
static int dk_done = 0;
static int dk_roll = 0;
static int dk_film = 0;
static int dk_size = 0;
static char dk_dcim[160] = "";

void mf_dev_close(void)
{
    if (dk_dev) { fos_dev_free(dk_dev); dk_dev = 0; }
    fos_dev_release();
    dk_nframes = 0;
    dk_fi = 0;
    dk_done = 0;
}

int mf_dev_open(int roll_no, int size)
{
    mf_dev_close();
    struct fos_lab_roll rolls[32];
    int nrolls = fos_lab_scan(rolls, 32);
    int film = -1;
    for (int i = 0; i < nrolls; i++)
        if (rolls[i].number == roll_no) { film = rolls[i].film; break; }
    if (film < 0 || film >= fos_nfilms) return -1;

    dk_roll = roll_no;
    dk_film = film;
    dk_size = fos_clamp(size, 0, FOS_SIZES - 1);
    dk_nframes = fos_lab_frames(roll_no, dk_frames, 64);
    if (dk_nframes <= 0) return 0;

    fos_lab_prepare(roll_no);
    const char *first = 0;
    for (int i = 0; i < dk_nframes; i++)
        if (dk_frames[i].when[0] && (!first || strcmp(dk_frames[i].when, first) < 0)) first = dk_frames[i].when;
    if (fos_lab_dcim_dir(dk_dcim, sizeof(dk_dcim), roll_no, film, first) < 0) dk_dcim[0] = 0;

    /* wait if a CR2 is still being written on the card */
    for (int w = 0; cr2_pending > 0 && w < 10000; w += 100) sleep_ms(100);
    return dk_nframes;
}

int mf_dev_step(int budget_ms, int out[3])
{
    out[0] = 0; out[1] = dk_done; out[2] = 0;
    if (!dk_dev)
    {
        while (dk_fi < dk_nframes)
        {
            const struct fos_lab_frame *f = &dk_frames[dk_fi++];
            char path[64], jpeg[220] = "";
            fos_lab_print_path(path, sizeof(path), dk_roll, f->no);
            if (dk_dcim[0])
            {
                const char *base = strrchr(f->cr2, '/');
                char name[9];
                fos_copy(name, sizeof(name), base ? base + 1 : f->cr2, 8);
                snprintf(jpeg, sizeof(jpeg), "%s/%s.JPG", dk_dcim, name);
            }
            struct stat st1, st2;
            char full_prn[300], full_jpg[300];
            snprintf(full_prn, sizeof(full_prn), "%s%s", card, path);
            snprintf(full_jpg, sizeof(full_jpg), "%s%s", card, jpeg);
            if (stat(full_prn, &st1) == 0 && (!jpeg[0] || f->missing || stat(full_jpg, &st2) == 0))
            {
                dk_done++;
                continue;
            }
            if (f->missing) { dk_done++; continue; }
            dk_dev = fos_dev_start(f->cr2, path, dk_film, f->no, f->when, f->fstop);
            if (dk_dev && jpeg[0]) fos_dev_jpeg(dk_dev, jpeg);
            if (dk_dev && dk_size > FOS_SIZE_S)
            {
                char zoom[64];
                fos_lab_zoom_path(zoom, sizeof(zoom), dk_roll, f->no);
                fos_dev_big(dk_dev, dk_size, zoom);
            }
            if (dk_dev && settings[FS_STAMP]) fos_dev_stamp(dk_dev, 1);
            if (dk_dev) break;
            dk_done++;
        }
        if (!dk_dev)
        {
            /* contact sheet */
            char paths[64][64];
            const char *pp[64];
            for (int i = 0; i < dk_nframes && i < 64; i++)
            {
                fos_lab_print_path(paths[i], sizeof(paths[i]), dk_roll, dk_frames[i].no);
                pp[i] = paths[i];
            }
            char sheet_path[64];
            fos_lab_print_path(sheet_path, sizeof(sheet_path), dk_roll, 0);
            fos_dev_sheet(sheet_path, dk_film, pp, dk_nframes);
            out[1] = dk_done;
            mf_dev_close();
            post_event(MF_EV_DEVELOPING, 0, 0);
            return 0;
        }
    }

    int prog = fos_dev_step(dk_dev, budget_ms);
    int doing = fos_dev_doing(dk_dev);
    out[0] = dk_frames[dk_fi - 1].no;
    out[1] = dk_done;
    out[2] = fos_clamp(prog, 0, 1000);
    if (doing == FOS_DEV_DONE || doing == FOS_DEV_FAILED)
    {
        fos_dev_free(dk_dev);
        dk_dev = 0;
        dk_done++;
        out[1] = dk_done;
        out[2] = 1000;
    }
    return 1;
}

int mf_dev_preview_bmp(uint32_t *argb, int w, int h)
{
    if (!dk_dev || w <= 0 || h <= 0) return 0;
    uint8_t *lum = malloc((size_t) w * h);
    if (!lum) return 0;
    int ok = fos_dev_preview(dk_dev, lum, w, h);
    if (ok)
    {
        for (int i = 0; i < w * h; i++)
        {
            /* safelight red-amber paper preview */
            uint32_t v = lum[i];
            uint32_t r = v;
            uint32_t g = v * 32 / 255;
            uint32_t b = v * 16 / 255;
            argb[i] = 0xFF000000u | (r << 16) | (g << 8) | b;
        }
    }
    free(lum);
    return ok;
}

/* ---------------------------------------------------------------- LiveView */

static struct fos_hud_palette hud_pal;

static void hud_palette(void)
{
    pthread_mutex_lock(&scr_lock);
    memset(pal, 0, sizeof(pal));
    hud_pal.clear = 0;
    hud_pal.black = 1;  pal[1] = 0x000000;
    hud_pal.white = 2;  pal[2] = 0xFFFFFF;
    hud_pal.red = 3;    pal[3] = 0xFF2A1A;
    hud_pal.dark_red = 4; pal[4] = 0x5A0E08;
    for (int i = 0; i <= 100; i++)
    {
        int v = i * 255 / 100;
        hud_pal.gray[i] = (uint8_t) (5 + i);
        pal[5 + i] = (uint32_t) (v << 16 | v << 8 | v);
    }
    pthread_mutex_unlock(&scr_lock);
    palette_changed();
}

static int meter_wobble;
static uint8_t hud_buf[FOS_W * FOS_H];

static void hud_draw(int now)
{
    memset(hud_buf, hud_pal.clear, sizeof(hud_buf));
    if (settings[FS_HUD] && roll.loaded)
    {
        struct fos_hud h;
        memset(&h, 0, sizeof(h));
        h.lit = !settings[FS_LEDS] || half_press || now < leds_until;
        h.blink = (now / 250) & 1;
        h.fstop = settings[FS_FSTOP];
        h.widest = settings[FS_LENS_WIDE];
        if (h.lit)
        {
            h.meter = 1;
            h.want = fos_clamp(h.fstop + meter_wobble, 0, fos_nfstops - 1);
        }
        static const char *speeds[] = { "60", "125", "250", "500" };
        snprintf(h.shutter, sizeof(h.shutter), "%s", speeds[fos_clamp(settings[FS_FSTOP] / 4, 0, 3)]);
        h.frame = roll.shot + 1;
        h.exposures = roll.exposures;
        h.finished = FINISHED;
        h.need_wind = need_wind;
        h.focus_screen = settings[FS_FOCUS];
        h.film = fos_films[fos_clamp(roll.film, 0, fos_nfilms - 1)].name;
        h.iso = fos_films[fos_clamp(roll.film, 0, fos_nfilms - 1)].box_iso;
        h.style = fos_clamp(settings[FS_HUD_STYLE], 0, FOS_HUD_STYLES - 1);
        h.flash = now < leds_until;
        h.batt = settings[FS_HUD_BATT] ? fos_port_battery(&h.batt_exact) : -1;
        fos_hud_draw(hud_buf, FOS_W, &h, &hud_pal);
    }
    present_rows(hud_buf, FOS_W, 0, FOS_H);
}

static void open_ui(int open)
{
    ui_running = 1;
    abort_req = 0;
    post_event(MF_EV_MODE, 1, 0);
    fos_ui_run(open);
    ui_running = 0;
    abort_req = 0;
    pthread_mutex_lock(&in_lock);
    kq_head = kq_tail = 0;
    pthread_mutex_unlock(&in_lock);
    hud_palette();
    save_state();
    post_event(MF_EV_MODE, 0, 0);
}

/* a key on the plain LiveView screen, as web.c's lv_key */
static void lv_key(int k)
{
    if (k & FK_RELEASE) return;
    if (k == FK_HOME || k == FK_MENU) { open_req = FOS_OPEN_HOME; return; }
    if (k == FK_PLAY) { open_req = FOS_OPEN_PRINTS; return; }
    if (!roll.loaded)
    {
        if (k == FK_SET) open_req = FOS_OPEN_SHELF;
        return;
    }
    if (!FINISHED && settings[FS_APKEYS] == 1 && (k == FK_UP || k == FK_DOWN || k == FK_WHEEL_L || k == FK_WHEEL_R))
    {
        int dir = k == FK_UP || k == FK_WHEEL_R ? 1 : -1;
        settings[FS_FSTOP] = fos_clamp(settings[FS_FSTOP] + dir, settings[FS_LENS_WIDE], fos_nfstops - 1);
        leds_until = now_ms() + 2000;
        return;
    }
    if (FINISHED)
    {
        if (k == FK_SET) open_req = FOS_OPEN_END;
        return;
    }
    if (need_wind && k == FK_SET)
    {
        need_wind = 0;
        leds_until = now_ms() + 1500;
        save_state();
        post_event(MF_EV_WIND, 0, 0);
    }
}

/* the shutter, fully pressed: a frame if the roll can take one */
static void shutter(void)
{
    if (shot_pending || ui_running || !roll.loaded || FINISHED || need_wind) return;
    shot_pending = file_no + 1;
    post_event(MF_EV_CAPTURE, shot_pending, 0);
}

void mf_run(void)
{
    for (;;)
    {
        int now = now_ms();
        int o = -1, fire = 0, done = 0;
        pthread_mutex_lock(&in_lock);
        o = open_req; open_req = -1;
        fire = shutter_req; shutter_req = 0;
        done = shot_done_req; shot_done_req = 0;
        pthread_mutex_unlock(&in_lock);

        if (done && shot_pending)
        {
            if (done > 0) shot_end(done == 2);
            shot_pending = 0;
        }
        if (o >= 0 && !shot_pending)
        {
            open_ui(o);
            continue;
        }
        if (fire) shutter();
        int k;
        while ((k = key_pop()) != FK_NONE)
        {
            lv_key(k);
            if (open_req >= 0) break;
        }
        if ((now / 700) % 4 == 0) meter_wobble = (now / 2800) % 3 - 1;
        hud_draw(now);
        sleep_ms(50);
    }
}

/* ---------------------------------------------------------------- start */

static int file_ok(const char *path)
{
    struct stat st;
    return !stat(path, &st) && S_ISREG(st.st_mode) && st.st_size > 0;
}

static void mkdirs(const char *path)
{
    char p[700];
    snprintf(p, sizeof(p), "%s", path);
    for (char *s = p + 1; *s; s++)
        if (*s == '/') { *s = 0; mkdir(p, 0777); *s = '/'; }
    mkdir(p, 0777);
}

int mf_init(const char *files_dir, const char *card_dir)
{
    static char font[600], films[600];
    snprintf(font, sizeof(font), "%s/%s", files_dir, FOS_FONT_FILE);
    snprintf(films, sizeof(films), "%s/%s", files_dir, FOS_FILM_FILE);
    if (!file_ok(font)) { logf_("no fonts at %s", font); return -1; }
    /* The film OS keeps a card's paths in 64 or 128 bytes, as a camera's card
     * is "B:/". The app's folder is 70 characters before DCIM/..., so the
     * logs and RAWs were cut short and the darkroom found no frames: the card
     * is the working directory, and its paths start "./". */
    mkdirs(card_dir);
    if (chdir(card_dir)) { logf_("can't go into the card %s: %s", card_dir, strerror(errno)); return -1; }
    snprintf(card, sizeof(card), "./");
    snprintf(dcim, sizeof(dcim), "%sDCIM/100CANON", card);
    snprintf(state_path, sizeof(state_path), "%sML/MFSTATE.BIN", card);
    mkdirs(dcim);
    char ml[600];
    snprintf(ml, sizeof(ml), "%sML/DARKROOM", card);
    mkdirs(ml);

    setenv("FOS_FONT", font, 1);
    setenv("FOS_FILMS", films, 1);
    setenv("FOS_CARD", card, 1);

    if (!load_state()) scan_card();
    fos_ui_init();
    sim_fonts();
    sim_films();
    if (roll.film >= fos_nfilms) roll.loaded = 0;
    hud_palette();
    logf_("card %s, roll %d (%s), last frame IMG_%04d", card, roll.number, roll.loaded ? "loaded" : "none", file_no);
    return 0;
}
