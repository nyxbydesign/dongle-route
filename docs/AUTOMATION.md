# Local ADB microphone automation

Version 0.4 incorporates a Wireless ADB client into DongleRoute (formerly USB Mic Inspector). The user manually authorizes this app's own pairing identity. No extra companion app is needed. Wireless debugging normally requires Wi-Fi; follow the requirements shown by Android Settings. This feature does not turn debugging on for you.

## First setup

1. End calls and recordings, release any old USB capture experiment, and connect the DAC.
2. Open Automatic microphone routing. Open Developer options, then Wireless debugging.
3. Put Settings and the app in split-screen. Open Pair device with pairing code and keep its dialog visible. Copy the pairing port and code into this app and tap Pair this app. The code is cleared after use.
4. Return to the main Wireless debugging screen. Its connection port is different from the temporary pairing port. Enter the connection port and tap Connect.
5. Refresh connected DACs and built-in microphones, select yours, and Check routing status. Choose the phone's intended built-in mic; devices may expose more than one. Enable automatic routing on reconnect and approve its notification request. Tap Enable again if the permission request interrupted the action.

The app accepts explicit localhost ports only. It does not discover remote hosts, reuse LADB keys, expose an arbitrary terminal, or import pairing secrets. A successful pairing does not itself establish an active connection.

## Behavior and recovery

The foreground service checks every five seconds, also responding to device callbacks. For exactly one selected physical USB device with matching input/output inventory, it prefers the selected built-in microphone for MIC and VOICE_COMMUNICATION. The helper revalidates that microphone against fresh phone inventory; it does not assume that every phone calls its microphone "bottom". Output preferences remain unchanged. Duplicate matching devices, unknown inventory, unsupported APIs and uncertain preference reads block changes.

Calls, non-normal audio mode and active recordings defer writes, including restoration. End them and wait for the pending restoration notification to finish. This deliberately avoids changing routing during a conversation. A device can change state between checks; Android provides no atomic compare-and-set across these preference operations.

Before each write, a shell-owned journal saves the original preference and intended change. Readback verifies the result. Disconnecting the selected DAC or choosing Stop restores only preferences still owned by this session. External changes cause a conflict instead of being overwritten. A failed connection is not proof that restoration happened.

If you previously ran the manual LADB commands selecting the bottom microphone, that is the saved baseline. **Stop & restore saved preferences keeps that baseline.** To return to Android's automatic input selection instead, explicitly choose **Restore Android microphone defaults**. That action only clears the matching built-in preferences or accepts already-absent ones; unrelated preferences are refused.

The private ADB identity survives app restarts; the active connection and service do not promise reboot recovery. Reopen and connect using the current port after a reboot, Wi-Fi change or Wireless debugging restart. If the process is killed or ADB is lost, microphone preferences may remain. Reconnect, Check routing status, then Stop & restore. The app blocks forgetting its identity when restoration may still be needed. To revoke authorization, also remove this app's paired-device entry in Android Settings after restoration.

Check routing status can describe an existing saved session. Stop and restore it before selecting another microphone for a new session. Recovery follows the saved microphone identity even if Android assigns it a different port ID.

## Minimal phone acceptance

The developer could not execute the integrated client against a phone or emulator. Please perform these explicit checks on an idle phone:

1. Pair and connect. Check routing status; report its error text if blocked, excluding codes and private keys. Do not change Android security settings to bypass a failure.
2. Enable with the DAC selected. Confirm the app reports an applied or already-correct preference. Confirm a short voice test in your intended app hears the phone microphone while headphone playback remains audible.
3. End the test recording/call. Unplug the DAC, wait for restoration, reconnect it and verify reapplication. Repeat the actual target-app voice/playback check.
4. End recordings/calls and choose Stop & restore. Confirm the reported restoration. Test the explicit defaults action separately only if you want to clear your earlier manual preferences.

Preference readback is policy evidence, not proof of the microphone used by Discord or telephony. Different recording sources and target-app communication routing can behave differently. Do not start a USB claim as a workaround for an automation error.

## Compatibility

| Capability | Scope |
| --- | --- |
| USB/audio inventory | Any exposed USB audio interfaces and Android devices; no vendor/product allowlist |
| App installation | Android 14+, USB host, ARM64 or x86_64 |
| Local pairing | Wireless debugging enabled and pairing manually approved by the user |
| Input automation | Runtime permission, named API, inventory and preference-query checks must pass; manual shell behavior has been reported on Android 17 |
| Multiple phone microphones | Explicit built-in microphone selection; target identity must remain unique and stable |
| Ambiguous USB topology, hubs or multiple devices | Conservative routing checks may block configuration even when inspection works |
| Calls and third-party apps | Independent actual routing/audibility acceptance required; no universal guarantee |

The detector and configuration flow are designed for different phone and dongle models. This release has not been tested across those models. An unsupported API or policy restriction is reported instead of bypassed.

## Technical boundary

Only fixed helper actions (`status`, `tick`, `stop`, `defaults`) are sent through the app's shell connection. The embedded helper is SHA-256 checked before execution. It requires UID 2000 and MODIFY_AUDIO_ROUTING, uses named generated Binder interfaces and refuses unsupported shapes. It does not guess transaction numbers or grant the ordinary app signature-level routing authority.

Preference queries distinguish native NAME_NOT_FOUND from other failures, cross-check Java/native views, and compare exact role/type/connection/address identities. State files under the app-specific shell temporary directory are owner-only and checked before use. These defenses do not replace actual Android-version compatibility testing.
