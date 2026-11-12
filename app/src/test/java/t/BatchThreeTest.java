package t;

import app.inkwell.*;
import android.graphics.Bitmap;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.io.File;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Backup/restore, crop, size preview, preview swipe, speed + motion filtering, rendering/blend, per-layer assist,
 *  animated PNG, text extras. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w841dp-h701dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BatchThreeTest extends Base {
    float W, H;
    @Before public void up() { launch(); closeGallery(); W = cv.getWidth(); H = cv.getHeight(); }
    Bitmap L() { return cv.cur().bmp; }
    CanvasView.Brush brush(String name) { for (CanvasView.Brush b : CanvasView.BRUSHES) if (b.name.equals(name)) return b; return null; }
    void put(CanvasView.Brush b, String k, float v) { call(b, "put", k, v); }

    void waitSaves() throws Exception { for (int i = 0; i < 20; i++) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS); Thread.sleep(50); } }

    @Test public void backup_and_restore_gallery() throws Exception {
        line(W * .3f, H * .4f, W * .7f, H * .4f);
        String id = (String) get(act, "curId");
        call(act, "setName", id, "Keep me");
        call(act, "saveCurrent", (Object) null);
        waitSaves();
        File zip = new File(OUT, "backup.zip");
        zip.delete();
        call(act, "backup", android.net.Uri.fromFile(zip));
        waitSaves();
        assertTrue("backup written", zip.length() > 1000);
        Set<String> names = new HashSet<>();
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip)) { z.stream().forEach(e -> names.add(e.getName())); }
        assertTrue(names.contains("gallery/" + id + ".psd"));
        assertTrue(names.contains("prefs.txt"));
        // lose the drawing, then restore it
        File gdir = (File) get(act, "gdir");
        File psd = new File(gdir, id + ".psd");
        for (File f : Objects.requireNonNull(gdir.listFiles())) if (f.getName().startsWith(id)) f.delete();
        assertFalse(psd.exists());
        call(act, "restore", android.net.Uri.fromFile(zip));
        waitSaves();
        assertTrue("drawing back", psd.exists());
        assertEquals("name back", "Keep me", call(act, "nameOf", id));
        // restoring again adds nothing and changes nothing
        long t = psd.lastModified();
        call(act, "restore", android.net.Uri.fromFile(zip));
        waitSaves();
        assertEquals(t, psd.lastModified());
    }

    @Test public void crop_box_drag_rotate_apply() {
        line(W * .3f, H * .5f, W * .7f, H * .5f);
        int w0 = cv.docW, h0 = cv.docH;
        call(act, "startCrop"); settle();
        assertTrue(cv.cropping);
        // drag the right edge handle inwards
        float[] h = {cv.crop.right, cv.crop.centerY()};
        ((android.graphics.Matrix) get(cv, "view")).mapPoints(h);
        stroke(new float[][]{{h[0], h[1]}, {h[0] - 60, h[1]}, {h[0] - 120, h[1]}}, MotionEvent.TOOL_TYPE_FINGER, 1);
        assertTrue("narrower", cv.crop.width() < w0 - 20);
        assertEquals(h0, cv.crop.height(), 0.5f);
        cv.cropRot = 10;
        settle(); shot("86_crop");
        cv.cropRot = 0;
        int nw = Math.round(cv.crop.width());
        cv.applyCrop();
        assertFalse(cv.cropping);
        assertEquals(nw, cv.docW);
        assertEquals(h0, cv.docH);
        assertTrue("artwork kept", inkSum(L()) > 0);
    }

    @Test public void size_preview_while_sliding() {
        Ui_listener(0.5f, false);
        assertTrue("preview shown", (Float) get(cv, "sizePrevX") >= 0);
        settle(); shot("87_size_preview");
        Ui_listener(0.5f, true);
        assertEquals(-1f, (Float) get(cv, "sizePrevX"), 0);
    }

    void Ui_listener(float v, boolean done) {
        Object s = get(act, "sizeS");
        Object l = get(s, "listener");
        try {
            java.lang.reflect.Method m = l.getClass().getMethod("changed", float.class, boolean.class);
            m.setAccessible(true);
            m.invoke(l, v, done);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test public void preview_swipes_between_drawings() throws Exception {
        String a = (String) get(act, "curId");
        call(act, "saveCurrent", (Object) null); waitSaves();
        call(act, "newGalleryItem", 600, 800); settle();
        String b = (String) get(act, "curId");
        call(act, "saveCurrent", (Object) null); waitSaves();
        call(act, "showPreview", a); settle();
        android.view.ViewGroup shell = (android.view.ViewGroup) get(act, "shell");
        View ov = shell.getChildAt(shell.getChildCount() - 1);
        TextView label = (TextView) ((android.view.ViewGroup) ov).getChildAt(1);
        String first = label.getText().toString();
        t0 = android.os.SystemClock.uptimeMillis();
        float dir = first.contains("1 / 2") ? -1 : 1; // swipe towards the other drawing
        ov.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, new float[][]{{400, 400}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        ov.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, new float[][]{{400 + dir * 200, 400}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        ov.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, new float[][]{{400 + dir * 400, 400}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        assertNotEquals("swiped to the other drawing", first, label.getText().toString());
        // a tap closes it
        ov.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, new float[][]{{400, 400}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        ov.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, new float[][]{{402, 400}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        assertNull(ov.getParent());
    }

    MotionEvent pen(int action, float x, float y, long t) {
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0; pp[0].toolType = MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = x; pc[0].y = y; pc[0].pressure = 1; pc[0].size = 1;
        return MotionEvent.obtain(t0, t, action, 1, pp, pc, 0, 0, 1, 1, 0, 0, 0, 0);
    }

    /** A pen line with a fixed time between points (ms), optionally shaky. */
    void timedLine(float y, long dtMs, float shake) {
        t0 = android.os.SystemClock.uptimeMillis();
        long t = t0;
        touch(cv, pen(MotionEvent.ACTION_DOWN, W * .2f, y, t));
        for (int i = 1; i <= 40; i++) {
            t += dtMs;
            touch(cv, pen(MotionEvent.ACTION_MOVE, W * (.2f + .6f * i / 40), y + (i % 2 == 0 ? shake : -shake), t));
        }
        touch(cv, pen(MotionEvent.ACTION_UP, W * .8f, y, t + dtMs));
        settle();
    }

    int inkRows(Bitmap b) { // how many rows contain ink (stroke thickness incl. shake)
        int n = 0;
        int[] row = new int[b.getWidth()];
        for (int y = 0; y < b.getHeight(); y++) {
            b.getPixels(row, 0, b.getWidth(), 0, y, b.getWidth(), 1);
            for (int px : row) if ((px >>> 24) > 60) { n++; break; }
        }
        return n;
    }

    @Test public void speed_dynamics_and_motion_filtering() {
        CanvasView.Brush b = brush("Fine liner");
        cv.brush = b;
        cv.size = 14;
        put(b, "speedSize", 1f);
        timedLine(H * .4f, 60, 0);   // slow
        long slow = inkSum(L());
        cv.clearLayer();
        timedLine(H * .4f, 2, 0);    // fast
        long fast = inkSum(L());
        assertTrue("fast strokes thinner: slow " + slow + " fast " + fast, fast < slow * 0.8);
        put(b, "speedSize", 0f);
        // motion filtering: shaky input gives a thinner band
        cv.clearLayer();
        cv.size = 4;
        timedLine(H * .4f, 16, 5);
        int shaky = inkRows(L());
        cv.clearLayer();
        put(b, "motionFilter", 1f);
        timedLine(H * .4f, 16, 5);
        int filtered = inkRows(L());
        put(b, "motionFilter", 0f);
        assertTrue("shake removed: " + shaky + " -> " + filtered, filtered < shaky);
    }

    @Test public void heavy_glaze_and_blend_modes() {
        CanvasView.Brush b = brush("Hard round");
        cv.brush = b;
        cv.size = 30;
        cv.opacity = 0.3f;
        float[][] back = {{W * .3f, H * .5f}, {W * .6f, H * .5f}, {W * .3f, H * .5f}, {W * .6f, H * .5f}};
        stroke(back, MotionEvent.TOOL_TYPE_STYLUS, 1);
        int light = alphaAt(L(), W * .45f, H * .5f);
        cv.clearLayer();
        put(b, "render", 1f);
        stroke(back, MotionEvent.TOOL_TYPE_STYLUS, 1);
        int heavy = alphaAt(L(), W * .45f, H * .5f);
        put(b, "render", 0f);
        assertTrue("heavy glaze builds up: " + light + " vs " + heavy, heavy > light + 30);
        // multiply: grey over grey gets darker than normal
        cv.opacity = 1f;
        cv.grey = 128; cv.fillLayer();
        put(b, "blend", 1f);
        stroke(new float[][]{{W * .3f, H * .3f}, {W * .45f, H * .3f}, {W * .6f, H * .3f}}, MotionEvent.TOOL_TYPE_STYLUS, 1);
        int[] d = toDoc(W * .45f, H * .3f);
        int g = L().getPixel(d[0], d[1]) & 255;
        put(b, "blend", 0f);
        assertEquals("multiply 128 × 128", 64, g, 3);
    }

    @Test public void drawing_assist_per_layer() {
        cv.cur().assist = true;
        cv.addLayer(cv.newLayer("free"));
        assertFalse("new layer has its own setting", cv.cur().assist);
        String st = cv.guideState();
        cv.layers.get(0).assist = false;
        cv.setGuideState(st);
        assertTrue("saved per layer", cv.layers.get(0).assist);
        assertFalse(cv.layers.get(1).assist);
        cv.toggleAssist(1);
        assertTrue(cv.layers.get(1).assist);
        cv.undo();
        assertFalse(cv.layers.get(1).assist);
        cv.setGuideState("1,100,true,0.5,1,4,0,0,0,0,0,0,true"); // older drawings: one switch for all
        assertTrue(cv.layers.get(0).assist && cv.layers.get(1).assist);
    }

    @Test public void animated_png() throws Exception {
        Class<?> v = Class.forName("app.inkwell.Video");
        Class<?> fr = Class.forName("app.inkwell.Video$Frames");
        Object frames = java.lang.reflect.Proxy.newProxyInstance(fr.getClassLoader(), new Class[]{fr}, (p, m, a) -> {
            if (m.getName().equals("count")) return 3;
            Bitmap b = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
            b.eraseColor(0xFF000000 | ((Integer) a[0] * 100) * 0x10101);
            return b;
        });
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        java.lang.reflect.Method ap = v.getDeclaredMethod("apng", java.io.OutputStream.class, int.class, int.class, int.class, fr);
        ap.setAccessible(true);
        ap.invoke(null, o, 40, 30, 12, frames);
        byte[] png = o.toByteArray();
        String s = new String(png, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("\u0089PNG"));
        assertTrue(s.contains("acTL") && s.contains("fdAT"));
        assertEquals("3 frame controls", 3, s.split("fcTL", -1).length - 1);
        Bitmap first = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length); // plain viewers show frame 1
        assertNotNull(first);
        assertEquals(40, first.getWidth());
    }

    @Test public void text_spacing_outline_rasterize() {
        CanvasView.TextInfo t = new CanvasView.TextInfo("HELLO", 120, false, 1, "sans-serif", 0);
        assertTrue(cv.addTextLayer(t));
        CanvasView.Layer l = cv.cur();
        long normal = inkSum(l.bmp);
        int w0 = inkWidth(l.bmp);
        CanvasView.TextInfo wide = new CanvasView.TextInfo("HELLO", 120, false, 1, "sans-serif", 0);
        wide.spacing = 0.4f;
        cv.editText(l, wide);
        assertTrue("letter spacing widens", inkWidth(l.bmp) > w0 + 40);
        CanvasView.TextInfo out = new CanvasView.TextInfo("HELLO", 120, false, 1, "sans-serif", 0);
        out.outline = true;
        cv.editText(l, out);
        assertTrue("outline uses less ink", inkSum(l.bmp) < normal * 0.8);
        settle(); shot("88_text_outline");
        cv.rasterizeText(l);
        assertNull(l.text);
        cv.undo();
        assertNotNull("undo brings the editable text back", l.text);
    }

    int inkWidth(Bitmap b) {
        int min = b.getWidth(), max = -1;
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        for (int i = 0; i < px.length; i++) if ((px[i] >>> 24) > 60) { int x = i % b.getWidth(); min = Math.min(min, x); max = Math.max(max, x); }
        return max - min;
    }
}
