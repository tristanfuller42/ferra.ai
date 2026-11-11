package t;

import ai.inkwell.*;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;

/** Helpers shared by the tests: launching, waiting, touching, screenshots, reflection. */
public abstract class Base {
    static final String OUT = System.getProperty("shots", System.getProperty("java.io.tmpdir"));
    protected ActivityController<MainActivity> ctl;
    protected MainActivity act;
    protected CanvasView cv;

    protected void launch() {
        ctl = Robolectric.buildActivity(MainActivity.class).setup();
        act = ctl.get();
        settle();
        cv = (CanvasView) get(act, "canvas");
    }

    /** Lets background threads finish and runs posted UI work. */
    protected void settle() {
        for (int i = 0; i < 40; i++) {
            ShadowLooper.idleMainLooper();
            try { Thread.sleep(25); } catch (InterruptedException e) { }
        }
        ShadowLooper.idleMainLooper();
    }

    protected void closeGallery() {
        View g = (View) get(act, "gallery");
        if (g != null) g.setVisibility(View.GONE);
        settle();
    }

    static Object get(Object o, String f) {
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                try { Field x = c.getDeclaredField(f); x.setAccessible(true); return x.get(o); }
                catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
            throw new RuntimeException("no field " + f);
        } catch (IllegalAccessException e) { throw new RuntimeException(e); }
    }

    static void set(Object o, String f, Object v) {
        try { Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); x.set(o, v); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static Object call(Object o, String name, Object... args) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass())
            for (Method m : c.getDeclaredMethods())
                if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                    try { m.setAccessible(true); return m.invoke(o, args); }
                    catch (java.lang.reflect.InvocationTargetException e) {
                        Throwable t = e.getCause();
                        if (t instanceof RuntimeException) throw (RuntimeException) t;
                        throw new RuntimeException(t);
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
        throw new RuntimeException("no method " + name + "/" + args.length);
    }

    // ---- touches (screen coordinates inside the canvas view) ----
    long t0 = SystemClock.uptimeMillis();

    protected MotionEvent ev(int action, float[][] pts, int toolType, float pressure) {
        int n = pts.length;
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            pp[i] = new MotionEvent.PointerProperties(); pp[i].id = i; pp[i].toolType = toolType;
            pc[i] = new MotionEvent.PointerCoords(); pc[i].x = pts[i][0]; pc[i].y = pts[i][1]; pc[i].pressure = pressure; pc[i].size = 1;
        }
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(t0, now, action, n, pp, pc, 0, 0, 1, 1, 0, 0, 0, 0);
    }

    protected void touch(View v, MotionEvent e) { v.dispatchTouchEvent(e); e.recycle(); }

    /** One-finger (or stylus) stroke through screen points. */
    protected void stroke(float[][] path, int tool, float pressure) {
        t0 = SystemClock.uptimeMillis();
        touch(cv, ev(MotionEvent.ACTION_DOWN, new float[][]{path[0]}, tool, pressure));
        for (int i = 1; i < path.length; i++) {
            ShadowLooper.idleMainLooper(8, java.util.concurrent.TimeUnit.MILLISECONDS);
            touch(cv, ev(MotionEvent.ACTION_MOVE, new float[][]{path[i]}, tool, pressure));
        }
        touch(cv, ev(MotionEvent.ACTION_UP, new float[][]{path[path.length - 1]}, tool, pressure));
        ShadowLooper.idleMainLooper();
    }

    protected void line(float x0, float y0, float x1, float y1) {
        int n = 20;
        float[][] p = new float[n + 1][];
        for (int i = 0; i <= n; i++) p[i] = new float[]{x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n};
        stroke(p, MotionEvent.TOOL_TYPE_FINGER, 1f);
    }

    /** Screen point -> canvas pixel. */
    protected int[] toDoc(float sx, float sy) {
        android.graphics.Matrix v = (android.graphics.Matrix) get(cv, "view"), inv = new android.graphics.Matrix();
        v.invert(inv);
        float[] p = {sx, sy};
        inv.mapPoints(p);
        return new int[]{(int) p[0], (int) p[1]};
    }

    protected int alphaAt(Bitmap b, float sx, float sy) {
        int[] d = toDoc(sx, sy);
        return b.getPixel(Math.max(0, Math.min(b.getWidth() - 1, d[0])), Math.max(0, Math.min(b.getHeight() - 1, d[1]))) >>> 24;
    }

    protected long inkSum(Bitmap b) {
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        long s = 0;
        for (int p : px) s += p >>> 24;
        return s;
    }

    protected void shot(String name) {
        View root = act.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
