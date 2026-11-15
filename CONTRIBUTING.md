# Contributing

This standalone Android project is a diagnostic prototype. Open changes as draft pull requests against `main` until physical-device acceptance is available.

Run `./gradlew --no-daemon --max-workers=2 assembleDebug assembleDebugAndroidTest lintDebug routingProtocolTests` (`.\gradlew.bat` on Windows) with JDK 21 and Android SDK/build-tools 35. Run `tests/run-routing-engine-tests.ps1`, `localadb/run-tests.ps1` after library compilation, and `tests/run-policy-tests.ps1` when descriptor policy changes. The minimum OS is Android 14/API 34; shipped native ABIs are ARM64 and x86_64. Offline tests do not establish real routing. Inspect final APK permissions, certificate continuity, embedded helper/notices, ELF and uncompressed native ZIP 16 KB alignment.

Keep USB interface claims fail-closed and explicitly initiated. Never claim a control, output, mixed or ambiguous interface. Do not start recording, playback or a USB claim automatically. Preserve explicit release controls and recovery warnings. Do not relax safety gates to enable an unverified DAC.

Document primary sources and separate upstream behavior, synthetic results, app-local observations and target-app acceptance. Preserve explicit local pairing, fixed helper actions, permission/API/inventory checks, write-ahead ownership state and verified restoration. Never broaden to remote hosts, guessed Binder transactions, debugging toggles, permission grants or hidden-API exemption settings. Pairing codes and credentials must not enter logs or reports. Keep phone acceptance separate from compiled instrumentation and fake-loopback transport tests.

Do not commit credentials, keystores, machine paths, device serials, phone dumps, personal reports or generated build output. Review/redact diagnostics before sharing. Keep changes limited to this project.
