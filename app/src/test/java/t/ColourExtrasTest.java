package t;

import app.inkwell.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.*;
import static org.junit.Assert.*;

/** Colour extras: layer colour blend modes, gradient map presets, recolor, per-channel curves, brush colour dynamics. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w841dp-h701dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ColourExtrasTest extends Base {
    float W, H;
    @Before public void up() { launch(); closeGallery(); W = cv.getWidth(); H = cv.getHeight(); }
    void pen(float[][] pts) { stroke(pts, MotionEvent.TOOL_TYPE_STYLUS, 1); }
    int at(float fx, float fy) { int[] d = toDoc(W * fx, H * fy); return cv.cur().bmp.getPixel(d[0], d[1]); }
    CanvasView.Brush brush(String n) { for (CanvasView.Brush b : CanvasView.BRUSHES) if (b.name.equals(n)) return b; return null; }
    Object adj(String name) { for (Object a : (Object[]) call(act, "adjustments")) if (get(a, "name").equals(name)) return a; return null; }

    @Test public void layer_blend_modes_include_colour_modes() {
        List<String> names = Arrays.asList(CanvasView.BLEND_NAMES);
        for (String n : new String[]{"Hue", "Saturation", "Color", "Luminosity", "Exclusion"}) assertTrue(n, names.contains(n));
        assertEquals(CanvasView.BLEND_NAMES.length, CanvasView.BLEND_SHORT.length);
        assertTrue("onion skins tinted by default", cv.onionTint);
    }

    @Test public void gradient_map_presets_and_custom_stops() throws Exception {
        int[] s = {0xFF000000, 0xFFFFFFFF, 0x00000000}, d = new int[3];
        Class<?> fx = Class.forName("app.inkwell.Fx");
        java.lang.reflect.Method m = fx.getDeclaredMethod("gradientMapStops", int[].class, int[].class, int[].class);
        m.setAccessible(true);
        m.invoke(null, s, d, new int[]{0xFF112233, 0xFF808080, 0xFFAABBCC});
        assertEquals(0xFF112233, d[0]);
        assertEquals(0xFFAABBCC, d[1]);
        assertEquals("transparent stays transparent", 0, d[2] >>> 24);
        android.content.SharedPreferences p = (android.content.SharedPreferences) get(act, "prefs");
        p.edit().putInt("gradMap", 3).apply(); // Sunset
        assertEquals(4, ((int[]) call(act, "gradStops")).length);
        p.edit().putInt("gradMap", 10).putString("gradCustom", "#000000,#FF0000").apply();
        assertArrayEquals(new int[]{0xFF000000, 0xFFFF0000}, (int[]) call(act, "gradStops"));
        // the bar shows preset chips
        p.edit().putInt("gradMap", 0).apply();
        cv.brush = brush("Hard round"); cv.size = 40; cv.color = 0xFF000000;
        pen(new float[][]{{W * .3f, H * .5f}, {W * .7f, H * .5f}});
        call(act, "startAdjust", adj("Gradient Map")); settle();
        assertNotNull(UiTest.find((View) get(act, "bottom"), "Thermal", true));
        shot("C10_gradient_map");
        call(act, "finishAdjust", false);
    }

    @Test public void recolor_replaces_a_tapped_colour_keeping_the_others() {
        cv.brush = brush("Hard round"); cv.size = 40; cv.opacity = 1f;
        cv.color = 0xFFE53935; pen(new float[][]{{W * .3f, H * .4f}, {W * .7f, H * .4f}});
        cv.color = 0xFF43A047; pen(new float[][]{{W * .3f, H * .6f}, {W * .7f, H * .6f}});
        cv.color = 0xFF1E88E5; // replace red with blue
        call(act, "startAdjust", adj("Recolor")); settle();
        int[] d = toDoc(W * .5f, H * .4f);
        cv.onAdjustTap.accept((float) d[0], (float) d[1]);
        settle();
        call(act, "finishAdjust", true); settle();
        int red = at(.5f, .4f), green = at(.5f, .6f);
        assertTrue("red became blue: " + Integer.toHexString(red), Color.blue(red) > Color.red(red) + 60);
        assertTrue("green untouched: " + Integer.toHexString(green), Color.green(green) > Color.red(green) + 60 && Color.green(green) > Color.blue(green));
    }

    @Test public void curves_edit_one_channel() {
        cv.brush = brush("Hard round"); cv.size = 40;
        pen(new float[][]{{W * .3f, H * .5f}, {W * .7f, H * .5f}});
        call(act, "startAdjust", adj("Curves")); settle();
        View cvw = (View) get(act, "curveView");
        call(cvw, "setChannel", 1); // red
        float w = cvw.getWidth(), h = cvw.getHeight();
        t0 = android.os.SystemClock.uptimeMillis();
        cvw.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, w / 2, h / 2, 0));
        cvw.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 50, MotionEvent.ACTION_MOVE, w / 2, h * 0.2f, 0));
        cvw.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 100, MotionEvent.ACTION_UP, w / 2, h * 0.2f, 0));
        int[][] l = (int[][]) call(cvw, "luts");
        assertTrue("red raised: " + l[1][128], l[1][128] > 150);
        assertEquals("green untouched", 128, l[2][128], 1);
        assertEquals("master untouched", 128, l[0][128], 1);
        call(act, "finishAdjust", false);
    }

    @Test public void brush_colour_dynamics_vary_the_colour() {
        CanvasView.Brush b = (CanvasView.Brush) call(brush("Hard round"), "copy", "Dyn");
        call(b, "put", "hueJitter", 1f);
        cv.brush = b; cv.size = 6; cv.opacity = 1f; cv.color = 0xFFE53935;
        float[][] path = new float[40][];
        for (int i = 0; i < 40; i++) path[i] = new float[]{W * (.2f + .6f * i / 39f), H * .5f};
        pen(path);
        Set<Integer> hues = new HashSet<>();
        float[] hsv = new float[3];
        for (int i = 0; i < 60; i++) {
            int p = at(.22f + .56f * i / 59f, .5f);
            if ((p >>> 24) < 200) continue;
            Color.colorToHSV(p, hsv);
            hues.add((int) (hsv[0] / 30));
        }
        assertTrue("many hues: " + hues, hues.size() >= 4);
        // without dynamics: one colour
        cv.brush = brush("Hard round"); cv.color = 0xFF1E88E5;
        pen(new float[][]{{W * .2f, H * .3f}, {W * .8f, H * .3f}});
        assertEquals(at(.3f, .3f) & 0xFFFFFF, at(.7f, .3f) & 0xFFFFFF);
        shot("C11_colour_dynamics");
    }
}
