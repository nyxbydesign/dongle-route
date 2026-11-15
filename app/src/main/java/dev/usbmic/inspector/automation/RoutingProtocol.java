package dev.usbmic.inspector.automation;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Shared by the Android bridge and focused JVM protocol tests, without Android stubs. */
final class RoutingProtocol {
    private static final Set<String> STATES = new HashSet<>(Arrays.asList("active", "idle", "busy", "restored", "conflict", "blocked"));
    private RoutingProtocol() {}

    static Result parse(String output, int exitCode) throws IOException {
        if (output == null || output.length() > 32768) throw new IOException("The routing helper returned an unrecognized response.");
        String record = output.trim();
        if (!record.startsWith("USBMIC1:") || record.indexOf('\n') >= 0 || record.indexOf('\r') >= 0) {
            throw new IOException("The routing helper returned an unrecognized response.");
        }
        try {
            JSONTokener tokens = new JSONTokener(record.substring(8));
            Object parsed = tokens.nextValue();
            if (!(parsed instanceof JSONObject) || tokens.nextClean() != 0) throw new JSONException("Extra or missing JSON data");
            JSONObject data = (JSONObject) parsed;
            Object ok = data.get("ok"), state = data.get("state"), message = data.get("message");
            Object idle = data.get("idle"), pending = data.get("restorePending");
            if (!(ok instanceof Boolean) || !(state instanceof String) || !(message instanceof String)
                    || !(idle instanceof Boolean) || !(pending instanceof Boolean) || !STATES.contains(state)) {
                throw new JSONException("Unexpected field type or state");
            }
            String text = ((String) message).replaceAll("[\\p{Cntrl}&&[^\\n]]", " ");
            if (text.length() > 1200) text = text.substring(0, 1200) + "...";
            if (exitCode != 0) return new Result(false, "blocked", "Routing helper refused or did not complete: " + text,
                    (Boolean) idle, (Boolean) pending);
            return new Result((Boolean) ok, (String) state, text, (Boolean) idle, (Boolean) pending);
        } catch (JSONException invalid) {
            throw new IOException("The routing helper returned malformed status.");
        }
    }

    static final class Result {
        final boolean ok;
        final String state;
        final String message;
        final boolean idle;
        final boolean restorePending;
        Result(boolean ok, String state, String message, boolean idle, boolean pending) {
            this.ok = ok; this.state = state; this.message = message; this.idle = idle; this.restorePending = pending;
        }
        String describe() { return state + ": " + message + (restorePending ? "\nRestoration is pending." : ""); }
    }
}
