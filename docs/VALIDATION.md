# Validation record

This record distinguishes build evidence, offline descriptor checks and physical-device acceptance. The exact source commits are supplied in the repository's pull requests and release metadata.

## Version 0.9 validation (2026-11-15)

This is a diagnostic update for a reported routing-start failure. It fixes a stale report-version label, preserves the latest helper failure after Stop, and identifies failing operations through fixed stages and safe error categories. Check compatibility now exercises the backend even without active ownership and makes no journal or policy writes. The exact phone-side failure is still unverified; this release does not claim to resolve it.

- Passed 50 routing scenarios / 437 assertions, 30 preference-proof checks and four safe-diagnostic checks. Read-only checks cover absent, active, interrupted and conflict ownership without saves or writes. Failure tests retain an apply/write cause when a later restoration/query operation also fails and exclude raw error text from diagnostics.
- Passed 30 unique app cases: thirteen service lifecycle cases, eight Android-view cases, one worker-order case, two framework/bridge cases, three shadow-pipe/diagnostic cases, and three adapter/journal cases. The latter exercise the actual inventory method using framework objects under Robolectric, the journal JSON codec and file-metadata predicates. One microphone fixture initially used an output-oriented type conversion; it was repaired to assert native input type, role, ID and address, then only that failed method was rerun. These checks do not establish physical-device inventory, kernel durability or SELinux behavior.
- App and instrumentation assembly passed. Lint passed with zero errors and the four existing reviewed warnings; protocol checks passed 42 assertions. Unchanged transport checks are reused. The initial automatic build-approval review timed out before process launch; its permitted single retry ran successfully.
- Verified version code 9 / 0.9-diagnostic, signer continuity, the same four permissions, native ELF/ZIP 16 KB alignment, nine notices, the new diagnostic helper, absence of obsolete native-policy dependencies and removal of the hardcoded 0.7 report label. The embedded helper exactly matches the generated helper. The APK stayed unchanged through the test-fixture repair.

APK: 15,244,916 bytes. SHA-256:

```text
6a4f9b83dd2be2244e87985d948cd8d47343ec024fdb79e207328e7d10fd73a4
```

No connected or advertised ADB device was found. An explicitly requested wireless connection then failed before authentication with Windows socket-access error 10013. No phone installation, policy mutation, audio test or USB claim occurred. Phone-side diagnosis and actual routing remain blocked. Original failures, passing results and artifact receipts are retained in ignored evidence/0.9/.

## Version 0.8 validation (2026-11-15)

A phone report showed local ADB connected while the helper failed with ClassNotFoundException and Stop repeatedly left recovery pending. This update removes the generated native-policy Java dependencies, verifies absent or fully stopped ownership before loading the audio adapter, and fixes the bridge rejecting retained-microphone monitoring commands. Saved or uncertain ownership is preserved. The recovery screen now shows the specific helper failure.

- Passed 48 helper scenarios / 399 assertions and 30 synthetic native capture-map/preference-proof checks. Coverage includes retained monitoring without a new port ID, matching-journal requirements, backend-free Stop/status with no ownership, and refusal of active or unreadable state. Generic JNI errors alone never establish an absent preference.
- Passed SDK 35 framework-signature and fixed-command tests, thirteen service lifecycle cases, one real-session worker-order case, seven Android-view cases, and three Binder-dump/loader cases. The pipe tests use Robolectric and a fake Binder; they do not establish real kernel-pipe behavior or native audio-service compatibility. An early simulated EOF exposed an observer mismatch; the repaired case checks the complete production read-plus-parser boundary and rejects incomplete data. Only that failed method was rerun.
- Application and instrumentation APK assembly passed. Initial unit-test compilation required a test-only helper JAR dependency; lint and the previously unexecuted tests then ran successfully. Lint retains zero errors and four previously reviewed warnings. Protocol checks passed 42 assertions; unchanged transport checks are reused from earlier releases.
- Verified version code 8 / 0.8-diagnostic, existing package and development certificate, exactly four permissions, ARM64/x86_64 native ELF and ZIP 16 KB alignment, nine notices and removal of obsolete generated native-policy dependencies. The embedded helper executable matches the generated helper's complete entry inventory; their JAR container timestamps differ after the test-plumbing repair. The qualified APK was retained unchanged. The narrow/large-font recovery screen was visually inspected.

APK: 15,241,424 bytes. SHA-256:

```text
58e54d546ed3491bcd4ed29201e73bf3fe63c1809c458cbb5a28f84bd6ac2fee
```

Original environment, compilation and observer failures, passing results and artifact receipts are retained in ignored evidence/0.8/. No phone discovery, installation, pairing, audio-policy write, recording, playback, USB claim or emulator retry was performed. Android 17 source verification and SDK 35 framework tests do not replace phone acceptance: upgrade, connect, Stop & restore, then deliberately verify routing, dongle reconnect and actual voice/headphone behavior on an idle phone.

## Version 0.7 validation (2026-11-15)

This update addresses user-reported dongle reconnect failure, loss of the idle ADB session after a Recents swipe, and inability to re-pair after Android removes authorization. Connect now has its own foreground owner independently of routing. Stop restores routing while retaining the connection; explicit Disconnect restores before closing when possible. Observable transport loss parks without an authorization retry loop, and same-key re-pairing remains available with pending restoration. Known USB/audio-list settling waits without writes; a new enumeration generation restores the baseline before reapplying.

- Reproduced the prior detach-inventory failure and passed 46 helper scenarios / 366 assertions covering settling, stale IDs, generation changes, deferred restoration, external conflicts and strict query/permission failures. The final Android adapter and new USB inventory classifier compiled and dexed successfully.
- Passed 42 protocol assertions, including the new waiting state. The unchanged 48 wire/loopback transport assertions are reused; no vendor transport patch was needed.
- Passed six native UI cases, thirteen controlled service lifecycle cases and one real-session worker-order regression. Coverage includes foreground-before-transport, idle Recents/heartbeat, Stop retaining the session, lost authorization with recovery debt, wait/reapply, restore-first Disconnect, one-shot system recovery, storage failures, forgetting only after owner shutdown, and stale callbacks/results not invalidating a newer owner. These use controlled session boundaries and do not connect to a phone.
- Initial build/lint/tests passed. Subsequent concrete persistence and owner/outcome race fixes were qualified with affected assembly/lint and lifecycle/worker tests; unchanged UI/protocol results were retained. Final build/lint passed with zero errors and the four previously reviewed warnings. Seven actual Android-view renders were retained.
- Verified package dev.usbmic.inspector, version code 7 / 0.7-diagnostic, min API 34, target/compile API 35, ARM64/x86_64, size 15,326,302 bytes; existing debug certificate and exactly four permissions retained. Native ELF/ZIP 16 KB alignment, new helper asset/classifier, nine notices and absence of legacy diagnostic classes were checked. The instrumentation APK is unchanged, built alongside the target and not installed or executed.

Final APK SHA-256:

```text
c78435bced0059316c4370f880fc200adcbd30752b858af42baac6510b8de2ed
```

The workstation temporarily disconnected after qualification. After recovery, the existing APK hash and retained final test XML were reconciled without rebuilding or rerunning passing tests. Source/artifact receipts and original regressions remain in ignored evidence/0.7/.

Physical-phone Recents behavior, actual ADB authorization revocation, USB replug, target-app capture and wired output still require acceptance. No phone discovery, connection, pairing, policy write, recording, playback, USB claim or emulator retry was performed here. Pairing itself remains an explicit visible split-screen operation; the Connect operation and connected-idle session have foreground ownership.

## Version 0.6 validation (2026-11-15)

The app now has one flat control screen, inline USB/microphone choices, a compact Pair/Connect sheet and an overflow menu. Device and Settings pages, nested cards and the redundant Start confirmation are removed. Unique supported device choices are selected automatically. A saved app-owned identity only selects the shorter connection form; it does not certify pairing. The last successful connection port is remembered, and connecting alone never starts routing.

- App and instrumentation APK assembly and lint tasks passed. Lint reports zero errors and the same four reviewed warnings. The first combined invocation ended on one UI test's asynchronous-dialog observer; its other five UI tests passed. After preserving that result, only the failed observer was repaired to wait for the main looper and rerun. All six flow cases are qualified against unchanged production code.
- Rendered and inspected seven actual Android-View PNGs with Robolectric SDK 35/native graphics. Coverage includes the single screen, synthetic selection without connection/routing claims, compact pairing/connection, returning-user setup, narrow/large text and recovery locks. These are local UI renders, not phone screenshots.
- Final APK: package dev.usbmic.inspector, version code 6 / 0.6-diagnostic, minimum API 34, target/compile API 35, ARM64/x86_64; 15,218,940 bytes. Existing debug signer retained. The same four intended permissions remain.
- Verified native ELF/ZIP 16 KB alignment, nine notice assets and absence of legacy diagnostic classes. The helper JAR container timestamp changed, while its nested executable classes.dex matches 0.5 exactly; previous helper/protocol/transport checks are reused. The unchanged instrumentation APK retains its prior qualification and was not installed or run.

Final APK SHA-256:

```text
fcbdf304be8eac65500736e1071b24e0c1cd471c8ff7286dbcc1ddfa2e529677
```

No real connection, pairing, audio-policy mutation, recording, playback, USB claim or new emulator attempt was performed. Physical-phone pairing, target-app capture, wired sound and reconnect/restoration remain acceptance items. Source/artifact binding and preserved test records are in ignored evidence/0.6/.

## Version 0.5 validation (2026-11-15)

Version 0.5 replaces the diagnostic activity with a focused native route dashboard, Devices, Settings and a separate local-connection setup. It removes the recorder/playback probes, USB capture-claim service, descriptor claim policy and experimental history UI. The app no longer requests RECORD_AUDIO or connected-device foreground-service permission. The input-policy helper and local ADB protocol remain unchanged.

- Passed app and instrumentation APK assembly and lint. An initial passing build reported three additional cleanup warnings (two obsolete strings and a programmatic icon constructor); those were corrected, and the affected assembly/lint passed with zero errors and the four previously reviewed ADB-TLS/permission-removal warnings.
- Passed four Robolectric 4.17 tests using SDK 35 and native graphics. They render the actual application Views: unpaired home, explicit three-step connection, sample inventory without a connection, and a narrow 320 dp screen with 1.5 font scale and reachable scrolling controls. Selecting a sample device does not imply an applied route.
- Added and passed one focused recovery-control test against unchanged production code: stored restoration debt locks route selection and prevents forgetting the identity or restoring/defaulting while disconnected. The original four passing results were retained and reused. Eight local PNGs were inspected; the public README uses clearly labelled sample-inventory previews.
- The final APK has exactly INTERNET, POST_NOTIFICATIONS, FOREGROUND_SERVICE and FOREGROUND_SERVICE_SPECIAL_USE. Package dev.usbmic.inspector, version code 5 / 0.5-diagnostic, minimum API 34, target/compile API 35, ARM64 and x86_64. Size: 15,235,712 bytes. Existing Android Debug signer and v2 signature verified unchanged.
- Verified all four shipped native libraries' ELF LOAD and uncompressed ZIP 16 KB alignment, embedded helper/notices, and removal of legacy diagnostic classes. The instrumentation APK's exact bytes are unchanged; prior runner/metadata/signature qualification is reused. It was not installed or executed.
- Reused the exact unchanged helper's 242 routing/selection assertions, 39 protocol assertions and 48 transport assertions from 0.4. No USB capture-policy tests apply after removal of that feature.

Final APK SHA-256:

```text
a9bad7f3f15cb4baeff1c64c79a9921ee94980ca0a00c30d705ab258323d9477
```

The JVM graphics tests use Android framework shadows and synthetic inventory. They are layout/control evidence, not physical-device pairing or routing acceptance. No phone connection, shell-policy mutation, recording, playback, USB claim or new emulator attempt was performed for 0.5. First-use pairing, continued wired sound, target-app capture and reconnect/restoration still require [phone acceptance](PHONE-TEST.md). Logs, first results and the final source/artifact receipt are retained privately in ignored evidence/0.5/.

## Version 0.4 validation (2026-11-15)

- Passed app and instrumentation APK assembly and 39 protocol parser assertions against real org.json. The routing helper is compiled and dexed from source into the app asset.
- Passed 36 offline routing/selection scenarios, 242 assertions: idle guards, saved manual baseline, reconnect, partial writes, crash intent, external conflicts, unknown queries, restoration failure handling, generic/empty microphone addresses, explicit mic selection, ambiguity and changed enumeration IDs. This supersedes the initial 27-scenario/167-assertion result before generic microphone support.
- Passed 34 shell/sync wire assertions and 14 fake-loopback connection timeout/cancellation assertions. No real pairing occurred.
- Final affected assembly/lint passed after removing three unwanted permissions inherited from a dependency manifest. The actual final APK has exactly the six intended permissions and no storage or phone-state permission.
- Lint: zero errors, four reviewed warnings. Two TrustAllX509TrustManager warnings are the vendored SslUtils anonymous manager's client/server checks. They implement the private ADB protocol context described in RESEARCH.md, with localhost-only scope and no PKI/server pinning claim. Two ScopedStorage warnings point to manifest removal declarations; the permissions are absent from the APK. No warning baseline or new suppression was added.
- Verified final DongleRoute metadata: package dev.usbmic.inspector, version code 4 / 0.4-diagnostic, min API 34, target/compile API 35; ARM64 and x86_64. APK size 15,346,862 bytes. Existing debug certificate and v2 signature verified unchanged. The affected assembly/lint was repeated after generic microphone selection and branding; unchanged protocol and transport assertions were reused.
- All four shipped native libraries passed 16 KB ELF LOAD and uncompressed ZIP alignment checks. Embedded helper and third-party license assets were checked. Instrumentation APK compiled and was inspected; it was not run.
- The task-owned emulator could not start because it required more free disk space than available. Both attempts exited before installation or pairing; task-created disposable data was removed while failure logs were retained. No existing phone or emulator was used, and no audio-policy mutation, audio probe or USB claim was run by the developer.

Final APK SHA-256:

```text
e2571c4566347914e523fd5708821ae16226ec12f2b9d9ba82148d3b4fb9ffdc
```

Build environment: JDK 21.0.11, Gradle 8.11.1, AGP 8.7.3, SDK/build-tools 35. Initial failures and source/artifact receipts are retained privately in ignored evidence/0.4/ and evidence/0.4-generic/. Unchanged descriptor policy reuses the prior 285-assertion evidence. The user's manual Android 17 shell preference test is separate from these build results. Embedded pairing, framework compatibility, actual target-app microphone/output behavior and reconnect/restoration require the explicit [phone acceptance steps](AUTOMATION.md).

## Version 0.3 validation (2026-11-14)

This update changes report evidence only. A synchronized, bounded process-memory journal retains the most recent 96 events, each at most 1,024 characters, with UTC timestamps and a count of discarded older entries. It records requests, preflight failures, claim results, held state, release/timeout/detach cleanup and probe-time state snapshots. Copied/shared reports include this history after Release; process restart clears it. No new persistence, permissions, shell mutation or audio-device behavior was added.

- Passed the affected Android `assembleDebug` and `lintDebug`; final lint reports `No issues found.`
- Verified APK metadata: version code 3 / `0.3-diagnostic`, unchanged package, min API 34 and target/compile API 35. APK size: 76,049 bytes.
- Verified the v2 signature and exact certificate continuity with versions 0.1 and 0.2.
- Reviewed the instrumentation and bounds. The descriptor policy and native claim/release decision paths are unchanged; the existing 285-assertion version 0.2 policy evidence is reused without rerunning that workload.
- Published and downloaded the existing read-only shell-helper JAR with the same previously validated SHA-256 listed below; no helper source change or rebuild was required.

Version 0.3 APK SHA-256:

```text
07e508542fed6909774d34425fde0535eee657baa5de0860adcb9e8606bc4ae5
```

No version 0.3 device test, audio probe or USB claim was performed by the developer. Reporting behavior has build and source-review evidence, not phone acceptance. Receipts are retained locally in ignored `evidence/0.3/`.

## Version 0.2 validation (2026-11-14)

- Reproduced the original HID rejection using the supplied descriptor bytes against the committed version 0.1 parser. The input length and SHA-256 matched the supplied report.
- Passed 285 descriptor-policy assertions, including the anonymized composite regression selecting capture interface 1, HID-first ordering, existing audio safety tests, and malformed, shared, duplicate and truncated HID cases. AudioControl, playback and HID interfaces are never selected for the claim.
- Passed `assembleDebug` and `lintDebug`; final lint reports `No issues found.` The existing documented connected-device USB prerequisite detector suppression remains unchanged.
- Verified the development APK's v2 signature and exact certificate match with version 0.1 for an in-place upgrade. Metadata: package `dev.usbmic.inspector`, version code 2, version `0.2-diagnostic`, min API 34, target/compile API 35. Artifact size: 68,861 bytes.
- Reviewed the narrow parser changes: optional HID descriptors are validated independently; interface-role and endpoint-address conflicts fail closed; all prior capture, playback and audio-control requirements remain in place. The service and interface-claim operation are unchanged.

Version 0.2 APK SHA-256:

```text
3664a14711c3b16c5fbc8005ad0c4a271c6490054b526bc0460554413b36da77
```

Build environment is unchanged from version 0.1 below. No device discovery, installation, runtime audio probe, USB claim or call was performed for this update. Offline descriptor acceptance does not establish working playback, microphone fallback or restoration. Version 0.2 build, metadata and signature receipts are retained locally under ignored `evidence/0.2/`.

## Version 0.1 historical validation (2026-11-11)

| Check | Result | What it establishes |
| --- | --- | --- |
| Pure Java descriptor-policy self-test | 218 synthetic assertions passed | Supported fixture allowed; malformed/truncated, mixed/shared, feedback and unsupported layouts refused |
| Android `assembleDebug` and `lintDebug` | Passed; final lint: `No issues found.` | Source compiles/packages against SDK 35 and passes the selected lint checks |
| APK metadata/signature inspection | Passed | Package `dev.usbmic.inspector`, version 1 / `0.1-diagnostic`, min API 34, target API 35; local debug-signed development APK |
| Read-only shell helper javac + D8 | Passed | Helper compiles to a dex JAR; not proof of hidden-API availability or shell privileges on a phone |
| Focused safety source review | No material findings | Explicit initiation/idle confirmation, fresh descriptor gate and independent framework checks precede the sole capture-only claim; no automatic permission-triggered claim |
| `adb devices -l` | No devices attached | No existing connected phone available for testing |
| `adb mdns services` | No services discovered | No already advertised wireless ADB target found |

Build environment: Windows, JDK 21.0.11, Gradle 8.11.1, Android Gradle Plugin 8.7.3, SDK platform/build-tools 35. The wrapper pins the Gradle distribution's official SHA-256.

An initial sandbox build could not access dependencies; an approved standard build path resolved that environment limitation. Lint then exposed API/declaration issues, which were corrected before the passing final build. One narrowly documented `ForegroundServicePermission` detector suppression remains on the connected-device service: this version of lint does not recognize the documented `UsbManager.requestPermission()` alternative. The activity requests USB permission and the service checks it before foreground promotion. No unrelated network/Bluetooth permission or lint baseline was added. [Android foreground-service USB prerequisite](https://developer.android.com/about/versions/14/changes/fgs-types-required#connected-device).

Version 0.1 artifact SHA-256 (the app output path is reused by subsequent builds):

```text
app/build/outputs/apk/debug/app-debug.apk
298bf5e3af10bafae375e495871525fd1f05a88148de18e72007ba174aa3c902

shell-helper/build/usb-audio-shell-probe.jar
075fdd9daf1b2f5e57f79fcf8219437843fa3e5e519511b47fc82b00273b388a
```

These generated outputs are excluded from source control. Original build/lint failures and final APK-inspection receipts are retained locally in ignored `evidence/`; phone dumps and personal reports are not part of the public source.

## Physical routing acceptance still pending

No phone or emulator was available to the developer during initial validation. A subsequent user-supplied version 0.1 report on 2026-11-14 documents app startup, Android audio-device inventory and USB descriptor inspection on a phone. The exact descriptor bytes were reconciled with the report's SHA-256; the original parser's HID rejection was reproduced offline. The public regression fixture retains this topology with anonymized vendor/product and string-index fields. Private phone build details and diagnostic reports are excluded from publication.

The initial report contained descriptor inspection only. A later user-supplied version 0.2 report records an active default recorder routed to USB, an explicitly preferred built-in recorder routed to the phone microphone, and USB playback routing before an experiment. The user confirmed seeing a successful interface claim before subsequent microphone probes; the later default recorder still selected USB. This does not establish the desired fallback. Version 0.2 retained only the final service status, so the exported report could not independently reconstruct claim/release timing. Version 0.3 adds bounded in-memory lifecycle history and probe-context snapshots to address that reporting gap without changing USB behavior.

Post-claim USB playback/audibility and driver restoration remain unverified. Subsequent shell permission/help output and a user-reported successful manual input preference test motivated version 0.4. Its automated behavior and Discord/telephone-call routing still require independent acceptance; repeated USB claims are not the next step.

Use the current [phone acceptance](PHONE-TEST.md) and [automation recovery guide](AUTOMATION.md). The legacy USB claim is removed in 0.5; earlier release evidence is retained here as history.
