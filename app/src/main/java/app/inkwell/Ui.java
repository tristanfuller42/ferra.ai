package app.inkwell;

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

    // ---------------- Popovers (Procreate-style panels instead of Android menus) ----------------

    /** A rounded dark panel anchored to a button. */
    static final class Pop {
        static Pop latest; // for tests
        static final java.util.ArrayList<Pop> open = new java.util.ArrayList<>();
        final android.widget.PopupWindow win;
        final android.widget.LinearLayout box;
        final Context c;
        final float dp;

        Pop(Context c, int widthDp) {
            this.c = c;
            dp = c.getResources().getDisplayMetrics().density;
            box = new android.widget.LinearLayout(c);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            box.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
            android.widget.ScrollView sv = new android.widget.ScrollView(c);
            sv.addView(box);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(PANEL);
            bg.setCornerRadius(14 * dp);
            win = new android.widget.PopupWindow(sv, (int) (widthDp * dp), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
            win.setBackgroundDrawable(bg);
            win.setElevation(18 * dp);
            win.setOutsideTouchable(true);
            latest = this;
        }

        android.widget.TextView title(String t) {
            android.widget.TextView v = new android.widget.TextView(c);
            v.setText(t);
            v.setTextColor(Color.WHITE);
            v.setTextSize(17);
            v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            v.setPadding((int) (16 * dp), (int) (8 * dp), (int) (16 * dp), (int) (8 * dp));
            box.addView(v);
            return v;
        }

        android.widget.TextView section(String t) {
            android.widget.TextView v = new android.widget.TextView(c);
            v.setText(t.toUpperCase());
            v.setTextColor(0xFF8E8E93);
            v.setTextSize(12);
            v.setPadding((int) (16 * dp), (int) (10 * dp), (int) (16 * dp), (int) (2 * dp));
            box.addView(v);
            return v;
        }

        /** A tappable row; the panel closes after the action unless keepOpen. */
        android.widget.TextView item(String label, boolean enabled, Runnable r) {
            android.widget.TextView v = new android.widget.TextView(c);
            v.setText(label);
            v.setTextSize(16);
            v.setTextColor(enabled ? 0xFFF2F2F7 : 0xFF636366);
            v.setPadding((int) (16 * dp), (int) (11 * dp), (int) (16 * dp), (int) (11 * dp));
            if (enabled) v.setOnClickListener(x -> { win.dismiss(); r.run(); });
            box.addView(v, new android.widget.LinearLayout.LayoutParams(-1, -2));
            return v;
        }

        /** A row with an on/off switch, like Procreate's toggles. */
        android.widget.Switch toggle(String label, boolean on, java.util.function.Consumer<Boolean> r) {
            android.widget.Switch sw = new android.widget.Switch(c);
            sw.setText(label);
            sw.setTextSize(16);
            sw.setTextColor(0xFFF2F2F7);
            sw.setChecked(on);
            sw.setPadding((int) (16 * dp), (int) (8 * dp), (int) (16 * dp), (int) (8 * dp));
            sw.setOnCheckedChangeListener((b, v) -> r.accept(v));
            box.addView(sw, new android.widget.LinearLayout.LayoutParams(-1, -2));
            return sw;
        }

        void add(View v) { box.addView(v); }

        void show(View anchor) {
            try { win.showAsDropDown(anchor, 0, (int) (4 * dp)); } catch (Exception e) { /* window not ready */ }
            open.removeIf(p -> !p.win.isShowing());
            if (win.isShowing() && !open.contains(this)) open.add(this);
        }
    }

    /**
     * Drop-in replacement for android.widget.PopupMenu that shows a Procreate-style popover.
     * Supports items, enabled/checked state and inline sub-sections.
     */
    static final class PopMenu {
        static PopMenu latest; // for tests
        interface Listener { boolean onMenuItemClick(Item it); }

        final class Item {
            final int id; final CharSequence title; boolean enabled = true, checkable, checked; final String section;
            Item(int id, CharSequence t, String section) { this.id = id; title = t; this.section = section; }
            int getItemId() { return id; }
            CharSequence getTitle() { return title; }
            Item setEnabled(boolean e) { enabled = e; return this; }
            Item setCheckable(boolean c) { checkable = c; return this; }
            Item setChecked(boolean c) { checked = c; return this; }
        }

        final class Sub {
            final String name;
            Sub(String n) { name = n; }
            Item add(int group, int id, int order, CharSequence t) { Item i = new Item(id, t, name); items.add(i); return i; }
        }

        final Context c; final View anchor;
        final java.util.ArrayList<Item> items = new java.util.ArrayList<>();
        Listener listener;
        Pop pop;

        PopMenu(Context c, View anchor) { this.c = c; this.anchor = anchor; latest = this; }
        PopMenu(Context c, View anchor, int gravity) { this(c, anchor); }

        PopMenu getMenu() { return this; }
        Item add(int group, int id, int order, CharSequence t) { Item i = new Item(id, t, null); items.add(i); return i; }
        Sub addSubMenu(int group, int id, int order, CharSequence t) { return new Sub(String.valueOf(t)); }
        int size() { return items.size(); }
        Item getItem(int i) { return items.get(i); }
        Item findItem(int id) { for (Item i : items) if (i.id == id) return i; return null; }
        void setOnMenuItemClickListener(Listener l) { listener = l; }

        void click(Item it) { if (listener != null) listener.onMenuItemClick(it); }

        void show() {
            pop = new Pop(c, 300);
            String sec = null;
            for (Item it : items) {
                if (it.section != null && !it.section.equals(sec)) pop.section(it.section);
                sec = it.section;
                if (it.checkable) pop.toggle(String.valueOf(it.title), it.checked, v -> { pop.win.dismiss(); click(it); });
                else pop.item(String.valueOf(it.title), it.enabled, () -> click(it));
            }
            pop.show(anchor);
        }
    }
}
