@echo off
rem Builds XenoGuesser into a Windows program: dist\XenoGuesser\XenoGuesser.exe, with its own
rem Java runtime, JOGL, PyTorch, the voice model, the glyph model and every asset inside, so it runs
rem on any Windows PC with nothing installed. Also zipped as dist\XenoGuesser.zip to share.
rem Needs (on this PC only): JDK 17 or newer (for jpackage) and Maven, both on the PATH.
setlocal
cd /d "%~dp0"

echo [1/4] Compiling and gathering libraries...
call mvn -q clean package -DskipTests
if errorlevel 1 goto failed

echo [2/4] Gathering the game's files...
set STAGE=target\package
copy /y target\xenoguesser.jar %STAGE%\ >nul
copy /y lib\jogamp-fat.jar %STAGE%\lib\ >nul
copy /y lib\steamworks4j-1.10.0.jar %STAGE%\lib\ >nul
copy /y steam_api64.dll %STAGE%\ >nul
copy /y steam_appid.txt %STAGE%\ >nul
xcopy /e /i /q /y assets %STAGE%\assets >nul
xcopy /e /i /q /y models %STAGE%\models >nul

echo [3/4] Packaging with its own Java runtime...
if exist dist rmdir /s /q dist
jpackage --type app-image --name XenoGuesser --app-version 1.0 ^
  --input %STAGE% --main-jar xenoguesser.jar --main-class XenoGuesser ^
  --icon assets\icons\xenoguesser.ico --dest dist ^
  --java-options "-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=70 -XX:ZUncommitDelay=30" ^
  --java-options "-Dxenoguesser.store=release" ^
  --java-options "--add-exports java.base/java.lang=ALL-UNNAMED" ^
  --java-options "--add-exports java.desktop/sun.java2d=ALL-UNNAMED" ^
  --java-options "--add-exports java.desktop/sun.awt=ALL-UNNAMED"
if errorlevel 1 goto failed
rem Steam reads steam_appid.txt from the working directory, which is the exe's folder when the exe
rem is double-clicked (the copy in app\ is the fallback SteamTransport copies from)
copy /y steam_appid.txt dist\XenoGuesser\ >nul

echo [4/4] Zipping it to share...
powershell -NoProfile -Command "Compress-Archive -Path 'dist\XenoGuesser' -DestinationPath 'dist\XenoGuesser.zip' -Force"
if errorlevel 1 goto failed

echo.
echo Done: dist\XenoGuesser\XenoGuesser.exe  (and dist\XenoGuesser.zip)
exit /b 0

:failed
echo.
echo Build failed.
exit /b 1
