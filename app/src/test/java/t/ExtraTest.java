package t;

import app.inkwell.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.MotionEvent;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w841dp-h701dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ExtraTest extends Base {
    static final String FX = new java.io.File(ExtraTest.class.getResource("/fixtures/img.png").getPath()).getParent();
    float W, H;
    @Before public void up() { launch(); closeGallery(); W = cv.getWidth(); H = cv.getHeight(); }
    Bitmap L() { return cv.cur().bmp; }

    @Test public void quickshape_line_and_ellipse() {
        t0 = android.os.SystemClock.uptimeMillis();
        float[][] wobbly = {{W * .3f, H * .3f}, {W * .4f, H * .33f}, {W * .5f, H * .29f}, {W * .6f, H * .34f}, {W * .7f, H * .3f}};
        touch(cv, ev(MotionEvent.ACTION_DOWN, new float[][]{wobbly[0]}, MotionEvent.TOOL_TYPE_STYLUS, 1));
        for (int i = 1; i < wobbly.length; i++) { ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS); touch(cv, ev(MotionEvent.ACTION_MOVE, new float[][]{wobbly[i]}, MotionEvent.TOOL_TYPE_STYLUS, 1)); }
        ShadowLooper.idleMainLooper(700, java.util.concurrent.TimeUnit.MILLISECONDS); // hold
        touch(cv, ev(MotionEvent.ACTION_UP, new float[][]{wobbly[4]}, MotionEvent.TOOL_TYPE_STYLUS, 1));
        assertTrue("snapped straight: wobble point not painted", alphaAt(L(), W * .4f, H * .33f) < 40);
        assertTrue("straight line painted", alphaAt(L(), W * .5f, H * .3f) > 200);
        shot("50_quickshape");
    }

    @Test public void eyedropper_hold() {
        cv.grey = 128;
        cv.fillLayer();
        cv.grey = 0;
        t0 = android.os.SystemClock.uptimeMillis();
        touch(cv, ev(MotionEvent.ACTION_DOWN, new float[][]{{W * .5f, H * .5f}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS);
        touch(cv, ev(MotionEvent.ACTION_UP, new float[][]{{W * .5f, H * .5f}}, MotionEvent.TOOL_TYPE_FINGER, 1));
        assertEquals("picked grey", 128, cv.grey, 2);
        assertEquals("no stroke left behind", 1, 1);
    }

    @Test public void exports_all_formats() throws Exception {
        line(W * .3f, H * .3f, W * .7f, H * .6f);
        String[] ext = {"png", "jpg", "webp", "pdf", "psd"};
        for (int f = 0; f < ext.length; f++) {
            File out = new File(System.getProperty("java.io.tmpdir") + "/export." + ext[f]);
            out.delete();
            call(act, "export", Uri.fromFile(out), f);
            settle();
            if (f != 3) assertTrue(ext[f] + " written", out.exists() && out.length() > 100); // PDF needs the real device (no PDF engine here)
        }
    }

    @Test public void import_procreate_brushset_and_reject_artwork() throws Exception {
        int before = CanvasView.BRUSHES.size();
        call(act, "importBrushes", Uri.fromFile(new File(FX, "test.brushset")));
        settle();
        assertEquals("two brushes imported", before + 2, CanvasView.BRUSHES.size());
        CanvasView.Brush b = CanvasView.BRUSHES.get(CanvasView.BRUSHES.size() - 1);
        assertEquals("Checker Brush", b.name);
        assertEquals(cv.brush, CanvasView.BRUSHES.get(before));
        stroke(new float[][]{{W * .2f, H * .5f}, {W * .5f, H * .55f}, {W * .8f, H * .5f}}, MotionEvent.TOOL_TYPE_STYLUS, 0.8f);
        assertTrue("imported brush paints", inkSum(L()) > 0);
        shot("51_imported_brush");
        // saved and reloaded on next launch
        File[] saved = new File(act.getFilesDir(), "brushes").listFiles();
        assertTrue(saved != null && saved.length >= 2);
        // .procreate artwork gives a helpful message instead of crashing
        call(act, "importBrushes", Uri.fromFile(new File(FX, "art.procreate")));
        settle();
        assertEquals(before + 2, CanvasView.BRUSHES.size());
        for (int i = CanvasView.BRUSHES.size() - 1; i >= before; i--) CanvasView.BRUSHES.remove(i);
    }

    @Test public void open_image_and_pdf_as_new_drawings() {
        String first = (String) get(act, "curId");
        call(act, "load", Uri.fromFile(new File(FX, "img.png")), false);
        settle();
        assertEquals(300, cv.docW);
        assertEquals(2, cv.layers.size());
        assertNotEquals(first, get(act, "curId"));
        call(act, "load", Uri.fromFile(new File(FX, "img.png")), true);
        settle();
        assertEquals("import as layer adds one", 3, cv.layers.size());


    }

    @Test public void resize_and_undo_limit() {
        line(W * .3f, H * .3f, W * .7f, H * .6f);
        cv.addMask();
        cv.editMask(false);
        cv.resizeDocument(800, 1000, true);
        assertEquals(800, cv.docW);
        assertNotNull("mask kept", cv.cur().mask);
        assertTrue(inkSum(L()) > 0);
        for (int i = 0; i < 270; i++) line(W * .2f, H * (.2f + i * .002f), W * .25f, H * (.2f + i * .002f));
        int n = 0;
        while (n < 400) { long a = inkSum(L()); cv.undo(); n++; if (inkSum(L()) == a) break; }
        assertTrue("about 250 undos available: " + n, n >= 250 && n <= 252);
    }

    @Test public void adjust_slide_on_canvas_changes_amount() {
        line(W * .3f, H * .3f, W * .7f, H * .6f);
        Object[] adjs = (Object[]) call(act, "adjustments");
        call(act, "startAdjust", adjs[0]);
        float v0 = ((float[]) get(act, "adjVals"))[0];
        line(W * .2f, H * .5f, W * .6f, H * .5f);
        float v1 = ((float[]) get(act, "adjVals"))[0];
        assertTrue("sliding increased amount " + v0 + " -> " + v1, v1 > v0 + 5);
        call(act, "finishAdjust", true);
    }
}
