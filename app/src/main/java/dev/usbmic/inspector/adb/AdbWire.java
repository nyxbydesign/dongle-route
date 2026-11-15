package dev.usbmic.inspector.adb;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Bounded shell-v2 and sync-v1 framing; independent of Android for protocol tests. */
final class AdbWire {
    static final String HELPER_PATH = "/data/local/tmp/dev.usbmic.inspector/routing-helper.jar";
    static final class ShellData {
        final int exitCode; final String stdout; final String stderr;
        ShellData(int code, byte[] out, byte[] err) {
            exitCode = code; stdout = new String(out, StandardCharsets.UTF_8); stderr = new String(err, StandardCharsets.UTF_8);
        }
    }
    static void checkPort(int port) throws IOException {
        if (port < 1 || port > 65535) throw new IOException("Invalid local ADB port");
    }
    static void checkPath(String path) throws IOException {
        if (!HELPER_PATH.equals(path)) throw new IOException("Only the fixed routing helper upload is allowed");
    }
    static ShellData readShell(InputStream input, int maximum) throws IOException {
        if (maximum < 1 || maximum > 1024 * 1024) throw new IOException("Invalid shell output limit");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int total = 0;
        while (true) {
            int channel = input.read();
            if (channel < 0) throw new EOFException("Shell closed before exit status");
            int length = littleInt(readExact(input, 4));
            if (channel == 3) {
                if (length != 1) throw new IOException("Malformed shell exit status");
                return new ShellData(readExact(input, 1)[0] & 255, stdout.toByteArray(), stderr.toByteArray());
            }
            if (channel != 1 && channel != 2) throw new IOException("Unexpected shell channel");
            if (length < 0 || length > maximum || total > maximum - length) throw new IOException("Shell output limit exceeded");
            byte[] bytes = readExact(input, length);
            total += length;
            if (channel == 1) stdout.write(bytes);
            else if (channel == 2) stderr.write(bytes);
        }
    }
    static void sendFile(OutputStream output, byte[] bytes, String path) throws IOException {
        checkPath(path);
        if (bytes == null || bytes.length == 0 || bytes.length > 4 * 1024 * 1024) throw new IOException("Invalid helper size");
        byte[] target = (path + ",33152").getBytes(StandardCharsets.UTF_8); // regular file, owner read/write
        header(output, "SEND", target.length); output.write(target);
        for (int offset = 0; offset < bytes.length;) {
            int length = Math.min(65536, bytes.length - offset);
            header(output, "DATA", length); output.write(bytes, offset, length); offset += length;
        }
        header(output, "DONE", 0); output.flush();
    }
    static void readSyncResult(InputStream input) throws IOException {
        String id = new String(readExact(input, 4), StandardCharsets.US_ASCII);
        int length = littleInt(readExact(input, 4));
        if ("OKAY".equals(id) && length == 0) return;
        if ("FAIL".equals(id) && length >= 0 && length <= 4096) {
            readExact(input, length); // Do not expose arbitrary device-side text in an exception.
            throw new IOException("ADB helper upload was rejected");
        }
        throw new IOException("Malformed ADB sync response");
    }
    static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        for (int offset = 0; offset < length;) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) throw new EOFException("Truncated ADB frame");
            if (count == 0) throw new IOException("ADB stream made no progress");
            offset += count;
        }
        return bytes;
    }
    private static int littleInt(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(); }
    private static void header(OutputStream output, String id, int value) throws IOException {
        output.write(id.getBytes(StandardCharsets.US_ASCII));
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }
}
