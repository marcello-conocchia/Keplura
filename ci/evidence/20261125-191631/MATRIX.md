# ci-local result matrix — run 20261125-191631

- repo HEAD: 8387fc3
- branch: feat/tool-use-web-fetch
- command: bash scripts/ci-local.sh 
- result: **PASS**

| stage | result | note |
|---|---|---|
| server:local-reader | PASS | locked Rust build and FD snapshot regressions |
| server:package | PASS | compile + fat jar |
| server:chunk-1 | PASS | 12 classes — [INFO] Tests run: 209, Failures: 0, Errors: 0, Skipped: 3 |
| server:chunk-2 | PASS | 12 classes — [INFO] Tests run: 93, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-3 | PASS | 12 classes — [INFO] Tests run: 140, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-4 | PASS | 12 classes — [INFO] Tests run: 82, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-5 | PASS | 12 classes — [INFO] Tests run: 83, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-6 | PASS | 12 classes — [INFO] Tests run: 97, Failures: 0, Errors: 0, Skipped: 1 |
| server:chunk-7 | PASS | 12 classes — [INFO] Tests run: 111, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-8 | PASS | 8 classes — [INFO] Tests run: 175, Failures: 0, Errors: 0, Skipped: 0 |
| server:manifest-audit | PASS | 92 classes, each exactly once in 8 chunks |
| desktop:build | PASS | npm ci + vue-tsc + vite build |
| desktop:cargo-check | PASS | cargo check --all-targets |
| e2e | SKIP | no suite selected (--suite/--with-e2e) |
