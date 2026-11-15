package dev.usbmic.inspector.adb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class AdbWireTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        AdbWire.ShellData result = AdbWire.readShell(in(frames(frame(1, "hello"), frame(2, "warning"), exit(37))), 12);
        check(result.exitCode == 37 && "hello".equals(result.stdout) && "warning".equals(result.stderr), "split output and exact status");
        check(AdbWire.readShell(in(frames(frame(1, "x"), exit(255))), 1).exitCode == 255, "exit byte unsigned and outside output budget");
        InputStream fragmented = new ByteArrayInputStream(frames(frame(1, "ok"), exit(0))) {
            @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(len, 1)); }
        };
        check("ok".equals(AdbWire.readShell(fragmented, 2).stdout), "fragmented headers and payloads");
        rejects(() -> AdbWire.readShell(in(frame(1, "no exit")), 64), "missing exit");
        rejects(() -> AdbWire.readShell(in(frames(frame(1, "ab"), frame(2, "c"), exit(0))), 2), "combined output bound");
        rejects(() -> AdbWire.readShell(in(frames(frame(4, "x"), exit(0))), 10), "unexpected channel");
        rejects(() -> AdbWire.readShell(in(new byte[]{1, -1, -1, -1, 127}), 10), "length rejected before allocation");
        rejects(() -> AdbWire.readShell(in(new byte[]{1, -1, -1, -1, -1}), 10), "negative length");
        rejects(() -> AdbWire.readShell(in(new byte[]{3, 4, 0, 0, 0, 0, 0, 0, 0}), 10), "wrong exit payload");
        rejects(() -> AdbWire.readExact(new InputStream() { public int read() { return 0; } public int read(byte[] b, int o, int l) { return 0; } }, 3), "no progress");
        for (int port : new int[]{1, 65535, 37000}) { AdbWire.checkPort(port); assertions++; }
        for (int port : new int[]{-1, 0, 65536, Integer.MAX_VALUE}) rejects(() -> AdbWire.checkPort(port), "bad port");
        AdbWire.checkPath(AdbWire.HELPER_PATH); assertions++;
        for (String path : new String[]{"/data/local/tmp/other.jar", AdbWire.HELPER_PATH + "/../x", AdbWire.HELPER_PATH + "\n", "", null}) rejects(() -> AdbWire.checkPath(path), "fixed path only");
        byte[] bytes = new byte[65537]; Arrays.fill(bytes, (byte) 7);
        ByteArrayOutputStream wire = new ByteArrayOutputStream(); AdbWire.sendFile(wire, bytes, AdbWire.HELPER_PATH);
        ByteArrayInputStream input = in(wire.toByteArray());
        check("SEND".equals(ascii(AdbWire.readExact(input, 4))), "SEND id");
        int targetLength = little(AdbWire.readExact(input, 4));
        check((AdbWire.HELPER_PATH + ",33152").equals(ascii(AdbWire.readExact(input, targetLength))), "mode owner rw only");
        check("DATA".equals(ascii(AdbWire.readExact(input, 4))) && little(AdbWire.readExact(input, 4)) == 65536, "chunk limit");
        check(Arrays.equals(Arrays.copyOf(bytes, 65536), AdbWire.readExact(input, 65536)), "first data bytes");
        check("DATA".equals(ascii(AdbWire.readExact(input, 4))) && little(AdbWire.readExact(input, 4)) == 1, "last chunk"); input.read();
        check("DONE".equals(ascii(AdbWire.readExact(input, 4))) && little(AdbWire.readExact(input, 4)) == 0 && input.read() == -1, "DONE frame");
        AdbWire.readSyncResult(in(new byte[]{'O','K','A','Y',0,0,0,0})); assertions++;
        rejects(() -> AdbWire.readSyncResult(in(new byte[]{'F','A','I','L',1,0,0,0,'x'})), "remote rejection");
        rejects(() -> AdbWire.readSyncResult(in(new byte[]{'F','A','I','L',-1,-1,-1,127})), "bounded failure");
        rejects(() -> AdbWire.readSyncResult(in(new byte[]{'O','K','A','Y',1,0,0,0,'x'})), "bad OKAY length");
        rejects(() -> AdbWire.sendFile(new ByteArrayOutputStream(), new byte[0], AdbWire.HELPER_PATH), "empty helper");
        System.out.println("AdbWireTest: " + assertions + " assertions passed");
    }
    interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, String label) throws Exception {
        try { action.run(); throw new AssertionError(label); } catch (IOException expected) { assertions++; }
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); assertions++; }
    private static byte[] frame(int channel, String text) { return packet(channel, text.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] exit(int code) { return packet(3, new byte[]{(byte) code}); }
    private static byte[] packet(int channel, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(channel);
        byte[] length = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).array(); out.write(length, 0, 4); out.write(data, 0, data.length); return out.toByteArray();
    }
    private static byte[] frames(byte[]... parts) { ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] part : parts) out.write(part, 0, part.length); return out.toByteArray(); }
    private static ByteArrayInputStream in(byte[] bytes) { return new ByteArrayInputStream(bytes); }
    private static int little(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(); }
    private static String ascii(byte[] bytes) { return new String(bytes, StandardCharsets.US_ASCII); }
}
