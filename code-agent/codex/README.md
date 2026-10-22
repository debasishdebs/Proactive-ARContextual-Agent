# Codex reviews

`reviews/` holds the independent read-only reviews (plan reviews, final passes, privacy and freeze reviews) written by Codex. Findings are ranked critical/important/minor with failure scenarios; the fixes are in the git history and the matching task reports under `../superpowers/sdd/`.

Run one: `codex exec -m gpt-6.1-sol -c model_reasoning_effort="high" -s read-only --skip-git-repo-check --ephemeral -C <repo-or-worktree> -o <out.md> "<prompt>"`. `prompts/` has an example prompt.
