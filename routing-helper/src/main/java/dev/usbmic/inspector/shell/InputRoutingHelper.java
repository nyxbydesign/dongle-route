package dev.usbmic.inspector.shell;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import dev.usbmic.inspector.shell.RoutingFailure.Stage;

/** Fixed app_process protocol for an already authorized local ADB shell. No shell evaluator. */
public final class InputRoutingHelper {
    private static final String DIRECTORY = "/data/local/tmp/dev.usbmic.inspector";
    private static final String JOURNAL = DIRECTORY + "/route-state.json";
    private static final String TEMP = JOURNAL + ".new";
    private static final String LOCK = DIRECTORY + "/route-state.lock";
    private static final int FILE_MODE = 0600;
    private static final long STARTED = SystemClock.elapsedRealtime();
    private InputRoutingHelper() { }

    public static void main(String[] args) {
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(15_000); }
            catch (InterruptedException finished) { return; }
            // A hung Binder cannot keep an unbounded helper alive. Durable write intent survives this exit.
            System.exit(124);
        }, "routing-helper-deadline");
        deadline.setDaemon(true);
        deadline.start();
        JSONObject output = null;
        int exitCode = 1;
        FileOutputStream lockStream = null;
        FileLock lock = null;
        Stage stage = Stage.COMMAND;
        try {
            String action = args.length > 0 ? args[0] : "";
            if (!("status".equals(action) || "tick".equals(action) || "tick-retained".equals(action) || "stop".equals(action) || "defaults".equals(action) || "check".equals(action))
                    || !(args.length == 1 || ((args.length == 3 || args.length == 4) && !"stop".equals(action)))) {
                throw new IllegalArgumentException("Fixed action or selection arguments invalid");
            }
            RoutingEngine.Selection selection = args.length >= 3
                    ? new RoutingEngine.Selection(decimal(args[1]), decimal(args[2])) : null;
            Integer microphoneId = args.length == 4 ? microphoneId(args[3]) : null;
            if ("tick-retained".equals(action) && args.length != 3) throw new IllegalArgumentException("Retained tick requires only a DAC selection");
            stage = Stage.IDENTITY;
            if (Process.myUid() != 2000) throw new SecurityException("ADB shell UID required");
            stage = Stage.JOURNAL_LOCK;
            {
                ensureDirectory();
                FileDescriptor fd = Os.open(LOCK, OsConstants.O_RDWR | OsConstants.O_CREAT | OsConstants.O_NOFOLLOW, FILE_MODE);
                lockStream = new FileOutputStream(fd);
                verifyRegular(LOCK);
                lock = lockStream.getChannel().tryLock();
                if (lock == null) throw new IllegalStateException("Another helper operation is active");
            }
            Journal store = new Journal();
            stage = Stage.JOURNAL_LOAD;
            RoutingEngine.Result result = RoutingEngine.execute(action, selection, microphoneId, store, AndroidBackend::new);
            output = json(result);
            exitCode = result.ok ? 0 : 1;
        } catch (Throwable failure) {
            // Avoid Binder error text, addresses and stack traces in the application protocol.
            try {
                output = new JSONObject().put("ok", false).put("state", "blocked")
                        .put("message", "Shell helper unavailable at " + RoutingFailure.describe(failure, stage)
                                + ". No automatic retry; inspect compatibility or reconnect.")
                        .put("idle", false).put("restorePending", unresolvedJournal())
                        .put("writeAttempts", 0).put("ownedPresets", 0);
            } catch (Exception ignored) { }
        } finally {
            if (lock != null) try { lock.release(); } catch (Exception ignored) { }
            if (lockStream != null) try { lockStream.close(); } catch (Exception ignored) { }
        }
        if (output == null) {
            System.out.println("USBMIC1:{\"ok\":false,\"state\":\"blocked\",\"message\":\"Helper failed before a verified result\",\"idle\":false,\"restorePending\":false}");
        } else System.out.println("USBMIC1:" + output.toString());
        System.out.flush();
        deadline.interrupt();
        System.exit(exitCode);
    }

    private static int decimal(String value) {
        if (!value.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid decimal selection");
        int parsed = Integer.parseInt(value);
        if (parsed > 65535) throw new IllegalArgumentException("Invalid decimal selection");
        return parsed;
    }

    private static int microphoneId(String value) {
        if (!value.matches("[0-9]{1,10}")) throw new IllegalArgumentException("Invalid microphone ID");
        long parsed = Long.parseLong(value);
        if (parsed <= 0 || parsed > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid microphone ID");
        return (int) parsed;
    }

    private static JSONObject json(RoutingEngine.Result result) throws Exception {
        return new JSONObject().put("ok", result.ok).put("state", result.state).put("message", result.message)
                .put("idle", result.idle).put("restorePending", result.restorePending)
                .put("writeAttempts", result.writeAttempts).put("ownedPresets", result.ownedPresets);
    }

    private static final class AndroidBackend implements RoutingEngine.Backend {
        final Context context;
        final AudioManager audio;
        final UsbManager usb;
        final Object audioService;
        final IBinder policyBinder;
        final Method javaGet, javaSet, javaClear, nativeGet, attributesRole, attributesType, attributesAddress;
        final Constructor<?> attributesConstructor;
        final int preferredRole;

        AndroidBackend() throws Exception {
            Stage stage = Stage.BACKEND_CONTEXT;
            try {
                Context system = ShellContextBootstrap.systemContext();
                context = system.createPackageContext("com.android.shell", 0);
                if (context.getApplicationInfo().uid != 2000 || !"com.android.shell".equals(context.getPackageName())) {
                    throw new SecurityException("Shell package context unavailable");
                }
                audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
                if (audio == null || usb == null) throw new IllegalStateException("Inventory services unavailable");
                requirePermission();
                stage = Stage.BACKEND_ATTRIBUTES;
                Class<?> attributes = Class.forName("android.media.AudioDeviceAttributes");
                attributesConstructor = attributes.getConstructor(int.class, int.class, String.class);
                attributesRole = attributes.getMethod("getRole");
                attributesType = attributes.getMethod("getType");
                attributesAddress = attributes.getMethod("getAddress");
                stage = Stage.BACKEND_AUDIO_SERVICE;
                Class<?> audioInterface = Class.forName("android.media.IAudioService");
                audioService = service("audio", "android.media.IAudioService$Stub");
                javaGet = audioInterface.getMethod("getPreferredDevicesForCapturePreset", int.class);
                javaSet = audioInterface.getMethod("setPreferredDevicesForCapturePreset", int.class, List.class);
                javaClear = audioInterface.getMethod("clearPreferredDevicesForCapturePreset", int.class);
                stage = Stage.BACKEND_NATIVE_QUERY;
                Class<?> audioSystem = Class.forName("android.media.AudioSystem");
                nativeGet = audioSystem.getMethod("getDevicesForRoleAndCapturePreset", int.class, int.class, List.class);
                preferredRole = audioSystem.getField("DEVICE_ROLE_PREFERRED").getInt(null);
                if (preferredRole != 1) throw new IllegalStateException("Unexpected native device role");
                stage = Stage.BACKEND_POLICY_BINDER;
                policyBinder = binder("media.audio_policy");
            } catch (Exception failure) { throw RoutingFailure.at(stage, failure); }
        }

        private AndroidBackend(Context context, AudioManager audio, UsbManager usb) {
            this.context = context; this.audio = audio; this.usb = usb;
            audioService = null; policyBinder = null;
            javaGet = javaSet = javaClear = nativeGet = attributesRole = attributesType = attributesAddress = null;
            attributesConstructor = null; preferredRole = 1;
        }

        void requirePermission() throws RoutingFailure {
            try {
            if (Process.myUid() != 2000
                    || context.checkPermission("android.permission.MODIFY_AUDIO_ROUTING", Process.myPid(), Process.myUid())
                    != PackageManager.PERMISSION_GRANTED) throw new SecurityException("Routing permission unavailable");
            } catch (Exception failure) { throw RoutingFailure.at(Stage.PERMISSION, failure); }
        }

        @Override public RoutingEngine.Observation observe(RoutingEngine.Selection selected,
                                                          RoutingEngine.Device retainedMicrophone,
                                                          Integer microphoneId) throws Exception {
            requirePermission();
            Environment before = environment(selected, retainedMicrophone, microphoneId);
            RoutingEngine.Preference[] preferences = preferences();
            RoutingEngine.Preference mic = preferences[0], voice = preferences[1];
            Environment after = environment(selected, retainedMicrophone, microphoneId);
            boolean stable = Objects.equals(before.bottom, after.bottom) && before.attached == after.attached
                    && before.microphoneId == after.microphoneId && Objects.equals(before.requestedMicrophone, after.requestedMicrophone)
                    && Objects.equals(before.key, after.key) && before.safe == after.safe;
            return new RoutingEngine.Observation(true, before.idle && after.idle,
                    before.safe && after.safe && stable, after.attached, after.bottom, after.key, mic, voice,
                    after.requestedMicrophone, microphoneId != null,
                    !before.refused && !after.refused && (!stable || before.settling || after.settling));
        }

        @Override public void write(RoutingEngine.Write request) throws Exception {
            Stage stage = Stage.GUARDS;
            try {
                int index = request.preset == 1 ? 0 : request.preset == 7 ? 1 : -1;
                if (index < 0) throw new IllegalArgumentException("Unsupported capture preset");
                RoutingEngine.Observation fresh = observe(request.selection, request.bottom, null);
                if (fresh.authorized && fresh.idle && (fresh.inventorySettling || (fresh.safeInventory
                        && request.requireAttached && (!fresh.attached || !Objects.equals(request.attachmentKey, fresh.attachmentKey))))) {
                    throw new RoutingEngine.InventoryTransition();
                }
                if (!fresh.authorized || !fresh.idle || !fresh.safeInventory || !request.bottom.equals(fresh.bottom)
                        || !request.expected.equals(fresh.preferences[index])
                        || (request.requireAttached && (!fresh.attached || !Objects.equals(request.attachmentKey, fresh.attachmentKey)))) {
                    throw new IllegalStateException("Fresh routing guards refused");
                }
                stage = Stage.MUTATION_ATTRIBUTES;
                List<Object> desired = new ArrayList<>();
                for (RoutingEngine.Device device : request.desired.devices) {
                    desired.add(attributesConstructor.newInstance(device.role, device.type, device.address));
                }
                stage = Stage.PERMISSION;
                requirePermission();
                stage = Stage.AUDIO_MODE;
                boolean normal = audio.getMode() == AudioManager.MODE_NORMAL;
                stage = Stage.RECORDINGS;
                if (!normal || !audio.getActiveRecordingConfigurations().isEmpty()) {
                    throw new IllegalStateException("Audio became busy");
                }
                stage = Stage.MUTATION_CALL;
                Object response = request.desired.isNone() ? javaClear.invoke(audioService, request.preset)
                        : javaSet.invoke(audioService, request.preset, desired);
                stage = Stage.MUTATION_RESULT;
                if (!(response instanceof Integer) || ((Integer) response) != 0) {
                    throw new IllegalStateException("Audio service refused routing mutation");
                }
            } catch (RoutingEngine.InventoryTransition transition) { throw transition; }
            catch (Exception failure) { throw RoutingFailure.at(stage, failure); }
        }

        private Environment environment(RoutingEngine.Selection selected, RoutingEngine.Device retainedMicrophone,
                                        Integer selectedMicrophoneId) throws Exception {
            Stage stage = Stage.PERMISSION;
            try {
                requirePermission();
                Environment result = new Environment();
                stage = Stage.AUDIO_MODE;
                boolean normal = audio.getMode() == AudioManager.MODE_NORMAL;
                stage = Stage.RECORDINGS;
                result.idle = normal && audio.getActiveRecordingConfigurations().isEmpty();
                List<AudioDeviceInfo> inputs = new ArrayList<>(), outputs = new ArrayList<>();
                List<MicrophoneSelector.Port> microphones = new ArrayList<>();
                stage = Stage.AUDIO_INVENTORY;
                for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_ALL)) {
                    if (device.isSource() && !device.isSink() && device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                        microphones.add(new MicrophoneSelector.Port(device.getId(),
                                new RoutingEngine.Device(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, device.getAddress())));
                    }
                    if (isUsb(device.getType())) {
                        if (device.isSource()) inputs.add(device);
                        if (device.isSink()) outputs.add(device);
                    }
                }
                stage = Stage.MICROPHONE_SELECTION;
                MicrophoneSelector.Port target = retainedMicrophone != null
                        ? MicrophoneSelector.resolve(microphones, retainedMicrophone)
                        : MicrophoneSelector.choose(microphones, selectedMicrophoneId);
                if (target != null) { result.bottom = target.device; result.microphoneId = target.id; }
                if (selectedMicrophoneId != null) {
                    MicrophoneSelector.Port requested = MicrophoneSelector.choose(microphones, selectedMicrophoneId);
                    result.requestedMicrophone = requested == null ? null : requested.device;
                }
                stage = Stage.USB_INVENTORY;
                List<UsbDevice> physical = new ArrayList<>(usb.getDeviceList().values());
                result.attached = !physical.isEmpty();
                boolean matches = physical.size() == 1 && selected != null
                        && physical.get(0).getVendorId() == selected.vid && physical.get(0).getProductId() == selected.pid;
                stage = Stage.USB_MATCH;
                boolean validPair = false;
                if (inputs.size() == 1 && outputs.size() == 1) {
                    AudioDeviceInfo in = inputs.get(0), out = outputs.get(0);
                    validPair = in.isSource() && !in.isSink() && out.isSink() && !out.isSource()
                            && in.getId() != out.getId() && in.getType() == out.getType() && !in.getAddress().isEmpty()
                            && in.getAddress().equals(out.getAddress());
                }
                UsbInventoryPolicy.State state = UsbInventoryPolicy.classify(physical.size(), matches, selected != null,
                        inputs.size(), outputs.size(), validPair);
                result.settling = state == UsbInventoryPolicy.State.SETTLING;
                result.refused = state == UsbInventoryPolicy.State.REFUSED;
                result.safe = state == UsbInventoryPolicy.State.ABSENT || state == UsbInventoryPolicy.State.ATTACHED;
                if (state == UsbInventoryPolicy.State.ABSENT) {
                    result.key = "absent";
                } else if (state == UsbInventoryPolicy.State.ATTACHED) {
                    UsbDevice dac = physical.get(0);
                    AudioDeviceInfo in = inputs.get(0), out = outputs.get(0);
                    stage = Stage.ATTACHMENT_KEY;
                    result.key = digest(selected.vid + ":" + selected.pid + ":" + dac.getDeviceName()
                            + ":" + in.getId() + ":" + in.getType() + ":" + in.getAddress()
                            + ":" + out.getId() + ":" + out.getType() + ":" + out.getAddress());
                }
                return result;
            } catch (Exception failure) { throw RoutingFailure.at(stage, failure); }
        }

        private RoutingEngine.Preference[] preferences() throws Exception {
            Stage stage = Stage.NATIVE_CAPTURE_QUERY;
            try {
                PreferenceRead[] reads = { preferenceRead(1), preferenceRead(7) };
                boolean absenceProof = false;
                for (PreferenceRead read : reads) if (read.before.status != 0) absenceProof = true;
                CaptureRoleMap before = null, after = null;
                if (absenceProof) {
                    stage = Stage.NATIVE_MAP_DUMP;
                    before = captureMap();
                    stage = Stage.CAPTURE_CONSISTENCY;
                    reads = new PreferenceRead[]{ preferenceRead(1), preferenceRead(7) };
                    stage = Stage.NATIVE_MAP_DUMP;
                    after = captureMap();
                    if (!before.equals(after)) throw new IllegalStateException("Native capture map changed during query");
                }
                RoutingEngine.Preference[] result = new RoutingEngine.Preference[2];
                stage = Stage.CAPTURE_CONSISTENCY;
                for (int i = 0; i < 2; i++) {
                    PreferenceRead read = reads[i];
                    result[i] = CapturePreferenceProof.resolve(RoutingEngine.PRESETS[i], read.before, read.javaDevices,
                            read.after, before, after);
                }
                return result;
            } catch (Exception failure) { throw RoutingFailure.at(stage, failure); }
        }

        private PreferenceRead preferenceRead(int preset) throws Exception {
            CapturePreferenceProof.Read before = nativePreference(preset);
            Object value;
            try { value = javaGet.invoke(audioService, preset); }
            catch (Exception failure) { throw RoutingFailure.at(Stage.JAVA_CAPTURE_QUERY, failure); }
            List<RoutingEngine.Device> javaDevices = devices(value);
            CapturePreferenceProof.Read after = nativePreference(preset);
            if (!before.agrees(after) || !before.devices.equals(javaDevices)) {
                throw new RoutingFailure(Stage.CAPTURE_CONSISTENCY, new IllegalStateException());
            }
            return new PreferenceRead(before, javaDevices, after);
        }

        private CapturePreferenceProof.Read nativePreference(int preset) throws Exception {
            List<Object> devices = new ArrayList<>();
            Object status;
            try { status = nativeGet.invoke(null, preset, preferredRole, devices); }
            catch (Exception failure) { throw RoutingFailure.at(Stage.NATIVE_CAPTURE_QUERY, failure); }
            if (!(status instanceof Integer)) throw new IllegalStateException("Native capture status unavailable");
            return new CapturePreferenceProof.Read((Integer) status, devices(devices));
        }

        private List<RoutingEngine.Device> devices(Object value) throws Exception {
            try {
                if (!(value instanceof List) || ((List<?>) value).size() > 16) {
                    throw new IllegalStateException("Capture device list unavailable");
                }
                List<RoutingEngine.Device> result = new ArrayList<>();
                for (Object attribute : (List<?>) value) {
                    result.add(new RoutingEngine.Device((Integer) attributesRole.invoke(attribute),
                            (Integer) attributesType.invoke(attribute), (String) attributesAddress.invoke(attribute)));
                }
                if (!result.isEmpty()) RoutingEngine.Preference.devices(result);
                return result;
            } catch (Exception failure) { throw RoutingFailure.at(Stage.DEVICE_ATTRIBUTES, failure); }
        }

        private CaptureRoleMap captureMap() throws Exception {
            String dump;
            try { dump = BoundedBinderDump.read(policyBinder, STARTED + 12_000); }
            catch (Exception failure) { throw RoutingFailure.at(Stage.NATIVE_MAP_DUMP, failure); }
            try { return CaptureRoleMap.parse(dump); }
            catch (Exception failure) { throw RoutingFailure.at(Stage.NATIVE_MAP_PARSE, failure); }
        }
        private static final class PreferenceRead {
            final CapturePreferenceProof.Read before, after;
            final List<RoutingEngine.Device> javaDevices;
            PreferenceRead(CapturePreferenceProof.Read before, List<RoutingEngine.Device> javaDevices, CapturePreferenceProof.Read after) {
                this.before = before; this.javaDevices = javaDevices; this.after = after;
            }
        }

        private static IBinder binder(String name) throws Exception {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
            if (binder == null || !binder.isBinderAlive()) throw new IllegalStateException("Required audio binder unavailable");
            return binder;
        }

        private static Object service(String name, String stub) throws Exception {
            IBinder binder = binder(name);
            return Class.forName(stub).getMethod("asInterface", IBinder.class).invoke(null, binder);
        }

        private static final class Environment {
            boolean idle, safe, attached, settling, refused;
            RoutingEngine.Device bottom, requestedMicrophone;
            int microphoneId;
            String key;
        }
    }

    /** Atomic, owner-only runtime state. No pairing keys, codes or other credentials are stored here. */
    private static final class Journal implements RoutingEngine.Store {
        private JSONObject document;
        @Override public RoutingEngine.Session load() throws Exception {
            Stage stage = Stage.JOURNAL_LOAD;
            try {
                StructStat stat = statIfExists(JOURNAL);
                if (stat == null) return null;
                verifyDirectory(); verifyRegular(JOURNAL);
                if (stat.st_size <= 0 || stat.st_size > 32_768) throw new IllegalStateException("Invalid journal size");
                FileDescriptor fd = Os.open(JOURNAL, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
                byte[] bytes = new byte[(int) stat.st_size];
                try (FileInputStream input = new FileInputStream(fd)) {
                    int offset = 0, count;
                    while (offset < bytes.length && (count = input.read(bytes, offset, bytes.length - offset)) > 0) offset += count;
                    if (offset != bytes.length || input.read() != -1) throw new IllegalStateException("Journal changed during read");
                }
                stage = Stage.JOURNAL_DECODE;
                document = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                return decode(document);
            } catch (Exception failure) { throw RoutingFailure.at(stage, failure); }
        }

        private static RoutingEngine.Session decode(JSONObject document) throws Exception {
            if (document.getInt("version") != 1) throw new IllegalStateException("Unknown journal version");
            RoutingEngine.Session session = new RoutingEngine.Session();
            session.phase = document.getString("phase");
            if (!Arrays.asList(RoutingEngine.APPLYING, RoutingEngine.ACTIVE, RoutingEngine.RESTORING,
                    RoutingEngine.CONFLICT, RoutingEngine.STOPPED).contains(session.phase)) throw new IllegalStateException("Unknown journal phase");
            session.defaults = document.getBoolean("defaults");
            session.recoveryBlocked = document.getBoolean("recoveryBlocked");
            JSONObject selected = document.optJSONObject("selected");
            if (selected != null) session.selection = new RoutingEngine.Selection(selected.getInt("vid"), selected.getInt("pid"));
            session.bottom = device(document.getJSONObject("bottom"));
            // Keep the schema-1 key so previous bottom-microphone journals remain recoverable.
            if (session.bottom.type != RoutingEngine.MIC_TYPE) throw new IllegalStateException("Journal target is not a built-in microphone");
            session.attachmentKey = document.getString("attachmentKey");
            session.desired = preference(document.getJSONObject("desired"));
            if (!(session.defaults ? session.desired.isNone() : session.desired.equals(RoutingEngine.Preference.of(session.bottom)))) {
                throw new IllegalStateException("Journal desired preference is not fixed by operation");
            }
            JSONArray originals = document.getJSONArray("originals"), steps = document.getJSONArray("steps");
            if (originals.length() != 2 || steps.length() != 2 || (!session.defaults && session.selection == null)) {
                throw new IllegalStateException("Invalid journal selection or preset count");
            }
            for (int i = 0; i < 2; i++) {
                session.originals[i] = preference(originals.getJSONObject(i));
                session.steps[i] = steps.getInt(i);
                if (session.steps[i] < RoutingEngine.NEW || session.steps[i] > RoutingEngine.RESTORED) throw new IllegalStateException("Invalid journal step");
            }
            return session;
        }

        @Override public void save(RoutingEngine.Session session) throws Exception {
            Stage stage = Stage.JOURNAL_SERIALIZE;
            try {
                JSONObject next = encode(session);
                JSONArray steps = next.getJSONArray("steps");
                if (document != null) {
                    JSONObject previous = new JSONObject(document.toString());
                    previous.remove("events");
                    if (previous.toString().equals(next.toString())) return;
                }
                JSONArray events = document == null ? new JSONArray() : document.optJSONArray("events");
                if (events == null) events = new JSONArray();
                JSONArray bounded = new JSONArray();
                for (int i = Math.max(0, events.length() - 15); i < events.length(); i++) bounded.put(events.getJSONObject(i));
                bounded.put(new JSONObject().put("time", System.currentTimeMillis()).put("phase", session.phase).put("steps", steps));
                next.put("events", bounded);
                byte[] bytes = next.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 32_768) throw new IllegalStateException("Journal exceeds bound");
                stage = Stage.JOURNAL_DIRECTORY;
                ensureDirectory();
                if (statIfExists(TEMP) != null) {
                    verifyRegular(TEMP);
                    if (!new File(TEMP).delete()) throw new IllegalStateException("Stale journal temporary could not be removed");
                }
                if (statIfExists(JOURNAL) != null) verifyRegular(JOURNAL);
                stage = Stage.JOURNAL_TEMP_CREATE;
                FileDescriptor fd = Os.open(TEMP, OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, FILE_MODE);
                try (FileOutputStream output = new FileOutputStream(fd)) {
                    stage = Stage.JOURNAL_TEMP_WRITE;
                    output.write(bytes); output.flush();
                    stage = Stage.JOURNAL_TEMP_SYNC;
                    output.getFD().sync();
                }
                stage = Stage.JOURNAL_TEMP_VERIFY;
                verifyRegular(TEMP);
                stage = Stage.JOURNAL_RENAME;
                Os.rename(TEMP, JOURNAL);
                stage = Stage.JOURNAL_DIRECTORY_SYNC;
                FileDescriptor directory = Os.open(DIRECTORY, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
                try {
                    StructStat opened = Os.fstat(directory);
                    if (!OsConstants.S_ISDIR(opened.st_mode) || opened.st_uid != 2000 || (opened.st_mode & 0777) != 0700) {
                        throw new SecurityException("Opened helper directory owner or mode invalid");
                    }
                    Os.fsync(directory);
                } finally { Os.close(directory); }
                document = next;
            } catch (Exception failure) { throw RoutingFailure.at(stage, failure); }
        }

        private static JSONObject encode(RoutingEngine.Session session) throws Exception {
            JSONObject next = new JSONObject().put("version", 1).put("phase", session.phase).put("defaults", session.defaults)
                    .put("recoveryBlocked", session.recoveryBlocked)
                    .put("selected", session.selection == null ? JSONObject.NULL : new JSONObject().put("vid", session.selection.vid).put("pid", session.selection.pid))
                    .put("bottom", device(session.bottom)).put("attachmentKey", session.attachmentKey == null ? "absent" : session.attachmentKey)
                    .put("desired", preference(session.desired));
            JSONArray originals = new JSONArray(), steps = new JSONArray();
            for (int i = 0; i < 2; i++) { originals.put(preference(session.originals[i])); steps.put(session.steps[i]); }
            next.put("originals", originals).put("steps", steps);
            return next;
        }

        private static RoutingEngine.Device device(JSONObject value) throws Exception {
            return new RoutingEngine.Device(value.getInt("role"), value.getInt("type"), value.getString("address"));
        }
        private static JSONObject device(RoutingEngine.Device value) throws Exception {
            return new JSONObject().put("role", value.role).put("type", value.type).put("address", value.address);
        }
        private static RoutingEngine.Preference preference(JSONObject value) throws Exception {
            JSONArray list = value.getJSONArray("devices");
            boolean none = value.getBoolean("none");
            if (none) {
                if (list.length() != 0) throw new IllegalStateException("Conflicting absent preference");
                return RoutingEngine.Preference.none();
            }
            List<RoutingEngine.Device> devices = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) devices.add(device(list.getJSONObject(i)));
            return RoutingEngine.Preference.devices(devices);
        }
        private static JSONObject preference(RoutingEngine.Preference value) throws Exception {
            JSONArray list = new JSONArray();
            for (RoutingEngine.Device device : value.devices) list.put(device(device));
            return new JSONObject().put("none", value.isNone()).put("devices", list);
        }
    }

    private static void ensureDirectory() throws Exception {
        if (statIfExists(DIRECTORY) == null) Os.mkdir(DIRECTORY, 0700);
        verifyDirectory();
    }
    private static boolean unresolvedJournal() {
        if (Process.myUid() != 2000) return false;
        try {
            RoutingEngine.Session session = new Journal().load();
            return session != null && (session.pending() || session.owned() > 0);
        } catch (Exception unavailable) {
            return true; // An unreadable journal cannot establish that restoration is complete.
        }
    }
    private static void verifyDirectory() throws Exception {
        verifyDirectoryStat(Os.lstat(DIRECTORY));
    }
    private static void verifyDirectoryStat(StructStat stat) {
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != 2000 || (stat.st_mode & 0777) != 0700) {
            throw new SecurityException("Helper directory owner or mode invalid");
        }
    }
    private static void verifyRegular(String path) throws Exception {
        verifyRegularStat(Os.lstat(path));
    }
    private static void verifyRegularStat(StructStat stat) {
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != 2000 || stat.st_nlink != 1 || (stat.st_mode & 0777) != FILE_MODE) {
            throw new SecurityException("Helper file owner, link count or mode invalid");
        }
    }
    private static StructStat statIfExists(String path) throws Exception {
        try { return Os.lstat(path); }
        catch (ErrnoException missing) { if (missing.errno == OsConstants.ENOENT) return null; throw missing; }
    }
    private static boolean isUsb(int type) {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }
    private static String digest(String value) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) {
            text.append(String.format(Locale.ROOT, "%02x", b & 255));
        }
        return text.toString();
    }

}
