package t;

import ai.inkwell.*;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w841dp-h701dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UiTest extends Base {
    float W, H;
    @Before public void up() { launch(); closeGallery(); W = cv.getWidth(); H = cv.getHeight(); line(W * .3f, H * .4f, W * .7f, H * .5f); }

    void dialogShot(String name) {
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull(name + " dialog shown", d);
        View v = d.getWindow().getDecorView();
        v.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.AT_MOST));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        try (FileOutputStream o = new FileOutputStream(OUT + "/" + name + ".png")) { b.compress(Bitmap.CompressFormat.PNG, 100, o); } catch (Exception e) { }
        d.dismiss();
    }

    @Test public void bars() {
        call(act, "setTool", CanvasView.SELECT); settle(); shot("20_select_bar");
        call(act, "setTool", CanvasView.MOVE); cv.setXform(3); settle(); shot("21_transform_warp");
        cv.setXform(1); settle(); shot("22_transform_freeform");
        call(act, "setTool", CanvasView.DRAW);
        Object[] adjs = (Object[]) call(act, "adjustments");
        for (Object a : adjs) if (get(a, "name").equals("Curves")) { call(act, "startAdjust", a); settle(); shot("23_curves"); call(act, "finishAdjust", false); }
        call(act, "startAdjust", adjs[0]); settle(); shot("24_blur"); call(act, "finishAdjust", false);
        call(act, "startLiquify"); settle(); shot("25_liquify"); call(act, "setTool", CanvasView.DRAW);
        call(act, "startClone"); settle(); shot("26_clone"); call(act, "setTool", CanvasView.DRAW);
        settle(); shot("27_back_to_draw");
    }

    @Test public void dialogs() {
        call(act, "canvasDialog"); dialogShot("32_canvas");
        call(act, "textDialog"); dialogShot("33_text");
        call(act, "animSettings"); dialogShot("34_anim_settings");
    }

    static Object latestPopMenu() {
        try { java.lang.reflect.Field f = Class.forName("ai.inkwell.Ui$PopMenu").getDeclaredField("latest"); f.setAccessible(true); return f.get(null); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static Object latestPop() {
        try { java.lang.reflect.Field f = Class.forName("ai.inkwell.Ui$Pop").getDeclaredField("latest"); f.setAccessible(true); return f.get(null); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Draws the latest popover's content to a PNG. */
    void popShot(String name) {
        android.widget.PopupWindow w = (android.widget.PopupWindow) get(latestPop(), "win");
        View v = w.getContentView();
        v.measure(View.MeasureSpec.makeMeasureSpec(Math.max(200, w.getWidth()), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(v.getMeasuredWidth(), Math.max(1, v.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
        new Canvas(b).drawColor(0xFF3A3A3C);
        v.draw(new Canvas(b));
        try (FileOutputStream o = new FileOutputStream(OUT + "/" + name + ".png")) { b.compress(Bitmap.CompressFormat.PNG, 100, o); } catch (Exception e) { }
        w.dismiss();
    }

    @Test public void actions_tabs_and_commands() {
        for (int tab = 0; tab < 6; tab++) { call(act, "actionsMenu", cv, tab); settle(); popShot("40_actions_tab_" + tab); }
        for (int id : new int[]{4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 7, 14, 12, 11}) {
            call(act, "action", id, cv);
            settle();
            Dialog d = ShadowDialog.getLatestDialog();
            if (d != null && d.isShowing()) d.dismiss();
        }
        call(act, "adjustMenu", cv); settle(); popShot("41_adjustments");
        call(act, "pickBrush"); settle(); popShot("42_brush_library");
        set(act, "libSet", "Painting");
        call(act, "pickBrush"); settle(); popShot("42b_painting_set");
        call(act, "pickGrey"); settle(); popShot("43_colour");
        cv.flipView(true);
        shot("44_after_actions");
    }

    @Test public void layer_options_all_items() {
        assertTrue(cv.addLayer(cv.newLayer("Two")));
        call(act, "togglePanel");
        settle();
        for (int id : new int[]{2, 3, 4, 5, 6, 7, 8, 20, 21, 23, 25, 26, 27, 22, 9}) {
            call(act, "layerOptions", cv, cv.current);
            Object m = latestPopMenu();
            Object it = call(m, "findItem", id);
            if (it == null) continue;
            call(m, "click", it);
            settle();
        }
        shot("45_layers_after_options");
    }
}
