@echo off
rem Builds and launches XenoGuesser with the developer switches on (see src\Dev.java): teleporting
rem by the map (hold P and click, or right-click), dev views and the rest. The packaged exe never has them.
echo Building and launching XenoGuesser (developer mode) via Maven...
mvn clean compile exec:exec -Dxenoguesser.dev=true
pause
