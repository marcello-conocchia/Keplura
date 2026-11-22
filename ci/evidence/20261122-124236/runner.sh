set -u
apt-get update -qq >/dev/null && apt-get install -y -qq acl git >/dev/null
useradd -m -u 1500 ci 2>/dev/null || true
chmod 755 /root; chmod -R a+rX /root/.cargo /root/.rustup 2>/dev/null
git clone -q -b ci-tu04a /in/ci.bundle /w
chown -R ci:ci /w /home/ci
echo "CI HEAD: $(git -C /w rev-parse HEAD) user=ci uid=1500 acl=$(command -v setfacl)"
runuser -u ci -- env HOME=/home/ci CARGO_HOME=/home/ci/.cargo RUSTUP_HOME=/root/.rustup PATH=/root/.cargo/bin:$PATH \
  bash -c 'cd /w && id && bash scripts/ci-local.sh'
rc=$?
cp -r /w/ci/evidence/2* /out/ 2>/dev/null
echo "CI EXIT: $rc"
exit $rc
