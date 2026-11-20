import { serverUrl } from "./api";
import { desktopFetch } from "./transport";

export interface SourceSelection {
  orgId: number; spaceId: number; kbId: number; documentId: number; chunkIdx: number;
  filename: string; sourceUpdatedAt?: string | null; startOffset?: number | null; pageNumber?: number | null;
}
export interface PreviewBlock { chunkIdx: number; text: string; pageNumber: number | null; sectionTitle: string | null }
export interface DocumentPreview {
  documentId: number; filename: string; contentType: string; indexStatus: string;
  selectedChunkIdx: number; exactLocation: boolean; hasPrevious: boolean; hasNext: boolean;
  blocks: PreviewBlock[]; sourceUpdatedAt?: string | null;
}
export function documentSourceUrl(source: SourceSelection): string {
  return `${serverUrl}/api/v1/orgs/${source.orgId}/spaces/${source.spaceId}/knowledge-bases/${source.kbId}/documents/${source.documentId}`;
}
export async function fetchSourcePreview(source: SourceSelection, center: number, signal: AbortSignal): Promise<
  {kind: "ok"; preview: DocumentPreview} | {kind: "expired" | "gone" | "error"}
> {
  const params = new URLSearchParams({chunkIdx: String(center)});
  if (center === source.chunkIdx) {
    if (source.startOffset != null) params.set("startOffset", String(source.startOffset));
    if (source.pageNumber != null) params.set("pageNumber", String(source.pageNumber));
  }
  try {
    const response = await desktopFetch(`${documentSourceUrl(source)}/preview?${params}`, {method: "GET", signal});
    if (response.status === 401) return {kind: "expired"};
    if (response.status === 404) return {kind: "gone"};
    if (!response.ok) return {kind: "error"};
    const preview = await response.json() as DocumentPreview;
    if (preview.documentId !== source.documentId || !Array.isArray(preview.blocks) || preview.blocks.length > 22) return {kind: "error"};
    return {kind: "ok", preview};
  } catch { return {kind: "error"}; }
}

/** Display the physical source unit while retaining the shared pageNumber wire field. */
export function sourcePageLabel(filename: string): string {
  return filename.toLowerCase().endsWith(".pptx") ? "Folie" : "Seite";
}
