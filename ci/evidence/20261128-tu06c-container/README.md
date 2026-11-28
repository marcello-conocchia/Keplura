# TU-06c Bridge-Mount-PrÃ¼fung

Am 28.11.2026 lief der Bridge-Dienst mit der tatsÃ¤chlichen Compose-Konfiguration
aus `deploy/compose.prod.yml` und `deploy/compose.mcp-stdio.yml` in einem
separaten Docker-Projekt. Das PrÃ¼fbild enthielt das lokal gebaute Server-Jar.
Zertifikat und SchlÃ¼ssel waren synthetische Testwerte. Die Host-Mounts wurden
Ã¼ber zwei eigens angelegte Linux-Docker-Volumes bereitgestellt.

```text
Docker 28.5.1
image=sha256:daaeb5aebf7cea3d68292c363a92f1545889747f16a9d150fbb8cec1679d0913
user=65532:65532
B10/B17: running production bridge mounts, environment and hardening verified.
```

Der HTTPS-Health-Endpunkt antwortete. Ein MCP-Aufruf ohne Token erhielt HTTP 401. Die PrÃ¼fung lief mit
`python deploy/mcp-stdio/check-bridge-container.py <Container-ID>`.
GeprÃ¼ft wurden die beiden schreibgeschÃ¼tzten Bridge-Bind-Mounts, der
Read-only-Root, UID und Capabilities sowie das Fehlen von Keplura-Zugangsdaten
in Container-Umgebung und Mounts.

Dieser Lauf deckt die Bridge-Seite von B10 und B17 ab. Der Startdienst und
echte Worker benÃ¶tigen einen Host mit lokalem rootless Podman, delegierten
Cgroups sowie vorinstalliertem Probe- und Worker-Image. Diese Abnahme steht aus.
