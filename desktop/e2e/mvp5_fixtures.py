#!/usr/bin/env python3
"""MVP-5 e2e fixtures. Written by hand (the e2e box has no PDFBox/POI): real
PDFs with valid classic xref tables + a real minimal-OPC DOCX (stored zip).

The SERVER's Tika pipeline is the judge:
  mvp5_spec.pdf   one page, text layer   -> must extract (PHRASE_PDF)
  mvp5_scan.pdf   one page, NO content   -> no text layer (NO_TEXT_LAYER)
  mvp5_big.pdf    N pages (default 60)   -> over the e2e-run pdf-max-pages cap
  mvp5_plan.docx  minimal OPC parts      -> must extract (PHRASE_DOCX)
"""
import argparse
import zipfile
from pathlib import Path

PHRASE_PDF = "Betriebstemperatur der Gasturbine Delta liegt bei 72 Grad Celsius"
PHRASE_DOCX = "Kostenplanung Werksniederlassung Nord betraegt 4.2 Millionen Euro"


def pdf_objects(pages, with_text, text):
    kids = " ".join(f"{3 + 2 * i} 0 R" for i in range(pages))
    font_no = 3 + 2 * pages
    objects = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [%s] /Count %d >>" % (kids, pages),
    ]
    bodies = [objects[0].encode(), objects[1].encode()]
    for i in range(pages):
        page_no = 3 + 2 * i
        cont_no = page_no + 1
        if with_text:
            bodies.append(
                (
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                    "/Resources << /Font << /F1 %d 0 R >> >> /Contents %d 0 R >>" % (font_no, cont_no)
                ).encode()
            )
            content = ("BT /F1 12 Tf 72 720 Td (%s Seite %d) Tj ET\n" % (text, i + 1)).encode("latin-1")
            bodies.append(
                b"<< /Length " + str(len(content)).encode() + b" >>\nstream\n" + content + b"endstream"
            )
        else:
            # scan: bare page, no /Contents, no fonts -> nothing to extract
            bodies.append(b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>")
    if with_text:
        bodies.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    return bodies


def make_pdf(path: Path, pages: int, with_text: bool, text: str = PHRASE_PDF) -> None:
    bodies = pdf_objects(pages, with_text, text)
    n = len(bodies) + 1
    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for idx, body in enumerate(bodies, start=1):
        offsets.append(len(out))
        out += str(idx).encode() + b" 0 obj\n" + body + b"\nendobj\n"
    xref_at = len(out)
    out += ("xref\n0 %d\n0000000000 65535 f \n" % n).encode()
    for off in offsets:
        out += ("%010d 00000 n \n" % off).encode()
    out += (
        b"trailer\n<< /Size " + str(n).encode() + b" /Root 1 0 R >>\nstartxref\n"
        + str(xref_at).encode()
        + b"\n%%EOF\n"
    )
    path.write_bytes(bytes(out))


DOC_CONTENT_TYPES = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
    "</Types>"
)

DOC_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
    "</Relationships>"
)


def make_docx(path: Path, phrase: str) -> None:
    document = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        "<w:body><w:p><w:r><w:t>" + phrase + "</w:t></w:r></w:p></w:body></w:document>"
    )
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        z.writestr("[Content_Types].xml", DOC_CONTENT_TYPES)
        z.writestr("_rels/.rels", DOC_RELS)
        z.writestr("word/document.xml", document)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="/tmp")
    ap.add_argument("--big-pages", type=int, default=60)
    args = ap.parse_args()
    d = Path(args.dir)
    d.mkdir(parents=True, exist_ok=True)
    make_pdf(d / "mvp5_spec.pdf", 1, True)
    make_pdf(d / "mvp5_scan.pdf", 1, False)
    make_pdf(d / "mvp5_big.pdf", args.big_pages, True)
    make_docx(d / "mvp5_plan.docx", PHRASE_DOCX)
    print("ok")
