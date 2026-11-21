# ci-local result matrix — run 20261121-200201

- repo HEAD: 15e3b63
- branch: 
- command: bash scripts/ci-local.sh 
- result: **PASS**

| stage | result | note |
|---|---|---|
| server:local-reader | PASS | locked Rust build and FD snapshot regressions |
| server:package | PASS | compile + fat jar |
| server:chunk-1 | PASS | 12 classes — [INFO] Tests run: 100, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-2 | PASS | 12 classes — [INFO] Tests run: 95, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-3 | PASS | 12 classes — [INFO] Tests run: 128, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-4 | PASS | 12 classes — [INFO] Tests run: 68, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-5 | PASS | 12 classes — [INFO] Tests run: 98, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-6 | PASS | 12 classes — [INFO] Tests run: 74, Failures: 0, Errors: 0, Skipped: 1 |
| server:chunk-7 | PASS | 12 classes — [INFO] Tests run: 113, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-8 | PASS | 1 classes — [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 |
| server:manifest-audit | PASS | 85 classes, each exactly once in 8 chunks |
| desktop:build | PASS | npm ci + vue-tsc + vite build |
| desktop:cargo-check | PASS | cargo check --all-targets |
| e2e | SKIP | no suite selected (--suite/--with-e2e) |
