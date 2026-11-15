package dev.usbmic.inspector.automation;

import android.content.Context;
import android.os.Looper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises real serialized work/callback ordering with a fake transport, never a socket. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ConnectionOutcomeTest {
    @Test public void lateFailureDoesNotInvalidateNewerTransportOrOutcome() throws Exception {
        ControlledSession session = new ControlledSession(RuntimeEnvironment.getApplication());
        try {
            session.connect(1, (result, error) -> { }); awaitWorker(session);
            assertEquals(1, session.losses);
            // Keep the old failure callback queued while the newer worker completes.
            session.connect(2, (result, error) -> { }); awaitWorker(session);
            assertTrue(session.connected);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(session.connected); assertEquals(1, session.losses);
            assertEquals("Session workers never publish a UI connection outcome before the owner guard", 0, session.connectionResultVersion);
        } finally {
            Field worker = AutomationSession.class.getDeclaredField("worker"); worker.setAccessible(true);
            ((ExecutorService) worker.get(session)).shutdownNow();
        }
    }
    private void awaitWorker(AutomationSession session) throws Exception {
        long until = System.nanoTime() + 2_000_000_000L;
        while (session.isBusy() && System.nanoTime() < until) Thread.sleep(2);
        assertFalse("Controlled worker completes", session.isBusy());
    }
    private static final class ControlledSession extends AutomationSession {
        volatile boolean connected;
        volatile int losses;
        ControlledSession(Context context) { super(context); }
        @Override void connectTransport(int port) throws IOException { if (port == 1) throw new IOException("controlled failure"); connected = true; }
        @Override void markConnectionLost() { losses++; connected = false; }
        @Override boolean isConnected() { return connected; }
    }
}
