#!/bin/sh
# Films the trailer's shots (see src/TrailerDirector.java) from a shot list, as JPEG frames in
# <out dir>/<shot>/, then tools/trailer/encode.py makes the clips. The game's window opens while
# it films; leave it alone. Usage (Git Bash):
#   tools/trailer/film.sh <shot list> <out dir> [seed] [shot,shot...]
cd "$(dirname "$0")/../.." || exit 1
list=$1; out=$2; seed=${3:-123}; only=$4
[ -n "$out" ] || { echo "usage: tools/trailer/film.sh <shot list> <out dir> [seed] [shots]"; exit 2; }
mvn -q -o compile || mvn -q compile || exit 1
if [ ! -f target/classpath.txt ] || [ pom.xml -nt target/classpath.txt ]; then
  mvn -q -o dependency:build-classpath -Dmdep.outputFile=target/classpath.txt || exit 1
fi
mkdir -p "$out"
java -XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=70 \
  --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED \
  --add-exports java.desktop/sun.awt=ALL-UNNAMED \
  -Dxenoguesser.dev=true -Dxenoguesser.seed="$seed" -Dxenoguesser.skipmenu=true \
  -Dxenoguesser.quality=high -Dxenoguesser.shadows=on -Dxenoguesser.unlockall=true \
  -Dxenoguesser.hudtest=film -Dxenoguesser.trailer="$list" -Dxenoguesser.trailerout="$out" \
  ${only:+-Dxenoguesser.trailershots="$only"} \
  -classpath "target/classes;$(cat target/classpath.txt)" XenoGuesser > "$out/log.txt" 2>&1
grep "\[TRAILER\]" "$out/log.txt"
