# Data transfer plane — M06 contract (Android → Windows)

M06 adds NO wire protocol changes: every control message below is a frozen
M02 type validated by the frozen M02 codec. This document records the M06
data-plane conventions (transport behavior, not message schemas) so both
platforms implement the same flow. The full engineering rationale lives in
`docs/architecture/m06-transfer-decisions.md` (20 ADRs).

## Transports and ports

* Discovery stays UDP (`DISCOVERY_UDP_PORT` 48423, M03 frozen).
* Control stays TCP (`CONTROL_TCP_PORT` 48424, M04 frozen).
* Bulk bytes use a dedicated TCP data connection to `DATA_TCP_PORT`
  (48425), centralized in AppConfig both sides. One stream per transfer;
  no file bytes ever travel on the control connection.

## Transfer flow (control plane, all frozen M02 types)

1. Preconditions (M04/M05, unchanged): control connected, mutual auth
   complete, receiver transaction authorized (code consumed).
2. Sender → receiver: `TRANSFER_REQUEST` (fresh `transferId`/`fileId`
   UUIDs; `sha256Hex` omitted per M02 "not yet computed";
   `checkpointSize` explicit default 8 MiB; `resumeOffset` 0).
   Envelope `transferId` equals payload `transferId`.
3. Receiver validates in order — authenticated channel (`PAIRING_REQUIRED`
   otherwise), authorized M05 session for the peer (`TRANSFER_REJECTED`
   otherwise), addressed to self, `resumeOffset == 0` (M10 owns resume),
   file-name shape, receiver idle (second concurrent request →
   `TRANSFER_REJECTED`; M12 owns orchestration), advisory free-space
   check (`NOT_ENOUGH_STORAGE`) — then replies `TRANSFER_ACCEPT`
   (`resumeOffset` 0) and arms the data wait.
4. Sender dials the data port at the control-derived host, writes the
   preamble, sends `TRANSFER_START`, streams bytes, emits throttled
   `TRANSFER_PROGRESS`, then `TRANSFER_COMPLETE`, then closes data.
5. Receiver streams socket → temp file; on byte-count match waits for
   `TRANSFER_COMPLETE` (bounded) and finalizes; any failure deletes temp.

## Data-plane preamble (M06 data transport, not M02 framing)

* First bytes on the data connection: 36-char lowercase transfer UUID +
  `\n` (37 bytes ASCII). The receiver reads to `\n` capped at 64 bytes;
  anything else (unknown id, overlong, EOF) closes the socket with no
  file touched. This binds the anonymous data socket to the authorized
  control-plane transfer; it replaces M02 length-prefix framing here
  because the payload IS the file stream (length known from metadata,
  delimited by byte count + control commit).
* After the preamble the stream is raw file bytes, nothing else. No
  per-block headers: application blocks are deliberately NOT TCP packets
  (TCP segmentation is the OS's job).

## `TRANSFER_COMPLETE.sha256Hex` in M06

* The frozen schema requires 64 hex chars. M06 sends 64 zeros meaning
  "hash not computed — M11 pending" (`M06_HASH_NOT_COMPUTED`).
* The receiver shape-validates and ignores the value; transport completion
  is NEVER integrity verification. M11 will send the real hash in the same
  field with no wire change; M06 receivers must be re-tested then.

## Authorization binding for M06 scope

* A transfer is accepted iff the requesting peer is authenticated AND has
  ≥1 M05 authorized session AND no transfer is already active. The single
  authorized session is inherited by the transfer. (Multi-session
  correlation, e.g. per-transaction references, is M12 scope and may
  motivate a protocol proposal then — not a blocker now.)

## Temp/finalize contract (both platforms must match behavior)

* In-progress bytes live at `<finalName>.localdrop-part` in the
  destination directory (raw prefix `[0..received)`, no header —
  adoptable by M10 resume later).
* On transport completion the temp is atomically moved to the final name;
  an occupied final name is never overwritten — first-free
  `<stem> (n)<ext>` wins. Temp is deleted on cancel/failure/timeout.
  M06 temps are explicitly non-durable (M10 will define durability).

## States and completion vocabulary (frozen M01 strings)

* `connecting` → `waiting acceptance` → `transferring` → `completed`
  (transport-complete only) with `failed` / `cancelled`. No `verifying`
  in M06 (M11). No resume states (M10).

## Compatibility notes

* Protocol version stays 1. No new types/fields; all values above use
  existing ranges. M06 senders interoperate with any v1 receiver
  implementing this same flow; older (M04/M05-only) receivers simply
  never send `TRANSFER_ACCEPT` (fail-closed on the sender timeout).

## M07 appendix — reverse direction (Windows → Android, 2026-10-09)

M07 adds NO wire protocol changes: the flow above is direction-agnostic.
The Windows M07 sender and the Android M07 receiver implement the SAME
sections 1:1 (same ports, same preamble, same control sequence, same zeros
sentinel, same temp/finalize contract, same single-transfer scope). The only
direction-specific adaptations are host-side API choices documented in
`docs/architecture/m07-transfer-decisions.md`:

* Sender endpoint derivation is symmetric: the M07 Windows sender derives
  the data host from its live control connection (with `::ffff:` mapped-form
  normalization for dual-stack listeners); the M06 Android sender does the
  same parse without normalization (JVM reports dotted quads).
* Receiver storage is symmetric for file-backed destinations (same
  `.localdrop-part` + uniquification contract); Android adds a
  `ReceiverDestination` seam so SAF/document-tree backends can plug in later
  without touching transfer logic (file behavior identical today).
* `resumeOffset` is 0 and `sha256Hex` is 64 zeros in BOTH directions;
  transport-complete is explicitly NOT verification in both directions.

Cross-runtime proof (loopback, production stacks both ends): Windows CLR
sender → JVM Android receiver, 100 MB MATCH (409 ms receiver-side,
244.5 MB/s) and 1 GB MATCH (5.2 s, 196 MB/s), stream-compared on the
receiving side. Protocol version stays 1.

## M07 verification-audit notes (2026-10-09+; no wire change)

* Finalization guarantee, stated precisely: the final name is never written
  directly. Commit = same-directory rename of the temp, or (rename failed)
  copy into a same-directory `<final>.localdrop-commit` staging file
  followed by a same-directory rename of the staging file. A crash may
  leave temp/staging files behind, but never a partial file at the final
  name. Do not describe the fallback path as "atomic"; the rename path is a
  single same-directory entry swap on ordinary filesystems.
* Completion authority: TRANSFER_COMPLETE travels on control while bytes
  travel on data, so arrival order is not guaranteed. Senders MUST hold the
  control channel briefly after emitting COMPLETE (Windows
  `TransferCommitLingerMs`) and treat their own TransportComplete as
  "bytes + commit signal delivered"; the RECEIVER's outcome is authoritative
  for completion. Tearing down control immediately can fail an otherwise
  complete transfer closed (observed live, then fixed).
* Discovery now carries dialable endpoints (peer-announced control port +
  recorded source host) so initiation needs no manual address; the endpoint
  is a dial hint only and never identity or authorization (M05 unchanged).
