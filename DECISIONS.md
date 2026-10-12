# DECISIONS LOG

## 2026-05-28
1. New standalone repo created: `proactive-context-agent`.
2. Repo boundaries fixed:
- UPI remains in separate repo.
- Docs remain in separate repo.
3. Capture and battery strategy locked:
- Activity-aware capture with battery guardrails.
- Low battery behavior defined.
4. Phone processing strategy locked:
- `>30%` every 15 min.
- `<=30%` charging-only.
5. Local model execution rule locked:
- charging OR battery `>70%`.
6. Uniform model interface required across cloud and local backends.
