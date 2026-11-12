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

    @Test public void audit_fixes_crop_undo_new_canvas_modes() {
        pen(new float[][]{{W * .3f, H * .4f}, {W * .7f, H * .45f}});
        int w0 = cv.docW, h0 = cv.docH;
        long ink = inkSum(L());
        // crop is undoable (no question, history kept)
        call(act, "startCrop"); settle();
        cv.crop.set(0, 0, w0 / 2f, h0);
        ((View) UiTest.find(((View) get(act, "bottom")), "Done")).performClick(); settle();
        assertEquals(w0 / 2, cv.docW);
        cv.undo();
        assertEquals("undo crop", w0, cv.docW);
        assertEquals(ink, inkSum(L()));
        cv.redo();
        assertEquals(w0 / 2, cv.docW);
        cv.undo();
        cv.undo(); // and the stroke before it is still undoable
        assertEquals(0, inkSum(L()));
        cv.redo();
        // resize is undoable too
        cv.resizeDocument(w0 * 2 / 3, h0 * 2 / 3, true);
        cv.undo();
        assertEquals(w0, cv.docW);
        // New canvas makes a new drawing and keeps this one
        String before = (String) get(act, "curId");
        call(act, "canvasDialog", true); settle();
        android.app.AlertDialog d = (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertEquals("Create", String.valueOf(d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).getText()));
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); settle();
        assertNotEquals("a new drawing", before, get(act, "curId"));
        assertTrue(((List<?>) call(act, "orderedIds")).contains(before));
        // picking a tool ends guide editing and Continue filling
        cv.guide = 7; cv.editGuide = true; cv.fillMode = true;
        call(act, "setTool", CanvasView.ERASE);
        assertFalse(cv.editGuide);
        assertFalse(cv.fillMode);
        call(act, "setTool", CanvasView.DRAW);
        cv.guide = 0;
    }

    @Test public void everything_survives_a_restart() {
        pen(new float[][]{{W * .3f, H * .4f}, {W * .7f, H * .45f}});
        cv.cur().name = "Lines";
        cv.cur().opacity = 150;
        cv.setBlend(cv.current, 1);
        cv.addLayer(cv.newLayer("Masked"));
        pen(new float[][]{{W * .3f, H * .6f}, {W * .7f, H * .6f}});
        cv.addMask();
        cv.cur().assist = true;
        CanvasView.TextInfo t = new CanvasView.TextInfo("Hi", 80, true, 0, "serif", 0);
        t.spacing = 0.2f; t.underline = true;
        assertTrue(cv.addTextLayer(t));
        cv.toggleLock(cv.current);
        cv.layers.get(0).hold = 3;
        cv.setBackground(200, true);
        cv.guide = 4; cv.symRotate = true;
        call(act, "togglePages");
        int layers = cv.layers.size();
        ctl.pause().stop();
        settle();
        launch();
        assertEquals(layers, cv.layers.size());
        assertEquals("Lines", cv.layers.get(0).name);
        assertEquals(150, cv.layers.get(0).opacity);
        assertEquals(1, cv.layers.get(0).blend);
        assertEquals(3, cv.layers.get(0).hold);
        assertNotNull("mask kept", cv.layers.get(1).mask);
        assertTrue("assist kept", cv.layers.get(1).assist);
        CanvasView.Layer tl = cv.layers.get(2);
        assertNotNull("text still editable", tl.text);
        assertEquals("Hi", tl.text.text);
        assertTrue(tl.text.underline);
        assertEquals(0.2f, tl.text.spacing, 1e-4);
        assertTrue("lock kept", tl.locked);
        assertEquals(4, cv.guide);
        assertTrue(cv.symRotate);
        assertTrue("page assist kept", cv.pages);
        assertEquals(200, (int) (Integer) get(cv, "bgGrey"));
    }

    @Test public void share_opens_the_share_sheet_with_the_file() throws Exception {
        pen(new float[][]{{W * .3f, H * .4f}, {W * .7f, H * .45f}});
        call(act, "shareAs", 0);
        for (int k = 0; k < 30; k++) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS); Thread.sleep(30); }
        android.content.Intent ch = Shadows.shadowOf(act).getNextStartedActivity();
        assertNotNull("share sheet opened", ch);
        assertEquals(android.content.Intent.ACTION_CHOOSER, ch.getAction());
        android.content.Intent send = ch.getParcelableExtra(android.content.Intent.EXTRA_INTENT);
        android.net.Uri u = send.getParcelableExtra(android.content.Intent.EXTRA_STREAM);
        assertEquals("content", u.getScheme());
        assertTrue((send.getFlags() & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        // the provider serves the file to other apps
        Files prov = org.robolectric.Robolectric.buildContentProvider(Files.class).create().get();
        try (android.os.ParcelFileDescriptor fd = prov.openFile(u, "r")) { assertTrue(fd.getStatSize() > 100); }
        assertEquals("image/png", prov.getType(u));
        try { prov.openFile(android.net.Uri.parse("content://app.inkwell.files/..%2Fsecret"), "r"); fail("must not leave the share folder"); }
        catch (java.io.FileNotFoundException expected) { }
    }

    android.widget.Switch guideSwitch() {
        call(act, "actionsMenu", cv, 1); settle();
        Object pop = UiTest.latestPop();
        View box = (View) get(pop, "box");
        List<View> all = new ArrayList<>();
        Deque<View> q = new ArrayDeque<>(Collections.singleton(box));
        while (!q.isEmpty()) { View v = q.poll(); all.add(v); if (v instanceof android.view.ViewGroup) for (int k = 0; k < ((android.view.ViewGroup) v).getChildCount(); k++) q.add(((android.view.ViewGroup) v).getChildAt(k)); }
        for (View v : all) if (v instanceof android.widget.Switch && "Drawing guide".contentEquals(((android.widget.Switch) v).getText())) return (android.widget.Switch) v;
        return null;
    }

    @Test public void drawing_guide_switch_shows_and_hides_the_guide() {
        assertEquals(0, cv.guide);
        android.widget.Switch sw = guideSwitch();
        assertNotNull(sw);
        assertFalse(sw.isChecked());
        sw.setChecked(true); settle();
        assertEquals("on: 2D grid the first time", 1, cv.guide);
        sw.setChecked(false); settle();
        assertEquals("off", 0, cv.guide);
        // the switch brings back the last guide used
        cv.guide = 6; // isometric
        sw = guideSwitch();
        assertTrue("shows it is on", sw.isChecked());
        sw.setChecked(false); settle();
        assertEquals(0, cv.guide);
        sw = guideSwitch();
        sw.setChecked(true); settle();
        assertEquals("isometric again", 6, cv.guide);
        // perspective gets its points
        sw.setChecked(false);
        act.getSharedPreferences("inkwell", 0).edit().putInt("lastGuide", 7).commit();
        java.util.Arrays.fill(cv.vp, 0);
        guideSwitch().setChecked(true); settle();
        assertEquals(7, cv.guide);
        boolean set = false; for (float v : cv.vp) if (v != 0) set = true;
        assertTrue("vanishing point placed", set);
        settle(); shot("91_guide_on");
    }

    @Test public void quickmenu_slots_can_hold_brushes_and_erasers() {
        act.getSharedPreferences("inkwell", 0).edit().putString("qm2", "b:6B pencil\ne:Charcoal\n2\n4\n5\n6").commit();
        call(act, "quickMenu", W / 2f, H / 2f); settle();
        android.widget.PopupWindow w = (android.widget.PopupWindow) get(act, "qmWin");
        android.view.ViewGroup ring = (android.view.ViewGroup) w.getContentView();
        assertEquals("6B pencil", String.valueOf(((TextView) ring.getChildAt(0)).getText()));
        assertTrue(String.valueOf(((TextView) ring.getChildAt(1)).getText()).contains("eraser"));
        ring.getChildAt(1).performClick(); settle();
        assertEquals(CanvasView.ERASE, cv.tool);
        assertEquals("Charcoal", cv.brush.name);
        call(act, "quickMenu", W / 2f, H / 2f); settle();
        ((android.view.ViewGroup) ((android.widget.PopupWindow) get(act, "qmWin")).getContentView()).getChildAt(0).performClick(); settle();
        assertEquals(CanvasView.DRAW, cv.tool);
        assertEquals("6B pencil", cv.brush.name);
        call(act, "setTool", CanvasView.ERASE);
        assertEquals("the eraser keeps the brush the QuickMenu gave it", "Charcoal", cv.brush.name);
        // picking a brush for a slot saves it
        String[] slots = (String[]) call(act, "qmSlots");
        slots[2] = "b:Ink pen";
        call(act, "saveQmSlots", (Object) slots);
        assertEquals("b:Ink pen", ((String[]) call(act, "qmSlots"))[2]);
        call(act, "setTool", CanvasView.DRAW);
    }

    @Test public void transform_rotation_lever() {
        pen(new float[][]{{W * .35f, H * .45f}, {W * .65f, H * .45f}});
        call(act, "setTool", CanvasView.MOVE); settle();
        cv.setXform(1); // freeform
        float[] h = (float[]) call(cv, "rotHandle");
        assertNotNull("lever shown", h);
        settle(); shot("92_rotation_lever");
        float[] c = (float[]) call(cv, "contentCentre");
        float r = (float) Math.hypot(h[0] - c[0], h[1] - c[1]);
        // drag the knob a quarter turn clockwise around the centre
        float[][] path = new float[10][];
        for (int i = 0; i < 10; i++) { double a = -Math.PI / 2 + (Math.PI / 2) * i / 9; path[i] = new float[]{(float) (c[0] + Math.cos(a) * r), (float) (c[1] + Math.sin(a) * r)}; }
        stroke(path, MotionEvent.TOOL_TYPE_FINGER, 1);
        android.graphics.Matrix m = (android.graphics.Matrix) get(cv, "moveT");
        float[] v = new float[9]; m.getValues(v);
        assertEquals("rotated 90°", 90, Math.toDegrees(Math.atan2(v[3], v[0])), 3);
        assertTrue(String.valueOf(((TextView) get(act, "hudView")).getText()).contains("°"));
        cv.undo();
        m.getValues(v);
        assertEquals("one undo puts it back", 0, Math.toDegrees(Math.atan2(v[3], v[0])), 0.5);
        // with Snapping: sticks to 15° steps
        cv.snapping = true;
        float[][] p2 = new float[8][];
        for (int i = 0; i < 8; i++) { double a = -Math.PI / 2 + Math.toRadians(43) * i / 7; p2[i] = new float[]{(float) (c[0] + Math.cos(a) * r), (float) (c[1] + Math.sin(a) * r)}; }
        stroke(p2, MotionEvent.TOOL_TYPE_FINGER, 1);
        m.getValues(v);
        assertEquals("snapped to 45°", 45, Math.toDegrees(Math.atan2(v[3], v[0])), 0.5);
        cv.snapping = false;
        call(act, "setTool", CanvasView.DRAW);
    }

    @Test public void colordrop_shows_a_dot_while_dragging() {
        View cb = (View) get(act, "colorBtn"); int[] l = new int[2]; cb.getLocationOnScreen(l);
        View d = act.getWindow().getDecorView();
        float sx = l[0] + cb.getWidth() / 2f, sy = l[1] + cb.getHeight() / 2f;
        t0 = android.os.SystemClock.uptimeMillis();
        d.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, sx, sy, 0));
        for (int i = 1; i <= 6; i++) d.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + i * 10, MotionEvent.ACTION_MOVE, sx - i * 60, sy + i * 80, 0));
        View ghost = (View) get(act, "cdGhost");
        assertTrue("dot follows the finger", ghost != null && ghost.getVisibility() == View.VISIBLE);
        settle(); shot("93_colordrop_dot");
        d.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 100, MotionEvent.ACTION_UP, sx - 360, sy + 480, 0));
        assertEquals(View.GONE, ghost.getVisibility());
    }

    CanvasView.Brush brushNamed(String n) { for (CanvasView.Brush b : CanvasView.BRUSHES) if (b.name.equals(n)) return b; return null; }

    @Test public void each_brush_keeps_its_own_size() {
        call(act, "setTool", CanvasView.DRAW);
        call(act, "switchBrush", brushNamed("Ink pen"));
        cv.size = 10;
        call(act, "switchBrush", brushNamed("HB pencil"));
        cv.size = 40;
        call(act, "switchBrush", brushNamed("Ink pen"));
        assertEquals("ink pen back to 10", 10f, cv.size, 0.01f);
        call(act, "switchBrush", brushNamed("HB pencil"));
        assertEquals("pencil back to 40", 40f, cv.size, 0.01f);
        // the eraser keeps separate sizes for the same brush
        call(act, "setTool", CanvasView.ERASE);
        call(act, "switchBrush", brushNamed("HB pencil"));
        cv.size = 70;
        call(act, "setTool", CanvasView.DRAW);
        assertEquals("HB pencil", cv.brush.name);
        assertEquals("painting size untouched by the eraser", 40f, cv.size, 0.01f);
        call(act, "setTool", CanvasView.ERASE);
        assertEquals(70f, cv.size, 0.01f);
        call(act, "setTool", CanvasView.DRAW);
        // survives a restart
        ctl.pause().stop(); settle(); launch();
        call(act, "switchBrush", brushNamed("Ink pen"));
        assertEquals(10f, cv.size, 0.01f);
    }

    @Test public void timelapse_replay_pause_and_scrub() throws Exception {
        java.io.File dir = (java.io.File) call(act, "tlDir", get(act, "curId"));
        dir.mkdirs();
        for (int i = 0; i < 40; i++) {
            Bitmap b = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888);
            b.eraseColor(0xFF000000 | (i * 6) * 0x10101);
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(dir, String.format("%06d.jpg", i)))) { b.compress(Bitmap.CompressFormat.JPEG, 90, o); }
        }
        call(act, "replayTimelapse"); settle();
        android.widget.FrameLayout ov = (android.widget.FrameLayout) get(act, "tlPlayer");
        assertNotNull("player open", ov);
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        TextView info = (TextView) ov.getChildAt(1);
        t0 = android.os.SystemClock.uptimeMillis();
        ov.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 100, 300, 0));
        String held = info.getText().toString();
        assertTrue("paused while held: " + held, held.startsWith("Scrubbing"));
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
        assertEquals("doesn't move while held", held, info.getText().toString());
        ov.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 50, MotionEvent.ACTION_MOVE, 100 + ov.getWidth() / 2f, 300, 0));
        assertNotEquals("dragging scrubs", held, info.getText().toString());
        settle(); shot("95_timelapse_scrub");
        ov.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 100, MotionEvent.ACTION_UP, 100 + ov.getWidth() / 2f, 300, 0));
        act.onBackPressed();
        assertNull("Back closes it", get(act, "tlPlayer"));
    }

    @Test public void halftone_brush_one_grid_no_double_up() {
        cv.brush = brushNamed("Halftone");
        cv.size = 120;
        cv.grey = 0;
        pen(new float[][]{{W * .3f, H * .5f}, {W * .5f, H * .5f}});
        Bitmap one = L().copy(Bitmap.Config.ARGB_8888, false);
        // a second pass over the same place, and overlapping it: no change where they overlap
        pen(new float[][]{{W * .35f, H * .5f}, {W * .45f, H * .52f}});
        pen(new float[][]{{W * .4f, H * .5f}, {W * .6f, H * .5f}});
        Bitmap two = L();
        int changed = 0, total = 0;
        for (int y = 0; y < one.getHeight(); y++) for (int x = 0; x < one.getWidth(); x++) {
            int a1 = one.getPixel(x, y) >>> 24;
            if (a1 == 0) continue;
            total++;
            if (a1 != (two.getPixel(x, y) >>> 24)) changed++;
        }
        assertTrue(total > 500);
        assertEquals("dots already there never get darker or shift", 0, changed);
        // dots, not a solid area: about a third of the covered area is ink
        int[] d = toDoc(W * .45f, H * .5f);
        int ink = 0, n = 0;
        for (int y = d[1] - 20; y < d[1] + 20; y++) for (int x = d[0] - 20; x < d[0] + 20; x++) { n++; if ((two.getPixel(x, y) >>> 24) > 128) ink++; }
        assertTrue("halftone dots: " + ink + "/" + n, ink > n * 0.15 && ink < n * 0.7);
        settle(); shot("96_halftone");
    }

    @Test public void curves_like_procreate_and_posterize_threshold() {
        pen(new float[][]{{W * .3f, H * .5f}, {W * .7f, H * .5f}});
        Object[] adjs = (Object[]) call(act, "adjustments");
        Object curves = null, post = null;
        for (Object a : adjs) { if (get(a, "name").equals("Curves")) curves = a; if (get(a, "name").equals("Posterize")) post = a; }
        call(act, "startAdjust", curves); settle();
        View cvw = (View) get(act, "curveView");
        assertNotNull("histogram drawn behind", get(cvw, "hist"));
        float w = cvw.getWidth(), h = cvw.getHeight();
        t0 = android.os.SystemClock.uptimeMillis();
        // tap the middle: a point lands ON the line (no jump)
        cvw.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, w / 2, h * 0.1f, 0));
        cvw.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 20, MotionEvent.ACTION_UP, w / 2, h * 0.1f, 0));
        List<?> pts = (List<?>) get(cvw, "pts");
        assertEquals(3, pts.size());
        assertEquals("added on the line", 0.5f, ((float[]) pts.get(1))[1], 0.02f);
        // drag it up: brighter mid-tones
        cvw.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 1000, MotionEvent.ACTION_DOWN, w / 2, h / 2, 0));
        cvw.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 1050, MotionEvent.ACTION_MOVE, w / 2, h * 0.25f, 0));
        cvw.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 1100, MotionEvent.ACTION_UP, w / 2, h * 0.25f, 0));
        assertEquals(0.75f, ((float[]) pts.get(1))[1], 0.03f);
        settle(); shot("97_curves");
        // double-tap removes it
        cvw.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 2000, MotionEvent.ACTION_DOWN, w / 2, h * 0.25f, 0));
        cvw.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 2030, MotionEvent.ACTION_UP, w / 2, h * 0.25f, 0));
        cvw.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 2150, MotionEvent.ACTION_DOWN, w / 2, h * 0.25f, 0));
        cvw.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 2180, MotionEvent.ACTION_UP, w / 2, h * 0.25f, 0));
        assertEquals("double-tap removed the point", 2, pts.size());
        call(act, "finishAdjust", false);
        // posterize threshold moves where the steps happen
        int[] a = (int[]) callStatic("posterize", 4, 0f), b = (int[]) callStatic("posterize", 4, 0.3f);
        assertFalse(java.util.Arrays.equals(a, b));
        call(act, "startAdjust", post); settle();
        TextView lab = (TextView) UiTest.find((View) get(act, "bottom"), "Threshold", true);
        assertNotNull("threshold slider shown", lab);
        call(act, "finishAdjust", false);
    }

    Object callStatic(String name, Object... args) {
        try {
            for (java.lang.reflect.Method m : Class.forName("app.inkwell.Fx").getDeclaredMethods())
                if (m.getName().equals(name) && m.getParameterCount() == args.length) { m.setAccessible(true); return m.invoke(null, args); }
        } catch (Exception e) { throw new RuntimeException(e); }
        throw new RuntimeException("no " + name);
    }

    @Test public void recent_brushes_keep_their_order_while_the_library_is_open() {
        call(act, "noteRecent", brushNamed("Charcoal"));
        call(act, "noteRecent", brushNamed("Ink pen"));
        set(act, "libSet", "Recent");
        call(act, "pickBrush"); settle();
        Object pop = UiTest.latestPop();
        View box = (View) get(pop, "box");
        View second = UiTest.find(box, "Charcoal"); // exact: not the "Charcoals" set
        assertNotNull(second);
        ((View) second.getParent()).performClick(); settle(); // pick Charcoal: it becomes most recent...
        View first = UiTest.find(box, "Ink pen", true);
        int[] p1 = new int[2], p2 = new int[2];
        first.getLocationOnScreen(p1);
        View now = UiTest.find(box, "Charcoal   ·  tap again to edit"); // the picked brush's row
        assertNotNull(now);
        now.getLocationOnScreen(p2);
        assertTrue("...but the list doesn't reorder while open", p1[1] < p2[1]);
        ((android.widget.PopupWindow) get(pop, "win")).dismiss();
        assertEquals("next time it is first", "Charcoal", ((CanvasView.Brush) ((List<?>) call(act, "recentBrushes")).get(0)).name);
    }

    @Test public void transform_shows_box_and_lever_right_away() {
        pen(new float[][]{{W * .35f, H * .45f}, {W * .65f, H * .5f}});
        int undoSteps = ((List<?>) get(cv, "undoList")).size();
        call(act, "setTool", CanvasView.MOVE); settle();
        assertTrue("box shows without touching", (Boolean) get(cv, "moving"));
        assertNotNull("rotation lever shows", call(cv, "rotHandle"));
        call(act, "setTool", CanvasView.DRAW);
        assertEquals("no empty Transform undo step", undoSteps, ((List<?>) get(cv, "undoList")).size());
        cv.undo();
        assertEquals("undo still takes back the stroke", 0, inkSum(L()));
    }
}
