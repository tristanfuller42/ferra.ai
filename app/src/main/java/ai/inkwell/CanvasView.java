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
import android.graphics.RectF;
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

    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint copy = new Paint();
    private final Paint paper = new Paint();
    private final PorterDuffXfermode clearMode = new PorterDuffXfermode(PorterDuff.Mode.CLEAR);

    // Stroke state
    private Canvas strokeCanvas;
    private boolean stroking;
    private float lx, ly;
    private final RectF dirty = new RectF();

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
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeCap(Paint.Cap.ROUND);
        pen.setStrokeJoin(Paint.Join.ROUND);
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
        syncBackup();
        if (getWidth() > 0) fit();
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
            case MotionEvent.ACTION_DOWN: {
                boolean erase = tool == ERASE || type == MotionEvent.TOOL_TYPE_ERASER
                        || (e.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0;
                pen.setXfermode(erase ? clearMode : null);
                pen.setColor(Color.rgb(grey, grey, grey));
                strokeCanvas = new Canvas(cur().bmp);
                stroking = true;
                map(e.getX(), e.getY());
                lx = pt[0]; ly = pt[1];
                dirty.set(lx, ly, lx, ly);
                segment(lx, ly, e.getPressure(), isPen, erase);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (!stroking) break;
                boolean erase = pen.getXfermode() != null;
                for (int i = 0; i < e.getHistorySize(); i++) {
                    map(e.getHistoricalX(i), e.getHistoricalY(i));
                    segment(pt[0], pt[1], e.getHistoricalPressure(i), isPen, erase);
                }
                map(e.getX(), e.getY());
                segment(pt[0], pt[1], e.getPressure(), isPen, erase);
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

    private void segment(float x, float y, float pressure, boolean isPen, boolean erase) {
        float w = isPen ? size * (0.15f + 0.85f * Math.min(pressure, 1f)) : size;
        if (erase) w *= 2f;
        pen.setStrokeWidth(w);
        if (x == lx && y == ly) strokeCanvas.drawPoint(x, y, pen);
        else strokeCanvas.drawLine(lx, ly, x, y, pen);
        dirty.union(x - w, y - w, x + w, y + w);
        dirty.union(lx - w, ly - w, lx + w, ly + w);
        lx = x; ly = y;
        cur().dirty = true;
        invalidate();
    }

    private Rect dirtyRect() {
        Rect r = new Rect();
        dirty.roundOut(r);
        r.inset(-2, -2);
        return r.intersect(0, 0, docW, docH) ? r : null;
    }

    private void commitStroke() {
        stroking = false;
        Rect r = dirtyRect();
        if (r == null) return;
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
        Rect r = dirtyRect();
        if (r != null) new Canvas(cur().bmp).drawBitmap(backup, r, r, copy);
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
