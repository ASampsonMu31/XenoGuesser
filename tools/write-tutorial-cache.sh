#!/bin/sh
# Writes the tutorial's cache (assets/tutorial_cache, see TutorialCache): runs the tutorial with
# everything made afresh (writing systems, textures, landmark and house photographs, every
# heatmap), saves it all once the world's ready, and quits. The game's window opens while it runs
# (the photographs are taken by the graphics card); a few minutes, longer on a PC short of memory.
# Usage (Git Bash, from anywhere):  tools/write-tutorial-cache.sh
# Exit code 1 if the cache wasn't written.
cd "$(dirname "$0")/.." || exit 1
mvn -q -o compile || mvn -q compile || exit 1
if [ ! -f target/classpath.txt ] || [ pom.xml -nt target/classpath.txt ]; then
  mvn -q -o dependency:build-classpath -Dmdep.outputFile=target/classpath.txt || \
    mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt || exit 1
fi
CP="target/classes;$(cat target/classpath.txt)"
timeout 1800 java -XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=70 \
  --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED \
  --add-exports java.desktop/sun.awt=ALL-UNNAMED \
  -Dxenoguesser.dev=true -Dxenoguesser.skipmenu=tutorial -Dxenoguesser.tutorialcache=write \
  -classpath "$CP" XenoGuesser > target/tutorial-cache.log 2>&1
grep -E "\[TUTORIAL\]|\[LOADING\]|Exception" target/tutorial-cache.log
grep -q "Cache written" target/tutorial-cache.log
