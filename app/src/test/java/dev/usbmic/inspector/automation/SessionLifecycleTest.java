package dev.usbmic.inspector.automation;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Service/session boundaries use a fake transport. No sockets, pairing or audio policy calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SessionLifecycleTest {
    private FakeSession session;
    private ServiceController<RoutingService> controller;
    private RoutingService service;

    @Before public void setup() throws Exception {
        session = new FakeSession(RuntimeEnvironment.getApplication());
        Field instance = AutomationSession.class.getDeclaredField("instance"); instance.setAccessible(true); instance.set(null, session);
        controller = Robolectric.buildService(RoutingService.class).create(); service = controller.get(); session.service = service;
    }
    @After public void cleanup() throws Exception {
        if (controller != null) controller.destroy();
        Field worker = AutomationSession.class.getDeclaredField("worker"); worker.setAccessible(true); ((ExecutorService) worker.get(session)).shutdownNow();
        Field instance = AutomationSession.class.getDeclaredField("instance"); instance.setAccessible(true); instance.set(null, null);
        RoutingService.running = RoutingService.sessionRunning = RoutingService.connecting = RoutingService.routingRequested = RoutingService.restoring = false;
        RoutingService.presentationState = "off";
    }
    private void connect() {
        service.onStartCommand(new Intent(service, RoutingService.class).setAction(RoutingService.CONNECT).putExtra("port", 5555), 0, 1);
        shadowOf(Looper.getMainLooper()).idle();
    }
    private void route() {
        service.onStartCommand(new Intent(service, RoutingService.class).setAction(RoutingService.ENABLE)
                .putExtra("vendor", 1).putExtra("product", 2).putExtra("microphone", 100), 0, 2);
    }

    @Test public void explicitConnectIsForegroundBeforeTransportAndNeverStartsRouting() {
        connect();
        assertTrue(session.foregroundAtConnect); assertTrue(session.connected);
        assertTrue(RoutingService.sessionRunning); assertFalse(RoutingService.running); assertFalse(RoutingService.routingRequested);
        assertEquals(0, session.routingCalls); assertFalse(shadowOf(service).isForegroundStopped());
    }
    @Test public void recentsRemovalLeavesIdleSessionAliveAndHeartbeatContinues() {
        connect(); service.onTaskRemoved(new Intent());
        assertTrue(RoutingService.sessionRunning); assertTrue(session.connected); assertFalse(shadowOf(service).isStoppedBySelf());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        assertEquals(1, session.heartbeats); assertEquals(0, session.disconnects); assertEquals(0, session.routingCalls);
        assertTrue(SessionEvents.read(service).contains("task_removed"));
    }
    @Test public void stopRestoresButKeepsTheIdleConnection() {
        connect(); route(); assertTrue(RoutingService.running);
        service.onStartCommand(new Intent().setAction(RoutingService.STOP), 0, 3);
        assertFalse(RoutingService.running); assertFalse(RoutingService.routingRequested); assertTrue(RoutingService.sessionRunning);
        assertTrue(session.connected); assertFalse(session.debt); assertFalse(shadowOf(service).isForegroundStopped()); assertEquals(0, session.disconnects);
    }
    @Test public void lostConnectionParksWithoutRetryOrClearingRestoration() {
        connect(); session.debt = true; session.failHeartbeat = true;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        assertFalse(session.connected); assertFalse(session.wantsConnection()); assertTrue(session.debt);
        assertFalse(RoutingService.routingRequested); assertFalse(RoutingService.running);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2));
        assertEquals(1, session.connects); assertEquals(1, session.heartbeats);
    }
    @Test public void deviceTransitionAndNewGenerationKeepRequestedRouting() {
        connect();
        session.results.addAll(Arrays.asList(result("waiting", false), result("active", true), result("restored", false), result("active", true)));
        route(); assertTrue(RoutingService.routingRequested); assertEquals("waiting", RoutingService.presentationState);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2)); assertEquals("active", RoutingService.presentationState);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5)); assertEquals("restored", RoutingService.presentationState); assertTrue(RoutingService.routingRequested);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5)); assertEquals("active", RoutingService.presentationState);
        assertEquals(Arrays.asList(100, 100, -1, -1), session.microphoneArguments);
        assertTrue(session.commands.get(0).endsWith(" tick 1 2 100"));
        assertTrue(session.commands.get(2).endsWith(" tick-retained 1 2"));
        assertTrue(session.commands.get(3).endsWith(" tick-retained 1 2"));
    }
    @Test public void explicitDisconnectRestoresBeforeClosingAndDoesNotRestart() {
        connect(); route();
        service.onStartCommand(new Intent().setAction(RoutingService.DISCONNECT), 0, 3);
        assertFalse(session.debt); assertFalse(session.connected); assertFalse(session.wantsConnection());
        assertFalse(RoutingService.sessionRunning); assertTrue(shadowOf(service).isStoppedBySelf()); assertTrue(shadowOf(service).isForegroundStopped());
        assertTrue(session.actions.indexOf("restore") < session.actions.indexOf("disconnect"));
        int calls = session.connects; service.onStartCommand(null, 0, 4); assertEquals(calls, session.connects);
    }
    @Test public void osReclaimConsumesOneAttemptAndNeverResumesRouting() {
        session.setConnectionWanted(true); session.failConnect = true;
        service.onStartCommand(null, 0, 1);
        assertFalse("Recovery intent is consumed before attempting the socket", session.wantedAtConnect);
        assertEquals(1, session.connects); assertFalse(session.wantsConnection()); assertFalse(RoutingService.running); assertEquals(0, session.routingCalls);
        service.onStartCommand(null, 0, 2); assertEquals(1, session.connects); assertTrue(shadowOf(service).isStoppedBySelf());
    }
    @Test public void forgetClosesOwnerAndDurableIntentBeforeIdentityRemoval() {
        connect(); assertTrue(RoutingService.prepareToForget(service));
        assertFalse(RoutingService.sessionRunning); assertFalse(session.connected); assertFalse(session.wantsConnection()); assertTrue(shadowOf(service).isForegroundStopped());
        session.debt = true; assertFalse(RoutingService.prepareToForget(service)); assertTrue(session.debt);
    }
    @Test public void capabilityBlockStopsRoutingWhileConnectionStaysOwned() {
        connect(); session.results.add(new RoutingProtocol.Result(false, "blocked", "API unavailable", true, false)); route();
        assertFalse(RoutingService.running); assertTrue(RoutingService.sessionRunning); assertTrue(session.connected);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16)); assertEquals(1, session.heartbeats); assertEquals(1, session.routingCalls);
    }
    @Test public void defaultsWaitsForIdleAndInventoryEvenBeforeAnyWrite() {
        connect(); session.restoreResults.addAll(Arrays.asList(result("busy", false), result("waiting", false), result("restored", false)));
        service.onStartCommand(new Intent().setAction(RoutingService.DEFAULTS).putExtra("microphone", 100), 0, 2);
        assertTrue(RoutingService.running); assertTrue(RoutingService.restoring);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5)); assertEquals("waiting", RoutingService.presentationState); assertTrue(RoutingService.running);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2)); assertFalse(RoutingService.running); assertTrue(RoutingService.sessionRunning); assertTrue(session.connected);
    }
    @Test public void failedPersistenceCannotLeaveAnAuthenticationRetryOwner() {
        session.failArm = true; connect();
        assertFalse(session.connected); assertFalse(RoutingService.sessionRunning); assertFalse(session.wantsConnection());
        assertTrue(shadowOf(service).isStoppedBySelf());
        session.failArm = false; connect(); session.debt = true; session.failClear = true; session.failHeartbeat = true;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        assertFalse(RoutingService.sessionRunning); assertTrue(shadowOf(service).isStoppedBySelf()); assertTrue(session.debt);
        int attempts = session.connects; service.onStartCommand(null, 0, 5); assertEquals(attempts, session.connects);
    }
    @Test public void delayedOldOwnerCallbackCannotCloseANewerConnection() {
        session.deferConnect = true; connect();
        AutomationSession.Callback<Boolean> oldCallback = session.deferred;
        service.onStartCommand(new Intent().setAction(RoutingService.DISCONNECT), 0, 2); controller.destroy();
        controller = Robolectric.buildService(RoutingService.class).create(); service = controller.get(); session.service = service;
        session.deferConnect = false; connect(); assertTrue(session.connected);
        int disconnects = session.disconnects;
        int outcomes = session.connectionResultVersion;
        oldCallback.complete(true, null);
        assertTrue(session.connected); assertTrue(RoutingService.sessionRunning); assertEquals(disconnects, session.disconnects); assertEquals(outcomes, session.connectionResultVersion);
    }
    @Test public void newerRequestWaitingOnOldWorkerDoesNotPublishItsOutcome() {
        session.deferConnect = true; connect();
        AutomationSession.Callback<Boolean> oldCallback = session.deferred;
        service.onStartCommand(new Intent().setAction(RoutingService.CONNECT).putExtra("port", 5556), 0, 2);
        int outcomes = session.connectionResultVersion;
        oldCallback.complete(null, "Old connection failed");
        assertEquals("Old callback cannot retire the newer UI request", outcomes, session.connectionResultVersion);
        session.deferConnect = false; shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertTrue(session.connected); assertEquals(outcomes + 1, session.connectionResultVersion);
    }
    private static RoutingProtocol.Result result(String state, boolean pending) { return new RoutingProtocol.Result(true, state, "test state", true, pending); }

    private static final class FakeSession extends AutomationSession {
        RoutingService service;
        boolean connected, debt, failConnect, failHeartbeat, foregroundAtConnect, wantedAtConnect, failArm, failClear, deferConnect;
        Callback<Boolean> deferred;
        int connects, heartbeats, routingCalls, disconnects;
        final List<RoutingProtocol.Result> results = new ArrayList<>();
        final List<RoutingProtocol.Result> restoreResults = new ArrayList<>();
        final List<Integer> microphoneArguments = new ArrayList<>();
        final List<String> actions = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        FakeSession(Context context) { super(context); }
        @Override boolean isBusy() { return false; }
        @Override boolean isConnected() { return connected; }
        @Override boolean needsRestoration() { return debt; }
        @Override boolean hasLocalIdentity() { return true; }
        @Override String lastConnectionPort() { return "5555"; }
        @Override boolean setConnectionWanted(boolean value) { return (value ? failArm : failClear) ? false : super.setConnectionWanted(value); }
        @Override void connect(int port, Callback<Boolean> callback) {
            connects++; foregroundAtConnect = shadowOf(service).isLastForegroundNotificationAttached(); wantedAtConnect = wantsConnection();
            if (deferConnect) { deferred = callback; return; }
            connected = !failConnect; callback.complete(connected, failConnect ? "Authentication unavailable" : null);
        }
        @Override void heartbeat(Callback<Boolean> callback) { heartbeats++; if (failHeartbeat) connected = false; callback.complete(!failHeartbeat, failHeartbeat ? "Lost" : null); }
        @Override void routing(RoutingBridge.Action action, int vendor, int product, int microphone, Callback<RoutingProtocol.Result> callback) {
            routingCalls++;
            try { commands.add(RoutingBridge.command(action, vendor, product, microphone)); }
            catch (java.io.IOException invalid) { callback.complete(null, "Fixed bridge command refused"); return; }
            RoutingProtocol.Result value;
            if (action == RoutingBridge.Action.STOP || action == RoutingBridge.Action.DEFAULTS) { actions.add("restore"); value = restoreResults.isEmpty() ? result("restored", false) : restoreResults.remove(0); }
            else { microphoneArguments.add(microphone); value = results.isEmpty() ? result("active", true) : results.remove(0); }
            debt = value.restorePending; lastRoutingResult = value; callback.complete(value, null);
        }
        @Override void markConnectionLost() { connected = false; }
        @Override void disconnect() { actions.add("disconnect"); disconnects++; connected = false; }
    }
}
