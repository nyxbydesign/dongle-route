package dev.usbmic.inspector.automation;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.usbmic.inspector.MainActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowPopupMenu;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Actual Android Views and sandbox inventory. No ADB connection or policy invocation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class UiScreenshotTest {
    private ActivityController<MainActivity> controller;

    @After public void close() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        Field instance = AutomationSession.class.getDeclaredField("instance"); instance.setAccessible(true);
        AutomationSession session = (AutomationSession) instance.get(null);
        if (session != null) {
            Field worker = AutomationSession.class.getDeclaredField("worker"); worker.setAccessible(true);
            ((ExecutorService) worker.get(session)).shutdownNow();
        }
        instance.set(null, null); RoutingService.running = RoutingService.sessionRunning = RoutingService.connecting = RoutingService.routingRequested = RoutingService.restoring = false; RoutingService.presentationState = "off";
    }

    @Test public void homeFitsAndHasNoExtraPages() throws Exception {
        MainActivity activity = start(412, 892);
        View decor = activity.getWindow().getDecorView();
        assertNotNull(text(decor, "Connect"));
        assertNull(text(decor, "Settings")); assertNull(text(decor, "Devices"));
        assertNotNull(text(decor, "LOCAL CONTROL / OFFLINE"));
        ScrollView scroll = (ScrollView) get(activity, "scroll");
        assertTrue("Normal layout fits the phone", scroll.getChildAt(0).getHeight() <= scroll.getHeight());
        assertFalse(AutomationSession.get(activity).isConnected()); assertFalse(RoutingService.running);
        capture(activity, null, "home-unpaired", 412, 892);
    }

    @Test public void pairingAndConnectionShareOneCompactSheet() throws Exception {
        MainActivity activity = start(412, 892);
        ((View) get(activity, "primary")).performClick();
        Dialog dialog = (Dialog) get(activity, "connectionDialog");
        View sheet = dialog.getWindow().getDecorView();
        measureSheet(sheet, 412, 892);
        assertNotNull(text(sheet, "Pairing port")); assertNotNull(text(sheet, "Pairing code"));
        assertNull(text(sheet, "Connection port"));
        assertTrue("Pairing sheet is compact", sheet.getMeasuredHeight() < 892 * .82f);
        capture(activity, dialog, "pair-sheet", 412, 892);
        EditText code = (EditText) get(activity, "pairingCode"); code.setText("123456");
        ((View) get(activity, "sheetAlternate")).performClick();
        assertSame(dialog, get(activity, "connectionDialog"));
        measureSheet(sheet, 412, 892);
        assertEquals("Secret is cleared when changing form", 0, code.length());
        assertNotNull(text(sheet, "Connection port")); assertNull(text(sheet, "Pairing code"));
        capture(activity, dialog, "connect-sheet", 412, 892);
        invoke(activity, "connectionCompleted");
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNull(get(activity, "connectionDialog"));
        assertFalse(RoutingService.running);
        assertNull("Connection does not start a route", shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        assertNull(AutomationSession.get(activity).lastRoutingResult);
    }

    @Test public void localIdentityOnlyChoosesShorterForm() throws Exception {
        File folder = new File(RuntimeEnvironment.getApplication().getNoBackupFilesDir(), "local-adb");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        assertTrue(new File(folder, "identity.pk8").createNewFile());
        assertTrue(new File(folder, "identity.crt").createNewFile());
        RuntimeEnvironment.getApplication().getSharedPreferences("routing_state", 0).edit().putInt("last_connection_port", 45678).commit();
        MainActivity activity = start(412, 892); ((View) get(activity, "primary")).performClick();
        Dialog dialog = (Dialog) get(activity, "connectionDialog");
        View sheet = dialog.getWindow().getDecorView();
        assertNotNull(text(sheet, "Connection port")); assertNull(text(sheet, "Pairing code"));
        assertEquals("45678", ((EditText) get(activity, "connectionPort")).getText().toString());
        assertFalse("Identity files do not prove connectivity", AutomationSession.get(activity).isConnected());
        assertNotNull(text(activity.getWindow().getDecorView(), "LOCAL CONTROL / OFFLINE"));
    }

    @Test public void sampleSelectionDoesNotClaimAnAppliedRoute() throws Exception {
        MainActivity activity = start(412, 892);
        set(activity, "dongles", Arrays.asList(new AutomationSession.DacChoice(0x1234, 1, "USB-C audio dongle", true)));
        set(activity, "microphones", Arrays.asList(new AutomationSession.MicChoice(100, "Demo phone", "")));
        set(activity, "selectedVendor", 0x1234); set(activity, "selectedProduct", 1); set(activity, "selectedMicId", 100);
        invoke(activity, "updateState"); layout(activity, 412, 892);
        assertNotNull(text(activity.getWindow().getDecorView(), "USB-C audio dongle"));
        assertNotNull(text(activity.getWindow().getDecorView(), "Phone microphone"));
        assertNull(text(activity.getWindow().getDecorView(), "Phone mic preference set"));
        assertNotNull(text(activity.getWindow().getDecorView(), "Connect"));
        assertFalse(RoutingService.running); assertFalse(AutomationSession.get(activity).isConnected());
        capture(activity, null, "home-selected-unpaired", 412, 892);
    }

    @Test public void narrowLargeFontKeepsPrimaryReachable() throws Exception {
        RuntimeEnvironment.setQualifiers("w320dp-h740dp-mdpi"); RuntimeEnvironment.setFontScale(1.5f);
        MainActivity activity = start(320, 740);
        ScrollView scroll = (ScrollView) get(activity, "scroll");
        capture(activity, null, "home-large-font", 320, 740);
        scroll.setSmoothScrollingEnabled(false); scroll.fullScroll(View.FOCUS_DOWN); layout(activity, 320, 740);
        View button = (View) get(activity, "primary"); int[] xy = new int[2]; button.getLocationOnScreen(xy);
        assertTrue(button.getHeight() >= 48);
        assertTrue("Main action remains reachable", xy[1] >= 0 && xy[1] + button.getHeight() <= 740);
        capture(activity, null, "home-large-font-action", 320, 740);
    }

    @Test public void offlineRecoveryLocksSelectionAndForget() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("routing_state", 0).edit().putBoolean("may_need_restore", true).commit();
        MainActivity activity = start(412, 892);
        assertNotNull(text(activity.getWindow().getDecorView(), "Reconnect to restore"));
        assertFalse(((View) get(activity, "dongleRow")).isEnabled()); assertFalse(((View) get(activity, "microphoneRow")).isEnabled());
        ((View) get(activity, "overflow")).performClick();
        PopupMenu menu = ShadowPopupMenu.getLatestPopupMenu();
        assertFalse(menu.getMenu().findItem(8).isEnabled());
        assertFalse(menu.getMenu().findItem(2).isEnabled()); assertFalse(menu.getMenu().findItem(3).isEnabled());
        assertTrue(menu.getMenu().findItem(1).isEnabled()); menu.dismiss();
        ((View) get(activity, "primary")).performClick();
        assertFalse((Boolean) get(activity, "pairingMode"));
        ((View) get(activity, "sheetAlternate")).performClick();
        assertTrue((Boolean) get(activity, "pairingMode"));
        assertTrue("Same-key re-pair is available while restoration is pending", ((View) get(activity, "sheetAction")).isEnabled());
        assertTrue(AutomationSession.get(activity).needsRestoration());
        ((Dialog) get(activity, "connectionDialog")).dismiss(); shadowOf(android.os.Looper.getMainLooper()).idle();
        capture(activity, null, "home-recovery", 412, 892);
    }

    private Object get(AutomationActivity a, String name) throws Exception {
        Field field = AutomationActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(a);
    }
    private void set(AutomationActivity a, String name, Object value) throws Exception {
        Field field = AutomationActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(a, value);
    }
    private void invoke(AutomationActivity a, String name) throws Exception {
        Method method = AutomationActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(a);
    }
    private MainActivity start(int width, int height) throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
        MainActivity activity = controller.get(); shadowOf(android.os.Looper.getMainLooper()).idle();
        for (int n = 0; n < 100 && AutomationSession.get(activity).isBusy(); n++) {
            Thread.sleep(10); shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        shadowOf(android.os.Looper.getMainLooper()).idle(); layout(activity, width, height); return activity;
    }
    private void layout(Activity activity, int width, int height) {
        View view = activity.getWindow().getDecorView(); view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); view.layout(0, 0, width, height);
    }
    private void measureSheet(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST)); view.layout(0, 0, width, view.getMeasuredHeight());
    }
    private void capture(Activity activity, Dialog dialog, String name, int width, int height) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(bitmap);
        activity.getWindow().getDecorView().draw(canvas);
        if (dialog != null) {
            canvas.drawColor(0x88000000); View sheet = dialog.getWindow().getDecorView(); measureSheet(sheet, width, height);
            canvas.save(); canvas.translate(0, height - sheet.getHeight()); sheet.draw(canvas); canvas.restore();
        }
        File folder = new File(System.getProperty("dongleroute.screenshots")); assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream file = new FileOutputStream(new File(folder, name + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, file)); }
        bitmap.recycle();
    }
    private TextView text(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int n = 0; n < ((ViewGroup) view).getChildCount(); n++) { TextView t = text(((ViewGroup) view).getChildAt(n), value); if (t != null) return t; }
        return null;
    }
}
