package ai.inkwell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Tiny hand-drawn UI pieces: icon buttons and a vertical slider. No image assets. */
final class Ui {
    static final int BAR = 0xFF1C1C1E, PANEL = 0xFF3A3A3C, ACCENT = 0xFF3D9BFF, ICON = 0xFFE8E8E8;

    static final int MENU = 0, MOVE = 1, BRUSH = 2, ERASER = 3, LAYERS = 4, UNDO = 5, REDO = 6, PICK = 7, COLOR = 8, ADJUST = 9, SELECT = 10, SMUDGE = 11;

    /** Square icon button. Icons are drawn in a 24x24 box. */
    static final class Icon extends View {
        final int kind;
        boolean on;
        int grey;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Icon(Context c, int kind) {
            super(c);
            this.kind = kind;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
        }

        void setOn(boolean v) { on = v; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 36f;
            c.save();
            c.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
            c.scale(s, s);
            int col = on ? ACCENT : ICON;
            if (isPressed()) col = Color.WHITE;
            p.setColor(col);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.8f);
            path.reset();
            switch (kind) {
                case MENU: // three sliders
                    for (int i = 0; i < 3; i++) {
                        float y = 6 + i * 6;
                        c.drawLine(3, y, 21, y, p);
                        p.setStyle(Paint.Style.FILL);
                        c.drawCircle(i == 1 ? 15 : 8, y, 2.4f, p);
                        p.setStyle(Paint.Style.STROKE);
                    }
                    break;
                case MOVE: // arrow cursor
                    path.moveTo(6, 3); path.lineTo(6, 19); path.lineTo(10.5f, 14.5f);
                    path.lineTo(13.5f, 21); path.lineTo(16, 20); path.lineTo(13, 13.5f);
                    path.lineTo(19, 13.5f); path.close();
                    c.drawPath(path, p);
                    break;
                case BRUSH: // pen nib
                    path.moveTo(4, 20); path.lineTo(7, 13); path.lineTo(17, 3); path.lineTo(21, 7);
                    path.lineTo(11, 17); path.close();
                    c.drawPath(path, p);
                    c.drawLine(7, 13, 11, 17, p);
                    break;
                case ERASER:
                    c.save(); c.rotate(-45, 12, 12);
                    c.drawRoundRect(new RectF(4, 8, 20, 16), 2, 2, p);
                    c.drawLine(10, 8, 10, 16, p);
                    c.restore();
                    c.drawLine(9, 21, 20, 21, p);
                    break;
                case LAYERS:
                    for (int i = 0; i < 2; i++) {
                        float o = i * 5;
                        path.reset();
                        path.moveTo(12, 4 + o); path.lineTo(21, 9 + o); path.lineTo(12, 14 + o); path.lineTo(3, 9 + o); path.close();
                        c.drawPath(path, p);
                    }
                    break;
                case UNDO:
                case REDO:
                    if (kind == REDO) { c.scale(-1, 1, 12, 12); }
                    path.moveTo(9, 6); path.lineTo(4, 11); path.lineTo(9, 16);
                    c.drawPath(path, p);
                    path.reset();
                    path.moveTo(4, 11); path.lineTo(14, 11);
                    path.quadTo(20, 11, 20, 16); path.quadTo(20, 20, 15, 20);
                    c.drawPath(path, p);
                    break;
                case PICK: // eyedropper
                    c.drawLine(5, 19, 14, 10, p);
                    p.setStrokeWidth(3.2f);
                    c.drawLine(14, 10, 18, 6, p);
                    p.setStrokeWidth(1.8f);
                    c.drawLine(12, 7, 17, 12, p);
                    break;
                case ADJUST: // magic wand
                    c.drawLine(4, 20, 15, 9, p);
                    c.drawLine(17, 3, 17, 7, p); c.drawLine(15, 5, 19, 5, p);
                    c.drawLine(20, 10, 20, 12, p); c.drawLine(19, 11, 21, 11, p);
                    c.drawLine(10, 4, 10, 6, p); c.drawLine(9, 5, 11, 5, p);
                    break;
                case SELECT: // dashed lasso
                    p.setPathEffect(new android.graphics.DashPathEffect(new float[]{2.5f, 2.5f}, 0));
                    c.drawOval(new RectF(3, 4, 21, 15), p);
                    p.setPathEffect(null);
                    path.moveTo(7, 14); path.quadTo(5, 18, 9, 20);
                    c.drawPath(path, p);
                    break;
                case SMUDGE: // fingertip dragging a streak
                    path.moveTo(3, 19); path.quadTo(9, 15, 13, 17); path.quadTo(17, 19, 21, 15);
                    c.drawPath(path, p);
                    c.drawRoundRect(new RectF(11, 3, 17, 13), 3, 3, p);
                    break;
                case COLOR:
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.rgb(grey, grey, grey));
                    c.drawCircle(12, 12, 9, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setColor(on ? ACCENT : ICON);
                    c.drawCircle(12, 12, 10.5f, p);
                    break;
            }
            c.restore();
        }

        @Override
        protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    }

    /** Curves editor: tap to add a point, drag to move it, drag a point off the side to remove it. */
    static final class CurveView extends View {
        final java.util.ArrayList<float[]> pts = new java.util.ArrayList<>();
        Runnable listener;
        private int drag = -1;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        CurveView(Context c) { super(c); reset(); }

        void reset() {
            pts.clear();
            pts.add(new float[]{0, 0});
            pts.add(new float[]{1, 1});
            invalidate();
        }

        float[][] arrays() {
            pts.sort((a, b) -> Float.compare(a[0], b[0]));
            float[] xs = new float[pts.size()], ys = new float[pts.size()];
            for (int i = 0; i < xs.length; i++) { xs[i] = pts.get(i)[0]; ys[i] = pts.get(i)[1]; }
            return new float[][]{xs, ys};
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF1C1C1E);
            c.drawRect(0, 0, w, h, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(0xFF48484A);
            for (int i = 1; i < 4; i++) { c.drawLine(w * i / 4, 0, w * i / 4, h, p); c.drawLine(0, h * i / 4, w, h * i / 4, p); }
            float[][] a = arrays();
            int[] lut = Fx.curve(a[0], a[1]);
            Path path = new Path();
            for (int i = 0; i < 256; i++) {
                float x = w * i / 255f, y = h - h * lut[i] / 255f;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            p.setStrokeWidth(2 * d);
            p.setColor(Color.WHITE);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            for (float[] q : pts) c.drawCircle(q[0] * w, h - q[1] * h, 6 * d, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            float x = e.getX() / w, y = 1 - e.getY() / h;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    drag = -1;
                    for (int i = 0; i < pts.size(); i++)
                        if (Math.hypot((pts.get(i)[0] - x) * w, (pts.get(i)[1] - y) * h) < 24 * d) drag = i;
                    if (drag < 0) { pts.add(new float[]{clamp(x), clamp(y)}); drag = pts.size() - 1; }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (drag < 0) break;
                    if ((x < -0.15f || x > 1.15f) && pts.size() > 2) { pts.remove(drag); drag = -1; break; }
                    pts.get(drag)[0] = clamp(x);
                    pts.get(drag)[1] = clamp(y);
                    break;
                default:
                    drag = -1;
            }
            invalidate();
            if (listener != null) listener.run();
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }

        private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
    }

    /** Vertical slider, value 0 (bottom) .. 1 (top). */
    static final class VSlider extends View {
        interface Listener { void changed(float v, boolean done); }
        float value;
        Listener listener;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        VSlider(Context c, float v) { super(c); value = v; }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            float tw = 6 * d, pad = 8 * d;
            r.set((w - tw) / 2, pad, (w + tw) / 2, h - pad);
            p.setColor(0xFF48484A);
            c.drawRoundRect(r, tw / 2, tw / 2, p);
            float y = h - pad - value * (h - 2 * pad);
            r.top = y;
            p.setColor(0xFFBDBDBD);
            c.drawRoundRect(r, tw / 2, tw / 2, p);
            p.setColor(Color.WHITE);
            r.set(w / 2 - 11 * d, y - 6 * d, w / 2 + 11 * d, y + 6 * d);
            c.drawRoundRect(r, 4 * d, 4 * d, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float pad = 8 * getResources().getDisplayMetrics().density;
            value = Math.max(0f, Math.min(1f, 1 - (e.getY() - pad) / (getHeight() - 2 * pad)));
            boolean done = e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL;
            if (listener != null) listener.changed(value, done);
            invalidate();
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
    }
}
