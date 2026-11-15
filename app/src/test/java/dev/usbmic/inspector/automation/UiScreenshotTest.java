package dev.usbmic.inspector.automation;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
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
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Renders actual Android Views in an unpaired sandbox; no transport connection or audio-policy call. */
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
        instance.set(null, null); RoutingService.running = false; RoutingService.presentationState = "off";
    }

    @Test public void unpairedHomeHasSingleClearAction() throws Exception {
        MainActivity activity = start(412, 892);
        TextView start = text(activity.getWindow().getDecorView(), "Connect this phone");
        assertNotNull(start); assertTrue(start.isEnabled()); assertTrue(start.getHeight() >= 48);
        assertFalse(AutomationSession.get(activity).isConnected());
        assertNotNull(text(activity.getWindow().getDecorView(), "Ready when you are"));
        assertNull(text(activity.getWindow().getDecorView(), "Microphone preference applied"));
        capture(activity, "home-unpaired", 412, 892);
    }

    @Test public void connectionPageRendersThreeExplicitSteps() throws Exception {
        MainActivity activity = start(412, 892);
        text(activity.getWindow().getDecorView(), "Set up ↗").performClick();
        layout(activity, 412, 892);
        assertNotNull(text(activity.getWindow().getDecorView(), "Pair once"));
        assertNotNull(text(activity.getWindow().getDecorView(), "Pairing port"));
        assertNotNull(text(activity.getWindow().getDecorView(), "Connection port"));
        assertNotNull(text(activity.getWindow().getDecorView(), "Six-digit pairing code"));
        capture(activity, "connection-top", 412, 892);
        ScrollView scroll = scroll(activity.getWindow().getDecorView()); scroll.setSmoothScrollingEnabled(false); scroll.fullScroll(View.FOCUS_DOWN); layout(activity, 412, 892);
        capture(activity, "connection-connect", 412, 892);
        assertFalse(AutomationSession.get(activity).isConnected());
    }

    @Test public void narrowLargeFontKeepsNavigationAndScrollableControls() throws Exception {
        RuntimeEnvironment.setQualifiers("w320dp-h740dp-mdpi"); RuntimeEnvironment.setFontScale(1.5f);
        MainActivity activity = start(320, 740);
        assertNotNull(text(activity.getWindow().getDecorView(), "Route"));
        assertNotNull(text(activity.getWindow().getDecorView(), "Settings"));
        ScrollView scroll = scroll(activity.getWindow().getDecorView());
        assertTrue("Large text content remains scrollable", scroll.getChildAt(0).getHeight() > scroll.getHeight());
        capture(activity, "home-large-font", 320, 740);
        scroll.setSmoothScrollingEnabled(false); scroll.fullScroll(View.FOCUS_DOWN); layout(activity, 320, 740);
        TextView start = text(activity.getWindow().getDecorView(), "Connect this phone");
        int[] xy = new int[2]; start.getLocationOnScreen(xy);
        assertTrue("Primary action can be reached by scrolling", xy[1] >= 0 && xy[1] + start.getHeight() <= 740);
        capture(activity, "home-large-font-action", 320, 740);
    }

    @Test public void sampleInventoryDoesNotImplyRoutingOrConnection() throws Exception {
        MainActivity activity = start(412, 892);
        set(activity, "dongles", Arrays.asList(new AutomationSession.DacChoice(0x1234, 1, "USB-C audio dongle", true)));
        set(activity, "microphones", Arrays.asList(new AutomationSession.MicChoice(100, "Demo phone", "")));
        set(activity, "selectedVendor", 0x1234); set(activity, "selectedProduct", 1); set(activity, "selectedMicId", 100);
        Method page = AutomationActivity.class.getDeclaredMethod("showPage", int.class); page.setAccessible(true); page.invoke(activity, 0);
        layout(activity, 412, 892);
        assertNotNull(text(activity.getWindow().getDecorView(), "USB-C audio dongle"));
        assertNotNull(text(activity.getWindow().getDecorView(), "Phone microphone"));
        assertNotNull(text(activity.getWindow().getDecorView(), "Connect this phone"));
        assertNull(text(activity.getWindow().getDecorView(), "Microphone preference applied"));
        assertFalse(AutomationSession.get(activity).isConnected()); assertFalse(RoutingService.running);
        capture(activity, "home-sample-inventory-unpaired", 412, 892);
        page.invoke(activity, 1); layout(activity, 412, 892);
        capture(activity, "devices-sample-inventory", 412, 892);
    }

    @Test public void offlineRecoveryKeepsSelectionAndForgetLocked() throws Exception {
        assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences("routing_state", 0).edit().putBoolean("may_need_restore", true).commit());
        MainActivity activity = start(412, 892);
        assertNotNull(text(activity.getWindow().getDecorView(), "Reconnect to restore"));
        assertFalse(((View) get(activity, "dongleCard")).isEnabled());
        assertFalse(((View) get(activity, "microphoneCard")).isEnabled());
        Method page = AutomationActivity.class.getDeclaredMethod("showPage", int.class); page.setAccessible(true); page.invoke(activity, 2);
        layout(activity, 412, 892);
        assertFalse(((View) get(activity, "forgetButton")).isEnabled());
        assertFalse(((View) get(activity, "restoreButton")).isEnabled());
        assertFalse(((View) get(activity, "defaultsButton")).isEnabled());
        assertFalse(AutomationSession.get(activity).isConnected());
        capture(activity, "settings-offline-recovery", 412, 892);
    }

    private Object get(AutomationActivity activity, String name) throws Exception {
        Field field = AutomationActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }

    private void set(AutomationActivity activity, String name, Object value) throws Exception {
        Field field = AutomationActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity, value);
    }

    private MainActivity start(int width, int height) throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
        MainActivity activity = controller.get();
        shadowOf(android.os.Looper.getMainLooper()).idle();
        for (int n = 0; n < 100 && AutomationSession.get(activity).isBusy(); n++) {
            Thread.sleep(10); shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        shadowOf(android.os.Looper.getMainLooper()).idle(); layout(activity, width, height); return activity;
    }
    private void layout(Activity activity, int width, int height) {
        View view = activity.getWindow().getDecorView(); view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); view.layout(0, 0, width, height);
    }
    private void capture(Activity activity, String name, int width, int height) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        activity.getWindow().getDecorView().draw(new Canvas(bitmap));
        File folder = new File(System.getProperty("dongleroute.screenshots")); assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream file = new FileOutputStream(new File(folder, name + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, file)); }
        bitmap.recycle();
    }
    private TextView text(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int n = 0; n < ((ViewGroup) view).getChildCount(); n++) { TextView result = text(((ViewGroup) view).getChildAt(n), value); if (result != null) return result; }
        return null;
    }
    private ScrollView scroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) for (int n = 0; n < ((ViewGroup) view).getChildCount(); n++) { ScrollView result = scroll(((ViewGroup) view).getChildAt(n)); if (result != null) return result; }
        return null;
    }
}
