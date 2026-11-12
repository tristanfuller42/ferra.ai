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
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Tilt, eyedropper ring, QuickShape arc/triangle/polyline, QuickMenu, gesture settings, layer two-finger gestures,
 *  brush export/recent/order, Page Assist, fonts, transform extras, gallery sort/composite. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w841dp-h701dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BatchTwoTest extends Base {
    float W, H;
    @Before public void up() { launch(); closeGallery(); W = cv.getWidth(); H = cv.getHeight(); }
    Bitmap L() { return cv.cur().bmp; }
    String hud() { TextView t = (TextView) get(act, "hudView"); return t == null ? "" : String.valueOf(t.getText()); }

    MotionEvent penEv(int action, float x, float y, float tilt) {
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0; pp[0].toolType = MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = x; pc[0].y = y; pc[0].pressure = 0.6f; pc[0].size = 1;
        pc[0].setAxisValue(MotionEvent.AXIS_TILT, tilt);
        return MotionEvent.obtain(t0, android.os.SystemClock.uptimeMillis(), action, 1, pp, pc, 0, 0, 1, 1, 0, 0, 0, 0);
    }

    void penLine(float y, float tilt) {
        t0 = android.os.SystemClock.uptimeMillis();
        touch(cv, penEv(MotionEvent.ACTION_DOWN, W * .3f, y, tilt));
        for (int i = 1; i <= 20; i++) { ShadowLooper.idleMainLooper(8, TimeUnit.MILLISECONDS); touch(cv, penEv(MotionEvent.ACTION_MOVE, W * (.3f + .4f * i / 20), y, tilt)); }
        touch(cv, penEv(MotionEvent.ACTION_UP, W * .7f, y, tilt));
        settle();
    }

    CanvasView.Brush brush(String name) { for (CanvasView.Brush b : CanvasView.BRUSHES) if (b.name.equals(name)) return b; return null; }

    /** Drags through points with a stylus and holds at the end (QuickShape). */
    void heldStroke(float[][] pts) {
        t0 = android.os.SystemClock.uptimeMillis();
        touch(cv, ev(MotionEvent.ACTION_DOWN, new float[][]{pts[0]}, MotionEvent.TOOL_TYPE_STYLUS, 1));
        for (int i = 1; i < pts.length; i++) for (int k = 1; k <= 6; k++) {
            float x = pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * k / 6, y = pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * k / 6;
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS);
            touch(cv, ev(MotionEvent.ACTION_MOVE, new float[][]{{x, y}}, MotionEvent.TOOL_TYPE_STYLUS, 1));
        }
        ShadowLooper.idleMainLooper(700, TimeUnit.MILLISECONDS);
        touch(cv, ev(MotionEvent.ACTION_UP, new float[][]{pts[pts.length - 1]}, MotionEvent.TOOL_TYPE_STYLUS, 1));
    }

    int shapeKind() { return (Integer) get(cv, "shapeKind"); }

    @Test public void tilt_widens_pencil_strokes() {
        cv.brush = brush("6B pencil");
        assertTrue((Float) call(cv.brush, "get", "tiltSize") > 0);
        cv.size = 12;
        penLine(H * .3f, 0f);
        long upright = inkSum(L());
        cv.clearLayer();
        penLine(H * .3f, 1.3f); // pen nearly flat
        long tilted = inkSum(L());
        assertTrue("tilted stroke covers more: " + upright + " vs " + tilted, tilted > upright * 1.5);
        // brushes without tilt settings ignore it
        cv.clearLayer();
        cv.brush = brush("Ink pen");
        penLine(H * .3f, 0f);
        long a = inkSum(L());
        cv.clearLayer();
        penLine(H * .3f, 1.3f);
        assertEquals(a, inkSum(L()));
    }

    @Test public void eyedropper_ring_shows_new_and_old_grey() {
        line(W * .2f, H * .5f, W * .8f, H * .5f);
        cv.grey = 200;
        cv.pickNext = true;
        float[] p = {W * .5f, H * .5f};
        t0 = android.os.SystemClock.uptimeMillis();
        touch(cv, ev(MotionEvent.ACTION_DOWN, new float[][]{p}, MotionEvent.TOOL_TYPE_FINGER, 1));
        assertTrue((Boolean) get(cv, "picking"));
        assertEquals("old grey kept for the lower half", 200, (int) (Integer) get(cv, "pickOld"));
        assertEquals("new grey picked", 0, cv.grey);
        settle(); shot("80_eyedropper_ring");
        touch(cv, ev(MotionEvent.ACTION_UP, new float[][]{p}, MotionEvent.TOOL_TYPE_FINGER, 1));
        assertFalse((Boolean) get(cv, "picking"));
    }

    @Test public void quickshape_arc_triangle_polyline() {
        // arc: a smooth bow
        float[][] arc = new float[13][];
        for (int i = 0; i <= 12; i++) { double a = Math.PI * i / 12; arc[i] = new float[]{(float) (W * .5f - Math.cos(a) * W * .2f), (float) (H * .5f - Math.sin(a) * H * .2f)}; }
        heldStroke(arc);
        assertEquals("arc", 3, shapeKind());
        shot("81_quickshape_arc");
        cv.finishShapeEdit();
        cv.clearLayer();
        // triangle: closed with three corners
        heldStroke(new float[][]{{W * .5f, H * .25f}, {W * .7f, H * .65f}, {W * .3f, H * .65f}, {W * .5f, H * .26f}});
        assertEquals("triangle", 4, shapeKind());
        assertEquals(6, ((float[]) get(cv, "shp")).length);
        cv.finishShapeEdit();
        cv.clearLayer();
        // polyline: zigzag
        heldStroke(new float[][]{{W * .2f, H * .6f}, {W * .35f, H * .3f}, {W * .5f, H * .6f}, {W * .65f, H * .3f}, {W * .8f, H * .6f}});
        assertEquals("polyline", 5, shapeKind());
        assertTrue("corners as handles", ((float[]) get(cv, "shp")).length >= 8);
        shot("82_quickshape_polyline");
        cv.finishShapeEdit();
        // a straight stroke is still a line
        cv.clearLayer();
        heldStroke(new float[][]{{W * .2f, H * .4f}, {W * .8f, H * .41f}});
        assertEquals("line", 0, shapeKind());
        cv.finishShapeEdit();
    }

    @Test public void quickmenu_from_hold_and_gesture_switches() {
        cv.holdAction = 1;
        int n = cv.layers.size();
        t0 = android.os.SystemClock.uptimeMillis();
        float[] p = {W * .5f, H * .5f};
        touch(cv, ev(MotionEvent.ACTION_DOWN, new float[][]{p}, MotionEvent.TOOL_TYPE_FINGER, 1));
        ShadowLooper.idleMainLooper(cv.holdDelay + 50, TimeUnit.MILLISECONDS);
        android.widget.PopupWindow w = (android.widget.PopupWindow) get(act, "qmWin");
        assertNotNull("QuickMenu opened", w);
        assertTrue(w.isShowing());
        touch(cv, ev(MotionEvent.ACTION_UP, new float[][]{p}, MotionEvent.TOOL_TYPE_FINGER, 1));
        assertEquals("the hold drew nothing", 0, inkSum(L()));
        android.view.ViewGroup ring = (android.view.ViewGroup) w.getContentView();
        View newLayer = null;
        for (int i = 0; i < ring.getChildCount(); i++) if ("New layer".contentEquals(((TextView) ring.getChildAt(i)).getText())) newLayer = ring.getChildAt(i);
        assertNotNull(newLayer);
        newLayer.performClick(); settle();
        assertEquals(n + 1, cv.layers.size());
        assertFalse(w.isShowing());
        // the sidebar button can open it too
        act.getSharedPreferences("inkwell", 0).edit().putInt("squareAction", 1).commit();
        ((View) get(act, "pickBtn")).performClick(); settle();
        assertTrue(((android.widget.PopupWindow) get(act, "qmWin")).isShowing());
        ((android.widget.PopupWindow) get(act, "qmWin")).dismiss();
        act.getSharedPreferences("inkwell", 0).edit().putInt("squareAction", 0).commit();
        // gesture switches: two-finger tap undo off
        cv.holdAction = 0;
        line(W * .3f, H * .3f, W * .6f, H * .3f);
        long ink = inkSum(L());
        cv.gUndo = false;
        t0 = android.os.SystemClock.uptimeMillis();
        float[][] two = {{W * .4f, H * .5f}, {W * .6f, H * .5f}};
        touch(cv, ev(MotionEvent.ACTION_DOWN, new float[][]{two[0]}, MotionEvent.TOOL_TYPE_FINGER, 1));
        touch(cv, ev(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), two, MotionEvent.TOOL_TYPE_FINGER, 1));
        touch(cv, ev(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), two, MotionEvent.TOOL_TYPE_FINGER, 1));
        touch(cv, ev(MotionEvent.ACTION_UP, new float[][]{two[0]}, MotionEvent.TOOL_TYPE_FINGER, 1));
        assertEquals("undo gesture switched off", ink, inkSum(L()));
        cv.gUndo = true;
    }

    @Test public void layer_two_finger_swipe_and_hold() {
        line(W * .3f, H * .3f, W * .6f, H * .3f);
        call(act, "togglePanel"); settle();
        View list = (View) get(act, "layerList");
        View row = null;
        for (int i = 0; i < ((android.view.ViewGroup) list).getChildCount(); i++) { View c = ((android.view.ViewGroup) list).getChildAt(i); if (Integer.valueOf(cv.current).equals(c.getTag())) row = c; }
        int[] lr = new int[2]; row.getLocationOnScreen(lr);
        View d = act.getWindow().getDecorView(); // through the whole window, in screen coordinates (like a real touch)
        float y = lr[1] + row.getHeight() / 2f, x = lr[0] + row.getWidth() * .3f;
        boolean before = cv.cur().alphaLock;
        t0 = android.os.SystemClock.uptimeMillis();
        float[][] a = {{x, y - 4}, {x + 40, y + 4}};
        d.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, new float[][]{a[0]}, MotionEvent.TOOL_TYPE_FINGER, 1));
        d.dispatchTouchEvent(ev(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, MotionEvent.TOOL_TYPE_FINGER, 1));
        for (int i = 1; i <= 8; i++) {
            float dx = 30 * i;
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS);
            d.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, new float[][]{{a[0][0] + dx, a[0][1]}, {a[1][0] + dx, a[1][1]}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        }
        d.dispatchTouchEvent(ev(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new float[][]{{a[0][0] + 240, a[0][1]}, {a[1][0] + 240, a[1][1]}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        d.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, new float[][]{{a[0][0] + 240, a[0][1]}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        settle();
        assertNotEquals("two-finger swipe right toggles Alpha Lock", before, cv.cur().alphaLock);
        assertTrue(hud().contains("Alpha Lock"));
        // two-finger hold selects the layer's contents
        assertNull(cv.sel);
        t0 = android.os.SystemClock.uptimeMillis();
        d.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, new float[][]{a[0]}, MotionEvent.TOOL_TYPE_FINGER, 1));
        d.dispatchTouchEvent(ev(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, MotionEvent.TOOL_TYPE_FINGER, 1));
        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS);
        d.dispatchTouchEvent(ev(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, MotionEvent.TOOL_TYPE_FINGER, 1));
        d.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, new float[][]{a[0]}, MotionEvent.TOOL_TYPE_FINGER, 1));
        settle();
        assertNotNull("two-finger hold selects contents", cv.sel);
    }

    @Test public void brush_export_import_recent_and_order() throws Exception {
        CanvasView.Brush b = brush("HB pencil");
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        Class<?> br = Class.forName("app.inkwell.Brushes");
        java.lang.reflect.Method ex = br.getDeclaredMethod("export", List.class, java.io.OutputStream.class); ex.setAccessible(true);
        ex.invoke(null, Arrays.asList(b, brush("Charcoal")), o);
        java.lang.reflect.Method rd = br.getDeclaredMethod("read", byte[].class); rd.setAccessible(true);
        List<?> back = (List<?>) rd.invoke(null, (Object) o.toByteArray());
        assertEquals(2, back.size());
        CanvasView.Brush r0 = (CanvasView.Brush) back.get(0);
        assertEquals("HB pencil", r0.name);
        assertEquals((Float) call(b, "get", "tiltSize"), (Float) call(r0, "get", "tiltSize"), 1e-6);
        assertArrayEquals((float[]) call(b, "values"), (float[]) call(r0, "values"), 1e-6f);
        // recent
        call(act, "noteRecent", brush("Charcoal"));
        call(act, "noteRecent", brush("Ink pen"));
        List<?> rec = (List<?>) call(act, "recentBrushes");
        assertEquals("Ink pen", ((CanvasView.Brush) rec.get(0)).name);
        assertEquals("Charcoal", ((CanvasView.Brush) rec.get(1)).name);
        // order survives
        CanvasView.Brush last = CanvasView.BRUSHES.get(CanvasView.BRUSHES.size() - 1);
        CanvasView.BRUSHES.remove(last);
        CanvasView.BRUSHES.add(0, last);
        call(act, "saveBrushOrder");
        CanvasView.BRUSHES.remove(last);
        CanvasView.BRUSHES.add(last);
        call(act, "applyBrushOrder");
        assertSame(last, CanvasView.BRUSHES.get(0));
        call(act, "pickBrush"); settle(); shot("83_brush_library_recent");
    }

    @Test public void page_assist_shows_one_page_and_exports_pdf() throws Exception {
        line(W * .2f, H * .3f, W * .8f, H * .3f);           // page 1
        int[] d1 = toDoc(W * .5f, H * .3f);
        cv.addLayer(cv.newLayer("Page 2"));
        line(W * .2f, H * .6f, W * .8f, H * .6f);           // page 2
        int[] d2 = toDoc(W * .5f, H * .6f);
        call(act, "togglePages"); settle();
        assertTrue(cv.pages);
        Bitmap screen = Bitmap.createBitmap(cv.getWidth(), cv.getHeight(), Bitmap.Config.ARGB_8888);
        cv.draw(new android.graphics.Canvas(screen));
        float[] s1 = {d1[0], d1[1]}, s2 = {d2[0], d2[1]};
        ((android.graphics.Matrix) get(cv, "view")).mapPoints(s1);
        ((android.graphics.Matrix) get(cv, "view")).mapPoints(s2);
        assertTrue("current page shows", (screen.getPixel((int) s2[0], (int) s2[1]) & 255) < 100);
        assertTrue("other page hidden", (screen.getPixel((int) s1[0], (int) s1[1]) & 255) > 200);
        shot("84_page_assist");
        java.io.File f = new java.io.File(OUT, "pages.pdf");
        call(act, "exportPages", android.net.Uri.fromFile(f)); // the PDF engine only exists on a real device: just no crash here
        call(act, "toggleAnimation");
        assertFalse("animation turns page assist off", cv.pages);
        call(act, "toggleAnimation");
    }

    @Test public void fonts_reject_non_fonts_and_fall_back() throws Exception {
        java.io.File junk = new java.io.File(OUT, "junk.ttf");
        java.nio.file.Files.write(junk.toPath(), "not a font".getBytes());
        call(act, "importFont", android.net.Uri.fromFile(junk));
        java.io.File dir = new java.io.File(act.getFilesDir(), "fonts");
        assertEquals("bad font not kept", 0, Objects.requireNonNull(dir.listFiles()).length);
        assertNotNull(CanvasView.typeface(act, "file:missing.ttf", true));
        android.app.Dialog d = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull("text dialog reopened", d);
        assertNotNull(UiTest.find(d.getWindow().getDecorView(), "＋ Import font"));
        d.dismiss();
    }

    @Test public void transform_rotate45_magnetics_and_mesh() {
        line(W * .3f, H * .4f, W * .7f, H * .45f);
        call(act, "setTool", CanvasView.MOVE);
        cv.rotateBy(45);
        android.graphics.Matrix m = (android.graphics.Matrix) get(cv, "moveT");
        float[] v = new float[9]; m.getValues(v);
        assertEquals(45, Math.toDegrees(Math.atan2(v[3], v[0])), 0.01);
        // magnetics: 43° pulls to 45°, 1.02x pulls to 1.0x... from 45: rotate a little more, then snap back
        cv.snapping = true;
        float[] c = (float[]) call(cv, "contentCentre");
        m.postRotate(3, c[0], c[1]);
        call(cv, "magnetics");
        m.getValues(v);
        assertEquals("rotation snaps to 45°", 45, Math.toDegrees(Math.atan2(v[3], v[0])), 0.01);
        // advanced mesh keeps the picture where it was
        cv.setXform(3);
        float[] before = (float[]) call(cv, "meshVerts");
        cv.setAdvancedMesh(true);
        float[] after = (float[]) call(cv, "meshVerts");
        float diff = 0;
        for (int i = 0; i < before.length; i++) diff = Math.max(diff, Math.abs(before[i] - after[i]));
        assertTrue("mesh unchanged when switching: " + diff, diff < 1f);
        assertEquals("25 handles", 50, ((float[]) call(cv, "handlePoints")).length);
        settle(); shot("85_advanced_mesh");
        cv.undo(); // step back: simple warp again
        assertFalse(cv.advMesh);
        call(act, "setTool", CanvasView.DRAW);
    }

    @Test public void gallery_sort_and_composite() throws Exception {
        line(W * .3f, H * .4f, W * .7f, H * .4f);
        String a = (String) get(act, "curId");
        call(act, "setName", a, "Zebra");
        call(act, "saveCurrent", (Object) null); settle(); ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
        Thread.sleep(300);
        Bitmap comp = (Bitmap) call(act, "compositeOf", a);
        assertEquals(cv.docW, comp.getWidth());
        call(act, "newGalleryItem", 800, 600); settle();
        String b = (String) get(act, "curId");
        call(act, "setName", b, "Apple");
        call(act, "saveCurrent", (Object) null); Thread.sleep(300); settle();
        android.content.SharedPreferences pr = act.getSharedPreferences("inkwell", 0);
        pr.edit().putInt("gallerySort", 1).commit();
        List<?> ids = (List<?>) call(act, "orderedIds");
        assertTrue("by name: Apple before Zebra", ids.indexOf(b) < ids.indexOf(a));
        pr.edit().putInt("gallerySort", 0).commit();
    }
}
