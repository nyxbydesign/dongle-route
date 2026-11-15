package dev.usbmic.inspector.automation;

import java.io.IOException;
import org.json.JSONObject;

/** Exercises the production decoder against the helper's actual fixed state contract. */
public final class RoutingProtocolTest {
    private static int assertions;
    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private static String response(boolean ok, String state, boolean idle, boolean pending) {
        return "USBMIC1:" + new JSONObject().put("ok", ok).put("state", state).put("message", "policy status")
                .put("idle", idle).put("restorePending", pending);
    }

    private static void refused(String value, String label) throws Exception {
        try { RoutingProtocol.parse(value, 0); throw new AssertionError("Accepted " + label); }
        catch (IOException expected) { assertions++; }
    }

    public static void main(String[] args) throws Exception {
        check(new JSONObject("{\"working\":true}").getBoolean("working"), "real org.json executes without Android stubs");
        for (String state : new String[]{"active", "idle", "busy", "waiting", "restored", "conflict", "blocked"}) {
            boolean ok = !state.equals("conflict") && !state.equals("blocked");
            RoutingProtocol.Result parsed = RoutingProtocol.parse(response(ok, state, !state.equals("busy"), false), 0);
            check(parsed.state.equals(state) && parsed.ok == ok, "known helper state " + state);
        }
        RoutingProtocol.Result busy = RoutingProtocol.parse(response(true, "busy", false, true), 0);
        check(busy.ok && !busy.idle && busy.restorePending, "busy Stop/defaults preserves deferred restoration");
        check(busy.describe().contains("pending"), "pending state is visible");
        RoutingProtocol.Result waiting = RoutingProtocol.parse(response(true, "waiting", true, true), 0);
        check(waiting.ok && waiting.restorePending && waiting.state.equals("waiting"), "device transition retains restoration ownership");
        check(!RoutingProtocol.parse(response(true, "waiting", true, true), 124).ok, "waiting cannot mask helper failure");
        RoutingProtocol.Result restored = RoutingProtocol.parse(" \n" + response(true, "restored", true, false) + "\n", 0);
        check(restored.ok && restored.idle && !restored.restorePending, "outer process whitespace allowed");
        for (int exit : new int[]{1, 2, 124, 255}) {
            RoutingProtocol.Result failed = RoutingProtocol.parse(response(true, "active", true, true), exit);
            check(!failed.ok && failed.state.equals("blocked") && failed.restorePending, "nonzero can never report success " + exit);
        }
        RoutingProtocol.Result reason = RoutingProtocol.parse(response(false, "conflict", true, true), 1);
        check(reason.message.contains("policy status") && reason.restorePending, "structured refusal reason/debt preserved");
        refused(null, "null");
        refused("", "empty");
        refused("USBMIC1:", "missing JSON");
        refused("noise\n" + response(true, "active", true, false), "extra stdout");
        refused(response(true, "active", true, false) + "\n" + response(true, "idle", true, false), "duplicate tagged records");
        refused(response(true, "active", true, false) + response(true, "idle", true, false), "concatenated tagged records");
        refused("USBMIC1:[]", "nonobject");
        refused("USBMIC1:{", "malformed JSON");
        refused("USBMIC1:{}", "missing fields");
        refused(response(true, "unknown", true, false), "unknown state");
        String valid = response(true, "active", true, false);
        for (String field : new String[]{"ok", "state", "message", "idle", "restorePending"}) {
            JSONObject wrong = new JSONObject(valid.substring(8));
            wrong.put(field, field.equals("state") || field.equals("message") ? Boolean.TRUE : "true");
            refused("USBMIC1:" + wrong, "wrong field type " + field);
            wrong.put(field, JSONObject.NULL);
            refused("USBMIC1:" + wrong, "null field " + field);
        }
        refused(valid + "junk", "trailing garbage");
        refused(valid + " ".repeat(32768), "bounded process output");
        JSONObject longMessage = new JSONObject(valid.substring(8)).put("message", "x".repeat(2000));
        RoutingProtocol.Result clipped = RoutingProtocol.parse("USBMIC1:" + longMessage, 0);
        check(clipped.message.length() == 1203 && clipped.message.endsWith("..."), "bounded UI message");
        JSONObject control = new JSONObject(valid.substring(8)).put("message", "status\u0000\tline");
        check(!RoutingProtocol.parse("USBMIC1:" + control, 0).message.contains("\u0000"), "control characters sanitized");
        System.out.println("Routing protocol tests passed: " + assertions + " assertions.");
    }
}
