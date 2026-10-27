@echo off
rem Serves the trailer at http://localhost:8123 (its voice lines, seeking and Export need a web
rem server, not a file opened straight from disk). Close the window to stop it.
cd /d "%~dp0..\.."
start "" http://localhost:8123/
python tools\trailer\serve.py 8123
