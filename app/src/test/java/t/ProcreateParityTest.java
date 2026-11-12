package t;

import ai.inkwell.*;
import android.graphics.Bitmap;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** The Procreate-parity batch: layers gestures, HUD, cursor, QuickShape, ColorDrop, selection, gallery, text, animation, prefs, transform, adjustments, liquify, camera. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w841dp-h701dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ProcreateParityTest extends Base {
    float W, H;
    @Before public void up() { launch(); closeGallery(); W = cv.getWidth(); H = cv.getHeight(); }
    Bitmap L() { return cv.cur().bmp; }
    String hudText() { TextView t = (TextView) get(act, "hudView"); return t == null ? "" : String.valueOf(t.getText()); }

    void hold(float x, float y, long ms) {
        ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS);
    }

    @Test public void layers_reorder_multi_merge_lock_reference() {
        line(W * .02f, H * .3f, W * .98f, H * .3f); // spans the whole canvas, so fills can't leak around it
        cv.addLayer(cv.newLayer("B")); line(W * .3f, H * .5f, W * .6f, H * .5f);
        cv.addLayer(cv.newLayer("C"));
        cv.moveLayerTo(2, 0);
        assertEquals("C", cv.layers.get(0).name);
        cv.undo();
        assertEquals("C", cv.layers.get(2).name);
        // swipe right on a row selects it
        call(act, "togglePanel"); settle();
        View list = (View) get(act, "layerList");
        View row = null;
        for (int i = 0; i < ((android.view.ViewGroup) list).getChildCount(); i++) { View c = ((android.view.ViewGroup) list).getChildAt(i); if (Integer.valueOf(1).equals(c.getTag())) row = c; }
        assertNotNull(row);
        t0 = android.os.SystemClock.uptimeMillis();
        int[] loc = new int[2]; row.getLocationOnScreen(loc);
        float rx = loc[0] + 50, ry = loc[1] + row.getHeight() / 2f;
        row.dispatchTouchEvent(evRaw(MotionEvent.ACTION_DOWN, rx, ry));
        row.dispatchTouchEvent(evRaw(MotionEvent.ACTION_MOVE, rx + 200, ry));
        row.dispatchTouchEvent(evRaw(MotionEvent.ACTION_UP, rx + 200, ry));
        settle();
        assertEquals("swipe right selects", 1, ((Set<?>) get(act, "multi")).size());
        shot("70_layers_multiselect");
        // group / merge / delete several
        cv.groupLayers(Arrays.asList(cv.layers.get(0), cv.layers.get(1)));
        assertTrue(cv.layers.get(0).group != 0 && cv.layers.get(0).group == cv.layers.get(1).group);
        cv.undo();
        cv.mergeRange(0, 1);
        assertEquals(2, cv.layers.size());
        cv.undo();
        assertEquals(3, cv.layers.size());
        cv.deleteLayers(Arrays.asList(cv.layers.get(2)));
        assertEquals(2, cv.layers.size());
        // lock blocks painting (close the Layers panel first: a canvas touch would only close it)
        call(act, "togglePanel"); settle();
        cv.select(1);
        cv.toggleLock(1);
        long ink = inkSum(L());
        line(W * .3f, H * .7f, W * .6f, H * .7f);
        assertEquals("locked layer unchanged", ink, inkSum(L()));
        assertTrue(hudText().contains("locked"));
        cv.toggleLock(1);
        // reference: fill on an empty layer using lines from the reference layer
        cv.toggleReference(0);
        cv.select(1);
        cv.clearLayer();
        cv.colorDrop(W * .45f, H * .4f, 30);
        int[] above = toDoc(W * .45f, H * .2f), between = toDoc(W * .45f, H * .4f);
        assertEquals("fill stays between the reference lines", 255, L().getPixel(between[0], between[1]) >>> 24);
        assertEquals("not past the line", 0, L().getPixel(above[0], above[1]) >>> 24);
    }

    MotionEvent evRaw(int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(t0, android.os.SystemClock.uptimeMillis(), action, x, y, 0);
        return e;
    }

    @Test public void hud_cursor_and_history_names() {
        line(W * .3f, H * .3f, W * .6f, H * .3f);
        cv.undo();
        assertEquals("Undo: Brush stroke", hudText());
        cv.redo();
        assertEquals("Redo: Brush stroke", hudText());
        Object side = get(act, "sizeS");
        call(side, "onTouchEvent", MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 10, 20, 0));
        assertTrue(hudText().startsWith("Size"));
        cv.showCursor = true;
        MotionEvent hv = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_MOVE, W * .5f, H * .5f, 0);
        cv.dispatchGenericMotionEvent(hv);
        cv.onHoverEvent(hv);
        assertTrue((Boolean) get(cv, "cursorOn"));
        cv.size = 80;
        settle();
        shot("71_brush_cursor");
    }

    interface Ui_ { }

    @Test public void quickshape_edit_rectangle_and_angle_snap() {
        // a rough square loop, held at the end -> rectangle with handles
        float[][] sq = {{W * .3f, H * .3f}, {W * .5f, H * .31f}, {W * .7f, H * .3f}, {W * .69f, H * .5f}, {W * .7f, H * .7f}, {W * .5f, H * .69f}, {W * .3f, H * .7f}, {W * .31f, H * .5f}, {W * .3f, H * .31f}};
        t0 = android.os.SystemClock.uptimeMillis();
        touch(cv, ev(MotionEvent.ACTION_DOWN, new float[][]{sq[0]}, MotionEvent.TOOL_TYPE_STYLUS, 1));
        for (int i = 1; i < sq.length; i++) for (int k = 1; k <= 6; k++) {
            float x = sq[i - 1][0] + (sq[i][0] - sq[i - 1][0]) * k / 6, y = sq[i - 1][1] + (sq[i][1] - sq[i - 1][1]) * k / 6;
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS);
            touch(cv, ev(MotionEvent.ACTION_MOVE, new float[][]{{x, y}}, MotionEvent.TOOL_TYPE_STYLUS, 1));
        }
        ShadowLooper.idleMainLooper(700, TimeUnit.MILLISECONDS);
        touch(cv, ev(MotionEvent.ACTION_UP, new float[][]{sq[sq.length - 1]}, MotionEvent.TOOL_TYPE_STYLUS, 1));
        assertTrue("editing the shape after lifting", cv.editingShape());
        assertEquals("rectangle detected", 2, (int) (Integer) get(cv, "shapeKind"));
        shot("72_quickshape_handles");
        float[] q = ((float[]) get(cv, "quad")).clone();
        float[] h = {q[4], q[5]};
        ((android.graphics.Matrix) get(cv, "view")).mapPoints(h);
        stroke(new float[][]{{h[0], h[1]}, {h[0] + 60, h[1] + 40}}, MotionEvent.TOOL_TYPE_STYLUS, 1); // drag a corner
        assertTrue("corner moved", ((float[]) get(cv, "quad"))[4] > q[4] + 10);
        stroke(new float[][]{{W * .1f, H * .1f}}, MotionEvent.TOOL_TYPE_STYLUS, 1);         // tap elsewhere: keep
        assertFalse(cv.editingShape());
        cv.undo(); // the tap elsewhere was a dot, like Procreate
        cv.undo();
        assertEquals("then one undo removes the shape", 0, inkSum(L()));
        // line with a second finger: snaps to 15°
        t0 = android.os.SystemClock.uptimeMillis();
        touch(cv, ev(MotionEvent.ACTION_DOWN, new float[][]{{W * .2f, H * .5f}}, MotionEvent.TOOL_TYPE_STYLUS, 1));
        for (int k = 1; k <= 12; k++) { ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS); touch(cv, ev(MotionEvent.ACTION_MOVE, new float[][]{{W * (.2f + .04f * k), H * (.5f - .006f * k)}}, MotionEvent.TOOL_TYPE_STYLUS, 1)); }
        ShadowLooper.idleMainLooper(700, TimeUnit.MILLISECONDS);
        touch(cv, ev(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new float[][]{{W * .68f, H * .428f}, {W * .9f, H * .9f}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        float x0 = (Float) get(cv, "lineX0"), y0 = (Float) get(cv, "lineY0"), x1 = (Float) get(cv, "lineX1"), y1 = (Float) get(cv, "lineY1");
        double ang = Math.toDegrees(Math.atan2(y1 - y0, x1 - x0));
        assertEquals("angle snapped to 15°: " + ang, 0, Math.abs(ang % 15) < 0.5 || Math.abs(Math.abs(ang % 15) - 15) < 0.5 ? 0 : 1);
    }

    @Test public void colordrop_refine_and_continue() {
        cv.size = 10;
        line(W * .2f, H * .3f, W * .8f, H * .3f);
        line(W * .2f, H * .6f, W * .8f, H * .6f);
        cv.addLayer(cv.newLayer("fill"));
        cv.toggleReference(0);
        cv.colorDrop(W * .5f, H * .45f, 10);
        long a = inkSum(L());
        cv.refineFill(254); // everything similar to white-ish... boundaries vanish at max threshold
        assertTrue("threshold refill changes the fill: " + a + " -> " + inkSum(L()), inkSum(L()) != a);
        cv.refineFill(30);
        call(act, "showFillBar"); settle(); shot("73_colordrop_bar");
        cv.clearLayer();
        cv.fillMode = true;
        stroke(new float[][]{{W * .5f, H * .45f}}, MotionEvent.TOOL_TYPE_FINGER, 1);
        assertTrue("continue filling: tap fills", inkSum(L()) > 0);
        cv.fillMode = false;
    }

    @Test public void polygon_selection_auto_threshold_and_ants() {
        cv.setTool(CanvasView.SELECT);
        cv.selKind = 1;
        float[][] pts = {{W * .3f, H * .3f}, {W * .6f, H * .3f}, {W * .5f, H * .6f}};
        for (float[] p : pts) stroke(new float[][]{p}, MotionEvent.TOOL_TYPE_FINGER, 1);
        assertNull("still placing corners", cv.sel);
        stroke(new float[][]{pts[0]}, MotionEvent.TOOL_TYPE_FINGER, 1); // tap the first corner
        assertNotNull("polygon closed into a selection", cv.sel);
        assertNotNull("marching ants outline", get(cv, "selEdge"));
        settle(); shot("74_polygon_selection");
        cv.clearSelection();
        // automatic + threshold refine
        cv.setTool(CanvasView.DRAW);
        line(W * .2f, H * .5f, W * .8f, H * .5f);
        cv.setTool(CanvasView.SELECT);
        cv.selKind = 0;
        stroke(new float[][]{{W * .5f, H * .3f}}, MotionEvent.TOOL_TYPE_FINGER, 1);
        int before = count(cv.sel);
        cv.refineAuto(255);
        assertTrue("refine changes the automatic selection", count(cv.sel) != before);
    }

    int count(byte[] m) { int n = 0; for (byte b : m) if ((b & 255) > 127) n++; return n; }

    @Test public void gallery_stacks_select_reorder_preview() {
        String a = (String) get(act, "curId");
        call(act, "showGallery"); settle();
        call(act, "newGalleryItem", 600, 600); settle();
        String b = (String) get(act, "curId");
        call(act, "showGallery"); settle();
        Map<String, String> st = new LinkedHashMap<>();
        st.put(a, "Sketches"); st.put(b, "Sketches");
        call(act, "saveStacks", st);
        call(act, "fillGallery"); settle();
        shot("75_gallery_stack");
        set(act, "openStack", "Sketches");
        call(act, "fillGallery"); settle();
        shot("76_inside_stack");
        call(act, "moveInOrder", a, b);
        List<?> ord = (List<?>) call(act, "orderedIds");
        assertTrue("reordered", ord.indexOf(a) < ord.indexOf(b));
        call(act, "duplicateItem", a);
        assertEquals(3, ((List<?>) call(act, "orderedIds")).size());
        call(act, "showPreview", a); settle();
        shot("77_preview");
    }

    @Test public void editable_text_fonts_and_persistence() {
        CanvasView.TextInfo t = new CanvasView.TextInfo("Hello", 90, true, 0, "serif", 0);
        assertTrue(cv.addTextLayer(t));
        long before = inkSum(L());
        CanvasView.TextInfo t2 = new CanvasView.TextInfo("Hello world", 120, false, 2, "monospace", 60);
        cv.editText(cv.cur(), t2);
        assertNotEquals(before, inkSum(L()));
        assertEquals("Hello world", cv.cur().text.text);
        cv.undo();
        assertEquals("Hello", cv.cur().text.text);
        cv.redo();
        String id = (String) get(act, "curId");
        call(act, "showGallery"); settle();
        call(act, "newGalleryItem", 600, 600); settle();
        call(act, "showGallery"); settle();
        call(act, "openGalleryItem", id); settle();
        assertNotNull("text still editable after reopening", cv.cur().text);
        assertEquals("monospace", cv.cur().text.font);
    }

    @Test public void animation_hold_modes_and_frame_options() {
        call(act, "toggleAnimation");
        for (int i = 0; i < 3; i++) cv.addLayer(cv.newLayer("F" + i));
        cv.layers.get(1).hold = 3;
        set(act, "animMode", 0);
        List<?> loop = (List<?>) call(act, "animSequence");
        assertEquals("holds repeat frames", 6, loop.size());
        set(act, "animMode", 1);
        List<?> pp = (List<?>) call(act, "animSequence");
        assertTrue("ping-pong goes back", pp.size() > loop.size());
        set(act, "animMode", 2);
        call(act, "togglePlay");
        ShadowLooper.idleMainLooper(3000, TimeUnit.MILLISECONDS);
        assertFalse("one shot stops", cv.playing);
        call(act, "frameOptions", cv, cv.current); settle();
    }

    @Test public void prefs_light_mode_transform_snap_adjust_pencil_liquify_camera() {
        // transform snapping: drag content near the canvas centre
        cv.size = 30;
        line(W * .3f, H * .3f, W * .4f, H * .3f);
        call(act, "setTool", CanvasView.MOVE);
        t0 = android.os.SystemClock.uptimeMillis();
        float[] c = {cv.docW / 2f, cv.docH / 2f};
        ((android.graphics.Matrix) get(cv, "view")).mapPoints(c);
        touch(cv, ev(MotionEvent.ACTION_DOWN, new float[][]{{W * .35f, H * .3f}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        touch(cv, ev(MotionEvent.ACTION_MOVE, new float[][]{{c[0] - 3, c[1] + 2}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        assertFalse("snap line shown", Float.isNaN((Float) get(cv, "snapX")) && Float.isNaN((Float) get(cv, "snapY")));
        settle(); shot("78_snapping");
        touch(cv, ev(MotionEvent.ACTION_UP, new float[][]{{c[0] - 3, c[1] + 2}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        call(act, "setTool", CanvasView.DRAW);
        // adjustment painted with the brush
        Object[] adjs = (Object[]) call(act, "adjustments");
        Object inv = null;
        for (Object a : adjs) if (get(a, "name").equals("Invert")) inv = a;
        cv.setBackground(255, true);
        cv.fillLayer();
        call(act, "startAdjust", inv);
        settle();
        Object fn = ((java.util.function.Function<float[], Object>) get(inv, "fn")).apply((float[]) get(act, "adjVals"));
        call(cv, "adjustPencil", fn);
        int[] p1 = toDoc(W * .5f, H * .5f), p2 = toDoc(W * .5f, H * .8f);
        int g1 = L().getPixel(p1[0], p1[1]) & 255;
        line(W * .4f, H * .5f, W * .6f, H * .5f);
        assertNotEquals("painted area adjusted", g1, L().getPixel(p1[0], p1[1]) & 255);
        assertEquals("rest unchanged", g1, L().getPixel(p2[0], p2[1]) & 255);
        call(act, "finishAdjust", true);
        // liquify extras
        call(act, "startLiquify");
        cv.liqMomentum = 0.5f;
        for (int m = 6; m <= 7; m++) { cv.liqMode = m; line(W * .4f, H * .5f, W * .6f, H * .55f); }
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
        call(act, "setTool", CanvasView.DRAW);
        // camera
        call(act, "takePhoto");
        android.content.Intent next = Shadows.shadowOf(act).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(android.provider.MediaStore.ACTION_IMAGE_CAPTURE, next.getAction());
        // prefs: global curve and light mode
        cv.globalCurve = 2f;
        android.content.SharedPreferences prefs = act.getSharedPreferences("inkwell", 0);
        prefs.edit().putBoolean("light", true).putInt("uiSize", 2).commit();
        ctl.pause().stop(); settle();
        launch(); closeGallery();
        call(act, "togglePanel"); settle();
        shot("79_light_mode_large");
        prefs.edit().putBoolean("light", false).putInt("uiSize", 1).commit();
        try { java.lang.reflect.Field f = Class.forName("ai.inkwell.Ui").getDeclaredField("light"); f.setAccessible(true); f.set(null, false); } catch (Exception e) { }
    }
}
