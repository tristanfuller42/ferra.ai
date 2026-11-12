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
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** New brush sets, shape/grain sources, Colour Fill, rotational symmetry, photo picker, liquify distortion,
 *  selection mask visibility. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w841dp-h701dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BatchFourTest extends Base {
    float W, H;
    @Before public void up() { launch(); closeGallery(); W = cv.getWidth(); H = cv.getHeight(); }
    Bitmap L() { return cv.cur().bmp; }
    String hud() { TextView t = (TextView) get(act, "hudView"); return t == null ? "" : String.valueOf(t.getText()); }
    void pen(float[][] pts) { stroke(pts, MotionEvent.TOOL_TYPE_STYLUS, 1); }

    @Test public void every_brush_set_has_brushes_that_paint() throws Exception {
        assertTrue("about 50 brushes: " + CanvasView.BRUSHES.size(), CanvasView.BRUSHES.size() >= 50);
        for (String set : new String[]{"Calligraphy", "Charcoals", "Drawing", "Textures", "Spray paints", "Elements"}) {
            int n = 0;
            for (CanvasView.Brush b : CanvasView.BRUSHES) if (b.set.equals(set)) n++;
            assertTrue(set + " has brushes", n >= 4);
        }
        cv.size = 40;
        // a sheet of every brush's stroke preview, to look at
        int cols = 5, cw = 330, ch = 90;
        List<CanvasView.Brush> all = CanvasView.BRUSHES;
        Bitmap sheet = Bitmap.createBitmap(cols * cw, ((all.size() + cols - 1) / cols) * ch, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas sc = new android.graphics.Canvas(sheet);
        sc.drawColor(0xFF2C2C2E); // previews are drawn for the dark UI
        android.graphics.Paint tp = new android.graphics.Paint(); tp.setTextSize(14); tp.setColor(0xFF3D9BFF);
        for (int i = 0; i < all.size(); i++) {
            CanvasView.Brush b = all.get(i);
            cv.brush = b;
            cv.clearLayer();
            pen(new float[][]{{W * .25f, H * .5f}, {W * .4f, H * .45f}, {W * .55f, H * .55f}, {W * .7f, H * .5f}});
            assertTrue(b.name + " paints", inkSum(L()) > 0);
            Bitmap pv = CanvasView.brushPreview(act, b, cw - 10, ch - 22);
            sc.drawBitmap(pv, (i % cols) * cw + 5, (i / cols) * ch + 18, null);
            sc.drawText(b.set + " · " + b.name, (i % cols) * cw + 5, (i / cols) * ch + 14, tp);
        }
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(OUT, "89_all_brushes.png"))) { sheet.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }

    @Test public void shape_and_grain_from_pictures() throws Exception {
        Bitmap pic = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        pic.eraseColor(0xFFFFFFFF);
        android.graphics.Paint p = new android.graphics.Paint(); p.setColor(0xFF000000);
        new android.graphics.Canvas(pic).drawRect(40, 10, 60, 90, p); // a dark bar on white paper
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        pic.compress(Bitmap.CompressFormat.PNG, 100, o);
        CanvasView.Brush b = ((CanvasView.Brush) call(CanvasView.BRUSHES.get(0), "copy", "Test shape"));
        Class<?> br = Class.forName("app.inkwell.Brushes");
        java.lang.reflect.Method shape = br.getDeclaredMethod("applyShape", CanvasView.Brush.class, byte[].class); shape.setAccessible(true);
        assertTrue((Boolean) shape.invoke(null, b, o.toByteArray()));
        byte[][] tip = (byte[][]) get(b, "tip");
        int[] tw = (int[]) get(b, "tw");
        assertEquals("bar becomes paint", 255, tip[0][50 * tw[0] + 50] & 255);
        assertEquals("paper stays empty", 0, tip[0][50 * tw[0] + 10] & 255);
        java.lang.reflect.Method grain = br.getDeclaredMethod("applyGrain", CanvasView.Brush.class, byte[].class); grain.setAccessible(true);
        assertTrue((Boolean) grain.invoke(null, b, o.toByteArray()));
        assertNotNull(get(b, "tex"));
        cv.brush = b; cv.size = 60;
        pen(new float[][]{{W * .3f, H * .5f}, {W * .7f, H * .5f}});
        assertTrue(inkSum(L()) > 0);
    }

    @Test public void colour_fill_selection() {
        call(act, "setTool", CanvasView.SELECT);
        cv.selKind = 2; // rectangle
        cv.selFill = true;
        cv.grey = 80;
        stroke(new float[][]{{W * .3f, H * .3f}, {W * .5f, H * .45f}, {W * .6f, H * .6f}}, MotionEvent.TOOL_TYPE_FINGER, 1);
        assertNotNull("selected", cv.sel);
        int[] in = toDoc(W * .45f, H * .45f), out = toDoc(W * .2f, H * .2f);
        assertEquals("filled with the grey", 80, L().getPixel(in[0], in[1]) & 255);
        assertEquals(255, L().getPixel(in[0], in[1]) >>> 24);
        assertEquals("outside untouched", 0, L().getPixel(out[0], out[1]) >>> 24);
        settle(); shot("90_colour_fill");
        cv.undo();
        assertEquals("one undo removes the fill", 0, L().getPixel(in[0], in[1]) >>> 24);
        cv.selFill = false;
        call(act, "setTool", CanvasView.DRAW);
    }

    @Test public void rotational_vs_mirror_symmetry() {
        cv.guide = 2; // vertical
        cv.size = 20;
        float[][] dot = {{W * .3f, H * .3f}, {W * .32f, H * .3f}};
        cv.symRotate = false;
        pen(dot);
        assertTrue("mirror copy", alphaAt(L(), W - W * .31f, H * .3f) > 100 || mirrored(.31f, .3f, false) > 100);
        cv.clearLayer();
        cv.symRotate = true;
        pen(dot);
        assertTrue("rotated copy (opposite corner)", mirrored(.31f, .3f, true) > 100);
        assertTrue("no mirror copy", mirrored(.31f, .3f, false) < 40);
        cv.guide = 0;
    }

    /** Alpha at the symmetric partner of a screen point (fractions of the view), in canvas coordinates. */
    int mirrored(float fx, float fy, boolean rotated) {
        int[] d = toDoc(W * fx, H * fy);
        int x = cv.docW - d[0], y = rotated ? cv.docH - d[1] : d[1];
        return L().getPixel(Math.max(0, Math.min(cv.docW - 1, x)), Math.max(0, Math.min(cv.docH - 1, y))) >>> 24;
    }

    @Test public void photo_button_uses_the_photo_picker() {
        call(act, "pickPhoto", 2);
        android.content.Intent i = Shadows.shadowOf(act).getNextStartedActivity();
        assertNotNull(i);
        assertEquals(android.provider.MediaStore.ACTION_PICK_IMAGES, i.getAction());
    }

    @Test public void liquify_distortion_adds_turbulence() {
        for (int k = 0; k < 8; k++) line(W * .2f, H * (.3f + k * .05f), W * .8f, H * (.3f + k * .05f));
        call(act, "startLiquify");
        cv.liqMode = 0;
        cv.liqDistortion = 0;
        line(W * .4f, H * .4f, W * .6f, H * .45f);
        Bitmap plain = L().copy(Bitmap.Config.ARGB_8888, false);
        cv.endLiquify(false); // cancel: back to the lines
        call(act, "startLiquify");
        cv.liqMode = 0;
        cv.liqDistortion = 1;
        line(W * .4f, H * .4f, W * .6f, H * .45f);
        Bitmap turb = L().copy(Bitmap.Config.ARGB_8888, false);
        cv.endLiquify(false);
        cv.liqDistortion = 0;
        int diff = 0;
        for (int y = 0; y < plain.getHeight(); y += 2) for (int x = 0; x < plain.getWidth(); x += 2)
            if (Math.abs((plain.getPixel(x, y) >>> 24) - (turb.getPixel(x, y) >>> 24)) > 60) diff++;
        assertTrue("distortion changes the warp: " + diff, diff > 50);
        call(act, "setTool", CanvasView.DRAW);
    }

    @Test public void selection_visibility_and_sidebar() {
        // selection mask visibility pref is used
        cv.selVisibility = 0f;
        call(act, "setTool", CanvasView.SELECT);
        cv.selKind = 2;
        stroke(new float[][]{{W * .3f, H * .3f}, {W * .6f, H * .6f}}, MotionEvent.TOOL_TYPE_FINGER, 1);
        Bitmap a = Bitmap.createBitmap(cv.getWidth(), cv.getHeight(), Bitmap.Config.ARGB_8888);
        cv.draw(new android.graphics.Canvas(a));
        cv.selVisibility = 2f;
        Bitmap b = Bitmap.createBitmap(cv.getWidth(), cv.getHeight(), Bitmap.Config.ARGB_8888);
        cv.draw(new android.graphics.Canvas(b));
        int x = (int) (W * .25f), y = (int) (H * .7f); // on the paper, outside the selection
        assertEquals("0%: no stripes", 0xFFFFFFFF, a.getPixel(x, y) | 0);
        long sa = 0, sb = 0;
        for (int i = 0; i < 40; i++) { sa += (a.getPixel(x + i, y + i) >> 16) & 255; sb += (b.getPixel(x + i, y + i) >> 16) & 255; } // red: the stripes are blue
        assertTrue("200%: strong stripes", sb < sa);
        call(act, "setTool", CanvasView.DRAW);
        // no grip on the sidebar any more: the first item is the size slider
        View side = (View) get(act, "side");
        assertSame(get(act, "sizeS"), ((android.view.ViewGroup) side).getChildAt(0));
    }
}
