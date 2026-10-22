---
name: container-runtime-install-ok
description: User allowed installing Podman/Docker on the laptop for the canvas helper isolation (slice 3); none installed as of 2026-10-21
metadata:
  node_type: memory
  type: feedback
  originSessionId: a6a5b53b-c399-4266-98de-c72503e9f379
  modified: 2026-10-21T18:02:22.117Z
---

On 2026-10-21 the user said neither Podman nor Docker is installed and that I may install them and run ops myself (for the slice 3 laptop helper: isolated Python/CLI jobs, see the slice 3 spec §4.4 and S22).

**Why:** slice 3 runs untrusted Python and generated-code jobs in rootless containers (Codex review finding #1).
**How to apply:** install (prefer Homebrew `podman`, then `podman machine init/start`) only when the helper task in slice 3 actually starts, not before; tell the user what was installed. Never put credentials into the container beyond the one API key the spec allows. Linked: [[codex-second-opinion-rules]].
