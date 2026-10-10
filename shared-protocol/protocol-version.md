# Protocol versioning — M02 contract

* `protocol`: `"localdrop"` (constant string, case-sensitive).
* `protocolVersion`: integer, value `1`.
* Protocol version, application version, and document version are separate
  concepts (Complete Spec §24). The wire version is independent of the UI/app
  version (`0.1.0-m02` etc. must never be parsed as a protocol version).

## Rules (implemented and tested on both platforms)

1. Every outbound envelope carries `protocol` and `protocolVersion` explicitly.
2. Every inbound envelope is validated BEFORE any other processing:
   * `protocol` missing or not exactly `"localdrop"` → reject
     (`INVALID_PROTOCOL`).
   * `protocolVersion` missing, not an integer, or not equal to `1` → reject
     (`INVALID_PROTOCOL`). No silent acceptance, no fallback, no negotiation
     in M02 (negotiation itself is M04 work).
3. Version handling is available to message processing: the parsed envelope
   exposes the validated version to the caller; since only `1` is accepted,
   the value is always `1` after successful validation.
4. A protocol-breaking change requires a version bump plus explicit
   version-handling/migration decisions. Out of scope for M02.

## Compatibility rule

A receiver implementing protocol version 1:

* MUST accept messages with `protocolVersion == 1`.
* MUST reject (with `INVALID_PROTOCOL`) any other version, including higher
  versions — never attempt to interpret a newer contract.
* MUST ignore unknown JSON object fields (forward-compatibility), but MUST
  NOT accept unknown message `type` values (see `envelope.md`).
