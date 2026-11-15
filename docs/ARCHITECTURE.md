# Architecture

The Android app owns one flat control screen and a foreground local-ADB session. Routing and connection lifetimes are separate. Device callbacks and periodic checks reconcile USB reconnects; generation guards prevent stale callbacks from closing a newer session.

The embedded ADB client uses an app-private identity, explicit pairing and localhost ports. The app uploads a SHA-256-verified helper and exposes fixed actions only: check, tick, retained tick, stop, defaults and read-only journal status. It has no general terminal.

## Policy boundary

The helper requires shell UID 2000 and MODIFY_AUDIO_ROUTING. Named framework APIs set input preferences for MIC and VOICE_COMMUNICATION. Output policy is untouched. Built-in microphones are identified by role, type and address; port IDs can change across enumeration.

The helper creates an unattached ActivityThread resource context. Calling systemMain would incorrectly classify the shell client as a system process, making UsbDevice reject a remote serial-reader Binder during parcel decoding. The client bootstrap is covered by real framework parcel regressions. It changes no system-thread flags or global thread pointers.

Java preference reads are cross-checked against AudioSystem JNI. A generic error or empty Java list alone never proves that a preference is absent. Ambiguous empty reads require consistent, complete native capture-role maps through a bounded Binder dump. Unknown formats fail closed; full dumps stay in process memory.

## Recovery

Before mutation, an owner-only shell journal records both original preferences and write intent. Writes repeat permission, idle-mode, recording, inventory and expected-value checks. Readback reconciles uncertain writes rather than blindly repeating them. External changes end ownership without overwriting them.

A retained tick requires a matching saved DAC and canonical microphone; it cannot choose a new input. Stop/status can finish early only for securely verified absent or fully stopped ownership. Other recovery paths preserve the journal. Existing package, launcher, identity and journal schema remain compatible across upgrades.

## Evidence

Tests distinguish pure state-machine checks, framework objects under Robolectric, APK inspection and actual phone behavior. The user reported 0.10 working; that does not independently establish every target app or reconnect condition. Current validation belongs in PRs and release notes, with private artifacts excluded from source.

Primary framework contracts: [ActivityThread](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/java/android/app/ActivityThread.java), [UsbDevice](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/java/android/hardware/usb/UsbDevice.java), [IAudioService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/media/java/android/media/IAudioService.aidl), [AudioSystem JNI](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/jni/android_media_AudioSystem.cpp).
