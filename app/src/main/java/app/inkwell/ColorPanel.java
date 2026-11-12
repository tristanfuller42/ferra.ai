package app.inkwell;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The Colour panel, like Procreate's: Disc, Classic, Harmony, Value and Palettes (pick one with the tabs; it is
 * remembered), current/previous colour, the default palette and recent colours at the bottom of every tab.
 */
public final class ColorPanel {
    static final String[] TABS = {"Disc", "Classic", "Harmony", "Value", "Palettes"};
    static final String[] HARMONY = {"Complementary", "Split", "Analogous", "Triadic", "Tetradic"};

    /** A named list of colours. */
    public static final class Palette {
        public String name;
        public final ArrayList<Integer> colors = new ArrayList<>();
        public Palette(String n) { name = n; }
    }

    private final MainActivity a;
    private final SharedPreferences prefs;
    private final float dp;
    private final File file;
    public final ArrayList<Palette> palettes = new ArrayList<>();
    private int color, previous;
    private final float[] hsv = new float[3];
    private java.util.function.IntConsumer onColor;
    Ui.Pop pop;
    private LinearLayout body;
    private View swatch;
    private final ArrayList<View> live = new ArrayList<>(); // views to redraw when the colour changes
    private Runnable syncValues; // Value tab sliders follow the colour

    public ColorPanel(MainActivity a, SharedPreferences prefs, float dp) {
        this.a = a; this.prefs = prefs; this.dp = dp;
        file = new File(a.getFilesDir(), "palettes.txt");
        load();
    }

    // ---------------- Palettes on disk: one per line, "name<TAB>#rrggbb,#rrggbb,…" ----------------

    void load() {
        palettes.clear();
        try {
            for (String line : new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8").split("\n")) {
                if (line.isEmpty()) continue;
                String[] f = line.split("\t", 2);
                Palette p = new Palette(f[0]);
                if (f.length > 1) for (String c : f[1].split(",")) if (!c.isEmpty()) try { p.colors.add(Color.parseColor(c)); } catch (Exception ignored) { }
                palettes.add(p);
            }
        } catch (Exception ignored) { }
        if (palettes.isEmpty()) { // starter palettes
            Palette basic = new Palette("Basics");
            for (int c : new int[]{0xFF000000, 0xFF555555, 0xFFAAAAAA, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
                    0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFF6D4C41}) basic.colors.add(c);
            Palette skin = new Palette("Skin tones");
            for (int c : new int[]{0xFFFFE0CC, 0xFFF5C6A5, 0xFFE0AC85, 0xFFC68642, 0xFF8D5524, 0xFF5C3A21}) skin.colors.add(c);
            palettes.add(basic);
            palettes.add(skin);
            save();
        }
    }

    void save() {
        StringBuilder sb = new StringBuilder();
        for (Palette p : palettes) {
            sb.append(p.name.replace('\t', ' ').replace('\n', ' ')).append('\t');
            for (int i = 0; i < p.colors.size(); i++) sb.append(i > 0 ? "," : "").append(String.format("#%06X", p.colors.get(i) & 0xFFFFFF));
            sb.append('\n');
        }
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(file)) { o.write(sb.toString().getBytes("UTF-8")); } catch (Exception ignored) { }
    }

    int defaultIndex() { return Math.max(0, Math.min(palettes.size() - 1, prefs.getInt("defPal", 0))); }

    List<Integer> history() {
        ArrayList<Integer> h = new ArrayList<>();
        for (String s : prefs.getString("colHist", "").split(",")) if (!s.isEmpty()) try { h.add(Color.parseColor(s)); } catch (Exception ignored) { }
        return h;
    }

    void remember(int c) {
        List<Integer> h = history();
        h.remove(Integer.valueOf(0xFF000000 | c));
        h.add(0, 0xFF000000 | c);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(10, h.size()); i++) sb.append(i > 0 ? "," : "").append(String.format("#%06X", h.get(i) & 0xFFFFFF));
        prefs.edit().putString("colHist", sb.toString()).apply();
    }

    // ---------------- Procreate .swatches (a zip holding Swatches.json) ----------------

    public static List<Palette> readSwatches(byte[] data) throws Exception {
        String json = null;
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            java.util.zip.ZipEntry e;
            while ((e = z.getNextEntry()) != null)
                if (e.getName().toLowerCase().endsWith(".json")) json = new String(Brushes.readAll(z), "UTF-8");
        }
        if (json == null) throw new Exception("not a .swatches file");
        org.json.JSONArray arr = json.trim().startsWith("[") ? new org.json.JSONArray(json) : new org.json.JSONArray().put(new org.json.JSONObject(json));
        ArrayList<Palette> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.getJSONObject(i);
            Palette p = new Palette(o.optString("name", "Imported"));
            org.json.JSONArray sw = o.optJSONArray("swatches");
            if (sw != null) for (int k = 0; k < sw.length(); k++) {
                org.json.JSONObject s = sw.optJSONObject(k);
                if (s == null) continue;
                p.colors.add(Color.HSVToColor(new float[]{(float) s.optDouble("hue", 0) * 360, (float) s.optDouble("saturation", 0), (float) s.optDouble("brightness", 0)}));
            }
            out.add(p);
        }
        return out;
    }

    public static void writeSwatches(Palette p, java.io.OutputStream os) throws Exception {
        org.json.JSONArray sw = new org.json.JSONArray();
        float[] h = new float[3];
        for (int c : p.colors) {
            Color.colorToHSV(c, h);
            sw.put(new org.json.JSONObject().put("hue", h[0] / 360.0).put("saturation", (double) h[1]).put("brightness", (double) h[2])
                    .put("alpha", 1).put("colorSpace", 0));
        }
        org.json.JSONArray root = new org.json.JSONArray().put(new org.json.JSONObject().put("name", p.name).put("swatches", sw));
        java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(os);
        z.putNextEntry(new java.util.zip.ZipEntry("Swatches.json"));
        z.write(root.toString().getBytes("UTF-8"));
        z.closeEntry();
        z.finish();
    }

    // ---------------- The panel ----------------

    /** Opens the panel under anchor, starting at colour start; onColor gets every change. */
    void show(View anchor, int start, java.util.function.IntConsumer onColor) {
        this.onColor = onColor;
        color = 0xFF000000 | start;
        previous = color;
        Color.colorToHSV(color, hsv);
        pop = new Ui.Pop(a, 340);
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding((int) (16 * dp), (int) (4 * dp), (int) (12 * dp), (int) (4 * dp));
        TextView t = new TextView(a);
        t.setText("Colour");
        t.setTextColor(Color.WHITE);
        t.setTextSize(17);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        swatch = new View(a) { // current (left) and previous (right); tap the right half to go back
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight(), r = 6 * dp;
                p.setColor(color); c.drawRoundRect(new RectF(0, 0, w / 2 + r, h), r, r, p);
                p.setColor(previous); c.drawRoundRect(new RectF(w / 2 - r, 0, w, h), r, r, p);
                p.setColor(color); c.drawRect(w / 2 - r, 0, w / 2, h, p);
            }
        };
        swatch.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP && e.getX() > v.getWidth() / 2f) set(previous);
            return true;
        });
        head.addView(swatch, new LinearLayout.LayoutParams((int) (64 * dp), (int) (28 * dp)));
        pop.add(head);
        LinearLayout tabs = new LinearLayout(a);
        tabs.setPadding((int) (8 * dp), 0, (int) (8 * dp), 0);
        HorizontalScrollView ts = new HorizontalScrollView(a);
        ts.setHorizontalScrollBarEnabled(false);
        ts.addView(tabs);
        pop.add(ts);
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding((int) (14 * dp), (int) (6 * dp), (int) (14 * dp), (int) (10 * dp));
        pop.add(body);
        TextView[] tv = new TextView[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            int k = i;
            tv[i] = chip(tabs, TABS[i], k == tab(), v -> {
                prefs.edit().putInt("colTab", k).apply();
                for (int j = 0; j < tv.length; j++) tv[j].setBackgroundColor(j == k ? Ui.ACCENT : 0);
                build();
            });
        }
        build();
        pop.win.setOnDismissListener(() -> remember(color));
        pop.show(anchor);
    }

    int tab() { return Math.max(0, Math.min(TABS.length - 1, prefs.getInt("colTab", 0))); }

    /** Sets the colour from outside the HSV views (swatches, hex, sliders) and refreshes everything. */
    void set(int c) {
        color = 0xFF000000 | c;
        float h = hsv[0];
        Color.colorToHSV(color, hsv);
        if (hsv[1] < 0.004f || hsv[2] < 0.004f) hsv[0] = h; // greys have no hue: keep the last one
        changed();
    }

    private void setHsv() { color = Color.HSVToColor(hsv); changed(); }

    private void changed() {
        if (onColor != null) onColor.accept(color);
        for (View v : live) v.invalidate();
        if (swatch != null) swatch.invalidate();
        if (syncValues != null) syncValues.run();
    }

    private void build() {
        body.removeAllViews();
        live.clear();
        syncValues = null;
        int side = (int) Math.min(300 * dp, a.getResources().getDisplayMetrics().widthPixels - 60 * dp);
        switch (tab()) {
            case 0: body.addView(disc(), new LinearLayout.LayoutParams(side, side)); break;
            case 1: classic(side); break;
            case 2: harmony(side); break;
            case 3: values(); break;
            default: palettesTab(); return; // the palettes tab is its own footer
        }
        footer();
    }

    private TextView label(String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(0xFF8E8E93);
        t.setTextSize(12);
        t.setPadding(0, (int) (10 * dp), 0, (int) (4 * dp));
        return t;
    }

    private TextView chip(LinearLayout row, String label, boolean on, View.OnClickListener l) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0);
        if (on) t.setBackgroundColor(Ui.ACCENT);
        t.setOnClickListener(l);
        row.addView(t, new LinearLayout.LayoutParams(-2, (int) (38 * dp)));
        return t;
    }

    /** A row of tappable colour swatches. */
    private View swatchRow(List<Integer> cs, java.util.function.IntConsumer longPress, Runnable plus) {
        LinearLayout row = new LinearLayout(a);
        for (int i = 0; i < cs.size(); i++) {
            int c = cs.get(i), k = i;
            View v = new View(a);
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setColor(c);
            d.setCornerRadius(6 * dp);
            d.setStroke(1, 0xFF636366);
            v.setBackground(d);
            v.setOnClickListener(x -> set(c));
            if (longPress != null) v.setOnLongClickListener(x -> { longPress.accept(k); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (28 * dp), (int) (28 * dp));
            lp.setMargins(2, 2, (int) (4 * dp), 2);
            row.addView(v, lp);
        }
        if (plus != null) {
            TextView v = new TextView(a);
            v.setText("+");
            v.setTextColor(Color.WHITE);
            v.setTextSize(18);
            v.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setCornerRadius(6 * dp);
            d.setStroke((int) (1.5f * dp), 0xFF8E8E93);
            v.setBackground(d);
            v.setOnClickListener(x -> plus.run());
            v.setContentDescription("Add the current colour");
            row.addView(v, new LinearLayout.LayoutParams((int) (28 * dp), (int) (28 * dp)));
        }
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        return hs;
    }

    /** Default palette and recent colours, under every tab except Palettes. */
    private void footer() {
        if (!palettes.isEmpty()) {
            Palette p = palettes.get(defaultIndex());
            body.addView(label(p.name.toUpperCase()));
            body.addView(swatchRow(p.colors, null, () -> { p.colors.add(color); save(); build(); }));
        }
        List<Integer> h = history();
        if (!h.isEmpty()) { body.addView(label("RECENT")); body.addView(swatchRow(h, null, null)); }
    }

    // ---------------- Disc: hue ring + saturation/brightness disc ----------------

    private Bitmap discCache; private float discHue = -1; private int discSize;

    private View disc() {
        View v = new View(a) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                float s = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f;
                float ring = s * 0.11f, ro = s / 2 - 2, ri = ro - ring, inner = ri - 6 * dp;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(ring);
                p.setShader(new android.graphics.SweepGradient(cx, cy, hueRing(), null));
                c.drawCircle(cx, cy, ro - ring / 2, p);
                p.setShader(null);
                int n = Math.max(2, (int) (inner * 2));
                if (discCache == null || discHue != hsv[0] || discSize != n) { // saturation → right, brightness → up
                    discCache = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
                    int[] px = new int[n * n];
                    float[] t = {hsv[0], 0, 0};
                    for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
                        float dx = x - n / 2f, dy = y - n / 2f;
                        if (dx * dx + dy * dy > (n / 2f) * (n / 2f)) continue;
                        t[1] = x / (float) (n - 1); t[2] = 1 - y / (float) (n - 1);
                        px[y * n + x] = Color.HSVToColor(t);
                    }
                    discCache.setPixels(px, 0, n, 0, 0, n, n);
                    discHue = hsv[0]; discSize = n;
                }
                c.drawBitmap(discCache, cx - n / 2f, cy - n / 2f, null);
                // markers
                double ang = Math.toRadians(hsv[0]);
                marker(c, p, cx + (float) Math.cos(ang) * (ro - ring / 2), cy + (float) Math.sin(ang) * (ro - ring / 2), Color.HSVToColor(new float[]{hsv[0], 1, 1}));
                marker(c, p, cx - n / 2f + hsv[1] * (n - 1), cy - n / 2f + (1 - hsv[2]) * (n - 1), color);
            }
            int part = -1;
            @Override public boolean onTouchEvent(MotionEvent e) {
                float s = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f;
                float ring = s * 0.11f, ro = s / 2 - 2, ri = ro - ring, inner = ri - 6 * dp;
                float dx = e.getX() - cx, dy = e.getY() - cy, d = (float) Math.hypot(dx, dy);
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) part = d > ri - 4 * dp ? 0 : 1;
                if (part == 0) hsv[0] = (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360);
                else {
                    if (d > inner) { dx = dx / d * inner; dy = dy / d * inner; } // stay inside the disc
                    hsv[1] = Math.max(0, Math.min(1, (dx + inner) / (2 * inner)));
                    hsv[2] = Math.max(0, Math.min(1, 1 - (dy + inner) / (2 * inner)));
                }
                setHsv();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        };
        live.add(v);
        return v;
    }

    private static int[] hueRing() {
        int[] c = new int[13];
        for (int i = 0; i <= 12; i++) c[i] = Color.HSVToColor(new float[]{i * 30 % 360, 1, 1});
        return c;
    }

    private void marker(Canvas c, Paint p, float x, float y, int fill) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill);
        c.drawCircle(x, y, 9 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * dp);
        p.setColor(Color.WHITE);
        c.drawCircle(x, y, 9 * dp, p);
        p.setStrokeWidth(1 * dp);
        p.setColor(0x66000000);
        c.drawCircle(x, y, 10.5f * dp, p);
    }

    // ---------------- Classic: saturation/brightness square + hue bar ----------------

    private void classic(int side) {
        View sq = new View(a) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight();
                p.setShader(new android.graphics.ComposeShader(
                        new android.graphics.LinearGradient(0, 0, w, 0, Color.WHITE, Color.HSVToColor(new float[]{hsv[0], 1, 1}), android.graphics.Shader.TileMode.CLAMP),
                        new android.graphics.LinearGradient(0, 0, 0, h, 0x00000000, 0xFF000000, android.graphics.Shader.TileMode.CLAMP),
                        android.graphics.PorterDuff.Mode.SRC_OVER));
                c.drawRoundRect(new RectF(0, 0, w, h), 8 * dp, 8 * dp, p);
                marker(c, p, hsv[1] * w, (1 - hsv[2]) * h, color);
            }
            @Override public boolean onTouchEvent(MotionEvent e) {
                hsv[1] = Math.max(0, Math.min(1, e.getX() / getWidth()));
                hsv[2] = Math.max(0, Math.min(1, 1 - e.getY() / getHeight()));
                setHsv();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        };
        live.add(sq);
        body.addView(sq, new LinearLayout.LayoutParams(side, (int) (side * 0.8f)));
        View bar = hueBar();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, (int) (34 * dp));
        lp.topMargin = (int) (10 * dp);
        body.addView(bar, lp);
    }

    private View hueBar() {
        View bar = new View(a) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight();
                p.setShader(new android.graphics.LinearGradient(0, 0, w, 0, hueRing(), null, android.graphics.Shader.TileMode.CLAMP));
                c.drawRoundRect(new RectF(0, h * 0.2f, w, h * 0.8f), h * 0.3f, h * 0.3f, p);
                marker(c, p, hsv[0] / 360f * w, h / 2, Color.HSVToColor(new float[]{hsv[0], 1, 1}));
            }
            @Override public boolean onTouchEvent(MotionEvent e) {
                hsv[0] = Math.max(0, Math.min(359.9f, e.getX() / getWidth() * 360));
                setHsv();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        };
        live.add(bar);
        return bar;
    }

    // ---------------- Harmony: hue/saturation wheel, brightness, matching colours ----------------

    private Bitmap wheelCache; private int wheelSize;

    float[] harmonyHues() {
        switch (prefs.getInt("harmony", 0)) {
            case 1: return new float[]{150, 210};
            case 2: return new float[]{-30, 30};
            case 3: return new float[]{120, 240};
            case 4: return new float[]{90, 180, 270};
            default: return new float[]{180};
        }
    }

    private void harmony(int side) {
        LinearLayout modes = new LinearLayout(a);
        TextView[] mv = new TextView[HARMONY.length];
        for (int i = 0; i < HARMONY.length; i++) {
            int k = i;
            mv[i] = chip(modes, HARMONY[i], prefs.getInt("harmony", 0) == i, v -> {
                prefs.edit().putInt("harmony", k).apply();
                for (int j = 0; j < mv.length; j++) mv[j].setBackgroundColor(j == k ? Ui.ACCENT : 0);
                changed();
            });
        }
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(modes);
        body.addView(hs);
        View wheel = new View(a) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                int n = (int) Math.min(getWidth(), getHeight());
                if (wheelCache == null || wheelSize != n) { // angle = hue, distance = saturation
                    wheelCache = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
                    int[] px = new int[n * n];
                    float[] t = {0, 0, 1};
                    float r = n / 2f;
                    for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
                        float dx = x - r, dy = y - r, d = (float) Math.hypot(dx, dy);
                        if (d > r) continue;
                        t[0] = (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360); t[1] = d / r;
                        px[y * n + x] = Color.HSVToColor(t);
                    }
                    wheelCache.setPixels(px, 0, n, 0, 0, n, n);
                    wheelSize = n;
                }
                float ox = (getWidth() - n) / 2f, oy = (getHeight() - n) / 2f, r = n / 2f;
                p.setAlpha(255);
                c.drawBitmap(wheelCache, ox, oy, p);
                p.setColor(Color.argb(Math.round((1 - hsv[2]) * 255), 0, 0, 0)); // darker with brightness
                c.drawCircle(ox + r, oy + r, r, p);
                for (float dh : harmonyHues()) {
                    double an = Math.toRadians(hsv[0] + dh);
                    marker(c, p, ox + r + (float) Math.cos(an) * hsv[1] * r, oy + r + (float) Math.sin(an) * hsv[1] * r,
                            Color.HSVToColor(new float[]{(hsv[0] + dh + 360) % 360, hsv[1], hsv[2]}));
                }
                double an = Math.toRadians(hsv[0]);
                marker(c, p, ox + r + (float) Math.cos(an) * hsv[1] * r, oy + r + (float) Math.sin(an) * hsv[1] * r, color);
            }
            @Override public boolean onTouchEvent(MotionEvent e) {
                float n = Math.min(getWidth(), getHeight()), r = n / 2f;
                float dx = e.getX() - getWidth() / 2f, dy = e.getY() - getHeight() / 2f;
                hsv[0] = (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360);
                hsv[1] = Math.max(0, Math.min(1, (float) Math.hypot(dx, dy) / r));
                setHsv();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        };
        live.add(wheel);
        body.addView(wheel, new LinearLayout.LayoutParams(side, side));
        body.addView(label("BRIGHTNESS"));
        SeekBar sb = new SeekBar(a);
        sb.setMax(1000);
        sb.setProgress(Math.round(hsv[2] * 1000));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) { if (u) { hsv[2] = v / 1000f; setHsv(); } }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        body.addView(sb, new LinearLayout.LayoutParams(side, (int) (36 * dp)));
        body.addView(label("MATCHING COLOURS · tap to use"));
        LinearLayout row = new LinearLayout(a);
        Runnable fill = () -> {
            row.removeAllViews();
            ArrayList<Integer> cs = new ArrayList<>();
            cs.add(color);
            for (float dh : harmonyHues()) cs.add(Color.HSVToColor(new float[]{(hsv[0] + dh + 360) % 360, hsv[1], hsv[2]}));
            for (int c : cs) {
                View v = new View(a);
                v.setBackgroundColor(c);
                v.setOnClickListener(x -> set(c));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (44 * dp), (int) (32 * dp));
                lp.rightMargin = (int) (6 * dp);
                row.addView(v, lp);
            }
        };
        fill.run();
        body.addView(row);
        View watcher = new View(a) { @Override protected void onDraw(Canvas c) { fill.run(); } }; // refresh the row on change
        live.add(watcher);
        body.addView(watcher, new LinearLayout.LayoutParams(1, 1));
    }

    // ---------------- Value: exact numbers ----------------

    private void values() {
        String[] names = {"Hue", "Saturation", "Brightness", "Red", "Green", "Blue"};
        int[] max = {360, 100, 100, 255, 255, 255};
        SeekBar[] bars = new SeekBar[6];
        TextView[] labs = new TextView[6];
        boolean[] busy = {false};
        for (int i = 0; i < 6; i++) {
            int k = i;
            labs[i] = label("");
            body.addView(labs[i]);
            bars[i] = new SeekBar(a);
            bars[i].setMax(max[i]);
            bars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int v, boolean u) {
                    if (!u || busy[0]) return;
                    if (k < 3) {
                        if (k == 0) hsv[0] = Math.min(359.9f, v); else if (k == 1) hsv[1] = v / 100f; else hsv[2] = v / 100f;
                        setHsv();
                    } else {
                        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
                        if (k == 3) r = v; else if (k == 4) g = v; else b = v;
                        set(Color.rgb(r, g, b));
                    }
                }
                public void onStartTrackingTouch(SeekBar s) { }
                public void onStopTrackingTouch(SeekBar s) { }
            });
            body.addView(bars[i], new LinearLayout.LayoutParams(-1, (int) (34 * dp)));
        }
        body.addView(label("HEX"));
        android.widget.EditText hex = new android.widget.EditText(a);
        hex.setSingleLine(true);
        hex.setTextColor(Color.WHITE);
        hex.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        hex.setOnEditorActionListener((v, id, ev) -> {
            try { set(Color.parseColor((v.getText().toString().startsWith("#") ? "" : "#") + v.getText().toString().trim())); }
            catch (Exception e) { a.hud("Use a code like #3A7BD5"); }
            return false;
        });
        body.addView(hex, new LinearLayout.LayoutParams((int) (160 * dp), -2));
        syncValues = () -> {
            busy[0] = true;
            int[] v = {Math.round(hsv[0]), Math.round(hsv[1] * 100), Math.round(hsv[2] * 100), Color.red(color), Color.green(color), Color.blue(color)};
            for (int i = 0; i < 6; i++) { bars[i].setProgress(v[i]); labs[i].setText(names[i].toUpperCase() + "   " + v[i] + (i == 0 ? "°" : i < 3 ? "%" : "")); }
            if (!hex.hasFocus()) hex.setText(String.format("#%06X", color & 0xFFFFFF));
            busy[0] = false;
        };
        syncValues.run();
    }

    // ---------------- Palettes ----------------

    private void palettesTab() {
        LinearLayout top = new LinearLayout(a);
        chip(top, "＋ New palette", false, v -> {
            Palette p = new Palette("Palette " + (palettes.size() + 1));
            p.colors.add(color);
            palettes.add(p);
            save();
            build();
        });
        chip(top, "Import", false, v -> a.importSwatches());
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(top);
        body.addView(hs);
        TextView tip = label("Tap a colour to use it · hold it to remove · ＋ adds the current colour · hold a name for options");
        body.addView(tip);
        for (int i = 0; i < palettes.size(); i++) {
            Palette p = palettes.get(i);
            int k = i;
            TextView name = label((k == defaultIndex() ? "★ " : "") + p.name);
            name.setTextColor(Color.WHITE);
            name.setTextSize(14);
            name.setOnClickListener(v -> { prefs.edit().putInt("defPal", k).apply(); a.hud("\"" + p.name + "\" shows under every tab"); build(); });
            name.setOnLongClickListener(v -> { paletteMenu(v, k); return true; });
            body.addView(name);
            // wrap swatches 10 to a row
            for (int s = 0; s < Math.max(1, (p.colors.size() + 1 + 9) / 10); s++) {
                int from = s * 10, to = Math.min(p.colors.size(), from + 10);
                boolean last = to == p.colors.size() && (p.colors.size() + 1 + 9) / 10 - 1 == s;
                int off = from;
                body.addView(swatchRow(p.colors.subList(from, to), j -> { p.colors.remove(off + j); save(); build(); },
                        last ? () -> { p.colors.add(color); save(); build(); } : null));
            }
        }
        List<Integer> h = history();
        if (!h.isEmpty()) { body.addView(label("RECENT")); body.addView(swatchRow(h, null, null)); }
    }

    private void paletteMenu(View anchor, int k) {
        Palette p = palettes.get(k);
        Ui.Pop m = new Ui.Pop(a, 220);
        m.item("Use as default (shown under every tab)", true, () -> { prefs.edit().putInt("defPal", k).apply(); build(); });
        m.item("Rename", true, () -> {
            android.widget.EditText et = new android.widget.EditText(a);
            et.setText(p.name);
            et.setSelectAllOnFocus(true);
            new android.app.AlertDialog.Builder(a).setTitle("Rename palette").setView(et)
                    .setPositiveButton("OK", (d, w) -> { p.name = et.getText().toString().trim().isEmpty() ? p.name : et.getText().toString().trim(); save(); build(); })
                    .setNegativeButton("Cancel", null).show();
        });
        m.item("Export as .swatches (Procreate)", true, () -> a.exportSwatches(p));
        m.item("Delete palette", true, () -> {
            palettes.remove(k);
            if (prefs.getInt("defPal", 0) >= palettes.size()) prefs.edit().putInt("defPal", 0).apply();
            save();
            build();
        });
        m.show(anchor);
    }

    /** After importing a .swatches file. */
    void added(List<Palette> ps) {
        palettes.addAll(ps);
        save();
        if (pop != null && pop.win.isShowing()) build();
    }
}
