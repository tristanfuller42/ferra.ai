package app.inkwell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Path;
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
    public static final int MAX_UNDO = 250;
    private static final long MAX_HISTORY_BYTES = 128L << 20;
    public static final int DRAW = 0, ERASE = 1, MOVE = 2, SMUDGE = 3, SELECT = 4, CLONE = 5, LIQUIFY = 6, ADJUST = 7;

    public static final String[] BLEND_NAMES = {"Normal", "Multiply", "Screen", "Overlay", "Darken", "Lighten",
            "Colour burn", "Colour dodge", "Hard light", "Soft light", "Difference", "Add"};
    static final android.graphics.BlendMode[] BLENDS = {
            android.graphics.BlendMode.SRC_OVER, android.graphics.BlendMode.MULTIPLY, android.graphics.BlendMode.SCREEN,
            android.graphics.BlendMode.OVERLAY, android.graphics.BlendMode.DARKEN, android.graphics.BlendMode.LIGHTEN,
            android.graphics.BlendMode.COLOR_BURN, android.graphics.BlendMode.COLOR_DODGE, android.graphics.BlendMode.HARD_LIGHT,
            android.graphics.BlendMode.SOFT_LIGHT, android.graphics.BlendMode.DIFFERENCE, android.graphics.BlendMode.PLUS};

    public static final class Layer {
        public Bitmap bmp, thumb;
        public String name;
        public int opacity = 255;
        public boolean visible = true, dirty = true, alphaLock, clip, editMask, maskOn = true;
        public int blend, group;          // group id, 0 = none
        public Layer mask;                // white = show, black = hide
        public boolean locked, reference; // locked: no changes; reference: fills/auto-select read this layer
        public TextInfo text;             // set while the layer is editable text
        public int hold = 1;              // animation: how many frames this frame is shown
        public Bitmap maskThumb;
        public Layer(Bitmap b, String n) { bmp = b; name = n; }
        void copyProps(Layer o) {
            opacity = o.opacity; visible = o.visible; alphaLock = o.alphaLock; clip = o.clip; blend = o.blend; group = o.group;
            locked = o.locked; hold = o.hold;
        }
    }

    /** Editable text layer settings. */
    public static final class TextInfo {
        public String text, font = "sans-serif";
        public float size;
        public boolean bold;
        public int align, grey;           // align: 0 left, 1 centre, 2 right
        public TextInfo(String t, float size, boolean bold, int align, String font, int grey) {
            text = t; this.size = size; this.bold = bold; this.align = align; this.font = font; this.grey = grey;
        }
    }

    public static final class Group {
        public String name;
        public boolean visible = true, collapsed;
        public int opacity = 255;
        public Group(String n) { name = n; }
    }

    interface Edit { void undo(); void redo(); long bytes(); default String name() { return "Change"; } default void name(String n) { } }

    /** Swaps a rectangle of pixels between the layer and stored compressed data. */
    private final class PixelEdit implements Edit {
        final Layer l; final Rect r; byte[] data;
        String nm = "Change";
        PixelEdit(Layer l, Rect r) { this.l = l; this.r = r; data = pack(l.bmp, r); }
        public String name() { return nm; }
        public void name(String n) { nm = n; }
        void swap() { byte[] now = pack(l.bmp, r); unpack(l.bmp, r, data); data = now; l.dirty = true; }
        public void undo() { swap(); }
        public void redo() { swap(); }
        public long bytes() { return data.length; }
    }

    /** Structural change (add/delete/reorder layer, opacity, ...). */
    private static final class Op implements Edit {
        final Runnable u, r; final long b;
        String nm = "Change";
        Op(Runnable u, Runnable r, long b) { this.u = u; this.r = r; this.b = b; }
        public String name() { return nm; }
        public void name(String n) { nm = n; }
        public void undo() { u.run(); }
        public void redo() { r.run(); }
        public long bytes() { return b; }
    }

    public final ArrayList<Layer> layers = new ArrayList<>();
    public final java.util.HashMap<Integer, Group> groups = new java.util.HashMap<>();
    public int nextGroup = 1;
    public int current, docW, docH;
    private Bitmap backup;               // copy of current layer before the active stroke
    private Canvas backupCanvas;
    private final ArrayList<Edit> undoList = new ArrayList<>(), redoList = new ArrayList<>();
    public Runnable onChange;

    public int tool = DRAW;
    public int grey = 0;                 // ink: 0 = black .. 255 = white
    public float size = 12f;
    private boolean stylusSeen;
    /** What one finger does once a stylus has been used: 0 navigate (palm rejection), 1 paint, 2 erase. */
    public int fingerMode = 2;
    /** Called with true when a finger starts erasing (load the Eraser's settings) and false when it ends. */
    public java.util.function.Consumer<Boolean> onFingerErase;
    private boolean fingerErasing;

    private void endFingerErase() {
        if (!fingerErasing) return;
        fingerErasing = false;
        if (onFingerErase != null) onFingerErase.accept(false);
    }

    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint copy = new Paint();
    private final Paint paper = new Paint();

    /** Background colour (like Procreate's "Background colour" layer): never painted on directly. */
    public int bgGrey = 255;
    public boolean bgVisible = true;

    /** Colour used where a solid picture is needed (video, thumbnails, reference). */
    public int paperColor() { return bgVisible ? Color.rgb(bgGrey, bgGrey, bgGrey) : Color.WHITE; }

    public void setBackground(int grey, boolean visible) {
        named("Background colour");
        int og = bgGrey; boolean ov = bgVisible;
        if (og == grey && ov == visible) return;
        apply(new Op(() -> { bgGrey = og; bgVisible = ov; }, () -> { bgGrey = grey; bgVisible = visible; }, 64));
    }

    private android.graphics.BitmapShader checker;

    /** Grey checkerboard shown where the background is hidden (transparent). */
    private Paint checkerPaint(float viewScale) {
        if (checker == null) {
            Bitmap t = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            Canvas tc = new Canvas(t);
            tc.drawColor(0xFFFFFFFF);
            Paint q = new Paint();
            q.setColor(0xFFD1D1D6);
            tc.drawRect(0, 0, 8, 8, q);
            tc.drawRect(8, 8, 16, 16, q);
            checker = new android.graphics.BitmapShader(t, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        }
        Matrix m = new Matrix();
        m.setScale(1 / viewScale, 1 / viewScale);
        checker.setLocalMatrix(m);
        Paint p = new Paint();
        p.setShader(checker);
        return p;
    }

    /** Brush preset. minSize/minAlpha = size/opacity at zero pressure (1 = pressure doesn't affect it). */
    public static final class Brush {
        public String name;
        float minSize, minAlpha, alpha, hard, grain, spacing;
        // Brush Studio settings (Procreate-style)
        public String set = "Imported";
        float streamline, stabilize;          // smoothing 0..1
        float taperStart, taperEnd;           // 0..1 (length relative to brush size)
        float jitterSize, jitterOpacity, scatter;
        float roundness = 1f, angle;          // dab shape; angle in degrees
        boolean follow;                       // rotate the dab with the stroke direction
        float grainScale = 1f;                // texture size multiplier
        float curve = 1f;                     // pressure curve: <1 = light touch gives more, >1 = needs more pressure
        float sizeScale = 1f;                 // maximum size relative to the size slider
        public boolean builtin;

        /** All adjustable settings as named values (used by Brush Studio, saving, reset). */
        static final String[] KEYS = {"spacing", "streamline", "stabilize", "taperStart", "taperEnd", "jitterSize",
                "jitterOpacity", "scatter", "hard", "roundness", "angle", "follow", "grain", "grainScale",
                "minSize", "minAlpha", "curve", "alpha", "sizeScale"};

        float get(String k) {
            switch (k) {
                case "spacing": return spacing; case "streamline": return streamline; case "stabilize": return stabilize;
                case "taperStart": return taperStart; case "taperEnd": return taperEnd; case "jitterSize": return jitterSize;
                case "jitterOpacity": return jitterOpacity; case "scatter": return scatter; case "hard": return hard;
                case "roundness": return roundness; case "angle": return angle; case "follow": return follow ? 1 : 0;
                case "grain": return grain; case "grainScale": return grainScale; case "minSize": return minSize;
                case "minAlpha": return minAlpha; case "curve": return curve; case "alpha": return alpha;
                case "sizeScale": return sizeScale;
            }
            return 0;
        }

        void put(String k, float v) {
            switch (k) {
                case "spacing": spacing = v; break; case "streamline": streamline = v; break; case "stabilize": stabilize = v; break;
                case "taperStart": taperStart = v; break; case "taperEnd": taperEnd = v; break; case "jitterSize": jitterSize = v; break;
                case "jitterOpacity": jitterOpacity = v; break; case "scatter": scatter = v; break; case "hard": hard = v; break;
                case "roundness": roundness = v; break; case "angle": angle = v; break; case "follow": follow = v >= 0.5f; break;
                case "grain": grain = v; break; case "grainScale": grainScale = v; break; case "minSize": minSize = v; break;
                case "minAlpha": minAlpha = v; break; case "curve": curve = v; break; case "alpha": alpha = v; break;
                case "sizeScale": sizeScale = v; break;
            }
        }

        float[] values() { float[] v = new float[KEYS.length]; for (int i = 0; i < v.length; i++) v[i] = get(KEYS[i]); return v; }
        void setValues(float[] v) { for (int i = 0; i < Math.min(v.length, KEYS.length); i++) put(KEYS[i], v[i]); }

        /** A copy for "Duplicate brush" (shares the tip/texture images). */
        Brush copy(String newName) {
            Brush b = new Brush(newName, minSize, minAlpha, alpha, hard, grain, spacing);
            b.setValues(values());
            b.set = set; b.tip = tip; b.tw = tw; b.th = th; b.tex = tex; b.gw = gw; b.gh = gh;
            return b;
        }

        /** Sets one Brush Studio value (for building presets). */
        Brush p(String k, float v) { put(k, v); return this; }

        /** Gives the brush a generated tip image: 1 = bristles (oil), 2 = sparse bristles (dry), 3 = sponge. */
        Brush tipKind(int kind) {
            int n = kind == 3 ? 128 : 96;
            float[] f = new float[n * n];
            java.util.Random r = new java.util.Random(kind * 977L);
            int count = kind == 1 ? 70 : kind == 2 ? 28 : 55;
            for (int i = 0; i < count; i++) {
                double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * 0.88;
                float cx = n / 2f + (float) (Math.cos(a) * d * n / 2), cy = n / 2f + (float) (Math.sin(a) * d * n / 2);
                float rad = kind == 3 ? 3 + r.nextFloat() * 9 : 2 + r.nextFloat() * (kind == 1 ? 4 : 3);
                float val = 0.45f + 0.55f * r.nextFloat();
                for (int y = Math.max(0, (int) (cy - rad - 1)); y < Math.min(n, (int) (cy + rad + 2)); y++)
                    for (int x = Math.max(0, (int) (cx - rad - 1)); x < Math.min(n, (int) (cx + rad + 2)); x++) {
                        float q = 1 - (float) Math.hypot(x + 0.5f - cx, y + 0.5f - cy) / rad;
                        if (q > 0) f[y * n + x] = Math.max(f[y * n + x], val * Math.min(1, q * 2.5f));
                    }
            }
            byte[] t = new byte[n * n];
            for (int i = 0; i < t.length; i++) t[i] = (byte) Math.round(Math.min(1, f[i]) * 255);
            setTip(t, n, n);
            return this;
        }

        Brush with(String set, float streamline, float taperStart, float taperEnd) {
            this.set = set; this.streamline = streamline; this.taperStart = taperStart; this.taperEnd = taperEnd;
            builtin = true;
            return this;
        }
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
        new Brush("Ink pen",        0.15f, 1f,    1f,   0.9f, 0f,    0.08f).with("Inking", 0.35f, 0.15f, 0.15f),
        new Brush("Brush pen",      0.03f, 1f,    1f,   0.85f, 0f,   0.05f).with("Inking", 0.45f, 0.3f, 0.3f),
        new Brush("Fine liner",     1f,    1f,    1f,   0.9f, 0f,    0.08f).with("Inking", 0.3f, 0f, 0f),
        new Brush("Dry ink",        0.3f,  0.6f,  1f,   0.8f, 0.5f,  0.1f).with("Inking", 0.2f, 0.1f, 0.1f),
        new Brush("Marker",         1f,    1f,    0.5f, 0.95f, 0f,   0.08f).with("Inking", 0.2f, 0f, 0f),
        new Brush("HB pencil",      0.7f,  0.15f, 0.9f, 0.7f, 0.6f,  0.1f).with("Sketching", 0f, 0f, 0f),
        new Brush("6B pencil",      0.6f,  0.25f, 1f,   0.6f, 0.75f, 0.1f).with("Sketching", 0f, 0f, 0f),
        new Brush("Charcoal",       0.6f,  0.2f,  1f,   0.4f, 0.85f, 0.1f).with("Sketching", 0f, 0f, 0f),
        new Brush("Soft shading",   0.5f,  0.05f, 0.8f, 0f,   0f,    0.1f).with("Airbrushing", 0f, 0f, 0f),
        new Brush("Airbrush",       1f,    0.02f, 0.6f, 0f,   0f,    0.1f).with("Airbrushing", 0f, 0f, 0f),
        new Brush("Hard round",     1f,    0.1f,  1f,   0.9f, 0f,    0.08f).with("Airbrushing", 0f, 0f, 0f),
        // Painting set
        new Brush("Round brush",    0.4f,  0.3f,  0.9f, 0.5f, 0f,    0.05f).with("Painting", 0.2f, 0.2f, 0.3f),
        new Brush("Flat brush",     0.6f,  0.5f,  1f,   0.8f, 0f,    0.04f).with("Painting", 0.2f, 0f, 0.2f)
                .p("roundness", 0.25f).p("angle", 90).p("follow", 1),
        new Brush("Gouache",        0.5f,  0.7f,  1f,   0.75f, 0.25f, 0.05f).with("Painting", 0.15f, 0.1f, 0.15f)
                .p("grainScale", 2f).p("jitterOpacity", 0.1f),
        new Brush("Oil paint",      0.6f,  0.4f,  1f,   0.8f, 0.2f,  0.03f).with("Painting", 0.15f, 0f, 0.25f)
                .p("follow", 1).tipKind(1),
        new Brush("Dry brush",      0.7f,  0.3f,  1f,   0.8f, 0.6f,  0.05f).with("Painting", 0.1f, 0f, 0.2f)
                .p("grainScale", 1.5f).p("follow", 1).tipKind(2),
        new Brush("Watercolour",    0.6f,  0.2f,  0.35f, 0f,  0.35f, 0.06f).with("Painting", 0.25f, 0.1f, 0.2f)
                .p("grainScale", 3f).p("jitterSize", 0.1f),
        new Brush("Palette knife",  0.8f,  1f,    1f,   0.95f, 0f,   0.03f).with("Painting", 0.3f, 0f, 0f)
                .p("roundness", 0.12f).p("angle", 30),
        new Brush("Sponge",         0.8f,  0.5f,  0.9f, 0.6f, 0.3f,  0.15f).with("Painting", 0f, 0f, 0f)
                .p("scatter", 0.25f).p("jitterSize", 0.3f).p("jitterOpacity", 0.3f).tipKind(3),
        new Brush("Blender",        0.6f,  0.2f,  0.6f, 0.2f, 0f,    0.06f).with("Painting", 0.3f, 0.1f, 0.1f)
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

    /** Factory settings of the built-in brushes, for "Reset brush". */
    public static final java.util.HashMap<String, float[]> DEFAULTS = new java.util.HashMap<>();
    static { for (Brush b : BRUSHES) DEFAULTS.put(b.name, b.values()); }

    public static final String[] SETS = {"Inking", "Sketching", "Painting", "Airbrushing", "Imported"};

    public Brush brush = BRUSHES.get(0);
    public float opacity = 1f;

    // Selection (null = nothing selected = everything editable)
    public byte[] sel;
    public int selKind = 1;            // 0 automatic, 1 freehand, 2 rectangle, 3 ellipse
    public boolean selSubtract;
    private Bitmap selOverlay;
    private Path selPath;
    private float selX0, selY0, autoSX;
    private int autoThr = 30;
    public java.util.function.Consumer<String> onInfo;

    // Transform (Move tool)
    private Bitmap floatBmp;
    private boolean moveBlocked;
    private PixelEdit moveEdit;
    private byte[] selBeforeMove;
    private final Matrix poly = new Matrix();
    private float[] srcCorners = new float[8], dstCorners = new float[8];
    public int xform;                  // 0 uniform, 1 freeform, 2 distort, 3 warp
    private float[] warp = new float[32]; // 4x4 warp control points (canvas coords, before poly)
    private int fx0, fy0;                // floating bitmap origin

    // Perspective guide vanishing points; guide editing; drawing assist
    public float[] vp = new float[6];
    public float guideOpacity = 0.35f, guideThick = 1.5f; // guide look (thickness in screen pixels)
    public int vpRays = 36;                                // lines drawn from each vanishing point
    public boolean horizonLevel = true;                    // keep the horizon straight unless the user tilts it

    private int guideColor(float mul) {
        return ((int) (255 * Math.min(1f, guideOpacity * mul)) << 24) | 0x3D9BFF;
    }
    public boolean editGuide, assist;
    private int dragVp = -1;
    private float asX, asY, adX, adY;
    private boolean asDir;
    public Runnable onCommitted;
    private int dragHandle = -1;

    // Smudge / clone
    private boolean smudging, cloning;
    private float smX, smY, cloneSrcX, cloneSrcY, cloneOX, cloneOY;
    private boolean cloneSet, cloneOffSet;
    public boolean cloneArmed;
    private int[] dab = new int[1024], buf2 = new int[1024];

    // Drawing guides: 0 off, 1 grid, 2 vertical, 3 horizontal, 4 quadrant, 5 radial symmetry
    public int guide, gridSize = 100;

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
        setBackgroundColor(0xFF161618);
    }

    // ---------- Document ----------

    public void setDocument(int w, int h, List<Layer> ls) {
        docW = w; docH = h;
        bgGrey = 255; bgVisible = true;
        groups.clear();
        nextGroup = 1;
        layers.clear();
        layers.addAll(ls);
        if (layers.isEmpty()) layers.add(newLayer("Layer 1"));
        current = layers.size() - 1;
        undoList.clear(); redoList.clear();
        moving = false; moveT.reset();
        backup = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        backupCanvas = new Canvas(backup);
        mask = new byte[w * h];
        sel = null; selOverlay = null; floatBmp = null; adjSrc = null; liqSrc = null; cloneSet = false;
        resetVps();
        playing = false;
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
            n.copyProps(l);
            new Canvas(n.bmp).drawBitmap(l.bmp, m, p);
            l.bmp.recycle();
            if (l.mask != null) {
                n.mask = new Layer(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), l.mask.name);
                n.mask.bmp.eraseColor(Color.WHITE);
                new Canvas(n.mask.bmp).drawBitmap(l.mask.bmp, m, p);
                n.maskOn = l.maskOn;
            }
            out.add(n);
        }
        java.util.HashMap<Integer, Group> keepGroups = new java.util.HashMap<>(groups);
        int keepNext = nextGroup, keepBg = bgGrey;
        boolean keepBgOn = bgVisible;
        setDocument(w, h, out);
        bgGrey = keepBg; bgVisible = keepBgOn;
        groups.putAll(keepGroups);
        nextGroup = keepNext;
        current = Math.min(keep, layers.size() - 1);
        syncBackup();
        changed();
    }

    public void setGroups(java.util.Map<Integer, Group> g) {
        groups.clear();
        groups.putAll(g);
        nextGroup = 1;
        for (int k : g.keySet()) nextGroup = Math.max(nextGroup, k + 1);
        invalidate();
    }

    public Layer newLayer(String name) {
        return new Layer(Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888), name);
    }

    public Layer cur() { return layers.get(current); }

    /** What painting edits: the current layer, or its mask while the mask is being edited. */
    public Layer tgt() {
        Layer l = layers.get(current);
        return l.editMask && l.mask != null ? l.mask : l;
    }

    public int maxLayers() {
        long per = (long) docW * docH * 4;
        return (int) Math.max(2, Math.min(50, Runtime.getRuntime().maxMemory() * 55 / 100 / per - 2));
    }

    private void syncBackup() { backupCanvas.drawBitmap(tgt().bmp, 0, 0, copy); }

    private void changed() {
        invalidate();
        if (onChange != null) onChange.run();
    }

    /** Name for the next undo step (shown as "Undo: name"). */
    private String pendingName;
    public java.util.function.Consumer<String> onHistory;
    private void named(String n) { pendingName = n; }

    private void push(Edit e) {
        if (pendingName != null) { e.name(pendingName); pendingName = null; }
        if (onCommitted != null) post(onCommitted);
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

    private Matrix fitMatrix() {
        Matrix m = new Matrix();
        float s = Math.min((float) getWidth() / docW, (float) getHeight() / docH) * 0.96f;
        m.setScale(s, s);
        m.postTranslate((getWidth() - docW * s) / 2f, (getHeight() - docH * s) / 2f);
        return m;
    }

    public void fit() {
        if (docW == 0) return;
        view.set(fitMatrix());
        invalidate();
    }

    /** Smoothly fits the canvas to the screen (quick pinch-in, Fit button). */
    /** Flips the view (not the pixels), like Procreate's Flip canvas. */
    public void flipView(boolean horizontal) {
        view.postScale(horizontal ? -1 : 1, horizontal ? 1 : -1, getWidth() / 2f, getHeight() / 2f);
        invalidate();
    }

    public void animateFit() {
        if (docW == 0) return;
        float[] a = new float[9], b = new float[9], c = new float[9];
        view.getValues(a);
        fitMatrix().getValues(b);
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(250);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator());
        va.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            for (int k = 0; k < 9; k++) c[k] = a[k] + (b[k] - a[k]) * t;
            view.setValues(c);
            invalidate();
        });
        va.start();
    }

    /** Screen-space gesture transform (moveT) expressed in canvas coordinates. */
    private Matrix layerMatrix() {
        view.invert(inverse);
        tmpM.set(view);
        tmpM.postConcat(moveT);
        tmpM.postConcat(inverse);
        return tmpM;
    }

    /** Full move transform in canvas coordinates: distort (poly) then gesture. */
    private Matrix transformMatrix() {
        Matrix m = new Matrix(poly);
        m.postConcat(layerMatrix());
        return m;
    }

    /** Screen positions of the transform handles: corners (+ edge midpoints for freeform, 16 points for warp). */
    private float[] handlePoints() {
        float[] q;
        Matrix m;
        if (xform == 3) {
            q = new float[32];
            for (int k = 0; k < 16; k++) { q[k * 2] = warp[k * 2]; q[k * 2 + 1] = warp[k * 2 + 1]; }
            m = transformMatrix();
        } else {
            q = new float[xform == 1 ? 16 : 8];
            System.arraycopy(srcCorners, 0, q, 0, 8);
            if (xform == 1) for (int k = 0; k < 4; k++) {
                int a = k, b = (k + 1) % 4;
                q[8 + k * 2] = (srcCorners[a * 2] + srcCorners[b * 2]) / 2;
                q[9 + k * 2] = (srcCorners[a * 2 + 1] + srcCorners[b * 2 + 1]) / 2;
            }
            m = transformMatrix();
        }
        m.postConcat(view);
        m.mapPoints(q);
        return q;
    }

    private static final int MESH = 16;

    /** Bezier-patch warp of the floating bitmap, then the freeform/distort matrix. */
    private float[] meshVerts() {
        float[] v = new float[(MESH + 1) * (MESH + 1) * 2];
        int k = 0;
        for (int j = 0; j <= MESH; j++)
            for (int i = 0; i <= MESH; i++) {
                float u = i / (float) MESH, w = j / (float) MESH;
                float[] bu = bern(u), bw = bern(w);
                float x = 0, y = 0;
                for (int r = 0; r < 4; r++) for (int cI = 0; cI < 4; cI++) {
                    float f = bw[r] * bu[cI];
                    x += f * warp[(r * 4 + cI) * 2];
                    y += f * warp[(r * 4 + cI) * 2 + 1];
                }
                v[k++] = x; v[k++] = y;
            }
        poly.mapPoints(v);
        return v;
    }

    private static float[] bern(float t) {
        float s = 1 - t;
        return new float[]{s * s * s, 3 * t * s * s, 3 * t * t * s, t * t * t};
    }

    /** Draws all layers: groups, blend modes, clipping masks, layer masks. live = include move preview. */
    private void render(Canvas c, boolean live) {
        if (anim && live) { renderAnim(c); return; }
        int i = 0;
        while (i < layers.size()) {
            int g = layers.get(i).group, j = i;
            while (j < layers.size() && layers.get(j).group == g) j++;
            if (g == 0) renderRange(c, i, j, live);
            else {
                Group gr = groups.get(g);
                if (gr == null || gr.visible) {
                    Paint gp = new Paint();
                    gp.setAlpha(gr == null ? 255 : gr.opacity);
                    c.saveLayer(0, 0, docW, docH, gp);
                    renderRange(c, i, j, live);
                    c.restore();
                }
            }
            i = j;
        }
    }

    private void renderRange(Canvas c, int from, int to, boolean live) {
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (int i = from; i < to; i++) {
            Layer l = layers.get(i);
            if (l.clip && i > from) continue; // drawn with its base layer
            int j = i + 1;
            while (j < to && layers.get(j).clip) j++;
            if (!l.visible) { i = j - 1; continue; }
            p.setAlpha(l.opacity);
            p.setBlendMode(BLENDS[l.blend]);
            if (j == i + 1) drawLayer(c, i, p, live);
            else {
                c.saveLayer(0, 0, docW, docH, p);
                drawLayer(c, i, new Paint(Paint.FILTER_BITMAP_FLAG), live);
                for (int k = i + 1; k < j; k++) {
                    Layer cl = layers.get(k);
                    if (!cl.visible) continue;
                    Paint a = new Paint(Paint.FILTER_BITMAP_FLAG);
                    a.setAlpha(cl.opacity);
                    a.setBlendMode(android.graphics.BlendMode.SRC_ATOP);
                    drawLayer(c, k, a, live);
                }
                c.restore();
                i = j - 1;
            }
        }
    }

    private static final Paint MASK_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    static {
        MASK_PAINT.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0})); // alpha = brightness
        MASK_PAINT.setBlendMode(android.graphics.BlendMode.DST_IN);
    }

    private void drawLayer(Canvas c, int i, Paint p, boolean live) {
        Layer l = layers.get(i);
        boolean masked = l.mask != null && l.maskOn;
        if (masked) { c.saveLayer(0, 0, docW, docH, p); p = new Paint(Paint.FILTER_BITMAP_FLAG); }
        c.drawBitmap(l.bmp, 0, 0, p);
        if (live && i == current && moving && floatBmp != null && !l.editMask) drawFloat(c, p);
        if (masked) { c.drawBitmap(l.mask.bmp, 0, 0, MASK_PAINT); c.restore(); }
    }

    /** Draws the floating (being transformed) content with uniform/freeform/distort/warp. */
    private void drawFloat(Canvas c, Paint p) {
        if (!smoothResample) { p = new Paint(p); p.setFilterBitmap(false); p.setAntiAlias(false); }
        c.save();
        if (xform == 3) {
            c.concat(layerMatrix());
            c.drawBitmapMesh(floatBmp, MESH, MESH, meshVerts(), 0, null, 0, p);
        } else {
            c.concat(transformMatrix());
            c.drawBitmap(floatBmp, fx0, fy0, p);
        }
        c.restore();
    }

    // ---------- Animation Assist ----------
    public boolean anim, animBackground, playing;
    public int onion = 2, fps = 12, playFrame;
    public float onionOpacity = 0.35f;

    /** Frame layer indices (layer 0 is excluded when it is the background). */
    public int firstFrame() { return anim && animBackground && layers.size() > 1 ? 1 : 0; }

    private void renderAnim(Canvas c) {
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        int f0 = firstFrame();
        if (f0 == 1 && layers.get(0).visible) drawLayer(c, 0, p, true);
        int cur = playing ? playFrame : Math.max(f0, current);
        if (!playing) for (int k = onion; k >= 1; k--) {
            float a = onionOpacity * (1 - (k - 1f) / (onion + 1));
            for (int idx : new int[]{cur - k, cur + k}) {
                if (idx < f0 || idx >= layers.size()) continue;
                p.setAlpha(Math.round(255 * a * (idx < cur ? 1f : 0.6f)));
                drawLayer(c, idx, p, false);
            }
        }
        if (cur < layers.size()) {
            p.setAlpha(layers.get(cur).opacity);
            p.setBlendMode(BLENDS[layers.get(cur).blend]);
            drawLayer(c, cur, p, !playing);
        }
    }

    /** One animation frame flattened (for GIF/MP4 export). */
    public Bitmap renderFrame(int idx, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(paperColor());
        c.scale((float) w / docW, (float) h / docH);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (firstFrame() == 1) drawLayer(c, 0, p, false);
        Layer l = layers.get(idx);
        p.setAlpha(l.opacity);
        p.setBlendMode(BLENDS[l.blend]);
        drawLayer(c, idx, p, false);
        return b;
    }

    /** Whole picture scaled down (time-lapse frames, reference window, gallery thumbnails). */
    public Bitmap renderSmall(int maxSide) {
        float s = Math.min(1f, (float) maxSide / Math.max(docW, docH));
        int w = Math.max(2, Math.round(docW * s) & ~1), h = Math.max(2, Math.round(docH * s) & ~1);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(paperColor());
        c.scale((float) w / docW, (float) h / docH);
        render(c, false);
        return b;
    }

    /** Draws the live picture into another canvas (reference window). */
    public void drawInto(Canvas c) {
        c.drawColor(paperColor());
        render(c, true);
    }

    public Runnable onFrame;

    private android.graphics.BitmapShader hatchShader;
    private boolean hatchStrong;
    private float hatchScale;

    /** Diagonal stripes, a fixed size on screen whatever the zoom. */
    private android.graphics.Shader hatch(boolean strong, float viewScale) {
        if (hatchShader == null || hatchStrong != strong || Math.abs(hatchScale - viewScale) > 0.01f) {
            int t = 16;
            Bitmap tile = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888);
            Canvas tc = new Canvas(tile);
            Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
            lp.setStrokeWidth(3);
            lp.setColor(strong ? 0x993D9BFF : 0x558E8E93);
            for (int k = -1; k <= 1; k++) tc.drawLine(k * t, t, k * t + t, 0, lp);
            hatchShader = new android.graphics.BitmapShader(tile, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
            Matrix m = new Matrix();
            m.setScale(1 / viewScale, 1 / viewScale);
            hatchShader.setLocalMatrix(m);
            hatchStrong = strong;
            hatchScale = viewScale;
        }
        return hatchShader;
    }

    public java.util.function.BiConsumer<Float, Float> onViewChanged; // zoom %, rotation degrees

    private float fitScale() { return Math.min((float) getWidth() / docW, (float) getHeight() / docH) * 0.96f; }

    // Transform snapping: centre/edges of the content lock to the canvas centre/edges
    public boolean snapping = true, smoothResample = true;
    private float snapX = Float.NaN, snapY = Float.NaN; // screen lines to show
    private float snapOffX, snapOffY;                    // current snap correction (kept separate from the finger)

    private void snapMove() {
        float[] q = handlePoints(); // corners on screen
        float minX = Math.min(Math.min(q[0], q[2]), Math.min(q[4], q[6])), maxX = Math.max(Math.max(q[0], q[2]), Math.max(q[4], q[6]));
        float minY = Math.min(Math.min(q[1], q[3]), Math.min(q[5], q[7])), maxY = Math.max(Math.max(q[1], q[3]), Math.max(q[5], q[7]));
        float[] c = {0, 0, docW / 2f, docH / 2f, docW, docH};
        view.mapPoints(c);
        float tol = 10 * getResources().getDisplayMetrics().density;
        snapX = snapY = Float.NaN;
        float[][] xs = {{(minX + maxX) / 2, c[2]}, {minX, c[0]}, {maxX, c[4]}};
        for (float[] k : xs) if (Math.abs(k[0] - k[1]) < tol) { snapOffX = k[1] - k[0]; moveT.postTranslate(snapOffX, 0); snapX = k[1]; break; }
        float[][] ys = {{(minY + maxY) / 2, c[3]}, {minY, c[1]}, {maxY, c[5]}};
        for (float[] k : ys) if (Math.abs(k[0] - k[1]) < tol) { snapOffY = k[1] - k[0]; moveT.postTranslate(0, snapOffY); snapY = k[1]; break; }
    }

    private void drawSnapLines(Canvas c) {
        if (!moving || (Float.isNaN(snapX) && Float.isNaN(snapY))) return;
        Paint p = new Paint();
        p.setColor(0xFFFFB020);
        p.setStrokeWidth(2);
        if (!Float.isNaN(snapX)) c.drawLine(snapX, 0, snapX, getHeight(), p);
        if (!Float.isNaN(snapY)) c.drawLine(0, snapY, getWidth(), snapY, p);
    }

    /** Called when a new touch starts; return true to swallow it (e.g. it just closed a menu). */
    public java.util.function.BooleanSupplier onTouchStart;
    public boolean showCursor;                   // Prefs: brush outline under the pen
    private float curSX = -1, curSY = -1;
    private boolean cursorOn;

    @Override
    public boolean onHoverEvent(MotionEvent e) {
        if (!showCursor) return super.onHoverEvent(e);
        curSX = e.getX(); curSY = e.getY();
        cursorOn = e.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT;
        invalidate();
        return true;
    }

    private void drawCursor(Canvas c) {
        if (!showCursor || !cursorOn || !(tool == DRAW || tool == ERASE || tool == SMUDGE || tool == CLONE)) return;
        float r = Math.max(2f, size * brush.sizeScale / 2f * view.mapRadius(1f));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(0x99000000);
        c.drawCircle(curSX, curSY, r, p);
        p.setStrokeWidth(1.2f);
        p.setColor(0xEEFFFFFF);
        c.drawCircle(curSX, curSY, r, p);
    }
    private boolean swallow;

    @Override
    protected void onDraw(Canvas c) {
        if (docW == 0) return;
        float sc = view.mapRadius(1f);
        c.save();
        c.concat(view);
        c.clipRect(0, 0, docW, docH);
        if (bgVisible) { paper.setColor(paperColor()); c.drawRect(0, 0, docW, docH, paper); }
        else c.drawRect(0, 0, docW, docH, checkerPaint(sc));
        render(c, true);
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setStyle(Paint.Style.STROKE);
        g.setStrokeWidth(guideThick / sc);
        if (guide == 6) { // isometric
            g.setColor(guideColor(1));
            float big = (float) Math.hypot(docW, docH);
            for (float ang : new float[]{30, 150, 90}) {
                c.save();
                c.rotate(ang, docW / 2f, docH / 2f);
                for (float y = docH / 2f - big; y < docH / 2f + big; y += gridSize) c.drawLine(docW / 2f - big, y, docW / 2f + big, y, g);
                c.restore();
            }
        } else if (guide >= 7) { // perspective
            int n = guide - 6;
            float big = 3 * (float) Math.hypot(docW, docH);
            g.setColor(guideColor(1));
            for (int k = 0; k < n; k++)
                for (int r = 0; r < vpRays; r++) {
                    double a = Math.PI * 2 * r / vpRays;
                    c.drawLine(vp[k * 2], vp[k * 2 + 1], vp[k * 2] + (float) Math.cos(a) * big, vp[k * 2 + 1] + (float) Math.sin(a) * big, g);
                }
            // horizon line and the points are drawn after the canvas clip (see drawPerspectiveHandles)
        } else if (guide == 1) { // grid
            g.setColor(guideColor(1));
            for (int x = gridSize; x < docW; x += gridSize) c.drawLine(x, 0, x, docH, g);
            for (int y = gridSize; y < docH; y += gridSize) c.drawLine(0, y, docW, y, g);
        } else if (guide >= 2 && guide <= 5) { // symmetry axes
            g.setColor(guideColor(2.2f));
            float cx = docW / 2f, cy = docH / 2f;
            if (guide == 2 || guide == 4) c.drawLine(cx, 0, cx, docH, g);
            if (guide == 3 || guide == 4) c.drawLine(0, cy, docW, cy, g);
            if (guide == 5) for (int k = 0; k < 4; k++) {
                double a = Math.PI * k / 4, r = Math.hypot(docW, docH);
                c.drawLine(cx - (float) (Math.cos(a) * r), cy - (float) (Math.sin(a) * r), cx + (float) (Math.cos(a) * r), cy + (float) (Math.sin(a) * r), g);
            }
        }
        if (selOverlay != null) { // diagonal hatching over what is NOT selected (stronger while selecting)
            Paint o = new Paint(Paint.FILTER_BITMAP_FLAG);
            o.setShader(hatch(tool == SELECT, sc));
            c.drawBitmap(selOverlay, 0, 0, o);
        }
        drawAnts(c, sc);
        if (selPath != null) {
            if (polyOpen) { // corners of a tap-polygon
                Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                dot.setColor(0xFF3D9BFF);
                c.drawCircle(polyX0, polyY0, 8 / sc, dot);
            }
            g.setColor(0xFF3D9BFF);
            g.setStrokeWidth(2f / sc);
            c.drawPath(selPath, g);
        }
        if (tool == CLONE && cloneSet) {
            float x = cloneSrcX, y = cloneSrcY;
            if (stroking && cloneOffSet) { x = curX + cloneOX; y = curY + cloneOY; }
            g.setColor(0xFF3D9BFF);
            g.setStrokeWidth(2f / sc);
            c.drawCircle(x, y, Math.max(size / 2, 8 / sc), g);
            c.drawLine(x - 12 / sc, y, x + 12 / sc, y, g);
            c.drawLine(x, y - 12 / sc, x, y + 12 / sc, g);
        }
        c.restore();
        if (guide >= 7) drawPerspectiveHandles(c);
        drawShapeHandles(c);
        drawCursor(c);
        drawSnapLines(c);
        if (onFrame != null) onFrame.run();
        if (tool == MOVE && moving) { // transform box + handles (screen space)
            float[] q = handlePoints();
            g.setStrokeWidth(1.5f);
            g.setColor(0xFF3D9BFF);
            if (xform == 3) { // warp grid
                for (int r = 0; r < 4; r++) for (int k = 0; k < 3; k++) {
                    int a = r * 4 + k, b = a + 1, a2 = k * 4 + r, b2 = a2 + 4;
                    c.drawLine(q[a * 2], q[a * 2 + 1], q[b * 2], q[b * 2 + 1], g);
                    c.drawLine(q[a2 * 2], q[a2 * 2 + 1], q[b2 * 2], q[b2 * 2 + 1], g);
                }
            } else {
                Path box = new Path();
                box.moveTo(q[0], q[1]); box.lineTo(q[2], q[3]); box.lineTo(q[4], q[5]); box.lineTo(q[6], q[7]); box.close();
                c.drawPath(box, g);
            }
            float hr = 8 * getResources().getDisplayMetrics().density;
            for (int k = 0; k < q.length / 2; k++) {
                g.setStyle(Paint.Style.FILL);
                g.setColor(xform == 0 ? Color.WHITE : 0xFF3D9BFF);
                c.drawCircle(q[k * 2], q[k * 2 + 1], hr, g);
                g.setStyle(Paint.Style.STROKE);
                g.setColor(xform == 0 ? 0xFF3D9BFF : Color.WHITE);
                c.drawCircle(q[k * 2], q[k * 2 + 1], hr, g);
            }
        }
    }

    // ---------- Input ----------

    // ---- Procreate-style extras ----
    public Runnable onGreyPicked, onToggleUi, onPicked, onCopyMenu;
    private float gSwipeY;
    public boolean pickNext; // eyedropper button armed
    private boolean picking, shaping, shapeClosed, holdUndoing, scrubbed;
    private float downSX, downSY, holdSX, holdSY, curX, curY, gScale = 1f;
    private float[] spts = new float[300];
    private int sn, gCount, scrubDir, scrubFlips;
    private float pathLen, scrubX, shapeP, ecx, ecy, erx, ery;

    /** Touch and hold: eyedropper. */
    private final Runnable pickHold = () -> {
        if (!stroking && !gesture) return;
        abortStroke();
        picking = true;
        pickAt(downSX, downSY);
    };

    /** Draw and hold: QuickShape (line, ellipse/circle or rectangle). */
    private final Runnable shapeHold = () -> {
        if (!stroking || shaping || sn < 6 || pathLen < 12) return;
        shaping = true;
        shapeConstrain = false;
        float sum = 0, x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int k = 0; k < sn; k += 3) {
            x0 = Math.min(x0, spts[k]); x1 = Math.max(x1, spts[k]);
            y0 = Math.min(y0, spts[k + 1]); y1 = Math.max(y1, spts[k + 1]);
            sum += spts[k + 2];
        }
        shapeP = sum / (sn / 3);
        float gap = (float) Math.hypot(curX - spts[0], curY - spts[1]);
        shapeClosed = gap < pathLen * 0.15f && (x1 - x0) > 4 && (y1 - y0) > 4;
        ecx = (x0 + x1) / 2; ecy = (y0 + y1) / 2; erx = (x1 - x0) / 2; ery = (y1 - y0) / 2;
        shapeKind = 0;
        if (shapeClosed) { // ellipse or rectangle: whichever the drawn points fit better
            float ellErr = 0, rectErr = 0;
            int n = 0;
            for (int k = 0; k < sn; k += 3, n++) {
                float nx = (spts[k] - ecx) / Math.max(1, erx), ny = (spts[k + 1] - ecy) / Math.max(1, ery);
                ellErr += Math.abs((float) Math.sqrt(nx * nx + ny * ny) - 1);
                float de = Math.min(Math.min(spts[k] - x0, x1 - spts[k]), Math.min(spts[k + 1] - y0, y1 - spts[k + 1]));
                rectErr += de / Math.max(1, Math.min(erx, ery));
            }
            shapeKind = rectErr < ellErr * 0.8f ? 2 : 1;
            quad = new float[]{x0, y0, x1, y0, x1, y1, x0, y1};
        }
        lineX0 = spts[0]; lineY0 = spts[1];
        renderShape();
    };

    public int undoHoldDelay = 600; // Prefs: how long to hold two fingers before rapid undo

    /** Two-finger hold: keep undoing. */
    private final Runnable undoHold = new Runnable() {
        public void run() {
            if (!gesture || gCount != 2 || gMax != 2 || gMoved > 30) return;
            holdUndoing = true;
            undo();
            postDelayed(this, 150);
        }
    };

    private void addPt(float x, float y, float p) {
        if (sn + 3 > spts.length) spts = java.util.Arrays.copyOf(spts, spts.length * 2);
        if (sn > 0) pathLen += (float) Math.hypot(x - spts[sn - 3], y - spts[sn - 2]);
        spts[sn++] = x; spts[sn++] = y; spts[sn++] = p;
        curX = x; curY = y;
    }

    private void startAt(float x, float y, float p) {
        lx = x; ly = y; lp = p;
        arc = 0; dabArc = 0;
        stamp(x, y, p);
        nextDab = step(p);
    }

    /** Undo the stroke's pixels so far, keeping the stroke active. */
    private void restoreStroke() {
        if (!eventDirty.isEmpty()) { strokeDirty.union(eventDirty); eventDirty.setEmpty(); }
        if (strokeDirty.isEmpty()) return;
        clearMask(strokeDirty);
        new Canvas(tgt().bmp).drawBitmap(backup, strokeDirty, strokeDirty, copy);
        strokeDirty.setEmpty();
    }

    // QuickShape state: 0 line, 1 ellipse, 2 rectangle (quad corners, editable)
    private int shapeKind;
    private boolean shapeConstrain, shapeEdit, wasShape;
    private float lineX0, lineY0, lineX1, lineY1;
    private float[] quad = new float[8];
    private int shapeDrag = -1;

    private void renderShape() {
        restoreStroke();
        if (shapeKind == 1) {
            float rx = erx, ry = ery;
            if (shapeConstrain) { rx = ry = Math.max(erx, ery); erx = ery = rx; } // perfect circle
            startAt(ecx + rx, ecy, shapeP);
            int n = 120;
            for (int k = 1; k <= n; k++) {
                double t = 2 * Math.PI * k / n;
                segmentTo(ecx + rx * (float) Math.cos(t), ecy + ry * (float) Math.sin(t), shapeP);
            }
        } else if (shapeKind == 2) {
            if (shapeConstrain && !shapeEdit) { // perfect square
                float side = Math.max(quad[2] - quad[0], quad[5] - quad[1]);
                quad = new float[]{quad[0], quad[1], quad[0] + side, quad[1], quad[0] + side, quad[1] + side, quad[0], quad[1] + side};
            }
            startAt(quad[0], quad[1], shapeP);
            for (int k = 1; k <= 4; k++) segmentTo(quad[(k % 4) * 2], quad[(k % 4) * 2 + 1], shapeP);
        } else {
            if (!shapeEdit) { lineX1 = curX; lineY1 = curY; }
            if (shapeConstrain) { // snap the angle to 15° steps
                float dx = lineX1 - lineX0, dy = lineY1 - lineY0, len = (float) Math.hypot(dx, dy);
                double a = Math.round(Math.atan2(dy, dx) / (Math.PI / 12)) * (Math.PI / 12);
                lineX1 = lineX0 + (float) Math.cos(a) * len; lineY1 = lineY0 + (float) Math.sin(a) * len;
            }
            startAt(lineX0, lineY0, shapeP);
            segmentTo(lineX1, lineY1, shapeP);
        }
        flush();
    }

    /** Handle positions (canvas coords) for editing the shape after lifting the pen. */
    private float[] shapeHandles() {
        if (shapeKind == 0) return new float[]{lineX0, lineY0, lineX1, lineY1};
        if (shapeKind == 1) return new float[]{ecx + erx, ecy, ecx - erx, ecy, ecx, ecy + ery, ecx, ecy - ery};
        return quad.clone();
    }

    private boolean shapeHandleTouch(MotionEvent e, int action) {
        view.invert(inverse);
        float[] f = {e.getX(), e.getY()};
        inverse.mapPoints(f);
        if (action == MotionEvent.ACTION_DOWN) {
            float[] h = shapeHandles();
            float best = 28 * getResources().getDisplayMetrics().density / view.mapRadius(1f);
            shapeDrag = -1;
            for (int k = 0; k < h.length / 2; k++) {
                float d = (float) Math.hypot(f[0] - h[k * 2], f[1] - h[k * 2 + 1]);
                if (d < best) { best = d; shapeDrag = k; }
            }
            return shapeDrag >= 0;
        }
        if (action == MotionEvent.ACTION_MOVE && shapeDrag >= 0) {
            if (shapeKind == 0) { if (shapeDrag == 0) { lineX0 = f[0]; lineY0 = f[1]; } else { lineX1 = f[0]; lineY1 = f[1]; } }
            else if (shapeKind == 1) { if (shapeDrag < 2) erx = Math.max(2, Math.abs(f[0] - ecx)); else ery = Math.max(2, Math.abs(f[1] - ecy)); }
            else { quad[shapeDrag * 2] = f[0]; quad[shapeDrag * 2 + 1] = f[1]; }
            renderShape();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) shapeDrag = -1;
        return true;
    }

    /** Keeps the edited shape as a normal stroke. */
    public void finishShapeEdit() {
        if (!shapeEdit) return;
        shapeEdit = false;
        shapeDrag = -1;
        wasShape = true;
        commitStroke();
        wasShape = false;
        shaping = false;
        endFingerErase();
        invalidate();
    }

    public boolean editingShape() { return shapeEdit; }

    private void drawShapeHandles(Canvas c) {
        if (!shapeEdit) return;
        float[] h = shapeHandles();
        Matrix m = new Matrix(view);
        m.mapPoints(h);
        float r = 9 * getResources().getDisplayMetrics().density;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int k = 0; k < h.length / 2; k++) {
            p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE);
            c.drawCircle(h[k * 2], h[k * 2 + 1], r, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(0xFF3D9BFF);
            c.drawCircle(h[k * 2], h[k * 2 + 1], r, p);
        }
    }

    /** Sets the ink grey to the composited colour under a screen point. */
    private void pickAt(float sx, float sy) {
        view.invert(inverse);
        map(sx, sy);
        int x = (int) pt[0], y = (int) pt[1];
        if (x < 0 || y < 0 || x >= docW || y >= docH) return;
        float g = paperColor() & 255;
        for (Layer l : layers) {
            if (!l.visible) continue;
            int px = l.bmp.getPixel(x, y);
            float a = (px >>> 24) / 255f * l.opacity / 255f;
            g = (px & 255) * a + g * (1 - a);
        }
        grey = Math.round(g);
        if (onGreyPicked != null) onGreyPicked.run();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean r = touch(e);
        int a = e.getActionMasked();
        if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && !shapeEdit && !stroking) endFingerErase();
        return r;
    }

    private boolean touch(MotionEvent e) {
        if (docW == 0) return false;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) swallow = onTouchStart != null && onTouchStart.getAsBoolean();
        if (swallow) { // this touch only closed a panel: don't draw with it
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallow = false;
            return true;
        }
        int type = e.getToolType(0);
        boolean isPen = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER;
        if (isPen) stylusSeen = true;
        if (showCursor) { // outline follows the pen while drawing
            curSX = e.getX(); curSY = e.getY();
            cursorOn = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL || isPen;
        }
        if (shapeEdit && action == MotionEvent.ACTION_DOWN && e.getPointerCount() == 1 && shapeHandleTouch(e, action)) return true;
        if (shapeEdit && shapeDrag >= 0) return shapeHandleTouch(e, action);
        if (shapeEdit && action == MotionEvent.ACTION_DOWN) finishShapeEdit(); // touch elsewhere: keep the shape and carry on
        if (shaping && stroking && e.getPointerCount() >= 2) { // second finger while QuickShape: constrain
            shapeConstrain = true;
            view.invert(inverse);
            pt[0] = e.getX(0); pt[1] = e.getY(0); inverse.mapPoints(pt);
            curX = pt[0]; curY = pt[1];
            renderShape();
            return true;
        }
        if (fillMode && action == MotionEvent.ACTION_DOWN && e.getPointerCount() == 1 && (tool == DRAW || tool == ERASE)) {
            view.invert(inverse);
            pt[0] = e.getX(); pt[1] = e.getY(); inverse.mapPoints(pt);
            colorDropDoc((int) pt[0], (int) pt[1], fillThreshold);
            return true;
        }
        if (fillMode && action != MotionEvent.ACTION_DOWN && !gesture && e.getPointerCount() == 1 && (tool == DRAW || tool == ERASE)) return true;

        if (pickNext && action == MotionEvent.ACTION_DOWN) { // eyedropper button armed
            pickNext = false;
            picking = true;
            stroking = false;
            pickAt(e.getX(), e.getY());
            if (onPicked != null) onPicked.run();
            return true;
        }
        if (picking && !gesture) {
            if (action == MotionEvent.ACTION_MOVE) pickAt(e.getX(), e.getY());
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) picking = false;
            return true;
        }
        if (editGuide && guide >= 7 && e.getPointerCount() == 1 && !gesture) return vpTouch(e, action);
        if (tool == MOVE) {
            if (xform > 0 && e.getPointerCount() == 1 && !gesture && handleDrag(e, action)) return true;
            return handleGesture(e, action, moveT);
        }
        if ((e.getPointerCount() >= 2 || gesture) && shapeEdit) finishShapeEdit();
        if (e.getPointerCount() >= 2 || gesture) return handleGesture(e, action, view);
        if (tool == SELECT) return selectTouch(e, action);
        if (tool == ADJUST && !adjPencil) return adjustTouch(e, action);
        if (stylusSeen && fingerMode != 1 && type == MotionEvent.TOOL_TYPE_FINGER) {
            boolean paintTool = tool == DRAW || tool == SMUDGE || tool == ERASE || tool == CLONE;
            if (fingerMode != 2 || !paintTool) return handleGesture(e, action, view);
            if (action == MotionEvent.ACTION_DOWN && !fingerErasing && tool != ERASE) {
                fingerErasing = true;
                if (onFingerErase != null) onFingerErase.accept(true);
            }
        }
        if (tool == LIQUIFY) return liquifyTouch(e, action);
        if (tool == CLONE && !fingerErasing && action == MotionEvent.ACTION_DOWN && (!cloneSet || cloneArmed)) {
            view.invert(inverse);
            map(e.getX(), e.getY());
            cloneSrcX = pt[0]; cloneSrcY = pt[1];
            cloneSet = true; cloneArmed = false; cloneOffSet = false;
            if (onInfo != null) onInfo.accept("Source set. Now paint to clone.");
            invalidate();
            return true;
        }

        view.invert(inverse);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (isLocked()) { stroking = false; return true; }
                erasing = tool == ERASE || fingerErasing || type == MotionEvent.TOOL_TYPE_ERASER
                        || (e.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0;
                smudging = tool == SMUDGE && !erasing;
                cloning = tool == CLONE && !erasing;
                adjPaint = tool == ADJUST && adjPencil && adjOut != null;
                if (adjPaint) { erasing = false; smudging = false; }
                penInput = isPen;
                stroking = true;
                picking = shaping = false;
                strokeDirty.setEmpty();
                sn = 0; pathLen = 0;
                downSX = holdSX = e.getX(); downSY = holdSY = e.getY();
                asDir = false;
                view.invert(inverse);
                pt[0] = e.getX(); pt[1] = e.getY(); inverse.mapPoints(pt);
                asX = pt[0]; asY = pt[1];
                map(e.getX(), e.getY());
                if (cloning && !cloneOffSet) { cloneOX = cloneSrcX - pt[0]; cloneOY = cloneSrcY - pt[1]; cloneOffSet = true; }
                smX = pt[0]; smY = pt[1];
                addPt(pt[0], pt[1], e.getPressure());
                smoothReset(pt[0], pt[1]);
                record(pt[0], pt[1], e.getPressure());
                startAt(pt[0], pt[1], e.getPressure());
                flush();
                if (!isPen) postDelayed(pickHold, 450);
                postDelayed(shapeHold, 550);
                break;
            case MotionEvent.ACTION_MOVE: {
                if (picking) { pickAt(e.getX(), e.getY()); break; }
                if (!stroking) break;
                if (Math.hypot(e.getX() - downSX, e.getY() - downSY) > 12) removeCallbacks(pickHold);
                if (shaping) {
                    map(e.getX(), e.getY());
                    curX = pt[0]; curY = pt[1];
                    if (!shapeClosed) renderShape();
                    break;
                }
                for (int i = 0; i < e.getHistorySize(); i++) {
                    map(e.getHistoricalX(i), e.getHistoricalY(i));
                    feed(e.getHistoricalPressure(i));
                }
                map(e.getX(), e.getY());
                feed(e.getPressure());
                flush();
                if (Math.hypot(e.getX() - holdSX, e.getY() - holdSY) > 6) {
                    holdSX = e.getX(); holdSY = e.getY();
                    removeCallbacks(shapeHold);
                    postDelayed(shapeHold, 550);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(pickHold);
                removeCallbacks(shapeHold);
                if (picking) picking = false;
                else if (stroking) {
                    if (shaping) { shapeEdit = true; shapeConstrain = false; invalidate(); break; } // adjust with handles, tap elsewhere to keep
                    finishSmoothing(e);
                    commitStroke();
                }
                shaping = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(pickHold);
                removeCallbacks(shapeHold);
                picking = shaping = false;
                abortStroke();
                break;
        }
        return true;
    }

    private void map(float x, float y) { pt[0] = x; pt[1] = y; inverse.mapPoints(pt); assistPt(); }

    /** Drawing assist: lock the stroke to the nearest guide direction (grid, isometric or perspective). */
    private void assistPt() {
        if (!stroking || !assist || !(guide == 1 || guide >= 6) || shaping) return;
        float x = pt[0], y = pt[1];
        if (!asDir) {
            float dx = x - asX, dy = y - asY;
            if (Math.hypot(dx, dy) < 10 / view.mapRadius(1f)) { pt[0] = asX; pt[1] = asY; return; }
            java.util.ArrayList<float[]> dirs = new java.util.ArrayList<>();
            if (guide == 1) { dirs.add(new float[]{1, 0}); dirs.add(new float[]{0, 1}); }
            else if (guide == 6) for (float a : new float[]{30, 150, 90}) {
                double r = Math.toRadians(a); dirs.add(new float[]{(float) Math.cos(r), (float) Math.sin(r)});
            } else {
                int n = guide - 6;
                for (int k = 0; k < n; k++) dirs.add(new float[]{vp[k * 2] - asX, vp[k * 2 + 1] - asY});
                if (n <= 2) dirs.add(new float[]{0, 1});
                if (n == 1) dirs.add(new float[]{1, 0});
            }
            float best = -1;
            for (float[] d : dirs) {
                float len = (float) Math.hypot(d[0], d[1]);
                if (len < 1e-3f) continue;
                float c = Math.abs((d[0] * dx + d[1] * dy) / (len * (float) Math.hypot(dx, dy)));
                if (c > best) { best = c; adX = d[0] / len; adY = d[1] / len; }
            }
            asDir = best >= 0;
            if (!asDir) return;
        }
        float t = (x - asX) * adX + (y - asY) * adY;
        pt[0] = asX + adX * t; pt[1] = asY + adY * t;
    }

    /** Horizon line, vanishing points and the horizon handle, drawn beyond the canvas edge too. */
    private void drawPerspectiveHandles(Canvas c) {
        float sc = view.mapRadius(1f), d = getResources().getDisplayMetrics().density;
        int n = guide - 6;
        float big = 6 * (float) Math.hypot(docW, docH);
        c.save();
        c.concat(view);
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setStyle(Paint.Style.STROKE);
        g.setStrokeWidth(Math.max(1.5f, guideThick) / sc);
        g.setColor(guideColor(2.4f) | 0x66000000);
        float[] h = horizon();
        c.drawLine(h[0] - h[2] * big, h[1] - h[3] * big, h[0] + h[2] * big, h[1] + h[3] * big, g);
        float r = (editGuide ? 11 : 6) * d / sc;
        for (int k = 0; k < n; k++) {
            g.setStyle(Paint.Style.FILL);
            g.setColor(0xFF3D9BFF);
            c.drawCircle(vp[k * 2], vp[k * 2 + 1], r, g);
            g.setStyle(Paint.Style.STROKE);
            g.setStrokeWidth(2 * d / sc);
            g.setColor(Color.WHITE);
            c.drawCircle(vp[k * 2], vp[k * 2 + 1], r, g);
        }
        if (editGuide && n >= 2) { // horizon handle in the middle
            float[] m = horizonHandle();
            g.setStyle(Paint.Style.FILL);
            g.setColor(Color.WHITE);
            c.drawRect(m[0] - r * 0.8f, m[1] - r * 0.8f, m[0] + r * 0.8f, m[1] + r * 0.8f, g);
            g.setStyle(Paint.Style.STROKE);
            g.setColor(0xFF3D9BFF);
            c.drawRect(m[0] - r * 0.8f, m[1] - r * 0.8f, m[0] + r * 0.8f, m[1] + r * 0.8f, g);
        }
        c.restore();
    }

    /** Horizon as point + unit direction: through the first two points, or level through the single point. */
    private float[] horizon() {
        if (guide - 6 < 2) return new float[]{vp[0], vp[1], 1, 0};
        float dx = vp[2] - vp[0], dy = vp[3] - vp[1], len = (float) Math.hypot(dx, dy);
        if (len < 1e-3f) return new float[]{vp[0], vp[1], 1, 0};
        return new float[]{vp[0], vp[1], dx / len, dy / len};
    }

    /** The middle of the horizon, kept on screen-ish so it can always be grabbed. */
    private float[] horizonHandle() {
        float[] h = horizon();
        float mx = (vp[0] + vp[2]) / 2, my = (vp[1] + vp[3]) / 2;
        // slide along the horizon towards the canvas centre so it stays reachable
        float t = (docW / 2f - mx) * h[2] + (docH / 2f - my) * h[3];
        return new float[]{mx + h[2] * t, my + h[3] * t};
    }

    public int fitInsetBottom;

    /** Zooms so the canvas and every vanishing point are visible. */
    public void fitGuide() {
        if (guide < 7) { animateFit(); return; }
        RectF r = new RectF(0, 0, docW, docH);
        for (int k = 0; k < guide - 6; k++) r.union(vp[k * 2], vp[k * 2 + 1]);
        r.inset(-r.width() * 0.08f, -r.height() * 0.08f);
        float avH = Math.max(1, getHeight() - fitInsetBottom); // space above the editor bar
        float s = Math.min(getWidth() / r.width(), avH / r.height());
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((getWidth() - r.width() * s) / 2f - r.left * s, (avH - r.height() * s) / 2f - r.top * s);
        float[] a = new float[9], b = new float[9], cc = new float[9];
        view.getValues(a);
        m.getValues(b);
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(250);
        va.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            for (int k = 0; k < 9; k++) cc[k] = a[k] + (b[k] - a[k]) * t;
            view.setValues(cc);
            invalidate();
        });
        va.start();
    }

    private float vpDownX, vpDownY;
    private float[] vpGrab = new float[2];
    private boolean vpMoved;
    private float[] vpBefore;

    /**
     * Editing perspective: drag a point to move it, tap a point to delete it,
     * tap empty canvas to add one (up to 3). Every change is an undo step.
     */
    private boolean vpTouch(MotionEvent e, int action) {
        view.invert(inverse);
        float[] f = {e.getX(), e.getY()};
        inverse.mapPoints(f);
        int n = guide - 6;
        if (action == MotionEvent.ACTION_DOWN) {
            dragVp = -1;
            vpMoved = false;
            vpDownX = e.getX(); vpDownY = e.getY();
            vpBefore = vp.clone();
            float best = 40 / view.mapRadius(1f) * getResources().getDisplayMetrics().density;
            for (int k = 0; k < n; k++) {
                float d = (float) Math.hypot(f[0] - vp[k * 2], f[1] - vp[k * 2 + 1]);
                if (d < best) { best = d; dragVp = k; }
            }
            if (n >= 2) { // horizon handle
                float[] m = horizonHandle();
                if (Math.hypot(f[0] - m[0], f[1] - m[1]) < best) { dragVp = -2; vpGrab = new float[]{f[0], f[1]}; }
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (Math.hypot(e.getX() - vpDownX, e.getY() - vpDownY) > 8 * getResources().getDisplayMetrics().density) vpMoved = true;
            if (dragVp == -2 && vpMoved) { // move the whole horizon (both points) up/down, or along with the finger if tilted
                float dy = f[1] - vpGrab[1], dx = horizonLevel ? 0 : f[0] - vpGrab[0];
                vp[0] = vpBefore[0] + dx; vp[1] = vpBefore[1] + dy; vp[2] = vpBefore[2] + dx; vp[3] = vpBefore[3] + dy;
                invalidate();
            } else if (dragVp >= 0 && vpMoved) {
                vp[dragVp * 2] = f[0]; vp[dragVp * 2 + 1] = f[1];
                if (horizonLevel && n >= 2 && dragVp < 2) vp[(1 - dragVp) * 2 + 1] = f[1]; // keep the horizon straight
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP) {
            int gBefore = guide;
            if (dragVp == -2) { /* horizon handle: nothing on tap */ }
            else if (dragVp >= 0 && !vpMoved && n > 1) { // tap a point: delete it
                float[] nv = new float[6];
                for (int k = 0, j = 0; k < n; k++) if (k != dragVp) { nv[j * 2] = vp[k * 2]; nv[j * 2 + 1] = vp[k * 2 + 1]; j++; }
                vp = nv;
                guide = 6 + n - 1;
            } else if (dragVp == -1 && !vpMoved && n < 3) { // tap empty canvas: add a point
                vp[n * 2] = f[0]; vp[n * 2 + 1] = (horizonLevel && n == 1) ? vp[1] : f[1]; // 2nd point sits on the horizon
                guide = 6 + n + 1;
            }
            if (guide != gBefore || !java.util.Arrays.equals(vp, vpBefore)) guideEdit(vpBefore, gBefore);
            dragVp = -1;
        } else dragVp = -1;
        return true;
    }

    /** Records a perspective change (points / number of points) as an undo step. */
    public void guideEdit(float[] beforeVp, int beforeGuide) {
        named("Drawing guide");
        float[] b = beforeVp.clone(), a = vp.clone();
        int ga = guide;
        push(new Op(() -> { vp = b.clone(); guide = beforeGuide; invalidate(); },
                    () -> { vp = a.clone(); guide = ga; invalidate(); }, 64));
        changed();
    }

    /** Guide settings as text (saved with each drawing). */
    public String guideState() {
        StringBuilder sb = new StringBuilder();
        sb.append(guide).append(',').append(gridSize).append(',').append(assist).append(',')
          .append(guideOpacity).append(',').append(guideThick).append(',').append(vpRays);
        for (float v : vp) sb.append(',').append(v);
        sb.append(',').append(horizonLevel);
        return sb.toString();
    }

    public void setGuideState(String s) {
        try {
            String[] v = s.trim().split(",");
            guide = Integer.parseInt(v[0]); gridSize = Integer.parseInt(v[1]); assist = Boolean.parseBoolean(v[2]);
            guideOpacity = Float.parseFloat(v[3]); guideThick = Float.parseFloat(v[4]); vpRays = Integer.parseInt(v[5]);
            for (int i = 0; i < 6; i++) vp[i] = Float.parseFloat(v[6 + i]);
            horizonLevel = v.length <= 12 || Boolean.parseBoolean(v[12]);
        } catch (Exception ignored) { }
        invalidate();
    }

    // ---------- Brush engine ----------
    // Each stroke stamps round "dabs" into a coverage mask (max, so overlaps don't darken),
    // then the mask is composited onto the layer's pre-stroke pixels with the ink grey.

    public float globalCurve = 1f, globalSmooth = 0f; // Prefs: pressure and smoothing for every brush

    private float pres(float p) {
        if (!penInput) return 1f;
        float q = Math.max(0f, Math.min(p, 1f));
        float c = brush.curve * globalCurve;
        return c == 1f ? q : (float) Math.pow(q, c); // pressure curve
    }

    private float diameter(float p) {
        return Math.max(1f, size * brush.sizeScale * (brush.minSize + (1 - brush.minSize) * pres(p)));
    }

    // Smoothing (StreamLine + Stabilization), taper, jitter state for the current stroke
    private float smoX, smoY, arc, dabArc, replayLen, strokeAng;
    private final float[] stabX = new float[24], stabY = new float[24];
    private int stabN, stabI;
    private long strokeSeed;
    private java.util.Random rnd = new java.util.Random();
    private float[] rec = new float[300];
    private int recN;

    private void smoothReset(float x, float y) {
        smoX = x; smoY = y; stabN = 0; stabI = 0; arc = 0; recN = 0;
        strokeSeed = System.nanoTime();
        rnd = new java.util.Random(strokeSeed);
    }

    /** Returns the smoothed position for a new input point (in pt[]). */
    private void smooth(float x, float y) {
        Brush b = brush;
        int n = Math.round(b.stabilize * 16);
        if (n > 1) { // stabilization: average of the last n points
            stabX[stabI] = x; stabY[stabI] = y;
            stabI = (stabI + 1) % Math.min(n, stabX.length);
            stabN = Math.min(stabN + 1, Math.min(n, stabX.length));
            float sx = 0, sy = 0;
            for (int i = 0; i < stabN; i++) { sx += stabX[i]; sy += stabY[i]; }
            x = sx / stabN; y = sy / stabN;
        }
        float sl = 1 - (1 - b.streamline) * (1 - globalSmooth);
        float k = 1 - 0.92f * sl; // streamline: the line follows the pen with a pull
        smoX += (x - smoX) * k;
        smoY += (y - smoY) * k;
        pt[0] = smoX; pt[1] = smoY;
    }

    /** Raw point in pt[] -> QuickShape record, smoothing, then the brush. */
    private void feed(float p) {
        addPt(pt[0], pt[1], p);
        lastRawX = pt[0]; lastRawY = pt[1]; lastP = p;
        smooth(pt[0], pt[1]);
        record(pt[0], pt[1], p);
        segmentTo(pt[0], pt[1], p);
    }

    private float lastRawX, lastRawY, lastP;

    /** At pen lift: let the smoothed line catch up to where the pen ended, then apply the end taper. */
    private void finishSmoothing(MotionEvent e) {
        if (recN >= 3 && (brush.streamline > 0 || brush.stabilize > 0 || globalSmooth > 0)) {
            float tx = lastRawX, ty = lastRawY;
            float sx = rec[recN - 3], sy = rec[recN - 2];
            int steps = 6;
            for (int i = 1; i <= steps; i++) {
                float t = i / (float) steps;
                float x = sx + (tx - sx) * t, y = sy + (ty - sy) * t;
                record(x, y, lastP);
                segmentTo(x, y, lastP);
            }
        }
        flush();
        if (brush.taperEnd > 0) replayWithTaper();
    }

    private void record(float x, float y, float p) {
        if (recN + 3 > rec.length) rec = java.util.Arrays.copyOf(rec, rec.length * 2);
        rec[recN++] = x; rec[recN++] = y; rec[recN++] = p;
    }

    /** Size multiplier from start/end taper at the current dab. */
    private float taper() {
        Brush b = brush;
        float m = 1f;
        if (b.taperStart > 0) m = Math.min(m, dabArc / (b.taperStart * size * 8 + 1));
        if (b.taperEnd > 0 && replayLen > 0) m = Math.min(m, (replayLen - dabArc) / (b.taperEnd * size * 8 + 1));
        return Math.max(0.06f, Math.min(1f, m));
    }

    /** Redraws the recorded (smoothed) stroke so the end taper can be applied. */
    private void replayWithTaper() {
        if (recN < 6) return;
        float total = 0;
        for (int i = 3; i < recN; i += 3) total += (float) Math.hypot(rec[i] - rec[i - 3], rec[i + 1] - rec[i - 2]);
        restoreStroke();
        rnd = new java.util.Random(strokeSeed);
        replayLen = total;
        arc = 0;
        startAt(rec[0], rec[1], rec[2]);
        for (int i = 3; i < recN; i += 3) segmentTo(rec[i], rec[i + 1], rec[i + 2]);
        flush();
        replayLen = 0;
    }

    private float step(float p) { return Math.max(0.5f, diameter(p) * brush.spacing); }

    private void segmentTo(float x, float y, float p) {
        float dx = x - lx, dy = y - ly;
        float d = (float) Math.hypot(dx, dy);
        if (d < 0.01f) { lp = p; return; }
        strokeAng = (float) Math.toDegrees(Math.atan2(dy, dx));
        while (nextDab <= d) {
            float t = nextDab / d;
            float q = lp + (p - lp) * t;
            dabArc = arc + nextDab;
            stamp(lx + dx * t, ly + dy * t, q);
            nextDab += step(q);
        }
        nextDab -= d;
        arc += d;
        lx = x; ly = y; lp = p;
    }

    /** Stamps a dab plus its mirror copies when symmetry is on. */
    private void stamp(float cx, float cy, float p) {
        stampOne(cx, cy, p);
        if (smudging || guide < 2 || guide > 5) return;
        float mx = docW - cx, my = docH - cy;
        if (guide == 2) stampOne(mx, cy, p);
        else if (guide == 3) stampOne(cx, my, p);
        else if (guide == 4) { stampOne(mx, cy, p); stampOne(cx, my, p); stampOne(mx, my, p); }
        else if (guide == 5) {
            float ox = cx - docW / 2f, oy = cy - docH / 2f;
            for (int k = 1; k < 8; k++) {
                double a = Math.PI * k / 4;
                float c = (float) Math.cos(a), s = (float) Math.sin(a);
                stampOne(docW / 2f + ox * c - oy * s, docH / 2f + ox * s + oy * c, p);
            }
        }
    }

    private void stampOne(float cx, float cy, float p) {
        Brush b = brush;
        float r = diameter(p) / 2f * taper();
        if (b.jitterSize > 0) r *= 1 - b.jitterSize * rnd.nextFloat();
        if (b.scatter > 0) { // random offset around the stroke
            double ang = rnd.nextDouble() * Math.PI * 2, dist = rnd.nextDouble() * b.scatter * r * 4;
            cx += (float) (Math.cos(ang) * dist); cy += (float) (Math.sin(ang) * dist);
        }
        r = Math.max(0.5f, r);
        float pr = pres(p);
        float alphaJ = b.jitterOpacity > 0 ? 1 - b.jitterOpacity * rnd.nextFloat() : 1f;
        int a = (int) (255 * opacity * b.alpha * alphaJ * (b.minAlpha + (1 - b.minAlpha) * pr));
        if (a <= 0) return;
        float soft = Math.max(1f, r * (1 - b.hard));
        int x0 = Math.max(0, (int) (cx - r - 1)), x1 = Math.min(docW - 1, (int) (cx + r + 1));
        int y0 = Math.max(0, (int) (cy - r - 1)), y1 = Math.min(docH - 1, (int) (cy + r + 1));
        if (x0 > x1 || y0 > y1) return;
        int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
        if (smudging) {
            if (dab.length < bw * bh) dab = new int[bw * bh];
            java.util.Arrays.fill(dab, 0, bw * bh, 0);
        }
        float grain = b.grain;
        float gsc = 1f / Math.max(0.1f, b.grainScale);
        // Dab shape: roundness squashes, angle (optionally following the stroke) rotates
        boolean shaped = b.roundness < 0.999f || b.angle != 0 || b.follow;
        double ang = Math.toRadians(b.angle + (b.follow ? strokeAng : 0));
        float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang), inv = 1f / Math.max(0.05f, b.roundness);
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
                float fyy = fy;
                if (shaped) { float u = fx * ca + fy * sa, v = (-fx * sa + fy * ca) * inv; fx = u; fyy = v; }
                float c;
                if (tip != null) {
                    c = sample(tip, tw, th, fx * sc + ox, fyy * sc + oy) / 255f;
                } else {
                    float dist = (float) Math.sqrt(fx * fx + fyy * fyy);
                    c = (r - dist) / soft + (b.hard > 0.5f ? 0.5f : 0f);
                    if (c > 1) c = 1;
                    else if (c > 0 && b.hard <= 0.5f) c = c * c * (3 - 2 * c);
                }
                if (c <= 0) continue;
                if (grain > 0) {
                    int gx = (int) (x * gsc), gy = (int) (y * gsc);
                    int g = b.tex != null ? b.tex[(gy % b.gh) * b.gw + (gx % b.gw)] & 255
                                          : NOISE[((gy & 127) << 7) | (gx & 127)] & 255;
                    c *= 1 - grain + grain * g / 255f;
                }
                int v = (int) (a * c);
                if (smudging) dab[(y - y0) * bw + (x - x0)] = v;
                else if (v > (mask[row + x] & 255)) mask[row + x] = (byte) v;
            }
        }
        if (smudging) {
            smudgeDab(x0, y0, bw, bh, Math.round(smX - cx), Math.round(smY - cy));
            smX = cx; smY = cy;
        }
        if (eventDirty.isEmpty()) eventDirty.set(x0, y0, x1 + 1, y1 + 1);
        else eventDirty.union(x0, y0, x1 + 1, y1 + 1);
    }

    /** Composites the mask over the backup into the layer, for the area touched since last flush. */
    private void flush() {
        Rect r = eventDirty;
        if (r.isEmpty()) return;
        if (smudging) { // smudge writes the layer directly
            if (strokeDirty.isEmpty()) strokeDirty.set(r); else strokeDirty.union(r);
            r.setEmpty();
            tgt().dirty = true;
            invalidate();
            return;
        }
        int w = r.width(), h = r.height();
        if (buf.length < w * h) buf = new int[w * h];
        backup.getPixels(buf, 0, w, r.left, r.top, w, h);
        int[] cl = adjPaint ? adjBlock(r) : cloning ? cloneBlock(r) : null;
        boolean lock = tgt().alphaLock;
        byte[] sm = sel;
        int ink = grey;
        for (int y = 0; y < h; y++) {
            int mrow = (r.top + y) * docW + r.left, brow = y * w;
            for (int x = 0; x < w; x++) {
                int m = mask[mrow + x] & 255;
                if (sm != null) m = m * (sm[mrow + x] & 255) / 255;
                if (m == 0) continue;
                int px = buf[brow + x];
                int A = px >>> 24, G = px & 255;
                int nA, nG;
                if (erasing) { if (lock) continue; nA = A * (255 - m) / 255; nG = G; }
                else if (cl != null) {
                    int cp = cl[brow + x], Ac = cp >>> 24, Gc = cp & 255;
                    int sa = Ac * m / 255;
                    if (lock) { nA = A; nG = G + (Gc - G) * sa / 255; }
                    else {
                        int keep = A * (255 - sa) / 255;
                        nA = sa + keep;
                        nG = nA == 0 ? 0 : (Gc * sa + G * keep) / nA;
                    }
                }
                else if (lock) { nA = A; nG = G + (ink - G) * m / 255; }
                else {
                    int keep = A * (255 - m) / 255;
                    nA = m + keep;
                    nG = nA == 0 ? 0 : (ink * m + G * keep) / nA;
                }
                buf[brow + x] = (nA << 24) | (nG * 0x010101);
            }
        }
        tgt().bmp.setPixels(buf, 0, w, r.left, r.top, w, h);
        if (strokeDirty.isEmpty()) strokeDirty.set(r); else strokeDirty.union(r);
        r.setEmpty();
        tgt().dirty = true;
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

    /** Pixels of the pre-stroke layer at the clone offset, for the rectangle r. */
    private int[] cloneBlock(Rect r) {
        int w = r.width(), h = r.height();
        if (buf2.length < w * h) buf2 = new int[w * h];
        java.util.Arrays.fill(buf2, 0, w * h, 0);
        Rect sr = new Rect(r);
        sr.offset(Math.round(cloneOX), Math.round(cloneOY));
        Rect ir = new Rect(sr);
        if (ir.intersect(0, 0, docW, docH)) {
            int iw = ir.width(), ih = ir.height();
            int[] t = new int[iw * ih];
            backup.getPixels(t, 0, iw, ir.left, ir.top, iw, ih);
            for (int y = 0; y < ih; y++)
                System.arraycopy(t, y * iw, buf2, (ir.top - sr.top + y) * w + (ir.left - sr.left), iw);
        }
        return buf2;
    }

    /** Smudge: pulls pixels from the previous dab position (offset dx,dy) into this dab. */
    private void smudgeDab(int x0, int y0, int bw, int bh, int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        Bitmap L = tgt().bmp;
        int n = bw * bh;
        int[] dst = new int[n], src = new int[n];
        L.getPixels(dst, 0, bw, x0, y0, bw, bh);
        System.arraycopy(dst, 0, src, 0, n);
        Rect sr = new Rect(x0 + dx, y0 + dy, x0 + dx + bw, y0 + dy + bh);
        Rect ir = new Rect(sr);
        if (ir.intersect(0, 0, docW, docH)) {
            int iw = ir.width(), ih = ir.height();
            int[] t = new int[iw * ih];
            L.getPixels(t, 0, iw, ir.left, ir.top, iw, ih);
            for (int y = 0; y < ih; y++)
                System.arraycopy(t, y * iw, src, (ir.top - sr.top + y) * bw + (ir.left - sr.left), iw);
        }
        byte[] sm = sel;
        for (int y = 0; y < bh; y++)
            for (int x = 0; x < bw; x++) {
                int i = y * bw + x;
                int v = dab[i];
                if (sm != null) v = v * (sm[(y0 + y) * docW + x0 + x] & 255) / 255;
                if (v == 0) continue;
                int d = dst[i], sp = src[i];
                int aD = d >>> 24, aS = sp >>> 24;
                int na = aD + (aS - aD) * v / 255;
                int pg = (d & 255) * aD + ((sp & 255) * aS - (d & 255) * aD) * v / 255;
                dst[i] = Fx.pix(na, na == 0 ? 0 : Fx.clamp(pg / na));
            }
        L.setPixels(dst, 0, bw, x0, y0, bw, bh);
        if (eventDirty.isEmpty()) eventDirty.set(x0, y0, x0 + bw, y0 + bh);
        else eventDirty.union(x0, y0, x0 + bw, y0 + bh);
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
        Layer l = tgt();
        Bitmap real = l.bmp;
        l.bmp = backup;
        PixelEdit pe = new PixelEdit(l, r);
        l.bmp = real;
        pe.name(adjPaint ? adjName : erasing ? "Eraser" : smudging ? "Smudge" : cloning ? "Clone" : wasShape ? "QuickShape" : "Brush stroke");
        if (l.text != null && !adjPaint) l.text = null; // painting on a text layer makes it plain pixels
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
            new Canvas(tgt().bmp).drawBitmap(backup, r, r, copy);
        }
        invalidate();
    }

    private boolean handleGesture(MotionEvent e, int action, Matrix target) {
        gCount = e.getPointerCount();
        if (!gesture) {
            gesture = true;
            removeCallbacks(shapeHold);
            removeCallbacks(pickHold);
            abortStroke();
            if (target == moveT) { beginMove(); xfMark(); }
            if (selPath != null) { selPath = null; invalidate(); }
            liqDown = false;
            gStart = e.getEventTime();
            gMoved = 0;
            gScale = 1f;
            gMax = gCount;
            holdUndoing = scrubbed = picking = false;
            scrubDir = scrubFlips = 0;
            gSwipeY = 0;
            reset(e, -1);
            scrubX = gX;
            downSX = e.getX(); downSY = e.getY();
            if (target == view) {
                if (gCount == 1) postDelayed(pickHold, 450);
                postDelayed(undoHold, undoHoldDelay);
            }
            return true;
        }
        gMax = Math.max(gMax, gCount);
        if (gMax > 1) removeCallbacks(pickHold);
        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN: reset(e, -1); scrubX = gX; break;
            case MotionEvent.ACTION_POINTER_UP: reset(e, e.getActionIndex()); break;
            case MotionEvent.ACTION_MOVE: {
                if (picking) { pickAt(e.getX(), e.getY()); break; }
                float[] m = state(e, -1);
                float dx = m[0] - gX, dy = m[1] - gY;
                gMoved += Math.abs(dx) + Math.abs(dy);
                if (gMoved > 30) removeCallbacks(pickHold);
                if (target == view && gCount >= 3) {
                    // Three-finger scrub (side to side) clears the layer
                    int dir = dx > 2 ? 1 : dx < -2 ? -1 : 0;
                    if (dir != 0) {
                        if (scrubDir == 0 || dir == scrubDir) { scrubDir = dir; scrubX = m[0]; }
                        else if (Math.abs(m[0] - scrubX) > 40) { scrubFlips++; scrubDir = dir; scrubX = m[0]; }
                    }
                    if (scrubFlips >= 3 && !scrubbed) { scrubbed = true; clearLayer(); }
                    gSwipeY += dy;
                    if (!scrubbed && scrubFlips == 0 && gSwipeY > 120 * getResources().getDisplayMetrics().density) {
                        scrubbed = true; // three-finger swipe down: copy & paste menu
                        if (onCopyMenu != null) onCopyMenu.run();
                    }
                } else {
                    if (target == moveT) { moveT.postTranslate(-snapOffX, -snapOffY); snapOffX = snapOffY = 0; } // un-snap, then follow the finger
                    target.postTranslate(dx, dy);
                    if (gDist > 0 && m[2] > 0) {
                        float s = m[2] / gDist;
                        float a = m[3] - gAngle;
                        if (a > 180) a -= 360; else if (a < -180) a += 360;
                        target.postScale(s, s, m[0], m[1]);
                        target.postRotate(a, m[0], m[1]);
                        gMoved += Math.abs(m[2] - gDist);
                        gScale *= s;
                    }
                    if (target == moveT && snapping && xform == 0) snapMove();
                }
                gX = m[0]; gY = m[1]; gDist = m[2]; gAngle = m[3];
                if (target == view && gCount >= 2 && onViewChanged != null) { // zoom % and angle, like Procreate's HUD
                    float[] v = new float[9];
                    view.getValues(v);
                    float zoom = (float) Math.hypot(v[0], v[3]) / fitScale() * 100;
                    float ang = (float) Math.toDegrees(Math.atan2(v[3], v[0]));
                    onViewChanged.accept(zoom, ang);
                }
                invalidate();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                gesture = false;
                snapX = snapY = Float.NaN;
                snapOffX = snapOffY = 0; // keep where it snapped
                removeCallbacks(pickHold);
                removeCallbacks(undoHold);
                if (picking) { picking = false; break; }
                if (target == moveT) xfUnmarkIfSame();
                if (target == moveT && action == MotionEvent.ACTION_UP && !holdUndoing && e.getEventTime() - gStart < 300 && gMoved < 40) {
                    if (gMax == 2) undo(); else if (gMax == 3) redo(); // two/three-finger tap while transforming
                }
                if (target == view && action == MotionEvent.ACTION_UP) {
                    long dt = e.getEventTime() - gStart;
                    if (!holdUndoing && !scrubbed && dt < 300 && gMoved < 40) {
                        if (gMax == 2) undo();
                        else if (gMax == 3) redo();
                        else if (gMax == 4 && onToggleUi != null) onToggleUi.run();
                    } else if (dt < 450 && gScale < 0.75f && gMax == 2) {
                        animateFit(); // quick pinch-in
                    }
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

    // ---------- Transform ----------

    /** Lifts the selection (or whole layer) into a floating bitmap for transforming. */
    public void beginMove() {
        if (moving) return;
        if (isLocked()) { moveBlocked = true; return; }
        moveBlocked = false;
        moving = true;
        xfUndo.clear(); xfRedo.clear();
        moveUndoSize = undoList.size();
        Layer l = tgt();
        moveEdit = new PixelEdit(l, new Rect(0, 0, docW, docH));
        moveEdit.name("Transform");
        selBeforeMove = selCopy();
        push(moveEdit);
        int n = docW * docH;
        int[] px = new int[n], fl = new int[n];
        l.bmp.getPixels(px, 0, docW, 0, 0, docW, docH);
        int minX = docW, minY = docH, maxX = -1, maxY = -1;
        for (int i = 0; i < n; i++) {
            int m = sel == null ? 255 : sel[i] & 255;
            int a = px[i] >>> 24;
            if (m == 0 || a == 0) { fl[i] = 0; continue; }
            int fa = a * m / 255;
            fl[i] = (fa << 24) | (px[i] & 0xFFFFFF);
            px[i] = ((a - fa) << 24) | (px[i] & 0xFFFFFF);
            if (fa > 0) {
                int x = i % docW, y = i / docW;
                if (x < minX) minX = x; if (x > maxX) maxX = x;
                if (y < minY) minY = y; if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0) { minX = 0; minY = 0; maxX = docW - 1; maxY = docH - 1; }
        int cw = maxX - minX + 1, ch = maxY - minY + 1;
        floatBmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        floatBmp.setPixels(fl, minY * docW + minX, docW, 0, 0, cw, ch);
        fx0 = minX; fy0 = minY;
        l.bmp.setPixels(px, 0, docW, 0, 0, docW, docH);
        srcCorners = new float[]{minX, minY, maxX + 1, minY, maxX + 1, maxY + 1, minX, maxY + 1};
        resetWarp();
        dstCorners = srcCorners.clone();
        poly.reset();
        moveT.reset();
        invalidate();
    }

    /** Bakes a pending move/scale/rotate/distort into the layer pixels. */
    public void commitMove() {
        finishShapeEdit();
        if (!moving) return;
        moving = false;
        xfUndo.clear(); xfRedo.clear();
        Layer l = tgt();
        Matrix m = transformMatrix();
        Paint p = smoothResample ? new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG) : new Paint();
        if (floatBmp != null) {
            Canvas lc = new Canvas(l.bmp);
            if (xform == 3) lc.concat(layerMatrix()); // drawFloat expects the view-space gesture on top
            if (xform == 3) lc.drawBitmapMesh(floatBmp, MESH, MESH, meshVerts(), 0, null, 0, p);
            else { lc.concat(m); lc.drawBitmap(floatBmp, fx0, fy0, p); }
            floatBmp.recycle();
            floatBmp = null;
        }
        if (sel != null) { // selection moves with its content
            Bitmap a = bytesToA8(sel, false), b = Bitmap.createBitmap(docW, docH, Bitmap.Config.ALPHA_8);
            new Canvas(b).drawBitmap(a, m, p);
            sel = a8ToBytes(b);
            a.recycle(); b.recycle();
            updateSelOverlay();
            if (!undoList.isEmpty() && undoList.get(undoList.size() - 1) == moveEdit) {
                undoList.remove(undoList.size() - 1); // one undo step for pixels + selection
                int n = docW * docH;
                PixelEdit pe = moveEdit;
                byte[] sb = deflate(selBeforeMove), sa = deflate(sel);
                named("Transform");
                push(new Op(() -> { pe.undo(); restoreSel(sb, n); }, () -> { pe.redo(); restoreSel(sa, n); },
                        pe.bytes() + sb.length + sa.length));
            }
        }
        moveEdit = null;
        selBeforeMove = null;
        moveT.reset();
        poly.reset();
        dragHandle = -1;
        xform = Math.min(xform, 2); // warp is per transform
        l.dirty = true;
        syncBackup();
        changed();
    }

    private float[] contentCentre() {
        float[] q = handlePoints();
        return new float[]{(q[0] + q[4]) / 2, (q[1] + q[5]) / 2};
    }

    public void flip(boolean horizontal) {
        if (tool != MOVE) return;
        beginMove();
        xfMark();
        float[] c = contentCentre();
        moveT.postScale(horizontal ? -1 : 1, horizontal ? 1 : -1, c[0], c[1]);
        invalidate();
    }

    public void rotate90() {
        if (tool != MOVE) return;
        beginMove();
        xfMark();
        float[] c = contentCentre();
        moveT.postRotate(90, c[0], c[1]);
        invalidate();
    }

    public void resetTransform() {
        if (!moving) return;
        xfMark();
        moveT.reset();
        poly.reset();
        dstCorners = srcCorners.clone();
        resetWarp();
        invalidate();
    }

    private void resetWarp() {
        float x0 = srcCorners[0], y0 = srcCorners[1], x1 = srcCorners[4], y1 = srcCorners[5];
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            warp[(r * 4 + c) * 2] = x0 + (x1 - x0) * c / 3f;
            warp[(r * 4 + c) * 2 + 1] = y0 + (y1 - y0) * r / 3f;
        }
    }

    /** Switch between uniform, freeform, distort and warp. */
    public void setXform(int mode) {
        beginMove();
        if (mode != xform) xfMark();
        xform = mode;
        invalidate();
    }

    /** Freeform / distort / warp: drag a handle. Returns false if the touch isn't on a handle. */
    private boolean handleDrag(MotionEvent e, int action) {
        if (action == MotionEvent.ACTION_DOWN) {
            beginMove();
            float[] q = handlePoints();
            float best = 32 * getResources().getDisplayMetrics().density;
            dragHandle = -1;
            for (int k = 0; k < q.length / 2; k++) {
                float d = (float) Math.hypot(e.getX() - q[k * 2], e.getY() - q[k * 2 + 1]);
                if (d < best) { best = d; dragHandle = k; }
            }
            if (dragHandle >= 0) xfMark();
            return dragHandle >= 0;
        }
        if (dragHandle < 0) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            // the handle must land under the finger: work back through the view and gesture transforms
            Matrix ui = new Matrix();
            new Matrix(layerMatrix()).invert(ui);
            view.invert(inverse);
            float[] f = {e.getX(), e.getY()};
            inverse.mapPoints(f);
            ui.mapPoints(f);
            if (xform == 3) {
                Matrix pi = new Matrix();
                if (poly.invert(pi)) { pi.mapPoints(f); warp[dragHandle * 2] = f[0]; warp[dragHandle * 2 + 1] = f[1]; }
            } else if (xform == 2) {
                dstCorners[dragHandle * 2] = f[0];
                dstCorners[dragHandle * 2 + 1] = f[1];
                poly.setPolyToPoly(srcCorners, 0, dstCorners, 0, 4);
            } else { // freeform: keep an axis-aligned box, move a corner or one edge
                float l = Math.min(dstCorners[0], dstCorners[6]), t = Math.min(dstCorners[1], dstCorners[3]);
                float r = Math.max(dstCorners[2], dstCorners[4]), b = Math.max(dstCorners[5], dstCorners[7]);
                int k = dragHandle;
                if (k == 0) { l = f[0]; t = f[1]; } else if (k == 1) { r = f[0]; t = f[1]; }
                else if (k == 2) { r = f[0]; b = f[1]; } else if (k == 3) { l = f[0]; b = f[1]; }
                else if (k == 4) t = f[1]; else if (k == 5) r = f[0]; else if (k == 6) b = f[1]; else l = f[0];
                if (Math.abs(r - l) < 2 || Math.abs(b - t) < 2) return true;
                dstCorners = new float[]{l, t, r, t, r, b, l, b};
                poly.setPolyToPoly(srcCorners, 0, dstCorners, 0, 4);
            }
            invalidate();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { dragHandle = -1; xfUnmarkIfSame(); }
        return true;
    }

    /** True (and a message) if the layer being edited is locked. */
    public boolean isLocked() {
        if (tgt().locked || cur().locked) { if (onInfo != null) onInfo.accept("Layer is locked"); return true; }
        return false;
    }

    public void toggleLock(int i) {
        Layer l = layers.get(i);
        boolean v = l.locked;
        named(v ? "Unlock layer" : "Lock layer");
        apply(new Op(() -> l.locked = v, () -> l.locked = !v, 64));
    }

    /** Marks one layer as the Reference for ColorDrop and Automatic selection (tap again to clear). */
    public void toggleReference(int i) {
        Layer l = layers.get(i);
        boolean v = l.reference;
        for (Layer x : layers) x.reference = false;
        l.reference = !v;
        changed();
    }

    /** Pixels used to decide fill / automatic-selection boundaries: the Reference layer if set. */
    private int[] boundaryPixels() {
        int[] px = new int[docW * docH];
        Layer ref = null;
        for (Layer l : layers) if (l.reference) ref = l;
        (ref != null ? ref : tgt()).bmp.getPixels(px, 0, docW, 0, 0, docW, docH);
        return px;
    }

    public void setTool(int t) {
        commitMove();
        if (t != CLONE) cloneArmed = false;
        tool = t;
        invalidate();
    }

    // ---------- Selection ----------

    private Bitmap bytesToA8(byte[] b, boolean invert) {
        Bitmap bm = Bitmap.createBitmap(docW, docH, Bitmap.Config.ALPHA_8);
        int rb = bm.getRowBytes();
        byte[] rows = new byte[rb * docH];
        for (int y = 0; y < docH; y++)
            for (int x = 0; x < docW; x++) {
                byte v = b[y * docW + x];
                rows[y * rb + x] = invert ? (byte) (255 - (v & 255)) : v;
            }
        bm.copyPixelsFromBuffer(ByteBuffer.wrap(rows));
        return bm;
    }

    private byte[] a8ToBytes(Bitmap bm) {
        int rb = bm.getRowBytes();
        ByteBuffer bb = ByteBuffer.allocate(rb * docH);
        bm.copyPixelsToBuffer(bb);
        byte[] rows = bb.array(), out = new byte[docW * docH];
        for (int y = 0; y < docH; y++) System.arraycopy(rows, y * rb, out, y * docW, docW);
        return out;
    }

    private Bitmap selEdge;

    private void updateSelOverlay() {
        if (selOverlay != null) selOverlay.recycle();
        selOverlay = sel == null ? null : bytesToA8(sel, true);
        if (selEdge != null) { selEdge.recycle(); selEdge = null; }
        if (sel != null) { // outline for the marching ants
            byte[] edge = new byte[sel.length];
            for (int y = 0; y < docH; y++)
                for (int x = 0; x < docW; x++) {
                    int i = y * docW + x;
                    boolean in = (sel[i] & 255) >= 128;
                    boolean diff = (x > 0 && ((sel[i - 1] & 255) >= 128) != in) || (y > 0 && ((sel[i - docW] & 255) >= 128) != in)
                            || (x < docW - 1 && ((sel[i + 1] & 255) >= 128) != in) || (y < docH - 1 && ((sel[i + docW] & 255) >= 128) != in);
                    if (diff) edge[i] = (byte) 255;
                }
            selEdge = bytesToA8(edge, false);
        }
        invalidate();
    }

    private float antsPhase;

    /** Animated black/white dashes along the selection edge. */
    private void drawAnts(Canvas c, float sc) {
        if (selEdge == null) return;
        Bitmap tile = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
        Canvas tc = new Canvas(tile);
        tc.drawColor(Color.WHITE);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        q.setColor(Color.BLACK);
        q.setStrokeWidth(4);
        for (int k = -1; k <= 1; k++) tc.drawLine(k * 12, 12, k * 12 + 12, 0, q);
        android.graphics.BitmapShader sh = new android.graphics.BitmapShader(tile, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        Matrix m = new Matrix();
        m.setScale(1 / sc, 1 / sc);
        m.postTranslate(antsPhase / sc, 0);
        sh.setLocalMatrix(m);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setShader(sh);
        c.drawBitmap(selEdge, 0, 0, p);
        antsPhase = (antsPhase + 2) % 12;
        postInvalidateDelayed(90);
    }

    /** Makes the selection change since `before` an undo step. */
    private void recordSel(byte[] before) {
        named("Selection");
        int n = docW * docH;
        byte[] b = before == null ? null : deflate(before);
        byte[] a = sel == null ? null : deflate(sel);
        long size = 64 + (b == null ? 0 : b.length) + (a == null ? 0 : a.length);
        push(new Op(() -> restoreSel(b, n), () -> restoreSel(a, n), size));
    }

    private void restoreSel(byte[] z, int n) {
        sel = z == null ? null : inflate(z, n);
        updateSelOverlay();
    }

    private byte[] selCopy() { return sel == null ? null : sel.clone(); }

    /** Combines a new shape mask into the selection (add or subtract). */
    private void combineSel(byte[] m) {
        byte[] before = selCopy();
        if (sel == null) {
            sel = new byte[docW * docH];
            if (selSubtract) java.util.Arrays.fill(sel, (byte) 255);
        }
        int any = 0;
        for (int i = 0; i < sel.length; i++) {
            int a = sel[i] & 255, b = m[i] & 255;
            int v = selSubtract ? Math.min(a, 255 - b) : Math.max(a, b);
            sel[i] = (byte) v;
            any |= v;
        }
        if (any == 0) sel = null;
        updateSelOverlay();
        recordSel(before);
        changed();
    }

    private boolean polyOpen, selMoved;
    private float polyX0, polyY0, selDownSX, selDownSY;
    private int lastAutoX = -1, lastAutoY;
    private Edit lastAutoEdit;
    private byte[] lastAutoBefore;

    /** Freehand: closes an open tap-polygon into a selection (or drops it if too small). */
    public void closePolygon() {
        if (!polyOpen || selPath == null) { polyOpen = false; return; }
        polyOpen = false;
        RectF bounds = new RectF();
        selPath.computeBounds(bounds, true);
        float minSide = 6 / view.mapRadius(1f);
        if (bounds.width() < minSide && bounds.height() < minSide) { selPath = null; invalidate(); return; }
        selPath.close();
        Bitmap b = Bitmap.createBitmap(docW, docH, Bitmap.Config.ALPHA_8);
        new Canvas(b).drawPath(selPath, new Paint(Paint.ANTI_ALIAS_FLAG));
        byte[] m = a8ToBytes(b);
        b.recycle();
        selPath = null;
        combineSel(m);
    }

    /** Automatic selection: redo the last one with a new threshold. */
    public void refineAuto(int thr) {
        autoThr = thr;
        if (lastAutoEdit == null || undoList.isEmpty() || undoList.get(undoList.size() - 1) != lastAutoEdit) return;
        undoList.remove(undoList.size() - 1);
        sel = lastAutoBefore == null ? null : lastAutoBefore.clone();
        byte[] m = new byte[docW * docH];
        Fx.flood(boundaryPixels(), docW, docH, lastAutoX, lastAutoY, autoThr, m);
        combineSel(m);
        lastAutoEdit = undoList.get(undoList.size() - 1);
    }

    public int autoThreshold() { return autoThr; }

    private boolean selectTouch(MotionEvent e, int action) {
        view.invert(inverse);
        map(e.getX(), e.getY());
        float x = pt[0], y = pt[1];
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                selX0 = x; selY0 = y; autoSX = e.getX();
                selDownSX = e.getX(); selDownSY = e.getY();
                selMoved = false;
                if (selKind == 1 && polyOpen && selPath != null) { selPath.lineTo(x, y); invalidate(); break; } // continue the polygon
                polyOpen = false;
                selPath = new Path();
                selPath.moveTo(x, y);
                polyX0 = x; polyY0 = y;
                break;
            case MotionEvent.ACTION_MOVE:
                if (selPath == null) break;
                if (Math.hypot(e.getX() - selDownSX, e.getY() - selDownSY) > 10 * getResources().getDisplayMetrics().density) selMoved = true;
                if (selKind == 0) {
                    autoThr = Fx.clamp(30 + Math.round((e.getX() - autoSX) / getWidth() * 255));
                    if (onInfo != null) onInfo.accept("Threshold " + autoThr * 100 / 255 + "%  (slide left/right)");
                } else if (selKind == 1) selPath.lineTo(x, y);
                else {
                    selPath.reset();
                    RectF r = new RectF(Math.min(selX0, x), Math.min(selY0, y), Math.max(selX0, x), Math.max(selY0, y));
                    if (selKind == 2) selPath.addRect(r, Path.Direction.CW); else selPath.addOval(r, Path.Direction.CW);
                }
                invalidate();
                break;
            case MotionEvent.ACTION_UP: {
                if (selPath == null) break;
                if (selKind == 1) { // freehand: taps place polygon corners; tap the first corner (or Done) to close
                    float d0 = (float) Math.hypot(x - polyX0, y - polyY0) * view.mapRadius(1f);
                    if (!selMoved && polyOpen && d0 < 24 * getResources().getDisplayMetrics().density) { closePolygon(); break; }
                    if (!selMoved) { polyOpen = true; if (onInfo != null) onInfo.accept("Tap to add corners · tap the first corner to close"); invalidate(); break; }
                    if (polyOpen) { invalidate(); break; } // a dragged segment inside a polygon
                }
                byte[] m;
                if (selKind == 0) {
                    int sx = (int) selX0, sy = (int) selY0;
                    if (sx < 0 || sy < 0 || sx >= docW || sy >= docH) { selPath = null; invalidate(); break; }
                    m = new byte[docW * docH];
                    Fx.flood(boundaryPixels(), docW, docH, sx, sy, autoThr, m);
                    lastAutoX = sx; lastAutoY = sy;
                    lastAutoBefore = sel == null ? null : sel.clone();
                    selPath = null;
                    combineSel(m);
                    lastAutoEdit = undoList.isEmpty() ? null : undoList.get(undoList.size() - 1);
                    break;
                } else {
                    RectF bounds = new RectF();
                    selPath.computeBounds(bounds, true);
                    float minSide = 6 / view.mapRadius(1f); // ~6 screen pixels
                    if (bounds.width() < minSide && bounds.height() < minSide) { selPath = null; invalidate(); break; } // a tap: no selection
                    selPath.close();
                    Bitmap b = Bitmap.createBitmap(docW, docH, Bitmap.Config.ALPHA_8);
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    new Canvas(b).drawPath(selPath, p);
                    m = a8ToBytes(b);
                    b.recycle();
                }
                selPath = null;
                combineSel(m);
                break;
            }
            case MotionEvent.ACTION_CANCEL: selPath = null; invalidate(); break;
        }
        return true;
    }

    /** Default vanishing points for 1-, 2- and 3-point perspective. */
    public void resetVps() {
        if (guide == 7) vp = new float[]{docW / 2f, docH * 0.45f, 0, 0, 0, 0};
        else vp = new float[]{docW * 0.12f, docH * 0.45f, docW * 0.88f, docH * 0.45f, docW / 2f, docH * 0.97f};
        invalidate();
    }

    public void setSelection(byte[] m) { byte[] b = selCopy(); sel = m; updateSelOverlay(); recordSel(b); changed(); }

    public void clearSelection() {
        if (sel == null) return;
        byte[] b = selCopy();
        sel = null;
        updateSelOverlay();
        recordSel(b);
        changed();
    }

    public void invertSelection() {
        byte[] before = selCopy();
        if (sel == null) { sel = new byte[docW * docH]; java.util.Arrays.fill(sel, (byte) 255); }
        else for (int i = 0; i < sel.length; i++) sel[i] = (byte) (255 - (sel[i] & 255));
        updateSelOverlay();
        recordSel(before);
        changed();
    }

    public void featherSelection(float radius) {
        if (sel == null) return;
        byte[] before = selCopy();
        Fx.feather(sel, docW, docH, radius);
        updateSelOverlay();
        recordSel(before);
    }

    /** Selects the painted pixels of the current layer. */
    public void selectLayer() {
        int[] px = new int[docW * docH];
        cur().bmp.getPixels(px, 0, docW, 0, 0, docW, docH);
        byte[] before = selCopy();
        sel = new byte[px.length];
        for (int i = 0; i < px.length; i++) sel[i] = (byte) (px[i] >>> 24);
        updateSelOverlay();
        recordSel(before);
        changed();
    }

    /** Copies (or cuts) the selection of the current layer into a new layer. */
    public boolean copyPaste(boolean cut) {
        named("Paste");
        commitMove();
        if (layers.size() >= maxLayers()) return false;
        Layer src = cur();
        int n = docW * docH;
        int[] px = new int[n], out = new int[n];
        src.bmp.getPixels(px, 0, docW, 0, 0, docW, docH);
        PixelEdit pe = cut ? new PixelEdit(src, new Rect(0, 0, docW, docH)) : null;
        for (int i = 0; i < n; i++) {
            int m = sel == null ? 255 : sel[i] & 255, a = px[i] >>> 24;
            int fa = a * m / 255;
            out[i] = (fa << 24) | (px[i] & 0xFFFFFF);
            if (cut) px[i] = ((a - fa) << 24) | (px[i] & 0xFFFFFF);
        }
        if (cut) src.bmp.setPixels(px, 0, docW, 0, 0, docW, docH);
        Layer l = newLayer(cut ? "Cut" : "Copy");
        l.bmp.setPixels(out, 0, docW, 0, 0, docW, docH);
        int at = current + 1;
        Runnable add = () -> { layers.add(at, l); current = at; };
        Runnable remove = () -> { layers.remove(l); current = at - 1; };
        if (pe == null) apply(new Op(remove, add, 64));
        else {
            push(new Op(() -> { remove.run(); pe.undo(); }, () -> { pe.redo(); add.run(); }, pe.bytes()));
            add.run();
            afterHistory();
        }
        return true;
    }

    // ---------- Clipboard ----------

    private Bitmap clipboard;

    /** Copies the selection (or everything) of the current layer, or of all layers when all = true. */
    public void copy(boolean all, boolean cut) {
        commitMove();
        Bitmap src = all ? flatten() : cur().bmp;
        int n = docW * docH;
        int[] px = new int[n];
        src.getPixels(px, 0, docW, 0, 0, docW, docH);
        if (sel != null) for (int i = 0; i < n; i++) px[i] = ((((px[i] >>> 24) * (sel[i] & 255)) / 255) << 24) | (px[i] & 0xFFFFFF);
        if (clipboard != null) clipboard.recycle();
        clipboard = Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888);
        clipboard.setPixels(px, 0, docW, 0, 0, docW, docH);
        if (all) src.recycle();
        if (cut && !all) clearLayer();
    }

    public boolean hasClipboard() { return clipboard != null && clipboard.getWidth() == docW && clipboard.getHeight() == docH; }

    public boolean paste() {
        if (!hasClipboard()) return true;
        Layer l = newLayer("Pasted");
        new Canvas(l.bmp).drawBitmap(clipboard, 0, 0, null);
        return addLayer(l);
    }

    // ---------- Colour fill (ColorDrop) ----------

    /** Fills the area under a screen point on the current layer with the ink grey. */
    public boolean fillMode;          // "Continue filling": taps fill
    public int fillThreshold = 30;
    private int lastFillX = -1, lastFillY;
    private Edit lastFillEdit;

    public void colorDrop(float sx, float sy, int threshold) {
        commitMove();
        view.invert(inverse);
        pt[0] = sx; pt[1] = sy;
        inverse.mapPoints(pt);
        colorDropDoc((int) pt[0], (int) pt[1], threshold);
    }

    /** Fills from a canvas point (boundaries come from the Reference layer if one is set). */
    public void colorDropDoc(int x, int y, int threshold) {
        if (x < 0 || y < 0 || x >= docW || y >= docH) return;
        if (isLocked()) return;
        fillThreshold = threshold;
        int n = docW * docH;
        Layer l = tgt();
        int[] px = new int[n];
        l.bmp.getPixels(px, 0, docW, 0, 0, docW, docH);
        byte[] m = new byte[n];
        Fx.flood(boundaryPixels(), docW, docH, x, y, threshold, m);
        Fx.dilate(m, docW, docH);
        named("ColorDrop");
        PixelEdit pe = new PixelEdit(l, new Rect(0, 0, docW, docH));
        push(pe);
        lastFillEdit = pe; lastFillX = x; lastFillY = y;
        fillWith(px, m);
        l.bmp.setPixels(px, 0, docW, 0, 0, docW, docH);
        afterHistory();
    }

    /** Redoes the last fill with a new threshold (slide after dropping, like Procreate). */
    public void refineFill(int threshold) {
        if (lastFillEdit == null || undoList.isEmpty() || undoList.get(undoList.size() - 1) != lastFillEdit) return;
        undoList.remove(undoList.size() - 1);
        lastFillEdit.undo();
        colorDropDoc(lastFillX, lastFillY, threshold);
    }

    /** Paints ink over px where mask (and selection) allow. */
    private void fillWith(int[] px, byte[] m) {
        boolean lock = tgt().alphaLock;
        for (int i = 0; i < px.length; i++) {
            int k = m == null ? 255 : m[i] & 255;
            if (sel != null) k = k * (sel[i] & 255) / 255;
            if (k == 0) continue;
            int A = px[i] >>> 24, G = px[i] & 255;
            if (lock) { px[i] = Fx.pix(A, G + (grey - G) * k / 255); continue; }
            int keep = A * (255 - k) / 255, nA = k + keep;
            px[i] = Fx.pix(nA, nA == 0 ? 0 : (grey * k + G * keep) / nA);
        }
    }

    public void fillLayer() {
        if (isLocked()) return;
        named("Fill layer");
        commitMove();
        Layer l = tgt();
        int[] px = new int[docW * docH];
        l.bmp.getPixels(px, 0, docW, 0, 0, docW, docH);
        push(new PixelEdit(l, new Rect(0, 0, docW, docH)));
        fillWith(px, null);
        l.bmp.setPixels(px, 0, docW, 0, 0, docW, docH);
        afterHistory();
    }

    public void invertLayer() {
        if (isLocked()) return;
        named("Invert");
        commitMove();
        Layer l = tgt();
        int[] px = new int[docW * docH], out = new int[docW * docH];
        l.bmp.getPixels(px, 0, docW, 0, 0, docW, docH);
        push(new PixelEdit(l, new Rect(0, 0, docW, docH)));
        Fx.lut(px, out, Fx.invert());
        Fx.mixSel(px, out, sel);
        l.bmp.setPixels(out, 0, docW, 0, 0, docW, docH);
        afterHistory();
    }

    // ---------- Adjustments (live preview on a worker thread) ----------

    interface AdjFn { void run(int[] src, int[] out); }

    private int[] adjSrc;
    private PixelEdit adjEdit;
    private volatile AdjFn adjPending;
    private volatile int adjGen;
    private boolean adjBusy;
    public java.util.function.BiConsumer<Float, Boolean> onAdjustDrag; // fraction moved, finished
    public java.util.function.BiConsumer<Float, Float> onAdjustTap;    // canvas point
    private float adjDownX, adjDownY;
    private boolean adjDragging;

    public boolean adjusting() { return adjSrc != null; }

    public void beginAdjust() {
        if (isLocked()) return;
        adjPencil = false; adjOut = null;
        commitMove();
        Layer l = tgt();
        adjEdit = new PixelEdit(l, new Rect(0, 0, docW, docH));
        adjSrc = new int[docW * docH];
        l.bmp.getPixels(adjSrc, 0, docW, 0, 0, docW, docH);
    }

    /** Requests a preview; only the newest request is computed. */
    public void previewAdjust(AdjFn fn) {
        if (adjSrc == null) return;
        adjPending = fn;
        adjGen++;
        if (adjBusy) return;
        adjBusy = true;
        runAdjustWorker();
    }

    private void runAdjustWorker() {
        AdjFn fn = adjPending;
        int gen = adjGen;
        int[] src = adjSrc;
        byte[] sm = sel;
        new Thread(() -> {
            int[] out = new int[src.length];
            try { fn.run(src, out); Fx.mixSel(src, out, sm); } catch (Throwable t) { out = null; }
            int[] res = out;
            post(() -> {
                if (adjSrc != src) { adjBusy = false; return; }
                if (res != null) { tgt().bmp.setPixels(res, 0, docW, 0, 0, docW, docH); tgt().dirty = true; invalidate(); }
                if (adjGen != gen) runAdjustWorker(); else adjBusy = false;
            });
        }).start();
    }

    /** Applies (runs the final effect synchronously) or cancels the adjustment. */
    // Pencil mode: the full effect is computed once, then painted in with the current brush
    public boolean adjPencil;
    public String adjName = "Adjustment";
    private boolean adjPaint;
    private int[] adjOut;
    private int adjUndoSize;

    /** Switches the running adjustment to Pencil mode: the layer goes back to normal and strokes paint the effect in. */
    public void adjustPencil(AdjFn fn) {
        if (adjSrc == null) return;
        adjPencil = true;
        adjOut = new int[adjSrc.length];
        fn.run(adjSrc, adjOut);
        Fx.mixSel(adjSrc, adjOut, sel);
        adjGen++;
        tgt().bmp.setPixels(adjSrc, 0, docW, 0, 0, docW, docH);
        adjUndoSize = undoList.size();
        syncBackup();
        invalidate();
    }

    private int[] adjBlock(Rect r) {
        int w = r.width(), h = r.height();
        if (buf2.length < w * h) buf2 = new int[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(adjOut, (r.top + y) * docW + r.left, buf2, y * w, w);
        return buf2;
    }

    public void endAdjust(AdjFn fn) {
        if (adjSrc == null) return;
        if (adjPencil) { // strokes are already undo steps; Cancel removes them
            int[] src = adjSrc;
            adjSrc = null; adjOut = null; adjPencil = false; adjGen++;
            if (fn == null) {
                tgt().bmp.setPixels(src, 0, docW, 0, 0, docW, docH);
                while (undoList.size() > adjUndoSize) undoList.remove(undoList.size() - 1);
            }
            adjEdit = null;
            afterHistory();
            return;
        }
        int[] src = adjSrc;
        adjSrc = null;
        adjGen++;
        Layer l = tgt();
        if (fn != null) {
            int[] out = new int[src.length];
            fn.run(src, out);
            Fx.mixSel(src, out, sel);
            l.bmp.setPixels(out, 0, docW, 0, 0, docW, docH);
            adjEdit.name(adjName);
            push(adjEdit);
        } else l.bmp.setPixels(src, 0, docW, 0, 0, docW, docH);
        adjEdit = null;
        afterHistory();
    }

    /** In Adjust mode, horizontal drags change the amount (like Procreate); taps report a point. */
    private boolean adjustTouch(MotionEvent e, int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN: adjDownX = e.getX(); adjDownY = e.getY(); adjDragging = false; break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - adjDownX) > 10) adjDragging = true;
                if (adjDragging && onAdjustDrag != null) onAdjustDrag.accept((e.getX() - adjDownX) / getWidth(), false);
                break;
            case MotionEvent.ACTION_UP:
                if (adjDragging) { if (onAdjustDrag != null) onAdjustDrag.accept((e.getX() - adjDownX) / getWidth(), true); }
                else if (onAdjustTap != null) {
                    view.invert(inverse);
                    map(e.getX(), e.getY());
                    onAdjustTap.accept(pt[0], pt[1]);
                }
                break;
        }
        return true;
    }

    // ---------- Liquify ----------

    public int liqMode;                 // 0 push, 1 twirl right, 2 twirl left, 3 pinch, 4 expand, 5 reconstruct, 6 crystals, 7 edge
    public float liqMomentum;           // 0..1: keeps going after you lift
    private float liqLastDx, liqLastDy;
    public float liqSize = 120, liqStrength = 0.5f;
    private int[] liqSrc;
    private float[] fdx, fdy;
    private int gw, gh;
    private PixelEdit liqEdit;
    private boolean liqDown;
    private float liqX, liqY;
    private static final int G = 4;     // displacement grid spacing (pixels)

    public void beginLiquify() {
        if (isLocked()) return;
        commitMove();
        Layer l = tgt();
        liqEdit = new PixelEdit(l, new Rect(0, 0, docW, docH));
        liqSrc = new int[docW * docH];
        l.bmp.getPixels(liqSrc, 0, docW, 0, 0, docW, docH);
        gw = docW / G + 2; gh = docH / G + 2;
        fdx = new float[gw * gh]; fdy = new float[gw * gh];
    }

    public boolean liquifying() { return liqSrc != null; }

    public void endLiquify(boolean apply) {
        if (liqSrc == null) return;
        if (apply) push(liqEdit);
        else tgt().bmp.setPixels(liqSrc, 0, docW, 0, 0, docW, docH);
        liqSrc = null; fdx = fdy = null; liqEdit = null;
        afterHistory();
    }

    public void liquifyResetAll() {
        if (liqSrc == null) return;
        java.util.Arrays.fill(fdx, 0); java.util.Arrays.fill(fdy, 0);
        tgt().bmp.setPixels(liqSrc, 0, docW, 0, 0, docW, docH);
        invalidate();
    }

    private final Runnable liqTick = new Runnable() { // twirl/pinch keep working while held
        public void run() {
            if (!liqDown || liqSrc == null || liqMode == 0) return;
            liquifyAt(liqX, liqY, 0, 0, 0.35f);
            postDelayed(this, 30);
        }
    };

    private boolean liquifyTouch(MotionEvent e, int action) {
        if (liqSrc == null) return true;
        view.invert(inverse);
        map(e.getX(), e.getY());
        float x = pt[0], y = pt[1];
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                liqDown = true; liqX = x; liqY = y;
                postDelayed(liqTick, 30);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!liqDown) break;
                liqLastDx = x - liqX; liqLastDy = y - liqY;
                liquifyAt(x, y, x - liqX, y - liqY, 1f);
                liqX = x; liqY = y;
                break;
            default:
                liqDown = false;
                removeCallbacks(liqTick);
                if (liqMomentum > 0) { // carry on a little after lifting, slowing down
                    int steps = Math.round(liqMomentum * 20);
                    for (int i = 1; i <= steps; i++) {
                        float f = 1 - i / (float) (steps + 1);
                        int k = i;
                        postDelayed(() -> {
                            if (liqSrc == null) return;
                            liqX += liqLastDx * f; liqY += liqLastDy * f;
                            liquifyAt(liqX, liqY, liqLastDx * f, liqLastDy * f, f);
                        }, 16L * k);
                    }
                }
        }
        return true;
    }

    private void liquifyAt(float cx, float cy, float dx, float dy, float k) {
        float R = liqSize, st = liqStrength * k;
        int gx0 = Math.max(0, (int) ((cx - R) / G)), gx1 = Math.min(gw - 1, (int) ((cx + R) / G) + 1);
        int gy0 = Math.max(0, (int) ((cy - R) / G)), gy1 = Math.min(gh - 1, (int) ((cy + R) / G) + 1);
        for (int gy = gy0; gy <= gy1; gy++)
            for (int gx = gx0; gx <= gx1; gx++) {
                float vx = gx * G - cx, vy = gy * G - cy;
                float d = (float) Math.hypot(vx, vy);
                if (d >= R) continue;
                float w = (1 - d / R); w *= w;
                int i = gy * gw + gx;
                switch (liqMode) {
                    case 0: fdx[i] += dx * w * liqStrength * 1.6f; fdy[i] += dy * w * liqStrength * 1.6f; break;
                    case 1: case 2: {
                        float a = (liqMode == 1 ? 0.08f : -0.08f) * w * st;
                        float c = (float) Math.cos(a), s = (float) Math.sin(a);
                        fdx[i] += vx - (vx * c - vy * s); fdy[i] += vy - (vx * s + vy * c);
                        break;
                    }
                    case 3: fdx[i] -= vx * 0.04f * w * st; fdy[i] -= vy * 0.04f * w * st; break;
                    case 4: fdx[i] += vx * 0.04f * w * st; fdy[i] += vy * 0.04f * w * st; break;
                    case 6: { // crystals: shattered random offsets
                        int hsh = (gx * 73856093) ^ (gy * 19349663);
                        float rx = ((hsh & 255) / 255f - 0.5f), ry = (((hsh >> 8) & 255) / 255f - 0.5f);
                        fdx[i] += rx * R * 0.08f * w * st; fdy[i] += ry * R * 0.08f * w * st;
                        break;
                    }
                    case 7: { // edge: pulls towards a line along the stroke
                        float len = (float) Math.hypot(liqLastDx, liqLastDy);
                        float nx = len > 0 ? -liqLastDy / len : 0, ny = len > 0 ? liqLastDx / len : 1;
                        float along = vx * nx + vy * ny;
                        fdx[i] -= nx * along * 0.06f * w * st; fdy[i] -= ny * along * 0.06f * w * st;
                        break;
                    }
                    default: fdx[i] *= 1 - 0.25f * w * Math.max(0.2f, st); fdy[i] *= 1 - 0.25f * w * Math.max(0.2f, st);
                }
            }
        Rect r = new Rect((int) (cx - R) - 2, (int) (cy - R) - 2, (int) (cx + R) + 3, (int) (cy + R) + 3);
        if (r.intersect(0, 0, docW, docH)) renderLiquify(r);
    }

    private float field(float[] f, float x, float y) {
        float gx = x / G, gy = y / G;
        int ix = Math.min(gw - 2, (int) gx), iy = Math.min(gh - 2, (int) gy);
        float tx = gx - ix, ty = gy - iy;
        int i = iy * gw + ix;
        float a = f[i] + (f[i + 1] - f[i]) * tx, b = f[i + gw] + (f[i + gw + 1] - f[i + gw]) * tx;
        return a + (b - a) * ty;
    }

    private void renderLiquify(Rect r) {
        int w = r.width(), h = r.height();
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int X = r.left + x, Y = r.top + y;
                float sx = X - field(fdx, X, Y), sy = Y - field(fdy, X, Y);
                int p = bilinear(liqSrc, sx, sy);
                if (sel != null) {
                    int m = sel[Y * docW + X] & 255, o = liqSrc[Y * docW + X];
                    if (m < 255) p = Fx.pix(((o >>> 24) * (255 - m) + (p >>> 24) * m) / 255, ((o & 255) * (255 - m) + (p & 255) * m) / 255);
                }
                out[y * w + x] = p;
            }
        tgt().bmp.setPixels(out, 0, w, r.left, r.top, w, h);
        tgt().dirty = true;
        invalidate();
    }

    private int bilinear(int[] s, float x, float y) {
        x = Math.max(0, Math.min(docW - 1.001f, x)); y = Math.max(0, Math.min(docH - 1.001f, y));
        int ix = (int) x, iy = (int) y;
        float tx = x - ix, ty = y - iy;
        int i = iy * docW + ix;
        int p00 = s[i], p10 = s[Math.min(i + 1, s.length - 1)], p01 = s[Math.min(i + docW, s.length - 1)], p11 = s[Math.min(i + docW + 1, s.length - 1)];
        float a00 = p00 >>> 24, a10 = p10 >>> 24, a01 = p01 >>> 24, a11 = p11 >>> 24;
        float a = (a00 * (1 - tx) + a10 * tx) * (1 - ty) + (a01 * (1 - tx) + a11 * tx) * ty;
        if (a < 0.5f) return 0;
        float g = ((p00 & 255) * a00 * (1 - tx) + (p10 & 255) * a10 * tx) * (1 - ty)
                + ((p01 & 255) * a01 * (1 - tx) + (p11 & 255) * a11 * tx) * ty;
        return Fx.pix(Math.round(a), Fx.clamp(Math.round(g / a)));
    }

    // ---------- Layer masks ----------

    public void addMask() {
        named("Add mask");
        Layer l = cur();
        if (l.mask != null) return;
        Layer m = newLayer(l.name + " mask");
        m.bmp.eraseColor(Color.WHITE);
        apply(new Op(() -> { l.mask = null; l.editMask = false; }, () -> { l.mask = m; l.editMask = true; }, 64));
    }

    public void deleteMask() {
        named("Delete mask");
        Layer l = cur();
        Layer m = l.mask;
        if (m == null) return;
        apply(new Op(() -> l.mask = m, () -> { l.mask = null; l.editMask = false; }, 64));
    }

    /** Bakes the mask into the layer's transparency. */
    public void applyMask() {
        named("Apply mask");
        Layer l = cur();
        Layer m = l.mask;
        if (m == null) return;
        l.editMask = false;
        PixelEdit pe = new PixelEdit(l, new Rect(0, 0, docW, docH));
        int n = docW * docH;
        int[] px = new int[n], mk = new int[n];
        l.bmp.getPixels(px, 0, docW, 0, 0, docW, docH);
        m.bmp.getPixels(mk, 0, docW, 0, 0, docW, docH);
        for (int i = 0; i < n; i++) {
            int v = (mk[i] & 255) * (mk[i] >>> 24) / 255;
            px[i] = (((px[i] >>> 24) * v / 255) << 24) | (px[i] & 0xFFFFFF);
        }
        l.bmp.setPixels(px, 0, docW, 0, 0, docW, docH);
        push(new Op(() -> { pe.undo(); l.mask = m; }, () -> { pe.redo(); l.mask = null; }, pe.bytes()));
        l.mask = null;
        afterHistory();
    }

    public void editMask(boolean on) {
        commitMove();
        Layer l = cur();
        if (l.mask == null) on = false;
        l.editMask = on;
        syncBackup();
        changed();
    }

    // ---------- Layer groups ----------

    /** Groups the current layer with the one below (joins the lower layer's group if it has one). */
    public void groupWithBelow() {
        named("Group");
        int i = current;
        if (i == 0) return;
        Layer a = layers.get(i), b = layers.get(i - 1);
        int oa = a.group, ob = b.group;
        int g = ob != 0 ? ob : (oa != 0 ? oa : nextGroup++);
        if (!groups.containsKey(g)) groups.put(g, new Group("Group " + g));
        apply(new Op(() -> { a.group = oa; b.group = ob; }, () -> { a.group = g; b.group = g; }, 64));
    }

    public void ungroup() {
        named("Ungroup");
        Layer a = cur();
        int old = a.group;
        if (old == 0) return;
        apply(new Op(() -> a.group = old, () -> a.group = 0, 64));
    }

    public void newGroup() {
        named("New group");
        Layer a = cur();
        int old = a.group, g = nextGroup++;
        groups.put(g, new Group("Group " + g));
        apply(new Op(() -> a.group = old, () -> a.group = g, 64));
    }

    /** Merges all layers of a group into one layer. */
    public void flattenGroup(int g) {
        named("Flatten group");
        commitMove();
        int i = 0;
        while (i < layers.size() && layers.get(i).group != g) i++;
        int j = i;
        while (j < layers.size() && layers.get(j).group == g) j++;
        if (j - i < 1) return;
        Layer out = newLayer(groups.containsKey(g) ? groups.get(g).name : "Group");
        Canvas c = new Canvas(out.bmp);
        renderRange(c, i, j, false);
        java.util.List<Layer> removed = new ArrayList<>(layers.subList(i, j));
        int at = i;
        apply(new Op(() -> { layers.remove(out); layers.addAll(at, removed); current = at; },
                     () -> { layers.removeAll(removed); layers.add(at, out); current = at; }, 64));
    }

    // ---------- Layer properties ----------

    public void setBlend(int i, int mode) {
        named("Blend mode");
        Layer l = layers.get(i);
        int old = l.blend;
        apply(new Op(() -> l.blend = old, () -> l.blend = mode, 64));
    }

    public void toggleAlphaLock(int i) {
        named("Alpha lock");
        Layer l = layers.get(i);
        boolean v = l.alphaLock;
        apply(new Op(() -> l.alphaLock = v, () -> l.alphaLock = !v, 64));
    }

    public void toggleClip(int i) {
        named("Clipping mask");
        Layer l = layers.get(i);
        boolean v = l.clip;
        apply(new Op(() -> l.clip = v, () -> l.clip = !v, 64));
    }

    public void rename(int i, String name) {
        named("Rename layer");
        Layer l = layers.get(i);
        String old = l.name;
        apply(new Op(() -> l.name = old, () -> l.name = name, 64));
    }

    /** New layer with text, centred, in the ink grey. */
    public boolean addTextLayer(String text, float sizePx, boolean bold) {
        return addTextLayer(new TextInfo(text, sizePx, bold, 1, "sans-serif", grey));
    }

    /** New layer with editable text, centred on the canvas. */
    public boolean addTextLayer(TextInfo t) {
        Layer l = newLayer(t.text.length() > 20 ? t.text.substring(0, 20) + "…" : t.text);
        l.text = t;
        drawText(l, t, docW / 2f, docH / 2f);
        return addLayer(l);
    }

    private void drawText(Layer l, TextInfo t, float cx, float cy) {
        android.text.TextPaint tp = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.rgb(t.grey, t.grey, t.grey));
        tp.setTextSize(t.size);
        tp.setTypeface(android.graphics.Typeface.create(t.font, t.bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        android.text.Layout.Alignment al = t.align == 0 ? android.text.Layout.Alignment.ALIGN_NORMAL
                : t.align == 2 ? android.text.Layout.Alignment.ALIGN_OPPOSITE : android.text.Layout.Alignment.ALIGN_CENTER;
        int width = Math.max(1, (int) (docW * 0.9f));
        android.text.StaticLayout sl = android.text.StaticLayout.Builder.obtain(t.text, 0, t.text.length(), tp, width).setAlignment(al).build();
        l.bmp.eraseColor(0);
        Canvas c = new Canvas(l.bmp);
        c.translate(cx - width / 2f, cy - sl.getHeight() / 2f);
        sl.draw(c);
        l.dirty = true;
    }

    /** Re-renders an editable text layer where its text currently is (after moving it). */
    public void editText(Layer l, TextInfo t) {
        int[] px = new int[docW * docH];
        l.bmp.getPixels(px, 0, docW, 0, 0, docW, docH);
        int minX = docW, minY = docH, maxX = -1, maxY = -1;
        for (int i = 0; i < px.length; i++) if ((px[i] >>> 24) != 0) {
            int x = i % docW, y = i / docW;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        float cx = maxX < 0 ? docW / 2f : (minX + maxX) / 2f, cy = maxY < 0 ? docH / 2f : (minY + maxY) / 2f;
        named("Edit text");
        TextInfo old = l.text;
        PixelEdit pe = new PixelEdit(l, new Rect(0, 0, docW, docH));
        drawText(l, t, cx, cy);
        l.text = t;
        l.name = t.text.length() > 20 ? t.text.substring(0, 20) + "…" : t.text;
        push(new Op(() -> { pe.undo(); l.text = old; }, () -> { pe.redo(); l.text = t; }, pe.bytes()));
        afterHistory();
    }

    private void afterHistory() {
        if (current >= layers.size()) current = layers.size() - 1;
        for (Layer l : layers) l.dirty = true;
        syncBackup();
        changed();
    }

    // ---- Step-by-step undo while transforming (before Done) ----
    private final ArrayList<float[]> xfUndo = new ArrayList<>(), xfRedo = new ArrayList<>();
    private int moveUndoSize;

    private float[] xfState() {
        float[] st = new float[9 + 9 + 8 + 32 + 1];
        float[] m = new float[9];
        moveT.getValues(m); System.arraycopy(m, 0, st, 0, 9);
        poly.getValues(m); System.arraycopy(m, 0, st, 9, 9);
        System.arraycopy(dstCorners, 0, st, 18, 8);
        System.arraycopy(warp, 0, st, 26, 32);
        st[58] = xform;
        return st;
    }

    private void xfRestore(float[] st) {
        moveT.setValues(java.util.Arrays.copyOfRange(st, 0, 9));
        poly.setValues(java.util.Arrays.copyOfRange(st, 9, 18));
        dstCorners = java.util.Arrays.copyOfRange(st, 18, 26);
        System.arraycopy(st, 26, warp, 0, 32);
        xform = (int) st[58];
        invalidate();
    }

    /** Remembers the transform before a change (a drag, pinch, handle drag, flip, rotate, reset). */
    private void xfMark() {
        if (!moving) return;
        xfUndo.add(xfState());
        xfRedo.clear();
    }

    /** Drops the last mark if the gesture didn't change anything (e.g. a tap). */
    private void xfUnmarkIfSame() {
        if (xfUndo.isEmpty()) return;
        if (java.util.Arrays.equals(xfUndo.get(xfUndo.size() - 1), xfState())) xfUndo.remove(xfUndo.size() - 1);
    }

    public Runnable onTransformChanged;

    /** Puts the lifted content back exactly where it was and ends the transform with no undo step. */
    private void cancelMove() {
        moveT.reset(); poly.reset(); resetWarp(); dstCorners = srcCorners.clone();
        int keep = Math.min(moveUndoSize, undoList.size());
        commitMove();
        while (undoList.size() > keep) undoList.remove(undoList.size() - 1); // the move never happened
        afterHistory();
    }

    public void undo() {
        String nm = !moving && !undoList.isEmpty() ? undoList.get(undoList.size() - 1).name() : "Transform step";
        undoInner();
        if (onHistory != null) onHistory.accept("Undo: " + nm);
    }

    public void redo() {
        String nm = !moving && !redoList.isEmpty() ? redoList.get(redoList.size() - 1).name() : "Transform step";
        redoInner();
        if (onHistory != null) onHistory.accept("Redo: " + nm);
    }

    private void undoInner() {
        finishShapeEdit();
        if (moving) { // step back through the transform without leaving it
            if (!xfUndo.isEmpty()) { xfRedo.add(xfState()); xfRestore(xfUndo.remove(xfUndo.size() - 1)); }
            else cancelMove();
            if (onTransformChanged != null) onTransformChanged.run();
            return;
        }
        commitMove();
        if (undoList.isEmpty()) return;
        Edit e = undoList.remove(undoList.size() - 1);
        e.undo();
        redoList.add(e);
        afterHistory();
    }

    private void redoInner() {
        finishShapeEdit();
        if (moving && !xfRedo.isEmpty()) {
            xfUndo.add(xfState());
            xfRestore(xfRedo.remove(xfRedo.size() - 1));
            if (onTransformChanged != null) onTransformChanged.run();
            return;
        }
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
        named("Add layer");
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
        d.copyProps(s);
        return addLayer(d);
    }

    public void deleteLayer() {
        named("Delete layer");
        commitMove();
        if (layers.size() < 2) { clearLayer(); return; }
        int at = current;
        Layer l = cur();
        apply(new Op(() -> { layers.add(at, l); current = at; },
                     () -> { layers.remove(at); current = Math.max(0, at - 1); }, 64));
    }

    /** Undo step that restores the whole layer list (order, groups). */
    private void listOp(String nm, Runnable change) {
        commitMove();
        ArrayList<Layer> before = new ArrayList<>(layers);
        int[] gBefore = new int[before.size()];
        for (int i = 0; i < gBefore.length; i++) gBefore[i] = before.get(i).group;
        int cb = current;
        change.run();
        ArrayList<Layer> after = new ArrayList<>(layers);
        int[] gAfter = new int[after.size()];
        for (int i = 0; i < gAfter.length; i++) gAfter[i] = after.get(i).group;
        int ca = Math.min(current, layers.size() - 1);
        named(nm);
        push(new Op(() -> { layers.clear(); layers.addAll(before); for (int i = 0; i < gBefore.length; i++) before.get(i).group = gBefore[i]; current = cb; },
                    () -> { layers.clear(); layers.addAll(after); for (int i = 0; i < gAfter.length; i++) after.get(i).group = gAfter[i]; current = ca; }, 64));
        afterHistory();
    }

    /** Drag-to-reorder: moves layer from -> to. */
    public void moveLayerTo(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= layers.size() || to >= layers.size()) return;
        listOp("Move layer", () -> {
            Layer l = layers.remove(from);
            layers.add(to, l);
            // dropped inside a group joins it; dropped outside leaves it
            int below = to > 0 ? layers.get(to - 1).group : 0, above = to < layers.size() - 1 ? layers.get(to + 1).group : 0;
            l.group = below != 0 && below == above ? below : (below == above ? below : (l.group == below || l.group == above ? l.group : 0));
            current = to;
        });
    }

    public void deleteLayers(java.util.List<Layer> sel2) {
        if (sel2.isEmpty() || sel2.size() >= layers.size()) return;
        listOp("Delete layers", () -> { layers.removeAll(sel2); current = Math.max(0, Math.min(current, layers.size() - 1)); });
    }

    /** Groups the given layers, moving them next to each other. */
    public void groupLayers(java.util.List<Layer> sel2) {
        if (sel2.size() < 1) return;
        int g = nextGroup++;
        groups.put(g, new Group("Group " + g));
        listOp("Group", () -> {
            int top = 0;
            for (Layer l : sel2) top = Math.max(top, layers.indexOf(l));
            ArrayList<Layer> ordered = new ArrayList<>();
            for (Layer l : layers) if (sel2.contains(l)) ordered.add(l);
            Layer anchor = layers.get(top);
            layers.removeAll(ordered);
            int at = 0;
            for (int i = 0; i < layers.size(); i++) if (layers.get(i) == anchor) at = i;
            at = Math.min(layers.size(), Math.max(0, top - ordered.size() + 1));
            layers.addAll(at, ordered);
            for (Layer l : ordered) l.group = g;
            current = at + ordered.size() - 1;
        });
    }

    /** Pinch-merge: merges layers a..b (inclusive) into one, keeping blend and opacity results. */
    public void mergeRange(int a, int b) {
        int lo = Math.max(0, Math.min(a, b)), hi = Math.min(layers.size() - 1, Math.max(a, b));
        if (hi <= lo) return;
        Layer out = newLayer(layers.get(hi).name);
        renderRange(new Canvas(out.bmp), lo, hi + 1, false);
        java.util.List<Layer> gone = new ArrayList<>(layers.subList(lo, hi + 1));
        listOp("Merge layers", () -> { layers.removeAll(gone); layers.add(lo, out); current = lo; });
    }

    public void moveLayer(int dir) {
        named("Move layer");
        commitMove();
        int a = current, b = current + dir;
        if (b < 0 || b >= layers.size()) return;
        apply(new Op(() -> { Collections.swap(layers, a, b); current = a; },
                     () -> { Collections.swap(layers, a, b); current = b; }, 64));
    }

    public void mergeDown() {
        named("Merge down");
        commitMove();
        int at = current;
        if (at == 0) return;
        Layer up = layers.get(at), down = layers.get(at - 1);
        PixelEdit pe = new PixelEdit(down, new Rect(0, 0, docW, docH));
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setAlpha(up.visible ? up.opacity : 0);
        p.setBlendMode(up.clip ? android.graphics.BlendMode.SRC_ATOP : BLENDS[up.blend]);
        new Canvas(down.bmp).drawBitmap(up.bmp, 0, 0, p);
        Runnable r = () -> { layers.remove(up); current = at - 1; };
        push(new Op(() -> { pe.undo(); layers.add(at, up); current = at; },
                    () -> { pe.redo(); r.run(); }, pe.bytes()));
        r.run();
        afterHistory();
    }

    public void clearLayer() {
        if (isLocked()) return;
        named("Clear layer");
        commitMove();
        push(new PixelEdit(tgt(), new Rect(0, 0, docW, docH)));
        if (sel == null) tgt().bmp.eraseColor(0);
        else {
            int[] px = new int[docW * docH];
            tgt().bmp.getPixels(px, 0, docW, 0, 0, docW, docH);
            for (int i = 0; i < px.length; i++)
                px[i] = (((px[i] >>> 24) * (255 - (sel[i] & 255)) / 255) << 24) | (px[i] & 0xFFFFFF);
            tgt().bmp.setPixels(px, 0, docW, 0, 0, docW, docH);
        }
        afterHistory();
    }

    public void toggleVisible(int i) {
        named("Layer visibility");
        Layer l = layers.get(i);
        boolean v = l.visible;
        apply(new Op(() -> l.visible = v, () -> l.visible = !v, 64));
    }

    /** Live opacity preview; call commitOpacity when the slider is released. */
    public void previewOpacity(int o) { cur().opacity = o; invalidate(); }

    public void commitOpacity(int before) {
        named("Layer opacity");
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

    /** All visible layers flattened onto the background colour (transparent if the background is hidden). */
    public Bitmap flatten() {
        commitMove();
        Bitmap out = Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        if (bgVisible) c.drawColor(paperColor());
        render(c, false);
        return out;
    }

    static byte[] deflate(byte[] b) {
        Deflater d = new Deflater(1);
        d.setInput(b);
        d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        while (!d.finished()) o.write(buf, 0, d.deflate(buf));
        d.end();
        return o.toByteArray();
    }

    /** Returns null if the data doesn't have exactly n bytes. */
    static byte[] inflate(byte[] data, int n) {
        byte[] out = new byte[n];
        Inflater in = new Inflater();
        in.setInput(data);
        int k = 0;
        try { while (k < n && !in.finished()) { int r = in.inflate(out, k, n - k); if (r == 0 && in.needsInput()) break; k += r; } }
        catch (Exception e) { k = -1; }
        boolean ok = k == n && in.finished();
        in.end();
        return ok ? out : null;
    }

    /** Renders a sample S-stroke with the brush, pressure rising then falling, in white. */
    public static Bitmap brushPreview(Context ctx, Brush b, int w, int h) {
        CanvasView v = new CanvasView(ctx);
        v.setDocument(w, h, new ArrayList<>());
        v.brush = b;
        v.grey = 255;
        v.size = h * 0.34f;
        v.penInput = true;
        v.stroking = true;
        int n = 60;
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            float x = w * (0.06f + 0.88f * t), y = h * (0.5f + 0.22f * (float) Math.sin(t * Math.PI * 2));
            float p = 0.25f + 0.75f * (float) Math.sin(t * Math.PI);
            if (i == 0) { v.smoothReset(x, y); v.addPt(x, y, p); v.record(x, y, p); v.startAt(x, y, p); }
            else { v.pt[0] = x; v.pt[1] = y; v.feed(p); }
        }
        v.finishSmoothing(null);
        v.flush();
        v.stroking = false;
        return v.cur().bmp;
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
