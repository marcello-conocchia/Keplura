# ADR-0001: Architecture baseline

Date: 2026-09-03
Status: Accepted (owner decision, master directive)

## Decision

- Keplura is a private enterprise AI platform for companies.
- Desktop: native installable app — Tauri 2 + Vue 3 + TypeScript (Windows, Linux, macOS).
- Server: Java 21 + Spring Boot 4.1.x + Maven, modular monolith.
- Communication: HTTPS; REST/JSON plus SSE for streaming. Desktop and server are
  separate products that interact only through the server API.
- Trust boundary: the desktop client is untrusted. The server is authoritative for
  authentication, authorization, organizations, roles, spaces, knowledge,
  documents, RAG, and the model gateway.
- Planned infrastructure: PostgreSQL + pgvector, suitable object storage,
  OpenAI-compatible model gateway backed by vLLM and/or Ollama.
- Auth strategy (later milestone): OIDC authorization code flow + PKCE.

## Rejected

- Browser web app as the primary desktop product.
- Microservices without real need; Kubernetes; Kafka; Redis; any added
  infrastructure purely for future-proofing.

## Consequences

Every feature task must fit the modular monolith and the desktop/server trust
split. New cross-cutting infrastructure requires an owner decision.
