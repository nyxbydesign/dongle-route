import dev.usbmic.inspector.shell.RoutingFailure;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

public final class RoutingFailureTest {
    public static void main(String[] args) {
        RoutingFailure failure = new RoutingFailure(RoutingFailure.Stage.NATIVE_CAPTURE_QUERY,
                new InvocationTargetException(new SecurityException("private address, path and key")));
        require(RoutingFailure.describe(failure, RoutingFailure.Stage.COMMAND).equals("NATIVE_CAPTURE_QUERY (SecurityException)"));
        require(RoutingFailure.at(RoutingFailure.Stage.ENGINE_OBSERVE, failure) == failure);
        require(RoutingFailure.describe(new IOException("private data"), RoutingFailure.Stage.JOURNAL_TEMP_WRITE)
                .equals("JOURNAL_TEMP_WRITE (IOException)"));
        require(RoutingFailure.describe(new PrivateDiagnostic(), RoutingFailure.Stage.ENGINE_PREPARE)
                .equals("ENGINE_PREPARE (Exception)"));
        System.out.println("PASS: 4 safe routing diagnostic checks");
    }
    private static final class PrivateDiagnostic extends Exception { }
    private static void require(boolean result) { if (!result) throw new AssertionError("Safe diagnostic contract failed"); }
}
