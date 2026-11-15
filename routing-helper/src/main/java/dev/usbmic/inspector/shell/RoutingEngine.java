package dev.usbmic.inspector.shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Pure state machine. Backend writes must repeat the guards and expected-value comparison. */
public final class RoutingEngine {
    public static final int[] PRESETS = {1, 7};
    public static final int MIC_TYPE = 15;
    public static final int INPUT_ROLE = 1;
    public static final int NEW = 0, APPLY_INTENT = 1, OWNED = 2, RESTORE_INTENT = 3, RESTORED = 4;
    public static final String APPLYING = "applying", ACTIVE = "active", RESTORING = "restoring";
    public static final String CONFLICT = "conflict", STOPPED = "stopped";

    public static final class Selection {
        public final int vid, pid;
        public Selection(int vid, int pid) {
            if (vid < 0 || vid > 65535 || pid < 0 || pid > 65535) throw new IllegalArgumentException("Invalid DAC selection");
            this.vid = vid; this.pid = pid;
        }
        @Override public boolean equals(Object other) {
            return other instanceof Selection && vid == ((Selection) other).vid && pid == ((Selection) other).pid;
        }
        @Override public int hashCode() { return vid * 31 + pid; }
    }

    public static final class Device {
        public final int role, type;
        public final String address;
        public Device(int role, int type, String address) {
            if (role != INPUT_ROLE || type < 0 || address == null || address.length() > 256) {
                throw new IllegalArgumentException("Invalid input preference");
            }
            this.role = role; this.type = type; this.address = address;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Device)) return false;
            Device d = (Device) other;
            return role == d.role && type == d.type && address.equals(d.address);
        }
        @Override public int hashCode() { return Objects.hash(role, type, address); }
    }

    public static final class Preference {
        public final List<Device> devices;
        private Preference(List<Device> devices) { this.devices = Collections.unmodifiableList(new ArrayList<>(devices)); }
        public static Preference none() { return new Preference(Collections.<Device>emptyList()); }
        public static Preference of(Device device) { return devices(Collections.singletonList(device)); }
        public static Preference devices(List<Device> devices) {
            if (devices == null || devices.isEmpty() || devices.size() > 16 || devices.contains(null)) {
                throw new IllegalArgumentException("Unknown or invalid preference list");
            }
            for (int i = 0; i < devices.size(); i++) {
                if (devices.subList(0, i).contains(devices.get(i))) throw new IllegalArgumentException("Duplicate input preference");
            }
            return new Preference(devices);
        }
        public boolean isNone() { return devices.isEmpty(); }
        @Override public boolean equals(Object other) { return other instanceof Preference && devices.equals(((Preference) other).devices); }
        @Override public int hashCode() { return devices.hashCode(); }
    }

    public static final class Observation {
        public final boolean authorized, idle, safeInventory, attached, inventorySettling;
        public final Device bottom;
        public final Device requestedMicrophone;
        public final boolean microphoneRequestProvided;
        public final String attachmentKey;
        public final Preference[] preferences;
        public Observation(boolean authorized, boolean idle, boolean safeInventory, boolean attached,
                           Device bottom, String attachmentKey, Preference mic, Preference voice) {
            this(authorized, idle, safeInventory, attached, bottom, attachmentKey, mic, voice, null, false);
        }
        public Observation(boolean authorized, boolean idle, boolean safeInventory, boolean attached,
                           Device bottom, String attachmentKey, Preference mic, Preference voice,
                           Device requestedMicrophone, boolean microphoneRequestProvided) {
            this(authorized, idle, safeInventory, attached, bottom, attachmentKey, mic, voice,
                    requestedMicrophone, microphoneRequestProvided, false);
        }
        public Observation(boolean authorized, boolean idle, boolean safeInventory, boolean attached,
                           Device bottom, String attachmentKey, Preference mic, Preference voice,
                           Device requestedMicrophone, boolean microphoneRequestProvided, boolean inventorySettling) {
            this.authorized = authorized; this.idle = idle; this.safeInventory = safeInventory;
            this.attached = attached; this.bottom = bottom; this.attachmentKey = attachmentKey;
            this.preferences = new Preference[]{mic, voice};
            this.requestedMicrophone = requestedMicrophone;
            this.microphoneRequestProvided = microphoneRequestProvided;
            this.inventorySettling = inventorySettling;
        }
    }

    /** A known inventory transition requires reconciliation before any further write. */
    public static final class InventoryTransition extends Exception {
        public InventoryTransition() { super("Selected USB inventory is settling"); }
    }

    public static final class Write {
        public final int preset;
        public final Preference expected, desired;
        public final Selection selection;
        public final Device bottom;
        public final String attachmentKey;
        public final boolean requireAttached;
        Write(int index, Preference expected, Preference desired, Session s, boolean apply) {
            this.preset = PRESETS[index]; this.expected = expected; this.desired = desired;
            this.selection = s.selection; this.bottom = s.bottom;
            this.attachmentKey = apply && !s.defaults ? s.attachmentKey : null;
            this.requireAttached = apply && !s.defaults;
        }
    }

    public interface Backend {
        Observation observe(Selection selection, Device retainedMicrophone, Integer selectedMicrophoneId) throws Exception;
        void write(Write request) throws Exception;
    }
    public interface Store {
        Session load() throws Exception;
        void save(Session session) throws Exception;
    }

    public static final class Session {
        public Selection selection;
        public Device bottom;
        public String attachmentKey, phase;
        public boolean defaults, recoveryBlocked;
        public Preference desired;
        public Preference[] originals = new Preference[2];
        public int[] steps = new int[2];
        public Session copy() {
            Session s = new Session();
            s.selection = selection; s.bottom = bottom; s.attachmentKey = attachmentKey;
            s.phase = phase; s.defaults = defaults; s.recoveryBlocked = recoveryBlocked; s.desired = desired;
            s.originals = originals.clone(); s.steps = steps.clone(); return s;
        }
        public boolean pending() {
            return APPLYING.equals(phase) || RESTORING.equals(phase);
        }
        public int owned() {
            int count = 0;
            for (int step : steps) if (step == APPLY_INTENT || step == OWNED || step == RESTORE_INTENT) count++;
            return count;
        }
    }

    public static final class Result {
        public final boolean ok, idle, restorePending;
        public final String state, message;
        public final int writeAttempts, ownedPresets;
        Result(boolean ok, String state, String message, boolean idle, boolean pending, int attempts, int owned) {
            this.ok = ok; this.state = state; this.message = message;
            this.idle = idle; this.restorePending = pending; this.writeAttempts = attempts; this.ownedPresets = owned;
        }
    }

    private final Backend backend;
    private final Store store;
    private Session session;
    private int writeAttempts;
    private boolean lastIdle;
    private String currentAction;
    private Integer selectedMicrophoneId;
    public RoutingEngine(Backend backend, Store store) { this.backend = backend; this.store = store; }

    public Result run(String action, Selection selected) {
        return run(action, selected, null);
    }

    public Result run(String action, Selection selected, Integer microphoneId) {
        writeAttempts = 0; lastIdle = false;
        currentAction = action; selectedMicrophoneId = microphoneId;
        try {
            if (!"status".equals(action) && !"tick".equals(action) && !"stop".equals(action) && !"defaults".equals(action)) {
                return result(false, "blocked", "Unknown fixed action.", false);
            }
            session = store.load();
            Selection effective = selected != null ? selected : session == null ? null : session.selection;
            Observation initial = observe(effective);
            if ("status".equals(action)) return status(initial);
            if (!initial.authorized) return result(false, "blocked", "Shell UID or routing permissions unavailable.", pending());
            if (session != null && !STOPPED.equals(session.phase) && !session.pending()
                    && !"stop".equals(action) && microphoneId != null
                    && initial.requestedMicrophone != null && !session.bottom.equals(initial.requestedMicrophone)) {
                return result(false, "blocked", "Stop the previous microphone session before changing or refreshing its selection.", pending());
            }
            if (session != null && !STOPPED.equals(session.phase) && selected != null
                    && !selected.equals(session.selection)) {
                return result(false, "blocked", "Stop the previous DAC session before changing selection.", pending());
            }
            if ("stop".equals(action)) {
                if (session == null || STOPPED.equals(session.phase)) return result(true, "idle", "No automated ownership to restore.", false);
                if (CONFLICT.equals(session.phase)) {
                    session.phase = STOPPED; store.save(session);
                    return result(true, "restored", "External preferences preserved. Ownership ended without overwriting them.", false);
                }
                session.phase = RESTORING; store.save(session);
                return restore("Original input preferences restored.", true);
            }
            if (session != null && CONFLICT.equals(session.phase)) {
                return result(false, "conflict", "An external preference change ended ownership. Stop before starting another session.", false);
            }
            if (session != null && session.pending()) {
                if (APPLYING.equals(session.phase)) session.recoveryBlocked = true;
                session.phase = RESTORING; store.save(session);
                return restore(session.recoveryBlocked
                        ? "Interrupted operation reconciled; originals restored. Enable again deliberately."
                        : "Deferred restoration completed; original input preferences restored.", !session.recoveryBlocked);
            }
            if (initial.inventorySettling) {
                if (session != null && ACTIVE.equals(session.phase) && !reconcile(initial)) return conflict();
                return waiting();
            }
            if ("defaults".equals(action)) return defaults(effective, initial);
            if (effective == null) return result(false, "blocked", "Choose the DAC before enabling automation.", pending());
            if (!initial.safeInventory) return result(false, "blocked", "USB inventory is ambiguous or does not match the selected DAC.", pending());
            if (session != null && ACTIVE.equals(session.phase)) {
                if (!reconcile(initial)) return conflict();
                if (!validMicrophone(initial.bottom) || !session.bottom.equals(initial.bottom)) return missingMicrophone();
                if (!initial.attached || !Objects.equals(session.attachmentKey, initial.attachmentKey)) {
                    session.phase = RESTORING; store.save(session);
                    return restore(initial.attached ? "DAC re-enumerated; previous input preferences restored before reapplying."
                            : "DAC detached; original input preferences restored.", true);
                }
                if (!initial.idle) return result(true, "busy", "Call or recording active; no routing writes.", false);
                return result(true, "active", "Owned preferences remain verified. Actual app microphone routing needs separate verification.", false);
            }
            if (!initial.attached) return result(true, "idle", "Selected DAC absent; no preferences changed.", false);
            if (!initial.idle) return result(true, "busy", "Call or recording active; applying is deferred.", false);
            if (!validMicrophone(initial.bottom)) return missingMicrophone();
            session = create(effective, initial, false);
            store.save(session); // Both originals are durable before the first possible mutation.
            return apply();
        } catch (Exception failure) {
            return result(false, "blocked", "Query, journal or guarded routing operation unavailable. No blind retry; inspect status or Stop.", pending());
        }
    }

    private Result status(Observation observation) {
        if (!observation.authorized) return result(false, "blocked", "Shell UID or routing permissions unavailable.", pending());
        if (session == null || STOPPED.equals(session.phase)) return result(true, "idle", "No active helper ownership. Status made no writes.", false);
        if (CONFLICT.equals(session.phase)) return result(false, "conflict", "External preference change recorded; status made no writes.", false);
        if (observation.inventorySettling) return waiting();
        if (session.pending()) return result(true, observation.idle ? "idle" : "busy", "Interrupted or deferred restoration requires Stop. Status made no writes.", true);
        for (int i = 0; i < 2; i++) {
            Preference expected = session.steps[i] == OWNED ? session.desired : session.originals[i];
            if (!expected.equals(observation.preferences[i])) return result(false, "conflict", "Current preferences differ from helper ownership. Status made no writes.", false);
        }
        return result(true, observation.idle ? "active" : "busy", "Journal and preferences inspected; status made no writes.", false);
    }

    private Result defaults(Selection selected, Observation observation) throws Exception {
        if (observation.inventorySettling) return waiting();
        if (!observation.safeInventory) return result(false, "blocked", "Choose the single connected DAC before clearing defaults.", pending());
        if (!observation.idle) return result(true, "busy", "Call or recording active; Android defaults are deferred.", true);
        if (!validMicrophone(observation.bottom)) return missingMicrophone();
        Preference bottom = Preference.of(observation.bottom);
        for (Preference preference : observation.preferences) {
            if (!preference.isNone() && !preference.equals(bottom)) {
                return result(false, "conflict", "Defaults refused: a preset has an unrelated preference. It was preserved.", false);
            }
        }
        session = create(selected, observation, true);
        store.save(session);
        return apply();
    }

    private Session create(Selection selected, Observation observation, boolean defaults) {
        Session s = new Session(); s.selection = selected; s.bottom = observation.bottom;
        s.attachmentKey = observation.attachmentKey; s.phase = APPLYING; s.defaults = defaults;
        s.originals = observation.preferences.clone();
        s.desired = defaults ? Preference.none() : Preference.of(s.bottom);
        return s;
    }

    private Result apply() throws Exception {
        try {
            for (int i = 0; i < 2; i++) {
                Observation current = observe(session.selection);
                if (!reconcile(current)) return conflict();
                if (current.inventorySettling) throw new InventoryTransition();
                if (!current.idle) {
                    session.recoveryBlocked = true;
                    session.phase = RESTORING; store.save(session);
                    return result(true, "busy", "Audio became busy; partial apply will be restored after it is idle.", true);
                }
                requireWritable(current, !session.defaults);
                if (session.originals[i].equals(session.desired)) continue;
                session.steps[i] = APPLY_INTENT;
                store.save(session);
                writeAttempts++;
                backend.write(new Write(i, session.originals[i], session.desired, session, true));
                Observation after = observe(session.selection);
                if (!session.desired.equals(after.preferences[i])) throw new IllegalStateException("Readback failed");
                session.steps[i] = OWNED; store.save(session);
            }
            Observation finalState = observe(session.selection);
            if (!reconcile(finalState)) return conflict();
            if (finalState.inventorySettling) throw new InventoryTransition();
            if (!session.defaults && finalState.safeInventory && (!finalState.attached
                    || !Objects.equals(session.attachmentKey, finalState.attachmentKey))) throw new InventoryTransition();
            if (session.defaults) {
                session.phase = STOPPED;
                session.steps = new int[]{RESTORED, RESTORED};
                store.save(session);
                return result(true, "restored", "MIC and VOICE_COMMUNICATION use verified Android defaults. Automation is stopped.", false);
            }
            session.phase = ACTIVE; store.save(session);
            return result(true, "active", "MIC and VOICE_COMMUNICATION prefer the selected built-in microphone. USB output was not changed; actual app routing still needs verification.", false);
        } catch (InventoryTransition transition) {
            session.phase = RESTORING;
            store.save(session);
            return waiting();
        } catch (Exception failure) {
            session.recoveryBlocked = true;
            session.phase = RESTORING;
            try { store.save(session); }
            catch (Exception journalFailure) {
                return result(false, "blocked", "Apply interrupted; durable intent retained. Stop is required to reconcile ownership.", true);
            }
            return restore("Apply failed; originals restored after readback reconciliation. Enable again deliberately.", false);
        }
    }

    private Result restore(String complete, boolean ok) throws Exception {
        Observation current = observe(session.selection);
        if (!reconcile(current)) return conflict();
        if (!current.idle) return result(true, "busy", "Call or recording active; original-state restoration is deferred.", true);
        if (current.inventorySettling) return waiting();
        requireWritable(current, false);
        for (int i = 0; i < 2; i++) {
            current = observe(session.selection);
            if (!reconcile(current)) return conflict();
            if (!current.idle) return result(true, "busy", "Audio became busy; original-state restoration remains pending.", true);
            if (current.inventorySettling) return waiting();
            requireWritable(current, false);
            if (session.steps[i] != OWNED && session.steps[i] != RESTORE_INTENT) continue;
            if (session.originals[i].equals(current.preferences[i])) {
                session.steps[i] = RESTORED; store.save(session); continue;
            }
            session.steps[i] = RESTORE_INTENT;
            store.save(session);
            try {
                writeAttempts++;
                backend.write(new Write(i, session.desired, session.originals[i], session, false));
            } catch (InventoryTransition transition) {
                return waiting();
            } catch (Exception failure) {
                Observation afterFailure = observe(session.selection);
                if (!reconcile(afterFailure)) return conflict();
                store.save(session);
                return result(false, "blocked", "Restoration write failed; actual preferences reconciled. Stop again explicitly; no blind retry.", session.owned() > 0);
            }
            Observation after = observe(session.selection);
            if (!session.originals[i].equals(after.preferences[i])) {
                if (!reconcile(after)) return conflict();
                return result(false, "blocked", "Restoration readback failed; ownership journal retained. Stop again explicitly.", true);
            }
            session.steps[i] = RESTORED; store.save(session);
        }
        session.phase = STOPPED; store.save(session);
        return result(ok, "restored", complete, false);
    }

    /** Resolve uncertain writes by observation, never by repeating the write. */
    private boolean reconcile(Observation observation) throws Exception {
        boolean changed = false;
        for (int i = 0; i < 2; i++) {
            Preference current = observation.preferences[i];
            int step = session.steps[i];
            if (step == APPLY_INTENT) {
                if (current.equals(session.desired)) { session.steps[i] = OWNED; changed = true; }
                else if (current.equals(session.originals[i])) { session.steps[i] = NEW; changed = true; }
                else return false;
            } else if (step == RESTORE_INTENT) {
                if (current.equals(session.originals[i])) { session.steps[i] = RESTORED; changed = true; }
                else if (!current.equals(session.desired)) return false;
            } else if (step == OWNED) {
                if (!current.equals(session.desired)) return false;
            } else if (!current.equals(session.originals[i])) return false;
        }
        if (changed) store.save(session);
        return true;
    }

    private Result conflict() throws Exception {
        session.phase = CONFLICT;
        session.steps = new int[]{RESTORED, RESTORED};
        store.save(session);
        return result(false, "conflict", "External preferences differ from the journal. Ownership ended; no routing writes or guessed undo.", false);
    }

    private Observation observe(Selection selected) throws Exception {
        boolean stoppedTarget = session != null && STOPPED.equals(session.phase) && !session.defaults
                && !session.recoveryBlocked && Objects.equals(selected, session.selection);
        Device retained = session != null && (!STOPPED.equals(session.phase) || stoppedTarget) ? session.bottom : null;
        Integer requested = "stop".equals(currentAction) || (session != null && session.pending()) ? null : selectedMicrophoneId;
        Observation observation = backend.observe(selected, retained, requested);
        // A new explicit valid selection after Stop wins; a stale ID after reconnect keeps the
        // previously verified canonical microphone rather than guessing a different input.
        if (stoppedTarget && observation != null && observation.requestedMicrophone != null
                && !session.bottom.equals(observation.requestedMicrophone)) {
            observation = backend.observe(selected, null, requested);
        }
        if (observation == null || observation.preferences[0] == null || observation.preferences[1] == null) {
            throw new IllegalStateException("Unknown preference query");
        }
        lastIdle = observation.idle;
        return observation;
    }

    private void requireWritable(Observation current, boolean attached) throws InventoryTransition {
        if (current.authorized && current.idle && (current.inventorySettling || (current.safeInventory
                && attached && (!current.attached || !Objects.equals(session.attachmentKey, current.attachmentKey))))) {
            throw new InventoryTransition();
        }
        if (!current.authorized || !current.idle || !current.safeInventory
                || !session.bottom.equals(current.bottom) || (attached && (!current.attached
                || !Objects.equals(session.attachmentKey, current.attachmentKey)))) {
            throw new IllegalStateException("Routing guards changed");
        }
    }

    private static boolean validMicrophone(Device microphone) {
        return microphone != null && microphone.role == INPUT_ROLE && microphone.type == MIC_TYPE;
    }
    private Result missingMicrophone() {
        return result(false, "blocked", "Built-in microphone selection is missing or ambiguous. Choose a unique built-in input.", pending());
    }
    private Result waiting() {
        return result(true, "waiting", "Waiting for the selected USB device to finish connecting or disconnecting; no routing writes.", pending());
    }
    private boolean pending() { return session != null && (session.pending() || session.owned() > 0); }
    private Result result(boolean ok, String state, String message, boolean pending) {
        return new Result(ok, state, message, lastIdle, pending, writeAttempts, session == null ? 0 : session.owned());
    }
}
