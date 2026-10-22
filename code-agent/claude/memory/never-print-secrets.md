---
name: never-print-secrets
description: Never print secret/key values to the transcript — not even in verification scripts; counts only
metadata:
  node_type: memory
  type: feedback
  originSessionId: a6a5b53b-c399-4266-98de-c72503e9f379
  modified: 2026-10-12T19:18:51.225Z
---

Never let a command output a secret value (.env values, API keys, tokens, credentials), including grep/sed context lines from source files that hold keys. Verification prints counts or key NAMES only; pipe matches into `grep -c` or `wc -l`, never to stdout.

**Why:** on 2026-10-12 a failed sed in a secret-scan check printed AWS Bedrock keys that the user does not control into the session transcript. The user called it a huge mistake and will stop using Claude if it happens again.

**How to apply:** before any command that touches .env or credential-bearing files, make sure its output cannot carry the values. Use `grep -c`, `-q` or `-l`, and compare values inside the pipeline without echoing them. If you're not sure, don't run it.
