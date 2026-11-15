package dev.usbmic.inspector.automation;

import android.content.Context;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Bounded private lifecycle evidence. Only fixed event names are accepted, never peer/error/credential data. */
final class SessionEvents {
    private static final Set<String> NAMES = new HashSet<>(Arrays.asList("session_started", "connect_requested",
            "connected", "connection_lost", "connect_failed", "task_removed", "routing_started", "routing_stopped",
            "routing_waiting", "routing_blocked", "restored", "disconnect_requested", "disconnected", "os_restart"));
    private SessionEvents() { }
    static synchronized void record(Context context, String event) {
        if (!NAMES.contains(event)) throw new IllegalArgumentException("Unknown lifecycle event");
        android.content.SharedPreferences prefs = context.getSharedPreferences("session_events", Context.MODE_PRIVATE);
        String[] old = prefs.getString("events", "").split("\n");
        StringBuilder value = new StringBuilder();
        for (int i = Math.max(0, old.length - 47); i < old.length; i++) if (!old[i].isEmpty()) value.append(old[i]).append('\n');
        value.append(Instant.now().toString()).append(' ').append(event);
        prefs.edit().putString("events", value.toString()).apply();
    }
    static String read(Context context) { return context.getSharedPreferences("session_events", Context.MODE_PRIVATE).getString("events", ""); }
}
