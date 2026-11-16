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
#include "fos_gfx.h"
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
static char abs_card[600];                  /* full path ending in '/' for Java/Kotlin File */
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
static volatile int half_press;
static int shutter_req, wind_req, shot_done_req, shot_pending;
static volatile int battery = 100;
static volatile int cr2_pending;            /* frames shot whose CR2 the app is still writing */

void mf_set_pending(int n) { cr2_pending = n < 0 ? 0 : n; }
void mf_half(int down) { half_press = down; }
void mf_shutter(void) { pthread_mutex_lock(&in_lock); shutter_req = 1; pthread_mutex_unlock(&in_lock); }
void mf_wind(void) { pthread_mutex_lock(&in_lock); wind_req = 1; pthread_mutex_unlock(&in_lock); }
void mf_shot_done(int ok, int dng)
{
    pthread_mutex_lock(&in_lock);
    shot_done_req = !ok ? -1 : dng ? 2 : 1;
    pthread_mutex_unlock(&in_lock);
}
void mf_battery(int percent) { battery = percent < 0 ? 0 : percent > 100 ? 100 : percent; }

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
int  fos_port_abort() { return 0; }
void fos_port_sleep(int ms) { sleep_ms(ms); }

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

void mf_roll_info(int out[6], char *film, int film_size)
{
    /* read from another thread: a snapshot good enough for a label */
    struct fos_roll r = roll;
    int known = r.loaded && r.film >= 0 && r.film < fos_nfilms;
    out[0] = r.loaded; out[1] = r.shot; out[2] = r.exposures;
    out[3] = need_wind;
    out[4] = known ? fos_films[r.film].box_iso : 0;
    out[5] = r.number;
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
    if (name && name_size > 0)
        snprintf(name, (size_t) name_size, "%s|%s|%s", f->name ? f->name : "", f->key ? f->key : "", f->desc ? f->desc : "");
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

void mf_switch_film(int film, int single_mode)
{
    if (film < 0 || film >= fos_nfilms) return;
    if (!single_mode)
    {
        if (!roll.loaded) load_roll(film, 36);
        return;
    }
    if (roll.loaded && roll.film == film && roll.shot < 64) return;
    if (roll.loaded) rewind_roll();
    load_roll(film, 64);
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
            const char *sub = (dcim_dir[0] == '.' && dcim_dir[1] == '/') ? dcim_dir + 2 : dcim_dir;
            snprintf(jpeg, sizeof(jpeg), "%s%s/%s.JPG", abs_card, sub, name);
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
static int dk_push = 0;
static int dk_target = 0;
static int dk_failed = 0;
static char dk_dcim[160] = "";
static char dk_err[128] = "";

const char *mf_dev_error(void) { return dk_err; }

void mf_dev_close(void)
{
    if (dk_dev) { fos_dev_free(dk_dev); dk_dev = 0; }
    fos_dev_release();
    dk_nframes = 0;
    dk_fi = 0;
    dk_done = 0;
    dk_target = 0;
}

int mf_dev_open(int roll_no, int size, int push_stops, int target_frame)
{
    mf_dev_close();
    dk_failed = 0;
    dk_err[0] = 0;
    char cache[64];
    fos_lab_cache_dir(cache, sizeof(cache));
    fos_dev_cache(cache);

    struct fos_lab_roll rolls[32];
    int nrolls = fos_lab_scan(rolls, 32);
    int film = -1;
    for (int i = 0; i < nrolls; i++)
        if (rolls[i].number == roll_no) { film = rolls[i].film; break; }
    if (film < 0 || film >= fos_nfilms) return -1;

    dk_roll = roll_no;
    dk_film = film;
    dk_size = fos_clamp(size, 0, FOS_SIZES - 1);
    dk_push = fos_clamp(push_stops, -2, 2);
    dk_target = target_frame;
    dk_nframes = fos_lab_frames(roll_no, dk_frames, 64);
    if (dk_nframes <= 0) return 0;

    fos_lab_prepare(roll_no);
    const char *first = 0;
    for (int i = 0; i < dk_nframes; i++)
        if (dk_frames[i].when[0] && (!first || strcmp(dk_frames[i].when, first) < 0)) first = dk_frames[i].when;
    if (fos_lab_dcim_dir(dk_dcim, sizeof(dk_dcim), roll_no, film, first) < 0) dk_dcim[0] = 0;

    /* wait if a CR2/DNG is still being written on the card */
    for (int w = 0; cr2_pending > 0 && w < 10000; w += 100) sleep_ms(100);
    return dk_target > 0 ? 1 : dk_nframes;
}

int mf_dev_step(int budget_ms, int out[3])
{
    out[0] = 0; out[1] = dk_done; out[2] = 0;
    if (!dk_dev)
    {
        while (dk_fi < dk_nframes)
        {
            const struct fos_lab_frame *f = &dk_frames[dk_fi++];
            if (dk_target > 0 && f->no != dk_target) continue;
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
            if (dk_target == 0 && stat(full_prn, &st1) == 0 && (!jpeg[0] || f->missing || stat(full_jpg, &st2) == 0))
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
            if (dk_dev && dk_push != 0) fos_dev_push(dk_dev, (float) dk_push);
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
            int all_failed = dk_failed > 0 && dk_failed >= dk_done;
            mf_dev_close();
            post_event(MF_EV_DEVELOPING, 0, 0);
            return all_failed ? -1 : 0;
        }
    }

    int prog = fos_dev_step(dk_dev, budget_ms);
    int doing = fos_dev_doing(dk_dev);
    out[0] = dk_frames[dk_fi - 1].no;
    out[1] = dk_done;
    out[2] = fos_clamp(prog, 0, 1000);
    if (doing == FOS_DEV_DONE || doing == FOS_DEV_FAILED)
    {
        if (doing == FOS_DEV_FAILED)
        {
            const char *why = fos_dev_error(dk_dev);
            snprintf(dk_err, sizeof(dk_err), "%s", (why && why[0]) ? why : "Development failed");
            logf_("darkroom frame %d failed: %s", out[0], dk_err);
            dk_failed++;
        }
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

/* ---------------------------------------------------------------- camera loop */

static void shutter(void)
{
    if (shot_pending || !roll.loaded || FINISHED || need_wind) return;
    shot_pending = file_no + 1;
    post_event(MF_EV_CAPTURE, shot_pending, 0);
}

void mf_run(void)
{
    for (;;)
    {
        int fire = 0, wind = 0, done = 0;
        pthread_mutex_lock(&in_lock);
        fire = shutter_req; shutter_req = 0;
        wind = wind_req; wind_req = 0;
        done = shot_done_req; shot_done_req = 0;
        pthread_mutex_unlock(&in_lock);

        if (done && shot_pending)
        {
            if (done > 0) shot_end(done == 2);
            shot_pending = 0;
        }
        if (wind && need_wind)
        {
            need_wind = 0;
            save_state();
            post_event(MF_EV_WIND, 0, 0);
        }
        if (fire) shutter();
        sleep_ms(30);
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
    mkdirs(card_dir);
    if (chdir(card_dir)) { logf_("can't go into the card %s: %s", card_dir, strerror(errno)); return -1; }
    snprintf(abs_card, sizeof(abs_card), "%s", card_dir);
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
    fos_gfx_init();
    sim_fonts();
    sim_films();
    char cache[64];
    fos_lab_cache_dir(cache, sizeof(cache));
    fos_dev_cache(cache);
    if (roll.film >= fos_nfilms) roll.loaded = 0;
    logf_("card %s, roll %d (%s), last frame IMG_%04d", card, roll.number, roll.loaded ? "loaded" : "none", file_no);
    return 0;
}
