@echo off
if not exist bin mkdir bin
del /q bin\*.class >nul 2>&1

:: Compile by looking inside the src folder
javac -cp "c:/jogl26/jogamp-fat.jar;src" -d bin src\%*