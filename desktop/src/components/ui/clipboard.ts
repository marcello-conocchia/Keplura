/**
 * Clipboard helper for the chat message/code-block copy actions
 * (CHAT-POLISH card §2). Presentation-layer only — no server calls.
 *
 * Primary path: the async Clipboard API (navigator.clipboard.writeText).
 * Documented fallback: a detached readonly <textarea> + document.execCommand
 * ("copy"). The fallback exists because the async API needs a secure
 * context AND clipboard-write permission; the packaged WebKitGTK webview can
 * deny either. execCommand is deprecated but still the only synchronous
 * in-webview copy path there. If BOTH fail the caller gets `false` and must
 * degrade honestly (button marks data-copied="0") — never a fake success.
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* permission denied / insecure context — fall through to the legacy path */
  }
  try {
    const area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.top = "-1000px";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand("copy");
    document.body.removeChild(area);
    return ok;
  } catch {
    return false;
  }
}
