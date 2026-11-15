package dev.usbmic.inspector.automation;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import static dev.usbmic.inspector.automation.RouteUi.*;

/** One route surface; connection and exceptional controls stay in transient overlays. */
public class AutomationActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private AutomationSession session;
    private LinearLayout body;
    private ScrollView scroll;
    private View dongleRow, microphoneRow;
    private TextView dongleTitle, micTitle, statusTitle, statusHint, primary, feedbackView, connectionLabel;
    private TextView overflow;
    private Dialog connectionDialog;
    private LinearLayout sheetBody;
    private EditText pairingPort, pairingCode, connectionPort;
    private TextView sheetAction, sheetAlternate, sheetFeedback;
    private boolean pairingMode, inventoryKnown, inventoryAttempted, inventoryDirty = true, observersRegistered;
    private boolean awaitingConnection;
    private int awaitedConnectionVersion;
    private long refreshAfter;
    private int selectedVendor = -1, selectedProduct = -1, selectedMicId = -1;
    private String rememberedDongle = "", rememberedMic = "", feedback = "", sheetMessage = "";
    private String savedPairPort = "", savedConnectionPort = "";
    private List<AutomationSession.DacChoice> dongles = new ArrayList<>();
    private List<AutomationSession.MicChoice> microphones = new ArrayList<>();

    private final AudioDeviceCallback audioDevices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { dirtyInventory(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { dirtyInventory(); }
    };
    private final BroadcastReceiver usbDevices = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { dirtyInventory(); }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if ((!inventoryAttempted || (inventoryDirty && SystemClock.uptimeMillis() >= refreshAfter)) && !session.isBusy()) refreshInventory();
            updateState(); main.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        session = AutomationSession.get(this);
        savedConnectionPort = session.lastConnectionPort();
        if (state != null) {
            selectedVendor = state.getInt("vendor", -1); selectedProduct = state.getInt("product", -1);
            selectedMicId = state.getInt("microphone", -1);
            rememberedDongle = state.getString("dongleLabel", ""); rememberedMic = state.getString("micLabel", "");
            savedPairPort = state.getString("pairPort", ""); savedConnectionPort = state.getString("connectPort", savedConnectionPort);
        }
        LinearLayout root = column(this); root.setBackgroundColor(BG);
        scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = column(this); body.setPadding(d(26), d(16), d(26), d(24)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(root); buildHome();
    }

    private int d(float value) { return dp(this, value); }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, d(height))); }
    private void rule(LinearLayout parent) {
        View line = new View(this); line.setBackgroundColor(BORDER); parent.addView(line, new LinearLayout.LayoutParams(-1, d(1)));
    }
    private TextView label(LinearLayout parent, String value, int size, int color, boolean medium) {
        TextView t = text(this, value, size, color, medium); parent.addView(t, new LinearLayout.LayoutParams(-1, -2)); return t;
    }
    private TextView mono(LinearLayout parent, String value) {
        TextView t = label(parent, value, 10, MUTED, false); t.setTypeface(Typeface.MONOSPACE); t.setLetterSpacing(.12f); return t;
    }
    private TextView action(LinearLayout parent, String title, boolean accent, View.OnClickListener listener) {
        TextView t = button(this, title, accent, listener); parent.addView(t, new LinearLayout.LayoutParams(-1, -2)); return t;
    }
    private void buildHome() {
        LinearLayout header = row(this);
        TextView brand = text(this, "DongleRoute", 19, TEXT, true);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        overflow = text(this, "\u22ee", 28, MUTED, false); overflow.setGravity(Gravity.CENTER);
        clickable(overflow, BG, 0, 0); overflow.setContentDescription("More options"); buttonSemantics(overflow);
        header.addView(overflow, new LinearLayout.LayoutParams(d(48), d(48)));
        overflow.setOnClickListener(this::showOptions); body.addView(header);
        gap(body, 44);
        connectionLabel = mono(body, "OFFLINE");
        gap(body, 28);
        rule(body); dongleRow = routeRow("USB OUT", "headphones", true);
        rule(body); microphoneRow = routeRow("PHONE IN", "mic", false); rule(body);
        gap(body, 26);
        statusTitle = label(body, "", 15, TEXT, true);
        gap(body, 7); statusHint = label(body, "", 13, MUTED, false);
        statusTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        gap(body, 16); feedbackView = label(body, "", 13, AMBER, false);
        body.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        gap(body, 40); primary = action(body, "Connect", true, view -> primaryAction());
        gap(body, 12);
        TextView note = label(body, "Output routing is unchanged.", 11, MUTED, false); note.setGravity(Gravity.CENTER);
        updateState();
    }

    private View routeRow(String caption, String icon, boolean output) {
        LinearLayout line = row(this); line.setPadding(0, d(24), 0, d(24));
        clickable(line, BG, 0, 0); line.setMinimumHeight(d(104)); buttonSemantics(line);
        line.addView(new Icon(this, icon, output ? TEXT : MINT), new LinearLayout.LayoutParams(d(24), d(28)));
        LinearLayout words = column(this); LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1); wp.setMarginStart(d(20)); line.addView(words, wp);
        mono(words, caption); gap(words, 9);
        TextView title = label(words, "", 21, TEXT, false);
        if (output) dongleTitle = title; else micTitle = title;
        line.addView(new Icon(this, "arrow", MUTED), new LinearLayout.LayoutParams(d(18), d(24)));
        line.setOnClickListener(v -> { if (!selectionLocked()) { if (output) chooseDongle(); else chooseMicrophone(); } });
        body.addView(line, new LinearLayout.LayoutParams(-1, -2)); return line;
    }

    private void updateState() {
        if (awaitingConnection && session.connectionResultVersion != awaitedConnectionVersion) {
            awaitingConnection = false;
            if (session.isConnected()) connectionCompleted();
            else sheetMessage = session.connectionStatus;
        }
        boolean connected = session.isConnected(), busy = session.isBusy(), active = RoutingService.running, debt = session.needsRestoration();
        AutomationSession.DacChoice dac = selectedDongle(); AutomationSession.MicChoice mic = selectedMicrophone();
        if (dac != null) rememberedDongle = dongleName(dac);
        if (mic != null) rememberedMic = micName(mic);
        dongleTitle.setText(dac != null ? rememberedDongle : selectionLocked() && !rememberedDongle.isEmpty() ? rememberedDongle : "Connect USB headphones");
        micTitle.setText(mic != null ? rememberedMic : selectionLocked() && !rememberedMic.isEmpty() ? rememberedMic : "Choose microphone");
        enabled(dongleRow, !busy && !selectionLocked()); enabled(microphoneRow, !busy && !selectionLocked());
        connectionLabel.setText(RoutingService.connecting ? "CONNECTING" : connected ? "CONNECTED" : "OFFLINE");
        connectionLabel.setTextColor(connected ? MINT : MUTED);
        String title, hint;
        if (!connected && debt) { title = "Reconnect to restore"; hint = "The previous microphone preference may still be set."; }
        else if (!connected) { title = "Connect to begin"; hint = "Pair once, then connect this phone."; }
        else if (active) {
            String state = RoutingService.presentationState;
            if ("active".equals(state)) { title = "Phone mic selected"; hint = "Check mic and headphones in your calling app."; }
            else if ("busy".equals(state)) { title = "Waiting for recording to end"; hint = "No routing changes during calls or recordings."; }
            else if ("restoring".equals(state)) { title = "Restoring previous preferences"; hint = "Keep the connection open."; }
            else if ("waiting".equals(state)) { title = RoutingService.restoring ? "Waiting to restore" : "Waiting for USB audio"; hint = "Waiting for Android to update USB audio."; }
            else if ("idle".equals(state) || "restored".equals(state)) { title = "Waiting for USB headphones"; hint = "Resumes when your dongle reconnects."; }
            else { title = "Checking routing support"; hint = "Requesting the selected phone input."; }
        } else if (debt) {
            RoutingProtocol.Result last = session.lastRoutingResult;
            title = last != null && !last.ok ? "Recovery needs attention" : "Restore previous route";
            hint = last != null && !last.ok ? last.message : "Restore before choosing another route.";
        }
        else if ("blocked".equals(RoutingService.presentationState) || "conflict".equals(RoutingService.presentationState)) { title = "Routing unavailable"; hint = "Open Details in the menu."; }
        else if (dac == null) { title = "No USB audio selected"; hint = dongles.isEmpty() ? "Plug in your headphone dongle." : "Tap USB OUT to choose a supported device."; }
        else if (mic == null) { title = "Choose a phone input"; hint = "Tap PHONE IN to select a built-in microphone."; }
        else { title = "Routing is off"; hint = "Use your phone mic with USB headphones."; }
        statusTitle.setText(title); statusHint.setText(hint);
        primary.setText(!connected ? debt ? "Reconnect to restore" : "Connect" : active || debt ? "Stop & restore" : "Use phone mic");
        enabled(primary, !busy || active);
        if (feedbackView != null) { feedbackView.setText(feedback); feedbackView.setVisibility(feedback.isEmpty() ? View.GONE : View.VISIBLE); }
        if (connectionDialog != null && connectionDialog.isShowing()) {
            enabled(sheetAction, !busy && !RoutingService.connecting && (!active || !connected)); enabled(sheetAlternate, !busy && !RoutingService.connecting && (!active || !connected));
            if (sheetFeedback != null) { sheetFeedback.setText(sheetMessage); sheetFeedback.setVisibility(sheetMessage.isEmpty() ? View.GONE : View.VISIBLE); }
        }
    }
    private boolean selectionLocked() { return RoutingService.running || session.needsRestoration(); }
    private void primaryAction() {
        if (!session.isConnected()) { openConnection(); return; }
        if (RoutingService.running || session.needsRestoration()) { startAction(RoutingService.STOP); return; }
        if (selectedDongle() == null) { chooseDongle(); return; }
        if (selectedMicrophone() == null) { chooseMicrophone(); return; }
        startAction(RoutingService.ENABLE);
    }
    private void showOptions(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        boolean connected = session.isConnected(), busy = session.isBusy(), active = RoutingService.running;
        menu.getMenu().add(0, 1, 0, connected ? "Connection" : "Connect").setEnabled(!active || !connected);
        menu.getMenu().add(0, 9, 1, active || session.needsRestoration() ? "Restore & disconnect" : "Disconnect").setEnabled(RoutingService.sessionRunning && !busy);
        menu.getMenu().add(0, 2, 1, "Restore previous preferences").setEnabled(connected && !busy);
        menu.getMenu().add(0, 3, 2, "Use Android defaults").setEnabled(connected && !busy && inventoryKnown && selectedMicrophone() != null);
        menu.getMenu().add(0, 4, 3, "Details");
        menu.getMenu().add(0, 7, 4, "Open-source notices");
        menu.getMenu().add(0, 8, 5, "Forget pairing").setEnabled(!busy && !active && !session.needsRestoration());
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: openConnection(); break;
                case 9: requestDisconnect(); break;
                case 2: startAction(RoutingService.STOP); break;
                case 3: confirmDefaults(); break;
                case 4: showRoutingDetails(); break;
                case 7: showLicenses(); break;
                case 8: forget(); break;
                default: return false;
            }
            return true;
        }); menu.show();
    }

    private void openConnection() {
        if (RoutingService.running && session.isConnected()) { message("Stop and restore before changing the connection."); return; }
        if (connectionDialog != null && connectionDialog.isShowing()) return;
        pairingMode = !session.hasLocalIdentity() && !session.needsRestoration(); sheetMessage = "";
        connectionDialog = new Dialog(this);
        SheetScroll sheet = new SheetScroll(this);
        sheetBody = column(this); sheetBody.setPadding(d(24), d(24), d(24), d(18));
        sheet.addView(sheetBody); connectionDialog.setContentView(sheet);
        connectionDialog.setOnDismissListener(dialog -> { rememberPortFields(); pairingPort = pairingCode = connectionPort = null; connectionDialog = null; awaitingConnection = false; });
        renderConnectionSheet();
        Window window = connectionDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(surface(this, PANEL, 0, 18)); window.setGravity(Gravity.BOTTOM);
            window.setDimAmount(.55f); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        connectionDialog.show(); if (window != null) window.setLayout(-1, -2); updateState();
    }
    private void renderConnectionSheet() {
        rememberPortFields(); pairingPort = pairingCode = connectionPort = null;
        sheetBody.removeAllViews(); sheetBody.setFocusableInTouchMode(true); sheetBody.requestFocus();
        label(sheetBody, pairingMode ? "Pair this phone" : "Connect this phone", 22, TEXT, true); gap(sheetBody, 10);
        label(sheetBody, pairingMode
                ? "Wireless debugging \u2192 Pair with code. Keep both apps open in split-screen."
                : "Use the main Wireless debugging port, not the pairing port.", 13, MUTED, false);
        gap(sheetBody, 8);
        TextView settings = text(this, "Open Android settings \u2197", 13, MINT, true);
        settings.setMinHeight(d(48)); settings.setGravity(Gravity.CENTER_VERTICAL); buttonSemantics(settings);
        settings.setOnClickListener(v -> openAndroidSettings()); sheetBody.addView(settings); gap(sheetBody, 8);
        if (pairingMode) {
            LinearLayout fields = row(this), port = column(this), code = column(this);
            fields.addView(port, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1); cp.setMarginStart(d(20)); fields.addView(code, cp);
            pairingPort = number(port, "Pairing port", "37123", false); pairingPort.setText(savedPairPort);
            pairingCode = number(code, "Pairing code", "6 digits", true); sheetBody.addView(fields);
        } else {
            connectionPort = number(sheetBody, "Connection port", "40215", false); connectionPort.setText(savedConnectionPort);
        }
        gap(sheetBody, 20);
        sheetAction = action(sheetBody, pairingMode ? "Pair" : "Connect", true, v -> { if (pairingMode) pair(); else connect(); }); gap(sheetBody, 5);
        sheetAlternate = text(this, pairingMode ? "Already paired? Connect" : "Pair again", 13, MUTED, false);
        sheetAlternate.setGravity(Gravity.CENTER); sheetAlternate.setMinHeight(d(48)); buttonSemantics(sheetAlternate);
        sheetAlternate.setOnClickListener(v -> { if (!session.isBusy()) { pairingMode = !pairingMode; sheetMessage = ""; renderConnectionSheet(); updateState(); } }); sheetBody.addView(sheetAlternate);
        sheetFeedback = label(sheetBody, sheetMessage, 12, AMBER, false); updateState();
    }
    private EditText number(LinearLayout parent, String name, String hint, boolean secret) {
        label(parent, name, 11, MUTED, false); gap(parent, 5);
        EditText field = new EditText(this); field.setTextSize(20); field.setTextColor(TEXT); field.setHintTextColor(0xff626d68); field.setHint(hint);
        field.setSingleLine(); field.setMinHeight(d(50)); field.setPadding(0, d(8), 0, d(8)); field.setBackgroundColor(PANEL);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | (secret ? InputType.TYPE_NUMBER_VARIATION_PASSWORD : 0));
        field.setSaveEnabled(false); field.setContentDescription(name);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if (secret) field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2)); rule(parent); return field;
    }
    private void openAndroidSettings() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
        catch (ActivityNotFoundException missing) { sheetMessage("Open Developer options in Android Settings."); }
    }
    private Integer port(EditText field) {
        try { int value = Integer.parseInt(field.getText().toString().trim()); if (value < 1 || value > 65535) throw new NumberFormatException(); return value; }
        catch (NumberFormatException invalid) { sheetMessage("Enter the port shown in Android Settings (1-65535)."); return null; }
    }
    private void pair() {
        if ((RoutingService.running && session.isConnected()) || session.isBusy()) { sheetMessage("Finish the current operation before pairing again."); return; }
        Integer port = port(pairingPort); if (port == null) return;
        char[] code = new char[pairingCode.length()]; pairingCode.getText().getChars(0, code.length, code, 0); pairingCode.setText("");
        boolean valid = code.length == 6; for (char digit : code) valid &= digit >= '0' && digit <= '9';
        if (!valid) { Arrays.fill(code, '\0'); sheetMessage("Enter the current six-digit pairing code."); return; }
        sheetMessage("Pairing\u2026");
        session.pair(port, code, (result, error) -> {
            if (isDestroyed()) return;
            if (error != null) { sheetMessage(error); return; }
            if (connectionDialog != null && connectionDialog.isShowing()) { pairingMode = false; sheetMessage = "Paired. Use the separate connection port."; renderConnectionSheet(); }
        });
    }
    private void connect() {
        Integer port = port(connectionPort); if (port == null || !notificationsReady()) return;
        rememberPortFields(); sheetMessage("Connecting\u2026");
        awaitedConnectionVersion = session.connectionResultVersion; awaitingConnection = true;
        try { startForegroundService(new Intent(this, RoutingService.class).setAction(RoutingService.CONNECT).putExtra("port", port)); }
        catch (RuntimeException unavailable) { awaitingConnection = false; sheetMessage("The background connection service could not start. Check notifications and retry."); }
    }
    private void requestDisconnect() {
        try { startService(new Intent(this, RoutingService.class).setAction(RoutingService.DISCONNECT)); }
        catch (RuntimeException unavailable) { message("The session could not close. Reopen the app and try again."); }
    }
    /** Connection never implies a routing request. Start remains a distinct user action. */
    private void connectionCompleted() {
        if (connectionDialog != null) {
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null && connectionDialog.getWindow() != null) keyboard.hideSoftInputFromWindow(connectionDialog.getWindow().getDecorView().getWindowToken(), 0);
            connectionDialog.dismiss();
        }
        feedback = ""; updateState();
    }

    private void refreshInventory() {
        if (session.isBusy()) return; inventoryAttempted = true; inventoryDirty = false;
        session.inventory((inventory, error) -> {
            if (isDestroyed()) return;
            if (error != null) { inventoryKnown = false; message(error); return; }
            inventoryKnown = true; dongles = inventory.dongles; microphones = inventory.microphones;
            if (RoutingService.running) { selectedVendor = RoutingService.configuredVendor; selectedProduct = RoutingService.configuredProduct; selectedMicId = RoutingService.configuredMicId; }
            if (!selectionLocked()) {
                if (selectedDongle() == null) {
                    AutomationSession.DacChoice sole = null; int count = 0;
                    for (AutomationSession.DacChoice choice : dongles) if (choice.audio && choice.count == 1) { count++; sole = choice; }
                    if (count == 1) { selectedVendor = sole.vendor; selectedProduct = sole.product; }
                }
                if (selectedMicrophone() == null) {
                    int valid = 0, sole = -1, bottoms = 0, bottom = -1;
                    for (AutomationSession.MicChoice mic : microphones) if (mic.id > 0 && mic.canonicalCount == 1) { valid++; sole = mic.id; if ("bottom".equals(mic.address)) { bottoms++; bottom = mic.id; } }
                    if (bottoms == 1) selectedMicId = bottom; else if (valid == 1 && microphones.size() == 1) selectedMicId = sole;
                }
            }
            updateState();
        });
    }
    private AutomationSession.DacChoice selectedDongle() { for (AutomationSession.DacChoice d : dongles) if (d.vendor == selectedVendor && d.product == selectedProduct && d.count == 1 && d.audio) return d; return null; }
    private AutomationSession.MicChoice selectedMicrophone() { for (AutomationSession.MicChoice m : microphones) if (m.id == selectedMicId && m.id > 0 && m.canonicalCount == 1) return m; return null; }
    private String dongleName(AutomationSession.DacChoice d) { return d.name == null || d.name.trim().isEmpty() ? "USB audio dongle" : d.name; }
    private String micName(AutomationSession.MicChoice m) {
        if (m.address.isEmpty()) return "Phone microphone";
        if ("bottom".equals(m.address)) return "Bottom microphone";
        if ("back".equals(m.address) || "rear".equals(m.address)) return "Rear microphone";
        if ("front".equals(m.address)) return "Front microphone";
        return "Phone mic \u00b7 " + m.address;
    }
    private void chooseDongle() {
        if (selectionLocked()) return;
        List<AutomationSession.DacChoice> valid = new ArrayList<>(); for (AutomationSession.DacChoice d : dongles) if (d.audio && d.count == 1) valid.add(d);
        if (valid.isEmpty()) { new AlertDialog.Builder(this).setTitle("No USB audio device").setMessage("Plug in one headphone dongle. If identical devices are attached, disconnect the extra one.").setPositiveButton("Refresh", (dialog, which) -> refreshInventory()).setNegativeButton("Close", null).show(); return; }
        String[] names = new String[valid.size()]; for (int i = 0; i < names.length; i++) names[i] = dongleName(valid.get(i));
        new AlertDialog.Builder(this).setTitle("USB headphones").setItems(names, (dialog, which) -> { selectedVendor = valid.get(which).vendor; selectedProduct = valid.get(which).product; updateState(); }).setNegativeButton("Close", null).show();
    }
    private void chooseMicrophone() {
        if (selectionLocked()) return;
        List<AutomationSession.MicChoice> valid = new ArrayList<>(); for (AutomationSession.MicChoice m : microphones) if (m.id > 0 && m.canonicalCount == 1) valid.add(m);
        if (valid.isEmpty()) { message("Android reports no independently routable phone microphone."); return; }
        String[] names = new String[valid.size()]; for (int i = 0; i < names.length; i++) names[i] = micName(valid.get(i));
        new AlertDialog.Builder(this).setTitle("Phone microphone").setItems(names, (dialog, which) -> { selectedMicId = valid.get(which).id; updateState(); }).setNegativeButton("Close", null).show();
    }
    private boolean notificationsReady() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 40); sheetMessage("Allow the visible session notification, then tap the action again."); message("Allow the visible session notification, then tap the action again."); return false;
        }
        NotificationManager manager = getSystemService(NotificationManager.class); NotificationChannel channel = manager.getNotificationChannel("automatic_input_routing");
        if (!manager.areNotificationsEnabled() || (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)) { message("Enable routing notifications in Android Settings first."); return false; }
        return true;
    }
    private void startAction(String action) {
        if (!session.isConnected()) { openConnection(); return; }
        if (session.isBusy() && !RoutingService.running) { message("Wait for the current action to finish."); return; }
        AutomationSession.DacChoice d = selectedDongle(); AutomationSession.MicChoice m = selectedMicrophone();
        if (!RoutingService.STOP.equals(action) && m == null) { chooseMicrophone(); return; }
        if (!RoutingService.STOP.equals(action) && !RoutingService.running && d == null && (!inventoryKnown || !dongles.isEmpty() || RoutingService.ENABLE.equals(action))) { chooseDongle(); return; }
        if (!RoutingService.running && !notificationsReady()) return;
        Intent request = new Intent(this, RoutingService.class).setAction(action); if (d != null) request.putExtra("vendor", d.vendor).putExtra("product", d.product); if (m != null) request.putExtra("microphone", m.id);
        try { if (RoutingService.running) startService(request); else startForegroundService(request); feedback = ""; updateState(); }
        catch (RuntimeException failure) { message("The routing service could not start. Restoration is unverified."); }
    }
    private void checkStatus() {
        if (!session.isConnected()) { openConnection(); return; }
        AutomationSession.DacChoice d = selectedDongle(); AutomationSession.MicChoice m = selectedMicrophone();
        session.routing(RoutingBridge.Action.CHECK, d == null ? -1 : d.vendor, d == null ? -1 : d.product, m == null ? -1 : m.id,
                (result, error) -> { if (!isDestroyed()) { if (error != null) message(error); showRoutingDetails(); } });
    }
    private void confirmDefaults() {
        new AlertDialog.Builder(this).setTitle("Use Android microphone defaults?").setMessage("This stops routing and clears matching phone-microphone preferences, including manual ones. Restore previous preferences instead keeps your earlier choices.")
                .setNegativeButton("Cancel", null).setPositiveButton("Use defaults", (dialog, which) -> startAction(RoutingService.DEFAULTS)).show();
    }
    private void forget() {
        if (RoutingService.running || session.isBusy() || session.needsRestoration()) { message("Restore previous preferences before forgetting pairing."); return; }
        new AlertDialog.Builder(this).setTitle("Forget pairing?").setMessage("Remove this app's local identity. You can also revoke its entry in Android Wireless debugging.")
                .setNegativeButton("Cancel", null).setPositiveButton("Forget", (dialog, which) -> {
                    if (!RoutingService.prepareToForget(this)) { message("Restore previous preferences and finish the current operation before forgetting pairing."); return; }
                    session.forget((result, error) -> message(error == null ? "Pairing removed." : error));
                }).show();
    }
    private void showRoutingDetails() {
        String info = installedVersion() + "\nLocal control: " + (session.isConnected() ? "connected" : "offline")
                + "\nBackground session: " + RoutingService.sessionRunning + "\nRouting requested: " + RoutingService.routingRequested + "\n\n" + RoutingService.status;
        RoutingProtocol.Result last = session.lastRoutingResult;
        boolean repeatsPolicy = last != null && (RoutingService.status.startsWith(last.describe())
                || (!last.message.isEmpty() && RoutingService.status.startsWith(last.message)));
        if (last != null && !repeatsPolicy) info += "\n\nLast policy check\n" + last.describe();
        RoutingProtocol.Result failure = session.lastRoutingFailure;
        if (failure != null && failure != last) info += "\n\nLatest routing failure (kept after Stop)\n" + failure.describe();
        info += "\n\nRestoration may be needed: " + (session.needsRestoration() ? "yes" : "no");
        if (!dongles.isEmpty()) {
            info += "\n\nUSB inventory";
            for (AutomationSession.DacChoice d : dongles) info += String.format(Locale.ROOT, "\n%s  %04x:%04x  %s", dongleName(d), d.vendor, d.product, d.audio ? "audio" : "no audio interface");
        }
        info += "\n\nPreference readback does not prove the input used by a calling app.";
        String events = SessionEvents.read(this);
        if (!events.isEmpty()) info += "\n\nRecent session events\n" + events;
        final String report = info;
        AlertDialog details = new AlertDialog.Builder(this).setTitle("Routing details").setMessage(info)
                .setNeutralButton("Check compatibility", (dialog, which) -> checkStatus())
                .setNegativeButton("Copy", (dialog, which) -> {
                    getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText("DongleRoute details", report));
                    message("Details copied. Review hardware information before sharing.");
                }).setPositiveButton("Close", null).create();
        details.setOnShowListener(dialog -> details.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(session.isConnected() && !session.isBusy() && !RoutingService.running));
        details.show();
    }
    private String installedVersion() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "DongleRoute " + info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            return "DongleRoute (installed version unavailable)";
        }
    }
    private void showLicenses() {
        try {
            String[] files = getAssets().list("third-party"); if (files == null) throw new IllegalStateException();
            new AlertDialog.Builder(this).setTitle("Open-source notices").setItems(files, (dialog, which) -> {
                try (InputStream input = getAssets().open("third-party/" + files[which]); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) { if (out.size() + count > 131072) throw new IllegalStateException(); out.write(buffer, 0, count); }
                    String notice = new String(out.toByteArray(), StandardCharsets.UTF_8);
                    ScrollView scroll = new ScrollView(this); TextView content = text(this, notice, 12, TEXT, false);
                    if (files[which].endsWith(".html")) content.setText(android.text.Html.fromHtml(notice, android.text.Html.FROM_HTML_MODE_LEGACY));
                    content.setPadding(d(20), d(20), d(20), d(20)); scroll.addView(content);
                    new AlertDialog.Builder(this).setTitle(files[which]).setView(scroll).setPositiveButton("Close", null).show();
                } catch (Exception unavailable) { message("Notice unavailable."); }
            }).setNegativeButton("Close", null).show();
        } catch (Exception unavailable) { message("Notices unavailable."); }
    }
    private void message(String value) { feedback = value; if (!isDestroyed()) updateState(); }
    private void sheetMessage(String value) { sheetMessage = value; if (!isDestroyed()) updateState(); }
    private void rememberPortFields() {
        if (pairingPort != null) savedPairPort = pairingPort.getText().toString();
        if (connectionPort != null) savedConnectionPort = connectionPort.getText().toString();
        if (pairingCode != null) pairingCode.setText("");
    }
    private void dirtyInventory() { inventoryDirty = true; refreshAfter = SystemClock.uptimeMillis() + 300; }
    @Override protected void onStart() {
        super.onStart(); dirtyInventory(); getSystemService(AudioManager.class).registerAudioDeviceCallback(audioDevices, main);
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED); filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbDevices, filter, Context.RECEIVER_EXPORTED); observersRegistered = true; main.post(ticker);
    }
    @Override protected void onStop() {
        main.removeCallbacks(ticker); rememberPortFields();
        if (observersRegistered) { getSystemService(AudioManager.class).unregisterAudioDeviceCallback(audioDevices); unregisterReceiver(usbDevices); observersRegistered = false; }
        super.onStop();
    }
    @Override protected void onDestroy() { if (connectionDialog != null) connectionDialog.dismiss(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        rememberPortFields(); state.putInt("vendor", selectedVendor); state.putInt("product", selectedProduct); state.putInt("microphone", selectedMicId);
        state.putString("pairPort", savedPairPort); state.putString("connectPort", savedConnectionPort); state.putString("dongleLabel", rememberedDongle); state.putString("micLabel", rememberedMic); super.onSaveInstanceState(state);
    }
    private static final class SheetScroll extends ScrollView {
        public SheetScroll(Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int maximum = (int) (getResources().getDisplayMetrics().heightPixels * .82f);
            int available = View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.UNSPECIFIED ? maximum : View.MeasureSpec.getSize(heightSpec);
            super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(Math.min(available, maximum), View.MeasureSpec.AT_MOST));
        }
    }
}
