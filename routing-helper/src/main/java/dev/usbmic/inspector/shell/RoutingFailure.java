package dev.usbmic.inspector.shell;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;

/** Only fixed operation names and allowlisted error categories enter the app protocol. */
public final class RoutingFailure extends Exception {
    public enum Stage {
        COMMAND, IDENTITY, JOURNAL_LOCK, JOURNAL_LOAD, JOURNAL_SAVE, JOURNAL_DECODE,
        JOURNAL_SERIALIZE, JOURNAL_DIRECTORY, JOURNAL_TEMP_CREATE, JOURNAL_TEMP_WRITE,
        JOURNAL_TEMP_SYNC, JOURNAL_TEMP_VERIFY, JOURNAL_RENAME, JOURNAL_DIRECTORY_SYNC,
        BACKEND_CONTEXT, BACKEND_ATTRIBUTES, BACKEND_AUDIO_SERVICE, BACKEND_NATIVE_QUERY, BACKEND_POLICY_BINDER,
        PERMISSION, AUDIO_MODE, RECORDINGS, AUDIO_INVENTORY, MICROPHONE_SELECTION, USB_INVENTORY,
        USB_MATCH, ATTACHMENT_KEY, JAVA_CAPTURE_QUERY, NATIVE_CAPTURE_QUERY, DEVICE_ATTRIBUTES,
        NATIVE_MAP_DUMP, NATIVE_MAP_PARSE, CAPTURE_CONSISTENCY, ENGINE_PREPARE, ENGINE_OBSERVE,
        GUARDS, GUARDED_WRITE, MUTATION_ATTRIBUTES, MUTATION_CALL, MUTATION_RESULT, APPLY_READBACK, RESTORE_READBACK
    }
    public final Stage stage;
    public RoutingFailure(Stage stage, Exception cause) { super(cause); this.stage = stage; }
    public static RoutingFailure at(Stage stage, Exception cause) {
        return cause instanceof RoutingFailure ? (RoutingFailure) cause : new RoutingFailure(stage, cause);
    }
    public static String describe(Throwable failure, Stage fallback) {
        Stage stage = failure instanceof RoutingFailure ? ((RoutingFailure) failure).stage : fallback;
        Throwable cause = root(failure);
        String category;
        switch (cause.getClass().getName()) {
            case "java.lang.ClassNotFoundException": category = "ClassNotFoundException"; break;
            case "java.lang.NoSuchMethodException": category = "NoSuchMethodException"; break;
            case "java.lang.NoSuchFieldException": category = "NoSuchFieldException"; break;
            case "java.lang.IllegalAccessException": category = "IllegalAccessException"; break;
            case "java.lang.SecurityException": category = "SecurityException"; break;
            case "java.lang.IllegalArgumentException": category = "IllegalArgumentException"; break;
            case "java.lang.IllegalStateException": category = "IllegalStateException"; break;
            case "java.lang.NullPointerException": category = "NullPointerException"; break;
            case "java.io.IOException": category = "IOException"; break;
            case "java.io.SyncFailedException": category = "SyncFailedException"; break;
            case "java.io.FileNotFoundException": category = "FileNotFoundException"; break;
            case "java.lang.InterruptedException": category = "InterruptedException"; break;
            case "android.os.RemoteException": category = "RemoteException"; break;
            case "android.os.DeadObjectException": category = "DeadObjectException"; break;
            case "android.os.ServiceSpecificException": category = "ServiceSpecificException"; break;
            case "android.system.ErrnoException":
                category = "ErrnoException";
                try { category += " errno=" + cause.getClass().getField("errno").getInt(cause); }
                catch (ReflectiveOperationException ignored) { }
                break;
            case "org.json.JSONException": category = "JSONException"; break;
            default: category = cause instanceof Error ? "Error" : "Exception";
        }
        return stage.name() + " (" + category + ")";
    }
    static Throwable root(Throwable failure) {
        for (int depth = 0; depth < 12 && failure.getCause() != null; depth++) {
            if (!(failure instanceof RoutingFailure || failure instanceof InvocationTargetException
                    || failure instanceof UndeclaredThrowableException)) break;
            failure = failure.getCause();
        }
        return failure;
    }
}
