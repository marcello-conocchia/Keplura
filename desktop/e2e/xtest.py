"""Minimal X11 keyboard driver over ctypes + libXtst (XTEST) — no deps beyond
system libs in the dev container (libX11, libXtst). Types REAL key events into
the focused widget; used by the packaged-app E2E driver.

Keysyms are resolved to keycodes AND shift-levels through the *current*
keyboard mapping (XGetKeyboardMapping), so typing is layout-correct for
whatever layout Xvfb loaded (the E2E forces `setxkbmap us` when available).
"""
import ctypes
import time

X11 = "libX11.so.6"
XTST = "libXtst.so.6"

# X11/keysymdef.h values for the printable ASCII this app needs.
KEYSYMS = {
    " ": 0x20, "!": 0x21, '"': 0x22, "#": 0x23, "$": 0x24, "%": 0x25,
    "&": 0x26, "'": 0x27, "(": 0x28, ")": 0x29, "*": 0x2a, "+": 0x2b,
    ",": 0x2c, "-": 0x2d, ".": 0x2e, "/": 0x2f, ":": 0x3a, ";": 0x3b,
    "<": 0x3c, "=": 0x3d, ">": 0x3e, "?": 0x3f, "@": 0x40,
    "[": 0x5b, "\\": 0x5c, "]": 0x5d, "^": 0x5e, "_": 0x5f, "`": 0x60,
    "{": 0x7b, "|": 0x7c, "}": 0x7d, "~": 0x7e,
    "Tab": 0xff09, "Return": 0xff0d, "space": 0x20,
    # DARK-FS packaged leg: the fullscreen shortcuts travel as real keysyms.
    # (X11 keysymdef: Escape 0xff1b; F1 = 0xffbe so F11 = 0xffbe + 10.)
    "Escape": 0xff1b, "F11": 0xffc8,
}
for _c in "abcdefghijklmnopqrstuvwxyz":
    KEYSYMS[_c] = ord(_c)
for _c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
    KEYSYMS[_c] = ord(_c)  # uppercase keysyms are the ASCII codes themselves
for _c in "0123456789":
    KEYSYMS[_c] = ord(_c)  # digit keysyms are the ASCII codes themselves

XK_Shift_L = 0xFFE1


class XTest:
    def __init__(self, display=None):
        self.x11 = ctypes.CDLL(X11)
        self.xtst = ctypes.CDLL(XTST)
        x11 = self.x11
        x11.XOpenDisplay.restype = ctypes.c_void_p
        x11.XKeysymToKeycode.restype = ctypes.c_uint
        x11.XKeysymToKeycode.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
        x11.XKeycodeToKeysym.restype = ctypes.c_ulong
        x11.XKeycodeToKeysym.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int]
        x11.XFlush.argtypes = [ctypes.c_void_p]
        x11.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]
        x11.XDisplayKeycodes.argtypes = [ctypes.c_void_p,
                                         ctypes.POINTER(ctypes.c_int),
                                         ctypes.POINTER(ctypes.c_int)]
        x11.XGetKeyboardMapping.restype = ctypes.POINTER(ctypes.c_ulong)
        x11.XGetKeyboardMapping.argtypes = [ctypes.c_void_p, ctypes.c_int,
                                            ctypes.c_int,
                                            ctypes.POINTER(ctypes.c_int)]
        x11.XFree.argtypes = [ctypes.c_void_p]
        self.xtst.XTestFakeKeyEvent.argtypes = [
            ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong
        ]
        self.dpy = x11.XOpenDisplay(display)
        if not self.dpy:
            raise RuntimeError("cannot open X display")
        self._shift_kc = self._keycode(XK_Shift_L)

    def _keycode(self, keysym):
        kc = self.x11.XKeysymToKeycode(self.dpy, keysym)
        if not kc:
            raise RuntimeError(f"no keycode for keysym {keysym:#x}")
        return kc

    def _fake_key(self, kc, down):
        self.xtst.XTestFakeKeyEvent(self.dpy, kc, 1 if down else 0, 0)
        self.x11.XFlush(self.dpy)

    def _needs_shift(self, kc, keysym):
        """True when `keysym` sits on an odd (shifted) level of keycode kc."""
        lo, hi = ctypes.c_int(), ctypes.c_int()
        self.x11.XDisplayKeycodes(self.dpy, ctypes.byref(lo), ctypes.byref(hi))
        per = ctypes.c_int()
        # one keycode at a time is valid; ask for the whole range in one go
        n = hi.value - lo.value + 1
        map_ = self.x11.XGetKeyboardMapping(self.dpy, lo.value, n, ctypes.byref(per))
        if not map_:
            raise RuntimeError("XGetKeyboardMapping failed")
        try:
            idx = (kc - lo.value) * per.value
            row = [map_[idx + j] for j in range(per.value)]
        finally:
            self.x11.XFree(ctypes.cast(map_, ctypes.c_void_p))
        for level, ks in enumerate(row):
            if ks == keysym:
                return level % 2 == 1
        # keysym not on this keycode's row (multi-keycode keysym): default to
        # unshifted — the readback check in the E2E catches actual mistakes.
        return False

    def press(self, key, delay=0.02):
        try:
            keysym = KEYSYMS[key]
        except KeyError:
            raise RuntimeError(f"unsupported key {key!r}")
        kc = self._keycode(keysym)
        shift = self._needs_shift(kc, keysym)
        if shift:
            self._fake_key(self._shift_kc, True)
        self._fake_key(kc, True)
        time.sleep(delay)
        self._fake_key(kc, False)
        if shift:
            self._fake_key(self._shift_kc, False)
        self.x11.XSync(self.dpy, 0)

    def type_text(self, text, delay=0.03):
        for ch in text:
            self.press(ch)
            time.sleep(delay)


if __name__ == "__main__":
    x = XTest()
    x.press("space")
    print("XTEST ok")
