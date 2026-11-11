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
import android.widget.PopupMenu;
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
    private static final int REQ_OPEN = 1, REQ_IMPORT = 2, REQ_BRUSH = 3, REQ_EXPORT = 10;
    private static final String[] FORMATS = {"PNG", "JPEG", "WebP", "PDF", "PSD (layers)"};
    private static final String[] MIMES = {"image/png", "image/jpeg", "image/webp", "application/pdf", "image/vnd.adobe.photoshop"};
    private static final String[] EXTS = {"png", "jpg", "webp", "pdf", "psd"};

    private CanvasView canvas;
    private Ui.Icon brushBtn, eraseBtn, moveBtn, colorBtn, layersBtn, pickBtn;
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
        dp = getResources().getDisplayMetrics().density;
        autosave = new File(getFilesDir(), "autosave.psd");

        canvas = new CanvasView(this);
        Rect max = getWindowManager().getMaximumWindowMetrics().getBounds();
        newDocument(Math.min(max.width(), max.height()), Math.max(max.width(), max.height()));
        canvas.onChange = this::refreshPanel;
        canvas.onGreyPicked = this::updateColor;

        // ---- Top bar: actions on the left, painting tools on the right ----
        int ib = (int) (44 * dp);
        LinearLayout left = new LinearLayout(this), right = new LinearLayout(this);
        left.setGravity(Gravity.CENTER_VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        icon(left, Ui.MENU, ib, this::actionsMenu);
        moveBtn = icon(left, Ui.MOVE, ib, v -> setTool(canvas.tool == CanvasView.MOVE ? CanvasView.DRAW : CanvasView.MOVE));
        brushName = new TextView(this);
        brushName.setTextColor(0xFF9A9A9E);
        brushName.setTextSize(13);
        brushName.setPadding(0, 0, (int) (6 * dp), 0);
        brushName.setOnClickListener(v -> pickBrush());
        right.addView(brushName);
        brushBtn = icon(right, Ui.BRUSH, ib, v -> { if (canvas.tool == CanvasView.DRAW) pickBrush(); else setTool(CanvasView.DRAW); });
        eraseBtn = icon(right, Ui.ERASER, ib, v -> { if (canvas.tool == CanvasView.ERASE) pickBrush(); else setTool(CanvasView.ERASE); });
        layersBtn = icon(right, Ui.LAYERS, ib, v -> togglePanel());
        colorBtn = icon(right, Ui.COLOR, ib, v -> pickGrey());
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
        Ui.VSlider sizeS = new Ui.VSlider(this, (float) Math.sqrt((canvas.size - 1) / 149f));
        sizeS.listener = (v, done) -> canvas.size = 1 + 149 * v * v; // finer control at small sizes
        side.addView(sizeS, new LinearLayout.LayoutParams((int) (40 * dp), (int) (150 * dp)));
        pickBtn = icon(side, Ui.PICK, (int) (40 * dp), v -> { canvas.pickNext = !canvas.pickNext; pickBtn.setOn(canvas.pickNext); });
        Ui.VSlider opS = new Ui.VSlider(this, canvas.opacity);
        opS.listener = (v, done) -> canvas.opacity = Math.max(0.01f, v);
        side.addView(opS, new LinearLayout.LayoutParams((int) (40 * dp), (int) (150 * dp)));
        icon(side, Ui.UNDO, (int) (40 * dp), v -> canvas.undo());
        icon(side, Ui.REDO, (int) (40 * dp), v -> canvas.redo());
        canvas.onPicked = () -> pickBtn.setOn(false);
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.addView(top, new LinearLayout.LayoutParams(-1, ib));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.setOnApplyWindowInsetsListener((v, in) -> {
            Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        canvas.onToggleUi = () -> { // four-finger tap: hide/show toolbar and panel
            boolean show = scroll.getVisibility() != View.VISIBLE;
            scroll.setVisibility(show ? View.VISIBLE : View.GONE);
            side.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) panel.setVisibility(View.GONE);
        };

        loadBrushes();
        // Restore last drawing, then handle "Open with" / "Share" intents
        new Thread(() -> {
            Psd.Doc d = null;
            if (autosave.exists()) {
                try (InputStream in = new FileInputStream(autosave)) { d = Psd.read(in, MAX_PIXELS); }
                catch (Throwable ignored) { }
            }
            Psd.Doc fd = d;
            runOnUiThread(() -> {
                if (fd != null) canvas.setDocument(fd.w, fd.h, fd.layers);
                loaded = true;
                handleIntent(getIntent());
            });
        }).start();
    }

    private abstract static class Slider implements SeekBar.OnSeekBarChangeListener {
        public void onStartTrackingTouch(SeekBar s) { }
        public void onStopTrackingTouch(SeekBar s) { }
    }

    /** New drawing or resize, with size presets. */
    private void canvasDialog() {
        Rect scr = getWindowManager().getMaximumWindowMetrics().getBounds();
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
        canvas.setTool(t);
        brushBtn.setOn(t == CanvasView.DRAW);
        eraseBtn.setOn(t == CanvasView.ERASE);
        moveBtn.setOn(t == CanvasView.MOVE);
    }

    private Ui.Icon icon(LinearLayout parent, int kind, int size, View.OnClickListener l) {
        Ui.Icon i = new Ui.Icon(this, kind);
        i.setOnClickListener(l);
        parent.addView(i, new LinearLayout.LayoutParams(size, size));
        return i;
    }

    /** Actions menu (top-left): files, canvas, settings, help. */
    private void actionsMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        android.view.Menu mm = m.getMenu();
        mm.add(0, 1, 0, "Open file…");
        mm.add(0, 2, 1, "Import image as layer…");
        android.view.SubMenu ex = mm.addSubMenu(0, 3, 2, "Export / share as");
        for (int i = 0; i < FORMATS.length; i++) ex.add(1, 100 + i, i, FORMATS[i]);
        mm.add(0, 4, 3, "Canvas size…");
        mm.add(0, 5, 4, "Fit to screen");
        mm.add(0, 6, 5, "Import brushes…");
        mm.add(0, 7, 6, "Sidebar on " + (sideLp.gravity == (Gravity.START | Gravity.CENTER_VERTICAL) ? "right" : "left"));
        mm.add(0, 8, 7, "Gestures help");
        m.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            if (id >= 100) { startExport(id - 100); return true; }
            switch (id) {
                case 1: openDoc(REQ_OPEN); break;
                case 2: openDoc(REQ_IMPORT); break;
                case 4: canvasDialog(); break;
                case 5: canvas.animateFit(); break;
                case 6: openDoc(REQ_BRUSH); break;
                case 7:
                    sideLp.gravity = sideLp.gravity == (Gravity.START | Gravity.CENTER_VERTICAL)
                            ? Gravity.END | Gravity.CENTER_VERTICAL : Gravity.START | Gravity.CENTER_VERTICAL;
                    side.setLayoutParams(sideLp);
                    break;
                case 8:
                    new AlertDialog.Builder(this).setTitle("Gestures").setMessage(
                            "Two-finger tap: undo · hold: keep undoing\n"
                          + "Three-finger tap: redo\n"
                          + "Pinch / drag / twist: zoom, move, rotate\n"
                          + "Quick pinch-in: fit to screen\n"
                          + "Three-finger scrub: clear layer\n"
                          + "Four-finger tap: full screen\n"
                          + "Touch and hold: eyedropper\n"
                          + "Draw and hold: QuickShape (line / ellipse)\n"
                          + "Tap the active brush or eraser: brush library")
                            .setPositiveButton("OK", null).show();
                    break;
            }
            return true;
        });
        m.show();
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

    /** Brush list + brush opacity. Tap the active Brush/Erase button to open. */
    private void pickBrush() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * dp);
        box.setPadding(p, p, p, p);
        AlertDialog[] dlg = new AlertDialog[1];
        for (CanvasView.Brush br : CanvasView.BRUSHES) {
            TextView t = new TextView(this);
            t.setText(br.name);
            t.setTextSize(17);
            t.setPadding(p, p, p, p);
            boolean on = br == canvas.brush;
            t.setBackgroundColor(on ? Color.BLACK : 0);
            t.setTextColor(on ? Color.WHITE : Color.BLACK);
            t.setOnClickListener(v -> { canvas.brush = br; updateBrush(); dlg[0].dismiss(); });
            if (br.file != null) t.setOnLongClickListener(v -> {
                confirm("Delete brush \"" + br.name + "\"?", () -> {
                    br.file.delete();
                    CanvasView.BRUSHES.remove(br);
                    if (canvas.brush == br) canvas.brush = CanvasView.BRUSHES.get(0);
                    updateBrush();
                    dlg[0].dismiss();
                });
                return true;
            });
            box.addView(t);
        }
        TextView ol = new TextView(this);
        ol.setText("Brush opacity");
        ol.setPadding(p, p, p, 0);
        box.addView(ol);
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(Math.round(canvas.opacity * 100));
        sb.setOnSeekBarChangeListener(new Slider() {
            public void onProgressChanged(SeekBar s, int v, boolean u) { canvas.opacity = Math.max(1, v) / 100f; }
        });
        box.addView(sb, new LinearLayout.LayoutParams(-1, (int) (48 * dp)));
        TextView imp = new TextView(this);
        imp.setText("+ Import brushes (Procreate .brush/.brushset, Photoshop .abr)");
        imp.setTextColor(Color.BLACK);
        imp.setPadding(p, p, p, p);
        imp.setOnClickListener(v -> {
            dlg[0].dismiss();
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_BRUSH);
        });
        box.addView(imp);
        TextView hint = new TextView(this);
        hint.setText("Long-press an imported brush to delete it.");
        hint.setPadding(p, 0, p, p);
        box.addView(hint);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(this).setTitle("Brushes").setView(sv).setPositiveButton("Done", null).show();
    }

    private void updateBrush() { brushName.setText(canvas.brush.name); }

    private void updateColor() {
        int g = canvas.grey;
        colorBtn.grey = g;
        colorBtn.invalidate();
    }

    /** Grey picker: slider (black..white) plus quick swatches. */
    private void pickGrey() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * dp);
        box.setPadding(p, p, p, p);
        View preview = new View(this);
        box.addView(preview, new LinearLayout.LayoutParams(-1, (int) (48 * dp)));
        SeekBar sb = new SeekBar(this);
        sb.setMax(255);
        Runnable show = () -> {
            int g = 255 - sb.getProgress();
            preview.setBackgroundColor(Color.rgb(g, g, g));
            canvas.grey = g;
            updateColor();
        };
        sb.setProgress(255 - canvas.grey);
        sb.setOnSeekBarChangeListener(new Slider() {
            public void onProgressChanged(SeekBar s, int v, boolean u) { show.run(); }
        });
        box.addView(sb, new LinearLayout.LayoutParams(-1, (int) (48 * dp)));
        LinearLayout sw = new LinearLayout(this);
        for (int i = 0; i <= 8; i++) {
            int g = Math.min(255, i * 32);
            View v = new View(this);
            v.setBackgroundColor(Color.rgb(g, g, g));
            v.setOnClickListener(x -> sb.setProgress(255 - g));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (40 * dp), 1f);
            lp.setMargins(2, 2, 2, 2);
            sw.addView(v, lp);
        }
        box.addView(sw);
        show.run();
        new AlertDialog.Builder(this).setTitle("Ink grey").setView(box).setPositiveButton("Done", null).show();
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

    private void refreshPanel() {
        if (panel == null || panel.getVisibility() != View.VISIBLE) return;
        layerList.removeAllViews();
        opacityBar.setProgress(canvas.cur().opacity);
        int tw = (int) (48 * dp), th = Math.max(1, tw * canvas.docH / canvas.docW);
        for (int i = canvas.layers.size() - 1; i >= 0; i--) { // top layer first
            CanvasView.Layer l = canvas.layers.get(i);
            if (l.dirty || l.thumb == null) {
                l.thumb = Bitmap.createScaledBitmap(l.bmp, tw, th, true);
                l.dirty = false;
            }
            final int idx = i;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, (int) (3 * dp), 0, (int) (3 * dp));
            r.setBackgroundColor(i == canvas.current ? 0xFF555555 : 0);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(l.thumb);
            iv.setBackgroundColor(0xFF888888);
            r.addView(iv, new LinearLayout.LayoutParams(tw, th));
            TextView name = new TextView(this);
            name.setText(l.name);
            name.setTextColor(Color.WHITE);
            name.setSingleLine(true);
            name.setPadding((int) (8 * dp), 0, 0, 0);
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView vis = new TextView(this);
            vis.setText(l.visible ? "Shown" : "Hidden");
            vis.setTextColor(l.visible ? Color.WHITE : 0xFF888888);
            vis.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
            vis.setOnClickListener(v -> canvas.toggleVisible(idx));
            r.addView(vis);
            r.setOnClickListener(v -> canvas.select(idx));
            layerList.addView(r);
        }
    }

    // ---------- Open / import ----------

    private void openMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
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
            try { CanvasView.BRUSHES.add(Brushes.load(f)); } catch (Exception ignored) { }
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
                        canvas.setDocument(d.w, d.h, d.layers);
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
        d.layers.add(new CanvasView.Layer(Bitmap.createBitmap(d.w, d.h, Bitmap.Config.ARGB_8888), "Layer 2"));
        return d;
    }

    // ---------- Export ----------

    private void exportMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
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
                        PdfDocument.Page pg = pdf.startPage(new PdfDocument.PageInfo.Builder(w, h, 1).create());
                        pg.getCanvas().drawBitmap(flat, 0, 0, null);
                        pdf.finishPage(pg);
                        pdf.writeTo(o);
                        pdf.close();
                        break;
                    }
                    default: Psd.write(o, w, h, layers, flat);
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
    }

    // ---------- Autosave ----------

    @Override
    protected void onPause() {
        super.onPause();
        if (!loaded) return;
        canvas.commitMove();
        Bitmap flat = canvas.flatten();
        java.util.List<CanvasView.Layer> layers = new java.util.ArrayList<>(canvas.layers);
        int w = canvas.docW, h = canvas.docH;
        new Thread(() -> {
            synchronized (MainActivity.class) {
                File tmp = new File(autosave.getPath() + ".tmp");
                try (OutputStream o = new FileOutputStream(tmp)) {
                    Psd.write(o, w, h, layers, flat);
                } catch (Throwable t) { flat.recycle(); return; }
                tmp.renameTo(autosave);
            }
            flat.recycle();
        }).start();
    }
}
