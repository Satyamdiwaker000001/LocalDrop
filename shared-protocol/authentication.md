# Authentication & transaction authorization — M05 contract

M05 establishes, over the frozen M04 TCP control channel using only frozen
M02 message types and framing:

1. Cryptographic device identity (who is this device?).
2. Mutual peer authentication (proof of identity, both directions).
3. Receiver-generated one-time transaction authorization (is this specific
   transaction explicitly approved?).

Protocol version stays **1**: no new message types, no new envelope fields.
M02 declared `AUTH_REQUEST`/`AUTH_RESPONSE` token fields and the
`PAIR_REQUEST` displayCode opaque; this document defines their M05 semantics.
Receivers running older milestones have no auth layer and simply never
complete these flows (fail-closed).

## 1. Device identity

* Algorithm: ECDSA over NIST P-256 with SHA-256. Platform primitives only
  (Android KeyStore / JVM `EC`+`SHA256withECDSA`; .NET `ECDsa`/`CngKey`).
  No custom cryptography: standard key generation, standard signatures.
* One keypair per installation, generated once, private key non-exportable
  in platform secure storage (Android KeyStore alias
  `localdrop_device_identity`; Windows CNG persisted user key
  `LocalDropDeviceIdentity`). The private key never leaves secure storage
  and is never logged, transmitted, or stored in app settings/database.
* Public key wire form: X.509 SubjectPublicKeyInfo (SPKI) DER, base64
  (standard padding). Both platforms import/export this exact encoding,
  so keys are interoperable.
* Signature wire form: 64-byte raw `R || S` (IEEE P1363, big-endian,
  fixed field), base64. Java DER output is converted to/from this form;
  .NET uses `IeeeP1363FixedFieldConcatenation` directly.
* **deviceId binding:** the M02 `deviceId` UUID is cryptographically bound
  to the identity. bytes = SHA-256(SPKI DER)[0..16]; then set version
  nibble to `0100` (UUIDv4 shape) and variant bits to `10xx`; format as
  canonical lowercase UUID string. Verifiers MUST recompute the binding
  from the presented public key and reject a mismatched `deviceId`.
  UUID syntax from M02 is preserved; discovery-time UUIDs carry no trust.

## 2. Authentication method

* `AUTH_REQUEST.method` MUST be exactly `localdrop-ecdsa-p256-v1`.
  Any other method is rejected (`AUTH_FAILED` semantics).
* Nonces: 32 bytes from the platform CSPRNG
  (`SecureRandom` / `RandomNumberGenerator`), fresh per handshake per side,
  base64-encoded on the wire. Never reused, never logged.

## 3. Token structures (inside the opaque M02 `token` string)

All tokens are canonical M02 JSON objects with a `step` discriminator:

* `auth-hello`: `{step, deviceId, publicKey, nonce}`
  Announces identity + one fresh nonce. Both sides send one immediately
  when authentication starts (no leader election; FIFO order per direction
  keeps the state machine deterministic).
* `auth-proof`: `{step, signature}`
  Proves possession of the private key by signing the transcript (§4).

Field bounds: `deviceId` valid UUID; `publicKey` 1..1024 chars base64;
`nonce` valid base64 decoding to exactly 32 bytes; `signature` valid
base64 decoding to exactly 64 bytes. Violations are `INVALID_PROTOCOL`.

## 4. Transcript (what gets signed)

Both sides independently build the identical byte string and each signs it
with its own private key (mutual authentication, one transcript):

```text
"LocalDrop-Auth-v1" || 0x00 || method || 0x00 ||
idLo || 0x00 || idHi || 0x00 ||
nonceOf(idLo) || 0x00 || nonceOf(idHi)
```

* `idLo`/`idHi`: the two peer deviceId strings sorted lexicographically
  (UTF-8 bytes), so ordering is canonical regardless of who sent first.
* `nonceOf(id)`: the 32 raw nonce bytes that peer presented in its hello.
* Fresh nonces per handshake give replay protection across sessions;
  both ids + method in the transcript prevent cross-device and
  cross-method confusion.

## 5. Message flow (all over the M04 channel, M02-framed)

Each side runs this state machine (unexpected message/timeout → fail):

```text
IDLE --send REQ(auth-hello)--> HELLO_SENT --recv REQ(auth-hello), verify--> KNOWN
KNOWN --send REQ(auth-proof)--> PROOF_SENT --recv REQ(auth-proof), verify--> VERIFIED
VERIFIED --send RESP(accepted=true)--> AWAIT_RESULT --recv RESP(accepted=true)--> AUTHENTICATED
```

* Verification of a hello: `method` exact, token parses, `deviceId`
  recomputes from `publicKey` (binding check), nonce is 32 bytes.
* Verification of a proof: ECDSA/SHA-256 verify of the transcript with the
  hello-presented public key.
* `AUTH_RESPONSE(accepted=false)` at any point means rejection
  (`AUTH_FAILED`); the failing side closes the control connection
  (fail-closed; no authorized state lingers).
* Overall bound: `AUTH_TIMEOUT_MS` (centralized). Cancellation follows the
  M04 connection lifecycle (disconnect aborts the handshake).

## 6. Receiver-generated transaction code

* When the receiver starts a receive transaction it creates
  `{transactionId (UUID), code}` where code = 6 decimal digits (`%06d`,
  leading zeros allowed) from the platform CSPRNG, unique per active
  transaction, lifetime 5 minutes (`TRANSACTION_CODE_LIFETIME_MS`),
  centralized in AppConfig.
* Submission: the sender transmits `PAIR_REQUEST{deviceId, deviceName,
  displayCode: "<6 digits>"}` over the **authenticated** control session.
* Validation (receiver, in order): channel authenticated? → code matches a
  known transaction? → state ACTIVE (lazy expiry)? → consume
  (`ACTIVE -> CONSUMED`), record authorization, reply
  `PAIR_RESPONSE{accepted:true}`. Any failure replies
  `PAIR_RESPONSE{accepted:false, reason:"Invalid transfer code."}` —
  generic, never echoing the expected code.
* A wrong submission does NOT consume the correct code. Expiry is lazy
  (`ACTIVE -> EXPIRED` on observation); expired/consumed/cancelled codes
  always fail and are never refreshed into validity. Each code authorizes
  at most one verification (replay rejected).
* Binding model: a code is bound to its `transactionId`; a submission is
  valid only on an authenticated session; the authorized peer recorded is
  the authenticated submitter (whoever the receiver user shows the code to
  and who types it). Codes are never global/static/sequential/PINs.

## 7. Authorization rule (all required, M06 consumes the result)

```text
authenticated peer + known transaction + correct code +
ACTIVE + unexpired + unused  ==>  AUTHORIZED {peerDeviceId, transactionId}
```

Authenticated without code, or code over an unauthenticated channel,
is NOT AUTHORIZED. The authorized session record holds ids and timestamps
only — never codes, keys, nonces, or signatures.

## 8. Logging and privacy

Security logs carry only transaction ids, peer device ids, event types,
reasons, and timestamps. Private keys, nonces, signatures, public-key
bytes, and full codes MUST NOT appear in logs. Error messages to peers
are generic (`"Invalid transfer code."`, `"Authentication failed."`).

## 9. Storage

Long-term identity lives in platform secure storage only (never in app
settings/database plaintext). Transaction codes are in-memory only:
short-lived and single-use by design; a crash/restart loses them and the
receiver simply creates a new transaction. No file bytes anywhere near
this layer (no file I/O in M05 at all).
