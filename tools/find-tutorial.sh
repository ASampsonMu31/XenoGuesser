#!/bin/sh
# Finds the tutorial's rounds on its planet (see TutorialFinder and .claude/skills/update-tutorial):
# builds the planet without GL (~2 min, ~10 GB) and writes assets/text/tutorial_rounds.txt.
# Usage (Git Bash, from anywhere):  tools/find-tutorial.sh [out file]
# Exit code 1 if some exercise found nowhere suitable.
cd "$(dirname "$0")/.." || exit 1
mvn -q -o compile || mvn -q compile || exit 1
if [ ! -f target/classpath.txt ] || [ pom.xml -nt target/classpath.txt ]; then
  mvn -q -o dependency:build-classpath -Dmdep.outputFile=target/classpath.txt || \
    mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt || exit 1
fi
CP="target/classes;$(cat target/classpath.txt)"
JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED"
java -Xmx10g $JAVA_OPTS -Dxenoguesser.dev=true -cp "$CP" TutorialFinder "$@" > target/tutorial-finder.log 2>&1
status=$?
grep -E "\[TUTORIAL\]|Exception" target/tutorial-finder.log
exit $status
