@echo off
if not exist bin mkdir bin
del /q bin\*.class >nul 2>&1

set DJL_REPO=%USERPROFILE%\.m2\repository\ai\djl
set SLF_REPO=%USERPROFILE%\.m2\repository\org\slf4j
set GSON_REPO=%USERPROFILE%\.m2\repository\com\google\code\gson

:: Expanded compilation string
set CLASSPATH=c:/jogl26/jogamp-fat.jar;%DJL_REPO%/api/0.26.0/api-0.26.0.jar;%DJL_REPO%/pytorch/pytorch-engine/0.26.0/pytorch-engine-0.26.0.jar;%SLF_REPO%/slf4j-api/1.7.36/slf4j-api-1.7.36.jar;%GSON_REPO%/gson/2.10.1/gson-2.10.1.jar;src

echo Compiling XenoGuesser project files...
javac -cp "%CLASSPATH%" -d bin src/GlyphGenerator.java src/XenoGuesser.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed.
    pause
    exit /b %ERRORLEVEL%
)

echo [SUCCESS] Compilation complete. Files outputted to /bin.