// REDESIGN: Indexstatus in Klartext für Anwender (keine Technikbegriffe).
import type { KnowledgeDocument } from "../../api";

type Status = KnowledgeDocument["indexStatus"];

export function statusLabel(status: Status): string {
  switch (status) {
    case "INDEXED": return "Durchsuchbar";
    case "PENDING": return "Wird verarbeitet";
    case "FAILED": return "Fehlgeschlagen";
    case "NO_TEXT_LAYER": return "Kein Text erkannt";
    default: return "Nicht durchsuchbar";
  }
}

export function statusHint(status: Status): string {
  switch (status) {
    case "INDEXED": return "Der Inhalt wird für Antworten im Chat genutzt.";
    case "PENDING": return "Das Dokument wird gerade gelesen. Das dauert meist nur wenige Sekunden.";
    case "FAILED": return "Das Dokument konnte nicht gelesen werden. Versuche „Erneut verarbeiten“.";
    case "NO_TEXT_LAYER": return "Vermutlich ein eingescanntes Dokument ohne Text. Texterkennung (OCR) ist nicht verfügbar.";
    default: return "Dieser Dateityp wird gespeichert, aber nicht durchsucht.";
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
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toLocaleString("de-DE", { maximumFractionDigits: 1 })} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} Byte`;
}

export function formatDate(iso?: string): string {
  if (!iso) return "–";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "–"
    : d.toLocaleDateString("de-DE", { day: "2-digit", month: "2-digit", year: "numeric" });
}

export function fileType(filename: string): string {
  const dot = filename.lastIndexOf(".");
  const ext = dot < 0 ? "" : filename.slice(dot + 1).toLowerCase();
  const names: Record<string, string> = { pdf: "PDF", docx: "Word", xlsx: "Excel", pptx: "PowerPoint", txt: "Text", md: "Markdown", csv: "CSV" };
  return names[ext] ?? (ext ? ext.toUpperCase() : "Datei");
}
