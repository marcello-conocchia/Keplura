// M3-HARDEN companion of cookie_persistence.rs: proves the 0600 tightening of
// the native cookie jar (security-gate follow-up). Mirrors the exact persist
// mechanics of tauri-plugin-http 2.6 (File::options().create().append() at
// setup, tokio::fs::write truncation on save — neither touches permissions,
// so on a default-umask-022 host the jar lands 0644 world-readable, measured
// live after the packaged E2E) and then asserts our hardening function fixes
// the effective mode without altering the file's contents.

use keplura_desktop_lib::{cookie_jar_path, harden_cookie_jar};

/// Fresh per-test dir under the system temp dir (unique process id + name).
fn scratch_dir(name: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!(
        "keplura-cookies-perms-{}-{name}",
        std::process::id()
    ));
    std::fs::create_dir_all(&dir).expect("scratch dir");
    dir
}

#[test]
fn jar_path_is_the_plugins_dot_cookies_in_the_cache_dir() {
    // Pins our mirror of the plugin's private COOKIES_FILENAME: if upstream
    // ever renames the jar, this stays green but the live hardening would
    // miss — hence the ADR-0005 note to re-verify filename/behaviour on any
    // plugin version bump.
    let p = cookie_jar_path(std::path::Path::new("/cache/dir"));
    assert_eq!(p.file_name().unwrap(), ".cookies");
    assert_eq!(p.parent().unwrap(), std::path::Path::new("/cache/dir"));
}

#[test]
fn hardening_survives_a_full_persist_cycle() {
    let dir = scratch_dir("cycle");
    let jar = cookie_jar_path(&dir);

    // Plugin setup path: File::options().create(true).append(true) — a
    // permission-untouched creation of an empty jar.
    std::fs::File::options()
        .create(true)
        .append(true)
        .read(true)
        .open(&jar)
        .expect("plugin-style create");
    // Persist cycle: request_save() truncates and rewrites the same path
    // (tokio::fs::write). Contents = the plugin's serde_json cookie array.
    std::fs::write(&jar, r#"[{"name":"KEPLURASESSION_DEV"}]"#).expect("plugin-style save");

    // What upstream leaves behind on a typical host: explicitly set the
    // pre-fix world-readable mode (umask on CI runners is not guaranteed —
    // the mode we must *repair from* is the bug, not the runner default).
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&jar, std::fs::Permissions::from_mode(0o644)).unwrap();
        assert_eq!(std::fs::metadata(&jar).unwrap().permissions().mode() & 0o777, 0o644);
    }

    harden_cookie_jar(&jar).expect("hardening succeeds");

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(
            std::fs::metadata(&jar).unwrap().permissions().mode() & 0o777,
            0o600,
            "cookie jar must be user-only after hardening"
        );
    }
    #[cfg(not(unix))]
    {
        // Windows: std can't set ACLs; hardening is a documented no-op and
        // must at least never fail or change readability.
        assert!(!std::fs::metadata(&jar).unwrap().permissions().readonly());
    }

    // Content untouched (hardening must not truncate the jar) and idempotent.
    assert_eq!(
        std::fs::read_to_string(&jar).unwrap(),
        r#"[{"name":"KEPLURASESSION_DEV"}]"#
    );
    harden_cookie_jar(&jar).expect("second hardening is a no-op, not an error");

    std::fs::remove_dir_all(&dir).ok();
}

#[test]
fn a_later_plugin_rewrite_re_hardens_cleanly() {
    // The runtime rewrites happen after our Ready/MainEventsCleared passes;
    // tokio::fs::write truncates (keeps the existing inode+mode on unix), and
    // the Exit pass runs on the final file — model a worst case where the
    // rewrite re-widened the mode (e.g. jar re-created externally).
    let dir = scratch_dir("rewrite");
    let jar = cookie_jar_path(&dir);
    std::fs::write(&jar, "[]").unwrap();
    harden_cookie_jar(&jar).unwrap();

    std::fs::remove_file(&jar).unwrap(); // jar re-created elsewhere
    std::fs::write(&jar, "[\"new\"]").unwrap();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&jar, std::fs::Permissions::from_mode(0o666)).unwrap();
        harden_cookie_jar(&jar).unwrap();
        assert_eq!(std::fs::metadata(&jar).unwrap().permissions().mode() & 0o777, 0o600);
    }
    #[cfg(not(unix))]
    harden_cookie_jar(&jar).unwrap();

    std::fs::remove_dir_all(&dir).ok();
}

#[cfg(unix)]
#[test]
fn missing_jar_reports_an_error_the_app_layer_swallows() {
    // harden_cookie_jar is the honest primitive: a missing jar is an error.
    // The RunEvent handler wraps it in `let _ =` (best-effort) — this pins
    // the primitive's contract so a future refactor can't quietly start
    // creating placeholder jars.
    let dir = scratch_dir("missing");
    let jar = cookie_jar_path(&dir);
    assert!(harden_cookie_jar(&jar).is_err());
    std::fs::remove_dir_all(&dir).ok();
}
