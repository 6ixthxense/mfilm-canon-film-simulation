/* fos_dng: a phone's DNG (Android's DngCreator) into a mosaic in memory,
 * for fos_dev_start_raw. Only the kind DngCreator writes: one CFA image,
 * uncompressed, 16 bits a sample, in strips; either byte order. The lens's
 * fall-off (OpcodeList2's GainMaps, from the camera's lens shading map) is
 * taken out as the mosaic is read, since a phone's RAW still has it.
 * Not in the camera's build: Android and devtest (FOS_DEV_RAWIN). */

#include "fos.h"
#include "fos_dev.h"
#include <math.h>
#include <string.h>

#ifdef FOS_DEV_RAWIN

struct dng
{
    void *f;
    int be;                     /* "MM": big-endian */
    int size;
};

static int rd(struct dng *g, int at, void *buf, int n)
{
    return at >= 0 && fos_port_seek(g->f, at) >= 0 && fos_port_read(g->f, buf, n) == n;
}

static unsigned u16(const struct dng *g, const uint8_t *p) { return g->be ? (unsigned) (p[0] << 8 | p[1]) : (unsigned) (p[0] | p[1] << 8); }
static uint32_t u32(const struct dng *g, const uint8_t *p)
{
    return g->be ? (uint32_t) p[0] << 24 | (uint32_t) p[1] << 16 | (uint32_t) p[2] << 8 | p[3]
                 : (uint32_t) p[3] << 24 | (uint32_t) p[2] << 16 | (uint32_t) p[1] << 8 | p[0];
}
/* the opcode lists are big-endian whatever the file is */
static uint32_t b32(const uint8_t *p) { return (uint32_t) p[0] << 24 | (uint32_t) p[1] << 16 | (uint32_t) p[2] << 8 | p[3]; }
static double bdouble(const uint8_t *p)
{
    uint64_t v = (uint64_t) b32(p) << 32 | b32(p + 4);
    double d;
    memcpy(&d, &v, 8);
    return d;
}
static float bfloat(const uint8_t *p)
{
    uint32_t v = b32(p);
    float f;
    memcpy(&f, &v, 4);
    return f;
}

/* a tag of the IFD at ifd: its type, count, and where its value is (in the
 * entry itself when it fits 4 bytes). 0 if it isn't there */
struct tag { int type; uint32_t count; int at; };
static const int TSIZE[] = { 0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8 };

static int find(struct dng *g, int ifd, int id, struct tag *t)
{
    uint8_t n2[2], e[12];
    if (!rd(g, ifd, n2, 2)) return 0;
    int n = (int) u16(g, n2);
    for (int i = 0; i < n && i < 1000; i++)
    {
        int at = ifd + 2 + 12 * i;
        if (!rd(g, at, e, 12)) return 0;
        if ((int) u16(g, e) != id) continue;
        t->type = (int) u16(g, e + 2);
        t->count = u32(g, e + 4);
        int sz = t->type > 0 && t->type <= 12 ? TSIZE[t->type] : 1;
        t->at = (long) sz * t->count <= 4 ? at + 8 : (int) u32(g, e + 8);
        return 1;
    }
    return 0;
}

/* value i of a tag, as a number (SHORT, LONG, RATIONAL, SRATIONAL, BYTE) */
static double num(struct dng *g, const struct tag *t, int i)
{
    uint8_t b[8];
    switch (t->type)
    {
        case 1: case 7: return rd(g, t->at + i, b, 1) ? b[0] : 0;
        case 3: return rd(g, t->at + 2 * i, b, 2) ? u16(g, b) : 0;
        case 4: return rd(g, t->at + 4 * i, b, 4) ? u32(g, b) : 0;
        case 5: if (!rd(g, t->at + 8 * i, b, 8)) return 0;
                return u32(g, b + 4) ? (double) u32(g, b) / u32(g, b + 4) : 0;
        case 10: if (!rd(g, t->at + 8 * i, b, 8)) return 0;
                 return (int32_t) u32(g, b + 4) ? (double) (int32_t) u32(g, b) / (int32_t) u32(g, b + 4) : 0;
    }
    return 0;
}

static double tag_num(struct dng *g, int ifd, int id, int i, double dflt)
{
    struct tag t;
    return find(g, ifd, id, &t) && (uint32_t) i < t.count ? num(g, &t, i) : dflt;
}

/* the light a DNG illuminant number stands for, K */
static int illuminant_k(int e)
{
    switch (e)
    {
        case 3: return 2850;  case 17: return 2856; case 24: return 3200;
        case 2: case 14: return 4200; case 12: case 19: return 6500; case 13: return 4600; case 15: return 3450;
        case 18: return 4874; case 23: return 5003; case 1: case 4: case 9: case 20: return 5500;
        case 10: case 21: return 6504; case 11: case 22: return 7500;
    }
    return 6504;
}

/* the gain maps of OpcodeList2 (DNG 1.3, opcode 9) laid over the mosaic */
#define MAXMAPS 4
struct gainmap
{
    int top, left, bottom, right, rpitch, cpitch, nv, nh;
    double sv, sh, ov, oh;
    float *g;
};

static int read_maps(struct dng *g, int ifd, struct gainmap *maps)
{
    struct tag t;
    if (!find(g, ifd, 0xC740, &t) || t.count < 4 || t.count > (16 << 20)) return 0;
    uint8_t *ops = fos_port_alloc((int) t.count);
    if (!ops) return 0;
    int n = 0;
    if (rd(g, t.at, ops, (int) t.count))
    {
        uint32_t count = b32(ops), p = 4;
        for (uint32_t i = 0; i < count && p + 16 <= t.count; i++)
        {
            uint32_t id = b32(ops + p), len = b32(ops + p + 12);
            const uint8_t *q = ops + p + 16;
            p += 16 + len;
            if (p > t.count) break;
            if (id != 9 || len < 76 || n >= MAXMAPS) continue;
            struct gainmap *m = &maps[n];
            m->top = (int) b32(q); m->left = (int) b32(q + 4); m->bottom = (int) b32(q + 8); m->right = (int) b32(q + 12);
            m->rpitch = (int) b32(q + 24); m->cpitch = (int) b32(q + 28);
            m->nv = (int) b32(q + 32); m->nh = (int) b32(q + 36);
            m->sv = bdouble(q + 40); m->sh = bdouble(q + 48); m->ov = bdouble(q + 56); m->oh = bdouble(q + 64);
            uint32_t planes = b32(q + 72);
            if (m->nv < 1 || m->nh < 1 || m->nv > 512 || m->nh > 512 || m->rpitch < 1 || m->cpitch < 1
                || len < 76 + 4u * m->nv * m->nh * planes || m->sv <= 0 || m->sh <= 0) continue;
            m->g = fos_port_alloc(4 * m->nv * m->nh);
            if (!m->g) continue;
            /* plane 0 of it: a Bayer mosaic is one plane */
            for (int k = 0; k < m->nv * m->nh; k++) m->g[k] = bfloat(q + 76 + 4 * k * (int) planes);
            n++;
        }
    }
    fos_port_free(ops);
    return n;
}

/* the gain of map m at (row, col) of a w x h image, bilinear between its points */
static float gain_at(const struct gainmap *m, int row, int col, int w, int h)
{
    double v = ((double) row / h - m->ov) / m->sv, u = ((double) col / w - m->oh) / m->sh;
    if (v < 0) v = 0;
    if (u < 0) u = 0;
    if (v > m->nv - 1) v = m->nv - 1;
    if (u > m->nh - 1) u = m->nh - 1;
    int i = (int) v, j = (int) u;
    if (i > m->nv - 2) i = m->nv > 1 ? m->nv - 2 : 0;
    if (j > m->nh - 2) j = m->nh > 1 ? m->nh - 2 : 0;
    float fv = (float) (v - i), fu = (float) (u - j);
    const float *p = m->g + i * m->nh + j;
    float a = p[0], b = m->nh > 1 ? p[1] : a;
    float c = m->nv > 1 ? p[m->nh] : a, d = m->nv > 1 && m->nh > 1 ? p[m->nh + 1] : c;
    return (a * (1 - fu) + b * fu) * (1 - fv) + (c * (1 - fu) + d * fu) * fv;
}

/* the CFA image's IFD: IFD0, or one of its SubIFDs when IFD0 is a thumbnail */
static int raw_ifd(struct dng *g, int ifd0)
{
    if ((int) tag_num(g, ifd0, 0x0106, 0, 0) == 32803) return ifd0;
    struct tag t;
    if (!find(g, ifd0, 0x014A, &t)) return -1;
    for (uint32_t i = 0; i < t.count && i < 8; i++)
    {
        int sub = (int) num(g, &t, (int) i);
        if ((int) tag_num(g, sub, 0x0106, 0, 0) == 32803) return sub;
    }
    return -1;
}

int fos_dng_load(const char *path, int kelvin, struct fos_raw *r, uint16_t **mosaic, char *err, int err_size)
{
    memset(r, 0, sizeof(*r));
    *mosaic = 0;
    struct dng g = { 0 };
    struct gainmap maps[MAXMAPS];
    memset(maps, 0, sizeof(maps));
    int nmaps = 0, ok = 0;
    uint16_t *px = 0;
    const char *why = "not a DNG";
    g.f = fos_port_open(path, 0);
    if (!g.f) { why = "can't open the DNG"; goto out; }
    uint8_t h[8];
    if (!rd(&g, 0, h, 8)) goto out;
    if (h[0] == 'M' && h[1] == 'M') g.be = 1;
    else if (!(h[0] == 'I' && h[1] == 'I')) goto out;
    if (u16(&g, h + 2) != 42) goto out;
    int ifd0 = (int) u32(&g, h + 4), ifd = raw_ifd(&g, ifd0);
    if (ifd < 0) { why = "no CFA image in the DNG"; goto out; }

    int w = (int) tag_num(&g, ifd, 0x0100, 0, 0), hh = (int) tag_num(&g, ifd, 0x0101, 0, 0);
    int bits = (int) tag_num(&g, ifd, 0x0102, 0, 0), comp = (int) tag_num(&g, ifd, 0x0103, 0, 1);
    if (w < 64 || hh < 64 || w > 16384 || hh > 16384) { why = "odd DNG size"; goto out; }
    if (bits != 16 || comp != 1) { why = "compressed or packed DNG: not one this reads"; goto out; }

    /* the colours of the top left 2 x 2 (0 red, 1 green, 2 blue) */
    struct tag cfa;
    uint8_t pat[4] = { 0, 1, 1, 2 };
    if (find(&g, ifd, 0x828E, &cfa) && cfa.count == 4) rd(&g, cfa.at, pat, 4);
    if (pat[0] == 0 && pat[3] == 2) r->cfa = FOS_CFA_RGGB;
    else if (pat[1] == 0 && pat[2] == 2) r->cfa = FOS_CFA_GRBG;
    else if (pat[2] == 0 && pat[1] == 2) r->cfa = FOS_CFA_GBRG;
    else if (pat[3] == 0 && pat[0] == 2) r->cfa = FOS_CFA_BGGR;
    else { why = "not a Bayer DNG"; goto out; }

    /* black: one level, or one per place in the 2 x 2 (their mean) */
    struct tag bl;
    double black = 0;
    if (find(&g, ifd, 0xC61A, &bl) && bl.count)
    {
        int n = bl.count > 4 ? 4 : (int) bl.count;
        for (int i = 0; i < n; i++) black += num(&g, &bl, i);
        black /= n;
    }
    double white = tag_num(&g, ifd, 0xC61D, 0, 65535);
    if (white <= black + 16) { why = "the DNG's white level is below its black"; goto out; }

    /* the colour matrix whose light is nearest the film's (in mired) */
    int k1 = illuminant_k((int) tag_num(&g, ifd0, 0xC65A, 0, 21)), k2 = illuminant_k((int) tag_num(&g, ifd0, 0xC65B, 0, 21));
    struct tag cm1, cm2;
    int have1 = find(&g, ifd0, 0xC621, &cm1) && cm1.count == 9, have2 = find(&g, ifd0, 0xC622, &cm2) && cm2.count == 9;
    if (!have1 && !have2) { why = "no colour matrix in the DNG"; goto out; }
    float want = 1e6f / (float) (kelvin > 1000 ? kelvin : 5500);
    int second = have2 && (!have1 || fabsf(1e6f / k2 - want) < fabsf(1e6f / k1 - want));
    struct tag *cm = second ? &cm2 : &cm1;
    double m[3][3], cal[3][3] = { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } };
    for (int i = 0; i < 9; i++) m[i / 3][i % 3] = num(&g, cm, i);
    /* the unit's own calibration on top, as the DNG spec has it: CC * CM */
    struct tag cc;
    if (find(&g, ifd0, second ? 0xC624 : 0xC623, &cc) && cc.count == 9)
        for (int i = 0; i < 9; i++) cal[i / 3][i % 3] = num(&g, &cc, i);
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++)
        {
            double s = 0;
            for (int k = 0; k < 3; k++) s += cal[i][k] * m[k][j];
            r->xyz_cam[i][j] = (float) s;
        }

    /* as shot: the camera's neutral, so its balance is 1 / that */
    struct tag an;
    if (find(&g, ifd0, 0xC628, &an) && an.count == 3)
        for (int c = 0; c < 3; c++)
        {
            double n = num(&g, &an, c);
            r->wb[c] = n > 0 ? (float) (1 / n) : 0;
        }

    int orient = (int) tag_num(&g, ifd0, 0x0112, 0, 1);
    nmaps = read_maps(&g, ifd, maps);
    if (!nmaps && ifd != ifd0) nmaps = read_maps(&g, ifd0, maps);

    /* the samples, strip by strip */
    struct tag so, sc;
    if (!find(&g, ifd, 0x0111, &so) || !find(&g, ifd, 0x0117, &sc) || so.count != sc.count || !so.count)
    { why = "no strips in the DNG"; goto out; }
    int rps = (int) tag_num(&g, ifd, 0x0116, 0, hh);
    if (rps < 1) rps = hh;
    px = fos_port_alloc(w * hh * 2);
    if (!px) { why = "out of memory for the DNG"; goto out; }
    int row = 0;
    for (uint32_t s = 0; s < so.count && row < hh; s++)
    {
        int rows = hh - row < rps ? hh - row : rps;
        int at = (int) num(&g, &so, (int) s);
        if ((int) num(&g, &sc, (int) s) < rows * w * 2 || !rd(&g, at, px + (long) row * w, rows * w * 2))
        { why = "the DNG is cut short"; goto out; }
        row += rows;
    }
    if (row < hh) { why = "the DNG is cut short"; goto out; }
    /* the samples' byte order, and the lens's fall-off taken out of them
     * (they are linear above black: a gain is a gain) */
    int hi = (int) white;
    for (int y = 0; y < hh; y++)
    {
        uint16_t *p = px + (long) y * w;
        if (g.be)
            for (int x = 0; x < w; x++) p[x] = (uint16_t) (p[x] >> 8 | p[x] << 8);
        for (int k = 0; k < nmaps; k++)
        {
            const struct gainmap *mp = &maps[k];
            if (y < mp->top || y >= mp->bottom || (y - mp->top) % mp->rpitch) continue;
            for (int x = mp->left; x < mp->right && x < w; x += mp->cpitch)
            {
                /* ponytail: a gain looked up per sample; a row of them per map if it shows in the time */
                float v = ((float) p[x] - (float) black) * gain_at(mp, y, x, w, hh) + (float) black;
                p[x] = (uint16_t) (v < 0 ? 0 : v > hi ? hi : v + 0.5f);
            }
        }
    }

    r->px = px;
    r->w = r->stride = w;
    r->h = hh;
    r->black = (float) black;
    r->white = (int) white;
    for (r->bits = 8; r->bits < 16 && (1 << r->bits) <= r->white; r->bits++) {}
    r->orient = orient;
    /* the middle 3:2 of the sensor, on even lines */
    r->crop_w = w & ~1;
    r->crop_h = (w * 2 / 3) & ~1;
    if (r->crop_h > hh) { r->crop_h = hh & ~1; r->crop_w = (hh * 3 / 2) & ~1; }
    r->crop_l = ((w - r->crop_w) / 2) & ~1;
    r->crop_t = ((hh - r->crop_h) / 2) & ~1;
    *mosaic = px;
    px = 0;
    ok = 1;
    {
        char msg[200];
        snprintf(msg, sizeof(msg), "dng: %dx%d, cfa %d, black %.1f, white %d, matrix for %d K, wb %.3f %.3f %.3f, %d gain maps",
                 w, hh, r->cfa, black, r->white, second ? k2 : k1, (double) r->wb[0], (double) r->wb[1], (double) r->wb[2], nmaps);
        fos_port_log(msg);
    }
out:
    if (!ok && err) snprintf(err, (size_t) err_size, "%s", why);
    for (int k = 0; k < MAXMAPS; k++) fos_port_free(maps[k].g);
    fos_port_free(px);
    if (g.f) fos_port_close(g.f);
    return ok;
}

#endif
