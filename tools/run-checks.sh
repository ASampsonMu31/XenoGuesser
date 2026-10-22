#!/bin/sh
# Compiles the game and runs its self-checks (see CLAUDE.md, "Checking a change"):
#   SphereToolkitCheck  the sphere maths, frames, cells and collision            (seconds)
#   PlanningCheck       world planning: nations, towns, roads, houses, vehicles  (~30 s;
#                       4 checks were already failing before the refactor: see CLAUDE.md)
#   RegressionCheck     fingerprints of everything seed 123's world is made of,  (~2 min, ~10 GB)
#                       against checks/regression-fingerprints.txt
# Usage (Git Bash, from anywhere):  tools/run-checks.sh [sphere|planning|regression|write]
#   no argument runs all three; "write" records new fingerprints (only after checking a
#   difference is one you meant to make).
# Exit code 1 if the sphere check or the regression check fails.
cd "$(dirname "$0")/.." || exit 1
mvn -q -o compile || mvn -q compile || exit 1
if [ ! -f target/classpath.txt ] || [ pom.xml -nt target/classpath.txt ]; then
  mvn -q -o dependency:build-classpath -Dmdep.outputFile=target/classpath.txt || \
    mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt || exit 1
fi
CP="target/classes;$(cat target/classpath.txt)"
JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED"
what=${1:-all}
status=0
if [ "$what" = all ] || [ "$what" = sphere ]; then
  echo "== SphereToolkitCheck"
  java -cp "$CP" SphereToolkitCheck || status=1
fi
if [ "$what" = all ] || [ "$what" = planning ]; then
  echo "== PlanningCheck (4 known failures)"
  java -Xmx8g -cp "$CP" PlanningCheck | grep -E "FAILED|checks,"
fi
if [ "$what" = all ] || [ "$what" = regression ]; then
  echo "== RegressionCheck"
  java -Xmx10g $JAVA_OPTS -cp "$CP" RegressionCheck > target/regression.log 2>&1 || status=1
  grep -E "DIFFERS|MISSING|fingerprints, |Exception" target/regression.log
fi
if [ "$what" = write ]; then
  java -Xmx10g $JAVA_OPTS -cp "$CP" RegressionCheck write | tail -1
fi
exit $status
