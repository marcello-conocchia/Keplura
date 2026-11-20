// ADR-0005 cookie-persistence contract, headless (runs on every CI platform —
// windows/macos/linux — without a GUI).
//
// These tests mirror EXACTLY the persistence pipeline of
// tauri-plugin-http 2.6 (src/reqwest_cookie_store.rs + lib.rs setup/save of
// the same version): a reqwest `cookie_store::CookieStore`, persisted as
// `serde_json::to_string` over the UNEXPIRED + PERSISTENT cookies only, and
// reloaded at startup via `cookie_store::serde::load`. That "persistent
// only" filter is why the server must send `Max-Age` on the session cookie
// (application.properties, ADR-0005) — a bare session cookie never reaches
// the disk and app-restart restore would silently fail. The negative-control
// test below pins that behaviour; if an upstream plugin change ever persists
// session cookies too, the negative test fails and we re-evaluate.
//
// Real behaviour against the real server (login/restore/logout under Xvfb)
// is covered by the packaged-app E2E driver in desktop/e2e/ (Linux) — see
// docs/development.md and the M2-POLISH PR evidence.

use cookie_store::{CookieStore, RawCookie};
use url::Url;

/// The dev-profile login Set-Cookie as the server actually emits it
/// (HttpOnly, SameSite=Lax, Max-Age from server.servlet.session.cookie.max-age).
const SERVER_SET_COOKIE: &str = "KEPLURASESSION_DEV=5AF5249BA5C6FC4EC9F0D1DAD56AB7D3; \
                                 HttpOnly; SameSite=Lax; Path=/; Max-Age=28800";
/// The same cookie WITHOUT the lifetime (the pre-ADR-0005 shape).
const SERVER_SET_COOKIE_SESSION_ONLY: &str =
    "KEPLURASESSION_DEV=5AF5249BA5C6FC4EC9F0D1DAD56AB7D3; HttpOnly; SameSite=Lax; Path=/";

fn server_url() -> Url {
    Url::parse("http://localhost:8080/api/v1/auth/login").unwrap()
}

/// Store a Set-Cookie exactly like reqwest does mid-session.
fn store_cookie(jar: &mut CookieStore, set_cookie: &str) {
    let raw = RawCookie::parse(set_cookie.to_string()).expect("valid Set-Cookie");
    jar.store_response_cookies(std::iter::once(raw), &server_url());
}

/// The plugin's save filter: unexpired AND persistent only
/// (CookieStoreMutex::cookies_to_str in tauri-plugin-http 2.6).
fn plugin_persistable(jar: &CookieStore) -> String {
    let cookies: Vec<_> = jar
        .iter_unexpired()
        .filter(|c| c.is_persistent())
        .cloned()
        .collect();
    serde_json::to_string(&cookies).expect("serializable")
}

/// The plugin's startup load (CookieStoreMutex::load in tauri-plugin-http 2.6).
fn plugin_reload(saved: &str) -> CookieStore {
    let reader = std::io::BufReader::new(std::io::Cursor::new(saved.as_bytes().to_vec()));
    cookie_store::serde::load(reader, |c| serde_json::from_str(c)).expect("loadable")
}

fn replay_header(jar: &CookieStore) -> String {
    jar.get_request_values(&server_url())
        .map(|(n, v)| format!("{n}={v}"))
        .collect::<Vec<_>>()
        .join("; ")
}

#[test]
fn session_cookie_with_max_age_survives_a_restart_roundtrip() {
    let mut jar = CookieStore::default();
    store_cookie(&mut jar, SERVER_SET_COOKIE);

    // Mid-session the cookie is replayed…
    assert!(replay_header(&jar).contains("KEPLURASESSION_DEV="));

    // …and after persist + reload (the app-restart path), the native jar
    // still holds and replays it — this is what makes restore work.
    let saved = plugin_persistable(&jar);
    assert!(!saved.contains("\"[]\""), "cookie was not persistable");
    let reloaded = plugin_reload(&saved);
    let replayed = replay_header(&reloaded);
    assert!(replayed.contains("KEPLURASESSION_DEV=5AF5249BA5C6FC4EC9F0D1DAD56AB7D3"),
            "reloaded jar must replay the exact session id, got: {replayed}");
    assert_eq!(replayed, replay_header(&jar), "replay must be identical across restart");
}

#[test]
fn negative_control_session_cookie_without_lifetime_is_never_persisted() {
    // Pins the upstream plugin behaviour ADR-0005's Max-Age compensates for:
    // without an explicit lifetime the cookie is dropped from the persisted
    // jar entirely — restore after restart would silently BREAK. If this
    // ever passes the second assert in reverse (upstream starts persisting
    // session cookies), re-evaluate the Max-Age decision.
    let mut jar = CookieStore::default();
    store_cookie(&mut jar, SERVER_SET_COOKIE_SESSION_ONLY);
    assert!(replay_header(&jar).contains("KEPLURASESSION_DEV="), "mid-session works");

    let saved = plugin_persistable(&jar);
    let reloaded = plugin_reload(&saved);
    assert!(
        !replay_header(&reloaded).contains("KEPLURASESSION_DEV="),
        "session-only cookie must NOT survive the plugin's persist filter"
    );
}

#[test]
fn the_cookie_is_flagged_http_only_and_lax_after_reload() {
    // The security-critical flags survive serialisation, so the persisted
    // copy keeps the exact ADR-0003 posture (JS-invisible, Lax CSRF stance).
    let mut jar = CookieStore::default();
    store_cookie(&mut jar, SERVER_SET_COOKIE);
    let reloaded = plugin_reload(&plugin_persistable(&jar));
    let cookie = reloaded
        .iter_unexpired()
        .find(|c| c.name() == "KEPLURASESSION_DEV")
        .expect("cookie present after reload");
    assert!(cookie.http_only().unwrap_or(false), "HttpOnly must survive");
    assert_eq!(
        format!("{:?}", cookie.same_site()),
        "Some(Lax)",
        "SameSite=Lax must survive"
    );
    assert!(cookie.is_persistent(), "reloaded copy must remain persistent");
}
