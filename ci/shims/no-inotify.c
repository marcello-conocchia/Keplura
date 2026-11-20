/* ci/shims/no-inotify.c — LD_PRELOAD shim, DEV-GATE-INFRASTRUKTUR ONLY.
 * Wird ausschliesslich im lokalen CI-Gate gegen das tauri-CLI geladen
 * (scripts/ci-local-e2e-gate.sh). Kein Produktcode, kein Build-Input des
 * Produkts, nicht ins Binary eingebettet.
 *
 * WARUM: Der geteilte Host-Kernel (rootless podman, nested) hat
 * fs.inotify.max_user_instances=128 — per UID, systemweit, durch
 * leakedWatcher anderer Agent-Container erschöpft (Messung im Lauf
 * UI-REFRESH-1: inotify_init() -> EMFILE, obwohl im eigenen PID-Namespace
 * 0 Holder sichtbar sind). /proc/sys ist read-only, kein sudo — die
 * Obergrenze kann nur der Operator heben.
 *
 * WAS DAS PROBLEM TRIGGERT: tauri-cli 2.11.4 baut in Rust::new()
 * (interface/rust.rs:141-146) UNBEDINGT einen notify-Debouncer auf den
 * Cargo.toml — .unwrap() — obwohl `tauri build` Watchen per Design
 * deaktiviert (build::Options -> no_watch: true, rust.rs:71); die
 * Instanz ist im Build-Pfad toter Code. Bei EMFILE panic + core dump ->
 * Gate-Stufe e2e:packaged-build FAILS, ohne dass ein Produktbug vorliegt.
 *
 * WAS DER SHIM TUT: fängt die vier glibc-Wrapper der inotify-Syscalls ab:
 *   init/init1 -> dup eines persistenten, nie geschlossenen
 *     pipe-read-ends (O_NONBLOCK|O_CLOEXEC). epoll-tauglich (mio-Register
 *     OK; ein /dev/null-Fd gibt dagegen EPERM — verifiziert), liest nie
 *     Events (Schreibende bleibt offen), Thread parkt in epoll_wait und
 *     wird beim Debouncer-Drop über notify-s-eigenen Channel geweckt.
 *   add_watch -> synthetische wd, rm_watch -> 0.
 * Scope-Pruefung: node/libuv und Chromium rufen die Syscalls per
 * raw syscall(2) auf — NICHT interposed (vite-Watcher verhaelt sich
 * unveraendert; alle Browser-Suiten laufen wie bisher). cargo, rustc, Spring Boot
 * (keine WatchService im Testpfad), die Python-AT-SPI-Harnesses und das
 * gepackte Produkt-binary nutzen kein glibc-inotify. Einziger Nutzer im
 * ganzen Gate: der tote Build-Debouncer des CLI.
 *
 * Build: gcc -shared -fPIC -O2 -o /tmp/no-inotify.so ci/shims/no-inotify.c */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <errno.h>
#include <stdint.h>
#include <unistd.h>

static int g_rd = -1;
static int g_wr_keepalive = -1;

__attribute__((constructor)) static void make_pipe(void) {
  int fds[2];
  if (pipe2(fds, O_NONBLOCK | O_CLOEXEC) == 0) {
    g_rd = fds[0];
    g_wr_keepalive = fds[1]; /* bewusst offen: kein HUP/EPOLLERR */
  }
}

static int fake_fd(void) {
  if (g_rd < 0) { errno = EMFILE; return -1; }
  return fcntl(g_rd, F_DUPFD_CLOEXEC, 3);
}

int inotify_init(void) { return fake_fd(); }
int inotify_init1(int flags) { (void)flags; return fake_fd(); }

static unsigned g_wd = 1;
int inotify_add_watch(int fd, const char *path, uint32_t mask) {
  (void)fd; (void)path; (void)mask;
  return (int)(g_wd++);
}

int inotify_rm_watch(int fd, uint32_t wd) {
  (void)fd; (void)wd;
  return 0;
}
