package dev.usbmic.inspector.automation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import dev.usbmic.inspector.MainActivity;

/** Explicit foreground owner of local ADB, independently of optional input routing. */
public final class RoutingService extends Service {
    static final String CONNECT = "dev.usbmic.inspector.automation.CONNECT";
    static final String DISCONNECT = "dev.usbmic.inspector.automation.DISCONNECT";
    static final String ENABLE = "dev.usbmic.inspector.automation.ENABLE";
    static final String STOP = "dev.usbmic.inspector.automation.STOP";
    static final String DEFAULTS = "dev.usbmic.inspector.automation.DEFAULTS";
    private static final int NOTIFICATION_ID = 40;
    private static final String CHANNEL = "automatic_input_routing";
    private static RoutingService owner;
    public static volatile boolean running;
    static volatile boolean sessionRunning, connecting, routingRequested, restoring;
    static volatile String presentationState = "off";
    static volatile int configuredMicId = -1, configuredVendor = -1, configuredProduct = -1;
    static volatile String status = "Local control is offline.";
    private final Handler main = new Handler(Looper.getMainLooper());
    private AutomationSession session;
    private RoutingBridge.Action mode;
    private int vendor = -1, product = -1, microphoneId = -1, connectionEpoch;
    private boolean operationRunning, observersRegistered, completed, disconnectAfterRestore, canonicalEstablished;
    private String notificationText = "", lastRecordedState = "";
    private final Runnable reconcile = this::reconcile;
    private final AudioDeviceCallback audioDevices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { debounce(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { debounce(); }
    };
    private final BroadcastReceiver usbDevices = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { debounce(); }
    };

    @Override public void onCreate() { super.onCreate(); session = AutomationSession.get(this); owner = this; }
    /** Called only from the foreground Forget confirmation, before any identity files are removed. */
    static boolean prepareToForget(Context context) {
        AutomationSession current = AutomationSession.get(context);
        if (running || current.isBusy() || current.needsRestoration() || !current.setConnectionWanted(false)) return false;
        if (owner != null) owner.finishSession("Local session closed before forgetting pairing.");
        else current.disconnect();
        return true;
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            if (!session.wantsConnection() || !session.hasLocalIdentity()) { finishSession("Local control is offline."); return START_NOT_STICKY; }
            int port;
            try { port = Integer.parseInt(session.lastConnectionPort()); }
            catch (NumberFormatException unavailable) { finishSession("Open DongleRoute and enter the current connection port."); return START_NOT_STICKY; }
            SessionEvents.record(this, "os_restart");
            requestConnection(port); // Consume one OS-reclaim attempt before opening a socket.
            return START_STICKY;
        }
        String action = intent.getAction();
        if (CONNECT.equals(action)) {
            int port = intent.getIntExtra("port", -1);
            if (port < 1 || port > 65535) { if (!sessionRunning) finishSession("Enter the current connection port."); return START_NOT_STICKY; }
            if (running && session.isConnected()) return START_STICKY;
            requestConnection(port); return START_STICKY;
        }
        if (DISCONNECT.equals(action)) {
            SessionEvents.record(this, "disconnect_requested");
            if (!session.setConnectionWanted(false)) { status = "Disconnect could not be saved. Keep the connection and try again."; updateNotification(); return START_STICKY; }
            if (!session.isConnected()) { finishSession("Local control closed. Saved preferences may still need restoration."); return START_NOT_STICKY; }
            if (running || session.needsRestoration()) { disconnectAfterRestore = true; requestRouting(STOP, intent); return START_NOT_STICKY; }
            finishSession("Local control disconnected. Pairing is kept."); return START_NOT_STICKY;
        }
        if (!ENABLE.equals(action) && !STOP.equals(action) && !DEFAULTS.equals(action)) { if (!sessionRunning) stopSelf(); return sessionRunning ? START_STICKY : START_NOT_STICKY; }
        requestRouting(action, intent); return START_STICKY;
    }

    private void requestConnection(int port) {
        mode = null; running = routingRequested = restoring = false; canonicalEstablished = false;
        connecting = true; presentationState = "off"; status = "Connecting local control."; completed = false;
        if (!promote()) { session.connectionStatus = status; return; }
        SessionEvents.record(this, "connect_requested");
        // Persist no restart while an authentication attempt is in flight. Only verified success re-arms it.
        if (!session.setConnectionWanted(false)) { connecting = false; finishSession("Connection intent could not be saved. Try again."); session.connectionStatus = status; return; }
        final int epoch = ++connectionEpoch; main.removeCallbacks(reconcile); connectWhenAvailable(port, epoch);
    }
    private void connectWhenAvailable(int port, int epoch) {
        if (owner != this || !sessionRunning || epoch != connectionEpoch) return;
        if (session.isBusy() || operationRunning) { main.postDelayed(() -> connectWhenAvailable(port, epoch), 300); return; }
        operationRunning = true;
        session.connect(port, (result, error) -> {
            operationRunning = false;
            if (owner != this) return;
            if (!sessionRunning || epoch != connectionEpoch) { session.disconnect(); return; }
            connecting = false;
            if (error != null || !session.isConnected()) { SessionEvents.record(this, "connect_failed"); parkDisconnected(error == null ? "Local connection failed. Reconnect or Pair again." : error); session.connectionResultVersion++; return; }
            if (!session.setConnectionWanted(true)) {
                SessionEvents.record(this, "connect_failed"); finishSession("The connected session could not be saved. Reconnect to try again."); session.connectionStatus = status; return;
            }
            status = "Local control connected. Routing is off."; SessionEvents.record(this, "connected"); updateNotification(); schedule(15_000);
            session.connectionResultVersion++;
        });
    }
    private void requestRouting(String action, Intent intent) {
        if (!session.isConnected()) {
            running = routingRequested = restoring = false; mode = null; status = "Reconnect local control before changing or restoring microphone preferences.";
            presentationState = "blocked"; if (sessionRunning) updateNotification(); else finishSession(status); return;
        }
        if (ENABLE.equals(action)) {
            if (running) return;
            vendor = intent.getIntExtra("vendor", -1); product = intent.getIntExtra("product", -1); microphoneId = intent.getIntExtra("microphone", -1);
            if (vendor < 0 || vendor > 65535 || product < 0 || product > 65535 || microphoneId <= 0) { status = "Select one USB audio device and an unambiguous phone microphone."; presentationState = "blocked"; return; }
            configuredVendor = vendor; configuredProduct = product; configuredMicId = microphoneId;
            mode = RoutingBridge.Action.TICK; routingRequested = true; restoring = false; canonicalEstablished = false; disconnectAfterRestore = false;
            SessionEvents.record(this, "routing_started");
        } else if (DEFAULTS.equals(action)) {
            vendor = intent.getIntExtra("vendor", vendor); product = intent.getIntExtra("product", product); microphoneId = intent.getIntExtra("microphone", microphoneId);
            if (microphoneId <= 0) { status = "Choose a current phone microphone for Android defaults."; presentationState = "blocked"; return; }
            mode = RoutingBridge.Action.DEFAULTS; routingRequested = false; restoring = true;
        } else { mode = RoutingBridge.Action.STOP; routingRequested = false; restoring = true; SessionEvents.record(this, "routing_stopped"); }
        completed = false; running = true; presentationState = mode == RoutingBridge.Action.TICK ? "checking" : "restoring";
        status = mode == RoutingBridge.Action.TICK ? "Checking the selected microphone preference." : "Restoring microphone preferences.";
        if (!promote()) return; main.removeCallbacks(reconcile); reconcile();
    }
    private boolean promote() {
        try {
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Local connection and microphone routing", NotificationManager.IMPORTANCE_LOW));
            startForeground(NOTIFICATION_ID, notification(status), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            if (!sessionRunning) SessionEvents.record(this, "session_started"); sessionRunning = true;
            if (!observersRegistered) {
                getSystemService(AudioManager.class).registerAudioDeviceCallback(audioDevices, main);
                IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED); filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
                registerReceiver(usbDevices, filter, Context.RECEIVER_EXPORTED); observersRegistered = true;
            }
            return true;
        } catch (RuntimeException unavailable) {
            running = routingRequested = restoring = connecting = false; mode = null; presentationState = "blocked";
            finishSession("The visible local-session service could not start. Check notifications and reconnect."); return false;
        }
    }
    private void debounce() { if (sessionRunning && mode != null) schedule(350); }
    private void schedule(long delay) { main.removeCallbacks(reconcile); main.postDelayed(reconcile, delay); }
    private void reconcile() {
        if (owner != this || !sessionRunning || connecting) return;
        if (operationRunning || session.isBusy()) { schedule(1000); return; }
        if (!session.isConnected()) { parkDisconnected("Local control was lost. Check the current port, or Pair again if removed in Wireless debugging."); return; }
        if (mode == null) {
            final int epoch = connectionEpoch;
            operationRunning = true;
            session.heartbeat((result, error) -> {
                operationRunning = false; if (owner != this || !sessionRunning || epoch != connectionEpoch) return;
                if (error != null || !session.isConnected()) { parkDisconnected(error == null ? "Local control was lost." : error); return; }
                schedule(mode == null ? 15_000 : 0);
            }); return;
        }
        RoutingBridge.Action requested = mode; operationRunning = true;
        session.routing(requested, vendor, product, requested == RoutingBridge.Action.TICK && canonicalEstablished ? -1 : microphoneId, (result, error) -> {
            operationRunning = false; if (owner != this || !sessionRunning) return;
            if (mode != requested) { schedule(0); return; }
            if (error != null) {
                if (!session.isConnected()) { parkDisconnected(error); return; }
                stopRoutingOnly(error + " Local control remains connected."); return;
            }
            presentationState = result.state; status = result.describe();
            if (!result.ok || "blocked".equals(result.state) || "conflict".equals(result.state)) { stopRoutingOnly(result.describe() + " Automatic changes stopped; local control remains connected."); return; }
            if ("active".equals(result.state)) canonicalEstablished = true;
            if ("waiting".equals(result.state) && !"waiting".equals(lastRecordedState)) SessionEvents.record(this, "routing_waiting");
            lastRecordedState = result.state; updateNotification();
            if (requested != RoutingBridge.Action.TICK && !result.restorePending
                    && !"waiting".equals(result.state) && !"busy".equals(result.state)) {
                if ("restored".equals(result.state) || "idle".equals(result.state)) {
                    SessionEvents.record(this, "restored"); running = routingRequested = restoring = false; mode = null;
                    if (disconnectAfterRestore) { finishSession("Preferences restored. Local control disconnected."); return; }
                    status = result.message + " Local control remains connected."; updateNotification(); schedule(15_000);
                } else stopRoutingOnly("Restoration was not verified. Local control remains connected; check details.");
                return;
            }
            schedule("waiting".equals(result.state) ? 2000 : 5000);
        });
    }
    private void stopRoutingOnly(String message) {
        running = routingRequested = restoring = false; mode = null; presentationState = "blocked"; status = message; disconnectAfterRestore = false;
        SessionEvents.record(this, "routing_blocked"); updateNotification(); schedule(15_000);
    }
    private void parkDisconnected(String message) {
        session.markConnectionLost();
        if (!session.setConnectionWanted(false)) {
            SessionEvents.record(this, "connection_lost");
            finishSession(message + " Session closed because its restart state could not be cleared."); return;
        }
        running = routingRequested = restoring = connecting = false; mode = null; presentationState = "blocked"; status = message;
        main.removeCallbacks(reconcile); SessionEvents.record(this, "connection_lost"); updateNotification();
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 41, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder notification = new Notification.Builder(this, CHANNEL).setSmallIcon(dev.usbmic.inspector.R.drawable.ic_route_notification)
                .setContentTitle(connecting ? "DongleRoute: connecting" : session.isConnected() ? "DongleRoute: local control connected" : "DongleRoute: reconnect required")
                .setContentText(text.length() > 180 ? text.substring(0, 180) : text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true);
        if (running || session.needsRestoration()) {
            PendingIntent stop = PendingIntent.getService(this, 40, new Intent(this, RoutingService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            notification.addAction(new Notification.Action.Builder(null, "Stop & restore", stop).build());
        }
        PendingIntent disconnect = PendingIntent.getService(this, 42, new Intent(this, RoutingService.class).setAction(DISCONNECT), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        notification.addAction(new Notification.Action.Builder(null, session.isConnected() && (running || session.needsRestoration()) ? "Restore & disconnect" : "Disconnect", disconnect).build());
        return notification.build();
    }
    private void updateNotification() {
        if (sessionRunning && !status.equals(notificationText)) { getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(status)); notificationText = status; }
    }
    private void finishSession(String message) {
        completed = true; ++connectionEpoch; session.setConnectionWanted(false); session.disconnect();
        sessionRunning = running = routingRequested = restoring = connecting = false; mode = null; status = message;
        session.connectionResultVersion++;
        main.removeCallbacksAndMessages(null); SessionEvents.record(this, "disconnected"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onTaskRemoved(Intent rootIntent) { SessionEvents.record(this, "task_removed"); super.onTaskRemoved(rootIntent); }
    @Override public void onDestroy() {
        if (owner == this) {
            if (!completed) status = "The local-session service ended. Reconnect; saved preferences may still need restoration.";
            session.disconnect(); sessionRunning = running = routingRequested = restoring = connecting = false;
            owner = null;
        }
        main.removeCallbacksAndMessages(null);
        if (observersRegistered) { getSystemService(AudioManager.class).unregisterAudioDeviceCallback(audioDevices); unregisterReceiver(usbDevices); }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
