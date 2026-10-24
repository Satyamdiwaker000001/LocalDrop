#!/usr/bin/env python3
"""M15 independent SHA-256 oracle + deterministic source generator.

This script is the INDEPENDENT correctness oracle for the M15 benchmark
matrix. It is deliberately written in a different language and with a
different implementation than the .NET / Kotlin harnesses so that a hash
mismatch cannot be caused by a shared bug:

  * Oracle        : Python `hashlib` (OpenSSL) over the generated content.
  * Implementation: .NET `System.Security.Cryptography` / Java
                    `MessageDigest` over the bytes actually received.

Pattern spec ("m15-pattern-v1"), identical in both implementations:

    CHUNK  = 1 MiB = 1048576 bytes
    CYCLE[i] = byte( ((i * 1103515245 + 12345) mod 2**32) >> 16 )
               for i in [0, CHUNK)
    TAG(k)   = [ k & 0xFF, (k >> 8) & 0xFF, 0x5A ^ (k & 0xFF),
                 0xA5 ^ ((k >> 8) & 0xFF) ]
    byte(o)  = CYCLE[o mod CHUNK] ^ TAG(floor(o / CHUNK))[(o mod CHUNK) & 3]

The 4-byte chunk tag makes the content period 64 GiB (65536 chunks), so a
write-offset error of ANY size (including a multiple of the 1 MiB chunk)
changes the content and therefore the SHA-256. The content is generated
incrementally; the full file is never held in memory.

Usage:
    python make_vectors.py --outdir <dir> --vectors <vectors.json> [--sizes 0,1048576,...]
                            [--no-files]

Outputs:
    <vectors.json>        canonical golden vectors (committed)
    <outdir>/m15_src_<bytes>.bin   deterministic source files (not committed)
"""

import argparse
import hashlib
import json
import os
import sys

CHUNK = 1 << 20
LCG_MUL = 1103515245
LCG_INC = 12345

DEFAULT_SIZES = [
    0,
    1 << 20,                 # 1 MiB
    100_000_000,             # 100 MB
    5_000_000_000,           # 5 GB  (> 4 GiB)
    21_474_836_480,          # 20 GiB (M09/M15 acceptance size)
]


def build_cycle():
    """1 MiB deterministic cycle (LCG high bits)."""
    import numpy as np

    idx = np.arange(CHUNK, dtype=np.uint64)
    vals = ((idx * LCG_MUL + LCG_INC) & 0xFFFFFFFF).astype(np.uint32)
    return (vals >> 16).astype(np.uint8)


def tag_for(chunk_index):
    k = chunk_index & 0xFFFF
    return bytes([k & 0xFF, (k >> 8) & 0xFF, 0x5A ^ (k & 0xFF), 0xA5 ^ ((k >> 8) & 0xFF)])


def content_chunks(size):
    """Yield successive content chunks (bytes) for `size` bytes.

    The per-chunk tag is applied with a big-integer XOR, which keeps the
    oracle fast enough for the 20 GiB acceptance size without ever holding
    the whole file in memory.
    """
    cycle_bytes = build_cycle().tobytes()
    remaining = size
    k = 0
    while remaining > 0:
        n = min(CHUNK, remaining)
        tag = tag_for(k)
        tile = (tag * (n // 4 + 1))[:n]
        cycle_int = int.from_bytes(cycle_bytes[:n], "big")
        chunk = (cycle_int ^ int.from_bytes(tile, "big")).to_bytes(n, "big")
        yield chunk
        remaining -= n
        k += 1


def hash_content(size):
    """SHA-256 of the generated content, computed without writing it to disk."""
    h = hashlib.sha256()
    for chunk in content_chunks(size):
        h.update(chunk)
    return h.hexdigest()


def write_content(path, size):
    """Write the generated content to `path` and return its SHA-256."""
    h = hashlib.sha256()
    with open(path, "wb") as fh:
        for chunk in content_chunks(size):
            h.update(chunk)
            fh.write(chunk)
    return h.hexdigest()


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--outdir", required=True, help="directory for generated source files")
    ap.add_argument("--vectors", required=True, help="path of the vectors.json to write")
    ap.add_argument("--sizes", default=None, help="comma separated sizes")
    ap.add_argument("--no-files", action="store_true", help="only compute hashes, write no files")
    args = ap.parse_args(argv)

    sizes = DEFAULT_SIZES
    if args.sizes:
        sizes = [int(s) for s in args.sizes.split(",") if s.strip()]

    os.makedirs(args.outdir, exist_ok=True)

    entries = []
    for size in sizes:
        if args.no_files:
            digest = hash_content(size)
        else:
            path = os.path.join(args.outdir, "m15_src_%d.bin" % size)
            digest = write_content(path, size)
            print("[m15] wrote %s (%d bytes) sha256=%s" % (path, size, digest), flush=True)
        entries.append({"bytes": size, "sha256": digest})
        print("[m15] size=%d sha256=%s" % (size, digest), flush=True)

    doc = {
        "spec": "m15-pattern-v1",
        "chunkBytes": CHUNK,
        "cycleLcgMultiplier": LCG_MUL,
        "cycleLcgIncrement": LCG_INC,
        "oracle": "python3 hashlib (OpenSSL)",
        "sizes": entries,
    }
    with open(args.vectors, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=2)
        fh.write("\n")
    print("[m15] vectors written to %s" % args.vectors)
    return 0


if __name__ == "__main__":
    sys.exit(main())
