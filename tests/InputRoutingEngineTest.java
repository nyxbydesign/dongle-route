import dev.usbmic.inspector.shell.RoutingEngine;
import dev.usbmic.inspector.shell.MicrophoneSelector;
import dev.usbmic.inspector.shell.UsbInventoryPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Fake backend exercises transactions and ownership; it cannot establish Android Binder compatibility. */
public final class InputRoutingEngineTest {
    private static final RoutingEngine.Selection DAC = new RoutingEngine.Selection(0x1234, 0x5678);
    private static final RoutingEngine.Device BOTTOM = new RoutingEngine.Device(1, 15, "bottom");
    private static final RoutingEngine.Preference TARGET = RoutingEngine.Preference.of(BOTTOM);
    private static final RoutingEngine.Preference NONE = RoutingEngine.Preference.none();
    private static final RoutingEngine.Preference OTHER = RoutingEngine.Preference.of(new RoutingEngine.Device(1, 3, "external"));
    private static int assertions, scenarios;

    public static void main(String[] args) {
        usbReconnectTransitions();
        attachDetachAndIdempotence();
        busyAndDeferredStop();
        preserveManualBaselines();
        statusAndUnknownQueries();
        externalOwnershipChanges();
        partialFailures();
        crashRecovery();
        defaultsAndGuards();
        journalFailures();
        microphoneSelection();
        System.out.println("PASS: " + scenarios + " routing scenarios, " + assertions + " assertions; fake backend only, no Android or device writes");
    }

    private static void usbReconnectTransitions() {
        Rig r = new Rig(); r.run("tick");
        r.backend.attached = false; r.backend.safe = false; r.backend.settling = true;
        RoutingEngine.Result settling = r.run("tick");
        check(settling.ok && "waiting".equals(settling.state) && settling.restorePending,
                "USB detach inventory transition keeps monitoring and restoration ownership");
        int writes = r.backend.writeCalls, saves = r.store.saves;
        check(r.run("tick").state.equals("waiting") && r.backend.writeCalls == writes && r.store.saves == saves,
                "unchanged settling observation makes no routing or journal writes");
        check(r.run("status").state.equals("waiting") && r.backend.writeCalls == writes && r.store.saves == saves,
                "status during settling is read-only");
        r.backend.safe = true; r.backend.settling = false;
        check(r.run("tick").state.equals("restored") && r.backend.preferences[0].isNone(),
                "settled physical detach restores original preference");
        r.backend.attached = true; r.backend.safe = false; r.backend.settling = true; r.backend.key = "new-ports";
        writes = r.backend.writeCalls;
        RoutingEngine.Result attaching = r.run("tick");
        check(attaching.ok && attaching.state.equals("waiting") && !attaching.restorePending && r.backend.writeCalls == writes,
                "new physical attach with missing audio ports waits without inventing ownership");
        r.backend.safe = true; r.backend.settling = false;
        check(r.run("tick").state.equals("active") && r.backend.writeCalls == writes + 2,
                "settled new ports reapply the route");
        scenarios++;

        r = new Rig(); r.run("tick"); r.backend.key = "immediate-replug"; writes = r.backend.writeCalls;
        check(r.run("tick").state.equals("restored") && r.backend.writeCalls == writes + 2,
                "missed absent interval restores prior baseline before replacing the generation");
        check(r.run("tick").state.equals("active") && r.store.saved.attachmentKey.equals("immediate-replug")
                && r.store.saved.originals[0].isNone(), "replacement generation uses the original baseline, never its own desired preference");
        scenarios++;

        r = new Rig(); r.run("tick"); r.backend.key = "next-generation"; r.backend.idle = false; writes = r.backend.writeCalls;
        check(r.run("tick").state.equals("busy") && r.backend.writeCalls == writes,
                "generation reconciliation waits during calls");
        r.backend.idle = true;
        check(r.run("tick").state.equals("restored") && r.run("tick").state.equals("active"),
                "idle generation recovery restores then reapplies");
        scenarios++;

        r = new Rig(); r.run("tick"); r.backend.settling = true; r.backend.safe = false; writes = r.backend.writeCalls;
        check(r.run("stop").state.equals("waiting") && r.store.saved.phase.equals(RoutingEngine.RESTORING)
                && r.backend.writeCalls == writes, "Stop retains explicit restoration intent during inventory transition");
        r.backend.settling = false; r.backend.safe = true; r.backend.attached = false;
        check(r.run("stop").state.equals("restored") && r.backend.preferences[1].isNone(),
                "Stop completes only after safe inventory returns");
        scenarios++;

        r = new Rig(); r.run("tick"); r.backend.key = "next-generation"; r.backend.preferences[0] = OTHER; writes = r.backend.writeCalls;
        check(r.run("tick").state.equals("conflict") && r.backend.writeCalls == writes,
                "re-enumeration does not authorize overwriting external preferences");
        r = new Rig(); r.run("tick"); r.backend.safe = false; r.backend.settling = true; r.backend.preferences[1] = OTHER;
        check(r.run("tick").state.equals("conflict") && r.backend.writeCalls == 2,
                "external preference changes during a transition end ownership without mutation");
        scenarios++;

        r = new Rig(); r.backend.safe = false;
        check(!r.run("tick").ok && r.backend.writeCalls == 0, "unclassified/foreign inventory remains blocked");
        r = new Rig(); r.backend.settling = true; r.backend.safe = false; r.backend.queryUnknown = true;
        check(!r.run("tick").ok && r.backend.writeCalls == 0, "query failure is never relabeled a USB transition");
        r = new Rig(); r.backend.settling = true; r.backend.safe = false; r.backend.authorized = false;
        check(!r.run("tick").ok && r.backend.writeCalls == 0, "permission failure remains terminal during transition");
        scenarios++;

        r = new Rig(); r.backend.settleBeforeWrite = 1;
        check(r.run("tick").state.equals("waiting") && r.backend.actualWrites.isEmpty()
                && r.store.saved.phase.equals(RoutingEngine.RESTORING) && !r.store.saved.recoveryBlocked,
                "fresh pre-write transition retains rollback intent without marking a permanent apply failure");
        r.backend.settling = false; r.backend.safe = true; r.backend.key = "replacement";
        check(r.run("tick").state.equals("restored") && r.run("tick").state.equals("active"),
                "verified non-mutating apply guard transition resumes after reconciliation");
        scenarios++;

        r = new Rig(); r.run("tick"); r.backend.settleBeforeWrite = r.backend.writeCalls + 1;
        check(r.run("stop").state.equals("waiting") && r.backend.actualWrites.size() == 2,
                "fresh pre-restore transition cannot perform a routing mutation");
        r.backend.settling = false; r.backend.safe = true;
        check(r.run("stop").state.equals("restored") && r.backend.preferences[0].isNone(),
                "pre-restore transition retries only after fresh intent/readback reconciliation");
        scenarios++;

        RoutingEngine.Device rear = new RoutingEngine.Device(1, 15, "rear");
        r = new Rig(); r.backend.microphones = Arrays.asList(port(10, BOTTOM), port(20, rear));
        check(r.engine().run("tick", DAC, 20).state.equals("active"), "explicit rear mic initially applies");
        r.backend.microphones = Arrays.asList(port(30, BOTTOM), port(40, rear)); r.backend.key = "new-usb-ids";
        check(r.engine().run("tick", DAC, 20).state.equals("restored"),
                "stale numeric hint does not hide the uniquely retained canonical microphone");
        check(r.engine().run("tick", DAC, null).state.equals("active") && r.store.saved.bottom.equals(rear),
                "fresh attach preserves a non-default canonical mic after baseline restoration");
        r.run("stop");
        check(r.engine().run("tick", DAC, 30).state.equals("active") && r.store.saved.bottom.equals(BOTTOM),
                "a new explicit valid selection after Stop can replace the canonical target");
        scenarios++;

        check(UsbInventoryPolicy.classify(0, false, true, 0, 0, false) == UsbInventoryPolicy.State.ABSENT, "empty inventories establish absence");
        check(UsbInventoryPolicy.classify(0, false, true, 1, 1, true) == UsbInventoryPolicy.State.SETTLING, "audio ports can outlive physical detach");
        check(UsbInventoryPolicy.classify(0, false, true, 1, 0, false) == UsbInventoryPolicy.State.SETTLING, "last input may linger alone");
        check(UsbInventoryPolicy.classify(1, true, true, 0, 0, false) == UsbInventoryPolicy.State.SETTLING, "chosen physical device may precede audio ports");
        check(UsbInventoryPolicy.classify(1, true, true, 1, 0, false) == UsbInventoryPolicy.State.SETTLING, "output registration may follow input");
        check(UsbInventoryPolicy.classify(1, true, true, 1, 1, true) == UsbInventoryPolicy.State.ATTACHED, "complete valid pair establishes attachment");
        check(UsbInventoryPolicy.classify(1, false, true, 0, 0, false) == UsbInventoryPolicy.State.REFUSED, "foreign physical device is not settling");
        check(UsbInventoryPolicy.classify(2, true, true, 1, 1, true) == UsbInventoryPolicy.State.REFUSED, "multiple physical devices stay refused");
        check(UsbInventoryPolicy.classify(1, true, true, 2, 1, true) == UsbInventoryPolicy.State.REFUSED, "ambiguous input ports stay refused");
        check(UsbInventoryPolicy.classify(1, true, true, 1, 1, false) == UsbInventoryPolicy.State.REFUSED, "mismatched complete pair stays refused");
        check(UsbInventoryPolicy.classify(0, false, false, 1, 1, true) == UsbInventoryPolicy.State.REFUSED, "unselected stale ports cannot be claimed as a known transition");
        scenarios++;
    }

    private static void attachDetachAndIdempotence() {
        Rig r = new Rig();
        check(r.run("tick").state.equals("active"), "attach applies");
        check(r.backend.actualWrites.size() == 2 && r.backend.preferences[0].equals(TARGET)
                && r.backend.preferences[1].equals(TARGET), "only two input presets changed");
        int saves = r.store.saves;
        RoutingEngine.Result repeated = r.run("tick");
        check(repeated.writeAttempts == 0 && r.store.saves == saves && r.backend.actualWrites.size() == 2,
                "repeated attached tick makes no routing or journal writes");
        r.backend.attached = false;
        check(r.run("tick").state.equals("restored"), "detach restores");
        check(r.backend.preferences[0].isNone() && r.backend.preferences[1].isNone(), "verified absent originals restored");
        int calls = r.backend.writeCalls;
        check(r.run("tick").state.equals("idle") && r.backend.writeCalls == calls, "remaining detached is idempotent");
        r.backend.attached = true;
        check(r.run("tick").state.equals("active") && r.backend.writeCalls == calls + 2, "new attach captures a fresh transaction");
        scenarios++;
    }

    private static void busyAndDeferredStop() {
        Rig r = new Rig(); r.backend.idle = false;
        check(r.run("tick").state.equals("busy") && r.backend.writeCalls == 0 && r.store.saved == null,
                "initial active call defers apply without creating ownership");
        r.backend.idle = true; r.run("tick"); r.backend.idle = false;
        RoutingEngine.Result stopping = r.run("stop");
        check(stopping.ok && stopping.restorePending && stopping.state.equals("busy") && r.backend.writeCalls == 2,
                "busy stop retains pending restoration without a write");
        r.backend.idle = true;
        check(r.run("stop").state.equals("restored") && r.backend.writeCalls == 4, "idle stop restores both originals");
        int writes = r.backend.writeCalls;
        check(r.run("stop").ok && r.backend.writeCalls == writes, "repeated stop is idempotent");
        scenarios++;

        r = new Rig(); r.run("tick"); r.backend.idle = false; r.backend.attached = false;
        check(r.run("tick").restorePending && r.backend.writeCalls == 2, "detach while busy defers original restoration");
        r.backend.idle = true;
        RoutingEngine.Result restored = r.run("tick");
        check(restored.ok && restored.state.equals("restored") && !restored.restorePending,
                "ordinary deferred detach resumes monitoring after restoration");
        scenarios++;

        r = new Rig(); r.backend.busyBeforeWrite = 1;
        RoutingEngine.Result changed = r.run("tick");
        check(changed.state.equals("busy") && changed.restorePending && r.backend.actualWrites.isEmpty(),
                "last backend idle check catches a new call before mutation");
        r.backend.idle = true;
        check(r.run("stop").state.equals("restored") && r.backend.actualWrites.isEmpty(), "never-applied intent needs no guessed undo");
        scenarios++;
    }

    private static void preserveManualBaselines() {
        Rig r = new Rig(); r.backend.preferences = new RoutingEngine.Preference[]{TARGET, TARGET};
        check(r.run("tick").state.equals("active") && r.backend.writeCalls == 0, "manual bottom baseline needs no mutation");
        check(r.store.saved.originals[0].equals(TARGET) && r.store.saved.originals[1].equals(TARGET), "manual baseline persisted exactly");
        check(r.run("stop").state.equals("restored") && r.backend.writeCalls == 0
                && r.backend.preferences[0].equals(TARGET) && r.backend.preferences[1].equals(TARGET),
                "Stop preserves manually configured bottom preferences");
        scenarios++;

        r = new Rig(); r.backend.preferences = new RoutingEngine.Preference[]{TARGET, NONE};
        r.run("tick"); r.run("stop");
        check(r.backend.actualWrites.size() == 2 && r.backend.actualWrites.get(0).preset == 7
                && r.backend.actualWrites.get(1).preset == 7, "mixed baseline changes and restores only VOICE_COMMUNICATION");
        check(r.backend.preferences[0].equals(TARGET) && r.backend.preferences[1].isNone(), "mixed original state retained");
        scenarios++;

        r = new Rig(); r.backend.preferences = new RoutingEngine.Preference[]{OTHER, NONE};
        r.run("tick"); r.run("stop");
        check(r.backend.preferences[0].equals(OTHER) && r.backend.preferences[1].equals(NONE), "nondefault canonical input baseline restored");
        scenarios++;
    }

    private static void statusAndUnknownQueries() {
        Rig r = new Rig(); r.run("tick");
        int saves = r.store.saves, writes = r.backend.writeCalls;
        check(r.run("status").state.equals("active") && r.store.saves == saves && r.backend.writeCalls == writes,
                "status performs neither routing writes nor journal reconciliation");
        r.store.saved.phase = RoutingEngine.APPLYING; r.store.saved.steps[0] = RoutingEngine.APPLY_INTENT;
        saves = r.store.saves;
        check(r.run("status").restorePending && r.store.saves == saves && r.backend.writeCalls == writes,
                "status leaves crash-pending intent untouched");
        scenarios++;

        r = new Rig(); r.backend.queryUnknown = true;
        check(!r.run("tick").ok && r.backend.writeCalls == 0 && r.store.saved == null,
                "unknown native preference query never becomes empty/default");
        check(!r.run("status").ok && r.store.saves == 0, "unknown status is blocked and read-only");
        scenarios++;

        r = new Rig(); r.backend.unknownAfterWrite = 1;
        RoutingEngine.Result unknown = r.run("tick");
        check(!unknown.ok && unknown.restorePending && r.backend.writeCalls == 1, "unknown post-write result retains pending ownership");
        r.backend.queryUnknown = false;
        check(r.run("stop").ok && r.backend.writeCalls == 2 && r.backend.preferences[0].isNone(),
                "explicit Stop reconciles uncertain apply and restores only its observed effect");
        scenarios++;
    }

    private static void externalOwnershipChanges() {
        Rig r = new Rig(); r.run("tick"); r.backend.preferences[1] = OTHER;
        RoutingEngine.Result result = r.run("tick");
        check(result.state.equals("conflict") && !result.ok && !result.restorePending && r.backend.writeCalls == 2,
                "external change stops all ownership without overwriting it");
        check(r.run("tick").state.equals("conflict") && r.backend.writeCalls == 2, "conflict does not auto-retry");
        check(r.run("stop").ok && r.backend.preferences[1].equals(OTHER) && r.backend.writeCalls == 2,
                "explicit Stop retires conflicting ownership without undoing external preference");
        scenarios++;

        r = new Rig(); r.backend.externalBeforeWrite = 1;
        result = r.run("tick");
        check(result.state.equals("conflict") && r.backend.actualWrites.isEmpty() && r.backend.preferences[0].equals(OTHER),
                "compare-before-write catches an external change after intent was saved");
        scenarios++;
    }

    private static void partialFailures() {
        Rig r = new Rig(); r.backend.failBeforeWrite = 2;
        RoutingEngine.Result failed = r.run("tick");
        check(!failed.ok && failed.state.equals("restored") && !failed.restorePending, "partial apply failure rolls back and requires deliberate enable");
        check(r.backend.writeCalls == 3 && r.backend.actualWrites.size() == 2
                && r.backend.preferences[0].isNone() && r.backend.preferences[1].isNone(),
                "second failed write is not repeated; first effect restored");
        scenarios++;

        r = new Rig(); r.backend.failAfterWrite = 2;
        failed = r.run("tick");
        check(!failed.ok && !failed.restorePending && r.backend.actualWrites.size() == 4
                && r.backend.preferences[0].isNone() && r.backend.preferences[1].isNone(),
                "failure with side effect reconciles and restores both effected presets");
        scenarios++;

        r = new Rig(); r.run("tick"); r.backend.failBeforeWrite = 3;
        failed = r.run("stop");
        check(!failed.ok && failed.restorePending && r.backend.writeCalls == 3, "failed restore is retained and not blindly repeated");
        check(r.run("stop").ok && r.backend.writeCalls == 5 && r.backend.preferences[0].isNone(),
                "subsequent explicit Stop reads actual state before retrying remaining restores");
        scenarios++;

        r = new Rig(); r.run("tick"); r.backend.failAfterWrite = 3;
        failed = r.run("stop");
        check(!failed.ok && failed.restorePending && r.backend.preferences[0].isNone(), "restore exception with side effect is observed");
        check(r.run("stop").ok && r.backend.writeCalls == 4, "already restored preset is not rewritten after uncertain response");
        scenarios++;
    }

    private static void crashRecovery() {
        Rig r = new Rig(); r.backend.crashBeforeWrite = 1;
        r.crash("tick");
        check(r.store.saved.steps[0] == RoutingEngine.APPLY_INTENT && r.backend.actualWrites.isEmpty(), "crash before mutation leaves durable intent");
        RoutingEngine.Result recovered = r.run("tick");
        check(!recovered.ok && recovered.state.equals("restored") && r.backend.actualWrites.isEmpty(),
                "restart reconciles unapplied intent without reapplying it");
        scenarios++;

        r = new Rig(); r.backend.crashAfterWrite = 1;
        r.crash("tick");
        check(r.store.saved.steps[0] == RoutingEngine.APPLY_INTENT && r.backend.preferences[0].equals(TARGET),
                "crash after mutation still has pre-write intent");
        check(r.run("stop").ok && r.backend.actualWrites.size() == 2 && r.backend.preferences[0].isNone()
                && r.backend.preferences[1].isNone(), "restart restores observed ownership, not untouched second preset");
        scenarios++;

        r = new Rig(); r.run("tick"); r.backend.crashAfterWrite = 3;
        r.crash("stop");
        check(r.store.saved.steps[0] == RoutingEngine.RESTORE_INTENT && r.backend.preferences[0].isNone(),
                "crash after restore retains recoverable intent");
        check(r.run("stop").ok && r.backend.actualWrites.size() == 4, "restore recovery skips the already restored preset");
        scenarios++;
    }

    private static void defaultsAndGuards() {
        Rig r = new Rig(); r.backend.preferences = new RoutingEngine.Preference[]{TARGET, TARGET};
        check(r.run("defaults").state.equals("restored") && r.backend.writeCalls == 2
                && r.backend.preferences[0].isNone() && r.backend.preferences[1].isNone(),
                "explicit Defaults clears existing manual bottom without requiring automation first");
        scenarios++;

        r = new Rig(); r.backend.attached = false; r.backend.preferences = new RoutingEngine.Preference[]{TARGET, NONE};
        check(r.run("defaults").ok && r.backend.writeCalls == 1, "Defaults can clear matching bottom while DAC absent");
        scenarios++;

        r = new Rig(); r.backend.preferences = new RoutingEngine.Preference[]{TARGET, OTHER};
        check(r.run("defaults").state.equals("conflict") && r.backend.writeCalls == 0
                && r.backend.preferences[1].equals(OTHER), "Defaults refuses unrelated current preferences before any clear");
        scenarios++;

        r = new Rig(); r.backend.idle = false;
        RoutingEngine.Result busy = r.run("defaults");
        check(busy.ok && busy.restorePending && busy.state.equals("busy") && r.backend.writeCalls == 0, "busy Defaults is deferred");
        scenarios++;

        r = new Rig(); r.backend.safe = false;
        check(!r.run("tick").ok && r.backend.writeCalls == 0, "ambiguous/foreign USB inventory blocks apply");
        r.backend.safe = true; r.backend.bottom = null;
        check(!r.run("tick").ok && r.backend.writeCalls == 0, "missing or ambiguous bottom mic blocks apply");
        r.backend.bottom = BOTTOM; r.backend.authorized = false;
        check(!r.run("tick").ok && r.backend.writeCalls == 0, "UID/permission guard blocks mutation");
        scenarios++;

        r = new Rig(); r.run("tick");
        check(!r.engine().run("tick", new RoutingEngine.Selection(1, 2)).ok && r.backend.writeCalls == 2,
                "changing selected DAC requires stopping existing ownership first");
        r.backend.safe = false;
        check(!r.run("stop").ok && r.backend.writeCalls == 2, "unsafe inventory prevents even an owned restoration write");
        scenarios++;
    }

    private static void journalFailures() {
        Rig r = new Rig(); r.store.failSave = 1;
        check(!r.run("tick").ok && r.backend.writeCalls == 0, "baseline journal failure prevents first routing mutation");
        scenarios++;
        r = new Rig(); r.store.failSave = 2;
        check(!r.run("tick").ok && r.backend.writeCalls == 0, "pre-write intent failure prevents routing mutation");
        scenarios++;
    }

    private static void microphoneSelection() {
        RoutingEngine.Device rear = new RoutingEngine.Device(1, 15, "rear");
        RoutingEngine.Device front = new RoutingEngine.Device(1, 15, "front");
        RoutingEngine.Device unnamed = new RoutingEngine.Device(1, 15, "");
        List<MicrophoneSelector.Port> two = Arrays.asList(port(10, BOTTOM), port(20, rear));
        check(MicrophoneSelector.choose(two, null).device.equals(BOTTOM), "unique known bottom is deterministic default");
        check(MicrophoneSelector.choose(two, 20).device.equals(rear), "explicit numeric ID can select another built-in input");
        check(MicrophoneSelector.choose(Collections.singletonList(port(10, rear)), null).device.equals(rear), "sole generic-address built-in is a safe default");
        check(MicrophoneSelector.choose(Collections.singletonList(port(10, unnamed)), null).device.equals(unnamed), "sole unnamed built-in is unambiguous");
        List<MicrophoneSelector.Port> genericTwo = Arrays.asList(port(10, rear), port(20, front));
        check(MicrophoneSelector.choose(genericTwo, null) == null, "multiple generic microphones need explicit choice");
        check(MicrophoneSelector.choose(genericTwo, 20).device.equals(front), "explicit choice resolves multiple generic microphones");
        check(MicrophoneSelector.choose(two, 99) == null, "missing or stale selected ID is refused");
        check(MicrophoneSelector.choose(two, 0) == null && MicrophoneSelector.choose(two, -1) == null, "zero and negative input IDs are not selection sentinels");
        check(MicrophoneSelector.choose(Arrays.asList(port(10, rear), port(10, front)), 10) == null, "duplicate audio IDs are ambiguous");
        check(MicrophoneSelector.choose(Arrays.asList(port(10, rear), port(20, rear)), 10) == null, "ID cannot distinguish equal canonical addresses");
        check(MicrophoneSelector.choose(Arrays.asList(port(10, BOTTOM), port(20, BOTTOM)), null) == null, "duplicate bottom addresses are refused");
        check(MicrophoneSelector.choose(Collections.singletonList(port(10, new RoutingEngine.Device(1, 22, "usb"))), 10) == null,
                "a USB microphone cannot become the built-in target");
        check(MicrophoneSelector.choose(Collections.singletonList(port(0, rear)), null) == null, "invalid audio ID is refused");
        check(MicrophoneSelector.choose(Collections.<MicrophoneSelector.Port>emptyList(), null) == null, "absent built-in input is refused");
        check(MicrophoneSelector.resolve(two, rear).device.equals(rear), "retained canonical target overrides automatic bottom default");
        check(MicrophoneSelector.resolve(Collections.singletonList(port(55, rear)), rear).id == 55, "canonical restoration survives port ID re-enumeration");
        check(MicrophoneSelector.resolve(Collections.singletonList(port(10, BOTTOM)), rear) == null, "missing retained target is not silently replaced");
        check(MicrophoneSelector.resolve(Arrays.asList(port(10, rear), port(20, rear)), rear) == null, "retained target must still resolve uniquely");
        scenarios++;

        Rig r = new Rig(); r.backend.microphones = Collections.singletonList(port(10, rear));
        check(r.run("tick").state.equals("active") && r.backend.preferences[0].equals(RoutingEngine.Preference.of(rear)),
                "generic phone applies its sole built-in canonical target");
        check(r.run("stop").ok && r.backend.preferences[0].isNone() && r.backend.preferences[1].isNone(), "generic phone restores original defaults");
        scenarios++;

        r = new Rig(); r.backend.microphones = Collections.singletonList(port(10, unnamed));
        check(r.run("tick").ok && r.backend.preferences[0].equals(RoutingEngine.Preference.of(unnamed)), "unique empty address routes without invented bottom");
        check(r.run("stop").ok && r.backend.preferences[0].isNone(), "unnamed input original state is restored");
        scenarios++;

        r = new Rig(); r.backend.microphones = genericTwo;
        check(!r.run("tick").ok && r.backend.actualWrites.isEmpty(), "ambiguous generic phone cannot auto-apply");
        check(r.engine().run("tick", DAC, 20).ok && r.backend.preferences[0].equals(RoutingEngine.Preference.of(front)),
                "explicit generic phone mic choice enables guarded routing");
        scenarios++;

        r = new Rig(); r.backend.microphones = two; r.run("tick");
        RoutingEngine.Result changed = r.engine().run("tick", DAC, 20);
        check(!changed.ok && changed.restorePending && r.backend.writeCalls == 2, "new live mic choice cannot redirect owned preferences");
        check(r.engine().run("stop", null, 20).ok && r.backend.preferences[0].isNone() && r.backend.writeCalls == 4,
                "Stop ignores a new UI choice and restores through its saved canonical target");
        scenarios++;

        r = new Rig(); r.backend.microphones = two; r.backend.crashAfterWrite = 1; r.crash("tick");
        RoutingEngine.Result recovered = r.engine().run("tick", DAC, 20);
        check(!recovered.ok && recovered.state.equals("restored") && r.backend.preferences[0].isNone()
                && r.backend.actualWrites.size() == 2 && r.backend.actualWrites.get(1).desired.isNone(),
                "pending recovery ignores a new UI target and only restores the old intent");
        scenarios++;

        r = new Rig(); r.backend.microphones = Collections.singletonList(port(10, rear));
        RoutingEngine.Preference manual = RoutingEngine.Preference.of(rear);
        r.backend.preferences = new RoutingEngine.Preference[]{manual, manual};
        r.run("tick"); r.run("stop");
        check(r.backend.actualWrites.isEmpty() && r.backend.preferences[0].equals(manual), "manual generic microphone baseline is preserved");
        scenarios++;

        r = new Rig(); r.backend.microphones = Collections.singletonList(port(10, rear));
        r.backend.preferences = new RoutingEngine.Preference[]{manual, manual};
        check(r.engine().run("defaults", DAC, 10).ok && r.backend.preferences[0].isNone() && r.backend.preferences[1].isNone(),
                "explicit Defaults clears only the chosen generic built-in preference");
        scenarios++;

        r = new Rig(); r.backend.microphones = Collections.singletonList(port(10, rear)); r.run("tick");
        r.backend.microphones = Collections.singletonList(port(55, rear));
        check(r.run("tick").ok && r.backend.writeCalls == 2, "retained ownership follows canonical target after ID changes");
        check(r.run("stop").ok && r.backend.writeCalls == 4, "re-enumerated target remains restorable");
        scenarios++;
    }

    private static MicrophoneSelector.Port port(int id, RoutingEngine.Device device) {
        return new MicrophoneSelector.Port(id, device);
    }

    private static void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private static final class Rig {
        final MemoryStore store = new MemoryStore();
        final FakeBackend backend = new FakeBackend(store);
        RoutingEngine engine() { return new RoutingEngine(backend, store); }
        RoutingEngine.Result run(String action) { return engine().run(action, "stop".equals(action) ? null : DAC); }
        void crash(String action) {
            try { run(action); throw new AssertionError("Expected simulated process death"); }
            catch (ProcessDeath expected) { }
        }
    }

    private static final class MemoryStore implements RoutingEngine.Store {
        RoutingEngine.Session saved;
        int saves, failSave;
        @Override public RoutingEngine.Session load() { return saved == null ? null : saved.copy(); }
        @Override public void save(RoutingEngine.Session value) throws Exception {
            saves++;
            if (saves == failSave) throw new Exception("Simulated fsync failure");
            saved = value.copy();
        }
    }

    private static final class ProcessDeath extends Error { }
    private static final class FakeBackend implements RoutingEngine.Backend {
        final MemoryStore store;
        RoutingEngine.Preference[] preferences = {NONE, NONE};
        RoutingEngine.Device bottom = BOTTOM;
        List<MicrophoneSelector.Port> microphones;
        boolean attached = true, safe = true, idle = true, authorized = true, queryUnknown, settling;
        String key = "stable-pair";
        int writeCalls, failBeforeWrite, failAfterWrite, crashBeforeWrite, crashAfterWrite;
        int busyBeforeWrite, externalBeforeWrite, unknownAfterWrite, settleBeforeWrite;
        final List<RoutingEngine.Write> actualWrites = new ArrayList<>();
        FakeBackend(MemoryStore store) { this.store = store; }
        @Override public RoutingEngine.Observation observe(RoutingEngine.Selection selected, RoutingEngine.Device retained, Integer microphoneId) throws Exception {
            if (queryUnknown) throw new Exception("Unknown native getter, never NONE");
            List<MicrophoneSelector.Port> ports = microphones != null ? microphones
                    : bottom == null ? Collections.<MicrophoneSelector.Port>emptyList() : Collections.singletonList(port(10, bottom));
            MicrophoneSelector.Port target = retained == null ? MicrophoneSelector.choose(ports, microphoneId) : MicrophoneSelector.resolve(ports, retained);
            MicrophoneSelector.Port requested = microphoneId == null ? null : MicrophoneSelector.choose(ports, microphoneId);
            return new RoutingEngine.Observation(authorized, idle, safe, attached, target == null ? null : target.device,
                    attached ? key : "absent", preferences[0], preferences[1],
                    requested == null ? null : requested.device, microphoneId != null, settling);
        }
        @Override public void write(RoutingEngine.Write request) throws Exception {
            writeCalls++;
            int index = request.preset == 1 ? 0 : request.preset == 7 ? 1 : -1;
            check(index >= 0, "only fixed capture presets can be written");
            check(store.saved != null && (store.saved.steps[index] == RoutingEngine.APPLY_INTENT
                    || store.saved.steps[index] == RoutingEngine.RESTORE_INTENT), "durable intent precedes every backend write");
            if (writeCalls == busyBeforeWrite) idle = false;
            if (writeCalls == externalBeforeWrite) preferences[index] = OTHER;
            if (writeCalls == settleBeforeWrite) { safe = false; settling = true; }
            RoutingEngine.Observation fresh = observe(request.selection, request.bottom, null);
            if (fresh.authorized && fresh.idle && fresh.inventorySettling) throw new RoutingEngine.InventoryTransition();
            if (!fresh.authorized || !fresh.idle || !fresh.safeInventory || !request.bottom.equals(fresh.bottom)
                    || !request.expected.equals(fresh.preferences[index])
                    || (request.requireAttached && (!fresh.attached || !request.attachmentKey.equals(fresh.attachmentKey)))) {
                throw new Exception("Fresh expected-value or idle guard refused");
            }
            if (writeCalls == crashBeforeWrite) throw new ProcessDeath();
            if (writeCalls == failBeforeWrite) throw new Exception("Failure before mutation");
            preferences[index] = request.desired;
            actualWrites.add(request);
            if (writeCalls == unknownAfterWrite) queryUnknown = true;
            if (writeCalls == crashAfterWrite) throw new ProcessDeath();
            if (writeCalls == failAfterWrite) throw new Exception("Failure after mutation");
        }
    }
}
