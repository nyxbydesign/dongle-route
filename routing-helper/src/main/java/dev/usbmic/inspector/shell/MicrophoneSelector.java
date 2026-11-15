package dev.usbmic.inspector.shell;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Resolves input ports to canonical routing identities; a port ID alone cannot disambiguate equal addresses. */
public final class MicrophoneSelector {
    private MicrophoneSelector() { }

    public static final class Port {
        public final int id;
        public final RoutingEngine.Device device;
        public Port(int id, RoutingEngine.Device device) { this.id = id; this.device = device; }
    }

    public static Port choose(List<Port> builtInPorts, Integer selectedId) {
        if (!valid(builtInPorts)) return null;
        if (selectedId != null) {
            Port selected = null;
            for (Port port : builtInPorts) if (port.id == selectedId) selected = port;
            return selected == null ? null : resolve(builtInPorts, selected.device);
        }
        Port bottom = null;
        int bottoms = 0;
        for (Port port : builtInPorts) {
            if ("bottom".equals(port.device.address)) { bottoms++; bottom = port; }
        }
        if (bottoms == 1) return bottom;
        return builtInPorts.size() == 1 ? builtInPorts.get(0) : null;
    }

    /** Existing ownership follows its saved canonical identity, even if enumeration IDs change. */
    public static Port resolve(List<Port> builtInPorts, RoutingEngine.Device canonical) {
        if (!valid(builtInPorts) || canonical == null || canonical.role != RoutingEngine.INPUT_ROLE
                || canonical.type != RoutingEngine.MIC_TYPE) return null;
        Port resolved = null;
        int count = 0;
        for (Port port : builtInPorts) if (canonical.equals(port.device)) { resolved = port; count++; }
        return count == 1 ? resolved : null;
    }

    private static boolean valid(List<Port> ports) {
        if (ports == null || ports.isEmpty()) return false;
        Set<Integer> ids = new HashSet<>();
        for (Port port : ports) {
            if (port == null || port.id <= 0 || !ids.add(port.id) || port.device == null
                    || port.device.role != RoutingEngine.INPUT_ROLE || port.device.type != RoutingEngine.MIC_TYPE) return false;
        }
        return true;
    }
}
