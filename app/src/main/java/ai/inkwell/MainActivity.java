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
    private static final int REQ_OPEN = 1, REQ_IMPORT = 2, REQ_EXPORT = 10;
    private static final String[] FORMATS = {"PNG", "JPEG", "WebP", "PDF", "PSD (layers)"};
    private static final String[] MIMES = {"image/png", "image/jpeg", "image/webp", "application/pdf", "image/vnd.adobe.photoshop"};
    private static final String[] EXTS = {"png", "jpg", "webp", "pdf", "psd"};

    private CanvasView canvas;
    private TextView brushBtn, eraseBtn, moveBtn, colorBtn;
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

        // ---- Toolbar ----
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) (4 * dp), 0, (int) (4 * dp), 0);
        brushBtn = button(bar, "Brush", v -> setTool(CanvasView.DRAW));
        eraseBtn = button(bar, "Erase", v -> setTool(CanvasView.ERASE));
        moveBtn = button(bar, "Move", v -> setTool(CanvasView.MOVE));
        colorBtn = button(bar, "", v -> { canvas.white = !canvas.white; updateColor(); });
        SeekBar sizeBar = new SeekBar(this);
        sizeBar.setMax(149);
        sizeBar.setProgress((int) canvas.size - 1);
        sizeBar.setOnSeekBarChangeListener(new Slider() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { canvas.size = p + 1; }
        });
        bar.addView(sizeBar, new LinearLayout.LayoutParams((int) (130 * dp), -2));
        button(bar, "Undo", v -> canvas.undo());
        button(bar, "Redo", v -> canvas.redo());
        button(bar, "Layers", v -> togglePanel());
        button(bar, "Open", this::openMenu);
        button(bar, "Export", this::exportMenu);
        button(bar, "Fit", v -> canvas.fit());
        button(bar, "New", v -> confirm("Start a new drawing? The current one is replaced.", () -> {
            Rect m = getWindowManager().getMaximumWindowMetrics().getBounds();
            newDocument(Math.min(m.width(), m.height()), Math.max(m.width(), m.height()));
        }));
        setTool(CanvasView.DRAW);
        updateColor();

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(bar, new FrameLayout.LayoutParams(-2, -1));

        // ---- Layers panel ----
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF0111111);
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
        stage.addView(panel, new FrameLayout.LayoutParams((int) (260 * dp), -1, Gravity.END));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, (int) (48 * dp)));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.setOnApplyWindowInsetsListener((v, in) -> {
            Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);

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
        highlight(brushBtn, t == CanvasView.DRAW);
        highlight(eraseBtn, t == CanvasView.ERASE);
        highlight(moveBtn, t == CanvasView.MOVE);
    }

    private void updateColor() {
        colorBtn.setText(canvas.white ? "● White" : "● Black");
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

    private void load(Uri uri, boolean asLayer) {
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
            throw new Exception("Procreate files can't be read directly. In Procreate use Share → PSD, then open that.");
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
