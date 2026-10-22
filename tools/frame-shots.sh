#!/bin/sh
# Runs the game (seed 123, developer switches on) once per developer view and saves the frame
# 20 s in, to compare what's drawn before and after a change to the rendering. Each run takes
# about three minutes (the world is made each time), and the window opens while it runs.
# Usage (Git Bash):  tools/frame-shots.sh <out dir> [view ...]
#   views default to: street shop bridge river herd (see -Dxenoguesser.view in RoundSpawner)
#   CLASSES=<dir> runs other compiled classes (e.g. a copy of target/classes from before a change)
# Frames land in <out dir>/<view>/frame (a PNG), the game's log beside it. Frames from two runs
# of the same code differ a little (the clouds move with the clock), so compare them by eye.
cd "$(dirname "$0")/.." || exit 1
out=$1
[ -n "$out" ] || { echo "usage: tools/frame-shots.sh <out dir> [view ...]"; exit 2; }
shift
views=${*:-street shop bridge river herd}
[ -n "$CLASSES" ] || { mvn -q -o compile || mvn -q compile || exit 1; }
if [ ! -f target/classpath.txt ] || [ pom.xml -nt target/classpath.txt ]; then
  mvn -q -o dependency:build-classpath -Dmdep.outputFile=target/classpath.txt || exit 1
fi
CP="${CLASSES:-target/classes};$(cat target/classpath.txt)"
for v in $views; do
  mkdir -p "$out/$v"
  timeout 400 java -XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=70 \
    --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED \
    --add-exports java.desktop/sun.awt=ALL-UNNAMED \
    -Dxenoguesser.dev=true -Dxenoguesser.seed=123 -Dxenoguesser.skipmenu=true \
    -Dxenoguesser.view="$v" "-Dxenoguesser.hudtest=frameshot,quit" \
    -Dxenoguesser.frameshot="$out/$v/frame" -Dxenoguesser.frameshotdelay=20000 -Dxenoguesser.quitdelay=24000 \
    -classpath "$CP" XenoGuesser > "$out/$v/log.txt" 2>&1
  echo "$v: $( [ -f "$out/$v/frame" ] && echo saved || echo 'no frame (see log.txt)')"
done
