#!/usr/bin/env python3
"""UI-REFRESH-2 evidence helper: where do two chat shots differ?
Reuses the stdlib PNG decoder from ui-refresh-1-pixdiff.py, then reports a
coarse grid (bands) of differing pixels so run-dependent content (timestamps,
stamped filenames) can be told apart from a real style regression.
Usage: ui-refresh-2-diffmap.py <a.png> <b.png> [cellPx]
"""
import sys, importlib.util, collections

src = open(sys.argv[0].rsplit("/", 1)[0] + "/ui-refresh-1-pixdiff.py").read()
# keep only the module part (the pixdiff script's own main body runs at import)
src = src.split("a, b = sys.argv[1]")[0]
pd = type(sys)("pd")
exec(compile(src, "ui-refresh-1-pixdiff.py", "exec"), pd.__dict__)

A, B = sys.argv[1], sys.argv[2]
CELL = int(sys.argv[3]) if len(sys.argv) > 3 else 100
wa, ha, pa = pd.load_png(A)
wb, hb, pb = pd.load_png(B)
if (wa, ha) != (wb, hb):
    print(f"size differs: {wa}x{ha} vs {wb}x{hb}")
    sys.exit(1)
bands = collections.Counter()
n = 0
for y in range(ha):
    row = y * wa * 3
    for x in range(wa):
        i = (row + x * 3)
        if pa[i:i+3] != pb[i:i+3]:
            n += 1
            bands[(x // CELL, y // CELL)] += 1
print(f"differing pixels: {n} ({100.0*n/(wa*ha):.3f}%) grid {CELL}px")
for (cx, cy), c in sorted(bands.items(), key=lambda kv: -kv[1])[:25]:
    print(f"  x{cx*CELL}-{cx*CELL+CELL} y{cy*CELL}-{cy*CELL+CELL}: {c}")
