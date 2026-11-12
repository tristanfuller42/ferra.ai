package t;

import app.inkwell.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.*;
import static org.junit.Assert.*;

/** Working in colour: painting, blending, smudge, fills, eyedropper, adjustments, files, the Colour panel and palettes. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w841dp-h701dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ColourTest extends Base {
    float W, H;
    @Before public void up() { launch(); closeGallery(); W = cv.getWidth(); H = cv.getHeight(); }
    Bitmap L() { return cv.cur().bmp; }
    void pen(float[][] pts) { stroke(pts, MotionEvent.TOOL_TYPE_STYLUS, 1); }
    int at(float fx, float fy) { int[] d = toDoc(W * fx, H * fy); return L().getPixel(d[0], d[1]); }
    CanvasView.Brush brush(String n) { for (CanvasView.Brush b : CanvasView.BRUSHES) if (b.name.equals(n)) return b; return null; }

    static void near(String what, int want, int got, int tol) {
        assertTrue(what + ": want " + Integer.toHexString(want) + " got " + Integer.toHexString(got),
                Math.abs(Color.red(want) - Color.red(got)) <= tol && Math.abs(Color.green(want) - Color.green(got)) <= tol && Math.abs(Color.blue(want) - Color.blue(got)) <= tol);
    }

    @Test public void paints_and_blends_in_colour() {
        cv.brush = brush("Hard round"); cv.size = 40; cv.opacity = 1f;
        cv.color = 0xFFE53935; // red
        pen(new float[][]{{W * .3f, H * .4f}, {W * .7f, H * .4f}});
        near("red stroke", 0xE53935, at(.5f, .4f), 2);
        assertEquals(255, at(.5f, .4f) >>> 24);
        cv.color = 0xFF1E88E5; cv.opacity = 0.5f; // half blue over red: purple
        pen(new float[][]{{W * .5f, H * .3f}, {W * .5f, H * .5f}});
        int p = at(.5f, .4f);
        assertTrue("mixed: " + Integer.toHexString(p), Color.red(p) > 100 && Color.blue(p) > 100 && Color.green(p) < 120);
        // smudge pulls red into empty space and keeps it red
        cv.opacity = 1f;
        call(act, "setTool", CanvasView.SMUDGE);
        cv.brush = brush("Hard round"); cv.size = 40;
        pen(new float[][]{{W * .65f, H * .4f}, {W * .72f, H * .4f}, {W * .8f, H * .4f}});
        int sm = at(.76f, .4f);
        assertTrue("smudged red: " + Integer.toHexString(sm), (sm >>> 24) > 30 && Color.red(sm) > Color.blue(sm) + 60);
        call(act, "setTool", CanvasView.DRAW);
        settle(); shot("C1_colour_paint");
    }

    @Test public void fills_eyedropper_and_colour_boundaries() {
        cv.brush = brush("Hard round"); cv.size = 14; cv.opacity = 1f;
        cv.color = 0xFF000000;
        // a box with a black outline
        pen(new float[][]{{W * .3f, H * .3f}, {W * .7f, H * .3f}, {W * .7f, H * .7f}, {W * .3f, H * .7f}, {W * .3f, H * .29f}});
        cv.color = 0xFF43A047; // green
        cv.colorDrop(W * .5f, H * .5f, 30);
        near("filled green", 0x43A047, at(.5f, .5f), 2);
        assertEquals("not outside the box", 0, at(.5f, .2f) >>> 24);
        // eyedropper picks the colour
        cv.color = 0xFF000000;
        cv.pickNext = true;
        t0 = android.os.SystemClock.uptimeMillis();
        touch(cv, ev(MotionEvent.ACTION_DOWN, new float[][]{{W * .5f, H * .5f}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        touch(cv, ev(MotionEvent.ACTION_UP, new float[][]{{W * .5f, H * .5f}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        near("picked green", 0x43A047, cv.color, 2);
        // a fill stops at a different colour too, not just at dark lines
        cv.clearLayer();
        cv.color = 0xFFFDD835; // a yellow stripe splits the canvas
        cv.size = 30;
        pen(new float[][]{{W * .02f, H * .5f}, {W * .98f, H * .5f}});
        cv.addLayer(cv.newLayer("fill"));
        cv.toggleReference(0);
        cv.color = 0xFF8E24AA;
        cv.colorDrop(W * .5f, H * .3f, 20);
        near("purple above", 0x8E24AA, at(.5f, .3f), 2);
        assertEquals("yellow line stops it", 0, at(.5f, .7f) >>> 24);
    }

    int[] arr(int... c) { return c; }

    Object fx(String name, Object... args) {
        try {
            for (java.lang.reflect.Method m : Class.forName("app.inkwell.Fx").getDeclaredMethods())
                if (m.getName().equals(name) && m.getParameterCount() == args.length) { m.setAccessible(true); return m.invoke(null, args); }
        } catch (Exception e) { throw new RuntimeException(e); }
        throw new RuntimeException("no " + name);
    }

    @Test public void colour_adjustments() {
        int[] src = arr(0xFFFF0000, 0xFF00FF00, 0xFF808080, 0x80FF0000), out = new int[4];
        fx("hsb", src, out, 120f, 0f, 0f);
        near("hue +120: red → green", 0x00FF00, out[0], 3);
        near("green → blue", 0x0000FF, out[1], 3);
        near("grey stays grey", 0x808080, out[2], 1);
        assertEquals("alpha kept", 0x80, out[3] >>> 24);
        fx("hsb", src, out, 0f, -1f, 0f);
        assertEquals("no saturation = grey", Color.red(out[0]), Color.green(out[0]));
        fx("colorBalance", src, out, 0f, 0f, 1f);
        assertTrue("more blue", Color.blue(out[2]) > 0x80 + 30);
        fx("lut", src, out, fx("invert"));
        near("invert per channel", 0x00FFFF, out[0], 0);
        fx("gaussian", new int[]{0xFFFF0000, 0xFFFF0000, 0xFFFF0000, 0xFFFF0000}, out, 2, 2, 1f);
        near("blur keeps red", 0xFF0000, out[0], 1);
        fx("gradientMap", arr(0xFF000000, 0xFFFFFFFF), out, 0xFF0000FF, 0xFFFFFF00);
        near("dark → blue", 0x0000FF, out[0], 1);
        near("light → yellow", 0xFFFF00, out[1], 1);
        fx("thresholdLum", arr(0xFFFFFF00, 0xFF0000FF), out, 128);
        near("bright yellow → white", 0xFFFFFF, out[0], 0);
        near("dark blue → black", 0x000000, out[1], 0);
    }

    @Test public void files_keep_colour() throws Exception {
        cv.brush = brush("Hard round"); cv.size = 50; cv.color = 0xFF1E88E5;
        pen(new float[][]{{W * .3f, H * .4f}, {W * .7f, H * .4f}});
        cv.setBackground(0xFFFFF3E0, true);
        // PSD round trip keeps the colour
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        Class<?> psd = Class.forName("app.inkwell.Psd");
        java.lang.reflect.Method w = null, r = null;
        for (java.lang.reflect.Method m : psd.getDeclaredMethods()) { if (m.getName().equals("write")) w = m; if (m.getName().equals("read")) r = m; }
        w.setAccessible(true); r.setAccessible(true);
        w.invoke(null, o, cv.docW, cv.docH, cv.layers, cv.groups, cv.flatten());
        Object doc = r.invoke(null, new java.io.ByteArrayInputStream(o.toByteArray()), 50_000_000L);
        List<?> ls = (List<?>) get(doc, "layers");
        int[] d = toDoc(W * .5f, H * .4f);
        near("PSD layer colour", 0x1E88E5, ((CanvasView.Layer) ls.get(0)).bmp.getPixel(d[0], d[1]), 1);
        java.lang.reflect.Method comp = psd.getDeclaredMethod("readComposite", java.io.InputStream.class); comp.setAccessible(true);
        Bitmap c = (Bitmap) comp.invoke(null, new java.io.ByteArrayInputStream(o.toByteArray()));
        near("PSD composite colour", 0x1E88E5, c.getPixel(d[0], d[1]), 1);
        near("background colour in composite", 0xFFF3E0, c.getPixel(2, 2), 1);
        // an imported picture keeps its colours
        java.io.File f = new java.io.File(OUT, "red.png");
        Bitmap red = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888); red.eraseColor(0xFFE53935);
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) { red.compress(Bitmap.CompressFormat.PNG, 100, fo); }
        Object dd = call(act, "decode", android.net.Uri.fromFile(f));
        near("imported image is not turned grey", 0xE53935, ((CanvasView.Layer) ((List<?>) get(dd, "layers")).get(0)).bmp.getPixel(5, 5), 1);
        // GIF palette: red maps to a red entry, grey to the grey ramp
        Class<?> v = Class.forName("app.inkwell.Video");
        java.lang.reflect.Method gi = v.getDeclaredMethod("gifIndex", int.class, int.class, int.class); gi.setAccessible(true);
        int ir = (Integer) gi.invoke(null, 0xFFFF0000, 0, 0);
        assertEquals("pure red", 5 * 36, ir);
        assertTrue("grey uses the grey ramp", (Integer) gi.invoke(null, 0xFF808080, 0, 0) >= 216);
        // text colour and background survive a restart
        CanvasView.TextInfo t = new CanvasView.TextInfo("Hi", 80, false, 1, "sans-serif", 0xFF8E24AA);
        assertTrue(cv.addTextLayer(t));
        ctl.pause().stop(); settle(); launch();
        CanvasView.Layer tl = cv.layers.get(cv.layers.size() - 1);
        assertNotNull(tl.text);
        assertEquals(0xFF8E24AA, 0xFF000000 | tl.text.color);
        assertEquals(0xFFFFF3E0, cv.bgColor);
    }

    @Test public void colour_panel_tabs_palettes_and_swatches() throws Exception {
        android.content.SharedPreferences pr = act.getSharedPreferences("inkwell", 0);
        for (int tab = 0; tab < 5; tab++) {
            pr.edit().putInt("colTab", tab).commit();
            call(act, "pickGrey"); settle();
            Object panel = call(act, "colorPanel");
            View body = (View) get(panel, "body");
            assertTrue("tab " + tab + " shows something", ((android.view.ViewGroup) body).getChildCount() > 0);
            if (tab == 0) { // drag on the disc's inner area: the ink changes colour
                View disc = ((android.view.ViewGroup) body).getChildAt(0);
                body.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST));
                body.layout(0, 0, body.getMeasuredWidth(), body.getMeasuredHeight());
                int before = cv.color;
                float cx = disc.getWidth() / 2f, cy = disc.getHeight() / 2f, ring = disc.getWidth() * 0.45f;
                t0 = android.os.SystemClock.uptimeMillis();
                disc.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, cx + ring, cy, 0)); // ring: hue
                disc.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 10, MotionEvent.ACTION_UP, cx + ring, cy, 0));
                disc.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 20, MotionEvent.ACTION_DOWN, cx + disc.getWidth() * 0.2f, cy - disc.getWidth() * 0.2f, 0));
                disc.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 30, MotionEvent.ACTION_UP, cx + disc.getWidth() * 0.2f, cy - disc.getWidth() * 0.2f, 0));
                assertNotEquals("picked a colour", before, cv.color);
                assertTrue("saturated", Color.red(cv.color) != Color.blue(cv.color) || Color.green(cv.color) != Color.blue(cv.color));
                assertEquals("colour button shows it", cv.color, ((Integer) get(get(act, "colorBtn"), "color")) | 0xFF000000);
            }
            body.forceLayout(); ((View) body.getParent()).forceLayout();
            popShotPanel("C2_colour_tab_" + tab);
            ((android.widget.PopupWindow) get(get(panel, "pop"), "win")).dismiss();
        }
        // palettes persist, and .swatches files go out and back in
        ColorPanel cp = (ColorPanel) call(act, "colorPanel");
        int n = cp.palettes.size();
        ColorPanel.Palette p = new ColorPanel.Palette("Test");
        p.colors.add(0xFFE53935); p.colors.add(0xFF1E88E5);
        cp.palettes.add(p);
        call(cp, "save");
        ColorPanel fresh = new ColorPanel(act, pr, 2f);
        assertEquals(n + 1, fresh.palettes.size());
        assertEquals(2, fresh.palettes.get(n).colors.size());
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        ColorPanel.writeSwatches(p, o);
        List<ColorPanel.Palette> back = ColorPanel.readSwatches(o.toByteArray());
        assertEquals("Test", back.get(0).name);
        near(".swatches keeps colours", 0xE53935, back.get(0).colors.get(0), 2);
        // recent colours
        call(cp, "remember", 0xFF123456);
        assertEquals(0xFF123456, (int) ((List<Integer>) call(cp, "history")).get(0));
    }

    static void forceAll(View v) {
        v.forceLayout();
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) forceAll(((android.view.ViewGroup) v).getChildAt(i));
    }

    void popShotPanel(String name) {
        Object pop = UiTest.latestPop();
        android.widget.PopupWindow w = (android.widget.PopupWindow) get(pop, "win");
        View v = w.getContentView();
        forceAll(v);
        v.measure(View.MeasureSpec.makeMeasureSpec(Math.max(200, w.getWidth()), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.AT_MOST));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(v.getMeasuredWidth(), Math.max(1, v.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
        new android.graphics.Canvas(b).drawColor(0xFF3A3A3C);
        v.draw(new android.graphics.Canvas(b));
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(OUT, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); } catch (Exception e) { }
    }

    @Test public void painting_a_mask_uses_brightness() {
        cv.brush = brush("Hard round"); cv.size = 40;
        cv.color = 0xFFE53935;
        pen(new float[][]{{W * .3f, H * .4f}, {W * .7f, H * .4f}});
        cv.addMask();
        cv.color = 0xFFFF0000; // red on a mask paints its brightness (dark grey), not red
        pen(new float[][]{{W * .3f, H * .6f}, {W * .7f, H * .6f}});
        int[] d = toDoc(W * .5f, H * .6f);
        int m = cv.cur().mask.bmp.getPixel(d[0], d[1]);
        assertEquals("mask stays grey", Color.red(m), Color.blue(m));
    }

    @Test public void palette_generator_from_words() {
        java.util.ArrayList<String> rules = new java.util.ArrayList<>();
        List<int[]> ocean = PaletteGen.generate("ocean", 6, 0, rules);
        assertEquals(6, ocean.size());
        assertEquals(6, rules.size());
        for (int[] p : ocean) assertEquals(5, p.length);
        // ocean leans blue/teal in the main (analogous) palette
        float[] hsv = new float[3];
        int blueish = 0;
        for (int c : ocean.get(0)) { Color.colorToHSV(c, hsv); if (hsv[0] > 150 && hsv[0] < 250) blueish++; }
        assertTrue("ocean is mostly blue: " + blueish, blueish >= 3);
        // same words, same palettes; Shuffle gives new ones
        assertArrayEquals(ocean.get(0), PaletteGen.generate("ocean", 6, 0, null).get(0));
        assertFalse(java.util.Arrays.equals(ocean.get(0), PaletteGen.generate("ocean", 6, 1, null).get(0)));
        // modifiers: pastel is light, dark is dark
        int[] pastel = PaletteGen.generate("pastel flowers", 1, 0, null).get(0), dark = PaletteGen.generate("dark flowers", 1, 0, null).get(0);
        int lp = 0, ld = 0; for (int c : pastel) lp += Fx_lum(c); for (int c : dark) ld += Fx_lum(c);
        assertTrue("pastel lighter than dark: " + lp + " vs " + ld, lp > ld + 200);
        // unknown words still work and are repeatable
        assertArrayEquals(PaletteGen.generate("zorblax", 1, 0, null).get(0), PaletteGen.generate("zorblax", 1, 0, null).get(0));
        assertTrue(PaletteGen.knownWords() > 80);
        // the panel: Generate, then Save adds a palette
        android.content.SharedPreferences pr = act.getSharedPreferences("inkwell", 0);
        pr.edit().putInt("colTab", 4).commit();
        call(act, "pickGrey"); settle();
        ColorPanel cp = (ColorPanel) call(act, "colorPanel");
        set(cp, "genQuery", "sunset");
        call(cp, "generator"); settle();
        View body = (View) get(cp, "body");
        int n = cp.palettes.size();
        View save = UiTest.find(body, "Save");
        assertNotNull(save);
        save.performClick();
        assertEquals(n + 1, cp.palettes.size());
        assertTrue(cp.palettes.get(n).name.startsWith("Sunset"));
        assertEquals(5, cp.palettes.get(n).colors.size());
        popShotPanel("C3_generator");
    }

    static int Fx_lum(int c) { return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000; }
}
