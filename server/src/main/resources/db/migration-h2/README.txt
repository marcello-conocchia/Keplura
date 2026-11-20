Placeholder so the {vendor} location resolves cleanly on H2 (prod smoke
fallback + replay test). Everything searchable that must exist on ALL
databases lives in ../migration (portable SQL); there is intentionally no
H2-only migration — H2 never gets native vector search (docs/adr/0007).
