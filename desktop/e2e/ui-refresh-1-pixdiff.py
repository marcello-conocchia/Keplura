#!/usr/bin/env python3
"""UI-REFRESH-1 AC4: pure-stdlib PNG pixel diff (no PIL in this container).
Usage: ui-refresh-1-pixdiff.py <a.png> <b.png>
Decodes both PNGs (RGBA8/RGB8, filters 0-4), compares every channel.
Reports size, differing-pixel count, max delta, bounding box of differences.
"""
import sys, zlib, struct

def load_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos = 8
    idat = b""
    w = h = depth = ctype = None
    plte = None
    trns = None
    while pos < len(data):
        (ln,) = struct.unpack(">I", data[pos:pos+4])
        tag = data[pos+4:pos+8]
        body = data[pos+8:pos+8+ln]
        if tag == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
        elif tag == b"IDAT":
            idat += body
        elif tag == b"PLTE":
            plte = body
        elif tag == b"tRNS":
            trns = body
        elif tag == b"IEND":
            break
        pos += 12 + ln
    assert depth == 8, f"bit depth {depth} unsupported"
    chan = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    raw = zlib.decompress(idat)
    stride = w * chan
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if f == 1:
            for i in range(chan, stride): line[i] = (line[i] + line[i-chan]) & 255
        elif f == 2:
            for i in range(stride): line[i] = (line[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                a = line[i-chan] if i >= chan else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i-chan] if i >= chan else 0
                b = prev[i]
                c = prev[i-chan] if i >= chan else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y*stride:(y+1)*stride] = line
        prev = line
    # normalise to RGB triples
    px = bytearray(w * h * 3)
    for i in range(w * h):
        if ctype == 6:
            r, g, b = out[i*4], out[i*4+1], out[i*4+2]
        elif ctype == 2:
            r, g, b = out[i*3], out[i*3+1], out[i*3+2]
        elif ctype == 0:
            r = g = b = out[i]
        elif ctype == 3:
            idx = out[i] * 3
            r, g, b = plte[idx], plte[idx+1], plte[idx+2]
        else:
            r = g = b = out[i*2]
        px[i*3:i*3+3] = bytes((r, g, b))
    return w, h, px

a, b = sys.argv[1], sys.argv[2]
wa, ha, pa = load_png(a)
wb, hb, pb = load_png(b)
if (wa, ha) != (wb, hb):
    print(f"{a}: {wa}x{ha} vs {b}: {wb}x{hb} -> SIZE DIFFERS")
    sys.exit(1)
n = wa * ha * 3
diff_px = 0
maxd = 0
minx = miny = 10**9; maxx = maxy = -1
for i in range(n):
    d = abs(pa[i] - pb[i])
    if d:
        pidx = i // 3
        if i % 3 == 0:
            diff_px += 1
            x, y = pidx % wa, pidx // wa
            minx = min(minx, x); maxx = max(maxx, x)
            miny = min(miny, y); maxy = max(maxy, y)
        if d > maxd:
            maxd = d
total = wa * ha
print(f"{wa}x{ha}: differing_pixels={diff_px} ({100.0*diff_px/total:.4f}%) max_channel_delta={maxd}", end="")
if diff_px:
    print(f" bbox=({minx},{miny})-({maxx},{maxy})")
else:
    print(" -> PIXEL-IDENTICAL")
sys.exit(0 if diff_px == 0 else 3)
