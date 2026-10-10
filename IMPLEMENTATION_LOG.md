# IMPLEMENTATION_LOG -- LocalDrop M01 Foundation + M02 Protocol Foundation

## Status: M01 COMPLETED/FROZEN (2026-10-08) -- M02 COMPLETED/FROZEN (2026-10-08) -- M03 COMPLETED/FROZEN (2026-10-08) -- M04 COMPLETED/FROZEN (2026-10-08) -- M05 COMPLETED/FROZEN (2026-10-08) -- M06 COMPLETED (2026-10-08) -- M07 COMPLETED + UI CLOSURE (2026-10-09), M08 NOT started

### Final M01 verification (2026-10-08, verification-only, no architecture changes)
1. Final repository tree (source, excl. `bin/obj/build/.gradle/.kotlin`):
   - `android/LocalDrop/app/src/main/AndroidManifest.xml`
   - `android/LocalDrop/app/src/main/java/com/localdrop/MainActivity.kt`
   - `.../core/{AppConfig,AppLogger}.kt`, `data/DataBoundary.kt`, `discovery/DiscoveryBoundary.kt`,
     `domain/DomainBoundary.kt`, `network/NetworkBoundary.kt`, `presentation/PresentationBoundary.kt`,
     `security/SecurityBoundary.kt`, `service/ServiceBoundary.kt`,
     `storage/{AppDatabase,Entities}.kt`, `transfer/TransferBoundary.kt`
   - `windows/LocalDrop.sln`, `windows/LocalDrop/{app.manifest,App.xaml,App.xaml.cs,LocalDrop.csproj}`
   - `windows/LocalDrop/{Application/ApplicationBoundary,Core/{AppConfig,AppLogging},Discovery/DiscoveryBoundary,Domain/DomainBoundary,Infrastructure/TrayManager,Network/NetworkBoundary,Presentation/{MainWindow.xaml,MainWindow.xaml.cs},Security/SecurityBoundary,Storage/{AppDbContext,Entities},Transfer/TransferBoundary}.cs`
   - `shared-protocol/*.md` (7 docs only), `docs/README.md + 7 pointer dirs`, `test-assets/README.md`, `README.md`, `.gitignore`, frozen `.docx` specs in root.
2. No M02+ implementation: all domain/network/discovery/transfer/security/service/application layers are placeholder boundary objects with `Note = "No ... in M01."`; state machines, messaging, framing arrive M02+.
3. No UDP/TCP networking: android `app/src` grep `DatagramSocket|ServerSocket|Socket(|SocketChannel|DatagramChannel|WifiManager|NsdManager|MulticastSocket` = 0 hits; windows src grep `UdpClient|TcpClient|TcpListener|NetworkStream|SslStream|System.Net.Sockets` = 0 source hits (only transitive framework descriptors in generated `obj/project.assets.json`, `obj/*.nuget.dgspec.json`, not source usage).
4. No pairing/auth/crypto: android grep `KeyStore|Cipher.getInstance|MessageDigest|SecureRandom|KeyPairGenerator` = 0 hits; windows grep `ProtectedData|AesGcm|Cipher|HMAC|PBKDF2|TOTP|QRCode` = 0 source hits (only transitive `System.Security.Cryptography.ProtectedData` descriptor in generated `obj/` JSON). `pairing`/`checkpoint` names appear only as SQLite/Room table/field definitions (6-table schema frozen early), no behavior.
5. No file-transfer/resume/integrity: android grep `FileInputStream|FileOutputStream|RandomAccessFile|FileChannel|sendFile|receiveFile|MessageDigest.*SHA|sha256|confirmedOffset` = only `confirmedOffsetBytes` field in `storage/Entities.kt` (schema default 0, no logic); windows equivalent grep = only `confirmedOffsetBytes` column in `Storage/AppDbContext.cs` DDL (no logic). No `FileStream/BinaryReader/SHA256` usage in windows source (only transitive `System.IO.FileSystem*` descriptors in `obj/` JSON).
6. No cloud/relay/Firebase/telemetry/external API/Internet fallback: android deps = Compose BOM 2024.12.01 + core-ktx + activity-compose + material3 + lifecycle-runtime-compose + Room 2.6.1 only; windows `LocalDrop.csproj` = `Microsoft.Extensions.Logging(+Console)` + `Microsoft.Data.Sqlite` only. Greps for `firebase|firestore|supabase|okhttp|retrofit|grpc|azure|aws|httpclient|relay|analytics|crashlytics` hit only the literal `No cloud/telemetry sink` comments and `No Internet. No cloud.` UI labels -- no SDKs, no endpoints, no fallback.
7. UI owns no networking/file I/O/crypto/orchestration: `MainActivity.kt` = Compose placeholder texts only (`M01 Foundation`, `No Internet. No cloud.`, `Transfer engine not yet implemented`); `MainWindow.xaml(.cs)` = static `TextBlock`s only (Nearby Devices/Active Transfers placeholders); android grep `java.io.File|ContentResolver|DocumentFile|InputStream|OutputStream` in `app/src/.../com/localdrop` = 0 hits; `AndroidManifest.xml` declares zero `uses-permission`/services/receivers/providers.
8. Structure vs frozen M01: `android/LocalDrop/` (Gradle wrapper, Compose shell, config/logging/persistence boundaries) ✓; `windows/LocalDrop.sln` + WPF shell + `Core/Storage/Domain/Application/Network/Discovery/Transfer/Security/Infrastructure/Presentation` boundary folders ✓; `shared-protocol/` docs-only, zero `.kt/.cs` ✓; `docs/` pointers + frozen root `.docx` ✓; `test-assets/` placeholder (no binaries) ✓; `README.md/.gitignore` present ✓; ports TBD-centralized in `AppConfig` both platforms, 8 MiB checkpoint constant only ✓.
9. Warnings (listed fully, none hidden, none fixed per M01 scope):
   - Windows `NU1903` x2 on every build: `Package 'SQLitePCLRaw.lib.e_sqlite3' 2.1.10 has a known high severity vulnerability` (transitive via frozen `Microsoft.Data.Sqlite 9.0.9`). `Build succeeded. 2 Warning(s) 0 Error(s)`. Not upgraded (would violate frozen M01 stack).
   - Android AGP: `This Android Gradle plugin (8.7.3) was tested up to compileSdk = 35` but `compileSdk = 36`. Non-blocking; no downgrade applied.
   - Android SDK tooling: `This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered`. Tooling mismatch only.
   - Android `stripDebugDebugSymbols`: `Unable to strip ... libandroidx.graphics.path.so, packaging them as they are.` Packaging notice only.
   - Re-verified 2026-10-08: `dotnet build windows/LocalDrop.sln -c Debug` → `Build succeeded, 2 Warning(s), 0 Error(s)`; `android/.../app-debug.apk` present (9600593 bytes, 2026-10-08 10:36, from prior `BUILD SUCCESSFUL in 6m 42s, 37 tasks` -- not rebuilt).
10. Result: ALL M01 CHECKS PASS. STOP -- M02 not begun.

### Completed (M01 scope only)
- [x] Repository skeleton per Complete Spec §4.5
- [x] shared-protocol documentation boundary (no runtime code)
- [x] Android shell builds (`assembleDebug`)
- [x] Windows shell builds (`dotnet build`)
- [x] Config / logging / persistence boundaries (no business logic)
- [x] No-cloud / no-M02+ verification

### Commands executed
- `dotnet build "A:\New project\LocalDrop\windows\LocalDrop.sln" -c Debug` (2026-10-08, re-verified: Build succeeded, 0 errors)
- `.\gradlew.bat assembleDebug` in `android\LocalDrop` (2026-10-08 10:36 UTC: BUILD SUCCESSFUL in 6m 42s, 37 tasks; APK `app\build\outputs\apk\debug\app-debug.apk`, 9600593 bytes)

### Build results
- Windows: `LocalDrop -> ...\bin\Debug\net10.0-windows\LocalDrop.dll`, 0 errors, 2 warnings (both NU1903: transitive `SQLitePCLRaw.lib.e_sqlite3 2.1.10` via `Microsoft.Data.Sqlite 9.0.9`, accepted for M01)
- Android: `BUILD SUCCESSFUL`, APK present (2026-10-08 10:36). Deps: Compose BOM 2024.12.01 + Room 2.6.1 + lifecycle only; no network/cloud deps. AGP 8.7.3 warns compileSdk 36 > tested 35 (suppression documented, non-blocking).

### Warnings
- NU1903 (Windows, x2): known high-severity vuln in transitive `SQLitePCLRaw.lib.e_sqlite3 2.1.10`. No action in M01 (frozen `Microsoft.Data.Sqlite 9.0.9`).
- AGP: `compileSdk = 36` newer than AGP 8.7.3 tested (35). Non-blocking warning only.
- SDK XML: `This version only understands SDK XML versions up to 3 but ... version 4 was encountered`. Tooling mismatch warning only.

### No-M02+ / no-cloud verification (2026-10-08)
- Android `app/src` grep `Socket|Datagram|Udp|Tcp|Http|OkHttp|Retrofit|Firebase|...|cloud|Bluetooth|Usb`: only matches are `No cloud/telemetry sink` comment and `No Internet. No cloud.` UI label. No sockets, no HTTP, no cloud SDKs. `app/build.gradle.kts` deps: Compose + Room + lifecycle only.
- Windows `LocalDrop/` (excl. `bin/obj`) grep `Socket|UdpClient|TcpClient|HttpClient|Firebase|Azure|AWS|cloud|Bluetooth|Usb`: only matches are `No cloud/telemetry sink` comment and `No Internet. No cloud.` UI label. `LocalDrop.csproj`: `Microsoft.Extensions.Logging(+Console)` + `Microsoft.Data.Sqlite` only. `obj/` socket strings are transitive .NET runtime descriptors, not source usage.
- `shared-protocol/`: docs only (`*.md`), no runtime code.
- Config: ports TBD-centralized on both platforms (`AppConfig`); 8 MiB checkpoint constant only; no magic port numbers scattered.

### Known limitations (by design for M01)
- No UDP discovery (M03), no TCP control/data (M04/M06+), no pairing/crypto (M05).
- No transfer, resume, integrity, multi-session, widgets, tray behavior, benchmark.
- Port values intentionally TBD; centralized config only.
- Placeholder UI only; UI owns no sockets/file I/O.
# M02 Appendix â€” Protocol Foundation (2026-10-08)

## Status: COMPLETED â€” all M02 acceptance items verified, M03 NOT started

### Protocol decisions actually implemented
- ONE LocalDrop protocol contract (PROTOCOL v1.0 wire authority):
  `protocol="localdrop"`, `protocolVersion=1` (validated, independent of app
  version). Android `com.localdrop.protocol` and Windows
  `LocalDrop.Protocol` are two representations of the same contract.
- Wire format: canonical UTF-8 JSON, contract key order, integers only
  (no floats/exponents/null production). Hand-rolled dependency-free
  JSON subset on both sides (org.json unavailable in JVM unit tests;
  keeps M01 production dependencies frozen).
- Envelope: `protocol, protocolVersion, type, messageId,
  correlationId?, sessionId?, transferId?, payload`. Responses echo the
  request `messageId` in `correlationId`. Unknown JSON fields ignored
  (explicit forward-compat rule); unknown `type`/bad version rejected.
- Vocabulary: exactly the 20 PROTOCOL v1.0 Â§5 message types; 12 error codes
  (Â§10); 15 session states (Â§4 vocabulary only); 10 transfer states
  (UI/UX Â§13, matching M01 AppConfig strings).
- Framing: `4-byte BE length N || N bytes JSON`, `0 < N <= 1048576`
  (1 MiB control-plane cap). Incremental `Decoder.feed()` for future M04
  TCP use (partial reads, split prefixes, multi-message feeds). Oversize
  declarations rejected WITHOUT allocating; buffer capped at 4+MAX.
- Validation is defensive (UUID syntax, bounds, base-name fileName,
  lowercase 64-hex sha256 shape, offset relations, transferId consistency);
  failures return INVALID_PROTOCOL values, never throw, never silent.
- Reconciliation: PROTOCOL v1.0 Â§5 (backed by shared-protocol/messages.md)
  chosen over Complete Spec Â§6.2 name variants (no STATUS/TRANSFER_FAILED/
  PAIR_CHALLENGE/etc.); failures use ERROR. Documented in
  shared-protocol/README.md. Future renames are versioned decisions.
- Payload models are STRUCTURES ONLY: no discovery/UDP, no TCP, no
  pairing/approval, no authentication execution, no crypto, no file I/O,
  no hashing runs (sha256Hex is shape-validated text, never computed),
  no state machine, no timers, no workers.

### Files added
- `shared-protocol/`: rewrote README/protocol-version/messages/errors/
  framing-resume-integrity/discovery-record/transfer-metadata to the M02
  contract; added `envelope.md`.
- `test-assets/protocol-vectors/v1/`: hello/transfer-request/
  transfer-accept/error/pause.json + vectors.md (byte counts + prefixes).
- Android `app/src/main/.../protocol/`: Protocol.kt, MessageType.kt,
  ProtocolError.kt, States.kt, JsonCanonical.kt, Models.kt, Envelope.kt,
  FrameCodec.kt (pure Kotlin, zero Android imports).
- Windows `LocalDrop/Protocol/`: Protocol.cs, MessageType.cs,
  ProtocolError.cs, States.cs, JsonCanonical.cs, Models.cs, Envelope.cs,
  FrameCodec.cs (BCL only, zero WPF references).
- Tests: Android `app/src/test/.../protocol/` (Vectors.kt,
  ProtocolCodecTest, ValidationTest, FrameCodecTest, VectorsTest);
  `windows/LocalDrop.Protocol.Tests/` (csproj + Vectors.cs +
  ProtocolCodecTests/ValidationTests/FrameCodecTests/VectorsTests.cs,
  protocol sources linked, not duplicated).

### Files modified (minimal, M02-required only)
- `app/build.gradle.kts`: + `testImplementation("junit:junit:4.13.2")`
  (test-only; production deps frozen).
- `windows/LocalDrop.sln`: + test project entry (via `dotnet sln add`).
- No M01 source file was edited (AppConfig, boundaries, UI, storage,
  manifest, LocalDrop.csproj untouched). No new permissions.

### Protocol version / types / format / framing / validation
- Version: `localdrop` / 1. Types: 20 (see messages.md). Format: canonical
  JSON over UTF-8. Framing: BE32 length-prefix, 1 MiB cap, incremental
  decoder. Validation: full envelope+payload schema validation per docs.

### Tests added (all offline, no network/device/server)
- 40 Android JVM unit tests + 76 Windows xUnit tests covering: valid
  serialize/deserialize, all-20-type round-trip, malformed, missing field,
  bad version, unknown type, invalid values (UUID/fileName/size/offset/
  sha256/progress/error-code/deviceType/lengths/floats), correlation,
  frame encode/decode, incomplete/split/byte-at-a-time feeds, invalid
  frames, max/max+1 boundaries, overrun cap, oversize rejection,
  vocabularies, and the 5 shared cross-platform vectors.

### Cross-platform compatibility result
- PASS: all 5 vector files byte-identical to both suites' embedded
  literals (verified by script: 262/590/359/192/226 bytes); framing
  prefixes match (00 00 01 06 / 00 00 02 4E / 00 00 01 67 /
  00 00 00 C0 / 00 00 00 E2). Android serialization == Windows
  serialization by construction (same canonical rules, proven by shared
  vectors); transfer vector proves 10 GiB fileSize handling.

### Build results
- Android `.\gradlew.bat assembleDebug`: BUILD SUCCESSFUL (only the
  pre-existing AGP compileSdk-36 warning; no new warnings).
- Android `:app:testDebugUnitTest`: 40/40 PASS.
- Windows `dotnet build LocalDrop.sln -c Debug`: 0 errors; only the 2
  pre-existing NU1903 warnings (frozen Sqlite dep, M01-accepted).
- Windows `dotnet test LocalDrop.Protocol.Tests`: 76/76 PASS.

### Warnings (no new action)
- NU1903 x2 (transitive SQLitePCLRaw via frozen Microsoft.Data.Sqlite
  9.0.9) â€” unchanged from M01, not upgraded per frozen-stack rule.
- AGP 8.7.3 vs compileSdk 36 â€” unchanged M01 notice.
- Test-only additions: xunit 2.9.3 / runner 2.8.2 / Test.SDK 17.13.0
  (test project only), junit 4.13.2 (test config only).

### Static audit (source files only, excl. bin/obj/build)
- No Socket/TcpClient/TcpListener/UdpClient/NetworkStream/HttpClient/
  WebSocket/Firebase/cloud/relay/telemetry/discovery/auth-impl in M02
  sources. Grep hits are only "No X" boundary comments, `sha256Hex`
  field-shape regex/text (no digest computation: no MessageDigest/
  SHA256-hash usage), and pre-existing M01 schema fields. No pairing/
  authentication behavior, no transfer/file-I/O behavior implemented.

### Known limitations (by design for M02)
- No UDP discovery (M03), no TCP (M04), no pairing/crypto (M05), no
  transfer/resume/integrity engines (M06+/M10/M11). Models are contracts.
- Port values still TBD/centralized; controlPort 0 = unassigned.
- Version negotiation is M04 work; M02 accepts exactly version 1.

### M03+ explicitly NOT implemented
- No sockets, broadcasts, scanning, IP discovery, connection
  establishment, pairing/auth flows, encryption, file transfer/streaming,
  resume engine, checkpoints, hash verification runs, services, tray
  behavior, widgets, cloud/relay/Firebase/telemetry, Internet fallback.- STOP -- M03 not begun.

# M03 Appendix -- LAN Device Discovery (2026-10-08)

## Status: COMPLETED -- all M03 acceptance items verified, M04 NOT started

### Discovery design actually implemented
- Transport: UDP broadcast (Complete Spec S5.1 authorizes broadcast/multicast
  "where supported and appropriate"; M03 uses broadcast -- no group management,
  works on phone-hotspot LANs). No mDNS/NSD (not required by spec), no TCP/HTTP.
- ONE shared wire protocol: each announcement datagram is the UTF-8 canonical
  JSON of an M02 HELLO envelope (same bytes M04 will carry on TCP; datagram
  boundaries replace framing, so no length prefix on the wire). No
  AndroidPacket vs WindowsPacket: both sides build/parse via the frozen M02
  ProtocolCodec. No second discovery record: peers ARE M02 DiscoveryRecords.
- Port 48423/udp centralized in AppConfig on both platforms
  (DISCOVERY_UDP_PORT / DiscoveryUdpPort). Resolves the TBD from Complete Spec
  S5.5 for discovery only; the TCP control port stays TBD (M04).
- Timing (centralized, M03-chosen; frozen spec fixes no numbers, M03 S8 permits
  configurable values): announce every 2000 ms; peer expires after 10000 ms
  unseen (~4 lost announcements tolerated). Inbound datagram cap 4096 bytes,
  rejected without parsing. Changing any of these is a versioned decision.
- Lifecycle: start()/stop() (Android, idempotent, daemon threads) and
  Start()/StopAsync()/Dispose (Windows, idempotent, CTS + socket close).
  Listener socket closed to unblock receive immediately; announcer sleep
  interrupted/cancelled; threads/tasks joined with timeout. No socket, thread,
  task, or timer leaks. Restart rebinds cleanly (reuseAddress set).
- Expiry is lazy (on snapshot/handle/prune): no timer thread, deterministic
  under fake clocks. State mapping: absent=Unknown, first insert=Discovered,
  update=Refreshed, pruned=Expired. No transfer/connection/pairing state here.
- Self-filter by authoritative deviceId at insert (never by IP: hotspot/NAT/
  multi-interface unreliability, M03 S10). Duplicates: same deviceId updates
  the existing record + refreshes expiry, never a second entry.
- Validation: every datagram gets full M02 envelope+payload validation;
  oversized/malformed/bad-version/non-HELLO/self packets map to Ignored and
  never disturb the listener. No secrets on the wire (no file info, no auth
  material, no keys). Discovery != trust (M05 owns trust).
- announcePort seam (defaults to listen port; production symmetric): lets two
  instances cross-target over loopback in tests without changing production.
- Real bug found by tests, fixed: Kotlin `bind(InetSocketAddress(port))`
  inside `DatagramSocket(null).apply{}` resolved bare `port` to
  DatagramSocket.getPort() (-1 on unbound socket), not LanDiscovery.port.
  Proved via javap bytecode (`invokevirtual getPort`). Fixed with explicit
  `this@LanDiscovery.port` + comment. Windows C# unaffected (no such trap).

### Files added
- Android `discovery/`: DiscoveryModels.kt (DeviceInfo/DiscoveredPeer/clocks),
  PeerRegistry.kt (validated registry, fake-clock expiry), LanDiscovery.kt
  (listener + announcer threads, pure java.net, zero android.* imports).
- Windows `Discovery/`: DiscoveryModels.cs, PeerRegistry.cs,
  LanDiscoveryService.cs (async UdpClient + CTS, zero WPF references).
- Tests Android `src/test/.../discovery/`: FakeDiscoveryClock,
  PeerRegistryTest (14), LanDiscoveryTest (6, real loopback sockets).
- Tests Windows Protocol.Tests: PeerRegistryTests (14), LanDiscoveryTests (6,
  real loopback sockets).

### Files modified (minimal, M03-required only)
- `core/AppConfig.kt` + `Core/AppConfig.cs`: +4 discovery constants each
  (port/interval/timeout/max-bytes). No existing value touched.
- `AndroidManifest.xml`: + `android.permission.INTERNET` ONLY (required for
  any socket use incl. LAN UDP; normal install-time permission, no prompt).
  No location (no Wi-Fi scan), no multicast lock (broadcast, not multicast),
  no service permission (M13 owns the foreground service).
- `shared-protocol/discovery-record.md`: + M03 transport-binding appendix.
  Record fields frozen, unchanged.
- `LocalDrop.Protocol.Tests.csproj`: + linked `Discovery/*.cs` and
  `Core/AppConfig.cs` source globs (no duplication; test-only).
- M01 UI/boundaries/storage and ALL M02 protocol files untouched. UI not
  rewired (M01 placeholder stands; M03 ends at peer knowledge, S26-minimal).

### Lifecycle and expiry behavior
- Announce 2 s; expire 10 s; lazy prune; refresh extends; reappear =
  Discovered again. Start/stop/restart/cancel verified by tests on both
  platforms (incl. prompt stop of a blocking receive and
  garbage-datagram resilience).

### Android result
- 20/20 new discovery tests pass; full suite 60/60 PASS
  (`:app:testDebugUnitTest`). `assembleDebug`: BUILD SUCCESSFUL (only the
  pre-existing AGP compileSdk-36 notice).

### Windows result
- 20/20 new discovery tests pass; full suite 96/96 PASS (`dotnet test`).
  `dotnet build LocalDrop.sln -c Debug`: 0 errors, only the 2 pre-existing
  NU1903 warnings (frozen Sqlite dep).

### Automated test result
- PASS: 60 Android + 96 Windows, all offline (loopback only, no Internet/
  hardware). Covers M03 S23 items 1-17: valid/malformed/version/oversize/
  bad-ID/self/non-HELLO handling, first/duplicate/multi-peer, expiry/
  refresh/reappear, lifecycle/restart/cancel, crash-resistance, shared
  M02-HELLO-vector compatibility on both sides.

### Physical LAN test result
- NOT RUN. No Android device + hotspot + PC hardware available in this
  environment. Result NOT faked. Intended scenario documented for M16:
  phone Mobile Data OFF + Hotspot ON, PC joined, both discover each other
  with zero Internet. Production path (255.255.255.255 broadcast) uses the
  same send/receive/validate code proven over loopback; only the destination
  address differs.

### Permissions added
- Android `INTERNET` only, justified above. Windows: none (desktop UDP needs
  no capability). No unrelated permissions.

### Warnings
- No new warnings. Pre-existing only: NU1903 x2, AGP compileSdk-36 notice,
  SDK-XML-version notice. No dependency added (platform sockets only).

### Static audit result (source only, excl. bin/obj/build)
- UDP (UdpClient/DatagramSocket/broadcast/reuse) present ONLY in
  LanDiscovery.kt, LanDiscoveryService.cs (+ one test sender). No TcpClient/
  TcpListener/NetworkStream/HttpClient/WebSocket/Firebase/mDNS/file-I-O/
  crypto/pairing-auth-behavior anywhere. "Pair/auth" names occur only as
  frozen M02 data contracts (no behavior), unchanged.

### M04+ explicitly NOT implemented
- No TCP connect, no control channel, no HELLO-over-TCP, no sessions,
  no request/response transport, no retry logic, no pairing/authentication
  flows, no encryption, no file transfer, no send/progress/accept UI,
  no widgets, no cloud/relay/telemetry, no Internet fallback.
- STOP -- M04 not begun.
# M04 Appendix -- TCP Control Channel (2026-10-08)

## Status: COMPLETED -- all M04 acceptance items verified, M05 NOT started

### Control-channel architecture
- One ControlConnection per peer (direct TCP, Complete Spec S5.2, PROTOCOL
  v1.0 S2) plus one ControlServer (inbound listener) per instance plus a
  ControlConnections tracker (keyed by peer deviceId when known, connection
  id until then). One failed peer never affects unrelated peers (proven by
  multi-peer tests). Owner is the application/test layer via
  ControlChannelListener / IControlEvents; UI owns no sockets (no Compose,
  Activity, MainWindow, or XAML-codebehind references).
- Android `com.localdrop.network`: ControlState.kt, ControlEvents.kt,
  ControlConnection.kt (blocking Socket + daemon read thread + send lock +
  CompletableFuture correlation map), ControlServer.kt (ServerSocket accept
  loop + per-inbound worker threads), ControlConnections.kt. Pure JVM networking
  (java.net only); blocking calls documented off-UI-thread.
- Windows `LocalDrop/Network`: ControlState.cs, ControlEvents.cs,
  ControlConnection.cs (TcpClient + async read Task + SemaphoreSlim send lock
  + TaskCompletionSource correlation map), ControlServer.cs (TcpListener accept
  loop), ControlConnections.cs (ConcurrentDictionary). Fully async + CTS.

### TCP port
- CONTROL_TCP_PORT / ControlTcpPort = 48424, centralized in AppConfig both
  platforms. MUST differ from discovery UDP 48423 (separate transports,
  separate ports; asserted by tests on both sides). M03 discovery untouched.

### Handshake behavior (symmetric, role-independent)
- On establish both sides send HELLO immediately (fresh messageId,
  controlPort = 48424, local identity). On validated HELLO receipt: store
  peerHello, reply HELLO_ACK (accepted=true) correlated to the HELLO id.
- READY iff received-HELLO AND received matching HELLO_ACK (correlation must
  equal our HELLO id; accepted must be true). Any other type during
  NEGOTIATING, or a bad ACK, is a PROTOCOL_VIOLATION close. Post-handshake
  every validated M02 message is transported with no business semantics.
- Version incompatibility and malformed handshakes are rejected by the frozen
  M02 codec and close the channel. Handshake success is NOT trust (no pairing,
  auth, keys, or encryption anywhere; M05 owns trust).

### Connection lifecycle
- DISCONNECTED -> CONNECTING -> NEGOTIATING -> READY, with FAILED on any
  failure path and DISCONNECTING during local shutdown (state names mirror
  PROTOCOL v1.0 S4; DISCONNECTED/DISCONNECTING are transport-only additions).
  Per-cycle state (peerHello, handshake flags/latch, notified flag, decoder)
  resets on every connect/accept so reconnects start clean.

### Framing integration
- M02 framing reused exactly: send = FrameCodec.encodeMessage; receive =
  incremental Decoder.feed over an 8 KiB read buffer (split prefixes,
  coalesced frames, and frame+partial mixes handled; oversized declarations
  rejected without allocating; buffer capped at 4+MAX). No second framing.

### Timeouts / send / receive / disconnect / failures
- CONTROL_CONNECT_TIMEOUT_MS = 5000, CONTROL_HANDSHAKE_TIMEOUT_MS = 5000
  (centralized, M04-chosen; spec fixes no numbers). Connect/handshake never
  block indefinitely (proven: blackhole-timeout and silent-peer tests).
- Send: serialized single-frame writes (synchronized / SemaphoreSlim), no
  interleaving (proven: 8x25 concurrent sends, 200/200 intact), no unbounded
  queues; oversize messages fail closed. Receive: continuous loop, EOF ->
  REMOTE_CLOSE, malformed -> PROTOCOL_VIOLATION close, listener bugs isolated.
- sendAndReceive uses the M02 correlationId (request messageId); no second
  correlation mechanism. Null on timeout/cancel/close.
- Disconnect is graceful + idempotent (send path closed, loop stopped, socket
  closed, pendings cancelled, LOCAL_DISCONNECT exactly once per cycle).
  Handled: refused, timeout, silent peer, remote close, malformed/invalid/
  oversized/version-mismatch input, send/receive failure, cancel, shutdown.

### Tests (all loopback/offline)
- Android 22 new (ControlLoopbackTest 10, ControlFramingTest 7,
  ControlServerTest 5); full suite 82/82 PASS. Covers S24 items 1-20.
- Windows 22 new + 2 interop (ControlLoopbackTests 10, ControlFramingTests 7,
  ControlServerTests 5, InteropTests 2); full suite 120/120 PASS.
- Connection timeout uses RFC-5737 192.0.2.1 (bounded, never hangs).

### Android <-> Windows interoperability result: PASS (both directions)
- Real production classes on both ends over 127.0.0.1:48501 (same M02 bytes):
  Phase A Java-driver (Android ControlConnection) <-> C# ControlServer:
  TCP + handshake + PAUSE -> correlated ERROR + disconnect, asserted on BOTH
  sides (driver exit OK + xUnit flags). Phase B C# ControlConnection <->
  Java-driver (Android ControlServer): same exchange, asserted on BOTH sides.
- Reran via script: Temp `interop.ps1` (Phase A/B), `interopA.ps1` (Phase A
  alone). C# interop tests gate on LOCALDROP_INTEROP=1 (pass vacuously in
  plain `dotnet test`; the script sets it). First Phase-A run exposed a TEST
  race (fast session invisible to snapshot polling); fixed by asserting on
  monotonic event flags -- production code was correct in all runs.
- Physical LAN (phone hotspot) test: NOT RUN (no hardware; not faked).
  Production broadcast/dial path differs from loopback only in destination
  address; hotspot scenario stays due at M16.

### Warnings / dependencies
- No new warnings (NU1903 x2 + AGP compileSdk-36 notice unchanged). No new
  dependencies (platform TCP only). One xUnit2013 style warning appeared
  during interop development and was eliminated by the rewrite.

### Static audit (source only, excl. bin/obj/build)
- TCP (Socket/ServerSocket/TcpClient/TcpListener/NetworkStream) present ONLY
  in the new M04 control files. No HTTP/WebSocket/Firebase/file-I-O/crypto;
  no pairing/authentication behavior (names occur only as frozen M02 data
  contracts); remaining `cloud|telemetry` hits are the known M01 "No ..."
  comments + UI label.

### Known limitation (honest gap, no frozen-code change made)
- M03 announces controlPort 0 and does not surface the UDP sender IP, so the
  app-layer mapping DiscoveredPeer -> (host, controlPort) is still pending
  (needed by M06 dialing). M04 connects with explicit endpoints (tests do
  exactly this). Wiring is a later-milestone app-layer concern; M03 stays
  frozen per S29 (no blocker for M04 transport).

### M05+ explicitly NOT implemented
- No pairing, authentication, authorization, encryption, key exchange, trust
  storage, file transfer/streaming, resume, checkpoints, hashing, transfer
  service, widgets, tray, cloud/relay/telemetry, HTTP, or Internet fallback.
- STOP -- M05 not begun.
# M05 Appendix -- Pairing, Device Identity & Transaction Authorization (2026-10-08)

## Status: COMPLETED -- all M05 acceptance items verified, M06 NOT started

### Security architecture implemented
- Device identity: per-installation EC P-256 keypair; deviceId UUID derived
  as UUIDv4-shaped SHA-256(SPKI)[0..16] (cryptographically bound, UUID syntax
  preserved). Android: AndroidKeyStore (alias localdrop_device_identity,
  non-exportable); Windows: persisted CNG user key (LocalDropDeviceIdentity,
  ExportPolicy None). Software stores (same curve/hash/encodings) power JVM
  tests where KeyStore is unavailable. Standard primitives only: ECDSA,
  SHA-256, platform CSPRNGs. No custom crypto, no key exchange, no legacy
  algorithms, no plaintext key persistence (audited).
- Mutual auth over M04 (AUTH_REQUEST/AUTH_RESPONSE only, method
  localdrop-ecdsa-p256-v1): hello (id+SPKI+32B nonce) / proof (raw R||S over
  canonical transcript binding method + sorted ids + both nonces) / result.
  Both sides initiate symmetrically; binding re-derived on receipt; strict
  order/state checks; unknown method, bad token, bad binding, bad signature,
  duplicate/out-of-order steps all fail closed (RESP false + disconnect).
  Auth proves identity ONLY, never trust/authorization.
- Transaction codes: receiver creates {txn UUID, %06d CSPRNG code}, 5-minute
  lifetime (centralized), in-memory only, states ACTIVE/EXPIRED(lazy)/
  CONSUMED/CANCELLED. Sender submits via PAIR_REQUEST.displayCode over an
  AUTHENTICATED channel; receiver validates auth, peer binding, code, state;
  consumes on success; generic "Invalid transfer code." otherwise (wrong
  attempts never consume). Single-use, non-refreshable, never global/PIN/
  sequential. Authorization = authenticated peer + known txn + correct +
  active + unexpired + unused; stored as {peer, txn, timestamp} for M06.
- AuthManager wraps connection listeners (AUTH_* consumed, rest forwarded;
  frozen M04 untouched); TransactionAuthorizer sits behind it; per-connection
  state with atomic join on initiator/responder races, watchdog-bounded
  handshakes, disconnect-driven cleanup. Thin UI models only
  (format/normalize helpers, phase snapshots via observers); no screens.
- Logging: ids/events/reasons only; codes/keys/nonces/sigs/key-bytes never
  logged (test 21 scans full flows incl. base64-blob shape check).

### Files added/changed
- Added shared-protocol/authentication.md FIRST (identity, method, tokens,
  transcript layout, PAIR code flow, binding, logging, v1-compat: no new
  types/fields, version stays 1).
- Android security/: DeviceIdentity, AuthProtocol, AuthManager,
  TransactionAuthorizer, TransactionManager, AuthorizedSession,
  SecurityLogger, AuthUiState (+6 test files).
- Windows Security/: same 8 (+backend split file); 7 test files.
- Modified only: AppConfig.kt/cs (+6 M05 constants each: method id, nonce
  bytes, auth timeout 10 s, code lifetime 300 000 ms, 6 digits, store names);
  test csproj (Security source links, MEL-backend excluded, CA1416 NoWarn
  with justification); AuthorizedSession snapshot() read API (both).
- M01-M04 sources untouched; no new permissions/dependencies; UI untouched.

### Tests and results (all offline; physical Wi-Fi NOT RUN, deferred)
- Android 38 new, suite 120/120 PASS. Windows 39 new + 2 interop, suite
  161/161 PASS. All M01-M04 tests still passing on both.
- Covers prompt items 1-24: identity create/persist(CNG reopen+delete)/
  reload, auth success, forged-signature/unknown-method/binding-mismatch/
  duplicate-hello/timeout/disconnect failures, txn create, 6-digit format,
  200-sample uniqueness, correct/wrong/expired/consumed/cancelled/replay,
  txn+session+peer binding, cross-receiver isolation, multi-txn isolation,
  auth-without-code and code-without-auth rejection, both-required accept,
  secret-free logs, failure/timeout cleanup, full-duplex loopback flows.
- Cross-platform interop PASS both directions (new harness, loopback):
  Phase C C#-responder/Java-initiator and Phase D Java-responder/C#-initiator
  each did TCP + mutual ECDSA auth (JDK provider <-> .NET verify, proving
  SPKI/deviceId/transcript/signature interop) + code submit/accept, asserted
  on BOTH ends. Repro: Temp interop5.ps1 (+AuthInterop.java driver);
  C# gates on LOCALDROP_INTEROP=1 (+LOCALDROP_CODE_FILE/LOCALDROP_TEST_CODE).
  First Phase-C attempt caught a test-only snapshot race (fast session vs
  polling); fixed via monotonic event flags -- production correct throughout.
- AndroidKeyStore path ships untested on JVM (unavailable there); software
  store covers identical primitives; on-device verification deferred.

### Build results / warnings
- Android assembleDebug + testDebugUnitTest: BUILD SUCCESSFUL (only frozen
  AGP compileSdk-36 notice). Windows sln build: 0 errors, only frozen
  NU1903 x2. No new warnings (transient xUnit2013 + CA1416 handled).

### Known limitations / residual notes
- No attempt-limiting on code submission (spec fixes only the four states;
  wrong attempts logged). No transaction persistence across restart (by
  design: new transaction required). DiscoveredPeer->(host,controlPort)
  app wiring still pending for M06 (noted in M04).
- M06+ explicitly NOT implemented: no file streaming/chunking/transfer,
  resume, checkpoints, hashing, perf, multi-file, foreground service,
  widgets, tray, packaging.
- STOP -- M06 not begun.
# M06 Appendix -- Android to Windows Streaming Transfer (2026-10-08)

## Status: COMPLETED -- all M06 acceptance items verified, M07 NOT started

### Architecture (see docs/architecture/m06-transfer-decisions.md, 20 ADRs)
- Single-stream dedicated TCP data connection (DATA_TCP_PORT 48425) alongside
  the M04 control channel; M02 framing/messages reused exactly, no wire change
  (protocol stays v1). Flow: M04/M05 session -> TRANSFER_REQUEST ->
  TRANSFER_ACCEPT/REJECT -> data dial + 37-byte preamble (transfer UUID) ->
  TRANSFER_START -> raw byte stream -> throttled TRANSFER_PROGRESS ->
  TRANSFER_COMPLETE (64-zero hash sentinel = not computed, M11 pending) ->
  transport-complete. Receiver validates auth/session/address/resume/idle/
  space in order, streams socket -> 1 reusable buffer -> .localdrop-part temp,
  finalizes by atomic move with never-overwrite uniquification.
- Streaming O(1) memory both ends; structural backpressure (blocking I/O, no
  queues); deterministic probe-with-backoff sizer (64 KiB..1 MiB, 3-window
  confirmation, 2 s cooldown, shrink disarms regrowth until 1.2x improvement);
  64-bit counters; cooperative cancellation; M02 error-code mapping; metrics
  without observer effect. Authorization binding is peer-level for M06
  single-transfer scope (documented; M12 may motivate a transaction reference).
- Endpoint resolution needs no discovery change: data host derives from the
  live control connection; data port is fixed/centralized like control.
- Bugs found by tests and fixed: (1) growth rule that could never trigger on
  steady links -> probe-with-backoff redesign; (2) temp deleted while
  FileStream open (Windows refuses) -> dispose-before-delete + handler-side
  handle close; (3) CANCEL between pending-clear and active-set matched
  neither -> atomic swap + pending-path outcomes; (4) outcome delivered
  before waiter registered -> terminal-outcome map + locked publish.

### Files added
- Android transfer/: TransferModels, TransferSource, AdaptiveBlockSizer,
  TransferMailbox, SenderEngine (+4 test files).
- Windows Transfer/: TransferModels, AdaptiveBlockSizer, TransferMailbox,
  TransferRequestHandler, ReceiverEngine (+4 test files, incl. gated
  TransferInteropTests).
- docs/architecture/m06-transfer-decisions.md (20 ADRs, required structure).
- shared-protocol/data-transfer.md (data-plane contract; M02 untouched).
- Temp-only harness (not repo): TransferInterop.java, interop6.ps1.

### Files modified (minimal)
- AppConfig.kt/cs: +M06 constants (ports/timeouts/block/adapt/progress) and
  DATA_ACCEPT_TIMEOUT 30 s. Test csproj: Transfer/*.cs link. Nothing else;
  M01-M05 sources untouched; no new permissions/dependencies.

### Tests: Android 30 new, suite 150/150 PASS
- Sizer 11 (init/growth/shrink/cooldown/no-oscillation/re-arm); models 5
  (64-bit math incl. near-Long.MAX_VALUE, wires, errors, routing, names);
  sender 12 (7 boundary sizes incl. 0-byte, 64 MB stream, monotonic
  progress, reject-no-dial, cancel, source-failure, refused/timeout,
  silent-accept timeout, invalid-name, slow-receiver backpressure);
  mailbox 2 (ordering, 128-cap).

### Tests: Windows 31 new, suite 192/192 PASS (run twice, stable)
- Sizer 10, models 4, receiver 14 (accept/finalize/collision/zero-byte,
  unauthenticated(T003-like)/no-session/misaddressed/resume/busy/preamble/
  early-EOF/COMPLETE-timeout/cancel/slow-drip/progress/temp-naming),
  mailbox 2, gated interop 1. Full suite green on repeat runs.

### Interop (loopback, production code both ends): PASS
- 100 MB: 340 ms sender-side (294 MB/s), byte MATCH by independent
  SHA-256 (python hashlib, test measurement only). 1 GB: 3.2 s (320 MB/s),
  MATCH. Both directions of assertion (engine outcomes + file compare).
  No bandwidth cap evidenced (far above 37 MB/s target, uncapped).
- 1 GB sizer adapted once (256 KiB -> 131072) on a loopback transient;
  history bounded; documented as normal controller behavior.

### Physical / large-file status (honest)
- Physical Android hardware + hotspot: NOT RUN (no device available).
- 10 GB+: DEFERRED (not capability — loopback proves size-agnosticism;
  ~30 s/GB measured pace + wall-time cost; needs M16 environment).

### Builds / warnings
- Android assembleDebug + testDebugUnitTest: BUILD SUCCESSFUL (only frozen
  AGP notice). Windows sln: 0 errors, only frozen NU1903 x2. No new warnings
  (transient CS0728/CS8602 fixed during development).

### Static audit
- No MAX_FILE_SIZE/2GB/4GB limits, no throttle/cap, no parallel streams, no
  resume/checkpoint code, no transfer hashing (M05 identity SHA-256 only),
  no HTTP/WebSocket/cloud/mDNS/second discovery, no custom crypto, no auth
  bypass (receiver enforces auth+session; preamble binds data socket), no
  UI-owned networking, no whole-file buffers (single block buffer per side),
  no unbounded queues (mailbox capped at 128), Int only for ports/timeouts/
  counts/blocks with documented exact conversions.

### Known limitations
# M07 Appendix -- Windows to Android Streaming Transfer (2026-10-09)

## Status: COMPLETED -- all M07 acceptance items verified, M08 NOT started

### Dependency audit (M04/M05 confirmed, no blockers)
- M04: `ControlConnection` (connect/send/remoteAddress), `ControlServer`,
  `TransferMailbox` present and symmetric on both platforms; M07 uses them
  unchanged (request/response correlation, data-host derivation).
- M05: `AuthManager.isAuthenticated/verifiedPeer`
  (`IsAuthenticated/VerifiedPeer`), `AuthorizedSessionStore.sessionsFor`
  (`SessionsFor`), `TransactionAuthorizer` chain present and symmetric;
  receiver enforces auth -> session -> addressed -> resume==0 -> name ->
  idle -> space in the M06 order. No security workaround needed or made.
- M06: `AdaptiveBlockSizer`, `TransferModels`
  (progress/outcome/metrics/errors/transferId routing), `TransferMailbox`,
  `data-transfer.md` contract (port 48425, 37-byte preamble, zeros sentinel,
  `.localdrop-part` discipline) reused verbatim; M01-M06 production sources
  untouched (verified: no edits outside new M07 files, new tests, docs, log).

### Architecture (see docs/architecture/m07-transfer-decisions.md, 20 ADRs)
- Reverse-direction counterpart of M06 over the UNCHANGED wire contract
  (protocol stays v1; §20 gate: no new type/field/framing/port/security
  item; appendix added to shared-protocol/data-transfer.md only).
- Reuse: sizer/models/mailbox/control/auth/session/data-plane reused
  directly (approach A). Platform counterparts (approach C): Windows
  `SenderEngine` (async + CancellationToken) + `TransferSource`
  (File/ByteArray); Android `ReceiverEngine` (daemon threads, no coroutines
  per M06 ADR 14) + `ReceiverDestination` seam (`FileReceiverDestination`
  default; SAF backends plug in later) + `TransferRequestHandler`.
- Streaming O(1) memory both ends; structural backpressure (blocking I/O,
  no queues); M06 sizer constants reused unchanged; 64-bit counters;
  cooperative cancellation; M02 error mapping; zeros-hash sentinel (M11
  pending); single transfer per receiver (M12 owns orchestration).
- Bugs found by tests and fixed: (1) `::ffff:127.0.0.1` mapped host from
  dual-stack listeners -> normalize to dotted quad in the NEW sender's
  `DeriveControlPeerHost` (sender-only addition); (2) transient CS8620
  nullable `WhenAll` inference in the new interop test -> cast to
  non-generic `Task` (test-only).

### Files added
- Windows Transfer/: `SenderEngine.cs` (async sender: request -> accept ->
  preamble -> START -> stream -> PROGRESS -> COMPLETE=zeros -> close),
  `TransferSource.cs` (ITransferSource + File/ByteArray).
- Android transfer/: `ReceiverEngine.kt` (data listener + atomic
  pending->active adoption + stream -> temp -> COMMIT-gated finalize),
  `ReceiverDestination.kt` (interface + file default), `TransferRequestHandler.kt`.
- Tests: Windows `SenderEngineTests.cs` (21), `SenderReceiverInteropTests.cs`
  (prod sender -> prod receiver, 5 MB), `SenderInteropTests.cs` (gated
  cross-runtime sender side); Android `ReceiverEngineTest.kt` (17),
  `TransferInteropTest.kt` (prod sender -> prod receiver, 5 MB).
- Docs: `docs/architecture/m07-transfer-decisions.md` (20 ADRs, required
  structure); `shared-protocol/data-transfer.md` + M07 reverse-direction
  appendix (no wire change).
- Temp-only harness (not repo): `ReceiverInterop.java` (production JVM
  receiver stack), `interop7.ps1` (orchestrator), generated source files.

### Files modified (minimal)
- `IMPLEMENTATION_LOG.md` (this appendix), `shared-protocol/data-transfer.md`
  (appendix only), new ADR doc. No M01-M06 production source touched; no new
  permissions (manifest untouched); no new dependencies (csproj/gradle
  untouched); no UI changes.

### Tests: Windows 23 new (21 sender + 1 same-stack interop + 1 gated), suite 215/215 PASS
- Sender: host derivation, small round-trip (preamble + zeros COMPLETE),
  real-file source, 8 boundary sizes (0/1/63K/64K/65K/256K/1M+1/5M), 64 MB
  stream, 50 GB progress math, monotonic/100% progress, reject-no-dial,
  cancel (+peer CANCEL), source-failure, refused, silent-timeout,
  slow-receiver backpressure (>1 s proof), invalid-name (no network touch).

### Tests: Android 18 new (17 receiver + 1 same-stack interop), suite 168/168 PASS
- Receiver: accept/finalize byte-exact + temp lifecycle + metrics, zero-byte,
  collision uniquification, unauthenticated (PAIRING_REQUIRED, no file),
  no-session, misaddressed + nonzero-resume, busy-reject + pending-cancel,
  unknown-preamble (no file, engine reusable), early-EOF cleanup,
  COMPLETE-timeout (no false completion), size-mismatch (no finalize),
  cancel cleanup, pending peer-ERROR code, monotonic progress, temp naming,
  slow-drip byte-exact, 50 GB progress math.

### Interop (loopback, production code): PASS
- Same-stack: Windows prod sender -> prod receiver 5 MB byte-exact (417 ms);
  Android prod sender -> prod receiver 5 MB stream-compared.
- Cross-runtime (M07 direction, temp harness): Windows CLR SenderEngine ->
  JVM Android ReceiverEngine with full M04 handshake + mutual ECDSA auth +
  real code authorization: 100 MB MATCH (sender 928 ms; receiver 409 ms,
  244.5 MB/s) and 1 GB MATCH (5.2 s, 196 MB/s), stream-compared on the JVM
  side. 1 GB run adapted twice on a loopback transient (bounded history;
  normal controller behavior, same as M06). No bandwidth cap evidenced (far
  above 37 MB/s target, uncapped).

### Physical / large-file status (honest)
- Physical Android hardware + hotspot (data OFF): NOT RUN (no device
  available; not faked). Production dial path differs from loopback only in
  destination address; hotspot scenario stays due at M16.
- 10 GB+: DEFERRED (not capability -- 1 GB proves size-agnosticism at
  ~200 MB/s pace, i.e. ~50 s for 10 GB; wall-time cost; needs M16
  environment).

### Builds / warnings
- Windows `dotnet build LocalDrop.sln -c Debug`: 0 errors; only the 2 frozen
  NU1903 warnings (transitive SQLitePCLRaw via frozen Microsoft.Data.Sqlite
  9.0.9, M01-accepted). `dotnet test LocalDrop.sln`: 215/215 PASS.
- Android `:app:assembleDebug`: BUILD SUCCESSFUL (only frozen AGP
  compileSdk-36 notice + SDK-XML-version notice).
  `:app:testDebugUnitTest`: 168/168 PASS (0 failures/errors).
- Flake note (honest): one full Windows run showed 214/215 with M06
  `ReceiverEngineTests.MissingComplete_TimesOutAndCleansTemp` failing inside
  `Setup` (auth/connect scaffolding, before any transfer logic runs). Passes
  in isolation and on repeat full runs (215/215 twice after, plus this
  third confirmation run 215/215). Cause: heavy parallel CPU load from the
  new tests (64 MB stream, boundary sizes) can delay the 15 s auth await in
  Setup. M07 changes no auth/control paths; not a product regression.
- No new warnings (transient CS8620 fixed; SDK-XML notice pre-existing).

### Static audit
- No MAX_FILE_SIZE/2GB/4GB limits, no throttle/cap, no parallel streams, no
  resume/checkpoint code, no transfer hashing (identity SHA-256 only, M05),
  no HTTP/WebSocket/cloud/mDNS/second discovery, no custom crypto, no auth
  bypass (receiver enforces auth+session; preamble binds data socket), no
  UI-owned networking, no whole-file buffers (single block buffer per side;
  test fixtures only), no unbounded queues (mailbox capped at 128; outcome
  queues hold one small object per transfer), no foreground service/widgets
  (Android), no tray work (Windows). `int` only for ports/timeouts/counts/
  bounded buffer lengths with documented exact conversions at both `want`
  casts.

### Known limitations
- Single active transfer per receiver (second -> TRANSFER_REJECTED); M12.
- No resume (interrupt -> clean failure + temp cleanup); M10.
- Transport-complete is explicitly unverified; M11.
- Android SAF/document-tree destinations need a `ReceiverDestination`
  backend (seam ready; file behavior identical today); rename atomicity
  documented as file-backed-only.
- DiscoveredPeer->endpoint wiring: RESOLVED by the verification audit (F4/F5)
  via announced control ports + recorded source hosts + TransferCoordinator/
  ReceiveCoordinator; control-derived data endpoints unchanged.
  [SUPERSEDED 2026-10-09: tap-to-send UI screens are now implemented — see
  M07 UI Closure Appendix below. The sentence "screens remain future work"
  below is retained as history.]
- M08+ (streaming follow-ups and everything after) NOT implemented.
- STOP -- M08 not begun.

# M07 Verification-Audit Appendix (independent audit, fixes applied)

## Verdict basis
Independent re-verification found 5 fixable defects/gaps (F1 staging
finalize, F2 pending-slot leak, F3 pre-cancelled token, F4/F5 endpoint +
integration gap incl. a live commit-vs-teardown race) plus 2 race/bound
hardening items (outcome-claim, earlyCompletes cap). All fixed minimally in
M07 scope (+ minimal M03 endpoint prerequisite, no wire change). One
historical flake remains UNRESOLVED (see below). Physical hotspot tests NOT
RUN. [SUPERSEDED 2026-10-09 for the UI part only: coordinators previously
proved the flow programmatically and tap-to-send UI did not exist yet — the
M07 UI Closure Appendix below records the now-functional screens. History
preserved; current status is in that appendix.]

## Fixes (see docs/architecture/m07-transfer-decisions.md amendments A1-A7)
- F1 HIGH: `FileReceiverDestination.finalize` staging commit; no direct
  final writes; `ReceiverDestinationTest` (6 new).
- F2 MEDIUM: Android `failPendingNow` releases pending slot (M06 Windows
  instance noted, untouched per scope).
- F3 MEDIUM: Windows sender pre-cancelled token -> Cancelled (2 tests).
- F4 HIGH: `lastSeenHost` + real control-port announcements both platforms
  (defaults preserve all M03 tests); `PeerEndpointTest(s)` (5+5).
- F5 HIGH: Windows `TransferCoordinator` (+ `TransferCommitLingerMs=1000`
  INITIAL) + Android `ReceiveCoordinator` + resolvers; full loopback
  discovery->transfer tests with no manual address
  (`TransferCoordinatorTests` 3, `CoordinatorInteropTest` 2).
- Races: first-claim-wins outcomes + finalizeGate commit serialization +
  earlyCompletes cap 16 (3 single-delivery/stray tests).
- Harness committed as `test-assets/interop-m07/`; re-verified 10 MB +
  100 MB MATCH via the committed script (273 MB/s @100 MB).

## Test totals after audit
- Windows `dotnet test LocalDrop.sln -c Debug`: 225/225 PASS x3 consecutive
  full runs (192 M01-M06 + 33 M07/audit: 23 sender, 5 endpoint, 3
  coordinator, 1 same-stack interop, 1 gated vacuous).
- Android `:app:testDebugUnitTest` + `:app:assembleDebug --offline`:
  185/185 PASS, BUILD SUCCESSFUL (168 M01-M06 + 17: 6 destination, 1
  adoption, 3 races, 5 endpoint, 2 coordinator; +1 interop counted in M07).
  Only frozen warnings (AGP compileSdk-36, SDK-XML, NU1903 x2).
- Cross-runtime (committed harness, loopback): 10 MB + 100 MB MATCH;
  earlier evidence 1 GB MATCH (196 MB/s) stands.

## Flake status: UNRESOLVED (reported honestly, not explained away)
- M06 `ReceiverEngineTests.MissingComplete_TimesOutAndCleansTemp` failed
  ONCE inside auth `Setup` (before any transfer logic) under full-suite
  parallel load. 5/5 isolation passes + 3/3 full-suite passes (225/225)
  since. Suspected mechanism (UNPROVEN): parallel CPU pressure delaying the
  15 s auth await. No timeout was raised, no assertion weakened, no test
  quarantined. If it recurs, capture the Setup exception and consider
  serializing heavy transfer collections (test-only change).

## Known limitations (updated)
- Single transfer per receiver (M12); no resume (M10); completion
  unverified (M11) � unchanged.
- [SUPERSEDED 2026-10-09] "Tap-to-send UI does not exist" -- functional WPF + Compose screens are now implemented and tested; see M07 UI Closure Appendix. (Original sentence retained as history.)
- `TransferMailbox` per-transferId map + M06 `ReceiverEngine`
  `_earlyCompletes` + terminal/finished queues are drained-by-discipline
  (same as M06 design); unbounded-peer hardening is M18 scope. The M07
  Android `_earlyCompletes` IS capped (16).
- Physical Android hotspot (data OFF) + 10 GB+ device tests: NOT RUN /
  DEFERRED. Loopback is not presented as Wi-Fi proof.
- M08+ NOT started. STOP.

# M07 UI Closure Appendix (2026-10-09)

## Status
Functional user flows implemented on Windows (WPF) and Android (Compose),
wired to the existing M07 coordinators/engines. No protocol, security, or
transport change. M08 NOT started.

## Documentation reconciliation
The following earlier statements are SUPERSEDED (marked in place; original
wording retained as history):
- M07 Appendix "Tap-to-send UI screens remain future work".
- Audit Appendix "coordinators prove the flow programmatically; tap-to-send
  UI does not exist yet" (UI part only; flake/physical-test statements in
  that appendix remain current).
- Audit "Known limitations": "Tap-to-send UI does not exist".
M06 Appendix "M07 NOT started" (line 501) is historical record of M06 time
and is left unchanged. Current M07 status is THIS appendix.

## What became functional
Windows (sender): Nearby-devices list with online/waiting status, manual
refresh + 2 s auto-poll, peer selection, native file picker with name/size,
6-digit receiver-code entry, gated Send (peer+file+valid code required),
live progress (percent, bytes, speed, ETA, engine sub-state), Cancel with
terminal Cancelled state, actionable per-code error text. Single app-root
construction (CNG identity, discovery announcing the real control port,
TransferCoordinator, SendController); one window, disposal on exit.
Android: Send tab (same workflow via SAF OpenDocument picker, no new
permission) and Receive tab (readiness, spaced single-use code display with
New-code action, incoming file/peer info, live progress, receiver Cancel,
terminal outcomes, destination note). Controllers own worker threads and
join them on close; Compose observes snapshot state only.

## Security preserved
M05 flow untouched: mutual auth before code submit, code shape/expiry/
single-use enforced by the existing TransactionManager/Authorizer, no
auto-approval, no hardcoded or logged codes, no trust from discovery or IP,
transfer starts only after ACCEPT. Receiver acceptance model: code-gated
authorization IS acceptance (no second accept gate invented).

## Honest deviations from UI/UX Spec v1.0 (documented, not silent)
- "Completed only after SHA-256" cannot hold before M11: success screens
  show Completed plus a one-line M11 note; no Verifying/Verified states.
- No Pause/Resume buttons (engine has no such capability).
- No widgets/tray/history/paired-list/settings (M12/M13/M14 scope).
- Rotation recreates controllers (active transfer cancelled cooperatively,
  discovery restarts); no new lifecycle dependency was added for M07.
- Android receives land in file-backed app-private storage via the
  existing ReceiverDestination seam (SAF-tree destinations still future).

## Tests added (all passing, see totals below)
- Windows `SendControllerTests` (24): peer updates/empty/flags, peer-gone
  block, real-outcome success/reject paths, invalid-code gate, duplicate
  prevention, picker cancel/failure, real-event progress, cancel, dispose
  cleanup, all-12-codes error text, formatter sanity.
- Android `SendUiControllerTest` (15): same coverage + M05 expiry/replay
  rule test + full loopback run through the real SendCoordinator
  (discovery-resolved dialling, byte equality).
- Android `ReceiveUiControllerTest` (5): code gating/display format,
  real-progress inbound success with byte equality, receiver cancel
  (sender sees NETWORK_LOST, no leftovers), close cleanup.
- Engine additions: `activeTransferInfo()` read-only accessor (used by the
  receive screen); receiver cancel reuses existing `cancelTransfer`.

## Changed files (UI closure only)
- New: windows `Transfer/{ITransferSender,SendController}.cs`,
  `SendControllerTests.cs`; `App.xaml(.cs)` wiring, `Presentation/`
  `MainWindow.xaml(.cs)` send view. Android `transfer/{SendCoordinator,
  SendUiController, ReceiveUiController, ContentUriTransferSource}.kt`,
  `MainActivity.kt` Send/Receive screens; tests `SendUiControllerTest.kt`,
  `ReceiveUiControllerTest.kt`; `activeTransferInfo()` on ReceiverEngine.
- Modified (additive only): `ITransferSender`/`TransferCoordinator`
  optional progress callback; `App.xaml` StartupUri removal.
- Untouched: build.gradle.kts, LocalDrop.csproj, AndroidManifest.xml (no
  new dependencies, no new permissions), all protocol/security/transport
  behavior.

## Known limitations (current)
- Physical-device hotspot/mobile-data-off testing NOT RUN; loopback
  integration (incl. 10 MB cross-runtime MATCH re-verified in this task)
  is not Wi-Fi proof. M16 owns the physical gate.
- One transfer at a time per controller (M12); no resume (M10); completion
  is transport-complete, not integrity-verified (M11).
- Prior audit notes stand: single historical flake UNRESOLVED (no
  recurrence in this task: full suites green, see below); mailbox/queue
  hardening is M18 scope.
- STOP -- M08 not begun.

## M07 Final Closure Patch & M08 Performance Formalization
- **M07 Closure**: Fixed Windows temporary-file cleanup race condition in \ReceiverEngine.DeleteTempQuietly\ by implementing a transient-retry mechanism for \IOException\ and \UnauthorizedAccessException\ that logs upon exhaustion without replacing original outcome. Fixed Android \AppStack\ and \AppStackViewModel\ nesting and scope compilation errors to finalize lifecycle decoupling.
- **M08 Formalization**: Created \docs/performance/M08_STREAMING_PERFORMANCE_METHODOLOGY.md\ specifying the formal benchmarking procedure for evaluating the \AdaptiveBlockSizer\. Does not introduce any physical speed caps or internet dependencies.
- STOP -- M09 not begun (superseded).

## M09 Large-File Acceptance Completion
- Validated large file transfers using deterministic, zero-allocation memory sources against a physical disk receiver.
- Proved memory boundedness: `SenderEngine` and `ReceiverEngine` allocate exact `byte[sizer.CurrentBlockSize]` constraints (maximum 1 MiB) without scaling by file size.
- Crosses 32-bit (4 GiB) boundary successfully: A 21,474,836,480-byte (20 GiB) transfer successfully routed via TCP loopback natively holding 64-bit bounds and identical SHA-256 integrity using an independent test oracle. (Exit code 0, 10 minutes runtime).
- Validated zero-byte, 1 MiB, 100 MB, 4.65 GiB, and 20 GiB file constraints via the `LargeFileAcceptanceTests` suite.
- Limits: M11 (SHA-256 in protocol) remains out of scope. Physical Wi-Fi and SSD capacity constraints apply for realistic device deployment but are documented as BLOCKED pending M16. No arbitrary application-level maximums have been applied.
- STOP -- M10 not begun.

## M12 Multi-Session Implementation Audit and Closure (2026-10-10)

### Status: CLOSED -- concurrency and isolation criteria evidenced, M13 NOT started

### 1. Current test run retrieved (no inference, no duplicate runs)
- Prior state on entry: latest `testDebugUnitTest` XML dated 2026-10-10 17:55 showed
  205 tests / 0 failures, but the working tree did NOT compile at that point (see
  defects below), so that result could not be claimed. It was discarded as evidence.
- Final verified runs (2026-10-10, after fixes):
  - Android `:app:testDebugUnitTest --offline`: exit 0, BUILD SUCCESSFUL.
    212 tests, 212 passed, 0 failed, 0 errors, 0 skipped (26 test classes).
    `ReceiverEngineTest`: 28/28 PASS.
  - Android `:app:assembleDebug --offline`: exit 0, BUILD SUCCESSFUL
    (only the frozen AGP compileSdk-36 notice).
  - Windows `dotnet build LocalDrop.sln -c Debug`: 0 errors; only the frozen
    NU1903 x2 warnings (transitive SQLitePCLRaw via frozen Microsoft.Data.Sqlite).
  - Windows `dotnet test LocalDrop.sln -c Debug`: 260 passed, 0 failed,
    0 skipped (13 s).
- Flake honesty: two full-suite Android runs each failed a DIFFERENT single test
  (`SenderEngineTest.small file round trips...` line 242, then
  `AuthorizationFlowTest` line 278); each passes in isolation and passed on the
  final full run. Both are outside the M12 code paths (fake-receiver sender test;
  auth flow test) and match the known parallel-load flake pattern already recorded
  in the M07 audit appendix. Reported, not hidden; no assertion weakened.

### 2. Android session-isolation audit (every actives/pendings access reviewed)
- Request path (`handleTransferRequest`): auth -> peer binding -> authorized session
  -> addressed -> resume-offset check -> file-name check -> under gate: running check,
  stale prune, capacity check (4 slots), duplicate-transferId reject, free-space
  precheck, then `pendings[id] = ...`. No overwrite of a live slot possible.
- Data adoption (`handleDataClient`): preamble -> read-only pending lookup (never
  cleared here) -> destination probes OUTSIDE the lock -> atomic pending->active swap
  UNDER the lock with per-transfer temp reservation (`reservedTemps`, gate-guarded;
  contended same-name falls back to `<name>.<id8>.localdrop-part`, single-transfer
  `<name>.localdrop-part` contract preserved). Lost races never stream.
- Cancel/timeout/error/close paths all address the exact transferId:
  `handleCancel`/`cancelTransfer` (pending -> outcome here; active -> abort there),
  `handlePeerError` with ID fails only the entry whose `control ==` sender (unknown
  ID touches nothing; null/empty ID keeps legacy control-scoped behavior),
  `handleControlClosed` fails only entries on the lost channel. One session cannot
  cancel/fail/clear another (proven by tests below).
- Completion/failure: first-claim-wins `claimOutcome` under gate; commit serialized
  by `finalizeGate` (lock order finalizeGate -> gate everywhere, no deadlock);
  commit-time final-name re-resolution upholds the collision policy for concurrent
  same-name landings. `earlyCompletes` capped at 16 against hostile peers.
- Shutdown (`stop`): snapshots actives+pendings under gate, clears maps, joins the
  accept thread, aborts every active and delivers Cancelled to every pending waiter.
  `reservedTemps` entries released via the claimed-completion path.
- GENUINE DEFECT FOUND AND FIXED (both platforms): `pruneStalePendingLocked`
  silently dropped expired pendings without delivering a terminal outcome, leaving
  waiters hanging until their own timeout. It now returns the expired IDs and both
  call sites deliver `Failed(TIMEOUT, "Pending request expired.")` OUTSIDE the gate.
  Minimal change, no lock-order change.
- Non-issues verified, left untouched: `rememberComplete` live/stray branches both
  store to the bounded map (redundant but harmless); checkpoint `getCheckpointAndMigrate`
  identity-migration semantics are M10-owned (concurrent same sender+fileId+size resume
  stays an M10 edge case; M12 tests use distinct fileIds).

### 3. Concurrent-transfer behavior tests (observable outcomes, not sizes)
Android `ReceiverEngineTest` (28 tests) evidences: two pendings accepted simultaneously;
`two concurrent transfers both complete byte exact` (200 KB + 50 KB deterministic payloads,
independent SHA-256/file compares, per-ID monotonic progress each ending at 100%);
`cancelling one transfer leaves the other running` (cancelled temp/final absent, survivor
byte-exact, engine idle); `sender error for one transfer does not fail another`
(exact sender code on the addressed ID, survivor byte-exact); `concurrent same filename
uniquifies without corruption` (`dup.bin` + `dup (2).bin`, SHA set equality, no temp
leftovers); `stop releases every active and pending transfer` (both Cancelled, no finals);
`duplicate transfer id is rejected without disturbing live transfer`;
`fifth concurrent request rejected while four slots full` (TRANSFER_REJECTED, slots freed
on cancel, new request accepted after). Tested-broken-test repairs (no weakening):
restored the missing `@Test fun unknown preamble...` header and hoisted `tidB` out of
its inner-try scope (compile errors that blocked the whole suite).
Windows `ReceiverEngineTests` (20 tests) evidences the same matrix:
`MultipleRequests_Concurrent_BothComplete`, `CancelOneTransfer_OtherCompletesByteExact`,
`PeerError_ScopedToAddressedTransfer`, `ConcurrentSameFilename_UniquifiesWithoutCorruption`,
`DuplicateTransferId_Rejected_LiveTransferUndisturbed`, plus the full M06/M07 regression set.

### 4. Windows and shared behavior audit
- Windows `ReceiverEngine` already uses plural `_actives`/`_pendings` maps (plain
  `Dictionary` under `_gate`, equivalent to Android's gate-guarded `ConcurrentHashMap`);
  all read/write sites reviewed and match Android semantics ID-for-ID (capacity 4,
  duplicate-ID reject, control-equality checks, per-transfer temps, first-claim outcomes,
  finalize-gate commit, bounded early-completes, stop releases all). Same prune fix applied.
- `TransferRequestHandler` on both platforms routes strictly by transferId
  (REQUEST/COMPLETE/CANCEL/ERROR) and forwards close events; no session cross-talk.
- Unchanged and re-verified per session: transferId validation/isolation, checkpoint
  ownership per transferId (cleared only for the completing/cancelled ID), mandatory
  M05 auth + transaction-code authorization ordering, enforced SHA-256 verification per
  session (zeros sentinel rejected), bounded streaming (single block buffer) with 64-bit
  counters. Concurrency limit 4+reject is justified (bounded temp/socket/file-handle
  growth) and tested (`fifth concurrent request rejected...` both platforms).
- Sender engines need no change (one instance per transfer; no shared session state).

### 5. Evidence summary (commands, exit codes, counts)
- `dotnet build windows/LocalDrop.sln -c Debug` -> 0 errors (NU1903 x2 frozen). 
- `dotnet test windows/LocalDrop.sln -c Debug` -> 260/260 PASS, exit 0.
- `.\gradlew.bat :app:testDebugUnitTest --offline` -> 212/212 PASS, exit 0.
- `.\gradlew.bat :app:assembleDebug --offline` -> BUILD SUCCESSFUL, exit 0.
- Diff scope: `ReceiverEngine.kt` (prune returns IDs + 2 delivery call sites),
  `ReceiverEngine.cs` (same), `ReceiverEngineTest.kt` (restored test header + tidB
  hoist). No other production files touched; no new deps/permissions; no UI changes.
- 20 GiB acceptance test NOT rerun (no regression or requirement justifies it).

### 6. M12 report statuses
- Multiple requests accepted: PASS (both platforms, second-pending accept tests).
- Multiple transfers actually executing concurrently: PASS (byte-exact dual completion).
- Automated concurrency and isolation tests: PASS (cancel/error/duplicate/collision/
  shutdown matrix, 28 Android + M12 Windows subset, all green).
- Physical multi-device testing: NOT RUN (no hardware; remains M16 per directive).
- STOP after M12. M13 NOT started. No UI redesign undertaken.
