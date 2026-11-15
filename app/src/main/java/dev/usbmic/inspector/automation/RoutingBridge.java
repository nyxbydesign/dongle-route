package dev.usbmic.inspector.automation;

import android.content.Context;
import dev.usbmic.inspector.adb.LocalAdbClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Only this fixed helper protocol is exposed to the app's routing controls. */
final class RoutingBridge {
    enum Action { STATUS, TICK, STOP, DEFAULTS }
    private static final String DIRECTORY = "/data/local/tmp/dev.usbmic.inspector";
    private static final String HELPER = DIRECTORY + "/routing-helper.jar";
    private final byte[] asset;
    private final String digest;
    private boolean uploaded;

    RoutingBridge(Context context) throws IOException {
        try (InputStream input = context.getAssets().open("routing-helper.jar");
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 1_048_576) throw new IOException("Embedded routing helper exceeds its size limit.");
                output.write(buffer, 0, read);
            }
            asset = output.toByteArray();
        }
        if (asset.length == 0) throw new IOException("Embedded routing helper is missing.");
        try {
            StringBuilder hash = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(asset)) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
            digest = hash.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    void resetUpload() { uploaded = false; }

    RoutingProtocol.Result execute(LocalAdbClient client, Action action, int vendor, int product, int microphoneId) throws IOException {
        if (!client.isConnected()) throw new IOException("Local ADB connection is not available.");
        if (!uploaded) {
            LocalAdbClient.ShellResult directory = client.shell("test ! -L " + DIRECTORY
                    + " && mkdir -p " + DIRECTORY + " && chmod 700 " + DIRECTORY + " && test ! -L " + HELPER, 5000, 4096);
            if (directory.exitCode != 0) throw new IOException("The private helper directory could not be prepared safely.");
            client.push(asset, HELPER, 8000);
            uploaded = true;
        }
        LocalAdbClient.ShellResult verified = client.shell("sha256sum " + HELPER, 5000, 4096);
        String[] fields = verified.stdout.trim().split("\\s+", 2);
        if (verified.exitCode != 0 || fields.length != 2 || !fields[0].equalsIgnoreCase(digest) || !fields[1].equals(HELPER)) {
            uploaded = false;
            throw new IOException("Routing helper verification failed. No routing command was run.");
        }
        String command = "CLASSPATH=" + HELPER + " app_process / dev.usbmic.inspector.shell.InputRoutingHelper "
                + action.name().toLowerCase(Locale.ROOT);
        if (action != Action.STOP) {
            if (vendor < 0 || vendor > 65535 || product < 0 || product > 65535) {
                if (action == Action.TICK) throw new IOException("Select exactly one USB audio dongle before enabling automation.");
                vendor = 0; product = 0; // Exact absent selection when no dongle is selected, never a wildcard.
            }
            command += " " + vendor + " " + product;
            if (microphoneId > 0) command += " " + microphoneId;
            else if (action == Action.TICK || action == Action.DEFAULTS) {
                throw new IOException("Select an unambiguous built-in microphone first.");
            }
        }
        LocalAdbClient.ShellResult response = client.shell(command, 22000, 32768);
        return RoutingProtocol.parse(response.stdout, response.exitCode);
    }
}
