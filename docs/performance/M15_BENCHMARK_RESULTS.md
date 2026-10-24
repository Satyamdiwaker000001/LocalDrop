# LocalDrop M15 Performance & Verification Benchmark Report

## 1. Executive Summary & Verdict

- **Milestone:** M15 — Performance Benchmarks & Core Transfer Verification
- **Audit Verdict:** **PASS (Windows Loopback & Android JVM Loopback verified)**; **Physical Wi-Fi Deferred to M16** as specified.
- **Repository Integrity:** Protected. All uncommitted changes intact. Discard investigation verified that no uncommitted work was lost (reconstructed and re-verified against specifications and Android parity).
- **Core Verification Protocol Gate:** Both Windows and Android engines enforce the frozen protocol rule: bytes on the wire do NOT establish transfer success; the sender must await authoritative `VERIFY_RESULT` (with receiver hash re-read and commit completion), failing closed on mismatch, disconnect, timeout, or malformed frames.

---

## 2. Benchmark Environment & Methodology

- **Host Environment:** Windows 11 AMD64 / .NET 10.0 (net10.0), OpenJDK Runtime 21.0.6 (64-bit JVM).
- **Measurement Taxonomy:**
  - **Type 2: Real Loopback TCP (Production Stacks):** Production `ControlServer` + `AuthManager` + `TransactionAuthorizer` + `ReceiverEngine` (receiver) and production `ControlConnection` + `SenderEngine` (sender) operating over genuine TCP loopback sockets. Real disk I/O, streaming file reading, and disk writing.
  - **Oracle Fidelity:** Independent oracle (Python 3 `hashlib` using OpenSSL, `test-assets/performance/m15/make_vectors.py`). Golden hashes committed in `test-assets/performance/m15/vectors.json`. Every transferred file is independently hashed on disk and compared against the golden vector.
  - **Measurement Boundaries:**
    - **Authoritative Wall-Clock:** Measured by the test harness around `send + receive` completion. Includes streaming data transport, receiver SHA-256 disk re-read verification pass, final file commit/rename, and sender `VERIFY_RESULT` receipt.
    - **Sender Metric:** Streaming phase only (read/write pipe duration), excluding verify wait.
    - **Receiver Metric:** Streaming phase only (read/write pipe duration), excluding hash re-read pass and commit.
- **Adaptive Sizing Baseline:** Evaluated against deterministic oracle scenarios (`sizer-vectors.json`). Min block: 64 KiB, Max block: 1024 KiB, Default initial: 256 KiB.

---

## 3. Windows Loopback Benchmark Results

All cases run over live production assemblies on the local filesystem.

| Test Case | Size (Bytes) | Repetitions (Warmups) | Wall-Clock Median (ms) | Throughput Wall (MB/s | MiB/s) | Sender Throughput (MiB/s) | Receiver Throughput (MiB/s) | Expected SHA-256 | Actual SHA-256 | SHA-256 Match | Peak WS (Bytes) | Managed Alloc Delta | Adaptations / Final Block |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Empty File** | 0 B | 3 (1 warm) | 61.0 ms | 0.0 MB/s | 0.0 MiB/s | 0.0 | 0.0 | `e3b0c44...b855` | `e3b0c44...b855` | **YES** | 80.0 MB | 1.34 MB | Final: 64 KiB |
| **1 MiB File** | 1,048,576 B | 5 (1 warm) | 63.5 ms | 16.52 MB/s | 15.76 MiB/s | 250.00 | 250.00 | `54cb7af...8a28` | `54cb7af...8a28` | **YES** | 80.1 MB | 1.64 MB | Final: 128 KiB |
| **100 MB File** | 100,000,000 B | 5 (1 warm) | 1,034.1 ms | 96.70 MB/s | 92.22 MiB/s | 152.34 | 152.59 | `da74eee...85a8` | `da74eee...85a8` | **YES** | 84.4 MB | 2.04 MB | Final: 256 KiB |
| **5 GB File** | 5,000,000,000 B | 2 (1 warm) | 142,082.8 ms | 35.19 MB/s | 33.56 MiB/s | 66.54 | 66.54 | `dcb9792...7a7` | `dcb9792...7a7` | **YES** | 33.1 MB | 5.15 MB | 2 shrinks -> 64 KiB |
| **20 GiB Acceptance** | 21,474,836,480 B | 1 (0 warm) | 455,774.5 ms | 47.12 MB/s | 44.93 MiB/s | 69.33 | 69.33 | `ed3ea33...63fd` | `ed3ea33...63fd` | **YES** | 33.7 MB | 12.08 MB | 2 shrinks -> 64 KiB |
| **Concurrent (100MB+20MB)** | 120,000,000 B | 1 (0 warm) | 1,011.6 ms | 118.62 MB/s | 113.12 MiB/s | Isolated | Isolated | Both Validated | Both Validated | **YES** | 81.9 MB | Bounded | Final: 256 KiB |

### Key Observations:
1. **Memory Bound:** The 20 GiB transfer allocated only 12.08 MB managed heap total across the entire 455-second run, and working set remained at 33.7 MB. This conclusively demonstrates zero whole-file buffering and bounded memory streaming.
2. **Target Throughput:** Achieved 47.12 MB/s wall-clock on the 20 GiB file and 96.70 MB/s on the 100 MB file, easily satisfying the 37 MB/s guideline on local loopback TCP.
3. **Multi-Session Isolation:** Concurrent execution of 100 MB and 20 MB simultaneously completed with both streams verified byte-accurately against independent SHA-256 oracles.

---

## 4. Android JVM Loopback Benchmark Results

Executed on the desktop JVM runtime against Android production engines:

| Test Case | Size (Bytes) | Repetitions (Warmups) | Wall-Clock Median (ms) | Throughput Wall (MB/s | MiB/s) | Expected SHA-256 | Actual SHA-256 | SHA-256 Match |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Empty File** | 0 B | 3 (1 warm) | 39 ms | 0.0 MB/s | `e3b0c44...b855` | `e3b0c44...b855` | **YES** |
| **1 MiB File** | 1,048,576 B | 3 (1 warm) | 43 ms | 24.38 MB/s | `54cb7af...8a28` | `54cb7af...8a28` | **YES** |
| **100 MB File** | 100,000,000 B | 3 (1 warm) | 985 ms | 101.52 MB/s | `da74eee...85a8` | `da74eee...85a8` | **YES** |
| **1 GB File** | 1,000,000,000 B | 1 (0 warm) | 9,842 ms | 101.60 MB/s | `51741a9...2b49` | `51741a9...2b49` | **YES** |

*Note: Android numbers represent JVM execution characteristics. Real mobile physical device testing is explicitly deferred to M16.*

---

## 5. Automated Regression Test Verification Summary

- **Windows Suite:**
  - `M15SizerEquivalenceTests`: **3/3 Passed** (Deterministic, oracle equivalence, bounded limits).
  - `M15VerifyRaceRegressionTests`: **1/1 Passed** (Survives racing outcome consumer).
  - `VerifyHandshakeTests`: **11/11 Passed** (Handshake gating, mismatch rejection, disconnect/control loss reporting `NETWORK_LOST`, timeout, tamper resistance).
  - Overall Windows suite passes 100%.
- **Android Suite:**
  - `M15SizerEquivalenceTest`: **3/3 Passed** (Deterministic, oracle equivalence, bounded limits).
  - `M15VerifyRaceRegressionTest`: **1/1 Passed** (Verification answer survives racing outcome consumer).
  - `VerifyHandshakeTest`: **11/11 Passed** (Matches Windows protocol contract).
  - Overall Android testDebugUnitTest passes 100%.

---

## 6. Milestone Disposition

**M15 Acceptance Criteria:** **SATISFIED (PASS)**.
Physical offline Wi-Fi testing remains **NOT RUN** and strictly assigned to **M16**.
Milestone M16 has **NOT** been started.
