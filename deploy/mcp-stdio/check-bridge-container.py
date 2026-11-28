"""Verify mount and secret boundaries of a running production bridge."""
import json
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 check-bridge-container.py <container-id>")

inspect = subprocess.check_output(["docker", "container", "inspect", "--", sys.argv[1]], text=True)
bridge, = json.loads(inspect)
assert bridge["State"]["Running"], "Bridge must be running"
assert bridge["Config"]["Labels"].get("com.docker.compose.service") == "mcp-stdio-bridge", "Expected the production Compose service"
assert not bridge["HostConfig"]["Privileged"], "Bridge must not be privileged"
assert bridge["HostConfig"]["ReadonlyRootfs"], "Bridge root filesystem must be read-only"
assert bridge["Config"]["User"].split(":")[0] == "65532", "Expected the bridge service account"
assert {"ALL"} <= set(bridge["HostConfig"]["CapDrop"]), "Expected all capabilities dropped"
assert "no-new-privileges" in " ".join(bridge["HostConfig"]["SecurityOpt"]), "NoNewPrivs is missing"
mounts = bridge["Mounts"]
assert {m["Destination"] for m in mounts if m["Type"] == "bind"} == {
    "/run/keplura-secrets", "/run/keplura-stdio"
}, "Unexpected bridge bind mounts"
assert len([m for m in mounts if m["Type"] == "bind"]) == 2, "Duplicate bind mount"
assert all(not m["RW"] and not str(m["Source"]).endswith(".sock") for m in mounts if m["Type"] == "bind"), "Unexpected bridge bind mount"
assert all(m["Type"] in {"bind", "tmpfs"} for m in mounts), "Unexpected bridge volume"
assert all(m["Destination"] == "/tmp" for m in mounts if m["Type"] == "tmpfs"), "Unexpected writable mount"
environment = dict(entry.split("=", 1) for entry in bridge["Config"]["Env"])
for name, default_path in (("KEPLURA_UPLOAD_DIR", "/var/lib/keplura/uploads"),
                           ("INTRACORE_UPLOAD_DIR", "/var/lib/intracore/uploads")):
    # Java runtime images may carry a fixed default upload path. This is not a credential or a mounted volume.
    if environment.get(name) == default_path:
        del environment[name]
keys = set(environment)
assert not any(key.startswith(("KEPLURA_", "INTRACORE_", "SPRING_", "POSTGRES_", "OPENAI_", "DOCKER_"))
               for key in keys), "Unexpected application environment in bridge"
print("B10/B17: running production bridge mounts, environment and hardening verified.")
