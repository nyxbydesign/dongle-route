package dev.usbmic.inspector.automation;

import android.os.IBinder;
import android.os.SystemClock;
import dev.usbmic.inspector.shell.BoundedBinderDump;
import dev.usbmic.inspector.shell.CaptureRoleMap;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Robolectric shadow-pipe API and fake standard Binder dump; no kernel or audio service access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class NativeCaptureDumpTest {
    private static final String EMPTY = "  Device role per capture preset dump:\n\n  Volume Groups dump:\n";
    @Test public void completePipeAndCallAreRequired() throws Exception {
        String result = BoundedBinderDump.read(binder(fd -> new FileOutputStream(fd).write(EMPTY.getBytes(StandardCharsets.UTF_8))), SystemClock.elapsedRealtime() + 1000);
        assertTrue(CaptureRoleMap.parse(result).absent(1, 1));
        refused(binder(fd -> { throw new SecurityException("private detail"); }));
        byte[] oversized = new byte[CaptureRoleMap.MAX_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) 'x');
        System.arraycopy(EMPTY.getBytes(StandardCharsets.UTF_8), 0, oversized, 0, EMPTY.length());
        refused(binder(fd -> new FileOutputStream(fd).write(oversized)));
        refused(binder(fd -> new FileOutputStream(fd).write(new byte[]{(byte) 0xc3, (byte) 0x28})));
    }
    @Test public void unfinishedDumpIsRejectedWithinBound() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try { refused(binder(fd -> { release.await(2, TimeUnit.SECONDS); })); }
        finally { release.countDown(); }
    }
    @Test public void loaderFailureKeepsStableStageAndRootCategory() throws Exception {
        Class<?> helper = Class.forName("dev.usbmic.inspector.shell.InputRoutingHelper");
        Class<?> unavailable = Class.forName(helper.getName() + "$BackendUnavailable");
        Constructor<?> constructor = unavailable.getDeclaredConstructor(String.class, Exception.class); constructor.setAccessible(true);
        Object failure = constructor.newInstance("AudioSystem capture role query", new ClassNotFoundException("private detail"));
        java.lang.reflect.Field stage = unavailable.getDeclaredField("stage"); stage.setAccessible(true);
        assertEquals("AudioSystem capture role query", stage.get(failure));
        Method rootCause = helper.getDeclaredMethod("rootCause", Throwable.class); rootCause.setAccessible(true);
        assertEquals(ClassNotFoundException.class, rootCause.invoke(null, failure).getClass());
    }
    private interface Dump { void write(FileDescriptor fd) throws Exception; }
    private static IBinder binder(Dump dump) {
        return (IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(), new Class<?>[]{IBinder.class}, (proxy, method, args) -> {
            if (method.getName().equals("dump")) { dump.write((FileDescriptor) args[0]); return null; }
            throw new AssertionError("Only standard Binder dump may be called");
        });
    }
    private static void refused(IBinder binder) throws Exception {
        try {
            CaptureRoleMap.parse(BoundedBinderDump.read(binder, SystemClock.elapsedRealtime() + 1000));
            fail("Incomplete native dump must remain unknown");
        } catch (IllegalArgumentException | IllegalStateException | java.nio.charset.CharacterCodingException expected) { }
    }
}
