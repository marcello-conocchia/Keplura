use tauri::{Manager, RunEvent};

/// Cookie-jar filename inside `app_cache_dir`. Mirrors the private
/// `COOKIES_FILENAME` constant of tauri-plugin-http 2.6 (src/lib.rs) — keep
/// in sync if the plugin is ever bumped (tests pin the effective behaviour).
const COOKIES_FILENAME: &str = ".cookies";

/// The exact path the http plugin persists its native cookie jar to.
pub fn cookie_jar_path(app_cache_dir: &std::path::Path) -> std::path::PathBuf {
    app_cache_dir.join(COOKIES_FILENAME)
}

/// Tighten the native cookie jar to user-only read/write (security hardening
/// for ADR-0005's local-theft window, follow-up of the M2-POLISH security
/// gate): upstream tauri-plugin-http 2.6 creates and rewrites `.cookies`
/// (plaintext session id) without touching permissions, so on a default
/// umask-022 host the file lands 0644 world-readable. Idempotent; only
/// rewrites the mode when it differs. Errors surface (caller decides) when
/// the jar does not exist or is not ours — hardening must never crash the app.
#[cfg(unix)]
pub fn harden_cookie_jar(path: &std::path::Path) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mut perms = std::fs::metadata(path)?.permissions();
    if perms.mode() & 0o777 != 0o600 {
        perms.set_mode(0o600);
        std::fs::set_permissions(path, perms)?;
    }
    Ok(())
}

/// Windows: `std::fs::Permissions` can only express the read-only bit — there
/// is no std way to set an NTFS DACL. Confidentiality there comes from the
/// jar's location: `app_cache_dir` resolves under `%LOCALAPPDATA%`, whose
/// default ACL grants access to the creating user (plus SYSTEM/admins) only,
/// and a truncating rewrite (`tokio::fs::write` semantics) inherits the
/// existing file's ACL. Deliberate no-op; documented in ADR-0005 Consequences.
#[cfg(not(unix))]
pub fn harden_cookie_jar(_path: &std::path::Path) -> std::io::Result<()> {
    Ok(())
}

/// Best-effort hardening of the live app's cookie jar. Never panics, never
/// blocks: a missing/unreadable jar (first launch race, plugin disabled,
/// permission oddity) is silently retried at the next event.
fn harden_cookie_jar_for<R: tauri::Runtime>(app: &tauri::AppHandle<R>) {
    if let Some(dir) = app.path().app_cache_dir().ok() {
        let _ = harden_cookie_jar(&cookie_jar_path(&dir));
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // The frontend reaches the Keplura Server through tauri-plugin-http
    // (ADR-0005): a Rust-side reqwest client with its own persistent cookie
    // jar, so the HttpOnly SameSite=Lax session survives packaged-app
    // restarts without ever being visible to webview JS. No custom commands;
    // the plugin's capability-scoped fetch is the whole surface.
    //
    // The builder callback below only ADDS the 0600 hardening of the plugin's
    // `.cookies` jar (M3-HARDEN); plugin setup/persist order is untouched:
    // tauri runs every plugin's `on_event` hook before this callback sees the
    // same event, so on `Exit` the plugin's final persist has already joined
    // its async write when we tighten. `Ready` covers the file created during
    // plugin setup, `MainEventsCleared` covers mid-session re-persists
    // (immediate-save on every Set-Cookie) and any external re-creation.
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_http::init())
        .build(tauri::generate_context!())
        .expect("error while building Keplura Desktop");

    app.run(|app_handle, event| match event {
        RunEvent::Ready | RunEvent::MainEventsCleared | RunEvent::Exit => {
            harden_cookie_jar_for(app_handle)
        }
        _ => {}
    });
}
