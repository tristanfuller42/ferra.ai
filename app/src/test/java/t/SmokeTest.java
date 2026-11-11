package t;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w841dp-h701dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SmokeTest extends Base {
    @Test public void screens() {
        launch();
        shot("01_gallery");
        closeGallery();
        assertTrue(cv.getWidth() > 0);
        line(cv.getWidth() * 0.3f, cv.getHeight() * 0.3f, cv.getWidth() * 0.7f, cv.getHeight() * 0.6f);
        shot("02_drawn");
        act.findViewById(android.R.id.content);
        call(act, "togglePanel");
        settle();
        shot("03_layers");
    }
}
