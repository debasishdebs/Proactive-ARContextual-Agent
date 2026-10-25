#!/usr/bin/env bash
# Scripted on-device checklist (R-75): phone + glasses. Bash 3.2 compatible.
#   PHONE_SERIAL=<serial> GLASSES_SERIAL=<serial> tools/device-checklist.sh [--dry-run] [--only <id>]
# The HUMAN performs every action. This script only: prints the instruction, waits for Enter,
# reads logcat markers (adb logcat -d, after one adb logcat -c at step start) and takes
# screenshots with `adb exec-out screencap -p`. It never taps, toggles Bluetooth/Wi-Fi,
# uninstalls anything, or reads .env / keys. --dry-run makes NO adb call at all.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DRY=0; ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --only) shift; ONLY="${1:-}"; [ -n "$ONLY" ] || { echo "--only needs a step id" >&2; exit 2; } ;;
    -h|--help) sed -n 2,7p "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

PHONE_SHOTS_DIR="$ROOT/docs/screens/phone"
HUD_SHOTS_DIR="$ROOT/docs/screens/hud"
OUT="$ROOT/docs/screens/checklist-$(date +%Y%m%d).txt"

# ---- step ids, in run order -------------------------------------------------------------------
STEP_IDS="S-LINK S-CAPTURE S-ASK S-NUDGE S-ACTION S-PAUSE S-OFFLINE-PAUSE S-FLUSH S-FORGET \
D-A1 D-A2 D-A3 D-A4 D-A5 D-C1 D-C2 D-C3 D-C4 D-D4 D-E1 D-E1H D-E2 \
P7-1 P7-2 P7-3 P7-4 P7-5 P7-6 P7-7 P7-8 P7-9 P7-10 P7-11 P7-12 P7-13"

# Screenshot lists, one "<file-name>|<what to show>" per line.
PHONE_SHOTS="home|Home
activity|Activity
day|Day (open any day)
memory-detail|Memory detail (open any memory)
ask-detail|Ask detail (open any ask)
frames|Frames (open from a day)
settings|Settings root
settings-endpoints|Settings > Endpoints
settings-diagnostics|Settings > Diagnostics
settings-diagnostics-queues|Diagnostics > Queues
settings-diagnostics-tracker|Diagnostics > Tracker
settings-diagnostics-logs|Diagnostics > Logs
settings-glasses|Settings > Glasses
settings-phone-inputs|Settings > Phone inputs
settings-capture|Settings > Capture
settings-roles|Settings > Roles
settings-prompts|Settings > Prompts
settings-retrieval|Settings > Retrieval
settings-persona|Settings > Persona
settings-memory|Settings > Memory
settings-memory-review|Settings > Memory > Review
settings-privacy|Settings > Privacy
settings-sources|Settings > Data sources
settings-actions|Settings > Actions
settings-shopping|Settings > Shopping
nudge-detail|Nudge detail (open any nudge)
onboarding-first-step|Onboarding first step (fresh install or reset onboarding)"
HUD_SHOTS="glance|HUD Glance page
today|HUD Today page
status|HUD Status page
listening|HUD Listening overlay (start an Ask)
answer|HUD Answer overlay
confirm|HUD Confirm overlay (propose a reminder)
paused-splash|HUD Paused splash (double-tap to pause)"

# ---- step definitions -------------------------------------------------------------------------
# Sets TITLE, INSTR, MARKERS (newline list: "P|regex" phone logcat, "G|regex" glasses logcat,
# a leading "!" means the pattern must be ABSENT) and SHOTMODE ("" | phone | hud).
# Every marker is a real Log message in android-companion/**/src/main.
define_step() {
  TITLE=""; INSTR=""; MARKERS=""; SHOTMODE=""
  local NL='
'
  case "$1" in
  S-LINK)
    TITLE="Link"
    INSTR="Open Proactive Context on both devices and connect. Phone Status shows the glasses connected."
    MARKERS="P|link=CONNECTED${NL}P|hello battery=" ;;
  S-CAPTURE)
    TITLE="Capture -> memory"
    INSTR="Let the glasses capture at least one frame; wait for it to appear in Activity on the phone."
    MARKERS="G|delivered ${NL}P|frame ingested${NL}!P|frame not stored" ;;
  S-ASK)
    TITLE="Ask -> answer -> TTS"
    INSTR="Ask a question from the glasses (long-press). The answer shows on the HUD and is spoken."
    MARKERS="P|listen start" ;;
  S-NUDGE)
    TITLE="Nudge"
    INSTR="Trigger or wait for a nudge; check the HUD toast (Max) and the phone feed."
    MARKERS="!P|nudge failed" ;;
  S-ACTION)
    TITLE="Action confirm (simultaneous phone + HUD)"
    INSTR="Ask 'remind me to call mom at 6'. Confirm on glasses and tap the phone within 1 s: exactly one outcome."
    MARKERS="P|confirm answered" ;;
  S-PAUSE)
    TITLE="Pause / resume"
    INSTR="Pause from the phone (Home), then resume; then double-tap on glasses to pause and resume."
    MARKERS="P|phone pause added${NL}P|phone resume for ${NL}G|paused locally${NL}G|resumed [0-9]+ pause" ;;
  S-OFFLINE-PAUSE)
    TITLE="Offline pause on glasses, then reconnect (stays paused)"
    INSTR="Take the link down yourself, double-tap pause on the glasses, bring the link back. Capture must stay paused."
    MARKERS="G|paused locally${NL}P|hello battery=${NL}G|capture skipped: paused" ;;
  S-FLUSH)
    TITLE="Offline buffer flush incl. a mid-frame link drop"
    INSTR="Buffer frames offline, reconnect and let them flush; repeat, dropping the link yourself mid-flush."
    MARKERS="G|delivered ${NL}G|flush done${NL}P|frame ingested${NL}G|flush interrupted|timeout" ;;
  S-FORGET)
    TITLE="Forget fact vs Forget moment"
    INSTR="On the phone: Forget a fact (the moment stays), then Forget a moment (photo and its facts go)."
    MARKERS="!P|forget failed" ;;
  D-A1)
    TITLE="Clock jump"
    INSTR="Glasses offline, frames buffered; move the glasses clock +2 days; reconnect. Frames flush and are not aged out; capture times on the phone match reality within the offset."
    MARKERS="G|delivered ${NL}P|frame ingested${NL}P|hello battery=" ;;
  D-A2)
    TITLE="Full glasses storage"
    INSTR="Fill glasses storage to < 1 MB free, then capture. The newest frame is buffered, the oldest dropped, nothing crashes."
    MARKERS="!G|FATAL EXCEPTION" ;;
  D-A3)
    TITLE="Cold start on Main"
    INSTR="Cold-start the phone app with StrictMode logging on. No disk read from ReceivedFrameLedger on Main. Note when CxrSessionManager.getInstance runs relative to the BT grant prompt."
    MARKERS="!P|FATAL EXCEPTION" ;;
  D-A4)
    TITLE="Delete all with frames buffered"
    INSTR="Buffer 5 frames offline, run Delete all on the phone, reconnect. 5 acks wiped_drop; no new frames in Activity."
    MARKERS="P|frame dropped: captured_before_delete_all${NL}G|delivered " ;;
  D-A5)
    TITLE="Mid-frame drop (spec 6.4)"
    INSTR="Kill BT during a flush (you do it). The frame resumes from chunk 0 with no duplicate; the Status buffer count is exact after the flush."
    MARKERS="G|flush interrupted${NL}G|flush done${NL}P|frame ingested" ;;
  D-C1)
    TITLE="Camera bind failure"
    INSTR="Revoke the camera on the glasses mid-session. No SurfaceTexture warning spam; the service stops cleanly."
    MARKERS="!G|FATAL EXCEPTION" ;;
  D-C2)
    TITLE="Persistent camera failure in background"
    INSTR="With the camera failing in the background, the HUD is brought forward at most 3 times, then stops."
    MARKERS="!G|FATAL EXCEPTION" ;;
  D-C3)
    TITLE="Force-stop by Rokid AssistServer"
    INSTR="Close Proactive Context on the glasses the Rokid way. Phone banner: 'Closed on glasses - capture stopped'. Reopen: HUD toast 'Capture resumed...' and capture resumes within one interval. Also: long-press Hi Rokid with KEYCODE 83 consumed; does a backgrounded, not-finishing activity keep the session open?"
    MARKERS="P|session closed GLASSES_EXIT|session closed USER_CLOSED${NL}P|link=CONNECTED" ;;
  D-C4)
    TITLE="SDK leaks"
    INSTR="Force connect failures 10x (you uninstall and reinstall the glasses app). No growth in CXR bindings (dumpsys activity services). The watcher reattaches."
    MARKERS="P|connect failed${NL}P|link=CONNECTED" ;;
  D-D4)
    TITLE="SQLCipher"
    INSTR="Turn encryption on, restart, query Activity, turn it off, restart. The data is intact both ways."
    MARKERS="!P|FATAL EXCEPTION" ;;
  D-E1)
    TITLE="Phone UI references"
    INSTR="Check a transformable frame inside verticalScroll (zoom vs scroll). Then each phone screen is captured to docs/screens/phone/."
    SHOTMODE="phone" ;;
  D-E1H)
    TITLE="HUD references"
    INSTR="Each HUD page/overlay is captured from the glasses to docs/screens/hud/."
    SHOTMODE="hud" ;;
  D-E2)
    TITLE="Gate stall"
    INSTR="Run tools/gate.sh 3x on the merged tip (another terminal). If a .stall file appears, attach it to the final review. Report the result here."
    ;;
  P7-1)
    TITLE="Phone pause drops the glasses buffer"
    INSTR="Phone pause on Home: HUD shows the pause mark within 2 s; buffered frames dropped (hello bufferSize = 0); resume on the phone: glasses capture resumes."
    MARKERS="P|phone pause added${NL}P|hello battery=.*buffered=0${NL}P|phone resume for " ;;
  P7-2)
    TITLE="Glasses offline pause, phone resume before reconnect"
    INSTR="Glasses offline: double-tap pause, tap Resume on the phone before reconnect. On reconnect: still paused + 'Still paused on glasses - Resume?'; Resume: capture runs."
    MARKERS="G|paused locally${NL}P|hello battery=${NL}G|resumed [0-9]+ pause" ;;
  P7-3)
    TITLE="Timed pause sent before the first hello"
    INSTR="Phone 'For 30 minutes' pause sent before the first hello: glasses stay paused; after hello the HUD state matches; it ends at 30 min on both sides."
    MARKERS="P|phone pause added${NL}P|hello battery=" ;;
  P7-4)
    TITLE="Quiet hours with the link down"
    INSTR="Set quiet hours 2 min ahead with the glasses link down: glasses pause on their own; reconnect: phone shows 'Quiet hours'; the next night produces a new pause id (Diagnostics log)."
    MARKERS="G|paused locally|capture skipped: paused${NL}P|hello battery=" ;;
  P7-5)
    TITLE="Nudge at Max vs Silent"
    INSTR="Nudge at Max: HUD 'HEADS UP' toast for 8 s + Glance one-liner. At Silent: phone feed only."
    MARKERS="!P|nudge failed" ;;
  P7-6)
    TITLE="Reminder confirm race"
    INSTR="Ask 'remind me to call mom at 6': HUD Confirm; tick on glasses while tapping X on the phone within 1 s: exactly one outcome. The reminder fires at 18:00 (also after a phone reboot)."
    # ReminderExecutor.kt:57 logs "reminder armed; code=NONE" on success (code=<FailCode> when armed with a warning).
    MARKERS="P|confirm answered${NL}P|reminder armed; code=" ;;
  P7-7)
    TITLE="Shopping auto-add + undo"
    INSTR="Always-confirm off for Shopping: ask to add milk. HUD 'Added - double-tap undo'; double-tap: the item is gone from the Shopping list; capture is NOT paused."
    MARKERS="P|undo asked${NL}!G|paused locally" ;;
  P7-8)
    TITLE="Calendar permission revoked"
    INSTR="Revoke WRITE_CALENDAR in system settings (you do it), then a calendar action: 'Calendar permission needed'; no crash."
    MARKERS="!P|FATAL EXCEPTION" ;;
  P7-9)
    TITLE="Sources: Calendar + Location"
    INSTR="Enable Calendar + Location: an Ask about 'my next meeting' uses the event; new frames get a place label; nothing about either appears on the HUD."
    ;;
  P7-10)
    TITLE="Phone Capture now"
    INSTR="Phone 'Capture now': a photo appears in Today with source phone; the button is disabled while paused."
    # Phone captures skip FrameIngestor ("frame ingested" is glasses-only); RokidFrameInbox.ingestFile logs `append result=<bool> origin=phone|glasses` (an id-free marker).
    MARKERS="P|append result=true origin=phone" ;;
  P7-11)
    TITLE="Forget fact cited by an Ask"
    INSTR="Forget fact on a memory cited by an Ask: the Ask shows 'Removed - a source was forgotten'; HUD Today drops it on the next frame; 'Reprocess old photos' never brings it back."
    MARKERS="!P|forget failed" ;;
  P7-12)
    TITLE="Forget moment"
    INSTR="Forget moment: the photo and all its facts are gone; Delete all still works."
    MARKERS="!P|forget failed" ;;
  P7-13)
    TITLE="Fresh-install wizard"
    INSTR="Fresh install (you do it): the Permissions step runs the voice probe and shows the status; AI Test passes; Done lands on Home."
    ;;
  *) return 1 ;;
  esac
  return 0
}

# ---- dry run: print the steps, no adb call ----------------------------------------------------
if [ "$DRY" -eq 1 ]; then
  n=0
  for id in $STEP_IDS; do
    if [ -n "$ONLY" ] && [ "$ONLY" != "$id" ]; then continue; fi
    define_step "$id" || continue
    n=$((n + 1))
    echo "[$id] $TITLE"
    echo "    $INSTR"
    if [ -n "$MARKERS" ]; then echo "$MARKERS" | sed 's/^/    marker: /'; fi
    if [ "$SHOTMODE" = phone ]; then echo "$PHONE_SHOTS" | sed 's/|.*//; s#^#    screenshot: docs/screens/phone/#; s/$/.png/'; fi
    if [ "$SHOTMODE" = hud ]; then echo "$HUD_SHOTS" | sed 's/|.*//; s#^#    screenshot: docs/screens/hud/#; s/$/.png/'; fi
  done
  if [ "$n" -eq 0 ]; then echo "no such step: $ONLY" >&2; exit 2; fi
  echo "(dry run: $n step(s), no adb call made)"
  exit 0
fi

# ---- real run ---------------------------------------------------------------------------------
: "${PHONE_SERIAL:?set PHONE_SERIAL}"
: "${GLASSES_SERIAL:?set GLASSES_SERIAL}"

# adb is used only for: devices, logcat -c, logcat -d, exec-out screencap -p.
serial_of() { if [ "$1" = P ]; then echo "$PHONE_SERIAL"; else echo "$GLASSES_SERIAL"; fi; }
logcat_clear() { adb -s "$(serial_of "$1")" logcat -c >/dev/null 2>&1; }
logcat_has() { adb -s "$(serial_of "$1")" logcat -d -v brief 2>/dev/null | grep -Eq -- "$2"; }
shot() { # <P|G> <dest.png>; writes to a temp file first so a failed capture leaves nothing
  local tmp="$2.tmp"
  if adb -s "$(serial_of "$1")" exec-out screencap -p > "$tmp" 2>/dev/null && [ -s "$tmp" ]; then
    mv "$tmp" "$2"; return 0
  fi
  return 1
}

DEVS="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')"
for s in "$PHONE_SERIAL" "$GLASSES_SERIAL"; do
  if ! echo "$DEVS" | grep -qx -- "$s"; then echo "device $s is not listed by 'adb devices'" >&2; exit 1; fi
done
mkdir -p "$PHONE_SHOTS_DIR" "$HUD_SHOTS_DIR"
echo "# device checklist $(date '+%Y-%m-%d %H:%M:%S')" >> "$OUT"

record() { echo "$1 $2 $3" >> "$OUT"; echo "  => $1 $2 $3"; }

take_shots() { # <phone|hud>
  local list dir who line name label r
  if [ "$1" = phone ]; then list="$PHONE_SHOTS"; dir="$PHONE_SHOTS_DIR"; who=P; else list="$HUD_SHOTS"; dir="$HUD_SHOTS_DIR"; who=G; fi
  while IFS= read -r line <&3; do
    name="${line%%|*}"; label="${line#*|}"
    printf 'Show: %s  -> Enter captures %s.png (s = skip): ' "$label" "$name"
    read -r r || r=s
    if [ "$r" = s ]; then echo "  skipped $name"; continue; fi
    if shot "$who" "$dir/$name.png"; then echo "  saved docs/screens/$1/$name.png"; else echo "  screencap FAILED for $name"; fi
  done 3<<EOF
$list
EOF
}

for id in $STEP_IDS; do
  if [ -n "$ONLY" ] && [ "$ONLY" != "$id" ]; then continue; fi
  define_step "$id" || continue
  echo
  echo "=== [$id] $TITLE ==="
  echo "$INSTR"
  logcat_clear P; logcat_clear G
  printf 'Do it, then press Enter (s + Enter skips this step): '
  if ! read -r ans; then record "$id" SKIP "(no input)"; continue; fi
  if [ "$ans" = s ]; then record "$id" SKIP ""; continue; fi

  if [ -n "$SHOTMODE" ]; then take_shots "$SHOTMODE"; fi

  missing=""
  if [ -n "$MARKERS" ]; then
    while IFS= read -r m <&4; do
      neg=0
      case "$m" in !*) neg=1; m="${m#!}" ;; esac
      dev="${m%%|*}"; pat="${m#*|}"
      if logcat_has "$dev" "$pat"; then found=1; else found=0; fi
      if [ "$neg" -eq 1 ]; then
        if [ "$found" -eq 1 ]; then missing="$missing [unexpected $dev:$pat]"; fi
      else
        if [ "$found" -eq 0 ]; then missing="$missing [missing $dev:$pat]"; fi
      fi
    done 4<<EOF
$MARKERS
EOF
    if [ -z "$missing" ]; then echo "  logcat markers: all OK"; else echo "  logcat markers:$missing"; fi
  fi

  printf 'Result? p = pass, f = fail, s = skip: '
  read -r v || v=s
  note=""
  [ -n "$missing" ] && note="markers:$missing"
  case "$v" in
    p|P) record "$id" PASS "$note" ;;
    f|F) record "$id" FAIL "$note" ;;
    *)   record "$id" SKIP "$note" ;;
  esac
done
echo
echo "Result file: $OUT"
