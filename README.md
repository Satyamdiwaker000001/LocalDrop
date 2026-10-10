# LocalDrop

Cable-free, Internet-independent, local-network file transfer
(Android ↔ Windows, Android ↔ Android).

> M01 Foundation. Frozen baseline: `LocalDrop_OpenCode_Master_Spec_v1.2`.

## Hard network rule (non-negotiable)

INTERNET AVAILABLE OR NOT, TRANSFERS USE LOCAL WI-FI ONLY.
No USB, no Ethernet, no cloud, no relay, no Internet fallback.

## Repository layout (Complete Spec §4.5)

```text
android/LocalDrop/     # Kotlin + Jetpack Compose + Android SDK
windows/LocalDrop/     # C# + .NET 10 + WPF (+ LocalDrop.sln)
shared-protocol/       # protocol docs/schema only, not runtime code
docs/                  # architecture/prd/srs/protocol/security/ui-ux/testing
test-assets/
```

## Stack (frozen)

* Android: Kotlin + Jetpack Compose, foreground service (M13), SQLite/Room.
* Windows: C# + .NET 10 + WPF, `Microsoft.Extensions.Logging`,
  `Microsoft.Data.Sqlite`.
* Networking: LAN-only UDP discovery (M03) + direct TCP control (M04) +
  direct TCP data. No server in the path.
* Crypto: platform primitives only, no custom crypto (M05).

## Milestones

M01 Foundation → M02 Protocol → M03 Discovery → M04 Control → M05 Pairing →
M06 Android→Windows 100MB → M07 reverse → M08 streaming → M09 large files →
M10 resume → M11 integrity → M12 multi-session → M13 service/widgets →
M14 tray → M15 benchmark → M16 offline acceptance → M17 packaging → M18 hardening.

## Build (M01)

Android:

```bat
cd android\LocalDrop
gradlew.bat assembleDebug
```

Windows:

```bat
dotnet build windows\LocalDrop.sln -c Debug
```

## Offline acceptance (M16, not M01)

Phone Mobile Data OFF + Hotspot ON, PC joined to hotspot, Windows may show
globe/No Internet — discovery + pairing + transfers must still work locally.
