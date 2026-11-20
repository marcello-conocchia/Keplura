#!/usr/bin/env python3
"""M4b packaged-app CHAT E2E (AC2): drives the REAL Tauri binary (native GTK
window + WebKitGTK webview, native reqwest transport — NOT the browser flow)
against the REAL Spring Boot jar and a hermetic in-process mock OpenAI
upstream, using only AT-SPI2 + XTEST (no test hooks in app code).

Proven journey (each step a JSON line; exit 0 only when all passed):
  1 launch fresh + login            -> Chat view mounts, empty list
  2 plain chat streams TOKENWISE    -> >=2 distinct partial renders of the
                                       streamed answer before the final one,
                                       then the 'fertig' pill + full text
  3 STOP mid-stream                 -> 'Stopp' breaks the native fetch; the
                                       persisted row renders 'gestoppt' with
                                       its partial text
  4 app restart (session restore)   -> conversation history comes back from
                                       the SERVER (transcript + both pills)
  5 logout                          -> login form, client list gone
  6 server restart (fresh db)       -> stale native cookie replays 401
                                       (server log proves it), login form;
                                       re-login + Chat shows the EMPTY list
                                       (no foreign/stale tenant refs)
  7 delete + reload persistence     -> on the restored db2 session: new chat,
                                       answer, delete, restart -> still gone

Fixtures (org + space) are created server-side over REST by this script under
the SAME dev-seeded account before the app launches — provisioning, not app
state; the app itself still performs the whole chat flow for real.

Run (needs Xvfb + dbus + at-spi2 + python3-gi, see e2e/README.md):
  dbus-run-session -- xvfb-run -a -s "-screen 0 1280x800x24" \
    python3 packaged_chat_e2e.py --bin ../src-tauri/target/release/keplura-desktop \
    --jar ../../server/target/keplura-server-0.1.0.jar \
    --email qa-pkg@e2e.example --password 'E2e-Pass-123'
"""
import argparse
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import gi

gi.require_version("Atspi", "2.0")
from gi.repository import Atspi  # noqa: E402

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from packaged_login_e2e import (  # noqa: E402  (reuse the proven primitives)
    XTest,
    app_root,
    clean_app_profile,
    do_login,
    entry_nodes,
    focus_and_click,
    log,
    snapshot,
    start_app,
    stop_app,
    wait_for,
    wait_login_form,
    wait_session_view,
    walk,
)

API = "http://localhost:8080"
MOCK_PORT = 19923
MOCK_MODEL = "pkg-qwen"
STREAM_ANSWER = "PKGE2E-STREAK-ANTWORT-OK"
# The abort leg needs a LONG answer streamed at a moderate pace: WebKitGTK's
# fetch abort closes with FIN, so server-side writes into the half-closed
# socket keep succeeding for ~2 more chunks before the kernel RST bites —
# with only 4 tokens the whole answer was always already read (runs 8-11
# persisted 'aborted' with FULL text). Many tokens => detection lag lands
# mid-answer and the persisted content is genuinely partial.
STOP_ANSWER = "STREP-" + "-".join(f"S{n:02d}" for n in range(1, 25))
STOP_PREFIX = "STOP-TEST"


# ----------------------------------------------------- mock OpenAI upstream
class MockHandler(BaseHTTPRequestHandler):
    """Streams SSE deltas tokenwise (chunked framing); STOP prompts slow."""

    # HTTP/1.1 REQUIRED: manual chunked framing is only well-formed under
    # 1.1 — an HTTP/1.0 response with a chunked header makes the JDK client
    # on the server relay the raw hex-length lines as body bytes, the SSE
    # observer then never sees the bare "data: [DONE]" line and the turn
    # persists "incomplete" instead of "complete" (packaged e2e run 1).
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):  # silence
        pass

    def do_POST(self):
        length = int(self.headers.get("content-length") or 0)
        raw = self.rfile.read(length)
        if not self.path.endswith("/chat/completions"):
            self.send_response(404)
            self.end_headers()
            return
        try:
            body = json.loads(raw or b"{}")
            msgs = body.get("messages") or []
            user = next((m for m in reversed(msgs) if m.get("role") == "user"), {})
        except Exception:
            user = {}
        slow = str(user.get("content", "")).startswith(STOP_PREFIX)
        answer = STOP_ANSWER if slow else STREAM_ANSWER
        # The tokenwise-render leg MUST pace the stream: an AT-SPI snapshot
        # walk costs ~0.2s, so a fast stream would land the whole answer in
        # one poll and the >=2-partials assertion could never hold.
        paced = slow or "Packetstream" in str(user.get("content", ""))
        self.send_response(200)
        self.send_header("content-type", "text/event-stream")
        self.send_header("cache-control", "no-cache")
        self.send_header("transfer-encoding", "chunked")
        self.end_headers()

        def chunk(obj):
            data = (f"data: {json.dumps(obj)}\n\n").encode()
            self.wfile.write(f"{len(data):x}\r\n".encode() + data + b"\r\n")
            self.wfile.flush()

        try:
            for i, tok in enumerate(answer.split("-")):
                piece = tok if i == 0 else "-" + tok
                chunk({"id": "p1", "model": MOCK_MODEL,
                       "choices": [{"index": 0, "delta": {"content": piece}}]})
                # Both streamed legs are paced: the tokenwise-render leg so
                # partial snapshots differ, the abort leg so the server's
                # broken-pipe detection (a chunk or two after the client's
                # abort) lands clearly BEFORE the answer would end.
                time.sleep(0.45 if paced else 0.12)
            chunk({"id": "p9", "model": MOCK_MODEL,
                   "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]})
            chunk_data = b"data: [DONE]\n\n"
            self.wfile.write(f"{len(chunk_data):x}\r\n".encode() + chunk_data + b"\r\n")
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass  # stop button mid-stream: expected

    def do_OPTIONS(self):
        self.send_response(404)
        self.end_headers()


def start_mock():
    srv = ThreadingHTTPServer(("127.0.0.1", MOCK_PORT), MockHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


# ---------------------------------------------------------------- server
def server_alive():
    try:
        with urllib.request.urlopen(API + "/api/v1/health", timeout=2) as r:
            return r.status == 200
    except Exception:
        return False


def wait_server(want=True, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if server_alive() == want:
            return
        time.sleep(0.5)
    raise AssertionError(f"server alive={want} not reached")


def start_server(jar, workdir, email, password):
    # A leftover H2 file from an interrupted earlier run would poison the
    # "fresh db" assertions (run5 saw org=3 on a supposed fresh boot).
    shutil.rmtree(workdir, ignore_errors=True)
    os.makedirs(workdir, exist_ok=True)
    logf = open(os.path.join(workdir, "server.log"), "ab")
    env = dict(
        os.environ,
        KEPLURA_DB_URL=f"jdbc:h2:file:{workdir}/e2e-db;AUTO_SERVER=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        KEPLURA_GATEWAY_BASE_URL=f"http://127.0.0.1:{MOCK_PORT}/v1",
        KEPLURA_GATEWAY_API_KEY="***",
        KEPLURA_CHAT_MODEL=MOCK_MODEL,
    )
    p = subprocess.Popen(
        ["java", "-Xmx320m", "-jar", jar,
         "--spring.profiles.active=dev",
         "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
         f"--keplura.dev.seed.email={email}",
         f"--keplura.dev.seed.password={password}",
         "--keplura.dev.seed.display-name=E2E Pkg Chat"],
        cwd=workdir, stdout=logf, stderr=subprocess.STDOUT, env=env,
    )
    wait_server(True)
    return p


def stop_server(p):
    if p and p.poll() is None:
        os.kill(p.pid, signal.SIGINT)
        try:
            p.wait(timeout=20)
        except subprocess.TimeoutExpired:
            p.kill()
    wait_server(False, timeout=60)


class Session:
    """REST session for server-side FIXTURES only (org + space provisioning
    and independent truth checks) — the chat flow itself runs in the app."""

    def __init__(self, email, password):
        self.cookie = None
        self.csrf = None
        last = None
        for attempt in range(8):
            req = urllib.request.Request(
                API + "/api/v1/auth/login",
                data=json.dumps({"email": email, "password": password}).encode(),
                headers={"content-type": "application/json"}, method="POST")
            try:
                with urllib.request.urlopen(req) as r:
                    # SEC-2: login now answers TWO Set-Cookie headers (session +
                    # keplura_csrf double-submit pair) — get_all keeps both.
                    pairs = [c.split(";")[0]
                             for c in (r.headers.get_all("set-cookie") or [])]
                    self.cookie = "; ".join(pairs) if pairs else None
                    assert self.cookie, "login answered without a session cookie"
                    body = json.loads(r.read())
                    self.csrf = body.get("csrfToken")
                    assert self.csrf, "login answered without a csrfToken"
                    return
            except urllib.error.HTTPError as e:
                last = e.code
                # 401: the dev seeder commits AFTER Tomcat accepts requests,
                # so a fixture login that lands in that window is rejected —
                # retry briefly. 429: login rate-limit can bite warm boot.
                if e.code not in (401, 429):
                    raise
            time.sleep(2.0)
        raise AssertionError(f"fixture login kept failing (last={last})")

    def call(self, method, path, payload=None):
        headers = {"content-type": "application/json",
                   **({"cookie": self.cookie} if self.cookie else {})}
        # SEC-2: the session gate demands the double-submit header on every
        # state-changing method (login itself was exempt, everything after isn't).
        if self.csrf and method.upper() not in ("GET", "HEAD", "OPTIONS"):
            headers["X-CSRF-Token"] = self.csrf
        return self._raw(method, path, payload, headers)

    def csrf_negative(self, path, payload):
        """Forged-write stand-in: session cookie rides, token header missing.
        Returns (status, body-snippet) — caller asserts 403 CSRF_INVALID."""
        headers = {"content-type": "application/json",
                   **({"cookie": self.cookie} if self.cookie else {})}
        return self._raw("POST", path, payload, headers, want_body=True)

    def _raw(self, method, path, payload, headers, want_body=False):
        req = urllib.request.Request(
            API + path,
            data=json.dumps(payload).encode() if payload is not None else None,
            headers=headers,
            method=method)
        try:
            with urllib.request.urlopen(req) as r:
                body = r.read()
                if want_body:
                    return r.status, body.decode("utf-8", "replace")[:80]
                return r.status, json.loads(body) if body else None
        except urllib.error.HTTPError as e:
            if want_body:
                return e.code, e.read().decode("utf-8", "replace")[:80]
            return e.code, None

    def ensure_org_space(self, org_name, space_name):
        st, orgs = self.call("GET", "/api/v1/orgs")
        assert st == 200, f"fixture login orgs {st}"
        org = next((o for o in orgs if o["name"] == org_name), None)
        if org is None:
            st, org = self.call("POST", "/api/v1/orgs", {"name": org_name})
            assert st == 201, f"org create {st}"
        oid = org["id"]
        st, spaces = self.call("GET", f"/api/v1/orgs/{oid}/spaces")
        assert st == 200
        space = next((s for s in spaces if s["name"] == space_name), None)
        if space is None:
            st, space = self.call("POST", f"/api/v1/orgs/{oid}/spaces",
                                  {"name": space_name})
            assert st in (200, 201), f"space create {st}"
        return oid, space["id"]

    def conversations(self, oid, sid):
        st, rows = self.call("GET", f"/api/v1/orgs/{oid}/spaces/{sid}/chat/conversations")
        assert st == 200, f"conv list {st}"
        return rows or []

    def transcript(self, oid, cid):
        st, t = self.call("GET", f"/api/v1/orgs/{oid}/chat/conversations/{cid}")
        assert st == 200
        return t["messages"]


# ------------------------------------------------------------- AT-SPI chat
def click_named(app_name, label, exact=True, timeout=20):
    """grabFocus + space on the push button whose NAME matches exactly (the
    login harness's substring match would hit 'Neuer Chat' for 'Chat')."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for acc in walk(app_root(app_name)):
            try:
                if acc.get_role_name() == "push button":
                    nm = (acc.get_name() or "").strip()
                    if (nm == label) if exact else (label in nm):
                        acc.grab_focus()
                        time.sleep(0.25)
                        return True
            except Exception:
                continue
        time.sleep(0.5)
    raise AssertionError(f"no push button named {label!r}")


def press(app_name, label, x):
    click_named(app_name, label)
    x.press("space")


def prompt_entry(app_name):
    """The composer textarea (placeholder-derived name), by AT-SPI."""
    deadline = time.time() + 20
    while time.time() < deadline:
        for acc in walk(app_root(app_name)):
            try:
                if acc.get_role_name() in ("entry", "text", "password text"):
                    nm = acc.get_name() or ""
                    if "Frage" in nm:
                        return acc
            except Exception:
                continue
        time.sleep(0.4)
    raise AssertionError("chat prompt textarea not found")


def streamed_texts(app_name):
    """All visible static-text nodes that are answer fragments — used to
    observe the tokenwise growth of the streaming row."""
    texts = []
    for role, nm, t in snapshot(app_name):
        if not t:
            continue
        c = t.strip()
        if c and (c.startswith("PKGE2E") or c.startswith("STREP")):
            texts.append(c)
    return texts


def wait_stream_grew_then_done(app_name, x, full=STREAM_ANSWER,
                               timeout=40):
    """Assert the streaming row was observed at >=2 DISTINCT partial lengths
    before the final full text (tokenwise render), then the fertig pill."""
    seen = set()
    deadline = time.time() + timeout
    done = False
    while time.time() < deadline:
        for t in streamed_texts(app_name):
            if t == full:
                done = True
            elif full.startswith(t):
                seen.add(len(t))
        if done:
            break
        time.sleep(0.15)
    assert done, f"final streamed text never reached {full!r}; parts seen={sorted(seen)}"
    assert len(seen) >= 2, (
        f"tokenwise render not proven (partial lengths seen: {sorted(seen)})")
    deadline = time.time() + 25
    while time.time() < deadline:
        rows = snapshot(app_name)
        if any("fertig" in (n + " " + t) for _, n, t in rows):
            return sorted(seen)
        time.sleep(0.5)
    rows = snapshot(app_name)
    dump = [f"{r}|{n[:30]}|{t[:70]}" for r, n, t in rows
            if any(k in (n + " " + t) for k in
                   ("fertig", "gestoppt", "unvollst", "PKGE2E", "Zeig"))]
    raise AssertionError(f"'fertig' pill never rendered; relevant rows: {dump}")


def wait_stop_row(app_name, timeout=30):
    """The aborted turn persists 'gestoppt' with non-empty partial text — the
    settle-poll in ChatView re-reads it; the pill proves arrival."""
    wait_for(app_name, contains="gestoppt", timeout=timeout)


def type_prompt(x, app_name, text):
    e = prompt_entry(app_name)
    e.grab_focus()
    time.sleep(0.3)
    x.type_text(text)
    got = ""
    for _ in range(20):
        # read the field back via its live Atspi node
        try:
            n = e.get_character_count()
            got = Atspi.Text.get_text(e, 0, n) or ""
        except Exception:
            got = ""
        if text in got:
            break
        time.sleep(0.2)
    assert text in got, f"prompt readback failed: {got!r}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bin", required=True)
    ap.add_argument("--jar", required=True)
    ap.add_argument("--email", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--app-name", default="keplura-desktop")
    args = ap.parse_args()
    stamp = int(time.time())
    org_name = f"PKG Chat {stamp}"
    space_name = "PKG Space"

    mock = start_mock()
    x = XTest()
    clean_app_profile()
    app = None
    srv = None
    try:
        jar = os.path.abspath(args.jar)
        srv = start_server(jar, "/tmp/e2e-pkgchat-server1", args.email, args.password)

        # fixtures: org + space through REST as the SAME account
        s = Session(args.email, args.password)
        oid, sid = s.ensure_org_space(org_name, space_name)
        assert len(s.conversations(oid, sid)) == 0
        log(step="0_fixture_org_space", ok=True, org=oid, space=sid)

        # -- 1. login, open Chat: fresh account -> empty conversation list
        app = start_app(os.path.abspath(args.bin))
        do_login(x, args)
        wait_session_view(args.app_name)
        press(args.app_name, "Chat", x)
        try:
            wait_for(args.app_name, contains="Noch keine Unterhaltung")
        except AssertionError:
            btns = [n for r, n, t in snapshot(args.app_name)
                    if r == "push button" and n][:30]
            raise AssertionError(f"chat-empty not reached; buttons={btns}")
        log(step="1_login_chat_empty", ok=True)

        # -- 2. plain chat streams tokenwise and completes
        press(args.app_name, "Neuer Chat", x)
        time.sleep(0.6)
        type_prompt(x, args.app_name, "Zeig Packetstream")
        x.press("Return")  # Enter sends (textarea keydown handler)
        parts = wait_stream_grew_then_done(args.app_name, x)
        log(step="2_stream_tokenwise", ok=True, partials=parts, answer=STREAM_ANSWER)

        # -- 3. stop mid-stream on a second conversation
        press(args.app_name, "Neuer Chat", x)
        time.sleep(0.6)
        type_prompt(x, args.app_name, STOP_PREFIX + " langsam")
        x.press("Return")
        # wait until streaming visibly started (a partial fragment of the
        # LONG stop answer), THEN press Stopp — ~11s of stream remain.
        deadline = time.time() + 20
        started = False
        while time.time() < deadline:
            parts = [t for t in streamed_texts(args.app_name)
                     if t != STOP_ANSWER and STOP_ANSWER.startswith(t)]
            if parts:
                started = True
                break
            time.sleep(0.15)
        assert started, "slow stream never started"
        press(args.app_name, "Stopp", x)
        wait_stop_row(args.app_name)
        convs = s.conversations(oid, sid)
        assert len(convs) == 2, f"expected 2 conversations, got {len(convs)}"
        stopped = max(convs, key=lambda c: c["id"])
        # Server truth settles ASYNC: the pill in step 3 is the client-side
        # optimistic state; the aborted row is persisted only after the relay
        # notices the disconnect (measured ~0.5-1s in the browser e2e). Poll.
        arows = []
        dbg = []
        deadline = time.time() + 45
        while time.time() < deadline:
            st, t = s.call("GET", f"/api/v1/orgs/{oid}/chat/conversations/{stopped['id']}")
            msgs = (t or {}).get("messages") if t else None
            arows = [m for m in (msgs or []) if m["role"] == "assistant"]
            dbg.append((st, len(msgs or []), [m["status"] for m in arows]))
            if arows and arows[-1]["status"] != "incomplete":
                break
            time.sleep(0.5)
        assert arows and arows[-1]["status"] == "aborted", \
            f"server truth: last assistant row not aborted: {arows[-1:]} polls={dbg[-6:]}"
        assert 0 < len(arows[-1]["content"]) < len(STOP_ANSWER), \
            f"partial text wrong length: {arows[-1]['content']!r}"
        log(step="3_stop_aborts", ok=True,
            persisted=arows[-1]["status"], partial=len(arows[-1]["content"]))

        # -- 4. app restart (native cookie restore): history from the server
        stop_app(app)
        app = start_app(os.path.abspath(args.bin))
        wait_session_view(args.app_name, timeout=35)
        press(args.app_name, "Chat", x)
        wait_for(args.app_name, contains="Neuer Chat")
        # After the first turn the server renames each thread to its prompt,
        # so the two list rows are push buttons named "Zeig Packetstream"
        # (completed, older) and "STOP-TEST langsam" (stopped, newer). List
        # order is activity-desc: rows[0]=stopped, rows[1]=completed. Open the
        # completed one — its re-read transcript must show full text + fertig.
        convs = s.conversations(oid, sid)
        assert len(convs) == 2
        rows = [a for a in walk(app_root(args.app_name))
                if a.get_role_name() == "push button"
                and (a.get_name() or "").startswith("Zeig Packetstream")]
        assert len(rows) == 1, f"completed conv row after restart: {len(rows)}"
        rows[0].grab_focus()
        x.press("space")
        time.sleep(1.0)
        wait_for(args.app_name, contains=STREAM_ANSWER, timeout=20)
        wait_for(args.app_name, contains="fertig", timeout=10)
        log(step="4_reload_history", ok=True, restored=len(rows))

        # -- 5. logout (navigate to Wissen for the Abmelden button). The
        # sidebar Wissen button NAME carries the KB-count badge ("Wissen 0"),
        # so match by prefix, not exactly.
        click_named(args.app_name, "Wissen", exact=False)
        x.press("space")
        wait_for(args.app_name, contains="Angemeldet als", timeout=15)
        focus_and_click(args.app_name, "push button", "Abmelden")
        time.sleep(0.4)
        x.press("space")
        wait_for(args.app_name, contains="Angemeldet als", want=False, timeout=20)
        wait_login_form(args.app_name, timeout=20)
        log(step="5_logout", ok=True)

        # -- 6. server restart on a FRESH db: stale native cookie 401s, the
        #       app must land on the login form (teardown), and after re-login
        #       the Chat view is EMPTY — no tenant refs survive the session.
        stop_app(app)
        stop_server(srv)
        srv = start_server(jar, "/tmp/e2e-pkgchat-server2", args.email, args.password)
        # fresh db2 fixtures BEFORE the app logs in, so org auto-select works
        s2 = Session(args.email, args.password)
        oid2, sid2 = s2.ensure_org_space(org_name + " B", space_name)
        app = start_app(os.path.abspath(args.bin))
        wait_login_form(args.app_name, timeout=35)
        stale = 0
        try:
            with open("/tmp/e2e-pkgchat-server2/server.log", errors="replace") as f:
                stale = len(re.findall(r"GET /api/v1/orgs -> 401", f.read()))
        except FileNotFoundError:
            pass
        assert stale >= 1, "server log shows no 401 replay — cookie not persisted?"
        do_login(x, args)
        wait_session_view(args.app_name)
        press(args.app_name, "Chat", x)
        wait_for(args.app_name, contains="Noch keine Unterhaltung")
        log(step="6_stale_session_teardown", ok=True, stale_replay_401=stale)

        # -- 7. sanity on the recovered session: chat works again
        press(args.app_name, "Neuer Chat", x)
        time.sleep(0.6)
        type_prompt(x, args.app_name, "Nochmal Packetstream")
        x.press("Return")
        wait_stream_grew_then_done(args.app_name, x)
        assert len(s2.conversations(oid2, sid2)) == 1
        log(step="7_recovered_session_chats", ok=True)

        log(result="ALL_STEPS_PASSED")
        return 0
    except Exception as e:
        import traceback
        traceback.print_exc(file=sys.stderr)
        log(result="FAILED", error=f"{type(e).__name__}: {e}")
        return 1
    finally:
        stop_app(app)
        try:
            stop_server(srv)
        except Exception:
            pass
        mock.shutdown()


if __name__ == "__main__":
    sys.exit(main())
