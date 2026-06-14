@echo off
set DJL_REPO=%USERPROFILE%\.m2\repository\ai\djl
set SLF_REPO=%USERPROFILE%\.m2\repository\org\slf4j
set GSON_REPO=%USERPROFILE%\.m2\repository\com\google\code\gson
set COMPRESS_REPO=%USERPROFILE%\.m2\repository\org\apache\commons

:: Runtime classpath tracking JOGL, Logging, GSON, and Apache Commons Compress
set RUN_CP=bin;c:/jogl26/jogamp-fat.jar;%DJL_REPO%/api/0.26.0/api-0.26.0.jar;%DJL_REPO%/pytorch/pytorch-engine/0.26.0/pytorch-engine-0.26.0.jar;%DJL_REPO%/pytorch/pytorch-model-zoo/0.26.0/pytorch-model-zoo-0.26.0.jar;%SLF_REPO%/slf4j-api/1.7.36/slf4j-api-1.7.36.jar;%SLF_REPO%/slf4j-simple/1.7.36/slf4j-simple-1.7.36.jar;%GSON_REPO%/gson/2.10.1/gson-2.10.1.jar;%COMPRESS_REPO%/commons-compress/1.26.0/commons-compress-1.26.0.jar

echo Booting XenoGuesser Game Loop Engine...

java --add-exports java.base/java.lang=ALL-UNNAMED ^
     --add-exports java.desktop/sun.java2d=ALL-UNNAMED ^
     --add-exports java.desktop/sun.awt=ALL-UNNAMED ^
     -cp "%RUN_CP%" XenoGuesser %*

pause