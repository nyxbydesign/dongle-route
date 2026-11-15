package dev.usbmic.inspector.adb;

import android.content.Context;
import android.os.Build;
import io.github.muntashirakon.adb.AdbConnection;
import io.github.muntashirakon.adb.AdbIdentity;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.PairingConnectionCtx;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** App-internal local transport. Never export this as a general command or Binder interface. */
public final class LocalAdbClient implements Closeable {
    private static final String HOST = "127.0.0.1";
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "local-adb-deadline"); thread.setDaemon(true); return thread;
    });
    private final AdbIdentity identity;
    private final ReentrantLock work = new ReentrantLock();
    private volatile AdbConnection connection;
    private volatile Operation active;

    public static final class ShellResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        ShellResult(AdbWire.ShellData data) { exitCode = data.exitCode; stdout = data.stdout; stderr = data.stderr; }
    }

    public LocalAdbClient(Context context) throws IOException {
        identity = new AdbIdentity(new File(context.getNoBackupFilesDir(), "local-adb"));
    }

    /** The caller's pairing-code array is erased even on invalid input or cancellation. */
    public void pair(int port, char[] code) throws IOException {
        byte[] password = null;
        try {
            AdbWire.checkPort(port);
            if (code == null || code.length != 6) throw new IOException("Enter the six-digit pairing code");
            password = new byte[6];
            for (int i = 0; i < code.length; i++) {
                if (code[i] < '0' || code[i] > '9') throw new IOException("Enter the six-digit pairing code");
                password[i] = (byte) code[i];
            }
            try (Operation operation = begin(20000)) {
                disconnectCurrent();
                identity.ensureExists();
                PrivateKey key = identity.loadPrivateKey();
                Certificate certificate = identity.loadCertificate();
                identity.verifyPair(key, certificate);
                Socket socket = operation.socket();
                PairingConnectionCtx pairing = new PairingConnectionCtx(HOST, port, password, key,
                        certificate, "DongleRoute");
                pairing.setSocket(socket);
                operation.setPairing(pairing);
                pairing.start();
                operation.check();
            } catch (IOException failure) { throw failure("Local ADB pairing", failure); }
            catch (Exception failure) { throw failure("Local ADB pairing", failure); }
        } finally {
            if (password != null) Arrays.fill(password, (byte) 0);
            if (code != null) Arrays.fill(code, '\0');
        }
    }

    public void connect(int port) throws IOException {
        AdbWire.checkPort(port);
        try (Operation operation = begin(15000)) {
            disconnectCurrent();
            identity.ensureExists();
            PrivateKey key = identity.loadPrivateKey(); // Fresh object: upstream closes destroy its key.
            Certificate certificate = identity.loadCertificate();
            identity.verifyPair(key, certificate);
            Socket socket = operation.socket();
            socket.connect(new InetSocketAddress(InetAddress.getByName(HOST), port), 8000);
            operation.check();
            AdbConnection candidate = new AdbConnection.Builder(HOST, port).setSocket(socket)
                    .setApi(Build.VERSION.SDK_INT).setPrivateKey(key).setCertificate(certificate)
                    .setDeviceName("DongleRoute").build();
            operation.setConnection(candidate);
            if (!candidate.connect(10000, TimeUnit.MILLISECONDS, true)) throw new IOException("Local ADB connection timed out");
            operation.check();
            connection = candidate;
            operation.keepConnection = true;
        } catch (IOException failure) { throw failure("Local ADB connection", failure); }
        catch (Exception failure) { throw failure("Local ADB connection", failure); }
    }

    public boolean isConnected() {
        AdbConnection current = connection;
        return current != null && current.isConnected() && current.isConnectionEstablished();
    }

    /** Call only with fixed bridge-owned commands. User text must never be concatenated here. */
    public ShellResult shell(String command, int timeoutMs, int maxOutputBytes) throws IOException {
        if (command == null || command.isEmpty() || command.length() > 8192
                || command.indexOf('\0') >= 0) throw new IOException("Invalid internal command");
        if (maxOutputBytes < 1 || maxOutputBytes > 1024 * 1024) throw new IOException("Invalid shell output limit");
        try (Operation operation = begin(timeoutMs)) {
            AdbConnection current = requireConnection();
            operation.setConnection(current);
            try (AdbStream stream = current.open("shell,v2,raw:" + command)) {
                ShellResult result = new ShellResult(AdbWire.readShell(stream.openInputStream(), maxOutputBytes));
                operation.check();
                operation.keepConnection = true;
                return result;
            }
        } catch (IOException failure) { throw failure("Local ADB command", failure); }
        catch (Exception failure) { throw failure("Local ADB command", failure); }
    }

    public void push(byte[] bytes, String path, int timeoutMs) throws IOException {
        AdbWire.checkPath(path);
        if (bytes == null || bytes.length == 0 || bytes.length > 4 * 1024 * 1024) throw new IOException("Invalid helper size");
        try (Operation operation = begin(timeoutMs)) {
            AdbConnection current = requireConnection();
            operation.setConnection(current);
            try (AdbStream stream = current.open("sync:")) {
                AdbWire.sendFile(stream.openOutputStream(), bytes, path);
                AdbWire.readSyncResult(stream.openInputStream());
                operation.check();
                operation.keepConnection = true;
            }
        } catch (IOException failure) { throw failure("Local ADB helper upload", failure); }
        catch (Exception failure) { throw failure("Local ADB helper upload", failure); }
    }

    /** Safe from another thread, including while pair/connect/open/read/write is blocked. */
    @Override public void close() {
        Operation operation = active;
        if (operation != null) operation.cancel();
        disconnectCurrent();
    }

    public void forgetIdentity() throws IOException {
        close();
        if (!work.tryLock()) throw new IOException("Wait for cancelled operation before forgetting identity");
        try { identity.forget(); } finally { work.unlock(); }
    }

    private AdbConnection requireConnection() throws IOException {
        AdbConnection current = connection;
        if (current == null || !isConnected()) throw new IOException("Connect local Wireless debugging first");
        return current;
    }

    private void disconnectCurrent() {
        AdbConnection current = connection;
        connection = null;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
    }

    private Operation begin(int timeoutMs) throws IOException {
        if (timeoutMs < 1 || timeoutMs > 60000) throw new IOException("Invalid ADB operation deadline");
        if (!work.tryLock()) throw new IOException("Another local ADB operation is active");
        Operation operation = new Operation(timeoutMs);
        active = operation;
        return operation;
    }

    private static IOException failure(String action, Exception failure) {
        // Do not propagate TLS, peer, key, pairing-code or device-address details into UI logs.
        return new IOException(action + " failed (" + failure.getClass().getSimpleName() + ")");
    }

    private final class Operation implements AutoCloseable {
        private final long until;
        private final ScheduledFuture<?> timeout;
        private volatile boolean cancelled;
        private volatile Socket socket;
        private volatile PairingConnectionCtx pairing;
        private volatile AdbConnection candidate;
        private boolean keepConnection;
        Operation(int timeoutMs) {
            until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            timeout = DEADLINES.schedule(this::cancel, timeoutMs, TimeUnit.MILLISECONDS);
        }
        synchronized Socket socket() throws IOException {
            check(); socket = new Socket(); return socket;
        }
        synchronized void setPairing(PairingConnectionCtx value) throws IOException {
            pairing = value;
            if (cancelled) { value.close(); throw new IOException("Local ADB operation cancelled"); }
        }
        synchronized void setConnection(AdbConnection value) throws IOException {
            candidate = value;
            if (cancelled) { value.close(); throw new IOException("Local ADB operation cancelled"); }
        }
        void check() throws IOException {
            if (cancelled || System.nanoTime() >= until || Thread.currentThread().isInterrupted()) {
                cancel(); throw new IOException("Local ADB operation cancelled or timed out");
            }
        }
        void cancel() {
            cancelled = true;
            Socket raw = socket;
            if (raw != null) try { raw.close(); } catch (IOException ignored) { }
            PairingConnectionCtx pair = pairing;
            if (pair != null) pair.close();
            AdbConnection current = candidate;
            if (current != null) try { current.close(); } catch (IOException ignored) { }
        }
        @Override public void close() {
            timeout.cancel(false);
            PairingConnectionCtx pair = pairing;
            if (pair != null) pair.close();
            if (!keepConnection || cancelled) cancel();
            active = null;
            work.unlock();
        }
    }
}
