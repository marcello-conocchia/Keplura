# Release process

How a Keplura version is cut and published. Releases and deployments are done
by maintainers only; this document describes the steps so they are
reproducible. The detailed internal gate list (German) is
[RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

> Status: no release has been published yet. The first planned release is
> `0.1.0`. Publishing requires the product owner's explicit approval
> (license chosen, readiness blockers closed, see
> [OPEN_SOURCE_READINESS.md](OPEN_SOURCE_READINESS.md)).

## Versioning

- [Semantic Versioning](https://semver.org/). Until `1.0.0`, a minor bump may
  break compatibility; the changelog marks it **Breaking**.
- One version for the whole product. It appears in four places and must match:
  `server/pom.xml`, `desktop/package.json`, `desktop/src-tauri/Cargo.toml`,
  `desktop/src-tauri/tauri.conf.json`. The server reports it at
  `GET /api/v1/system/info`.
- Git tags are `v<version>`, e.g. `v0.1.0`, created on `main`.

## Steps

1. **Freeze.** Pick the `main` commit to release. No open Class-A findings.
2. **Version and changelog.** In one PR: bump the version in the four files,
   move the **Unreleased** entries of [CHANGELOG.md](../CHANGELOG.md) under the
   new version with today's date, regenerate the license inventory:

   ```bash
   node scripts/third-party-licenses.cjs > docs/legal/THIRD_PARTY_LICENSES.md
   ```

3. **Linux gate.** On the release commit, as a non-root user with `acl`:

   ```bash
   bash scripts/ci-local.sh --with-e2e
   ```

   Keep the evidence folder (`ci/evidence/<timestamp>/`) and link it in the
   release notes.
4. **Installer and update check.** On a clean Linux VM: fresh
   `deploy/install.sh`, `node deploy/pilot-check.cjs`, then an update from the
   previous release with `scripts/backup.sh` → `git checkout v<new>` →
   `./install.sh`. Run `bash deploy/test-install.sh`.
5. **Cross-platform desktop builds.** Build the desktop app on Linux, Windows
   and macOS (`npm ci && npm run tauri build`), start it and sign in against
   the test server. Windows/macOS signing and notarisation are separate
   decisions (cost, accounts).
6. **Artifacts.** For every published file create a SHA-256 checksum:

   ```bash
   sha256sum Keplura_<version>_amd64.deb keplura-server-<version>.jar > SHA256SUMS
   ```

   Recommended, not yet automated: an SBOM per component (e.g. CycloneDX for
   Maven/npm/Cargo) attached to the release.
7. **Tag and publish.** After approval: tag `v<version>` on the merged release
   commit, push the tag, create a GitHub release with the changelog section,
   checksums, evidence link and upgrade notes. Container images are not
   published yet; operators build the image locally with `install.sh`.
8. **Announce** security-relevant fixes with a GitHub security advisory.

## Reproducibility notes

- Dependency versions are pinned by `desktop/package-lock.json`,
  `desktop/src-tauri/Cargo.lock`, `server/native/local-reader/Cargo.lock` and
  explicit versions in `server/pom.xml` (Spring Boot BOM). Use `npm ci` and
  `cargo build --locked`.
- The server image (`deploy/Dockerfile.server`) builds everything from source
  inside containers (Node 22, Maven + Temurin 21, Rust 1.100). Base images are
  referenced by tag, not digest; pin digests if bit-for-bit reproducible images
  are required.
- `ollama/ollama:latest` and `vllm/vllm-openai:latest` in the Compose files
  float. Pin versions for a release you support long-term
  (`OLLAMA_IMAGE` can override the image in `compose.prod.yml`).
