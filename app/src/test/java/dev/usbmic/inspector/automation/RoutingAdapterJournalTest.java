package dev.usbmic.inspector.automation;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.system.StructStat;
import dev.usbmic.inspector.shell.RoutingEngine;
import dev.usbmic.inspector.shell.RoutingFailure;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.ShadowProcess;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Actual helper adapters/codec against framework objects and shadows; no kernel or policy writes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class RoutingAdapterJournalTest {
    private static final String HELPER = "dev.usbmic.inspector.shell.InputRoutingHelper";
    @Test public void androidInventoryAdapterUsesFrameworkAudioAndUsbObjects() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Context permission = new ContextWrapper(app) {
            @Override public int checkPermission(String name, int pid, int uid) { return PackageManager.PERMISSION_GRANTED; }
        };
        AudioManager audio = app.getSystemService(AudioManager.class);
        UsbManager usb = app.getSystemService(UsbManager.class);
        AudioDeviceInfo mic = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build();
        Object port = ReflectionHelpers.getField(mic, "mPort");
        Object handle = ReflectionHelpers.getField(port, "mHandle");
        ReflectionHelpers.setField(handle, "mId", 10); ReflectionHelpers.setField(port, "mAddress", "bottom");
        ReflectionHelpers.setField(port, "mRole", 1); // AudioPort ROLE_SOURCE
        ReflectionHelpers.setField(port, "mType", Class.forName("android.media.AudioSystem").getField("DEVICE_IN_BUILTIN_MIC").getInt(null));
        assertEquals(10, mic.getId()); assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, mic.getType());
        assertEquals("bottom", mic.getAddress()); assertTrue(mic.isSource()); assertFalse(mic.isSink());
        shadowOf(audio).setInputDevices(Collections.singletonList(mic));
        shadowOf(audio).setOutputDevices(Collections.emptyList());
        Class<?> type = Class.forName(HELPER + "$AndroidBackend");
        Constructor<?> constructor = type.getDeclaredConstructor(Context.class, AudioManager.class, UsbManager.class); constructor.setAccessible(true);
        Object adapter = constructor.newInstance(permission, audio, usb);
        Method environment = type.getDeclaredMethod("environment", RoutingEngine.Selection.class, RoutingEngine.Device.class, Integer.class); environment.setAccessible(true);
        int uid = android.os.Process.myUid(); ShadowProcess.setUid(2000);
        try {
            Object result = environment.invoke(adapter, new RoutingEngine.Selection(1, 2), null, 10);
            assertEquals(new RoutingEngine.Device(1, 15, "bottom"), field(result, "bottom"));
            assertEquals(false, field(result, "attached")); assertEquals(true, field(result, "safe"));
            assertEquals("absent", field(result, "key"));
            Context refused = new ContextWrapper(app) {
                @Override public int checkPermission(String name, int pid, int uid) { return PackageManager.PERMISSION_DENIED; }
            };
            try { environment.invoke(constructor.newInstance(refused, audio, usb), null, null, 10); fail(); }
            catch (InvocationTargetException expected) {
                assertEquals("PERMISSION (SecurityException)", RoutingFailure.describe(expected.getCause(), RoutingFailure.Stage.COMMAND));
            }
        } finally { ShadowProcess.setUid(uid); }
    }
    @Test public void actualJournalCodecRoundTripsAndRejectsUnsafeSchema() throws Exception {
        Class<?> journal = Class.forName(HELPER + "$Journal");
        Method encode = journal.getDeclaredMethod("encode", RoutingEngine.Session.class); encode.setAccessible(true);
        Method decode = journal.getDeclaredMethod("decode", JSONObject.class); decode.setAccessible(true);
        RoutingEngine.Session session = new RoutingEngine.Session();
        session.phase = RoutingEngine.APPLYING; session.selection = new RoutingEngine.Selection(1, 2);
        session.bottom = new RoutingEngine.Device(1, 15, "bottom"); session.attachmentKey = "synthetic-generation";
        session.desired = RoutingEngine.Preference.of(session.bottom);
        session.originals[0] = RoutingEngine.Preference.none(); session.originals[1] = RoutingEngine.Preference.of(new RoutingEngine.Device(1, 15, "rear"));
        session.steps[0] = RoutingEngine.APPLY_INTENT; session.steps[1] = RoutingEngine.NEW;
        JSONObject encoded = (JSONObject) encode.invoke(null, session);
        RoutingEngine.Session restored = (RoutingEngine.Session) decode.invoke(null, new JSONObject(encoded.toString()));
        assertEquals(session.bottom, restored.bottom); assertEquals(session.desired, restored.desired);
        assertEquals(session.originals[1], restored.originals[1]); assertEquals(1, restored.owned()); assertTrue(restored.pending());
        refusedDecode(decode, new JSONObject(encoded.toString()).put("version", 2));
        refusedDecode(decode, new JSONObject(encoded.toString()).put("phase", "unknown"));
        JSONObject badTarget = new JSONObject(encoded.toString()); badTarget.getJSONObject("bottom").put("type", 22); refusedDecode(decode, badTarget);
        JSONObject badDesired = new JSONObject(encoded.toString()); badDesired.getJSONObject("desired").put("none", true); refusedDecode(decode, badDesired);
        JSONObject badStep = new JSONObject(encoded.toString()); badStep.getJSONArray("steps").put(0, 9); refusedDecode(decode, badStep);
    }
    @Test public void actualMetadataGuardsRejectLinksWrongOwnerModeAndType() throws Exception {
        Class<?> helper = Class.forName(HELPER);
        Method directory = helper.getDeclaredMethod("verifyDirectoryStat", StructStat.class); directory.setAccessible(true);
        Method regular = helper.getDeclaredMethod("verifyRegularStat", StructStat.class); regular.setAccessible(true);
        directory.invoke(null, stat(OsConstants.S_IFDIR | 0700, 2000, 1));
        regular.invoke(null, stat(OsConstants.S_IFREG | 0600, 2000, 1));
        refusedStat(directory, stat(OsConstants.S_IFDIR | 0755, 2000, 1));
        refusedStat(directory, stat(OsConstants.S_IFDIR | 0700, 10000, 1));
        refusedStat(regular, stat(OsConstants.S_IFREG | 0600, 2000, 2));
        refusedStat(regular, stat(OsConstants.S_IFLNK | 0600, 2000, 1));
        refusedStat(regular, stat(OsConstants.S_IFREG | 0644, 2000, 1));
        assertEquals("JOURNAL_RENAME (ErrnoException errno=13)", RoutingFailure.describe(
                new ErrnoException("private path", 13), RoutingFailure.Stage.JOURNAL_RENAME));
    }
    private static StructStat stat(int mode, int uid, int links) { return new StructStat(1, 2, mode, links, uid, 2000, 0, 32, 0, 0, 0, 4096, 1); }
    private static Object field(Object value, String name) throws Exception { Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value); }
    private static void refusedDecode(Method method, JSONObject value) throws Exception {
        try { method.invoke(null, value); fail("Invalid journal must remain blocked"); } catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof Exception); }
    }
    private static void refusedStat(Method method, StructStat value) throws Exception {
        try { method.invoke(null, value); fail("Unsafe metadata must remain blocked"); } catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof SecurityException); }
    }
}
