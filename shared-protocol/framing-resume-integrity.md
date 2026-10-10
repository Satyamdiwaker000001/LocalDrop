# Framing / resume / integrity — M02 contract + future pointers

## 1. Framing — M02 CONTRACT (implemented, no sockets)

Control messages travel as ONE framed unit:

```text
frame := length_prefix || json_bytes
length_prefix := 4-byte unsigned big-endian integer N  (0 < N <= 1048576)
json_bytes    := N bytes of UTF-8 encoded canonical JSON envelope
```

* `MAX_FRAME_BYTES = 1048576` (1 MiB). The control plane carries metadata
  only — never file bytes — so 1 MiB is ample and bounds attacker-controlled
  allocation. This limit is protocol surface: changing it is a versioned
  decision.
* Canonical JSON rules (identical output on Android and Windows):
  * UTF-8, no BOM, no insignificant whitespace.
  * Object keys in contract order (envelope order in `envelope.md`, payload
    order in `messages.md`). Parsers accept any key order; serializers emit
    contract order.
  * Strings: `"` → `\"`, `\` → `\\`, control chars `< 0x20` use the short
    escapes `\b \f \n \r \t` where defined else `\u00xx` (lowercase hex).
    Characters `>= 0x20` (including non-ASCII) are emitted raw in UTF-8.
    `/` is NOT escaped. Surrogates are never emitted; a lone surrogate on
    input is a parse error.
  * Numbers: JSON integers only (optional `-`, digits). No floats, no
    exponents — a float/exponent token is a parse error (`INVALID_PROTOCOL`).
    Range: signed 64-bit (`-2^63..2^63-1`); out-of-range is a parse error.
  * Literals `true`, `false` only (`null` is never produced; a `null` token
    where a value is required is a schema violation).
* Framing rules for the future TCP code (M04), defined now so M04 has no
  choice to make:
  * TCP is a stream: NEVER assume one read == one message. The M02
    incremental decoder (`feed()` bytes in, zero or more complete messages
    out) is what M04 must use; it handles partial reads/writes, multiple
    messages per read, and split length-prefixes.
  * Decoder outcomes: `NeedMoreData` (incomplete frame — keep bytes),
    `Message` (exactly N bytes consumed), `Failure(INVALID_PROTOCOL)` for:
    declared length `0`, declared length `> MAX_FRAME_BYTES` (rejected WITHOUT
    allocating N bytes), truncated JSON, invalid JSON.
  * The decoder never buffers more than `4 + MAX_FRAME_BYTES` bytes; feeding
    beyond that without completing a frame is a failure, not growth.
  * Boundary conditions (all tested): zero-length frame, exactly-max frame,
    max+1 declaration, length prefix split across feeds, JSON split mid-token,
    two frames in one feed, trailing garbage after a frame (starts the next
    decode; garbage that cannot form a frame fails on completion).

## 2. Resume — M10 POINTER (no M02 behavior)

* Receiver persists durable checkpoints; resume from last confirmed durable
  offset; validate negotiated resume position; never complete before all
  bytes + SHA-256 verification; partial files remain distinguishable from
  completed files.
* M02 contribution: `RESUME_QUERY` / `RESUME_RESPONSE` message contracts,
  `resumeOffset` / `confirmedOffset` field validation, 8 MiB default
  checkpoint constant. No persistence, no retransmission, no negotiation
  engine in M02.

## 3. Integrity — M11 POINTER (no M02 behavior)

* SHA-256 mandatory. Mismatch = integrity failure, never marked completed.
* M02 contribution: `sha256Hex` field shape rule (64 lowercase hex chars),
  `TRANSFER_COMPLETE` / `VERIFY_REQUEST` / `VERIFY_RESULT` contracts,
  `CHECKSUM_MISMATCH` code. No hashing runs in M02 (and therefore no
  `java.security.MessageDigest` / `SHA256` usage exists in M02 sources).

## 4. Chunking reminder

8 MiB is the initial LOGICAL checkpoint unit for resume/progress accounting,
NOT one TCP packet per chunk. Tunable after benchmarking (M15).
