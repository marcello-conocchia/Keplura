#!/usr/bin/env bash
set -euo pipefail
cd /review/repo
if test -d ci/evidence/20261122-121520 && ! git ls-files --error-unmatch ci/evidence/20261122-121520/MATRIX.md >/dev/null 2>&1; then
  mv ci/evidence/20261122-121520 /tmp/tu04a-evidence-before-tu04b
fi
git fetch /source feat/tool-use-document-read
git checkout --detach 8665d0d655740ecaca3840a912024c93ad35110f
test -z "$(git status --porcelain --untracked-files=no)"
test "$(id -u)" -ne 0
{ id; setfacl --version | head -1; git rev-parse HEAD; git status --short --untracked-files=no; } > /tmp/tu04b-environment.txt
cat /tmp/tu04b-environment.txt
set +e
bash scripts/ci-local.sh > /tmp/tu04b-ci-console.log 2>&1
result=$?
printf "%s\n" "$result" > /tmp/tu04b-ci-exit
printf "ci_exit=%s\n" "$result" >> /tmp/tu04b-environment.txt
tail -n22 /tmp/tu04b-ci-console.log
exit "$result"