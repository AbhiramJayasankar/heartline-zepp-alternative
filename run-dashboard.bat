@echo off
rem Starts the GTR 2e dashboard at http://localhost:8765 and opens it in the browser.
rem Turn off your phone's Bluetooth first: the watch accepts only one connection.
cd /d "%~dp0"
start "" http://localhost:8765
.venv\Scripts\python.exe -m uvicorn server.app:app --port 8765
