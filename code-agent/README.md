# code-agent: everything an AI coding agent (or a new contributor) needs to continue this project

This folder is the project's **working memory**. The product code lives in `android-companion/`, `tools/`, `docs/`. Nothing here is built or shipped. Read in this order:

1. [`handoffs/README.md`](handoffs/README.md) points to the current handoff (state, decisions, first-day plan, pitfalls). **Start there.**
2. [`claude/`](claude/README.md): rules the agents follow (model split, safety rules, never-print-secrets, device/adb rules) and a snapshot of the Claude memory files.
3. [`codex/`](codex/README.md): the independent Codex (gpt-6.1-sol, read-only) reviews of plans and code, and how to run one.
4. [`superpowers/`](superpowers/README.md): subagent-driven-development ledgers, per-task briefs and reports, and where plans/specs live (`docs/superpowers/`).
5. [`releases/`](releases/): GitHub release notes text (no APKs are attached to releases until builds are release-signed).

```
code-agent/
  README.md            this index
  handoffs/            pointers to the handoff docs (they stay in docs/superpowers/specs/)
  claude/              working rules + memory snapshot (live copy: ~/.claude/projects/<project>/memory)
  codex/               reviews/ (18 reviews), prompts/
  superpowers/sdd/     one folder per executed plan: briefs, task reports, review/diagnosis files, integration ledger
  releases/            release-notes markdown used with `gh release create --notes-file`
```

Build prerequisites for a fresh clone (JDK 17, Android SDK 36 + NDK 28.2 + CMake 3.22.1, git-lfs, the llama.cpp submodule) are in the root `README.md`, section "Build from a fresh clone (dependencies)".

Conventions: Sonnet-only agents, Codex for reviews, one review per task, reuse-first, never print secrets, never push/rm from an agent (the owner pushes with one-line commands). Details in `claude/WORKING-RULES.md`. No secrets, personal screenshots or device identifiers should ever be added here; the history of this repo was rewritten once to remove screenshots (see the handoff).
