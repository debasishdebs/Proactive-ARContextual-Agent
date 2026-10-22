---
name: no-debug-apks-on-releases
description: Never attach APKs to GitHub releases (PCA or LiveFit) until builds are release-signed and not debug builds
metadata:
  node_type: memory
  type: feedback
  originSessionId: a6a5b53b-c399-4266-98de-c72503e9f379
  modified: 2026-10-22T18:40:17.963Z
---

On 2026-10-23 the user said: do not attach APKs to the GitHub releases, "at least till they are debug free". Release notes for PCA (v1-beta, v1.0.0-rc1, v0.9-beta) and LiveFit v1.0-beta were written without assets (`~/Desktop/rokid/archive/release-notes/`).

**Why:** the installed builds are debug-signed (phone APK is ~250 MB) and can embed keys from local.properties / BuildConfig; the earlier LiveFit v0.1.0/v0.2.0 pre-releases did carry debug-signed APKs for testers, but the user does not want that repeated for PCA.

**How to apply:** when creating or updating any release, upload no `*.apk`/asset. Only consider attaching APKs once the build is release-signed (non-debug), after scanning it for embedded secrets and the user agrees. Related: [[never-print-secrets]], [[resume-from-backlog]].
