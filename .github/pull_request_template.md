## What and why

<!-- What does this change do, and why is it needed? Link the issue. -->

## Risk class

<!-- One line: Class A (trust-critical: auth, sessions, CSRF, roles, tenant
isolation, document permissions, data sources, secrets, external hosts,
upload/parsing/OCR, data-changing migrations, new runtime dependencies) or
Class B (everything else) — and why. When in doubt: Class A.
See CONTRIBUTING.md. -->

Class:

## How it was tested

<!-- Targeted tests you ran, plus the local CI gate on the final commit:
`bash scripts/ci-local.sh` (non-root, with `acl`) → evidence folder. -->

- [ ] Tests for the changed area added or updated
- [ ] `scripts/ci-local.sh` green on the final commit (evidence: `ci/evidence/...`)
- [ ] Screenshot attached if the UI changed

## Checklist

- [ ] No secrets, real credentials or personal data in code, tests, logs or screenshots
- [ ] `contracts/openapi.yaml` updated for API changes
- [ ] New Flyway migration instead of editing an applied one (if the schema changed)
- [ ] `CHANGELOG.md` updated for user-visible changes
- [ ] New runtime dependency: license checked, inventory regenerated
