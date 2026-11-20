<script lang="ts">
// Shared render config for every LLM-authored Markdown block (CHAT-POLISH
// card §1). This module is the ONE XSS wall of the chat: model output is
// untrusted text that becomes HTML here, so the sanitiser — not the parser —
// decides what reaches the DOM.
//
// Hard rules (enforced by DOMPurify config + the hook below):
// - Raw HTML inside the answer is sanitised away tag by tag (marked passes
//   it through unescaped by design; sanitisation is the guarantee).
// - Allowed protocols: http, https, mailto only. Everything else (in
//   particular javascript:, data:) is stripped from href/src by
//   ALLOWED_URI_REGEXP + ALLOWED_ATTR excluding event handlers anyway.
// - No event handler attributes ever survive (DOMPurify drops on* by default).
// - Every surviving external link gets rel=noopener noreferrer so no
//   window.opener handle leaks back into the app origin.
// - No inline style anywhere (FORBID_ATTR style): style is ours, not the
//   model's.
// - Version pinning: marked 16.x + dompurify 3.4.15 exact in package.json
//   (>=3.4.13 clears the published DOMPurify XSS advisories; npm audit 0).
//
// Singleton-state note (DOMPurify advisory GHSA-cmwh… hygiene): we NEVER use
// setConfig() — every sanitize() call passes its own config object, so there
// is no global config to pollute across calls.
import DOMPurify from "dompurify";
import { marked, type Tokens } from "marked";

// Only real web/mail links survive; data:/javascript:/vbscript:/file: die here.
const SAFE_URI = /^(?:https?:|mailto:|https?:\/\/|#)/i;

const SANITIZE_CONFIG = {
  USE_PROFILES: { html: true },
  FORBID_TAGS: ["style", "form", "input", "textarea", "select", "button"],
  FORBID_ATTR: ["style"],
  ALLOWED_URI_REGEXP: SAFE_URI,
  // IMPORTANT: the default XML-safe mode would silently strip the VALUE of
  // data-inapp-link="1" (its XML attribute normalisation) — the marker the
  // ChatView click handler uses to refuse navigation would vanish and the
  // webview WOULD navigate. We sanitize HTML fragments only, so XML mode off.
  SAFE_FOR_XML: false,
};

// marked renders links without rel by default; DOMPurify's default URI list
// already strips javascript:/data: hrefs. ONE hook does the anchor policy
// uniformly AFTER sanitisation — for Markdown-authored links AND raw-HTML
// links alike (a raw <a> never passes through any custom renderer, which an
// early version of this file wrongly relied on):
// - rel=noopener noreferrer: no window.opener handle back into the app
// - target cleared: the webview never opens new windows from chat links
// - data-inapp-link marker: ChatView's delegated click handler matches on it
//   and preventDefaults — rendered links must never navigate the webview
//   (ADR-0005 pins all network I/O through desktopFetch; there is no native
//   opener path).
DOMPurify.addHook("afterSanitizeAttributes", (node) => {
  if (node instanceof HTMLAnchorElement) {
    node.rel = "noopener noreferrer";
    node.removeAttribute("target");
    node.setAttribute("data-inapp-link", "1");
  }
});

function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
function escapeAttr(s: string): string {
  return escapeHtml(s).replace(/"/g, "&quot;");
}

/** Renderer overrides. Fenced code becomes <pre data-code><code>…</code></pre>
 *  so ChatView attaches a per-block Copy button AFTER mount by DOM query —
 *  never by string-splicing HTML (that would reopen the injection surface).
 *  Links carry data-inapp-link so the click handler can preventDefault. */
const renderer = new marked.Renderer();
renderer.code = (token: Tokens.Code) => {
  const lang = token.lang ? ` data-lang="${escapeAttr(token.lang)}"` : "";
  return `<pre data-code${lang}><code>${escapeHtml(token.text)}\n</code></pre>`;
};
// NOTE: no custom link renderer — marked's stock link renderer already drops
// dangerous URL schemes, and the DOMPurify hook above applies the anchor
// policy uniformly to EVERY surviving link (markdown- or HTML-authored).

marked.setOptions({ gfm: true, breaks: false, renderer });

/** Markdown (LLM-authored) -> sanitised, chat-safe HTML string. */
export function renderAnswerMarkdown(source: string): string {
  const html = marked.parse(source, { async: false }) as string;
  return DOMPurify.sanitize(html, SANITIZE_CONFIG) as string;
}
</script>

<script setup lang="ts">
/**
 * MarkdownAnswer (CHAT-POLISH card §1): renders one LLM answer as sanitized
 * GitHub-style Markdown in the DESIGN.md token look. The HTML comes only
 * from renderAnswerMarkdown() (marked + DOMPurify, config above) and is
 * inserted with v-html — safe ONLY because of that sanitisation pipeline.
 * Link interception and per-code-block copy buttons are attached by the
 * parent (ChatView) on the mounted root element.
 */
import { computed } from "vue";

const props = defineProps<{ source: string }>();

const html = computed(() => renderAnswerMarkdown(props.source));
</script>

<template>
  <div class="md-answer" data-testid="chat-md" v-html="html"></div>
</template>

<!-- UI-REFRESH-2 Phase 2: der scoped :deep-Block ist unveraendert als
     .md-answer-Regelgruppe nach styles/tailwind.css umgezogen (1:1, keine
     neuen Werte). Die Sicherheitswand (DOMPurify-Konfig, Hooks, Renderer,
     v-html nur Pipeline-Ausgang) ist unangetastet — dieser Diff enthaelt
     ausschliesslich Stil-Transport: Root-Klasse .md -> .md-answer (die
     v-html-Kinder tragen weiterhin KEINE Klassen; Utilities bleiben aus dem
     Injection-Subtree komplett draussen). -->

<style scoped>
.md-answer{font-family:var(--ic-font);font-size:16px;line-height:26px;overflow-wrap:anywhere}.md-answer :deep(table){display:block;max-width:100%;overflow:auto;border-collapse:collapse}.md-answer :deep(pre){position:relative;max-width:100%;overflow:auto;background:var(--ic-surface-subtle);border-radius:6px;padding:16px;font:400 13px/20px var(--ic-font-mono)}.md-answer :deep(pre[data-code]){padding-top:48px}.md-answer :deep(code){font-family:var(--ic-font-mono)}.md-answer :deep(th),.md-answer :deep(td){padding:10px 12px;border:1px solid var(--ic-separator);font-size:14px;line-height:22px}.md-answer :deep(a){color:var(--ic-accent);text-decoration:underline}.md-answer :deep(h2){font-size:20px;line-height:28px;margin:24px 0 12px}.md-answer :deep(p){margin:0 0 16px}
 .md-answer :deep(.code-copy){position:absolute;top:8px;right:8px;display:inline-flex;align-items:center;justify-content:center;min-width:32px;min-height:32px;padding:4px 8px;border:1px solid var(--ic-input-edge);border-radius:6px;color:var(--ic-text-2);background:var(--ic-surface);font:500 13px/20px var(--ic-font);cursor:pointer}
 .md-answer :deep(.code-copy:hover){color:var(--ic-text);background:var(--ic-hover-tint)} .md-answer :deep(.code-copy[data-copied="1"]){color:var(--ic-status-ok)}
 @media(max-width:767px),(any-pointer:coarse){.md-answer :deep(.code-copy){min-height:44px;min-width:44px}.md-answer :deep(pre[data-code]){padding-top:60px}}
</style>
