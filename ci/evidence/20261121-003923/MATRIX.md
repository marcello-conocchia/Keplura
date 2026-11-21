# ci-local result matrix — run 20261121-003923

- repo HEAD: 237b3ed
- branch: feat/oversharing-content
- command: bash scripts/ci-local.sh 
- result: **PASS**

| stage | result | note |
|---|---|---|
| server:local-reader | PASS | locked Rust build and FD snapshot regressions |
| server:package | PASS | compile + fat jar |
| server:chunk-1 | PASS | 12 classes — [INFO] Tests run: 84, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-2 | PASS | 12 classes — [INFO] Tests run: 88, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-3 | PASS | 12 classes — [INFO] Tests run: 114, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-4 | PASS | 12 classes — [INFO] Tests run: 71, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-5 | PASS | 12 classes — [INFO] Tests run: 91, Failures: 0, Errors: 0, Skipped: 1 |
| server:chunk-6 | PASS | 12 classes — [INFO] Tests run: 69, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-7 | PASS | 8 classes — [INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0 |
| server:manifest-audit | PASS | 80 classes, each exactly once in 7 chunks |
| desktop:build | PASS | npm ci + vue-tsc + vite build |
| desktop:cargo-check | PASS | cargo check --all-targets |
| e2e | SKIP | no suite selected (--suite/--with-e2e) |
