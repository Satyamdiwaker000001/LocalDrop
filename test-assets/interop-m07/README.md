# M07 cross-runtime interop harness

`interop7.ps1` runs the real Windows (CLR) sender against the real Android
(JVM) receiver over loopback; `ReceiverInterop.java` hosts the production
JVM receiver stack (M04 control + M05 auth/authorizer + M07 `ReceiverEngine`).

This harness is a MANUAL test asset (like the M06 temp harness it replaces
in committed form): it needs JDK 21, Python 3, .NET 10, and a prior Android
unit-test build. It is NOT part of automated suites — `SenderInteropTests`
passes vacuously without `LOCALDROP_INTEROP=1`.

See `interop7.ps1` header for usage. Loopback only; not a substitute for M16
physical-device Wi-Fi testing. Verified evidence (2026-10-09, loopback):
100 MB MATCH (~245 MB/s receiver-side), 1 GB MATCH (~196 MB/s).
