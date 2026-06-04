@echo off
:: 1. Create the bin folder if it doesn't exist yet
if not exist bin mkdir bin

:: 2. Clean out any old binaries inside the bin folder
del /q bin\*.class >nul 2>&1

:: 3. Compile your code directly INTO the bin folder using the -d flag
javac -cp "c:/jogl26/jogamp-fat.jar;." -d bin %*