package t;
public class CanvasViewAccess {
    static byte[] inflate(byte[] d, int n) {
        try { java.lang.reflect.Method m = app.inkwell.CanvasView.class.getDeclaredMethod("inflate", byte[].class, int.class); m.setAccessible(true); return (byte[]) m.invoke(null, d, n); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
