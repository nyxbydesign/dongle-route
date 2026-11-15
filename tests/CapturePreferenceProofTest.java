import dev.usbmic.inspector.shell.CaptureRoleMap;
import dev.usbmic.inspector.shell.CapturePreferenceProof;
import dev.usbmic.inspector.shell.RoutingEngine;
import java.util.Collections;
import java.util.List;

/** Synthetic native dump fixtures, never private phone dumps. */
public final class CapturePreferenceProofTest {
    private static int checks;
    private static final String EMPTY = "Audio Policy\n  Device role per capture preset dump:\n\n  Volume Groups dump:\n    -media (id: 1)\n";
    private static final List<RoutingEngine.Device> NONE = Collections.emptyList();
    private static final List<RoutingEngine.Device> MIC = Collections.singletonList(new RoutingEngine.Device(1, 15, "bottom"));
    private static final CapturePreferenceProof.Read ERROR = new CapturePreferenceProof.Read(1, NONE);
    public static void main(String[] args) {
        CaptureRoleMap map = CaptureRoleMap.parse(EMPTY);
        check(map.absent(1, 1) && map.absent(7, 1), "recognized empty complete section proves no map entry");
        check(CaptureRoleMap.parse(EMPTY.replace("\n", "\r\n")).equals(map), "CRLF source-equivalent section accepted");
        check(CaptureRoleMap.parse("  Device role per capture preset dump:\n\n  Volume Groups dump:").equals(map), "empty map followed by empty volume groups is complete");
        String populated = EMPTY.replace("dump:\n\n", "dump:\n    Capture preset(7) Device Role(1) Devices(AUDIO_DEVICE_IN_BUILTIN_MIC, @:bottom)\n\n");
        CaptureRoleMap other = CaptureRoleMap.parse(populated);
        check(!other.absent(7, 1) && other.absent(1, 1), "presence is retained without interpreting device vectors");
        check(CapturePreferenceProof.resolve(1, ERROR, NONE, ERROR, map, map).isNone(), "generic JNI error plus independent complete bracketed absence proof establishes NONE");
        CapturePreferenceProof.Read positive = new CapturePreferenceProof.Read(0, MIC);
        check(CapturePreferenceProof.resolve(1, positive, MIC, positive, null, null).devices.equals(MIC), "positive JNI and Java attributes agree without dump fallback");
        refuses(() -> CapturePreferenceProof.resolve(1, ERROR, NONE, ERROR, null, null));
        refuses(() -> CapturePreferenceProof.resolve(1, ERROR, NONE, ERROR, map, null));
        refuses(() -> CapturePreferenceProof.resolve(1, ERROR, NONE, ERROR, map, other));
        refuses(() -> CapturePreferenceProof.resolve(7, ERROR, NONE, ERROR, other, other));
        refuses(() -> CapturePreferenceProof.resolve(1, new CapturePreferenceProof.Read(0, NONE), NONE, new CapturePreferenceProof.Read(0, NONE), map, map));
        refuses(() -> CapturePreferenceProof.resolve(1, new CapturePreferenceProof.Read(-2, NONE), NONE, new CapturePreferenceProof.Read(-2, NONE), map, map));
        refuses(() -> CapturePreferenceProof.resolve(1, new CapturePreferenceProof.Read(100, NONE), NONE, new CapturePreferenceProof.Read(100, NONE), map, map));
        refuses(() -> CapturePreferenceProof.resolve(1, positive, NONE, positive, map, map));
        refuses(() -> CapturePreferenceProof.resolve(1, positive, MIC, ERROR, map, map));
        refuses(() -> CapturePreferenceProof.resolve(1, new CapturePreferenceProof.Read(1, MIC), MIC, new CapturePreferenceProof.Read(1, MIC), map, map));
        refuses(() -> CaptureRoleMap.parse(""));
        refuses(() -> CaptureRoleMap.parse(EMPTY.substring(0, EMPTY.indexOf("  Volume"))));
        refuses(() -> CaptureRoleMap.parse(EMPTY.replace("\n\n", "\n")));
        refuses(() -> CaptureRoleMap.parse(EMPTY + EMPTY));
        refuses(() -> CaptureRoleMap.parse(EMPTY.replace("\n\n", "\nunknown entry\n\n")));
        refuses(() -> CaptureRoleMap.parse(populated.replace("    Capture", "   Capture")));
        refuses(() -> CaptureRoleMap.parse(populated.replace("preset(7)", "preset(07)")));
        refuses(() -> CaptureRoleMap.parse(populated.replace("Role(1)", "Role(3)")));
        refuses(() -> CaptureRoleMap.parse(populated.replace("preset(7)", "preset(4294967296)")));
        refuses(() -> CaptureRoleMap.parse(populated.replace("\n\n", "\n    Capture preset(7) Device Role(1) Devices(other)\n\n")));
        refuses(() -> CaptureRoleMap.parse("Permission Denial\n" + EMPTY));
        refuses(() -> CaptureRoleMap.parse(EMPTY + "DUMP TIMEOUT"));
        refuses(() -> CaptureRoleMap.parse(EMPTY + "\0"));
        refuses(() -> CaptureRoleMap.parse(String.join("", Collections.nCopies(300000, "x"))));
        System.out.println("PASS: " + checks + " native capture preference proof checks; synthetic fixtures only");
    }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void refuses(Runnable operation) {
        checks++; try { operation.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Unknown native proof must be refused");
    }
}
