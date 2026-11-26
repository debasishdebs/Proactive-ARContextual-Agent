#!/bin/bash
# Self test for push_clean.sh: builds a throwaway repo with personal files, runs the dry run and the local-branch mode, and proves
# (1) denied paths and personal text are gone from EVERY commit, (2) public files are kept, (3) the verifier really fails on a dirty repo.
# No network, no remote, nothing pushed. usage: tools/dev/test-push-clean.sh
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT; S=$W/src; mkdir -p "$S"; cd "$S"
git init -q -b main; git config user.email t@example.invalid; git config user.name t
mkdir -p docs/superpowers/plans/handoffs docs/superpowers/decisions docs/superpowers/reviews/2026-11 docs/superpowers/specs .superpowers app
echo base > README.md; echo code > app/Main.kt; echo ok > ROADMAP.md
git add -A; git commit -q -m base; git tag base
echo "personal" > docs/superpowers/plans/handoffs/HANDOFF-2026-11-26-FULL.md
echo "personal" > docs/superpowers/plans/handoffs/release-plan-2026-11-26.md
echo "personal" > docs/superpowers/decisions/owner-decisions.md
echo "personal" > HANDOFF-2026-10-24.md; echo "personal" > MORNING.md; echo "x" > .superpowers/note.md
echo "personal" > docs/superpowers/specs/2026-10-21-handoff.md; echo "coords" > docs/superpowers/reviews/2026-11/codex-hyderabad-coords.md
git add -A -f; git commit -q -m "docs: handoff 12z (personal only)"
echo "see /Users/<user>/Desktop/rokid/x and .pca/keystore.properties" > docs/notes.md
mkdir -p docs; echo "// key at ~/<signing dir>/release" > app/Sign.kt; git add -A; git commit -q -m "feat: keep this /Users/<user>/path in the message"
git rm -q -r docs/superpowers/plans/handoffs; git commit -q -m "drop handoffs from tip"   # history still holds them
R=$S BASE=base "$HERE/push_clean.sh" --check main > "$W/check.out" 2>&1 || { cat "$W/check.out"; echo "FAIL: dry run failed"; exit 1; }
grep -q 'verification passed' "$W/check.out" || { echo "FAIL: no pass line"; exit 1; }
R=$S "$HERE/push_clean.sh" --local clean-out main >/dev/null
F=$(git log clean-out --format= --name-only | sort -u)
for p in HANDOFF-2026-10-24.md MORNING.md docs/superpowers/plans/handoffs docs/superpowers/decisions .superpowers docs/superpowers/specs/2026-10-21-handoff.md hyderabad; do
  echo "$F" | grep -q "$p" && { echo "FAIL: $p still in history of clean-out"; exit 1; }; done
for p in README.md ROADMAP.md app/Main.kt docs/notes.md; do echo "$F" | grep -q "^$p\$" || { echo "FAIL: public file $p missing"; exit 1; }; done
git log clean-out -p | grep -q -E '/Users/<user>|pca-release-keys|\<signing dir>' && { echo "FAIL: personal text survived"; exit 1; }
git show clean-out:docs/notes.md | grep -q '/Users/<user>' || { echo "FAIL: text rewrite missing"; exit 1; }
# the verifier must FAIL on the dirty source repo (negative test)
if R=$S PUSH_CLEAN_VERIFY_ONLY=1 "$HERE/push_clean.sh" >/dev/null 2>&1; then echo "FAIL: verifier passed a dirty repo"; exit 1; fi
echo "push-clean selftest: PASS"
