package ai.inkwell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.MotionEvent;
import android.view.View;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Black-and-white canvas. Ink lives in an ALPHA_8 bitmap (1 byte/pixel):
 * 0 = white paper, 255 = black ink. Undo replays recent strokes on top of a
 * "base" bitmap that holds everything older.
 */
public class CanvasView extends View {
    private static final int MAX_UNDO = 40;

    /** One stroke: flat array of x,y,width triples. erase=true removes ink. clear=true wipes all. */
    private static final class Stroke {
        float[] pts = new float[96];
        int n;
        boolean erase, clear;

        void add(float x, float y, float w) {
            if (n + 3 > pts.length) pts = java.util.Arrays.copyOf(pts, pts.length * 2);
            pts[n++] = x; pts[n++] = y; pts[n++] = w;
        }
    }

    private Bitmap ink, base;
    private Canvas inkCanvas;
    private final ArrayList<Stroke> strokes = new ArrayList<>();
    private final ArrayList<Stroke> redo = new ArrayList<>();
    private Stroke cur;

    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eraser = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inkPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint copy = new Paint();
    private final Paint paper = new Paint();

    private final Matrix view = new Matrix(), inverse = new Matrix();
    private final float[] tmp = new float[2];
    private boolean fitted;

    public boolean eraseMode;
    public float size = 12f;           // brush diameter in canvas pixels
    private boolean stylusSeen;        // once a pen is used, fingers only pan/zoom

    // Two-finger gesture state
    private boolean gesture;
    private float gDist, gMidX, gMidY, gMoved;
    private long gStart;
    private int gMaxPointers;

    public CanvasView(Context c, int w, int h) {
        super(c);
        ink = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        base = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        inkCanvas = new Canvas(ink);
        for (Paint p : new Paint[]{pen, eraser}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Color.BLACK);
        }
        eraser.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        copy.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        inkPaint.setColor(Color.BLACK);
        paper.setColor(Color.WHITE);
        setBackgroundColor(0xFF2A2A2A);
    }

    // ---------- View transform ----------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (!fitted || ow == 0) { fit(); fitted = true; }
        else { // keep the same canvas point centred when the screen folds/unfolds
            view.postTranslate((w - ow) / 2f, (h - oh) / 2f);
            invalidate();
        }
    }

    public void fit() {
        float s = Math.min((float) getWidth() / ink.getWidth(), (float) getHeight() / ink.getHeight());
        view.setScale(s, s);
        view.postTranslate((getWidth() - ink.getWidth() * s) / 2f, (getHeight() - ink.getHeight() * s) / 2f);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.save();
        c.concat(view);
        c.drawRect(0, 0, ink.getWidth(), ink.getHeight(), paper);
        c.drawBitmap(ink, 0, 0, inkPaint);
        c.restore();
    }

    // ---------- Input ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int tool = e.getToolType(0);
        boolean pen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
        if (pen) stylusSeen = true;

        if (e.getPointerCount() >= 2 || gesture || (stylusSeen && !pen && tool == MotionEvent.TOOL_TYPE_FINGER)) {
            return handleGesture(e, action);
        }

        view.invert(inverse);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                cur = new Stroke();
                cur.erase = eraseMode || tool == MotionEvent.TOOL_TYPE_ERASER
                        || (e.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0;
                addPoint(e.getX(), e.getY(), e.getPressure(), pen);
                break;
            case MotionEvent.ACTION_MOVE:
                if (cur == null) break;
                for (int i = 0; i < e.getHistorySize(); i++)
                    addPoint(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalPressure(i), pen);
                addPoint(e.getX(), e.getY(), e.getPressure(), pen);
                break;
            case MotionEvent.ACTION_UP:
                if (cur != null) commit();
                break;
            case MotionEvent.ACTION_CANCEL:
                if (cur != null) { cur = null; rebuild(); }
                break;
        }
        return true;
    }

    private void addPoint(float sx, float sy, float pressure, boolean pen) {
        tmp[0] = sx; tmp[1] = sy;
        inverse.mapPoints(tmp);
        float w = pen ? size * (0.15f + 0.85f * Math.min(pressure, 1f)) : size;
        cur.add(tmp[0], tmp[1], w);
        drawSegment(cur, cur.n - 3);
        invalidate();
    }

    /** Draws the segment ending at triple index i (or a dot if it's the first point). */
    private void drawSegment(Stroke s, int i) {
        Paint p = s.erase ? eraser : pen;
        float[] a = s.pts;
        float w = a[i + 2];
        if (s.erase) w *= 2f; // eraser is a bit bigger than the pen
        p.setStrokeWidth(w);
        if (i == 0) inkCanvas.drawPoint(a[0], a[1], p);
        else inkCanvas.drawLine(a[i - 3], a[i - 2], a[i], a[i + 1], p);
    }

    private void drawStroke(Stroke s) {
        if (s.clear) { ink.eraseColor(0); return; }
        for (int i = 0; i < s.n; i += 3) drawSegment(s, i);
    }

    private void commit() {
        push(cur);
        cur = null;
    }

    private void push(Stroke s) {
        strokes.add(s);
        redo.clear();
        if (strokes.size() > MAX_UNDO) { // bake oldest stroke into base
            Stroke old = strokes.remove(0);
            Canvas bc = new Canvas(base);
            Canvas saved = inkCanvas;
            inkCanvas = bc;
            if (old.clear) base.eraseColor(0); else drawStroke(old);
            inkCanvas = saved;
        }
    }

    private boolean handleGesture(MotionEvent e, int action) {
        if (!gesture) {
            gesture = true;
            if (cur != null) { cur = null; rebuild(); } // abort stroke that turned into a pinch
            gStart = e.getEventTime();
            gMoved = 0;
            gMaxPointers = e.getPointerCount();
            resetGesture(e, -1);
            return true;
        }
        gMaxPointers = Math.max(gMaxPointers, e.getPointerCount());
        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN:
                resetGesture(e, -1);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                resetGesture(e, e.getActionIndex());
                break;
            case MotionEvent.ACTION_MOVE: {
                float[] m = mid(e, -1);
                float dx = m[0] - gMidX, dy = m[1] - gMidY;
                view.postTranslate(dx, dy);
                if (gDist > 0 && m[2] > 0) {
                    float s = m[2] / gDist;
                    view.postScale(s, s, m[0], m[1]);
                    gMoved += Math.abs(m[2] - gDist);
                }
                gMoved += Math.abs(dx) + Math.abs(dy);
                gMidX = m[0]; gMidY = m[1]; gDist = m[2];
                invalidate();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                gesture = false;
                // Quick tap with fingers, no movement: 2 fingers = undo, 3 = redo
                if (action == MotionEvent.ACTION_UP && e.getEventTime() - gStart < 300 && gMoved < 40) {
                    if (gMaxPointers == 2) undo();
                    else if (gMaxPointers == 3) redo();
                }
                break;
        }
        return true;
    }

    private void resetGesture(MotionEvent e, int skip) {
        float[] m = mid(e, skip);
        gMidX = m[0]; gMidY = m[1]; gDist = m[2];
    }

    /** Midpoint x, y and average spread of all pointers except index skip. */
    private static float[] mid(MotionEvent e, int skip) {
        float x = 0, y = 0; int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            x += e.getX(i); y += e.getY(i); n++;
        }
        if (n == 0) return new float[]{0, 0, 0};
        x /= n; y /= n;
        float d = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            d += (float) Math.hypot(e.getX(i) - x, e.getY(i) - y);
        }
        return new float[]{x, y, n > 1 ? d / n : 0};
    }

    // ---------- Commands ----------

    private void rebuild() {
        inkCanvas.drawBitmap(base, 0, 0, copy);
        for (Stroke s : strokes) drawStroke(s);
        invalidate();
    }

    public void undo() {
        if (strokes.isEmpty()) return;
        redo.add(strokes.remove(strokes.size() - 1));
        rebuild();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        Stroke s = redo.remove(redo.size() - 1);
        strokes.add(s);
        drawStroke(s);
        invalidate();
    }

    public void clear() {
        Stroke s = new Stroke();
        s.clear = true;
        push(s);
        drawStroke(s);
        invalidate();
    }

    /** Flattened image: white paper + black ink. */
    public Bitmap export() {
        Bitmap out = Bitmap.createBitmap(ink.getWidth(), ink.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        c.drawBitmap(ink, 0, 0, inkPaint);
        return out;
    }

    // ---------- Autosave (raw alpha bytes, deflated) ----------

    public void save(File f) {
        final int w = ink.getWidth(), h = ink.getHeight();
        final ByteBuffer buf = ByteBuffer.allocate(ink.getByteCount());
        ink.copyPixelsToBuffer(buf);
        new Thread(() -> {
            File t = new File(f.getPath() + ".tmp");
            try (DataOutputStream o = new DataOutputStream(new DeflaterOutputStream(new FileOutputStream(t)))) {
                o.writeInt(w); o.writeInt(h); o.writeInt(buf.capacity());
                o.write(buf.array());
            } catch (Exception ignored) { return; }
            t.renameTo(f);
        }).start();
    }

    public void load(File f) {
        if (!f.exists()) return;
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new FileInputStream(f)))) {
            int w = in.readInt(), h = in.readInt(), len = in.readInt();
            if (w != ink.getWidth() || h != ink.getHeight() || len != ink.getByteCount()) return;
            byte[] b = new byte[len];
            in.readFully(b);
            base.copyPixelsFromBuffer(ByteBuffer.wrap(b));
            strokes.clear(); redo.clear();
            rebuild();
        } catch (Exception ignored) { }
    }
}
