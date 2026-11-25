#!/bin/bash
# Push the current branch to GitHub as <target> (default scratch/v2) WITHOUT the personal trip analysis files:
# clones to a temp dir, strips those paths from history (git filter-repo), pushes. Local history is untouched (pushed hashes differ).
set -eu
R=${R:-$HOME/Desktop/rokid/proactive-context-agent}; BR=${1:-$(git -C "$R" rev-parse --abbrev-ref HEAD)}; TARGET=${2:-scratch/v2}
T=$(mktemp -d)/pca-push
git clone -q --no-local --single-branch --branch "$BR" "$R" "$T"; cd "$T"
git filter-repo --force --invert-paths \
 --path-glob 'docs/superpowers/plans/handoffs/pin-moment-candidates-*.md' \
 --path-glob 'docs/superpowers/plans/handoffs/trip-analysis-*.md' \
 --path-glob 'docs/superpowers/plans/handoffs/trip-gap-analysis-*.md' \
 --path-glob 'docs/superpowers/plans/handoffs/retention-redaction-analysis-*.md' >/dev/null 2>&1
git remote add origin git@github.com:debasishdebs/Proactive-ARContextual-Agent.git
git fetch -q origin "refs/heads/$TARGET:refs/remotes/origin/$TARGET" 2>/dev/null || true
git push --force-with-lease origin "$BR:refs/heads/$TARGET"
rm -rf "$(dirname "$T")"
