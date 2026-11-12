package t;

import app.inkwell.*;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Random taps, drags, pinches and button presses all over the app: nothing may crash. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w411dp-h797dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MonkeyTest extends Base {
    final Random r = new Random(Long.getLong("monkeySeed", 42));

    List<View> clickables(View v, List<View> out) {
        if (v.getVisibility() != View.VISIBLE) return out;
        if (v.isClickable() && v.isEnabled() && v.getWidth() > 0) out.add(v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) clickables(((ViewGroup) v).getChildAt(i), out);
        return out;
    }

    MotionEvent e(int action, float[][] p, int tool) {
        int n = p.length;
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            pp[i] = new MotionEvent.PointerProperties(); pp[i].id = i; pp[i].toolType = tool;
            pc[i] = new MotionEvent.PointerCoords(); pc[i].x = p[i][0]; pc[i].y = p[i][1]; pc[i].pressure = 0.2f + r.nextFloat() * 0.8f; pc[i].size = 1;
        }
        return MotionEvent.obtain(t0, android.os.SystemClock.uptimeMillis(), action, n, pp, pc, 0, 0, 1, 1, 0, 0, 0, 0);
    }

    /** A random gesture with 1-3 fingers (or a pen) through the whole window. */
    void gesture(View root) {
        int w = root.getWidth(), h = root.getHeight();
        int n = r.nextInt(10) < 7 ? 1 : 2 + r.nextInt(2);
        int tool = n == 1 && r.nextBoolean() ? MotionEvent.TOOL_TYPE_STYLUS : MotionEvent.TOOL_TYPE_FINGER;
        float[][] p = new float[n][];
        for (int i = 0; i < n; i++) p[i] = new float[]{r.nextFloat() * w, r.nextFloat() * h};
        t0 = android.os.SystemClock.uptimeMillis();
        root.dispatchTouchEvent(e(MotionEvent.ACTION_DOWN, new float[][]{p[0]}, tool));
        for (int i = 1; i < n; i++) root.dispatchTouchEvent(e(MotionEvent.ACTION_POINTER_DOWN | (i << MotionEvent.ACTION_POINTER_INDEX_SHIFT), Arrays.copyOf(p, i + 1), tool));
        int steps = r.nextInt(12);
        for (int s = 0; s < steps; s++) {
            for (float[] q : p) { q[0] += (r.nextFloat() - 0.5f) * 80; q[1] += (r.nextFloat() - 0.5f) * 80; }
            ShadowLooper.idleMainLooper(r.nextInt(3) == 0 ? 300 : 12, TimeUnit.MILLISECONDS);
            root.dispatchTouchEvent(e(MotionEvent.ACTION_MOVE, p, tool));
        }
        for (int i = n - 1; i >= 1; i--) root.dispatchTouchEvent(e(MotionEvent.ACTION_POINTER_UP | (i << MotionEvent.ACTION_POINTER_INDEX_SHIFT), Arrays.copyOf(p, i + 1), tool));
        root.dispatchTouchEvent(e(MotionEvent.ACTION_UP, new float[][]{p[0]}, tool));
    }

    @Test public void two_thousand_random_actions_do_not_crash() {
        launch();
        closeGallery();
        View root = act.getWindow().getDecorView();
        String[] safe = {"togglePanel", "pickBrush", "pickGrey", "toggleAnimation", "togglePages", "startCrop", "startLiquify", "showGallery", "undoBtn"};
        int[] tools = {CanvasView.DRAW, CanvasView.ERASE, CanvasView.SMUDGE, CanvasView.SELECT, CanvasView.MOVE};
        for (int i = 0; i < 2000; i++) {
            try {
                int k = r.nextInt(100);
                if (k < 55) gesture(root);
                else if (k < 75) { // tap a random button on screen (dialogs and file pickers are closed straight away)
                    List<View> cs = clickables(root, new ArrayList<>());
                    if (!cs.isEmpty()) cs.get(r.nextInt(cs.size())).performClick();
                } else if (k < 85) call(act, "setTool", tools[r.nextInt(tools.length)]);
                else if (k < 90) { if (r.nextBoolean()) cv.undo(); else cv.redo(); }
                else if (k < 95) { String m = safe[r.nextInt(safe.length)]; if (!m.equals("undoBtn")) call(act, m); }
                else act.onBackPressed();
                ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS);
                android.app.Dialog d = org.robolectric.shadows.ShadowDialog.getLatestDialog();
                if (d != null && d.isShowing() && r.nextInt(3) > 0) d.dismiss();
                if (((View) get(act, "gallery")) != null && ((View) get(act, "gallery")).getVisibility() == View.VISIBLE && r.nextInt(4) == 0) closeGallery();
                if (act.isFinishing()) { launch(); root = act.getWindow().getDecorView(); }
            } catch (Throwable t) {
                Throwable c = t;
                while (c.getCause() != null && (c instanceof RuntimeException) && c.getCause() != c) c = c.getCause();
                throw new AssertionError("crash at step " + i + ": " + c, c);
            }
        }
        assertNotNull(cv);
    }
}
