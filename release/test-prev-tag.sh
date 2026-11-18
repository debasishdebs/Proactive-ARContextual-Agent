#!/usr/bin/env bash
# Tests for prev-tag.sh.
set -uo pipefail
p="$(cd "$(dirname "$0")" && pwd)/prev-tag.sh"; n=0; bad=0
ck() { n=$((n+1)); if [ "$2" = "$3" ]; then :; else bad=$((bad+1)); echo "FAIL $1: expected '$2' got '$3'"; fi; }
c() { "$p" cmp "$1" "$2" 2>/dev/null; }
ck "1.10>1.9" 1 "$(c v1.10.0 v1.9.0)"
ck "rc10>rc2" 1 "$(c v1.0.0-rc.10 v1.0.0-rc.2)"
ck "rel>rc10" 1 "$(c v1.0.0 v1.0.0-rc.10)"
ck "rc2<rc10" -1 "$(c v1.0.0-rc.2 v1.0.0-rc.10)"
ck "eq" 0 "$(c v1.2.3 v1.2.3)"
ck "num<alpha" -1 "$(c v1.0.0-1 v1.0.0-alpha)"
ck "alpha<beta" -1 "$(c v1.0.0-alpha v1.0.0-beta)"
ck "shorter<longer" -1 "$(c v1.0.0-rc v1.0.0-rc.1)"
ck "major" 1 "$(c v2.0.0-rc.1 v1.99.99)"
for bad_t in 1.0.0 v1.0 v1.0.0- v1.0.0-rc..1 v01.0.0 v1.0.0-rc.01 vx.y.z "v1.0.0-rc 1" ""; do
  "$p" cmp "$bad_t" v1.0.0 >/dev/null 2>&1; ck "invalid '$bad_t' (left)" 1 "$?"
  "$p" cmp v1.0.0 "$bad_t" >/dev/null 2>&1; ck "invalid '$bad_t' (right)" 1 "$?"
  "$p" prev "$bad_t" </dev/null >/dev/null 2>&1; ck "invalid prev '$bad_t'" 1 "$?"
done
list=$'v1.0.0-rc.2\nv1.0.0-rc.10\nv1.0.0-rc.9\nv0.9.0\nv1.0.0\nv1.1.0\njunk\nv1.0.0-'
ck "prev of v1.0.0" v1.0.0-rc.10 "$("$p" prev v1.0.0 <<<"$list")"
ck "prev of rc.10" v1.0.0-rc.9 "$("$p" prev v1.0.0-rc.10 <<<"$list")"
ck "prev of v1.1.0" v1.0.0 "$("$p" prev v1.1.0 <<<"$list")"
ck "prev of v0.9.0 none" "" "$("$p" prev v0.9.0 <<<"$list")"
ck "self excluded" v1.0.0-rc.10 "$("$p" prev v1.0.0 <<<"$list"$'\nv1.0.0')"
echo "$n checks, $bad failed"; [ "$bad" -eq 0 ]
