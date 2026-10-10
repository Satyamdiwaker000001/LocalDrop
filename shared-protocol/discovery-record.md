# DiscoveryRecord — M02 contract (PROTOCOL v1.0 §3)

No discovery BEHAVIOR in M02 (no UDP, no broadcast, no scanning — M03). This
is the validated data contract M03/M04 will carry.

```text
DiscoveryRecord {
  protocol: "localdrop",            // constant
  protocolVersion: integer,          // 1
  deviceId: UUID,
  deviceName: string,               // 1..128 chars
  deviceType: "android" | "windows",
  controlPort: integer,             // 0..65535, 0 = not yet assigned
  capabilities: [string]            // 0..32 entries, each 1..64 chars
}
```

## Validation rules (both platforms, tested)

* `protocol` must be `"localdrop"`; `protocolVersion` must be `1`
  (same rule as the envelope).
* `deviceId` must be valid UUID syntax.
* `deviceName` must be `1..128` chars (empty and oversized rejected).
* `deviceType` must be exactly `"android"` or `"windows"`.
* `controlPort` must be `0..65535`. Port value `0` explicitly means
  "not yet assigned": exact production port values are TBD and centralized
  in one configuration module per platform (Complete Spec §5.5). M02 MUST NOT
  invent or scatter magic port numbers.
* `capabilities`: at most 32 entries, each `1..64` chars. Tokens are opaque
  in M02 (vocabulary defined when first used, M03+).
* A `HELLO` payload carries exactly these fields (same names, same rules).

## Frozen rules

* Bind to the active local Wi-Fi interface (M03 behavior, not M02 code).
* Never query Internet services.
* Must work with Android Mobile Data OFF + Hotspot ON.
* Same-LAN presence never equals trust (SECURITY v1.0).

## M03 transport binding (record fields unchanged)

M03 implements the announcement/lifecycle; the record above is frozen.

* Transport: UDP broadcast (Complete Spec §5.1 authorizes
  broadcast/multicast "where supported and appropriate"; M03 uses
  broadcast: no group management, works on phone-hotspot LANs).
* Port: `48423/udp`, centralized in `AppConfig` on both platforms
  (`DISCOVERY_UDP_PORT` / `DiscoveryUdpPort`). This resolves the TBD from
  Complete Spec §5.5 for discovery only; the TCP control port stays TBD.
* Datagram payload: UTF-8 canonical JSON of an M02 `HELLO` envelope
  (same bytes the M04 control channel will carry; datagram boundaries
  replace framing, so no length prefix on the wire).
* Size bound: inbound datagrams larger than `4096` bytes
  (`DISCOVERY_MAX_PACKET_BYTES`) are rejected without parsing.
* Timing (centralized, M03-chosen; the frozen spec fixes no numbers):
  announce every `2000` ms; a peer unseen for `10000` ms is expired and
  removed (tolerates ~4 lost announcements).
* Self-filter: by `deviceId`, never by IP (hotspot/NAT/multi-interface
  make IP comparison unreliable).
* Duplicates: repeat announcements for a known `deviceId` update the
  existing record and refresh expiry; never a second entry.
* Validation: full M02 envelope+payload validation applies to every
  datagram; non-`HELLO` types, bad versions, malformed/oversized/self
  packets are ignored without affecting the listener.
* No secrets on the wire: no file info, no auth material, no keys.
  Pairing/authentication is M05; trust is never inferred from discovery.
