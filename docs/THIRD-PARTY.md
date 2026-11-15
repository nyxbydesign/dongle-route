# Third-party components and rebuilding

The APK includes third-party license texts in `assets/third-party/`; the source distribution also retains `localadb/LICENSES/` and file-level notices.

| Component | Version/source | License |
| --- | --- | --- |
| LibADB Android, selected source subset | 3.1.1, commit c849886ebc6d48e7b46d967e78a6bb65c90c3b74 | Apache-2.0 alternative, plus retained file-level BSD/MIT notices |
| Conscrypt Android | 2.9.0 | Apache-2.0 and bundled notices |
| SPAKE2 Android | 2.2.1, commit 7615ddd680b990e14513ebb66eac4cb0dbf82464 | LGPL-3.0 |
| Bouncy Castle bcprov-jdk15to18 | 1.87 | MIT-style |
| AndroidX annotations | 1.9.1 | Apache-2.0 |

The modified LibADB source is in `localadb/src/`. Its source selection, patch rationale, native dependency hashes and source links are recorded in [UPSTREAM-PATCHES.md](../localadb/UPSTREAM-PATCHES.md). No LADB application source is included.

SPAKE2 corresponding source is available at [the pinned upstream revision](https://github.com/MuntashirAkon/spake2-java/tree/7615ddd680b990e14513ebb66eac4cb0dbf82464). To modify the linked library, obtain that source and follow its Android/native build configuration to produce an AAR. In `localadb/build.gradle`, replace the `spake2-android` dependency with `implementation files('libs/spake2-modified.aar')` and place your rebuilt AAR at `localadb/libs/spake2-modified.aar`. Keep its notices and native ABI support. Build this project with the README command to relink and package a modified APK. Use your own signing key; an APK signed with a different key requires uninstalling the existing installation before installation. Restore routing preferences before uninstalling.

No project term restricts modifying the linked library or reverse engineering to debug those modifications. Retain the supplied LGPL/GPL texts and applicable notices when redistributing. The full app source and Gradle build configuration are provided so recipients can rebuild and relink it. Release APKs currently use a development debug certificate, not a production signing identity.
