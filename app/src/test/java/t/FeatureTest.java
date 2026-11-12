package t;

import ai.inkwell.*;
import android.graphics.Bitmap;
import android.view.MotionEvent;
import android.view.View;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w841dp-h701dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FeatureTest extends Base {
    float W, H;

    @Before public void up() { launch(); closeGallery(); W = cv.getWidth(); H = cv.getHeight(); }

    Bitmap L() { return cv.cur().bmp; }

    @Test public void strokeUndoRedo() {
        long before = inkSum(L());
        line(W * .3f, H * .3f, W * .7f, H * .6f);
        long after = inkSum(L());
        assertTrue("stroke paints", after > before);
        cv.undo();
        assertEquals("undo clears", before, inkSum(L()));
        cv.redo();
        assertEquals("redo restores", after, inkSum(L()));
    }

    @Test public void every_brush_draws_and_erases() {
        for (int i = 0; i < CanvasView.BRUSHES.size(); i++) {
            cv.brush = CanvasView.BRUSHES.get(i);
            long b0 = inkSum(L());
            float y = H * (0.08f + 0.84f * i / CanvasView.BRUSHES.size()); // rows fit any number of brushes
            stroke(new float[][]{{W * .2f, y}, {W * .4f, y + 5}, {W * .6f, y}}, MotionEvent.TOOL_TYPE_STYLUS, 0.7f);
            assertTrue("brush " + cv.brush.name + " paints", inkSum(L()) > b0);
        }
        cv.setTool(CanvasView.ERASE);
        long b1 = inkSum(L());
        stroke(new float[][]{{W * .1f, H * .1f}, {W * .5f, H * .5f}, {W * .9f, H * .9f}}, MotionEvent.TOOL_TYPE_STYLUS, 1f);
        assertTrue("eraser removes", inkSum(L()) < b1);
    }

    @Test public void smudge_moves_paint() {
        line(W * .5f, H * .2f, W * .5f, H * .8f);
        cv.setTool(CanvasView.SMUDGE);
        cv.size = 40;
        Bitmap before = L().copy(Bitmap.Config.ARGB_8888, false);
        line(W * .5f, H * .5f, W * .65f, H * .5f);
        assertTrue("smudge changed pixels", !before.sameAs(L()));
        cv.undo();
        assertTrue("smudge undone", before.sameAs(L()));
    }

    @Test public void layers_ops() {
        line(W * .3f, H * .3f, W * .7f, H * .3f);
        assertTrue(cv.addLayer(cv.newLayer("L2")));
        assertEquals(2, cv.layers.size());
        line(W * .3f, H * .6f, W * .7f, H * .6f);
        assertTrue(cv.duplicate());
        assertEquals(3, cv.layers.size());
        cv.moveLayer(-1);
        cv.setBlend(cv.current, 1);
        cv.toggleClip(cv.current);
        cv.toggleAlphaLock(cv.current);
        cv.rename(cv.current, "renamed");
        assertEquals("renamed", cv.cur().name);
        cv.fillLayer();
        cv.invertLayer();
        cv.mergeDown();
        assertEquals(2, cv.layers.size());
        cv.deleteLayer();
        assertEquals(1, cv.layers.size());
        for (int i = 0; i < 12; i++) cv.undo();
        assertEquals(1, cv.layers.size());
        Bitmap f = cv.flatten();
        assertNotNull(f);
    }

    @Test public void selection_limits_painting_and_undoes() {
        cv.setTool(CanvasView.SELECT);
        cv.selKind = 2;
        stroke(new float[][]{{W * .2f, H * .2f}, {W * .35f, H * .35f}, {W * .5f, H * .5f}}, MotionEvent.TOOL_TYPE_FINGER, 1);
        assertNotNull("rect selection made", cv.sel);
        cv.setTool(CanvasView.DRAW);
        cv.size = 30;
        line(W * .1f, H * .35f, W * .9f, H * .35f);
        assertTrue("inside painted", alphaAt(L(), W * .35f, H * .35f) > 200);
        assertEquals("outside untouched", 0, alphaAt(L(), W * .8f, H * .35f));
        cv.undo(); // stroke
        cv.undo(); // selection
        assertNull("selection undone", cv.sel);
        cv.redo();
        assertNotNull("selection redone", cv.sel);
        cv.invertSelection();
        cv.featherSelection(5);
        cv.clearSelection();
        assertNull(cv.sel);
        cv.undo();
        assertNotNull("clear undone", cv.sel);
        // automatic + freehand + ellipse
        cv.clearSelection();
        cv.setTool(CanvasView.SELECT);
        cv.selKind = 0;
        stroke(new float[][]{{W * .5f, H * .5f}}, MotionEvent.TOOL_TYPE_FINGER, 1);
        assertNotNull("automatic selects", cv.sel);
        cv.selKind = 1;
        stroke(new float[][]{{W * .2f, H * .2f}, {W * .4f, H * .2f}, {W * .3f, H * .4f}}, MotionEvent.TOOL_TYPE_FINGER, 1);
        cv.selKind = 3; cv.selSubtract = true;
        stroke(new float[][]{{W * .2f, H * .2f}, {W * .3f, H * .3f}}, MotionEvent.TOOL_TYPE_FINGER, 1);
        assertTrue(cv.copyPaste(false));
        assertTrue(cv.copyPaste(true));
    }

    @Test public void transform_moves_and_modes() {
        cv.size = 20;
        line(W * .4f, H * .4f, W * .6f, H * .4f);
        int[] p = toDoc(W * .5f, H * .4f);
        assertTrue(L().getPixel(p[0], p[1]) >>> 24 > 200);
        cv.setTool(CanvasView.MOVE);
        line(W * .5f, H * .5f, W * .5f, H * .7f); // drag down by 0.2H
        cv.setTool(CanvasView.DRAW);           // commits
        assertTrue("moved away", (L().getPixel(p[0], p[1]) >>> 24) < 50);
        int[] q = toDoc(W * .5f, H * .6f);
        assertTrue("moved to new place", (L().getPixel(q[0], q[1]) >>> 24) > 200);
        cv.undo();
        assertTrue("move undone", (L().getPixel(p[0], p[1]) >>> 24) > 200);
        for (int mode = 1; mode <= 3; mode++) {
            cv.setTool(CanvasView.MOVE);
            cv.setXform(mode);
            float[] hp = (float[]) call(cv, "handlePoints");
            float[] c0 = {hp[0], hp[1]};
            stroke(new float[][]{c0, {c0[0] - 30, c0[1] - 30}, {c0[0] - 60, c0[1] - 40}}, MotionEvent.TOOL_TYPE_FINGER, 1);
            cv.flip(true);
            cv.rotate90();
            cv.setTool(CanvasView.DRAW);
            assertTrue("mode " + mode + " keeps paint", inkSum(L()) > 0);
        }
    }

    @Test public void adjustments_all() {
        line(W * .3f, H * .3f, W * .7f, H * .6f);
        Object[] adjs = (Object[]) call(act, "adjustments");
        for (Object a : adjs) {
            call(act, "startAdjust", a);
            float[] vals = (float[]) get(act, "adjVals");
            float[] max = (float[]) get(a, "max");
            for (int i = 0; i < vals.length; i++) vals[i] = max[i] * 0.5f + 0.01f;
            settle();
            call(act, "finishAdjust", true);
            assertFalse(cv.adjusting());
        }
        // cancel restores
        Bitmap b = L().copy(Bitmap.Config.ARGB_8888, false);
        call(act, "startAdjust", adjs[0]);
        ((float[]) get(act, "adjVals"))[0] = 20;
        cv.previewAdjust(((java.util.function.Function<float[], Object>) get(adjs[0], "fn")).apply((float[]) get(act, "adjVals")) instanceof Object ? null : null);
        call(act, "finishAdjust", false);
        assertTrue("cancel restores", b.sameAs(L()));
    }

    @Test public void liquify_clone_fill() {
        line(W * .3f, H * .5f, W * .7f, H * .5f);
        call(act, "startLiquify");
        assertTrue(cv.liquifying());
        for (int m = 0; m < 6; m++) { cv.liqMode = m; line(W * .5f, H * .45f, W * .5f, H * .55f); }
        call(act, "setTool", CanvasView.DRAW);
        assertFalse(cv.liquifying());
        call(act, "startClone");
        stroke(new float[][]{{W * .5f, H * .5f}}, MotionEvent.TOOL_TYPE_FINGER, 1); // set source
        long b = inkSum(L());
        line(W * .5f, H * .8f, W * .6f, H * .8f);
        assertTrue("clone paints", inkSum(L()) > b);
        call(act, "setTool", CanvasView.DRAW);
        cv.colorDrop(W * .3f, H * .2f, 30);
        assertTrue("fill covers area", alphaAt(L(), W * .3f, H * .2f) == 255);
    }

    @Test public void masks_and_groups() {
        cv.size = 60;
        line(W * .2f, H * .5f, W * .8f, H * .5f);
        cv.addMask();
        assertNotNull(cv.cur().mask);
        assertTrue(cv.cur().editMask);
        long layerInk = inkSum(cv.cur().bmp);
        cv.grey = 0;
        line(W * .2f, H * .5f, W * .5f, H * .5f); // hide left half
        assertEquals("painting mask keeps layer pixels", layerInk, inkSum(cv.cur().bmp));
        Bitmap flat = cv.flatten();
        int[] l = toDoc(W * .3f, H * .5f), r = toDoc(W * .7f, H * .5f);
        assertTrue("masked part hidden", (flat.getPixel(l[0], l[1]) & 255) > 200);
        assertTrue("unmasked part shows", (flat.getPixel(r[0], r[1]) & 255) < 60);
        cv.applyMask();
        assertNull(cv.cur().mask);
        assertTrue(cv.addLayer(cv.newLayer("B")));
        cv.groupWithBelow();
        assertTrue(cv.layers.get(0).group != 0 && cv.layers.get(0).group == cv.layers.get(1).group);
        call(act, "togglePanel");
        settle();
        shot("10_groups_panel");
        cv.flattenGroup(cv.layers.get(0).group);
        assertEquals(1, cv.layers.size());
    }

    @Test public void psd_roundtrip() throws Exception {
        line(W * .3f, H * .3f, W * .7f, H * .3f);
        cv.cur().blend = 2; cv.cur().opacity = 200;
        cv.addMask();
        line(W * .3f, H * .3f, W * .4f, H * .3f);
        cv.editMask(false);
        assertTrue(cv.addLayer(cv.newLayer("Top")));
        line(W * .3f, H * .6f, W * .7f, H * .6f);
        cv.cur().clip = true; cv.cur().alphaLock = true;
        cv.groupWithBelow();
        cv.groups.get(cv.cur().group).name = "Folder";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        Bitmap flat = cv.flatten();
        call(Class.forName("ai.inkwell.Psd"), "write", o, cv.docW, cv.docH, cv.layers, cv.groups, flat);
        Object doc = callStatic("ai.inkwell.Psd", "read", new ByteArrayInputStream(o.toByteArray()), 100_000_000L);
        List<CanvasView.Layer> ls = (List<CanvasView.Layer>) get(doc, "layers");
        Map<Integer, CanvasView.Group> gs = (Map<Integer, CanvasView.Group>) get(doc, "groups");
        assertEquals(2, ls.size());
        assertTrue(ls.get(0).bmp.sameAs(cv.layers.get(0).bmp));
        assertTrue(ls.get(1).bmp.sameAs(cv.layers.get(1).bmp));
        assertEquals(2, ls.get(0).blend);
        assertEquals(200, ls.get(0).opacity);
        assertNotNull("mask kept", ls.get(0).mask);
        assertTrue("mask pixels kept", ls.get(0).mask.bmp.sameAs(cv.layers.get(0).mask.bmp));
        assertTrue(ls.get(1).clip);
        assertTrue(ls.get(1).alphaLock);
        assertEquals("group kept", 1, gs.size());
        assertEquals("Folder", gs.values().iterator().next().name);
        assertTrue(ls.get(0).group != 0 && ls.get(0).group == ls.get(1).group);
    }

    static Object callStatic(String cls, String m, Object... a) throws Exception {
        for (java.lang.reflect.Method x : Class.forName(cls).getDeclaredMethods())
            if (x.getName().equals(m) && x.getParameterCount() == a.length) { x.setAccessible(true); return x.invoke(null, a); }
        throw new RuntimeException("no " + m);
    }

    static Object call(Class<?> c, String m, Object... a) throws Exception {
        for (java.lang.reflect.Method x : c.getDeclaredMethods())
            if (x.getName().equals(m) && x.getParameterCount() == a.length) { x.setAccessible(true); return x.invoke(null, a); }
        throw new RuntimeException("no " + m);
    }

    @Test public void guides_assist_symmetry_text() {
        cv.guide = 2; // vertical symmetry
        line(W * .3f, H * .3f, W * .35f, H * .3f);
        int[] p = toDoc(W * .32f, H * .3f);
        assertTrue("mirror painted", L().getPixel(cv.docW - 1 - p[0], p[1]) >>> 24 > 100);
        for (int g = 1; g <= 9; g++) {
            cv.guide = g; cv.assist = true;
            if (g >= 7) cv.resetVps();
            line(W * .2f, H * .2f, W * .6f, H * .25f);
            cv.invalidate();
            shot("11_guide_" + g);
        }
        cv.guide = 0;
        assertTrue(cv.addTextLayer("Hello", 80, true));
        assertTrue(inkSum(cv.cur().bmp) > 0);
    }

    @Test public void animation_gif_and_frames() throws Exception {
        line(W * .3f, H * .3f, W * .5f, H * .3f);
        call(act, "toggleAnimation");
        assertTrue(cv.anim);
        for (int i = 0; i < 3; i++) { assertTrue(cv.addLayer(cv.newLayer("F" + i))); line(W * (.3f + i * .1f), H * .5f, W * (.4f + i * .1f), H * .5f); }
        settle();
        shot("12_animation");
        call(act, "togglePlay");
        ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        call(act, "togglePlay");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int n = cv.layers.size();
        Object frames = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{Class.forName("ai.inkwell.Video$Frames")}, (pr, m, a) ->
                        m.getName().equals("count") ? n : cv.renderFrame((Integer) a[0], 200, 240));
        call(Class.forName("ai.inkwell.Video"), "gif", o, 200, 240, 12, frames);
        byte[] g = o.toByteArray();
        assertEquals("GIF89a", new String(g, 0, 6, "US-ASCII"));
        assertEquals("ends with trailer", 0x3B, g[g.length - 1]);
        int gce = 0; // graphic control extension (21 F9 04) precedes each frame
        for (int i = 0; i + 2 < g.length; i++) if ((g[i] & 255) == 0x21 && (g[i + 1] & 255) == 0xF9 && g[i + 2] == 4) gce++;
        assertTrue("one frame per layer", gce >= n);
    }

    @Test public void gallery_timelapse_reference_selection_save() {
        line(W * .3f, H * .3f, W * .7f, H * .3f);
        call(act, "captureTimelapse");
        settle();
        File[] tl = (File[]) call(act, "tlFrames");
        assertTrue("time-lapse frame written", tl.length >= 1);
        call(act, "toggleReference");
        settle();
        shot("13_reference");
        cv.setTool(CanvasView.SELECT);
        cv.selKind = 2;
        stroke(new float[][]{{W * .2f, H * .2f}, {W * .5f, H * .5f}}, MotionEvent.TOOL_TYPE_FINGER, 1);
        call(act, "saveSelection");
        cv.clearSelection();
        assertEquals(1, ((List<?>) get(act, "savedSel")).size());
        byte[] s = CanvasViewAccess.inflate(((List<byte[]>) get(act, "savedSel")).get(0), cv.docW * cv.docH);
        assertNotNull(s);
        String first = (String) get(act, "curId");
        call(act, "showGallery");
        settle();
        call(act, "newGalleryItem", 1000, 800);
        settle();
        assertEquals(1000, cv.docW);
        assertNotEquals(first, get(act, "curId"));
        call(act, "showGallery");
        settle();
        shot("14_gallery_two");
        call(act, "openGalleryItem", first);
        settle();
        assertEquals(first, get(act, "curId"));
        assertTrue("first drawing restored", inkSum(L()) > 0);
    }

    @Test public void restart_restores_drawing() {
        line(W * .3f, H * .3f, W * .7f, H * .3f);
        long ink = inkSum(L());
        ctl.pause().stop();
        settle();
        launch();
        assertEquals("autosave restores", ink, inkSum(cv.cur().bmp));
    }

    @Test public void gestures_two_finger_tap_undo_and_quick_pinch_fit() {
        line(W * .3f, H * .3f, W * .7f, H * .3f);
        long ink = inkSum(L());
        t0 = android.os.SystemClock.uptimeMillis();
        touch(cv, ev(MotionEvent.ACTION_DOWN, new float[][]{{W * .4f, H * .5f}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        touch(cv, ev(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new float[][]{{W * .4f, H * .5f}, {W * .6f, H * .5f}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        touch(cv, ev(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new float[][]{{W * .4f, H * .5f}, {W * .6f, H * .5f}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        touch(cv, ev(MotionEvent.ACTION_UP, new float[][]{{W * .4f, H * .5f}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        assertTrue("two-finger tap undid", inkSum(L()) < ink);
    }

    @Test public void each_tool_remembers_its_settings() {
        CanvasView.Brush pencil = null, brushPen = null;
        for (CanvasView.Brush b : CanvasView.BRUSHES) { if (b.name.equals("HB pencil")) pencil = b; if (b.name.equals("Brush pen")) brushPen = b; }
        call(act, "setTool", CanvasView.DRAW);
        cv.brush = pencil; cv.size = 5; cv.opacity = 0.6f;
        call(act, "setTool", CanvasView.ERASE);
        cv.brush = brushPen; cv.size = 140; cv.opacity = 1f;
        call(act, "setTool", CanvasView.SMUDGE);
        cv.size = 50;
        call(act, "setTool", CanvasView.DRAW);
        assertEquals("ink brush kept", pencil, cv.brush);
        assertEquals("ink size kept", 5f, cv.size, 0.01f);
        assertEquals(0.6f, cv.opacity, 0.001f);
        Ui_value("ink slider shows small size", 5);
        call(act, "setTool", CanvasView.ERASE);
        assertEquals("eraser brush kept", brushPen, cv.brush);
        assertEquals("eraser size kept", 140f, cv.size, 0.01f);
        Ui_value("eraser slider shows big size", 140);
        // through an adjustment and back
        Object[] adjs = (Object[]) call(act, "adjustments");
        call(act, "startAdjust", adjs[0]);
        call(act, "finishAdjust", true);
        assertEquals("back on brush with ink settings", 5f, cv.size, 0.01f);
        call(act, "setTool", CanvasView.SMUDGE);
        assertEquals(50f, cv.size, 0.01f);
        // survives a restart
        call(act, "setTool", CanvasView.DRAW);
        ctl.pause().stop();
        settle();
        launch();
        assertEquals("ink size after restart", 5f, cv.size, 0.01f);
        assertEquals(pencil.name, cv.brush.name);
        call(act, "setTool", CanvasView.ERASE);
        assertEquals("eraser size after restart", 140f, cv.size, 0.01f);
    }

    void Ui_value(String msg, float size) {
        Object slider = get(act, "sizeS");
        float v = (Float) get(slider, "value");
        assertEquals(msg, (float) Math.sqrt((size - 1) / 149f), v, 0.01f);
    }

    CanvasView.Brush named(String n) { for (CanvasView.Brush b : CanvasView.BRUSHES) if (b.name.equals(n)) return b; return null; }

    long offLine(Bitmap b, float yScreen) { // ink farther than 12 px from the intended line
        int[] p = toDoc(0, yScreen);
        long s = 0;
        for (int y = 0; y < b.getHeight(); y++) if (Math.abs(y - p[1]) > 12) for (int x = 0; x < b.getWidth(); x++) s += b.getPixel(x, y) >>> 24;
        return s;
    }

    @Test public void streamline_smooths_and_taper_thins() {
        CanvasView.Brush fl = named("Fine liner");
        cv.brush = fl; cv.size = 6;
        float[] before = (float[]) call(fl, "values");
        float[][] zig = new float[41][];
        for (int i = 0; i <= 40; i++) zig[i] = new float[]{W * (.2f + .6f * i / 40), H * .5f + ((i % 2 == 0) ? -14 : 14)};
        call(fl, "put", "streamline", 0f);
        stroke(zig, MotionEvent.TOOL_TYPE_STYLUS, 1);
        long rough = offLine(L(), H * .5f);
        cv.clearLayer();
        call(fl, "put", "streamline", 1f);
        stroke(zig, MotionEvent.TOOL_TYPE_STYLUS, 1);
        long smooth = offLine(L(), H * .5f);
        assertTrue("StreamLine smooths the zigzag: " + rough + " -> " + smooth, smooth < rough * 0.6);
        // taper
        cv.clearLayer();
        call(fl, "put", "streamline", 0f);
        cv.size = 30;
        float[][] st = new float[21][];
        for (int i = 0; i <= 20; i++) st[i] = new float[]{W * (.2f + .6f * i / 20), H * .3f};
        stroke(st, MotionEvent.TOOL_TYPE_STYLUS, 1);
        long plain = inkSum(L());
        cv.clearLayer();
        call(fl, "put", "taperStart", 1f); call(fl, "put", "taperEnd", 1f);
        stroke(st, MotionEvent.TOOL_TYPE_STYLUS, 1);
        long tapered = inkSum(L());
        assertTrue("taper makes the ends thinner: " + plain + " -> " + tapered, tapered < plain * 0.9);
        shot("52_tapered");
        call(fl, "setValues", (Object) before);
    }

    @Test public void shape_jitter_scatter_grain_scale_curve_draw() {
        CanvasView.Brush b = named("Hard round");
        cv.brush = b;
        float[] before = (float[]) call(b, "values");
        for (String k : new String[]{"roundness", "angle", "follow", "jitterSize", "jitterOpacity", "scatter", "grain", "grainScale", "curve", "sizeScale", "stabilize"}) {
            call(b, "put", k, k.equals("angle") ? 45f : k.equals("roundness") ? 0.3f : k.equals("grainScale") ? 3f : k.equals("curve") ? 2f : 0.6f);
        }
        long b0 = inkSum(L());
        stroke(new float[][]{{W * .2f, H * .5f}, {W * .4f, H * .55f}, {W * .6f, H * .45f}, {W * .8f, H * .5f}}, MotionEvent.TOOL_TYPE_STYLUS, 0.7f);
        assertTrue(inkSum(L()) > b0);
        shot("53_shaped_brush");
        call(b, "setValues", (Object) before);
    }

    @Test public void brush_studio_edits_persist_and_reset() {
        CanvasView.Brush ink = named("Ink pen");
        cv.brush = ink;
        call(act, "brushStudio", ink);
        settle();
        shot("54_brush_studio");
        set(act, "studioTab", 1);
        call(act, "brushStudio", ink);
        settle();
        shot("55_brush_studio_streamline");
        call(ink, "put", "streamline", 0.9f);
        call(ink, "put", "taperEnd", 0.7f);
        call(act, "saveBrush", ink);
        call(act, "closeStudio");
        ctl.pause().stop();
        settle();
        // reload built-in defaults then load saved edits, as on a fresh start
        call(named("Ink pen"), "setValues", (Object) CanvasView.DEFAULTS.get("Ink pen"));
        launch();
        CanvasView.Brush again = named("Ink pen");
        assertEquals("edit kept after restart", 0.9f, (Float) call(again, "get", "streamline"), 0.001f);
        assertEquals(0.7f, (Float) call(again, "get", "taperEnd"), 0.001f);
        call(again, "setValues", (Object) CanvasView.DEFAULTS.get("Ink pen"));
        assertEquals("reset", CanvasView.DEFAULTS.get("Ink pen")[1], (Float) call(again, "get", "streamline"), 0.001f);
        java.io.File f = (java.io.File) get(again, "file");
        if (f != null) f.delete();
    }

    @Test public void selection_tap_does_nothing_done_keeps_tap_icon_deselects() {
        line(W * .3f, H * .3f, W * .7f, H * .6f);
        View selBtn = (View) get(act, "selectBtn");
        selBtn.performClick();                       // enter Selection
        assertEquals(CanvasView.SELECT, cv.tool);
        cv.selKind = 1;
        stroke(new float[][]{{W * .5f, H * .5f}}, MotionEvent.TOOL_TYPE_FINGER, 1);  // a tap
        assertNull("a tap makes no selection (no blue page)", cv.sel);
        cv.selKind = 2;
        stroke(new float[][]{{W * .3f, H * .3f}, {W * .5f, H * .5f}}, MotionEvent.TOOL_TYPE_FINGER, 1);
        assertNotNull(cv.sel);
        shot("60_selecting");
        selBtn.performClick();                       // done: back to drawing, selection kept
        assertEquals(CanvasView.DRAW, cv.tool);
        assertNotNull(cv.sel);
        settle();
        shot("61_selection_while_drawing");
        selBtn.performClick();                       // tap again: deselect
        assertNull("icon deselects", cv.sel);
        assertEquals(CanvasView.DRAW, cv.tool);
    }

    @Test public void tapping_canvas_closes_panels_without_drawing() {
        call(act, "togglePanel");
        settle();
        View panel = (View) get(act, "panel");
        assertEquals(View.VISIBLE, panel.getVisibility());
        long ink = inkSum(L());
        line(W * .3f, H * .5f, W * .6f, H * .5f);
        assertEquals("panel closed", View.GONE, panel.getVisibility());
        assertEquals("that touch did not draw", ink, inkSum(L()));
        call(act, "pickGrey");
        settle();
        line(W * .3f, H * .5f, W * .6f, H * .5f);
        Object pop = get(Class.class.cast(null) == null ? latest() : null, "win");
        assertFalse("colour panel closed", ((android.widget.PopupWindow) pop).isShowing());
        assertEquals(ink, inkSum(L()));
        line(W * .3f, H * .5f, W * .6f, H * .5f);
        assertTrue("next touch draws", inkSum(L()) > ink);
    }

    static Object latest() {
        try { java.lang.reflect.Field f = Class.forName("ai.inkwell.Ui$Pop").getDeclaredField("latest"); f.setAccessible(true); return f.get(null); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test public void background_colour_like_procreate() throws Exception {
        call(act, "newGalleryItem", 1000, 1200);
        settle();
        assertEquals("new drawing has one layer", 1, cv.layers.size());
        assertEquals("it is blank", 0, inkSum(L()));
        line(W * .3f, H * .5f, W * .7f, H * .5f);
        assertTrue("drawing goes on the layer", inkSum(L()) > 0);
        Bitmap f = cv.flatten();
        assertEquals("background untouched", 0xFFFFFFFF, f.getPixel(5, 5));
        cv.setBackground(80, true);
        f = cv.flatten();
        assertEquals("background colour changed", 80, f.getPixel(5, 5) & 255);
        assertEquals("layer pixels unchanged by background", 255, f.getPixel(5, 5) >>> 24);
        cv.undo();
        assertEquals("background change undoes", 255, cv.flatten().getPixel(5, 5) & 255);
        cv.redo();
        call(act, "togglePanel");
        settle();
        shot("62_background_row");
        cv.setBackground(80, false);
        settle();
        shot("63_background_hidden");
        assertEquals("hidden background is transparent", 0, cv.flatten().getPixel(5, 5) >>> 24);
        java.io.File png = new java.io.File(System.getProperty("java.io.tmpdir"), "bg.png"), jpg = new java.io.File(System.getProperty("java.io.tmpdir"), "bg.jpg");
        call(act, "export", android.net.Uri.fromFile(png), 0);
        call(act, "export", android.net.Uri.fromFile(jpg), 1);
        settle();
        Bitmap pb = android.graphics.BitmapFactory.decodeFile(png.getPath()), jb = android.graphics.BitmapFactory.decodeFile(jpg.getPath());
        assertEquals("PNG keeps transparency", 0, pb.getPixel(5, 5) >>> 24);
        assertTrue("JPEG gets white", (jb.getPixel(5, 5) & 255) > 245);
        // kept per drawing
        cv.setBackground(40, true);
        String id = (String) get(act, "curId");
        call(act, "showGallery"); settle();
        call(act, "newGalleryItem", 800, 800); settle();
        assertEquals("other drawing starts white", 255, cv.bgGrey);
        call(act, "showGallery"); settle();
        call(act, "openGalleryItem", id); settle();
        assertEquals("background restored", 40, cv.bgGrey);
        assertTrue(cv.bgVisible);
    }

    @Test public void undo_steps_back_through_a_move_before_done() {
        cv.size = 20;
        line(W * .4f, H * .3f, W * .6f, H * .3f);
        int[] a = toDoc(W * .5f, H * .3f);
        // select it
        cv.setTool(CanvasView.SELECT); cv.selKind = 2;
        stroke(new float[][]{{W * .35f, H * .25f}, {W * .65f, H * .35f}}, MotionEvent.TOOL_TYPE_FINGER, 1);
        assertNotNull(cv.sel);
        call(act, "setTool", CanvasView.MOVE);
        line(W * .5f, H * .5f, W * .5f, H * .6f);   // move 1: down 0.1H
        line(W * .5f, H * .5f, W * .5f, H * .6f);   // move 2: down another 0.1H
        cv.flip(true);                              // step 3
        assertEquals("still transforming", CanvasView.MOVE, cv.tool);
        cv.undo();                                  // undo flip
        cv.undo();                                  // undo move 2
        assertEquals("still transforming after undo", CanvasView.MOVE, cv.tool);
        call(act, "setTool", CanvasView.DRAW);      // Done
        int[] b = toDoc(W * .5f, H * .4f);
        assertTrue("kept only the first move", (L().getPixel(b[0], b[1]) >>> 24) > 200);
        assertTrue("not at the original place", (L().getPixel(a[0], a[1]) >>> 24) < 50);
        // undo everything step by step inside a new transform
        call(act, "setTool", CanvasView.MOVE);
        line(W * .5f, H * .5f, W * .5f, H * .6f);
        cv.undo();                                  // undo that move
        cv.undo();                                  // nothing left: content goes back, transform ends cleanly
        call(act, "setTool", CanvasView.DRAW);
        assertTrue("back after the first move", (L().getPixel(b[0], b[1]) >>> 24) > 200);
        cv.redo();
        assertTrue("redo has nothing from the cancelled move", (L().getPixel(b[0], b[1]) >>> 24) > 200);
        // redo inside a transform
        call(act, "setTool", CanvasView.MOVE);
        line(W * .5f, H * .5f, W * .5f, H * .6f);
        cv.undo();
        cv.redo();
        call(act, "setTool", CanvasView.DRAW);
        int[] c = toDoc(W * .5f, H * .5f);
        assertTrue("redo re-applied the move", (L().getPixel(c[0], c[1]) >>> 24) > 200);
    }

    void tap(float x, float y) { stroke(new float[][]{{x, y}}, MotionEvent.TOOL_TYPE_FINGER, 1); }

    float[] vpScreen(int k) {
        float[] p = {cv.vp[k * 2], cv.vp[k * 2 + 1]};
        ((android.graphics.Matrix) get(cv, "view")).mapPoints(p);
        return p;
    }

    @Test public void perspective_points_add_move_delete_with_undo() {
        cv.guide = 8; cv.resetVps();
        call(act, "guideEditor");
        settle();
        assertTrue("editing points", cv.editGuide);
        assertEquals("2-point", 8, cv.guide);
        tap(W * .5f, H * .25f);                                  // add a third point
        assertEquals("3-point after tap", 9, cv.guide);
        cv.undo();
        assertEquals("undo removes the added point", 8, cv.guide);
        cv.redo();
        assertEquals(9, cv.guide);
        float[] p0 = vpScreen(0);
        float oldX = cv.vp[0];
        line(p0[0], p0[1], p0[0] + 120, p0[1] + 40);            // drag point 0
        assertTrue("point moved", Math.abs(cv.vp[0] - oldX) > 20);
        cv.undo();
        assertEquals("undo puts the point back", oldX, cv.vp[0], 0.5f);
        float[] p1 = vpScreen(1);
        tap(p1[0], p1[1]);                                       // tap a point: delete
        assertEquals("deleted a point", 8, cv.guide);
        cv.undo();
        assertEquals("undo restores it", 9, cv.guide);
        cv.vpRays = 120;
        cv.guideOpacity = 0.6f; cv.guideThick = 3f;
        cv.invalidate();
        settle();
        shot("64_perspective_editor");
        // kept with the drawing
        String id = (String) get(act, "curId");
        call(act, "showGallery"); settle();
        call(act, "newGalleryItem", 800, 800); settle();
        assertEquals("new drawing has no guide", 0, cv.guide);
        call(act, "showGallery"); settle();
        call(act, "openGalleryItem", id); settle();
        assertEquals("guide restored", 9, cv.guide);
        assertEquals(120, cv.vpRays);
        assertEquals(0.6f, cv.guideOpacity, 0.001f);
    }

    @Test public void horizon_level_offcanvas_points_and_handle() {
        cv.guide = 8; cv.resetVps();
        call(act, "guideEditor");
        settle();
        // drag point 0 up: horizon stays straight
        float[] p0 = vpScreen(0);
        line(p0[0], p0[1], p0[0] + 20, p0[1] - 150);
        assertEquals("horizon level", cv.vp[1], cv.vp[3], 0.01f);
        // drag a point far outside the canvas, then show all and grab it again
        float[] p1 = vpScreen(1);
        line(p1[0], p1[1], W - 2, p1[1]);                  // to the right edge of the view (outside the paper)
        cv.fitGuide();
        ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS);
        p1 = vpScreen(1);
        line(p1[0], p1[1], W - 2, p1[1]);                  // and further out again
        assertTrue("point is well outside the canvas: " + cv.vp[2], cv.vp[2] > cv.docW * 1.3f);
        cv.fitGuide();
        ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS);
        float[] again = vpScreen(1);
        assertTrue("visible after Show all points", again[0] > 0 && again[0] < W && again[1] > 0 && again[1] < H);
        settle();
        shot("65_points_outside_canvas");
        // horizon handle moves both points
        float[] m = (float[]) call(cv, "horizonHandle");
        ((android.graphics.Matrix) get(cv, "view")).mapPoints(m);
        float y0 = cv.vp[1];
        line(m[0], m[1], m[0], m[1] + 60);
        assertTrue("horizon moved", cv.vp[1] > y0 + 10);
        assertEquals("still level", cv.vp[1], cv.vp[3], 0.01f);
        cv.undo();
        assertEquals("undo", y0, cv.vp[1], 0.5f);
        // tilt allowed when switched on
        cv.horizonLevel = false;
        p0 = vpScreen(0);
        line(p0[0], p0[1], p0[0], p0[1] - 80);
        assertTrue("tilted", Math.abs(cv.vp[1] - cv.vp[3]) > 10);
    }
}
