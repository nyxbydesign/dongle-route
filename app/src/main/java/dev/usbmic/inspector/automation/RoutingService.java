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

/** User-started policy reconciliation. It never captures audio or claims USB interfaces. */
public final class RoutingService extends Service {
    static final String ENABLE = "dev.usbmic.inspector.automation.ENABLE";
    static final String STOP = "dev.usbmic.inspector.automation.STOP";
    static final String DEFAULTS = "dev.usbmic.inspector.automation.DEFAULTS";
    private static final int NOTIFICATION_ID = 40;
    private static final String CHANNEL = "automatic_input_routing";
    public static volatile boolean running;
    static volatile String presentationState = "off";
    static volatile int configuredMicId = -1, configuredVendor = -1, configuredProduct = -1;
    static volatile String status = "Automation is off. Connect to check whether saved routing preferences need restoration.";
    private final Handler main = new Handler(Looper.getMainLooper());
    private AutomationSession session;
    private RoutingBridge.Action mode = RoutingBridge.Action.TICK;
    private int vendor = -1, product = -1, microphoneId = -1;
    private boolean operationRunning;
    private boolean observersRegistered;
    private boolean completed;
    private String notificationText = "";

    private final Runnable reconcile = this::reconcile;
    private final AudioDeviceCallback audioDevices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { debounce(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { debounce(); }
    };
    private final BroadcastReceiver usbDevices = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { debounce(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        session = AutomationSession.get(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { end("Automation did not restart automatically. Reconnect and check saved preferences."); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (!ENABLE.equals(action) && !STOP.equals(action) && !DEFAULTS.equals(action)) {
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (ENABLE.equals(action)) {
            if (running) return START_NOT_STICKY;
            vendor = intent.getIntExtra("vendor", -1);
            product = intent.getIntExtra("product", -1);
            microphoneId = intent.getIntExtra("microphone", -1);
            if (vendor < 0 || vendor > 65535 || product < 0 || product > 65535) {
                end("Select exactly one USB audio dongle before enabling automation."); return START_NOT_STICKY;
            }
            if (microphoneId <= 0) { end("Select an unambiguous built-in microphone before enabling automation."); return START_NOT_STICKY; }
            configuredVendor = vendor; configuredProduct = product; configuredMicId = microphoneId;
            mode = RoutingBridge.Action.TICK;
        } else if (DEFAULTS.equals(action)) {
            mode = RoutingBridge.Action.DEFAULTS;
            if (intent.hasExtra("vendor")) {
                vendor = intent.getIntExtra("vendor", -1);
                product = intent.getIntExtra("product", -1);
            }
            if (intent.hasExtra("microphone")) microphoneId = intent.getIntExtra("microphone", -1);
            if (microphoneId <= 0) { end("Select a current built-in microphone before restoring Android defaults."); return START_NOT_STICKY; }
            configuredVendor = vendor; configuredProduct = product; configuredMicId = microphoneId;
        } else mode = RoutingBridge.Action.STOP;
        if (!session.isConnected()) {
            end("Local ADB is disconnected. Automatic writes are stopped. Saved preferences have not been verified restored; reconnect, check status, then Stop & restore.");
            return START_NOT_STICKY;
        }
        completed = false;
        running = true;
        presentationState = mode == RoutingBridge.Action.TICK ? "checking" : "restoring";
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Automatic microphone routing", NotificationManager.IMPORTANCE_LOW));
        status = mode == RoutingBridge.Action.TICK ? "Checking the selected DAC and routing capabilities…"
                : mode == RoutingBridge.Action.DEFAULTS ? "Waiting to restore Android microphone defaults…" : "Waiting to restore saved microphone preferences…";
        try {
            startForeground(NOTIFICATION_ID, notification(status), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            if (!observersRegistered) {
                getSystemService(AudioManager.class).registerAudioDeviceCallback(audioDevices, main);
                IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
                filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
                registerReceiver(usbDevices, filter, Context.RECEIVER_EXPORTED);
                observersRegistered = true;
            }
            main.removeCallbacks(reconcile);
            reconcile();
        } catch (RuntimeException failure) {
            end("The foreground routing service could not start. No restoration has been verified. Check notification permission and reconnect before trying again.");
        }
        return START_NOT_STICKY;
    }

    private void debounce() {
        if (!running) return;
        main.removeCallbacks(reconcile);
        main.postDelayed(reconcile, 350);
    }

    private void reconcile() {
        if (!running || operationRunning) return;
        if (session.isBusy()) { main.postDelayed(reconcile, 1000); return; }
        RoutingBridge.Action requested = mode;
        operationRunning = true;
        session.routing(requested, vendor, product, microphoneId, (result, error) -> {
            operationRunning = false;
            if (!running) return;
            if (error != null) { presentationState = "blocked"; end(error + " Restoration is not guaranteed; reconnect and check status."); return; }
            if (mode != requested) { main.post(reconcile); return; }
            presentationState = result.state;
            if (!result.ok || result.state.equals("blocked") || result.state.equals("conflict")) {
                end(result.describe() + "\nAutomatic writes stopped. Check status and use Stop & restore when the blocker is resolved.");
                return;
            }
            status = result.describe();
            updateNotification();
            if (requested != RoutingBridge.Action.TICK && !result.restorePending
                    && (result.state.equals("restored") || result.state.equals("idle"))) {
                end(result.message);
                return;
            }
            if (requested != RoutingBridge.Action.TICK && !result.restorePending) {
                end("Restoration could not be verified. Check status before trying again.");
                return;
            }
            main.removeCallbacks(reconcile);
            main.postDelayed(reconcile, 5000);
        });
    }

    private Notification notification(String text) {
        PendingIntent stop = PendingIntent.getService(this, 40,
                new Intent(this, RoutingService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 41, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(dev.usbmic.inspector.R.drawable.ic_route_notification)
                .setContentTitle(mode == RoutingBridge.Action.TICK ? "DongleRoute: automatic microphone routing" : "DongleRoute: restoration pending")
                .setContentText(text.length() > 180 ? text.substring(0, 180) : text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop & restore", stop).build()).build();
    }

    private void updateNotification() {
        if (!status.equals(notificationText)) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(status));
            notificationText = status;
        }
    }

    private void end(String message) {
        completed = true;
        running = false;
        status = message;
        main.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (!completed) {
            running = false;
            presentationState = "blocked";
            status = "The routing service ended. Saved preferences may still be active. Reconnect, check status, then Stop & restore.";
        }
        main.removeCallbacksAndMessages(null);
        if (observersRegistered) {
            getSystemService(AudioManager.class).unregisterAudioDeviceCallback(audioDevices);
            unregisterReceiver(usbDevices);
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
