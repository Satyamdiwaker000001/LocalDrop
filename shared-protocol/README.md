# shared-protocol — LocalDrop M02 contract (source of truth)

> M02 protocol foundation. This folder is the conceptual source of truth for
> the ONE LocalDrop protocol contract (PROTOCOL v1.0).
> Android Kotlin (`com.localdrop.protocol`) and Windows C#
> (`LocalDrop.Protocol`) are two representations of the SAME contract.
> If a protocol rule changes, update these docs first, then the implementations.
>
> M02 defines the language only. No sockets, no discovery behavior, no
> pairing/authentication behavior, no transfer behavior live here.

## Baseline

* `protocol`: constant string `"localdrop"`.
* `protocolVersion`: integer, value `1`.
* Protocol version, application version, and document version are separate
  concepts (Complete Spec §24). The version on the wire is validated by every
  receiver; it is never inferred from the app version.

## Contents

* `protocol-version.md` — versioning rule (M02: explicit + validated).
* `envelope.md` — common message envelope: fields, meaning, required/optional,
  correlation rules, compatibility rules.
* `messages.md` — exact message-type vocabulary (PROTOCOL v1.0 §5) and the
  payload schema contract per type (structure only, no behavior).
* `errors.md` — structured error model: the 12 authoritative codes.
* `framing-resume-integrity.md` — M02 framing contract; resume/integrity
  remain future-milestone pointers (M10/M11).
* `discovery-record.md` — DiscoveryRecord field boundary + validation rules.
* `transfer-metadata.md` — transfer identity fields, validation rules, and the
  state vocabularies (protocol values only, no state machine in M02).
* `vectors.md` — deterministic cross-platform test vectors.

## Frozen rules repeated here for implementers

* Transport (future milestones): LAN-only UDP discovery, direct TCP control,
  direct TCP data. No server, cloud, relay, or Internet endpoint in the path.
* One TCP data stream initially; parallel streams require benchmark evidence.
* No manual IP entry in normal UX.
* No custom cryptography (SECURITY v1.0 §4). M02 performs NO authentication,
  pairing, encryption, or key exchange — it only defines message structures.
* Logical checkpoint default: 8 MiB, configurable (Master Spec §7).
* SHA-256 mandatory before `completed` (enforced M11, represented M02).
* Exact production port values: TBD — centralized in one
  protocol/configuration module per platform (Complete Spec §5.5).
  M02 MUST NOT invent or scatter magic port numbers.

## M02 vocabulary reconciliation (decision log)

PROTOCOL v1.0 §5 and `messages.md` agree on the 20 wire message types; the
Complete Spec §6.2 list differs in a few names (`PAIR_CHALLENGE`,
`PAIR_CONFIRM`, `TRANSFER_INFO`, `RESUME_REQUEST`, `STATUS`, `TRANSFER_FAILED`).
Decision: PROTOCOL v1.0 is the wire-level authority, so M02 implements the
PROTOCOL v1.0 §5 vocabulary exactly (`RESUME_QUERY` / `RESUME_RESPONSE`, no
`STATUS`, no `TRANSFER_FAILED`; failures are reported with `ERROR`).
If a future milestone needs the §6.2 names, that is a versioned protocol
decision, not a silent addition.
