package dev.usbmic.inspector.automation;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import dev.usbmic.inspector.shell.ShellContextBootstrap;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Framework parcel regression. System-role field changes exist only in this isolated test fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class UsbParcelBootstrapTest {
    @Test public void freshBootstrapCreatesResourceOnlyShellContextWithoutPublishingSystemThread() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object previous = ReflectionHelpers.getStaticField(activityThread, "sCurrentActivityThread");
        PackageInfo shell = new PackageInfo(); shell.packageName = "com.android.shell";
        shell.applicationInfo = new ApplicationInfo(RuntimeEnvironment.getApplication().getApplicationInfo());
        shell.applicationInfo.packageName = shell.packageName; shell.applicationInfo.uid = 2000;
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(shell);
        try {
            ReflectionHelpers.setStaticField(activityThread, "sCurrentActivityThread", null);
            Context system = ShellContextBootstrap.systemContext();
            Context shellContext = system.createPackageContext("com.android.shell", 0);
            assertEquals("com.android.shell", shellContext.getPackageName());
            assertEquals(2000, shellContext.getApplicationInfo().uid);
            assertNotNull(shellContext.getResources());
            assertNull(ReflectionHelpers.getStaticField(activityThread, "sCurrentActivityThread"));
            assertEquals(false, activityThread.getMethod("isSystem").invoke(null));
            assertEquals(0x1234, remoteReaderDevice().getVendorId());
        } finally { ReflectionHelpers.setStaticField(activityThread, "sCurrentActivityThread", previous); }
    }
    @Test public void existingSystemThreadIsRefusedWithoutChangingItsRole() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
        Object previousCurrent = ReflectionHelpers.getStaticField(activityThread, "sCurrentActivityThread");
        boolean previousRole = ReflectionHelpers.getField(thread, "mSystemThread");
        try {
            ReflectionHelpers.setStaticField(activityThread, "sCurrentActivityThread", thread);
            ReflectionHelpers.setField(thread, "mSystemThread", true);
            try { ShellContextBootstrap.systemContext(); fail("An existing system-role process must remain blocked"); }
            catch (IllegalStateException expected) { }
            assertSame(thread, ReflectionHelpers.getStaticField(activityThread, "sCurrentActivityThread"));
            assertEquals(true, activityThread.getMethod("isSystem").invoke(null));
        } finally {
            ReflectionHelpers.setField(thread, "mSystemThread", previousRole);
            ReflectionHelpers.setStaticField(activityThread, "sCurrentActivityThread", previousCurrent);
        }
    }
    @Test public void remoteSerialReaderParcelFailsOnlyWithSystemThreadRole() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
        assertNotNull(thread);
        Object previousCurrent = ReflectionHelpers.getStaticField(activityThread, "sCurrentActivityThread");
        boolean previous = ReflectionHelpers.getField(thread, "mSystemThread");
        try {
            ReflectionHelpers.setStaticField(activityThread, "sCurrentActivityThread", thread);
            ReflectionHelpers.setField(thread, "mSystemThread", false);
            UsbDevice device = remoteReaderDevice();
            Field serialReader = UsbDevice.class.getDeclaredField("mSerialNumberReader"); serialReader.setAccessible(true);
            assertFalse(Class.forName("android.hardware.usb.IUsbSerialReader$Stub").isInstance(serialReader.get(device)));
            Parcel response = Parcel.obtain();
            try {
                Bundle bundle = new Bundle(); bundle.putParcelable("fixture", device); bundle.writeToParcel(response, 0);
                ReflectionHelpers.setField(thread, "mSystemThread", true);
                assertEquals(true, activityThread.getMethod("isSystem").invoke(null));
                try { readBundle(response); fail("System-role process must reject a remote serial reader"); }
                catch (IllegalArgumentException expected) { }
                ReflectionHelpers.setField(thread, "mSystemThread", false);
                assertEquals(false, activityThread.getMethod("isSystem").invoke(null));
                UsbDevice restored = readBundle(response);
                assertEquals(0x1234, restored.getVendorId()); assertEquals(0x5678, restored.getProductId());
                assertEquals("/dev/bus/usb/fixture", restored.getDeviceName());
            } finally { response.recycle(); }
        } finally {
            ReflectionHelpers.setField(thread, "mSystemThread", previous);
            ReflectionHelpers.setStaticField(activityThread, "sCurrentActivityThread", previousCurrent);
        }
    }

    private static UsbDevice readBundle(Parcel parcel) {
        parcel.setDataPosition(0);
        Bundle restored = Bundle.CREATOR.createFromParcel(parcel);
        return restored.getParcelable("fixture", UsbDevice.class);
    }
    private static UsbDevice remoteReaderDevice() {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeString("/dev/bus/usb/fixture"); parcel.writeInt(0x1234); parcel.writeInt(0x5678);
            parcel.writeInt(0); parcel.writeInt(0); parcel.writeInt(0);
            parcel.writeString("Fixture"); parcel.writeString("Fixture DAC"); parcel.writeString("1.0");
            // Unattached Binder has no local serial-reader interface, so asInterface creates its proxy.
            parcel.writeStrongBinder(new Binder());
            parcel.writeParcelableArray(new UsbConfiguration[0], 0);
            parcel.writeInt(1); parcel.writeInt(1); parcel.writeInt(0); parcel.writeInt(0); parcel.writeInt(0);
            parcel.setDataPosition(0);
            return UsbDevice.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }
}
