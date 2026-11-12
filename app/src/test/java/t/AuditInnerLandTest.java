package t;

import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Pixel Fold screen: InnerLand. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w920dp-h767dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AuditInnerLandTest extends AuditTest {
    String tag() { return "InnerLand"; }
}
