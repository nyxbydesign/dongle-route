package dev.usbmic.inspector.shell;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognizes the complete AOSP engine capture-role section; unknown vendor formats fail closed. */
public final class CaptureRoleMap {
    public static final int MAX_BYTES = 262144;
    private static final String HEADER = "  Device role per capture preset dump:";
    private static final String NEXT = "  Volume Groups dump:";
    private static final Pattern ENTRY = Pattern.compile("    Capture preset\\(([0-9]{1,10})\\) Device Role\\(([0-9]{1,10})\\) Devices\\(([^\\p{Cntrl}]{1,8192})\\)");
    private final Map<String, String> entries;
    private CaptureRoleMap(Map<String, String> entries) { this.entries = Collections.unmodifiableMap(entries); }

    public static CaptureRoleMap parse(String dump) {
        if (dump == null || dump.isEmpty() || dump.length() > MAX_BYTES || dump.indexOf('\0') >= 0
                || dump.contains("Permission Denial") || dump.contains("Error dumping") || dump.contains("DUMP TIMEOUT")) {
            throw new IllegalArgumentException("Native capture map dump unavailable");
        }
        String[] lines = dump.split("\n", -1);
        Map<String, String> entries = new LinkedHashMap<>();
        int headers = 0, nextHeaders = 0, state = 0;
        for (String raw : lines) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (HEADER.equals(line)) {
                if (++headers != 1 || state != 0) throw invalid();
                state = 1;
            } else if (NEXT.equals(line)) {
                if (++nextHeaders != 1 || state != 2) throw invalid();
                state = 3;
            } else if (state == 1) {
                if (line.isEmpty()) { state = 2; continue; }
                Matcher match = ENTRY.matcher(line);
                if (!match.matches()) throw invalid();
                long preset = Long.parseLong(match.group(1)), role = Long.parseLong(match.group(2));
                if (preset > 0xffffffffL || role < 1 || role > 2
                        || !Long.toString(preset).equals(match.group(1)) || !Long.toString(role).equals(match.group(2))) throw invalid();
                String key = preset + ":" + role;
                if (entries.put(key, match.group(3)) != null) throw invalid();
            } else if (state == 2 || line.contains("Device role per capture preset dump:")
                    || line.contains("Capture preset(") || line.contains("Volume Groups dump:")) throw invalid();
        }
        if (headers != 1 || nextHeaders != 1 || state != 3) throw invalid();
        return new CaptureRoleMap(entries);
    }
    public boolean absent(int preset, int role) { return !entries.containsKey(preset + ":" + role); }
    @Override public boolean equals(Object other) { return other instanceof CaptureRoleMap && entries.equals(((CaptureRoleMap) other).entries); }
    @Override public int hashCode() { return entries.hashCode(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Unrecognized or incomplete native capture map"); }
}
