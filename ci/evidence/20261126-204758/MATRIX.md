# ci-local result matrix — run 20261126-204758

- repo HEAD: 37ea701
- branch: 
- command: bash scripts/ci-local.sh 
- result: **FAIL (exit 1)**

| stage | result | note |
|---|---|---|
| server:local-reader | PASS | locked Rust build and FD snapshot regressions |
| server:package | PASS | compile + fat jar |
| server:chunk-1 | PASS | 12 classes — [INFO] Tests run: 213, Failures: 0, Errors: 0, Skipped: 4 |
| server:chunk-2 | PASS | 12 classes — [INFO] Tests run: 93, Failures: 0, Errors: 0, Skipped: 0 |
| server:chunk-3 | FAIL | [ERROR] Tests run: 140, Failures: 2, Errors: 1, Skipped: 0 — test |
