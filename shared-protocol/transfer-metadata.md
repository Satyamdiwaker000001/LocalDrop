# Transfer metadata — M02 contract (PROTOCOL v1.0 §7)

No transfer BEHAVIOR in M02 (no file I/O, no streaming, no checkpoints, no
state machine — M06+/M08/M10). This is the validated data contract.

## Identity fields

* Transfer ID (UUID) — `transferId`.
* Sender / receiver device IDs (UUID) — `senderDeviceId`, `receiverDeviceId`.
* File ID (stable transfer-file identifier, opaque, 1..128 chars) — `fileId`.
* File name (base name only, 1..255 chars) — `fileName`.
* File size (integer bytes, `0..2^63-1`) — `fileSize`. Never used for
  allocation in M02.
* Logical checkpoint size (default 8 MiB = `8388608`, configurable;
  when present `1..2^30`) — `checkpointSize`.
* Whole-file SHA-256 (64 lowercase hex chars) when known; omitted when not
  yet computed — `sha256Hex`.
* Resume offset / checkpoint (integer bytes, `0..fileSize`) — `resumeOffset`
  (request) / `confirmedOffset` (response).
* Protocol version (integer `1`) — carried by the envelope.

## Field validation rules (both platforms, tested)

* UUID fields: valid UUID syntax, canonical lowercase form on output.
* `fileName`: `1..255` chars; MUST NOT contain `/` or `\`; MUST NOT be `.`
  or `..` (path-traversal defense at the contract boundary; enforcement of
  safe destinations is M06+).
* `fileSize`: integer `0..9223372036854775807`. Negative or non-integer
  rejected. 10 GB+ values are valid inputs (first-class, Complete Spec
  NR-14); no memory is allocated from this value.
* `checkpointSize`: when present, integer `1..1073741824`; omitted means
  the default `8388608`.
* `sha256Hex`: when present, exactly 64 characters, each `0-9a-f`
  (lowercase only; uppercase rejected — canonical form).
* `resumeOffset` / `confirmedOffset`: integer `>= 0`; `resumeOffset` must
  additionally satisfy `resumeOffset <= fileSize` within `TRANSFER_REQUEST`.

## State vocabularies (values only — NO transition engine in M02)

Transfer states (UI/UX Spec §13; mirrored in `AppConfig` on both platforms):

```text
available, connecting, waiting acceptance, transferring, paused,
reconnecting, verifying, completed, failed, cancelled
```

Session states (PROTOCOL v1.0 §4):

```text
DISCOVERED, CONNECTING, NEGOTIATING, AUTHENTICATING, READY,
TRANSFER_REQUESTED, ACCEPTED, TRANSFERRING, VERIFYING, COMPLETED,
REJECTED, CANCELLED, FAILED, RECONNECTING, RESUMING
```

Both vocabularies are implemented as closed, validated enumerations with
exact string forms. Parsing rejects any other value. No timers, no workers,
no transition tables in M02 — later milestones own the engine; M02 owns the
words.
