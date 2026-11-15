<div align="center">
  <img src="docs/assets/dongle-route-banner.svg" alt="DongleRoute - detect, inspect and configure USB audio" width="100%" />
</div>

<p align="center">
  <strong>Your headphones on USB. Your voice from your phone.</strong><br />
  Discover what a dongle exposes, choose a built-in microphone, and try a compatible routing setup.
</p>

<p align="center">
  <a href="https://github.com/nyxbydesign/dongle-route/releases"><img src="https://img.shields.io/github/v/release/nyxbydesign/dongle-route?include_prereleases&amp;style=for-the-badge&amp;color=b89ae8&amp;label=Download" alt="Download diagnostic APK" /></a>
  <img src="https://img.shields.io/badge/Android-14%2B-85cfc0?style=for-the-badge" alt="Android 14 or later" />
  <img src="https://img.shields.io/badge/Root-not%20required-b89ae8?style=for-the-badge" alt="Root not required" />
  <img src="https://img.shields.io/badge/Stage-diagnostic-e8c48a?style=for-the-badge" alt="Diagnostic release" />
</p>

<p align="center">
  <a href="#get-started">Get started</a> &nbsp; · &nbsp;
  <a href="docs/AUTOMATION.md">Setup &amp; recovery</a> &nbsp; · &nbsp;
  <a href="#compatibility">Compatibility</a> &nbsp; · &nbsp;
  <a href="docs/VALIDATION.md">Validation</a>
</p>

<br />

## A small app for a surprisingly annoying problem

Some USB-C audio dongles expose a microphone input even when you only want their headphone output. Android may select that input instead of your phone's microphone.

**DongleRoute** brings device inspection and input configuration into one app. It was previously called USB Mic Inspector; existing installations keep the same package and signing identity.

<table>
  <tr>
    <td width="33%" valign="top">
      <h3>01 · Detect</h3>
      <p>See connected USB devices, audio interfaces, endpoints, and Android input/output devices. No dongle brand allowlist.</p>
    </td>
    <td width="33%" valign="top">
      <h3>02 · Choose</h3>
      <p>Select your dongle and an actual built-in phone microphone. Multiple mic layouts are handled through explicit selection.</p>
    </td>
    <td width="33%" valign="top">
      <h3>03 · Configure</h3>
      <p>Pair this app with local Wireless debugging, then enable input preferences and DAC reconnect handling with visible restore controls.</p>
    </td>
  </tr>
</table>

> [!IMPORTANT]
> This is a **diagnostic release**. The manual Android 17 shell preference worked in the user's test. Integrated pairing, reconnect handling and restoration still need phone acceptance. Detection works across device models; a global routing override cannot be guaranteed on every Android phone or in every calling app.

## Get started

**[Download the APK from Releases →](https://github.com/nyxbydesign/dongle-route/releases)**

No Shizuku, separate LADB app, or root is needed. You authorize this app's own local ADB pairing.

1. **Open Automatic microphone routing.** Enable Wireless debugging yourself in Android Developer options.
2. **Pair in split-screen.** In Settings, open *Pair device with pairing code*. Keep that dialog visible alongside DongleRoute. Enter its pairing port and code, then tap **Pair this app**.
3. **Connect using the other port.** Use the connection port on the main Wireless debugging screen. It differs from the temporary pairing port.
4. **Select and enable.** Choose your DAC and built-in microphone, check routing status, then tap **Enable automatic routing on reconnect**. Allow notifications for the visible Stop control.

Verify a short voice test in the app you actually use, and confirm that wired headphone playback stays audible. The app does not start microphone recordings or USB claims automatically.

**[Full setup and a minimal phone test →](docs/AUTOMATION.md)**

## Routing you can undo

| Control | What it does |
| --- | --- |
| **Enable automatic routing on reconnect** | Prefers the selected phone mic for MIC and VOICE_COMMUNICATION while the selected DAC is attached and this service remains connected. Output policy stays unchanged. |
| **Stop & restore saved preferences** | Returns to the preferences present before automation, including any manual phone-mic preference you had already set. |
| **Restore Android microphone defaults** | Explicitly clears matching phone-microphone preferences so Android can choose automatically. Unrelated preferences are preserved. |

Changes wait during calls or recordings. If local ADB disconnects or Android kills the app, preferences can remain active: reconnect and restore. Reopen and connect using the current port after reboot or a Wireless debugging restart.

## Compatibility

| | Current support |
| --- | --- |
| **Install** | Android 14+, USB host support, ARM64 or x86_64 |
| **Inspect** | Connected USB/audio inventory; no Pixel-only or dongle-ID allowlist |
| **Configure** | Runtime shell permission, named API, microphone identity and preference-query checks must pass |
| **Reconnect** | Selected DAC, active foreground service and live local ADB connection |
| **Voice apps & calls** | Actual microphone route and headphone audibility require independent testing |

Ambiguous device layouts and unsupported phone policies stop with a diagnostic. Inspection can still be useful when configuration is unavailable. See [the compatibility notes](docs/AUTOMATION.md#compatibility).

## Local connection, deliberate controls

- The ADB client connects only to `127.0.0.1` and generates its own app-private pairing identity, excluded from backup.
- Pairing codes are transient. Another app's credentials are never read.
- The app does not enable debugging, grant privileged permissions, or change Android security settings.
- No microphone samples are saved. Reports are shared only by your deliberate action; review them for personal hardware details before posting.

The older **USB capture claim** remains a separate manual experiment. It did not establish default microphone fallback in the supplied phone test. Automation does not use it. Release may require unplug/replug to restore the driver; stop repeating it if the default route remains USB.

<details>
<summary><strong>Build from source</strong></summary>

Use JDK 21 and Android SDK platform/build-tools 35. The Gradle 8.11.1 wrapper verifies its distribution checksum.

```powershell
.\gradlew.bat --no-daemon --max-workers=2 assembleDebug assembleDebugAndroidTest lintDebug routingProtocolTests
.\tests\run-routing-engine-tests.ps1
.\localadb\run-tests.ps1
.\tests\run-policy-tests.ps1
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`, signed with a local Android debug key. Building does not install, pair, or change a phone. The routing helper is built from `routing-helper/` and packaged as an APK asset.

See [contributing](CONTRIBUTING.md), [third-party notices and relinking](docs/THIRD-PARTY.md), and [transport patches](localadb/UPSTREAM-PATCHES.md).

</details>

<details>
<summary><strong>Research and verification</strong></summary>

[Research](docs/RESEARCH.md) documents the public API boundary, Android's shell-side capture preferences, and the separate USB experiment. [Validation](docs/VALIDATION.md) records actual builds, offline tests and remaining runtime blockers.

Preference readback is policy evidence. A preferred device being accepted is not proof that Discord or telephony actually used it.

</details>

<br />

<p align="center"><sub>A Nyx by Design project · Less mystery in your audio route.</sub></p>
