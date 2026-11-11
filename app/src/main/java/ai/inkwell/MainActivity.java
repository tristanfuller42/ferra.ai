package ai.inkwell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private CanvasView canvas;
    private TextView brushBtn, eraseBtn;
    private File autosave;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float dp = getResources().getDisplayMetrics().density;

        // Canvas = largest window this device can show (Fold unfolded), so it never needs resizing.
        Rect max = getWindowManager().getMaximumWindowMetrics().getBounds();
        int side = Math.max(max.width(), max.height());
        int other = Math.min(max.width(), max.height());
        canvas = new CanvasView(this, other, side); // portrait page
        autosave = new File(getFilesDir(), "autosave.bin");
        canvas.load(autosave);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Color.BLACK);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (4 * dp);
        bar.setPadding(pad, 0, pad, 0);

        brushBtn = button(bar, "Brush", v -> setErase(false));
        eraseBtn = button(bar, "Erase", v -> setErase(true));

        SeekBar sizeBar = new SeekBar(this);
        sizeBar.setMax(99);
        sizeBar.setProgress((int) canvas.size - 1);
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { canvas.size = p + 1; }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        bar.addView(sizeBar, new LinearLayout.LayoutParams((int) (140 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));

        button(bar, "Undo", v -> canvas.undo());
        button(bar, "Redo", v -> canvas.redo());
        button(bar, "Fit", v -> canvas.fit());
        button(bar, "Clear", v -> new AlertDialog.Builder(this)
                .setMessage("Clear the canvas? (Undo can bring it back.)")
                .setPositiveButton("Clear", (d, w) -> canvas.clear())
                .setNegativeButton("Cancel", null).show());
        button(bar, "Save", v -> export());
        setErase(false);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFillViewport(true);
        scroll.addView(bar, new FrameLayout.LayoutParams(-2, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, (int) (48 * dp)));
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.setOnApplyWindowInsetsListener((v, in) -> {
            Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
    }

    private TextView button(LinearLayout bar, String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        t.setPadding(p, 0, p, 0);
        t.setOnClickListener(l);
        bar.addView(t, new LinearLayout.LayoutParams(-2, -1));
        return t;
    }

    private void setErase(boolean e) {
        canvas.eraseMode = e;
        style(brushBtn, !e);
        style(eraseBtn, e);
    }

    private static void style(TextView t, boolean on) {
        t.setBackgroundColor(on ? Color.WHITE : Color.BLACK);
        t.setTextColor(on ? Color.BLACK : Color.WHITE);
    }

    private void export() {
        Bitmap bmp = canvas.export();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, "inkwell_" + System.currentTimeMillis() + ".png");
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Inkwell");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        String msg = "Save failed";
        if (uri != null) {
            try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
                msg = "Saved to Pictures/Inkwell";
            } catch (Exception ignored) { }
        }
        bmp.recycle();
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        canvas.save(autosave);
    }
}
