#!/usr/bin/env python3
"""DARK-FS packaged-app leg (native Tauri runtime: GTK window + WebKitGTK).

Proves the behaviours that ONLY exist in the packaged app (the browser suite
dark_flow_e2e.cjs covers token switching + the Fullscreen-API fallback):
  1. after login, the account menu exposes the theme/fullscreen controls,
  2. selecting Dunkel/Hell/Wie System changes actual native-window pixels
     to the corresponding surface colours, with screenshots as evidence,
  3. F11 reaches our webview key handler AND the native Tauri
     setFullscreen IPC completes without rejection. The completion proof is
     the button label flipping to "Vollbild verlassen": our code flips its
     fullscreen ref ONLY after `await win.setFullscreen(true)` resolved
     (a missing core:window:allow-set-fullscreen capability or a rejected
     IPC leaves the label untouched). Escape mirrors it back.

KNOWN ENVIRONMENT LIMIT (documented honestly, not hidden): under a bare
Xvfb there is NO window manager, so GTK's fullscreen request has nothing to
resize/reparent the window against — neither the geometry nor
_NET_WM_STATE_FULLSCREEN visibly change (verified empirically with debug
runs). This leg therefore proves wiring + capability + call completion on
the native runtime, not the pixel outcome of a WM honouring it; the WM part
is covered by the browser leg's real Fullscreen API and, when a desktop
session exists, by manual QA.

Method: packaged_login/chat primitives — AT-SPI2 for tree + focus, ctypes
XTEST for real key events (xtest.py carries the Escape/F11 keysyms). Zero
test hooks in app code.

Prereq: a seeded server jar answering on :8080 (the ci-local
"dark-packaged" wrapper boots it; manually see e2e/README.md).

Run:
  dbus-run-session -- xvfb-run -a -s "-screen 0 1280x800x24" \
    python3 packaged_dark_e2e.py --bin ../src-tauri/target/release/keplura-desktop \
    --email <seed-email> --password '<seed-secret>'
"""
import argparse
import json
import os
import sys
import time

import gi

gi.require_version("Atspi", "2.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Atspi, Gdk  # noqa: E402

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from packaged_login_e2e import (  # noqa: E402
    XTest,
    app_root,
    clean_app_profile,
    do_login,
    open_account_menu,
    select_account_item,
    server_alive,
    start_app,
    stop_app,
    walk,
    wait_for,
    wait_login_form,
    wait_session_view,
)

APP_NAME = "keplura-desktop"
step_no = 0


def log(step, **extra):
    global step_no
    step_no += 1
    print(json.dumps({"step": step, "no": step_no, "ok": True, **extra}, ensure_ascii=False), flush=True)


def fail(step, error, detail=None):
    print(json.dumps({"step": step, "no": step_no + 1, "ok": False, "error": error,
                      **({"detail": detail} if detail else {})}, ensure_ascii=False), flush=True)
    sys.exit(1)


# ------------------------------------------------- AT-SPI walking primitives

def focus_window(x):
    """Focus the webview WITHOUT activating a control: grabFocus on the frame
    node — the proven primitive from packaged_login_e2e.do_login — so the
    next XTEST keystroke lands inside the DOM, not on a focused button."""
    deadline = time.time() + 15
    while time.time() < deadline:
        try:
            frames = [c for c in walk(app_root(APP_NAME))
                      if c.get_role_name() == "frame"]
            if frames:
                frames[0].grab_focus()
                time.sleep(0.4)
                return True
        except Exception:
            pass
        time.sleep(0.5)
    return False


def palette(mode):
    """Surface colours from the authoritative System-1.0 tokens (UI-04), not hard-coded drift-prone values."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "design", "system-v1", "tokens.json")
    with open(path, encoding="utf-8") as fh:
        theme = json.load(fh)["themes"][mode]
    rgb = lambda h: tuple(int(h.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4))  # noqa: E731
    return {rgb(theme[k]) for k in ("canvas", "content", "surface", "sidebar")}


def assert_palette(mode):
    """Observe rendered pixels, independently of the stored theme selection."""
    root = Gdk.get_default_root_window()
    image = Gdk.pixbuf_get_from_window(root, 0, 0, root.get_width(), root.get_height())
    assert image is not None, "native screenshot unavailable"
    colours = palette(mode)
    pixels, stride, channels = image.get_pixels(), image.get_rowstride(), image.get_n_channels()
    matches = sum(tuple(pixels[y * stride + x * channels:y * stride + x * channels + 3]) in colours
                  for y in range(0, image.get_height(), 4) for x in range(0, image.get_width(), 4))
    out = os.environ.get("E2E_OUT", "/tmp/dark-packaged-evidence")
    os.makedirs(out, exist_ok=True)
    image.savev(os.path.join(out, f"native-{mode}.png"), "png", [], [])
    assert matches > 1000, f"{mode} surface colours absent from native window ({matches} samples)"
    return matches


# ------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bin", required=True)
    ap.add_argument("--email", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--app-name", default=APP_NAME)
    args = ap.parse_args()

    if not server_alive():
        fail("prereq", "no seeded server answering on http://localhost:8080")

    clean_app_profile()
    x = XTest()
    app = None
    try:
        app = start_app(os.path.abspath(args.bin))
        Atspi.init()
        app_root(APP_NAME)
        wait_login_form(APP_NAME)
        log("login-form")

        do_login(x, args, email=args.email)
        wait_session_view(APP_NAME, timeout=35)
        log("session-view")

        # -- 1/2. native account menu + actual rendered palettes -----------
        open_account_menu(x, APP_NAME)
        wait_for(APP_NAME, role="menu item", contains="Dunkel", timeout=10)
        wait_for(APP_NAME, role="menu item", contains="Vollbild aktivieren", timeout=10)
        log("sidebar-controls-present")
        select_account_item(x, APP_NAME, "Dunkel")
        log("theme-dark-native", surface_samples=assert_palette("dark"))
        open_account_menu(x, APP_NAME)
        select_account_item(x, APP_NAME, "Hell")
        log("theme-light-native", surface_samples=assert_palette("light"))
        open_account_menu(x, APP_NAME)
        select_account_item(x, APP_NAME, "Wie System")
        log("theme-reset-system", surface_samples=assert_palette("light"))

        # -- 3. F11 -> key handler runs, native setFullscreen IPC completes -
        # Pre: our ref must say "not fullscreen" (enter-label present).
        if not focus_window(x):
            fail("focus", "webview frame never focusable")
        x.press("F11")
        # Success == label flips to exit-state, which ONLY happens after
        # `await getCurrentWindow().setFullscreen(true)` RESOLVED (rejection
        # or a missing capability leaves the ref — and the label — alone).
        open_account_menu(x, APP_NAME)
        wait_for(APP_NAME, role="menu item", contains="Vollbild verlassen", timeout=10)
        log("f11-native-call-completed",
            note="label flip proves IPC Ok; WM-less Xvfb shows no geometry change (see docstring)")

        # Close the menu by selecting the current theme; Escape must reach
        # the app's fullscreen handler rather than the menu's dismiss handler.
        select_account_item(x, APP_NAME, "Wie System")
        # -- 4. Escape -> our Tauri-path handler exits again ----------------
        if not focus_window(x):
            fail("focus2", "webview frame never re-focusable")
        x.press("Escape")
        open_account_menu(x, APP_NAME)
        wait_for(APP_NAME, role="menu item", contains="Vollbild aktivieren", timeout=10)
        log("escape-exit")

        log("done", steps=step_no, result="ALL_STEPS_PASSED")
        return 0
    except SystemExit:
        raise
    except Exception as e:
        fail("harness", f"{type(e).__name__}: {e}")
    finally:
        stop_app(app)


if __name__ == "__main__":
    # main() returns 0 on the green path and fail() sys.exit(1)s otherwise —
    # NOT `main() or 1` (that would turn the success code 0 into exit 1; the
    # ci-local run_e2e wrapper asserts on the process exit, run 20260909-013014).
    sys.exit(main())
