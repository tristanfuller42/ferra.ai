package t;

import app.inkwell.*;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import org.junit.Test;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import java.io.FileOutputStream;

/** Walks through every screen and saves screenshots, at the Pixel Fold's real screen sizes (see subclasses). */
public abstract class AuditTest extends Base {
    abstract String tag();
    float W, H;

    void popShot(String name) {
        Object pop = UiTest.latestPop();
        if (pop == null) return;
        android.widget.PopupWindow w = (android.widget.PopupWindow) get(pop, "win");
        if (!w.isShowing()) return;
        View v = w.getContentView();
        v.measure(View.MeasureSpec.makeMeasureSpec(Math.max(200, w.getWidth()), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(v.getMeasuredWidth(), Math.max(1, v.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
        new Canvas(b).drawColor(0xFF3A3A3C);
        v.draw(new Canvas(b));
        save(b, name);
        w.dismiss();
    }

    void dialogShot(String name) {
        Dialog d = ShadowDialog.getLatestDialog();
        if (d == null || !d.isShowing()) return;
        View v = d.getWindow().getDecorView();
        v.measure(View.MeasureSpec.makeMeasureSpec((int) (W * 0.9f), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.AT_MOST));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        save(b, name);
        d.dismiss();
    }

    void save(Bitmap b, String name) {
        try (FileOutputStream o = new FileOutputStream(OUT + "/audit_" + tag() + "_" + name + ".png")) { b.compress(Bitmap.CompressFormat.PNG, 100, o); } catch (Exception e) { }
    }

    void screen(String name) { settle(); shot("audit_" + tag() + "_" + name); }

    @Test public void walk_every_screen() {
        launch();
        call(act, "showGallery"); settle();
        screen("01_gallery");
        closeGallery();
        W = cv.getWidth(); H = cv.getHeight();
        stroke(new float[][]{{W * .3f, H * .4f}, {W * .5f, H * .35f}, {W * .7f, H * .45f}}, MotionEvent.TOOL_TYPE_STYLUS, 1);
        screen("02_canvas");
        call(act, "togglePanel"); screen("03_layers"); call(act, "togglePanel");
        View top = (View) get(act, "brushBtn");
        for (int tab = 0; tab < 6; tab++) { call(act, "actionsMenu", cv, tab); settle(); popShot("04_actions_" + tab); }
        call(act, "pickBrush"); settle(); popShot("05_brush_library");
        call(act, "brushStudio", cv.brush); screen("06_brush_studio"); call(act, "closeStudio");
        call(act, "pickGrey"); settle(); popShot("07_colour");
        call(act, "adjustMenu", get(act, "adjustBtn")); settle(); popShot("08_adjust_menu");
        Object[] adjs = (Object[]) call(act, "adjustments");
        call(act, "startAdjust", adjs[0]); screen("09_adjust_bar"); call(act, "finishAdjust", false);
        call(act, "setTool", CanvasView.SELECT); screen("10_select_bar");
        call(act, "setTool", CanvasView.MOVE); screen("11_transform_bar");
        call(act, "setTool", CanvasView.DRAW);
        call(act, "startLiquify"); screen("12_liquify_bar"); call(act, "setTool", CanvasView.DRAW);
        call(act, "guideEditor"); settle(); popShot("13_guide"); dialogShot("13_guide_dialog");
        call(act, "toggleAnimation"); screen("14_animation"); call(act, "toggleAnimation");
        call(act, "togglePages"); screen("15_pages"); call(act, "togglePages");
        call(act, "textDialog"); settle(); dialogShot("16_text");
        call(act, "canvasDialog"); settle(); dialogShot("17_canvas_size");
        call(act, "startCrop"); screen("18_crop"); call(act, "setTool", CanvasView.DRAW);
        call(act, "quickMenu", W / 2f, H / 2f); screen("19_quickmenu");
    }
}
