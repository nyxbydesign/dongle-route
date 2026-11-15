package dev.usbmic.inspector;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import dev.usbmic.inspector.adb.LocalAdbClient;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Explicit integration harness for an isolated test device; never invoked by application flows. */
public final class LocalAdbSmokeInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle supplied) {
        super.onCreate(supplied);
        arguments = supplied == null ? new Bundle() : supplied;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        LocalAdbClient client = null;
        char[] code = null;
        String step = "arguments";
        try {
            if (!"isolated-emulator-only".equals(arguments.getString("testScope"))) {
                throw new IllegalArgumentException("Explicit isolated-device test scope required");
            }
            int pairPort = Integer.parseInt(arguments.getString("pairPort", "0"));
            int connectPort = Integer.parseInt(arguments.getString("connectPort", "0"));
            String suppliedCode = arguments.getString("pairCode", "");
            if (!suppliedCode.matches("[0-9]{6}")) throw new IllegalArgumentException("Invalid pairing input");
            code = suppliedCode.toCharArray();
            arguments.remove("pairCode");
            client = new LocalAdbClient(getTargetContext());
            step = "pair";
            client.pair(pairPort, code);
            result.putBoolean("paired", true);
            step = "connect";
            client.connect(connectPort);
            require(client.isConnected(), "Connection not established");
            result.putBoolean("connected", true);
            step = "shell_identity";
            LocalAdbClient.ShellResult identity = client.shell("id -u", 10_000, 4096);
            require(identity.exitCode == 0 && "2000".equals(identity.stdout.trim()), "Unexpected shell identity");
            result.putBoolean("shell_uid_2000", true);
            String directory = "/data/local/tmp/dev.usbmic.inspector";
            step = "transfer_setup";
            require(client.shell("mkdir -p " + directory + " && chmod 700 " + directory,
                    10_000, 4096).exitCode == 0, "Transfer directory unavailable");
            step = "sync_transfer";
            byte[] witness = "usb-mic-transport-smoke\n".getBytes(StandardCharsets.UTF_8);
            String witnessPath = directory + "/smoke-transfer.txt";
            client.push(witness, witnessPath, 10_000);
            LocalAdbClient.ShellResult transferred = client.shell("cat " + witnessPath, 10_000, 4096);
            require(transferred.exitCode == 0 && new String(witness, StandardCharsets.UTF_8)
                    .equals(transferred.stdout), "Transferred bytes differ");
            result.putBoolean("sync_verified", true);
            step = "helper_upload";
            byte[] helper;
            try (InputStream input = getTargetContext().getAssets().open("routing-helper.jar")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (bytes.size() + read > 2_000_000) throw new IllegalStateException("Helper exceeds bound");
                    bytes.write(buffer, 0, read);
                }
                helper = bytes.toByteArray();
            }
            String digest = hex(MessageDigest.getInstance("SHA-256").digest(helper));
            String helperPath = directory + "/routing-helper-smoke.jar";
            client.push(helper, helperPath, 15_000);
            LocalAdbClient.ShellResult remoteDigest = client.shell("sha256sum " + helperPath, 10_000, 4096);
            require(remoteDigest.exitCode == 0 && remoteDigest.stdout.trim().startsWith(digest + " "),
                    "Helper digest differs");
            result.putBoolean("helper_upload_verified", true);
            step = "read_only_helper";
            LocalAdbClient.ShellResult status = client.shell("CLASSPATH=" + helperPath
                    + " app_process /system/bin dev.usbmic.inspector.shell.InputRoutingHelper status 0 0",
                    15_000, 65_536);
            String record = null;
            for (String line : status.stdout.split("\\r?\\n")) {
                if (line.startsWith("USBMIC1:")) {
                    require(record == null, "Duplicate helper record");
                    record = line.substring("USBMIC1:".length());
                }
            }
            require(record != null, "Helper emitted no protocol record");
            JSONObject parsed = new JSONObject(record);
            require(parsed.has("ok") && parsed.has("state") && parsed.has("restorePending"),
                    "Helper record incomplete");
            result.putBoolean("helper_protocol_verified", true);
            result.putBoolean("helper_ok", parsed.getBoolean("ok"));
            result.putString("helper_state", parsed.getString("state"));
            result.putBoolean("routing_mutations_requested", false);
            step = "close";
            client.close();
            client = null;
            result.putString("result", "PASS");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // Pairing codes, protocol payloads and keys must never appear in test output.
            result.putString("result", "FAIL");
            result.putString("failed_step", step);
            result.putString("failure_type", failure.getClass().getName());
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (code != null) java.util.Arrays.fill(code, '\0');
            if (arguments != null) arguments.remove("pairCode");
            if (client != null) try { client.close(); } catch (Exception ignored) { }
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
}
