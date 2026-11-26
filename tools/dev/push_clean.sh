#!/bin/bash
# Publish a CLEAN copy of a branch: personal paths stripped from ALL history, personal text rewritten, then VERIFIED.
#
# usage: push_clean.sh [--local NAME | --check] [BRANCH] [TARGET]
#   (no flag)     clone BRANCH to a temp dir, strip + rewrite + verify, then push it to origin as TARGET (default scratch/v2)
#   --local NAME  same, but NEVER pushes: the cleaned branch is fetched into this repo as local branch NAME
#   --check       same, but nothing leaves the temp dir: prints the verification result only (dry run)
# Env: R=<repo> (default: the repo this script lives in), BASE=<ref> (with --local/--check: print how many commits sit on top of
#      the cleaned copy of BASE, e.g. BASE=rel-1.9).
# Lists (next to this script): push_clean.deny (paths to strip; # comments ok), push_clean.replace (text rewrites; NO comments allowed, every line is a rule), push_clean.scan (must-not-match regexes).
# Local history is untouched (the cleaned hashes differ). Self test: tools/dev/test-push-clean.sh.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
MODE=push; LOCALNAME=
case "${1:-}" in
  --local) MODE=local; LOCALNAME=${2:?--local needs a branch name}; shift 2;;
  --check) MODE=check; shift;;
esac
R=${R:-$(git -C "$HERE" rev-parse --show-toplevel)}
BR=${1:-$(git -C "$R" rev-parse --abbrev-ref HEAD)}; TARGET=${2:-scratch/v2}

# verify_clean <repo dir>: no denied path and no scan-pattern text in ANY commit of any branch. Exit 0 = clean.
verify_clean() {
  local D=$1 bad=0
  python3 - "$D" "$HERE/push_clean.deny" <<'PY' || bad=1
import subprocess, sys, fnmatch
repo, deny = sys.argv[1], sys.argv[2]
allow = {'android-companion/phone/src/test/resources/canvas-tls-test.p12', 'android-companion/phone/src/test/resources/canvas-tls-other.p12'}  # throwaway test certificates
rules = [l.strip() for l in open(deny) if l.strip() and not l.lstrip().startswith('#')]
paths = set(subprocess.run(['git', '-C', repo, 'log', '--all', '--format=', '--name-only'], capture_output=True, text=True, check=True).stdout.split('\n')) - {''}
def hit(p, r):
    if r.startswith('glob:'): return fnmatch.fnmatch(p, r[5:])
    return p == r or p.startswith(r.rstrip('/') + '/') if r.endswith('/') else p == r
bad = sorted(p for p in paths if p not in allow and any(hit(p, r) for r in rules))
for p in bad: print('DENIED PATH STILL PRESENT: ' + p)
sys.exit(1 if bad else 0)
PY
  local RE; RE=$(grep -v -E '^\s*(#|$)' "$HERE/push_clean.scan" | paste -sd'|' -)
  local HITS; HITS=$(git -C "$D" log --all -G"$RE" --format='%h %s' -- . ':(exclude)tools/dev/push_clean.scan' ':(exclude)tools/dev/push_clean.replace' ':(exclude)tools/dev/test-push-clean.sh' | head -20)
  if [ -n "$HITS" ]; then echo "SCAN PATTERN STILL PRESENT in commits:"; echo "$HITS"; bad=1; fi
  return $bad
}

if [ "${PUSH_CLEAN_VERIFY_ONLY:-}" = 1 ]; then verify_clean "$R"; exit $?; fi   # used by the self test

W=$(mktemp -d); T=$W/pca-clean
trap 'rm -rf "$W"' EXIT
GIT_LFS_SKIP_SMUDGE=1 git clone -q --no-local --single-branch --branch "$BR" "$R" "$T"; cd "$T"
git filter-repo --force --invert-paths --paths-from-file "$HERE/push_clean.deny" \
  --replace-text "$HERE/push_clean.replace" --replace-message "$HERE/push_clean.replace" >/dev/null 2>&1
if ! verify_clean "$T"; then echo "push_clean: VERIFICATION FAILED, nothing was published" >&2; exit 3; fi
echo "push_clean: verification passed ($(git rev-list --count HEAD) commits on $BR)"
if [ -n "${BASE:-}" ]; then
  OLD=$(git -C "$R" rev-parse "$BASE"); NEW=$(awk -v o="$OLD" '$1==o{print $2}' .git/filter-repo/commit-map)
  [ -n "$NEW" ] && [ "$NEW" != 0000000000000000000000000000000000000000 ] && echo "push_clean: $(git rev-list --count "$NEW..HEAD") commits on top of cleaned $BASE ($NEW)"
fi
case $MODE in
  check) echo "push_clean: --check, nothing published";;
  local) git -C "$R" fetch -q "$T" "+refs/heads/$BR:refs/heads/$LOCALNAME"; echo "push_clean: local branch $LOCALNAME = $(git -C "$R" rev-parse --short "$LOCALNAME") (not pushed)";;
  push)  git remote add origin "$(git -C "$R" remote get-url origin)"
         git fetch -q origin "refs/heads/$TARGET:refs/remotes/origin/$TARGET" 2>/dev/null || true
         git push --force-with-lease origin "$BR:refs/heads/$TARGET";;
esac
