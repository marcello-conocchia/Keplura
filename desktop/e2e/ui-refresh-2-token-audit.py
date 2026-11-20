#!/usr/bin/env python3
"""UI-REFRESH-2 Kollisions-Audit: jedes Tailwind-Core-Token, das durch die
Phase-2-@source-Dateien NEU generiert wird (im baseline-Bundle noch fehlt),
darf in KEINEM class-Attribut einer gefrorenen Datei vorkommen — sonst wuerde
die neue globale Utility-Regel ploetzlich gefrorenes DOM restylen.
Exit 0 + "NONE" = sauber."""
import re, glob, sys

BASE = open(glob.glob(
    "/home/hermes/workspaces/IntraCore/.baseline/t_ui11f53ad9-main/desktop/dist/assets/index-*.css"
)[0]).read()

def unesc(s):
    return re.sub(r"\\(.)", r"\1", s)

base_classes = {unesc(c) for c in re.findall(r"\.((?:\\.|\w|-)+)\s*[{:]", BASE)}
base_classes |= {unesc(c) for c in re.findall(r"\.((?:\\.|\w|-)+)(?=[,:.]|\s*\{)", BASE)}

MIGRATED = {"src/components/ChatView.vue", "src/components/ui/MarkdownAnswer.vue"}
frozen = [f for f in glob.glob("src/components/*.vue") + glob.glob("src/components/ui/*.vue")
          + ["src/App.vue", "index.html"] if f not in MIGRATED]
frozen_tokens = set()
for f in frozen:
    for m in re.finditer(r'class="([^"]+)"', open(f, encoding="utf-8").read()):
        frozen_tokens |= set(m.group(1).split())
    # dynamic class bindings too ( :class="'x y'" strings inside migrated-adjacent files )

chat_tokens = set()
for f in MIGRATED:
    txt = open(f, encoding="utf-8").read()
    for m in re.finditer(r'class="([^"]*)"', txt):
        chat_tokens |= set(m.group(1).split())
    for m in re.finditer(r":class=\"'([^']*)'\"", txt):
        chat_tokens |= set(m.group(1).split())
    for m in re.finditer(r"\? '([\w-]+(?: [\w-]+)+)' : '([\w-]+(?: [\w-]+)+)'", txt):
        chat_tokens |= set(m.group(1).split()) | set(m.group(2).split())

new_tokens = {t for t in chat_tokens if t not in base_classes and not t.startswith("@")}
# template-expression noise (ternary operators etc.) is not a class token:
new_tokens = {t for t in new_tokens if re.fullmatch(r"[\w\[\]().,%/:#-]+", t)
              and any(ch.isalpha() for ch in t)}
collide = sorted(t for t in new_tokens if t in frozen_tokens)
print("migrated class tokens:", len(chat_tokens))
print("newly generated (not in baseline bundle):", len(new_tokens))
for t in sorted(new_tokens):
    print("  +", t)
print("COLLIDE with frozen DOM:", collide if collide else "NONE")
sys.exit(1 if collide else 0)
