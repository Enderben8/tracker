@echo off
rem Launches Revision Tracker. Works from Explorer (double-click), Command Prompt or PowerShell.
rem The ".\" matters: this PC has NoDefaultCurrentDirectoryInExePath set, so a bare
rem "gradlew" is not found even inside the project folder.
cd /d "%~dp0"
call .\gradlew.bat :composeApp:run
if errorlevel 1 (
    echo.
    echo Something went wrong - see the messages above.
    pause
)
