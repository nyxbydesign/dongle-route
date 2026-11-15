# Setup and recovery

## Connect this phone

1. Enable Android **Developer options → Wireless debugging**.
2. Put Settings and DongleRoute in split-screen. Open **Pair device with pairing code** and keep that dialog visible while entering its port and code in DongleRoute.
3. After pairing, use the separate connection port from the main Wireless debugging page. Connect and allow the visible session notification.
4. Select your USB dongle and built-in microphone, then tap **Use phone mic**.

Connecting alone leaves routing off. Ports can change after reboot, Wi-Fi changes or restarting Wireless debugging. A saved identity shortens setup; it does not prove Android still authorizes it.

## Daily use

The foreground connection survives a Recents swipe, including while routing is off. **Stop & restore** ends routing and keeps the connection. **Disconnect** restores owned preferences when possible and closes the session. Android's Active apps Stop and Force stop controls are respected. Pairing itself remains an explicit visible operation.

Known USB/audio-list transitions wait without writing. After the selected dongle reconnects, the service restores the baseline before reapplying its saved microphone identity. Calls and recordings defer all changes, including restoration.

## Restore safely

Stop restores the preferences saved before routing began. If you had manually selected a phone microphone earlier, that is your baseline. **Android defaults** is the separate action for clearing matching phone-microphone preferences.

When authorization is lost, pair the same app identity again, connect with the current port and restore. Pending recovery permits re-pairing but blocks forgetting the identity. Keep app data until restoration is resolved.

If no app-owned route exists, Stop can verify that from the private journal without loading the audio adapter. Unreadable, interrupted or owned state remains protected.

## Troubleshoot

Open **Details → Routing details → Check compatibility** while connected and routing is off. This checks the journal, USB/audio inventory and input-preference APIs without applying a route. It does not test write durability or actual capture in another app.

Details retains the latest helper failure after Stop, with a fixed operation stage and safe error category. Copying is deliberate; review hardware information before sharing. Do not change security settings to bypass an unsupported API or permission failure.

Supported configuration requires one unambiguous USB audio device, a unique built-in microphone, shell routing permission and consistent preference queries. Unknown policies or inventory stop with a diagnostic. Actual voice capture and wired sound depend on the phone and target app.
