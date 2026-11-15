# Contributing

This standalone Android project provides a focused routing app with diagnostic release status. Use `main` as the integration branch. Keep physical routing acceptance separate from build, layout and offline test results.

Run `./gradlew --no-daemon --max-workers=2 assembleDebug assembleDebugAndroidTest lintDebug` (`.\gradlew.bat` on Windows) with JDK 21 and Android SDK/build-tools 35. Run `routingProtocolTests`, `tests/run-routing-engine-tests.ps1`, and `localadb/run-tests.ps1` when their contracts change. For UI changes, render the actual Android views with the focused JVM screenshot harness; inspect normal and larger-font layouts. The minimum OS is Android 14/API 34; shipped native ABIs are ARM64 and x86_64. Offline tests do not establish real routing. Inspect final APK permissions, certificate continuity, embedded helper/notices, ELF and uncompressed native ZIP 16 KB alignment.

The app must not record audio, play test tones, or claim USB interfaces. The obsolete detach experiment is removed. Preserve explicit pairing, routing and recovery controls. A running service or accepted preference is not proof of actual target-app microphone routing; status presentation must retain that distinction. Do not relax inventory checks to enable an unverified DAC.

Document primary sources and separate upstream behavior, synthetic results, app-local observations and target-app acceptance. Preserve explicit local pairing, fixed helper actions, permission/API/inventory checks, write-ahead ownership state and verified restoration. Never broaden to remote hosts, guessed Binder transactions, debugging toggles, permission grants or hidden-API exemption settings. Pairing codes and credentials must not enter logs or reports. Keep phone acceptance separate from compiled instrumentation and fake-loopback transport tests.

Do not commit credentials, keystores, machine paths, device serials, phone dumps, personal reports or generated build output. Review/redact diagnostics before sharing. Keep changes limited to this project.
