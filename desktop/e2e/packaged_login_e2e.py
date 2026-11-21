#!/usr/bin/env python3
"""Packaged-desktop-app E2E driver for M2-POLISH (ADR-0005 evidence).

Runs the REAL `tauri build` binary under Xvfb (native GTK window + WebKitGTK
webview — the actual packaged product), drives it through AT-SPI2 (the same
accessibility tree a screen reader consumes) plus XTEST key events, against a
REAL Spring Boot server. Steps (PO mandate):

  1. launch fresh            -> login form
  2. login                   -> session card, empty-orgs hint
  3. app restart             -> session restored from the NATIVE cookie jar
  4. logout                  -> back to login form
  5. login, server restart   -> restored cookie is STALE -> login form again
                                (and the server log proves the jar REPLAYED
                                 the persisted cookie: 401 on /api/v1/orgs)
  6. server stopped          -> "Not reachable" card, still functional UI

Cookie safety is asserted from outside (no test hooks in app code): the only
way restore can work after a process restart is the persistent native jar,
and the webview never has a cookie of its own to leak.

Usage:
  dbus-run-session -- xvfb-run -a -s "-screen 0 1280x800x24" \
    python3 packaged_login_e2e.py --bin <app-binary> --jar <server-jar> \
    --email qa@e2e.example --password 'E2e-Pass-123'
JSON-lines report to stdout; exit 0 only when every step passed.
"""
import argparse
import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.request

import gi
gi.require_version("Atspi", "2.0")
from gi.repository import Atspi  # noqa: E402

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from xtest import XTest  # noqa: E402

BASE = "http://localhost:8080"


def log(**kw):
    print(json.dumps(kw, ensure_ascii=False), flush=True)


# ------------------------------------------------------------- AT-SPI helpers

def app_root(name, timeout=45):
    deadline = time.time() + timeout
    while time.time() < deadline:
        names = []
        for i in range(Atspi.get_desktop_count()):
            desk = Atspi.get_desktop(i)
            for j in range(desk.get_child_count()):
                try:
                    app = desk.get_child_at_index(j)
                    if app:
                        nm = app.get_name()
                        names.append(nm)
                        if nm == name:
                            return app
                except Exception:
                    continue
        time.sleep(0.5)
    raise RuntimeError(f"AT-SPI app '{name}' not found (visible apps: {names})")


def walk(node):
    try:
        yield node
    except Exception:
        return
    try:
        n = node.get_child_count()
    except Exception:
        n = 0
    for i in range(n):
        try:
            child = node.get_child_at_index(i)
        except Exception:
            child = None
        if child is not None:
            yield from walk(child)


def node_text(acc):
    """Accessible text content — pygobject's Atspi.Text needs an explicit end
    offset (the deprecated (-1) form returns ''), so bound by char count."""
    try:
        n = acc.get_character_count()
        return Atspi.Text.get_text(acc, 0, n)
    except Exception:
        return None


def snapshot(name):
    rows = []
    for acc in walk(app_root(name)):
        try:
            role, nm = acc.get_role_name(), acc.get_name()
        except Exception:
            continue
        txt = node_text(acc) or ""
        rows.append((role, nm or "", txt))
    return rows


def find(rows, role=None, contains=None):
    out = []
    for r, n, t in rows:
        if role and r != role:
            continue
        if contains and contains not in (n + " " + t):
            continue
        out.append((r, n, t))
    return out


def wait_for(name, role=None, contains=None, timeout=30, want=True, exact=False):
    deadline = time.time() + timeout
    last = []
    while time.time() < deadline:
        rows = snapshot(name)
        if exact:
            last = [(r, n, t) for (r, n, t) in find(rows, role, None)
                    if contains in (n, t)]
        else:
            last = find(rows, role, contains)
        if bool(last) == want:
            return last
        time.sleep(0.8)
    raise AssertionError(
        f"wait_for(role={role!r}, contains={contains!r}, want={want}) timed out; "
        f"last matches={last[:5]!r}, full snapshot head={snapshot(name)[:12]!r}"
    )


def wait_login_form(app_name, timeout=30):
    """Login form visible = the 'Anmelden' SUBMIT push button (exact name; the
    heading 'Anmelden' is a static-text node and 'Anmelden…' is the busy label
    — neither may match while we wait for the FORM specifically)."""
    return wait_for(app_name, role="push button", contains="Anmelden",
                    timeout=timeout, exact=True)


def wait_session_view(app_name, timeout=30):
    return wait_for(app_name, role="combo box", contains="Konto:", timeout=timeout)


def open_account_menu(x, app_name):
    """The redesigned sidebar exposes its account trigger as a combo box."""
    focus_and_click(app_name, "combo box", "Konto:")
    x.press("space")
    wait_for(app_name, role="menu item", contains="Abmelden", timeout=10)


def select_account_item(x, app_name, label):
    focus_and_click(app_name, "menu item", label)
    x.press("Return")
    time.sleep(0.4)


def focus_and_click(name, role, label):
    """grabFocus on the first matching node then press it via keyboard."""
    deadline = time.time() + 15
    while time.time() < deadline:
        for acc in walk(app_root(name)):
            try:
                if acc.get_role_name() == role and label in (acc.get_name() or ""):
                    acc.grab_focus()
                    return True
            except Exception:
                continue
        time.sleep(0.5)
    raise AssertionError(f"no {role} named {label!r} in {name}")


# ------------------------------------------------------------------- server

def server_alive():
    try:
        with urllib.request.urlopen(BASE + "/api/v1/health", timeout=2) as r:
            return r.status == 200
    except Exception:
        return False


def wait_server(want=True, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if server_alive() == want:
            return
        time.sleep(0.5)
    raise AssertionError(f"server alive={want} not reached in {timeout}s")


def start_server(jar, workdir, email, password):
    """Start the real jar with dev seeding in a FRESH workdir (clean Tomcat
    session store, clean H2) and return (proc, logfile)."""
    os.makedirs(workdir, exist_ok=True)
    logf = open(os.path.join(workdir, "server.log"), "ab")
    env = dict(os.environ, KEPLURA_DB_URL=f"jdbc:h2:file:{workdir}/e2e-db;AUTO_SERVER=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
    p = subprocess.Popen(
        ["java", "-jar", jar,
         "--spring.profiles.active=dev",
         "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
         f"--keplura.dev.seed.email={email}",
         f"--keplura.dev.seed.password={password}",
         "--keplura.dev.seed.display-name=E2E Tester"],
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
    wait_server(False)


# ---------------------------------------------------------------------- app

def start_app(binary):
    return subprocess.Popen(
        [binary],
        stdout=open("/tmp/e2e-app.log", "ab"), stderr=subprocess.STDOUT,
        env=dict(os.environ, WEBKIT_DISABLE_COMPOSITING_MODE="1", LANG="de_DE.UTF-8", LANGUAGE="de"),
    )


def stop_app(p):
    if p and p.poll() is None:
        p.terminate()
        try:
            p.wait(timeout=10)
        except subprocess.TimeoutExpired:
            p.kill()


def entry_nodes(app_name):
    """Text-input nodes of the login form in DOM order (email, then password).
    WebKit exposes <input type=password> with role 'password text'."""
    out = []
    for acc in walk(app_root(app_name)):
        try:
            if acc.get_role_name() in ("entry", "password text"):
                out.append(acc)
        except Exception:
            continue
    return out


def do_login(x, args, email=None):
    email = email or args.email
    wait_login_form(args.app_name)
    # Activate the window through the frame node, then drive the DOM-order
    # fields (email autofocused by LoginView; Tab reaches password). Every
    # keystroke batch is verified by reading the field back over AT-SPI —
    # a lost-focus or layout mismatch fails loudly here, not silently later.
    entries = []
    deadline = time.time() + 15
    while time.time() < deadline:
        try:
            frames = [c for c in walk(app_root(args.app_name))
                      if c.get_role_name() == "frame"]
            if frames:
                frames[0].grab_focus()
        except Exception:
            pass
        time.sleep(0.5)
        found = {n.get_name(): n for n in entry_nodes(args.app_name)}
        if "E-Mail oder Benutzername" in found and "Passwort" in found:
            entries = (found["E-Mail oder Benutzername"], found["Passwort"])
            break
    assert len(entries) == 2, "email+password entries not found in the a11y tree"
    email_node, password_node = entries
    assert (node_text(email_node) or "") == "", "email field not empty at start"
    email_node.grab_focus()
    time.sleep(0.3)
    x.type_text(email)
    got = node_text(email_node) or ""
    assert got == email, f"email readback mismatch: typed {email!r}, field holds {got!r}"
    x.press("Tab")
    time.sleep(0.35)
    x.type_text(args.password)
    got = node_text(password_node)
    if got is None:
        raise AssertionError("password field text not readable via AT-SPI")
    if got != args.password:
        # Entries often expose bullets instead of raw values in AT-SPI;
        # accept the exact text or a same-length masked value.
        masked = set(got) <= {"*", "\u2022"}
        assert masked and len(got) == len(args.password), \
            f"password readback mismatch: len(typed)={len(args.password)}, field={got!r}"
    x.press("Return")


def count_log_lines(path, pattern):
    try:
        with open(path, "r", errors="replace") as f:
            return len(re.findall(pattern, f.read()))
    except FileNotFoundError:
        return -1


# ------------------------------------------------------------------- main

def clean_app_profile():
    """Fresh start for the app: wipe config+cache (the NATIVE cookie jar
    lives in the app cache dir as `.cookies`) so step 1 provably begins with
    an empty jar (PO requirement: no test hooks in app code)."""
    import shutil
    home = os.path.expanduser("~")
    for base in (os.path.join(home, ".config"), os.path.join(home, ".cache"),
                 os.path.join(home, ".local", "share")):
        for d in (os.path.join(base, "com.keplura.desktop"),
                  os.path.join(base, "Keplura")):
            if os.path.isdir(d):
                shutil.rmtree(d, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bin", required=True)
    ap.add_argument("--jar", required=True)
    ap.add_argument("--email", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--app-name", default="keplura-desktop")
    args = ap.parse_args()

    clean_app_profile()
    x = XTest()
    app = None
    srv1 = srv2 = None
    try:
        jar = os.path.abspath(args.jar)
        srv1 = start_server(jar, "/tmp/e2e-server1", args.email, args.password)

        # -- 1. fresh launch: login form (native jar is empty)
        app = start_app(os.path.abspath(args.bin))
        wait_login_form(args.app_name)
        log(step="1_launch_fresh", ok=True)

        # -- 2. login through the real webview
        do_login(x, args)
        wait_session_view(args.app_name)
        wait_for(args.app_name, contains="Noch keine Organisation")
        log(step="2_login", ok=True)

        # -- 3. app restart: session must restore from the native jar
        stop_app(app)
        app = start_app(os.path.abspath(args.bin))
        wait_session_view(args.app_name, timeout=35)
        log(step="3_restore_after_restart", ok=True)

        # -- 4. logout via the account menu
        open_account_menu(x, args.app_name)
        select_account_item(x, args.app_name, "Abmelden")
        wait_for(args.app_name, role="combo box", contains="Konto:", want=False, timeout=20)
        wait_login_form(args.app_name, timeout=20)
        log(step="4_logout", ok=True)

        # -- 5. login again, then restart the SERVER (new process, new session
        #       store; user is re-seeded idempotently into the fresh dev DB, so
        #       only the SESSIONS differ). The persisted native cookie is now
        #       stale: restore must 401 -> login form.
        do_login(x, args)
        wait_session_view(args.app_name)
        stop_app(app)
        stop_server(srv1)
        srv2 = start_server(jar, "/tmp/e2e-server2", args.email, args.password)
        app = start_app(os.path.abspath(args.bin))
        wait_login_form(args.app_name, timeout=35)
        stale_replayed = count_log_lines("/tmp/e2e-server2/server.log", r"GET /api/v1/orgs -> 401")
        assert stale_replayed >= 1, \
            "server log shows no 401 on /orgs — native jar did not replay the persisted cookie"
        log(step="5_stale_session_rejected", ok=True, stale_replay_401_count=stale_replayed)

        # -- 5b. re-login against the fresh server to prove the flow is healthy
        do_login(x, args)
        wait_session_view(args.app_name)
        log(step="5b_relogin", ok=True)

        # -- 6. server unreachable: kill it, restart app, expect offline copy
        stop_app(app)
        stop_server(srv2)
        app = start_app(os.path.abspath(args.bin))
        wait_for(args.app_name, contains="Keine Verbindung", timeout=40)
        wait_login_form(args.app_name, timeout=20)
        log(step="6_server_unreachable", ok=True)

        log(result="ALL_STEPS_PASSED")
        return 0
    except Exception as e:
        log(result="FAILED", error=f"{type(e).__name__}: {e}")
        return 1
    finally:
        stop_app(app)
        stop_server(srv1)
        stop_server(srv2)


if __name__ == "__main__":
    sys.exit(main())
