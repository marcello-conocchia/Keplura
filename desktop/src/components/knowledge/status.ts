// REDESIGN: Indexstatus in Klartext für Anwender (keine Technikbegriffe).
import type { KnowledgeDocument } from "../../api";
import { tr, dateLocale } from "../../i18n";

type Status = KnowledgeDocument["indexStatus"];

export function statusLabel(status: Status): string {
  switch (status) {
    case "INDEXED": return tr("Durchsuchbar");
    case "PENDING": return tr("Wird verarbeitet");
    case "FAILED": return tr("Fehlgeschlagen");
    case "NO_TEXT_LAYER": return tr("Kein Text erkannt");
    default: return tr("Nicht durchsuchbar");
  }
}

export function statusHint(status: Status): string {
  switch (status) {
    case "INDEXED": return tr("Der Inhalt wird für Antworten im Chat genutzt.");
    case "PENDING": return tr("Das Dokument wird gerade gelesen. Das dauert meist nur wenige Sekunden.");
    case "FAILED": return tr("Das Dokument konnte nicht gelesen werden. Versuche „Erneut verarbeiten“.");
    case "NO_TEXT_LAYER": return tr("Vermutlich ein eingescanntes Dokument ohne Text. Texterkennung (OCR) ist nicht verfügbar.");
    default: return tr("Dieser Dateityp wird gespeichert, aber nicht durchsucht.");
  }
}

export function statusTone(status: Status): "ok" | "wait" | "neutral" | "error" {
  switch (status) {
    case "INDEXED": return "ok";
    case "PENDING": return "wait";
    case "FAILED": return "error";
    default: return "neutral";
  }
}

export function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toLocaleString(dateLocale(), { maximumFractionDigits: 1 })} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return tr("{0} Byte", bytes);
}

export function formatDate(iso?: string): string {
  if (!iso) return "–";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "–"
    : d.toLocaleDateString(dateLocale(), { day: "2-digit", month: "2-digit", year: "numeric" });
}

export function fileType(filename: string): string {
  const dot = filename.lastIndexOf(".");
  const ext = dot < 0 ? "" : filename.slice(dot + 1).toLowerCase();
  const names: Record<string, string> = { pdf: "PDF", docx: tr("Word"), xlsx: tr("Excel"), pptx: tr("PowerPoint"), txt: tr("Text"), md: tr("Markdown"), csv: "CSV" };
  return names[ext] ?? (ext ? ext.toUpperCase() : tr("Datei"));
}
