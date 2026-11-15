#!/usr/bin/env python3
"""The darkroom on a DNG (fos_dng.c, the phone's RAW) against the same frame as a CR2.

mkcr2 makes a CR2 and its samples (--raw); the picture area of those samples
goes into a DNG as Android's DngCreator writes one: one CFA image, 16 bits,
uncompressed, the EOS M's colour matrix, its black and white. Both develop
with the same film; their JPEGs must be all but the same (the CR2's black is
measured from its masked columns, the DNG's is given). Then the same DNG in
the other byte order, and one whose samples are halved with a GainMap of 2
that puts them back, as a phone's lens shading does: the same picture again.

  python3 dngtest.py      (after make out/devtest out/mkcr2)
"""
import os
import struct
import subprocess
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out", "dng")
RAW_W, RAW_H, L, T, W, H = 5280, 3528, 84, 64, 5184, 3456
BLACK, WHITE = 2048, 13600
XYZ_CAM = [6602, -841, -939, -4472, 12458, 2247, -975, 2039, 6148]      # the EOS M's, /10000


def opcode_gainmap(left, right):
    """OpcodeList2 with one GainMap (opcode 9), 2 x 2 points: left at the left edge to right at the right,
    as a lens's fall-off is undone (a gain that is the same everywhere would be hidden by the darkroom's exposure)"""
    params = struct.pack(">IIIIIIII", 0, 0, H, W, 0, 1, 1, 1)
    params += struct.pack(">II", 2, 2) + struct.pack(">dddd", 1.0, 1.0, 0.0, 0.0) + struct.pack(">I", 1)
    params += struct.pack(">ffff", left, right, left, right)
    return struct.pack(">I", 1) + struct.pack(">IIII", 9, 0x01030000, 0, len(params)) + params


def write_dng(path, samples, be, opcodes=None):
    e = ">" if be else "<"
    tags = []           # (tag, type, count, bytes of the value)

    def add(tag, typ, values, fmt):
        tags.append((tag, typ, len(values), struct.pack(e + fmt * len(values), *values)))

    def srat(vals):
        out = []
        for v in vals:
            out += [v, 10000]
        return out

    pixels = samples.astype(e + "u2").tobytes()
    add(0x00FE, 4, [0], "I")
    add(0x0100, 4, [W], "I")
    add(0x0101, 4, [H], "I")
    add(0x0102, 3, [16], "H")
    add(0x0103, 3, [1], "H")
    add(0x0106, 3, [32803], "H")
    add(0x0111, 4, [0], "I")            # the strip's offset, set below
    add(0x0112, 3, [1], "H")
    add(0x0115, 3, [1], "H")
    add(0x0116, 4, [H], "I")
    add(0x0117, 4, [len(pixels)], "I")
    add(0x828D, 3, [2, 2], "H")
    tags.append((0x828E, 1, 4, bytes([0, 1, 1, 2])))
    add(0xC612, 1, [1, 4, 0, 0], "B")
    add(0xC61A, 4, [BLACK], "I")
    add(0xC61D, 4, [WHITE], "I")
    tags.append((0xC621, 10, 9, struct.pack(e + "i" * 18, *srat(XYZ_CAM))))     # 9 SRATIONALs
    add(0xC65A, 3, [21], "H")
    if opcodes:
        tags.append((0xC740, 7, len(opcodes), opcodes))
    tags.sort()
    ifd_size = 2 + 12 * len(tags) + 4
    data_at = 8 + ifd_size
    blobs, entries = b"", b""
    strip_at = None
    for tag, typ, count, val in tags:
        if tag == 0x0111:
            strip_at = len(entries)
        if len(val) <= 4:
            entries += struct.pack(e + "HHI", tag, typ, count) + val.ljust(4, b"\0")
        else:
            entries += struct.pack(e + "HHII", tag, typ, count, data_at + len(blobs))
            blobs += val + (b"\0" if len(val) & 1 else b"")
    pix_at = data_at + len(blobs)
    entries = entries[:strip_at + 8] + struct.pack(e + "I", pix_at) + entries[strip_at + 12:]
    head = (b"MM" if be else b"II") + struct.pack(e + "HI", 42, 8)
    with open(path, "wb") as f:
        f.write(head + struct.pack(e + "H", len(tags)) + entries + struct.pack(e + "I", 0) + blobs + pixels)


def develop(src, jpeg):
    env = dict(os.environ, FOS_MIX="1", FOS_SIZE="2", FOS_JPEG=jpeg)
    r = subprocess.run([os.path.join(HERE, "out", "devtest"), src, os.path.join(OUT, "x.FOP")],
                       env=env, capture_output=True, text=True)
    if r.returncode:
        print(r.stdout + r.stderr)
        sys.exit(f"{src}: didn't develop")
    return np.asarray(Image.open(jpeg)).astype(float)


def main():
    os.makedirs(OUT, exist_ok=True)
    cr2, u16 = os.path.join(OUT, "f.CR2"), os.path.join(OUT, "f.u16")
    subprocess.run([os.path.join(HERE, "out", "mkcr2"), cr2, "--raw", u16], check=True, capture_output=True)
    raw = np.fromfile(u16, dtype="<u2").reshape(RAW_H, RAW_W)
    pic = raw[T:T + H, L:L + W]
    # darker to the right, as a lens falls off: 1 / (1 + x / W), which the GainMap puts back
    g = 1 + np.arange(W) / W
    shaded = ((pic.astype(float) - BLACK) / g + BLACK).round().clip(0, 65535).astype(np.uint16)
    write_dng(os.path.join(OUT, "le.DNG"), pic, False)
    write_dng(os.path.join(OUT, "be.DNG"), pic, True)
    write_dng(os.path.join(OUT, "gain.DNG"), shaded, False, opcode_gainmap(1.0, 2.0))
    write_dng(os.path.join(OUT, "nogain.DNG"), shaded, False)

    ref = develop(cr2, os.path.join(OUT, "cr2.JPG"))
    fail = 0
    # nogain: the shading left in, which must show (else the test can't see a GainMap missed)
    for name, limit in (("le", 2.0), ("be", 2.0), ("gain", 2.5), ("nogain", -8.0)):
        got = develop(os.path.join(OUT, name + ".DNG"), os.path.join(OUT, name + ".JPG"))
        if got.shape != ref.shape:
            print(f"{name}.DNG: {got.shape} against the CR2's {ref.shape}")
            fail = 1
            continue
        d = np.abs(got - ref).mean()
        ok = d <= limit if limit > 0 else d >= -limit
        fail |= not ok
        want = f"at most {limit}" if limit > 0 else f"at least {-limit}"
        print(f"{name}.DNG: {d:.2f} levels from the CR2's print on average ({'ok' if ok else 'WRONG'}, {want})")
    sys.exit(fail)


if __name__ == "__main__":
    main()
