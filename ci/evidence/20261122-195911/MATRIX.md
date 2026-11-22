# ci-local result matrix — run 20261122-195911

- repo HEAD: 8665d0d
- branch: 
- command: bash scripts/ci-local.sh 
- result: **PASS**

| stage | result | note |
|---|---|---|
| server:local-reader | PASS | locked Rust build and FD snapshot regressions |
| server:package | PASS | compile + fat jar |
| server:chunk-1 | PASS | 12 classes — [INFO] Tests run: 166, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-2 | PASS | 12 classes — [INFO] Tests run: 108, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-3 | PASS | 12 classes — [INFO] Tests run: 131, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-4 | PASS | 12 classes — [INFO] Tests run: 80, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-5 | PASS | 12 classes — [INFO] Tests run: 96, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-6 | PASS | 12 classes — [INFO] Tests run: 83, Failures: 0, Errors: 0, Skipped: 1 |
| server:chunk-7 | PASS | 12 classes — [INFO] Tests run: 138, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-8 | PASS | 4 classes — [INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 0 |
| server:manifest-audit | PASS | 88 classes, each exactly once in 8 chunks |
| desktop:build | PASS | npm ci + vue-tsc + vite build |
| desktop:cargo-check | PASS | cargo check --all-targets |
| e2e | SKIP | no suite selected (--suite/--with-e2e) |
