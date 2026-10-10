# Protocol test vectors — v1 (M02 cross-platform contract)

Deterministic vectors proving Android and Windows agree byte-for-byte.
Files contain canonical JSON with NO trailing newline (exact bytes matter).

| File                   | Bytes | Frame prefix (4-byte BE) | Covers                                    |
|------------------------|-------|--------------------------|-------------------------------------------|
| `hello.json`           | 262   | `00 00 01 06`            | HELLO, DiscoveryRecord, no optional IDs   |
| `transfer-request.json`| 590   | `00 00 02 4E`            | TRANSFER_REQUEST, session+transfer IDs, 10 GiB `fileSize`, sha256 |
| `transfer-accept.json` | 359   | `00 00 01 67`            | TRANSFER_ACCEPT, `correlationId` echoes request `messageId` |
| `error.json`           | 192   | `00 00 00 C0`            | ERROR with `TRANSFER_REJECTED`            |
| `pause.json`           | 226   | `00 00 00 E2`            | PAUSE minimal envelope + transferId       |

## Rules for both test suites

1. Serializing the equivalent logical object MUST produce bytes identical to
   the file content.
2. Deserializing the file bytes MUST yield the expected logical object.
3. `Android deserialize(Windows bytes) == expected` and vice versa is proven
   by both suites using THESE files as the shared bytes (each suite embeds
   the identical literals; the files are the human-readable source of truth).
4. Framing a file's bytes MUST produce `prefix || bytes` with the prefix
   above; decoding must recover the exact bytes.

## Fixed identities used across vectors

* Android device: `aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa` ("Pixel 8")
* Windows device: `cccccccc-cccc-4ccc-8ccc-cccccccccccc`
* Session: `22222222-2222-4222-8222-222222222222`
* Transfer: `bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb`
* Request message: `33333333-3333-4333-8333-333333333333`
  (the accept vector's `correlationId` echoes it)

## Negative cases (inlined in test suites, not files)

* `{"protocol":"localdrop","protocolVersion":2,...}` → reject
  (`INVALID_PROTOCOL`: bad version).
* `{"protocol":"localdrop","protocolVersion":1,"type":"NOPE",...}` → reject
  (unknown type).
* Envelope missing `payload` → reject (missing required field).
* Truncated JSON / non-JSON bytes → reject (malformed).
* `fileName` with `/`, `sha256Hex` uppercase, `fileSize` negative,
  `transferredBytes > totalBytes` → reject (invalid field value).
* Frame declaring length `0`, length `> 1048576`, or truncated body →
  decoder failure without over-allocation.
