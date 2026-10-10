# M08 Streaming Performance Methodology

## A. Purpose and Scope
This document formalizes the reproducible procedure for evaluating LocalDrop's M08 adaptive streaming pipeline. Its purpose is to verify the behavior of the deterministic block sizer, bounded buffers, and TCP backpressure logic under various conditions. 

**Note:** This is strictly for the M08 formalization. It does not introduce any protocol changes or speed caps, nor does it establish an Internet dependency. This procedure is entirely distinct from the full M15 benchmark campaign (which focuses on broader bottleneck analysis).

## B. Environment
Before beginning the test, the following details must be recorded to ensure reproducibility:
- **Operating System and Runtime Versions** (e.g., Windows 11 / .NET 10, Android 14 / ART)
- **Hardware:** CPU, RAM, and relevant power profiles (e.g., battery saver vs. maximum performance).
- **Network Topology:** 
  - **Loopback:** Data exchanged within a single physical host (e.g., emulator loopback, `127.0.0.1`).
  - **Physical Wi-Fi / Hotspot:** Real physical local networks connecting distinct hardware endpoints.
  
**Critical Constraint:** Loopback test results must *never* be presented as evidence of physical Wi-Fi or hotspot throughput.

## C. Harness and Invocation
The current implementation evaluates streaming logic via the unit-test suite on a loopback connection.
- **Windows Harness:** The `.NET` xUnit test suite (`LocalDrop.Protocol.Tests.dll`).
  - *Invocation:* `dotnet test "windows\LocalDrop.Protocol.Tests\LocalDrop.Protocol.Tests.csproj" --filter "AdaptiveBlockSizer"`
- **Android Harness:** The Gradle JUnit test suite (`app/src/test`).
  - *Invocation:* `gradlew.bat testDebugUnitTest --tests "com.localdrop.transfer.AdaptiveBlockSizerTest"`

*Note:* A standalone automated performance benchmark executable covering end-to-end multi-gigabyte transfers over physical Wi-Fi does not currently exist in the repository.

## D. Test Sizes and Warm-Up
To evaluate the deterministic block sizer across its size boundaries, use the following payload sizes:
- **Small (256 KB):** Validates file-size-aware bounding (does not hit the 1 MiB max).
- **Medium (10 MB):** Validates multi-window growth and stall recovery.
- **Large (500 MB):** Validates prolonged saturation at the `TRANSFER_BLOCK_MAX_BYTES` cap.

**Warm-Up Policy:** The first transfer on a newly established process must be discarded to account for JIT compilation, class-loading overhead, and socket initialization. Record only the subsequent repetitions.

*Disclaimer:* This does not imply that 10 GB+ payload acceptance is complete; that requirement belongs to M09.

## E. Measurement Boundaries and Units
- **Start Boundary:** Upon dispatching the `TRANSFER_START` control message.
- **End Boundary:** Upon receipt of the final transport byte *and* the `TRANSFER_COMPLETE` control message.
- **Throughput Unit:** Megabytes per second (MB/s). (1 MB = 1,000,000 bytes, or MiB/s explicitly specified as 1,048,576 bytes).
- **Metrics Recorded:** Total transferred bytes, total elapsed time (ms).
- **Differentiation:** Instantaneous speed (the single-window throughput used for adaptation) must not be confused with the overall average throughput or ETA.

## F. Correctness Verification
Destination correctness is verified through byte-for-byte array equality (as currently performed in `ReceiverEngineTests.cs`). 

*Disclaimer:* The current protocol sentinel (`M07HashNotComputed` / 64 zeros) transmitted in `TRANSFER_COMPLETE` is merely a structural placeholder for M02 compliance. It is **not** an integrity verification mechanism. Real production SHA-256 verification belongs strictly to M11.

## G. Adaptive versus Fixed-Size Comparison
- **Existing Baseline:** There is currently no safe fixed-size baseline implementation inside the production code.
- **Limitation:** A production feature for locking the window size to a fixed value does not exist and will not be added merely for testing.
- **Adaptive Production Policy:** The following variables govern the adaptive stream (defined in `AppConfig`):
  - **Minimum:** 65,536 bytes (64 KiB)
  - **Maximum:** 1,048,576 bytes (1 MiB)
  - **Initial:** 262,144 bytes (256 KiB)
  - **Confirmation Windows:** 3 consecutive windows
  - **Cooldown:** 2,000 ms
  - **Growth Rule:** Requires > `1.2x` improvement ratio to re-arm.
  - **Shrink Rule:** Shrinks on sustained `< 0.5x` ratio or stalls.

## H. Results Template
Provide this blank table to record subsequent physical tests:

| Run ID | Environment / Topology | File Size | Policy | Warm-Up/Repetition | Elapsed Time | Average Throughput | Destination Correctness | Observations |
|--------|-----------------------|-----------|--------|--------------------|--------------|-------------------|-------------------------|--------------|
| | | | | | | | | |
| | | | | | | | | |
| | | | | | | | | |

## I. Interpretation and Limitations
- **Throughput Targets:** The previously noted ~37 MB/s target is a reference benchmark, not a hard speed cap or a universal guarantee.
- **Variables:** Actual throughput is heavily influenced by hardware limitations, storage I/O speeds, real-time Wi-Fi congestion, OS thread scheduling, and runtime GC pauses.
- **Topological Restrictions:** Physical hotspot acceptance cannot and must not be claimed without an actual device test over a physical Wi-Fi Direct/Hotspot topology. Loopback provides 0ms latency and infinite bandwidth, masking true network behavior.

## J. Architecture Rationale
The current architecture avoids application-level queueing. It relies strictly on a single bounded buffer (max 1 MiB) and the blocking `WriteAsync` operations. This ensures that slow writes apply direct, native backpressure to the reading side, conserving heap memory. The `AdaptiveBlockSizer` probe controller scales the block size upwards when conditions are favorable, while using a `1.2x` improvement threshold to aggressively mitigate size oscillation over noisy wireless links. This rationale is aligned with M06 architecture decisions (`m06-transfer-decisions.md`).
