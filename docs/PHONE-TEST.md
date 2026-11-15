# Minimal phone acceptance

DongleRoute's layout, build and policy tests do not establish actual microphone routing on your phone. Start while idle, before using a real conversation.

1. Install the latest APK as an update. Open connection setup and pair this app with Android Wireless debugging. Keep the pairing dialog visible in split-screen. Connect with the separate connection port from the main Wireless debugging screen.
2. Plug in the DAC/headphones. Confirm it appears in Devices. Choose that dongle and the intended built-in phone microphone on the route dashboard.
3. Start routing and allow its notification. Distinguish **checking**, **waiting**, **preference applied**, and **blocked**. A running service is not itself confirmation of an applied preference.
4. Deliberately run a brief microphone test in your intended voice app. Confirm speech near the phone is heard and headphone output stays audible. DongleRoute does not record audio or play test tones. Test telephony separately in a deliberately arranged, nonessential call.
5. End the recording/call. Disconnect the dongle and allow restoration, then reconnect it and check reapplication. Repeat the target-app mic/headphone check.
6. End recordings/calls, then Stop and restore. Check its reported outcome. Your saved baseline can include the manual phone-mic preferences you had before starting.
7. Use the separate Android-defaults action only if you want Android to choose inputs automatically again. It can clear earlier manual matching phone-mic preferences.

If the app reports a permission/API/inventory failure, do not change Android security settings to bypass it. Record the concise compatibility result and Android version, excluding pairing codes and credentials.

If ADB or the process stops, preferences may remain. Reconnect and restore before forgetting pairing or uninstalling. Ports can change after a reboot, Wi-Fi change or Wireless debugging restart.

The USB-detach experiment and audio probes from the old diagnostic app were removed in version 0.5. Do not repeat that experiment to resolve a configuration failure. Historical behavior remains documented in RESEARCH.md and older release records.
