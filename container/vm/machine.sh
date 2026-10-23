#!/bin/bash
# Podman machine lifecycle for WP10 (core plan section 8, user decision 2026-10-23 evening). Subcommands:
#   recreate  stop the previous machine, CONFIRM it is stopped, remove it, recreate it with ONE /stage mount, start it
#   stop      end of a batch: stop the machine and confirm the state is not running (the VM is stopped whenever idle)
# Runs only inside the vm-session owner (tools/canvas-helper/pca_lock.py vm-session), which holds host.lock for the whole sequence and
# releases it only after a confirmed stop. `machine rm` is refused while the previous machine is not confirmed stopped.
# Environment: PCA_MACHINE (pca), PCA_STAGE_DIR (~/.pca-helper/stage), PCA_PODMAN (podman), PCA_PYTHON (python3), PCA_CONFIRM_TRIES (30),
# PCA_POLL_S (1), PCA_MACHINE_MEMORY_MB (4096).
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCK_PY="$HERE/../../tools/canvas-helper/pca_lock.py"
PODMAN="${PCA_PODMAN:-podman}"
NAME="${PCA_MACHINE:-pca}"
STAGE="${PCA_STAGE_DIR:-$HOME/.pca-helper/stage}"
TRIES="${PCA_CONFIRM_TRIES:-30}"
POLL="${PCA_POLL_S:-1}"
MEM="${PCA_MACHINE_MEMORY_MB:-4096}"

die() { echo "machine.sh: $*" >&2; exit "${2:-1}"; }

# Existence/state: prints stopped|running|starting|absent|unknown.
state() {
  local out rc
  out="$("$PODMAN" machine inspect "$NAME" 2>&1)"; rc=$?
  if [ "$rc" -ne 0 ]; then
    case "$(printf '%s' "$out" | tr 'A-Z' 'a-z')" in
      *"does not exist"*|*"no such"*|*"not found"*) echo absent ;;
      *) echo unknown ;;
    esac
    return
  fi
  case "$(printf '%s' "$out" | tr -d ' \n' | sed -n 's/.*"State":"\([A-Za-z]*\)".*/\1/p' | tr 'A-Z' 'a-z')" in
    stopped) echo stopped ;;
    running) echo running ;;
    starting) echo starting ;;
    *) echo unknown ;;
  esac
}

confirm_stopped() {
  local i=0 s
  while [ "$i" -lt "$TRIES" ]; do
    s="$(state)"
    [ "$s" = stopped ] && return 0
    i=$((i + 1)); [ "$POLL" != 0 ] && sleep "$POLL"
  done
  return 1
}

require_owner() {
  [ "${PCA_VM_SESSION:-}" = 1 ] || die "must run inside the vm-session owner (PCA_VM_SESSION unset)" 75
  "${PCA_PYTHON:-python3}" "$LOCK_PY" check-owner >/dev/null 2>&1 || die "no vm-session owner holds host.lock" 75
}

stop_machine() {
  "$PODMAN" machine stop "$NAME" >/dev/null 2>&1 || true
  confirm_stopped
}

cmd_recreate() {
  mkdir -p "$STAGE" || die "cannot create stage dir"
  local s; s="$(state)"
  case "$s" in
    absent) ;;
    stopped|running|starting)
      stop_machine || die "previous machine $NAME is not confirmed stopped; remove refused" 1
      "$PODMAN" machine rm -f "$NAME" >/dev/null 2>&1 || die "machine rm failed" 1
      ;;
    *) die "previous machine state unknown; nothing removed" 1 ;;
  esac
  "$PODMAN" machine init --memory "$MEM" --volume "$STAGE:/stage" "$NAME" >/dev/null 2>&1 || die "machine init failed" 1
  "$PODMAN" machine start "$NAME" >/dev/null 2>&1 || die "machine start failed" 1
}

cmd_stop() {
  stop_machine || die "machine $NAME is not confirmed stopped" 1
}

case "${1:-}" in
  recreate) require_owner; cmd_recreate ;;
  stop) require_owner; cmd_stop ;;
  *) die "usage: machine.sh recreate|stop" 2 ;;
esac
