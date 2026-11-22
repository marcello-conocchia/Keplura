# ci-local result matrix — run 20261122-124236

- repo HEAD: 97ede97
- branch: ci-tu04a
- command: bash scripts/ci-local.sh 
- result: **PASS**

| stage | result | note |
|---|---|---|
| server:local-reader | PASS | locked Rust build and FD snapshot regressions |
| server:package | PASS | compile + fat jar |
| server:chunk-1 | PASS | 12 classes — [INFO] Tests run: 158, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-2 | PASS | 12 classes — [INFO] Tests run: 95, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-3 | PASS | 12 classes — [INFO] Tests run: 128, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-4 | PASS | 12 classes — [INFO] Tests run: 68, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-5 | PASS | 12 classes — [INFO] Tests run: 96, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-6 | PASS | 12 classes — [INFO] Tests run: 78, Failures: 0, Errors: 0, Skipped: 1 |
| server:chunk-7 | PASS | 12 classes — [INFO] Tests run: 152, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-8 | PASS | 3 classes — [INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 |
| server:manifest-audit | PASS | 87 classes, each exactly once in 8 chunks |
| desktop:build | PASS | npm ci + vue-tsc + vite build |
| desktop:cargo-check | PASS | cargo check --all-targets |
| e2e | SKIP | no suite selected (--suite/--with-e2e) |
