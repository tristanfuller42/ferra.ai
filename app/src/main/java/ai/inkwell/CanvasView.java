package ai.inkwell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Layered greyscale canvas. Layers are ARGB bitmaps holding only greys
 * (plus transparency). Undo stores compressed "before" pixels
 * of the changed rectangle, so 100 steps fit in little memory.
 */
public class CanvasView extends View {
    public static final int MAX_UNDO = 100;
    private static final long MAX_HISTORY_BYTES = 96L << 20;
    public static final int DRAW = 0, ERASE = 1, MOVE = 2;

    public static final class Layer {
        public Bitmap bmp, thumb;
        public String name;
        public int opacity = 255;
        public boolean visible = true, dirty = true;
        public Layer(Bitmap b, String n) { bmp = b; name = n; }
    }

    interface Edit { void undo(); void redo(); long bytes(); }

    /** Swaps a rectangle of pixels between the layer and stored compressed data. */
    private final class PixelEdit implements Edit {
        final Layer l; final Rect r; byte[] data;
        PixelEdit(Layer l, Rect r) { this.l = l; this.r = r; data = pack(l.bmp, r); }
        void swap() { byte[] now = pack(l.bmp, r); unpack(l.bmp, r, data); data = now; l.dirty = true; }
        public void undo() { swap(); }
        public void redo() { swap(); }
        public long bytes() { return data.length; }
    }

    /** Structural change (add/delete/reorder layer, opacity, ...). */
    private static final class Op implements Edit {
        final Runnable u, r; final long b;
        Op(Runnable u, Runnable r, long b) { this.u = u; this.r = r; this.b = b; }
        public void undo() { u.run(); }
        public void redo() { r.run(); }
        public long bytes() { return b; }
    }

    public final ArrayList<Layer> layers = new ArrayList<>();
    public int current, docW, docH;
    private Bitmap backup;               // copy of current layer before the active stroke
    private Canvas backupCanvas;
    private final ArrayList<Edit> undoList = new ArrayList<>(), redoList = new ArrayList<>();
    public Runnable onChange;

    public int tool = DRAW;
    public int grey = 0;                 // ink: 0 = black .. 255 = white
    public float size = 12f;
    private boolean stylusSeen;

    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint copy = new Paint();
    private final Paint paper = new Paint();

    /** Brush preset. minSize/minAlpha = size/opacity at zero pressure (1 = pressure doesn't affect it). */
    public static final class Brush {
        public String name;
        float minSize, minAlpha, alpha, hard, grain, spacing;
        /** Optional tip image (0..255 = paint amount) as a mip chain, and grain texture. */
        byte[][] tip; int[] tw, th;
        byte[] tex; int gw, gh;
        public java.io.File file;      // set for imported brushes (saved copy)
        Brush(String n, float minSize, float minAlpha, float alpha, float hard, float grain, float spacing) {
            name = n; this.minSize = minSize; this.minAlpha = minAlpha; this.alpha = alpha;
            this.hard = hard; this.grain = grain; this.spacing = spacing;
        }

        /** Sets the tip and builds half-size copies so small stamps stay smooth. */
        void setTip(byte[] t, int w, int h) {
            ArrayList<byte[]> ts = new ArrayList<>();
            ArrayList<Integer> ws = new ArrayList<>(), hs = new ArrayList<>();
            ts.add(t); ws.add(w); hs.add(h);
            while (w > 2 && h > 2) {
                int nw = w / 2, nh = h / 2;
                byte[] n = new byte[nw * nh];
                for (int y = 0; y < nh; y++)
                    for (int x = 0; x < nw; x++) {
                        int i = y * 2 * w + x * 2;
                        n[y * nw + x] = (byte) (((t[i] & 255) + (t[i + 1] & 255) + (t[i + w] & 255) + (t[i + w + 1] & 255)) / 4);
                    }
                t = n; w = nw; h = nh;
                ts.add(t); ws.add(w); hs.add(h);
            }
            tip = ts.toArray(new byte[0][]);
            tw = new int[ws.size()]; th = new int[hs.size()];
            for (int i = 0; i < tw.length; i++) { tw[i] = ws.get(i); th[i] = hs.get(i); }
        }
    }

    public static final ArrayList<Brush> BRUSHES = new ArrayList<>(java.util.Arrays.asList(
        //          name            minSize minAlpha alpha hard  grain spacing
        new Brush("Ink pen",        0.15f, 1f,    1f,   0.9f, 0f,    0.08f),
        new Brush("Brush pen",      0.03f, 1f,    1f,   0.85f, 0f,   0.05f),
        new Brush("Fine liner",     1f,    1f,    1f,   0.9f, 0f,    0.08f),
        new Brush("Dry ink",        0.3f,  0.6f,  1f,   0.8f, 0.5f,  0.1f),
        new Brush("Marker",         1f,    1f,    0.5f, 0.95f, 0f,   0.08f),
        new Brush("HB pencil",      0.7f,  0.15f, 0.9f, 0.7f, 0.6f,  0.1f),
        new Brush("6B pencil",      0.6f,  0.25f, 1f,   0.6f, 0.75f, 0.1f),
        new Brush("Charcoal",       0.6f,  0.2f,  1f,   0.4f, 0.85f, 0.1f),
        new Brush("Soft shading",   0.5f,  0.05f, 0.8f, 0f,   0f,    0.1f),
        new Brush("Airbrush",       1f,    0.02f, 0.6f, 0f,   0f,    0.1f),
        new Brush("Hard round",     1f,    0.1f,  1f,   0.9f, 0f,    0.08f)
    ));

    /** 128x128 tileable paper grain, 0..255. */
    private static final byte[] NOISE = new byte[128 * 128];
    static {
        java.util.Random rnd = new java.util.Random(7);
        float[] n = new float[128 * 128];
        for (int i = 0; i < n.length; i++) n[i] = rnd.nextFloat();
        for (int i = 0; i < n.length; i++) { // light blur so grain looks like paper tooth
            int x = i & 127, y = i >> 7;
            float s = 0;
            for (int k = -1; k <= 1; k++) for (int j = -1; j <= 1; j++)
                s += n[(((y + k) & 127) << 7) | ((x + j) & 127)];
            float v = s / 9f;
            v = Math.max(0, Math.min(1, (v - 0.5f) * 3f + 0.5f));
            NOISE[i] = (byte) (v * 255);
        }
    }

    public Brush brush = BRUSHES.get(0);
    public float opacity = 1f;

    // Stroke state
    private byte[] mask = new byte[0];
    private int[] buf = new int[4096];
    private boolean stroking, erasing, penInput;
    private float lx, ly, lp, nextDab;
    private final Rect eventDirty = new Rect(), strokeDirty = new Rect();

    // View + move-tool transforms (screen space)
    private final Matrix view = new Matrix(), inverse = new Matrix(), moveT = new Matrix(), tmpM = new Matrix();
    private final float[] pt = new float[2];
    private boolean moving, fitted;

    // Gesture state
    private boolean gesture;
    private float gX, gY, gDist, gAngle, gMoved;
    private long gStart;
    private int gMax;

    public CanvasView(Context c) {
        super(c);
        copy.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        paper.setColor(Color.WHITE);
        setBackgroundColor(0xFF2A2A2A);
    }

    // ---------- Document ----------

    public void setDocument(int w, int h, List<Layer> ls) {
        docW = w; docH = h;
        layers.clear();
        layers.addAll(ls);
        if (layers.isEmpty()) layers.add(newLayer("Layer 1"));
        current = layers.size() - 1;
        undoList.clear(); redoList.clear();
        moving = false; moveT.reset();
        backup = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        backupCanvas = new Canvas(backup);
        mask = new byte[w * h];
        syncBackup();
        if (getWidth() > 0) fit();
        changed();
    }

    /** Changes canvas size. scale=true fits artwork to the new size; false keeps it 1:1, centred (crop/extend). */
    public void resizeDocument(int w, int h, boolean scale) {
        commitMove();
        int keep = current;
        ArrayList<Layer> out = new ArrayList<>();
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        float s = scale ? Math.min((float) w / docW, (float) h / docH) : 1f;
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((w - docW * s) / 2f, (h - docH * s) / 2f);
        for (Layer l : layers) {
            Layer n = new Layer(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), l.name);
            n.opacity = l.opacity;
            n.visible = l.visible;
            new Canvas(n.bmp).drawBitmap(l.bmp, m, p);
            l.bmp.recycle();
            out.add(n);
        }
        setDocument(w, h, out);
        current = Math.min(keep, layers.size() - 1);
        syncBackup();
        changed();
    }

    public Layer newLayer(String name) {
        return new Layer(Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888), name);
    }

    public Layer cur() { return layers.get(current); }

    public int maxLayers() {
        long per = (long) docW * docH * 4;
        return (int) Math.max(2, Math.min(50, Runtime.getRuntime().maxMemory() * 55 / 100 / per - 2));
    }

    private void syncBackup() { backupCanvas.drawBitmap(cur().bmp, 0, 0, copy); }

    private void changed() {
        invalidate();
        if (onChange != null) onChange.run();
    }

    private void push(Edit e) {
        undoList.add(e);
        redoList.clear();
        long total = 0;
        for (Edit x : undoList) total += x.bytes();
        while (undoList.size() > 1 && (undoList.size() > MAX_UNDO || total > MAX_HISTORY_BYTES))
            total -= undoList.remove(0).bytes();
    }

    // ---------- View transform ----------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (!fitted || ow == 0) { fit(); fitted = true; }
        else { view.postTranslate((w - ow) / 2f, (h - oh) / 2f); invalidate(); }
    }

    public void fit() {
        if (docW == 0) return;
        float s = Math.min((float) getWidth() / docW, (float) getHeight() / docH) * 0.96f;
        view.setScale(s, s);
        view.postTranslate((getWidth() - docW * s) / 2f, (getHeight() - docH * s) / 2f);
        invalidate();
    }

    /** Move-tool transform expressed in canvas coordinates. */
    private Matrix layerMatrix() {
        view.invert(inverse);
        tmpM.set(view);
        tmpM.postConcat(moveT);
        tmpM.postConcat(inverse);
        return tmpM;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (docW == 0) return;
        c.save();
        c.concat(view);
        c.clipRect(0, 0, docW, docH);
        c.drawRect(0, 0, docW, docH, paper);
        for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            if (!l.visible) continue;
            layerPaint.setAlpha(l.opacity);
            if (i == current && moving) {
                c.save();
                c.concat(layerMatrix());
                c.drawBitmap(l.bmp, 0, 0, layerPaint);
                c.restore();
            } else c.drawBitmap(l.bmp, 0, 0, layerPaint);
        }
        c.restore();
    }

    // ---------- Input ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (docW == 0) return false;
        int action = e.getActionMasked();
        int type = e.getToolType(0);
        boolean isPen = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER;
        if (isPen) stylusSeen = true;

        if (tool == MOVE) return handleGesture(e, action, moveT);
        if (e.getPointerCount() >= 2 || gesture || (stylusSeen && type == MotionEvent.TOOL_TYPE_FINGER))
            return handleGesture(e, action, view);

        view.invert(inverse);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                erasing = tool == ERASE || type == MotionEvent.TOOL_TYPE_ERASER
                        || (e.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0;
                penInput = isPen;
                stroking = true;
                strokeDirty.setEmpty();
                map(e.getX(), e.getY());
                lx = pt[0]; ly = pt[1]; lp = e.getPressure();
                stamp(lx, ly, lp);
                nextDab = step(lp);
                flush();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!stroking) break;
                for (int i = 0; i < e.getHistorySize(); i++) {
                    map(e.getHistoricalX(i), e.getHistoricalY(i));
                    segmentTo(pt[0], pt[1], e.getHistoricalPressure(i));
                }
                map(e.getX(), e.getY());
                segmentTo(pt[0], pt[1], e.getPressure());
                flush();
                break;
            case MotionEvent.ACTION_UP:
                if (stroking) commitStroke();
                break;
            case MotionEvent.ACTION_CANCEL:
                abortStroke();
                break;
        }
        return true;
    }

    private void map(float x, float y) { pt[0] = x; pt[1] = y; inverse.mapPoints(pt); }

    // ---------- Brush engine ----------
    // Each stroke stamps round "dabs" into a coverage mask (max, so overlaps don't darken),
    // then the mask is composited onto the layer's pre-stroke pixels with the ink grey.

    private float pres(float p) { return penInput ? Math.max(0f, Math.min(p, 1f)) : 1f; }

    private float diameter(float p) {
        return Math.max(1f, size * (brush.minSize + (1 - brush.minSize) * pres(p)));
    }

    private float step(float p) { return Math.max(0.5f, diameter(p) * brush.spacing); }

    private void segmentTo(float x, float y, float p) {
        float dx = x - lx, dy = y - ly;
        float d = (float) Math.hypot(dx, dy);
        if (d < 0.01f) { lp = p; return; }
        while (nextDab <= d) {
            float t = nextDab / d;
            float q = lp + (p - lp) * t;
            stamp(lx + dx * t, ly + dy * t, q);
            nextDab += step(q);
        }
        nextDab -= d;
        lx = x; ly = y; lp = p;
    }

    private void stamp(float cx, float cy, float p) {
        Brush b = brush;
        float r = diameter(p) / 2f;
        float pr = pres(p);
        int a = (int) (255 * opacity * b.alpha * (b.minAlpha + (1 - b.minAlpha) * pr));
        if (a <= 0) return;
        float soft = Math.max(1f, r * (1 - b.hard));
        int x0 = Math.max(0, (int) (cx - r - 1)), x1 = Math.min(docW - 1, (int) (cx + r + 1));
        int y0 = Math.max(0, (int) (cy - r - 1)), y1 = Math.min(docH - 1, (int) (cy + r + 1));
        if (x0 > x1 || y0 > y1) return;
        float grain = b.grain;
        // Tip image: pick the mip level closest to the stamp size
        byte[] tip = null; int tw = 0, th = 0; float sc = 0, ox = 0, oy = 0;
        if (b.tip != null) {
            int lv = 0;
            while (lv + 1 < b.tip.length && Math.max(b.tw[lv + 1], b.th[lv + 1]) >= 2 * r) lv++;
            tip = b.tip[lv]; tw = b.tw[lv]; th = b.th[lv];
            sc = Math.max(tw, th) / (2 * r);
            ox = tw / 2f - 0.5f; oy = th / 2f - 0.5f;
        }
        for (int y = y0; y <= y1; y++) {
            float fy = y + 0.5f - cy;
            int row = y * docW;
            for (int x = x0; x <= x1; x++) {
                float fx = x + 0.5f - cx;
                float c;
                if (tip != null) {
                    c = sample(tip, tw, th, fx * sc + ox, fy * sc + oy) / 255f;
                } else {
                    float dist = (float) Math.sqrt(fx * fx + fy * fy);
                    c = (r - dist) / soft + (b.hard > 0.5f ? 0.5f : 0f);
                    if (c > 1) c = 1;
                    else if (c > 0 && b.hard <= 0.5f) c = c * c * (3 - 2 * c);
                }
                if (c <= 0) continue;
                if (grain > 0) {
                    int g = b.tex != null ? b.tex[(y % b.gh) * b.gw + (x % b.gw)] & 255
                                          : NOISE[((y & 127) << 7) | (x & 127)] & 255;
                    c *= 1 - grain + grain * g / 255f;
                }
                int v = (int) (a * c);
                if (v > (mask[row + x] & 255)) mask[row + x] = (byte) v;
            }
        }
        if (eventDirty.isEmpty()) eventDirty.set(x0, y0, x1 + 1, y1 + 1);
        else eventDirty.union(x0, y0, x1 + 1, y1 + 1);
    }

    /** Composites the mask over the backup into the layer, for the area touched since last flush. */
    private void flush() {
        Rect r = eventDirty;
        if (r.isEmpty()) return;
        int w = r.width(), h = r.height();
        if (buf.length < w * h) buf = new int[w * h];
        backup.getPixels(buf, 0, w, r.left, r.top, w, h);
        int ink = grey;
        for (int y = 0; y < h; y++) {
            int mrow = (r.top + y) * docW + r.left, brow = y * w;
            for (int x = 0; x < w; x++) {
                int m = mask[mrow + x] & 255;
                if (m == 0) continue;
                int px = buf[brow + x];
                int A = px >>> 24, G = px & 255;
                int nA, nG;
                if (erasing) { nA = A * (255 - m) / 255; nG = G; }
                else {
                    int keep = A * (255 - m) / 255;
                    nA = m + keep;
                    nG = nA == 0 ? 0 : (ink * m + G * keep) / nA;
                }
                buf[brow + x] = (nA << 24) | (nG * 0x010101);
            }
        }
        cur().bmp.setPixels(buf, 0, w, r.left, r.top, w, h);
        if (strokeDirty.isEmpty()) strokeDirty.set(r); else strokeDirty.union(r);
        r.setEmpty();
        cur().dirty = true;
        invalidate();
    }

    /** Bilinear sample of a greyscale image, 0 outside. */
    private static float sample(byte[] t, int w, int h, float u, float v) {
        int x = (int) Math.floor(u), y = (int) Math.floor(v);
        float fx = u - x, fy = v - y;
        float a = px(t, w, h, x, y), b = px(t, w, h, x + 1, y);
        float c = px(t, w, h, x, y + 1), d = px(t, w, h, x + 1, y + 1);
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
    }

    private static int px(byte[] t, int w, int h, int x, int y) {
        return x < 0 || y < 0 || x >= w || y >= h ? 0 : t[y * w + x] & 255;
    }

    private void clearMask(Rect r) {
        for (int y = r.top; y < r.bottom; y++)
            java.util.Arrays.fill(mask, y * docW + r.left, y * docW + r.right, (byte) 0);
    }

    private void commitStroke() {
        stroking = false;
        Rect r = new Rect(strokeDirty);
        if (r.isEmpty()) return;
        clearMask(r);
        // "before" pixels come from the backup copy
        Layer l = cur();
        Bitmap real = l.bmp;
        l.bmp = backup;
        PixelEdit pe = new PixelEdit(l, r);
        l.bmp = real;
        push(pe);
        backupCanvas.drawBitmap(real, r, r, copy);
        changed();
    }

    private void abortStroke() {
        if (!stroking) return;
        stroking = false;
        flush();
        Rect r = strokeDirty;
        if (!r.isEmpty()) {
            clearMask(r);
            new Canvas(cur().bmp).drawBitmap(backup, r, r, copy);
        }
        invalidate();
    }

    private boolean handleGesture(MotionEvent e, int action, Matrix target) {
        if (!gesture) {
            gesture = true;
            abortStroke();
            if (target == moveT) moving = true;
            gStart = e.getEventTime();
            gMoved = 0;
            gMax = e.getPointerCount();
            reset(e, -1);
            return true;
        }
        gMax = Math.max(gMax, e.getPointerCount());
        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN: reset(e, -1); break;
            case MotionEvent.ACTION_POINTER_UP: reset(e, e.getActionIndex()); break;
            case MotionEvent.ACTION_MOVE: {
                float[] m = state(e, -1);
                float dx = m[0] - gX, dy = m[1] - gY;
                target.postTranslate(dx, dy);
                gMoved += Math.abs(dx) + Math.abs(dy);
                if (gDist > 0 && m[2] > 0) {
                    float s = m[2] / gDist;
                    float a = m[3] - gAngle;
                    if (a > 180) a -= 360; else if (a < -180) a += 360;
                    target.postScale(s, s, m[0], m[1]);
                    target.postRotate(a, m[0], m[1]);
                    gMoved += Math.abs(m[2] - gDist);
                }
                gX = m[0]; gY = m[1]; gDist = m[2]; gAngle = m[3];
                invalidate();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                gesture = false;
                if (target == view && action == MotionEvent.ACTION_UP
                        && e.getEventTime() - gStart < 300 && gMoved < 40) {
                    if (gMax == 2) undo();
                    else if (gMax == 3) redo();
                }
                break;
        }
        return true;
    }

    private void reset(MotionEvent e, int skip) {
        float[] m = state(e, skip);
        gX = m[0]; gY = m[1]; gDist = m[2]; gAngle = m[3];
    }

    /** Midpoint x, y, average spread and angle of the first two pointers (excluding skip). */
    private static float[] state(MotionEvent e, int skip) {
        float x = 0, y = 0; int n = 0;
        float ax = 0, ay = 0, bx = 0, by = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            float px = e.getX(i), py = e.getY(i);
            if (n == 0) { ax = px; ay = py; } else if (n == 1) { bx = px; by = py; }
            x += px; y += py; n++;
        }
        if (n == 0) return new float[4];
        x /= n; y /= n;
        float d = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            d += (float) Math.hypot(e.getX(i) - x, e.getY(i) - y);
        }
        float ang = n > 1 ? (float) Math.toDegrees(Math.atan2(by - ay, bx - ax)) : 0;
        return new float[]{x, y, n > 1 ? d / n : 0, ang};
    }

    // ---------- Commands ----------

    /** Bakes a pending move/scale/rotate into the layer pixels. */
    public void commitMove() {
        if (!moving) return;
        moving = false;
        Layer l = cur();
        Matrix m = new Matrix(layerMatrix());
        moveT.reset();
        if (m.isIdentity()) { invalidate(); return; }
        push(new PixelEdit(l, new Rect(0, 0, docW, docH)));
        Bitmap src = l.bmp.copy(Bitmap.Config.ARGB_8888, false);
        l.bmp.eraseColor(0);
        new Canvas(l.bmp).drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        src.recycle();
        l.dirty = true;
        syncBackup();
        changed();
    }

    public void setTool(int t) { commitMove(); tool = t; }

    private void afterHistory() {
        if (current >= layers.size()) current = layers.size() - 1;
        for (Layer l : layers) l.dirty = true;
        syncBackup();
        changed();
    }

    public void undo() {
        commitMove();
        if (undoList.isEmpty()) return;
        Edit e = undoList.remove(undoList.size() - 1);
        e.undo();
        redoList.add(e);
        afterHistory();
    }

    public void redo() {
        commitMove();
        if (redoList.isEmpty()) return;
        Edit e = redoList.remove(redoList.size() - 1);
        e.redo();
        undoList.add(e);
        afterHistory();
    }

    private void apply(Edit e) { e.redo(); push(e); afterHistory(); }

    public void select(int i) { commitMove(); current = i; syncBackup(); changed(); }

    public boolean addLayer(Layer l) {
        commitMove();
        if (layers.size() >= maxLayers()) return false;
        int at = current + 1;
        apply(new Op(() -> { layers.remove(l); current = at - 1; },
                     () -> { layers.add(at, l); current = at; }, 64));
        return true;
    }

    public boolean duplicate() {
        Layer s = cur();
        Layer d = new Layer(s.bmp.copy(Bitmap.Config.ARGB_8888, true), s.name + " copy");
        d.opacity = s.opacity;
        return addLayer(d);
    }

    public void deleteLayer() {
        commitMove();
        if (layers.size() < 2) { clearLayer(); return; }
        int at = current;
        Layer l = cur();
        apply(new Op(() -> { layers.add(at, l); current = at; },
                     () -> { layers.remove(at); current = Math.max(0, at - 1); }, 64));
    }

    public void moveLayer(int dir) {
        commitMove();
        int a = current, b = current + dir;
        if (b < 0 || b >= layers.size()) return;
        apply(new Op(() -> { Collections.swap(layers, a, b); current = a; },
                     () -> { Collections.swap(layers, a, b); current = b; }, 64));
    }

    public void mergeDown() {
        commitMove();
        int at = current;
        if (at == 0) return;
        Layer up = layers.get(at), down = layers.get(at - 1);
        PixelEdit pe = new PixelEdit(down, new Rect(0, 0, docW, docH));
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setAlpha(up.visible ? up.opacity : 0);
        new Canvas(down.bmp).drawBitmap(up.bmp, 0, 0, p);
        Runnable r = () -> { layers.remove(up); current = at - 1; };
        push(new Op(() -> { pe.undo(); layers.add(at, up); current = at; },
                    () -> { pe.redo(); r.run(); }, pe.bytes()));
        r.run();
        afterHistory();
    }

    public void clearLayer() {
        commitMove();
        push(new PixelEdit(cur(), new Rect(0, 0, docW, docH)));
        cur().bmp.eraseColor(0);
        afterHistory();
    }

    public void toggleVisible(int i) {
        Layer l = layers.get(i);
        boolean v = l.visible;
        apply(new Op(() -> l.visible = v, () -> l.visible = !v, 64));
    }

    /** Live opacity preview; call commitOpacity when the slider is released. */
    public void previewOpacity(int o) { cur().opacity = o; invalidate(); }

    public void commitOpacity(int before) {
        Layer l = cur();
        int after = l.opacity;
        if (after != before) push(new Op(() -> l.opacity = before, () -> l.opacity = after, 64));
        changed();
    }

    /** Adds an image as a new layer, scaled to fit and centred. */
    public boolean importLayer(Bitmap src, String name) {
        Layer l = newLayer(name);
        float s = Math.min(1f, Math.min((float) docW / src.getWidth(), (float) docH / src.getHeight()));
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((docW - src.getWidth() * s) / 2f, (docH - src.getHeight() * s) / 2f);
        new Canvas(l.bmp).drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        return addLayer(l);
    }

    /** All visible layers flattened onto white paper. */
    public Bitmap flatten() {
        commitMove();
        Bitmap out = Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (Layer l : layers) {
            if (!l.visible) continue;
            p.setAlpha(l.opacity);
            c.drawBitmap(l.bmp, 0, 0, p);
        }
        return out;
    }

    // ---------- Pixel packing for undo ----------

    static byte[] pack(Bitmap b, Rect r) {
        int[] px = new int[r.width() * r.height()];
        b.getPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height());
        ByteBuffer bb = ByteBuffer.allocate(px.length * 4);
        bb.asIntBuffer().put(px);
        Deflater d = new Deflater(1);
        d.setInput(bb.array());
        d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[65536];
        while (!d.finished()) o.write(buf, 0, d.deflate(buf));
        d.end();
        return o.toByteArray();
    }

    static void unpack(Bitmap b, Rect r, byte[] data) {
        byte[] raw = new byte[r.width() * r.height() * 4];
        Inflater in = new Inflater();
        in.setInput(data);
        try {
            int n = 0;
            while (n < raw.length && !in.finished()) n += in.inflate(raw, n, raw.length - n);
        } catch (Exception ignored) { }
        in.end();
        int[] px = new int[r.width() * r.height()];
        ByteBuffer.wrap(raw).asIntBuffer().get(px);
        b.setPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height());
    }
}
