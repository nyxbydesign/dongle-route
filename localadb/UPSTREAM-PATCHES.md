# Local ADB transport source

This module selects the Apache-2.0 alternative for LibADB Android 3.1.1,
commit `c849886ebc6d48e7b46d967e78a6bb65c90c3b74`:
<https://github.com/MuntashirAkon/libadb-android/tree/3.1.1>.
Retain the additional BSD/MIT attribution in the upstream files and LICENSES.
No LADB application code or credentials are included.

Only the direct connection, pairing and protocol classes are shipped. The
upstream blocking connection manager and mDNS discovery are omitted: the app
owns cancellable socket setup and accepts only explicit localhost ports.

Local patches require bundled Conscrypt's public API, create TLS contexts per
identity, expose already-owned sockets for cancellation, add bounded waits,
repair stream-open/flow-control waits and null-safe pairing cleanup, reject
oversized peer negotiation/queued input, and remove peer/debug logging. Protocol
and app-owned wrapper tests cover frame bounds, exit status, sync responses,
path restrictions, close cancellation and timeout behavior.

The API 34+ fork also removes the unused legacy PRNG implementation, simplifies
charset compatibility, allocates destinations by UTF-8 byte count, and repairs
the unsigned single-byte InputStream contract. Native pairing state is destroyed
once, only after the worker exits, so cancellation cannot free an active context.

After building the library, run `./localadb/run-tests.ps1`. The runner compiles
the framework-independent protocol tests and uses only fake loopback peers; it
does not invoke ADB, pair a phone, or start another Gradle build. Pass
`-CompiledLibraryClasses` to use an already-qualified standalone compilation.

Pinned native dependencies:

- `org.conscrypt:conscrypt-android:2.9.0` (Apache-2.0), AAR SHA256
  `53ee06fc093b6f085c7f81da77f622823e65489cf1bdc64a87230a23603d7773`.
- `com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1`
  (LGPL-3.0), commit `7615ddd680b990e14513ebb66eac4cb0dbf82464`, AAR SHA256
  `8798fb6c04b5d53a6307ed9481e9afe563227abd4e8b9e917c8514fa850071bb`.
  Corresponding source: <https://github.com/MuntashirAkon/spake2-java/tree/2.2.1>.
  Its native SPAKE2 implementation is used, rather than the pure-Java variant.
- BouncyCastle `bcprov-jdk15to18:1.87` (MIT-style license):
  <https://www.bouncycastle.org/about/license/>.

Distribute the LGPL and GPL license texts with the application, prominent
third-party notices, and the complete source/build instructions needed to
rebuild this app against a modified SPAKE2 dependency. The application's public
source distribution provides that rebuilding route. Do not remove these notices
or restrict debugging modifications to the linked library.

ARM64 and the other three published native ABIs were inspected: LOAD alignment
is 16 KB. ARM64 RELRO geometry was also inspected. These observations do not
replace final APK ZIP alignment checks and physical-phone pairing tests.
