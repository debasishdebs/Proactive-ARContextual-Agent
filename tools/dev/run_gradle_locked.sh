#!/bin/bash
# usage: tools/dev/run_gradle_locked.sh <repo-or-worktree-root> <log> <gradle args...>
# ONE Gradle job at a time on the 8 GB Mac (mkdir lock; remove a stale lock only when no gradle process runs).
LOCK=${PCA_GRADLE_LOCK:-/tmp/pca-gradle.lock}
WT="$1"; LOGF="$2"; shift 2
until mkdir "$LOCK" 2>/dev/null; do sleep 15; done
trap 'rmdir "$LOCK" 2>/dev/null' EXIT
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Desktop/rokid/android-sdk}
cd "$WT/android-companion" || exit 9
./gradlew --no-daemon -Dorg.gradle.workers.max=2 --offline --continue "$@" > "$LOGF" 2>&1
echo "rc=$?" >> "$LOGF"
