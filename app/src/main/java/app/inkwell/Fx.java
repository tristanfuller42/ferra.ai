package app.inkwell;

/**
 * Pixel effects on greyscale ARGB arrays (unpremultiplied, R=G=B).
 * Pure Java so they can run on a background thread and be tested off-device.
 */
final class Fx {
    private Fx() { }

    static int grey(int px) { return px & 255; }
    static int alpha(int px) { return px >>> 24; }
    static int pix(int a, int g) { return (a << 24) | (g * 0x010101); }
    static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    // ---------------- Tone (lookup table) ----------------

    static void lut(int[] s, int[] d, int[] lut) {
        for (int i = 0; i < s.length; i++) d[i] = (s[i] & 0xFF000000) | (lut[s[i] & 255] * 0x010101);
    }

    static int[] brightnessContrast(float bright, float contrast) {
        int[] l = new int[256];
        float f = contrast >= 0 ? 1f / Math.max(0.02f, 1f - contrast) : 1f + contrast;
        for (int i = 0; i < 256; i++) l[i] = clamp(Math.round((i - 128) * f + 128 + bright * 255));
        return l;
    }

    static int[] invert() { int[] l = new int[256]; for (int i = 0; i < 256; i++) l[i] = 255 - i; return l; }

    static int[] threshold(int level) { int[] l = new int[256]; for (int i = 0; i < 256; i++) l[i] = i >= level ? 255 : 0; return l; }

    static int[] posterize(int levels) {
        int[] l = new int[256];
        int n = Math.max(2, levels) - 1;
        for (int i = 0; i < 256; i++) l[i] = Math.round(Math.round(i * n / 255f) * 255f / n);
        return l;
    }

    /** Monotone curve through points (x,y in 0..1, sorted by x) -> lookup table. */
    static int[] curve(float[] xs, float[] ys) {
        int n = xs.length;
        float[] m = new float[n];
        float[] dl = new float[Math.max(1, n - 1)];
        for (int i = 0; i < n - 1; i++) dl[i] = (ys[i + 1] - ys[i]) / Math.max(1e-4f, xs[i + 1] - xs[i]);
        for (int i = 0; i < n; i++) {
            if (i == 0) m[i] = dl[0];
            else if (i == n - 1) m[i] = dl[n - 2];
            else m[i] = dl[i - 1] * dl[i] <= 0 ? 0 : (dl[i - 1] + dl[i]) / 2;
        }
        int[] l = new int[256];
        for (int k = 0; k < 256; k++) {
            float x = k / 255f;
            int i = 0;
            while (i < n - 2 && x > xs[i + 1]) i++;
            float h = Math.max(1e-4f, xs[i + 1] - xs[i]);
            float t = Math.max(0, Math.min(1, (x - xs[i]) / h));
            float t2 = t * t, t3 = t2 * t;
            float y = (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i]
                    + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1];
            l[k] = clamp(Math.round(y * 255));
        }
        return l;
    }

    // ---------------- Blurs ----------------

    /** Splits into premultiplied grey and alpha planes. */
    private static void split(int[] s, int[] g, int[] a) {
        for (int i = 0; i < s.length; i++) { int al = s[i] >>> 24; a[i] = al; g[i] = (s[i] & 255) * al; }
    }

    private static void join(int[] g, int[] a, int[] d, int scale) {
        for (int i = 0; i < d.length; i++) {
            int al = clamp(a[i] / scale);
            int gr = al == 0 ? 0 : clamp(g[i] / scale / al);
            d[i] = pix(al, gr);
        }
    }

    /** One box pass along rows (dx=1) or columns (dx=w), edge-clamped. Values are scaled by (2r+1). */
    private static void box(int[] s, int[] d, int w, int h, int r, boolean rows) {
        int len = rows ? w : h, lines = rows ? h : w;
        int step = rows ? 1 : w;
        int div = 2 * r + 1;
        for (int ln = 0; ln < lines; ln++) {
            int base = rows ? ln * w : ln;
            long sum = 0;
            for (int k = -r; k <= r; k++) sum += s[base + Math.max(0, Math.min(len - 1, k)) * step];
            for (int x = 0; x < len; x++) {
                d[base + x * step] = (int) (sum / div);
                int add = Math.min(len - 1, x + r + 1), sub = Math.max(0, x - r);
                sum += s[base + add * step] - s[base + sub * step];
            }
        }
    }

    /** Gaussian-like blur (3 box passes each way). radius ~ sigma in pixels. */
    static void gaussian(int[] s, int[] d, int w, int h, float radius) {
        int r = Math.max(1, Math.round(radius));
        int n = w * h;
        int[] g = new int[n], a = new int[n], t = new int[n];
        split(s, g, a);
        for (int pass = 0; pass < 3; pass++) {
            box(g, t, w, h, r, true); box(t, g, w, h, r, false);
            box(a, t, w, h, r, true); box(t, a, w, h, r, false);
        }
        join(g, a, d, 1);
    }

    static void motion(int[] s, int[] d, int w, int h, float length, float angleDeg) {
        int n = Math.max(2, Math.min(40, Math.round(length / 2)));
        double an = Math.toRadians(angleDeg);
        float dx = (float) Math.cos(an) * length, dy = (float) Math.sin(an) * length;
        int[] ox = new int[n], oy = new int[n];
        for (int k = 0; k < n; k++) {
            float t = k / (float) (n - 1) - 0.5f;
            ox[k] = Math.round(dx * t); oy[k] = Math.round(dy * t);
        }
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                long sg = 0, sa = 0;
                for (int k = 0; k < n; k++) {
                    int xx = Math.max(0, Math.min(w - 1, x + ox[k])), yy = Math.max(0, Math.min(h - 1, y + oy[k]));
                    int p = s[yy * w + xx], al = p >>> 24;
                    sa += al; sg += (p & 255) * al;
                }
                int al = (int) (sa / n);
                d[y * w + x] = pix(al, sa == 0 ? 0 : clamp((int) (sg / sa)));
            }
    }

    /** Zoom ("perspective") blur towards (cx, cy). amount 0..1. */
    static void zoom(int[] s, int[] d, int w, int h, float cx, float cy, float amount) {
        int n = 16;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                long sg = 0, sa = 0;
                for (int k = 0; k < n; k++) {
                    float f = 1 - amount * 0.35f * k / (n - 1);
                    int xx = Math.max(0, Math.min(w - 1, Math.round(cx + (x - cx) * f)));
                    int yy = Math.max(0, Math.min(h - 1, Math.round(cy + (y - cy) * f)));
                    int p = s[yy * w + xx], al = p >>> 24;
                    sa += al; sg += (p & 255) * al;
                }
                d[y * w + x] = pix((int) (sa / n), sa == 0 ? 0 : clamp((int) (sg / sa)));
            }
    }

    static void sharpen(int[] s, int[] d, int w, int h, float amount) {
        gaussian(s, d, w, h, 1.5f);
        for (int i = 0; i < s.length; i++) {
            int g = s[i] & 255, b = d[i] & 255;
            d[i] = (s[i] & 0xFF000000) | (clamp(Math.round(g + amount * 2 * (g - b))) * 0x010101);
        }
    }

    /** Glow from light areas. */
    static void bloom(int[] s, int[] d, int w, int h, float amount, float radius) {
        int[] bright = new int[s.length];
        for (int i = 0; i < s.length; i++) {
            int g = s[i] & 255, a = s[i] >>> 24;
            int v = clamp((g - 110) * 255 / 145);
            bright[i] = pix(a * v / 255, 255);
        }
        gaussian(bright, bright, w, h, radius);
        for (int i = 0; i < s.length; i++) {
            int g = s[i] & 255, a = s[i] >>> 24;
            int glow = Math.round((bright[i] >>> 24) * amount * 2.2f);
            int ng = 255 - (255 - g) * (255 - clamp(glow)) / 255;
            d[i] = pix(Math.max(a, clamp(glow)), ng);
        }
    }

    // ---------------- Texture / stylise ----------------

    private static int hash(int x) {
        x ^= x >>> 16; x *= 0x7feb352d; x ^= x >>> 15; x *= 0x846ca68b; x ^= x >>> 16;
        return x;
    }

    static void noise(int[] s, int[] d, float amount, int seed) {
        for (int i = 0; i < s.length; i++) {
            int r = (hash(i * 31 + seed) & 255) - 128;
            d[i] = (s[i] & 0xFF000000) | (clamp((s[i] & 255) + Math.round(r * amount * 0.9f)) * 0x010101);
        }
    }

    static void glitch(int[] s, int[] d, int w, int h, float amount, int seed) {
        System.arraycopy(s, 0, d, 0, s.length);
        if (amount <= 0) return;
        java.util.Random r = new java.util.Random(seed);
        int bands = 3 + Math.round(amount * 40);
        for (int b = 0; b < bands; b++) {
            int y0 = r.nextInt(h), bh = 1 + r.nextInt(Math.max(2, h / 25));
            int shift = Math.round((r.nextFloat() - 0.5f) * amount * w / 3);
            for (int y = y0; y < Math.min(h, y0 + bh); y++)
                for (int x = 0; x < w; x++) d[y * w + x] = s[y * w + Math.floorMod(x - shift, w)];
        }
        int blocks = Math.round(amount * 25);
        for (int b = 0; b < blocks; b++) { // displaced rectangles
            int bw = 8 + r.nextInt(Math.max(9, w / 8)), bh = 4 + r.nextInt(Math.max(5, h / 30));
            int x0 = r.nextInt(w), y0 = r.nextInt(h), sx = x0 + r.nextInt(41) - 20, sy = y0 + r.nextInt(21) - 10;
            for (int y = 0; y < bh; y++)
                for (int x = 0; x < bw; x++) {
                    int dx = x0 + x, dy = y0 + y, xx = sx + x, yy = sy + y;
                    if (dx < w && dy < h && xx >= 0 && yy >= 0 && xx < w && yy < h) d[dy * w + dx] = s[yy * w + xx];
                }
        }
    }

    /** Classic 45° halftone dots. cell = dot spacing in pixels. Dot area matches the tone. */
    static void halftone(int[] s, int[] d, int w, int h, float cell) {
        final float k = 0.70710678f;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float u = (x + y) * k, v = (x - y) * k;
                int iu0 = (int) Math.floor(u / cell), iv0 = (int) Math.floor(v / cell);
                float cov = 0;
                for (int du = -1; du <= 1; du++)       // neighbouring dots can reach into this cell
                    for (int dv = -1; dv <= 1; dv++) {
                        float cu = (iu0 + du + 0.5f) * cell, cv = (iv0 + dv + 0.5f) * cell;
                        int sx = Math.round((cu + cv) * k), sy = Math.round((cu - cv) * k);
                        if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
                        int p = s[sy * w + sx];
                        float dark = (255 - (p & 255)) * (p >>> 24) / 65025f;
                        if (dark <= 0) continue;
                        float rad = cell * 0.5642f * (float) Math.sqrt(dark) * 1.08f;
                        float c = rad - (float) Math.hypot(u - cu, v - cv) + 0.5f;
                        if (c > cov) cov = Math.min(1, c);
                    }
                int a0 = s[y * w + x] >>> 24;
                d[y * w + x] = pix(Math.max(a0, Math.round(cov * 255)), Math.round(255 * (1 - cov)));
            }
    }

    // ---------------- Selection helpers ----------------

    /** Blends result back toward the original by the selection mask (null = everything). */
    static void mixSel(int[] src, int[] out, byte[] sel) {
        if (sel == null) return;
        for (int i = 0; i < src.length; i++) {
            int m = sel[i] & 255;
            if (m == 255) continue;
            if (m == 0) { out[i] = src[i]; continue; }
            int a0 = src[i] >>> 24, g0 = src[i] & 255, a1 = out[i] >>> 24, g1 = out[i] & 255;
            out[i] = pix(a0 + (a1 - a0) * m / 255, g0 + (g1 - g0) * m / 255);
        }
    }

    /**
     * Flood fill from (sx,sy) over pixels similar to the seed. Writes 255 into out where filled.
     * Similarity compares alpha and premultiplied grey; thr is 0..255.
     */
    static int flood(int[] px, int w, int h, int sx, int sy, int thr, byte[] out) {
        int seed = px[sy * w + sx];
        int a0 = seed >>> 24, ga0 = (seed & 255) * a0 / 255 + (255 - a0);
        int[] stack = new int[Math.max(64, w * 4)];
        int sp = 0, count = 0;
        stack[sp++] = sy * w + sx;
        while (sp > 0) {
            int i = stack[--sp];
            int y = i / w, x = i % w;
            int lx = x;
            while (lx >= 0 && out[y * w + lx] == 0 && similar(px[y * w + lx], a0, ga0, thr)) lx--;
            lx++;
            boolean up = false, down = false;
            for (int xx = lx; xx < w && out[y * w + xx] == 0 && similar(px[y * w + xx], a0, ga0, thr); xx++) {
                out[y * w + xx] = (byte) 255;
                count++;
                if (y > 0) {
                    boolean ok = out[(y - 1) * w + xx] == 0 && similar(px[(y - 1) * w + xx], a0, ga0, thr);
                    if (ok && !up) { if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, sp * 2); stack[sp++] = (y - 1) * w + xx; }
                    up = ok;
                }
                if (y < h - 1) {
                    boolean ok = out[(y + 1) * w + xx] == 0 && similar(px[(y + 1) * w + xx], a0, ga0, thr);
                    if (ok && !down) { if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, sp * 2); stack[sp++] = (y + 1) * w + xx; }
                    down = ok;
                }
            }
        }
        return count;
    }

    private static boolean similar(int p, int a0, int ga0, int thr) {
        int a = p >>> 24;
        int ga = (p & 255) * a / 255 + (255 - a); // grey as seen on white paper
        return Math.abs(a - a0) <= thr * 2 && Math.abs(ga - ga0) <= thr;
    }

    /** How different a pixel is from the seed (0..255), the same measure flood() compares with its threshold. */
    static int diff(int p, int a0, int ga0) {
        int a = p >>> 24, ga = (p & 255) * a / 255 + (255 - a);
        return Math.max(Math.abs(a - a0) / 2, Math.abs(ga - ga0));
    }

    /**
     * Soft fill edge: grows the mask into neighbouring pixels (line edges) with partial coverage that rises with the
     * threshold, so every threshold step changes the edge a little instead of nothing-then-everything.
     */
    static void softGrow(int[] px, byte[] m, int w, int h, int seed, int thr, int rings) {
        int a0 = seed >>> 24, ga0 = (seed & 255) * a0 / 255 + (255 - a0);
        for (int r = 0; r < rings; r++) {
            byte[] c = m.clone();
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if (c[i] != 0) continue;
                    int nb = Math.max(Math.max(x > 0 ? c[i - 1] & 255 : 0, x < w - 1 ? c[i + 1] & 255 : 0),
                            Math.max(y > 0 ? c[i - w] & 255 : 0, y < h - 1 ? c[i + w] & 255 : 0));
                    if (nb == 0) continue;
                    int cov = (thr + 96 - diff(px[i], a0, ga0)) * 255 / 96; // fades out over 96 grey levels past the threshold
                    cov = Math.min(nb, Math.max(0, Math.min(255, cov)));
                    if (cov > 0) m[i] = (byte) cov;
                }
        }
    }


    /** Softens a mask edge. */
    static void feather(byte[] m, int w, int h, float radius) {
        int n = w * h;
        int[] a = new int[n], t = new int[n];
        for (int i = 0; i < n; i++) a[i] = m[i] & 255;
        int r = Math.max(1, Math.round(radius / 1.7f));
        for (int pass = 0; pass < 3; pass++) { box(a, t, w, h, r, true); box(t, a, w, h, r, false); }
        for (int i = 0; i < n; i++) m[i] = (byte) clamp(a[i]);
    }
}
