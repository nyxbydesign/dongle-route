package dev.usbmic.inspector.automation;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual framework reflection shape and real fixed-command builder; no policy calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class RoutingBridgeContractTest {
    @Test public void namedPlatformJniContractExistsWithoutGeneratedNativeAidl() throws Exception {
        Class<?> system = Class.forName("android.media.AudioSystem");
        Method getter = system.getMethod("getDevicesForRoleAndCapturePreset", int.class, int.class, List.class);
        assertEquals(int.class, getter.getReturnType()); assertTrue(Modifier.isStatic(getter.getModifiers()));
        assertEquals(1, system.getField("DEVICE_ROLE_PREFERRED").getInt(null));
        assertEquals(1, system.getField("AUDIO_STATUS_ERROR").getInt(null));
        Class<?> service = Class.forName("android.media.IAudioService");
        assertEquals(List.class, service.getMethod("getPreferredDevicesForCapturePreset", int.class).getReturnType());
        assertEquals(int.class, service.getMethod("setPreferredDevicesForCapturePreset", int.class, List.class).getReturnType());
        assertEquals(int.class, service.getMethod("clearPreferredDevicesForCapturePreset", int.class).getReturnType());
    }
    @Test public void explicitTickAndCanonicalMonitorUseDifferentFixedCommands() throws Exception {
        assertTrue(RoutingBridge.command(RoutingBridge.Action.TICK, 1, 2, 100).endsWith(" tick 1 2 100"));
        assertTrue(RoutingBridge.command(RoutingBridge.Action.TICK, 1, 2, -1).endsWith(" tick-retained 1 2"));
        assertTrue(RoutingBridge.command(RoutingBridge.Action.STOP, -1, -1, -1).endsWith(" stop"));
        assertTrue(RoutingBridge.command(RoutingBridge.Action.STATUS, -1, -1, -1).endsWith(" status 0 0"));
        assertTrue(RoutingBridge.command(RoutingBridge.Action.CHECK, -1, -1, -1).endsWith(" check"));
        assertTrue(RoutingBridge.command(RoutingBridge.Action.CHECK, 1, 2, 100).endsWith(" check 1 2 100"));
        try { RoutingBridge.command(RoutingBridge.Action.TICK, -1, -1, -1); fail(); } catch (IOException expected) { }
        try { RoutingBridge.command(RoutingBridge.Action.DEFAULTS, 1, 2, -1); fail(); } catch (IOException expected) { }
    }
}
