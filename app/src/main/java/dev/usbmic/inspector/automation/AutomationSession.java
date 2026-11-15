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

/** One serialized, process-local owner of the embedded ADB connection. */
final class AutomationSession {
    private static AutomationSession instance;
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean working = new AtomicBoolean();
    private volatile LocalAdbClient client;
    private RoutingBridge bridge;
    private volatile boolean restorationUnresolved = true;
    volatile RoutingProtocol.Result lastRoutingResult;
    volatile String connectionStatus = "Disconnected. Pair and connect using this app's own identity.";

    private AutomationSession(Context context) {
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
        submit(() -> {
            client().connect(port);
            if (!client().isConnected()) throw new IOException("Not connected");
            if (bridge != null) bridge.resetUpload();
            connectionStatus = "Connected to this phone's Wireless debugging service.";
            return true;
        }, (result, error) -> {
            if (error != null) connectionStatus = "Connection failed. Check the current connection port; it can change after restarting Wireless debugging.";
            callback.complete(result, error);
        }, "Connection failed. Check the connection port shown on the main Wireless debugging screen, not the pairing port.");
    }

    void routing(RoutingBridge.Action action, int vendor, int product, int microphoneId, Callback<RoutingProtocol.Result> callback) {
        submit(() -> {
            if (bridge == null) bridge = new RoutingBridge(context);
            if (action == RoutingBridge.Action.TICK || action == RoutingBridge.Action.DEFAULTS) rememberRestoration(true);
            RoutingProtocol.Result result = bridge.execute(client(), action, vendor, product, microphoneId);
            lastRoutingResult = result;
            if (result.restorePending || result.state.equals("active")) rememberRestoration(true);
            else if (result.ok && (result.state.equals("restored") || result.state.equals("idle"))) rememberRestoration(false);
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
            LocalAdbClient current = client();
            current.close();
            current.forgetIdentity();
            client = null;
            if (bridge != null) bridge.resetUpload();
            connectionStatus = "This app's pairing identity was removed. Pair again before reconnecting.";
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
