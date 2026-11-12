package app.inkwell;

/**
 * Pixel effects on ARGB arrays (unpremultiplied colour).
 * Pure Java so they can run on a background thread and be tested off-device.
 */
final class Fx {
    private Fx() { }

    static int alpha(int px) { return px >>> 24; }
    static int red(int px) { return (px >> 16) & 255; }
    static int green(int px) { return (px >> 8) & 255; }
    static int blue(int px) { return px & 255; }
    /** Perceived brightness 0..255. */
    static int lum(int px) { return (red(px) * 299 + green(px) * 587 + blue(px) * 114) / 1000; }
    static int pix(int a, int g) { return (a << 24) | (g * 0x010101); }
    static int pix(int a, int r, int g, int b) { return (a << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b); }
    static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    // ---------------- Tone (lookup table, applied to red, green and blue) ----------------

    static void lut(int[] s, int[] d, int[] lut) {
        for (int i = 0; i < s.length; i++) {
            int p = s[i];
            d[i] = (p & 0xFF000000) | (lut[(p >> 16) & 255] << 16) | (lut[(p >> 8) & 255] << 8) | lut[p & 255];
        }
    }

    /** Curves with separate tables: all channels (c), then red, green and blue. */
    static void lut4(int[] s, int[] d, int[] c, int[] r, int[] g, int[] b) {
        for (int i = 0; i < s.length; i++) {
            int p = s[i];
            d[i] = (p & 0xFF000000) | (r[c[(p >> 16) & 255]] << 16) | (g[c[(p >> 8) & 255]] << 8) | b[c[p & 255]];
        }
    }

    /** Threshold on brightness: black or white. */
    static void thresholdLum(int[] s, int[] d, int level) {
        for (int i = 0; i < s.length; i++) d[i] = (s[i] & 0xFF000000) | (lum(s[i]) >= level ? 0xFFFFFF : 0);
    }

    static int[] brightnessContrast(float bright, float contrast) {
        int[] l = new int[256];
        float f = contrast >= 0 ? 1f / Math.max(0.02f, 1f - contrast) : 1f + contrast;
        for (int i = 0; i < 256; i++) l[i] = clamp(Math.round((i - 128) * f + 128 + bright * 255));
        return l;
    }

    static int[] invert() { int[] l = new int[256]; for (int i = 0; i < 256; i++) l[i] = 255 - i; return l; }

    static int[] threshold(int level) { int[] l = new int[256]; for (int i = 0; i < 256; i++) l[i] = i >= level ? 255 : 0; return l; }

    static int[] posterize(int levels) { return posterize(levels, 0); }

    /** Posterize with a threshold shift (-0.5..0.5): moves where each step to the next level happens (darker or lighter). */
    static int[] posterize(int levels, float shift) {
        int[] l = new int[256];
        int n = Math.max(2, levels) - 1;
        for (int i = 0; i < 256; i++) {
            int band = Math.max(0, Math.min(n, Math.round(i * n / 255f - shift)));
            l[i] = Math.round(band * 255f / n);
        }
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

    // ---------------- Colour ----------------

    /** Hue shift (-180..180°), saturation and brightness (-1..1), like Procreate's Hue, Saturation, Brightness. */
    static void hsb(int[] s, int[] d, float hueDeg, float sat, float bright) {
        float[] hsv = new float[3];
        for (int i = 0; i < s.length; i++) {
            int p = s[i];
            android.graphics.Color.RGBToHSV(red(p), green(p), blue(p), hsv);
            hsv[0] = ((hsv[0] + hueDeg) % 360 + 360) % 360;
            hsv[1] = Math.max(0, Math.min(1, sat >= 0 ? hsv[1] + (1 - hsv[1]) * sat * hsv[1] * 2 : hsv[1] * (1 + sat)));
            hsv[2] = Math.max(0, Math.min(1, bright >= 0 ? hsv[2] + (1 - hsv[2]) * bright : hsv[2] * (1 + bright)));
            d[i] = (p & 0xFF000000) | (android.graphics.Color.HSVToColor(hsv) & 0xFFFFFF);
        }
    }

    /** Colour balance: cyan↔red, magenta↔green, yellow↔blue (-1..1), strongest in the mid-tones. */
    static void colorBalance(int[] s, int[] d, float cr, float mg, float yb) {
        for (int i = 0; i < s.length; i++) {
            int p = s[i];
            float l = lum(p) / 255f, mid = 1 - Math.abs(l - 0.5f) * 1.4f; // most effect in the mid-tones
            float k = 90 * Math.max(0.2f, mid);
            d[i] = pix(p >>> 24, Math.round(red(p) + cr * k), Math.round(green(p) + mg * k), Math.round(blue(p) + yb * k));
        }
    }

    /** Gradient map through several colours, evenly spaced from dark to light. */
    static void gradientMapStops(int[] s, int[] d, int[] stops) {
        int n = stops.length - 1;
        int[] lut = new int[256];
        for (int l = 0; l < 256; l++) {
            float t = l / 255f * n;
            int k = Math.min(n - 1, (int) t);
            float f = t - k;
            int a = stops[k], b = stops[k + 1];
            lut[l] = ((Math.round(red(a) + (red(b) - red(a)) * f)) << 16) | ((Math.round(green(a) + (green(b) - green(a)) * f)) << 8) | Math.round(blue(a) + (blue(b) - blue(a)) * f);
        }
        for (int i = 0; i < s.length; i++) d[i] = (s[i] & 0xFF000000) | lut[lum(s[i])];
    }

    /**
     * Recolor: pixels close to the target colour take the new colour's hue and saturation, keeping their own shading
     * (light and dark), with a soft edge. tolerance 0..1.
     */
    static void recolor(int[] s, int[] d, int target, int to, float tolerance) {
        float[] th = new float[3], nh = new float[3], ph = new float[3];
        android.graphics.Color.colorToHSV(target | 0xFF000000, th);
        android.graphics.Color.colorToHSV(to | 0xFF000000, nh);
        int tol = Math.round(tolerance * 255);
        float vScale = th[2] > 0.02f ? nh[2] / th[2] : 1;
        for (int i = 0; i < s.length; i++) {
            int p = s[i];
            if ((p >>> 24) == 0) { d[i] = p; continue; }
            int df = Math.max(Math.abs(red(p) - red(target)), Math.max(Math.abs(green(p) - green(target)), Math.abs(blue(p) - blue(target))));
            float k = df <= tol ? 1 : df >= tol + 40 ? 0 : 1 - (df - tol) / 40f; // soft edge
            if (k <= 0) { d[i] = p; continue; }
            android.graphics.Color.colorToHSV(p | 0xFF000000, ph);
            ph[0] = nh[0];
            ph[1] = Math.min(1, nh[1] * (th[1] > 0.02f ? ph[1] / th[1] : 1));
            ph[2] = Math.min(1, ph[2] * vScale);
            int q = android.graphics.Color.HSVToColor(ph);
            d[i] = pix(p >>> 24, Math.round(red(p) + (red(q) - red(p)) * k), Math.round(green(p) + (green(q) - green(p)) * k), Math.round(blue(p) + (blue(q) - blue(p)) * k));
        }
    }

    /** Gradient map: dark→light mapped from colour a to colour b. */
    static void gradientMap(int[] s, int[] d, int ca, int cb) {
        for (int i = 0; i < s.length; i++) {
            int p = s[i], l = lum(p);
            d[i] = pix(p >>> 24, red(ca) + (red(cb) - red(ca)) * l / 255, green(ca) + (green(cb) - green(ca)) * l / 255,
                    blue(ca) + (blue(cb) - blue(ca)) * l / 255);
        }
    }

    // ---------------- Blurs ----------------

    /** Splits into premultiplied colour planes and alpha. */
    private static void split(int[] s, int[] r, int[] g, int[] b, int[] a) {
        for (int i = 0; i < s.length; i++) {
            int p = s[i], al = p >>> 24;
            a[i] = al; r[i] = red(p) * al; g[i] = green(p) * al; b[i] = blue(p) * al;
        }
    }

    private static void join(int[] r, int[] g, int[] b, int[] a, int[] d) {
        for (int i = 0; i < d.length; i++) {
            int al = clamp(a[i]);
            d[i] = al == 0 ? 0 : pix(al, r[i] / al, g[i] / al, b[i] / al);
        }
    }

    /** One box pass along rows (dx=1) or columns (dx=w), edge-clamped. */
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

    private static void blur3(int[] plane, int[] t, int w, int h, int r) {
        for (int pass = 0; pass < 3; pass++) { box(plane, t, w, h, r, true); box(t, plane, w, h, r, false); }
    }

    /** Gaussian-like blur (3 box passes each way). radius ~ sigma in pixels. */
    static void gaussian(int[] s, int[] d, int w, int h, float radius) {
        int r = Math.max(1, Math.round(radius));
        int n = w * h;
        int[] pr = new int[n], pg = new int[n], pb = new int[n], a = new int[n], t = new int[n];
        split(s, pr, pg, pb, a);
        blur3(pr, t, w, h, r); blur3(pg, t, w, h, r); blur3(pb, t, w, h, r); blur3(a, t, w, h, r);
        join(pr, pg, pb, a, d);
    }

    /** Average of samples (premultiplied), used by motion and zoom blur. */
    private static int average(long sr, long sg, long sb, long sa, int n) {
        if (sa == 0) return 0;
        return pix((int) (sa / n), (int) (sr / sa), (int) (sg / sa), (int) (sb / sa));
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
                long sr = 0, sg = 0, sb = 0, sa = 0;
                for (int k = 0; k < n; k++) {
                    int xx = Math.max(0, Math.min(w - 1, x + ox[k])), yy = Math.max(0, Math.min(h - 1, y + oy[k]));
                    int p = s[yy * w + xx], al = p >>> 24;
                    sa += al; sr += red(p) * al; sg += green(p) * al; sb += blue(p) * al;
                }
                d[y * w + x] = average(sr, sg, sb, sa, n);
            }
    }

    /** Zoom ("perspective") blur towards (cx, cy). amount 0..1. */
    static void zoom(int[] s, int[] d, int w, int h, float cx, float cy, float amount) {
        int n = 16;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                long sr = 0, sg = 0, sb = 0, sa = 0;
                for (int k = 0; k < n; k++) {
                    float f = 1 - amount * 0.35f * k / (n - 1);
                    int xx = Math.max(0, Math.min(w - 1, Math.round(cx + (x - cx) * f)));
                    int yy = Math.max(0, Math.min(h - 1, Math.round(cy + (y - cy) * f)));
                    int p = s[yy * w + xx], al = p >>> 24;
                    sa += al; sr += red(p) * al; sg += green(p) * al; sb += blue(p) * al;
                }
                d[y * w + x] = average(sr, sg, sb, sa, n);
            }
    }

    static void sharpen(int[] s, int[] d, int w, int h, float amount) {
        gaussian(s, d, w, h, 1.5f);
        float k = amount * 2;
        for (int i = 0; i < s.length; i++) {
            int p = s[i], q = d[i];
            d[i] = pix(p >>> 24, Math.round(red(p) + k * (red(p) - red(q))), Math.round(green(p) + k * (green(p) - green(q))),
                    Math.round(blue(p) + k * (blue(p) - blue(q))));
        }
    }

    /** Glow from light areas. */
    static void bloom(int[] s, int[] d, int w, int h, float amount, float radius) {
        int[] bright = new int[s.length];
        for (int i = 0; i < s.length; i++) {
            int p = s[i], a = p >>> 24;
            int v = clamp((lum(p) - 110) * 255 / 145);
            bright[i] = (clamp(a * v / 255) << 24) | (p & 0xFFFFFF); // the glow keeps the colour of what glows
        }
        gaussian(bright, bright, w, h, radius);
        for (int i = 0; i < s.length; i++) {
            int p = s[i], a = p >>> 24, gl = bright[i];
            int glow = clamp(Math.round((gl >>> 24) * amount * 2.2f));
            int r = 255 - (255 - red(p)) * (255 - glow * red(gl) / 255) / 255;
            int g = 255 - (255 - green(p)) * (255 - glow * green(gl) / 255) / 255;
            int b = 255 - (255 - blue(p)) * (255 - glow * blue(gl) / 255) / 255;
            d[i] = pix(Math.max(a, glow), r, g, b);
        }
    }

    // ---------------- Texture / stylise ----------------

    private static int hash(int x) {
        x ^= x >>> 16; x *= 0x7feb352d; x ^= x >>> 15; x *= 0x846ca68b; x ^= x >>> 16;
        return x;
    }

    static void noise(int[] s, int[] d, float amount, int seed) {
        for (int i = 0; i < s.length; i++) {
            int n = Math.round(((hash(i * 31 + seed) & 255) - 128) * amount * 0.9f), p = s[i];
            d[i] = pix(p >>> 24, red(p) + n, green(p) + n, blue(p) + n);
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

    /** Classic 45° halftone dots in the colours of the picture. cell = dot spacing in pixels. Dot area matches the tone. */
    static void halftone(int[] s, int[] d, int w, int h, float cell) {
        final float k = 0.70710678f;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float u = (x + y) * k, v = (x - y) * k;
                int iu0 = (int) Math.floor(u / cell), iv0 = (int) Math.floor(v / cell);
                float cov = 0;
                int col = 0;
                for (int du = -1; du <= 1; du++)       // neighbouring dots can reach into this cell
                    for (int dv = -1; dv <= 1; dv++) {
                        float cu = (iu0 + du + 0.5f) * cell, cv = (iv0 + dv + 0.5f) * cell;
                        int sx = Math.round((cu + cv) * k), sy = Math.round((cu - cv) * k);
                        if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
                        int p = s[sy * w + sx];
                        float dark = (255 - lum(p)) * (p >>> 24) / 65025f;
                        if (dark <= 0) continue;
                        float rad = cell * 0.5642f * (float) Math.sqrt(dark) * 1.08f;
                        float c = rad - (float) Math.hypot(u - cu, v - cv) + 0.5f;
                        if (c > cov) { cov = Math.min(1, c); col = lum(p) < 40 ? 0 : p; } // dark dots are black
                    }
                int a0 = s[y * w + x] >>> 24;
                int r = 255 + Math.round((red(col) - 255) * cov), g = 255 + Math.round((green(col) - 255) * cov), b = 255 + Math.round((blue(col) - 255) * cov);
                d[y * w + x] = pix(Math.max(a0, Math.round(cov * 255)), r, g, b);
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
            int a = src[i], b = out[i];
            out[i] = pix((a >>> 24) + ((b >>> 24) - (a >>> 24)) * m / 255, red(a) + (red(b) - red(a)) * m / 255,
                    green(a) + (green(b) - green(a)) * m / 255, blue(a) + (blue(b) - blue(a)) * m / 255);
        }
    }

    /** Colour as seen over white paper, packed RGB. */
    private static int onWhite(int p) {
        int a = p >>> 24;
        return ((red(p) * a / 255 + 255 - a) << 16) | ((green(p) * a / 255 + 255 - a) << 8) | (blue(p) * a / 255 + 255 - a);
    }

    /**
     * Flood fill from (sx,sy) over pixels similar to the seed. Writes 255 into out where filled.
     * Similarity compares alpha and the colour as seen on white; thr is 0..255.
     */
    static int flood(int[] px, int w, int h, int sx, int sy, int thr, byte[] out) {
        int seed = px[sy * w + sx];
        int[] stack = new int[Math.max(64, w * 4)];
        int sp = 0, count = 0;
        stack[sp++] = sy * w + sx;
        while (sp > 0) {
            int i = stack[--sp];
            int y = i / w, x = i % w;
            int lx = x;
            while (lx >= 0 && out[y * w + lx] == 0 && diff(px[y * w + lx], seed) <= thr) lx--;
            lx++;
            boolean up = false, down = false;
            for (int xx = lx; xx < w && out[y * w + xx] == 0 && diff(px[y * w + xx], seed) <= thr; xx++) {
                out[y * w + xx] = (byte) 255;
                count++;
                if (y > 0) {
                    boolean ok = out[(y - 1) * w + xx] == 0 && diff(px[(y - 1) * w + xx], seed) <= thr;
                    if (ok && !up) { if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, sp * 2); stack[sp++] = (y - 1) * w + xx; }
                    up = ok;
                }
                if (y < h - 1) {
                    boolean ok = out[(y + 1) * w + xx] == 0 && diff(px[(y + 1) * w + xx], seed) <= thr;
                    if (ok && !down) { if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, sp * 2); stack[sp++] = (y + 1) * w + xx; }
                    down = ok;
                }
            }
        }
        return count;
    }

    /** How different a pixel is from the seed (0..255): the biggest colour or alpha difference. */
    static int diff(int p, int seed) {
        int a = onWhite(p), b = onWhite(seed);
        int dc = Math.max(Math.abs(red(a) - red(b)), Math.max(Math.abs(green(a) - green(b)), Math.abs(blue(a) - blue(b))));
        return Math.max(Math.abs((p >>> 24) - (seed >>> 24)) / 2, dc);
    }

    /**
     * Soft fill edge: grows the mask into neighbouring pixels (line edges) with partial coverage that rises with the
     * threshold, so every threshold step changes the edge a little instead of nothing-then-everything.
     */
    static void softGrow(int[] px, byte[] m, int w, int h, int seed, int thr, int rings) {
        for (int r = 0; r < rings; r++) {
            byte[] c = m.clone();
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if (c[i] != 0) continue;
                    int nb = Math.max(Math.max(x > 0 ? c[i - 1] & 255 : 0, x < w - 1 ? c[i + 1] & 255 : 0),
                            Math.max(y > 0 ? c[i - w] & 255 : 0, y < h - 1 ? c[i + w] & 255 : 0));
                    if (nb == 0) continue;
                    int cov = (thr + 96 - diff(px[i], seed)) * 255 / 96; // fades out over 96 levels past the threshold
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
