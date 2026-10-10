# Message types + payload schemas — M02 contract (PROTOCOL v1.0 §5)

Exactly 20 wire message types. No other type may be produced; any other
`type` value is rejected on receipt. Defining a model here does NOT implement
the feature: e.g. `TRANSFER_REQUEST` exists as a data contract, but M02 sends
nothing over any network; pairing/authentication messages are structures only
(behavior is M04/M05).

Conventions used below for every payload field:

* `string(a..b)` — string with length between a and b characters (UTF-16 code
  units on both platforms; vectors use ASCII so units == bytes).
* `uuid` — canonical lowercase UUID string, e.g.
  `123e4567-e89b-12d3-a456-426614174000`.
* `int` — JSON integer (no floats, no exponents; see serialization rules).
* `bool` — JSON boolean.
* `X?` — optional field; omitted (never `null`) when absent.
* Payload key order is fixed as listed (canonical serialization).

## 1. Capability / hello

### `HELLO`

Announces presence/capabilities (transport binding is M03/M04; this is the
data contract only).

| Field         | Type            | Rule                                                        |
|---------------|-----------------|-------------------------------------------------------------|
| `deviceId`    | uuid            | Stable device identity.                                     |
| `deviceName`  | string(1..128)  | Human-readable name.                                        |
| `deviceType`  | string          | `"android"` or `"windows"` only.                            |
| `controlPort` | int             | `0..65535`. `0` = not yet assigned (ports TBD per Complete Spec §5.5; M02 MUST NOT invent production port values). |
| `capabilities`| array of string | 0..32 entries, each string(1..64). Opaque capability tokens; vocabulary defined when used (M03+). |

### `HELLO_ACK`

| Field        | Type           | Rule                                         |
|--------------|----------------|----------------------------------------------|
| `deviceId`   | uuid           | Responder identity.                          |
| `deviceName` | string(1..128) | Responder name.                              |
| `deviceType` | string         | `"android"` or `"windows"` only.             |
| `accepted`   | bool           | Whether the hello is accepted at protocol level. |

`HELLO_ACK` SHOULD carry `correlationId` = the `HELLO` message's `messageId`.

## 2. Pairing (structure only — approval/persistence are M05)

### `PAIR_REQUEST`

| Field         | Type            | Rule                                                        |
|---------------|-----------------|-------------------------------------------------------------|
| `deviceId`    | uuid            | Requester identity.                                         |
| `deviceName`  | string(1..128)  | Requester name.                                             |
| `displayCode` | string(1..16)   | Opaque human-verifiable value. M02 defines shape only; comparison/approval UX is M05. |

### `PAIR_RESPONSE`

| Field      | Type            | Rule                                           |
|------------|-----------------|------------------------------------------------|
| `deviceId` | uuid            | Responder identity.                            |
| `accepted` | bool            | Pairing decision (made by user/M05, not M02).  |
| `reason?`  | string(0..256)  | Optional human-readable reason when rejected.  |

## 3. Authentication (structure only — handshake/crypto are M05)

`AUTH_REQUEST` / `AUTH_RESPONSE` carry opaque fields. M02 performs NO
authentication; `method` has no M02 vocabulary (defined M05).

### `AUTH_REQUEST`

| Field      | Type           | Rule                                                     |
|------------|----------------|----------------------------------------------------------|
| `deviceId` | uuid           | Requester identity.                                      |
| `method`   | string(1..64)  | Authentication method name. Opaque in M02.               |
| `token`    | string(1..4096)| Opaque authentication token. Never logged (SECURITY v1.0 §5). |

### `AUTH_RESPONSE`

| Field      | Type           | Rule                                           |
|------------|----------------|------------------------------------------------|
| `deviceId` | uuid           | Responder identity.                            |
| `accepted` | bool           | Authentication decision (M05, not M02).        |
| `reason?`  | string(0..256) | Optional reason.                               |

## 4. Transfer request / response (contract only — no transfer in M02)

### `TRANSFER_REQUEST`

Full transfer metadata (PROTOCOL v1.0 §7; field rules in
`transfer-metadata.md`):

| Field            | Type             | Rule                                                        |
|------------------|------------------|-------------------------------------------------------------|
| `transferId`     | uuid             | Transfer identity. SHOULD equal envelope `transferId` when the envelope carries one. |
| `senderDeviceId` | uuid             | Sender identity.                                            |
| `receiverDeviceId` | uuid           | Intended receiver identity.                                 |
| `fileId`         | string(1..128)   | Stable transfer-file identifier (opaque).                   |
| `fileName`       | string(1..255)   | Base file name only: MUST NOT contain `/`, `\`, and MUST NOT be `.` or `..`. |
| `fileSize`       | int              | `0..2^63-1` bytes. Never used for allocation in M02.       |
| `checkpointSize?` | int             | Logical checkpoint unit; when present `1..2^30`. Omitted = default 8 MiB (`8388608`). |
| `sha256Hex?`     | string           | Whole-file SHA-256 as 64 lowercase hex chars when known; omitted when not yet computed (verification enforced M11). |
| `resumeOffset`   | int              | `0..fileSize`. Nonzero means "requesting resume from here" (negotiation behavior is M10). |

### `TRANSFER_ACCEPT`

| Field          | Type | Rule                                              |
|----------------|------|---------------------------------------------------|
| `transferId`   | uuid | Accepted transfer.                                |
| `resumeOffset` | int  | `>= 0`. Receiver-confirmed start offset (behavior M10; value shape validated M02). |

### `TRANSFER_REJECT`

| Field        | Type   | Rule                                |
|--------------|--------|-------------------------------------|
| `transferId` | uuid   | Rejected transfer.                  |
| `error`      | object | Structured error `{code, message}` per `errors.md`. |

## 5. Transfer progress signals (contract only — no state machine in M02)

### `TRANSFER_START`

| Field        | Type | Rule                        |
|--------------|------|-----------------------------|
| `transferId` | uuid | Starting transfer.          |
| `offset`     | int  | `>= 0`. Start byte offset.  |

### `TRANSFER_PROGRESS`

| Field              | Type | Rule                                              |
|--------------------|------|---------------------------------------------------|
| `transferId`       | uuid | Transfer being reported.                          |
| `transferredBytes` | int  | `0..totalBytes`.                                  |
| `totalBytes`       | int  | `>= 0`. Expected total (normally == `fileSize`). |

### `PAUSE` / `RESUME`

| Field        | Type | Rule               |
|--------------|------|--------------------|
| `transferId` | uuid | Target transfer.   |

### `CANCEL`

| Field        | Type            | Rule                          |
|--------------|-----------------|-------------------------------|
| `transferId` | uuid            | Target transfer.              |
| `reason?`    | string(0..256)  | Optional reason.              |

## 6. Resume negotiation (contract only — durable behavior is M10)

### `RESUME_QUERY`

| Field        | Type           | Rule                          |
|--------------|----------------|-------------------------------|
| `transferId` | uuid           | Transfer to resume.           |
| `fileId`     | string(1..128) | Identity check (must match).  |
| `fileSize`   | int            | `>= 0`. Identity check (must match; mismatch handling is M10). |

### `RESUME_RESPONSE`

| Field             | Type | Rule                                            |
|-------------------|------|-------------------------------------------------|
| `transferId`      | uuid | Transfer to resume.                             |
| `confirmedOffset` | int  | `>= 0`. Last durable offset (durability is M10; shape validated M02). |
| `fileSize`        | int  | `>= 0`. Echo for identity check.               |

## 7. Completion / verification (contract only — enforcement is M11)

### `TRANSFER_COMPLETE`

| Field        | Type   | Rule                                                        |
|--------------|--------|-------------------------------------------------------------|
| `transferId` | uuid   | Completed transfer.                                         |
| `fileSize`   | int    | `>= 0`. Total bytes sent.                                   |
| `sha256Hex`  | string | 64 lowercase hex chars. Expected whole-file hash (comparison enforced M11). |

### `VERIFY_REQUEST`

| Field        | Type   | Rule                              |
|--------------|--------|-----------------------------------|
| `transferId` | uuid   | Transfer under verification.      |
| `sha256Hex`  | string | 64 lowercase hex chars to verify against. |

### `VERIFY_RESULT`

| Field        | Type   | Rule                                                        |
|--------------|--------|-------------------------------------------------------------|
| `transferId` | uuid   | Verified transfer.                                          |
| `match`      | bool   | Whether hashes matched. `false` = integrity failure (M11); never `completed`. |
| `sha256Hex`  | string | 64 lowercase hex chars actually computed.                   |

## 8. `ERROR`

| Field          | Type            | Rule                                                        |
|----------------|-----------------|-------------------------------------------------------------|
| `code`         | string          | Exactly one of the 12 codes in `errors.md`.                 |
| `message`      | string(0..512)  | Human-readable detail. Must not contain secrets.            |
| `refMessageId?` | uuid           | The offending message's `messageId`, when the error answers one. Prefer `correlationId` on the envelope for request/response matching; `refMessageId` mirrors it inside the payload for transports that log payloads. |

## Session state machine reference (PROTOCOL v1.0 §4, vocabulary only)

```text
DISCOVERED -> CONNECTING -> NEGOTIATING -> AUTHENTICATING -> READY
  -> TRANSFER_REQUESTED -> ACCEPTED -> TRANSFERRING -> VERIFYING -> COMPLETED
Failure/recovery: REJECTED, CANCELLED, FAILED, RECONNECTING, RESUMING.
```

M02 represents these 15 values as a validated vocabulary (see
`transfer-metadata.md`). NO transition engine, NO timers, NO networking in
M02 — the state machine is implemented by later milestones.
