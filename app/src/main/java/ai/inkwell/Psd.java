package ai.inkwell;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal Photoshop (.psd) reader/writer: 8-bit RGB or greyscale, layers, RLE. Colours become grey. */
final class Psd {
    /** PSD blend keys in CanvasView.BLEND_NAMES order. */
    static final String[] KEYS = {"norm", "mul ", "scrn", "over", "dark", "lite", "idiv", "div ", "hLit", "sLit", "diff", "lddg"};

    static final class Doc {
        int w, h;
        final ArrayList<CanvasView.Layer> layers = new ArrayList<>();
        final java.util.HashMap<Integer, CanvasView.Group> groups = new java.util.HashMap<>();
    }

    // ---------------- Reading ----------------

    static Doc read(InputStream raw, long maxPixels) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 65536));
        if (in.readInt() != 0x38425053) throw new IOException("Not a PSD file");
        if (in.readUnsignedShort() != 1) throw new IOException("Large-document (PSB) files are not supported");
        skip(in, 6);
        int channels = in.readUnsignedShort();
        int h = in.readInt(), w = in.readInt();
        int depth = in.readUnsignedShort(), mode = in.readUnsignedShort();
        if (depth != 8 || (mode != 1 && mode != 3))
            throw new IOException("Only 8-bit RGB or greyscale PSD is supported");
        skip(in, in.readInt()); // colour mode data
        skip(in, in.readInt()); // image resources

        double s = Math.min(1.0, Math.sqrt((double) maxPixels / ((double) w * h)));
        Doc doc = new Doc();
        doc.w = Math.max(1, (int) Math.round(w * s));
        doc.h = Math.max(1, (int) Math.round(h * s));

        long lmLen = in.readInt() & 0xFFFFFFFFL;
        if (lmLen > 0) {
            byte[] lm = new byte[(int) lmLen];
            in.readFully(lm);
            readLayers(new DataInputStream(new java.io.ByteArrayInputStream(lm)), doc, mode, (float) s);
        }
        if (doc.layers.isEmpty()) {
            Bitmap merged = readMerged(in, w, h, channels, mode);
            CanvasView.Layer l = new CanvasView.Layer(Bitmap.createBitmap(doc.w, doc.h, Bitmap.Config.ARGB_8888), "Background");
            Matrix m = new Matrix();
            m.setScale((float) doc.w / w, (float) doc.h / h);
            new Canvas(l.bmp).drawBitmap(merged, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            merged.recycle();
            doc.layers.add(l);
        }
        return doc;
    }

    private static void readLayers(DataInputStream in, Doc doc, int mode, float s) throws IOException {
        long infoLen = in.readInt() & 0xFFFFFFFFL;
        if (infoLen == 0) return;
        int count = Math.abs(in.readShort());
        int[][] rects = new int[count][];
        int[][] chIds = new int[count][];
        long[][] chLens = new long[count][];
        int[] opac = new int[count], blend = new int[count];
        boolean[] hidden = new boolean[count], clip = new boolean[count], lock = new boolean[count], maskOff = new boolean[count];
        int[] folder = new int[count], maskDef = new int[count];
        int[][] mrect = new int[count][];
        String[] names = new String[count];

        for (int i = 0; i < count; i++) {
            rects[i] = new int[]{in.readInt(), in.readInt(), in.readInt(), in.readInt()}; // t l b r
            int nc = in.readUnsignedShort();
            chIds[i] = new int[nc];
            chLens[i] = new long[nc];
            for (int c = 0; c < nc; c++) {
                chIds[i][c] = in.readShort();
                chLens[i][c] = in.readInt() & 0xFFFFFFFFL;
            }
            skip(in, 4); // "8BIM"
            byte[] bk = new byte[4];
            in.readFully(bk);
            String bkey = new String(bk, StandardCharsets.ISO_8859_1);
            for (int k = 0; k < KEYS.length; k++) if (KEYS[k].equals(bkey)) blend[i] = k;
            opac[i] = in.readUnsignedByte();
            clip[i] = in.readUnsignedByte() != 0;
            int flags = in.readUnsignedByte();
            hidden[i] = (flags & 2) != 0;
            lock[i] = (flags & 1) != 0;
            in.readUnsignedByte();
            byte[] extra = new byte[in.readInt()];
            in.readFully(extra);
            DataInputStream ex = new DataInputStream(new java.io.ByteArrayInputStream(extra));
            int ml = ex.readInt(); // layer mask
            if (ml >= 18) {
                mrect[i] = new int[]{ex.readInt(), ex.readInt(), ex.readInt(), ex.readInt()};
                maskDef[i] = ex.readUnsignedByte();
                maskOff[i] = (ex.readUnsignedByte() & 2) != 0;
                skip(ex, ml - 18);
            } else skip(ex, ml);
            skip(ex, ex.readInt()); // blending ranges
            int nl = ex.readUnsignedByte();
            byte[] nb = new byte[nl];
            ex.readFully(nb);
            names[i] = new String(nb, StandardCharsets.ISO_8859_1);
            skip(ex, (4 - (nl + 1) % 4) % 4);
            try { // additional info blocks: unicode name, group markers
                while (ex.available() >= 12) {
                    ex.readInt();
                    int key = ex.readInt();
                    int len = ex.readInt();
                    if (key == 0x6C756E69) { // luni
                        int n = ex.readInt();
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < n; k++) sb.append(ex.readChar());
                        names[i] = sb.toString();
                        skip(ex, len - 4 - 2L * n);
                    } else if (key == 0x6C736374) { // lsct = group/folder marker
                        folder[i] = ex.readInt();
                        skip(ex, len - 4);
                    } else skip(ex, len);
                }
            } catch (IOException ignored) { }
        }

        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        int nextId = 1;
        for (int i = 0; i < count; i++) {
            int t = rects[i][0], l = rects[i][1], b = rects[i][2], r = rects[i][3];
            int lw = r - l, lh = b - t;
            byte[][] ch = new byte[4][]; // 0..2 colour, 3 alpha
            byte[] mch = null;
            for (int c = 0; c < chIds[i].length; c++) {
                byte[] data = new byte[(int) chLens[i][c]];
                in.readFully(data);
                int id = chIds[i][c];
                int slot = id == -1 ? 3 : (id >= 0 && id <= 2 ? id : -1);
                if (slot >= 0 && lw > 0 && lh > 0) ch[slot] = decode(data, lw, lh);
                if (id == -2 && mrect[i] != null) {
                    int mw = mrect[i][3] - mrect[i][1], mh = mrect[i][2] - mrect[i][0];
                    if (mw > 0 && mh > 0) mch = decode(data, mw, mh);
                }
            }
            // Groups: in PSD order (bottom to top) a divider (3) opens, the folder record (1/2) closes
            if (folder[i] == 3) { stack.push(nextId++); continue; }
            if (folder[i] == 1 || folder[i] == 2) {
                int g = stack.isEmpty() ? nextId++ : stack.pop();
                CanvasView.Group gr = new CanvasView.Group(names[i]);
                gr.opacity = opac[i];
                gr.visible = !hidden[i];
                gr.collapsed = folder[i] == 2;
                doc.groups.put(g, gr);
                continue;
            }
            CanvasView.Layer layer = new CanvasView.Layer(Bitmap.createBitmap(doc.w, doc.h, Bitmap.Config.ARGB_8888), names[i]);
            layer.opacity = opac[i];
            layer.visible = !hidden[i];
            layer.blend = blend[i];
            layer.clip = clip[i];
            layer.alphaLock = lock[i];
            layer.group = stack.isEmpty() ? 0 : stack.peek();
            if (mrect[i] != null) { // layer mask
                CanvasView.Layer m = new CanvasView.Layer(Bitmap.createBitmap(doc.w, doc.h, Bitmap.Config.ARGB_8888), names[i] + " mask");
                m.bmp.eraseColor(0xFF000000 | maskDef[i] * 0x010101);
                if (mch != null) {
                    int mw = mrect[i][3] - mrect[i][1], mh = mrect[i][2] - mrect[i][0];
                    Bitmap part = compose(new byte[][]{mch, null, null, null}, mw, mh, 1);
                    Matrix mm = new Matrix();
                    mm.setScale(s, s);
                    mm.preTranslate(mrect[i][1], mrect[i][0]);
                    Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG);
                    pp.setBlendMode(android.graphics.BlendMode.SRC);
                    new Canvas(m.bmp).drawBitmap(part, mm, pp);
                    part.recycle();
                }
                layer.mask = m;
                layer.maskOn = !maskOff[i];
            }
            if (lw > 0 && lh > 0 && ch[0] != null) {
                Bitmap part = compose(ch, lw, lh, mode);
                Matrix m = new Matrix();
                m.setScale(s, s);
                m.preTranslate(l, t);
                new Canvas(layer.bmp).drawBitmap(part, m, new Paint(Paint.FILTER_BITMAP_FLAG));
                part.recycle();
            }
            doc.layers.add(layer);
        }
    }

    private static Bitmap readMerged(DataInputStream in, int w, int h, int channels, int mode) throws IOException {
        int comp = in.readUnsignedShort();
        int nc = Math.min(channels, 4);
        byte[][] ch = new byte[4][];
        if (comp == 0) {
            for (int c = 0; c < channels; c++) {
                byte[] b = new byte[w * h];
                in.readFully(b);
                if (c < nc) ch[c] = b;
            }
        } else if (comp == 1) {
            int[] counts = new int[channels * h];
            for (int i = 0; i < counts.length; i++) counts[i] = in.readUnsignedShort();
            byte[] row = new byte[w * 2 + 256];
            for (int c = 0; c < channels; c++) {
                byte[] b = c < nc ? new byte[w * h] : null;
                for (int y = 0; y < h; y++) {
                    int n = counts[c * h + y];
                    if (row.length < n) row = new byte[n];
                    in.readFully(row, 0, n);
                    if (b != null) unpackBits(row, 0, n, b, y * w, w);
                }
                ch[c] = b;
            }
        } else throw new IOException("Unsupported PSD compression");
        // Merged image: grey = 1 channel (+alpha), RGB = 3 (+alpha)
        int colours = mode == 1 ? 1 : 3;
        byte[][] out = new byte[4][];
        out[0] = ch[0];
        if (colours == 3) { out[1] = ch[1]; out[2] = ch[2]; }
        out[3] = null; // ignore merged alpha: flatten on white looks right
        return compose(out, w, h, mode);
    }

    /** Decodes a layer channel (compression flag + data). */
    private static byte[] decode(byte[] d, int w, int h) {
        int comp = ((d[0] & 255) << 8) | (d[1] & 255);
        byte[] out = new byte[w * h];
        if (comp == 0) {
            System.arraycopy(d, 2, out, 0, Math.min(out.length, d.length - 2));
        } else if (comp == 1) {
            int p = 2 + h * 2;
            for (int y = 0; y < h; y++) {
                int n = ((d[2 + y * 2] & 255) << 8) | (d[3 + y * 2] & 255);
                if (p + n > d.length) break;
                unpackBits(d, p, n, out, y * w, w);
                p += n;
            }
        } else return null; // zip compression: not supported
        return out;
    }

    private static void unpackBits(byte[] src, int p, int n, byte[] dst, int o, int w) {
        int end = p + n, oe = o + w;
        while (p < end && o < oe) {
            int c = src[p++];
            if (c >= 0) {
                int k = Math.min(c + 1, Math.min(oe - o, end - p));
                System.arraycopy(src, p, dst, o, k);
                p += c + 1; o += k;
            } else if (c != -128) {
                int k = Math.min(1 - c, oe - o);
                if (p >= end) break;
                byte v = src[p++];
                for (int i = 0; i < k; i++) dst[o++] = v;
            }
        }
    }

    /** Builds a grey ARGB bitmap from colour channels (+ optional alpha). */
    private static Bitmap compose(byte[][] ch, int w, int h, int mode) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] row = new int[w];
        boolean rgb = mode == 3 && ch[1] != null && ch[2] != null;
        for (int y = 0; y < h; y++) {
            int o = y * w;
            for (int x = 0; x < w; x++, o++) {
                int g = rgb ? ((ch[0][o] & 255) * 299 + (ch[1][o] & 255) * 587 + (ch[2][o] & 255) * 114) / 1000
                            : ch[0][o] & 255;
                int a = ch[3] != null ? ch[3][o] & 255 : 255;
                row[x] = (a << 24) | (g << 16) | (g << 8) | g;
            }
            b.setPixels(row, 0, w, 0, y, w, 1);
        }
        return b;
    }

    private static void skip(DataInputStream in, long n) throws IOException {
        while (n > 0) {
            int k = in.skipBytes((int) Math.min(n, Integer.MAX_VALUE));
            if (k <= 0) { if (in.read() < 0) throw new IOException("Truncated PSD"); k = 1; }
            n -= k;
        }
    }

    // ---------------- Writing ----------------

    static void write(OutputStream os, int w, int h, List<CanvasView.Layer> layers,
                      java.util.Map<Integer, CanvasView.Group> groups, Bitmap flat) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(os, 65536));
        out.writeInt(0x38425053);
        out.writeShort(1);
        out.write(new byte[6]);
        out.writeShort(3);
        out.writeInt(h);
        out.writeInt(w);
        out.writeShort(8);
        out.writeShort(3);
        out.writeInt(0); // colour mode data
        out.writeInt(0); // image resources

        ByteArrayOutputStream recs = new ByteArrayOutputStream();
        DataOutputStream r = new DataOutputStream(recs);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int records = 0;
        for (int i = 0; i < layers.size(); i++) {
            CanvasView.Layer l = layers.get(i);
            int g = l.group;
            boolean opens = g != 0 && (i == 0 || layers.get(i - 1).group != g);
            boolean closes = g != 0 && (i == layers.size() - 1 || layers.get(i + 1).group != g);
            if (opens) { marker(r, data, "</Layer group>", 255, true, 3); records++; }
            byte[] alpha = channel(l.bmp, 24, true);
            byte[] grey = channel(l.bmp, 0, true);
            byte[] mask = l.mask != null ? channel(l.mask.bmp, 0, true) : null;
            r.writeInt(0); r.writeInt(0); r.writeInt(h); r.writeInt(w);
            r.writeShort(mask != null ? 5 : 4);
            r.writeShort(-1); r.writeInt(alpha.length);
            for (int c = 0; c < 3; c++) { r.writeShort(c); r.writeInt(grey.length); }
            if (mask != null) { r.writeShort(-2); r.writeInt(mask.length); }
            r.writeBytes("8BIM" + KEYS[l.blend]);
            r.writeByte(l.opacity);
            r.writeByte(l.clip ? 1 : 0);
            r.writeByte((l.visible ? 0 : 2) | (l.alphaLock ? 1 : 0));
            r.writeByte(0);
            byte[] name = l.name.getBytes(StandardCharsets.ISO_8859_1);
            int nl = Math.min(name.length, 255);
            int padded = (nl + 1 + 3) / 4 * 4;
            int maskLen = mask != null ? 20 : 0;
            r.writeInt(4 + maskLen + 4 + padded);
            r.writeInt(maskLen);
            if (mask != null) {
                r.writeInt(0); r.writeInt(0); r.writeInt(h); r.writeInt(w);
                r.writeByte(255);
                r.writeByte(l.maskOn ? 0 : 2);
                r.writeShort(0);
            }
            r.writeInt(0); // blending ranges
            r.writeByte(nl);
            r.write(name, 0, nl);
            r.write(new byte[padded - nl - 1]);
            data.write(alpha);
            data.write(grey); data.write(grey); data.write(grey);
            if (mask != null) data.write(mask);
            records++;
            if (closes) {
                CanvasView.Group gr = groups.get(g);
                marker(r, data, gr == null ? "Group" : gr.name, gr == null ? 255 : gr.opacity,
                        gr == null || gr.visible, gr != null && gr.collapsed ? 2 : 1);
                records++;
            }
        }
        byte[] recBytes = recs.toByteArray();
        recs.reset();
        new DataOutputStream(recs).writeShort(records);
        recs.write(recBytes);
        int infoLen = recs.size() + data.size();
        int pad = infoLen % 2;
        out.writeInt(4 + infoLen + pad + 4);
        out.writeInt(infoLen + pad);
        recs.writeTo(out);
        data.writeTo(out);
        if (pad == 1) out.writeByte(0);
        out.writeInt(0); // global mask info

        // Merged composite (grey, so R=G=B)
        byte[] g = channel(flat, 0, false);
        int countsLen = h * 2;
        out.writeShort(1);
        for (int c = 0; c < 3; c++) out.write(g, 0, countsLen);
        for (int c = 0; c < 3; c++) out.write(g, countsLen, g.length - countsLen);
        out.flush();
    }

    /** Empty layer record used for group start/end markers (lsct). */
    private static void marker(DataOutputStream r, ByteArrayOutputStream data, String nm, int opacity, boolean visible, int type) throws IOException {
        r.writeInt(0); r.writeInt(0); r.writeInt(0); r.writeInt(0);
        r.writeShort(4);
        r.writeShort(-1); r.writeInt(2);
        for (int c = 0; c < 3; c++) { r.writeShort(c); r.writeInt(2); }
        r.writeBytes("8BIMpass");
        r.writeByte(opacity);
        r.writeByte(0);
        r.writeByte(visible ? 0 : 2);
        r.writeByte(0);
        byte[] name = nm.getBytes(StandardCharsets.ISO_8859_1);
        int nl = Math.min(name.length, 255);
        int padded = (nl + 1 + 3) / 4 * 4;
        r.writeInt(4 + 4 + padded + 16);
        r.writeInt(0);
        r.writeInt(0);
        r.writeByte(nl);
        r.write(name, 0, nl);
        r.write(new byte[padded - nl - 1]);
        r.writeBytes("8BIMlsct");
        r.writeInt(4);
        r.writeInt(type);
        for (int c = 0; c < 4; c++) data.write(new byte[2]); // raw, zero size
    }

    /** One channel, RLE: [compression flag if withFlag] + row counts + rows. shift 24 = alpha, 0 = blue/grey. */
    private static byte[] channel(Bitmap b, int shift, boolean withFlag) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w];
        byte[] row = new byte[w];
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] counts = new int[h];
        for (int y = 0; y < h; y++) {
            b.getPixels(px, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) row[x] = (byte) (px[x] >>> shift);
            int before = body.size();
            packBits(row, w, body);
            counts[y] = body.size() - before;
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream(body.size() + h * 2 + 2);
        if (withFlag) { o.write(0); o.write(1); }
        for (int c : counts) { o.write(c >> 8); o.write(c); }
        o.write(body.toByteArray(), 0, body.size());
        return o.toByteArray();
    }

    private static void packBits(byte[] s, int n, ByteArrayOutputStream o) {
        int i = 0;
        while (i < n) {
            int j = i + 1;
            while (j < n && j - i < 128 && s[j] == s[i]) j++;
            if (j - i >= 3) { o.write(1 - (j - i)); o.write(s[i]); i = j; continue; }
            int st = i;
            while (i < n && i - st < 128) {
                if (i + 2 < n && s[i] == s[i + 1] && s[i] == s[i + 2]) break;
                i++;
            }
            o.write(i - st - 1);
            o.write(s, st, i - st);
        }
    }
}
