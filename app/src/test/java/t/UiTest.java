package t;

import ai.inkwell.*;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.PopupMenu;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowPopupMenu;
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
        call(act, "pickBrush"); dialogShot("30_brushes");
        call(act, "pickGrey"); dialogShot("31_grey");
        call(act, "canvasDialog"); dialogShot("32_canvas");
        call(act, "textDialog"); dialogShot("33_text");
        call(act, "animSettings"); dialogShot("34_anim_settings");
    }

    /** Opens the Actions menu and taps every item that stays inside the app. */
    @Test public void actions_menu_all_items() {
        View anchor = cv;
        call(act, "actionsMenu", anchor);
        PopupMenu pm = ShadowPopupMenu.getLatestPopupMenu();
        assertNotNull(pm);
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < pm.getMenu().size(); i++) names.append(pm.getMenu().getItem(i).getTitle()).append(" | ");
        System.out.println("ACTIONS: " + names);
        int[] safe = {4, 5, 7, 8, 9, 10, 11, 12, 13};
        for (int id : safe) {
            call(act, "actionsMenu", anchor);
            PopupMenu p = ShadowPopupMenu.getLatestPopupMenu();
            MenuItem it = p.getMenu().findItem(id);
            assertNotNull("item " + id, it);
            Shadows.shadowOf(p).getOnMenuItemClickListener().onMenuItemClick(it);
            settle();
            Dialog d = ShadowDialog.getLatestDialog();
            if (d != null && d.isShowing()) d.dismiss();
            PopupMenu sub = ShadowPopupMenu.getLatestPopupMenu();
            if (sub != p && sub != null) {
                for (int k = 0; k < sub.getMenu().size(); k++) {
                    MenuItem si = sub.getMenu().getItem(k);
                    String t = String.valueOf(si.getTitle());
                    if (t.startsWith("Export")) continue; // opens the system file picker
                    Shadows.shadowOf(sub).getOnMenuItemClickListener().onMenuItemClick(si);
                    settle();
                    Dialog d2 = ShadowDialog.getLatestDialog();
                    if (d2 != null && d2.isShowing()) d2.dismiss();
                }
            }
        }
        shot("40_after_actions");
    }

    @Test public void layer_options_all_items() {
        assertTrue(cv.addLayer(cv.newLayer("Two")));
        call(act, "togglePanel");
        settle();
        for (int id : new int[]{2, 3, 4, 5, 6, 7, 8, 20, 21, 23, 25, 26, 27, 22, 9}) {
            call(act, "layerOptions", cv, cv.current);
            PopupMenu p = ShadowPopupMenu.getLatestPopupMenu();
            MenuItem it = p.getMenu().findItem(id);
            if (it == null) continue;
            Shadows.shadowOf(p).getOnMenuItemClickListener().onMenuItemClick(it);
            settle();
        }
        shot("41_layers_after_options");
    }
}
