# ci-local result matrix — run 20261126-205059

- repo HEAD: 37ea701
- branch: 
- command: bash scripts/ci-local.sh 
- result: **PASS**

| stage | result | note |
|---|---|---|
| server:local-reader | PASS | locked Rust build and FD snapshot regressions |
| server:package | PASS | compile + fat jar |
| server:chunk-1 | PASS | 12 classes — [INFO] Tests run: 213, Failures: 0, Errors: 0, Skipped: 4 |
| server:chunk-2 | PASS | 12 classes — [INFO] Tests run: 93, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-3 | PASS | 12 classes — [INFO] Tests run: 140, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-4 | PASS | 12 classes — [INFO] Tests run: 82, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-5 | PASS | 12 classes — [INFO] Tests run: 77, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-6 | PASS | 12 classes — [INFO] Tests run: 103, Failures: 0, Errors: 0, Skipped: 1 |
| server:chunk-7 | PASS | 12 classes — [INFO] Tests run: 73, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-8 | PASS | 12 classes — [INFO] Tests run: 164, Failures: 0, Errors: 0, Skipped: 1 |
| server:chunk-9 | PASS | 4 classes — [INFO] Tests run: 108, Failures: 0, Errors: 0, Skipped: 0 |
| server:manifest-audit | PASS | 100 classes, each exactly once in 9 chunks |
| desktop:build | PASS | npm ci + vue-tsc + vite build |
| desktop:cargo-check | PASS | cargo check --all-targets |
| e2e | SKIP | no suite selected (--suite/--with-e2e) |
