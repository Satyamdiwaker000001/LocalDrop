# Message envelope — M02 contract

One envelope wraps every LocalDrop control message. The envelope carries
routing/identity/correlation; the `payload` carries the per-type fields
defined in `messages.md`.

## Wire field order (canonical serialization)

Serializers MUST emit keys in this exact order; optional fields are omitted
(not `null`) when absent:

```text
protocol, protocolVersion, type, messageId,
correlationId?, sessionId?, transferId?, payload
```

## Fields

| Field           | Required | Type     | Meaning / purpose                                         |
|-----------------|----------|----------|-----------------------------------------------------------|
| `protocol`      | yes      | string   | Must be exactly `"localdrop"`. Identifies the protocol family. |
| `protocolVersion` | yes    | integer  | Must be `1`. Wire version, independent of app version.    |
| `type`          | yes      | string   | One of the 20 vocabulary values in `messages.md`.         |
| `messageId`     | yes      | string   | UUID v4 (canonical lowercase form) identifying THIS message. |
| `correlationId` | no       | string   | UUID of the message this message answers. Present on responses; equals the request's `messageId`. Enables request/response matching without any transport state. |
| `sessionId`     | no       | string   | UUID identifying the control session this message belongs to (session management itself is M04; M02 only reserves and validates the field). |
| `transferId`    | no       | string   | UUID identifying the transfer this message belongs to. Must equal the transfer UUID inside transfer payloads when both are present. |
| `payload`       | yes      | object   | Per-type fields per `messages.md`. Always an object (possibly empty `{}` for types with no fields — none in v1; every type has at least an empty-able object). |

No other envelope fields exist in v1. Every field has the purpose above;
fields were not added speculatively.

## Message IDs / correlation semantics

* Senders generate a fresh random UUID v4 per outbound message (`messageId`).
* A response sets `correlationId` to the answered message's `messageId`.
  Example: `TRANSFER_ACCEPT.correlationId == TRANSFER_REQUEST.messageId`.
* IDs are opaque: receivers MUST NOT infer ordering, timing, or identity from
  them. They are validated for shape (UUID syntax) only.
* For deterministic tests, codecs accept caller-supplied IDs; production code
  generates random UUIDs. Test vectors use fixed UUIDs (see `vectors.md`).

## Validation (both platforms, tested)

Reject with `INVALID_PROTOCOL` (malformed envelope) — never throw, never
accept silently:

1. input is not valid JSON, or the top level is not an object;
2. `protocol` missing / not a string / not `"localdrop"`;
3. `protocolVersion` missing / not an integer / not `1`;
4. `type` missing / not a string / not in the 20-type vocabulary;
5. `messageId` missing / not a string / not valid UUID syntax;
6. `correlationId` / `sessionId` / `transferId` present but not valid UUID
   syntax;
7. `payload` missing / not an object;
8. per-type payload schema violation (see `messages.md`).

Unknown (non-envelope) JSON fields are IGNORED (forward compatibility), except
an unknown `type` is always rejected. This is the only silent-tolerance rule,
and it is explicit.

## Compatibility rules

* Since only v1 exists, `protocolVersion` is always `1` after validation.
* Optional ID fields: a v1 receiver accepts messages with or without them;
  when present they must be well-formed UUIDs.
* Adding a NEW optional envelope field in the future requires a protocol
  version decision; v1 implementations ignore unknown fields, so a new
  optional field is receivable but never produced by v1 senders.
