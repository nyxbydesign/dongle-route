package dev.usbmic.inspector.automation;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import dev.usbmic.inspector.CaptureClaimService;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** Explicit pairing and policy controls, without a terminal or externally callable commands. */
public final class AutomationActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private AutomationSession session;
    private TextView connectionStatus, routingStatus, feedback;
    private EditText pairingPort, pairingCode, connectionPort;
    private Spinner dac, microphone;
    private Button pair, connect, refresh, check, enable, stop, defaults, forget, cancel;
    private boolean inventoryKnown;
    private boolean inventoryAttempted;
    private int dacCount;
    private int restoredMicId = -1;
    private final Runnable render = new Runnable() {
        @Override public void run() {
            boolean busy = session.isBusy();
            boolean active = RoutingService.running;
            boolean connected = session.isConnected();
            if (!busy && !inventoryAttempted) refreshDacs();
            String connectionText = (connected ? "Local ADB connected.\n" : "Local ADB not connected.\n")
                    + session.connectionStatus + (busy ? "\nWorking…" : "")
                    + (session.needsRestoration() ? "\nSaved preferences may need restoration. Connect, check status, then Stop & restore before forgetting pairing." : "");
            connectionStatus.setText(connectionText);
            routingStatus.setText(RoutingService.status);
            pair.setEnabled(!busy && !active);
            connect.setEnabled(!busy && !active);
            refresh.setEnabled(!busy && !active);
            check.setEnabled(!busy && connected && !active);
            enable.setEnabled(!busy && connected && !active && selected() != null && selectedMicrophone() != null);
            stop.setEnabled(connected);
            defaults.setEnabled(connected && !busy && (active || inventoryKnown) && selectedMicrophone() != null);
            forget.setEnabled(!busy && !active && !session.needsRestoration());
            cancel.setEnabled(!active && (busy || connected));
            dac.setEnabled(!active && !busy);
            microphone.setEnabled(!active && !busy);
            main.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        session = AutomationSession.get(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(scroll);
        label(body, "DongleRoute", 24);
        label(body, "Detect connected USB dongles, inspect available built-in microphones, and configure a supported audio route. Automation requires Wireless debugging and this app's own paired connection. Android capabilities vary; routing and continued USB playback need verification on each device.", 15);
        connectionStatus = label(body, session.connectionStatus, 16);
        button(body, "Open Developer options", view -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (ActivityNotFoundException unavailable) { message("Developer options could not be opened. Open them manually in Android Settings."); }
        });
        label(body, "Pair once: open Wireless debugging, then choose Pair device with pairing code. Keep that dialog open and put Settings and this app in split-screen. Enter the pairing port and code below. Then use the different connection port from the main Wireless debugging screen. This app never enables debugging or approves pairing for you.", 14);
        pairingPort = number(body, "Pairing port", false);
        pairingCode = number(body, "Six-digit pairing code", true);
        pair = button(body, "Pair this app", view -> pair());
        connectionPort = number(body, "Connection port", false);
        connect = button(body, "Connect", view -> {
            Integer port = port(connectionPort);
            if (port == null) return;
            message("Connecting…");
            session.connect(port, (result, error) -> message(error == null ? "Connected. Select an audio dongle and built-in microphone, then check capability status or enable automation." : error));
        });
        cancel = button(body, "Cancel / disconnect local ADB", view -> {
            if (RoutingService.running) { message("Use Stop & restore before disconnecting automation."); return; }
            session.cancelConnection();
            message("Connection cancelled. Saved microphone preferences are not automatically cleared by disconnecting.");
        });
        forget = button(body, "Forget this app's pairing", view -> {
            if (RoutingService.running || session.isBusy() || session.needsRestoration()) return;
            new AlertDialog.Builder(this).setTitle("Forget this app's pairing?")
                    .setMessage("Only this app's own pairing identity is removed. This does not restore microphone preferences. Use Stop & restore first if needed.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Forget pairing", (dialog, which) ->
                            session.forget((result, error) -> message(error == null ? "Pairing identity removed. Pair again to reconnect." : error))).show();
        });
        label(body, "Detected USB dongles", 20);
        dac = new Spinner(this);
        dac.setContentDescription("Selected USB audio dongle");
        body.addView(dac);
        label(body, "Built-in microphone", 20);
        microphone = new Spinner(this);
        microphone.setContentDescription("Selected built-in microphone");
        body.addView(microphone);
        refresh = button(body, "Refresh dongles and microphones", view -> refreshDacs());
        label(body, "Choose one audio dongle and an unambiguous built-in microphone. The microphone list shows Android's current input IDs and addresses; an empty address is labeled system default. A unique bottom microphone or sole built-in input is preselected. Otherwise choose explicitly. Shared addresses cannot be routed independently.", 14);
        label(body, "USB audio interfaces indicate a candidate, not confirmed routing support. Check capability status after connecting. Unsupported devices and ambiguous matches remain blocked. Automation restores saved preferences on dongle disconnect and reapplies on reconnect while this service and local ADB remain connected.", 14);
        routingStatus = label(body, RoutingService.status, 16);
        check = button(body, "Check routing status", view -> {
            AutomationSession.DacChoice choice = selectedDongle();
            AutomationSession.MicChoice mic = selectedMicrophone();
            session.routing(RoutingBridge.Action.STATUS, choice == null ? -1 : choice.vendor, choice == null ? -1 : choice.product,
                    mic == null ? -1 : mic.id,
                    (result, error) -> message(error == null ? result.describe() : error));
        });
        enable = button(body, "Enable automatic routing on reconnect", view -> confirmEnable());
        stop = button(body, "Stop & restore saved preferences", view -> startAction(RoutingService.STOP, false));
        defaults = button(body, "Restore Android microphone defaults", view -> {
            new AlertDialog.Builder(this).setTitle("Restore Android microphone defaults?")
                    .setMessage("This stops automation and returns microphone selection to Android's defaults. It can clear microphone preferences you previously set manually.\n\nStop & restore instead returns to the preferences saved before automation, including any manual preferences.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Restore Android defaults", (dialog, which) -> startAction(RoutingService.DEFAULTS, true)).show();
        });
        label(body, "An active call or recording defers policy changes. Pending restoration keeps a visible foreground notification until it can finish. If local ADB is lost or Android stops the app, preferences may remain active: reconnect, check status, and use Stop & restore. Connection ports can change after Wireless debugging restarts or the phone reboots.", 14);
        feedback = label(body, "Pairing codes are used once and never saved or included in diagnostic reports.", 15);
        button(body, "Back to diagnostics", view -> finish());
        if (state != null) {
            pairingPort.setText(state.getString("pairingPort", ""));
            connectionPort.setText(state.getString("connectionPort", ""));
            restoredMicId = state.getInt("microphoneId", -1);
        }
        if (!session.isBusy()) refreshDacs();
    }

    private TextView label(LinearLayout body, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setPadding(0, 10, 0, 10);
        body.addView(view); return view;
    }

    private Button button(LinearLayout body, String text, View.OnClickListener listener) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false); view.setOnClickListener(listener);
        body.addView(view); return view;
    }

    private EditText number(LinearLayout body, String hint, boolean secret) {
        EditText field = new EditText(this);
        field.setHint(hint); field.setSingleLine();
        field.setInputType(InputType.TYPE_CLASS_NUMBER | (secret ? InputType.TYPE_NUMBER_VARIATION_PASSWORD : 0));
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        body.addView(field); return field;
    }

    private Integer port(EditText field) {
        try {
            int port = Integer.parseInt(field.getText().toString().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException invalid) { message("Enter a port between 1 and 65535 in the corresponding port field."); return null; }
    }

    private void pair() {
        Integer port = port(pairingPort);
        if (port == null) return;
        if (pairingCode.length() != 6) {
            pairingCode.setText("");
            message("Enter the six-digit code from the currently open Android pairing dialog.");
            return;
        }
        char[] code = new char[6];
        pairingCode.getText().getChars(0, code.length, code, 0);
        pairingCode.setText("");
        boolean valid = code.length == 6;
        for (char digit : code) valid &= digit >= '0' && digit <= '9';
        if (!valid) { Arrays.fill(code, '\0'); message("Enter the six-digit code from the currently open Android pairing dialog."); return; }
        message("Pairing… Keep the Android pairing dialog open.");
        session.pair(port, code, (result, error) -> message(error == null ? "Pairing completed. Use the separate connection port and tap Connect." : error));
    }

    private void refreshDacs() {
        inventoryAttempted = true;
        AutomationSession.MicChoice previousMic = selectedMicrophone();
        int wantedMic = RoutingService.running ? RoutingService.configuredMicId : previousMic == null ? restoredMicId : previousMic.id;
        session.inventory((inventory, error) -> {
            if (error != null) { inventoryKnown = false; message(error); return; }
            inventoryKnown = true;
            List<AutomationSession.DacChoice> choices = inventory.dongles;
            dacCount = choices.size();
            dac.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
            if (RoutingService.running) {
                for (int i = 0; i < choices.size(); i++) {
                    if (choices.get(i).vendor == RoutingService.configuredVendor && choices.get(i).product == RoutingService.configuredProduct) dac.setSelection(i);
                }
            }
            List<AutomationSession.MicChoice> microphones = new ArrayList<>();
            microphones.add(new AutomationSession.MicChoice(-1, "Choose a built-in microphone", ""));
            microphones.addAll(inventory.microphones);
            microphone.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, microphones));
            int select = 0, bottom = 0, bottoms = 0;
            for (int i = 1; i < microphones.size(); i++) {
                AutomationSession.MicChoice mic = microphones.get(i);
                if (mic.id > 0 && mic.canonicalCount == 1 && mic.id == wantedMic) select = i;
                if (mic.id > 0 && mic.canonicalCount == 1 && mic.address.equals("bottom")) { bottom = i; bottoms++; }
            }
            if (select == 0 && !RoutingService.running) {
                if (bottoms == 1) select = bottom;
                else if (inventory.microphones.size() == 1 && microphones.get(1).id > 0 && microphones.get(1).canonicalCount == 1) select = 1;
            }
            microphone.setSelection(select);
            message((choices.isEmpty() ? "No USB dongle is currently connected. " : "Detected " + choices.size() + " USB device groups. ")
                    + (inventory.microphones.isEmpty() ? "Android exposes no built-in microphone input; routing remains unavailable."
                    : "Detected " + inventory.microphones.size() + " built-in microphone inputs. Select the intended route, then check capability status."));
        });
    }

    private AutomationSession.DacChoice selected() {
        AutomationSession.DacChoice choice = selectedDongle();
        return choice != null && choice.audio ? choice : null;
    }

    private AutomationSession.DacChoice selectedDongle() {
        Object value = dac == null ? null : dac.getSelectedItem();
        if (!(value instanceof AutomationSession.DacChoice)) return null;
        AutomationSession.DacChoice choice = (AutomationSession.DacChoice) value;
        return choice.count == 1 ? choice : null;
    }

    private AutomationSession.MicChoice selectedMicrophone() {
        Object value = microphone == null ? null : microphone.getSelectedItem();
        if (!(value instanceof AutomationSession.MicChoice)) return null;
        AutomationSession.MicChoice choice = (AutomationSession.MicChoice) value;
        return choice.id > 0 && choice.canonicalCount == 1 ? choice : null;
    }

    private void confirmEnable() {
        new AlertDialog.Builder(this).setTitle("Enable automatic microphone routing?")
                .setMessage("For your selected audio dongle, request the selected built-in microphone while retaining USB headphone output. Changes wait while a call or recording is active. Saved preferences are restored on dongle disconnect or Stop. Device support is checked before changes; keep the visible service notification and Wireless debugging connection active.")
                .setNegativeButton("Cancel", null).setPositiveButton("Enable automation", (dialog, which) -> startAction(RoutingService.ENABLE, true)).show();
    }

    private boolean notificationsReady() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 40);
            message("Allow notifications for the visible Stop control, then tap the routing action again. Nothing starts automatically.");
            return false;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel("automatic_input_routing");
        if (!manager.areNotificationsEnabled() || (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)) {
            message("Enable this app's automatic-routing notifications in Android before starting the service.");
            return false;
        }
        return true;
    }

    private void startAction(String action, boolean needsSelection) {
        if (!session.isConnected()) { message("Connect this app to local Wireless debugging first. Restoration has not been verified."); return; }
        if (RoutingService.ENABLE.equals(action) && CaptureClaimService.isExperimentActive()) {
            message("Release the separate capture experiment before enabling automation."); return;
        }
        AutomationSession.DacChoice choice = selected();
        AutomationSession.MicChoice mic = selectedMicrophone();
        if (!RoutingService.STOP.equals(action) && mic == null) {
            message("Select a current, unambiguous built-in microphone first."); return;
        }
        if (needsSelection && !RoutingService.running && (choice == null && (!inventoryKnown || dacCount > 0 || RoutingService.ENABLE.equals(action)))) {
            message("Refresh connected dongles and select exactly one unambiguous USB audio device."); return;
        }
        if (!RoutingService.running && !notificationsReady()) return;
        Intent request = new Intent(this, RoutingService.class).setAction(action);
        if (choice != null) request.putExtra("vendor", choice.vendor).putExtra("product", choice.product);
        if (mic != null) request.putExtra("microphone", mic.id);
        try {
            if (RoutingService.running) startService(request); else startForegroundService(request);
        } catch (RuntimeException failure) { message("The routing service could not start. No restoration has been verified."); }
    }

    private void message(String text) { if (!isDestroyed() && feedback != null) feedback.setText(text); }
    @Override protected void onStart() { super.onStart(); main.post(render); }
    @Override protected void onStop() { main.removeCallbacks(render); pairingCode.setText(""); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("pairingPort", pairingPort.getText().toString());
        state.putString("connectionPort", connectionPort.getText().toString());
        AutomationSession.MicChoice mic = selectedMicrophone();
        if (mic != null) state.putInt("microphoneId", mic.id);
        super.onSaveInstanceState(state);
    }
}
