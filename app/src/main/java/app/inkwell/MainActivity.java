package app.inkwell;

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
    private static final int REQ_OPEN = 1, REQ_IMPORT = 2, REQ_BRUSH = 3, REQ_REF = 4, REQ_TL = 5, REQ_GIF = 6, REQ_AMP4 = 7, REQ_GALLERY = 8, REQ_CAMERA = 9, REQ_EXPORT = 10, REQ_BRUSH_EXPORT = 11, REQ_FONT = 12, REQ_TREE = 13, REQ_PDF_MANY = 14, REQ_PAGES_PDF = 15, REQ_APNG = 16, REQ_LAYERS_TREE = 17, REQ_BACKUP = 18, REQ_RESTORE = 19, REQ_SHAPE = 20, REQ_GRAIN = 21;
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
        if (prefs != null) prefs.edit().putString("tb" + k, tBrush[k]).putFloat("ts" + k, tSize[k]).putFloat("to" + k, tOp[k])
                .putString("bsz" + k + ":" + tBrush[k], canvas.size + "," + canvas.opacity).apply(); // this brush's own size
    }

    /** Switches brush for the current tool; each brush keeps its own size and opacity (per tool), like Procreate. */
    void switchBrush(CanvasView.Brush b) {
        if (b == null) return;
        int k = slot(canvas.tool);
        if (k >= 0) storeTool(canvas.tool); // remember the old brush's size
        canvas.brush = b;
        if (k >= 0) {
            String v = prefs.getString("bsz" + k + ":" + b.name, null);
            if (v != null) {
                try {
                    String[] f = v.split(",");
                    canvas.size = Float.parseFloat(f[0]);
                    canvas.opacity = Float.parseFloat(f[1]);
                } catch (Exception ignored) { }
            }
            storeTool(canvas.tool);
        }
        if (sizeS != null) { sizeS.value = (float) Math.sqrt(Math.max(0, canvas.size - 1) / 149f); sizeS.invalidate(); }
        if (opS != null) { opS.value = canvas.opacity; opS.invalidate(); }
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
        prefs = getSharedPreferences("inkwell", MODE_PRIVATE);
        int uiSize = prefs.getInt("uiSize", 1);                  // 0 small, 1 medium, 2 large
        int ib = (int) ((uiSize == 0 ? 38 : uiSize == 2 ? 52 : 44) * dp);
        LinearLayout left = new LinearLayout(this), right = new LinearLayout(this);
        left.setGravity(Gravity.CENTER_VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        TextView gal = button(left, "Gallery", v -> showGallery());
        gal.setTextSize(15);
        icon(left, Ui.MENU, ib, this::actionsMenu);
        adjustBtn = icon(left, Ui.ADJUST, ib, this::adjustMenu);
        selectBtn = icon(left, Ui.SELECT, ib, v -> {
            if (canvas.tool == CanvasView.SELECT) setTool(CanvasView.DRAW);   // done selecting: back to drawing
            else if (canvas.sel != null) { canvas.clearSelection(); toast("Deselected"); } // like Procreate
            else setTool(CanvasView.SELECT);
        });
        moveBtn = icon(left, Ui.MOVE, ib, v -> setTool(canvas.tool == CanvasView.MOVE ? CanvasView.DRAW : CanvasView.MOVE));
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
        sizeS.listener = (v, done) -> { // finer control at small sizes
            canvas.size = 1 + 149 * v * v;
            hud("Size " + Math.round(v * 100) + "%");
            if (done) { canvas.hideSizePreview(); return; }
            int[] sl = new int[2], cl = new int[2];
            sizeS.getLocationOnScreen(sl); canvas.getLocationOnScreen(cl);
            boolean leftSide = sideLp.gravity == (Gravity.START | Gravity.CENTER_VERTICAL);
            float y = sl[1] - cl[1] + sizeS.getHeight() * (1 - v);
            float x = leftSide ? sl[0] - cl[0] + sizeS.getWidth() + 24 * dp : sl[0] - cl[0] - 24 * dp;
            canvas.showSizePreview(x, y, !leftSide);
        };
        side.addView(sizeS, new LinearLayout.LayoutParams((int) (40 * dp), (int) (150 * dp)));
        pickBtn = icon(side, Ui.PICK, (int) (40 * dp), v -> {
            if (prefs.getInt("squareAction", 0) == 1) { // Prefs: the sidebar button opens the QuickMenu
                int[] l = new int[2]; v.getLocationOnScreen(l);
                quickMenu(l[0] + v.getWidth() / 2f + 110 * dp * (sideLp.gravity == (Gravity.START | Gravity.CENTER_VERTICAL) ? 1 : -1), l[1] + v.getHeight() / 2f);
                return;
            }
            canvas.pickNext = !canvas.pickNext; pickBtn.setOn(canvas.pickNext);
        });
        opS = new Ui.VSlider(this, canvas.opacity);
        opS.listener = (v, done) -> { canvas.opacity = Math.max(0.01f, v); hud("Opacity " + Math.round(canvas.opacity * 100) + "%"); };
        side.addView(opS, new LinearLayout.LayoutParams((int) (40 * dp), (int) (150 * dp)));
        icon(side, Ui.UNDO, (int) (40 * dp), v -> canvas.undo());
        icon(side, Ui.REDO, (int) (40 * dp), v -> canvas.redo());
        canvas.onPicked = () -> pickBtn.setOn(false);
        canvas.onTouchStart = this::closePanels;
        canvas.onTransformChanged = () -> { if (canvas.tool == CanvasView.MOVE) showTransformBar(); }; // mode chip follows undo
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
        canvas.onInfo = this::hud;
        canvas.onHistory = this::hud;
        canvas.onViewChanged = (z, a) -> hud(Math.round(z) + "%   ·   " + Math.round(a) + "°");
        setTool(CanvasView.DRAW);
        updateColor();

        // ---- Layers panel ----
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable pbg = new android.graphics.drawable.GradientDrawable();
        pbg.setColor(Ui.PANEL);
        pbg.setCornerRadius(12 * dp);
        panel.setBackground(pbg);
        LinearLayout head = new LinearLayout(this); // title and "+" only: everything else is a gesture or in layer options
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView lt = new TextView(this);
        lt.setText("Layers");
        lt.setTextColor(Color.WHITE);
        lt.setTextSize(17);
        lt.setPadding((int) (6 * dp), 0, 0, 0);
        head.addView(lt, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView add = new TextView(this);
        add.setText("+");
        add.setTextColor(Color.WHITE);
        add.setTextSize(26);
        add.setPadding((int) (14 * dp), 0, (int) (10 * dp), 0);
        add.setContentDescription("New layer");
        add.setOnClickListener(v -> { if (!canvas.addLayer(canvas.newLayer("Layer " + (canvas.layers.size() + 1)))) full(); });
        head.addView(add);
        panel.addView(head);
        panel.setPadding((int) (6 * dp), (int) (6 * dp), (int) (6 * dp), (int) (6 * dp));
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
        layerList = new LinearLayout(this) {
            @Override public boolean onInterceptTouchEvent(android.view.MotionEvent e) { return pinchMerge(e) || super.onInterceptTouchEvent(e); }
            @Override public boolean onTouchEvent(android.view.MotionEvent e) { pinchMerge(e); return true; }
        };
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
        tlOn = prefs.getBoolean("timelapse", true);
        canvas.fingerMode = prefs.getInt("fingerMode", prefs.getBoolean("fingerPaint", false) ? 1 : 2);
        canvas.holdAction = prefs.getInt("holdAction", 0);
        canvas.selVisibility = prefs.getFloat("selVis", 1f);
        canvas.holdDelay = prefs.getInt("holdDelay", 450);
        canvas.gUndo = prefs.getBoolean("gUndo", true); canvas.gRedo = prefs.getBoolean("gRedo", true);
        canvas.gClear = prefs.getBoolean("gClear", true); canvas.gCopy = prefs.getBoolean("gCopy", true);
        canvas.gFull = prefs.getBoolean("gFull", true);
        canvas.onQuickMenu = (x, y) -> { int[] l = new int[2]; canvas.getLocationOnScreen(l); quickMenu(l[0] + x, l[1] + y); };
        acceptDrops(canvas, true); // drag images in from another app (split screen): each becomes a layer
        canvas.onFingerErase = on -> { // finger erases with the Eraser's own brush, size and opacity
            if (on) { storeTool(canvas.tool); applyTool(CanvasView.ERASE); } else applyTool(canvas.tool);
        };
        canvas.showCursor = prefs.getBoolean("cursor", false);
        canvas.globalCurve = prefs.getFloat("gCurve", 1f);
        canvas.globalSmooth = prefs.getFloat("gSmooth", 0f);
        canvas.undoHoldDelay = prefs.getInt("undoDelay", 600);
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
                if (curId != null) loadBackground();
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
    private void canvasDialog() { canvasDialog(false); }

    /** Canvas size: forNew = make a new drawing in the Gallery (Create); otherwise resize this one (Resize, undoable). */
    private void canvasDialog(boolean forNew) {
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

        // units: pixels, inches or centimetres (inches/cm use the DPI to get pixels)
        int[] unit = {prefs.getInt("unit", 0)};
        android.widget.EditText dpiIn = number(prefs.getInt("dpi", 300));
        java.util.function.ToIntFunction<android.widget.EditText> px = e -> {
            double v;
            try { v = Double.parseDouble(e.getText().toString().replace(',', '.')); } catch (Exception ex) { return -1; }
            int dpi = parse(dpiIn);
            if (unit[0] == 0) return (int) Math.round(v);
            if (dpi <= 0) return -1;
            return (int) Math.round(unit[0] == 1 ? v * dpi : v / 2.54 * dpi);
        };
        java.util.function.BiConsumer<android.widget.EditText, Integer> showPx = (e, v) -> {
            int dpi = Math.max(1, parse(dpiIn));
            if (unit[0] == 0) { e.setText(String.valueOf(v)); return; }
            double u = unit[0] == 1 ? v / (double) dpi : v * 2.54 / dpi;
            e.setText(String.valueOf(Math.round(u * 100) / 100.0).replaceAll("\\.0$", ""));
        };
        Runnable setInput = () -> {
            int t = unit[0] == 0 ? android.text.InputType.TYPE_CLASS_NUMBER
                    : android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL;
            wIn.setInputType(t); hIn.setInputType(t);
        };
        LinearLayout ur = barRow();
        TextView[] uc = new TextView[3];
        String[] un = {"Pixels", "Inches", "Centimetres"};
        TextView dl = new TextView(this);
        dl.setText("   DPI ");
        for (int i = 0; i < 3; i++) {
            int k = i;
            uc[i] = chip(ur, un[i], unit[0] == i, v -> {
                int w = px.applyAsInt(wIn), h = px.applyAsInt(hIn);
                unit[0] = k;
                prefs.edit().putInt("unit", k).apply();
                for (int j = 0; j < 3; j++) uc[j].setBackgroundColor(j == k ? Ui.ACCENT : 0);
                setInput.run();
                if (w > 0) showPx.accept(wIn, w);
                if (h > 0) showPx.accept(hIn, h);
            });
        }
        ur.addView(dl);
        ur.addView(dpiIn, new LinearLayout.LayoutParams((int) (64 * dp), -2));
        box.addView(ur);
        setInput.run();
        showPx.accept(wIn, canvas.docW);
        showPx.accept(hIn, canvas.docH);

        TextView info = new TextView(this);
        info.setPadding(0, 0, 0, p);
        box.addView(info);
        Runnable upd = () -> {
            int w = px.applyAsInt(wIn), h = px.applyAsInt(hIn);
            long n = (long) w * h;
            info.setText(w <= 0 || h <= 0 ? "Enter a width and height"
                    : n > MAX_PIXELS ? w + " × " + h + " px is too big: max about 9 megapixels (" + n / 100000 / 10f + " MP)"
                    : String.format("%d × %d px · %.1f megapixels · about %d layers", w, h, n / 1e6, layerCap(w, h)));
        };
        android.text.TextWatcher tw = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) { upd.run(); }
        };
        wIn.addTextChangedListener(tw);
        hIn.addTextChangedListener(tw);
        dpiIn.addTextChangedListener(tw);
        upd.run();

        for (Object[] pr : presets) {
            TextView t = new TextView(this);
            t.setText(pr[0] + "   " + pr[1] + " × " + pr[2]);
            t.setPadding(p / 2, p * 2 / 3, p / 2, p * 2 / 3);
            t.setTextSize(15);
            t.setOnClickListener(v -> { showPx.accept(wIn, (Integer) pr[1]); showPx.accept(hIn, (Integer) pr[2]); });
            box.addView(t);
        }
        android.widget.CheckBox scale = new android.widget.CheckBox(this);
        scale.setText("Scale artwork to fit (off = crop/extend around centre)");
        scale.setChecked(true);
        if (!forNew) box.addView(scale);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(forNew ? "New canvas" : "Canvas size").setView(sv)
                .setPositiveButton(forNew ? "Create" : "Resize", null)
                .setNegativeButton("Cancel", null).show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int w = px.applyAsInt(wIn), h = px.applyAsInt(hIn);
            if (!valid(w, h)) return;
            prefs.edit().putInt("dpi", Math.max(1, parse(dpiIn))).apply();
            if (forNew) { d.dismiss(); saveCurrent(null); newGalleryItem(w, h); return; } // a new drawing in the Gallery, like Procreate
            if ((long) layerCap(w, h) < canvas.layers.size()) { toast("Too many layers for that size"); return; }
            d.dismiss();
            canvas.resizeDocument(w, h, scale.isChecked()); // undoable
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

    /** Closes modes that live in the bottom bar (guide editing, "Continue filling") so touches draw again. */
    private void endBarModes() {
        boolean was = canvas.editGuide || canvas.fillMode;
        canvas.editGuide = false;
        canvas.fillMode = false;
        if (was) { canvas.invalidate(); if (bottom != null) bottom.setVisibility(View.GONE); }
    }

    /** Crop & resize like Procreate: drag the box on the canvas; rotate; exact numbers under Settings. */
    private void startCrop() {
        setTool(CanvasView.DRAW);
        closePanels();
        canvas.startCrop();
        canvas.onCropChanged = this::showCropBar;
        showCropBar();
    }

    private void showCropBar() {
        if (!canvas.cropping) return;
        LinearLayout a = barRow(), b = barRow();
        TextView info = new TextView(this);
        info.setText(String.format("  %d × %d px  ", Math.round(canvas.crop.width()), Math.round(canvas.crop.height())));
        info.setTextColor(Color.WHITE);
        a.addView(info);
        String[] asp = {"Free", "Original", "Square", "4:3", "16:9"};
        float[] av = {0, (float) canvas.docW / canvas.docH, 1, 4 / 3f, 16 / 9f};
        for (int i = 0; i < asp.length; i++) {
            int k = i;
            chip(a, asp[i], Math.abs(canvas.cropAspect - av[i]) < 1e-3f, v -> {
                canvas.cropAspect = av[k];
                if (av[k] > 0) { // reshape around the centre, keeping the area roughly the same
                    float cx = canvas.crop.centerX(), cy = canvas.crop.centerY();
                    float area = canvas.crop.width() * canvas.crop.height();
                    float h = (float) Math.sqrt(area / av[k]), w = h * av[k];
                    canvas.crop.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
                }
                canvas.invalidate();
                showCropBar();
            });
        }
        b.addView(slider("Rotate °", Math.round(canvas.cropRot), -45, 45, v -> { canvas.cropRot = v; canvas.invalidate(); }));
        chip(b, "Reset", false, v -> { canvas.crop.set(0, 0, canvas.docW, canvas.docH); canvas.cropRot = 0; canvas.cropAspect = 0; canvas.invalidate(); showCropBar(); });
        chip(b, "Settings…", false, v -> { canvas.cancelCrop(); bottom.setVisibility(View.GONE); canvasDialog(); });
        chip(b, "Cancel", false, v -> { canvas.cancelCrop(); bottom.setVisibility(View.GONE); });
        chip(b, "Done", true, v -> {
            int w = Math.round(canvas.crop.width()), h = Math.round(canvas.crop.height());
            if (!valid(w, h)) return;
            if ((long) layerCap(w, h) < canvas.layers.size()) { toast("Too many layers for that size"); return; }
            canvas.applyCrop(); // undoable, like Procreate
            bottom.setVisibility(View.GONE);
        });
        showBar(a, b);
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
        canvas.guide = 0; canvas.editGuide = false; canvas.symRotate = false; // each new drawing starts without a guide
        canvas.guideOpacity = 0.35f; canvas.guideThick = 1.5f; canvas.vpRays = 36; canvas.gridSize = 100; canvas.horizonLevel = true;
        if (bottom != null && canvas.tool != CanvasView.SELECT && canvas.tool != CanvasView.MOVE) bottom.setVisibility(View.GONE);
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

    private static void highlight(TextView t, boolean on) {
        t.setBackgroundColor(on ? Color.WHITE : 0);
        t.setTextColor(on ? Color.BLACK : Color.WHITE);
    }

    private void setTool(int t) {
        if (canvas.cropping) canvas.cancelCrop(); // picking a tool leaves Crop without changing anything
        endBarModes();
        if (canvas.tool == CanvasView.SELECT && t != CanvasView.SELECT) canvas.closePolygon();
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
        else if (t == CanvasView.MOVE) { canvas.beginMove(); showTransformBar(); } // box, handles and rotation lever show straight away
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
            chip(a, kinds[i], canvas.selKind == i, v -> { canvas.closePolygon(); canvas.selKind = k; showSelectBar(); });
        }
        chip(b, canvas.selSubtract ? "− Subtract" : "+ Add", canvas.selSubtract, v -> { canvas.selSubtract = !canvas.selSubtract; showSelectBar(); });
        if (canvas.selKind != 0) chip(b, (canvas.selFill ? "✓ " : "") + "Colour Fill", canvas.selFill, v -> { canvas.selFill = !canvas.selFill; showSelectBar(); });
        chip(b, "Invert", false, v -> canvas.invertSelection());
        chip(b, "Copy & Paste", false, v -> { if (!canvas.copyPaste(false)) full(); });
        chip(b, "Cut & Paste", false, v -> { if (!canvas.copyPaste(true)) full(); });
        chip(b, "Feather", false, v -> featherDialog());
        chip(b, "Clear", false, v -> canvas.clearSelection());
        chip(b, "Save", false, v -> saveSelection());
        chip(b, "Load", false, this::loadSelectionMenu);
        chip(a, "Done", true, v -> { canvas.closePolygon(); setTool(CanvasView.DRAW); });
        if (canvas.selKind == 0) showBar(a, b, slider("Threshold %", pct(canvas.autoThreshold()), 0, 100, v -> setAutoThreshold(Math.round(v * 2.55f))));
        else showBar(a, b);
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
        chip(a, "Rotate 45°", false, v -> canvas.rotateBy(45));
        chip(a, "Rotate 90°", false, v -> canvas.rotate90());
        if (canvas.xform == 3) chip(a, (canvas.advMesh ? "✓ " : "") + "Advanced mesh", canvas.advMesh, v -> { canvas.setAdvancedMesh(!canvas.advMesh); showTransformBar(); });
        chip(a, "Reset", false, v -> canvas.resetTransform());
        chip(a, (canvas.snapping ? "✓ " : "") + "Snapping + magnetics", canvas.snapping, v -> { canvas.snapping = !canvas.snapping; showTransformBar(); });
        chip(a, canvas.smoothResample ? "Smooth" : "Nearest", false, v -> { canvas.smoothResample = !canvas.smoothResample; canvas.invalidate(); showTransformBar(); });
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
            new Adj("Posterize", new String[]{"Levels", "Threshold"}, new float[]{2, -0.5f}, new float[]{32, 0.5f}, new float[]{4, 0},
                    v -> (s, o) -> Fx.lut(s, o, Fx.posterize(Math.round(v[0]), v[1]))),
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
            lab.setTextColor(0xFF9A9A9E);
            box.addView(lab);
            Runnable showVal = () -> { // the value next to the name, for fine control
                float v = adjVals[k];
                boolean whole = a.max[k] - a.min[k] > 3;
                lab.setText(a.params[k] + "   " + (whole ? String.valueOf(Math.round(v)) : (v > 0 && a.min[k] < 0 ? "+" : "") + Math.round(v * 100) + "%"));
            };
            showVal.run();
            SeekBar sb = new SeekBar(this);
            sb.setMax(1000);
            sb.setProgress(Math.round((a.def[i] - a.min[i]) / (a.max[i] - a.min[i]) * 1000));
            sb.setOnSeekBarChangeListener(new Slider() {
                public void onProgressChanged(SeekBar s, int p, boolean u) {
                    adjVals[k] = a.min[k] + (a.max[k] - a.min[k]) * p / 1000f;
                    showVal.run();
                    canvas.previewAdjust(a.fn.apply(adjVals));
                }
            });
            adjBars[i] = sb;
            box.addView(sb, new LinearLayout.LayoutParams((int) (300 * dp), -2));
        }
        if (a.name.equals("Curves")) {
            curveView = new Ui.CurveView(this);
            curveView.listener = () -> canvas.previewAdjust(a.fn.apply(adjVals));
            int[] hist = new int[256]; // the layer's greys, shown behind the curve
            Bitmap lb = canvas.cur().bmp;
            int step = Math.max(1, (int) Math.sqrt((double) lb.getWidth() * lb.getHeight() / 250000));
            int[] row = new int[lb.getWidth()];
            for (int y = 0; y < lb.getHeight(); y += step) {
                lb.getPixels(row, 0, lb.getWidth(), 0, y, lb.getWidth(), 1);
                for (int x = 0; x < row.length; x += step) if ((row[x] >>> 24) > 20) hist[row[x] & 255]++;
            }
            curveView.hist = hist;
            int side = (int) Math.min(300 * dp, getResources().getDisplayMetrics().widthPixels - 48 * dp);
            box.addView(curveView, new LinearLayout.LayoutParams(side, (int) (side * 0.8f)));
            TextView tip = new TextView(this);
            tip.setText("Tap the line: add a point · drag it · double-tap: remove");
            tip.setTextColor(0xFF8E8E93);
            tip.setTextSize(12);
            box.addView(tip);
        }
        LinearLayout btns = barRow();
        chip(btns, "Reset", false, v -> {
            if (curveView != null && a.name.equals("Curves")) curveView.reset();
            for (int i = 0; i < adjBars.length; i++)
                adjBars[i].setProgress(Math.round((a.def[i] - a.min[i]) / (a.max[i] - a.min[i]) * 1000));
            canvas.previewAdjust(a.fn.apply(adjVals));
        });
        chip(btns, canvas.adjPencil ? "Pencil" : "Layer", false, v -> { // Pencil: paint the effect in with the brush
            if (!canvas.adjPencil) { canvas.adjustPencil(a.fn.apply(adjVals)); hud("Paint where you want " + a.name); ((TextView) v).setText("Pencil"); }
        });
        chip(btns, "Cancel", false, v -> finishAdjust(false));
        chip(btns, "Apply", true, v -> finishAdjust(true));
        showBar(box, btns);
        canvas.adjName = a.name;
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
        String[] names = {"Push", "Twirl ↻", "Twirl ↺", "Pinch", "Expand", "Reconstruct", "Crystals", "Edge"};
        for (int i = 0; i < names.length; i++) {
            int k = i;
            chip(modes, names[i], canvas.liqMode == i, v -> { canvas.liqMode = k; showLiquifyBar(); });
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(slider("Momentum", Math.round(canvas.liqMomentum * 100), 0, 100, v -> canvas.liqMomentum = v / 100f));
        box.addView(slider("Distortion", Math.round(canvas.liqDistortion * 100), 0, 100, v -> canvas.liqDistortion = v / 100f));
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

    private float cdX, cdY, cdHoldX;
    private boolean cdDrag, cdHeld;
    private int cdBaseThr;
    private final Runnable cdHold = () -> { // finger rests on the canvas: drop now, then slide to set the threshold
        int[] loc = new int[2];
        canvas.getLocationOnScreen(loc);
        float x = cdX - loc[0], y = cdY - loc[1];
        if (x < 0 || y < 0 || x > canvas.getWidth() || y > canvas.getHeight()) return;
        canvas.colorDrop(x, y, canvas.fillThreshold);
        cdHeld = true; cdHoldX = cdX; cdBaseThr = canvas.fillThreshold;
        showThresholdBar(canvas.fillThreshold);
        if (this.cdGhost != null) this.cdGhost.setVisibility(View.GONE);
    };

    private static int pct(int thr) { return Math.round(thr * 100 / 255f); }

    /** Refills with a new threshold, at most once per frame (each refill scans the whole canvas). */
    private int pendingThr = -1;
    private final Runnable refill = () -> {
        if (pendingThr >= 0) { canvas.refineFill(pendingThr); canvas.fillThreshold = pendingThr; pendingThr = -1; }
    };
    private int pendingAuto = -1;
    private final Runnable reselect = () -> { if (pendingAuto >= 0) { canvas.refineAuto(pendingAuto); pendingAuto = -1; } };
    private void setAutoThreshold(int thr) {
        boolean idle = pendingAuto < 0;
        pendingAuto = Math.max(0, Math.min(255, thr));
        if (idle) canvas.postOnAnimation(reselect);
    }
    private void setFillThreshold(int thr) {
        thr = Math.max(0, Math.min(255, thr));
        if (thr == canvas.fillThreshold && pendingThr < 0) return;
        boolean idle = pendingThr < 0;
        pendingThr = thr;
        if (idle) canvas.postOnAnimation(refill);
    }

    // ColorDrop helpers on screen: a grey dot that follows the finger, and a threshold bar at the top (like Procreate)
    private View cdGhost, cdBar;
    private TextView cdBarLabel;
    private View cdBarFill;

    private void showDropGhost(float rawX, float rawY) {
        if (cdGhost == null) {
            cdGhost = new View(this) {
                final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas c) {
                    float w = getWidth(), r = 15 * dp, cx = w / 2, cy = r + 3 * dp, tip = getHeight() - 2 * dp;
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2 * dp); p.setColor(Ui.ACCENT);
                    c.drawLine(cx, cy + r, cx, tip, p); // stem down to where it will drop
                    p.setStyle(Paint.Style.FILL); p.setColor(0xFF000000 | canvas.grey * 0x10101);
                    c.drawCircle(cx, cy, r, p);
                    p.setStyle(Paint.Style.STROKE); p.setColor(0xFF1C1C1E); p.setStrokeWidth(4.5f * dp);
                    c.drawCircle(cx, cy, r + 1 * dp, p); // dark outline: shows on white paper
                    p.setColor(Color.WHITE); p.setStrokeWidth(2.5f * dp);
                    c.drawCircle(cx, cy, r, p);
                    p.setStyle(Paint.Style.FILL); p.setColor(Ui.ACCENT);
                    c.drawCircle(cx, tip, 3 * dp, p);
                }
            };
            shell.addView(cdGhost, new FrameLayout.LayoutParams((int) (40 * dp), (int) (78 * dp)));
        }
        int[] sl = new int[2];
        shell.getLocationOnScreen(sl);
        cdGhost.setTranslationX(rawX - sl[0] - 20 * dp);
        cdGhost.setTranslationY(rawY - sl[1] - 76 * dp); // above the finger so it stays visible
        cdGhost.setVisibility(View.VISIBLE);
        cdGhost.invalidate();
    }

    private void showThresholdBar(int thr) {
        if (cdBar == null) {
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setPadding((int) (14 * dp), (int) (8 * dp), (int) (14 * dp), (int) (10 * dp));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xF02C2C2E); bg.setCornerRadius(12 * dp);
            b.setBackground(bg);
            cdBarLabel = new TextView(this);
            cdBarLabel.setTextColor(Color.WHITE);
            cdBarLabel.setTextSize(13);
            cdBarLabel.setGravity(Gravity.CENTER);
            b.addView(cdBarLabel);
            FrameLayout track = new FrameLayout(this);
            android.graphics.drawable.GradientDrawable tb = new android.graphics.drawable.GradientDrawable();
            tb.setColor(0xFF48484A); tb.setCornerRadius(3 * dp);
            track.setBackground(tb);
            cdBarFill = new View(this);
            android.graphics.drawable.GradientDrawable fb = new android.graphics.drawable.GradientDrawable();
            fb.setColor(Ui.ACCENT); fb.setCornerRadius(3 * dp);
            cdBarFill.setBackground(fb);
            track.addView(cdBarFill, new FrameLayout.LayoutParams(0, -1));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams((int) (220 * dp), (int) (6 * dp));
            tl.topMargin = (int) (6 * dp);
            b.addView(track, tl);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = (int) (64 * dp);
            shell.addView(b, lp);
            cdBar = b;
        }
        cdBarLabel.setText("ColorDrop Threshold   " + pct(thr) + "%");
        ViewGroup.LayoutParams fl = cdBarFill.getLayoutParams();
        fl.width = Math.round(220 * dp * thr / 255f);
        cdBarFill.setLayoutParams(fl);
        cdBar.setVisibility(View.VISIBLE);
        cdBar.bringToFront();
    }

    private void hideDropHelpers() {
        if (cdGhost != null) cdGhost.setVisibility(View.GONE);
        if (cdBar != null) cdBar.setVisibility(View.GONE);
    }

    private boolean colorDropTouch(View v, android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                cdX = e.getRawX(); cdY = e.getRawY(); cdDrag = cdHeld = false;
                break;
            case android.view.MotionEvent.ACTION_MOVE:
                if (cdHeld) { // like Procreate: slide sideways to change the threshold; the bar at the top shows it
                    int thr = cdBaseThr + Math.round((e.getRawX() - cdHoldX) / (canvas.getWidth() * 0.6f) * 255);
                    thr = Math.max(0, Math.min(255, thr));
                    setFillThreshold(thr);
                    showThresholdBar(thr);
                    return true;
                }
                if (Math.hypot(e.getRawX() - cdX, e.getRawY() - cdY) > 24 * dp) cdDrag = true;
                if (cdDrag) showDropGhost(e.getRawX(), e.getRawY());
                if (cdDrag && Math.hypot(e.getRawX() - cdX, e.getRawY() - cdY) > 6 * dp) { // still moving: restart the hold timer
                    cdX = e.getRawX(); cdY = e.getRawY();
                    v.removeCallbacks(cdHold);
                    v.postDelayed(cdHold, 450);
                }
                if (cdDrag) return true;
                break;
            case android.view.MotionEvent.ACTION_UP:
                v.removeCallbacks(cdHold);
                hideDropHelpers();
                if (cdHeld) { cdHeld = false; showFillBar(); return true; }
                if (cdDrag) {
                    int[] loc = new int[2];
                    canvas.getLocationOnScreen(loc);
                    canvas.colorDrop(e.getRawX() - loc[0], e.getRawY() - loc[1], canvas.fillThreshold);
                    showFillBar();
                    return true;
                }
                break;
            case android.view.MotionEvent.ACTION_CANCEL:
                v.removeCallbacks(cdHold);
                hideDropHelpers();
                cdHeld = false;
                break;
        }
        return false; // let taps through to the colour picker
    }

    /** After ColorDrop: adjust the threshold (refills live) and "Continue filling" like Procreate. */
    private void showFillBar() {
        LinearLayout a = barRow();
        android.widget.Switch cont = new android.widget.Switch(this);
        cont.setText("Continue filling  ");
        cont.setTextColor(Color.WHITE);
        cont.setChecked(canvas.fillMode);
        cont.setOnCheckedChangeListener((b, on) -> { canvas.fillMode = on; if (on) hud("Tap areas to fill them"); });
        a.addView(cont);
        chip(a, "Done", true, v -> { canvas.fillMode = false; bottom.setVisibility(View.GONE); });
        showBar(slider("Threshold %", pct(canvas.fillThreshold), 0, 100, v -> setFillThreshold(Math.round(v * 2.55f))), a);
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
        mm.add(0, 28, 18, "Lock").setCheckable(true).setChecked(l.locked);
        mm.add(0, 29, 19, "Reference").setCheckable(true).setChecked(l.reference);
        mm.add(0, 31, 21, "Drawing Assist").setCheckable(true).setChecked(l.assist);
        if (l.text != null) mm.add(0, 30, 20, "Edit text");
        if (l.text != null) mm.add(0, 32, 22, "Rasterize text");
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
                case 11: canvas.deleteLayer(); break; // undoable, so no question (like Procreate)
                case 20: canvas.addMask(); toast("Mask added: paint black to hide, white to show"); break;
                case 21: canvas.editMask(!l.editMask); break;
                case 22: canvas.applyMask(); break;
                case 23: l.maskOn = !l.maskOn; canvas.invalidate(); refreshPanel(); break;
                case 24: canvas.deleteMask(); break;
                case 25: canvas.groupWithBelow(); break;
                case 26: canvas.newGroup(); break;
                case 27: canvas.ungroup(); break;
                case 28: canvas.toggleLock(idx); break;
                case 32: canvas.rasterizeText(canvas.layers.get(idx)); refreshPanel(); break;
                case 31: canvas.toggleAssist(idx); hud(canvas.layers.get(idx).assist ? "Drawing Assist on for this layer" : "Drawing Assist off"); break;
                case 29: canvas.toggleReference(idx); hud(canvas.layers.get(idx).reference ? "Reference: fills use this layer's lines" : "Reference off"); break;
                case 30: textDialog(l); break;
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

    private void textDialog() { textDialog(null); }

    private static final String[] FONTS = {"sans-serif", "serif", "monospace", "sans-serif-condensed", "casual", "cursive"};
    private static final String[] FONT_NAMES = {"Sans", "Serif", "Mono", "Condensed", "Casual", "Cursive"};

    /** Add text, or edit an existing text layer (font, size, bold, alignment). */
    private void textDialog(CanvasView.Layer editing) {
        CanvasView.TextInfo cur = editing != null ? editing.text : null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * dp);
        box.setPadding(p, p, p, 0);
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("Text");
        et.setMinLines(2);
        if (cur != null) et.setText(cur.text);
        box.addView(et);
        java.util.List<String> fontIds = new java.util.ArrayList<>(java.util.Arrays.asList(FONTS)), fontNames = new java.util.ArrayList<>(java.util.Arrays.asList(FONT_NAMES));
        File[] custom = fontDir().listFiles();
        if (custom != null) { java.util.Arrays.sort(custom); for (File f : custom) { fontIds.add("file:" + f.getName()); fontNames.add(f.getName().replaceAll("\\.[^.]*$", "")); } }
        int[] font = {cur == null ? 0 : Math.max(0, fontIds.indexOf(cur.font))};
        int[] align = {cur == null ? 1 : cur.align};
        LinearLayout fonts = barRow(), aligns = barRow();
        Runnable[] redraw = new Runnable[1];
        redraw[0] = () -> {
            fonts.removeAllViews(); aligns.removeAllViews();
            for (int i = 0; i < fontIds.size(); i++) {
                int k = i;
                TextView c = chip(fonts, fontNames.get(i), font[0] == i, v -> { font[0] = k; redraw[0].run(); });
                c.setTypeface(CanvasView.typeface(this, fontIds.get(i), false));
            }
            chip(fonts, "＋ Import font", false, v -> { // .ttf / .otf from Files
                if (textDlg != null) textDlg.dismiss();
                fontReturn = new Object[]{editing};
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf",
                                "application/x-font-otf", "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream"}), REQ_FONT);
            });
            String[] an = {"Left", "Centre", "Right"};
            for (int i = 0; i < 3; i++) { int k = i; chip(aligns, an[i], align[0] == i, v -> { align[0] = k; redraw[0].run(); }); }
        };
        redraw[0].run();
        HorizontalScrollView fs = new HorizontalScrollView(this);
        fs.addView(fonts);
        box.addView(fs);
        box.addView(aligns);
        TextView sl = new TextView(this);
        sl.setText("Size");
        box.addView(sl);
        SeekBar sb = new SeekBar(this);
        sb.setMax(400);
        sb.setProgress(cur != null ? Math.round(cur.size) : Math.max(24, canvas.docW / 12));
        box.addView(sb);
        android.widget.CheckBox bold = new android.widget.CheckBox(this);
        bold.setText("Bold");
        bold.setChecked(cur != null && cur.bold);
        LinearLayout styles = barRow();
        styles.addView(bold);
        android.widget.CheckBox under = new android.widget.CheckBox(this), outl = new android.widget.CheckBox(this);
        under.setText("Underline"); under.setChecked(cur != null && cur.underline);
        outl.setText("Outline"); outl.setChecked(cur != null && cur.outline);
        styles.addView(under); styles.addView(outl);
        box.addView(styles);
        TextView spl = new TextView(this);
        box.addView(spl);
        SeekBar sp = new SeekBar(this); // letter spacing −0.10 … +0.50 em
        sp.setMax(60);
        sp.setProgress(Math.round(((cur != null ? cur.spacing : 0) + 0.1f) * 100));
        box.addView(sp);
        TextView lsl = new TextView(this);
        box.addView(lsl);
        SeekBar ls = new SeekBar(this); // line spacing 0.6 … 2.5 ×
        ls.setMax(190);
        ls.setProgress(Math.round(((cur != null ? cur.lineSpacing : 1f) - 0.6f) * 100));
        box.addView(ls);
        Runnable lbl = () -> {
            spl.setText(String.format("Letter spacing  %.2f", sp.getProgress() / 100f - 0.1f));
            lsl.setText(String.format("Line spacing  %.2f×", 0.6f + ls.getProgress() / 100f));
        };
        lbl.run();
        sp.setOnSeekBarChangeListener(new Slider() { public void onProgressChanged(SeekBar s2, int p2, boolean u) { lbl.run(); } });
        ls.setOnSeekBarChangeListener(new Slider() { public void onProgressChanged(SeekBar s2, int p2, boolean u) { lbl.run(); } });
        textDlg = new AlertDialog.Builder(this).setTitle(editing != null ? "Edit text" : "Add text").setView(box)
                .setPositiveButton(editing != null ? "Update" : "Add", (d, w) -> {
                    String t = et.getText().toString();
                    if (t.trim().isEmpty()) return;
                    CanvasView.TextInfo ti = new CanvasView.TextInfo(t, Math.max(8, sb.getProgress()), bold.isChecked(), align[0], fontIds.get(font[0]),
                            cur != null ? cur.grey : canvas.grey);
                    ti.spacing = sp.getProgress() / 100f - 0.1f;
                    ti.lineSpacing = 0.6f + ls.getProgress() / 100f;
                    ti.underline = under.isChecked();
                    ti.outline = outl.isChecked();
                    if (editing != null) { canvas.editText(editing, ti); return; }
                    if (!canvas.addTextLayer(ti)) { full(); return; }
                    setTool(CanvasView.MOVE);
                    hud("Drag to place the text, then tap Done");
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---- Gallery: share / export several drawings ----

    private java.util.List<String> manyIds;
    private int manyFmt; // 0 PNG, 1 JPEG, 4 PSD (into a folder)

    private Bitmap compositeOf(String id) throws Exception {
        try (InputStream in = new FileInputStream(docFile(id))) { return Psd.readComposite(in); }
    }

    private String fileNameOf(String id, String ext) { return nameOf(id).replaceAll("[/\\\\:*?\"<>|]", "_") + "." + ext; }

    private void exportManyMenu(View anchor, java.util.List<String> ids) {
        Ui.PopMenu m = new Ui.PopMenu(this, anchor);
        m.getMenu().add(0, 0, 0, "PNG files (into a folder)");
        m.getMenu().add(0, 1, 1, "JPEG files (into a folder)");
        m.getMenu().add(0, 4, 2, "PSD files with layers (into a folder)");
        m.getMenu().add(0, 3, 3, "One PDF, a page per drawing");
        m.setOnMenuItemClickListener(it -> {
            manyIds = ids; manyFmt = it.getItemId();
            saveCurrent(() -> {
                if (manyFmt == 3) startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/pdf").putExtra(Intent.EXTRA_TITLE, "Inkwell drawings.pdf"), REQ_PDF_MANY);
                else startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
            });
            return true;
        });
        m.show();
    }

    /** Writes the picked drawings: one PDF, or one file each into the chosen folder. */
    private void exportMany(Uri target, boolean pdf) {
        java.util.List<String> ids = manyIds;
        int fmt = manyFmt;
        if (ids == null) return;
        toast("Exporting " + ids.size() + " drawings…");
        new Thread(() -> {
            int done = 0;
            String err = null;
            try {
                if (pdf) {
                    PdfDocument doc = new PdfDocument();
                    float k = 72f / 300f;
                    int page = 1;
                    for (String id : ids) {
                        Bitmap b = compositeOf(id);
                        PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(
                                Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), page++).create());
                        pg.getCanvas().scale(k, k);
                        pg.getCanvas().drawBitmap(b, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
                        doc.finishPage(pg);
                        b.recycle();
                        done++;
                    }
                    try (OutputStream o = getContentResolver().openOutputStream(target, "wt")) { doc.writeTo(o); }
                    doc.close();
                } else {
                    getContentResolver().takePersistableUriPermission(target, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    Uri parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(target, android.provider.DocumentsContract.getTreeDocumentId(target));
                    String ext = fmt == 0 ? "png" : fmt == 1 ? "jpg" : "psd";
                    String mime = fmt == 0 ? "image/png" : fmt == 1 ? "image/jpeg" : "image/vnd.adobe.photoshop";
                    for (String id : ids) {
                        Uri out = android.provider.DocumentsContract.createDocument(getContentResolver(), parent, mime, fileNameOf(id, ext));
                        if (out == null) continue;
                        try (OutputStream o = getContentResolver().openOutputStream(out, "wt")) {
                            if (fmt == 4) java.nio.file.Files.copy(docFile(id).toPath(), o);
                            else {
                                Bitmap b = compositeOf(id);
                                b.compress(fmt == 0 ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, fmt == 0 ? 100 : 95, o);
                                b.recycle();
                            }
                        }
                        done++;
                    }
                }
            } catch (Throwable t) { err = t.getMessage(); }
            int d = done; String e = err;
            runOnUiThread(() -> toast(e == null ? "Exported " + d + " drawings" : "Export stopped after " + d + ": " + e));
        }).start();
        manyIds = null;
    }

    /** Share sheet with several drawings as PNG (from the app's own share folder; nothing is added to Photos). */
    private void shareMany(java.util.List<String> ids) {
        toast("Preparing " + ids.size() + " drawings…");
        saveCurrent(() -> new Thread(() -> {
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            File d = Files.dir(this);
            File[] old = d.listFiles();
            if (old != null) for (File f : old) f.delete();
            try {
                java.util.Set<String> used = new java.util.HashSet<>();
                for (String id : ids) {
                    String nm = fileNameOf(id, "png");
                    for (int k = 2; !used.add(nm); k++) nm = fileNameOf(id, "").replaceAll("\\.$", "") + " " + k + ".png";
                    File f = new File(d, nm);
                    try (OutputStream o = new FileOutputStream(f)) {
                        Bitmap b = compositeOf(id);
                        b.compress(Bitmap.CompressFormat.PNG, 100, o);
                        b.recycle();
                    }
                    uris.add(Files.uri(f));
                }
            } catch (Throwable t) { runOnUiThread(() -> toast("Could not prepare: " + t.getMessage())); }
            if (uris.isEmpty()) return;
            runOnUiThread(() -> {
                Intent i = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE).setType("image/png")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                android.content.ClipData cd = android.content.ClipData.newRawUri("", uris.get(0));
                for (int k = 1; k < uris.size(); k++) cd.addItem(new android.content.ClipData.Item(uris.get(k)));
                i.setClipData(cd);
                if (uris.size() == 1) i.putExtra(Intent.EXTRA_STREAM, uris.get(0)); else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                try { startActivity(Intent.createChooser(i, "Share drawings")); } catch (Exception e) { toast("No app to share with"); }
            });
        }).start());
    }

    /** Drag and drop from other apps: images/art files (and brush files) dropped on a view are imported. */
    private void acceptDrops(View target, boolean asLayer) {
        target.setOnDragListener((v, ev) -> {
            switch (ev.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED: {
                    android.content.ClipDescription d = ev.getClipDescription();
                    return d != null && (d.hasMimeType("image/*") || d.hasMimeType("application/*") || d.hasMimeType("text/uri-list"));
                }
                case android.view.DragEvent.ACTION_DROP: {
                    android.content.ClipData cd = ev.getClipData();
                    if (cd == null) return false;
                    try { requestDragAndDropPermissions(ev); } catch (Exception ignored) { }
                    int n = 0;
                    for (int i = 0; i < cd.getItemCount(); i++) {
                        Uri u = cd.getItemAt(i).getUri();
                        if (u == null) continue;
                        load(u, asLayer);
                        if (!asLayer) break; // one new drawing per drop
                        n++;
                    }
                    return n > 0 || !asLayer;
                }
                default: return true;
            }
        });
    }

    private AlertDialog textDlg;
    private Object[] fontReturn; // the text being edited when "Import font" was tapped

    private File fontDir() { File d = new File(getFilesDir(), "fonts"); d.mkdirs(); return d; }

    /** Copies a .ttf/.otf into the app and checks it is a real font. */
    private void importFont(Uri uri) {
        String name = displayName(uri);
        if (name == null || !name.contains(".")) name = "Font " + System.currentTimeMillis() + ".ttf";
        name = name.replaceAll("[/\\\\:]", "_");
        File f = new File(fontDir(), name);
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream o = new FileOutputStream(f)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            new android.graphics.fonts.Font.Builder(f).build(); // throws if it isn't a real font
            toast("Font added: " + name.replaceAll("\\.[^.]*$", ""));
        } catch (Exception e) {
            f.delete();
            toast("Not a font file (use .ttf or .otf)");
        }
        CanvasView.Layer back = fontReturn != null ? (CanvasView.Layer) fontReturn[0] : null;
        fontReturn = null;
        textDialog(back);
    }

    private void guideMenu(View anchor) { guideEditor(); }

    /** Actions → Canvas → Drawing guide switch: shows the last-used guide (2D Grid the first time), like Procreate. */
    private void setGuideOn(boolean on) {
        if (on) {
            int g = prefs.getInt("lastGuide", 1);
            canvas.guide = g;
            if (g >= 7) { // perspective needs points
                boolean unset = true;
                for (float v : canvas.vp) if (v != 0) unset = false;
                if (unset) canvas.resetVps();
            }
        } else {
            if (canvas.guide != 0) prefs.edit().putInt("lastGuide", canvas.guide).apply();
            canvas.guide = 0;
            canvas.editGuide = false;
        }
        canvas.invalidate();
    }

    private void editVanishingPoints() { guideEditor(); }

    private int guideTab() {
        int g = canvas.guide;
        return g == 1 ? 1 : g == 6 ? 2 : g >= 7 ? 3 : g >= 2 ? 4 : 0;
    }

    /** Drawing Guide editor (like Procreate's): type, look, assisted drawing; perspective points editable with undo. */
    private void guideEditor() {
        int tab = guideTab();
        canvas.editGuide = tab == 3;
        canvas.invalidate();
        LinearLayout types = barRow();
        String[] names = {"Off", "2D Grid", "Isometric", "Perspective", "Symmetry"};
        for (int i = 0; i < names.length; i++) {
            int k = i;
            chip(types, names[i], i == tab, v -> {
                if (k == guideTab()) return;
                if (k == 0) canvas.guide = 0;
                else if (k == 1) canvas.guide = 1;
                else if (k == 2) canvas.guide = 6;
                else if (k == 3) { canvas.guide = 8; canvas.resetVps(); }
                else canvas.guide = 2;
                canvas.invalidate();
                guideEditor();
            });
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (tab == 1 || tab == 2) box.addView(slider("Grid size", canvas.gridSize, 8, 600, v -> { canvas.gridSize = v; canvas.invalidate(); }));
        if (tab == 3) {
            TextView hint = new TextView(this);
            hint.setText("Tap: add point (max 3) · tap point: delete · drag: move, even off the canvas · ▢ moves the horizon");
            hint.setTextColor(0xFF9A9A9E);
            hint.setTextSize(13);
            box.addView(hint);
            box.addView(slider("Lines per point", canvas.vpRays, 6, 180, v -> { canvas.vpRays = v; canvas.invalidate(); }));
            LinearLayout hz = barRow();
            android.widget.Switch tilt = new android.widget.Switch(this);
            tilt.setText("Tilt horizon  ");
            tilt.setTextColor(Color.WHITE);
            tilt.setChecked(!canvas.horizonLevel);
            tilt.setOnCheckedChangeListener((b, on) -> {
                canvas.horizonLevel = !on;
                if (!on && canvas.guide >= 8) { // straighten: both points at the average height
                    float[] before = canvas.vp.clone();
                    float y = (canvas.vp[1] + canvas.vp[3]) / 2;
                    canvas.vp[1] = y; canvas.vp[3] = y;
                    canvas.guideEdit(before, canvas.guide);
                }
                canvas.invalidate();
            });
            hz.addView(tilt);
            chip(hz, "Show all points", false, v -> {
                canvas.fitInsetBottom = bottom.getVisibility() == View.VISIBLE ? bottom.getHeight() + (int) (16 * dp) : 0;
                canvas.fitGuide();
            });
            box.addView(hz);
        }
        if (tab == 4) {
            LinearLayout sym = barRow();
            String[] sn = {"Vertical", "Horizontal", "Quadrant", "Radial"};
            for (int i = 0; i < sn.length; i++) {
                int g = 2 + i;
                chip(sym, sn[i], canvas.guide == g, v -> { canvas.guide = g; canvas.invalidate(); guideEditor(); });
            }
            box.addView(sym);
            android.widget.Switch rot = new android.widget.Switch(this);
            rot.setText("Rotational symmetry (off = mirror)  ");
            rot.setTextColor(Color.WHITE);
            rot.setChecked(canvas.symRotate);
            rot.setOnCheckedChangeListener((b, on) -> canvas.symRotate = on);
            box.addView(rot);
        }
        if (tab != 0) {
            box.addView(slider("Opacity", Math.round(canvas.guideOpacity * 100), 5, 100, v -> { canvas.guideOpacity = v / 100f; canvas.invalidate(); }));
            box.addView(slider("Thickness", Math.round(canvas.guideThick * 10), 5, 60, v -> { canvas.guideThick = v / 10f; canvas.invalidate(); }));
        }
        LinearLayout end = barRow();
        if (tab != 0 && tab != 4) {
            android.widget.Switch sw = new android.widget.Switch(this);
            sw.setText("Assisted drawing (this layer)  ");
            sw.setTextColor(Color.WHITE);
            sw.setChecked(canvas.cur().assist);
            sw.setOnCheckedChangeListener((b, on) -> { if (canvas.cur().assist != on) canvas.toggleAssist(canvas.current); refreshPanel(); });
            end.addView(sw);
        }
        if (tab == 3) chip(end, "Reset points", false, v -> {
            float[] before = canvas.vp.clone();
            int gb = canvas.guide;
            canvas.guide = 8;
            canvas.resetVps();
            canvas.guideEdit(before, gb);
        });
        chip(end, "Done", true, v -> {
            canvas.editGuide = false;
            if (canvas.guide != 0) prefs.edit().putInt("lastGuide", canvas.guide).apply(); // the switch brings this one back
            canvas.invalidate();
            bottom.setVisibility(View.GONE);
        });
        showBar(types, box, end);
    }

    /** A labelled slider row with the current value. */
    private LinearLayout slider(String label, int value, int min, int max, java.util.function.IntConsumer onChange) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setTextColor(0xFFE8E8E8);
        t.setTextSize(13);
        t.setText(label + "  " + value);
        r.addView(t, new LinearLayout.LayoutParams((int) (130 * dp), -2));
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        sb.setOnSeekBarChangeListener(new Slider() {
            public void onProgressChanged(SeekBar s2, int p, boolean u) { t.setText(label + "  " + (p + min)); onChange.accept(p + min); }
        });
        r.addView(sb, new LinearLayout.LayoutParams((int) (240 * dp), (int) (40 * dp)));
        return r;
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
                pop.item("Insert a photo", true, () -> pickPhoto(REQ_IMPORT));
                pop.item("Take a photo", true, this::takePhoto);
                pop.item("Open as a new drawing", true, () -> action(1, anchor));
                pop.item("Add text", true, () -> action(9, anchor));
                pop.item("Cut", true, () -> canvas.copy(false, true));
                pop.item("Copy", true, () -> canvas.copy(false, false));
                pop.item("Copy canvas", true, () -> canvas.copy(true, false));
                pop.item("Paste", canvas.hasClipboard(), () -> { if (!canvas.paste()) full(); });
                break;
            case 1: // Canvas
                pop.item("Crop & resize", true, this::startCrop);
                pop.toggle("Animation Assist", canvas.anim, v -> { pop.win.dismiss(); action(12, anchor); });
                pop.toggle("Page Assist", canvas.pages, v -> { pop.win.dismiss(); togglePages(); });
                pop.toggle("Drawing guide", canvas.guide != 0, this::setGuideOn);
                pop.item("Edit drawing guide", true, () -> guideMenu(anchor));
                pop.toggle("Reference", refView != null && refView.getVisibility() == View.VISIBLE, v -> { pop.win.dismiss(); action(11, anchor); });
                pop.item("Flip canvas horizontally", true, () -> canvas.flipView(true));
                pop.item("Flip canvas vertically", true, () -> canvas.flipView(false));
                pop.item("Fit to screen", true, () -> action(5, anchor));
                pop.item("Canvas information", true, this::canvasInfo);
                break;
            case 2: // Share
                pop.section("Share image");
                for (int i = FORMATS.length - 1; i >= 0; i--) { int f = i; pop.item(FORMATS[i].replace(" (layers)", ""), true, () -> shareAs(f)); }
                pop.section("Save to Files");
                LinearLayout sv = barRow();
                for (int i = 0; i < FORMATS.length; i++) { int f = i; chip(sv, FORMATS[i].replace(" (layers)", ""), false, v -> { pop.win.dismiss(); startExport(f); }); }
                HorizontalScrollView svs = new HorizontalScrollView(this);
                svs.addView(sv);
                pop.add(svs);
                pop.section("Share layers");
                pop.item("PNG files (each layer)", true, () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_LAYERS_TREE));
                pop.item("Animated GIF", true, () -> exportAnimRequest(true));
                pop.item("Animated PNG", true, () -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/png").putExtra(Intent.EXTRA_TITLE, nameOf(curId) + ".png"), REQ_APNG));
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
                pop.toggle("Brush cursor", canvas.showCursor, v -> { canvas.showCursor = v; prefs.edit().putBoolean("cursor", v).apply(); });
                pop.section("Finger when using a stylus");
                LinearLayout fm = barRow();
                String[] fmn = {"Navigate", "Paint", "Erase"};
                for (int i = 0; i < 3; i++) {
                    int k = i;
                    chip(fm, fmn[i], canvas.fingerMode == i, v -> { canvas.fingerMode = k; prefs.edit().putInt("fingerMode", k).apply(); pop.win.dismiss(); });
                }
                pop.add(fm);
                pop.section("Interface size");
                LinearLayout sz = barRow();
                String[] szn = {"Small", "Medium", "Large"};
                for (int i = 0; i < 3; i++) {
                    int k = i;
                    chip(sz, szn[i], prefs.getInt("uiSize", 1) == i, v -> { prefs.edit().putInt("uiSize", k).apply(); pop.win.dismiss(); saveCurrent(this::recreate); });
                }
                pop.add(sz);
                pop.section("Pressure and smoothing (all brushes)");
                pop.add(slider("Pressure curve", Math.round(canvas.globalCurve * 100), 30, 300, v -> { canvas.globalCurve = v / 100f; prefs.edit().putFloat("gCurve", canvas.globalCurve).apply(); }));
                pop.add(slider("Smoothing", Math.round(canvas.globalSmooth * 100), 0, 100, v -> { canvas.globalSmooth = v / 100f; prefs.edit().putFloat("gSmooth", canvas.globalSmooth).apply(); }));
                pop.section("Gesture controls");
                pop.add(new TextView(this) {{ setText("   Touch and hold with a finger"); setTextColor(0xFFE8E8E8); }});
                LinearLayout ha = barRow();
                String[] han = {"Eyedropper", "QuickMenu", "Nothing"};
                for (int i = 0; i < 3; i++) {
                    int k = i;
                    chip(ha, han[i], canvas.holdAction == i, v -> { canvas.holdAction = k; prefs.edit().putInt("holdAction", k).apply(); pop.win.dismiss(); actionsMenu(anchor, 4); });
                }
                pop.add(ha);
                pop.add(slider("Hold delay (ms)", canvas.holdDelay, 150, 1200, v -> { canvas.holdDelay = v; prefs.edit().putInt("holdDelay", v).apply(); }));
                pop.add(new TextView(this) {{ setText("   Sidebar square button"); setTextColor(0xFFE8E8E8); }});
                LinearLayout sq = barRow();
                String[] sqn = {"Eyedropper", "QuickMenu"};
                for (int i = 0; i < 2; i++) {
                    int k = i;
                    chip(sq, sqn[i], prefs.getInt("squareAction", 0) == i, v -> { prefs.edit().putInt("squareAction", k).apply(); pop.win.dismiss(); actionsMenu(anchor, 4); });
                }
                pop.add(sq);
                pop.toggle("Two-finger tap: undo", canvas.gUndo, v -> { canvas.gUndo = v; prefs.edit().putBoolean("gUndo", v).apply(); });
                pop.toggle("Three-finger tap: redo", canvas.gRedo, v -> { canvas.gRedo = v; prefs.edit().putBoolean("gRedo", v).apply(); });
                pop.toggle("Three-finger scrub: clear layer", canvas.gClear, v -> { canvas.gClear = v; prefs.edit().putBoolean("gClear", v).apply(); });
                pop.toggle("Three-finger swipe down: copy & paste", canvas.gCopy, v -> { canvas.gCopy = v; prefs.edit().putBoolean("gCopy", v).apply(); });
                pop.toggle("Four-finger tap: full screen", canvas.gFull, v -> { canvas.gFull = v; prefs.edit().putBoolean("gFull", v).apply(); });
                pop.add(slider("Rapid undo delay (ms)", canvas.undoHoldDelay, 200, 1500, v -> { canvas.undoHoldDelay = v; prefs.edit().putInt("undoDelay", v).apply(); }));
                pop.section("Selection");
                pop.add(slider("Selection mask visibility %", Math.round(canvas.selVisibility * 100), 0, 200, v -> { canvas.selVisibility = v / 100f; prefs.edit().putFloat("selVis", canvas.selVisibility).apply(); canvas.invalidate(); }));
                pop.item("Import brushes", true, () -> action(6, anchor));
                break;
            default: // Help
                pop.item("Gesture controls", true, () -> action(8, anchor));
                pop.item("About Inkwell", true, () -> new AlertDialog.Builder(this).setTitle("Inkwell")
                        .setMessage("A tiny greyscale drawing app. No libraries, plain Java.").setPositiveButton("OK", null).show());
        }
        pop.show(anchor);
    }

    private Uri photoUri;

    /** Camera photo inserted as a new layer (saved to Pictures/Inkwell first; no extra permission needed). */
    private void takePhoto() {
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "inkwell_photo_" + System.currentTimeMillis() + ".jpg");
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Inkwell");
            photoUri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            startActivityForResult(new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri), REQ_CAMERA);
        } catch (Exception e) { hud("No camera app available"); }
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

    /** Android's photo picker (like Procreate's Photo button); falls back to the file browser on older phones. */
    private void pickPhoto(int req) {
        Intent i = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*");
        try { startActivityForResult(i, req); }
        catch (Exception e) { startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), req); }
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
        java.util.List<CanvasView.Brush> recentAtOpen = recentBrushes(); // the Recent list keeps its order until the library closes
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
            java.util.List<String> setNames = new java.util.ArrayList<>();
            if (!recentAtOpen.isEmpty()) setNames.add("Recent");
            setNames.addAll(java.util.Arrays.asList(CanvasView.SETS));
            for (String set : setNames) {
                boolean any = set.equals("Recent");
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
                st.setOnLongClickListener(v -> { // export a whole set
                    java.util.List<CanvasView.Brush> bs = set.equals("Recent") ? new java.util.ArrayList<>(recentAtOpen) : new java.util.ArrayList<>();
                    if (!set.equals("Recent")) for (CanvasView.Brush b : CanvasView.BRUSHES) if (b.set.equals(set)) bs.add(b);
                    if (bs.isEmpty()) return true;
                    Ui.Pop sp = new Ui.Pop(this, 220);
                    sp.item("Export set \"" + set + "\"", true, () -> { pop.win.dismiss(); exportBrushFile(bs, set + ".inkbrushset"); });
                    sp.show(v);
                    return true;
                });
                sets.addView(st);
            }
            boolean recent = "Recent".equals(libSet);
            java.util.List<CanvasView.Brush> shown = new java.util.ArrayList<>();
            if (recent) shown.addAll(recentAtOpen);
            else for (CanvasView.Brush br : CanvasView.BRUSHES) if (br.set.equals(libSet)) shown.add(br);
            for (CanvasView.Brush br : shown) {
                LinearLayout outer = new LinearLayout(this);
                outer.setGravity(Gravity.CENTER_VERTICAL);
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
                    switchBrush(br);
                    noteRecent(br);
                    fill[0].run();
                });
                row.setOnLongClickListener(v -> { brushMenu(v, br, pop); return true; });
                outer.addView(row, new LinearLayout.LayoutParams(0, -2, 1f));
                if (!recent) { // drag the handle to reorder brushes in the set
                    TextView h = new TextView(this);
                    h.setText("≡");
                    h.setTextSize(22);
                    h.setTextColor(0xFF8E8E93);
                    h.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
                    h.setContentDescription("Drag to reorder");
                    float[] y0 = new float[1];
                    h.setOnTouchListener((v, e) -> {
                        switch (e.getActionMasked()) {
                            case android.view.MotionEvent.ACTION_DOWN:
                                y0[0] = e.getRawY(); outer.setElevation(10 * dp); outer.setAlpha(0.85f);
                                v.getParent().requestDisallowInterceptTouchEvent(true);
                                return true;
                            case android.view.MotionEvent.ACTION_MOVE: outer.setTranslationY(e.getRawY() - y0[0]); return true;
                            default:
                                outer.setTranslationY(0); outer.setElevation(0); outer.setAlpha(1);
                                if (e.getActionMasked() != android.view.MotionEvent.ACTION_UP) return true;
                                CanvasView.Brush target = null;
                                for (int c = 0; c < list.getChildCount(); c++) {
                                    View ch = list.getChildAt(c);
                                    int[] loc = new int[2];
                                    ch.getLocationOnScreen(loc);
                                    if (e.getRawY() >= loc[1] && e.getRawY() < loc[1] + ch.getHeight()) target = shown.get(c);
                                }
                                if (target != null && target != br) {
                                    int ti = CanvasView.BRUSHES.indexOf(target);
                                    CanvasView.BRUSHES.remove(br);
                                    CanvasView.BRUSHES.add(Math.min(CanvasView.BRUSHES.size(), ti), br);
                                    saveBrushOrder();
                                    fill[0].run();
                                }
                                return true;
                        }
                    });
                    outer.addView(h);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(0, 2, (int) (8 * dp), 2);
                list.addView(outer, lp);
            }
        };
        fill[0].run();
        cols.addView(sets, new LinearLayout.LayoutParams((int) (130 * dp), -2));
        cols.addView(list, new LinearLayout.LayoutParams(0, -2, 1f));
        pop.add(cols);
        pop.show(brushBtn);
    }

    // ---- Recent brushes, brush order, brush export ----

    private java.util.List<CanvasView.Brush> recentBrushes() {
        java.util.List<CanvasView.Brush> r = new java.util.ArrayList<>();
        for (String n : prefs.getString("recent", "").split("\n"))
            for (CanvasView.Brush b : CanvasView.BRUSHES) if (b.name.equals(n)) { r.add(b); break; }
        return r;
    }

    private void noteRecent(CanvasView.Brush br) {
        StringBuilder sb = new StringBuilder(br.name);
        int n = 1;
        for (CanvasView.Brush b : recentBrushes()) if (b != br && n++ < 10) sb.append('\n').append(b.name);
        prefs.edit().putString("recent", sb.toString()).apply();
    }

    private void saveBrushOrder() {
        StringBuilder sb = new StringBuilder();
        for (CanvasView.Brush b : CanvasView.BRUSHES) sb.append(b.name).append('\n');
        prefs.edit().putString("brushOrder", sb.toString()).apply();
    }

    /** Puts brushes back in the order the user dragged them into (new ones keep their place at the end). */
    private void applyBrushOrder() {
        java.util.List<String> order = java.util.Arrays.asList(prefs.getString("brushOrder", "").split("\n"));
        if (order.size() < 2) return;
        java.util.List<CanvasView.Brush> copy = new java.util.ArrayList<>(CanvasView.BRUSHES);
        copy.sort((a, b) -> {
            int ia = order.indexOf(a.name), ib = order.indexOf(b.name);
            if (ia < 0) ia = 100000 + CanvasView.BRUSHES.indexOf(a);
            if (ib < 0) ib = 100000 + CanvasView.BRUSHES.indexOf(b);
            return Integer.compare(ia, ib);
        });
        CanvasView.BRUSHES.clear();
        CanvasView.BRUSHES.addAll(copy);
    }

    private java.util.List<CanvasView.Brush> exportBrushes;

    private void exportBrushFile(java.util.List<CanvasView.Brush> bs, String name) {
        exportBrushes = bs;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, name.replaceAll("[/\\\\:]", "_")), REQ_BRUSH_EXPORT);
    }

    /** Long-press a brush: duplicate / reset / delete. */
    private void brushMenu(View anchor, CanvasView.Brush br, Ui.Pop lib) {
        Ui.Pop pop = new Ui.Pop(this, 240);
        pop.item("Edit in Brush Studio", true, () -> { lib.win.dismiss(); switchBrush(br); brushStudio(br); });
        pop.item("Export brush", true, () -> { lib.win.dismiss(); exportBrushFile(java.util.Collections.singletonList(br), br.name + ".inkbrush"); });
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
        {{"Stabilization"}, {"streamline", "StreamLine (smoothing)", 0f, 1f, "%"}, {"stabilize", "Stabilization", 0f, 1f, "%"},
                {"motionFilter", "Motion filtering (removes shake, no lag)", 0f, 1f, "%"}},
        {{"Taper"}, {"taperStart", "Start taper", 0f, 1f, "%"}, {"taperEnd", "End taper", 0f, 1f, "%"}},
        {{"Shape"}, {"roundness", "Roundness", 0.05f, 1f, "%"}, {"angle", "Angle", 0f, 180f, "°"},
                {"follow", "Rotate with stroke", 0f, 1f, "toggle"}, {"hard", "Hardness", 0f, 1f, "%"}},
        {{"Grain"}, {"grain", "Grain depth", 0f, 1f, "%"}, {"grainScale", "Grain scale", 0.25f, 4f, "×"}},
        {{"Dynamics"}, {"minSize", "Pressure → size", 0f, 1f, "inv%"}, {"minAlpha", "Pressure → opacity", 0f, 1f, "inv%"},
                {"curve", "Pressure curve (soft … firm)", 0.3f, 3f, "×"},
                {"tiltSize", "Tilt → size (pen on its side)", 0f, 1f, "%"}, {"tiltOpacity", "Tilt → lighter", 0f, 1f, "%"},
                {"speedSize", "Speed → thinner (fast strokes)", 0f, 1f, "%"}, {"speedOpacity", "Speed → lighter (fast strokes)", 0f, 1f, "%"}},
        {{"Rendering"}, {"render", "Rendering mode", 0f, 1f, "choice:Light glaze|Heavy glaze"},
                {"blend", "Blend mode", 0f, 5f, "choice:Normal|Multiply|Darken|Lighten|Screen|Overlay"},
                {"pattern", "Halftone grid (dots fixed to the canvas)", 0f, 1f, "choice:Off|On"},
                {"patternScale", "Halftone spacing", 3f, 60f, "px"}, {"patternDot", "Halftone dot size", 0.1f, 0.7f, "%"}},
        {{"Properties"}, {"sizeScale", "Maximum size", 0.1f, 3f, "×"}, {"alpha", "Maximum opacity", 0.05f, 1f, "%"}},
        {{"About this brush"}},
    };

    private FrameLayout studio;
    private int studioTab;
    private CanvasView.Brush studioBrush; // brush waiting for a shape/grain picture
    private Runnable studioRedraw;

    private void brushStudio(CanvasView.Brush b) {
        float[] before = b.values();
        String beforeName = b.name, beforeSet = b.set;
        byte[][] beforeTip = b.tip == null ? null : new byte[b.tip.length][];
        if (beforeTip != null) for (int i = 0; i < b.tip.length; i++) beforeTip[i] = b.tip[i].clone();
        int[] beforeTw = b.tw, beforeTh = b.th;
        byte[] beforeTex = b.tex == null ? null : b.tex.clone();
        int beforeGw = b.gw, beforeGh = b.gh;
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
            b.tip = beforeTip; b.tw = beforeTw; b.th = beforeTh; b.tex = beforeTex; b.gw = beforeGw; b.gh = beforeGh; // shape/grain too
            previews.remove(b);
            closeStudio();
        });
        chip(head, "Done", true, v -> { previews.remove(b); saveBrush(b); closeStudio(); });
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
                if (fmt.startsWith("choice:")) { // a row of chips, one per option
                    String[] opts = fmt.substring(7).split("\\|");
                    TextView cl = new TextView(this);
                    cl.setText(label);
                    cl.setTextColor(0xFFE8E8E8);
                    cl.setTextSize(14);
                    cl.setPadding(0, (int) (10 * dp), 0, (int) (4 * dp));
                    settings.addView(cl);
                    LinearLayout cr = barRow();
                    TextView[] cs = new TextView[opts.length];
                    for (int k = 0; k < opts.length; k++) {
                        int kk = k;
                        cs[k] = chip(cr, opts[k], Math.round(b.get(key)) == k, v -> {
                            b.put(key, kk);
                            for (int j = 0; j < cs.length; j++) cs[j].setBackgroundColor(j == kk ? Ui.ACCENT : 0);
                            redraw.run();
                        });
                    }
                    HorizontalScrollView hs = new HorizontalScrollView(this);
                    hs.addView(cr);
                    settings.addView(hs);
                    continue;
                }
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
                    String shown = fmt.equals("px") ? Math.round(v) + " px" : fmt.equals("°") ? Math.round(v) + "°" : fmt.equals("×") ? String.format("%.2f×", v) : Math.round(v * 100) + "%";
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
            String secName = (String) sec[0][0];
            if (secName.equals("Shape") || secName.equals("Grain")) { // Shape source / Grain source, like Procreate
                boolean shape = secName.equals("Shape");
                LinearLayout src = barRow();
                chip(src, shape ? "Import shape image…" : "Import grain image…", false, v -> {
                    studioBrush = b; studioRedraw = () -> { previews.remove(b); show[0].run(); redraw.run(); };
                    pickPhoto(shape ? REQ_SHAPE : REQ_GRAIN);
                });
                chip(src, "Invert", false, v -> {
                    if (shape && b.tip != null) { for (byte[] lv : b.tip) Brushes.invert(lv); }
                    else if (!shape) Brushes.invert(b.tex);
                    previews.remove(b); redraw.run();
                });
                chip(src, shape ? "Round tip" : "No grain", false, v -> {
                    if (shape) { b.tip = null; b.tw = null; b.th = null; } else { b.tex = null; b.grain = 0; }
                    previews.remove(b); show[0].run(); redraw.run();
                });
                HorizontalScrollView hs = new HorizontalScrollView(this);
                hs.addView(src);
                settings.addView(hs);
                TextView note = new TextView(this);
                note.setText(shape ? "Any picture works: white (or dark marks on paper) becomes paint." : "Any picture works as paper texture: light areas let paint through.");
                note.setTextColor(0xFF8E8E93);
                settings.addView(note);
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

    private void toast(String s) { hud(s); }

    private TextView hudView;

    /** Small banner at the top of the canvas, like Procreate's notices. */
    private void hud(String msg) {
        if (stage == null) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); return; }
        if (hudView == null) {
            hudView = new TextView(this);
            hudView.setTextColor(Color.WHITE);
            hudView.setTextSize(14);
            hudView.setPadding((int) (14 * dp), (int) (6 * dp), (int) (14 * dp), (int) (6 * dp));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xE62C2C2E);
            bg.setCornerRadius(16 * dp);
            hudView.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = (int) (10 * dp);
            stage.addView(hudView, lp);
        }
        hudView.setText(msg);
        hudView.setVisibility(View.VISIBLE);
        hudView.bringToFront();
        hudView.removeCallbacks(hideHud);
        hudView.postDelayed(hideHud, msg.length() > 40 ? 2600 : 1300);
    }

    private final Runnable hideHud = () -> { if (hudView != null) hudView.setVisibility(View.GONE); };

    private void full() { toast("Layer limit reached for this canvas size"); }

    /** Tapping the canvas closes open menus and the Layers panel, like Procreate. Returns true if something closed. */
    // ---------- QuickMenu (like Procreate): 6 shortcuts in a ring; long-press a slot to change it ----------

    private static final String[] QM_NAMES = {"Undo", "Redo", "New layer", "Duplicate layer", "Merge down", "Clear layer",
            "Flip canvas ↔", "Flip canvas ↕", "Copy & Paste", "Paste", "Select layer", "Invert selection", "Fill layer",
            "Hide/show layer", "Fit to screen", "Eyedropper", "Full screen", "Reference"};
    private android.widget.PopupWindow qmWin;

    /** Slot contents: an action number, "b:<brush>" (paint with that brush) or "e:<brush>" (erase with it). */
    private String[] qmSlots() {
        String saved = prefs.getString("qm2", null);
        String[] p = saved != null ? saved.split("\n", -1) : prefs.getString("qm", "2,4,5,6,8,15").split(",");
        String[] r = new String[6];
        for (int i = 0; i < 6; i++) r[i] = i < p.length && !p[i].isEmpty() ? p[i] : String.valueOf(i);
        return r;
    }

    private void saveQmSlots(String[] slots) { prefs.edit().putString("qm2", String.join("\n", slots)).apply(); }

    private CanvasView.Brush brushNamed(String n) {
        for (CanvasView.Brush b : CanvasView.BRUSHES) if (b.name.equals(n)) return b;
        return null;
    }

    private String qmLabel(String tok) {
        if (tok.startsWith("b:")) return tok.substring(2);
        if (tok.startsWith("e:")) return tok.substring(2) + "\n(eraser)";
        try { return QM_NAMES[Integer.parseInt(tok)]; } catch (Exception e) { return "?"; }
    }

    private void qmRun(String tok) {
        if (tok.startsWith("b:") || tok.startsWith("e:")) { // switch to that brush for painting or erasing
            CanvasView.Brush b = brushNamed(tok.substring(2));
            if (b == null) { hud("That brush no longer exists"); return; }
            int tool = tok.startsWith("b:") ? CanvasView.DRAW : CanvasView.ERASE;
            setTool(tool);
            switchBrush(b);
            noteRecent(b);
            hud((tool == CanvasView.DRAW ? "Brush: " : "Eraser: ") + b.name);
            return;
        }
        try { qmRun(Integer.parseInt(tok)); } catch (NumberFormatException ignored) { }
    }

    /** Pick a brush for a QuickMenu slot (grouped by set). */
    private void qmPickBrush(View anchor, boolean eraser, java.util.function.Consumer<String> done) {
        Ui.Pop pop = new Ui.Pop(this, 260);
        pop.title(eraser ? "Erase with…" : "Paint with…");
        java.util.List<String> sets = new java.util.ArrayList<>(java.util.Arrays.asList(CanvasView.SETS));
        for (String set : sets) {
            boolean any = false;
            for (CanvasView.Brush b : CanvasView.BRUSHES) if (b.set.equals(set)) any = true;
            if (!any) continue;
            pop.section(set);
            for (CanvasView.Brush b : CanvasView.BRUSHES) if (b.set.equals(set)) pop.item(b.name, true, () -> done.accept((eraser ? "e:" : "b:") + b.name));
        }
        pop.show(anchor);
    }

    private void qmRun(int id) {
        switch (id) {
            case 0: canvas.undo(); break;
            case 1: canvas.redo(); break;
            case 2: if (!canvas.addLayer(canvas.newLayer("Layer " + (canvas.layers.size() + 1)))) full(); break;
            case 3: if (!canvas.duplicate()) full(); break;
            case 4: canvas.mergeDown(); break;
            case 5: canvas.clearLayer(); break;
            case 6: canvas.flipView(true); break;
            case 7: canvas.flipView(false); break;
            case 8: if (!canvas.copyPaste(false)) full(); break;
            case 9: if (!canvas.paste()) full(); break;
            case 10: canvas.selectLayer(); break;
            case 11: canvas.invertSelection(); break;
            case 12: canvas.fillLayer(); break;
            case 13: canvas.toggleVisible(canvas.current); break;
            case 14: canvas.animateFit(); break;
            case 15: canvas.pickNext = true; pickBtn.setOn(true); hud("Tap the canvas to pick a grey"); break;
            case 16: if (canvas.onToggleUi != null) canvas.onToggleUi.run(); break;
            case 17: toggleReference(); break;
        }
        refreshPanel();
    }

    /** Shows the QuickMenu centred on a screen point. */
    void quickMenu(float x, float y) {
        if (qmWin != null) qmWin.dismiss();
        int size = (int) (230 * dp), btn = (int) (78 * dp);
        FrameLayout ring = new FrameLayout(this);
        String[] slots = qmSlots();
        for (int i = 0; i < 6; i++) {
            int k = i;
            TextView t = new TextView(this);
            t.setText(qmLabel(slots[i]));
            t.setTextColor(Color.WHITE);
            t.setTextSize(12);
            t.setGravity(Gravity.CENTER);
            t.setPadding((int) (4 * dp), 0, (int) (4 * dp), 0);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(0xF02C2C2E);
            bg.setStroke((int) (1.5f * dp), 0xFF636366);
            t.setBackground(bg);
            double a = -Math.PI / 2 + i * Math.PI / 3;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(btn, btn);
            lp.leftMargin = (int) (size / 2f + Math.cos(a) * (size / 2f - btn / 2f) - btn / 2f);
            lp.topMargin = (int) (size / 2f + Math.sin(a) * (size / 2f - btn / 2f) - btn / 2f);
            t.setOnClickListener(v -> { qmWin.dismiss(); qmRun(slots[k]); });
            t.setOnLongClickListener(v -> { // choose what this slot does: an action, a brush or an eraser
                java.util.function.Consumer<String> set = tok -> { slots[k] = tok; saveQmSlots(slots); ((TextView) v).setText(qmLabel(tok)); };
                Ui.PopMenu m = new Ui.PopMenu(this, v);
                m.getMenu().add(0, 1000, 0, "A brush…");
                m.getMenu().add(0, 1001, 1, "An eraser…");
                for (int j = 0; j < QM_NAMES.length; j++) m.getMenu().add(0, j, j + 2, QM_NAMES[j]);
                m.setOnMenuItemClickListener(it -> {
                    int id = it.getItemId();
                    if (id >= 1000) qmPickBrush(v, id == 1001, set);
                    else set.accept(String.valueOf(id));
                    return true;
                });
                m.show();
                return true;
            });
            ring.addView(t, lp);
        }
        TextView hint = new TextView(this);
        hint.setText("hold to\nchange");
        hint.setTextColor(0xFF8E8E93);
        hint.setTextSize(10);
        hint.setGravity(Gravity.CENTER);
        ring.addView(hint, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        qmWin = new android.widget.PopupWindow(ring, size, size, true);
        qmWin.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        qmWin.setOutsideTouchable(true);
        Rect scr = screenBounds();
        int px = (int) Math.max(0, Math.min(scr.width() - size, x - size / 2f));
        int py = (int) Math.max(0, Math.min(scr.height() - size, y - size / 2f));
        try { qmWin.showAtLocation(canvas, Gravity.NO_GRAVITY, px, py); } catch (Exception e) { /* window not ready */ }
    }

    private boolean closePanels() {
        boolean closed = false;
        if (qmWin != null && qmWin.isShowing()) { qmWin.dismiss(); closed = true; }
        for (Ui.Pop p : new java.util.ArrayList<>(Ui.Pop.open)) // only this screen's popovers
            if (p.c == this && p.win.isShowing()) { p.win.dismiss(); closed = true; }
        Ui.Pop.open.removeIf(p -> !p.win.isShowing());
        if (panel != null && panel.getVisibility() == View.VISIBLE) {
            swiped = null;
            panel.setVisibility(View.GONE);
            layersBtn.setOn(false);
            closed = true;
        }
        return closed;
    }

    private void togglePanel() {
        swiped = null;
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
        if (canvas.anim || canvas.pages) refreshTimeline();
        if (panel == null || panel.getVisibility() != View.VISIBLE) return;
        layerList.removeAllViews();
        if (swiped != null && !canvas.layers.contains(swiped)) swiped = null;
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
            r.setBackgroundColor(multi.contains(l) ? 0xFF2F5F95 : i == canvas.current ? 0xFF48484A : 0);
            r.setTag(idx);
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
            name.setText((l.clip ? "↳ " : "") + l.name + (l.alphaLock ? "  α" : "") + (l.locked ? "  🔒" : "") + (l.reference ? "  REF" : "") + (l.text != null ? "  T" : "") + (l.assist ? "  · Assisted" : ""));
            name.setTextColor(Color.WHITE);
            name.setSingleLine(true);
            name.setPadding((int) (8 * dp), 0, 0, 0);
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView bl = new TextView(this);
            bl.setText(CanvasView.BLEND_NAMES[l.blend].substring(0, 1));
            bl.setTextColor(l.blend == 0 ? 0xFF9A9A9E : Ui.ACCENT);
            bl.setPadding((int) (8 * dp), (int) (8 * dp), (int) (4 * dp), (int) (8 * dp));
            bl.setOnClickListener(v -> blendMenu(v, idx));
            TextView vis = new TextView(this);
            vis.setText(l.visible ? "●" : "○");
            vis.setTextSize(16);
            vis.setTextColor(l.visible ? Color.WHITE : 0xFF888888);
            vis.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
            vis.setOnClickListener(v -> canvas.toggleVisible(idx));
            if (l == swiped) { // swiped left: Lock / Duplicate / Delete buttons inside the row, like Procreate
                swipeButton(r, l.locked ? "Unlock" : "Lock", 0xFF636366, () -> canvas.toggleLock(idx));
                swipeButton(r, "Duplicate", 0xFF636366, () -> { canvas.select(idx); if (!canvas.duplicate()) full(); });
                if (canvas.layers.size() > 1) swipeButton(r, "Delete", 0xFFD9443B, () -> { canvas.select(idx); canvas.deleteLayer(); });
            } else { r.addView(bl); r.addView(vis); }
            r.setOnClickListener(v -> {
                if (swiped != null) { swiped = null; refreshPanel(); return; } // tap anywhere closes the buttons
                if (idx == canvas.current) layerOptions(v, idx); else canvas.select(idx);
            });
            View.OnTouchListener g = rowGestures(r, l, idx);
            for (int c = 0; c < r.getChildCount(); c++) if (r.getChildAt(c).isClickable()) r.getChildAt(c).setOnTouchListener(g);
            r.setOnTouchListener(g);
            layerList.addView(r);
        }
        backgroundRow();
        if (!multi.isEmpty()) { // multi-select actions (swipe right on layers to select)
            LinearLayout mb = barRow();
            TextView t = new TextView(this);
            t.setText(multi.size() + " selected  ");
            t.setTextColor(Color.WHITE);
            mb.addView(t);
            chip(mb, "Group", false, v -> { canvas.groupLayers(new java.util.ArrayList<>(multi)); multi.clear(); refreshPanel(); });
            chip(mb, "Delete", false, v -> { canvas.deleteLayers(new java.util.ArrayList<>(multi)); multi.clear(); refreshPanel(); }); // undoable
            chip(mb, "Clear", false, v -> { multi.clear(); refreshPanel(); });
            layerList.addView(mb, 0);
        }
    }

    private final java.util.LinkedHashSet<CanvasView.Layer> multi = new java.util.LinkedHashSet<>();

    /** Layer row gestures like Procreate: long-press drag to reorder, swipe left for actions, swipe right to select. */
    private View.OnTouchListener rowGestures(View row, CanvasView.Layer l, int idx) {
        float[] st = new float[3]; // downX, downY, dragging(1)
        Runnable startDrag = () -> {
            st[2] = 1;
            row.setElevation(12 * dp);
            row.setAlpha(0.85f);
            row.getParent().requestDisallowInterceptTouchEvent(true);
        };
        float slop = 6 * dp; // below the list's own scroll slop, so we decide first
        return (v, e) -> {
            float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1];
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX(); st[1] = e.getRawY(); st[2] = 0;
                    row.removeCallbacks(startDrag);
                    row.postDelayed(startDrag, 380);
                    return false;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (st[2] == 1) { row.setTranslationY(dy); return true; }
                    if (st[2] == 0 && Math.hypot(dx, dy) > slop) {
                        row.removeCallbacks(startDrag);
                        if (Math.abs(dx) > Math.abs(dy)) { // sideways: this is a swipe, stop the list from scrolling
                            st[2] = 2;
                            row.getParent().requestDisallowInterceptTouchEvent(true);
                            v.setPressed(false);
                        } else st[2] = 3; // up/down: let the list scroll
                    }
                    if (st[2] == 2) { row.setTranslationX(Math.max(-120 * dp, Math.min(120 * dp, dx))); return true; }
                    return false;
                case android.view.MotionEvent.ACTION_UP:
                    row.removeCallbacks(startDrag);
                    if (st[2] == 1) { drop(row, idx, e.getRawY()); return true; }
                    if (st[2] == 2) {
                        row.setTranslationX(0);
                        if (dx < -48 * dp) layerSwipeActions(row, l, idx); // swipe left: Lock / Duplicate / Delete
                        else if (dx > 48 * dp) { // swipe right: close the buttons, or select
                            if (swiped != null) swiped = null; else if (!multi.remove(l)) multi.add(l);
                            refreshPanel();
                        }
                        return true;
                    }
                    return false;
                default: // cancel
                    row.removeCallbacks(startDrag);
                    row.setTranslationX(0); row.setTranslationY(0); row.setElevation(0); row.setAlpha(1);
                    st[2] = 0;
                    return false;
            }
        };
    }

    /** Ends a long-press drag: moves the layer to the row under the finger. */
    private void drop(View row, int idx, float y) {
        int target = idx;
        for (int c = 0; c < layerList.getChildCount(); c++) {
            View ch = layerList.getChildAt(c);
            if (!(ch.getTag() instanceof Integer) || ch == row) continue;
            int[] loc = new int[2];
            ch.getLocationOnScreen(loc);
            if (y >= loc[1] && y < loc[1] + ch.getHeight()) target = (Integer) ch.getTag();
        }
        row.setTranslationY(0);
        row.setElevation(0);
        row.setAlpha(1);
        canvas.moveLayerTo(idx, target);
    }

    private CanvasView.Layer swiped; // layer whose row shows Lock / Duplicate / Delete

    private void layerSwipeActions(View row, CanvasView.Layer l, int idx) {
        swiped = l;
        refreshPanel();
    }

    private void swipeButton(LinearLayout r, String label, int color, Runnable action) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(color);
        b.setPadding((int) (10 * dp), (int) (12 * dp), (int) (10 * dp), (int) (12 * dp));
        b.setOnClickListener(v -> { swiped = null; action.run(); refreshPanel(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
        lp.leftMargin = (int) (2 * dp);
        r.addView(b, lp);
    }

    /** Pinch two layers together in the list to merge them and everything between. */
    /**
     * Two fingers on the layer list (like Procreate): pinch two rows together = merge them and everything between;
     * two-finger swipe right on one row = Alpha Lock; two-finger hold on one row = select that layer's contents.
     */
    private boolean pinchMerge(android.view.MotionEvent e) {
        if (e.getPointerCount() < 2) { pinchA = -1; layerList.removeCallbacks(twoHold); return false; }
        int act = e.getActionMasked();
        if (act == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            pinchA = rowAt(e.getRawY(0));
            pinchB = rowAt(e.getRawY(1));
            pinchD = Math.abs(e.getY(1) - e.getY(0));
            twoX = (e.getX(0) + e.getX(1)) / 2; twoY = (e.getY(0) + e.getY(1)) / 2;
            twoDone = false;
            layerList.removeCallbacks(twoHold);
            if (pinchA >= 0 && pinchA == pinchB) layerList.postDelayed(twoHold, 500);
            return true;
        }
        if (act == android.view.MotionEvent.ACTION_MOVE && pinchA >= 0) {
            float cx = (e.getX(0) + e.getX(1)) / 2, cy = (e.getY(0) + e.getY(1)) / 2;
            if (Math.hypot(cx - twoX, cy - twoY) > 10 * dp) layerList.removeCallbacks(twoHold);
            if (!twoDone && pinchA == pinchB && cx - twoX > 48 * dp && Math.abs(cy - twoY) < 30 * dp) {
                twoDone = true;
                canvas.toggleAlphaLock(pinchA);
                hud(canvas.layers.get(pinchA).alphaLock ? "Alpha Lock on" : "Alpha Lock off");
                refreshPanel();
            }
            return true;
        }
        if (act == android.view.MotionEvent.ACTION_POINTER_UP && pinchA >= 0) {
            layerList.removeCallbacks(twoHold);
            float d = Math.abs(e.getY(1) - e.getY(0));
            if (!twoDone && pinchB >= 0 && pinchA != pinchB && d < pinchD * 0.6f) { canvas.mergeRange(pinchA, pinchB); hud("Layers merged"); }
            pinchA = -1;
            return true;
        }
        return pinchA >= 0;
    }

    private float twoX, twoY;
    private boolean twoDone;
    private final Runnable twoHold = () -> {
        if (this.pinchA < 0 || twoDone) return;
        twoDone = true;
        canvas.select(this.pinchA);
        canvas.selectLayer();
        hud("Selected the layer's contents");
        refreshPanel();
    };

    private int pinchA = -1, pinchB = -1;
    private float pinchD;

    private int rowAt(float rawY) {
        for (int c = 0; c < layerList.getChildCount(); c++) {
            View ch = layerList.getChildAt(c);
            if (!(ch.getTag() instanceof Integer)) continue;
            int[] loc = new int[2];
            ch.getLocationOnScreen(loc);
            if (rawY >= loc[1] && rawY < loc[1] + ch.getHeight()) return (Integer) ch.getTag();
        }
        return -1;
    }

    /** The "Background colour" row at the bottom of the Layers panel, like Procreate. */
    private void backgroundRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
        View sw = new View(this);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(Color.rgb(canvas.bgGrey, canvas.bgGrey, canvas.bgGrey));
        d.setStroke((int) (2 * dp), 0xFF636366);
        sw.setBackground(d);
        sw.setAlpha(canvas.bgVisible ? 1f : 0.35f);
        r.addView(sw, new LinearLayout.LayoutParams((int) (44 * dp), (int) (32 * dp)));
        TextView n = new TextView(this);
        n.setText("Background colour");
        n.setTextColor(Color.WHITE);
        n.setPadding((int) (8 * dp), 0, 0, 0);
        r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView vis = new TextView(this);
        vis.setText(canvas.bgVisible ? "●" : "○");
        vis.setTextSize(16);
        vis.setTextColor(canvas.bgVisible ? Color.WHITE : 0xFF888888);
        vis.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
        vis.setOnClickListener(v -> canvas.setBackground(canvas.bgGrey, !canvas.bgVisible));
        r.addView(vis);
        r.setOnClickListener(this::pickBackground);
        layerList.addView(r);
    }

    /** Grey picker for the background colour. */
    private void pickBackground(View anchor) {
        int before = canvas.bgGrey;
        Ui.Pop pop = new Ui.Pop(this, 300);
        pop.title("Background colour");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (14 * dp);
        box.setPadding(p, 0, p, p);
        SeekBar sb = new SeekBar(this);
        sb.setMax(255);
        sb.setProgress(canvas.bgGrey);
        sb.setOnSeekBarChangeListener(new Slider() {
            public void onProgressChanged(SeekBar s2, int v, boolean u) { canvas.bgGrey = v; canvas.bgVisible = true; canvas.invalidate(); }
        });
        box.addView(sb, new LinearLayout.LayoutParams(-1, (int) (44 * dp)));
        LinearLayout sw = new LinearLayout(this);
        for (int g : new int[]{0, 32, 64, 96, 128, 160, 192, 224, 255}) {
            View v = new View(this);
            android.graphics.drawable.GradientDrawable dr = new android.graphics.drawable.GradientDrawable();
            dr.setColor(Color.rgb(g, g, g));
            dr.setCornerRadius(6 * dp);
            dr.setStroke(1, 0xFF636366);
            v.setBackground(dr);
            v.setOnClickListener(x -> sb.setProgress(g));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (26 * dp), (int) (26 * dp));
            lp.setMargins(2, 2, (int) (4 * dp), 2);
            sw.addView(v, lp);
        }
        box.addView(sw);
        pop.add(box);
        pop.win.setOnDismissListener(() -> { // one undo step for the whole change
            int after = canvas.bgGrey;
            canvas.bgGrey = before;
            canvas.setBackground(after, true);
            refreshPanel();
        });
        pop.show(anchor);
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
    private int animMode; // 0 loop, 1 ping-pong, 2 one shot
    private int playPos;

    /** Frames in play order, each repeated for its hold, following the playback mode. */
    private java.util.List<Integer> animSequence() {
        java.util.ArrayList<Integer> seq = new java.util.ArrayList<>();
        int f0 = canvas.firstFrame();
        for (int i = f0; i < canvas.layers.size(); i++) for (int h = 0; h < Math.max(1, canvas.layers.get(i).hold); h++) seq.add(i);
        if (animMode == 1 && canvas.layers.size() - f0 > 2) { // ping-pong: back again without repeating the ends
            for (int i = canvas.layers.size() - 2; i > f0; i--) for (int h = 0; h < Math.max(1, canvas.layers.get(i).hold); h++) seq.add(i);
        }
        return seq;
    }

    private final Runnable playTick = new Runnable() {
        public void run() {
            if (!canvas.playing) return;
            java.util.List<Integer> seq = animSequence();
            if (seq.isEmpty()) return;
            if (playPos >= seq.size()) {
                if (animMode == 2) { togglePlay(); return; } // one shot: stop at the end
                playPos = 0;
            }
            canvas.playFrame = seq.get(playPos++);
            canvas.invalidate();
            handler.postDelayed(this, 1000 / Math.max(1, canvas.fps));
        }
    };

    private boolean playing() { return canvas.playing; }

    private void togglePlay() {
        canvas.playing = !canvas.playing;
        if (canvas.playing) { playPos = 0; canvas.playFrame = canvas.current; handler.post(playTick); }
        canvas.invalidate();
        refreshTimeline();
    }

    /** Page Assist (like Procreate): each layer is a page; flip through them; export all as one PDF. */
    private void togglePages() {
        endBarModes();
        if (canvas.anim) toggleAnimation();
        canvas.pages = !canvas.pages;
        timeline.setVisibility(canvas.pages ? View.VISIBLE : View.GONE);
        if (canvas.pages) refreshTimeline();
        canvas.invalidate();
    }

    private void pagesBar() {
        LinearLayout ctl = barRow();
        int f0 = canvas.firstFrame(), n = canvas.layers.size() - f0, cur = Math.max(0, canvas.current - f0);
        chip(ctl, "‹", false, v -> { if (canvas.current > f0) canvas.select(canvas.current - 1); });
        TextView pn = new TextView(this);
        pn.setText("  Page " + (cur + 1) + " of " + n + "  ");
        pn.setTextColor(Color.WHITE);
        ctl.addView(pn);
        chip(ctl, "›", false, v -> { if (canvas.current < canvas.layers.size() - 1) canvas.select(canvas.current + 1); });
        chip(ctl, "＋ Page", false, v -> { if (!canvas.addLayer(canvas.newLayer("Page " + (n + 1)))) full(); });
        chip(ctl, (canvas.animBackground ? "✓ " : "") + "Background layer", canvas.animBackground, v -> { canvas.animBackground = !canvas.animBackground; refreshTimeline(); canvas.invalidate(); });
        chip(ctl, "Export PDF", false, v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf").putExtra(Intent.EXTRA_TITLE, nameOf(curId) + ".pdf"), REQ_PAGES_PDF));
        HorizontalScrollView h1 = new HorizontalScrollView(this);
        h1.addView(ctl);
        timeline.addView(h1);
    }

    /** All pages as one PDF (each page drawn over the background layer when that is on). */
    private void exportPages(Uri uri) {
        int f0 = canvas.firstFrame(), w = canvas.docW, h = canvas.docH;
        toast("Saving…");
        PdfDocument pdf = new PdfDocument();
        float k = 72f / 300f;
        try {
            for (int i = f0; i < canvas.layers.size(); i++) {
                Bitmap b = canvas.renderFrame(i, w, h);
                PdfDocument.Page pg = pdf.startPage(new PdfDocument.PageInfo.Builder(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), i - f0 + 1).create());
                pg.getCanvas().scale(k, k);
                pg.getCanvas().drawBitmap(b, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
                pdf.finishPage(pg);
                b.recycle();
            }
            try (OutputStream o = getContentResolver().openOutputStream(uri, "wt")) { pdf.writeTo(o); }
            toast("Saved " + (canvas.layers.size() - f0) + " pages");
        } catch (Exception e) { toast("Save failed: " + e.getMessage()); }
        pdf.close();
    }

    private void toggleAnimation() {
        endBarModes();
        if (!canvas.anim && canvas.pages) { canvas.pages = false; }
        canvas.anim = !canvas.anim;
        if (!canvas.anim && canvas.playing) togglePlay();
        timeline.setVisibility(canvas.anim ? View.VISIBLE : View.GONE);
        if (canvas.anim) refreshTimeline();
        canvas.invalidate();
    }

    private void refreshTimeline() {
        if (timeline == null || !(canvas.anim || canvas.pages)) return;
        timeline.removeAllViews();
        if (canvas.pages) pagesBar();
        LinearLayout ctl = barRow();
        chip(ctl, canvas.playing ? "■ Stop" : "▶ Play", canvas.playing, v -> togglePlay());
        chip(ctl, "＋ Frame", false, v -> { if (!canvas.addLayer(canvas.newLayer("Frame " + (canvas.layers.size() + 1)))) full(); });
        chip(ctl, "Settings", false, v -> animSettings());
        chip(ctl, "Export", false, v -> {
            Ui.PopMenu m = new Ui.PopMenu(this, v);
            m.getMenu().add(0, REQ_GIF, 0, "Animated GIF");
            m.getMenu().add(0, REQ_APNG, 1, "Animated PNG");
            m.getMenu().add(0, REQ_AMP4, 2, "MP4 video");
            m.setOnMenuItemClickListener(it -> {
                int id = it.getItemId();
                String type = id == REQ_GIF ? "image/gif" : id == REQ_APNG ? "image/png" : "video/mp4";
                String ext = id == REQ_GIF ? ".gif" : id == REQ_APNG ? ".png" : ".mp4";
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(type).putExtra(Intent.EXTRA_TITLE, nameOf(curId) + ext), id);
                return true;
            });
            m.show();
        });
        TextView info = new TextView(this);
        info.setText("  " + canvas.fps + " fps · onion " + canvas.onion);
        info.setTextColor(0xFF8E8E93);
        ctl.addView(info);
        if (!canvas.pages) {
            HorizontalScrollView h1 = new HorizontalScrollView(this);
            h1.addView(ctl);
            timeline.addView(h1);
        }
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
            n.setText((canvas.pages ? "Page " : "") + (i - f0 + 1));
            n.setTextColor(Color.WHITE);
            n.setTextSize(11);
            cell.addView(n);
            cell.setOnClickListener(v -> { if (idx == canvas.current) frameOptions(v, idx); else canvas.select(idx); });
            if (canvas.layers.get(i).hold > 1) { TextView hb = new TextView(this); hb.setText("×" + canvas.layers.get(i).hold); hb.setTextColor(Ui.ACCENT); hb.setTextSize(11); cell.addView(hb); }
            frames.addView(cell);
        }
        HorizontalScrollView h2 = new HorizontalScrollView(this);
        h2.addView(frames);
        timeline.addView(h2);
    }

    /** Tap the selected frame: hold duration, duplicate, delete (like Procreate's frame options). */
    private void frameOptions(View anchor, int idx) {
        CanvasView.Layer l = canvas.layers.get(idx);
        Ui.Pop pop = new Ui.Pop(this, 280);
        if (canvas.pages) {
            pop.title("Page " + (idx - canvas.firstFrame() + 1));
            pop.item("Duplicate page", true, () -> { canvas.select(idx); if (!canvas.duplicate()) full(); });
            pop.item("Delete page", canvas.layers.size() > 1, () -> { canvas.select(idx); canvas.deleteLayer(); });
            pop.show(anchor);
            return;
        }
        pop.title("Frame " + (idx - canvas.firstFrame() + 1));
        pop.add(slider("Hold duration", l.hold, 1, 12, v -> { l.hold = v; refreshTimeline(); }));
        pop.item("Duplicate frame", true, () -> { canvas.select(idx); if (!canvas.duplicate()) full(); });
        pop.item("Delete frame", canvas.layers.size() > 1, () -> { canvas.select(idx); canvas.deleteLayer(); });
        pop.show(anchor);
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
        LinearLayout modes = barRow();
        int[] mode = {animMode};
        String[] mn = {"Loop", "Ping-pong", "One shot"};
        TextView[] mc = new TextView[3];
        for (int i = 0; i < 3; i++) {
            int k = i;
            mc[i] = chip(modes, mn[i], animMode == i, v -> { mode[0] = k; for (int j = 0; j < 3; j++) { mc[j].setBackgroundColor(j == k ? Ui.ACCENT : 0); } });
        }
        box.addView(modes);
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
                    animMode = mode[0];
                    canvas.invalidate();
                    refreshTimeline();
                }).show();
    }

    // ---- Backup / restore: the whole gallery, brushes, fonts and settings in one zip ----

    private static final String[] BACKUP_DIRS = {"gallery", "brushes", "fonts"};

    void backup(Uri uri) {
        toast("Backing up…");
        new Thread(() -> {
            String msg;
            int files = 0;
            synchronized (MainActivity.class) {
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt");
                     java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream(os, 65536))) {
                    for (String dir : BACKUP_DIRS) {
                        File[] fs = new File(getFilesDir(), dir).listFiles();
                        if (fs == null) continue;
                        java.util.Arrays.sort(fs, (x, y) -> Boolean.compare(!x.getName().endsWith(".psd"), !y.getName().endsWith(".psd"))); // drawings first
                        for (File f : fs) {
                            if (!f.isFile() || f.getName().endsWith(".tmp")) continue; // time-lapse folders are left out (large)
                            z.putNextEntry(new java.util.zip.ZipEntry(dir + "/" + f.getName()));
                            java.nio.file.Files.copy(f.toPath(), z);
                            z.closeEntry();
                            files++;
                        }
                    }
                    StringBuilder sb = new StringBuilder();
                    for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                        Object v = e.getValue();
                        String t = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l" : v instanceof Float ? "f" : "s";
                        sb.append(t).append('\t').append(e.getKey()).append('\t').append(esc(String.valueOf(v))).append('\n');
                    }
                    z.putNextEntry(new java.util.zip.ZipEntry("prefs.txt"));
                    z.write(sb.toString().getBytes("UTF-8"));
                    z.closeEntry();
                    msg = "Backup saved (" + files + " files)";
                } catch (Throwable t) { msg = "Backup failed: " + t.getMessage(); }
            }
            String m = msg;
            runOnUiThread(() -> toast(m));
        }).start();
    }

    /** Adds drawings, brushes and fonts from a backup that aren't here yet; never overwrites. */
    void restore(Uri uri) {
        toast("Restoring…");
        new Thread(() -> {
            int added = 0;
            String err = null;
            java.util.List<File> newBrushes = new java.util.ArrayList<>();
            java.util.Set<String> skipIds = new java.util.HashSet<>();
            String stacksIn = null, orderIn = null;
            java.util.Map<String, String[]> prefIn = new java.util.HashMap<>();
            synchronized (MainActivity.class) {
                try (InputStream is = getContentResolver().openInputStream(uri);
                     java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(is, 65536))) {
                    java.util.zip.ZipEntry e;
                    boolean any = false;
                    while ((e = z.getNextEntry()) != null) {
                        String n = e.getName();
                        if (n.equals("prefs.txt")) {
                            for (String line : new String(Brushes.readAll(z), "UTF-8").split("\n")) {
                                String[] f = line.split("\t", 3);
                                if (f.length == 3) prefIn.put(f[1], new String[]{f[0], unesc(f[2])});
                            }
                            any = true;
                            continue;
                        }
                        int slash = n.indexOf('/');
                        if (slash < 0 || n.contains("..")) continue;
                        String dir = n.substring(0, slash), name = n.substring(slash + 1);
                        if (!java.util.Arrays.asList(BACKUP_DIRS).contains(dir) || name.isEmpty() || name.contains("/")) continue;
                        any = true;
                        if (dir.equals("gallery") && name.equals("stacks.txt")) { stacksIn = new String(Brushes.readAll(z), "UTF-8"); continue; }
                        if (dir.equals("gallery") && name.equals("order.txt")) { orderIn = new String(Brushes.readAll(z), "UTF-8"); continue; }
                        File d = new File(getFilesDir(), dir);
                        d.mkdirs();
                        File out = new File(d, name);
                        String id = dir.equals("gallery") ? name.replaceAll("[._].*$", "") : null;
                        if (id != null && name.endsWith(".psd") && out.exists()) skipIds.add(id); // same drawing already here
                        if (out.exists() || (id != null && skipIds.contains(id))) continue;
                        java.nio.file.Files.copy(z, out.toPath());
                        if (dir.equals("brushes")) newBrushes.add(out);
                        if (name.endsWith(".psd")) added++;
                    }
                    if (!any) err = "That isn't an Inkwell backup";
                    // merge stacks and gallery order
                    if (stacksIn != null) {
                        java.util.Map<String, String> m = stacks();
                        for (String line : stacksIn.split("\n")) {
                            int k = line.indexOf('=');
                            if (k > 0 && !m.containsKey(line.substring(0, k))) m.put(line.substring(0, k), line.substring(k + 1));
                        }
                        saveStacks(m);
                    }
                    if (orderIn != null) {
                        java.util.List<String> o = manualIds();
                        for (String id : orderIn.split("\n")) if (!id.isEmpty() && !o.contains(id)) o.add(id);
                        writeText(orderFile(), String.join("\n", o));
                    }
                } catch (Throwable t) { err = t.getMessage(); }
            }
            int a = added; String er = err;
            runOnUiThread(() -> {
                android.content.SharedPreferences.Editor ed = prefs.edit();
                for (java.util.Map.Entry<String, String[]> pe : prefIn.entrySet()) { // settings you don't have yet
                    String k = pe.getKey(), t = pe.getValue()[0], v = pe.getValue()[1];
                    if (prefs.contains(k) || k.equals("cur")) continue;
                    try {
                        switch (t) {
                            case "b": ed.putBoolean(k, Boolean.parseBoolean(v)); break;
                            case "i": ed.putInt(k, Integer.parseInt(v)); break;
                            case "l": ed.putLong(k, Long.parseLong(v)); break;
                            case "f": ed.putFloat(k, Float.parseFloat(v)); break;
                            default: ed.putString(k, v);
                        }
                    } catch (Exception ignored) { }
                }
                ed.apply();
                for (File f : newBrushes) {
                    try {
                        CanvasView.Brush b = Brushes.load(f);
                        CanvasView.Brush same = null;
                        if (b.builtin) for (CanvasView.Brush x : CanvasView.BRUSHES) if (x.builtin && x.name.equals(b.name)) same = x;
                        if (same != null) { same.setValues(b.values()); same.set = b.set; same.file = f; }
                        else CanvasView.BRUSHES.add(b);
                    } catch (Exception ignored) { }
                }
                fillGallery();
                toast(er != null ? "Restore failed: " + er : "Restored " + a + " drawings");
            });
        }).start();
    }

    /** Share → PNG files: every layer as its own PNG (transparent, full size) in a chosen folder. */
    private void exportLayers(Uri tree) {
        java.util.List<CanvasView.Layer> ls = new java.util.ArrayList<>(canvas.layers);
        java.util.List<Bitmap> copies = new java.util.ArrayList<>();
        for (CanvasView.Layer l : ls) copies.add(l.bmp.copy(Bitmap.Config.ARGB_8888, false));
        String base = nameOf(curId).replaceAll("[/\\\\:*?\"<>|]", "_");
        toast("Saving layers…");
        new Thread(() -> {
            int done = 0; String err = null;
            try {
                Uri parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree));
                for (int i = 0; i < ls.size(); i++) {
                    String nm = String.format("%s %02d %s.png", base, i + 1, ls.get(i).name.replaceAll("[/\\\\:*?\"<>|]", "_"));
                    Uri out = android.provider.DocumentsContract.createDocument(getContentResolver(), parent, "image/png", nm);
                    if (out == null) continue;
                    try (OutputStream o = getContentResolver().openOutputStream(out, "wt")) { copies.get(i).compress(Bitmap.CompressFormat.PNG, 100, o); }
                    done++;
                }
            } catch (Throwable t) { err = t.getMessage(); }
            for (Bitmap b : copies) b.recycle();
            int d = done; String e = err;
            runOnUiThread(() -> toast(e == null ? "Saved " + d + " layers" : "Stopped after " + d + ": " + e));
        }).start();
    }

    /** kind: 0 GIF, 1 MP4, 2 animated PNG. */
    private void exportAnimation(Uri uri, int kind) {
        boolean gif = kind != 1;
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
        java.util.List<Integer> seq = animSequence();
        toast("Exporting animation…");
        new Thread(() -> {
            String msg = "Animation saved";
            Video.Frames frames = new Video.Frames() {
                public int count() { return gif ? seq.size() : Math.max(seq.size(), seq.size() * Math.max(1, 2 * fps / Math.max(1, seq.size()))); } // MP4: ~2 s minimum
                public Bitmap get(int i) { return fr[seq.get(i % seq.size()) - f0].copy(Bitmap.Config.ARGB_8888, false); }
            };
            try {
                if (kind == 2) try (OutputStream o = getContentResolver().openOutputStream(uri, "wt")) { Video.apng(o, w, h, fps, frames); }
                else if (gif) try (OutputStream o = getContentResolver().openOutputStream(uri, "wt")) { Video.gif(o, w, h, fps, frames); }
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
        applyBrushOrder();
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
                    switchBrush(bs.get(0));
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

    /** Share → a format: makes the file, then opens the share sheet (like Procreate). */
    private void shareAs(int fmt) {
        File d = Files.dir(this);
        File[] old = d.listFiles();
        if (old != null) for (File f : old) f.delete(); // only the latest shared file is kept
        File f = new File(d, nameOf(curId).replaceAll("[/\\\\:*?\"<>|]", "_") + "." + EXTS[fmt]);
        exportDone = () -> {
            Uri u = Files.uri(f);
            Intent i = new Intent(Intent.ACTION_SEND).setType(MIMES[fmt]).putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(android.content.ClipData.newRawUri("", u));
            try { startActivity(Intent.createChooser(i, "Share")); } catch (Exception e) { toast("No app to share with"); }
        };
        export(Uri.fromFile(f), fmt);
    }

    private Runnable exportDone; // run after the next export finishes (share sheet)

    private void export(Uri uri, int fmt) {
        Bitmap flat = canvas.flatten();
        if (!canvas.bgVisible && (fmt == 1 || fmt == 3 || fmt == 4)) { // JPEG/PDF can't be transparent; PSD composite
            Bitmap w2 = Bitmap.createBitmap(flat.getWidth(), flat.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c2 = new Canvas(w2);
            c2.drawColor(Color.WHITE);
            c2.drawBitmap(flat, 0, 0, null);
            flat.recycle();
            flat = w2;
        }
        Bitmap flatF = flat;
        java.util.List<CanvasView.Layer> layers = new java.util.ArrayList<>(canvas.layers);
        java.util.Map<Integer, CanvasView.Group> groups = new java.util.HashMap<>(canvas.groups);
        int w = canvas.docW, h = canvas.docH;
        toast("Saving…");
        new Thread(() -> {
            String msg = "Saved";
            try (OutputStream o = getContentResolver().openOutputStream(uri, "wt")) {
                switch (fmt) {
                    case 0: flatF.compress(Bitmap.CompressFormat.PNG, 100, o); break;
                    case 1: flatF.compress(Bitmap.CompressFormat.JPEG, 95, o); break;
                    case 2: flatF.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, o); break;
                    case 3: {
                        PdfDocument pdf = new PdfDocument();
                        // page size as if printed at 300 dpi (points are 1/72 inch); the image keeps full resolution
                        float k = 72f / 300f;
                        PdfDocument.Page pg = pdf.startPage(new PdfDocument.PageInfo.Builder(
                                Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), 1).create());
                        pg.getCanvas().scale(k, k);
                        pg.getCanvas().drawBitmap(flatF, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
                        pdf.finishPage(pg);
                        pdf.writeTo(o);
                        pdf.close();
                        break;
                    }
                    default: Psd.write(o, w, h, layers, groups, flatF);
                }
            } catch (Throwable t) { msg = "Save failed: " + t.getMessage(); }
            flatF.recycle();
            String fm = msg;
            boolean ok = !msg.startsWith("Save failed");
            runOnUiThread(() -> {
                Runnable then = exportDone;
                exportDone = null;
                if (ok && then != null) then.run(); else toast(fm);
            });
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAMERA) {
            if (res == RESULT_OK && photoUri != null) load(photoUri, true);
            else if (photoUri != null) try { getContentResolver().delete(photoUri, null, null); } catch (Exception ignored) { }
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_EXPORT) export(data.getData(), exportFormat);
        if (req == REQ_FONT) importFont(data.getData());
        if ((req == REQ_SHAPE || req == REQ_GRAIN) && studioBrush != null) {
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                byte[] img = Brushes.readAll(in);
                boolean ok = req == REQ_SHAPE ? Brushes.applyShape(studioBrush, img) : Brushes.applyGrain(studioBrush, img);
                toast(ok ? (req == REQ_SHAPE ? "Shape set" : "Grain set") : "Couldn't read that picture");
                if (ok && studioRedraw != null) studioRedraw.run();
            } catch (Exception e) { toast("Couldn't read that picture"); }
        }
        if (req == REQ_TREE) exportMany(data.getData(), false);
        if (req == REQ_PDF_MANY) exportMany(data.getData(), true);
        if (req == REQ_PAGES_PDF) exportPages(data.getData());
        if (req == REQ_BRUSH_EXPORT && exportBrushes != null) {
            try (OutputStream o = getContentResolver().openOutputStream(data.getData())) {
                Brushes.export(exportBrushes, o);
                toast(exportBrushes.size() == 1 ? "Brush exported" : exportBrushes.size() + " brushes exported");
            } catch (Exception e) { toast("Could not export: " + e.getMessage()); }
            exportBrushes = null;
        }
        else if (req == REQ_OPEN) load(data.getData(), false);
        else if (req == REQ_IMPORT) load(data.getData(), true);
        else if (req == REQ_BRUSH) importBrushes(data.getData());
        else if (req == REQ_GALLERY) load(data.getData(), false);
        else if (req == REQ_TL) exportTimelapse(data.getData());
        else if (req == REQ_GIF || req == REQ_AMP4) exportAnimation(data.getData(), req == REQ_GIF ? 0 : 1);
        else if (req == REQ_APNG) exportAnimation(data.getData(), 2);
        else if (req == REQ_BACKUP) backup(data.getData());
        else if (req == REQ_RESTORE) restore(data.getData());
        else if (req == REQ_LAYERS_TREE) exportLayers(data.getData());
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
        // Back closes the innermost thing first: Brush Studio, preview, a stack, open panels, a tool mode;
        // then the canvas goes to the Gallery, and the Gallery leaves the app.
        if (studio != null) { closeStudio(); return; }
        if (tlPlayer != null) { closeTimelapsePlayer(); return; }
        if (previewOv != null && previewOv.getParent() != null) { shell.removeView(previewOv); previewOv = null; return; }
        if (gallery != null && gallery.getVisibility() == View.VISIBLE) {
            if (openStack != null) { openStack = null; fillGallery(); return; }
            if (selectMode) { selectMode = false; picked.clear(); fillGallery(); return; }
            moveTaskToBack(true);
            return;
        }
        if (closePanels()) return;
        if (canvas.cropping) { canvas.cancelCrop(); bottom.setVisibility(View.GONE); return; }
        if (canvas.editGuide || canvas.fillMode) { endBarModes(); return; }
        if (canvas.adjusting()) { finishAdjust(false); return; }                 // Back = Cancel for an adjustment
        if (canvas.liquifying()) { canvas.endLiquify(false); setTool(CanvasView.DRAW); return; }
        if (canvas.tool != CanvasView.DRAW && canvas.tool != CanvasView.ERASE && canvas.tool != CanvasView.SMUDGE) { setTool(CanvasView.DRAW); return; }
        showGallery();
    }

    private View previewOv;

    private File docFile(String id) { return new File(gdir, id + ".psd"); }
    private File thumbFile(String id) { return new File(gdir, id + ".png"); }
    private File nameFile(String id) { return new File(gdir, id + ".name"); }
    private File selFile(String id) { return new File(gdir, id + ".sel"); }
    private File tlDir(String id) { return new File(gdir, id + "_tl"); }
    private File bgFile(String id) { return new File(gdir, id + ".bg"); }

    private File guideFile(String id) { return new File(gdir, id + ".guide"); }

    private File textFile(String id) { return new File(gdir, id + ".text"); }
    private File animFile(String id) { return new File(gdir, id + ".anim"); }
    private File flagsFile(String id) { return new File(gdir, id + ".flags"); } // per layer: locked, reference

    private static String esc(String t) { return t.replace("\\", "\\\\").replace("\n", "\\n").replace("|", "\\p"); }
    private static String unesc(String t) { return t.replace("\\p", "|").replace("\\n", "\n").replace("\\\\", "\\"); }

    /** Editable text and animation settings live beside the PSD. */
    private void saveExtras() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < canvas.layers.size(); i++) {
            CanvasView.TextInfo t = canvas.layers.get(i).text;
            if (t != null) sb.append(i).append('|').append(esc(t.text)).append('|').append(t.size).append('|').append(t.bold)
                    .append('|').append(t.align).append('|').append(t.font).append('|').append(t.grey)
                    .append('|').append(t.spacing).append('|').append(t.lineSpacing).append('|').append(t.underline).append('|').append(t.outline).append('\n');
        }
        writeText(textFile(curId), sb.toString());
        StringBuilder a = new StringBuilder();
        a.append(canvas.fps).append(',').append(canvas.onion).append(',').append(canvas.onionOpacity).append(',')
         .append(canvas.animBackground).append(',').append(animMode).append(',').append(canvas.anim);
        for (CanvasView.Layer l : canvas.layers) a.append(',').append(l.hold);
        writeText(animFile(curId), a.toString());
        prefs.edit().putBoolean("pages_" + curId, canvas.pages).apply();
        StringBuilder fl = new StringBuilder();
        for (CanvasView.Layer l : canvas.layers) fl.append(l.locked ? 'L' : '-').append(l.reference ? 'R' : '-').append(',');
        writeText(flagsFile(curId), fl.toString());
    }

    private void loadExtras() {
        for (CanvasView.Layer l : canvas.layers) { l.text = null; l.hold = 1; l.locked = false; l.reference = false; }
        try {
            String[] f = readText(flagsFile(curId)).trim().split(",");
            for (int i = 0; i < f.length && i < canvas.layers.size(); i++) {
                canvas.layers.get(i).locked = f[i].indexOf('L') >= 0;
                canvas.layers.get(i).reference = f[i].indexOf('R') >= 0;
            }
        } catch (Exception ignored) { }
        try {
            for (String line : readText(textFile(curId)).split("\n")) {
                String[] f = line.split("\\|", -1);
                if (f.length < 7) continue;
                int i = Integer.parseInt(f[0]);
                if (i < canvas.layers.size()) {
                    CanvasView.TextInfo ti = new CanvasView.TextInfo(unesc(f[1]), Float.parseFloat(f[2]), Boolean.parseBoolean(f[3]),
                            Integer.parseInt(f[4]), f[5], Integer.parseInt(f[6]));
                    if (f.length >= 11) {
                        ti.spacing = Float.parseFloat(f[7]); ti.lineSpacing = Float.parseFloat(f[8]);
                        ti.underline = Boolean.parseBoolean(f[9]); ti.outline = Boolean.parseBoolean(f[10]);
                    }
                    canvas.layers.get(i).text = ti;
                }
            }
        } catch (Exception ignored) { }
        try {
            String[] f = readText(animFile(curId)).trim().split(",");
            canvas.fps = Integer.parseInt(f[0]); canvas.onion = Integer.parseInt(f[1]); canvas.onionOpacity = Float.parseFloat(f[2]);
            canvas.animBackground = Boolean.parseBoolean(f[3]); animMode = Integer.parseInt(f[4]);
            boolean on = Boolean.parseBoolean(f[5]);
            for (int i = 6; i < f.length && i - 6 < canvas.layers.size(); i++) canvas.layers.get(i - 6).hold = Integer.parseInt(f[i]);
            if (on != canvas.anim) toggleAnimation();
        } catch (Exception ignored) { if (canvas.anim) toggleAnimation(); }
        if (prefs.getBoolean("pages_" + curId, false) != canvas.pages) togglePages();
    }

    private void writeText(File f, String t) {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(t.getBytes("UTF-8")); } catch (Exception ignored) { }
    }

    private String readText(File f) throws Exception { return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"); }

    private void saveBackground() {
        saveExtras();
        try (FileOutputStream o = new FileOutputStream(bgFile(curId))) { o.write((canvas.bgGrey + "," + canvas.bgVisible).getBytes("UTF-8")); }
        catch (Exception ignored) { }
        try (FileOutputStream o = new FileOutputStream(guideFile(curId))) { o.write(canvas.guideState().getBytes("UTF-8")); }
        catch (Exception ignored) { }
    }

    /** Restores the drawing's background colour (white if none saved). */
    private void loadBackground() {
        canvas.bgGrey = 255; canvas.bgVisible = true;
        try {
            String[] v = new String(java.nio.file.Files.readAllBytes(bgFile(curId).toPath()), "UTF-8").trim().split(",");
            canvas.bgGrey = Integer.parseInt(v[0]);
            canvas.bgVisible = Boolean.parseBoolean(v[1]);
        } catch (Exception ignored) { }
        loadExtras();
        canvas.guide = 0; canvas.editGuide = false; canvas.symRotate = false;
        try { canvas.setGuideState(new String(java.nio.file.Files.readAllBytes(guideFile(curId).toPath()), "UTF-8")); }
        catch (Exception ignored) { }
        canvas.invalidate();
    }
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
        if (curId != null) saveBackground();
        canvas.commitMove();
        Bitmap flat = canvas.flatten();
        Bitmap thumb = canvas.renderSmall(420);
        Bitmap prev = canvas.renderSmall(1600);
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
                try (OutputStream o = new FileOutputStream(previewFile(id))) { prev.compress(Bitmap.CompressFormat.JPEG, 88, o); }
                catch (Throwable ignored) { }
            }
            flat.recycle();
            thumb.recycle();
            prev.recycle();
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
            acceptDrops(gallery, false); // dropped file becomes a new drawing
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
            button(head, "Backup", v -> {
                Ui.Pop bp = new Ui.Pop(this, 300);
                bp.item("Back up everything…", true, () -> saveCurrent(() -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                        .putExtra(Intent.EXTRA_TITLE, "Inkwell backup " + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date()) + ".zip"), REQ_BACKUP)));
                bp.item("Restore from a backup…", true, () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_RESTORE));
                TextView note = new TextView(this);
                note.setText("Drawings, stacks, brushes, fonts and settings in one file. Keep it in Drive or on a computer: "
                        + "uninstalling Inkwell deletes its drawings. Restore adds what's missing and keeps what you have.");
                note.setTextColor(0xFF8E8E93);
                note.setPadding((int) (16 * dp), (int) (6 * dp), (int) (16 * dp), (int) (8 * dp));
                bp.add(note);
                bp.show(v);
            });
            button(head, "Import", v -> openDoc(REQ_GALLERY));
            button(head, "Photo", v -> pickPhoto(REQ_GALLERY));
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

    private File previewFile(String id) { return new File(gdir, id + ".prev.jpg"); }
    private File stacksFile() { return new File(gdir, "stacks.txt"); }
    private File orderFile() { return new File(gdir, "order.txt"); }
    private String openStack;                         // stack being viewed, null = top level
    private boolean selectMode;
    private final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();

    private java.util.Map<String, String> stacks() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        try { for (String line : readText(stacksFile()).split("\n")) { int k = line.indexOf('='); if (k > 0) m.put(line.substring(0, k), line.substring(k + 1)); } }
        catch (Exception ignored) { }
        return m;
    }

    private void saveStacks(java.util.Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : m.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        writeText(stacksFile(), sb.toString());
    }

    private static final String[] SORTS = {"Manual", "Name", "Newest", "Oldest"};

    /** Drawing ids in the order the Gallery shows them (Sort: manual drag order, name or date). */
    private java.util.List<String> orderedIds() {
        java.util.List<String> o = manualIds();
        int sort = prefs.getInt("gallerySort", 0);
        if (sort == 1) o.sort((a, b) -> nameOf(a).compareToIgnoreCase(nameOf(b)));
        else if (sort >= 2) {
            java.util.Map<String, Long> t = new java.util.HashMap<>();
            for (String id : o) t.put(id, docFile(id).lastModified());
            o.sort((a, b) -> sort == 2 ? Long.compare(t.get(b), t.get(a)) : Long.compare(t.get(a), t.get(b)));
        }
        return o;
    }

    /** Drawing ids in custom order (drag to reorder), new drawings first. */
    private java.util.List<String> manualIds() {
        File[] docs = gdir.listFiles((d, n) -> n.endsWith(".psd"));
        if (docs == null) docs = new File[0];
        java.util.Arrays.sort(docs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        java.util.List<String> all = new java.util.ArrayList<>();
        for (File f : docs) all.add(f.getName().replace(".psd", ""));
        java.util.List<String> out = new java.util.ArrayList<>();
        try { for (String id : readText(orderFile()).split("\n")) if (all.contains(id) && !out.contains(id)) out.add(id); } catch (Exception ignored) { }
        for (String id : all) if (!out.contains(id)) out.add(0, id); // new drawings first
        return out;
    }

    private void moveInOrder(String id, String beforeId) {
        java.util.List<String> o = orderedIds(); // dragging uses what you see, then becomes the manual order
        prefs.edit().putInt("gallerySort", 0).apply();
        o.remove(id);
        int at = beforeId == null ? o.size() : Math.max(0, o.indexOf(beforeId));
        o.add(at, id);
        writeText(orderFile(), String.join("\n", o));
    }

    private void fillGallery() {
        if (galleryGrid == null) return;
        galleryGrid.removeAllViews();
        java.util.Map<String, String> st = stacks();
        java.util.List<String> ids = orderedIds();
        LinearLayout bar2 = barRow(); // stack title / select actions
        if (openStack != null) chip(bar2, "‹ Gallery   " + openStack, false, v -> { openStack = null; fillGallery(); });
        chip(bar2, selectMode ? "Done" : "Select", selectMode, v -> { selectMode = !selectMode; picked.clear(); fillGallery(); });
        if (!selectMode) chip(bar2, "Sort: " + SORTS[prefs.getInt("gallerySort", 0)], false, v -> {
            Ui.PopMenu m = new Ui.PopMenu(this, v);
            for (int i = 0; i < SORTS.length; i++) m.getMenu().add(0, i, i, SORTS[i]);
            m.setOnMenuItemClickListener(it -> { prefs.edit().putInt("gallerySort", it.getItemId()).apply(); fillGallery(); return true; });
            m.show();
        });
        if (selectMode && !picked.isEmpty()) {
            chip(bar2, "Share", false, v -> shareMany(new java.util.ArrayList<>(picked)));
            chip(bar2, "Export", false, v -> exportManyMenu(v, new java.util.ArrayList<>(picked)));
            chip(bar2, "Stack", false, v -> stackDialog(new java.util.ArrayList<>(picked)));
            if (openStack != null) chip(bar2, "Unstack", false, v -> { java.util.Map<String, String> m = stacks(); for (String id : picked) m.remove(id); saveStacks(m); picked.clear(); fillGallery(); });
            chip(bar2, "Duplicate", false, v -> { for (String id : picked) duplicateItem(id); picked.clear(); fillGallery(); });
            chip(bar2, "Delete", false, v -> confirm("Delete " + picked.size() + " drawings? This can't be undone.", () -> { for (String id : new java.util.ArrayList<>(picked)) deleteItem(id); picked.clear(); fillGallery(); }));
        }
        galleryGrid.addView(bar2);
        // what to show: drawings in this stack, or top level = unstacked drawings + one card per stack
        java.util.List<Object> cards = new java.util.ArrayList<>();
        java.util.Set<String> seenStacks = new java.util.HashSet<>();
        for (String id : ids) {
            String sk = st.get(id);
            if (openStack != null) { if (openStack.equals(sk)) cards.add(id); }
            else if (sk == null) cards.add(id);
            else if (seenStacks.add(sk)) cards.add(new String[]{sk, id});
        }
        File[] docs = new File[0];
        int cols = Math.max(2, (int) (getResources().getDisplayMetrics().widthPixels / dp / 190));
        int cw = (int) ((getResources().getDisplayMetrics().widthPixels - 16 * dp) / cols);
        LinearLayout row = null;
        for (int i = 0; i < cards.size(); i++) {
            if (i % cols == 0) { row = new LinearLayout(this); galleryGrid.addView(row); }
            if (cards.get(i) instanceof String[]) { // a stack
                String[] sk = (String[]) cards.get(i);
                int count = 0;
                for (String v : st.values()) if (v.equals(sk[0])) count++;
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding((int) (6 * dp), (int) (8 * dp), (int) (6 * dp), (int) (4 * dp));
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setBackgroundColor(0xFF48484A);
                iv.setPadding(10, 10, 10, 10);
                Bitmap tb = android.graphics.BitmapFactory.decodeFile(thumbFile(sk[1]).getPath());
                if (tb != null) iv.setImageBitmap(tb);
                card.addView(iv, new LinearLayout.LayoutParams(-1, (int) (cw * 1.1f)));
                TextView nm = new TextView(this);
                nm.setText("▤ " + sk[0]);
                nm.setTextColor(Color.WHITE);
                card.addView(nm);
                TextView c2 = new TextView(this);
                c2.setText(count + " drawings");
                c2.setTextColor(0xFF8E8E93);
                c2.setTextSize(12);
                card.addView(c2);
                card.setOnClickListener(v -> { openStack = sk[0]; fillGallery(); });
                row.addView(card, new LinearLayout.LayoutParams(cw, -2));
                continue;
            }
            String id = (String) cards.get(i);
            File docFileI = docFile(id);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding((int) (6 * dp), (int) (8 * dp), (int) (6 * dp), (int) (4 * dp));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(picked.contains(id) ? 0xFF34C759 : id.equals(curId) ? Ui.ACCENT : 0xFF2C2C2E);
            iv.setPadding(3, 3, 3, 3);
            Bitmap tb = android.graphics.BitmapFactory.decodeFile(thumbFile(id).getPath());
            if (tb != null) iv.setImageBitmap(tb);
            card.addView(iv, new LinearLayout.LayoutParams(-1, (int) (cw * 1.1f)));
            TextView nm = new TextView(this);
            nm.setText(nameOf(id));
            nm.setTextColor(Color.WHITE);
            nm.setSingleLine(true);
            nm.setPadding(0, (int) (4 * dp), 0, 0);
            nm.setOnClickListener(v -> { if (!selectMode) renameItem(id); else togglePick(id); }); // tap the title to rename
            card.addView(nm);
            TextView sz = new TextView(this);
            sz.setText(tb == null ? "" : new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(new java.util.Date(docFileI.lastModified())));
            sz.setTextColor(0xFF8E8E93);
            sz.setTextSize(12);
            card.addView(sz);
            card.setOnClickListener(v -> { if (selectMode) togglePick(id); else openGalleryItem(id); });
            card.setTag(id);
            card.setOnTouchListener(cardGestures(card, id));
            row.addView(card, new LinearLayout.LayoutParams(cw, -2));
        }
        if (cards.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("No drawings yet. Tap ＋ New.");
            e.setTextColor(0xFF8E8E93);
            e.setPadding((int) (8 * dp), (int) (24 * dp), 0, 0);
            galleryGrid.addView(e);
        }
    }

    private void togglePick(String id) { if (!picked.remove(id)) picked.add(id); fillGallery(); }

    /** Gallery card: long-press then drag to reorder; long-press alone for the menu; pinch out to preview. */
    private View.OnTouchListener cardGestures(View card, String id) {
        float[] st = new float[4]; // downX, downY, mode (0 none, 1 armed, 2 dragging), pinch start
        Runnable arm = () -> { st[2] = 1; card.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start(); };
        return (v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX(); st[1] = e.getRawY(); st[2] = 0;
                    card.postDelayed(arm, 420);
                    return false;
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                    card.removeCallbacks(arm);
                    st[3] = (float) Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0));
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (e.getPointerCount() >= 2 && st[3] > 0) { // pinch out: preview
                        float d = (float) Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0));
                        if (d > st[3] * 1.4f) { st[3] = 0; showPreview(id); }
                        return true;
                    }
                    float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1];
                    if (st[2] == 0 && Math.hypot(dx, dy) > 12 * dp) card.removeCallbacks(arm);
                    if (st[2] >= 1 && Math.hypot(dx, dy) > 12 * dp) {
                        st[2] = 2;
                        card.getParent().requestDisallowInterceptTouchEvent(true);
                        card.setTranslationX(dx); card.setTranslationY(dy);
                        card.setElevation(16 * dp);
                        return true;
                    }
                    return st[2] == 2;
                }
                default:
                    card.removeCallbacks(arm);
                    card.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                    if (st[2] == 2) { // drop before the card under the finger
                        String target = cardAt(e.getRawX(), e.getRawY(), card);
                        card.setTranslationX(0); card.setTranslationY(0); card.setElevation(0);
                        if (target != null) { moveInOrder(id, target); fillGallery(); }
                        return true;
                    }
                    if (st[2] == 1 && e.getActionMasked() == android.view.MotionEvent.ACTION_UP) { galleryItemMenu(card, id); return true; }
                    return false;
            }
        };
    }

    private String cardAt(float rx, float ry, View except) {
        for (int r = 0; r < galleryGrid.getChildCount(); r++) {
            View row = galleryGrid.getChildAt(r);
            if (!(row instanceof LinearLayout)) continue;
            LinearLayout lr = (LinearLayout) row;
            for (int c = 0; c < lr.getChildCount(); c++) {
                View ch = lr.getChildAt(c);
                if (ch == except || !(ch.getTag() instanceof String)) continue;
                int[] loc = new int[2];
                ch.getLocationOnScreen(loc);
                if (rx >= loc[0] && rx < loc[0] + ch.getWidth() && ry >= loc[1] && ry < loc[1] + ch.getHeight()) return (String) ch.getTag();
            }
        }
        return null;
    }

    /** Full-screen look at a drawing (pinch a gallery card, like Procreate). Tap to close. */
    /** Full-screen preview (pinch out on a drawing). Swipe left/right for the next drawing, tap to close. */
    private void showPreview(String id) {
        java.util.Map<String, String> st = stacks();
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String x : orderedIds()) if (java.util.Objects.equals(st.get(x), openStack)) ids.add(x);
        if (!ids.contains(id)) ids.add(0, id);
        int[] at = {ids.indexOf(id)};
        FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(0xF0000000);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ov.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setPadding((int) (16 * dp), (int) (16 * dp), 0, 0);
        ov.addView(t);
        Runnable show = () -> {
            String cur = ids.get(at[0]);
            File f = previewFile(cur).exists() ? previewFile(cur) : thumbFile(cur);
            iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(f.getPath()));
            t.setText(nameOf(cur) + "   ·   " + (at[0] + 1) + " / " + ids.size() + "   ·   swipe for more, tap to close");
        };
        show.run();
        float[] down = new float[1];
        ov.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) down[0] = e.getX();
            else if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) iv.setTranslationX(e.getX() - down[0]);
            else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                float dx = e.getX() - down[0];
                iv.setTranslationX(0);
                if (Math.abs(dx) < 24 * dp) { shell.removeView(ov); return true; } // tap
                if (dx < -80 * dp && at[0] < ids.size() - 1) at[0]++;
                else if (dx > 80 * dp && at[0] > 0) at[0]--;
                show.run();
            } else iv.setTranslationX(0);
            return true;
        });
        shell.addView(ov);
        previewOv = ov;
    }

    private void renameItem(String id) {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(nameOf(id));
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Rename").setView(et)
                .setPositiveButton("OK", (d, w) -> { setName(id, et.getText().toString()); fillGallery(); }).show();
    }

    private void stackDialog(java.util.List<String> ids) {
        java.util.Map<String, String> m = stacks();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(m.values());
        Ui.Pop pop = new Ui.Pop(this, 260);
        pop.title("Move to stack");
        for (String n : names) pop.item(n, true, () -> { for (String id : ids) m.put(id, n); saveStacks(m); picked.clear(); selectMode = false; fillGallery(); });
        pop.item("＋ New stack…", true, () -> {
            android.widget.EditText et = new android.widget.EditText(this);
            et.setText("Stack");
            new AlertDialog.Builder(this).setTitle("New stack").setView(et).setPositiveButton("OK", (d, w) -> {
                for (String id : ids) m.put(id, et.getText().toString().trim().isEmpty() ? "Stack" : et.getText().toString().trim());
                saveStacks(m); picked.clear(); selectMode = false; fillGallery();
            }).show();
        });
        pop.show(galleryGrid);
    }

    private void duplicateItem(String id) {
        String nid = newId() + (int) (Math.random() * 1000);
        try {
            java.nio.file.Files.copy(docFile(id).toPath(), docFile(nid).toPath());
            for (File[] pair : new File[][]{{thumbFile(id), thumbFile(nid)}, {bgFile(id), bgFile(nid)}, {guideFile(id), guideFile(nid)},
                    {textFile(id), textFile(nid)}, {animFile(id), animFile(nid)}, {previewFile(id), previewFile(nid)},
                    {flagsFile(id), flagsFile(nid)}, {selFile(id), selFile(nid)}})
                if (pair[0].exists()) java.nio.file.Files.copy(pair[0].toPath(), pair[1].toPath());
            setName(nid, nameOf(id) + " copy");
            java.util.Map<String, String> m = stacks();
            if (m.containsKey(id)) { m.put(nid, m.get(id)); saveStacks(m); }
        } catch (Exception e) { toast("Could not duplicate"); }
    }

    private void deleteItem(String id) {
        for (File f : new File[]{docFile(id), thumbFile(id), nameFile(id), selFile(id), bgFile(id), guideFile(id), textFile(id), animFile(id), previewFile(id), flagsFile(id)}) f.delete();
        File[] tl = tlDir(id).listFiles();
        if (tl != null) for (File f : tl) f.delete();
        tlDir(id).delete();
        prefs.edit().remove("pages_" + id).apply();
        java.util.Map<String, String> m = stacks();
        if (m.remove(id) != null) saveStacks(m);
        if (id.equals(curId)) {
            Rect scr = screenBounds();
            curId = newId();
            prefs.edit().putString("cur", curId).apply();
            newDocument(Math.min(scr.width(), scr.height()), Math.max(scr.width(), scr.height()));
            savedSel.clear();
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
                loadBackground();
                loadSelections();
                gallery.setVisibility(View.GONE);
                if (canvas.anim || canvas.pages) refreshTimeline();
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
            if (id == 99) canvasDialog(true);
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
        m.getMenu().add(0, 4, 3, "Move to stack…");
        if (stacks().containsKey(id)) m.getMenu().add(0, 5, 4, "Remove from stack");
        m.getMenu().add(0, 6, 5, "Preview");
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
                case 2: duplicateItem(id); fillGallery(); break; // copies everything: text, guides, animation, flags…
                case 4: stackDialog(java.util.Collections.singletonList(id)); break;
                case 5: { java.util.Map<String, String> mm2 = stacks(); mm2.remove(id); saveStacks(mm2); fillGallery(); break; }
                case 6: showPreview(id); break;
                case 3:
                    confirm("Delete \"" + nameOf(id) + "\"? This can't be undone.", () -> { deleteItem(id); fillGallery(); });
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

    private FrameLayout tlPlayer; // full-screen time-lapse replay

    /** Time-lapse replay, full screen like Procreate: plays; touch and hold pauses; drag left/right scrubs; lift continues. */
    private void replayTimelapse() {
        File[] fs = tlFrames();
        if (fs.length == 0) { toast("Nothing recorded yet"); return; }
        closeTimelapsePlayer();
        FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(0xFF111113);
        ov.setClickable(true);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ov.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        TextView info = new TextView(this);
        info.setTextColor(0xFFAEAEB2);
        info.setTextSize(13);
        info.setPadding((int) (16 * dp), 0, (int) (16 * dp), (int) (14 * dp));
        ov.addView(info, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        View track = new View(this);
        track.setBackgroundColor(0xFF48484A);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, (int) (3 * dp), Gravity.BOTTOM);
        tl.bottomMargin = (int) (44 * dp); tl.leftMargin = tl.rightMargin = (int) (24 * dp);
        ov.addView(track, tl);
        View fill = new View(this);
        fill.setBackgroundColor(Ui.ACCENT);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(0, (int) (3 * dp), Gravity.BOTTOM);
        fl.bottomMargin = tl.bottomMargin; fl.leftMargin = tl.leftMargin;
        ov.addView(fill, fl);
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(22);
        close.setPadding((int) (18 * dp), (int) (12 * dp), (int) (18 * dp), (int) (12 * dp));
        close.setOnClickListener(v -> closeTimelapsePlayer());
        ov.addView(close, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        int n = fs.length;
        int[] pos = {0};
        boolean[] held = {false}, stop = {false};
        Runnable show = () -> {
            int k = Math.max(0, Math.min(n - 1, pos[0]));
            Bitmap b = android.graphics.BitmapFactory.decodeFile(fs[k].getPath());
            if (b != null) iv.setImageBitmap(b);
            ViewGroup.LayoutParams lp = fill.getLayoutParams();
            lp.width = Math.round((ov.getWidth() - 48 * dp) * (k + 1f) / n);
            fill.setLayoutParams(lp);
            info.setText(held[0] ? "Scrubbing · " + Math.round(100f * (k + 1) / n) + "%" : "Time-lapse · touch to pause · drag to scrub");
        };
        float[] down = new float[2];
        ov.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: held[0] = true; down[0] = e.getX(); down[1] = pos[0]; show.run(); break;
                case android.view.MotionEvent.ACTION_MOVE: // the whole width = the whole recording
                    pos[0] = Math.max(0, Math.min(n - 1, Math.round(down[1] + (e.getX() - down[0]) / Math.max(1, v.getWidth()) * n)));
                    show.run();
                    break;
                default: held[0] = false; show.run();
            }
            return true;
        });
        shell.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        tlPlayer = ov;
        ov.setTag(stop);
        int step = Math.max(1, n / 600); // full replay in about 20 seconds
        Runnable tick = new Runnable() {
            public void run() {
                if (stop[0]) return;
                if (!held[0] && pos[0] < n - 1) { pos[0] = Math.min(n - 1, pos[0] + step); show.run(); }
                ov.postDelayed(this, 33);
            }
        };
        ov.post(() -> { show.run(); ov.postDelayed(tick, 33); });
    }

    private void closeTimelapsePlayer() {
        if (tlPlayer == null) return;
        ((boolean[]) tlPlayer.getTag())[0] = true;
        shell.removeView(tlPlayer);
        tlPlayer = null;
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
