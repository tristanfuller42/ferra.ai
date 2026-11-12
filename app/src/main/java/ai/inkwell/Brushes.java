package ai.inkwell;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Imports Procreate (.brush/.brushset) and Photoshop (.abr) brushes; stores them in app files. */
final class Brushes {
    private static final int MAX_TIP = 512, MAX_TEX = 512;

    /** True if the first bytes look like a brush file. */
    static boolean looksLikeAbr(byte[] m) {
        return m[0] == 0 && (m[1] == 1 || m[1] == 2 || (m[1] >= 6 && m[1] <= 10));
    }

    static List<CanvasView.Brush> read(byte[] data) throws IOException {
        if (data.length > 4 && data[0] == 'P' && data[1] == 'K') return readProcreate(data);
        if (data.length > 4 && looksLikeAbr(data)) return readAbr(data);
        throw new IOException("Not a Procreate or Photoshop brush file");
    }

    // ---------------- Procreate ----------------
    // .brush = zip with Shape.png, Grain.png, Brush.archive (binary plist).
    // .brushset = zip of folders, each holding those files.

    private static List<CanvasView.Brush> readProcreate(byte[] data) throws IOException {
        Map<String, Map<String, byte[]>> dirs = new TreeMap<>();
        boolean artwork = false;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName();
                int slash = n.lastIndexOf('/');
                String dir = slash < 0 ? "" : n.substring(0, slash);
                String file = n.substring(slash + 1).toLowerCase();
                if (file.equals("document.archive")) artwork = true;
                if (!(file.startsWith("shape") || file.startsWith("grain") || file.equals("brush.archive"))) continue;
                dirs.computeIfAbsent(dir, k -> new HashMap<>()).put(file, readAll(z));
            }
        }
        List<CanvasView.Brush> out = new ArrayList<>();
        int i = 1;
        for (Map.Entry<String, Map<String, byte[]>> d : dirs.entrySet()) {
            Map<String, byte[]> f = d.getValue();
            byte[] shape = find(f, "shape"), grain = find(f, "grain");
            if (shape == null && !f.containsKey("brush.archive")) continue;
            Map<String, Object> st = settings(f.get("brush.archive"));
            String name = st.get("name") instanceof String ? (String) st.get("name") : "Procreate brush " + i;
            i++;
            float pSize = num(st, "dynamicsPressureSize", 0.6f);
            float pOpac = num(st, "dynamicsPressureOpacity", 0f);
            float spacing = num(st, "plotSpacing", 0.1f);
            CanvasView.Brush b = new CanvasView.Brush(name,
                    clamp(1 - pSize), clamp(1 - pOpac), 1f, 0.9f, 0f, Math.max(0.03f, Math.min(1f, spacing)));
            if (shape != null) {
                int[] wh = new int[2];
                byte[] t = grey(shape, MAX_TIP, wh, truthy(st.get("shapeInverted")));
                if (t != null) b.setTip(t, wh[0], wh[1]);
            }
            if (grain != null) {
                int[] wh = new int[2];
                byte[] t = grey(grain, MAX_TEX, wh, truthy(st.get("textureInverted")));
                if (t != null && !isFlat(t)) { b.tex = t; b.gw = wh[0]; b.gh = wh[1]; b.grain = 0.85f; }
            }
            out.add(b);
        }
        if (out.isEmpty()) throw new IOException(artwork
                ? "This is Procreate artwork, not a brush. In Procreate use Share → PSD, then Open it."
                : "No brushes found in this file");
        return out;
    }

    private static byte[] find(Map<String, byte[]> f, String prefix) {
        for (Map.Entry<String, byte[]> e : f.entrySet())
            if (e.getKey().startsWith(prefix)) return e.getValue();
        return null;
    }

    /** Pulls numeric settings and the name out of Brush.archive. Missing values fall back to defaults. */
    private static Map<String, Object> settings(byte[] plist) {
        Map<String, Object> out = new HashMap<>();
        if (plist == null) return out;
        try {
            Object root = new BPlist(plist).parse();
            if (!(root instanceof Map)) return out;
            Object objs = ((Map<?, ?>) root).get("$objects");
            if (!(objs instanceof List)) return out;
            List<?> list = (List<?>) objs;
            for (Object o : list) {
                if (!(o instanceof Map)) continue;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof BPlist.Uid) {
                        int u = ((BPlist.Uid) v).v;
                        v = u >= 0 && u < list.size() ? list.get(u) : null;
                    }
                    if (v != null && !out.containsKey(String.valueOf(e.getKey())))
                        out.put(String.valueOf(e.getKey()), v);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static float num(Map<String, Object> m, String k, float def) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).floatValue() : def;
    }

    private static boolean truthy(Object o) {
        return o instanceof Boolean ? (Boolean) o : o instanceof Number && ((Number) o).intValue() != 0;
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    /** Decodes an image to 0..255 "paint amount" (brightness × alpha), downscaled to max side. */
    private static byte[] grey(byte[] img, int max, int[] wh, boolean invert) {
        Bitmap b = BitmapFactory.decodeByteArray(img, 0, img.length);
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        if (Math.max(w, h) > max) {
            float s = (float) max / Math.max(w, h);
            Bitmap n = Bitmap.createScaledBitmap(b, Math.max(1, (int) (w * s)), Math.max(1, (int) (h * s)), true);
            b.recycle();
            b = n; w = b.getWidth(); h = b.getHeight();
        }
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        byte[] out = new byte[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int lum = (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
            if (invert) lum = 255 - lum;
            out[i] = (byte) (lum * (p >>> 24) / 255);
        }
        wh[0] = w; wh[1] = h;
        return out;
    }

    private static boolean isFlat(byte[] t) {
        int lo = 255, hi = 0;
        for (byte v : t) { int x = v & 255; lo = Math.min(lo, x); hi = Math.max(hi, x); }
        return hi - lo < 8;
    }

    // ---------------- Photoshop ABR ----------------

    private static List<CanvasView.Brush> readAbr(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int version = in.readUnsignedShort();
        List<CanvasView.Brush> out = new ArrayList<>();
        if (version == 1 || version == 2) {
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                int type = in.readUnsignedShort();
                int size = in.readInt();
                byte[] body = new byte[size];
                in.readFully(body);
                if (type != 2) continue; // only sampled brushes have a tip image
                DataInputStream b = new DataInputStream(new ByteArrayInputStream(body));
                b.readInt(); // misc
                int spacing = b.readUnsignedShort();
                String name = "Photoshop brush " + (out.size() + 1);
                if (version == 2) {
                    int n = b.readInt();
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < n; k++) { char c = b.readChar(); if (c != 0) sb.append(c); }
                    if (sb.length() > 0) name = sb.toString();
                }
                b.readUnsignedByte(); // antialias
                b.skipBytes(8);       // short bounds
                CanvasView.Brush br = abrSample(b, name, spacing / 100f);
                if (br != null) out.add(br);
            }
        } else {
            int sub = in.readUnsignedShort();
            // Find the "samp" section holding the tip images
            while (in.available() >= 12) {
                int sig = in.readInt(), key = in.readInt();
                long len = in.readInt() & 0xFFFFFFFFL;
                if (sig != 0x3842494D) break; // 8BIM
                if (key != 0x73616D70) { skip(in, len); continue; } // not "samp"
                byte[] sec = new byte[(int) len];
                in.readFully(sec);
                DataInputStream s = new DataInputStream(new ByteArrayInputStream(sec));
                while (s.available() > 4) {
                    long bl = s.readInt() & 0xFFFFFFFFL;
                    long padded = (bl + 3) & ~3L;
                    byte[] body = new byte[(int) Math.min(padded, s.available())];
                    s.readFully(body);
                    DataInputStream b = new DataInputStream(new ByteArrayInputStream(body));
                    try {
                        b.skipBytes(sub == 1 ? 47 : 301);
                        CanvasView.Brush br = abrSample(b, "Photoshop brush " + (out.size() + 1), 0.15f);
                        if (br != null) out.add(br);
                    } catch (EOFException ignored) { }
                }
            }
        }
        if (out.isEmpty()) throw new IOException("No sampled brushes found (computed Photoshop brushes aren't supported)");
        return out;
    }

    /** Reads bounds + depth + compression + pixel data of an ABR brush tip. */
    private static CanvasView.Brush abrSample(DataInputStream b, String name, float spacing) throws IOException {
        int top = b.readInt(), left = b.readInt(), bottom = b.readInt(), right = b.readInt();
        int depth = b.readUnsignedShort();
        int comp = b.readUnsignedByte();
        int w = right - left, h = bottom - top;
        if (w <= 0 || h <= 0 || w > 5000 || h > 5000) return null;
        int bpp = depth == 16 ? 2 : 1;
        byte[] raw = new byte[w * h * bpp];
        if (comp == 0) b.readFully(raw);
        else {
            int[] counts = new int[h];
            for (int y = 0; y < h; y++) counts[y] = b.readUnsignedShort();
            int rowLen = w * bpp;
            for (int y = 0; y < h; y++) {
                byte[] row = new byte[counts[y]];
                b.readFully(row);
                unpackBits(row, raw, y * rowLen, rowLen);
            }
        }
        byte[] tip = new byte[w * h];
        for (int i = 0; i < tip.length; i++) tip[i] = raw[i * bpp]; // 16-bit: keep high byte
        // Downscale huge tips
        if (Math.max(w, h) > MAX_TIP) {
            float s = (float) MAX_TIP / Math.max(w, h);
            int nw = Math.max(1, (int) (w * s)), nh = Math.max(1, (int) (h * s));
            byte[] n = new byte[nw * nh];
            for (int y = 0; y < nh; y++)
                for (int x = 0; x < nw; x++) n[y * nw + x] = tip[(int) (y / s) * w + (int) (x / s)];
            tip = n; w = nw; h = nh;
        }
        CanvasView.Brush br = new CanvasView.Brush(name, 0.4f, 1f, 1f, 0.9f, 0f, Math.max(0.03f, Math.min(1f, spacing)));
        br.setTip(tip, w, h);
        return br;
    }

    private static void unpackBits(byte[] src, byte[] dst, int o, int n) {
        int p = 0, end = o + n;
        while (p < src.length && o < end) {
            int c = src[p++];
            if (c >= 0) {
                int k = Math.min(c + 1, Math.min(end - o, src.length - p));
                System.arraycopy(src, p, dst, o, k);
                p += c + 1; o += k;
            } else if (c != -128 && p < src.length) {
                byte v = src[p++];
                for (int i = 0; i < 1 - c && o < end; i++) dst[o++] = v;
            }
        }
    }

    // ---------------- Saving imported brushes ----------------

    static void save(CanvasView.Brush b, File f) throws IOException {
        try (DataOutputStream o = new DataOutputStream(new java.util.zip.DeflaterOutputStream(new FileOutputStream(f)))) {
            o.writeInt(2);
            o.writeUTF(b.name);
            o.writeFloat(b.minSize); o.writeFloat(b.minAlpha); o.writeFloat(b.alpha);
            o.writeFloat(b.hard); o.writeFloat(b.grain); o.writeFloat(b.spacing);
            o.writeInt(b.tip == null ? 0 : b.tw[0]); o.writeInt(b.tip == null ? 0 : b.th[0]);
            if (b.tip != null) o.write(b.tip[0]);
            o.writeInt(b.tex == null ? 0 : b.gw); o.writeInt(b.tex == null ? 0 : b.gh);
            if (b.tex != null) o.write(b.tex);
            // version 2: Brush Studio settings
            o.writeUTF(b.set);
            o.writeBoolean(b.builtin);
            o.writeInt(CanvasView.Brush.KEYS.length);
            for (String k : CanvasView.Brush.KEYS) { o.writeUTF(k); o.writeFloat(b.get(k)); }
        }
        b.file = f;
    }

    static CanvasView.Brush load(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.util.zip.InflaterInputStream(new FileInputStream(f)))) {
            int ver = in.readInt();
            if (ver != 1 && ver != 2) throw new IOException("bad version");
            CanvasView.Brush b = new CanvasView.Brush(in.readUTF(), in.readFloat(), in.readFloat(), in.readFloat(),
                    in.readFloat(), in.readFloat(), in.readFloat());
            int w = in.readInt(), h = in.readInt();
            if (w > 0) { byte[] t = new byte[w * h]; in.readFully(t); b.setTip(t, w, h); }
            w = in.readInt(); h = in.readInt();
            if (w > 0) { b.tex = new byte[w * h]; in.readFully(b.tex); b.gw = w; b.gh = h; }
            if (ver >= 2) {
                b.set = in.readUTF();
                b.builtin = in.readBoolean();
                int n = in.readInt();
                for (int i = 0; i < n; i++) { String k = in.readUTF(); b.put(k, in.readFloat()); }
            }
            b.file = f;
            return b;
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    private static void skip(DataInputStream in, long n) throws IOException {
        while (n > 0) { int k = in.skipBytes((int) Math.min(n, Integer.MAX_VALUE)); if (k <= 0) throw new EOFException(); n -= k; }
    }

    // ---------------- Minimal binary plist reader ----------------

    static final class BPlist {
        static final class Uid { final int v; Uid(int v) { this.v = v; } }
        private final byte[] d;
        private int offSize, refSize;
        private long[] offsets;

        BPlist(byte[] d) { this.d = d; }

        Object parse() throws IOException {
            if (d.length < 40 || !new String(d, 0, 6, StandardCharsets.US_ASCII).equals("bplist")) throw new IOException("not bplist");
            int t = d.length - 32;
            offSize = d[t + 6] & 255;
            refSize = d[t + 7] & 255;
            int num = (int) be(t + 8, 8);
            long top = be(t + 16, 8), table = be(t + 24, 8);
            offsets = new long[num];
            for (int i = 0; i < num; i++) offsets[i] = be((int) table + i * offSize, offSize);
            return obj((int) top, 0);
        }

        private long be(int p, int n) {
            long v = 0;
            for (int i = 0; i < n; i++) v = (v << 8) | (d[p + i] & 255);
            return v;
        }

        private Object obj(int ref, int depth) throws IOException {
            if (depth > 64 || ref < 0 || ref >= offsets.length) throw new IOException("bad ref");
            int p = (int) offsets[ref];
            int m = d[p] & 255, hi = m >> 4, lo = m & 15;
            p++;
            switch (hi) {
                case 0x0: return lo == 9 ? Boolean.TRUE : lo == 8 ? Boolean.FALSE : null;
                case 0x1: return be(p, 1 << lo);
                case 0x2: {
                    long bits = be(p, 1 << lo);
                    return lo == 2 ? (double) Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
                }
                case 0x8: return new Uid((int) be(p, lo + 1));
                default: break;
            }
            int len = lo;
            if (lo == 15) {
                int im = d[p] & 15;
                len = (int) be(p + 1, 1 << im);
                p += 1 + (1 << im);
            }
            switch (hi) {
                case 0x4: { byte[] b = new byte[len]; System.arraycopy(d, p, b, 0, len); return b; }
                case 0x5: return new String(d, p, len, StandardCharsets.US_ASCII);
                case 0x6: return new String(d, p, len * 2, StandardCharsets.UTF_16BE);
                case 0xA: {
                    List<Object> l = new ArrayList<>(len);
                    for (int i = 0; i < len; i++) l.add(obj((int) be(p + i * refSize, refSize), depth + 1));
                    return l;
                }
                case 0xD: {
                    Map<Object, Object> mp = new HashMap<>();
                    for (int i = 0; i < len; i++) {
                        Object k = obj((int) be(p + i * refSize, refSize), depth + 1);
                        Object v = obj((int) be(p + (len + i) * refSize, refSize), depth + 1);
                        mp.put(k, v);
                    }
                    return mp;
                }
                default: return null;
            }
        }
    }
}
