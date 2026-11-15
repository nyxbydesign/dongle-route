package dev.usbmic.inspector.automation;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
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

/** A focused native route controller. Discovery is passive; routing starts only after confirmation. */
public class AutomationActivity extends Activity {
    private static final int HOME = 0, DEVICES = 1, SETTINGS = 2, CONNECTION = 3;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AutomationSession session;
    private LinearLayout root, body, navigation;
    private ScrollView scroll;
    private int page = HOME, selectedVendor = -1, selectedProduct = -1, selectedMicId = -1;
    private boolean inventoryAttempted, inventoryKnown;
    private boolean inventoryDirty = true, observersRegistered;
    private long refreshAfter;
    private String rememberedDongle = "", rememberedMic = "";
    private List<AutomationSession.DacChoice> dongles = new ArrayList<>();
    private List<AutomationSession.MicChoice> microphones = new ArrayList<>();
    private String feedback = "", savedPairPort = "", savedConnectionPort = "";
    private TextView connectionChip, routeTitle, routeHint, dongleTitle, dongleHint, micTitle, micHint;
    private TextView primary, feedbackView, pairButton, connectButton, cancelButton, refreshButton;
    private TextView restoreButton, defaultsButton, forgetButton, statusButton, connectionSummary;
    private EditText pairingPort, pairingCode, connectionPort;
    private View dongleCard, microphoneCard;
    private final android.window.OnBackInvokedCallback back = () -> { if (page != HOME) showPage(HOME); else finish(); };
    private final AudioDeviceCallback audioDevices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { dirtyInventory(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { dirtyInventory(); }
    };
    private final BroadcastReceiver usbDevices = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { dirtyInventory(); }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if ((!inventoryAttempted || (inventoryDirty && SystemClock.uptimeMillis() >= refreshAfter)) && !session.isBusy()) refreshInventory(false);
            updateState();
            main.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        session = AutomationSession.get(this);
        getWindow().getDecorView().setSystemUiVisibility(0);
        if (state != null) {
            page = state.getInt("page", HOME); selectedVendor = state.getInt("vendor", -1); selectedProduct = state.getInt("product", -1);
            selectedMicId = state.getInt("microphone", -1); savedPairPort = state.getString("pairPort", ""); savedConnectionPort = state.getString("connectPort", "");
            rememberedDongle = state.getString("dongleLabel", ""); rememberedMic = state.getString("micLabel", "");
        }
        root = column(this); root.setBackgroundColor(BG);
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        body = column(this); body.setPadding(d(24), d(20), d(24), d(28)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = row(this); navigation.setPadding(d(12), d(7), d(12), d(7)); navigation.setBackground(surface(this, BG, 0, 0));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            navigation.setVisibility(insets.isVisible(WindowInsets.Type.ime()) ? View.GONE : View.VISIBLE);
            return insets;
        });
        setContentView(root); getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, back); showPage(page);
    }

    private int d(float value) { return dp(this, value); }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, d(height))); }
    private TextView label(LinearLayout parent, String value, int size, int color, boolean medium) {
        TextView t = text(this, value, size, color, medium); parent.addView(t, new LinearLayout.LayoutParams(-1, -2)); return t;
    }
    private void eyebrow(LinearLayout parent, String value) {
        TextView t = label(parent, value, 10, VIOLET, true); t.setLetterSpacing(.16f);
    }
    private LinearLayout panel(LinearLayout parent) {
        LinearLayout card = column(this); card.setPadding(d(18), d(18), d(18), d(18)); card.setBackground(surface(this, PANEL, BORDER, 24));
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2)); return card;
    }
    private TextView action(LinearLayout parent, String title, boolean accent, View.OnClickListener listener) {
        TextView b = button(this, title, accent, listener); parent.addView(b, new LinearLayout.LayoutParams(-1, -2)); return b;
    }
    private void heading(String title, String subtitle) {
        LinearLayout header = row(this);
        Icon logo = new Icon(this, "route", VIOLET); header.addView(logo, new LinearLayout.LayoutParams(d(27), d(27)));
        TextView brand = text(this, "DongleRoute", 19, TEXT, true); LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1); bp.setMarginStart(d(10)); header.addView(brand, bp);
        connectionChip = text(this, "Set up", 11, VIOLET, true); connectionChip.setGravity(Gravity.CENTER); connectionChip.setMinHeight(d(48)); connectionChip.setPadding(d(13), d(8), d(13), d(8));
        clickable(connectionChip, PANEL, BORDER, 16); connectionChip.setOnClickListener(v -> showPage(CONNECTION)); connectionChip.setContentDescription("Manage local connection"); header.addView(connectionChip);
        body.addView(header); gap(body, 30);
        TextView h = label(body, title, 32, TEXT, true); h.setAccessibilityHeading(true);
        if (!subtitle.isEmpty()) { gap(body, 10); label(body, subtitle, 15, MUTED, false); }
        gap(body, 26);
    }

    private void showPage(int destination) {
        rememberPortFields(); page = destination;
        connectionChip = routeTitle = routeHint = dongleTitle = dongleHint = micTitle = micHint = primary = feedbackView = null;
        pairButton = connectButton = cancelButton = refreshButton = restoreButton = defaultsButton = forgetButton = statusButton = connectionSummary = null;
        pairingPort = pairingCode = connectionPort = null; dongleCard = microphoneCard = null;
        body.removeAllViews();
        if (page == CONNECTION) buildConnection(); else if (page == DEVICES) buildDevices(); else if (page == SETTINGS) buildSettings(); else buildHome();
        buildNavigation(); updateState(); scroll.scrollTo(0, 0);
    }

    private void buildHome() {
        heading("Keep the sound.\nChoose your mic.", "A phone microphone. Your wired headphones.");
        LinearLayout route = panel(body); eyebrow(route, "YOUR ROUTE"); gap(route, 18);
        dongleCard = routeRow(route, "headphones", MINT, "OUTPUT", true);
        LinearLayout flow = row(this); flow.setPadding(d(22), d(7), 0, d(7));
        TextView line = text(this, "│", 20, BORDER, false); flow.addView(line, new LinearLayout.LayoutParams(d(27), -2));
        TextView relationship = text(this, "Separate input. Same headphones.", 11, MUTED, false); flow.addView(relationship); route.addView(flow);
        microphoneCard = routeRow(route, "mic", VIOLET, "INPUT", false);
        gap(body, 18);
        LinearLayout status = row(this); status.setPadding(d(15), d(16), d(15), d(16)); status.setBackground(surface(this, INSET, 0, 18));
        Icon pulse = new Icon(this, "route", VIOLET); status.addView(pulse, new LinearLayout.LayoutParams(d(24), d(24)));
        LinearLayout words = column(this); LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1); wp.setMarginStart(d(12)); status.addView(words, wp);
        routeTitle = label(words, "Ready when you are", 14, TEXT, true); gap(words, 5); routeHint = label(words, "Connect this phone to get started.", 12, MUTED, false);
        clickable(status, INSET, 0, 18); status.setOnClickListener(v -> showRoutingDetails()); body.addView(status);
        gap(body, 24); primary = action(body, "Connect this phone", true, v -> primaryAction());
        gap(body, 12); TextView note = label(body, "Changes wait during calls or recordings.", 12, MUTED, false); note.setGravity(Gravity.CENTER);
        gap(body, 14); feedbackView = label(body, feedback, 13, AMBER, false);
    }

    private View routeRow(LinearLayout parent, String icon, int color, String caption, boolean output) {
        LinearLayout row = row(this); row.setPadding(d(13), d(15), d(13), d(15)); clickable(row, INSET, 0, 18); row.setMinimumHeight(d(87));
        LinearLayout symbol = row(this); symbol.setGravity(Gravity.CENTER); symbol.setBackground(surface(this, output ? 0xff203330 : 0xff30283f, 0, 14)); symbol.addView(new Icon(this, icon, color), new LinearLayout.LayoutParams(d(24), d(24))); row.addView(symbol, new LinearLayout.LayoutParams(d(46), d(46)));
        LinearLayout words = column(this); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMarginStart(d(13)); row.addView(words, p);
        TextView small = label(words, caption, 9, color, true); small.setLetterSpacing(.14f); gap(words, 7);
        TextView title = label(words, "", 16, TEXT, true); gap(words, 5); TextView detail = label(words, "", 11, MUTED, false);
        if (output) { dongleTitle = title; dongleHint = detail; } else { micTitle = title; micHint = detail; }
        row.addView(new Icon(this, "arrow", MUTED), new LinearLayout.LayoutParams(d(18), d(22)));
        buttonSemantics(row); row.setOnClickListener(v -> { if (selectionLocked()) message("Stop and restore the saved route before changing devices."); else if (output) chooseDongle(); else chooseMicrophone(); });
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row;
    }

    private void buildConnection() {
        heading("Connect this phone", "A local connection lets DongleRoute request your chosen microphone.");
        connectionSummary = label(body, "", 14, MINT, true); gap(body, 17);
        LinearLayout setup = panel(body); step(setup, "01", "Open Wireless debugging", "In Developer options, turn on Wireless debugging."); gap(setup, 14);
        action(setup, "Open Android settings", false, v -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (ActivityNotFoundException missing) { message("Open Developer options in Android Settings, then Wireless debugging."); }
        });
        gap(body, 14); LinearLayout pairing = panel(body); step(pairing, "02", "Pair once", "Open Pair device with pairing code. Keep its dialog visible beside this app in split-screen.");
        gap(pairing, 18); pairingPort = number(pairing, "Pairing port", "e.g. 37123", false); pairingPort.setText(savedPairPort);
        gap(pairing, 14); pairingCode = number(pairing, "Six-digit pairing code", "••••••", true);
        gap(pairing, 16); pairButton = action(pairing, "Pair this phone", false, v -> pair());
        gap(body, 14); LinearLayout connecting = panel(body); step(connecting, "03", "Connect", "Use the connection port on the main Wireless debugging screen. It is different from the pairing port.");
        gap(connecting, 18); connectionPort = number(connecting, "Connection port", "e.g. 40215", false); connectionPort.setText(savedConnectionPort);
        gap(connecting, 16); connectButton = action(connecting, "Connect", true, v -> connect());
        gap(body, 16); cancelButton = action(body, "Cancel / disconnect", false, v -> {
            if (RoutingService.running || session.needsRestoration()) { message("Restore saved preferences before disconnecting."); return; }
            session.cancelConnection(); message("Disconnected. Pairing is kept for next time.");
        });
        gap(body, 14); feedbackView = label(body, feedback, 13, AMBER, false);
        gap(body, 12); label(body, "Already paired? Skip to step 03. Ports can change after a reboot or Wireless debugging restart.", 12, MUTED, false);
    }

    private void step(LinearLayout parent, String n, String title, String explanation) {
        eyebrow(parent, "STEP " + n); gap(parent, 9); label(parent, title, 20, TEXT, true); gap(parent, 9); label(parent, explanation, 13, MUTED, false);
    }

    private EditText number(LinearLayout parent, String name, String hint, boolean secret) {
        label(parent, name, 12, MUTED, true); gap(parent, 8);
        EditText field = new EditText(this); field.setTextSize(18); field.setTextColor(TEXT); field.setHintTextColor(0xff6e687e); field.setHint(hint);
        field.setSingleLine(); field.setMinHeight(d(54)); field.setPadding(d(15), d(12), d(15), d(12)); field.setBackground(surface(this, INSET, BORDER, 13));
        field.setInputType(InputType.TYPE_CLASS_NUMBER | (secret ? InputType.TYPE_NUMBER_VARIATION_PASSWORD : 0)); field.setSaveEnabled(false);
        field.setContentDescription(name); field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if (secret) field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2)); return field;
    }

    private void buildDevices() {
        heading("Your devices", "Choose a USB audio output and a built-in phone microphone.");
        refreshButton = action(body, "Refresh devices", false, v -> refreshInventory(true)); gap(body, 23);
        eyebrow(body, "CONNECTED USB"); gap(body, 11);
        if (dongles.isEmpty()) emptyCard("Plug in your dongle", "Connected USB devices appear here. No USB access permission is needed to discover them.", "headphones");
        for (AutomationSession.DacChoice item : dongles) {
            boolean selected = item.vendor == selectedVendor && item.product == selectedProduct;
            deviceTile(dongleName(item), item.count != 1 ? "Multiple identical devices · routing unavailable" : item.audio ? "USB audio detected" : "No audio streaming interface detected", "headphones", selected, () -> {
                if (selectionLocked()) { message("Stop and restore before changing the route."); return; }
                if (!item.audio || item.count != 1) { showDeviceDetails(item); return; }
                selectedVendor = item.vendor; selectedProduct = item.product; message("USB output selected."); showPage(DEVICES);
            });
        }
        gap(body, 25); eyebrow(body, "BUILT-IN MICROPHONES"); gap(body, 11);
        if (microphones.isEmpty()) emptyCard("No phone mic reported", "Refresh to check Android's available inputs.", "mic");
        for (AutomationSession.MicChoice mic : microphones) deviceTile(micName(mic), mic.canonicalCount != 1 ? "Shared address · cannot select independently" : "Built-in phone input", "mic", mic.id == selectedMicId, () -> {
            if (selectionLocked()) { message("Stop and restore before changing the route."); return; }
            if (mic.id <= 0 || mic.canonicalCount != 1) { message("Android cannot distinguish this microphone for routing."); return; }
            selectedMicId = mic.id; message("Phone microphone selected."); showPage(DEVICES);
        });
        gap(body, 15); feedbackView = label(body, feedback, 13, AMBER, false);
    }

    private void emptyCard(String title, String explanation, String icon) {
        LinearLayout card = panel(body); card.addView(new Icon(this, icon, MUTED), new LinearLayout.LayoutParams(d(32), d(32))); gap(card, 16);
        label(card, title, 18, TEXT, true); gap(card, 8); label(card, explanation, 13, MUTED, false);
    }

    private void deviceTile(String title, String detail, String icon, boolean selected, Runnable click) {
        LinearLayout tile = row(this); tile.setPadding(d(16), d(17), d(15), d(17)); clickable(tile, PANEL, selected ? VIOLET : BORDER, 19); tile.setMinimumHeight(d(80));
        tile.addView(new Icon(this, icon, "mic".equals(icon) ? VIOLET : MINT), new LinearLayout.LayoutParams(d(26), d(26)));
        LinearLayout words = column(this); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMarginStart(d(15)); tile.addView(words, p);
        label(words, title, 15, TEXT, true); gap(words, 7); label(words, detail, 12, MUTED, false);
        tile.addView(new Icon(this, selected ? "check" : "arrow", selected ? VIOLET : MUTED), new LinearLayout.LayoutParams(d(20), d(22)));
        buttonSemantics(tile); tile.setOnClickListener(v -> click.run()); body.addView(tile, new LinearLayout.LayoutParams(-1, -2)); gap(body, 10);
    }

    private void buildSettings() {
        heading("A little control", "Connection, recovery, and the details when you need them.");
        setting("Local connection", session.isConnected() ? "Connected to this phone" : "Pair or reconnect this phone", () -> showPage(CONNECTION));
        statusButton = setting("Check compatibility", "Read current routing status without changing it", this::checkStatus);
        gap(body, 16); eyebrow(body, "RECOVERY"); gap(body, 12);
        restoreButton = setting("Restore saved preferences", "Stop routing and return to your previous choices", () -> startAction(RoutingService.STOP, false));
        defaultsButton = setting("Use Android defaults", "Let Android choose its microphone again", this::confirmDefaults);
        forgetButton = setting("Forget this pairing", "Remove this app's local connection identity", this::forget);
        gap(body, 16); eyebrow(body, "ABOUT"); gap(body, 12);
        setting("Routing details", "Support status and your selected devices", this::showRoutingDetails);
        setting("Open-source notices", "Libraries that make the local connection possible", this::showLicenses);
        gap(body, 18); label(body, "DongleRoute · 0.5 diagnostic", 12, MUTED, true); gap(body, 8);
        label(body, "Input preferences vary by phone. Verify your microphone and wired playback in the app you use.", 12, MUTED, false);
        gap(body, 16); feedbackView = label(body, feedback, 13, AMBER, false);
    }

    private TextView setting(String title, String detail, Runnable action) {
        TextView row = text(this, title + "\n" + detail, 14, TEXT, true); row.setLineSpacing(d(7), 1); row.setPadding(d(17), d(17), d(17), d(17)); row.setMinHeight(d(72));
        android.text.SpannableString styled = new android.text.SpannableString(row.getText()); styled.setSpan(new android.text.style.ForegroundColorSpan(MUTED), title.length() + 1, styled.length(), 0); styled.setSpan(new android.text.style.RelativeSizeSpan(.85f), title.length() + 1, styled.length(), 0); row.setText(styled);
        clickable(row, PANEL, BORDER, 18); row.setOnClickListener(v -> action.run()); buttonSemantics(row); body.addView(row, new LinearLayout.LayoutParams(-1, -2)); gap(body, 10); return row;
    }

    private void buildNavigation() {
        navigation.removeAllViews(); nav("Route", "route", HOME); nav("Devices", "devices", DEVICES); nav("Settings", "settings", SETTINGS);
    }
    private void nav(String title, String icon, int destination) {
        boolean selected = page == destination || (destination == SETTINGS && page == CONNECTION);
        LinearLayout item = column(this); item.setGravity(Gravity.CENTER); item.setPadding(d(7), d(9), d(7), d(9)); item.setMinimumHeight(d(57));
        clickable(item, selected ? 0xff282137 : BG, 0, 17); item.addView(new Icon(this, icon, selected ? VIOLET : MUTED), new LinearLayout.LayoutParams(d(23), d(23)));
        gap(item, 5); TextView label = text(this, title, 10, selected ? VIOLET : MUTED, true); label.setGravity(Gravity.CENTER); item.addView(label);
        buttonSemantics(item); item.setContentDescription(title); item.setSelected(selected); item.setOnClickListener(v -> showPage(destination)); navigation.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void updateState() {
        boolean busy = session.isBusy(), connected = session.isConnected(), active = RoutingService.running, debt = session.needsRestoration();
        if (connectionChip != null) { connectionChip.setText(connected ? "● Connected" : "Set up ↗"); connectionChip.setTextColor(connected ? MINT : VIOLET); }
        if (dongleTitle != null) {
            AutomationSession.DacChoice output = selectedDongle(); if (output != null) rememberedDongle = dongleName(output);
            dongleTitle.setText(output == null ? selectionLocked() ? rememberedDongle.isEmpty() ? "Saved USB output" : rememberedDongle : "Choose your dongle" : dongleName(output));
            dongleHint.setText(output == null ? selectionLocked() ? "Not currently detected" : "Tap to select USB output" : "Wired USB audio");
            AutomationSession.MicChoice input = selectedMicrophone(); if (input != null) rememberedMic = micName(input);
            micTitle.setText(input == null ? selectionLocked() ? rememberedMic.isEmpty() ? "Saved phone microphone" : rememberedMic : "Choose phone mic" : micName(input));
            micHint.setText(input == null ? selectionLocked() ? "Saved input identity" : "Tap to select a built-in input" : "Built-in microphone");
            enabled(dongleCard, !busy && !selectionLocked()); enabled(microphoneCard, !busy && !selectionLocked());
        }
        if (routeTitle != null) {
            String title = "Ready when you are", hint = "Connect this phone to get started.";
            if (!connected && debt) { title = "Reconnect to restore"; hint = "Saved microphone preferences may still be active."; }
            else if (connected && active) {
                String state = RoutingService.presentationState;
                if ("active".equals(state)) { title = "Microphone preference applied"; hint = "Verify voice and wired sound in your calling app."; }
                else if ("busy".equals(state)) { title = "Waiting for a quiet moment"; hint = "Changes resume after calls or recordings end."; }
                else if ("restoring".equals(state)) { title = "Restoring your preferences"; hint = "Keep the local connection open until this finishes."; }
                else if ("idle".equals(state) || "restored".equals(state)) { title = "Waiting for your dongle"; hint = "Routing resumes when the selected DAC reconnects."; }
                else { title = "Checking your route"; hint = "Confirming this phone supports the selected input."; }
            } else if (connected && debt) { title = "Saved route needs attention"; hint = "Restore it before starting a different route."; }
            else if (connected && "blocked".equals(RoutingService.presentationState)) { title = "Setup needs attention"; hint = "Tap for details. No successful route is assumed."; }
            else if (connected) { title = selectedDongle() == null ? "Plug in your dongle" : "Your route is ready to try"; hint = "Start to request this microphone for your USB setup."; }
            routeTitle.setText(title); routeHint.setText(hint);
        }
        if (primary != null) {
            primary.setText(!connected ? "Connect this phone" : active || debt ? "Stop & restore" : selectedDongle() == null ? "Find your dongle" : "Start routing");
            enabled(primary, !busy || active);
        }
        if (connectionSummary != null) connectionSummary.setText(connected ? "Connected · you can return to Route." : busy ? "Working on the local connection…" : "Your pairing stays on this phone.");
        enabled(pairButton, !busy && !active && !debt); enabled(connectButton, !busy && !active); enabled(cancelButton, !active && !debt && (busy || connected));
        enabled(refreshButton, !busy); enabled(statusButton, connected && !busy && !active);
        enabled(restoreButton, connected && !busy); enabled(defaultsButton, connected && !busy && selectedMicrophone() != null && inventoryKnown);
        enabled(forgetButton, !busy && !active && !debt);
        if (feedbackView != null) { feedbackView.setText(feedback); feedbackView.setVisibility(feedback.isEmpty() ? View.GONE : View.VISIBLE); }
    }

    private boolean selectionLocked() { return RoutingService.running || session.needsRestoration(); }
    private void primaryAction() {
        if (!session.isConnected()) { showPage(CONNECTION); return; }
        if (RoutingService.running || session.needsRestoration()) { startAction(RoutingService.STOP, false); return; }
        if (selectedDongle() == null) { refreshInventory(true); showPage(DEVICES); return; }
        if (selectedMicrophone() == null) { chooseMicrophone(); return; }
        new AlertDialog.Builder(this).setTitle("Start this route?").setMessage("Use the selected phone microphone with your USB headphones. Changes wait during calls or recordings. Keep the connection and notification active; Stop restores your previous preferences.")
                .setNegativeButton("Not now", null).setPositiveButton("Start routing", (dialog, which) -> startAction(RoutingService.ENABLE, true)).show();
    }

    private void refreshInventory(boolean report) {
        if (session.isBusy()) { if (report) message("Wait for the current action to finish."); return; }
        inventoryAttempted = true; inventoryDirty = false;
        session.inventory((inventory, error) -> {
            if (isDestroyed()) return;
            if (error != null) { inventoryKnown = false; message(error); return; }
            inventoryKnown = true; dongles = inventory.dongles; microphones = inventory.microphones;
            if (RoutingService.running) { selectedVendor = RoutingService.configuredVendor; selectedProduct = RoutingService.configuredProduct; selectedMicId = RoutingService.configuredMicId; }
            if (!selectionLocked()) {
                if (selectedDongle() == null) { for (AutomationSession.DacChoice choice : dongles) if (choice.audio && choice.count == 1) { selectedVendor = choice.vendor; selectedProduct = choice.product; break; } }
                if (selectedMicrophone() == null) {
                    int valid = 0, sole = -1, bottoms = 0, bottom = -1;
                    for (AutomationSession.MicChoice mic : microphones) if (mic.id > 0 && mic.canonicalCount == 1) { valid++; sole = mic.id; if ("bottom".equals(mic.address)) { bottoms++; bottom = mic.id; } }
                    if (bottoms == 1) selectedMicId = bottom; else if (valid == 1 && microphones.size() == 1) selectedMicId = sole;
                }
            }
            if (report) feedback = dongles.isEmpty() ? "No USB device connected. Plug in your dongle and refresh." : "Devices refreshed.";
            if (page == HOME || page == DEVICES) showPage(page); else updateState();
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
        return "Phone mic · " + m.address;
    }

    private void chooseDongle() {
        if (selectionLocked()) return;
        List<AutomationSession.DacChoice> valid = new ArrayList<>(); for (AutomationSession.DacChoice choice : dongles) if (choice.audio && choice.count == 1) valid.add(choice);
        if (valid.isEmpty()) { showPage(DEVICES); message("Connect one USB audio dongle, then refresh."); return; }
        String[] names = new String[valid.size()]; for (int i = 0; i < names.length; i++) names[i] = dongleName(valid.get(i));
        new AlertDialog.Builder(this).setTitle("USB headphone output").setItems(names, (dialog, which) -> { selectedVendor = valid.get(which).vendor; selectedProduct = valid.get(which).product; showPage(HOME); }).setNegativeButton("Cancel", null).show();
    }
    private void chooseMicrophone() {
        if (selectionLocked()) return;
        List<AutomationSession.MicChoice> valid = new ArrayList<>(); for (AutomationSession.MicChoice mic : microphones) if (mic.id > 0 && mic.canonicalCount == 1) valid.add(mic);
        if (valid.isEmpty()) { showPage(DEVICES); message("No unambiguous built-in microphone is available. Refresh to check again."); return; }
        String[] names = new String[valid.size()]; for (int i = 0; i < names.length; i++) names[i] = micName(valid.get(i));
        new AlertDialog.Builder(this).setTitle("Phone microphone").setItems(names, (dialog, which) -> { selectedMicId = valid.get(which).id; showPage(HOME); }).setNegativeButton("Cancel", null).show();
    }

    private Integer port(EditText field) {
        try { int value = Integer.parseInt(field.getText().toString().trim()); if (value < 1 || value > 65535) throw new NumberFormatException(); return value; }
        catch (NumberFormatException invalid) { message("Enter the port shown in Android Settings (1–65535)."); return null; }
    }
    private void pair() {
        Integer port = port(pairingPort); if (port == null) return;
        char[] code = new char[pairingCode.length()]; pairingCode.getText().getChars(0, code.length, code, 0); pairingCode.setText("");
        boolean valid = code.length == 6; for (char digit : code) valid &= digit >= '0' && digit <= '9';
        if (!valid) { Arrays.fill(code, '\0'); message("Enter the current six-digit pairing code."); return; }
        message("Pairing. Keep Android's pairing dialog visible.");
        session.pair(port, code, (result, error) -> { message(error == null ? "Paired. Now use the separate connection port below." : error); if (error == null && connectionPort != null) connectionPort.requestFocus(); });
    }
    private void connect() {
        Integer port = port(connectionPort); if (port == null) return; rememberPortFields(); message("Connecting to this phone…");
        session.connect(port, (result, error) -> { if (isDestroyed()) return; if (error != null) { message(error); return; } feedback = "Connected. Choose your route and start when ready."; showPage(HOME); });
    }

    private void checkStatus() {
        if (!session.isConnected()) { showPage(CONNECTION); return; }
        AutomationSession.DacChoice d = selectedDongle(); AutomationSession.MicChoice m = selectedMicrophone();
        message("Checking this phone's routing support…");
        session.routing(RoutingBridge.Action.STATUS, d == null ? -1 : d.vendor, d == null ? -1 : d.product, m == null ? -1 : m.id,
                (result, error) -> { if (isDestroyed()) return; message(error == null ? result.describe() : error); showRoutingDetails(); });
    }
    private void confirmDefaults() {
        new AlertDialog.Builder(this).setTitle("Let Android choose again?").setMessage("This stops routing and clears matching phone-microphone preferences, including ones set manually. To keep your earlier choices, use Restore saved preferences instead.")
                .setNegativeButton("Cancel", null).setPositiveButton("Use Android defaults", (dialog, which) -> startAction(RoutingService.DEFAULTS, true)).show();
    }
    private void forget() {
        if (RoutingService.running || session.isBusy() || session.needsRestoration()) { message("Restore saved preferences before forgetting pairing."); return; }
        new AlertDialog.Builder(this).setTitle("Forget this phone's pairing?").setMessage("Only this app's local identity is removed. You can also revoke its paired-device entry in Android's Wireless debugging settings.")
                .setNegativeButton("Keep pairing", null).setPositiveButton("Forget", (dialog, which) -> session.forget((result, error) -> message(error == null ? "Pairing forgotten. Pair again to reconnect." : error))).show();
    }
    private boolean notificationsReady() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 40); message("Allow the visible Stop notification, then tap the action again."); return false;
        }
        NotificationManager manager = getSystemService(NotificationManager.class); NotificationChannel channel = manager.getNotificationChannel("automatic_input_routing");
        if (!manager.areNotificationsEnabled() || (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)) { message("Enable DongleRoute's routing notifications in Android Settings first."); return false; }
        return true;
    }
    private void startAction(String action, boolean needsSelection) {
        if (!session.isConnected()) { showPage(CONNECTION); message("Reconnect before changing or restoring preferences."); return; }
        if (session.isBusy() && !RoutingService.running) { message("Wait for the current action to finish."); return; }
        AutomationSession.DacChoice d = selectedDongle(); AutomationSession.MicChoice m = selectedMicrophone();
        if (!RoutingService.STOP.equals(action) && m == null) { message("Choose an unambiguous phone microphone first."); return; }
        if (needsSelection && !RoutingService.running && d == null && (!inventoryKnown || !dongles.isEmpty() || RoutingService.ENABLE.equals(action))) { showPage(DEVICES); message("Select one connected USB audio dongle first."); return; }
        if (!RoutingService.running && !notificationsReady()) return;
        Intent request = new Intent(this, RoutingService.class).setAction(action); if (d != null) request.putExtra("vendor", d.vendor).putExtra("product", d.product); if (m != null) request.putExtra("microphone", m.id);
        try { if (RoutingService.running) startService(request); else startForegroundService(request); feedback = ""; updateState(); }
        catch (RuntimeException failure) { message("The routing service could not start. Saved preferences have not been verified restored."); }
    }

    private void showRoutingDetails() {
        String info = session.isConnected() ? "Local connection: connected" : "Local connection: disconnected";
        info += "\n\n" + RoutingService.status;
        RoutingProtocol.Result last = session.lastRoutingResult;
        if (last != null) info += "\n\nLast preference check\n" + last.describe();
        info += "\n\nSaved preferences may need restoration: " + (session.needsRestoration() ? "yes" : "no");
        info += "\n\nPolicy readback does not prove the microphone used by a calling app. Verify voice and audible wired output there.";
        new AlertDialog.Builder(this).setTitle("Routing details").setMessage(info).setPositiveButton("Done", null).show();
    }
    private void showDeviceDetails(AutomationSession.DacChoice item) {
        new AlertDialog.Builder(this).setTitle(dongleName(item)).setMessage(String.format(Locale.ROOT, "USB %04x:%04x\n%s\nConnected devices with these identifiers: %d", item.vendor, item.product, item.audio ? "Audio streaming interfaces detected." : "No audio streaming interface detected.", item.count)).setPositiveButton("Done", null).show();
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
                    content.setPadding(d(20), d(20), d(20), d(20)); content.setTextIsSelectable(true); scroll.addView(content);
                    new AlertDialog.Builder(this).setTitle(files[which]).setView(scroll).setPositiveButton("Done", null).show();
                } catch (Exception unavailable) { message("This notice could not be opened."); }
            }).setNegativeButton("Done", null).show();
        } catch (Exception unavailable) { message("Open-source notices are unavailable."); }
    }

    private void message(String text) { feedback = text; if (!isDestroyed()) updateState(); }
    private void rememberPortFields() {
        if (pairingPort != null) savedPairPort = pairingPort.getText().toString();
        if (connectionPort != null) savedConnectionPort = connectionPort.getText().toString();
        if (pairingCode != null) pairingCode.setText("");
    }
    private void dirtyInventory() { inventoryDirty = true; refreshAfter = SystemClock.uptimeMillis() + 300; }
    @Override protected void onStart() {
        super.onStart(); dirtyInventory();
        getSystemService(AudioManager.class).registerAudioDeviceCallback(audioDevices, main);
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED); filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbDevices, filter, Context.RECEIVER_EXPORTED); observersRegistered = true; main.post(ticker);
    }
    @Override protected void onStop() {
        main.removeCallbacks(ticker); rememberPortFields();
        if (observersRegistered) { getSystemService(AudioManager.class).unregisterAudioDeviceCallback(audioDevices); unregisterReceiver(usbDevices); observersRegistered = false; }
        super.onStop();
    }
    @Override protected void onDestroy() { getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(back); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        rememberPortFields(); state.putInt("page", page); state.putInt("vendor", selectedVendor); state.putInt("product", selectedProduct); state.putInt("microphone", selectedMicId);
        state.putString("pairPort", savedPairPort); state.putString("connectPort", savedConnectionPort);
        state.putString("dongleLabel", rememberedDongle); state.putString("micLabel", rememberedMic);
        super.onSaveInstanceState(state);
    }
}
