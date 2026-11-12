package t;

import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Pixel Fold screen: InnerPort. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w767dp-h920dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AuditInnerPortTest extends AuditTest {
    String tag() { return "InnerPort"; }
}
