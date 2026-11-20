#!/usr/bin/env python3
"""UI-REFRESH-2: diff the per-state text dumps of the two evidence legs.
Exit 0 = both legs rendered the same content at every capture point (then
any pixel delta is render-layer only); exit 1 = state divergence found."""
import json, sys
a = json.load(open(sys.argv[1])); b = json.load(open(sys.argv[2]))
diffs = 0
for tag in sorted(set(a) | set(b)):
    ta, tb = a.get(tag, {}), b.get(tag, {})
    for field in sorted(set(ta) | set(tb)):
        va, vb = ta.get(field, ""), tb.get(field, "")
        if va != vb:
            diffs += 1
            print(f"DIFF {tag}:{field}")
            print("  base:", repr(va)[:400])
            print("  new :", repr(vb)[:400])
print(f"states={len(set(a)|set(b))} text-diffs={diffs}")
sys.exit(1 if diffs else 0)
