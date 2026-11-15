package dev.usbmic.inspector.automation;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import dev.usbmic.inspector.adb.LocalAdbClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One serialized, process-local owner of the embedded ADB connection. */
class AutomationSession {
    private static AutomationSession instance;
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean working = new AtomicBoolean();
    private final AtomicInteger connectionAttempt = new AtomicInteger();
    private volatile LocalAdbClient client;
    private RoutingBridge bridge;
    private volatile boolean restorationUnresolved = true;
    volatile RoutingProtocol.Result lastRoutingResult;
    volatile String connectionStatus = "Disconnected. Pair and connect using this app's own identity.";
    volatile int connectionResultVersion;

    AutomationSession(Context context) {
        this.context = context.getApplicationContext();
        restorationUnresolved = preferences().getBoolean("may_need_restore", false);
    }
    static synchronized AutomationSession get(Context context) {
        if (instance == null) instance = new AutomationSession(context);
        return instance;
    }

    interface Callback<T> { void complete(T result, String error); }
    private interface Work<T> { T run() throws Exception; }

    private <T> void submit(Work<T> operation, Callback<T> callback, String failureMessage) {
        if (!working.compareAndSet(false, true)) {
            main.post(() -> callback.complete(null, "Another local ADB operation is running. Wait for it to finish."));
            return;
        }
        worker.execute(() -> {
            T result = null;
            String error = null;
            try {
                restorationUnresolved = preferences().getBoolean("may_need_restore", false);
                result = operation.run();
            }
            catch (Exception failure) { error = failureMessage; }
            finally { working.set(false); }
            T completed = result;
            String problem = error;
            main.post(() -> callback.complete(completed, problem));
        });
    }

    boolean isBusy() { return working.get(); }
    boolean needsRestoration() { return restorationUnresolved; }
    boolean isConnected() { LocalAdbClient current = client; return current != null && current.isConnected(); }
    // Presence selects the shorter form; it does not establish pairing or connectivity.
    boolean hasLocalIdentity() {
        java.io.File folder = new java.io.File(context.getNoBackupFilesDir(), "local-adb");
        return new java.io.File(folder, "identity.pk8").isFile() && new java.io.File(folder, "identity.crt").isFile();
    }
    String lastConnectionPort() {
        int port = preferences().getInt("last_connection_port", 0);
        return port > 0 && port <= 65535 ? Integer.toString(port) : "";
    }
    boolean wantsConnection() { return preferences().getBoolean("connection_requested", false); }
    boolean setConnectionWanted(boolean wanted) {
        return preferences().edit().putBoolean("connection_requested", wanted).commit();
    }

    private LocalAdbClient client() throws IOException {
        if (client == null) client = new LocalAdbClient(context);
        return client;
    }

    void pair(int port, char[] code, Callback<Boolean> callback) {
        if (working.get()) {
            Arrays.fill(code, '\0');
            main.post(() -> callback.complete(null, "Another local ADB operation is running."));
            return;
        }
        connectionAttempt.incrementAndGet();
        submit(() -> {
            try { client().pair(port, code); connectionStatus = "Paired. Now enter the separate connection port and tap Connect."; return true; }
            finally { Arrays.fill(code, '\0'); }
        }, (result, error) -> {
            Arrays.fill(code, '\0');
            if (error != null) connectionStatus = "Pairing did not complete. Check the open Android pairing dialog and retry manually.";
            callback.complete(result, error);
        }, "Pairing failed. Keep the pairing dialog open and check its port and six-digit code. Retry manually.");
    }

    void connect(int port, Callback<Boolean> callback) {
        final int attempt = connectionAttempt.incrementAndGet();
        submit(() -> {
            try { connectTransport(port); }
            catch (IOException failure) { markConnectionLost(); throw failure; }
            if (bridge != null) bridge.resetUpload();
            connectionStatus = "Connected to this phone's Wireless debugging service.";
            preferences().edit().putInt("last_connection_port", port).apply();
            return true;
        }, (result, error) -> {
            if (attempt != connectionAttempt.get()) { callback.complete(null, "Connection request was superseded."); return; }
            callback.complete(result, error);
        }, "Connection failed. Check the current connection port. If this app was removed in Wireless debugging, choose Pair again.");
    }
    /** Transport boundary is injectable in local lifecycle tests; production always verifies the shell UID. */
    void connectTransport(int port) throws IOException {
        client().connect(port);
        if (!probeCurrentConnection()) throw new IOException("Shell connection unavailable");
    }

    /** A successful handshake alone is not a continuing shell session. This command never changes policy. */
    void heartbeat(Callback<Boolean> callback) {
        submit(() -> {
            if (!probeCurrentConnection()) { markConnectionLost(); throw new IOException("Shell session lost"); }
            return true;
        }, callback, "Local connection was lost. Reconnect, or Pair again if authorization was removed.");
    }

    private boolean probeCurrentConnection() {
        try {
            LocalAdbClient current = client;
            if (current == null || !current.isConnected()) return false;
            LocalAdbClient.ShellResult result = current.shell("id -u", 5000, 128);
            return result.exitCode == 0 && "2000".equals(result.stdout.trim());
        } catch (IOException unavailable) { return false; }
    }

    void markConnectionLost() {
        LocalAdbClient current = client;
        if (current != null) current.close();
        if (bridge != null) bridge.resetUpload();
        connectionStatus = "Local connection lost. Check its current port, or Pair again if removed in Wireless debugging.";
    }

    void routing(RoutingBridge.Action action, int vendor, int product, int microphoneId, Callback<RoutingProtocol.Result> callback) {
        submit(() -> {
            if (bridge == null) bridge = new RoutingBridge(context);
            if (action == RoutingBridge.Action.TICK || action == RoutingBridge.Action.DEFAULTS) rememberRestoration(true);
            RoutingProtocol.Result result;
            try { result = bridge.execute(client(), action, vendor, product, microphoneId); }
            catch (IOException unavailable) {
                // A helper/protocol error is not necessarily a dead transport. Confirm with one read-only probe.
                if (!probeCurrentConnection()) markConnectionLost();
                throw unavailable;
            }
            lastRoutingResult = result;
            if (result.restorePending || result.state.equals("active")) rememberRestoration(true);
            else if (result.ok && (result.state.equals("restored") || result.state.equals("idle") || result.state.equals("waiting"))) rememberRestoration(false);
            return result;
        }, callback, "Local ADB or routing-helper communication failed. Automatic writes are stopped. Reconnect and check status; saved preferences may still need restoration.");
    }

    void inventory(Callback<Inventory> callback) {
        submit(() -> {
            Map<String, DacChoice> choices = new LinkedHashMap<>();
            for (UsbDevice device : context.getSystemService(UsbManager.class).getDeviceList().values()) {
                boolean audio = false;
                for (int i = 0; i < device.getInterfaceCount(); i++) {
                    UsbInterface intf = device.getInterface(i);
                    if (intf.getInterfaceClass() == 1 && intf.getInterfaceSubclass() == 2) audio = true;
                }
                String key = device.getVendorId() + ":" + device.getProductId();
                DacChoice existing = choices.get(key);
                if (existing != null) { existing.count++; existing.audio |= audio; }
                else choices.put(key, new DacChoice(device.getVendorId(), device.getProductId(), device.getProductName(), audio));
            }
            List<MicChoice> microphones = new ArrayList<>();
            for (AudioDeviceInfo input : context.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (input.isSource() && input.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    microphones.add(new MicChoice(input.getId(), String.valueOf(input.getProductName()), input.getAddress()));
                }
            }
            for (MicChoice mic : microphones) {
                for (MicChoice other : microphones) if (mic != other && mic.address.equals(other.address)) mic.canonicalCount++;
            }
            return new Inventory(new ArrayList<>(choices.values()), microphones);
        }, callback, "Dongle or built-in microphone inventory could not be read. Refresh it before enabling routing.");
    }

    void forget(Callback<Boolean> callback) {
        submit(() -> {
            if (RoutingService.running || restorationUnresolved) throw new IOException("Restore saved preferences first");
            if (!setConnectionWanted(false)) throw new IOException("Connection intent could not be cleared");
            LocalAdbClient current = client();
            current.close();
            current.forgetIdentity();
            client = null;
            if (bridge != null) bridge.resetUpload();
            connectionStatus = "This app's pairing identity was removed. Pair again before reconnecting.";
            preferences().edit().remove("last_connection_port").apply();
            return true;
        }, callback, "Pairing was kept. Connect and use Stop & restore, then verify status before forgetting this app's identity.");
    }

    private SharedPreferences preferences() { return context.getSharedPreferences("routing_state", Context.MODE_PRIVATE); }

    private void rememberRestoration(boolean unresolved) throws IOException {
        // Persist before an action that may write, so process death cannot hide restoration debt.
        if (!preferences().edit().putBoolean("may_need_restore", unresolved).commit()) {
            restorationUnresolved = true;
            throw new IOException("Restoration state could not be saved safely.");
        }
        restorationUnresolved = unresolved;
    }

    void cancelConnection() {
        LocalAdbClient current = client;
        if (current != null) new Thread(current::close, "cancel-local-adb").start();
        connectionStatus = "Connection cancelled. Reconnect manually; any saved routing preferences may still need restoration.";
    }

    void disconnect() {
        connectionAttempt.incrementAndGet();
        LocalAdbClient current = client;
        if (current != null) current.close();
        if (bridge != null) bridge.resetUpload();
        connectionStatus = "Disconnected. Pairing is kept for the next connection.";
    }

    static final class DacChoice {
        final int vendor, product;
        final String name;
        boolean audio;
        int count = 1;
        DacChoice(int vendor, int product, String name, boolean audio) {
            this.vendor = vendor; this.product = product; this.name = name; this.audio = audio;
        }
        @Override public String toString() {
            return String.format(Locale.ROOT, "%s — %04x:%04x%s", name == null ? "USB audio device" : name, vendor, product,
                    count != 1 ? " (multiple matching devices; selection blocked)"
                            : audio ? " (USB audio interfaces detected)" : " (no USB audio streaming interface detected)");
        }
    }

    static final class MicChoice {
        final int id;
        final String name, address;
        int canonicalCount = 1;
        MicChoice(int id, String name, String address) {
            this.id = id; this.name = name; this.address = address == null ? "" : address;
        }
        @Override public String toString() {
            if (id < 0) return name;
            return name + " | address: " + (address.isEmpty() ? "system default (empty)" : address) + " | id: " + id
                    + (id == 0 ? " (unusable Android input ID)" : canonicalCount > 1 ? " (shared address; cannot route independently)" : "");
        }
    }

    static final class Inventory {
        final List<DacChoice> dongles;
        final List<MicChoice> microphones;
        Inventory(List<DacChoice> dongles, List<MicChoice> microphones) { this.dongles = dongles; this.microphones = microphones; }
    }
}
