package dev.usbmic.inspector.shell;

import java.util.List;
import java.util.ArrayList;

/** JNI generic errors never establish absence without two complete, consistent native maps. */
public final class CapturePreferenceProof {
    private CapturePreferenceProof() { }
    public static final class Read {
        public final int status;
        public final List<RoutingEngine.Device> devices;
        public Read(int status, List<RoutingEngine.Device> devices) {
            if (devices == null || devices.size() > 16 || devices.contains(null)) throw invalid();
            if (!devices.isEmpty()) RoutingEngine.Preference.devices(devices);
            this.status = status; this.devices = new ArrayList<>(devices);
        }
        public boolean agrees(Read other) { return status == other.status && devices.equals(other.devices); }
    }
    public static RoutingEngine.Preference resolve(int preset, Read before, List<RoutingEngine.Device> javaDevices,
                                                   Read after, CaptureRoleMap mapBefore, CaptureRoleMap mapAfter) {
        if (!before.agrees(after) || !before.devices.equals(javaDevices)) throw invalid();
        if (before.status == 0 && !before.devices.isEmpty()) return RoutingEngine.Preference.devices(before.devices);
        if (before.status == 1 && before.devices.isEmpty() && mapBefore != null && mapBefore.equals(mapAfter)
                && mapBefore.absent(preset, 1)) return RoutingEngine.Preference.none();
        throw invalid();
    }
    private static IllegalStateException invalid() { return new IllegalStateException("Capture preference proof unavailable or inconsistent"); }
}
