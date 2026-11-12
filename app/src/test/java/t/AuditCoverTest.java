package t;

import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Pixel Fold screen: Cover. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w411dp-h797dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AuditCoverTest extends AuditTest {
    String tag() { return "Cover"; }
}
