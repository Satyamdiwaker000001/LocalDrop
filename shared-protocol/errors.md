# Error model — M02 contract (PROTOCOL v1.0 §10)

Errors are structured, deterministic, serializable values — not exceptions
thrown across peers. The `ERROR` message payload is `{code, message,
refMessageId?}` (see `messages.md` §8).

## Authoritative codes (exactly these 12; no additions in M02)

| Code                 | Meaning / when produced                                         |
|----------------------|-----------------------------------------------------------------|
| `INVALID_PROTOCOL`   | Malformed envelope, bad version, unknown type, schema violation, malformed frame. |
| `AUTH_FAILED`        | Authentication rejected (produced M05+; vocabulary only M02).  |
| `PAIRING_REQUIRED`   | Operation needs pairing first (produced M05+; vocabulary only M02). |
| `TRANSFER_REJECTED`  | Receiver declined the transfer.                                |
| `NOT_ENOUGH_STORAGE` | Destination cannot hold the file (detected M06+; vocabulary only M02). |
| `FILE_NOT_FOUND`     | Named transfer file unavailable (detected M06+; vocabulary only M02). |
| `NETWORK_LOST`       | Local path lost (detected M04+; vocabulary only M02).          |
| `RESUME_UNAVAILABLE` | No durable checkpoint to resume from (detected M10+; vocabulary only M02). |
| `CHECKSUM_MISMATCH`  | SHA-256 mismatch (detected M11+; vocabulary only M02).         |
| `CANCELLED`          | Transfer cancelled by a peer.                                   |
| `TIMEOUT`            | Operation timed out waiting (timers are M04+; vocabulary only M02). |
| `INTERNAL_ERROR`     | Receiver-side unexpected failure. Never leaks internals.        |

## Rules (implemented and tested on both platforms)

1. `code` MUST be exactly one of the 12 strings above (case-sensitive).
   Unknown codes are rejected during parsing — never invented, never passed
   through silently.
2. `message` is a human-readable string, `0..512` chars, MUST NOT contain
   secrets (tokens, keys, pairing codes — SECURITY v1.0 §5).
3. Errors are values: parsing an `ERROR` message yields a structured
   `ProtocolError(code, message)` both sides interpret identically.
4. Local validation failures (bad envelope, bad frame) map to
   `INVALID_PROTOCOL`; they do not throw across API boundaries — the codec
   returns a typed failure the caller can convert to an `ERROR` message.
5. Error codes are stable protocol surface: renaming/removing a code is a
   protocol-breaking change (see `protocol-version.md`).
