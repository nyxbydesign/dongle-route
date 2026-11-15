package dev.usbmic.inspector.shell;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Standard Binder dump only; full EOF and completed call are required. Nothing is logged. */
public final class BoundedBinderDump {
    private BoundedBinderDump() { }
    public static String read(IBinder binder, long operationDeadline) throws Exception {
        long end = Math.min(operationDeadline, SystemClock.elapsedRealtime() + 600);
        if (end - SystemClock.elapsedRealtime() < 100) throw new IllegalStateException("Native capture proof time budget exhausted");
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        AtomicReference<byte[]> bytes = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > CaptureRoleMap.MAX_BYTES) throw new IllegalStateException("Native capture dump exceeds bound");
                    output.write(buffer, 0, count);
                }
                bytes.set(output.toByteArray());
            } catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "capture-map-reader");
        Thread writer = new Thread(() -> {
            try { binder.dump(pipe[1].getFileDescriptor(), new String[0]); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { try { pipe[1].close(); } catch (Exception ignored) { } }
        }, "capture-map-dump");
        reader.setDaemon(true); writer.setDaemon(true); reader.start(); writer.start();
        try {
            joinUntil(writer, end); joinUntil(reader, end);
            if (writer.isAlive() || reader.isAlive() || failure.get() != null || bytes.get() == null) {
                throw new IllegalStateException("Complete native capture dump unavailable within budget");
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.get())).toString();
        } finally { try { pipe[0].close(); } finally { pipe[1].close(); } }
    }
    private static void joinUntil(Thread thread, long end) throws InterruptedException {
        long remaining = end - SystemClock.elapsedRealtime();
        if (remaining > 0) thread.join(remaining);
    }
}
