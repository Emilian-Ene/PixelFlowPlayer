# Edge Digital Signage Roadmap

Guiding principles
- Offline-first, download-to-disk, deterministic playback
- Idempotent operations; content addressed by hash
- Least-privilege, secure-by-default
- Operable at fleet scale (zero-touch, observability, remote control)
- Backward-compatible migrations and feature flags

## Phase 0 — Foundations (now)
- Repo hygiene, CI/CD, code quality gates, release channels (dev/beta/prod)
- Observability base: structured logs + device metrics payload in heartbeat
- Feature flags and experiment toggles
- Strict semantic versioning for APIs and app
Pros: faster iteration, safer releases; Cons: initial setup time

## Phase 1 — Harden current MVP
- Player: offline-only playback; block UI until 100% downloads (skip failed), resume cached media
- Robust heartbeat (backoff, jitter, version in headers)
- Manifest: include sha256, size, mime, dimensions; validate after download
- Strict cache ownership: cleanup by manifest; storage quotas
- Proof of Play logging (local ring buffer + batched upload)
Acceptance: zero black screens, recovery from network loss, PoP visible in CMS
Pros: reliability; Cons: more manifest complexity

## Phase 2 — Content delivery optimization
- Content-addressable storage (CAS) using sha256 filenames; de-dup across playlists
- Delta delivery: only fetch unknown hashes
- Packaged bundles (zip/tar.zst) for large playlists; optional per-edge unpack
- Parallel downloads with bandwidth shaping; resume support (HTTP Range)
- CDN integration (signed, expiring URLs) with origin fallback
Metrics: avg publish→ready < X min; 95% download success; cache hit rate
Pros: speed, cost; Cons: packaging pipeline complexity

## Phase 3 — Device provisioning & kiosk
- Device Owner (DO) provisioning; Lock Task whitelisting
- Zero‑touch / QR / NFC bootstrap options
- Remote commands: reboot, clear cache, rotate logs/PIN, change heartbeat interval
- Remote config: throttles, download concurrency, sleep windows
Pros: enterprise readiness; Cons: DO enrollment process

## Phase 4 — Scheduling & layout engine
- Schedules: dayparting, recurrence, holidays, timezones
- Targeting: tags, groups, geofencing
- Zones/layouts: flexible regions, safe aspect-ratio policies, transitions
- Template engine for data-driven creatives (JSON → template)
Pros: richer UX; Cons: more QA/edge cases

## Phase 5 — Monitoring, SLAs, and alerting
- Health states: Online/Degraded/Offline with causes (network, storage, decoding, thermal)
- SLOs: uptime, publish→ready latency, playback error rate; alert policies
- Fleet dashboards (device map, versions, content coverage)
Pros: operational visibility; Cons: telemetry pipeline cost

## Phase 6 — Security & compliance
- TLS everywhere; optional mTLS for enterprise
- Signed manifests and asset hashes; fail-closed on tamper
- Key rotation; encrypted local store for sensitive tokens
- RBAC, audit logs, data retention; privacy controls for PoP
Pros: trust & compliance; Cons: cert lifecycle overhead

## Phase 7 — Performance & media quality
- Media preflight/transcoding presets server-side (profiles per device class)
- Frame drop and decode stats from ExoPlayer; thermal/battery monitoring
- Storage/IO benchmarks; GC pressure and bitmap pool tuning (Glide)
Pros: smooth playback; Cons: pipeline compute usage

## Phase 8 — Scale & multi‑tenant
- Tenants, workspaces, quotas/limits, per-tenant encryption scopes
- Rate limiting & isolation; cost metering by GB delivered and device‑hours
Pros: SaaS-friendly; Cons: multi-tenant complexity

## Phase 9 — Extensibility
- Webhooks and API for external CMS integrations
- Plugin template SDK (safe sandbox) for dynamic widgets (weather, KPIs)
- Remote diagnostic bundle (redacted)
Pros: ecosystem; Cons: plugin security surface

## Phase 10 — Rollout & support
- Canary deployments; staged rollout with kill switches
- Pilot customers; success criteria and rollback plan
- Support playbooks: black screen, pairing loops, cache growth, network flaps
Pros: safer launches; Cons: longer release cycles initially

## Key design choices
- Offline-only vs streaming: Offline-only chosen → deterministic, resilient; Cons: storage, initial latency
- Poll heartbeat vs push: Poll first → simple; Push later → lower latency, more infra
- Asset packaging vs individual files: Packages → fewer RTTs, atomic swaps; Cons: unpack, retry granularity
- ExoPlayer/Glide native vs WebView: Native chosen → performance/control; Cons: more UI work
- CAS by hash vs path-based: CAS chosen → de-dup/integrity; Cons: manifest management

## Operational metrics
- Publish→ready time; cache hit rate; failed download ratio
- Playback continuity (dropped frames, stalls); device thermal throttling
- Online ratio; heartbeat latency; storage usage distribution
- Proof-of-Play coverage and upload delay

## Upgrade strategy
- Backward-compatible heartbeats
- Versioned manifest schema; dual-serve window
- Feature flags for new download strategies; staged rollout
- Safe migrations with rollback (e.g., CAS)

## Documentation & support
- Customer: pairing, assigning content, SLAs, troubleshooting
- Ops: runbooks, dashboards, alert catalogs
- Dev: API contracts, media prep specs, device class profiles
