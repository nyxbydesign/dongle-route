# Minimal phone acceptance

DongleRoute's layout, build and policy tests do not establish actual microphone routing on your phone. Start while idle, before using a real conversation.

1. Install the latest APK as an update. Open the connection sheet and pair this app with Android Wireless debugging if necessary. Keep the pairing dialog visible in split-screen. Connect with the separate connection port from the main Wireless debugging screen.
2. Plug in the DAC/headphones. Confirm its name appears on the main screen. The app selects clear choices automatically; tap a device row only if you need another dongle or built-in microphone.
3. First leave routing off. Swipe the app from Recents and verify its connection notification remains. Reopen it and check that the session is still connected; connecting alone must not start microphone routing. Then start routing. Distinguish **checking**, **waiting**, **preference applied**, and **blocked**. A running service is not itself confirmation of an applied preference.
4. Deliberately run a brief microphone test in your intended voice app. Confirm speech near the phone is heard and headphone output stays audible. DongleRoute does not record audio or play test tones. Test telephony separately in a deliberately arranged, nonessential call.
5. End the recording/call. Disconnect the dongle and allow the USB/audio inventory to settle and restoration to finish. Reconnect it and check reapplication, including when Android assigns new port IDs. Repeat the target-app mic/headphone check.
6. End recordings/calls, then Stop and restore. Check its reported outcome. Your saved baseline can include the manual phone-mic preferences you had before starting.
7. Use the separate Android-defaults action only if you want Android to choose inputs automatically again. It can clear earlier manual matching phone-mic preferences.

If the app reports a permission/API/inventory failure, do not change Android security settings to bypass it. Record the concise compatibility result and Android version, excluding pairing codes and credentials.

For a routing-start failure, use Routing details → Check compatibility while connected and routing is off. This reads capabilities without applying preferences. Copy the resulting details, which include the installed app version and retain the latest helper failure even after Stop. Avoid repeatedly enabling routing to obtain the same error. A successful read-only check does not prove that policy writes or journal persistence will succeed.

When upgrading from a helper-loading failure with the recovery screen locked, connect and tap Stop & restore once. A securely verified absent or stopped journal should report that no app-owned route needs restoration and unlock device selection. If saved ownership still exists, recovery must remain pending until it can be verified. Do not clear app data or delete its shell journal to bypass that state. Share the new capability-stage error privately if it remains blocked.

If ADB or the process stops, preferences may remain. Reconnect and restore before forgetting pairing or uninstalling. If Android's paired-device entry was removed, re-pair the same app identity; pending restoration must not prevent that repair. An existing tunnel may not close immediately on every Android build. Check the app's observable connection status and use Routing details to copy its bounded session history if needed. Ports can change after a reboot, Wi-Fi change or Wireless debugging restart.

Stop & restore should leave the local connection available. Explicit Disconnect should restore owned preferences before closing when possible. Android's Active apps Stop and Settings Force stop must be respected; these differ from a Recents swipe.

The USB-detach experiment and audio probes from the old diagnostic app were removed in version 0.5. Do not repeat that experiment to resolve a configuration failure. Historical behavior remains documented in RESEARCH.md and older release records.
