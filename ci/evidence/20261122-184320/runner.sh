set -u
apt-get update -qq >/dev/null && apt-get install -y -qq acl git >/dev/null
useradd -m -u 1500 ci 2>/dev/null || true
chmod 755 /root; chmod -R a+rX /root/.cargo /root/.rustup 2>/dev/null
git clone -q -b ci-tu04a2 /in/ci.bundle /w
chown -R ci:ci /w /home/ci
runuser -u ci -- env HOME=/home/ci CARGO_HOME=/home/ci/.cargo RUSTUP_HOME=/root/.rustup PATH=/root/.cargo/bin:$PATH \
  bash -c 'cd /w && { id; setfacl --version | head -1; git rev-parse HEAD; } > /tmp/env.txt; cat /tmp/env.txt; bash scripts/ci-local.sh'
rc=$?
for d in /w/ci/evidence/2*; do [ -f "$d/MATRIX.md" ] && [ ! -f "$d/environment.txt" ] && { cat /tmp/env.txt; echo "ci_exit=$rc"; } > "$d/environment.txt"; done
cp -r /w/ci/evidence/2* /out/ 2>/dev/null
echo "CI EXIT: $rc"
exit $rc
