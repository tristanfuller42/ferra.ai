package ai.inkwell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final long MAX_PIXELS = 3000L * 3000L;
    private static final int REQ_OPEN = 1, REQ_IMPORT = 2, REQ_BRUSH = 3, REQ_REF = 4, REQ_TL = 5, REQ_GIF = 6, REQ_AMP4 = 7, REQ_GALLERY = 8, REQ_EXPORT = 10;
    private static final String[] FORMATS = {"PNG", "JPEG", "WebP", "PDF", "PSD (layers)"};
    private static final String[] MIMES = {"image/png", "image/jpeg", "image/webp", "application/pdf", "image/vnd.adobe.photoshop"};
    private static final String[] EXTS = {"png", "jpg", "webp", "pdf", "psd"};

    private CanvasView canvas;
    private FrameLayout shell, stage;
    private LinearLayout timeline;
    private android.content.SharedPreferences prefs;
    private File gdir;
    private String curId;
    private boolean tlOn, tlBusy;
    private Ui.VSlider sizeS, opS;

    // ---- Per-tool settings (like Procreate): brush, size and opacity for each painting tool ----
    private static final int[] SLOT_TOOLS = {CanvasView.DRAW, CanvasView.SMUDGE, CanvasView.ERASE, CanvasView.CLONE};
    private final String[] tBrush = {"Ink pen", "Soft shading", "Hard round", "Hard round"};
    private final float[] tSize = {12, 30, 40, 30};
    private final float[] tOp = {1, 1, 1, 1};

    private static int slot(int tool) {
        for (int i = 0; i < SLOT_TOOLS.length; i++) if (SLOT_TOOLS[i] == tool) return i;
        return -1;
    }

    /** Remembers the canvas's current brush/size/opacity for this tool. */
    private void storeTool(int tool) {
        int k = slot(tool);
        if (k < 0) return;
        tBrush[k] = canvas.brush.name;
        tSize[k] = canvas.size;
        tOp[k] = canvas.opacity;
        if (prefs != null) prefs.edit().putString("tb" + k, tBrush[k]).putFloat("ts" + k, tSize[k]).putFloat("to" + k, tOp[k]).apply();
    }

    /** Puts this tool's remembered settings back on the canvas and the sidebar. */
    private void applyTool(int tool) {
        int k = slot(tool);
        if (k < 0) return;
        CanvasView.Brush b = CanvasView.BRUSHES.get(0);
        for (CanvasView.Brush x : CanvasView.BRUSHES) if (x.name.equals(tBrush[k])) { b = x; break; }
        canvas.brush = b;
        canvas.size = tSize[k];
        canvas.opacity = tOp[k];
        if (sizeS != null) { sizeS.value = (float) Math.sqrt(Math.max(0, canvas.size - 1) / 149f); sizeS.invalidate(); }
        if (opS != null) { opS.value = canvas.opacity; opS.invalidate(); }
        if (brushName != null) updateBrush();
    }

    private void loadToolPrefs() {
        for (int k = 0; k < SLOT_TOOLS.length; k++) {
            tBrush[k] = prefs.getString("tb" + k, tBrush[k]);
            tSize[k] = prefs.getFloat("ts" + k, tSize[k]);
            tOp[k] = prefs.getFloat("to" + k, tOp[k]);
        }
    }

    private Ui.Icon brushBtn, eraseBtn, moveBtn, colorBtn, layersBtn, pickBtn, smudgeBtn, adjustBtn, selectBtn;
    private LinearLayout bottom;
    private TextView brushName;
    private LinearLayout side;
    private FrameLayout.LayoutParams sideLp;
    private LinearLayout panel, layerList;
    private SeekBar opacityBar;
    private File autosave;
    private float dp;
    private volatile boolean loaded;
    private int exportFormat;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dp = getResources().getDisplayMetrics().density;
        autosave = new File(getFilesDir(), "autosave.psd");

        canvas = new CanvasView(this);
        Rect max = screenBounds();
        newDocument(Math.min(max.width(), max.height()), Math.max(max.width(), max.height()));
        canvas.onChange = this::refreshPanel;
        canvas.onGreyPicked = this::updateColor;

        // ---- Top bar: actions on the left, painting tools on the right ----
        int ib = (int) (44 * dp);
        LinearLayout left = new LinearLayout(this), right = new LinearLayout(this);
        left.setGravity(Gravity.CENTER_VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        TextView gal = button(left, "Gallery", v -> showGallery());
        gal.setTextSize(15);
        icon(left, Ui.MENU, ib, this::actionsMenu);
        adjustBtn = icon(left, Ui.ADJUST, ib, this::adjustMenu);
        selectBtn = icon(left, Ui.SELECT, ib, v -> setTool(canvas.tool == CanvasView.SELECT ? CanvasView.DRAW : CanvasView.SELECT));
        moveBtn = icon(left, Ui.MOVE, ib, v -> setTool(canvas.tool == CanvasView.MOVE ? CanvasView.DRAW : CanvasView.MOVE));
        brushName = new TextView(this);
        brushName.setTextColor(0xFF9A9A9E);
        brushName.setTextSize(13);
        brushName.setPadding(0, 0, (int) (6 * dp), 0);
        brushName.setOnClickListener(v -> pickBrush());
        right.addView(brushName);
        brushBtn = icon(right, Ui.BRUSH, ib, v -> { if (canvas.tool == CanvasView.DRAW) pickBrush(); else setTool(CanvasView.DRAW); });
        smudgeBtn = icon(right, Ui.SMUDGE, ib, v -> { if (canvas.tool == CanvasView.SMUDGE) pickBrush(); else setTool(CanvasView.SMUDGE); });
        eraseBtn = icon(right, Ui.ERASER, ib, v -> { if (canvas.tool == CanvasView.ERASE) pickBrush(); else setTool(CanvasView.ERASE); });
        layersBtn = icon(right, Ui.LAYERS, ib, v -> togglePanel());
        colorBtn = icon(right, Ui.COLOR, ib, v -> pickGrey());
        colorBtn.setOnTouchListener(this::colorDropTouch);
        FrameLayout top = new FrameLayout(this);
        top.setBackgroundColor(Ui.BAR);
        top.addView(left, new FrameLayout.LayoutParams(-2, -1, Gravity.START));
        top.addView(right, new FrameLayout.LayoutParams(-2, -1, Gravity.END));
        View scroll = top; // hidden by four-finger tap

        // ---- Sidebar: size, eyedropper, opacity, undo/redo ----
        side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        android.graphics.drawable.GradientDrawable sbg = new android.graphics.drawable.GradientDrawable();
        sbg.setColor(Ui.PANEL);
        sbg.setCornerRadius(12 * dp);
        side.setBackground(sbg);
        side.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
        sizeS = new Ui.VSlider(this, (float) Math.sqrt((canvas.size - 1) / 149f));
        sizeS.listener = (v, done) -> canvas.size = 1 + 149 * v * v; // finer control at small sizes
        side.addView(sizeS, new LinearLayout.LayoutParams((int) (40 * dp), (int) (150 * dp)));
        pickBtn = icon(side, Ui.PICK, (int) (40 * dp), v -> { canvas.pickNext = !canvas.pickNext; pickBtn.setOn(canvas.pickNext); });
        opS = new Ui.VSlider(this, canvas.opacity);
        opS.listener = (v, done) -> canvas.opacity = Math.max(0.01f, v);
        side.addView(opS, new LinearLayout.LayoutParams((int) (40 * dp), (int) (150 * dp)));
        icon(side, Ui.UNDO, (int) (40 * dp), v -> canvas.undo());
        icon(side, Ui.REDO, (int) (40 * dp), v -> canvas.redo());
        canvas.onPicked = () -> pickBtn.setOn(false);
        canvas.onCopyMenu = () -> {
            Ui.PopMenu m = new Ui.PopMenu(this, canvas, Gravity.CENTER);
            String[] items = {"Cut", "Copy", "Copy all", "Duplicate", "Cut & Paste", "Paste"};
            for (int i = 0; i < items.length; i++) m.getMenu().add(0, i, i, items[i]).setEnabled(i != 5 || canvas.hasClipboard());
            m.setOnMenuItemClickListener(it -> {
                switch (it.getItemId()) {
                    case 0: canvas.copy(false, true); break;
                    case 1: canvas.copy(false, false); break;
                    case 2: canvas.copy(true, false); break;
                    case 3: if (!canvas.copyPaste(false)) full(); break;
                    case 4: if (!canvas.copyPaste(true)) full(); break;
                    case 5: if (!canvas.paste()) full(); break;
                }
                return true;
            });
            m.show();
        };
        canvas.onInfo = msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        setTool(CanvasView.DRAW);
        updateBrush();
        updateColor();

        // ---- Layers panel ----
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable pbg = new android.graphics.drawable.GradientDrawable();
        pbg.setColor(Ui.PANEL);
        pbg.setCornerRadius(12 * dp);
        panel.setBackground(pbg);
        TextView lt = new TextView(this);
        lt.setText("Layers");
        lt.setTextColor(Color.WHITE);
        lt.setTextSize(17);
        lt.setPadding((int) (6 * dp), 0, 0, (int) (4 * dp));
        panel.addView(lt);
        panel.setPadding((int) (6 * dp), (int) (6 * dp), (int) (6 * dp), (int) (6 * dp));
        LinearLayout r1 = row(), r2 = row();
        button(r1, "+ New", v -> { if (!canvas.addLayer(canvas.newLayer("Layer " + (canvas.layers.size() + 1)))) full(); });
        button(r1, "Copy", v -> { if (!canvas.duplicate()) full(); });
        button(r1, "Delete", v -> confirm("Delete this layer?", canvas::deleteLayer));
        button(r2, "Up", v -> canvas.moveLayer(1));
        button(r2, "Down", v -> canvas.moveLayer(-1));
        button(r2, "Merge", v -> canvas.mergeDown());
        button(r2, "Clear", v -> canvas.clearLayer());
        panel.addView(r1);
        panel.addView(r2);
        TextView ol = new TextView(this);
        ol.setText("Layer opacity");
        ol.setTextColor(Color.WHITE);
        panel.addView(ol);
        opacityBar = new SeekBar(this);
        opacityBar.setMax(255);
        opacityBar.setOnSeekBarChangeListener(new Slider() {
            int before;
            public void onStartTrackingTouch(SeekBar s) { before = canvas.cur().opacity; }
            public void onProgressChanged(SeekBar s, int p, boolean u) { if (u) canvas.previewOpacity(p); }
            public void onStopTrackingTouch(SeekBar s) { canvas.commitOpacity(before); }
        });
        panel.addView(opacityBar);
        ScrollView ls = new ScrollView(this);
        layerList = new LinearLayout(this);
        layerList.setOrientation(LinearLayout.VERTICAL);
        ls.addView(layerList);
        panel.addView(ls, new LinearLayout.LayoutParams(-1, 0, 1f));
        panel.setVisibility(View.GONE);

        FrameLayout stage = new FrameLayout(this);
        stage.addView(canvas);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams((int) (280 * dp), (int) (460 * dp), Gravity.END | Gravity.TOP);
        plp.setMargins(0, (int) (6 * dp), (int) (8 * dp), (int) (8 * dp));
        stage.addView(panel, plp);
        sideLp = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL);
        sideLp.setMargins((int) (6 * dp), 0, (int) (6 * dp), 0);
        stage.addView(side, sideLp);
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bbg = new android.graphics.drawable.GradientDrawable();
        bbg.setColor(Ui.PANEL);
        bbg.setCornerRadius(12 * dp);
        bottom.setBackground(bbg);
        bottom.setPadding((int) (8 * dp), (int) (4 * dp), (int) (8 * dp), (int) (4 * dp));
        bottom.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.setMargins((int) (8 * dp), 0, (int) (8 * dp), (int) (10 * dp));
        stage.addView(bottom, blp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.addView(top, new LinearLayout.LayoutParams(-1, ib));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));
        timeline = new LinearLayout(this);
        timeline.setOrientation(LinearLayout.VERTICAL);
        timeline.setBackgroundColor(Ui.BAR);
        timeline.setVisibility(View.GONE);
        root.addView(timeline, new LinearLayout.LayoutParams(-1, -2));
        this.stage = stage;
        shell = new FrameLayout(this);
        shell.addView(root);
        shell.setOnApplyWindowInsetsListener((v, in) -> {
            Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(shell);
        canvas.onToggleUi = () -> { // four-finger tap: hide/show toolbar and panel
            boolean show = scroll.getVisibility() != View.VISIBLE;
            scroll.setVisibility(show ? View.VISIBLE : View.GONE);
            side.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) panel.setVisibility(View.GONE);
        };

        loadBrushes();
        canvas.onCommitted = this::captureTimelapse;
        canvas.onFrame = () -> { if (refView != null && refView.getVisibility() == View.VISIBLE && refMode == 0) refContent.invalidate(); };
        prefs = getSharedPreferences("inkwell", MODE_PRIVATE);
        tlOn = prefs.getBoolean("timelapse", true);
        canvas.fingerPaint = prefs.getBoolean("fingerPaint", false);
        loadToolPrefs();
        applyTool(canvas.tool);
        gdir = new File(getFilesDir(), "gallery");
        gdir.mkdirs();
        if (autosave.exists()) { // move the pre-gallery drawing into the gallery
            String id = newId();
            autosave.renameTo(docFile(id));
            prefs.edit().putString("cur", id).apply();
        }
        curId = prefs.getString("cur", null);
        if (curId != null && !docFile(curId).exists()) curId = null;
        // Open the last drawing, then show the gallery (like Procreate) and handle "Open with" / "Share"
        new Thread(() -> {
            Psd.Doc d = null;
            if (curId != null) {
                try (InputStream in = new FileInputStream(docFile(curId))) { d = Psd.read(in, MAX_PIXELS); }
                catch (Throwable ignored) { }
            }
            Psd.Doc fd = d;
            runOnUiThread(() -> {
                if (fd != null) { canvas.setDocument(fd.w, fd.h, fd.layers); canvas.setGroups(fd.groups); }
                if (curId == null) { curId = newId(); prefs.edit().putString("cur", curId).apply(); }
                loadSelections();
                loaded = true;
                if (b == null) showGallery();
                handleIntent(getIntent());
            });
        }).start();
    }

    /** Largest window this device can show (Fold unfolded); falls back to the display size. */
    private Rect screenBounds() {
        Rect r = getWindowManager().getMaximumWindowMetrics().getBounds();
        if (r.width() < 16 || r.height() < 16) {
            android.util.DisplayMetrics m = getResources().getDisplayMetrics();
            r = new Rect(0, 0, Math.max(720, m.widthPixels), Math.max(1280, m.heightPixels));
        }
        return r;
    }

    private abstract static class Slider implements SeekBar.OnSeekBarChangeListener {
        public void onStartTrackingTouch(SeekBar s) { }
        public void onStopTrackingTouch(SeekBar s) { }
    }

    /** New drawing or resize, with size presets. */
    private void canvasDialog() {
        Rect scr = screenBounds();
        int sw = Math.min(scr.width(), scr.height()), sh = Math.max(scr.width(), scr.height());
        Object[][] presets = {
            {"This screen", sw, sh},
            {"Current canvas", canvas.docW, canvas.docH},
            {"Square 2048", 2048, 2048},
            {"Square 2900 (max)", 2900, 2900},
            {"iPad Procreate default", 2048, 2732},
            {"A4 @ 300 dpi", 2480, 3508},
            {"US Letter @ 300 dpi", 2550, 3300},
            {"A5 @ 300 dpi", 1748, 2480},
            {"Comic page (6.6×10.2 in) @ 300", 1988, 3075},
            {"HD 1920×1080", 1920, 1080},
            {"4K 3840×2160", 3840, 2160},
            {"Instagram portrait", 1080, 1350},
        };
        int p = (int) (12 * dp);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, p, p, p);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.EditText wIn = number(canvas.docW), hIn = number(canvas.docH);
        row.addView(wIn, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = new TextView(this);
        x.setText(" × ");
        row.addView(x);
        row.addView(hIn, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView swap = new TextView(this);
        swap.setText("  ⇄ Swap");
        swap.setPadding(p, p, p, p);
        swap.setOnClickListener(v -> {
            CharSequence t = wIn.getText();
            wIn.setText(hIn.getText());
            hIn.setText(t);
        });
        row.addView(swap);
        box.addView(row);

        TextView info = new TextView(this);
        info.setPadding(0, 0, 0, p);
        box.addView(info);
        Runnable upd = () -> {
            int w = parse(wIn), h = parse(hIn);
            long px = (long) w * h;
            info.setText(w <= 0 || h <= 0 ? "Enter a width and height in pixels"
                    : px > MAX_PIXELS ? "Too big: max about 9 megapixels (" + px / 100000 / 10f + " MP)"
                    : String.format("%.1f megapixels · about %d layers", px / 1e6, layerCap(w, h)));
        };
        android.text.TextWatcher tw = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) { upd.run(); }
        };
        wIn.addTextChangedListener(tw);
        hIn.addTextChangedListener(tw);
        upd.run();

        for (Object[] pr : presets) {
            TextView t = new TextView(this);
            t.setText(pr[0] + "   " + pr[1] + " × " + pr[2]);
            t.setPadding(p / 2, p * 2 / 3, p / 2, p * 2 / 3);
            t.setTextSize(15);
            t.setOnClickListener(v -> { wIn.setText(String.valueOf(pr[1])); hIn.setText(String.valueOf(pr[2])); });
            box.addView(t);
        }
        android.widget.CheckBox scale = new android.widget.CheckBox(this);
        scale.setText("When resizing: scale artwork to fit (off = crop/extend around centre)");
        scale.setChecked(true);
        box.addView(scale);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Canvas size").setView(sv)
                .setPositiveButton("New drawing", null)
                .setNeutralButton("Resize current", null)
                .setNegativeButton("Cancel", null).show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int w = parse(wIn), h = parse(hIn);
            if (!valid(w, h)) return;
            d.dismiss();
            confirm("Start a new " + w + " × " + h + " drawing? The current one is replaced.", () -> newDocument(w, h));
        });
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            int w = parse(wIn), h = parse(hIn);
            if (!valid(w, h)) return;
            if ((long) layerCap(w, h) < canvas.layers.size()) { toast("Too many layers for that size"); return; }
            d.dismiss();
            confirm("Resize to " + w + " × " + h + "? Undo history is cleared.", () -> canvas.resizeDocument(w, h, scale.isChecked()));
        });
    }

    private android.widget.EditText number(int v) {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(v));
        e.setSelectAllOnFocus(true);
        return e;
    }

    private static int parse(android.widget.EditText e) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return 0; }
    }

    private boolean valid(int w, int h) {
        if (w < 16 || h < 16) { toast("Minimum is 16 × 16"); return false; }
        if ((long) w * h > MAX_PIXELS) { toast("Too big: max about 9 megapixels"); return false; }
        return true;
    }

    private static int layerCap(int w, int h) {
        long per = (long) w * h * 4;
        return (int) Math.max(2, Math.min(50, Runtime.getRuntime().maxMemory() * 55 / 100 / per - 2));
    }

    private void newDocument(int w, int h) {
        canvas.docW = w; canvas.docH = h;
        canvas.setDocument(w, h, java.util.Collections.singletonList(canvas.newLayer("Layer 1")));
    }

    // ---------- UI helpers ----------

    private TextView button(LinearLayout bar, String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        int p = (int) (10 * dp);
        t.setPadding(p, p / 2, p, p / 2);
        t.setOnClickListener(l);
        bar.addView(t, new LinearLayout.LayoutParams(-2, -1));
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setLayoutParams(new LinearLayout.LayoutParams(-1, (int) (40 * dp)));
        return r;
    }

    private static void highlight(TextView t, boolean on) {
        t.setBackgroundColor(on ? Color.WHITE : 0);
        t.setTextColor(on ? Color.BLACK : Color.WHITE);
    }

    private void setTool(int t) {
        if (canvas.adjusting()) finishAdjust(true);      // like Procreate: picking another tool applies
        if (canvas.liquifying()) { canvas.endLiquify(true); }
        if (t != canvas.tool) {
            storeTool(canvas.tool);     // only painting tools have settings to keep
            applyTool(t);
        }
        canvas.setTool(t);
        brushBtn.setOn(t == CanvasView.DRAW);
        smudgeBtn.setOn(t == CanvasView.SMUDGE);
        eraseBtn.setOn(t == CanvasView.ERASE);
        moveBtn.setOn(t == CanvasView.MOVE);
        selectBtn.setOn(t == CanvasView.SELECT);
        adjustBtn.setOn(t == CanvasView.ADJUST || t == CanvasView.LIQUIFY || t == CanvasView.CLONE);
        if (bottom == null) return; // still building the screen
        if (t == CanvasView.SELECT) showSelectBar();
        else if (t == CanvasView.MOVE) showTransformBar();
        else if (t != CanvasView.ADJUST && t != CanvasView.LIQUIFY && t != CanvasView.CLONE) bottom.setVisibility(View.GONE);
    }

    // ---------- Bottom context bar ----------

    private LinearLayout barRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private void showBar(View... rows) {
        bottom.removeAllViews();
        for (View v : rows) {
            if (v instanceof LinearLayout && ((LinearLayout) v).getOrientation() == LinearLayout.HORIZONTAL) {
                HorizontalScrollView hs = new HorizontalScrollView(this);
                hs.setHorizontalScrollBarEnabled(false);
                hs.addView(v);
                bottom.addView(hs);
            } else bottom.addView(v);
        }
        bottom.setVisibility(View.VISIBLE);
    }

    private TextView chip(LinearLayout row, String label, boolean on, View.OnClickListener l) {
        TextView t = button(row, label, l);
        t.setTextSize(14);
        t.setLayoutParams(new LinearLayout.LayoutParams(-2, (int) (40 * dp)));
        if (on) { t.setBackgroundColor(Ui.ACCENT); t.setTextColor(Color.WHITE); }
        else t.setTextColor(0xFFE8E8E8);
        return t;
    }

    private void showSelectBar() {
        LinearLayout a = barRow(), b = barRow();
        String[] kinds = {"Automatic", "Freehand", "Rectangle", "Ellipse"};
        for (int i = 0; i < kinds.length; i++) {
            int k = i;
            chip(a, kinds[i], canvas.selKind == i, v -> { canvas.selKind = k; showSelectBar(); });
        }
        chip(b, canvas.selSubtract ? "− Subtract" : "+ Add", canvas.selSubtract, v -> { canvas.selSubtract = !canvas.selSubtract; showSelectBar(); });
        chip(b, "Invert", false, v -> canvas.invertSelection());
        chip(b, "Copy & Paste", false, v -> { if (!canvas.copyPaste(false)) full(); });
        chip(b, "Cut & Paste", false, v -> { if (!canvas.copyPaste(true)) full(); });
        chip(b, "Feather", false, v -> featherDialog());
        chip(b, "Clear", false, v -> canvas.clearSelection());
        chip(b, "Save", false, v -> saveSelection());
        chip(b, "Load", false, this::loadSelectionMenu);
        showBar(a, b);
    }

    private void featherDialog() {
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(10);
        new AlertDialog.Builder(this).setTitle("Feather (pixels)").setView(sb)
                .setPositiveButton("Apply", (d, w) -> canvas.featherSelection(Math.max(1, sb.getProgress()))).show();
    }

    private void showTransformBar() {
        LinearLayout a = barRow();
        String[] modes = {"Uniform", "Freeform", "Distort", "Warp"};
        for (int i = 0; i < modes.length; i++) {
            int k = i;
            chip(a, modes[i], canvas.xform == i, v -> { canvas.setXform(k); showTransformBar(); });
        }
        chip(a, "Flip H", false, v -> canvas.flip(true));
        chip(a, "Flip V", false, v -> canvas.flip(false));
        chip(a, "Rotate 90°", false, v -> canvas.rotate90());
        chip(a, "Reset", false, v -> canvas.resetTransform());
        chip(a, "Done", false, v -> setTool(CanvasView.DRAW));
        showBar(a);
    }

    // ---------- Adjustments ----------

    /** An adjustment: parameter names, ranges and defaults, and how to build the effect from values. */
    private static final class Adj {
        final String name; final String[] params; final float[] min, max, def;
        final java.util.function.Function<float[], CanvasView.AdjFn> fn;
        Adj(String n, String[] p, float[] mn, float[] mx, float[] d, java.util.function.Function<float[], CanvasView.AdjFn> f) {
            name = n; params = p; min = mn; max = mx; def = d; fn = f;
        }
    }

    private Adj adj;
    private float[] adjVals;
    private SeekBar[] adjBars;
    private float zoomCX, zoomCY, dragStart;
    private Ui.CurveView curveView;

    private Adj[] adjustments() {
        int w = canvas.docW, h = canvas.docH;
        return new Adj[]{
            new Adj("Gaussian Blur", new String[]{"Radius"}, new float[]{0}, new float[]{60}, new float[]{0},
                    v -> (s, o) -> { if (v[0] < 0.5f) System.arraycopy(s, 0, o, 0, s.length); else Fx.gaussian(s, o, w, h, v[0]); }),
            new Adj("Motion Blur", new String[]{"Length", "Angle"}, new float[]{0, 0}, new float[]{200, 180}, new float[]{0, 0},
                    v -> (s, o) -> { if (v[0] < 1) System.arraycopy(s, 0, o, 0, s.length); else Fx.motion(s, o, w, h, v[0], v[1]); }),
            new Adj("Perspective Blur", new String[]{"Amount (tap canvas to set centre)"}, new float[]{0}, new float[]{1}, new float[]{0},
                    v -> (s, o) -> { if (v[0] < 0.01f) System.arraycopy(s, 0, o, 0, s.length); else Fx.zoom(s, o, w, h, zoomCX, zoomCY, v[0]); }),
            new Adj("Noise", new String[]{"Amount"}, new float[]{0}, new float[]{1}, new float[]{0},
                    v -> (s, o) -> Fx.noise(s, o, v[0], 7)),
            new Adj("Sharpen", new String[]{"Amount"}, new float[]{0}, new float[]{1}, new float[]{0},
                    v -> (s, o) -> Fx.sharpen(s, o, w, h, v[0])),
            new Adj("Bloom", new String[]{"Amount", "Radius"}, new float[]{0, 2}, new float[]{1, 80}, new float[]{0, 20},
                    v -> (s, o) -> Fx.bloom(s, o, w, h, v[0], v[1])),
            new Adj("Glitch", new String[]{"Amount"}, new float[]{0}, new float[]{1}, new float[]{0},
                    v -> (s, o) -> Fx.glitch(s, o, w, h, v[0], 11)),
            new Adj("Halftone", new String[]{"Dot size"}, new float[]{3}, new float[]{40}, new float[]{8},
                    v -> (s, o) -> Fx.halftone(s, o, w, h, v[0])),
            new Adj("Brightness / Contrast", new String[]{"Brightness", "Contrast"}, new float[]{-1, -0.95f}, new float[]{1, 0.95f}, new float[]{0, 0},
                    v -> (s, o) -> Fx.lut(s, o, Fx.brightnessContrast(v[0], v[1]))),
            new Adj("Curves", new String[0], new float[0], new float[0], new float[0],
                    v -> { float[][] c = curveView.arrays(); int[] l = Fx.curve(c[0], c[1]); return (s, o) -> Fx.lut(s, o, l); }),
            new Adj("Threshold", new String[]{"Level"}, new float[]{1}, new float[]{254}, new float[]{128},
                    v -> (s, o) -> Fx.lut(s, o, Fx.threshold(Math.round(v[0])))),
            new Adj("Posterize", new String[]{"Levels"}, new float[]{2}, new float[]{16}, new float[]{4},
                    v -> (s, o) -> Fx.lut(s, o, Fx.posterize(Math.round(v[0])))),
            new Adj("Invert", new String[0], new float[0], new float[0], new float[0],
                    v -> (s, o) -> Fx.lut(s, o, Fx.invert())),
        };
    }

    private void adjustMenu(View anchor) {
        if (canvas.adjusting()) finishAdjust(true);
        if (canvas.liquifying()) canvas.endLiquify(true);
        Adj[] all = adjustments();
        java.util.Map<String, Adj> by = new java.util.HashMap<>();
        for (Adj a : all) by.put(a.name, a);
        Ui.Pop pop = new Ui.Pop(this, 280);
        pop.title("Adjustments");
        String[][] groups = {
                {"Tone", "Brightness / Contrast", "Curves", "Threshold", "Posterize", "Invert"},
                {"Blur", "Gaussian Blur", "Motion Blur", "Perspective Blur"},
                {"Effects", "Noise", "Sharpen", "Bloom", "Glitch", "Halftone"},
                {"Distort", "Liquify", "Clone"}};
        for (String[] g : groups) {
            pop.section(g[0]);
            for (int i = 1; i < g.length; i++) {
                String n = g[i];
                if (n.equals("Liquify")) pop.item(n, true, this::startLiquify);
                else if (n.equals("Clone")) pop.item(n, true, this::startClone);
                else if (by.containsKey(n)) pop.item(n, true, () -> startAdjust(by.get(n)));
            }
        }
        pop.show(anchor);
    }

    private void startAdjust(Adj a) {
        setTool(CanvasView.ADJUST);
        adj = a;
        adjVals = a.def.clone();
        zoomCX = canvas.docW / 2f; zoomCY = canvas.docH / 2f;
        canvas.beginAdjust();
        TextView title = new TextView(this);
        title.setText(a.name + (a.params.length > 0 ? "  ·  slide on canvas to adjust" : ""));
        title.setTextColor(Color.WHITE);
        title.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(title);
        adjBars = new SeekBar[a.params.length];
        for (int i = 0; i < a.params.length; i++) {
            int k = i;
            TextView lab = new TextView(this);
            lab.setText(a.params[i]);
            lab.setTextColor(0xFF9A9A9E);
            box.addView(lab);
            SeekBar sb = new SeekBar(this);
            sb.setMax(1000);
            sb.setProgress(Math.round((a.def[i] - a.min[i]) / (a.max[i] - a.min[i]) * 1000));
            sb.setOnSeekBarChangeListener(new Slider() {
                public void onProgressChanged(SeekBar s, int p, boolean u) {
                    adjVals[k] = a.min[k] + (a.max[k] - a.min[k]) * p / 1000f;
                    canvas.previewAdjust(a.fn.apply(adjVals));
                }
            });
            adjBars[i] = sb;
            box.addView(sb, new LinearLayout.LayoutParams((int) (300 * dp), -2));
        }
        if (a.name.equals("Curves")) {
            curveView = new Ui.CurveView(this);
            curveView.listener = () -> canvas.previewAdjust(a.fn.apply(adjVals));
            box.addView(curveView, new LinearLayout.LayoutParams((int) (220 * dp), (int) (220 * dp)));
        }
        LinearLayout btns = barRow();
        chip(btns, "Reset", false, v -> {
            if (curveView != null && a.name.equals("Curves")) curveView.reset();
            for (int i = 0; i < adjBars.length; i++)
                adjBars[i].setProgress(Math.round((a.def[i] - a.min[i]) / (a.max[i] - a.min[i]) * 1000));
            canvas.previewAdjust(a.fn.apply(adjVals));
        });
        chip(btns, "Cancel", false, v -> finishAdjust(false));
        chip(btns, "Apply", true, v -> finishAdjust(true));
        showBar(box, btns);
        canvas.onAdjustDrag = (f, done) -> {
            if (adjBars.length == 0) return;
            if (dragStart < 0) dragStart = adjBars[0].getProgress();
            adjBars[0].setProgress(Math.max(0, Math.min(1000, Math.round(dragStart + f * 1000))));
            if (done) dragStart = -1;
        };
        dragStart = -1;
        canvas.onAdjustTap = (x, y) -> {
            if (a.name.startsWith("Perspective")) { zoomCX = x; zoomCY = y; canvas.previewAdjust(a.fn.apply(adjVals)); }
        };
        canvas.previewAdjust(a.fn.apply(adjVals));
    }

    private void finishAdjust(boolean apply) {
        if (!canvas.adjusting()) return;
        canvas.endAdjust(apply ? adj.fn.apply(adjVals) : null);
        bottom.setVisibility(View.GONE);
        setTool(CanvasView.DRAW);
    }

    private void startLiquify() {
        setTool(CanvasView.LIQUIFY);
        canvas.beginLiquify();
        showLiquifyBar();
    }

    private void showLiquifyBar() {
        LinearLayout modes = barRow();
        String[] names = {"Push", "Twirl ↻", "Twirl ↺", "Pinch", "Expand", "Reconstruct"};
        for (int i = 0; i < names.length; i++) {
            int k = i;
            chip(modes, names[i], canvas.liqMode == i, v -> { canvas.liqMode = k; showLiquifyBar(); });
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView sl = new TextView(this);
        sl.setText("Size");
        sl.setTextColor(0xFF9A9A9E);
        box.addView(sl);
        SeekBar size = new SeekBar(this);
        size.setMax(500);
        size.setProgress((int) canvas.liqSize);
        size.setOnSeekBarChangeListener(new Slider() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { canvas.liqSize = Math.max(10, p); }
        });
        box.addView(size, new LinearLayout.LayoutParams((int) (300 * dp), -2));
        TextView pl = new TextView(this);
        pl.setText("Pressure");
        pl.setTextColor(0xFF9A9A9E);
        box.addView(pl);
        SeekBar str = new SeekBar(this);
        str.setMax(100);
        str.setProgress(Math.round(canvas.liqStrength * 100));
        str.setOnSeekBarChangeListener(new Slider() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { canvas.liqStrength = Math.max(0.02f, p / 100f); }
        });
        box.addView(str, new LinearLayout.LayoutParams((int) (300 * dp), -2));
        LinearLayout btns = barRow();
        chip(btns, "Reset", false, v -> canvas.liquifyResetAll());
        chip(btns, "Cancel", false, v -> { canvas.endLiquify(false); setTool(CanvasView.DRAW); });
        chip(btns, "Apply", true, v -> { canvas.endLiquify(true); setTool(CanvasView.DRAW); });
        showBar(modes, box, btns);
    }

    private void startClone() {
        setTool(CanvasView.CLONE);
        canvas.cloneArmed = true;
        LinearLayout a = barRow();
        TextView t = new TextView(this);
        t.setText("Clone: tap to set the source, then paint. ");
        t.setTextColor(Color.WHITE);
        a.addView(t);
        chip(a, "Move source", false, v -> { canvas.cloneArmed = true; toast("Tap to set a new source"); });
        chip(a, "Done", true, v -> setTool(CanvasView.DRAW));
        showBar(a);
    }

    // ---------- ColorDrop: drag the colour circle onto the canvas to fill ----------

    private float cdX, cdY;
    private boolean cdDrag;

    private boolean colorDropTouch(View v, android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN: cdX = e.getRawX(); cdY = e.getRawY(); cdDrag = false; break;
            case android.view.MotionEvent.ACTION_MOVE:
                if (Math.hypot(e.getRawX() - cdX, e.getRawY() - cdY) > 24 * dp) cdDrag = true;
                break;
            case android.view.MotionEvent.ACTION_UP:
                if (cdDrag) {
                    int[] loc = new int[2];
                    canvas.getLocationOnScreen(loc);
                    canvas.colorDrop(e.getRawX() - loc[0], e.getRawY() - loc[1], 30);
                    return true;
                }
                break;
        }
        return false; // let taps through to the colour picker
    }

    // ---------- Layer options (tap the selected layer) ----------

    private void layerOptions(View anchor, int idx) {
        CanvasView.Layer l = canvas.layers.get(idx);
        Ui.PopMenu m = new Ui.PopMenu(this, anchor);
        Ui.PopMenu mm = m.getMenu();
        mm.add(0, 1, 0, "Rename");
        mm.add(0, 2, 1, "Select");
        mm.add(0, 3, 2, "Copy");
        mm.add(0, 4, 3, "Fill layer");
        mm.add(0, 5, 4, "Clear");
        mm.add(0, 6, 5, "Alpha lock").setCheckable(true).setChecked(l.alphaLock);
        mm.add(0, 7, 6, "Clipping mask").setCheckable(true).setChecked(l.clip);
        mm.add(0, 8, 7, "Invert");
        mm.add(0, 9, 8, "Merge down");
        Ui.PopMenu.Sub bm = mm.addSubMenu(0, 10, 9, "Blend mode: " + CanvasView.BLEND_NAMES[l.blend]);
        for (int i = 0; i < CanvasView.BLEND_NAMES.length; i++) bm.add(1, 100 + i, i, CanvasView.BLEND_NAMES[i]);
        mm.add(0, 11, 10, "Delete");
        if (l.mask == null) mm.add(0, 20, 11, "Add mask");
        else {
            mm.add(0, 21, 11, l.editMask ? "Edit layer (not mask)" : "Edit mask");
            mm.add(0, 22, 12, "Apply mask");
            mm.add(0, 23, 13, (l.maskOn ? "Disable" : "Enable") + " mask");
            mm.add(0, 24, 14, "Delete mask");
        }
        mm.add(0, 25, 15, "Group with layer below");
        mm.add(0, 26, 16, "New group");
        if (l.group != 0) mm.add(0, 27, 17, "Remove from group");
        m.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            if (id >= 100) { canvas.setBlend(idx, id - 100); return true; }
            switch (id) {
                case 1: {
                    android.widget.EditText et = new android.widget.EditText(this);
                    et.setText(l.name);
                    et.setSelectAllOnFocus(true);
                    new AlertDialog.Builder(this).setTitle("Rename layer").setView(et)
                            .setPositiveButton("OK", (d, w) -> canvas.rename(idx, et.getText().toString())).show();
                    break;
                }
                case 2: canvas.selectLayer(); break;
                case 3: if (!canvas.duplicate()) full(); break;
                case 4: canvas.fillLayer(); break;
                case 5: canvas.clearLayer(); break;
                case 6: canvas.toggleAlphaLock(idx); break;
                case 7: canvas.toggleClip(idx); break;
                case 8: canvas.invertLayer(); break;
                case 9: canvas.mergeDown(); break;
                case 11: confirm("Delete this layer?", canvas::deleteLayer); break;
                case 20: canvas.addMask(); toast("Mask added: paint black to hide, white to show"); break;
                case 21: canvas.editMask(!l.editMask); break;
                case 22: canvas.applyMask(); break;
                case 23: l.maskOn = !l.maskOn; canvas.invalidate(); refreshPanel(); break;
                case 24: canvas.deleteMask(); break;
                case 25: canvas.groupWithBelow(); break;
                case 26: canvas.newGroup(); break;
                case 27: canvas.ungroup(); break;
            }
            return true;
        });
        m.show();
    }

    private void blendMenu(View anchor, int idx) {
        Ui.PopMenu m = new Ui.PopMenu(this, anchor);
        for (int i = 0; i < CanvasView.BLEND_NAMES.length; i++) m.getMenu().add(0, i, i, CanvasView.BLEND_NAMES[i]);
        m.setOnMenuItemClickListener(it -> { canvas.setBlend(idx, it.getItemId()); return true; });
        m.show();
    }

    private void textDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * dp);
        box.setPadding(p, p, p, 0);
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("Text");
        et.setMinLines(2);
        box.addView(et);
        TextView sl = new TextView(this);
        sl.setText("Size");
        box.addView(sl);
        SeekBar sb = new SeekBar(this);
        sb.setMax(400);
        sb.setProgress(Math.max(24, canvas.docW / 12));
        box.addView(sb);
        android.widget.CheckBox bold = new android.widget.CheckBox(this);
        bold.setText("Bold");
        box.addView(bold);
        new AlertDialog.Builder(this).setTitle("Add text").setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String t = et.getText().toString();
                    if (t.trim().isEmpty()) return;
                    if (!canvas.addTextLayer(t, Math.max(8, sb.getProgress()), bold.isChecked())) { full(); return; }
                    setTool(CanvasView.MOVE);
                    toast("Text added on a new layer. Drag to place it, then tap Done.");
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void guideMenu(View anchor) {
        Ui.PopMenu m = new Ui.PopMenu(this, anchor);
        String[] g = {"Off", "2D grid", "Symmetry: vertical", "Symmetry: horizontal", "Symmetry: quadrant", "Symmetry: radial (8)",
                "Isometric", "Perspective: 1-point", "Perspective: 2-point", "Perspective: 3-point"};
        for (int i = 0; i < g.length; i++) m.getMenu().add(0, i, i, (canvas.guide == i ? "✓ " : "") + g[i]);
        m.getMenu().add(0, 50, 50, (canvas.assist ? "✓ " : "") + "Drawing assist (lock strokes to the guide)");
        m.getMenu().add(0, 51, 51, "Grid / isometric spacing…");
        m.getMenu().add(0, 52, 52, "Move vanishing points…");
        m.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            if (id == 50) { canvas.assist = !canvas.assist; return true; }
            if (id == 51) {
                android.widget.EditText et = new android.widget.EditText(this);
                et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                et.setText(String.valueOf(canvas.gridSize));
                new AlertDialog.Builder(this).setTitle("Spacing (pixels)").setView(et)
                        .setPositiveButton("OK", (d, w) -> { canvas.gridSize = Math.max(8, parse(et)); canvas.invalidate(); }).show();
                return true;
            }
            if (id == 52) { editVanishingPoints(); return true; }
            canvas.guide = id;
            if (id >= 7) { canvas.resetVps(); editVanishingPoints(); }
            canvas.invalidate();
            return true;
        });
        m.show();
    }

    private void editVanishingPoints() {
        if (canvas.guide < 7) { toast("Choose a perspective guide first"); return; }
        canvas.editGuide = true;
        canvas.invalidate();
        LinearLayout a = barRow();
        TextView t = new TextView(this);
        t.setText("Drag the vanishing points. ");
        t.setTextColor(Color.WHITE);
        a.addView(t);
        chip(a, (canvas.assist ? "✓ " : "") + "Assist", canvas.assist, v -> { canvas.assist = !canvas.assist; editVanishingPoints(); });
        chip(a, "Reset", false, v -> canvas.resetVps());
        chip(a, "Done", true, v -> { canvas.editGuide = false; canvas.invalidate(); bottom.setVisibility(View.GONE); });
        showBar(a);
    }

    private Ui.Icon icon(LinearLayout parent, int kind, int size, View.OnClickListener l) {
        Ui.Icon i = new Ui.Icon(this, kind);
        i.setOnClickListener(l);
        parent.addView(i, new LinearLayout.LayoutParams(size, size));
        return i;
    }

    /** Actions menu (top-left): files, canvas, settings, help. */
    private static final String[] ACTION_TABS = {"Add", "Canvas", "Share", "Video", "Prefs", "Help"};
    private int actionTab;

    private void actionsMenu(View anchor) { actionsMenu(anchor, actionTab); }

    /** Actions panel with tabs, like Procreate's wrench menu. */
    private void actionsMenu(View anchor, int tab) {
        actionTab = tab;
        Ui.Pop pop = new Ui.Pop(this, 340);
        LinearLayout tabs = barRow();
        tabs.setPadding((int) (6 * dp), 0, (int) (6 * dp), (int) (4 * dp));
        for (int i = 0; i < ACTION_TABS.length; i++) {
            int k = i;
            TextView t = chip(tabs, ACTION_TABS[i], i == tab, v -> { pop.win.dismiss(); actionsMenu(anchor, k); });
            t.setTextSize(13);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(tabs);
        pop.add(hs);
        boolean left = sideLp.gravity == (Gravity.START | Gravity.CENTER_VERTICAL);
        switch (tab) {
            case 0: // Add
                pop.item("Insert a file", true, () -> action(2, anchor));
                pop.item("Open as a new drawing", true, () -> action(1, anchor));
                pop.item("Add text", true, () -> action(9, anchor));
                pop.item("Cut", true, () -> canvas.copy(false, true));
                pop.item("Copy", true, () -> canvas.copy(false, false));
                pop.item("Copy canvas", true, () -> canvas.copy(true, false));
                pop.item("Paste", canvas.hasClipboard(), () -> { if (!canvas.paste()) full(); });
                break;
            case 1: // Canvas
                pop.item("Crop & resize", true, () -> action(4, anchor));
                pop.toggle("Animation Assist", canvas.anim, v -> { pop.win.dismiss(); action(12, anchor); });
                pop.toggle("Drawing guide", canvas.guide != 0, v -> { pop.win.dismiss(); if (v) guideMenu(anchor); else { canvas.guide = 0; canvas.invalidate(); } });
                pop.item("Edit drawing guide", true, () -> guideMenu(anchor));
                pop.toggle("Reference", refView != null && refView.getVisibility() == View.VISIBLE, v -> { pop.win.dismiss(); action(11, anchor); });
                pop.item("Flip canvas horizontally", true, () -> canvas.flipView(true));
                pop.item("Flip canvas vertically", true, () -> canvas.flipView(false));
                pop.item("Fit to screen", true, () -> action(5, anchor));
                pop.item("Canvas information", true, this::canvasInfo);
                break;
            case 2: // Share
                pop.section("Share image");
                for (int i = FORMATS.length - 1; i >= 0; i--) { int f = i; pop.item(FORMATS[i].replace(" (layers)", ""), true, () -> startExport(f)); }
                pop.section("Share layers");
                pop.item("Animated GIF", true, () -> exportAnimRequest(true));
                pop.item("Animated MP4", true, () -> exportAnimRequest(false));
                break;
            case 3: // Video
                pop.item("Time-lapse replay", true, this::replayTimelapse);
                pop.toggle("Record time-lapse", tlOn, v -> { tlOn = v; prefs.edit().putBoolean("timelapse", tlOn).apply(); });
                pop.item("Export time-lapse: full length", true, () -> timelapseExport(false));
                pop.item("Export time-lapse: 30 seconds", true, () -> timelapseExport(true));
                pop.item("Delete recorded time-lapse", true, () -> confirm("Delete the recorded time-lapse?", () -> { for (File f : tlFrames()) f.delete(); }));
                break;
            case 4: // Prefs
                pop.toggle("Right-hand interface", !left, v -> action(7, anchor));
                pop.toggle("Fingers paint when using a stylus", canvas.fingerPaint, v -> action(14, anchor));
                pop.item("Import brushes", true, () -> action(6, anchor));
                break;
            default: // Help
                pop.item("Gesture controls", true, () -> action(8, anchor));
                pop.item("About Inkwell", true, () -> new AlertDialog.Builder(this).setTitle("Inkwell")
                        .setMessage("A tiny greyscale drawing app. No libraries, plain Java.").setPositiveButton("OK", null).show());
        }
        pop.show(anchor);
    }

    private void exportAnimRequest(boolean gif) {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(gif ? "image/gif" : "video/mp4")
                .putExtra(Intent.EXTRA_TITLE, nameOf(curId) + (gif ? ".gif" : ".mp4")), gif ? REQ_GIF : REQ_AMP4);
    }

    private void timelapseExport(boolean shortVersion) {
        tlShort = shortVersion;
        if (tlFrames().length == 0) { toast("Nothing recorded yet"); return; }
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/mp4").putExtra(Intent.EXTRA_TITLE, nameOf(curId) + " time-lapse.mp4"), REQ_TL);
    }

    private void canvasInfo() {
        new AlertDialog.Builder(this).setTitle("Canvas information").setMessage(
                "Size: " + canvas.docW + " × " + canvas.docH + " px\n"
              + "Layers: " + canvas.layers.size() + " of " + canvas.maxLayers() + " possible\n"
              + "Time-lapse frames: " + tlFrames().length + "\n"
              + "Name: " + nameOf(curId)).setPositiveButton("OK", null).show();
    }

    /** Runs an Actions command by id (also used by tests). */
    private void action(int id, View anchor) {
        switch (id) {
            case 1: openDoc(REQ_OPEN); break;
            case 2: openDoc(REQ_IMPORT); break;
            case 4: canvasDialog(); break;
            case 5: canvas.animateFit(); break;
            case 6: openDoc(REQ_BRUSH); break;
            case 9: textDialog(); break;
            case 10: guideMenu(anchor); break;
            case 11: toggleReference(); break;
            case 12: toggleAnimation(); break;
            case 13: timelapseMenu(anchor); break;
            case 14:
                canvas.fingerPaint = !canvas.fingerPaint;
                prefs.edit().putBoolean("fingerPaint", canvas.fingerPaint).apply();
                break;
            case 7:
                sideLp.gravity = sideLp.gravity == (Gravity.START | Gravity.CENTER_VERTICAL)
                        ? Gravity.END | Gravity.CENTER_VERTICAL : Gravity.START | Gravity.CENTER_VERTICAL;
                side.setLayoutParams(sideLp);
                break;
            case 8:
                new AlertDialog.Builder(this).setTitle("Gesture controls").setMessage(
                        "Two-finger tap: undo · hold: keep undoing\n"
                      + "Three-finger tap: redo\n"
                      + "Pinch / drag / twist: zoom, move, rotate\n"
                      + "Quick pinch-in: fit to screen\n"
                      + "Three-finger scrub: clear layer\n"
                      + "Three-finger swipe down: copy & paste menu\n"
                      + "Four-finger tap: full screen\n"
                      + "Touch and hold: eyedropper\n"
                      + "Draw and hold: QuickShape (line / ellipse)\n"
                      + "Tap the active brush, smudge or eraser: Brush Library\n"
                      + "Tap the selected brush in the library: Brush Studio\n"
                      + "Drag the colour circle onto the canvas: fill (ColorDrop)\n"
                      + "Tap the selected layer: layer options\n"
                      + "In an adjustment: slide on the canvas to change the amount\n"
                      + "Layers: tap the mask thumbnail to paint the mask")
                        .setPositiveButton("OK", null).show();
                break;
        }
    }

    private void openDoc(int req) {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), req);
    }

    private void startExport(int fmt) {
        exportFormat = fmt;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(MIMES[fmt])
                .putExtra(Intent.EXTRA_TITLE, "inkwell_" + System.currentTimeMillis() / 1000 + "." + EXTS[fmt]), REQ_EXPORT);
    }

    private String libSet;

    /** Brush Library, like Procreate: brush sets on the left, brushes with stroke previews on the right. */
    private void pickBrush() {
        if (libSet == null) libSet = canvas.brush.set;
        Ui.Pop pop = new Ui.Pop(this, 440);
        LinearLayout head = barRow();
        head.setPadding((int) (16 * dp), 0, (int) (8 * dp), 0);
        TextView t = new TextView(this);
        t.setText("Brush Library");
        t.setTextColor(Color.WHITE);
        t.setTextSize(17);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        chip(head, "Import", false, v -> { pop.win.dismiss(); openDoc(REQ_BRUSH); });
        chip(head, "＋", false, v -> { // new brush = copy of the current one, then edit it
            pop.win.dismiss();
            CanvasView.Brush b = canvas.brush.copy(canvas.brush.name + " copy");
            b.set = libSet;
            CanvasView.BRUSHES.add(b);
            canvas.brush = b;
            saveBrush(b);
            updateBrush();
            brushStudio(b);
        });
        pop.add(head);
        LinearLayout cols = new LinearLayout(this);
        LinearLayout sets = new LinearLayout(this);
        sets.setOrientation(LinearLayout.VERTICAL);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            sets.removeAllViews();
            list.removeAllViews();
            for (String set : CanvasView.SETS) {
                boolean any = false;
                for (CanvasView.Brush b : CanvasView.BRUSHES) if (b.set.equals(set)) any = true;
                if (!any && !set.equals(libSet)) continue;
                TextView st = new TextView(this);
                st.setText(set);
                st.setTextSize(15);
                st.setPadding((int) (14 * dp), (int) (12 * dp), (int) (8 * dp), (int) (12 * dp));
                boolean on = set.equals(libSet);
                st.setTextColor(on ? Color.WHITE : 0xFFAEAEB2);
                st.setBackgroundColor(on ? Ui.ACCENT : 0);
                st.setOnClickListener(v -> { libSet = set; fill[0].run(); });
                sets.addView(st);
            }
            for (CanvasView.Brush br : CanvasView.BRUSHES) {
                if (!br.set.equals(libSet)) continue;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
                boolean on = br == canvas.brush;
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(on ? Ui.ACCENT : 0);
                bg.setCornerRadius(8 * dp);
                row.setBackground(bg);
                TextView nm = new TextView(this);
                nm.setText(br.name + (on ? "   ·  tap again to edit" : ""));
                nm.setTextSize(14);
                nm.setTextColor(on ? Color.WHITE : 0xFFE8E8E8);
                row.addView(nm);
                ImageView pv = new ImageView(this);
                pv.setImageBitmap(brushPreview(br));
                row.addView(pv, new LinearLayout.LayoutParams((int) (240 * dp), (int) (36 * dp)));
                row.setOnClickListener(v -> {
                    if (canvas.brush == br) { pop.win.dismiss(); brushStudio(br); return; }
                    canvas.brush = br;
                    updateBrush();
                    fill[0].run();
                });
                row.setOnLongClickListener(v -> { brushMenu(v, br, pop); return true; });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(0, 2, (int) (8 * dp), 2);
                list.addView(row, lp);
            }
        };
        fill[0].run();
        cols.addView(sets, new LinearLayout.LayoutParams((int) (130 * dp), -2));
        cols.addView(list, new LinearLayout.LayoutParams(0, -2, 1f));
        pop.add(cols);
        pop.show(brushBtn);
    }

    /** Long-press a brush: duplicate / reset / delete. */
    private void brushMenu(View anchor, CanvasView.Brush br, Ui.Pop lib) {
        Ui.Pop pop = new Ui.Pop(this, 240);
        pop.item("Edit in Brush Studio", true, () -> { lib.win.dismiss(); canvas.brush = br; updateBrush(); brushStudio(br); });
        pop.item("Duplicate", true, () -> {
            CanvasView.Brush b = br.copy(br.name + " copy");
            CanvasView.BRUSHES.add(CanvasView.BRUSHES.indexOf(br) + 1, b);
            saveBrush(b);
            lib.win.dismiss();
            pickBrush();
        });
        pop.item("Reset to original", br.builtin && CanvasView.DEFAULTS.containsKey(br.name), () -> {
            br.setValues(CanvasView.DEFAULTS.get(br.name));
            if (br.file != null) { br.file.delete(); br.file = null; }
            previews.remove(br);
            lib.win.dismiss();
            pickBrush();
        });
        pop.item("Delete", !br.builtin, () -> confirm("Delete brush \"" + br.name + "\"?", () -> {
            if (br.file != null) br.file.delete();
            CanvasView.BRUSHES.remove(br);
            if (canvas.brush == br) canvas.brush = CanvasView.BRUSHES.get(0);
            updateBrush();
            lib.win.dismiss();
        }));
        pop.show(anchor);
    }

    /** Saves a brush's settings (built-in brushes keep their edits too). */
    private void saveBrush(CanvasView.Brush b) {
        try {
            if (b.file == null) {
                String safe = b.name.replaceAll("[^A-Za-z0-9]+", "_");
                b.file = new File(brushDir(), (b.builtin ? "b_" : "u_" + System.currentTimeMillis() + "_") + safe + ".fbr");
            }
            Brushes.save(b, b.file);
        } catch (Exception e) { toast("Could not save brush: " + e.getMessage()); }
    }

    // ---------- Brush Studio ----------

    /** Setting: key, label, min, max, how to show the value. */
    private static final Object[][][] STUDIO = {
        {{"Stroke path"}, {"spacing", "Spacing", 0.02f, 1f, "%"}, {"jitterSize", "Size jitter", 0f, 1f, "%"},
                {"jitterOpacity", "Opacity jitter", 0f, 1f, "%"}, {"scatter", "Scatter", 0f, 1f, "%"}},
        {{"Stabilization"}, {"streamline", "StreamLine (smoothing)", 0f, 1f, "%"}, {"stabilize", "Stabilization", 0f, 1f, "%"}},
        {{"Taper"}, {"taperStart", "Start taper", 0f, 1f, "%"}, {"taperEnd", "End taper", 0f, 1f, "%"}},
        {{"Shape"}, {"roundness", "Roundness", 0.05f, 1f, "%"}, {"angle", "Angle", 0f, 180f, "°"},
                {"follow", "Rotate with stroke", 0f, 1f, "toggle"}, {"hard", "Hardness", 0f, 1f, "%"}},
        {{"Grain"}, {"grain", "Grain depth", 0f, 1f, "%"}, {"grainScale", "Grain scale", 0.25f, 4f, "×"}},
        {{"Dynamics"}, {"minSize", "Pressure → size", 0f, 1f, "inv%"}, {"minAlpha", "Pressure → opacity", 0f, 1f, "inv%"},
                {"curve", "Pressure curve (soft … firm)", 0.3f, 3f, "×"}},
        {{"Properties"}, {"sizeScale", "Maximum size", 0.1f, 3f, "×"}, {"alpha", "Maximum opacity", 0.05f, 1f, "%"}},
        {{"About this brush"}},
    };

    private FrameLayout studio;
    private int studioTab;

    private void brushStudio(CanvasView.Brush b) {
        float[] before = b.values();
        String beforeName = b.name, beforeSet = b.set;
        if (studio != null) shell.removeView(studio);
        studio = new FrameLayout(this);
        studio.setBackgroundColor(0xFF111113);
        studio.setClickable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = barRow();
        head.setPadding((int) (12 * dp), 0, (int) (8 * dp), 0);
        TextView t = new TextView(this);
        t.setText("Brush Studio  ·  " + b.name);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        chip(head, "Cancel", false, v -> {
            b.setValues(before); b.name = beforeName; b.set = beforeSet;
            previews.remove(b);
            closeStudio();
        });
        chip(head, "Done", true, v -> { previews.remove(b); saveBrush(b); updateBrush(); closeStudio(); });
        col.addView(head, new LinearLayout.LayoutParams(-1, (int) (52 * dp)));
        // live preview "drawing pad"
        ImageView pad = new ImageView(this);
        pad.setBackgroundColor(0xFF2C2C2E);
        int pw = (int) (Math.min(getResources().getDisplayMetrics().widthPixels, 900 * dp) - 24 * dp);
        Runnable redraw = () -> pad.setImageBitmap(CanvasView.brushPreview(this, b, pw, (int) (110 * dp)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, (int) (110 * dp));
        plp.setMargins((int) (12 * dp), 0, (int) (12 * dp), (int) (8 * dp));
        col.addView(pad, plp);
        LinearLayout body = new LinearLayout(this);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.VERTICAL);
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0);
        Runnable[] show = new Runnable[1];
        show[0] = () -> {
            tabs.removeAllViews();
            settings.removeAllViews();
            for (int i = 0; i < STUDIO.length; i++) {
                int k = i;
                TextView tt = new TextView(this);
                tt.setText((String) STUDIO[i][0][0]);
                tt.setTextSize(15);
                tt.setPadding((int) (14 * dp), (int) (12 * dp), (int) (8 * dp), (int) (12 * dp));
                tt.setTextColor(i == studioTab ? Color.WHITE : 0xFFAEAEB2);
                tt.setBackgroundColor(i == studioTab ? Ui.ACCENT : 0);
                tt.setOnClickListener(v -> { studioTab = k; show[0].run(); });
                tabs.addView(tt);
            }
            Object[][] sec = STUDIO[studioTab];
            if (sec.length == 1) { aboutBrush(settings, b, t); return; }
            for (int i = 1; i < sec.length; i++) {
                String key = (String) sec[i][0], label = (String) sec[i][1], fmt = (String) sec[i][4];
                float mn = (Float) sec[i][2], mx = (Float) sec[i][3];
                if (fmt.equals("toggle")) {
                    android.widget.Switch sw = new android.widget.Switch(this);
                    sw.setText(label);
                    sw.setTextColor(Color.WHITE);
                    sw.setTextSize(15);
                    sw.setChecked(b.get(key) >= 0.5f);
                    sw.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
                    sw.setOnCheckedChangeListener((x, on) -> { b.put(key, on ? 1 : 0); redraw.run(); });
                    settings.addView(sw);
                    continue;
                }
                boolean inv = fmt.equals("inv%");
                TextView lab = new TextView(this);
                lab.setTextColor(0xFFE8E8E8);
                lab.setTextSize(14);
                lab.setPadding(0, (int) (10 * dp), 0, 0);
                settings.addView(lab);
                SeekBar sb = new SeekBar(this);
                sb.setMax(1000);
                float cur = inv ? 1 - b.get(key) : b.get(key);
                sb.setProgress(Math.round((cur - mn) / (mx - mn) * 1000));
                Runnable label2 = () -> {
                    float v = b.get(key);
                    if (inv) v = 1 - v;
                    String shown = fmt.equals("°") ? Math.round(v) + "°" : fmt.equals("×") ? String.format("%.2f×", v) : Math.round(v * 100) + "%";
                    lab.setText(label + "   " + shown);
                };
                label2.run();
                sb.setOnSeekBarChangeListener(new Slider() {
                    public void onProgressChanged(SeekBar s2, int pr, boolean u) {
                        float v = mn + (mx - mn) * pr / 1000f;
                        b.put(key, inv ? 1 - v : v);
                        label2.run();
                    }
                    public void onStopTrackingTouch(SeekBar s2) { redraw.run(); }
                });
                settings.addView(sb, new LinearLayout.LayoutParams(-1, (int) (40 * dp)));
            }
        };
        show[0].run();
        redraw.run();
        ScrollView ts = new ScrollView(this);
        ts.addView(tabs);
        ScrollView ss = new ScrollView(this);
        ss.addView(settings);
        body.addView(ts, new LinearLayout.LayoutParams((int) (170 * dp), -1));
        body.addView(ss, new LinearLayout.LayoutParams(0, -1, 1f));
        col.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        studio.addView(col);
        shell.addView(studio);
    }

    private void aboutBrush(LinearLayout settings, CanvasView.Brush b, TextView title) {
        TextView nl = new TextView(this);
        nl.setText("Name");
        nl.setTextColor(0xFF8E8E93);
        settings.addView(nl);
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(b.name);
        et.setTextColor(Color.WHITE);
        et.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int x, int y) { }
            public void onTextChanged(CharSequence c, int a, int x, int y) { }
            public void afterTextChanged(android.text.Editable e) {
                if (e.length() > 0 && !b.builtin) { b.name = e.toString(); title.setText("Brush Studio  ·  " + b.name); }
            }
        });
        et.setEnabled(!b.builtin);
        settings.addView(et);
        TextView sl = new TextView(this);
        sl.setText("Brush set");
        sl.setTextColor(0xFF8E8E93);
        sl.setPadding(0, (int) (12 * dp), 0, 0);
        settings.addView(sl);
        LinearLayout sets = barRow();
        for (String set : CanvasView.SETS) chip(sets, set, set.equals(b.set), v -> { b.set = set; libSet = set; settings.removeAllViews(); aboutBrush(settings, b, title); });
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(sets);
        settings.addView(hs);
        TextView info = new TextView(this);
        info.setText(b.builtin ? "Built-in brush. Your changes are kept; long-press it in the library to reset."
                : "Your brush. Long-press it in the library to duplicate or delete.");
        info.setTextColor(0xFF8E8E93);
        info.setPadding(0, (int) (12 * dp), 0, 0);
        settings.addView(info);
    }

    private void closeStudio() {
        if (studio != null) { shell.removeView(studio); studio = null; }
    }

    private final java.util.HashMap<CanvasView.Brush, Bitmap> previews = new java.util.HashMap<>();

    /** A sample stroke drawn with the brush itself (white ink for the dark library). */
    private Bitmap brushPreview(CanvasView.Brush b) {
        Bitmap bm = previews.get(b);
        if (bm == null) {
            bm = CanvasView.brushPreview(this, b, (int) (240 * dp), (int) (36 * dp));
            previews.put(b, bm);
        }
        return bm;
    }

    private void updateBrush() { brushName.setText(canvas.brush.name); }

    private void updateColor() {
        int g = canvas.grey;
        colorBtn.grey = g;
        colorBtn.invalidate();
    }

    /** Grey picker: slider (black..white) plus quick swatches. */
    private final java.util.ArrayList<Integer> greyHistory = new java.util.ArrayList<>();

    /** Colour panel (greyscale): a tone strip, fine slider, palette and history. */
    private void pickGrey() {
        Ui.Pop pop = new Ui.Pop(this, 320);
        pop.title("Colour");
        int p = (int) (14 * dp);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, 0, p, p);
        View preview = new View(this);
        SeekBar sb = new SeekBar(this);
        sb.setMax(255);
        Runnable show = () -> {
            int g = 255 - sb.getProgress();
            preview.setBackgroundColor(Color.rgb(g, g, g));
            canvas.grey = g;
            updateColor();
        };
        View strip = new View(this) { // tap or drag along the tone strip
            final Paint pt = new Paint();
            @Override protected void onDraw(Canvas c) {
                pt.setShader(new android.graphics.LinearGradient(0, 0, getWidth(), 0, Color.BLACK, Color.WHITE, android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(0, 0, getWidth(), getHeight(), pt);
                float rr = getHeight() / 2.6f, x = rr + canvas.grey / 255f * (getWidth() - 2 * rr);
                Paint r = new Paint(Paint.ANTI_ALIAS_FLAG);
                r.setStyle(Paint.Style.STROKE);
                r.setStrokeWidth(3 * dp);
                r.setColor(Ui.ACCENT);
                c.drawCircle(x, getHeight() / 2f, rr, r);
            }
            @Override public boolean onTouchEvent(android.view.MotionEvent e) {
                int g = Math.max(0, Math.min(255, Math.round(e.getX() / getWidth() * 255)));
                sb.setProgress(255 - g);
                invalidate();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        };
        box.addView(strip, new LinearLayout.LayoutParams(-1, (int) (56 * dp)));
        sb.setProgress(255 - canvas.grey);
        sb.setOnSeekBarChangeListener(new Slider() {
            public void onProgressChanged(SeekBar s, int v, boolean u) { show.run(); strip.invalidate(); }
        });
        box.addView(sb, new LinearLayout.LayoutParams(-1, (int) (44 * dp)));
        box.addView(preview, new LinearLayout.LayoutParams(-1, (int) (28 * dp)));
        TextView pl = new TextView(this);
        pl.setText("PALETTE");
        pl.setTextColor(0xFF8E8E93);
        pl.setTextSize(12);
        pl.setPadding(0, p, 0, 4);
        box.addView(pl);
        box.addView(swatches(new int[]{0, 32, 64, 96, 128, 160, 192, 224, 255}, sb));
        if (!greyHistory.isEmpty()) {
            TextView hl = new TextView(this);
            hl.setText("HISTORY");
            hl.setTextColor(0xFF8E8E93);
            hl.setTextSize(12);
            hl.setPadding(0, p, 0, 4);
            box.addView(hl);
            int[] h = new int[greyHistory.size()];
            for (int i = 0; i < h.length; i++) h[i] = greyHistory.get(i);
            box.addView(swatches(h, sb));
        }
        pop.add(box);
        show.run();
        pop.win.setOnDismissListener(() -> { // remember recently used greys
            greyHistory.remove(Integer.valueOf(canvas.grey));
            greyHistory.add(0, canvas.grey);
            while (greyHistory.size() > 9) greyHistory.remove(greyHistory.size() - 1);
        });
        pop.show(colorBtn);
    }

    private LinearLayout swatches(int[] greys, SeekBar sb) {
        LinearLayout sw = new LinearLayout(this);
        for (int g : greys) {
            View v = new View(this);
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setColor(Color.rgb(g, g, g));
            d.setCornerRadius(6 * dp);
            d.setStroke(1, 0xFF636366);
            v.setBackground(d);
            v.setOnClickListener(x -> sb.setProgress(255 - g));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (28 * dp), (int) (28 * dp));
            lp.setMargins(2, 2, (int) (4 * dp), 2);
            sw.addView(v, lp);
        }
        return sw;
    }

    private void confirm(String msg, Runnable yes) {
        new AlertDialog.Builder(this).setMessage(msg)
                .setPositiveButton("OK", (d, w) -> yes.run())
                .setNegativeButton("Cancel", null).show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void full() { toast("Layer limit reached for this canvas size"); }

    private void togglePanel() {
        panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        layersBtn.setOn(panel.getVisibility() == View.VISIBLE);
        refreshPanel();
    }

    private Bitmap thumbOf(CanvasView.Layer l, int tw, int th) {
        if (l.dirty || l.thumb == null || l.thumb.getWidth() != tw) {
            Bitmap t = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(t);
            c.drawColor(Color.WHITE);
            c.drawBitmap(l.bmp, null, new Rect(0, 0, tw, th), new Paint(Paint.FILTER_BITMAP_FLAG));
            l.thumb = t;
            l.dirty = false;
        }
        return l.thumb;
    }

    private void refreshPanel() {
        if (canvas.anim) refreshTimeline();
        if (panel == null || panel.getVisibility() != View.VISIBLE) return;
        layerList.removeAllViews();
        opacityBar.setProgress(canvas.cur().opacity);
        int tw = (int) (44 * dp), th = Math.max(1, tw * canvas.docH / canvas.docW);
        int lastGroup = 0;
        for (int i = canvas.layers.size() - 1; i >= 0; i--) { // top layer first
            CanvasView.Layer l = canvas.layers.get(i);
            final int idx = i;
            if (l.group != 0 && l.group != lastGroup) groupHeader(l.group);
            lastGroup = l.group;
            CanvasView.Group gr = canvas.groups.get(l.group);
            if (l.group != 0 && gr != null && gr.collapsed) continue;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(l.group != 0 ? (int) (18 * dp) : 0, (int) (3 * dp), 0, (int) (3 * dp));
            r.setBackgroundColor(i == canvas.current ? 0xFF48484A : 0);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(thumbOf(l, tw, th));
            iv.setBackgroundColor(i == canvas.current && !l.editMask ? Ui.ACCENT : 0xFF888888);
            iv.setPadding(2, 2, 2, 2);
            iv.setOnClickListener(v -> { canvas.select(idx); canvas.editMask(false); });
            r.addView(iv, new LinearLayout.LayoutParams(tw, th + 4));
            if (l.mask != null) {
                ImageView mv = new ImageView(this);
                mv.setImageBitmap(thumbOf(l.mask, tw, th));
                mv.setBackgroundColor(i == canvas.current && l.editMask ? Ui.ACCENT : 0xFF555555);
                mv.setPadding(2, 2, 2, 2);
                mv.setAlpha(l.maskOn ? 1f : 0.4f);
                mv.setOnClickListener(v -> { canvas.select(idx); canvas.editMask(true); });
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(tw, th + 4);
                mlp.leftMargin = (int) (4 * dp);
                r.addView(mv, mlp);
            }
            TextView name = new TextView(this);
            name.setText((l.clip ? "↳ " : "") + l.name + (l.alphaLock ? "  α" : ""));
            name.setTextColor(Color.WHITE);
            name.setSingleLine(true);
            name.setPadding((int) (8 * dp), 0, 0, 0);
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView bl = new TextView(this);
            bl.setText(CanvasView.BLEND_NAMES[l.blend].substring(0, 1));
            bl.setTextColor(l.blend == 0 ? 0xFF9A9A9E : Ui.ACCENT);
            bl.setPadding((int) (8 * dp), (int) (8 * dp), (int) (4 * dp), (int) (8 * dp));
            bl.setOnClickListener(v -> blendMenu(v, idx));
            r.addView(bl);
            TextView vis = new TextView(this);
            vis.setText(l.visible ? "●" : "○");
            vis.setTextSize(16);
            vis.setTextColor(l.visible ? Color.WHITE : 0xFF888888);
            vis.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
            vis.setOnClickListener(v -> canvas.toggleVisible(idx));
            r.addView(vis);
            r.setOnClickListener(v -> { if (idx == canvas.current) layerOptions(v, idx); else canvas.select(idx); });
            layerList.addView(r);
        }
    }

    private void groupHeader(int g) {
        CanvasView.Group gr = canvas.groups.get(g);
        if (gr == null) { gr = new CanvasView.Group("Group"); canvas.groups.put(g, gr); }
        CanvasView.Group G = gr;
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackgroundColor(0xFF3A3A3C);
        TextView arrow = new TextView(this);
        arrow.setText(gr.collapsed ? "▸" : "▾");
        arrow.setTextColor(Color.WHITE);
        arrow.setTextSize(16);
        arrow.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
        arrow.setOnClickListener(v -> { G.collapsed = !G.collapsed; refreshPanel(); });
        r.addView(arrow);
        TextView n = new TextView(this);
        n.setText(gr.name + (gr.opacity < 255 ? "  " + gr.opacity * 100 / 255 + "%" : ""));
        n.setTextColor(Color.WHITE);
        n.setOnClickListener(v -> groupMenu(v, g));
        r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView vis = new TextView(this);
        vis.setText(gr.visible ? "●" : "○");
        vis.setTextSize(16);
        vis.setTextColor(Color.WHITE);
        vis.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
        vis.setOnClickListener(v -> { G.visible = !G.visible; canvas.invalidate(); refreshPanel(); });
        r.addView(vis);
        layerList.addView(r);
    }

    private void groupMenu(View anchor, int g) {
        CanvasView.Group gr = canvas.groups.get(g);
        Ui.PopMenu m = new Ui.PopMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Rename group");
        m.getMenu().add(0, 2, 1, "Group opacity…");
        m.getMenu().add(0, 3, 2, "Flatten group");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: {
                    android.widget.EditText et = new android.widget.EditText(this);
                    et.setText(gr.name);
                    new AlertDialog.Builder(this).setTitle("Rename group").setView(et)
                            .setPositiveButton("OK", (d, w) -> { gr.name = et.getText().toString(); refreshPanel(); }).show();
                    break;
                }
                case 2: {
                    SeekBar sb = new SeekBar(this);
                    sb.setMax(255);
                    sb.setProgress(gr.opacity);
                    sb.setOnSeekBarChangeListener(new Slider() {
                        public void onProgressChanged(SeekBar s, int p, boolean u) { gr.opacity = p; canvas.invalidate(); }
                    });
                    new AlertDialog.Builder(this).setTitle("Group opacity").setView(sb)
                            .setPositiveButton("OK", (d, w) -> refreshPanel()).show();
                    break;
                }
                case 3: canvas.flattenGroup(g); break;
            }
            return true;
        });
        m.show();
    }

    // ---------- Reference window ----------

    private LinearLayout refView;
    private View refContent;
    private int refMode;            // 0 canvas, 1 image
    private Bitmap refImage;

    private void toggleReference() {
        if (refView == null) {
            refView = new LinearLayout(this);
            refView.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Ui.PANEL);
            bg.setCornerRadius(10 * dp);
            refView.setBackground(bg);
            refView.setPadding((int) (4 * dp), 0, (int) (4 * dp), (int) (4 * dp));
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView cv = chip(head, "Canvas", refMode == 0, null), im = chip(head, "Image", refMode == 1, null);
            View gap = new View(this);
            head.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
            cv.setOnClickListener(v -> { refMode = 0; toggleReference(); toggleReference(); });
            im.setOnClickListener(v -> {
                if (refImage == null) { openDoc(REQ_REF); return; }
                refMode = 1; toggleReference(); toggleReference();
            });
            TextView rs = chip(head, "⤡", false, null);
            rs.setTextSize(20);
            TextView x = chip(head, "✕", false, v -> refView.setVisibility(View.GONE));
            head.setOnTouchListener(dragMove(refView));
            rs.setOnTouchListener(dragResize(refView));
            refView.addView(head, new LinearLayout.LayoutParams(-1, (int) (40 * dp)));
            refContent = new View(this) {
                @Override
                protected void onDraw(Canvas c) {
                    float w = getWidth(), h = getHeight();
                    if (refMode == 1 && refImage != null) {
                        float s = Math.min(w / refImage.getWidth(), h / refImage.getHeight());
                        c.translate((w - refImage.getWidth() * s) / 2, (h - refImage.getHeight() * s) / 2);
                        c.scale(s, s);
                        c.drawBitmap(refImage, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
                    } else if (canvas.docW > 0) {
                        float s = Math.min(w / canvas.docW, h / canvas.docH);
                        c.translate((w - canvas.docW * s) / 2, (h - canvas.docH * s) / 2);
                        c.scale(s, s);
                        c.clipRect(0, 0, canvas.docW, canvas.docH);
                        canvas.drawInto(c);
                    }
                }
            };
            refContent.setOnTouchListener((v, e) -> { // tap the reference image to pick its grey
                if (refMode != 1 || refImage == null || e.getActionMasked() != android.view.MotionEvent.ACTION_UP) return true;
                float s = Math.min((float) v.getWidth() / refImage.getWidth(), (float) v.getHeight() / refImage.getHeight());
                int px = (int) ((e.getX() - (v.getWidth() - refImage.getWidth() * s) / 2) / s);
                int py = (int) ((e.getY() - (v.getHeight() - refImage.getHeight() * s) / 2) / s);
                if (px >= 0 && py >= 0 && px < refImage.getWidth() && py < refImage.getHeight()) {
                    canvas.grey = Video.grey(refImage.getPixel(px, py));
                    updateColor();
                }
                return true;
            });
            refView.addView(refContent, new LinearLayout.LayoutParams(-1, 0, 1f));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (280 * dp), (int) (300 * dp), Gravity.TOP | Gravity.START);
            lp.setMargins((int) (60 * dp), (int) (60 * dp), 0, 0);
            stage.addView(refView, lp);
            return;
        }
        if (refView.getVisibility() == View.VISIBLE) { refView.setVisibility(View.GONE); return; }
        refView.setVisibility(View.VISIBLE);
        refContent.invalidate();
    }

    private View.OnTouchListener dragMove(View target) {
        float[] st = new float[4];
        return (v, e) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) target.getLayoutParams();
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { st[0] = e.getRawX(); st[1] = e.getRawY(); st[2] = lp.leftMargin; st[3] = lp.topMargin; }
            else if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                lp.leftMargin = Math.max(0, (int) (st[2] + e.getRawX() - st[0]));
                lp.topMargin = Math.max(0, (int) (st[3] + e.getRawY() - st[1]));
                target.setLayoutParams(lp);
            }
            return true;
        };
    }

    private View.OnTouchListener dragResize(View target) {
        float[] st = new float[4];
        return (v, e) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) target.getLayoutParams();
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { st[0] = e.getRawX(); st[1] = e.getRawY(); st[2] = target.getWidth(); st[3] = target.getHeight(); }
            else if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                lp.width = Math.max((int) (160 * dp), (int) (st[2] + e.getRawX() - st[0]));
                lp.height = Math.max((int) (140 * dp), (int) (st[3] + e.getRawY() - st[1]));
                target.setLayoutParams(lp);
            }
            return true;
        };
    }

    private void loadReferenceImage(Uri uri) {
        new Thread(() -> {
            try {
                Psd.Doc d = decode(uri);
                Bitmap b = Bitmap.createBitmap(d.w, d.h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                c.drawColor(Color.WHITE);
                for (CanvasView.Layer l : d.layers) if (l.visible) c.drawBitmap(l.bmp, 0, 0, null);
                runOnUiThread(() -> {
                    refImage = b;
                    refMode = 1;
                    if (refView != null) { refView.setVisibility(View.GONE); toggleReference(); }
                });
            } catch (Throwable t) { runOnUiThread(() -> toast("Could not open: " + t.getMessage())); }
        }).start();
    }

    // ---------- Animation Assist ----------

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable playTick = new Runnable() {
        public void run() {
            if (!canvas.playing) return;
            int f0 = canvas.firstFrame(), n = canvas.layers.size() - f0;
            if (n <= 0) return;
            canvas.playFrame = f0 + ((canvas.playFrame - f0 + 1) % n);
            canvas.invalidate();
            handler.postDelayed(this, 1000 / Math.max(1, canvas.fps));
        }
    };

    private boolean playing() { return canvas.playing; }

    private void togglePlay() {
        canvas.playing = !canvas.playing;
        if (canvas.playing) { canvas.playFrame = canvas.current; handler.post(playTick); }
        canvas.invalidate();
        refreshTimeline();
    }

    private void toggleAnimation() {
        canvas.anim = !canvas.anim;
        if (!canvas.anim && canvas.playing) togglePlay();
        timeline.setVisibility(canvas.anim ? View.VISIBLE : View.GONE);
        if (canvas.anim) { refreshTimeline(); toast("Animation Assist: each layer is a frame"); }
        canvas.invalidate();
    }

    private void refreshTimeline() {
        if (timeline == null || !canvas.anim) return;
        timeline.removeAllViews();
        LinearLayout ctl = barRow();
        chip(ctl, canvas.playing ? "■ Stop" : "▶ Play", canvas.playing, v -> togglePlay());
        chip(ctl, "＋ Frame", false, v -> { if (!canvas.addLayer(canvas.newLayer("Frame " + (canvas.layers.size() + 1)))) full(); });
        chip(ctl, "Settings", false, v -> animSettings());
        chip(ctl, "Export", false, v -> {
            Ui.PopMenu m = new Ui.PopMenu(this, v);
            m.getMenu().add(0, REQ_GIF, 0, "Animated GIF");
            m.getMenu().add(0, REQ_AMP4, 1, "MP4 video");
            m.setOnMenuItemClickListener(it -> {
                boolean gif = it.getItemId() == REQ_GIF;
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(gif ? "image/gif" : "video/mp4")
                        .putExtra(Intent.EXTRA_TITLE, nameOf(curId) + (gif ? ".gif" : ".mp4")), it.getItemId());
                return true;
            });
            m.show();
        });
        TextView info = new TextView(this);
        info.setText("  " + canvas.fps + " fps · onion " + canvas.onion);
        info.setTextColor(0xFF8E8E93);
        ctl.addView(info);
        HorizontalScrollView h1 = new HorizontalScrollView(this);
        h1.addView(ctl);
        timeline.addView(h1);
        LinearLayout frames = barRow();
        int tw = (int) (44 * dp), th = Math.max(1, tw * canvas.docH / Math.max(1, canvas.docW)); // same size as the layers panel
        int showH = Math.min(th, (int) (64 * dp));
        int f0 = canvas.firstFrame();
        for (int i = f0; i < canvas.layers.size(); i++) {
            int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding((int) (3 * dp), (int) (3 * dp), (int) (3 * dp), (int) (3 * dp));
            cell.setBackgroundColor(idx == canvas.current ? Ui.ACCENT : 0);
            ImageView iv = new ImageView(this);
            iv.setBackgroundColor(Color.WHITE);
            iv.setImageBitmap(thumbOf(canvas.layers.get(i), tw, th));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(iv, new LinearLayout.LayoutParams(tw, showH));
            TextView n = new TextView(this);
            n.setText(String.valueOf(i - f0 + 1));
            n.setTextColor(Color.WHITE);
            n.setTextSize(11);
            cell.addView(n);
            cell.setOnClickListener(v -> canvas.select(idx));
            frames.addView(cell);
        }
        HorizontalScrollView h2 = new HorizontalScrollView(this);
        h2.addView(frames);
        timeline.addView(h2);
    }

    private void animSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * dp);
        box.setPadding(p, p, p, 0);
        TextView fl = new TextView(this);
        box.addView(fl);
        SeekBar fps = new SeekBar(this);
        fps.setMax(29);
        fps.setProgress(canvas.fps - 1);
        box.addView(fps);
        TextView ol = new TextView(this);
        box.addView(ol);
        SeekBar on = new SeekBar(this);
        on.setMax(5);
        on.setProgress(canvas.onion);
        box.addView(on);
        TextView oo = new TextView(this);
        box.addView(oo);
        SeekBar op = new SeekBar(this);
        op.setMax(100);
        op.setProgress(Math.round(canvas.onionOpacity * 100));
        box.addView(op);
        android.widget.CheckBox bgc = new android.widget.CheckBox(this);
        bgc.setText("First layer is a background (shown on every frame)");
        bgc.setChecked(canvas.animBackground);
        box.addView(bgc);
        Runnable upd = () -> {
            fl.setText("Frames per second: " + (fps.getProgress() + 1));
            ol.setText("Onion skin frames: " + on.getProgress());
            oo.setText("Onion skin opacity: " + op.getProgress() + "%");
        };
        Slider sl = new Slider() { public void onProgressChanged(SeekBar s, int v, boolean u) { upd.run(); } };
        fps.setOnSeekBarChangeListener(sl); on.setOnSeekBarChangeListener(sl); op.setOnSeekBarChangeListener(sl);
        upd.run();
        new AlertDialog.Builder(this).setTitle("Animation settings").setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    canvas.fps = fps.getProgress() + 1;
                    canvas.onion = on.getProgress();
                    canvas.onionOpacity = op.getProgress() / 100f;
                    canvas.animBackground = bgc.isChecked();
                    canvas.invalidate();
                    refreshTimeline();
                }).show();
    }

    private void exportAnimation(Uri uri, boolean gif) {
        int f0 = canvas.firstFrame(), n = canvas.layers.size() - f0;
        if (n <= 0) return;
        int w, h;
        if (gif) {
            float s = Math.min(1f, 800f / Math.max(canvas.docW, canvas.docH));
            w = Math.max(1, Math.round(canvas.docW * s)); h = Math.max(1, Math.round(canvas.docH * s));
        } else { int[] vs = Video.videoSize(canvas.docW, canvas.docH); w = vs[0]; h = vs[1]; }
        int fps = canvas.fps;
        // render frames now (UI thread owns the layers), encode in the background
        Bitmap[] fr = new Bitmap[n];
        for (int i = 0; i < n; i++) fr[i] = canvas.renderFrame(f0 + i, w, h);
        toast("Exporting animation…");
        new Thread(() -> {
            String msg = "Animation saved";
            Video.Frames frames = new Video.Frames() {
                public int count() { return gif ? n : Math.max(n, n * Math.max(1, 2 * fps / n)); } // MP4: loop to ~2 s minimum
                public Bitmap get(int i) { return fr[i % n].copy(Bitmap.Config.ARGB_8888, false); }
            };
            try {
                if (gif) try (OutputStream o = getContentResolver().openOutputStream(uri, "wt")) { Video.gif(o, w, h, fps, frames); }
                else try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "rw")) { Video.mp4(fd.getFileDescriptor(), w, h, fps, frames); }
            } catch (Throwable t) { msg = "Export failed: " + t.getMessage(); }
            for (Bitmap b : fr) b.recycle();
            String fm = msg;
            runOnUiThread(() -> toast(fm));
        }).start();
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                int dot = n.lastIndexOf('.');
                return dot > 0 ? n.substring(0, dot) : n;
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ---------- Open / import ----------

    private void openMenu(View anchor) {
        Ui.PopMenu m = new Ui.PopMenu(this, anchor);
        m.getMenu().add(0, REQ_OPEN, 0, "Open file (replaces drawing)");
        m.getMenu().add(0, REQ_IMPORT, 1, "Import as new layer");
        m.setOnMenuItemClickListener(it -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(i, it.getItemId());
            return true;
        });
        m.show();
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) uri = i.getData();
        else if (Intent.ACTION_SEND.equals(i.getAction())) uri = (Uri) i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return;
        setIntent(new Intent());
        Uri u = uri;
        new AlertDialog.Builder(this).setMessage("Open this file?")
                .setPositiveButton("Replace drawing", (d, w) -> load(u, false))
                .setNeutralButton("Add as layer", (d, w) -> load(u, true))
                .setNegativeButton("Cancel", null).show();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (loaded) handleIntent(i); else setIntent(i);
    }

    private File brushDir() {
        File d = new File(getFilesDir(), "brushes");
        d.mkdirs();
        return d;
    }

    private void loadBrushes() {
        File[] fs = brushDir().listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            try {
                CanvasView.Brush b = Brushes.load(f);
                CanvasView.Brush same = null;
                if (b.builtin) for (CanvasView.Brush x : CanvasView.BRUSHES) if (x.builtin && x.name.equals(b.name)) same = x;
                if (same != null) { same.setValues(b.values()); same.set = b.set; same.file = f; } // edited built-in
                else CanvasView.BRUSHES.add(b);
            } catch (Exception ignored) { }
        }
    }

    private void importBrushes(Uri uri) {
        toast("Importing brushes…");
        new Thread(() -> {
            try {
                byte[] data;
                try (InputStream in = getContentResolver().openInputStream(uri)) { data = Brushes.readAll(in); }
                java.util.List<CanvasView.Brush> bs = Brushes.read(data);
                long t = System.currentTimeMillis();
                for (int i = 0; i < bs.size(); i++)
                    Brushes.save(bs.get(i), new File(brushDir(), t + "_" + String.format("%04d", i) + ".fbr"));
                runOnUiThread(() -> {
                    CanvasView.BRUSHES.addAll(bs);
                    canvas.brush = bs.get(0);
                    updateBrush();
                    toast(bs.size() + " brush(es) imported");
                });
            } catch (Throwable e) {
                runOnUiThread(() -> toast("Could not import brushes: " + e.getMessage()));
            }
        }).start();
    }

    /** True if the file is a brush file (Procreate zip or Photoshop .abr). */
    private boolean isBrushFile(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            byte[] m = new byte[4];
            if (in.read(m) < 4) return false;
            return (m[0] == 'P' && m[1] == 'K') || Brushes.looksLikeAbr(m);
        } catch (Exception e) { return false; }
    }

    private void load(Uri uri, boolean asLayer) {
        if (isBrushFile(uri)) { importBrushes(uri); return; }
        toast("Opening…");
        new Thread(() -> {
            try {
                Psd.Doc d = decode(uri);
                runOnUiThread(() -> {
                    if (asLayer) {
                        for (CanvasView.Layer l : d.layers)
                            if (!canvas.importLayer(l.bmp, l.name)) { full(); break; }
                    } else {
                        saveCurrent(null);
                        curId = newId();
                        prefs.edit().putString("cur", curId).apply();
                        String fname = displayName(uri);
                        if (fname != null) setName(curId, fname);
                        if (d.layers.size() == 1 && d.groups.isEmpty()) // flat image: add a blank layer to draw on
                            d.layers.add(new CanvasView.Layer(Bitmap.createBitmap(d.w, d.h, Bitmap.Config.ARGB_8888), "Layer 2"));
                        canvas.setDocument(d.w, d.h, d.layers);
                        canvas.setGroups(d.groups);
                        savedSel.clear();
                        saveCurrent(this::fillGallery);
                        if (gallery != null) gallery.setVisibility(View.GONE);
                    }
                    toast(d.layers.size() + " layer(s) loaded");
                });
            } catch (Throwable t) {
                runOnUiThread(() -> toast("Could not open: " + t.getMessage()));
            }
        }).start();
    }

    /** Reads PSD, PDF (page 1) or any image Android can decode (PNG, JPEG, WebP, HEIC, AVIF, GIF, BMP). */
    private Psd.Doc decode(Uri uri) throws Exception {
        byte[] magic = new byte[4];
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            int n = in.read(magic);
            if (n < 4) throw new Exception("File is empty");
        }
        String sig = new String(magic, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (sig.equals("8BPS")) {
            try (InputStream in = getContentResolver().openInputStream(uri)) { return Psd.read(in, MAX_PIXELS); }
        }
        Bitmap img;
        String name;
        if (sig.equals("%PDF")) {
            name = "PDF page 1";
            try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
                 PdfRenderer r = new PdfRenderer(fd);
                 PdfRenderer.Page p = r.openPage(0)) {
                double s = Math.min(4.0, Math.sqrt((double) MAX_PIXELS / ((double) p.getWidth() * p.getHeight())));
                img = Bitmap.createBitmap((int) (p.getWidth() * s), (int) (p.getHeight() * s), Bitmap.Config.ARGB_8888);
                img.eraseColor(Color.WHITE);
                p.render(img, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            }
        } else if (sig.startsWith("PK")) {
            throw new Exception("Procreate artwork files can't be read directly. In Procreate use Share → PSD, then open that.");
        } else {
            name = "Image";
            img = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri), (dec, info, src) -> {
                dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                double s = Math.min(1.0, Math.sqrt((double) MAX_PIXELS / ((double) w * h)));
                if (s < 1) dec.setTargetSize((int) (w * s), (int) (h * s));
            });
        }
        // Convert to grey
        Psd.Doc d = new Psd.Doc();
        d.w = img.getWidth(); d.h = img.getHeight();
        Bitmap g = Bitmap.createBitmap(d.w, d.h, Bitmap.Config.ARGB_8888);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(g).drawBitmap(img, 0, 0, p);
        img.recycle();
        d.layers.add(new CanvasView.Layer(g, name));
        return d;
    }

    // ---------- Export ----------

    private void exportMenu(View anchor) {
        Ui.PopMenu m = new Ui.PopMenu(this, anchor);
        for (int i = 0; i < FORMATS.length; i++) m.getMenu().add(0, i, i, FORMATS[i]);
        m.setOnMenuItemClickListener(it -> {
            exportFormat = it.getItemId();
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(MIMES[exportFormat])
                    .putExtra(Intent.EXTRA_TITLE, "inkwell_" + System.currentTimeMillis() / 1000 + "." + EXTS[exportFormat]);
            startActivityForResult(i, REQ_EXPORT);
            return true;
        });
        m.show();
    }

    private void export(Uri uri, int fmt) {
        Bitmap flat = canvas.flatten();
        java.util.List<CanvasView.Layer> layers = new java.util.ArrayList<>(canvas.layers);
        java.util.Map<Integer, CanvasView.Group> groups = new java.util.HashMap<>(canvas.groups);
        int w = canvas.docW, h = canvas.docH;
        toast("Saving…");
        new Thread(() -> {
            String msg = "Saved";
            try (OutputStream o = getContentResolver().openOutputStream(uri, "wt")) {
                switch (fmt) {
                    case 0: flat.compress(Bitmap.CompressFormat.PNG, 100, o); break;
                    case 1: flat.compress(Bitmap.CompressFormat.JPEG, 95, o); break;
                    case 2: flat.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, o); break;
                    case 3: {
                        PdfDocument pdf = new PdfDocument();
                        // page size as if printed at 300 dpi (points are 1/72 inch); the image keeps full resolution
                        float k = 72f / 300f;
                        PdfDocument.Page pg = pdf.startPage(new PdfDocument.PageInfo.Builder(
                                Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), 1).create());
                        pg.getCanvas().scale(k, k);
                        pg.getCanvas().drawBitmap(flat, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
                        pdf.finishPage(pg);
                        pdf.writeTo(o);
                        pdf.close();
                        break;
                    }
                    default: Psd.write(o, w, h, layers, groups, flat);
                }
            } catch (Throwable t) { msg = "Save failed: " + t.getMessage(); }
            flat.recycle();
            String fm = msg;
            runOnUiThread(() -> toast(fm));
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_EXPORT) export(data.getData(), exportFormat);
        else if (req == REQ_OPEN) load(data.getData(), false);
        else if (req == REQ_IMPORT) load(data.getData(), true);
        else if (req == REQ_BRUSH) importBrushes(data.getData());
        else if (req == REQ_GALLERY) load(data.getData(), false);
        else if (req == REQ_TL) exportTimelapse(data.getData());
        else if (req == REQ_GIF || req == REQ_AMP4) exportAnimation(data.getData(), req == REQ_GIF);
        else if (req == REQ_REF) loadReferenceImage(data.getData());
    }

    // ---------- Autosave + Gallery ----------

    @Override
    protected void onPause() {
        super.onPause();
        storeTool(canvas.tool);
        if (loaded) saveCurrent(null);
    }

    @Override
    public void onBackPressed() {
        if (studio != null) { closeStudio(); return; }
        if (gallery != null && gallery.getVisibility() == View.VISIBLE) {
            if (docFile(curId).exists()) gallery.setVisibility(View.GONE); else super.onBackPressed();
        } else showGallery();
    }

    private File docFile(String id) { return new File(gdir, id + ".psd"); }
    private File thumbFile(String id) { return new File(gdir, id + ".png"); }
    private File nameFile(String id) { return new File(gdir, id + ".name"); }
    private File selFile(String id) { return new File(gdir, id + ".sel"); }
    private File tlDir(String id) { return new File(gdir, id + "_tl"); }
    private String newId() { return Long.toString(System.currentTimeMillis(), 36); }

    private String nameOf(String id) {
        try { return new String(java.nio.file.Files.readAllBytes(nameFile(id).toPath()), "UTF-8"); }
        catch (Exception e) { return "Untitled"; }
    }

    private void setName(String id, String n) {
        try (FileOutputStream o = new FileOutputStream(nameFile(id))) { o.write(n.getBytes("UTF-8")); } catch (Exception ignored) { }
    }

    /** Saves the open drawing (layers as PSD + thumbnail) on a background thread, then runs done on the UI thread. */
    private void saveCurrent(Runnable done) {
        if (adjActiveFinish()) { /* applied */ }
        canvas.commitMove();
        Bitmap flat = canvas.flatten();
        Bitmap thumb = canvas.renderSmall(420);
        java.util.List<CanvasView.Layer> layers = new java.util.ArrayList<>(canvas.layers);
        java.util.Map<Integer, CanvasView.Group> groups = new java.util.HashMap<>(canvas.groups);
        int w = canvas.docW, h = canvas.docH;
        String id = curId;
        new Thread(() -> {
            synchronized (MainActivity.class) {
                File doc = docFile(id), tmp = new File(doc.getPath() + ".tmp");
                try (OutputStream o = new FileOutputStream(tmp)) {
                    Psd.write(o, w, h, layers, groups, flat);
                    tmp.renameTo(doc);
                } catch (Throwable ignored) { }
                try (OutputStream o = new FileOutputStream(thumbFile(id))) { thumb.compress(Bitmap.CompressFormat.PNG, 100, o); }
                catch (Throwable ignored) { }
            }
            flat.recycle();
            thumb.recycle();
            if (done != null) runOnUiThread(done);
        }).start();
    }

    private boolean adjActiveFinish() {
        if (canvas.adjusting()) { finishAdjust(true); return true; }
        if (canvas.liquifying()) { canvas.endLiquify(true); return true; }
        return false;
    }

    private FrameLayout gallery;
    private LinearLayout galleryGrid;

    private void showGallery() {
        if (gallery == null) {
            gallery = new FrameLayout(this);
            gallery.setBackgroundColor(0xFF111113);
            gallery.setClickable(true);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding((int) (12 * dp), 0, (int) (8 * dp), 0);
            TextView t = new TextView(this);
            t.setText("Gallery");
            t.setTextColor(Color.WHITE);
            t.setTextSize(22);
            head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            button(head, "Import", v -> openDoc(REQ_GALLERY));
            button(head, "＋ New", this::newMenu);
            col.addView(head, new LinearLayout.LayoutParams(-1, (int) (52 * dp)));
            ScrollView sv = new ScrollView(this);
            galleryGrid = new LinearLayout(this);
            galleryGrid.setOrientation(LinearLayout.VERTICAL);
            galleryGrid.setPadding((int) (8 * dp), 0, (int) (8 * dp), (int) (8 * dp));
            sv.addView(galleryGrid);
            col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
            gallery.addView(col);
            shell.addView(gallery);
        }
        gallery.setVisibility(View.VISIBLE);
        if (playing()) togglePlay();
        saveCurrent(this::fillGallery);
        fillGallery();
    }

    private void fillGallery() {
        if (galleryGrid == null) return;
        galleryGrid.removeAllViews();
        File[] docs = gdir.listFiles((d, n) -> n.endsWith(".psd"));
        if (docs == null) docs = new File[0];
        java.util.Arrays.sort(docs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int cols = Math.max(2, (int) (getResources().getDisplayMetrics().widthPixels / dp / 190));
        int cw = (int) ((getResources().getDisplayMetrics().widthPixels - 16 * dp) / cols);
        LinearLayout row = null;
        for (int i = 0; i < docs.length; i++) {
            if (i % cols == 0) { row = new LinearLayout(this); galleryGrid.addView(row); }
            String id = docs[i].getName().replace(".psd", "");
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding((int) (6 * dp), (int) (8 * dp), (int) (6 * dp), (int) (4 * dp));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(id.equals(curId) ? Ui.ACCENT : 0xFF2C2C2E);
            iv.setPadding(3, 3, 3, 3);
            Bitmap tb = android.graphics.BitmapFactory.decodeFile(thumbFile(id).getPath());
            if (tb != null) iv.setImageBitmap(tb);
            card.addView(iv, new LinearLayout.LayoutParams(-1, (int) (cw * 1.1f)));
            TextView nm = new TextView(this);
            nm.setText(nameOf(id));
            nm.setTextColor(Color.WHITE);
            nm.setSingleLine(true);
            nm.setPadding(0, (int) (4 * dp), 0, 0);
            card.addView(nm);
            TextView sz = new TextView(this);
            sz.setText(tb == null ? "" : new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(new java.util.Date(docs[i].lastModified())));
            sz.setTextColor(0xFF8E8E93);
            sz.setTextSize(12);
            card.addView(sz);
            card.setOnClickListener(v -> openGalleryItem(id));
            card.setOnLongClickListener(v -> { galleryItemMenu(v, id); return true; });
            row.addView(card, new LinearLayout.LayoutParams(cw, -2));
        }
        if (docs.length == 0) {
            TextView e = new TextView(this);
            e.setText("No drawings yet. Tap ＋ New.");
            e.setTextColor(0xFF8E8E93);
            e.setPadding((int) (8 * dp), (int) (24 * dp), 0, 0);
            galleryGrid.addView(e);
        }
    }

    private void openGalleryItem(String id) {
        if (id.equals(curId)) { gallery.setVisibility(View.GONE); return; }
        toast("Opening…");
        new Thread(() -> {
            Psd.Doc d = null;
            synchronized (MainActivity.class) {
                try (InputStream in = new FileInputStream(docFile(id))) { d = Psd.read(in, MAX_PIXELS); }
                catch (Throwable ignored) { }
            }
            Psd.Doc fd = d;
            runOnUiThread(() -> {
                if (fd == null) { toast("Could not open this drawing"); return; }
                curId = id;
                prefs.edit().putString("cur", id).apply();
                canvas.setDocument(fd.w, fd.h, fd.layers);
                canvas.setGroups(fd.groups);
                loadSelections();
                gallery.setVisibility(View.GONE);
                if (canvas.anim) refreshTimeline();
            });
        }).start();
    }

    /** Starts a new gallery drawing of the given size. */
    private void newGalleryItem(int w, int h) {
        curId = newId();
        prefs.edit().putString("cur", curId).apply();
        newDocument(w, h);
        savedSel.clear();
        saveCurrent(null);
        if (gallery != null) gallery.setVisibility(View.GONE);
    }

    private void newMenu(View anchor) {
        Rect scr = screenBounds();
        int sw = Math.min(scr.width(), scr.height()), sh = Math.max(scr.width(), scr.height());
        int[][] sizes = {{sw, sh}, {sh, sw}, {2048, 2048}, {2048, 2732}, {2480, 3508}, {2550, 3300}, {1988, 3075}, {1920, 1080}, {3840, 2160}, {1080, 1350}};
        String[] names = {"Screen size", "Screen size (landscape)", "Square", "iPad Procreate default", "A4 @ 300 dpi", "US Letter @ 300 dpi",
                "Comic page @ 300 dpi", "HD 1920×1080", "4K", "Instagram portrait"};
        Ui.PopMenu m = new Ui.PopMenu(this, anchor);
        for (int i = 0; i < names.length; i++) m.getMenu().add(0, i, i, names[i] + "   " + sizes[i][0] + "×" + sizes[i][1]);
        m.getMenu().add(0, 99, 99, "Custom size…");
        m.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            saveCurrent(null);
            if (id == 99) { newGalleryItem(sw, sh); canvasDialog(); }
            else newGalleryItem(sizes[id][0], sizes[id][1]);
            return true;
        });
        m.show();
    }

    private void galleryItemMenu(View anchor, String id) {
        Ui.PopMenu m = new Ui.PopMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Rename");
        m.getMenu().add(0, 2, 1, "Duplicate");
        m.getMenu().add(0, 3, 2, "Delete");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: {
                    android.widget.EditText et = new android.widget.EditText(this);
                    et.setText(nameOf(id));
                    et.setSelectAllOnFocus(true);
                    new AlertDialog.Builder(this).setTitle("Rename").setView(et)
                            .setPositiveButton("OK", (d, w) -> { setName(id, et.getText().toString()); fillGallery(); }).show();
                    break;
                }
                case 2: {
                    String nid = newId();
                    try {
                        java.nio.file.Files.copy(docFile(id).toPath(), docFile(nid).toPath());
                        if (thumbFile(id).exists()) java.nio.file.Files.copy(thumbFile(id).toPath(), thumbFile(nid).toPath());
                        setName(nid, nameOf(id) + " copy");
                    } catch (Exception e) { toast("Could not duplicate"); }
                    fillGallery();
                    break;
                }
                case 3:
                    confirm("Delete \"" + nameOf(id) + "\"? This can't be undone.", () -> {
                        for (File f : new File[]{docFile(id), thumbFile(id), nameFile(id), selFile(id)}) f.delete();
                        File[] tl = tlDir(id).listFiles();
                        if (tl != null) for (File f : tl) f.delete();
                        tlDir(id).delete();
                        if (id.equals(curId)) { // keep a valid open drawing
                            Rect scr = screenBounds();
                            curId = newId();
                            prefs.edit().putString("cur", curId).apply();
                            newDocument(Math.min(scr.width(), scr.height()), Math.max(scr.width(), scr.height()));
                            savedSel.clear();
                        }
                        fillGallery();
                    });
                    break;
            }
            return true;
        });
        m.show();
    }

    // ---------- Time-lapse ----------

    private long tlLast;

    private void captureTimelapse() {
        if (!tlOn || tlBusy || !loaded || curId == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - tlLast < 250) return; // keep drawing smooth during rapid strokes
        tlLast = now;
        tlBusy = true;
        Bitmap b = canvas.renderSmall(1080);
        File dir = tlDir(curId);
        new Thread(() -> {
            dir.mkdirs();
            String[] n = dir.list();
            File f = new File(dir, String.format(java.util.Locale.US, "%07d.jpg", n == null ? 0 : n.length));
            try (OutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, 80, o); }
            catch (Throwable ignored) { }
            b.recycle();
            tlBusy = false;
        }).start();
    }

    private File[] tlFrames() {
        File[] fs = tlDir(curId).listFiles((d, n) -> n.endsWith(".jpg"));
        if (fs == null) return new File[0];
        java.util.Arrays.sort(fs);
        return fs;
    }

    private void timelapseMenu(View anchor) {
        Ui.PopMenu m = new Ui.PopMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Time-lapse replay");
        m.getMenu().add(0, 2, 1, "Export time-lapse: full length (MP4)");
        m.getMenu().add(0, 3, 2, "Export time-lapse: 30 seconds (MP4)");
        m.getMenu().add(0, 4, 3, (tlOn ? "✓ " : "") + "Record time-lapse");
        m.getMenu().add(0, 5, 4, "Delete this drawing's time-lapse");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: replayTimelapse(); break;
                case 2: case 3:
                    tlShort = it.getItemId() == 3;
                    if (tlFrames().length == 0) { toast("Nothing recorded yet"); break; }
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("video/mp4").putExtra(Intent.EXTRA_TITLE, nameOf(curId) + " time-lapse.mp4"), REQ_TL);
                    break;
                case 4: tlOn = !tlOn; prefs.edit().putBoolean("timelapse", tlOn).apply(); toast(tlOn ? "Recording time-lapse" : "Time-lapse off"); break;
                case 5: confirm("Delete the recorded time-lapse?", () -> { for (File f : tlFrames()) f.delete(); }); break;
            }
            return true;
        });
        m.show();
    }

    private boolean tlShort;

    private void exportTimelapse(Uri uri) {
        File[] all = tlFrames();
        int n = tlShort ? Math.min(all.length, 900) : all.length;
        File[] pick = new File[n];
        for (int i = 0; i < n; i++) pick[i] = all[(int) ((long) i * all.length / n)];
        android.graphics.BitmapFactory.Options op = new android.graphics.BitmapFactory.Options();
        op.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(all[all.length - 1].getPath(), op);
        int[] vs = Video.videoSize(op.outWidth, op.outHeight);
        Bitmap last = canvas.renderSmall(1280);
        toast("Making video…");
        new Thread(() -> {
            String msg = "Time-lapse saved";
            try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "rw")) {
                Video.mp4(fd.getFileDescriptor(), vs[0], vs[1], 30, new Video.Frames() {
                    public int count() { return n + 60; } // hold the finished picture for 2 s
                    public Bitmap get(int i) {
                        if (i >= n) return last.copy(Bitmap.Config.ARGB_8888, false);
                        return android.graphics.BitmapFactory.decodeFile(pick[i].getPath());
                    }
                });
            } catch (Throwable t) { msg = "Video failed: " + t.getMessage(); }
            last.recycle();
            String fm = msg;
            runOnUiThread(() -> toast(fm));
        }).start();
    }

    private void replayTimelapse() {
        File[] fs = tlFrames();
        if (fs.length == 0) { toast("Nothing recorded yet"); return; }
        ImageView iv = new ImageView(this);
        iv.setBackgroundColor(Color.WHITE);
        iv.setAdjustViewBounds(true);
        AlertDialog d = new AlertDialog.Builder(this).setView(iv).setPositiveButton("Close", null).show();
        boolean[] stop = {false};
        d.setOnDismissListener(x -> stop[0] = true);
        new Thread(() -> {
            int step = Math.max(1, fs.length / 600); // replay in ~20 s max
            for (int i = 0; i < fs.length && !stop[0]; i += step) {
                Bitmap b = android.graphics.BitmapFactory.decodeFile(fs[i].getPath());
                runOnUiThread(() -> iv.setImageBitmap(b));
                try { Thread.sleep(33); } catch (InterruptedException e) { return; }
            }
        }).start();
    }

    // ---------- Saved selections ----------

    private final java.util.ArrayList<byte[]> savedSel = new java.util.ArrayList<>();

    private void saveSelection() {
        if (canvas.sel == null) { toast("Nothing is selected"); return; }
        savedSel.add(CanvasView.deflate(canvas.sel));
        writeSelections();
        toast("Selection " + savedSel.size() + " saved");
    }

    private void loadSelectionMenu(View anchor) {
        if (savedSel.isEmpty()) { toast("No saved selections"); return; }
        Ui.PopMenu m = new Ui.PopMenu(this, anchor);
        for (int i = 0; i < savedSel.size(); i++) m.getMenu().add(0, i, i, "Load selection " + (i + 1));
        m.getMenu().add(0, 999, 999, "Delete all saved selections");
        m.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 999) { savedSel.clear(); writeSelections(); return true; }
            byte[] b = CanvasView.inflate(savedSel.get(it.getItemId()), canvas.docW * canvas.docH);
            if (b != null) canvas.setSelection(b); else toast("Saved selection doesn't fit this canvas size");
            return true;
        });
        m.show();
    }

    private void writeSelections() {
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(new FileOutputStream(selFile(curId)))) {
            o.writeInt(savedSel.size());
            for (byte[] b : savedSel) { o.writeInt(b.length); o.write(b); }
        } catch (Exception ignored) { }
    }

    private void loadSelections() {
        savedSel.clear();
        try (java.io.DataInputStream in = new java.io.DataInputStream(new FileInputStream(selFile(curId)))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) { byte[] b = new byte[in.readInt()]; in.readFully(b); savedSel.add(b); }
        } catch (Exception ignored) { }
    }
}
